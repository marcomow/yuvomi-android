package io.github.marcomow.yuvomi.feature.shopping

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/** A row of `GET /shopping` (docs/spec/shopping.md, fields read). */
@Serializable
data class ShoppingList(
    val id: Int,
    val name: String = "",
    @SerialName("item_total") val itemTotal: Int = 0,
    @SerialName("item_checked") val itemChecked: Int = 0,
    /** `0` when the list mirrors a read-only CalDAV list (#2283); missing means it accepts. */
    @SerialName("accepts_new_items") val acceptsNewItems: FlexBool = true,
) {
    val open: Int get() = (itemTotal - itemChecked).coerceAtLeast(0)
}

@Serializable
data class ShoppingItem(
    val id: Int,
    @SerialName("list_id") val listId: Int? = null,
    val name: String = "",
    val quantity: String? = null,
    val category: String? = null,
    @SerialName("is_checked") val isChecked: FlexBool = false,
    val notes: String? = null,
    val url: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
    @SerialName("price_cents") val priceCents: Long? = null,
    @SerialName("store_id") val storeId: Int? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class ShoppingCategory(
    val id: Int,
    val name: String = "",
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class ShoppingStore(val id: Int, val name: String = "")

/** One recorded purchase of `GET /shopping/price-history` (upstream 2.86.0, part 1 of #1003). */
@Serializable
data class Purchase(
    @SerialName("item_name") val itemName: String = "",
    @SerialName("price_cents") val priceCents: Long? = null,
    @SerialName("store_name") val storeName: String? = null,
    @SerialName("purchased_at") val purchasedAt: String? = null,
)

@Serializable
data class Suggestion(val name: String = "", val category: String? = null, val quantity: String? = null)

@Serializable
data class ListVersion(@SerialName("list_id") val listId: Int, val version: Long)

/** `list_change` on item writes: the list's change counter before and after this write. */
@Serializable
data class ListChange(
    @SerialName("list_id") val listId: Int,
    val before: Long? = null,
    val after: Long? = null,
)

/** `GET /shopping/{listId}/items`. */
@Serializable
data class ItemsAnswer(
    val data: List<ShoppingItem> = emptyList(),
    val list: ShoppingList? = null,
    val categories: List<ShoppingCategory> = emptyList(),
    val version: Long? = null,
)

@Serializable
data class ItemAnswer(
    val data: ShoppingItem,
    @SerialName("list_change") val listChange: ListChange? = null,
)

@Serializable
data class ChangeAnswer(
    @SerialName("list_change") val listChange: ListChange? = null,
    val deleted: Int? = null,
    val data: List<ShoppingItem>? = null,
)

@Serializable
data class Recipient(val id: Int, @SerialName("display_name") val displayName: String = "")

@Serializable
data class MealImport(
    val transferred: Int = 0,
    val added: Int = 0,
    val skipped: Int = 0,
    val meals: Int = 0,
    @SerialName("added_ids") val addedIds: List<Int> = emptyList(),
    @SerialName("ingredient_ids") val ingredientIds: List<Int> = emptyList(),
)

/** `POST /pantry/import-shopping`; `changes` is handed back verbatim to the undo. */
@Serializable
data class PantryImport(
    val added: Int = 0,
    val merged: Int = 0,
    val skipped: Int = 0,
    val changes: JsonArray = JsonArray(emptyList()),
)

/** The parts of `GET /pantry` the transfer presets read. */
@Serializable
data class PantryStock(
    val data: List<PantryRow> = emptyList(),
    val locations: List<PantryLocation> = emptyList(),
)

@Serializable
data class PantryRow(
    val name: String = "",
    val unit: String? = null,
    @SerialName("location_id") val locationId: Int? = null,
    val category: String? = null,
)

@Serializable
data class PantryLocation(val id: Int, val name: String = "")
