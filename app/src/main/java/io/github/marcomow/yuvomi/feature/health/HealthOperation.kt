package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Health calls (docs/spec/health.md). Must match
 * `upstream/operations-used.d/health.txt` exactly (ContractFilesTest).
 */
enum class HealthOperation(override val method: String, override val template: String) : ApiOperation
