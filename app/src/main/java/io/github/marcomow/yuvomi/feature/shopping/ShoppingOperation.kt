package io.github.marcomow.yuvomi.feature.shopping

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Shopping calls. Must match
 * `upstream/operations-used.d/shopping.txt` exactly (ContractFilesTest).
 * The two pantry import routes are here because only the shopping page calls them.
 */
enum class ShoppingOperation(override val method: String, override val template: String) : ApiOperation {
    LIST_CREATE("POST", "/api/v1/shopping"),
    LIST_RENAME("PUT", "/api/v1/shopping/{listId}"),
    LIST_DELETE("DELETE", "/api/v1/shopping/{listId}"),
    LIST_DUPLICATE("POST", "/api/v1/shopping/{listId}/duplicate"),
    ITEMS("GET", "/api/v1/shopping/{listId}/items"),
    ITEM_CREATE("POST", "/api/v1/shopping/{listId}/items"),
    ITEM_UPDATE("PATCH", "/api/v1/shopping/items/{itemId}"),
    ITEM_DELETE("DELETE", "/api/v1/shopping/items/{itemId}"),
    ITEMS_REORDER("PATCH", "/api/v1/shopping/{listId}/items/reorder"),
    CHECKED_DELETE("DELETE", "/api/v1/shopping/{listId}/items/checked"),
    CATEGORY_CREATE("POST", "/api/v1/shopping/categories"),
    CATEGORY_RENAME("PUT", "/api/v1/shopping/categories/{catId}"),
    CATEGORY_DELETE("DELETE", "/api/v1/shopping/categories/{catId}"),
    CATEGORIES_REORDER("PATCH", "/api/v1/shopping/categories/reorder"),
    STORES("GET", "/api/v1/shopping/stores"),
    STORE_CREATE("POST", "/api/v1/shopping/stores"),
    STORE_RENAME("PUT", "/api/v1/shopping/stores/{id}"),
    STORE_DELETE("DELETE", "/api/v1/shopping/stores/{id}"),
    SUGGESTIONS("GET", "/api/v1/shopping/suggestions"),
    VERSIONS("GET", "/api/v1/shopping/versions"),
    PRICE_HISTORY("GET", "/api/v1/shopping/price-history"),
    SEND_RECIPIENTS("GET", "/api/v1/shopping/send-recipients"),
    SEND("POST", "/api/v1/shopping/{listId}/send"),
    IMPORT_MEAL_PLAN("POST", "/api/v1/shopping/{listId}/import-meal-plan"),
    PANTRY_IMPORT("POST", "/api/v1/pantry/import-shopping"),
    PANTRY_IMPORT_UNDO("POST", "/api/v1/pantry/import-shopping/undo"),
}
