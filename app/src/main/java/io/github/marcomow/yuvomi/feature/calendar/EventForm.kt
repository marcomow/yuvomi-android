package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.feature.tasks.RRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The event form (`buildEventModalContent`, `saveEvent`, public/pages/calendar.js) as plain
 * data: what it shows, how it checks, and the body it sends (CAL-R15 to R18).
 */
data class EventForm(
    val title: String = "",
    val localCalendarId: Int? = null,
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val assignees: List<Int> = emptyList(),
    val rule: RRule.Parts = RRule.Parts(),
    val remindersOn: Boolean = false,
    val reminders: List<EventReminders.Row> = emptyList(),
    val location: String = "",
    val description: String = "",
    val visibility: String = "all",
    val countdown: Boolean = false,
    /** Null: no colour of its own, the assignee's is borrowed (#891). */
    val color: String? = null,
    val icon: String = "calendar",
    /** `""`, `google:<id>`, `caldav:<account>|<url>`, `outlook:<account>|<id>`. */
    val syncTarget: String = "",
    val removeAttachment: Boolean = false,
) {
    /** `eventStartFromForm()`. */
    val start: String get() = if (allDay) startDate.toString() else "${startDate}T${hm(startTime)}"

    /** `eventEndFromForm()`. */
    val end: String get() = if (allDay) endDate.toString() else "${endDate}T${hm(endTime)}"

    /** The sync target sends the event to Google or CalDAV: it leaves the local calendars. */
    val leavesLocalCalendars: Boolean get() = syncTarget.startsWith("google:") || syncTarget.startsWith("caldav:")

    enum class Field { TITLE, END, UNTIL, REMINDERS }

    /** The rule to save: the stored one verbatim while the form shows what it says (#756). */
    fun ruleToSave(stored: String?): String? = RRule.resolve(rule, stored)

    /**
     * `saveEvent()`'s checks, all at once: a title, an end not before the start, and a repeat
     * end not before the start when this save touches the rule or the start day (#1607).
     */
    fun errors(existing: CalendarEvent?, dates: CalendarDates): Map<Field, String> = buildMap {
        if (title.isBlank()) put(Field.TITLE, "calendar.titleRequired")
        if (end < start && !(allDay && endDate == startDate)) put(Field.END, "calendar.endBeforeStart")
        val rule = ruleToSave(existing?.recurrenceRule)
        val touched = existing == null || rule != existing.recurrenceRule ||
            startDate != dates.localDate(existing.startDatetime)
        if (touched && RecurrenceScope.seriesEndsBeforeStart(rule, start)) put(Field.UNTIL, "calendar.recurrenceEndBeforeStart")
    }

    /** The body of every event write (`saveEvent()` before the scope decides). */
    fun body(existing: CalendarEvent?, calendarsOffered: Boolean): JsonObject = buildJsonObject {
        put("title", title.trim())
        put("description", description.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("start_datetime", start)
        put("end_datetime", end)
        put("all_day", allDay)
        put("location", location.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("color", color?.let { JsonPrimitive(it) } ?: JsonNull)
        put("icon", icon)
        put("assigned_to", JsonArray(assignees.map { JsonPrimitive(it) }))
        val local = if (leavesLocalCalendars || !calendarsOffered) null else localCalendarId
        if (local != null) put("local_calendar_id", local)
        put("visibility", visibility)
        put("countdown", countdown)
        put("recurrence_rule", ruleToSave(existing?.recurrenceRule)?.let { JsonPrimitive(it) } ?: JsonNull)
        val target = syncTarget
        put("target_google_calendar_id", if (target.startsWith("google:")) JsonPrimitive(target.removePrefix("google:")) else JsonNull)
        val caldav = target.takeIf { it.startsWith("caldav:") }?.removePrefix("caldav:")?.split('|', limit = 2)
        put("target_caldav_account_id", caldav?.getOrNull(0)?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("target_caldav_calendar_url", caldav?.getOrNull(1)?.let { JsonPrimitive(it) } ?: JsonNull)
        val outlook = target.takeIf { it.startsWith("outlook:") }?.removePrefix("outlook:")?.split('|', limit = 2)
        put("target_outlook_account_id", outlook?.getOrNull(0)?.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("target_outlook_calendar_id", outlook?.getOrNull(1)?.let { JsonPrimitive(it) } ?: JsonNull)
        if (removeAttachment) put("remove_attachment", true)
    }

    /** The reminder rows as the save sees them: none while the switch is off. */
    val activeReminders: List<EventReminders.Row> get() = if (remindersOn) reminders else emptyList()

    /** Moves the start and keeps the duration (`wireDurationMemory`, #441). */
    fun withStart(date: LocalDate, time: LocalTime): EventForm {
        val before = LocalDateTime.of(startDate, startTime)
        val after = LocalDateTime.of(date, time)
        val duration = java.time.Duration.between(before, LocalDateTime.of(endDate, endTime))
        val newEnd = after.plus(if (duration.isNegative) java.time.Duration.ZERO else duration)
        return copy(startDate = date, startTime = time, endDate = newEnd.toLocalDate(), endTime = newEnd.toLocalTime())
    }

    companion object {
        fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

        /** The form for a new event (`buildEventModalContent({ mode: 'create' })`). */
        fun create(
            date: LocalDate,
            time: LocalTime,
            durationMin: Int,
            me: Int?,
            prefs: CalendarPrefs,
            defaultCalendar: Int?,
        ): EventForm {
            val start = LocalDateTime.of(date, time)
            val end = start.plusMinutes(durationMin.toLong())
            val rows = prefs.defaultReminders.filter { it >= 0 }.distinct().take(EventReminders.MAX).map { EventReminders.rowForLead(it.toLong()) }
            return EventForm(
                localCalendarId = defaultCalendar,
                startDate = date, startTime = time, endDate = end.toLocalDate(), endTime = end.toLocalTime(),
                assignees = if (prefs.defaultAssignMe && me != null) listOf(me) else emptyList(),
                remindersOn = rows.isNotEmpty(),
                reminders = rows,
            )
        }

        /** The form for an existing event or occurrence (`buildEventModalContent({ mode: 'edit' })`). */
        fun edit(ev: CalendarEvent, reminders: List<StoredReminder>, dates: CalendarDates, durationMin: Int): EventForm {
            val startDate = dates.localDate(ev.startDatetime) ?: LocalDate.now()
            val startTime = if (ev.startDatetime.length > 10) dates.localTime(ev.startDatetime) else LocalTime.of(9, 0)
            val derived = LocalDateTime.of(startDate, startTime).plusMinutes(durationMin.toLong())
            val endRaw = ev.endDatetime
            val endDate = endRaw?.let { dates.localDate(it) } ?: derived.toLocalDate()
            val endTime = if (endRaw != null && endRaw.length > 10) dates.localTime(endRaw) else derived.toLocalTime()
            val rows = reminders.take(EventReminders.MAX).map { EventReminders.rowFrom(ev, it, dates) }
            val visibility = ev.visibility.ifEmpty { "all" }
            return EventForm(
                title = ev.title,
                localCalendarId = ev.localCalendarId,
                allDay = ev.allDay,
                startDate = startDate, startTime = startTime, endDate = endDate, endTime = endTime,
                assignees = ev.assignedUsers.map { it.id }.ifEmpty { listOfNotNull(ev.assignedTo) },
                rule = RRule.parse(ev.recurrenceRule),
                remindersOn = rows.isNotEmpty(),
                reminders = rows,
                location = ev.location.orEmpty(),
                description = ev.description.orEmpty(),
                visibility = visibility,
                countdown = ev.countdown,
                color = ev.color,
                icon = CalendarIcons.normalise(ev.icon),
                syncTarget = when {
                    ev.targetGoogleCalendarId != null -> "google:${ev.targetGoogleCalendarId}"
                    ev.targetCaldavAccountId != null && ev.targetCaldavCalendarUrl != null -> "caldav:${ev.targetCaldavAccountId}|${ev.targetCaldavCalendarUrl}"
                    ev.targetOutlookAccountId != null && ev.targetOutlookCalendarId != null -> "outlook:${ev.targetOutlookAccountId}|${ev.targetOutlookCalendarId}"
                    else -> ""
                },
            )
        }
    }
}
