package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Recipes calls. Must match
 * `upstream/operations-used.d/recipes.txt` exactly (ContractFilesTest).
 * Listing and creating recipes are shared kitchen calls (`KitchenApi`).
 */
enum class RecipesOperation(override val method: String, override val template: String) : ApiOperation {
    RECIPE_UPDATE("PUT", "/api/v1/recipes/{id}"),
    RECIPE_DELETE("DELETE", "/api/v1/recipes/{id}"),
    RECIPE_IMAGE("GET", "/api/v1/recipes/{id}/image"),
    RECIPE_PROVIDER_THUMBNAIL("GET", "/api/v1/recipes/{id}/provider-thumbnail"),
    RECIPE_INGREDIENT_MATCH("PUT", "/api/v1/recipes/{id}/ingredient-match"),
    RECIPE_TO_SHOPPING_LIST("POST", "/api/v1/recipes/{id}/to-shopping-list"),
    ;
}
