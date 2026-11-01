package io.github.marcomow.yuvomi.feature.shopping

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Shopping calls. Must match
 * `upstream/operations-used.d/shopping.txt` exactly (ContractFilesTest).
 */
enum class ShoppingOperation(override val method: String, override val template: String) : ApiOperation {
    ;
}
