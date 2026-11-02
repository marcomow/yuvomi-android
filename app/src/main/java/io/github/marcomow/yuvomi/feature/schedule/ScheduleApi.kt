package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.documents.DocumentsOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** docs/spec/schedule.md, "Upstream operations". */
class ScheduleApi(private val client: YuvomiClient) {

    /** `load()`: everything the page reads at once, the pattern days afterwards. */
    suspend fun load(today: LocalDate): ScheduleData = coroutineScope {
        val users = async { client.call(DocumentsOperation.AUTH_USERS).decodeData(ListSerializer(Account.serializer())) }
        val types = async { shiftTypes() }
        val fields = async { client.call(ScheduleOperation.CUSTOM_FIELDS).decodeData(ListSerializer(CustomField.serializer())) }
        val patterns = async { client.call(ScheduleOperation.PATTERNS).decodeData(ListSerializer(Pattern.serializer())) }
        val overrides = async { client.call(ScheduleOperation.OVERRIDES).decodeData(ListSerializer(Override.serializer())) }
        val extras = async { client.call(ScheduleOperation.EXTRAS).decodeData(ListSerializer(Extra.serializer())) }
        val todayEntries = async { entries(today, today) }
        val prefs = async { client.call(ScheduleOperation.PREFERENCES).decodeData(MyPreferences.serializer()) }
        // Both are optional on the web (`.catch`): the page works without them.
        val household = async { runCatching { client.call(Operation.PREFERENCES).decodeData(HouseholdChoices.serializer()) }.getOrDefault(HouseholdChoices()) }
        val people = async { runCatching { client.call(ScheduleOperation.HOUSEHOLD_MEMBERS).decodeData(ListSerializer(Person.serializer())) }.getOrDefault(emptyList()) }
        val list = patterns.await()
        val days = list.map { p -> async { patternDays(p.id) } }.awaitAll()
        ScheduleData(
            users = users.await(),
            types = types.await(),
            customFields = fields.await(),
            patterns = list.mapIndexed { i, p -> p.copy(days = days[i]) },
            overrides = overrides.await(),
            extras = extras.await(),
            warnings = todayEntries.await().warnings,
            preferences = prefs.await(),
            household = household.await(),
            people = people.await(),
        )
    }

    suspend fun shiftTypes(): List<ShiftType> = client.call(ScheduleOperation.SHIFT_TYPES).decodeData(ListSerializer(ShiftType.serializer()))

    suspend fun patternDays(id: Int): List<PatternDay> =
        client.call(ScheduleOperation.PATTERN_DAYS, mapOf("id" to id)).decodeData(ListSerializer(PatternDay.serializer()))

    /** `GET /entries?from&to[&user_id]`, at most 731 days. */
    suspend fun entries(from: LocalDate, to: LocalDate, userId: Int? = null): Entries =
        client.call(ScheduleOperation.ENTRIES, query = buildList {
            add("from" to from.toString())
            add("to" to to.toString())
            if (userId != null) add("user_id" to userId.toString())
        }).decodeData(Entries.serializer())

    /** `refreshOverview`'s second call; optional like on the web. */
    suspend fun holidays(from: LocalDate, to: LocalDate): List<Holiday> =
        client.call(ScheduleOperation.HOLIDAYS, query = listOf("from" to from.toString(), "to" to to.toString()))
            .decodeData(ListSerializer(Holiday.serializer()))

    // ── Shift types and fields ──

    suspend fun createShiftType(body: JsonObject, idempotencyKey: String): ShiftType =
        client.call(ScheduleOperation.SHIFT_TYPE_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(ShiftType.serializer())

    suspend fun updateShiftType(id: Int, body: JsonObject): ShiftType =
        client.call(ScheduleOperation.SHIFT_TYPE_UPDATE, mapOf("id" to id), body = body).decodeData(ShiftType.serializer())

    suspend fun setShiftTypeFields(id: Int, fields: List<ScheduleBodies.AttachedField>): ShiftType =
        client.call(ScheduleOperation.SHIFT_TYPE_FIELDS, mapOf("id" to id), body = ScheduleBodies.typeFields(fields)).decodeData(ShiftType.serializer())

    suspend fun deleteShiftType(id: Int) {
        client.call(ScheduleOperation.SHIFT_TYPE_DELETE, mapOf("id" to id))
    }

    suspend fun createCustomField(name: String, idempotencyKey: String): CustomField =
        client.call(ScheduleOperation.CUSTOM_FIELD_CREATE, body = buildJsonObject { put("name", name.trim()) }, idempotencyKey = idempotencyKey)
            .decodeData(CustomField.serializer())

    suspend fun renameCustomField(id: Int, name: String): CustomField =
        client.call(ScheduleOperation.CUSTOM_FIELD_UPDATE, mapOf("id" to id), body = buildJsonObject { put("name", name.trim()) })
            .decodeData(CustomField.serializer())

    suspend fun deleteCustomField(id: Int) {
        client.call(ScheduleOperation.CUSTOM_FIELD_DELETE, mapOf("id" to id))
    }

    // ── Patterns ──

    suspend fun createPattern(body: JsonObject, idempotencyKey: String): Pattern =
        client.call(ScheduleOperation.PATTERN_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Pattern.serializer())

    suspend fun updatePattern(id: Int, body: JsonObject): Pattern =
        client.call(ScheduleOperation.PATTERN_UPDATE, mapOf("id" to id), body = body).decodeData(Pattern.serializer())

    suspend fun replacePatternDays(id: Int, days: List<ScheduleBodies.DayRow>): List<PatternDay> =
        client.call(ScheduleOperation.PATTERN_DAYS_REPLACE, mapOf("id" to id), body = ScheduleBodies.days(days))
            .decodeData(ListSerializer(PatternDay.serializer()))

    suspend fun deletePattern(id: Int) {
        client.call(ScheduleOperation.PATTERN_DELETE, mapOf("id" to id))
    }

    // ── Overrides ──

    suspend fun overrides(userId: Int? = null, from: String? = null, to: String? = null): List<Override> =
        client.call(ScheduleOperation.OVERRIDES, query = buildList {
            if (userId != null) add("user_id" to userId.toString())
            if (from != null) add("from" to from)
            if (to != null) add("to" to to)
        }).decodeData(ListSerializer(Override.serializer()))

    suspend fun putOverride(dateKey: String, body: JsonObject): Override =
        client.call(ScheduleOperation.OVERRIDE_PUT, mapOf("dateKey" to dateKey), body = body).decodeData(Override.serializer())

    /** Returns how many days were written (`updated`). */
    suspend fun fillOverrides(body: JsonObject, idempotencyKey: String): Int =
        intField(client.call(ScheduleOperation.OVERRIDES_FILL, body = body, idempotencyKey = idempotencyKey), "updated")

    /** Returns how many days were removed (`deleted`). */
    suspend fun deleteOverrides(userId: Int, from: String, to: String): Int =
        intField(client.call(ScheduleOperation.OVERRIDES_DELETE_RANGE, query = listOf("user_id" to userId.toString(), "from" to from, "to" to to)), "deleted")

    suspend fun deleteOverride(dateKey: String, userId: Int) {
        client.call(ScheduleOperation.OVERRIDE_DELETE, mapOf("dateKey" to dateKey), query = listOf("user_id" to userId.toString()))
    }

    // ── Extras ──

    suspend fun extras(userId: Int? = null, from: String? = null, to: String? = null): List<Extra> =
        client.call(ScheduleOperation.EXTRAS, query = buildList {
            if (userId != null) add("user_id" to userId.toString())
            if (from != null) add("from" to from)
            if (to != null) add("to" to to)
        }).decodeData(ListSerializer(Extra.serializer()))

    suspend fun createExtra(body: JsonObject, idempotencyKey: String): Extra =
        client.call(ScheduleOperation.EXTRA_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Extra.serializer())

    /** Returns how many extras were created (`created`). */
    suspend fun fillExtras(body: JsonObject, idempotencyKey: String): Int =
        intField(client.call(ScheduleOperation.EXTRAS_FILL, body = body, idempotencyKey = idempotencyKey), "created")

    suspend fun updateExtra(id: Int, body: JsonObject): Extra =
        client.call(ScheduleOperation.EXTRA_UPDATE, mapOf("id" to id), body = body).decodeData(Extra.serializer())

    suspend fun deleteExtra(id: Int) {
        client.call(ScheduleOperation.EXTRA_DELETE, mapOf("id" to id))
    }

    // ── The member's own: feed and preferences ──

    /** null when the feed is off. */
    suspend fun feed(): Feed? = feedOf(client.call(ScheduleOperation.FEED))

    suspend fun regenerateFeed(idempotencyKey: String): Feed? = feedOf(client.call(ScheduleOperation.FEED_REGENERATE, idempotencyKey = idempotencyKey))

    suspend fun disableFeed() {
        client.call(ScheduleOperation.FEED_DISABLE)
    }

    suspend fun preferences(): MyPreferences = client.call(ScheduleOperation.PREFERENCES).decodeData(MyPreferences.serializer())

    private fun feedOf(answer: JsonElement): Feed? {
        val data = (answer as? JsonObject)?.get("data")
        if (data == null || data is JsonNull) return null
        return data.decode(Feed.serializer()).takeIf { it.url.isNotEmpty() }
    }

    private fun intField(answer: JsonElement, name: String): Int =
        (((answer as? JsonObject)?.get("data") as? JsonObject)?.get(name) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    companion object {
        fun encode(data: ScheduleData): String = ApiJson.encodeToString(ScheduleData.serializer(), data)
        fun decode(text: String): ScheduleData = ApiJson.decodeFromString(ScheduleData.serializer(), text)
        fun encodeEntries(entries: Entries): String = ApiJson.encodeToString(Entries.serializer(), entries)
        fun decodeEntries(text: String): Entries = ApiJson.decodeFromString(Entries.serializer(), text)
    }
}

/** Request bodies, as the page sends them (`saveCreatedSchedule`, `saveShiftTypeEdit`, `savePatternEdit`). */
object ScheduleBodies {
    data class AttachedField(val customFieldId: Int, val showInOverlay: Boolean)
    data class DayRow(val position: Int, val shiftTypeId: Int?, val fieldValues: Map<String, String> = emptyMap())

    private fun putNullable(key: String, value: String?, into: MutableMap<String, JsonElement>) {
        into[key] = value?.let(::JsonPrimitive) ?: JsonNull
    }

    fun fieldValues(values: Map<String, String>): JsonObject =
        JsonObject(values.mapValues { JsonPrimitive(it.value.trim()) }.filterValues { it.content.isNotEmpty() })

    /** The shift type form: name, code, colour, icon, and both times or neither. */
    fun shiftType(name: String, shortCode: String, color: String, icon: String?, start: String?, end: String?): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map["name"] = JsonPrimitive(name.trim())
        map["short_code"] = JsonPrimitive(shortCode.trim())
        map["color"] = JsonPrimitive(color)
        putNullable("icon", icon?.ifEmpty { null }, map)
        putNullable("start_time", start?.ifEmpty { null }, map)
        putNullable("end_time", end?.ifEmpty { null }, map)
        return JsonObject(map)
    }

    fun typeFields(fields: List<AttachedField>): JsonObject = buildJsonObject {
        put("fields", buildJsonArray {
            fields.forEachIndexed { i, f ->
                add(buildJsonObject {
                    put("custom_field_id", f.customFieldId)
                    put("position", i)
                    put("show_in_overlay", f.showInOverlay)
                })
            }
        })
    }

    /** The pattern form: name, start, length, active, and the optional window. */
    fun pattern(userId: Int?, name: String, anchor: String, cycleLength: Int, active: Boolean, validFrom: String?, validUntil: String?): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        if (userId != null) map["user_id"] = JsonPrimitive(userId)
        map["name"] = JsonPrimitive(name.trim())
        map["anchor_date"] = JsonPrimitive(anchor)
        map["cycle_length"] = JsonPrimitive(cycleLength)
        map["is_active"] = JsonPrimitive(active)
        putNullable("valid_from", validFrom?.ifEmpty { null }, map)
        putNullable("valid_until", validUntil?.ifEmpty { null }, map)
        return JsonObject(map)
    }

    fun days(days: List<DayRow>): JsonObject = buildJsonObject {
        put("days", JsonArray(days.map { d ->
            buildJsonObject {
                put("position", d.position)
                if (d.shiftTypeId == null) put("shift_type_id", JsonNull) else put("shift_type_id", d.shiftTypeId)
                put("field_values", fieldValues(d.fieldValues))
            }
        }))
    }

    /** One override day, or (with [from]/[to]) a fill. */
    fun override(userId: Int, shiftTypeId: Int?, note: String, fieldValues: Map<String, String>, from: String? = null, to: String? = null): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map["user_id"] = JsonPrimitive(userId)
        if (from != null) map["from"] = JsonPrimitive(from)
        if (to != null) map["to"] = JsonPrimitive(to)
        map["shift_type_id"] = shiftTypeId?.let(::JsonPrimitive) ?: JsonNull
        map["note"] = JsonPrimitive(note)
        map["field_values"] = fieldValues(fieldValues)
        return JsonObject(map)
    }

    /** One extra (with [dateKey]) or a fill (with [from]/[to]). */
    fun extra(
        userId: Int,
        shiftTypeId: Int,
        note: String,
        reminderOffsetMinutes: Int?,
        fieldValues: Map<String, String>,
        dateKey: String? = null,
        from: String? = null,
        to: String? = null,
    ): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map["user_id"] = JsonPrimitive(userId)
        map["shift_type_id"] = JsonPrimitive(shiftTypeId)
        map["note"] = JsonPrimitive(note)
        map["reminder_offset_minutes"] = reminderOffsetMinutes?.let(::JsonPrimitive) ?: JsonNull
        map["field_values"] = fieldValues(fieldValues)
        if (dateKey != null) map["date_key"] = JsonPrimitive(dateKey)
        if (from != null) map["from"] = JsonPrimitive(from)
        if (to != null) map["to"] = JsonPrimitive(to)
        return JsonObject(map)
    }
}
