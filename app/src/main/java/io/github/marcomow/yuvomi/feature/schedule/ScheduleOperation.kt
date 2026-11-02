package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Schedule calls (docs/spec/schedule.md). Must match
 * `upstream/operations-used.d/schedule.txt` exactly (ContractFilesTest).
 */
enum class ScheduleOperation(override val method: String, override val template: String) : ApiOperation
