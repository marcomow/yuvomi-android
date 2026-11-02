package io.github.marcomow.yuvomi.feature.reminders

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations phone reminders call (docs/spec/reminders.md). Must match
 * `upstream/operations-used.d/reminders.txt` exactly (ContractFilesTest).
 */
enum class RemindersOperation(override val method: String, override val template: String) : ApiOperation {
    UPCOMING("GET", "/api/v1/reminders/upcoming"),
    DISMISS("PATCH", "/api/v1/reminders/{id}/dismiss"),
}
