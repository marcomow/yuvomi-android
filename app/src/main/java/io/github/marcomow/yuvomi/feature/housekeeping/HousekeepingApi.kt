package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.documents.DocumentsOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** docs/spec/housekeeping.md, "Upstream operations". [zone] is the household's day zone (`dayTimeZone()`). */
class HousekeepingApi(private val client: YuvomiClient, private val zone: String) {

    private val day = listOf("timezone" to zone)

    /** `loadData()`: the seven reads in parallel; the report month is read separately. */
    suspend fun load(): HousekeepingData = coroutineScope {
        val dashboard = async { client.call(HousekeepingOperation.DASHBOARD, query = day).decodeData(Dashboard.serializer()) }
        val tasks = async { client.call(HousekeepingOperation.TASKS).decodeData(ListSerializer(DecayTask.serializer())) }
        val current = async { visits(null, null) }
        val templates = async { client.call(HousekeepingOperation.TASK_TEMPLATES).decodeData(ListSerializer(TaskTemplate.serializer())) }
        val workers = async { client.call(HousekeepingOperation.WORKERS, query = day).decode(WorkersAnswer.serializer()) }
        val currency = async { currency() }
        val w = workers.await()
        HousekeepingData(
            dashboard = dashboard.await(),
            tasks = tasks.await(),
            current = current.await(),
            templates = templates.await(),
            workers = w.data,
            canManage = w.canManage,
            currency = currency.await(),
        )
    }

    /** `prefs.data?.currency ?? 'EUR'`. */
    suspend fun currency(): String {
        val data = client.call(Operation.PREFERENCES).jsonObject["data"] as? JsonObject
        return (data?.get("currency") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "EUR"
    }

    /** `GET /visits?month=&worker_id=`; without a month the server answers the current one. */
    suspend fun visits(month: String?, workerId: Int?): VisitReport {
        val query = buildList {
            if (month != null) add("month" to month)
            if (workerId != null) add("worker_id" to workerId.toString())
        }
        return client.call(HousekeepingOperation.VISITS, query = query).decodeData(VisitReport.serializer())
    }

    suspend fun visit(id: Int): Visit = client.call(HousekeepingOperation.VISIT_GET, mapOf("id" to id)).decodeData(Visit.serializer())

    // ── Sessions (toggleSession) ──

    suspend fun checkIn(body: JsonObject, idempotencyKey: String): Visit =
        client.call(HousekeepingOperation.CHECK_IN, body = body, idempotencyKey = idempotencyKey).decodeData(Visit.serializer())

    suspend fun checkOut(workerId: Int, idempotencyKey: String): Visit =
        client.call(HousekeepingOperation.CHECK_OUT, body = buildJsonObject { put("worker_id", workerId) }, idempotencyKey = idempotencyKey)
            .decodeData(Visit.serializer())

    suspend fun reopen(sessionId: Int, idempotencyKey: String): Visit =
        client.call(HousekeepingOperation.REOPEN, mapOf("id" to sessionId), idempotencyKey = idempotencyKey).decodeData(Visit.serializer())

    // ── Visits ──

    suspend fun updateVisit(id: Int, body: JsonObject): Visit =
        client.call(HousekeepingOperation.VISIT_UPDATE, mapOf("id" to id), body = body).decodeData(Visit.serializer())

    suspend fun deleteVisit(id: Int) {
        client.call(HousekeepingOperation.VISIT_DELETE, mapOf("id" to id))
    }

    suspend fun pay(id: Int, idempotencyKey: String): Visit =
        client.call(HousekeepingOperation.VISIT_PAY, mapOf("id" to id), idempotencyKey = idempotencyKey).decodeData(Visit.serializer())

    suspend fun unpay(id: Int, idempotencyKey: String): Visit =
        client.call(HousekeepingOperation.VISIT_UNPAY, mapOf("id" to id), idempotencyKey = idempotencyKey).decodeData(Visit.serializer())

    /** The receipt goes to Documents (`POST /documents`); returns the new document's id. */
    suspend fun uploadReceipt(name: String, description: String, folderName: String, fileName: String, dataUrl: String, idempotencyKey: String): Int? {
        val body = buildJsonObject {
            put("name", name)
            put("description", description)
            put("category", "finance")
            put("visibility", "family")
            put("status", "active")
            put("allowed_member_ids", buildJsonArray { })
            put("original_name", fileName)
            put("content_data", dataUrl)
            put("folder_key", "housekeeping")
            put("folder_name", folderName)
        }
        val answer = client.call(DocumentsOperation.DOCUMENT_CREATE, body = body, idempotencyKey = idempotencyKey)
        val data = answer.jsonObject["data"] as? JsonObject
        return (data?.get("id") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

    // ── Chores (decay tasks) ──

    suspend fun createTask(name: String, area: String, frequencyDays: Int, idempotencyKey: String): DecayTask =
        client.call(HousekeepingOperation.TASK_CREATE, body = taskBody(name, area, frequencyDays), idempotencyKey = idempotencyKey)
            .decodeData(DecayTask.serializer())

    suspend fun updateTask(id: Int, name: String, area: String, frequencyDays: Int): DecayTask =
        client.call(HousekeepingOperation.TASK_UPDATE, mapOf("taskId" to id), body = taskBody(name, area, frequencyDays))
            .decodeData(DecayTask.serializer())

    /** The undo of a completion: `last_completed` back to what it was, `null` for never (`undoCompleteTask`). */
    suspend fun restoreCompletion(id: Int, previous: String?): DecayTask =
        client.call(HousekeepingOperation.TASK_UPDATE, mapOf("taskId" to id), body = buildJsonObject { put("last_completed", previous?.let(::JsonPrimitive) ?: JsonNull) })
            .decodeData(DecayTask.serializer())

    suspend fun completeTask(id: Int, idempotencyKey: String): DecayTask =
        client.call(HousekeepingOperation.TASK_COMPLETE, mapOf("taskId" to id), idempotencyKey = idempotencyKey).decodeData(DecayTask.serializer())

    suspend fun deleteTask(id: Int) {
        client.call(HousekeepingOperation.TASK_DELETE, mapOf("taskId" to id))
    }

    // ── Profiles ──

    suspend fun saveWorker(body: JsonObject, idempotencyKey: String): Worker =
        client.call(HousekeepingOperation.WORKER_SAVE, body = body, idempotencyKey = idempotencyKey).decodeData(Worker.serializer())

    private fun taskBody(name: String, area: String, frequencyDays: Int) = buildJsonObject {
        put("name", name)
        put("area", area)
        put("frequency_days", frequencyDays)
    }

    companion object {
        fun decodeData(json: String): HousekeepingData =
            io.github.marcomow.yuvomi.core.api.ApiJson.decodeFromString(HousekeepingData.serializer(), json)

        fun encodeData(data: HousekeepingData): String =
            io.github.marcomow.yuvomi.core.api.ApiJson.encodeToString(HousekeepingData.serializer(), data)
    }
}
