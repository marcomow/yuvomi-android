package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Calendar calls (docs/spec/calendar.md). Must match
 * `upstream/operations-used.d/calendar.txt` exactly (ContractFilesTest).
 */
enum class CalendarOperation(override val method: String, override val template: String) : ApiOperation
