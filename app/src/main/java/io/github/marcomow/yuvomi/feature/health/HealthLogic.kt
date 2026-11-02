package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Activity (public/utils/health-activity.js). */
object HealthActivity {
    val TYPES = listOf("running", "cycling", "swimming", "strength", "yoga", "walking", "other")
    val INTENSITIES = listOf("low", "moderate", "high")
    val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun typeLabel(type: String, s: Strings): String = if (type in TYPES) s.t("health.activity.type.$type") else type

    data class Day(val date: LocalDate, val weekday: Int, val durationMin: Double, val count: Int)

    data class Week(val days: List<Day>, val from: LocalDate, val to: LocalDate)

    /** `weekSummary`: the rolling last seven days while the anchor is in this week, else the calendar week. */
    fun week(rows: List<Activity>, anchor: LocalDate, today: LocalDate): Week {
        val start = if (HealthVitals.isRolling("week", anchor, today)) today.minusDays(6)
        else anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0L..6L).map { i ->
            val d = start.plusDays(i)
            val mine = rows.filter { it.performedAt.take(10) == d.toString() }
            Day(d, d.dayOfWeek.value - 1, mine.sumOf { it.durationMin ?: 0.0 }, mine.size)
        }
        return Week(days, start, start.plusDays(6))
    }

    fun inWeek(rows: List<Activity>, week: Week): List<Activity> =
        rows.filter { it.performedAt.take(10) >= week.from.toString() && it.performedAt.take(10) <= week.to.toString() }
            .sortedWith(compareByDescending<Activity> { it.performedAt }.thenByDescending { it.id })

    data class Totals(val count: Int, val durationMin: Double, val distanceKm: Double, val calories: Double)

    fun totals(rows: List<Activity>) = Totals(rows.size, rows.sumOf { it.durationMin ?: 0.0 }, rows.sumOf { it.distanceKm ?: 0.0 }, rows.sumOf { it.calories ?: 0.0 })

    fun statusText(rows: List<Activity>, date: (String) -> String, s: Strings): String {
        val latest = rows.maxByOrNull { it.performedAt } ?: return ""
        return "${typeLabel(latest.type, s)} · ${date(latest.performedAt.take(10))}"
    }
}

/** Lab values (public/utils/health-labs.js). */
object HealthLabs {
    fun flag(value: Double?, low: Double?, high: Double?): String? = when {
        value == null -> null
        low != null && value < low -> "low"
        high != null && value > high -> "high"
        low != null || high != null -> "normal"
        else -> null
    }

    fun abnormal(report: LabReport): Int = report.results.count { it.flag != null && it.flag != "normal" }

    fun names(reports: List<LabReport>): List<String> {
        val seen = LinkedHashMap<String, String>()
        reports.flatMap { it.results }.map { it.analyte.trim() }.filter { it.isNotEmpty() }.forEach { seen.putIfAbsent(it.lowercase(), it) }
        return seen.values.toList()
    }

    data class TrendPoint(val reportId: Int, val date: String, val value: Double, val unit: String?, val refLow: Double?, val refHigh: Double?)

    fun trend(reports: List<LabReport>, analyte: String): List<TrendPoint> {
        val name = analyte.trim().lowercase()
        if (name.isEmpty()) return emptyList()
        return reports.mapNotNull { rep ->
            val r = rep.results.firstOrNull { it.analyte.trim().lowercase() == name } ?: return@mapNotNull null
            val v = r.value ?: return@mapNotNull null
            TrendPoint(rep.id, rep.reportDate.take(10), v, r.unit, r.refLow, r.refHigh)
        }.sortedWith(compareBy<TrendPoint> { it.date }.thenBy { it.reportId })
    }

    /** `defaultTrendAnalyte`: an abnormal value of the latest report with a trend first. */
    fun defaultAnalyte(reports: List<LabReport>): String? {
        val names = names(reports)
        if (names.isEmpty()) return null
        val latest = reports.maxWithOrNull(compareBy<LabReport> { it.reportDate.take(10) }.thenBy { it.id })
        fun canonical(raw: String) = names.firstOrNull { it.lowercase() == raw.trim().lowercase() }
        val abnormal = latest?.results.orEmpty().filter { it.flag != null && it.flag != "normal" }.mapNotNull { canonical(it.analyte) }
        val hasTrend = { n: String -> trend(reports, n).size >= 2 }
        return abnormal.firstOrNull(hasTrend) ?: abnormal.firstOrNull() ?: names.firstOrNull(hasTrend) ?: names.first()
    }

    fun reference(r: LabResult, s: Strings): String = when {
        r.refLow != null && r.refHigh != null -> "${s.num(r.refLow, 2)} – ${s.num(r.refHigh, 2)}"
        r.refLow != null -> "≥ ${s.num(r.refLow, 2)}"
        r.refHigh != null -> "≤ ${s.num(r.refHigh, 2)}"
        else -> ""
    }

    fun statusText(reports: List<LabReport>, date: (String) -> String, s: Strings): String {
        val latest = reports.maxByOrNull { it.reportDate } ?: return ""
        val n = abnormal(latest)
        val d = date(latest.reportDate.take(10))
        return if (n > 0) "$d · ${s.t("health.labs.abnormalBadge", "count" to n)}" else d
    }
}

/** Preventive care (public/utils/health-prevention.js, `preventionDueRowMarkup`). */
object HealthPrevention {
    /** `DATE_STATUS_ALERT_DAYS`: "soon" is a week or less. */
    const val ALERT_DAYS = 7

    fun dueText(daysLeft: Int, s: Strings): String = when {
        daysLeft < 0 -> s.t("health.prevention.overdueDays", "count" to -daysLeft)
        daysLeft == 0 -> s.t("health.prevention.dueToday")
        else -> s.t("health.prevention.dueInDays", "count" to daysLeft)
    }

    /** Months as the form shows them: whole years as years (`intervalMonthsToInput`). */
    fun intervalToInput(months: Int?): Pair<String, String> = when {
        months == null -> "" to "months"
        months > 0 && months % 12 == 0 -> (months / 12).toString() to "years"
        else -> months.toString() to "months"
    }

    fun inputToMonths(value: String, unit: String): Int? {
        val n = value.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (unit == "years") (n * 12).roundToInt() else n.roundToInt()
    }

    fun label(record: PreventionRecord, types: List<PreventionType>): String =
        types.firstOrNull { it.id == record.typeId }?.name ?: record.name.orEmpty()

    /** Records grouped by type (or by name without one), newest group first. */
    fun groups(records: List<PreventionRecord>): List<List<PreventionRecord>> =
        records.groupBy { it.typeId?.toString() ?: "name:${it.name}" }.values
            .map { g -> g.sortedByDescending { it.givenOn } }
            .sortedByDescending { it.first().givenOn }

    fun statusText(due: List<PreventionDue>, s: Strings): String {
        val item = due.minByOrNull { it.daysLeft } ?: return ""
        return "${item.typeName} · ${dueText(item.daysLeft, s)}"
    }
}

/** Nutrition (public/utils/health-nutrition.js). */
object HealthNutrition {
    data class Nutrient(val key: String, val labelKey: String, val unitKey: String)

    val NUTRIENTS = listOf(
        Nutrient("energy_kcal", "health.nutrition.nutrient.energyKcal", "health.nutrition.unit.kcal"),
        Nutrient("fat_g", "health.nutrition.nutrient.fat", "health.nutrition.unit.g"),
        Nutrient("saturated_fat_g", "health.nutrition.nutrient.saturatedFat", "health.nutrition.unit.g"),
        Nutrient("carbs_g", "health.nutrition.nutrient.carbs", "health.nutrition.unit.g"),
        Nutrient("sugar_g", "health.nutrition.nutrient.sugar", "health.nutrition.unit.g"),
        Nutrient("protein_g", "health.nutrition.nutrient.protein", "health.nutrition.unit.g"),
        Nutrient("salt_g", "health.nutrition.nutrient.salt", "health.nutrition.unit.g"),
        Nutrient("fiber_g", "health.nutrition.nutrient.fiber", "health.nutrition.unit.g"),
    )
    val MEALS = listOf("breakfast", "lunch", "dinner", "snack")

    data class Progress(val hasTarget: Boolean, val ratio: Float, val over: Boolean)

    /** `nutrientProgress`: a 0 target is met by nothing and passed by anything. */
    fun progress(value: Double?, target: Double?): Progress {
        if (target == null) return Progress(false, 0f, false)
        val current = value ?: 0.0
        if (target == 0.0) return Progress(true, if (current > 0) 1f else 0f, current > 0)
        val r = current / target
        return Progress(true, r.coerceIn(0.0, 1.0).toFloat(), r > 1)
    }

    fun statusText(summary: NutritionSummary?, s: Strings): String {
        val kcal = summary?.totals?.get("energy_kcal") ?: 0.0
        if (kcal <= 0) return ""
        return "${s.t("health.nutrition.todayTitle")}: ${s.num(kcal, 0)} ${s.t("health.nutrition.unit.kcal")}"
    }
}

/** Fasting (public/utils/health-fasting.js). */
object HealthFasting {
    val PRESETS = listOf(12, 14, 15, 16, 18, 20, 23)

    /** `formatFastingDuration`: whole minutes as `5 h 20 min`, days past 24 hours as `+N days`. */
    fun duration(minutes: Double, s: Strings): String {
        val total = minutes.toLong().coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return when {
            h > 0 && m > 0 -> s.t("health.duration.hm", "hours" to h, "minutes" to m)
            h > 0 -> s.t("health.duration.h", "hours" to h)
            else -> s.t("health.duration.m", "minutes" to m)
        }
    }

    /** `HH:MM:SS` with unbounded hours (`formatFastingClock`). */
    fun clock(seconds: Long): String {
        val t = seconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
    }

    /** A valid goal: whole hours from 1 to 336 (`goalMinutes` in services/fasting.js). */
    fun goalFromHours(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..336 }?.let { it * 60 }
}

/** ApiProblem to upstream's sentences (public/utils/friendly-error.js), then the area's own fallback. */
object HealthErrors {
    private val REASONS = mapOf(
        "health_vital_not_found" to "health.errorVitalGone",
        "health_vital_out_of_range" to "health.errorVitalImpossible",
        "health_vital_bp_order" to "health.errorVitalBpOrder",
        "health_medication_not_found" to "health.errorMedicationGone",
        "health_schedule_not_found" to "health.errorScheduleGone",
        "health_dose_not_found" to "health.errorDoseGone",
        "health_lab_report_not_found" to "health.errorLabReportGone",
        "health_lab_result_not_found" to "health.errorLabResultGone",
        "health_activity_not_found" to "health.errorActivityGone",
        "health_prevention_type_not_found" to "health.errorPreventionTypeGone",
        "health_prevention_selected_type_not_found" to "health.errorSelectedPreventionTypeGone",
        "health_prevention_record_not_found" to "health.errorPreventionRecordGone",
        "health_nutrition_entry_not_found" to "health.errorNutritionEntryGone",
        "health_cycle_period_not_found" to "health.errorPeriodGone",
        "health_cycle_log_not_found" to "health.errorCycleDayGone",
        "FASTING_REVISION_CONFLICT" to "health.fasting.conflict",
        "FASTING_RANGE_INVALID" to "health.fasting.rangeError",
        "FASTING_FUTURE" to "health.fasting.rangeError",
        "FASTING_GOAL_INVALID" to "health.fasting.goalHint",
    )

    /** `isGone`: the row is gone, the screen reloads. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status == 404 && e.reason != null

    fun key(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason != null && e.reason in REASONS -> REASONS.getValue(e.reason)
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> when (e.reason) {
                "csrf_invalid" -> "common.errorFormExpired"
                "module_access_denied" -> "common.errorModuleNoAccess"
                else -> "common.errorNoPermission"
            }
            e.status == 429 -> "common.errorRateLimited"
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }
}
