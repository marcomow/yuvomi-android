package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Meals calls. Must match
 * `upstream/operations-used.d/meals.txt` exactly (ContractFilesTest).
 */
enum class MealsOperation(override val method: String, override val template: String) : ApiOperation {
    ;
}
