package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.meals.MealsOperation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** docs/spec/budget.md, "Upstream operations". */
class BudgetApi(private val client: YuvomiClient) {

    /** `scope` only counts in the personal mode (`budgetFilter`); the shared mode ignores it. */
    data class View(val personal: Boolean, val scope: String, val accountId: Int? = null) {
        fun query(): List<Pair<String, String>> = buildList {
            accountId?.let { add("account_id" to it.toString()) }
            if (personal) add("scope" to scope)
        }
    }

    /** The month as `loadMonth` reads it: entries and both summaries in one go. */
    data class Month(val entries: List<BudgetEntry>, val summary: BudgetSummary, val previous: BudgetSummary?)

    suspend fun entries(month: String, view: View): List<BudgetEntry> =
        client.call(BudgetOperation.ENTRIES_LIST, query = listOf("month" to month) + view.query())
            .decodeData(ListSerializer(BudgetEntry.serializer()))

    suspend fun entriesRaw(month: String, view: View): JsonElement =
        client.call(BudgetOperation.ENTRIES_LIST, query = listOf("month" to month) + view.query())

    suspend fun summaryRaw(month: String, view: View): JsonElement =
        client.call(BudgetOperation.SUMMARY, query = listOf("month" to month) + view.query())

    data class Search(val entries: List<BudgetEntry>, val truncated: Boolean)

    /** `GET /budget?q=`: all months, newest 200 (`searchEntries`). */
    suspend fun search(query: String, view: View): Search {
        val answer = client.call(BudgetOperation.ENTRIES_LIST, query = listOf("q" to query) + view.query())
        val truncated = ((answer as? JsonObject)?.get("meta") as? JsonObject)?.get("truncated")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
        return Search(answer.decodeData(ListSerializer(BudgetEntry.serializer())), truncated)
    }

    /** `GET /budget?loan_id=`: the entries of a loan's payments. */
    suspend fun loanEntries(loanId: Int): List<BudgetEntry> =
        client.call(BudgetOperation.ENTRIES_LIST, query = listOf("loan_id" to loanId.toString()))
            .decodeData(ListSerializer(BudgetEntry.serializer()))

    suspend fun create(body: JsonObject, idempotencyKey: String): BudgetEntry =
        client.call(BudgetOperation.ENTRY_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(BudgetEntry.serializer())

    suspend fun update(id: Int, body: JsonObject): BudgetEntry =
        client.call(BudgetOperation.ENTRY_UPDATE, mapOf("id" to id), body = body).decodeData(BudgetEntry.serializer())

    suspend fun updateSeries(id: Int, body: JsonObject) {
        client.call(BudgetOperation.SERIES_UPDATE, mapOf("id" to id), body = body)
    }

    suspend fun delete(id: Int) {
        client.call(BudgetOperation.ENTRY_DELETE, mapOf("id" to id))
    }

    suspend fun deleteSeries(id: Int) {
        client.call(BudgetOperation.SERIES_DELETE, mapOf("id" to id))
    }

    /** `PATCH /budget/{id}/confirm`: books an expected entry with the amount and date it really had. */
    suspend fun confirm(id: Int, amount: Double, date: String?) {
        client.call(BudgetOperation.ENTRY_CONFIRM, mapOf("id" to id), body = buildJsonObject {
            put("amount", amount)
            if (!date.isNullOrBlank()) put("date", date)
        })
    }

    /** The relative URL of the CSV export (`/api/v1/budget/export?...`), for [YuvomiClient.bytes]. */
    fun exportUrl(query: List<Pair<String, String>>): String =
        BudgetOperation.EXPORT.path() + "?" + query.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }

    suspend fun export(query: List<Pair<String, String>>): ByteArray = client.bytes(exportUrl(query))

    suspend fun meta(): BudgetMeta = client.call(BudgetOperation.META).decodeData(BudgetMeta.serializer())

    suspend fun metaRaw(): JsonElement = client.call(BudgetOperation.META)

    // ── Categories (category-manager.js with basePath /budget/categories) ──

    suspend fun categories(lang: String): List<BudgetCategory> =
        client.call(BudgetOperation.CATEGORIES_LIST, query = listOf("lang" to lang)).decodeData(ListSerializer(BudgetCategory.serializer()))

    suspend fun createCategory(name: String, type: String, idempotencyKey: String): BudgetCategory =
        client.call(BudgetOperation.CATEGORY_CREATE, body = buildJsonObject { put("name", name); put("type", type) }, idempotencyKey = idempotencyKey)
            .decodeData(BudgetCategory.serializer())

    suspend fun renameCategory(key: String, name: String) {
        client.call(BudgetOperation.CATEGORY_UPDATE, mapOf("key" to key), body = buildJsonObject { put("name", name) })
    }

    suspend fun deleteCategory(key: String) {
        client.call(BudgetOperation.CATEGORY_DELETE, mapOf("key" to key))
    }

    suspend fun reorderCategories(type: String, order: List<String>) {
        client.call(BudgetOperation.CATEGORIES_REORDER, body = buildJsonObject {
            put("order", JsonArray(order.map(::JsonPrimitive)))
            put("type", type)
        })
    }

    suspend fun createSubcategory(category: String, name: String, idempotencyKey: String): BudgetSubcategory =
        client.call(BudgetOperation.SUBCATEGORY_CREATE, mapOf("categoryKey" to category), body = buildJsonObject { put("name", name) }, idempotencyKey = idempotencyKey)
            .decodeData(BudgetSubcategory.serializer())

    suspend fun renameSubcategory(category: String, key: String, name: String) {
        client.call(BudgetOperation.SUBCATEGORY_UPDATE, mapOf("key" to category, "subKey" to key), body = buildJsonObject { put("name", name) })
    }

    suspend fun deleteSubcategory(category: String, key: String) {
        client.call(BudgetOperation.SUBCATEGORY_DELETE, mapOf("key" to category, "subKey" to key))
    }

    suspend fun reorderSubcategories(category: String, order: List<String>) {
        client.call(BudgetOperation.SUBCATEGORIES_REORDER, mapOf("key" to category), body = buildJsonObject {
            put("order", JsonArray(order.map(::JsonPrimitive)))
        })
    }

    // ── Accounts ──

    suspend fun accountsRaw(): JsonElement = client.call(BudgetOperation.ACCOUNTS_LIST, query = listOf("include_archived" to "1"))

    suspend fun accounts(): AccountsAnswer = accountsRaw().decodeData(AccountsAnswer.serializer())

    suspend fun createAccount(body: JsonObject, idempotencyKey: String): BudgetAccount =
        client.call(BudgetOperation.ACCOUNT_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(BudgetAccount.serializer())

    suspend fun updateAccount(id: Int, body: JsonObject): BudgetAccount =
        client.call(BudgetOperation.ACCOUNT_UPDATE, mapOf("id" to id), body = body).decodeData(BudgetAccount.serializer())

    suspend fun deleteAccount(id: Int) {
        client.call(BudgetOperation.ACCOUNT_DELETE, mapOf("id" to id))
    }

    // ── Transfers ──

    suspend fun transfer(id: Int): BudgetTransfer =
        client.call(BudgetOperation.TRANSFER_GET, mapOf("id" to id)).decodeData(BudgetTransfer.serializer())

    suspend fun createTransfer(body: JsonObject, idempotencyKey: String): BudgetTransfer =
        client.call(BudgetOperation.TRANSFER_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(BudgetTransfer.serializer())

    suspend fun updateTransfer(id: Int, body: JsonObject, series: Boolean): BudgetTransfer =
        client.call(if (series) BudgetOperation.TRANSFER_SERIES_UPDATE else BudgetOperation.TRANSFER_UPDATE, mapOf("id" to id), body = body)
            .decodeData(BudgetTransfer.serializer())

    // ── Loans ──

    suspend fun loansRaw(): JsonElement = client.call(BudgetOperation.LOANS_LIST)

    suspend fun loans(): LoansAnswer = loansRaw().decodeData(LoansAnswer.serializer())

    suspend fun createLoan(body: JsonObject, idempotencyKey: String) {
        client.call(BudgetOperation.LOAN_CREATE, body = body, idempotencyKey = idempotencyKey)
    }

    suspend fun updateLoan(id: Int, body: JsonObject) {
        client.call(BudgetOperation.LOAN_UPDATE, mapOf("id" to id), body = body)
    }

    suspend fun deleteLoan(id: Int) {
        client.call(BudgetOperation.LOAN_DELETE, mapOf("id" to id))
    }

    suspend fun previewLoan(body: JsonObject): LoanPreview =
        client.call(BudgetOperation.LOAN_PREVIEW, body = body, idempotencyKey = YuvomiClient.newIdempotencyKey()).decodeData(LoanPreview.serializer())

    /** Returns the new payment's id (`data.payment.id`) for the undo. */
    suspend fun payLoan(id: Int, installment: Int, amount: Double, paidDate: String, idempotencyKey: String): Int? {
        val answer = client.call(BudgetOperation.LOAN_PAYMENT_CREATE, mapOf("id" to id), body = buildJsonObject {
            put("installment_number", installment)
            put("amount", amount)
            put("paid_date", paidDate)
        }, idempotencyKey = idempotencyKey)
        val payment = ((answer as? JsonObject)?.get("data") as? JsonObject)?.get("payment") as? JsonObject
        return (payment?.get("id") as? JsonPrimitive)?.content?.toIntOrNull()
    }

    suspend fun deleteLoanPayment(id: Int, paymentId: Int) {
        client.call(BudgetOperation.LOAN_PAYMENT_DELETE, mapOf("id" to id, "paymentId" to paymentId))
    }

    // ── Plans and statistics ──

    suspend fun plans(month: String, view: View): BudgetPlans =
        client.call(BudgetOperation.PLANS_LIST, query = listOf("month" to month) + view.copy(accountId = null).query())
            .decodeData(BudgetPlans.serializer())

    suspend fun setPlan(category: String, amount: Double) {
        client.call(BudgetOperation.PLAN_SET, mapOf("category" to category), body = buildJsonObject { put("amount", amount) })
    }

    suspend fun deletePlan(category: String) {
        client.call(BudgetOperation.PLAN_DELETE, mapOf("category" to category))
    }

    suspend fun stats(range: String, anchor: String, view: View): BudgetStats =
        client.call(BudgetOperation.STATS, query = listOf("range" to range, "anchor" to anchor) + view.copy(accountId = null).query())
            .decodeData(BudgetStats.serializer())

    // ── Household ──

    suspend fun preferences(): BudgetPreferences = client.call(Operation.PREFERENCES).decodeData(BudgetPreferences.serializer())

    /** `GET /family/members` (Meals' operation): the people a booking can be assigned to. */
    suspend fun members(): List<BudgetMember> {
        val data = (client.call(MealsOperation.FAMILY_MEMBERS) as? JsonObject)?.get("data")
        val list = when (data) {
            is JsonArray -> data
            is JsonObject -> data["users"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { runCatching { ApiJson.decodeFromJsonElement(BudgetMember.serializer(), it) }.getOrNull() }
    }

    // ── Receipts (document-attach.js) ──

    suspend fun documents(): List<BudgetDocument> =
        client.call(BudgetOperation.DOCUMENTS_LIST).decodeData(ListSerializer(BudgetDocument.serializer()))

    /** Uploads one receipt as `bindDocumentAttachField(...).commit()` does; returns its document id. */
    suspend fun uploadReceipt(name: String, originalName: String, dataUrl: String, folderName: String, idempotencyKey: String): BudgetDocument =
        client.call(BudgetOperation.DOCUMENT_CREATE, body = buildJsonObject {
            put("name", name)
            put("description", "")
            put("category", "finance")
            put("visibility", "family")
            put("status", "active")
            put("allowed_member_ids", JsonArray(emptyList()))
            put("original_name", originalName)
            put("content_data", dataUrl)
            put("folder_key", "budget")
            put("folder_name", folderName)
        }, idempotencyKey = idempotencyKey).decodeData(BudgetDocument.serializer())

    /** A receipt's bytes: `/preview` for images and PDFs, `/download` otherwise (`attachmentLinksNode`). */
    suspend fun receipt(id: Int, preview: Boolean): ByteArray =
        client.bytes((if (preview) BudgetOperation.DOCUMENT_PREVIEW else BudgetOperation.DOCUMENT_DOWNLOAD).path(mapOf("id" to id)))

    fun receiptWebPath(id: Int, preview: Boolean): String =
        (if (preview) BudgetOperation.DOCUMENT_PREVIEW else BudgetOperation.DOCUMENT_DOWNLOAD).path(mapOf("id" to id))

    companion object {
        fun nullable(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }

        fun ids(values: List<Int>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

        fun data(json: JsonElement): JsonElement? = (json as? JsonObject)?.get("data") ?: json.jsonObject["data"]
    }
}
