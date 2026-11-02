package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/** docs/spec/health.md, the pure ports (HEALTH-A1 to A12). */
class HealthLogicTest {
    private val strings = Strings("en", mapOf(
        "health.duration.hm" to "{{hours}} h {{minutes}} min",
        "health.duration.h" to "{{hours}} h",
        "health.duration.m" to "{{minutes}} min",
        "health.prevention.overdueDays" to "Overdue by {{count}} days",
        "health.prevention.dueInDays" to "Due in {{count}} days",
        "health.prevention.dueToday" to "Due today",
        "health.vitals.metric.weight" to "Weight",
        "health.vitals.metric.bp" to "Blood pressure",
        "health.overview.brief.changes" to "Since the last measurement: {{list}}.",
        "health.overview.brief.item" to "{{metric}} {{value}}",
    ), emptyMap()) { if (it == 1.0) "one" else "other" }

    private val today = LocalDate.of(2026, 11, 4) // a Wednesday

    private fun vital(id: Int, type: String, at: String, v1: Double, v2: Double? = null, unit: String? = null) =
        Vital(id, 1, type, v1, v2, null, unit, at)

    // ── Vitals ──

    /** HEALTH-A1: the series converts to the most frequent unit and buckets by day; the latest two give the delta. */
    @Test fun `HEALTH-A1 vital series converts units and computes the delta`() {
        val rows = listOf(
            vital(1, "weight", "2026-11-01T08:00", 80.0, unit = "kg"),
            vital(2, "weight", "2026-11-03T08:00", 79.5, unit = "kg"),
            vital(3, "weight", "2026-11-04T07:00", 176.37, unit = "lb"),
        )
        val s = HealthVitals.series(rows, "weight", "week", today, today)
        assertEquals("kg", s.unit)
        assertEquals(7, s.points.size)
        assertEquals(today.minusDays(6), s.from)
        assertEquals(80.0, s.latest!!.value1!!, 0.01)
        assertEquals(0.5, s.deltas[0]!!, 0.01)
        assertEquals(3, s.readings.size)
    }

    /** HEALTH-A1: the current week and month roll, earlier ones are calendar periods, and stepping joins them. */
    @Test fun `HEALTH-A1 period stepping walks rolling and calendar windows`() {
        assertTrue(HealthVitals.isRolling("month", today, today))
        val back = HealthVitals.step("month", today, -1, today)
        assertEquals(LocalDate.of(2026, 10, 1), back)
        assertEquals(LocalDate.of(2026, 10, 31), HealthVitals.buckets("month", back, today).to)
        // Forward from the rolling window: the rest of the current calendar month.
        assertEquals(LocalDate.of(2026, 11, 30), HealthVitals.step("month", today, 1, today))
        assertEquals(12, HealthVitals.buckets("year", today, today).keys.size)
        assertEquals("hour", HealthVitals.buckets("day", today, today).gran)
    }

    /** HEALTH-A2: hard limits stop the save, soft ranges only warn, systolic must exceed diastolic. */
    @Test fun `HEALTH-A2 plausibility follows the server's limits`() {
        assertEquals("bpOrder", HealthVitals.plausibility("bp", "mmHg", listOf(80.0, 120.0, null))[1])
        assertEquals("impossible", HealthVitals.plausibility("spo2", "%", listOf(101.0, null, null))[0])
        assertEquals("high", HealthVitals.plausibility("glucose", "mg/dL", listOf(400.0, null, null))[0])
        assertTrue(HealthVitals.plausibility("weight", "kg", listOf(72.0, null, null)).isEmpty())
        assertEquals("high", HealthVitals.plausibility("bp", "mmHg", listOf(120.0, 80.0, 190.0))[2])
    }

    @Test fun `HEALTH-A2 durations and mood read as the web app shows them`() {
        assertEquals(7 to 30, HealthVitals.split(7.5))
        assertEquals("7 h 30 min", HealthVitals.duration(7.5, strings))
        assertEquals("health.vitals.mood.good", HealthVitals.moodKey(3.6))
        assertEquals("Since the last measurement: Weight -0.5 kg.", HealthVitals.brief(listOf(
            vital(1, "weight", "2026-11-01T08:00", 80.0, unit = "kg"),
            vital(2, "weight", "2026-11-03T08:00", 79.5, unit = "kg"),
        ), today, strings))
    }

    /** HEALTH-A3: the create body owns the row for a person in care; the patch clears emptied fields. */
    @Test fun `HEALTH-A3 vital bodies follow collectVitalBody and vitalPatchBody`() {
        val f = HealthForms.VitalForm(type = "bp", systolic = "120", diastolic = "80", date = today, time = "08:15", visibility = "family")
        val create = (HealthForms.vitalBody(f, null, personId = 3, meId = 1) as HealthForms.Built.Ok).body
        assertEquals("2026-11-04T08:15", create["measured_at"]!!.jsonPrimitive.content)
        assertEquals("mmHg", create["unit"]!!.jsonPrimitive.content)
        assertEquals(3, create["user_id"]!!.jsonPrimitive.content.toInt())
        assertFalse("value_num3" in create)
        val own = (HealthForms.vitalBody(f, null, personId = 1, meId = 1) as HealthForms.Built.Ok).body
        assertFalse("user_id" in own)
        val row = vital(9, "bp", "2026-11-04T08:15", 120.0, 80.0, "mmHg").copy(value3 = 70.0, note = "x")
        val patch = (HealthForms.vitalBody(f.copy(note = ""), row, 1, 1) as HealthForms.Built.Ok).body
        assertEquals(JsonNull, patch["value_num3"])
        assertEquals(JsonNull, patch["note"])
        assertFalse("unit" in patch)
        assertEquals("health.errorVitalBpOrder", (HealthForms.vitalBody(f.copy(systolic = "70"), null, 1, 1) as HealthForms.Built.Invalid).key)
        assertEquals("health.vitals.invalidValue", (HealthForms.vitalBody(f.copy(diastolic = ""), null, 1, 1) as HealthForms.Built.Invalid).key)
        val sleep = (HealthForms.vitalBody(HealthForms.VitalForm(type = "sleep", hours = "7", minutes = "30", date = today, time = "07:00"), null, 1, 1) as HealthForms.Built.Ok).body
        assertEquals(7.5, sleep["value_num"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    // ── Medications ──

    private val schedule = MedSchedule(id = 11, medicationId = 5, timeOfDay = "08:00", daysMask = null, doseQty = 1.0)
    private fun med(logs: List<MedLog> = emptyList(), active: Boolean = true) =
        Medication(5, 1, "Ibuprofen", active = active, schedules = listOf(schedule, schedule.copy(id = 12, timeOfDay = "20:00", daysMask = 1 shl 2)), logs = logs)

    /** HEALTH-A4: due doses follow the weekday mask, start and end, and inactive medications drop out. */
    @Test fun `HEALTH-A4 due doses and the weekday mask`() {
        val due = HealthMeds.dueDoses(HealthMeds.currentSchedules(listOf(med())), today, today)
        assertEquals(listOf("2026-11-04T08:00", "2026-11-04T20:00"), due.map { it.scheduledAt })
        assertEquals(1, HealthMeds.dueDoses(HealthMeds.currentSchedules(listOf(med())), today.plusDays(1), today.plusDays(1)).size)
        assertTrue(HealthMeds.currentSchedules(listOf(med(active = false))).isEmpty())
        assertNull(HealthMeds.indicesToMask(setOf(0, 1, 2, 3, 4, 5, 6)))
        assertEquals(0b101, HealthMeds.indicesToMask(setOf(0, 2)))
        assertEquals(setOf(0, 2), HealthMeds.maskToIndices(0b101))
    }

    /** HEALTH-A5: adherence counts planned doses only; the streak needs every planned dose of a day. */
    @Test fun `HEALTH-A5 adherence and streak`() {
        val logs = listOf(
            MedLog(1, 5, 11, "2026-11-04T08:00", "taken"),
            MedLog(2, 5, 12, "2026-11-04T20:00", "taken"),
            MedLog(3, 5, 11, "2026-11-03T08:00", "taken"),
            MedLog(4, 5, 11, "2026-11-02T08:00", "skipped"),
            MedLog(5, 5, null, null, "taken", "2026-11-04T10:00"),
        )
        val a = HealthMeds.adherenceFor(listOf(med(logs)), today)
        assertEquals(3, a.taken)
        // Seven days of the 08:00 slot plus one Wednesday at 20:00.
        assertEquals(8, a.planned)
        assertEquals(2, HealthMeds.streak(listOf(med(logs)), today))
        assertEquals("taken", HealthMeds.logFor(HealthMeds.Dose(11, 5, today, "08:00", 1.0), logs)?.status)
    }

    /** HEALTH-A6: an as-needed dose waits out its minimum interval; stock below the threshold asks for a refill. */
    @Test fun `HEALTH-A6 as-needed countdown and refill state`() {
        val prn = Medication(6, 1, "Paracetamol", prn = true, minIntervalHours = 6.0, logs = listOf(MedLog(9, 6, null, null, "taken", "2026-11-04T08:00")))
        val st = HealthMeds.prnState(prn, LocalDateTime.of(2026, 11, 4, 12, 30))
        assertFalse(st.allowed)
        assertEquals(LocalDateTime.of(2026, 11, 4, 14, 0), st.nextAllowedAt)
        assertEquals(90, st.remainingMinutes)
        assertTrue(HealthMeds.prnState(prn, LocalDateTime.of(2026, 11, 4, 14, 0)).allowed)
        assertEquals(HealthMeds.Refill.LOW, HealthMeds.refill(prn.copy(stockQty = 3.0, refillThreshold = 5.0)))
        assertEquals(HealthMeds.Refill.OUT, HealthMeds.refill(prn.copy(stockQty = 0.0)))
        assertEquals(HealthMeds.Refill.NONE, HealthMeds.refill(prn))
    }

    /** HEALTH-A7: a dose is one request with its stock deduction; a new medication carries its slots. */
    @Test fun `HEALTH-A7 dose and medication bodies`() {
        val take = HealthForms.doseBody(HealthMeds.Dose(11, 5, today, "08:00", 2.0), take = true, nowStamp = "2026-11-04T08:05")
        assertEquals("taken", take["status"]!!.jsonPrimitive.content)
        assertEquals("2026-11-04T08:00", take["scheduled_at"]!!.jsonPrimitive.content)
        assertEquals(2.0, take["stock_deduct"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val skip = HealthForms.doseBody(HealthMeds.Dose(11, 5, today, "08:00", 2.0), take = false, nowStamp = "x")
        assertFalse("stock_deduct" in skip || "taken_at" in skip)
        val form = HealthForms.MedForm(name = " Vitamin D ", prn = false, interval = "8", slots = listOf(HealthForms.SlotForm("07:30", "1", setOf(0, 2))))
        val body = (HealthForms.medBody(form, creating = true, personId = 4, meId = 1) as HealthForms.Built.Ok).body
        assertEquals("Vitamin D", body["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["min_interval_hours"])
        assertEquals(5, body["schedules"]!!.jsonArray[0].jsonObject["days_mask"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, body["user_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("health.meds.nameRequired", (HealthForms.medBody(form.copy(name = " "), true, 1, 1) as HealthForms.Built.Invalid).key)
        val log = HealthForms.logBody("taken", today, "09:10")
        assertEquals("2026-11-04T09:10:00", log["taken_at"]!!.jsonPrimitive.content)
        assertFalse("taken_at" in HealthForms.logBody("pending", today, "09:10"))
    }

    /** HEALTH-A8: a retry after an unknown outcome reuses the key; a clear answer starts afresh. */
    @Test fun `HEALTH-A8 dose attempts keep the key while the outcome is unknown`() {
        val attempts = DoseAttempts()
        val first = attempts.key("dose:1")
        attempts.settle("dose:1", ApiProblem.Network(IOException("down")))
        assertEquals(first, attempts.key("dose:1"))
        attempts.settle("dose:1", ApiProblem.Http(400, null, null))
        assertNotEquals(first, attempts.key("dose:1"))
    }

    // ── Cycle ──

    private fun period(id: Int, start: String, end: String? = null) = CyclePeriod(id, 1, start, end)

    /** HEALTH-A9: the average of plausible gaps from four periods on, the phase from the last start. */
    @Test fun `HEALTH-A9 cycle prediction`() {
        val periods = listOf(
            period(1, "2026-07-10", "2026-07-14"), period(2, "2026-08-07", "2026-08-11"),
            period(3, "2026-09-04", "2026-09-08"), period(4, "2026-10-02", "2026-10-06"),
            period(5, "2026-10-30", "2026-11-03"),
        )
        val p = HealthCycle.predict(periods, CycleSettings(), today)
        assertEquals(28, p.stats.avgCycle)
        assertEquals("history", p.stats.source)
        assertEquals(6, p.cycleDay)
        assertEquals(LocalDate.of(2026, 11, 27), p.nextStart)
        assertEquals("follicular", p.phase)
        assertEquals(true, p.stats.regular)
        assertEquals(LocalDate.of(2026, 11, 13), p.ovulation)
        val suppressed = HealthCycle.predict(periods, CycleSettings(contraception = "pill"), today)
        assertTrue(suppressed.fertilitySuppressed)
        assertNull(suppressed.ovulation)
        val pregnant = HealthCycle.predict(periods, CycleSettings(pregnancyMode = true, pregnancyDueDate = "2027-03-01"), today)
        assertTrue(pregnant.pregnant)
        assertEquals(23, pregnant.pregnancy.weeks)
        assertFalse(HealthCycle.predict(emptyList(), CycleSettings(), today).hasData)
        assertEquals(5, HealthCycle.openPeriod(listOf(period(5, "2026-11-02")), today)?.id)
    }

    /** HEALTH-A10: the day body sends every field, so a cleared one is cleared on the server too. */
    @Test fun `HEALTH-A10 cycle day and settings bodies`() {
        val f = HealthForms.DayForm(today, flow = "light", symptoms = mapOf("cramps" to 2), feelings = listOf("good"), temp = "36.6")
        val body = (HealthForms.dayBody(f) as HealthForms.Built.Ok).body
        assertEquals("c", body["basal_temp_unit"]!!.jsonPrimitive.content)
        assertEquals(2, body["symptoms"]!!.jsonArray[0].jsonObject["intensity"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, body["intimacy"])
        assertTrue(HealthForms.dayBody(f.copy(temp = "50")) is HealthForms.Built.Invalid)
        val settings = HealthForms.cycleSettingsBody(CycleSettings(cycleLengthAvg = 30))
        assertEquals(14, settings.size)
        assertEquals(JsonPrimitive(true), settings["show_pms"])
        assertEquals("health.cycle.endBeforeStart", (HealthForms.periodBody(today, today.minusDays(1), "", "private") as HealthForms.Built.Invalid).key)
    }

    // ── The rest ──

    /** HEALTH-A11: lab flags, the default trend analyte, prevention texts and bodies. */
    @Test fun `HEALTH-A11 labs and prevention`() {
        assertEquals("high", HealthLabs.flag(6.2, 4.0, 6.0))
        assertEquals("normal", HealthLabs.flag(5.0, null, 6.0))
        assertNull(HealthLabs.flag(5.0, null, null))
        val reports = listOf(
            LabReport(1, 1, "2026-01-10", results = listOf(LabResult(1, 1, "HbA1c", 5.9, "%"), LabResult(2, 1, "LDL", 110.0))),
            LabReport(2, 1, "2026-06-10", results = listOf(LabResult(3, 2, "hba1c", 6.4, "%", null, 6.0, "high"), LabResult(4, 2, "LDL", 100.0))),
        )
        assertEquals("HbA1c", HealthLabs.defaultAnalyte(reports))
        assertEquals(listOf(5.9, 6.4), HealthLabs.trend(reports, "HBA1C").map { it.value })
        val result = (HealthForms.resultBody(HealthForms.ResultForm("LDL", "160", "mg/dL", "", "130")) as HealthForms.Built.Ok).body
        assertEquals("high", result["flag"]!!.jsonPrimitive.content)
        assertEquals("Overdue by 3 days", HealthPrevention.dueText(-3, strings))
        assertEquals("Due today", HealthPrevention.dueText(0, strings))
        assertEquals("2" to "years", HealthPrevention.intervalToInput(24))
        val form = HealthForms.PreventionForm(typeId = null, name = "Tetanus", givenOn = today, interval = "10", intervalUnit = "years")
        val body = (HealthForms.preventionBody(form, true, 1, 1) as HealthForms.Built.Ok).body
        assertEquals(120, body["interval_months"]!!.jsonPrimitive.content.toInt())
        assertEquals("Tetanus", body["name"]!!.jsonPrimitive.content)
        assertTrue(HealthForms.preventionBody(form.copy(name = ""), true, 1, 1) is HealthForms.Built.Invalid)
    }

    /** HEALTH-A12: the activity week, nutrition progress and bodies, and the error sentences. */
    @Test fun `HEALTH-A12 activity, nutrition and errors`() {
        val rows = listOf(
            Activity(1, 1, "running", 30.0, 5.0, performedAt = "2026-11-04T07:00"),
            Activity(2, 1, "yoga", 45.0, performedAt = "2026-11-02T18:00"),
            Activity(3, 1, "yoga", 60.0, performedAt = "2026-10-20T18:00"),
        )
        val week = HealthActivity.week(rows, today, today)
        assertEquals(today.minusDays(6), week.from)
        assertEquals(2, HealthActivity.inWeek(rows, week).size)
        assertEquals(75.0, HealthActivity.totals(HealthActivity.inWeek(rows, week)).durationMin, 0.0)
        val p = HealthNutrition.progress(2500.0, 2000.0)
        assertTrue(p.over)
        assertEquals(1f, p.ratio)
        assertTrue(HealthNutrition.progress(1.0, 0.0).over)
        assertNull(HealthForms.nutrients(mapOf("fat_g" to "-1")))
        val target = (HealthForms.targetBody(mapOf("energy_kcal" to "2000"), 1, 1) as HealthForms.Built.Ok).body
        assertEquals(8, target.size)
        assertEquals(JsonNull, target["fat_g"])
        assertEquals("health.errorVitalGone", HealthErrors.key(ApiProblem.Http(404, "health_vital_not_found", null), "x"))
        assertTrue(HealthErrors.isGone(ApiProblem.Http(404, "health_vital_not_found", null)))
        assertEquals("health.fasting.conflict", HealthErrors.key(ApiProblem.Http(409, "FASTING_REVISION_CONFLICT", null), "x"))
        assertEquals("common.errorNoPermission", HealthErrors.key(ApiProblem.Http(403, null, null), "x"))
        assertEquals("fallback", HealthErrors.key(ApiProblem.Http(400, null, null), "fallback"))
        assertEquals(HealthRoutes.START, HealthRoutes.area("unknown"))
        assertEquals(listOf("vitals", "meds", "prevention", "labs", "activity", "nutrition"), HealthRoutes.areas(cycleEnabled = false, fastingEnabled = false))
    }
}
