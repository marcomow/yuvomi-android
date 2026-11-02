package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Pantry calls. Must match
 * `upstream/operations-used.d/pantry.txt` exactly (ContractFilesTest).
 * `GET /pantry`, the shopping lists and the transfer undo are shared kitchen calls (KitchenApi).
 */
enum class PantryOperation(override val method: String, override val template: String) : ApiOperation {
    ITEM_CREATE("POST", "/api/v1/pantry"),
    ITEM_UPDATE("PUT", "/api/v1/pantry/{itemId}"),
    ITEM_PATCH("PATCH", "/api/v1/pantry/{itemId}"),
    ITEM_DELETE("DELETE", "/api/v1/pantry/{itemId}"),
    PRODUCT_DELETE("DELETE", "/api/v1/pantry/products/{productId}"),
    LOCATIONS_LIST("GET", "/api/v1/pantry/locations"),
    LOCATION_CREATE("POST", "/api/v1/pantry/locations"),
    LOCATION_UPDATE("PUT", "/api/v1/pantry/locations/{locId}"),
    LOCATIONS_REORDER("PATCH", "/api/v1/pantry/locations/reorder"),
    LOCATION_DELETE("DELETE", "/api/v1/pantry/locations/{locId}"),

    // Only the pantry page puts stock on a shopping list (PANTRY-R17).
    SHOPPING_IMPORT_PANTRY("POST", "/api/v1/shopping/{listId}/import-pantry"),
}
