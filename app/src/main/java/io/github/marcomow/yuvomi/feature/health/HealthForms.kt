package io.github.marcomow.yuvomi.feature.health

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The editors' forms and the bodies they send, as the web page's `collect*Body` functions build
 * them (docs/spec/health.md). Pure, so the rules are tested without a screen.
 */
object HealthForms {
    /** An empty field is null; a field that is not a number is NaN, which the callers refuse. */
    fun number(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return t.replace(',', '.').toDoubleOrNull() ?: Double.NaN
    }

    fun plain(value: Double?): String = value?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

    private fun JsonObjectBuilder.putOrNull(key: String, value: Double?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    private fun JsonObjectBuilder.putOrNull(key: String, value: String?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    private fun JsonObjectBuilder.putOrNull(key: String, value: Int?) = put(key, value?.let(::JsonPrimitive) ?: JsonNull)

    /** `ownerField`: only when recording for someone else. */
    fun JsonObjectBuilder.owner(personId: Int?, meId: Int?) {
        if (personId != null && personId != meId) put("user_id", personId)
    }

    sealed interface Built {
        data class Ok(val body: JsonObject) : Built
        data class Invalid(val key: String) : Built
    }

    // ── Vitals (`collectVitalBody`, `vitalPatchBody`) ──

    data class VitalForm(
        val type: String = "bp",
        val value: String = "",
        val systolic: String = "",
        val diastolic: String = "",
        val pulse: String = "",
        val hours: String = "",
        val minutes: String = "",
        val mood: Int? = null,
        val unit: String = "",
        val date: LocalDate,
        val time: String,
        val note: String = "",
        val visibility: String = "private",
    ) {
        val measuredAt: String get() = "${date}T$time"

        /** The numbers as they stand, for the plausibility line while typing. */
        fun values(): List<Double?> {
            fun n(s: String) = number(s)?.takeIf { it.isFinite() }
            return if (HealthVitals.metric(type)?.format == "pair") listOf(n(systolic), n(diastolic), n(pulse)) else listOf(n(value), null, null)
        }

        fun unitSent(): String? = if (HealthVitals.metric(type)?.format == "pair") "mmHg" else unit.ifBlank { null }
    }

    fun vitalForm(row: Vital?, type: String, date: LocalDate, time: String, visibility: String): VitalForm {
        if (row == null) {
            val metric = HealthVitals.metric(type)
            return VitalForm(type = type, unit = metric?.units?.firstOrNull().orEmpty(), date = date, time = time, visibility = visibility)
        }
        val split = HealthVitals.split(row.value1)
        return VitalForm(
            type = row.type,
            value = plain(row.value1),
            systolic = plain(row.value1),
            diastolic = plain(row.value2),
            pulse = plain(row.value3),
            hours = split?.first?.toString().orEmpty(),
            minutes = split?.second?.toString().orEmpty(),
            mood = row.value1?.toInt(),
            unit = row.unit ?: HealthVitals.metric(row.type)?.units?.firstOrNull().orEmpty(),
            date = HealthTimes.date(row.measuredAt) ?: date,
            time = row.measuredAt.drop(11).take(5).ifEmpty { time },
            note = row.note.orEmpty(),
            visibility = row.visibility,
        )
    }

    /** The body of a new measurement, or why it cannot be sent (`health.vitals.invalidValue`, a hard limit). */
    fun vitalBody(f: VitalForm, editing: Vital?, personId: Int?, meId: Int?): Built {
        val metric = HealthVitals.metric(f.type)
        val invalid = Built.Invalid("health.vitals.invalidValue")
        var v1: Double? = null
        var v2: Double? = null
        var v3: Double? = null
        var unit: String? = null
        when (metric?.format) {
            "duration" -> {
                val h = number(f.hours)
                val m = number(f.minutes)
                if (h?.isNaN() == true || m?.isNaN() == true) return invalid
                val total = HealthVitals.toHours((h ?: 0.0).toInt(), (m ?: 0.0).toInt())
                if (total <= 0 || total > 24) return invalid
                v1 = total
                unit = "h"
            }
            "scale" -> v1 = f.mood?.takeIf { it in 1..5 }?.toDouble() ?: return invalid
            "pair" -> {
                v1 = number(f.systolic)?.takeIf { it.isFinite() } ?: return invalid
                v2 = number(f.diastolic)?.takeIf { it.isFinite() } ?: return invalid
                val p = number(f.pulse)
                if (p?.isNaN() == true) return invalid
                v3 = p
                unit = "mmHg"
            }
            else -> {
                v1 = number(f.value)?.takeIf { it.isFinite() } ?: return invalid
                unit = f.unit.ifBlank { null }
            }
        }
        val unitEditable = metric?.format == null && (metric?.units?.size ?: 0) > 1
        val unitChanged = editing != null && unitEditable && (unit ?: "") != (editing.unit ?: "")
        // `vitalMeasureChanged` (2.84.0, #2375): when editing, the hard limits count only if the
        // measurement changes, as on the server; a legacy row outside them still takes a note.
        val judged = editing == null || unitChanged || v1 != editing.value1 || v2 != editing.value2 || v3 != editing.value3
        val issues = if (judged) HealthVitals.plausibility(f.type, unit, listOf(v1, v2, v3)) else emptyMap()
        if ("bpOrder" in issues.values) return Built.Invalid("health.errorVitalBpOrder")
        if ("impossible" in issues.values) return Built.Invalid("health.errorVitalImpossible")
        val note = f.note.trim().ifEmpty { null }
        return Built.Ok(buildJsonObject {
            put("type", f.type)
            put("measured_at", f.measuredAt)
            put("visibility", f.visibility)
            put("value_num", v1)
            if (editing != null) {
                // `vitalPatchBody`: emptied channels and note are cleared, the unit only sent when it changed.
                putOrNull("value_num2", v2)
                putOrNull("value_num3", v3)
                putOrNull("note", note)
                if (unitChanged) putOrNull("unit", unit)
            } else {
                if (v2 != null) put("value_num2", v2)
                if (v3 != null) put("value_num3", v3)
                if (unit != null) put("unit", unit)
                if (note != null) put("note", note)
                owner(personId, meId)
            }
        })
    }

    // ── Medications (`collectMedBody`, the schedule editor) ──

    data class SlotForm(val time: String = "08:00", val dose: String = "1", val days: Set<Int> = emptySet())

    data class MedForm(
        val name: String = "",
        val dosage: String = "",
        val form: String = "",
        val stock: String = "",
        val stockUnit: String = "",
        val refill: String = "",
        val note: String = "",
        val visibility: String = "private",
        val active: Boolean = true,
        val prn: Boolean = false,
        val interval: String = "",
        val prnDose: String = "",
        /** Time slots of a NEW medication, sent with it in one request (#2054). */
        val slots: List<SlotForm> = emptyList(),
    )

    fun medForm(med: Medication?, visibility: String): MedForm = if (med == null) MedForm(visibility = visibility) else MedForm(
        name = med.name, dosage = med.dosageText.orEmpty(), form = med.form.orEmpty(), stock = plain(med.stockQty),
        stockUnit = med.stockUnit.orEmpty(), refill = plain(med.refillThreshold), note = med.note.orEmpty(),
        visibility = med.visibility, active = med.active, prn = med.prn, interval = plain(med.minIntervalHours), prnDose = plain(med.prnDoseQty),
    )

    fun slotBody(s: SlotForm): JsonObject? {
        if (!Regex("^\\d{2}:\\d{2}$").matches(s.time)) return null
        val dose = number(s.dose)
        if (dose?.isNaN() == true) return null
        return buildJsonObject {
            put("time_of_day", s.time)
            putOrNull("dose_qty", dose)
            putOrNull("days_mask", HealthMeds.indicesToMask(s.days))
        }
    }

    fun medBody(f: MedForm, creating: Boolean, personId: Int?, meId: Int?): Built {
        val name = f.name.trim()
        if (name.isEmpty()) return Built.Invalid("health.meds.nameRequired")
        val nums = listOf(f.stock, f.refill, f.interval, f.prnDose).map(::number)
        if (nums.any { it?.isNaN() == true }) return Built.Invalid("health.meds.saveError")
        val interval = if (f.prn) nums[2] else null
        if (interval != null && (interval <= 0 || interval > 24 * 28)) return Built.Invalid("health.meds.saveError")
        val slots = if (creating) f.slots.map { slotBody(it) ?: return Built.Invalid("health.meds.schedule.timeRequired") } else emptyList()
        fun str(v: String) = v.trim().ifEmpty { null }
        return Built.Ok(buildJsonObject {
            put("name", name)
            str(f.dosage)?.let { put("dosage_text", it) }
            str(f.form)?.let { put("form", it) }
            putOrNull("stock_qty", nums[0])
            str(f.stockUnit)?.let { put("stock_unit", it) }
            putOrNull("refill_threshold", nums[1])
            str(f.note)?.let { put("note", it) }
            put("visibility", f.visibility)
            put("active", if (f.active) 1 else 0)
            put("prn", if (f.prn) 1 else 0)
            putOrNull("min_interval_hours", interval)
            putOrNull("prn_dose_qty", if (f.prn) nums[3] else null)
            if (creating) {
                put("schedules", JsonArray(slots))
                owner(personId, meId)
            }
        })
    }

    /** A planned dose taken or skipped (`sendScheduledDose`), with the stock deduction in the same request. */
    fun doseBody(dose: HealthMeds.Dose, take: Boolean, nowStamp: String): JsonObject = buildJsonObject {
        put("status", if (take) "taken" else "skipped")
        put("scheduled_at", dose.scheduledAt)
        put("schedule_id", dose.scheduleId)
        dose.doseQty?.let { put("dose_qty", it) }
        if (take) {
            put("taken_at", nowStamp)
            dose.doseQty?.let { put("stock_deduct", it) }
        }
    }

    /** An as-needed dose (`handlePrnDose`). */
    fun prnBody(med: Medication, nowStamp: String): JsonObject = buildJsonObject {
        put("status", "taken")
        put("taken_at", nowStamp)
        med.prnDoseQty?.let {
            put("dose_qty", it)
            put("stock_deduct", it)
        }
    }

    /** Correcting an entry of the intake log (#701): status, and the time when taken. */
    fun logBody(status: String, date: LocalDate, time: String): JsonObject = buildJsonObject {
        put("status", status)
        if (status == "taken") put("taken_at", "${date}T${time.takeIf { it.length == 5 } ?: "00:00"}:00")
    }

    // ── Cycle ──

    fun periodBody(start: LocalDate, end: LocalDate?, note: String, visibility: String): Built {
        if (end != null && end < start) return Built.Invalid("health.cycle.endBeforeStart")
        return Built.Ok(buildJsonObject {
            put("start_date", start.toString())
            putOrNull("end_date", end?.toString())
            putOrNull("note", note.trim().ifEmpty { null })
            put("visibility", visibility)
        })
    }

    data class DayForm(
        val date: LocalDate,
        val flow: String? = null,
        val symptoms: Map<String, Int?> = emptyMap(),
        val feelings: List<String> = emptyList(),
        val temp: String = "",
        val tempUnit: String = "c",
        val mucus: String? = null,
        val lh: String? = null,
        val pregnancy: String? = null,
        val intimacy: String? = null,
        val note: String = "",
        val visibility: String = "private",
    )

    fun dayForm(log: CycleDayLog?, date: LocalDate, visibility: String): DayForm = if (log == null) DayForm(date, visibility = visibility) else DayForm(
        date = date, flow = log.flow, symptoms = log.symptoms.associate { it.key to it.intensity }, feelings = log.feelings,
        temp = plain(log.basalTemp), tempUnit = log.basalTempUnit ?: "c", mucus = log.cervixMucus, lh = log.lhTest,
        pregnancy = log.pregnancyTest, intimacy = log.intimacy, note = log.note.orEmpty(), visibility = log.visibility,
    )

    /** The day sheet's body; the server upserts by `log_date` and replaces symptoms and feelings. */
    fun dayBody(f: DayForm): Built {
        val temp = number(f.temp)
        if (temp?.isNaN() == true) return Built.Invalid("health.vitals.invalidValue")
        if (temp != null) {
            val range = if (f.tempUnit == "f") 93.0..108.0 else 34.0..42.0
            if (temp !in range) return Built.Invalid("health.vitals.invalidValue")
        }
        return Built.Ok(buildJsonObject {
            put("log_date", f.date.toString())
            putOrNull("flow", f.flow)
            put("symptoms", buildJsonArray {
                f.symptoms.forEach { (k, i) -> add(buildJsonObject { put("key", k); putOrNull("intensity", i) }) }
            })
            put("feelings", buildJsonArray { f.feelings.forEach { add(JsonPrimitive(it)) } })
            putOrNull("basal_temp", temp)
            putOrNull("basal_temp_unit", if (temp != null) f.tempUnit else null)
            putOrNull("cervix_mucus", f.mucus)
            putOrNull("lh_test", f.lh)
            putOrNull("pregnancy_test", f.pregnancy)
            putOrNull("intimacy", f.intimacy)
            putOrNull("note", f.note.trim().ifEmpty { null })
            put("visibility", f.visibility)
        })
    }

    /** `PUT /cycle/settings` replaces every field: the unchanged ones go back as they were. */
    fun cycleSettingsBody(s: CycleSettings): JsonObject = buildJsonObject {
        putOrNull("cycle_length_avg", s.cycleLengthAvg)
        putOrNull("period_length_avg", s.periodLengthAvg)
        putOrNull("luteal_length", s.lutealLength)
        put("track_fertility", s.trackFertility)
        put("pregnancy_mode", s.pregnancyMode)
        putOrNull("pregnancy_due_date", s.pregnancyDueDate)
        put("default_visibility", s.defaultVisibility)
        putOrNull("remind_period_days_before", s.remindPeriodDaysBefore)
        put("remind_log_daily", s.remindLogDaily)
        putOrNull("contraception", s.contraception)
        put("perimenopause_mode", s.perimenopauseMode)
        put("show_pms", s.showPms)
        putOrNull("notify_partner_user_id", s.notifyPartnerUserId)
        putOrNull("notify_partner_days_before", s.notifyPartnerDaysBefore)
    }

    // ── Prevention (`collectPreventionBody`) ──

    data class PreventionForm(
        val typeId: Int? = null,
        val name: String = "",
        val givenOn: LocalDate,
        val dose: String = "",
        val provider: String = "",
        val batch: String = "",
        val note: String = "",
        val interval: String = "",
        val intervalUnit: String = "months",
        val nextDue: LocalDate? = null,
        val offset: String = "",
        val visibility: String = "private",
    )

    fun preventionForm(r: PreventionRecord?, today: LocalDate, visibility: String): PreventionForm {
        if (r == null) return PreventionForm(givenOn = today, visibility = visibility)
        val (iv, unit) = HealthPrevention.intervalToInput(r.intervalMonths)
        return PreventionForm(
            typeId = r.typeId, name = r.name.orEmpty(), givenOn = HealthTimes.date(r.givenOn) ?: today, dose = plain(r.doseNumber),
            provider = r.provider.orEmpty(), batch = r.batch.orEmpty(), note = r.note.orEmpty(), interval = iv, intervalUnit = unit,
            nextDue = HealthTimes.date(r.nextDueOn), offset = r.reminderOffsetDays?.toString().orEmpty(), visibility = r.visibility,
        )
    }

    fun preventionBody(f: PreventionForm, creating: Boolean, personId: Int?, meId: Int?): Built {
        val invalid = Built.Invalid("health.prevention.invalid")
        if (f.typeId == null && f.name.isBlank()) return invalid
        val dose = number(f.dose)
        val offset = number(f.offset)
        val interval = if (f.interval.isBlank()) null else HealthPrevention.inputToMonths(f.interval, f.intervalUnit) ?: return invalid
        if (dose?.isNaN() == true || offset?.isNaN() == true) return invalid
        if (interval != null && interval !in 1..600) return invalid
        if (offset != null && (offset < 0 || offset > 365 || offset % 1 != 0.0)) return invalid
        return Built.Ok(buildJsonObject {
            putOrNull("type_id", f.typeId)
            put("given_on", f.givenOn.toString())
            put("visibility", f.visibility)
            if (f.typeId == null) put("name", f.name.trim())
            putOrNull("dose_number", dose)
            putOrNull("interval_months", interval)
            putOrNull("reminder_offset_days", offset?.toInt())
            putOrNull("next_due_on", f.nextDue?.toString())
            putOrNull("provider", f.provider.trim().ifEmpty { null })
            putOrNull("batch", f.batch.trim().ifEmpty { null })
            putOrNull("note", f.note.trim().ifEmpty { null })
            if (creating) owner(personId, meId)
        })
    }

    // ── Labs (`collectLabHead`, the analyte form) ──

    fun labBody(date: LocalDate, labName: String, note: String, visibility: String, creating: Boolean, personId: Int?, meId: Int?): JsonObject =
        buildJsonObject {
            put("report_date", date.toString())
            putOrNull("lab_name", labName.trim().ifEmpty { null })
            putOrNull("note", note.trim().ifEmpty { null })
            put("visibility", visibility)
            if (creating) owner(personId, meId)
        }

    data class ResultForm(val analyte: String = "", val value: String = "", val unit: String = "", val low: String = "", val high: String = "")

    fun resultForm(r: LabResult?): ResultForm = if (r == null) ResultForm() else
        ResultForm(r.analyte, plain(r.value), r.unit.orEmpty(), plain(r.refLow), plain(r.refHigh))

    fun resultBody(f: ResultForm): Built {
        if (f.analyte.isBlank()) return Built.Invalid("health.labs.results.analyteRequired")
        val v = number(f.value)?.takeIf { it.isFinite() } ?: return Built.Invalid("health.labs.results.valueRequired")
        val lo = number(f.low)
        val hi = number(f.high)
        if (lo?.isNaN() == true || hi?.isNaN() == true) return Built.Invalid("health.labs.results.valueRequired")
        return Built.Ok(buildJsonObject {
            put("analyte", f.analyte.trim())
            put("value_num", v)
            putOrNull("unit", f.unit.trim().ifEmpty { null })
            putOrNull("ref_low", lo)
            putOrNull("ref_high", hi)
            putOrNull("flag", HealthLabs.flag(v, lo, hi))
        })
    }

    // ── Activity (`collectActivityBody`) ──

    data class ActivityForm(
        val type: String = "",
        val custom: String = "",
        val date: LocalDate,
        val time: String,
        val duration: String = "",
        val distance: String = "",
        val calories: String = "",
        val intensity: String = "",
        val note: String = "",
        val visibility: String = "private",
    )

    fun activityForm(a: Activity?, date: LocalDate, time: String, visibility: String): ActivityForm {
        if (a == null) return ActivityForm(date = date, time = time, visibility = visibility)
        val preset = a.type in HealthActivity.TYPES
        return ActivityForm(
            type = if (preset) a.type else CUSTOM, custom = if (preset) "" else a.type,
            date = HealthTimes.date(a.performedAt) ?: date, time = a.performedAt.drop(11).take(5).ifEmpty { time },
            duration = plain(a.durationMin), distance = plain(a.distanceKm), calories = plain(a.calories),
            intensity = a.intensity.orEmpty(), note = a.note.orEmpty(), visibility = a.visibility,
        )
    }

    const val CUSTOM = "__custom__"

    fun activityBody(f: ActivityForm, editing: Boolean, personId: Int?, meId: Int?): Built {
        val invalid = Built.Invalid("health.activity.invalid")
        val type = (if (f.type == CUSTOM) f.custom.trim() else f.type).ifEmpty { return invalid }
        val nums = listOf(f.duration, f.distance, f.calories).map(::number)
        if (nums.any { it?.isNaN() == true }) return invalid
        return Built.Ok(buildJsonObject {
            put("type", type)
            put("performed_at", "${f.date}T${f.time}")
            put("visibility", f.visibility)
            if (editing) {
                putOrNull("duration_min", nums[0])
                putOrNull("distance_km", nums[1])
                putOrNull("calories", nums[2])
                putOrNull("intensity", f.intensity.ifEmpty { null })
                putOrNull("note", f.note.trim().ifEmpty { null })
            } else {
                nums[0]?.let { put("duration_min", it) }
                nums[1]?.let { put("distance_km", it) }
                nums[2]?.let { put("calories", it) }
                f.intensity.ifEmpty { null }?.let { put("intensity", it) }
                f.note.trim().ifEmpty { null }?.let { put("note", it) }
                owner(personId, meId)
            }
        })
    }

    // ── Nutrition (`collectNutritionBody`, `collectNutrients`) ──

    data class NutritionForm(
        val title: String = "",
        val date: LocalDate,
        val time: String,
        val meal: String? = null,
        val nutrients: Map<String, String> = emptyMap(),
        val note: String = "",
        val visibility: String = "private",
    )

    fun nutritionForm(e: NutritionEntry?, date: LocalDate, time: String, visibility: String): NutritionForm {
        if (e == null) return NutritionForm(date = date, time = time, visibility = visibility)
        return NutritionForm(
            title = e.title, date = HealthTimes.date(e.consumedAt) ?: date, time = e.consumedAt.drop(11).take(5).ifEmpty { time },
            meal = e.mealType, nutrients = HealthNutrition.NUTRIENTS.associate { it.key to plain(e.nutrient(it.key)) },
            note = e.note.orEmpty(), visibility = e.visibility,
        )
    }

    /** All eight values, empty as null; a negative or unreadable one refuses the form. */
    fun nutrients(values: Map<String, String>): Map<String, Double?>? {
        val out = mutableMapOf<String, Double?>()
        for (n in HealthNutrition.NUTRIENTS) {
            val v = number(values[n.key].orEmpty())
            if (v != null && (v.isNaN() || v < 0)) return null
            out[n.key] = v
        }
        return out
    }

    fun nutritionBody(f: NutritionForm, creating: Boolean, personId: Int?, meId: Int?): Built {
        val invalid = Built.Invalid("health.nutrition.invalid")
        if (f.title.isBlank()) return invalid
        val values = nutrients(f.nutrients) ?: return invalid
        return Built.Ok(buildJsonObject {
            put("title", f.title.trim())
            put("consumed_at", "${f.date}T${f.time}")
            putOrNull("meal_type", f.meal)
            putOrNull("note", f.note.trim().ifEmpty { null })
            put("visibility", f.visibility)
            values.forEach { (k, v) -> putOrNull(k, v) }
            if (creating) owner(personId, meId)
        })
    }

    fun targetBody(values: Map<String, String>, personId: Int?, meId: Int?): Built {
        val parsed = nutrients(values) ?: return Built.Invalid("health.nutrition.invalid")
        return Built.Ok(buildJsonObject {
            parsed.forEach { (k, v) -> putOrNull(k, v) }
            owner(personId, meId)
        })
    }
}

/** Wall-clock stamps of the household (`YYYY-MM-DDTHH:MM`). */
object HealthTimes {
    fun date(value: String?): LocalDate? = value?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
}
