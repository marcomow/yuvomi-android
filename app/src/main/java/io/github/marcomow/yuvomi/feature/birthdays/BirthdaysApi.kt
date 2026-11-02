package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** docs/spec/birthdays.md, "Upstream operations". */
class BirthdaysApi(private val client: YuvomiClient) {

    suspend fun list(): JsonElement = client.call(BirthdaysOperation.LIST)

    suspend fun create(body: JsonObject, idempotencyKey: String): Birthday =
        client.call(BirthdaysOperation.CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Birthday.serializer())

    /** Partial: a missing field keeps its stored value (`PUT /birthdays/:id`). */
    suspend fun update(id: Int, body: JsonObject): Birthday =
        client.call(BirthdaysOperation.UPDATE, mapOf("id" to id), body = body).decodeData(Birthday.serializer())

    suspend fun link(id: Int, contactId: Int?): Birthday =
        update(id, buildJsonObject { put("contact_id", contactId?.let(::JsonPrimitive) ?: JsonNull) })

    suspend fun delete(id: Int) {
        client.call(BirthdaysOperation.DELETE, mapOf("id" to id))
    }

    suspend fun candidates(): ImportCandidates =
        client.call(BirthdaysOperation.IMPORT_CANDIDATES).decodeData(ImportCandidates.serializer())

    suspend fun import(contactIds: List<Int>, idempotencyKey: String): ImportResult =
        client.call(BirthdaysOperation.IMPORT, body = buildJsonObject {
            put("contact_ids", buildJsonArray { contactIds.forEach { add(JsonPrimitive(it)) } })
        }, idempotencyKey = idempotencyKey).decodeData(ImportResult.serializer())

    /** null: the feed is off. */
    suspend fun feed(): BirthdayFeed? {
        val data = (client.call(BirthdaysOperation.FEED) as? JsonObject)?.get("data")
        return if (data == null || data is JsonNull) null else data.let { ApiJson.decodeFromJsonElement(BirthdayFeed.serializer(), it) }
    }

    /** Turns the feed on, or replaces its address (the old one stops working). */
    suspend fun regenerateFeed(): BirthdayFeed = client.call(BirthdaysOperation.FEED_REGENERATE).decodeData(BirthdayFeed.serializer())

    suspend fun disableFeed() {
        client.call(BirthdaysOperation.FEED_DISABLE)
    }

    companion object {
        fun decodeList(json: JsonElement): List<Birthday> = json.decodeData(ListSerializer(Birthday.serializer()))
    }
}
