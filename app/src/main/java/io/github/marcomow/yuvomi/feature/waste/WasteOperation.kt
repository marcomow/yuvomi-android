package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Waste calls (docs/spec/waste.md). Must match
 * `upstream/operations-used.d/waste.txt` exactly (ContractFilesTest).
 *
 * Also used, from another enum: `CalendarOperation.WASTE_OCCURRENCES`
 * (`GET /waste/occurrences`, the upcoming pickups), declared by Calendar for its waste layer.
 */
enum class WasteOperation(override val method: String, override val template: String) : ApiOperation {
    TYPES("GET", "/api/v1/waste/types"),
    TYPE_CREATE("POST", "/api/v1/waste/types"),
    TYPE_UPDATE("PUT", "/api/v1/waste/types/{id}"),
    TYPE_DELETE("DELETE", "/api/v1/waste/types/{id}"),
    SCHEDULES("GET", "/api/v1/waste/schedules"),
    SCHEDULE_CREATE("POST", "/api/v1/waste/schedules"),
    SCHEDULE_UPDATE("PUT", "/api/v1/waste/schedules/{id}"),
    SCHEDULE_DELETE("DELETE", "/api/v1/waste/schedules/{id}"),
    OVERRIDE_SET("PUT", "/api/v1/waste/schedules/{id}/overrides/{originalDate}"),
    OVERRIDE_DELETE("DELETE", "/api/v1/waste/schedules/{id}/overrides/{originalDate}"),
    /** Only the Tags view reads the whole list (the notes); the page reads pickups through the occurrences. */
    PICKUPS_LIST("GET", "/api/v1/waste/pickups"),
    PICKUP_CREATE("POST", "/api/v1/waste/pickups"),
    PICKUP_GET("GET", "/api/v1/waste/pickups/{id}"),
    PICKUP_UPDATE("PUT", "/api/v1/waste/pickups/{id}"),
    PICKUP_DELETE("DELETE", "/api/v1/waste/pickups/{id}"),
    IMPORT_PREVIEW("POST", "/api/v1/waste/import/preview"),
    IMPORT_COMMIT("POST", "/api/v1/waste/import/commit"),
    SOURCES("GET", "/api/v1/waste/sources"),
    SOURCE_CREATE("POST", "/api/v1/waste/sources"),
    SOURCE_GET("GET", "/api/v1/waste/sources/{id}"),
    SOURCE_RENAME("PUT", "/api/v1/waste/sources/{id}"),
    SOURCE_DELETE("DELETE", "/api/v1/waste/sources/{id}"),
    SOURCE_REFRESH("POST", "/api/v1/waste/sources/{id}/refresh"),
    MAPPING_UPDATE("PUT", "/api/v1/waste/sources/{id}/mappings/{mappingId}"),
    REIMPORT_PREVIEW("POST", "/api/v1/waste/sources/{id}/reimport/preview"),
    REIMPORT_COMMIT("POST", "/api/v1/waste/sources/{id}/reimport/commit"),
    PROFILE_EXPORT("GET", "/api/v1/waste/sources/{id}/mapping-profile/export"),
    PROFILE_IMPORT_PREVIEW("POST", "/api/v1/waste/sources/{id}/mapping-profile/import/preview"),
    PROFILE_IMPORT_COMMIT("POST", "/api/v1/waste/sources/{id}/mapping-profile/import/commit"),
    REMINDER_SETTINGS("GET", "/api/v1/waste/reminder-settings"),
    REMINDER_SETTING_UPDATE("PUT", "/api/v1/waste/reminder-settings/{typeId}"),
    FEED("GET", "/api/v1/waste/feed"),
}
