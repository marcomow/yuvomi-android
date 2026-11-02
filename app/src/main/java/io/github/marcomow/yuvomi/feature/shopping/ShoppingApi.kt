package io.github.marcomow.yuvomi.feature.shopping

import io.github.marcomow.yuvomi.core.api.KitchenApi
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** docs/spec/shopping.md: every call the shopping screen makes. */
class ShoppingApi(private val client: YuvomiClient) {
    private val kitchen = KitchenApi(client)
    private fun list(id: Int) = mapOf("listId" to id)

    // ── Lists ──
    suspend fun lists(): JsonElement = kitchen.shoppingLists()

    suspend fun createList(name: String, key: String): ShoppingList =
        client.call(ShoppingOperation.LIST_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = key)
            .decodeData(ShoppingList.serializer())

    suspend fun renameList(id: Int, name: String): ShoppingList =
        client.call(ShoppingOperation.LIST_RENAME, list(id), body = buildJsonObject { put("name", name) })
            .decodeData(ShoppingList.serializer())

    suspend fun deleteList(id: Int) {
        client.call(ShoppingOperation.LIST_DELETE, list(id))
    }

    suspend fun duplicateList(id: Int, name: String, resetChecked: Boolean, keepQuantities: Boolean, keepNotes: Boolean, key: String): ShoppingList =
        client.call(ShoppingOperation.LIST_DUPLICATE, list(id), body = buildJsonObject {
            put("name", name)
            put("resetChecked", resetChecked)
            put("keepQuantities", keepQuantities)
            put("keepNotes", keepNotes)
        }, idempotencyKey = key).decodeData(ShoppingList.serializer())

    // ── Items ──
    suspend fun items(listId: Int): JsonElement = client.call(ShoppingOperation.ITEMS, list(listId))

    suspend fun addItem(listId: Int, name: String, quantity: String?, category: String?, key: String): ItemAnswer =
        client.call(ShoppingOperation.ITEM_CREATE, list(listId), body = buildJsonObject {
            put("name", name)
            put("quantity", quantity)
            put("category", category)
        }, idempotencyKey = key).decode(ItemAnswer.serializer())

    /** Partial: only the fields in [changes] are written (the route keeps the rest). */
    suspend fun updateItem(id: Int, changes: JsonObject): ItemAnswer =
        client.call(ShoppingOperation.ITEM_UPDATE, mapOf("itemId" to id), body = changes).decode(ItemAnswer.serializer())

    suspend fun deleteItem(id: Int): ChangeAnswer =
        client.call(ShoppingOperation.ITEM_DELETE, mapOf("itemId" to id)).decode(ChangeAnswer.serializer())

    suspend fun reorder(listId: Int, category: String, order: List<Int>): ChangeAnswer =
        client.call(ShoppingOperation.ITEMS_REORDER, list(listId), body = buildJsonObject {
            put("category", category)
            putJsonArray("order") { order.forEach { add(JsonPrimitive(it)) } }
        }).decode(ChangeAnswer.serializer())

    suspend fun deleteChecked(listId: Int, ids: List<Int>): ChangeAnswer =
        client.call(ShoppingOperation.CHECKED_DELETE, list(listId), body = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
        }).decode(ChangeAnswer.serializer())

    suspend fun suggestions(q: String): List<Suggestion> =
        client.call(ShoppingOperation.SUGGESTIONS, query = listOf("q" to q)).decodeData(ListSerializer(Suggestion.serializer()))

    suspend fun versions(): List<ListVersion> =
        client.call(ShoppingOperation.VERSIONS).decodeData(ListSerializer(ListVersion.serializer()))

    // ── Categories ──
    suspend fun categories(): JsonElement = kitchen.shoppingCategories()

    suspend fun addCategory(name: String, key: String): ShoppingCategory =
        client.call(ShoppingOperation.CATEGORY_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = key)
            .decodeData(ShoppingCategory.serializer())

    suspend fun renameCategory(id: Int, name: String): ShoppingCategory =
        client.call(ShoppingOperation.CATEGORY_RENAME, mapOf("catId" to id), body = buildJsonObject { put("name", name) })
            .decodeData(ShoppingCategory.serializer())

    suspend fun deleteCategory(id: Int) {
        client.call(ShoppingOperation.CATEGORY_DELETE, mapOf("catId" to id))
    }

    suspend fun reorderCategories(order: List<Int>): List<ShoppingCategory> =
        client.call(ShoppingOperation.CATEGORIES_REORDER, body = buildJsonObject {
            putJsonArray("order") { order.forEach { add(JsonPrimitive(it)) } }
        }).decodeData(ListSerializer(ShoppingCategory.serializer()))

    // ── Shops ──
    suspend fun stores(): JsonElement = client.call(ShoppingOperation.STORES)

    suspend fun addStore(name: String, key: String): ShoppingStore =
        client.call(ShoppingOperation.STORE_CREATE, body = buildJsonObject { put("name", name) }, idempotencyKey = key)
            .decodeData(ShoppingStore.serializer())

    suspend fun renameStore(id: Int, name: String): ShoppingStore =
        client.call(ShoppingOperation.STORE_RENAME, mapOf("id" to id), body = buildJsonObject { put("name", name) })
            .decodeData(ShoppingStore.serializer())

    suspend fun deleteStore(id: Int) {
        client.call(ShoppingOperation.STORE_DELETE, mapOf("id" to id))
    }

    /** The household currency for prices (`GET /preferences` `currency`, EUR when missing). */
    suspend fun currency(): String =
        ((client.call(Operation.PREFERENCES) as? JsonObject)?.get("data") as? JsonObject)
            ?.get("currency")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "EUR"

    // ── Send ──
    suspend fun recipients(): List<Recipient> =
        client.call(ShoppingOperation.SEND_RECIPIENTS).decodeData(ListSerializer(Recipient.serializer()))

    suspend fun send(listId: Int, userId: Int, key: String) {
        client.call(ShoppingOperation.SEND, list(listId), body = buildJsonObject { put("userId", userId) }, idempotencyKey = key)
    }

    // ── Meal plan ──
    suspend fun importMealPlan(listId: Int, from: String, to: String, preview: Boolean, key: String?): MealImport =
        client.call(ShoppingOperation.IMPORT_MEAL_PLAN, list(listId), body = buildJsonObject {
            put("from", from)
            put("to", to)
            if (preview) put("preview", true)
        }, idempotencyKey = key).decodeData(MealImport.serializer())

    suspend fun undoTransfer(ids: List<Int>, ingredientIds: List<Int>) {
        kitchen.undoTransfer(buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
            if (ingredientIds.isNotEmpty()) putJsonArray("ingredient_ids") { ingredientIds.forEach { add(JsonPrimitive(it)) } }
        })
    }

    // ── Pantry ──
    suspend fun pantry(): PantryStock = kitchen.pantry().decode(PantryStock.serializer())

    suspend fun toPantry(listId: Int, rows: List<PantryTransferRow>, key: String): PantryImport =
        client.call(ShoppingOperation.PANTRY_IMPORT, body = buildJsonObject {
            put("list_id", listId)
            putJsonArray("items") {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("shopping_item_id", r.itemId)
                        put("quantity", r.quantity)
                        put("unit", r.unit)
                        put("location_id", r.locationId)
                    })
                }
            }
        }, idempotencyKey = key).decodeData(PantryImport.serializer())

    suspend fun undoPantry(changes: JsonArray, key: String) {
        client.call(ShoppingOperation.PANTRY_IMPORT_UNDO, body = buildJsonObject { put("changes", changes) }, idempotencyKey = key)
    }

    companion object {
        fun decodeLists(json: JsonElement): List<ShoppingList> = json.decodeData(ListSerializer(ShoppingList.serializer()))
        fun decodeCategories(json: JsonElement): List<ShoppingCategory> = json.decodeData(ListSerializer(ShoppingCategory.serializer()))
        fun decodeStores(json: JsonElement): List<ShoppingStore> = json.decodeData(ListSerializer(ShoppingStore.serializer()))
        fun decodeItems(json: JsonElement): ItemsAnswer = json.decode(ItemsAnswer.serializer())
    }
}

/** One row of the pantry transfer (SHOP-R30). */
data class PantryTransferRow(val itemId: Int, val quantity: Double, val unit: String, val locationId: Int?)
