package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Settings calls (docs/spec/settings.md). Must match
 * `upstream/operations-used.d/settings.txt` exactly (ContractFilesTest).
 */
enum class SettingsOperation(override val method: String, override val template: String) : ApiOperation
