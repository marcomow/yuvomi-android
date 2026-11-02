package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.api.ApiOperation
import io.github.marcomow.yuvomi.core.api.Operation

/**
 * The upstream operations Calendar calls (docs/spec/calendar.md). Must match
 * `upstream/operations-used.d/calendar.txt` exactly (ContractFilesTest).
 *
 * Also used, from other enums: `Operation.PREFERENCES`, `Operation.TASKS_LIST` (the task layer)
 * and `Operation.FAMILY_MEMBERS` (the people of the pickers and filters).
 */
enum class CalendarOperation(override val method: String, override val template: String) : ApiOperation {
    EVENTS_LIST("GET", "/api/v1/calendar"),
    EVENT_CREATE("POST", "/api/v1/calendar"),
    EVENT_SEARCH("GET", "/api/v1/calendar/search"),
    HOLIDAYS("GET", "/api/v1/calendar/holidays"),
    EVENT_GET("GET", "/api/v1/calendar/{id}"),
    EVENT_UPDATE("PUT", "/api/v1/calendar/{id}"),
    EVENT_DELETE("DELETE", "/api/v1/calendar/{id}"),
    EVENT_RESET("POST", "/api/v1/calendar/{id}/reset"),
    EVENT_EXCEPTION("POST", "/api/v1/calendar/{id}/exceptions"),
    OCCURRENCE_UPDATE("PUT", "/api/v1/calendar/{seriesId}/occurrences/{recurrenceId}"),
    OCCURRENCE_DELETE("DELETE", "/api/v1/calendar/{seriesId}/occurrences/{recurrenceId}"),
    FOLLOWING_UPDATE("PUT", "/api/v1/calendar/{seriesId}/occurrences/{recurrenceId}/following"),
    FOLLOWING_DELETE("DELETE", "/api/v1/calendar/{seriesId}/occurrences/{recurrenceId}/following"),
    CALENDARS_LIST("GET", "/api/v1/calendar/calendars"),
    CALENDAR_CREATE("POST", "/api/v1/calendar/calendars"),
    CALENDARS_REORDER("PATCH", "/api/v1/calendar/calendars/reorder"),
    CALENDAR_UPDATE("PUT", "/api/v1/calendar/calendars/{id}"),
    CALENDAR_DELETE("DELETE", "/api/v1/calendar/calendars/{id}"),
    CALENDAR_FEED_REGENERATE("POST", "/api/v1/calendar/calendars/{id}/feed/regenerate"),
    CALENDAR_FEED_DELETE("DELETE", "/api/v1/calendar/calendars/{id}/feed"),

    // Outside /calendar: the layers and reminders the web page reads and writes.
    REMINDERS_ALL("GET", "/api/v1/reminders/all"),
    REMINDERS_SET("PUT", "/api/v1/reminders"),
    REMINDERS_CLEAR("DELETE", "/api/v1/reminders"),
    WASTE_OCCURRENCES("GET", "/api/v1/waste/occurrences"),
}
