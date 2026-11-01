package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Pantry calls. Must match
 * `upstream/operations-used.d/pantry.txt` exactly (ContractFilesTest).
 */
enum class PantryOperation(override val method: String, override val template: String) : ApiOperation {
    ;
}
