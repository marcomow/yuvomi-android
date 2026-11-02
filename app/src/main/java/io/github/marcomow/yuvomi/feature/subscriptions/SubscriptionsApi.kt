package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The list filters the server applies (`loadOnce()`): search, category, payment method, status. */
data class SubscriptionQuery(
    val q: String = "",
    val categoryId: Int? = null,
    val paymentMethodId: Int? = null,
    val status: String = "all",
    val refreshRates: Boolean = false,
) {
    fun params(): List<Pair<String, String>> = buildList {
        if (q.isNotBlank()) add("q" to q.trim())
        categoryId?.let { add("category_id" to it.toString()) }
        paymentMethodId?.let { add("payment_method_id" to it.toString()) }
        if (status != "all") add("status" to status)
        if (refreshRates) add("refresh_rates" to "true")
    }
}

/** `POST /:id/renew` answers the subscription and the undo token of this payment. */
data class Renewal(val subscription: Subscription, val undoToken: String?)

@Serializable
private data class LogoAnswer(val options: List<LogoOption> = emptyList())

/** docs/spec/subscriptions.md, "Upstream operations". */
class SubscriptionsApi(private val client: YuvomiClient) {

    suspend fun list(query: SubscriptionQuery): JsonElement = client.call(SubscriptionsOperation.LIST, query = query.params())
    suspend fun meta(): JsonElement = client.call(SubscriptionsOperation.META)
    suspend fun settings(): JsonElement = client.call(SubscriptionsOperation.SETTINGS)

    suspend fun create(body: JsonObject, idempotencyKey: String): Subscription =
        client.call(SubscriptionsOperation.CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Subscription.serializer())

    suspend fun update(id: Int, body: JsonObject): Subscription =
        client.call(SubscriptionsOperation.UPDATE, mapOf("id" to id), body = body).decodeData(Subscription.serializer())

    suspend fun delete(id: Int) {
        client.call(SubscriptionsOperation.DELETE, mapOf("id" to id))
    }

    suspend fun renew(id: Int, idempotencyKey: String): Renewal {
        val answer = client.call(SubscriptionsOperation.RENEW, mapOf("id" to id), body = JsonObject(emptyMap()), idempotencyKey = idempotencyKey)
        val token = ((answer as? JsonObject)?.get("undo") as? JsonObject)?.get("token") as? JsonPrimitive
        return Renewal(answer.decodeData(Subscription.serializer()), token?.contentOrNull)
    }

    suspend fun undoRenew(id: Int, token: String, idempotencyKey: String): Subscription =
        client.call(SubscriptionsOperation.RENEW_UNDO, mapOf("id" to id), body = buildJsonObject { put("token", token) }, idempotencyKey = idempotencyKey)
            .decodeData(Subscription.serializer())

    suspend fun saveSettings(monthlyBudget: Double, baseCurrency: String): SubscriptionSettings =
        client.call(SubscriptionsOperation.SETTINGS_UPDATE, body = buildJsonObject {
            put("monthly_budget", monthlyBudget)
            put("base_currency", baseCurrency)
        }).decodeData(SubscriptionSettings.serializer())

    suspend fun createCategory(name: String, color: String, idempotencyKey: String): SubscriptionCategory =
        client.call(SubscriptionsOperation.CATEGORY_CREATE, body = buildJsonObject {
            put("name", name)
            put("color", color)
        }, idempotencyKey = idempotencyKey).decodeData(SubscriptionCategory.serializer())

    suspend fun updateCategory(id: Int, name: String, color: String?): SubscriptionCategory =
        client.call(SubscriptionsOperation.CATEGORY_UPDATE, mapOf("id" to id), body = buildJsonObject {
            put("name", name)
            if (color != null) put("color", color)
        }).decodeData(SubscriptionCategory.serializer())

    /** Answers `{ deleted, affected }`. */
    suspend fun deleteCategory(id: Int) {
        client.call(SubscriptionsOperation.CATEGORY_DELETE, mapOf("id" to id))
    }

    suspend fun createMethod(name: String, idempotencyKey: String): PaymentMethod =
        client.call(SubscriptionsOperation.METHOD_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = idempotencyKey)
            .decodeData(PaymentMethod.serializer())

    suspend fun updateMethod(id: Int, name: String): PaymentMethod =
        client.call(SubscriptionsOperation.METHOD_UPDATE, mapOf("id" to id), body = buildJsonObject { put("name", name) })
            .decodeData(PaymentMethod.serializer())

    suspend fun deleteMethod(id: Int) {
        client.call(SubscriptionsOperation.METHOD_DELETE, mapOf("id" to id))
    }

    /** `PUT /meta/order` with `{ categories: [...] }` or `{ payment_methods: [...] }`. */
    suspend fun reorder(key: String, ids: List<Int>) {
        client.call(SubscriptionsOperation.META_ORDER, body = buildJsonObject { put(key, JsonArray(ids.map { JsonPrimitive(it) })) })
    }

    /** The logos the server found for a site or name; a 404 `subscriptions_no_logo_found` when none. */
    suspend fun logoSearch(query: String, idempotencyKey: String): List<String> =
        client.call(SubscriptionsOperation.LOGO_SEARCH, body = buildJsonObject { put("query", query) }, idempotencyKey = idempotencyKey)
            .decodeData(LogoAnswer.serializer()).options.map { it.logoData }.filter { it.isNotEmpty() }

    companion object {
        fun decodeList(answer: JsonElement): SubscriptionList = answer.decodeData(SubscriptionList.serializer())
        fun decodeMeta(answer: JsonElement): SubscriptionMeta = answer.decodeData(SubscriptionMeta.serializer())
        fun decodeSettings(answer: JsonElement): SubscriptionSettings = answer.decodeData(SubscriptionSettings.serializer())
        fun decodeSubscriptions(answer: JsonElement): List<Subscription> =
            (answer.jsonObject["data"] as? JsonObject)?.get("subscriptions")?.let {
                io.github.marcomow.yuvomi.core.api.ApiJson.decodeFromJsonElement(ListSerializer(Subscription.serializer()), it)
            }.orEmpty()
    }
}
