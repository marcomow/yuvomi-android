package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Inventory calls (docs/spec/inventory.md). Must match
 * `upstream/operations-used.d/inventory.txt` exactly (ContractFilesTest).
 */
enum class InventoryOperation(override val method: String, override val template: String) : ApiOperation {
    ITEMS_LIST("GET", "/api/v1/inventory/items"),
    ITEM_CREATE("POST", "/api/v1/inventory/items"),
    ITEM_GET("GET", "/api/v1/inventory/items/{id}"),
    ITEM_UPDATE("PUT", "/api/v1/inventory/items/{id}"),
    ITEM_DELETE("DELETE", "/api/v1/inventory/items/{id}"),
    ITEM_HISTORY("GET", "/api/v1/inventory/items/{id}/history"),
    ITEM_ENTRY_LINK("POST", "/api/v1/inventory/items/{id}/entries"),
    ITEM_ENTRY_UNLINK("DELETE", "/api/v1/inventory/items/{id}/entries/{entryId}"),
    DATE_COMPLETE("POST", "/api/v1/inventory/items/{id}/dates/{dateId}/complete"),

    CATEGORIES_LIST("GET", "/api/v1/inventory/categories"),
    CATEGORY_CREATE("POST", "/api/v1/inventory/categories"),
    CATEGORIES_REORDER("PATCH", "/api/v1/inventory/categories/reorder"),
    CATEGORY_UPDATE("PUT", "/api/v1/inventory/categories/{key}"),
    CATEGORY_DELETE("DELETE", "/api/v1/inventory/categories/{key}"),

    LOCATIONS_LIST("GET", "/api/v1/inventory/locations"),
    LOCATION_CREATE("POST", "/api/v1/inventory/locations"),
    LOCATIONS_REORDER("PATCH", "/api/v1/inventory/locations/reorder"),
    LOCATION_UPDATE("PUT", "/api/v1/inventory/locations/{id}"),
    LOCATION_DELETE("DELETE", "/api/v1/inventory/locations/{id}"),
    SUBLOCATION_CREATE("POST", "/api/v1/inventory/locations/{parentId}/subcategories"),
    SUBLOCATIONS_REORDER("PATCH", "/api/v1/inventory/locations/{parentId}/subcategories/reorder"),
    SUBLOCATION_UPDATE("PUT", "/api/v1/inventory/locations/{parentId}/subcategories/{id}"),
    SUBLOCATION_DELETE("DELETE", "/api/v1/inventory/locations/{parentId}/subcategories/{id}"),
}
