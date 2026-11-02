package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Housekeeping calls (docs/spec/housekeeping.md). Must match
 * `upstream/operations-used.d/housekeeping.txt` exactly (ContractFilesTest).
 *
 * Also used, from other enums: `Operation.PREFERENCES` (the household currency) and
 * `DocumentsOperation.DOCUMENT_CREATE` (the payment receipt of a visit, `POST /documents`).
 */
enum class HousekeepingOperation(override val method: String, override val template: String) : ApiOperation {
    DASHBOARD("GET", "/api/v1/housekeeping/dashboard"),
    TASK_TEMPLATES("GET", "/api/v1/housekeeping/task-templates"),
    WORKERS("GET", "/api/v1/housekeeping/workers"),
    WORKER_SAVE("POST", "/api/v1/housekeeping/worker"),
    CHECK_IN("POST", "/api/v1/housekeeping/work-sessions/check-in"),
    CHECK_OUT("POST", "/api/v1/housekeeping/work-sessions/check-out"),
    REOPEN("POST", "/api/v1/housekeeping/work-sessions/{id}/reopen"),
    VISITS("GET", "/api/v1/housekeeping/visits"),
    VISIT_GET("GET", "/api/v1/housekeeping/visits/{id}"),
    VISIT_UPDATE("PUT", "/api/v1/housekeeping/visits/{id}"),
    VISIT_DELETE("DELETE", "/api/v1/housekeeping/visits/{id}"),
    VISIT_PAY("POST", "/api/v1/housekeeping/visits/{id}/pay"),
    VISIT_UNPAY("POST", "/api/v1/housekeeping/visits/{id}/unpay"),
    TASKS("GET", "/api/v1/housekeeping/decay-tasks"),
    TASK_CREATE("POST", "/api/v1/housekeeping/decay-tasks"),
    TASK_UPDATE("PATCH", "/api/v1/housekeeping/decay-tasks/{taskId}"),
    TASK_COMPLETE("POST", "/api/v1/housekeeping/decay-tasks/{taskId}/complete"),
    TASK_DELETE("DELETE", "/api/v1/housekeeping/decay-tasks/{taskId}"),
}
