package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.feature.health.FastingCursor
import io.github.marcomow.yuvomi.feature.health.HealthApi
import io.github.marcomow.yuvomi.feature.health.HealthForms
import io.github.marcomow.yuvomi.feature.health.HealthMeds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * docs/spec/health.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). The seed
 * gives every health row to linda and lets alex and linda care for emma and leo, so alex starts
 * without own health data: every fixture here is alex's own (or emma's, through care), dated in
 * 2001 where a date could collide, and removed again.
 */
class HealthContractTest : ContractTestBase() {

    private fun alex(): Pair<HealthApi, Int> = signedIn("alex").let { (c, p) -> HealthApi(c) to p.user.id }

    private suspend fun expectHttp(status: Int, block: suspend () -> Unit): ApiProblem.Http {
        try {
            block()
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            return e
        }
        fail("expected HTTP $status")
        error("unreachable")
    }

    /** HEALTH-A18 (GET /caregivers/me, GET /visibility-defaults, the cycle flag of GET /preferences). */
    @Test fun `HEALTH-A18 the shared answers decode and alex cares for the children`() = runBlocking<Unit> {
        val (api, _) = alex()
        val emma = signedIn("emma").second.user.id
        val leo = signedIn("leo").second.user.id
        assertTrue(api.careFor().containsAll(listOf(emma, leo)))
        assertTrue(api.visibilityDefaults().values.all { it == "private" || it == "family" })
        api.cycleEnabled()
        assertTrue(api.members().any { it.id == emma })
    }

    /** HEALTH-A19 (vitals: list, create, update, delete; limits; visibility; care). */
    @Test fun `HEALTH-A19 vitals round trip with limits, privacy and care`() = runBlocking<Unit> {
        val (api, me) = alex()
        val form = HealthForms.VitalForm(type = "weight", value = "81.4", unit = "kg", date = LocalDate.of(2001, 2, 3), time = "07:15", note = unique("w"))
        val body = (HealthForms.vitalBody(form, null, me, me) as HealthForms.Built.Ok).body
        val key = HealthApi.newKey()
        val created = api.createVital(body, key)
        try {
            assertEquals("weight", created.type)
            assertEquals(81.4, created.value1!!, 0.0)
            assertEquals("2001-02-03T07:15", created.measuredAt.take(16))
            assertEquals(me, created.userId)
            assertEquals(created.id, api.createVital(body, key).id)
            assertTrue(api.vitals(me).any { it.id == created.id })
            val edited = api.updateVital(created.id, buildJsonObject { put("value_num", 80.9); put("note", JsonNull) })
            assertEquals(80.9, edited.value1!!, 0.0)
            assertNull(edited.note)
            val impossible = expectHttp(400) { api.updateVital(created.id, buildJsonObject { put("value_num", 900) }) }
            assertEquals("health_vital_out_of_range", impossible.reason)
            val order = expectHttp(400) {
                api.createVital(buildJsonObject { put("type", "bp"); put("value_num", 70); put("value_num2", 90); put("unit", "mmHg"); put("measured_at", "2001-02-03T08:00") }, HealthApi.newKey())
            }
            assertEquals("health_vital_bp_order", order.reason)
        } finally {
            api.deleteVital(created.id)
        }
        assertEquals("health_vital_not_found", expectHttp(404) { api.deleteVital(created.id) }.reason)

        // Linda's measurements are private: alex sees none of them, and may not record for her.
        val linda = signedIn("linda").second.user.id
        assertTrue(api.vitals(linda).isEmpty())
        expectHttp(403) { api.createVital(buildJsonObject { put("type", "weight"); put("value_num", 60); put("measured_at", "2001-02-03T08:00"); put("user_id", linda) }, HealthApi.newKey()) }
        // Emma is in alex's care: he records for her and sees it, even private.
        val emma = signedIn("emma").second.user.id
        val forEmma = api.createVital(buildJsonObject {
            put("type", "temp"); put("value_num", 38.2); put("unit", "°C"); put("measured_at", "2001-02-03T09:00"); put("visibility", "private"); put("user_id", emma)
        }, HealthApi.newKey())
        try {
            assertEquals(emma, forEmma.userId)
            assertTrue(api.vitals(emma).any { it.id == forEmma.id })
        } finally {
            api.deleteVital(forEmma.id)
        }
    }

    /** HEALTH-A20 (medications, schedules, the dose log with take, skip, undo, correct and delete). */
    @Test fun `HEALTH-A20 medications, schedules and doses`() = runBlocking<Unit> {
        val (api, me) = alex()
        val name = unique("Vitamin D")
        val form = HealthForms.MedForm(name = name, stock = "20", refill = "5", slots = listOf(HealthForms.SlotForm("08:00", "1", emptySet())))
        val med = api.createMedication((HealthForms.medBody(form, true, me, me) as HealthForms.Built.Ok).body, HealthApi.newKey())
        try {
            assertEquals(1, med.schedules.size)
            val schedule = med.schedules.single()
            assertEquals("08:00", schedule.timeOfDay)
            assertNull(schedule.daysMask)
            val evening = api.createSchedule(med.id, HealthForms.slotBody(HealthForms.SlotForm("20:00", "2", setOf(0, 6)))!!, HealthApi.newKey())
            assertEquals(65, evening.daysMask)

            // A planned dose taken: one request, the stock goes down by the dose; undo gives it back.
            val dose = HealthMeds.Dose(schedule.id, med.id, LocalDate.of(2001, 2, 3), "08:00", 1.0)
            val taken = api.logDose(med.id, HealthForms.doseBody(dose, take = true, nowStamp = "2001-02-03T08:05"), HealthApi.newKey())
            assertEquals("taken", taken.log!!.status)
            assertEquals(1.0, taken.stockDeducted, 0.0)
            fun stock() = runBlocking { api.medications(me, "2001-01-01T00:00", "2001-12-31T23:59").single { it.id == med.id } }
            assertEquals(19.0, stock().stockQty!!, 0.0)
            assertTrue(stock().logs.any { it.id == taken.log.id })
            api.undoDose(taken.log.id, taken.stockDeducted)
            assertEquals(20.0, stock().stockQty!!, 0.0)
            assertEquals("pending", stock().logs.single { it.id == taken.log.id }.status)
            assertEquals("health_dose_pending", expectHttp(409) { api.undoDose(taken.log.id, 0.0) }.reason)

            // The scheduler's row: take and skip by id, correct it, never delete it.
            val retaken = api.markLog(taken.log.id, take = true, stockDeduct = 1.0, key = HealthApi.newKey())
            assertEquals("taken", retaken.log!!.status)
            assertEquals("skipped", api.markLog(taken.log.id, take = false, stockDeduct = null, key = HealthApi.newKey()).log!!.status)
            val corrected = api.updateLog(taken.log.id, HealthForms.logBody("taken", LocalDate.of(2001, 2, 3), "09:30"))
            assertEquals("2001-02-03T09:30", corrected.takenAt!!.take(16))
            expectHttp(409) { api.deleteLog(taken.log.id) }

            // An as-needed dose has no schedule and can go.
            val prn = api.updateMedication(med.id, buildJsonObject { put("prn", 1); put("min_interval_hours", 6); put("prn_dose_qty", 1) })
            assertTrue(prn.prn)
            val single = api.logDose(med.id, HealthForms.prnBody(prn, "2001-02-03T12:00"), HealthApi.newKey())
            api.deleteLog(single.log!!.id)
            assertEquals("health_dose_not_found", expectHttp(404) { api.deleteLog(single.log.id) }.reason)

            api.deleteSchedule(evening.id)
            assertEquals("health_schedule_not_found", expectHttp(404) { api.deleteSchedule(evening.id) }.reason)
        } finally {
            api.deleteMedication(med.id)
        }
        assertEquals("health_medication_not_found", expectHttp(404) { api.deleteMedication(med.id) }.reason)
    }

    /** HEALTH-A21 (fasting: state, stats, history, create, finish, undo of the end, update, delete, settings). */
    @Test fun `HEALTH-A21 fasting round trip`() = runBlocking<Unit> {
        val (api, _) = alex()
        val before = api.fastingState(null)
        assertTrue(before.canWrite)
        val goal = before.settings?.defaultGoalMinutes
        // A completed fast far in the past, acknowledging the safety note on the way.
        val start = Instant.parse("2001-02-03T18:00:00Z")
        val done = api.startFast(buildJsonObject {
            put("start_at", start.toString())
            put("end_at", start.plus(16, ChronoUnit.HOURS).toString())
            put("start_tzid", "UTC")
            put("acknowledge_safety", true)
        }, HealthApi.newKey())
        try {
            assertNotNull(done.endAt)
            val rated = api.updateFast(done.id, buildJsonObject { put("rating", 4); put("note", unique("fast")); put("expected_revision", done.revision) })
            assertEquals(4, rated.rating)
            assertEquals("FASTING_REVISION_CONFLICT", expectHttp(409) { api.updateFast(done.id, buildJsonObject { put("rating", 3); put("expected_revision", done.revision) }) }.reason)
            val (rows, _) = api.fastingHistory(null, FastingCursor("2100-01-01T00:00:00Z", 999_999_999))
            assertTrue(rows.any { it.id == done.id })
            assertTrue(api.fastingStats(null).allTime.count >= 1)
            assertTrue(api.fastingState(null).acknowledged)

            if (before.active == null) {
                val active = api.startFast(buildJsonObject { put("start_tzid", "UTC") }, HealthApi.newKey())
                try {
                    assertNull(active.endAt)
                    Thread.sleep(20)
                    val finished = api.finishFast(active)
                    assertNotNull(finished.endAt)
                    val reopened = api.updateFast(finished.id, buildJsonObject { put("end_at", JsonNull); put("expected_revision", finished.revision) })
                    assertNull(reopened.endAt)
                    api.deleteFast(reopened)
                } catch (e: Throwable) {
                    runCatching { api.deleteFast(api.fastingState(null).active ?: active) }
                    throw e
                }
            }
            val settings = api.saveFastingSettings(buildJsonObject { put("default_goal_minutes", 960) })
            assertEquals(960, settings.defaultGoalMinutes)
        } finally {
            api.saveFastingSettings(buildJsonObject { put("default_goal_minutes", goal?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull) })
            api.deleteFast(api.fastingHistory(null, FastingCursor("2100-01-01T00:00:00Z", 999_999_999)).first.single { it.id == done.id })
        }
    }

    /** HEALTH-A22 (cycle: periods, day logs, settings, bulk visibility). */
    @Test fun `HEALTH-A22 cycle round trip`() = runBlocking<Unit> {
        val (api, me) = alex()
        val settings = api.cycleSettings()
        val period = api.createPeriod((HealthForms.periodBody(LocalDate.of(2001, 3, 1), null, unique("p"), "family") as HealthForms.Built.Ok).body, HealthApi.newKey())
        try {
            assertNull(period.endDate)
            val ended = api.updatePeriod(period.id, buildJsonObject { put("end_date", "2001-03-05") })
            assertEquals("2001-03-05", ended.endDate)
            expectHttp(400) { api.updatePeriod(period.id, buildJsonObject { put("end_date", "2001-02-01") }) }
            val day = HealthForms.DayForm(LocalDate.of(2001, 3, 2), flow = "medium", symptoms = mapOf("cramps" to 2), feelings = listOf("sensitive"), temp = "36.4", intimacy = "protected")
            val log = api.saveCycleLog((HealthForms.dayBody(day) as HealthForms.Built.Ok).body, HealthApi.newKey())
            // One row per day: a second save replaces it.
            val again = api.saveCycleLog((HealthForms.dayBody(day.copy(flow = "light")) as HealthForms.Built.Ok).body, HealthApi.newKey())
            assertEquals(log.id, again.id)
            val mine = api.cycleLogs(me).single { it.id == log.id }
            assertEquals("light", mine.flow)
            assertEquals(listOf("cramps"), mine.symptoms.map { it.key })
            assertEquals(listOf("sensitive"), mine.feelings)
            assertEquals("protected", mine.intimacy)
            assertTrue(api.periods(me).any { it.id == period.id })
            val (periods, logs) = api.setCycleVisibility("private")
            assertTrue(periods >= 1 && logs >= 1)
            assertEquals("private", api.periods(me).single { it.id == period.id }.visibility)
            api.deleteCycleLog(log.id)
            assertEquals("health_cycle_log_not_found", expectHttp(404) { api.deleteCycleLog(log.id) }.reason)
            // Settings: PUT replaces everything, so sending them back unchanged is a no-op.
            val saved = api.saveCycleSettings(HealthForms.cycleSettingsBody(settings.copy(cycleLengthAvg = 29)))
            assertEquals(29, saved.cycleLengthAvg)
        } finally {
            api.saveCycleSettings(HealthForms.cycleSettingsBody(settings))
            api.deletePeriod(period.id)
        }
        assertEquals("health_cycle_period_not_found", expectHttp(404) { api.deletePeriod(period.id) }.reason)
    }

    /** HEALTH-A23 (prevention: types, records, due list). */
    @Test fun `HEALTH-A23 prevention records and the due list`() = runBlocking<Unit> {
        val (api, me) = alex()
        val types = api.preventionTypes()
        val free = api.createPreventionRecord((HealthForms.preventionBody(
            HealthForms.PreventionForm(name = unique("Check-up"), givenOn = LocalDate.of(2001, 4, 1), provider = "Dr. Test"), true, me, me,
        ) as HealthForms.Built.Ok).body, HealthApi.newKey())
        val typed = types.firstOrNull()?.let { type ->
            api.createPreventionRecord((HealthForms.preventionBody(
                HealthForms.PreventionForm(typeId = type.id, givenOn = LocalDate.of(2001, 4, 1), interval = "12"), true, me, me,
            ) as HealthForms.Built.Ok).body, HealthApi.newKey())
        }
        try {
            assertEquals("Dr. Test", free.provider)
            assertTrue(api.preventionRecords(me).any { it.id == free.id })
            val edited = api.updatePreventionRecord(free.id, buildJsonObject { put("dose_number", 2); put("next_due_on", "2001-05-01") })
            assertEquals(2.0, edited.doseNumber!!, 0.0)
            if (typed != null) {
                // A typed record with an interval long past is overdue.
                val due = api.preventionDue(me).single { it.recordId == typed.id }
                assertEquals("2002-04-01", due.dueOn)
                assertTrue(due.daysLeft < 0)
            }
            assertEquals("health_prevention_selected_type_not_found", expectHttp(400) {
                api.updatePreventionRecord(free.id, buildJsonObject { put("type_id", 999_999) })
            }.reason)
        } finally {
            api.deletePreventionRecord(free.id)
            typed?.let { api.deletePreventionRecord(it.id) }
        }
        assertEquals("health_prevention_record_not_found", expectHttp(404) { api.deletePreventionRecord(free.id) }.reason)
    }

    /** HEALTH-A24 (labs: reports and analytes). */
    @Test fun `HEALTH-A24 lab reports and analytes`() = runBlocking<Unit> {
        val (api, me) = alex()
        val report = api.createLab(HealthForms.labBody(LocalDate.of(2001, 5, 2), unique("Lab"), "", "private", true, me, me), HealthApi.newKey())
        try {
            assertTrue(report.results.isEmpty())
            val ldl = api.createResult(report.id, (HealthForms.resultBody(HealthForms.ResultForm("LDL", "160", "mg/dL", "", "130")) as HealthForms.Built.Ok).body, HealthApi.newKey())
            assertEquals("high", ldl.flag)
            val fixed = api.updateResult(ldl.id, buildJsonObject { put("value_num", 120) })
            assertEquals(120.0, fixed.value!!, 0.0)
            val head = api.updateLab(report.id, buildJsonObject { put("note", "fasting") })
            assertEquals("fasting", head.note)
            assertEquals(1, api.labs(me).single { it.id == report.id }.results.size)
            api.deleteResult(ldl.id)
            assertEquals("health_lab_result_not_found", expectHttp(404) { api.deleteResult(ldl.id) }.reason)
        } finally {
            api.deleteLab(report.id)
        }
        assertEquals("health_lab_report_not_found", expectHttp(404) { api.deleteLab(report.id) }.reason)
    }

    /** HEALTH-A25 (activities). */
    @Test fun `HEALTH-A25 activities round trip`() = runBlocking<Unit> {
        val (api, me) = alex()
        val form = HealthForms.ActivityForm(type = "running", date = LocalDate.of(2001, 6, 3), time = "06:30", duration = "42", distance = "7.5", intensity = "moderate")
        val row = api.createActivity((HealthForms.activityBody(form, false, me, me) as HealthForms.Built.Ok).body, HealthApi.newKey())
        try {
            assertEquals(42.0, row.durationMin!!, 0.0)
            assertEquals("moderate", row.intensity)
            val edited = api.updateActivity(row.id, (HealthForms.activityBody(form.copy(type = HealthForms.CUSTOM, custom = "Rowing", distance = ""), true, me, me) as HealthForms.Built.Ok).body)
            assertEquals("Rowing", edited.type)
            assertNull(edited.distanceKm)
            assertTrue(api.activities(me).any { it.id == row.id })
        } finally {
            api.deleteActivity(row.id)
        }
        assertEquals("health_activity_not_found", expectHttp(404) { api.deleteActivity(row.id) }.reason)
    }

    /** HEALTH-A26 (nutrition: entries, the day's summary, the target). */
    @Test fun `HEALTH-A26 nutrition entries, summary and target`() = runBlocking<Unit> {
        val (api, me) = alex()
        val day = LocalDate.parse(api.nutritionSummary(me).date!!)
        val before = api.nutritionSummary(me)
        val form = HealthForms.NutritionForm(title = unique("Porridge"), date = day, time = "07:30", meal = "breakfast", nutrients = mapOf("energy_kcal" to "350", "protein_g" to "12.5"), visibility = "all")
        val entry = api.createNutritionEntry((HealthForms.nutritionBody(form, true, me, me) as HealthForms.Built.Ok).body, HealthApi.newKey())
        try {
            assertEquals("all", entry.visibility)
            assertEquals(350.0, entry.energyKcal!!, 0.0)
            val after = api.nutritionSummary(me)
            assertEquals((before.totals["energy_kcal"] ?: 0.0) + 350.0, after.totals["energy_kcal"]!!, 0.01)
            val edited = api.updateNutritionEntry(entry.id, (HealthForms.nutritionBody(form.copy(meal = null, nutrients = mapOf("energy_kcal" to "300")), false, me, me) as HealthForms.Built.Ok).body)
            assertNull(edited.mealType)
            assertNull(edited.proteinG)
            assertTrue(api.nutritionEntries(me, day.toString()).any { it.id == entry.id })
            api.saveNutritionTarget((HealthForms.targetBody(mapOf("energy_kcal" to "2000"), me, me) as HealthForms.Built.Ok).body)
            assertEquals(2000.0, api.nutritionSummary(me).target!!["energy_kcal"]!!, 0.0)
        } finally {
            // All empty deletes the target again (alex had none).
            api.saveNutritionTarget((HealthForms.targetBody(emptyMap(), me, me) as HealthForms.Built.Ok).body)
            api.deleteNutritionEntry(entry.id)
        }
        assertNull(api.nutritionSummary(me).target)
        assertEquals("health_nutrition_entry_not_found", expectHttp(404) { api.deleteNutritionEntry(entry.id) }.reason)
        assertFalse(api.nutritionEntries(me, day.toString()).any { it.id == entry.id })
    }
}
