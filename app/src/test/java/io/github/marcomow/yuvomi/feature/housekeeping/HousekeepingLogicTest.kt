package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** docs/spec/housekeeping.md, the pure rules (HOUSE-A1 to A9). */
class HousekeepingLogicTest {
    private val strings = Strings(
        "en",
        mapOf(
            "housekeeping.taskTemplateData.cleanBathrooms.name" to "Clean bathrooms",
            "housekeeping.taskTemplateData.cleanBathrooms.area" to "Bathrooms",
            "housekeeping.unnamedWorker" to "Housekeeper",
            "housekeeping.calendarVisitTitle" to "Housekeeping: {{name}}",
            "housekeeping.paymentTaskTitle" to "Pay {{name}} for housekeeping",
            "housekeeping.paymentTaskDescription" to "Housekeeping visit on {{date}}. Amount due: {{amount}}.",
        ),
        emptyMap(),
    ) { "other" }

    private fun worker(current: Visit? = null, today: Visit? = null, rate: Double? = 45.0, hourly: Boolean = false) =
        Worker(id = 1, displayName = "Maria", dailyRate = rate, hourlyRate = if (hourly) 20.0 else 0.0, rateType = if (hourly) "hourly" else "daily",
            paymentSchedule = "twice_monthly", currentSession = current, todaySession = today)

    /** HOUSE-A1: amounts only with the right and a value (R20); admins always see them. */
    @Test fun `HOUSE-A1 amounts hidden without the right or the value`() {
        assertTrue(HousekeepingRules.amountsHidden(false, 12.0))
        assertTrue(HousekeepingRules.amountsHidden(true, null))
        assertFalse(HousekeepingRules.amountsHidden(true, 0.0))
        assertTrue(HousekeepingRules.canSeeAmounts(false, emptyMap()))
        assertFalse(HousekeepingRules.canSeeAmounts(false, mapOf("housekeeping_view_amounts" to "none")))
        assertTrue(HousekeepingRules.canSeeAmounts(true, mapOf("housekeeping_view_amounts" to "none")))
    }

    /** HOUSE-A2: amount fields as text: grouping refused, precision by currency, untouched stays. */
    @Test fun `HOUSE-A2 amount fields`() {
        assertNull(HousekeepingRules.amountProblem("", "EUR", "en"))
        assertNull(HousekeepingRules.amountProblem("12.5", "EUR", "en"))
        assertNull(HousekeepingRules.amountProblem("12,50", "EUR", "de"))
        assertEquals(HousekeepingRules.AmountProblem.GROUPED, HousekeepingRules.amountProblem("1.200", "EUR", "de"))
        assertEquals(HousekeepingRules.AmountProblem.GROUPED, HousekeepingRules.amountProblem("1,200", "EUR", "en"))
        assertEquals(HousekeepingRules.AmountProblem.INVALID, HousekeepingRules.amountProblem("-3", "EUR", "en"))
        assertEquals(HousekeepingRules.AmountProblem.INVALID, HousekeepingRules.amountProblem("abc", "EUR", "en"))
        assertEquals(HousekeepingRules.AmountProblem.PRECISION, HousekeepingRules.amountProblem("12.5", "JPY", "en"))
        assertNull(HousekeepingRules.amountProblem("12.5", "JPY", "en", original = 12.5))
        assertEquals(12.5, HousekeepingRules.parseAmount("12,5", "de")!!, 0.0)
        assertEquals(0.5, HousekeepingRules.parseAmount(".5", "en")!!, 0.0)
        assertNull(HousekeepingRules.parseAmount(" ", "en"))
        assertTrue(HousekeepingRules.parseAmount("1,200", "en")!!.isNaN())
        assertEquals("45,00", HousekeepingRules.amountToInput(45.0, "EUR", "de"))
        assertEquals("1250", HousekeepingRules.amountToInput(1250.0, "JPY", "en"))
    }

    /** HOUSE-A3: `computeHourlyAmount`: minutes to the quarter hour. */
    @Test fun `HOUSE-A3 hourly amount rounds to the quarter hour`() {
        assertEquals(20.0, HousekeepingRules.hourlyAmount(53.0, 20.0), 1e-9)
        assertEquals(15.0, HousekeepingRules.hourlyAmount(38.0, 20.0), 1e-9)
        assertEquals(0.0, HousekeepingRules.hourlyAmount(-5.0, 20.0), 1e-9)
        assertEquals(0.0, HousekeepingRules.hourlyAmount(60.0, null), 1e-9)
    }

    /** HOUSE-A4: the worker strip: the button follows the open session, the line today's (#1133). */
    @Test fun `HOUSE-A4 session line and check button`() {
        val open = Visit(id = 9, checkIn = "2026-11-02T08:30:00Z")
        val done = Visit(id = 9, checkIn = "2026-11-02T08:30:00Z", checkOut = "2026-11-02T12:00:00Z")
        assertEquals(HousekeepingRules.CheckAction.CHECK_OUT, HousekeepingRules.checkAction(worker(current = open, today = open)))
        assertEquals(HousekeepingRules.CheckAction.CHECK_IN_AGAIN, HousekeepingRules.checkAction(worker(today = done)))
        assertEquals(HousekeepingRules.CheckAction.CHECK_IN, HousekeepingRules.checkAction(worker()))
        assertEquals(HousekeepingRules.SessionLine.CheckedIn(open.checkIn), HousekeepingRules.sessionLine(worker(current = open, today = open), true))
        assertEquals(HousekeepingRules.SessionLine.DoneToday(done.checkIn, done.checkOut!!), HousekeepingRules.sessionLine(worker(today = done), true))
        assertEquals(HousekeepingRules.SessionLine.Rate(false, 45.0, "twice_monthly"), HousekeepingRules.sessionLine(worker(), true))
        assertEquals(HousekeepingRules.SessionLine.Rate(true, 20.0, "twice_monthly"), HousekeepingRules.sessionLine(worker(hourly = true), true))
        assertEquals(HousekeepingRules.SessionLine.NotHere, HousekeepingRules.sessionLine(worker(rate = null), true))
    }

    /** HOUSE-A5: the payments chart: share, minimum scale, empty months, paid share. */
    @Test fun `HOUSE-A5 chart bars and paid months`() {
        val months = listOf(MonthPayment("2026-06", 0.0, 0.0), MonthPayment("2026-07", 10.0, 0.0), MonthPayment("2026-08", 400.0, 100.0))
        val bars = HousekeepingRules.bars(months)
        assertEquals(0.0, bars[0].scale, 0.0)
        assertEquals(0.06, bars[1].scale, 1e-9)
        assertEquals(1.0, bars[2].scale, 1e-9)
        assertEquals(0.75, bars[2].paidShare, 1e-9)
        assertEquals(2, HousekeepingRules.paidMonths(months).size)
        assertTrue(HousekeepingRules.paidMonths(null).isEmpty())
    }

    /** HOUSE-A6: areas and templates of the create sheet (R24). */
    @Test fun `HOUSE-A6 known areas and remaining templates`() {
        val tasks = listOf(DecayTask(1, "Clean bathrooms", "bathrooms"), DecayTask(2, "Dust", " Kitchen "))
        val templates = listOf(TaskTemplate("cleanBathrooms", "Clean bathrooms", "Bathrooms", 7), TaskTemplate(null, "Wash car", "Garage", 30))
        assertEquals(listOf("bathrooms", "Garage", "Kitchen"), HousekeepingRules.knownAreas(tasks, templates.map { HousekeepingRules.templateLabel(it, true, strings) }))
        val left = HousekeepingRules.remainingTemplates(templates, tasks) { HousekeepingRules.templateLabel(it, false, strings) }
        assertEquals(listOf("Wash car"), left.map { it.name })
        assertTrue(HousekeepingRules.taskFormValid("A", "B", "1"))
        assertFalse(HousekeepingRules.taskFormValid("A", "B", "0"))
        assertFalse(HousekeepingRules.taskFormValid("A", " ", "3"))
    }

    /** HOUSE-A7: the visit row and the report month. */
    @Test fun `HOUSE-A7 payment meta, hidden receipts, month steps`() {
        assertEquals(listOf("housekeeping.paymentPending"), HousekeepingRules.paymentMetaKeys(Visit(1), readOnly = false))
        assertEquals(listOf("housekeeping.paymentPaid"), HousekeepingRules.paymentMetaKeys(Visit(1, paidAt = "x", canEdit = true), readOnly = false))
        assertEquals(listOf("housekeeping.paymentPaid", "housekeeping.settledAdminOnly"), HousekeepingRules.paymentMetaKeys(Visit(1, paidAt = "x"), readOnly = false))
        assertEquals(listOf("housekeeping.paymentPaid"), HousekeepingRules.paymentMetaKeys(Visit(1, paidAt = "x"), readOnly = true))
        assertTrue(HousekeepingRules.receiptHidden(Visit(1, hasReceipt = true)))
        assertFalse(HousekeepingRules.receiptHidden(Visit(1, hasReceipt = true, receiptDocumentId = 4)))
        assertEquals("2026-01", HousekeepingRules.shiftMonth("2025-12", 1))
        assertEquals("2025-12", HousekeepingRules.shiftMonth("2026-01", -1))
    }

    /** HOUSE-A8: the bodies: worker (other rate as stored), visit (minutes or rate), check-in. */
    @Test fun `HOUSE-A8 request bodies`() {
        val stored = worker(hourly = false)
        val form = HousekeepingRules.workerForm(stored, "EUR", "en").copy(rateType = "hourly", hourlyRate = "18.5", dailyRate = "999", username = " ")
        val body = HousekeepingRules.workerBody(form, stored, "en")
        assertEquals("18.5", body["hourly_rate"]!!.jsonPrimitive.content)
        assertEquals("45.0", body["daily_rate"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["username"])
        assertFalse("avatar_data" in body)
        assertTrue(HousekeepingRules.usernameValid("maria.s"))
        assertFalse(HousekeepingRules.usernameValid("ma"))
        val texts = HousekeepingRules.visitTexts(null, "Nov 2", "€45.00", strings)
        assertEquals("Housekeeping: Housekeeper", texts.eventTitle)
        assertEquals("Housekeeping visit on Nov 2. Amount due: €45.00.", texts.paymentDescription)
        val hourly = HousekeepingRules.visitBody(Visit(1, rateType = "hourly"), "2026-11-02", "2026-11-01", 90.0, null, 5.0, null, texts)
        assertEquals("90.0", hourly["minutes_worked"]!!.jsonPrimitive.content)
        assertFalse("daily_rate" in hourly)
        assertEquals(JsonNull, hourly["receipt_document_id"])
        val daily = HousekeepingRules.visitBody(Visit(1), "2026-11-02", "2026-11-02", null, 45.0, 0.0, 7, texts)
        assertFalse("minutes_worked" in daily)
        assertEquals("7", daily["receipt_document_id"]!!.jsonPrimitive.content)
        val checkIn = HousekeepingRules.checkInBody(worker(hourly = true), "Europe/Berlin", texts)
        assertEquals("0.0", checkIn["daily_rate"]!!.jsonPrimitive.content)
        assertEquals("Europe/Berlin", checkIn["timezone"]!!.jsonPrimitive.content)
    }

    /** HOUSE-A9: errors: the app's sentence per reason, reloads after gone and already checked in. */
    @Test fun `HOUSE-A9 error sentences`() {
        val gone = ApiProblem.Http(404, "housekeeping_visit_not_found", "Visit not found.")
        assertEquals("housekeeping.errorVisitGone", HousekeepingErrors.key(gone))
        assertTrue(HousekeepingErrors.reloads(gone))
        assertTrue(HousekeepingErrors.alreadyDeleted(gone, HousekeepingErrors.VISIT_GONE))
        assertFalse(HousekeepingErrors.alreadyDeleted(gone, HousekeepingErrors.TASK_GONE))
        val checkedIn = ApiProblem.Http(409, "housekeeping_already_checked_in", null)
        assertEquals("housekeeping.errorAlreadyCheckedIn", HousekeepingErrors.key(checkedIn))
        assertTrue(HousekeepingErrors.reloads(checkedIn))
        assertEquals("housekeeping.errorAmountsHidden", HousekeepingErrors.key(ApiProblem.Http(403, "amounts_hidden", null)))
        assertEquals("common.errorNoPermission", HousekeepingErrors.key(ApiProblem.Http(403, null, null)))
        assertFalse(HousekeepingErrors.isGone(ApiProblem.Http(403, "housekeeping_visit_not_found", null)))
        assertEquals("android.housekeeping.usernameTaken", HousekeepingErrors.key(ApiProblem.Http(409, "housekeeping_username_taken", null)))
        assertEquals("common.errorGeneric", HousekeepingErrors.key(ApiProblem.Http(500, null, null)))
        assertEquals("common.errorOfflineMutation", HousekeepingErrors.key(ApiProblem.Network(IOException("x"))))
    }

    /** HOUSE-A10: the Tags view: hashtags in chore names and areas, and in a person's notes. */
    @Test fun `HOUSE-A10 tag source items`() {
        val items = HousekeepingTagSource.items(
            listOf(DecayTask(3, "Windows #spring", "Whole house"), DecayTask(4, "Oven", "Kitchen")),
            listOf(Worker(id = 8, displayName = "Maria", notes = "Keys at #neighbour")),
        )
        assertEquals(2, items.size)
        assertEquals(setOf("spring"), items[0].tags)
        assertEquals(HousekeepingRoutes.task(3), items[0].route)
        assertEquals("worker:8", items[1].key)
        assertEquals(HousekeepingRoutes.worker(8), items[1].route)
    }
}
