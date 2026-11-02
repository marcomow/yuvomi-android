package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Waste calls (docs/spec/waste.md). Must match
 * `upstream/operations-used.d/waste.txt` exactly (ContractFilesTest).
 */
enum class WasteOperation(override val method: String, override val template: String) : ApiOperation
