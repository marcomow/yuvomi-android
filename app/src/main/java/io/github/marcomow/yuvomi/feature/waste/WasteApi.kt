package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.calendar.CalendarOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** docs/spec/waste.md, "Upstream operations". */
class WasteApi(private val client: YuvomiClient) {

    /** `loadData()`: types with the archived ones, schedules, the next 90 days, sources. */
    suspend fun load(from: String, to: String): WasteData = coroutineScope {
        val types = async { client.call(WasteOperation.TYPES, query = listOf("include_archived" to "1")).decodeData(ListSerializer(WasteType.serializer())) }
        val schedules = async { client.call(WasteOperation.SCHEDULES).decodeData(ListSerializer(WasteSchedule.serializer())) }
        val occurrences = async { occurrences(from, to) }
        val sources = async { client.call(WasteOperation.SOURCES).decodeData(ListSerializer(WasteSource.serializer())) }
        WasteData(types.await(), schedules.await(), occurrences.await(), sources.await())
    }

    suspend fun occurrences(from: String, to: String): List<WasteOccurrence> =
        client.call(CalendarOperation.WASTE_OCCURRENCES, query = listOf("from" to from, "to" to to))
            .decodeData(ListSerializer(WasteOccurrence.serializer()))

    // ── Types ──

    suspend fun createType(name: String, icon: String, color: String, key: String): WasteType =
        client.call(WasteOperation.TYPE_CREATE, body = typeBody(name, icon, color), idempotencyKey = key).decodeData(WasteType.serializer())

    suspend fun updateType(id: Int, body: JsonObject): WasteType =
        client.call(WasteOperation.TYPE_UPDATE, mapOf("id" to id), body = body).decodeData(WasteType.serializer())

    suspend fun deleteType(id: Int) {
        client.call(WasteOperation.TYPE_DELETE, mapOf("id" to id))
    }

    // ── Schedules and their overrides ──

    suspend fun createSchedule(body: JsonObject, key: String): WasteSchedule =
        client.call(WasteOperation.SCHEDULE_CREATE, body = body, idempotencyKey = key).decodeData(WasteSchedule.serializer())

    suspend fun updateSchedule(id: Int, body: JsonObject): WasteSchedule =
        client.call(WasteOperation.SCHEDULE_UPDATE, mapOf("id" to id), body = body).decodeData(WasteSchedule.serializer())

    suspend fun deleteSchedule(id: Int) {
        client.call(WasteOperation.SCHEDULE_DELETE, mapOf("id" to id))
    }

    /** Move ([replacement] a date) or skip ([replacement] null) one occurrence of a series. */
    suspend fun setOverride(scheduleId: Int, originalDate: String, replacement: String?) {
        client.call(
            WasteOperation.OVERRIDE_SET,
            mapOf("id" to scheduleId, "originalDate" to originalDate),
            body = buildJsonObject { put("replacement_date", replacement?.let(::JsonPrimitive) ?: JsonNull) },
        )
    }

    suspend fun deleteOverride(scheduleId: Int, originalDate: String) {
        client.call(WasteOperation.OVERRIDE_DELETE, mapOf("id" to scheduleId, "originalDate" to originalDate))
    }

    // ── One-off pickups ──

    suspend fun pickup(id: Int): WastePickup = client.call(WasteOperation.PICKUP_GET, mapOf("id" to id)).decodeData(WastePickup.serializer())

    suspend fun createPickup(body: JsonObject, key: String): WastePickup =
        client.call(WasteOperation.PICKUP_CREATE, body = body, idempotencyKey = key).decodeData(WastePickup.serializer())

    suspend fun updatePickup(id: Int, body: JsonObject): WastePickup =
        client.call(WasteOperation.PICKUP_UPDATE, mapOf("id" to id), body = body).decodeData(WastePickup.serializer())

    suspend fun deletePickup(id: Int) {
        client.call(WasteOperation.PICKUP_DELETE, mapOf("id" to id))
    }

    // ── Sources and import ──

    suspend fun source(id: Int): WasteSource = client.call(WasteOperation.SOURCE_GET, mapOf("id" to id)).decodeData(WasteSource.serializer())

    suspend fun renameSource(id: Int, name: String) {
        client.call(WasteOperation.SOURCE_RENAME, mapOf("id" to id), body = buildJsonObject { put("name", name) })
    }

    suspend fun deleteSource(id: Int) {
        client.call(WasteOperation.SOURCE_DELETE, mapOf("id" to id))
    }

    suspend fun refreshSource(id: Int, key: String): SourceOutcome =
        client.call(WasteOperation.SOURCE_REFRESH, mapOf("id" to id), idempotencyKey = key).decodeData(SourceOutcome.serializer())

    suspend fun createUrlSource(name: String, url: String, interval: Int, key: String): SourceOutcome =
        client.call(
            WasteOperation.SOURCE_CREATE,
            body = buildJsonObject { put("name", name); put("url", url); put("refresh_interval_minutes", interval) },
            idempotencyKey = key,
        ).decodeData(SourceOutcome.serializer())

    /** `{ type_ids }` or `{ ignored: true }` for an empty set. */
    suspend fun updateMapping(sourceId: Int, mappingId: Int, typeIds: List<Int>) {
        val body = if (typeIds.isEmpty()) buildJsonObject { put("ignored", true) }
        else buildJsonObject { put("type_ids", buildJsonArray { typeIds.forEach { add(JsonPrimitive(it)) } }) }
        client.call(WasteOperation.MAPPING_UPDATE, mapOf("id" to sourceId, "mappingId" to mappingId), body = body)
    }

    /** A new file import ([sourceId] null) or a re-import; a URL source sends no `ics`. */
    suspend fun preview(sourceId: Int?, ics: String?, key: String): ImportPreview {
        val body = buildJsonObject { if (ics != null) put("ics", ics) }
        val answer = if (sourceId == null) client.call(WasteOperation.IMPORT_PREVIEW, body = body, idempotencyKey = key)
        else client.call(WasteOperation.REIMPORT_PREVIEW, mapOf("id" to sourceId), body = body, idempotencyKey = key)
        return answer.decodeData(ImportPreview.serializer())
    }

    suspend fun commit(sourceId: Int?, body: JsonObject, key: String): SourceOutcome {
        val answer = if (sourceId == null) client.call(WasteOperation.IMPORT_COMMIT, body = body, idempotencyKey = key)
        else client.call(WasteOperation.REIMPORT_COMMIT, mapOf("id" to sourceId), body = body, idempotencyKey = key)
        return answer.decodeData(SourceOutcome.serializer())
    }

    /** The profile as the server hands it out, kept as JSON (`{ version, source_name, mappings }`). */
    suspend fun exportProfile(sourceId: Int): JsonElement =
        client.call(WasteOperation.PROFILE_EXPORT, mapOf("id" to sourceId)).jsonObject["data"] ?: JsonNull

    suspend fun previewProfile(sourceId: Int, profile: JsonElement, key: String): ProfilePreview =
        client.call(WasteOperation.PROFILE_IMPORT_PREVIEW, mapOf("id" to sourceId), body = buildJsonObject { put("profile", profile) }, idempotencyKey = key)
            .decodeData(ProfilePreview.serializer())

    suspend fun commitProfile(sourceId: Int, profile: JsonElement, digest: String?, key: String) {
        client.call(
            WasteOperation.PROFILE_IMPORT_COMMIT,
            mapOf("id" to sourceId),
            body = buildJsonObject { put("profile", profile); put("profile_digest", digest?.let(::JsonPrimitive) ?: JsonNull) },
            idempotencyKey = key,
        )
    }

    // ── Per-member reminders and feed ──

    suspend fun reminderSettings(): List<ReminderSetting> =
        client.call(WasteOperation.REMINDER_SETTINGS).decodeData(ListSerializer(ReminderSetting.serializer()))

    suspend fun updateReminder(setting: ReminderSetting) {
        client.call(
            WasteOperation.REMINDER_SETTING_UPDATE,
            mapOf("typeId" to setting.typeId),
            body = buildJsonObject {
                put("enabled", setting.enabled)
                put("offset_days", setting.offsetDays)
                put("delivery_time", setting.deliveryTime)
            },
        )
    }

    /** The member's own feed, or null when it is off. */
    suspend fun feed(): WasteFeed? {
        val data = client.call(WasteOperation.FEED).jsonObject["data"]
        return if (data == null || data is JsonNull) null else data.decode(WasteFeed.serializer())
    }

    companion object {
        fun typeBody(name: String, icon: String, color: String) = buildJsonObject {
            put("name", name)
            put("icon", icon)
            put("color", color)
        }

        fun decodeData(json: String): WasteData = ApiJson.decodeFromString(WasteData.serializer(), json)

        fun encodeData(data: WasteData): String = ApiJson.encodeToString(WasteData.serializer(), data)
    }
}
