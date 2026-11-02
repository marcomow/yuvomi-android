package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A person on an event (`ASSIGNED_USERS_SQL`, server/routes/calendar/helpers.js). */
@Serializable
data class EventPerson(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    val color: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
)

/**
 * An event or one expanded occurrence of a series, as `serializeEvent()` answers it
 * (server/routes/calendar/helpers.js). Occurrences of a series carry the series' `id`; the
 * recurrence fields say which occurrence it is and what this member may do with it.
 */
@Serializable
data class CalendarEvent(
    val id: Int,
    val title: String = "",
    val description: String? = null,
    @SerialName("start_datetime") val startDatetime: String = "",
    @SerialName("end_datetime") val endDatetime: String? = null,
    @SerialName("all_day") val allDay: FlexBool = false,
    val location: String? = null,
    val color: String? = null,
    val icon: String? = null,
    @SerialName("assigned_to") val assignedTo: Int? = null,
    @SerialName("assigned_name") val assignedName: String? = null,
    @SerialName("assigned_users") val assignedUsers: List<EventPerson> = emptyList(),
    @SerialName("created_by") val createdBy: Int? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("recurrence_rule") val recurrenceRule: String? = null,
    val visibility: String = "all",
    val countdown: FlexBool = false,
    @SerialName("external_source") val externalSource: String? = null,
    @SerialName("user_modified") val userModified: FlexBool = false,
    @SerialName("subscription_id") val subscriptionId: Int? = null,
    @SerialName("calendar_ref_id") val calendarRefId: Int? = null,
    @SerialName("source_calendar_ref_id") val sourceCalendarRefId: Int? = null,
    @SerialName("source_calendar_name") val sourceCalendarName: String? = null,
    @SerialName("source_calendar_color") val sourceCalendarColor: String? = null,
    @SerialName("cal_name") val calName: String? = null,
    @SerialName("cal_color") val calColor: String? = null,
    @SerialName("local_calendar_id") val localCalendarId: Int? = null,
    @SerialName("local_calendar_name") val localCalendarName: String? = null,
    @SerialName("local_calendar_color") val localCalendarColor: String? = null,
    @SerialName("target_google_calendar_id") val targetGoogleCalendarId: String? = null,
    @SerialName("target_caldav_account_id") val targetCaldavAccountId: Int? = null,
    @SerialName("target_caldav_calendar_url") val targetCaldavCalendarUrl: String? = null,
    @SerialName("target_outlook_account_id") val targetOutlookAccountId: Int? = null,
    @SerialName("target_outlook_calendar_id") val targetOutlookCalendarId: String? = null,
    val tzid: String? = null,
    // Birthdays shown as events (#524): the client localises their title.
    @SerialName("birthday_name") val birthdayName: String? = null,
    @SerialName("birthday_date") val birthdayDate: String? = null,
    @SerialName("birthday_event_kind") val birthdayEventKind: String? = null,
    @SerialName("housekeeping_visit_id") val housekeepingVisitId: Int? = null,
    // Attachment (#1358): a document in Documents, or nothing for who may not see it.
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_mime") val attachmentMime: String? = null,
    @SerialName("attachment_document_id") val attachmentDocumentId: Int? = null,
    @SerialName("attachment_locked") val attachmentLocked: Boolean? = null,
    @SerialName("attachment_data") val attachmentData: String? = null,
    // Recurrence metadata (`recurrenceMetadata()`).
    @SerialName("series_id") val seriesId: Int? = null,
    @SerialName("recurrence_id") val recurrenceId: String? = null,
    @SerialName("is_recurring_instance") val isRecurringInstance: FlexBool = false,
    @SerialName("is_series_start") val isSeriesStart: FlexBool = false,
    @SerialName("is_occurrence_override") val isOccurrenceOverride: FlexBool = false,
    @SerialName("is_local_recurring_series") val isLocalRecurringSeries: Boolean = false,
    @SerialName("can_override_occurrence") val canOverrideOccurrence: Boolean = false,
    @SerialName("can_detach_occurrence") val canDetachOccurrence: Boolean = false,
    @SerialName("reminder_owner_id") val reminderOwnerId: Int? = null,
    @SerialName("reminder_anchor_start") val reminderAnchorStart: String? = null,
) {
    val isBirthday: Boolean get() = birthdayName != null
    val hasAttachment: Boolean get() = attachmentDocumentId != null || !attachmentData.isNullOrEmpty()

    /** One key per occurrence: series id and its start (two occurrences share the id). */
    val occurrenceKey: String get() = "$id@$startDatetime"
}

/** A local calendar (`serializeCalendar()`, server/services/local-calendars.js). */
@Serializable
data class LocalCalendar(
    val id: Int,
    val name: String = "",
    val color: String = "#007AFF",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("feed_url") val feedUrl: String? = null,
    @SerialName("event_count") val eventCount: Int = 0,
    @SerialName("uploads_to_icloud") val uploadsToIcloud: Boolean = false,
)

/** `GET /calendar/holidays`: a public or school holiday (`holidays.getForRange`). */
@Serializable
data class Holiday(
    val id: Int = 0,
    val type: String = "public",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    val name: String = "",
    val color: String? = null,
)

@Serializable
data class ShiftType(
    val name: String = "",
    @SerialName("short_code") val shortCode: String? = null,
    val color: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
)

/** One resolved schedule entry (`scheduleData()`, server/services/schedule.js). */
@Serializable
data class ScheduleEntry(
    @SerialName("date_key") val dateKey: String = "",
    @SerialName("user_id") val userId: Int? = null,
    val source: String? = null,
    val note: String? = null,
    @SerialName("shift_type") val shiftType: ShiftType? = null,
)

/** One waste collection (`getOccurrences()`, waste store). */
@Serializable
data class WasteOccurrence(
    @SerialName("date_key") val dateKey: String = "",
    @SerialName("type_id") val typeId: Int? = null,
    @SerialName("type_name") val typeName: String = "",
    @SerialName("type_color") val typeColor: String? = null,
    @SerialName("deep_link") val deepLink: String? = null,
)

/** A task with a due date, as the task layer shows it (`filterTasksForCalendar`). */
@Serializable
data class CalendarTask(
    val id: Int,
    val title: String = "",
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    val status: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    val priority: String? = null,
    @SerialName("assigned_users") val assignedUsers: List<EventPerson> = emptyList(),
)

/** One stored reminder (`GET /reminders/all`). `remind_at` is naive UTC. */
@Serializable
data class StoredReminder(
    val id: Int = 0,
    @SerialName("remind_at") val remindAt: String = "",
)

@Serializable
data class GoogleTarget(val id: String, val summary: String? = null)

@Serializable
data class CaldavTarget(
    val accountId: Int,
    val accountName: String = "",
    val calendarUrl: String,
    val calendarName: String? = null,
)

@Serializable
data class OutlookTarget(
    val accountId: Int,
    val accountName: String = "",
    val calendarId: String,
    val calendarName: String? = null,
)

/** `GET /calendar/sync-targets` (server/routes/calendar/sync-targets.js). */
@Serializable
data class SyncTargets(
    val google: List<GoogleTarget> = emptyList(),
    val caldav: List<CaldavTarget> = emptyList(),
    val outlook: List<OutlookTarget> = emptyList(),
)

/** The fields of `GET /preferences` the calendar reads (`applyEventFormPreferences`). */
@Serializable
data class CalendarPrefs(
    @SerialName("week_start") val weekStart: String? = null,
    @SerialName("calendar_default_duration") val defaultDuration: Int? = null,
    @SerialName("calendar_default_reminders") val defaultReminders: List<Int> = emptyList(),
    @SerialName("calendar_default_assign_me") val defaultAssignMe: FlexBool = false,
    @SerialName("calendar_default_target") val defaultTarget: String? = null,
    @SerialName("holiday_show_public") val holidayShowPublic: FlexBool = false,
    @SerialName("holiday_show_school") val holidayShowSchool: FlexBool = false,
    @SerialName("holiday_public_color") val holidayPublicColor: String? = null,
    @SerialName("holiday_school_color") val holidaySchoolColor: String? = null,
)

/** A member of the household (`GET /family/members`). */
@Serializable
data class CalendarMember(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
)
