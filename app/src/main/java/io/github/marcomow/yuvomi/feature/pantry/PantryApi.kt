package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.KitchenApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** docs/spec/pantry.md, "Upstream operations". */
class PantryApi(private val client: YuvomiClient) {
    val kitchen = KitchenApi(client)

    /** The fields of the editor, as `saveItem` sends them (PANTRY-R13). */
    data class ItemFields(
        val name: String,
        val quantity: Double,
        val unit: String,
        val locationId: Int?,
        val category: String,
        val expiresOn: String?,
        val minQuantity: Double?,
        val notes: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("quantity", quantity)
            put("unit", unit)
            put("location_id", locationId?.let(::JsonPrimitive) ?: JsonNull)
            put("category", category)
            put("expires_on", expiresOn?.let(::JsonPrimitive) ?: JsonNull)
            put("min_quantity", minQuantity?.let(::JsonPrimitive) ?: JsonNull)
            put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    /** One entry of an import: a batch with its shortfall text, or a product without batches. */
    sealed interface TransferEntry {
        data class Batch(val itemId: Int, val quantity: String?) : TransferEntry
        data class Product(val productId: Int) : TransferEntry
    }

    suspend fun stock(): JsonElement = kitchen.pantry()

    suspend fun create(fields: ItemFields, idempotencyKey: String): PantryItem =
        client.call(PantryOperation.ITEM_CREATE, body = fields.toJson(), idempotencyKey = idempotencyKey).decodeData(PantryItem.serializer())

    suspend fun update(id: Int, fields: ItemFields): PantryItem =
        client.call(PantryOperation.ITEM_UPDATE, mapOf("itemId" to id), body = fields.toJson()).decodeData(PantryItem.serializer())

    /** The stepper's write: the quantity only (PANTRY-R12). */
    suspend fun setQuantity(id: Int, quantity: Double): PantryItem =
        client.call(PantryOperation.ITEM_PATCH, mapOf("itemId" to id), body = buildJsonObject { put("quantity", quantity) })
            .decodeData(PantryItem.serializer())

    suspend fun delete(id: Int) {
        client.call(PantryOperation.ITEM_DELETE, mapOf("itemId" to id))
    }

    suspend fun deleteProduct(id: Int) {
        client.call(PantryOperation.PRODUCT_DELETE, mapOf("productId" to id))
    }

    suspend fun locations(): List<PantryLocation> =
        client.call(PantryOperation.LOCATIONS_LIST).decodeData(ListSerializer(PantryLocation.serializer()))

    suspend fun createLocation(name: String, idempotencyKey: String): PantryLocation =
        client.call(PantryOperation.LOCATION_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = idempotencyKey)
            .decodeData(PantryLocation.serializer())

    suspend fun renameLocation(id: Int, name: String): PantryLocation =
        client.call(PantryOperation.LOCATION_UPDATE, mapOf("locId" to id), body = buildJsonObject { put("name", name) })
            .decodeData(PantryLocation.serializer())

    suspend fun reorderLocations(order: List<Int>): List<PantryLocation> =
        client.call(PantryOperation.LOCATIONS_REORDER, body = buildJsonObject { put("order", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } }) })
            .decodeData(ListSerializer(PantryLocation.serializer()))

    /** Returns how many batches were left without a place (`orphaned`). */
    suspend fun deleteLocation(id: Int): Int {
        val answer = client.call(PantryOperation.LOCATION_DELETE, mapOf("locId" to id)) as? JsonObject
        return (answer?.get("orphaned") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    suspend fun shoppingLists(): List<ShoppingListRef> =
        kitchen.shoppingLists().decodeData(ListSerializer(ShoppingListRef.serializer()))

    suspend fun toShopping(listId: Int, entries: List<TransferEntry>, idempotencyKey: String): TransferResult {
        val body = buildJsonObject {
            put("items", buildJsonArray {
                entries.forEach { entry ->
                    add(
                        when (entry) {
                            is TransferEntry.Batch -> buildJsonObject {
                                put("pantry_item_id", entry.itemId)
                                put("quantity", entry.quantity?.let(::JsonPrimitive) ?: JsonNull)
                            }
                            is TransferEntry.Product -> buildJsonObject { put("pantry_product_id", entry.productId) }
                        },
                    )
                }
            })
        }
        return client.call(PantryOperation.SHOPPING_IMPORT_PANTRY, mapOf("listId" to listId), body = body, idempotencyKey = idempotencyKey)
            .decodeData(TransferResult.serializer())
    }

    suspend fun undoTransfer(ids: List<Int>) {
        kitchen.undoTransfer(buildJsonObject { put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) })
    }

    companion object {
        fun decodeStock(json: JsonElement): PantryStock = json.decode(PantryStock.serializer())

        fun decodeStock(text: String): PantryStock = decodeStock(ApiJson.parseToJsonElement(text))
    }
}
