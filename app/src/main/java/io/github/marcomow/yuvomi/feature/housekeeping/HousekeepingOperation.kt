package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Housekeeping calls (docs/spec/housekeeping.md). Must match
 * `upstream/operations-used.d/housekeeping.txt` exactly (ContractFilesTest).
 */
enum class HousekeepingOperation(override val method: String, override val template: String) : ApiOperation
