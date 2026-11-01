package io.github.marcomow.yuvomi.feature.pantry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One batch (`pantry_items` row plus the location join of `GET /pantry`). */
@Serializable
data class PantryItem(
    val id: Int,
    val name: String = "",
    val quantity: Double = 0.0,
    val unit: String = PantryUnits.DEFAULT,
    @SerialName("location_id") val locationId: Int? = null,
    @SerialName("location_name") val locationName: String? = null,
    @SerialName("location_icon") val locationIcon: String? = null,
    val category: String? = null,
    @SerialName("expires_on") val expiresOn: String? = null,
    @SerialName("min_quantity") val minQuantity: Double? = null,
    val notes: String? = null,
    @SerialName("product_id") val productId: Int? = null,
)

/** A household product over the batches (#1448); it outlives its last batch. */
@Serializable
data class PantryProduct(
    val id: Int,
    val name: String = "",
    val unit: String? = null,
    val category: String? = null,
    @SerialName("batch_count") val batchCount: Int = 0,
)

@Serializable
data class PantryLocation(
    val id: Int,
    val name: String = "",
    val icon: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/** A Shopping category, the pantry's category list (`shopping_categories`). */
@Serializable
data class PantryCategory(
    val id: Int = 0,
    val name: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/** The whole answer of `GET /pantry`: `{ data, products, locations, categories }`. */
@Serializable
data class PantryStock(
    @SerialName("data") val items: List<PantryItem> = emptyList(),
    val products: List<PantryProduct> = emptyList(),
    val locations: List<PantryLocation> = emptyList(),
    val categories: List<PantryCategory> = emptyList(),
)

@Serializable
data class ShoppingListRef(val id: Int, val name: String = "")

/** `data` of `POST /shopping/{listId}/import-pantry`. */
@Serializable
data class TransferResult(
    val added: Int = 0,
    val skipped: Int = 0,
    @SerialName("added_ids") val addedIds: List<Int> = emptyList(),
)
