package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiOperation
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.documents.DocumentsOperation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** docs/spec/calendar.md, "Upstream operations". */
class CalendarApi(val client: YuvomiClient) {

    // ── Reads ──

    /** `GET /calendar?from&to`: events and expanded occurrences of the window. */
    suspend fun events(from: String, to: String): JsonElement =
        client.call(CalendarOperation.EVENTS_LIST, query = listOf("from" to from, "to" to to))

    suspend fun event(id: Int): CalendarEvent =
        client.call(CalendarOperation.EVENT_GET, mapOf("id" to id)).decodeData(CalendarEvent.serializer())

    /** `GET /calendar/search?q`: up to 100 hits and the total (#471). */
    suspend fun search(q: String): Pair<List<CalendarEvent>, Int> {
        val answer = client.call(CalendarOperation.EVENT_SEARCH, query = listOf("q" to q))
        val list = decodeEvents(answer)
        val total = ((answer as? JsonObject)?.get("total") as? JsonPrimitive)?.content?.toIntOrNull() ?: list.size
        return list to total
    }

    suspend fun holidays(from: String, to: String): JsonElement =
        client.call(CalendarOperation.HOLIDAYS, query = listOf("from" to from, "to" to to))

    /** The task layer: `GET /tasks?include_future=1` (core operation). */
    suspend fun tasks(): JsonElement = client.call(Operation.TASKS_LIST, query = listOf("include_future" to "1"))

    suspend fun schedule(from: String, to: String): JsonElement =
        client.call(CalendarOperation.SCHEDULE_ENTRIES, query = listOf("from" to from, "to" to to))

    suspend fun waste(from: String, to: String): JsonElement =
        client.call(CalendarOperation.WASTE_OCCURRENCES, query = listOf("from" to from, "to" to to))

    suspend fun calendars(): JsonElement = client.call(CalendarOperation.CALENDARS_LIST)

    suspend fun members(): JsonElement = client.call(Operation.FAMILY_MEMBERS)

    suspend fun preferences(): CalendarPrefs = client.call(Operation.PREFERENCES).decodeData(CalendarPrefs.serializer())

    suspend fun syncTargets(): SyncTargets = client.call(Operation.CALENDAR_SYNC_TARGETS).decodeData(SyncTargets.serializer())

    suspend fun reminders(eventId: Int): List<StoredReminder> =
        client.call(CalendarOperation.REMINDERS_ALL, query = reminderQuery(eventId)).decodeData(ListSerializer(StoredReminder.serializer()))

    // ── Event writes ──

    suspend fun create(body: JsonObject, idempotencyKey: String): CalendarEvent =
        client.call(CalendarOperation.EVENT_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(CalendarEvent.serializer())

    suspend fun update(id: Int, body: JsonObject): CalendarEvent =
        client.call(CalendarOperation.EVENT_UPDATE, mapOf("id" to id), body = body).decodeData(CalendarEvent.serializer())

    suspend fun delete(id: Int) {
        client.call(CalendarOperation.EVENT_DELETE, mapOf("id" to id))
    }

    suspend fun reset(id: Int) {
        client.call(CalendarOperation.EVENT_RESET, mapOf("id" to id))
    }

    suspend fun exception(seriesId: Int, body: Map<String, String>) {
        client.call(CalendarOperation.EVENT_EXCEPTION, mapOf("id" to seriesId), body = JsonObject(body.mapValues { JsonPrimitive(it.value) }))
    }

    /** `PUT .../occurrences/{recurrenceId}[/following]`. */
    suspend fun updateOccurrence(seriesId: Int, recurrenceId: String, following: Boolean, body: JsonObject): CalendarEvent {
        val op = if (following) CalendarOperation.FOLLOWING_UPDATE else CalendarOperation.OCCURRENCE_UPDATE
        return client.call(op, mapOf("seriesId" to seriesId, "recurrenceId" to recurrenceId), body = body).decodeData(CalendarEvent.serializer())
    }

    suspend fun deleteOccurrence(seriesId: Int, recurrenceId: String, following: Boolean) {
        val op = if (following) CalendarOperation.FOLLOWING_DELETE else CalendarOperation.OCCURRENCE_DELETE
        client.call(op, mapOf("seriesId" to seriesId, "recurrenceId" to recurrenceId))
    }

    suspend fun setReminders(eventId: Int, remindAts: List<String>) {
        client.call(CalendarOperation.REMINDERS_SET, query = reminderQuery(eventId), body = buildJsonObject {
            put("remind_ats", JsonArray(remindAts.map { JsonPrimitive(it) }))
        })
    }

    suspend fun clearReminders(eventId: Int) {
        client.call(CalendarOperation.REMINDERS_CLEAR, query = reminderQuery(eventId))
    }

    // ── Local calendars ──

    suspend fun createCalendar(name: String, color: String, idempotencyKey: String): LocalCalendar =
        client.call(CalendarOperation.CALENDAR_CREATE, body = buildJsonObject {
            put("name", name)
            put("color", color)
        }, idempotencyKey = idempotencyKey).decodeData(LocalCalendar.serializer())

    suspend fun updateCalendar(id: Int, changes: JsonObject): LocalCalendar =
        client.call(CalendarOperation.CALENDAR_UPDATE, mapOf("id" to id), body = changes).decodeData(LocalCalendar.serializer())

    suspend fun reorderCalendars(order: List<Int>) {
        client.call(CalendarOperation.CALENDARS_REORDER, body = buildJsonObject { put("order", JsonArray(order.map { JsonPrimitive(it) })) })
    }

    suspend fun deleteCalendar(id: Int) {
        client.call(CalendarOperation.CALENDAR_DELETE, mapOf("id" to id))
    }

    suspend fun enableFeed(id: Int) {
        client.call(CalendarOperation.CALENDAR_FEED_REGENERATE, mapOf("id" to id))
    }

    suspend fun disableFeed(id: Int) {
        client.call(CalendarOperation.CALENDAR_FEED_DELETE, mapOf("id" to id))
    }

    /** An attachment image through the session (`attachment_preview_url`). */
    suspend fun preview(documentId: Int): ByteArray = client.bytes(DocumentsOperation.DOCUMENT_PREVIEW.path(mapOf("id" to documentId)))

    private fun reminderQuery(eventId: Int) = listOf("entity_type" to "event", "entity_id" to eventId.toString())

    companion object {
        fun decodeEvents(answer: JsonElement): List<CalendarEvent> = answer.decodeData(ListSerializer(CalendarEvent.serializer()))
        fun decodeCalendars(answer: JsonElement): List<LocalCalendar> = answer.decodeData(ListSerializer(LocalCalendar.serializer()))
        fun decodeHolidays(answer: JsonElement): List<Holiday> = answer.decodeData(ListSerializer(Holiday.serializer()))
        fun decodeTasks(answer: JsonElement): List<CalendarTask> = answer.decodeData(ListSerializer(CalendarTask.serializer()))
        fun decodeMembers(answer: JsonElement): List<CalendarMember> = answer.decodeData(ListSerializer(CalendarMember.serializer()))
        fun decodeWaste(answer: JsonElement): List<WasteOccurrence> = answer.decodeData(ListSerializer(WasteOccurrence.serializer()))

        /** `{ data: { entries, warnings } }`. */
        fun decodeSchedule(answer: JsonElement): List<ScheduleEntry> {
            val data = (answer as? JsonObject)?.get("data") as? JsonObject ?: return emptyList()
            val entries = data["entries"] ?: return emptyList()
            return runCatching { entries.decode(ListSerializer(ScheduleEntry.serializer())) }.getOrDefault(emptyList())
        }

        fun encode(op: ApiOperation): String = op.signature
        val json = ApiJson
    }
}
