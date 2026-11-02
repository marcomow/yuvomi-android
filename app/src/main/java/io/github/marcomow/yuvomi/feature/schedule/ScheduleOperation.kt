package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Schedule calls (docs/spec/schedule.md). Must match
 * `upstream/operations-used.d/schedule.txt` exactly (ContractFilesTest).
 * `GET /preferences` (week start, hidden templates) is the core [io.github.marcomow.yuvomi.core.api.Operation.PREFERENCES].
 */
enum class ScheduleOperation(override val method: String, override val template: String) : ApiOperation {
    ENTRIES("GET", "/api/v1/schedule/entries"),
    HOUSEHOLD_MEMBERS("GET", "/api/v1/schedule/household-members"),
    SHIFT_TYPES("GET", "/api/v1/schedule/shift-types"),
    SHIFT_TYPE_CREATE("POST", "/api/v1/schedule/shift-types"),
    SHIFT_TYPE_UPDATE("PUT", "/api/v1/schedule/shift-types/{id}"),
    SHIFT_TYPE_DELETE("DELETE", "/api/v1/schedule/shift-types/{id}"),
    SHIFT_TYPE_FIELDS("PUT", "/api/v1/schedule/shift-types/{id}/fields"),
    CUSTOM_FIELDS("GET", "/api/v1/schedule/custom-fields"),
    CUSTOM_FIELD_CREATE("POST", "/api/v1/schedule/custom-fields"),
    CUSTOM_FIELD_UPDATE("PUT", "/api/v1/schedule/custom-fields/{id}"),
    CUSTOM_FIELD_DELETE("DELETE", "/api/v1/schedule/custom-fields/{id}"),
    PATTERNS("GET", "/api/v1/schedule/patterns"),
    PATTERN_CREATE("POST", "/api/v1/schedule/patterns"),
    PATTERN_UPDATE("PUT", "/api/v1/schedule/patterns/{id}"),
    PATTERN_DELETE("DELETE", "/api/v1/schedule/patterns/{id}"),
    PATTERN_DAYS("GET", "/api/v1/schedule/patterns/{id}/days"),
    PATTERN_DAYS_REPLACE("PUT", "/api/v1/schedule/patterns/{id}/days"),
    OVERRIDES("GET", "/api/v1/schedule/overrides"),
    OVERRIDES_DELETE_RANGE("DELETE", "/api/v1/schedule/overrides"),
    OVERRIDES_FILL("POST", "/api/v1/schedule/overrides/fill"),
    OVERRIDE_PUT("PUT", "/api/v1/schedule/overrides/{dateKey}"),
    OVERRIDE_DELETE("DELETE", "/api/v1/schedule/overrides/{dateKey}"),
    EXTRAS("GET", "/api/v1/schedule/extras"),
    EXTRA_CREATE("POST", "/api/v1/schedule/extras"),
    EXTRAS_FILL("POST", "/api/v1/schedule/extras/fill"),
    EXTRA_UPDATE("PUT", "/api/v1/schedule/extras/{id}"),
    EXTRA_DELETE("DELETE", "/api/v1/schedule/extras/{id}"),
    FEED("GET", "/api/v1/schedule/feed"),
    FEED_REGENERATE("POST", "/api/v1/schedule/feed/regenerate"),
    FEED_DISABLE("DELETE", "/api/v1/schedule/feed"),
    PREFERENCES("GET", "/api/v1/schedule/preferences"),

    // Outside /schedule: the household's holidays sit over the overview's days (`refreshOverview`).
    // The account directory (`GET /auth/users`) is DocumentsOperation.AUTH_USERS until it moves to core.
    HOLIDAYS("GET", "/api/v1/calendar/holidays"),
}
