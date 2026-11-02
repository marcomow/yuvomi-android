package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.core.api.FlexBool
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.budget.BudgetOperation
import io.github.marcomow.yuvomi.feature.documents.DocumentsOperation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A tracked date of an item (`inventory_item_dates`): TÜV, service, insurance renewal. */
@Serializable
data class TrackedDate(
    val id: Int = 0,
    val label: String = "",
    val date: String = "",
    @SerialName("reminder_offset_days") val reminderOffsetDays: Int? = 30,
    @SerialName("interval_months") val intervalMonths: Int? = null,
    @SerialName("interval_distance") val intervalDistance: Int? = null,
)

/** A budget booking linked to an item (`loadLinkedEntriesForItems`). */
@Serializable
data class LinkedEntry(
    val id: Int = 0,
    @SerialName("entry_id") val entryId: Int,
    val role: String = "purchase",
    @SerialName("amount_share") val amountShare: Double? = null,
    val title: String = "",
    val amount: Double = 0.0,
    val date: String = "",
)

/** A document attached to an item (`documentLinksFor`). */
@Serializable
data class ItemAttachment(
    val id: Int = 0,
    @SerialName("document_id") val documentId: Int? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: originalName.orEmpty()
}

/** An item as `GET /inventory/items` lists it and every item write answers it (`loadItem`). */
@Serializable
data class InventoryItem(
    val id: Int,
    val name: String = "",
    val brand: String? = null,
    val model: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null,
    val category: String = "other",
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("category_icon") val categoryIcon: String? = null,
    @SerialName("category_label_key") val categoryLabelKey: String? = null,
    @SerialName("location_id") val locationId: Int? = null,
    @SerialName("location_path") val locationPath: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    @SerialName("purchase_price") val purchasePrice: Double? = null,
    val currency: String? = null,
    val vendor: String? = null,
    @SerialName("warranty_months") val warrantyMonths: Int? = null,
    val condition: String = "good",
    val status: String = "active",
    val notes: String? = null,
    @SerialName("photo_data") val photoData: String? = null,
    @SerialName("account_username") val accountUsername: String? = null,
    val odometer: Long? = null,
    @SerialName("odometer_unit") val odometerUnit: String? = null,
    @SerialName("odometer_on") val odometerOn: String? = null,
    @SerialName("created_by") val createdBy: Int? = null,
    val attachments: List<ItemAttachment> = emptyList(),
    @SerialName("linked_entries") val linkedEntries: List<LinkedEntry> = emptyList(),
    @SerialName("linked_entries_total") val linkedEntriesTotal: Double = 0.0,
    @SerialName("tracked_dates") val trackedDates: List<TrackedDate> = emptyList(),
)

/** An item category (`inventory_categories`): seeded ones carry a `label_key`. */
@Serializable
data class InventoryCategory(
    val key: String,
    val name: String? = null,
    @SerialName("label_key") val labelKey: String? = null,
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("tracks_odometer") val tracksOdometer: FlexBool = false,
)

/** A location with its sub-locations (`loadTree`; the path segment is `subcategories`). */
@Serializable
data class InventoryLocation(
    val id: Int,
    val name: String = "",
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val subcategories: List<InventoryLocation> = emptyList(),
)

/** One line of the item's history (`loadHistory`): a service log entry, a booking or a document. */
@Serializable
data class HistoryEntry(
    val type: String,
    val id: Int = 0,
    val date: String = "",
    val label: String = "",
    val odometer: Long? = null,
    val vendor: String? = null,
    val note: String? = null,
    val role: String? = null,
    val amount: Double? = null,
)

@Serializable
data class ItemHistory(val timeline: List<HistoryEntry> = emptyList(), val total: Double = 0.0)

/** A budget booking as the booking picker lists it (`GET /budget?month=`). */
@Serializable
data class BudgetBooking(
    val id: Int,
    val title: String = "",
    val amount: Double = 0.0,
    val date: String = "",
    @SerialName("recurrence_parent_id") val recurrenceParentId: Int? = null,
    @SerialName("is_pending") val isPending: FlexBool = false,
)

/** A document the attach field can link (`GET /documents`). */
@Serializable
data class LinkableDocument(
    val id: Int,
    val name: String = "",
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

/** docs/spec/inventory.md, "Upstream operations". */
class InventoryApi(private val client: YuvomiClient) {

    suspend fun items(): JsonElement = client.call(InventoryOperation.ITEMS_LIST)

    suspend fun categories(): JsonElement = client.call(InventoryOperation.CATEGORIES_LIST)

    suspend fun locations(): JsonElement = client.call(InventoryOperation.LOCATIONS_LIST)

    suspend fun item(id: Int): InventoryItem =
        client.call(InventoryOperation.ITEM_GET, mapOf("id" to id)).decodeData(InventoryItem.serializer())

    suspend fun history(id: Int): ItemHistory =
        client.call(InventoryOperation.ITEM_HISTORY, mapOf("id" to id)).decodeData(ItemHistory.serializer())

    suspend fun create(body: JsonObject, idempotencyKey: String): InventoryItem =
        client.call(InventoryOperation.ITEM_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(InventoryItem.serializer())

    suspend fun update(id: Int, body: JsonObject): InventoryItem =
        client.call(InventoryOperation.ITEM_UPDATE, mapOf("id" to id), body = body).decodeData(InventoryItem.serializer())

    suspend fun delete(id: Int) {
        client.call(InventoryOperation.ITEM_DELETE, mapOf("id" to id))
    }

    suspend fun linkEntry(id: Int, entryId: Int, role: String, idempotencyKey: String): InventoryItem =
        client.call(InventoryOperation.ITEM_ENTRY_LINK, mapOf("id" to id), body = buildJsonObject {
            put("entry_id", entryId)
            put("role", role)
        }, idempotencyKey = idempotencyKey).decodeData(InventoryItem.serializer())

    suspend fun unlinkEntry(id: Int, entryId: Int): InventoryItem =
        client.call(InventoryOperation.ITEM_ENTRY_UNLINK, mapOf("id" to id, "entryId" to entryId)).decodeData(InventoryItem.serializer())

    /** `openCompletionSheet`: the service log entry, and the date rolls forward or goes. */
    suspend fun complete(id: Int, dateId: Int, body: JsonObject, idempotencyKey: String) {
        client.call(InventoryOperation.DATE_COMPLETE, mapOf("id" to id, "dateId" to dateId), body = body, idempotencyKey = idempotencyKey)
    }

    // ── Categories ──

    suspend fun createCategory(name: String, idempotencyKey: String): InventoryCategory =
        client.call(InventoryOperation.CATEGORY_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = idempotencyKey)
            .decodeData(InventoryCategory.serializer())

    suspend fun renameCategory(key: String, name: String): InventoryCategory =
        client.call(InventoryOperation.CATEGORY_UPDATE, mapOf("key" to key), body = buildJsonObject { put("name", name) })
            .decodeData(InventoryCategory.serializer())

    suspend fun reorderCategories(keys: List<String>) {
        client.call(InventoryOperation.CATEGORIES_REORDER, body = buildJsonObject { put("order", JsonArray(keys.map { JsonPrimitive(it) })) })
    }

    suspend fun deleteCategory(key: String) {
        client.call(InventoryOperation.CATEGORY_DELETE, mapOf("key" to key))
    }

    // ── Locations ──

    suspend fun createLocation(name: String, parentId: Int?, idempotencyKey: String) {
        val body = buildJsonObject { put("name", name) }
        if (parentId == null) {
            client.call(InventoryOperation.LOCATION_CREATE, body = body, idempotencyKey = idempotencyKey)
        } else {
            client.call(InventoryOperation.SUBLOCATION_CREATE, mapOf("parentId" to parentId), body = body, idempotencyKey = idempotencyKey)
        }
    }

    suspend fun renameLocation(id: Int, parentId: Int?, name: String) {
        val body = buildJsonObject { put("name", name) }
        if (parentId == null) {
            client.call(InventoryOperation.LOCATION_UPDATE, mapOf("id" to id), body = body)
        } else {
            client.call(InventoryOperation.SUBLOCATION_UPDATE, mapOf("parentId" to parentId, "id" to id), body = body)
        }
    }

    suspend fun reorderLocations(ids: List<Int>, parentId: Int?) {
        val body = buildJsonObject { put("order", JsonArray(ids.map { JsonPrimitive(it) })) }
        if (parentId == null) {
            client.call(InventoryOperation.LOCATIONS_REORDER, body = body)
        } else {
            client.call(InventoryOperation.SUBLOCATIONS_REORDER, mapOf("parentId" to parentId), body = body)
        }
    }

    suspend fun deleteLocation(id: Int, parentId: Int?) {
        if (parentId == null) {
            client.call(InventoryOperation.LOCATION_DELETE, mapOf("id" to id))
        } else {
            client.call(InventoryOperation.SUBLOCATION_DELETE, mapOf("parentId" to parentId, "id" to id))
        }
    }

    // ── Other modules' reads ──

    /** The booking picker's month (Budget's `GET /budget?month=`): booked entries, no generated instances. */
    suspend fun bookings(month: String): List<BudgetBooking> =
        client.call(BudgetOperation.ENTRIES_LIST, query = listOf("month" to month)).decodeData(ListSerializer(BudgetBooking.serializer()))
            .filter { it.recurrenceParentId == null && !it.isPending }

    suspend fun documents(): List<LinkableDocument> =
        client.call(DocumentsOperation.DOCUMENTS_LIST).decodeData(ListSerializer(LinkableDocument.serializer()))

    /** `bindDocumentAttachField(...).commit()` for one new file, into the Inventory folder. */
    suspend fun upload(name: String, originalName: String, dataUrl: String, category: String, folderName: String, idempotencyKey: String): LinkableDocument =
        client.call(DocumentsOperation.DOCUMENT_CREATE, body = buildJsonObject {
            put("name", name)
            put("description", "")
            put("category", category)
            put("visibility", "family")
            put("status", "active")
            put("allowed_member_ids", JsonArray(emptyList()))
            put("original_name", originalName)
            put("content_data", dataUrl)
            put("folder_key", "inventory")
            put("folder_name", folderName)
        }, idempotencyKey = idempotencyKey).decodeData(LinkableDocument.serializer())

    companion object {
        fun decodeItems(answer: JsonElement): List<InventoryItem> = answer.decodeData(ListSerializer(InventoryItem.serializer()))

        fun decodeCategories(answer: JsonElement): List<InventoryCategory> = answer.decodeData(ListSerializer(InventoryCategory.serializer()))

        fun decodeLocations(answer: JsonElement): List<InventoryLocation> = answer.decodeData(ListSerializer(InventoryLocation.serializer()))
    }
}
