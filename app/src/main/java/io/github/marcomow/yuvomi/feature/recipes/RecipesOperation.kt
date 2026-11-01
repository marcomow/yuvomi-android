package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Recipes calls. Must match
 * `upstream/operations-used.d/recipes.txt` exactly (ContractFilesTest).
 */
enum class RecipesOperation(override val method: String, override val template: String) : ApiOperation {
    ;
}
