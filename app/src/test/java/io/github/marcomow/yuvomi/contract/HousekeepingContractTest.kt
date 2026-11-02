package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingApi
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingRules
import io.github.marcomow.yuvomi.feature.housekeeping.Worker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * docs/spec/housekeeping.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). The
 * seed has one housekeeper, maria, checked in today, with chores and visits. Every write here
 * runs on a housekeeper or chore the test creates itself and removes again (the profile goes
 * with its account, `DELETE /auth/users/{id}`).
 */
class HousekeepingContractTest : ContractTestBase() {

    private val texts = HousekeepingRules.VisitTexts("Housekeeping: Test", "Pay Test", "Visit")

    private fun alex(): Pair<HousekeepingApi, YuvomiClient> = signedIn("alex").first.let { HousekeepingApi(it, "UTC") to it }

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

    private suspend fun newWorker(api: HousekeepingApi, hourly: Boolean = false): Worker {
        val form = HousekeepingRules.WorkerForm(
            displayName = unique("HK"),
            rateType = if (hourly) "hourly" else "daily",
            dailyRate = "40",
            hourlyRate = "20",
            paymentSchedule = "monthly",
            notes = "contract #test",
        )
        return api.saveWorker(HousekeepingRules.workerBody(form, null, "en"), YuvomiClient.newIdempotencyKey())
    }

    private fun removeWorker(client: YuvomiClient, worker: Worker) {
        worker.userId?.let { raw(client, "DELETE", "/api/v1/auth/users/$it", "{}", tolerate = true) }
    }

    /** HOUSE-A16 (GET /dashboard, /decay-tasks, /visits, /task-templates, /workers, /preferences currency). */
    @Test fun `HOUSE-A16 the page loads and decodes the seed`() = runBlocking<Unit> {
        val (api, _) = alex()
        val data = api.load()
        assertTrue(data.workers.any { it.username == "maria" })
        assertTrue(data.canManage)
        assertTrue(data.tasks.isNotEmpty())
        assertTrue(data.tasks.all { it.urgencyStatus in setOf("overdue", "today", "ok") })
        assertEquals(8, data.templates.size)
        assertTrue(data.templates.all { it.key != null })
        assertNotNull(data.dashboard.pendingPayments)
        assertEquals(6, data.dashboard.monthlyPayments!!.size)
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString().take(7), data.current.month)
        assertNotNull(data.current.totals)
        assertTrue(data.currency.isNotBlank())
    }

    /** HOUSE-A17 (POST /worker create and edit, can_manage, the username rules). */
    @Test fun `HOUSE-A17 a profile is created, edited, and its username checked`() = runBlocking<Unit> {
        val (api, client) = alex()
        val worker = newWorker(api)
        try {
            assertEquals(40.0, worker.dailyRate!!, 0.0)
            assertEquals("monthly", worker.paymentSchedule)
            val form = HousekeepingRules.workerForm(worker, "EUR", "en").copy(rateType = "hourly", hourlyRate = "22.5", phone = "+1 555 0100")
            val edited = api.saveWorker(HousekeepingRules.workerBody(form, worker, "en"), YuvomiClient.newIdempotencyKey())
            assertEquals(worker.id, edited.id)
            assertTrue(edited.hourly)
            assertEquals(22.5, edited.hourlyRate!!, 0.0)
            assertEquals(40.0, edited.dailyRate!!, 0.0)
            assertEquals("+1 555 0100", edited.phone)
            val invalid = expectHttp(400) { api.saveWorker(HousekeepingRules.workerBody(form.copy(username = "a b"), worker, "en"), YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_username_invalid", invalid.reason)
            val taken = expectHttp(409) { api.saveWorker(HousekeepingRules.workerBody(form.copy(username = "maria"), worker, "en"), YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_username_taken", taken.reason)
            val gone = expectHttp(404) { api.saveWorker(HousekeepingRules.workerBody(form.copy(id = 999_999), null, "en"), YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_worker_not_found", gone.reason)
        } finally {
            removeWorker(client, worker)
        }
    }

    /** HOUSE-A18 (check-in, check-out, reopen and their conflicts). */
    @Test fun `HOUSE-A18 sessions check in, out and reopen`() = runBlocking<Unit> {
        val (api, client) = alex()
        val worker = newWorker(api)
        var sessionId: Int? = null
        try {
            val checkIn = api.checkIn(HousekeepingRules.checkInBody(worker, "UTC", texts), YuvomiClient.newIdempotencyKey())
            sessionId = checkIn.id
            assertNull(checkIn.checkOut)
            assertEquals(40.0, checkIn.dailyRate!!, 0.0)
            val again = expectHttp(409) { api.checkIn(HousekeepingRules.checkInBody(worker, "UTC", texts), YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_already_checked_in", again.reason)
            val open = expectHttp(409) { api.reopen(checkIn.id, YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_visit_open", open.reason)
            val listed = api.load().workers.first { it.id == worker.id }
            assertEquals(checkIn.id, listed.currentSession?.id)
            val out = api.checkOut(worker.id, YuvomiClient.newIdempotencyKey())
            assertEquals(checkIn.id, out.id)
            assertNotNull(out.checkOut)
            val none = expectHttp(404) { api.checkOut(worker.id, YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_open_session_not_found", none.reason)
            val reopened = api.reopen(out.id, YuvomiClient.newIdempotencyKey())
            assertNull(reopened.checkOut)
            api.checkOut(worker.id, YuvomiClient.newIdempotencyKey())
            val unknown = expectHttp(404) { api.checkIn(HousekeepingRules.checkInBody(worker.copy(id = 999_999), "UTC", texts), YuvomiClient.newIdempotencyKey()) }
            assertEquals("housekeeping_worker_not_found", unknown.reason)
        } finally {
            sessionId?.let { runCatching { api.deleteVisit(it) } }
            removeWorker(client, worker)
        }
    }

    /** HOUSE-A19 (GET/PUT/DELETE /visits/{id}, pay, unpay, the receipt into Documents, `?worker_id`). */
    @Test fun `HOUSE-A19 a visit is edited, paid, unpaid and deleted`() = runBlocking<Unit> {
        val (api, client) = alex()
        val worker = newWorker(api)
        var visitId: Int? = null
        var receiptId: Int? = null
        try {
            val id = api.checkIn(HousekeepingRules.checkInBody(worker, "UTC", texts), YuvomiClient.newIdempotencyKey()).id
            visitId = id
            api.checkOut(worker.id, YuvomiClient.newIdempotencyKey())
            val visit = api.visit(id)
            assertTrue(visit.canEdit && visit.canDelete && visit.canMarkPaid)
            assertFalse(visit.canMarkUnpaid)
            assertEquals(worker.displayName, visit.workerName)
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            receiptId = api.uploadReceipt("Receipt ${unique("r")}", "contract", "Housekeeping", "receipt.txt", "data:text/plain;base64,aGVsbG8=", YuvomiClient.newIdempotencyKey())
            assertNotNull(receiptId)
            val edited = api.updateVisit(id, HousekeepingRules.visitBody(visit, day, day, null, 50.0, 7.5, receiptId, texts))
            assertEquals(50.0, edited.dailyRate!!, 0.0)
            assertEquals(7.5, edited.extras!!, 0.0)
            assertEquals(receiptId, edited.receiptDocumentId)
            val month = api.visits(day.take(7), worker.id)
            assertEquals(listOf(id), month.visits.map { it.id })
            assertEquals(57.5, month.totals!!.total, 0.001)
            assertNotNull(month.visits.single().receiptDocumentName)
            val paid = api.pay(id, YuvomiClient.newIdempotencyKey())
            assertNotNull(paid.paidAt)
            val settled = api.visit(id)
            assertTrue(settled.canMarkUnpaid)
            assertFalse(settled.canMarkPaid)
            assertNull(api.unpay(id, YuvomiClient.newIdempotencyKey()).paidAt)
            api.updateVisit(id, HousekeepingRules.visitBody(visit, day, day, null, 50.0, 7.5, null, texts))
            api.deleteVisit(id)
            val gone = expectHttp(404) { api.visit(id) }
            assertEquals("housekeeping_visit_not_found", gone.reason)
            assertEquals("housekeeping_visit_not_found", expectHttp(404) { api.deleteVisit(id) }.reason)
            visitId = null
        } finally {
            visitId?.let { runCatching { api.deleteVisit(it) } }
            receiptId?.let { raw(client, "DELETE", "/api/v1/documents/$it", "{}", tolerate = true) }
            removeWorker(client, worker)
        }
    }

    /** HOUSE-A20 (POST/PATCH/DELETE /decay-tasks, complete, the undo of a completion). */
    @Test fun `HOUSE-A20 a chore is created, completed, restored, edited and deleted`() = runBlocking<Unit> {
        val (api, _) = alex()
        val name = unique("Chore")
        val created = api.createTask(name, "Garage", 10, YuvomiClient.newIdempotencyKey())
        try {
            assertEquals(name, created.name)
            assertNull(created.lastCompleted)
            val done = api.completeTask(created.id, YuvomiClient.newIdempotencyKey())
            assertNotNull(done.lastCompleted)
            assertEquals("ok", done.urgencyStatus)
            assertNull(api.restoreCompletion(created.id, null).lastCompleted)
            val edited = api.updateTask(created.id, "$name!", "Attic", 3)
            assertEquals("Attic", edited.area)
            assertEquals(3, edited.frequencyDays)
            assertTrue(api.load().tasks.any { it.id == created.id })
            val bad = expectHttp(400) { api.updateTask(created.id, name, "Attic", 0) }
            assertNull(bad.reason)
        } finally {
            api.deleteTask(created.id)
        }
        assertEquals("housekeeping_task_not_found", expectHttp(404) { api.deleteTask(created.id) }.reason)
        assertEquals("housekeeping_task_not_found", expectHttp(404) { api.completeTask(created.id, YuvomiClient.newIdempotencyKey()) }.reason)
    }

    /** HOUSE-A21: a non-admin does not manage profiles and cannot create one. */
    @Test fun `HOUSE-A21 only admins manage profiles`() = runBlocking<Unit> {
        val (client, _) = signedIn("emma")
        val api = HousekeepingApi(client, "UTC")
        val answer = try {
            api.load()
        } catch (e: ApiProblem.Http) {
            // A child without the module gets the gate's 403; nothing more to check then.
            assertEquals(403, e.status)
            return@runBlocking
        }
        assertFalse(answer.canManage)
        // Children start read-only and without amounts (seed-demo.js): the server sends `null`, never 0 (R20).
        assertNull(answer.dashboard.pendingPayments)
        assertNull(answer.dashboard.monthlyPayments)
        assertNull(answer.current.totals)
        assertTrue(answer.workers.all { it.dailyRate == null })
        assertTrue(answer.current.visits.none { it.canMarkPaid || it.canEdit })
        val body = buildJsonObject { put("display_name", unique("HK")); put("daily_rate", 1) }
        expectHttp(403) { api.saveWorker(body, YuvomiClient.newIdempotencyKey()) }
    }
}
