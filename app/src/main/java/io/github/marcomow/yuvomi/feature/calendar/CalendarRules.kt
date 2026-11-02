package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * What the calendar filter hides (CAL-R12, `state` of public/pages/calendar.js): layers,
 * people, "assigned to me" and hidden calendars. Kept per server and member on this device,
 * like the web page keeps it in `localStorage`.
 */
@Serializable
data class CalendarFilter(
    val view: String = CalendarView.MONTH.key,
    val layerHolidays: Boolean = true,
    val layerSchool: Boolean = true,
    val layerBirthdays: Boolean = true,
    val layerSchedule: Boolean = true,
    /** Opt-in: off by default, like the web page (#1063 Phase 5). */
    val layerWaste: Boolean = false,
    val assignedToMe: Boolean = false,
    /** Member ids, or [UNASSIGNED]; empty means everybody. */
    val people: Set<String> = emptySet(),
    /** Source key to its remembered name and colour (`hiddenSources`, #1064). */
    val hiddenSources: Map<String, HiddenSource> = emptyMap(),
) {
    companion object {
        const val UNASSIGNED = "none"
    }
}

@Serializable
data class HiddenSource(val name: String = "", val color: String? = null)

/** A calendar or subscription for the filter sheet (`calendarSources()`). */
data class CalendarSource(val key: String, val name: String, val color: String?)

/** The rules of the web page that decide what shows and who may change it. */
object CalendarRules {

    // ── Colours (public/utils/event-color.js) ──

    const val FALLBACK_COLOR = "#8E8E93"

    /** The web page's palette (`EVENT_COLORS`) with the names it gives them. */
    val EVENT_COLORS = listOf(
        "#587DCE" to "calendar.colorBlue", "#3CA368" to "calendar.colorGreen", "#E0843E" to "calendar.colorOrange",
        "#CE5053" to "calendar.colorRed", "#8156C0" to "calendar.colorPurple", "#DB684C" to "calendar.colorCoral",
        "#3E9DCA" to "calendar.colorSkyBlue", "#D8B349" to "calendar.colorYellow", "#85868B" to "calendar.colorGray",
        "#279EA4" to "calendar.colorCyan",
    )

    /** The family tones of the shared colour picker (`AVATAR_COLORS`, public/utils/color.js). */
    val FAMILY_COLORS = listOf(
        "#78808C" to "colorPicker.gray", "#3B82F6" to "colorPicker.blue", "#16A34A" to "colorPicker.green",
        "#D97706" to "colorPicker.ocher", "#059669" to "colorPicker.emerald", "#D946EF" to "colorPicker.fuchsia",
        "#EF4444" to "colorPicker.red", "#0891B2" to "colorPicker.cyan", "#EA580C" to "colorPicker.orange",
        "#EC4899" to "colorPicker.pink",
    )
    const val USER_COLOR_DEFAULT = "#0891B2"

    /** `resolveEventColor()`: own colour, then the primary assignee's, then the calendar's. */
    fun color(ev: CalendarEvent): String {
        ev.color?.let { return it }
        if (ev.assignedUsers.isNotEmpty()) {
            val primary = ev.assignedUsers.firstOrNull { it.id == ev.assignedTo } ?: ev.assignedUsers.first()
            return primary.color ?: FALLBACK_COLOR
        }
        return ev.calColor ?: ev.localCalendarColor ?: FALLBACK_COLOR
    }

    /** `pickerColors()`: the palette, plus the event's own colour when it is not in it (#856). */
    fun pickerColors(current: String?): List<String> {
        val palette = EVENT_COLORS.map { it.first }
        val own = current?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }
        return if (own != null && palette.none { it.equals(own, ignoreCase = true) }) palette + own else palette
    }

    // ── Birthdays (public/utils/birthday-event.js) ──

    /** `localizeBirthdayEvent()`: a birthday's title and description in the UI language. */
    fun localize(ev: CalendarEvent, s: Strings, dateText: (String) -> String): CalendarEvent {
        val name = ev.birthdayName ?: return ev
        if (ev.birthdayEventKind == "name_day") {
            return ev.copy(
                title = s.t("birthdays.nameDayCalendarEventTitle", "name" to name),
                description = s.t("birthdays.nameDayCalendarEventDescription", "name" to name),
            )
        }
        return ev.copy(
            title = s.t("birthdays.calendarEventTitle", "name" to name),
            description = ev.birthdayDate?.let { s.t("birthdays.calendarEventDescription", "name" to name, "date" to dateText(it)) }
                ?: s.t("birthdays.calendarEventDescriptionNoDate", "name" to name),
        )
    }

    // ── Filters ──

    /** `eventSourceKey()`: the event's calendar or subscription, or null for none. */
    fun sourceKey(ev: CalendarEvent): String? {
        ev.subscriptionId?.let { return "sub:$it" }
        (ev.sourceCalendarRefId ?: ev.calendarRefId)?.let { return "cal:$it" }
        ev.localCalendarId?.let { return "local:$it" }
        return null
    }

    /** `calendarSources()`: local calendars, the sources of loaded events, and every hidden one. */
    fun sources(locals: List<LocalCalendar>, events: List<CalendarEvent>, hidden: Map<String, HiddenSource>): List<CalendarSource> {
        val out = LinkedHashMap<String, CalendarSource>()
        locals.forEach { out["local:${it.id}"] = CalendarSource("local:${it.id}", it.name, it.color) }
        for (ev in events) {
            val key = sourceKey(ev) ?: continue
            val name = ev.sourceCalendarName ?: ev.calName ?: ev.localCalendarName ?: ""
            val color = ev.sourceCalendarColor ?: ev.calColor ?: ev.localCalendarColor
            val known = out[key]
            out[key] = if (known == null) CalendarSource(key, name, color)
            else known.copy(name = known.name.ifEmpty { name }, color = known.color ?: color)
        }
        hidden.forEach { (key, h) ->
            val known = out[key]
            out[key] = if (known == null) CalendarSource(key, h.name, h.color)
            else known.copy(name = known.name.ifEmpty { h.name }, color = known.color ?: h.color)
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    /** `belongsToMe()` and `matchesPeopleFilter()` together (`passesPersonFilters`). */
    fun passesPeople(assignees: List<Int>, filter: CalendarFilter, me: Int?, birthday: Boolean = false): Boolean {
        if (birthday) return true
        if (filter.assignedToMe && me != null && me !in assignees) return false
        if (filter.people.isEmpty()) return true
        if (assignees.isEmpty()) return CalendarFilter.UNASSIGNED in filter.people
        return assignees.any { it.toString() in filter.people }
    }

    /** `eventsOnDay()` filter: layer, source and people. */
    fun shows(ev: CalendarEvent, filter: CalendarFilter, me: Int?): Boolean {
        if (ev.isBirthday && !filter.layerBirthdays) return false
        val key = sourceKey(ev)
        if (key != null && key in filter.hiddenSources) return false
        return passesPeople(ev.assignedUsers.map { it.id }, filter, me, ev.isBirthday)
    }

    /** `filterTasksForCalendar()`: open tasks with a due date. */
    fun calendarTasks(tasks: List<CalendarTask>): List<CalendarTask> =
        tasks.filter { it.dueDate != null && it.status != "done" && it.archivedAt == null }

    /** `holidaysOnDay()`. */
    fun holidaysOn(holidays: List<Holiday>, day: LocalDate, filter: CalendarFilter): List<Holiday> {
        val key = day.toString()
        return holidays.filter {
            it.startDate <= key && it.endDate >= key &&
                !(it.type == "public" && !filter.layerHolidays) && !(it.type == "school" && !filter.layerSchool)
        }
    }

    /** People toggle with "everybody" collapsing back to the empty set (`toggleCalendarPersonChip`). */
    fun togglePerson(filter: CalendarFilter, key: String, memberCount: Int): CalendarFilter {
        val people = if (key in filter.people) filter.people - key else filter.people + key
        return filter.copy(people = if (people.size == memberCount + 1) emptySet() else people)
    }

    /** `activeFilterCount()`. */
    fun activeCount(filter: CalendarFilter, prefs: CalendarPrefs, scheduleOn: Boolean): Int {
        var n = 0
        if (filter.assignedToMe) n++
        if (filter.people.isNotEmpty()) n++
        if (filter.hiddenSources.isNotEmpty()) n++
        if (prefs.holidayShowPublic && !filter.layerHolidays) n++
        if (prefs.holidayShowSchool && !filter.layerSchool) n++
        if (!filter.layerBirthdays) n++
        if (scheduleOn && !filter.layerSchedule) n++
        return n
    }

    // ── Who may change an event (R23, D5) ──

    /** `ownsEvent()`: created or assigned. */
    fun owns(ev: CalendarEvent, me: Int?): Boolean {
        if (me == null) return false
        return ev.createdBy == me || ev.assignedTo == me || ev.assignedUsers.any { it.id == me }
    }

    /**
     * `mayChangeEvent()` through `canChangeRecord('calendar', own)`: write access, and the
     * event is one's own unless `calendar_edit_others` allows the others' too.
     */
    fun mayChange(ev: CalendarEvent, me: Int?, write: Boolean, editOthers: Boolean): Boolean =
        write && (editOthers || owns(ev, me))
}

/**
 * The recurring-scope arithmetic of public/utils/recurrence-scope.js (#532, #975, #2034):
 * which endpoint "only this", "this and following" and "whole series" use, and the legacy
 * path for series that cannot hold linked overrides.
 */
object RecurrenceScope {
    enum class Scope(val key: String, val labelKey: String) {
        THIS("this", "calendar.recurringScopeThis"),
        FOLLOWING("following", "calendar.recurringScopeFollowing"),
        SERIES("series", "calendar.recurringScopeSeries"),
    }

    fun isLocalSeries(ev: CalendarEvent): Boolean = ev.isLocalRecurringSeries
    fun canOverride(ev: CalendarEvent): Boolean = ev.canOverrideOccurrence
    fun canEditOccurrence(ev: CalendarEvent): Boolean = ev.canOverrideOccurrence || ev.canDetachOccurrence
    fun requiresWholeSeriesConfirmation(ev: CalendarEvent): Boolean = isLocalSeries(ev) && !canEditOccurrence(ev)
    fun isExternalSeries(ev: CalendarEvent): Boolean = !ev.recurrenceRule.isNullOrEmpty() && !isLocalSeries(ev)

    /** At the start of a series "this and following" is the whole series. */
    fun followingMeansWholeSeries(ev: CalendarEvent): Boolean = !ev.isRecurringInstance || ev.isSeriesStart

    /** Whether the legacy path (new event plus exception, or truncation) applies. */
    fun usesLegacyPath(ev: CalendarEvent): Boolean = ev.canDetachOccurrence && !ev.canOverrideOccurrence

    private val RECURRENCE_ID = Regex("^\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}:\\d{2}Z?)?$")

    fun recurrenceId(ev: CalendarEvent): String? = ev.recurrenceId?.takeIf { RECURRENCE_ID.matches(it) }

    /** The body of `POST /calendar/:id/exceptions` (`occurrenceExceptionBody`). */
    fun exceptionBody(recurrenceId: String): Map<String, String> =
        if (recurrenceId.length == 10) mapOf("recurrence_id" to recurrenceId, "date" to recurrenceId) else mapOf("recurrence_id" to recurrenceId)

    /**
     * `recurrenceSeriesDay()`: the day the rule counts the occurrence on. A floating or date
     * key is its own day; an instant key falls on the day of the series zone.
     */
    fun seriesDay(ev: CalendarEvent, id: String): String {
        if (!id.endsWith("Z")) return id.take(10)
        val zone = ev.tzid?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        if (zone != null) {
            return runCatching { java.time.Instant.parse(id).atZone(zone).toLocalDate().toString() }.getOrDefault(id.take(10))
        }
        val start = ev.startDatetime
        return if (Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(start) && start.length >= 10) start.take(10) else id.take(10)
    }

    /**
     * `truncateRuleBefore()`: the rule ends the day before [occurrenceDay]; FREQ, INTERVAL,
     * BYDAY and a month-end BYMONTHDAY survive, UNTIL and COUNT are replaced.
     */
    fun truncateRuleBefore(rule: String?, occurrenceDay: String): String? {
        if (rule.isNullOrEmpty() || !Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(occurrenceDay)) return null
        val raw = rule.removePrefix("RRULE:")
        var freq: String? = null
        var interval: String? = null
        var byday: String? = null
        var monthEnd = false
        for (segment in raw.split(';')) {
            val eq = segment.indexOf('=')
            if (eq == -1) continue
            val value = segment.substring(eq + 1)
            when (segment.substring(0, eq).uppercase()) {
                "FREQ" -> freq = value
                "INTERVAL" -> interval = value
                "BYDAY" -> byday = value
                "BYMONTHDAY" -> if (value.trim() == "-1") monthEnd = true
            }
        }
        val f = freq ?: return null
        val keep = mutableListOf("FREQ=$f")
        if (interval != null && interval != "1") keep += "INTERVAL=$interval"
        if (byday != null) keep += "BYDAY=$byday"
        if (monthEnd && f.uppercase() == "MONTHLY") keep += "BYMONTHDAY=-1"
        val until = LocalDate.parse(occurrenceDay.take(10)).minusDays(1)
        keep += "UNTIL=${until.toString().replace("-", "")}"
        return keep.joinToString(";")
    }

    /** `seriesEndsBeforeStart()`: UNTIL before the start day (#1607). */
    fun seriesEndsBeforeStart(rule: String?, startDate: String): Boolean {
        val start = startDate.take(10)
        val m = Regex("(?:^|[;:])UNTIL=(\\d{4})(\\d{2})(\\d{2})", RegexOption.IGNORE_CASE).find(rule ?: "") ?: return false
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(start)) return false
        return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}" < start
    }

    /**
     * `shiftSeriesStart()`: a whole-series edit moves the series start by the shift the form
     * gave the opened occurrence, so the series is not re-anchored. Wall times, `YYYY-MM-DDTHH:MM`.
     */
    fun shiftSeriesStart(masterStart: String, instanceStart: String, editedStart: String, allDay: Boolean, dates: CalendarDates): String {
        if (allDay) {
            val delta = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(instanceStart.take(10)), LocalDate.parse(editedStart.take(10)))
            return LocalDate.parse(masterStart.take(10)).plusDays(delta).toString()
        }
        val instance = dates.dateTime(instanceStart) ?: return editedStart
        val edited = dates.dateTime(editedStart) ?: return editedStart
        val master = dates.dateTime(masterStart) ?: return editedStart
        val shifted = master.plus(java.time.Duration.between(instance, edited))
        return wall(shifted)
    }

    /** `shiftEndForStart()`: the end that keeps the form's duration from [newStart]. */
    fun shiftEndForStart(newStart: String, editedStart: String, editedEnd: String?, allDay: Boolean, dates: CalendarDates): String? {
        if (editedEnd == null) return null
        if (allDay) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(editedStart.take(10)), LocalDate.parse(editedEnd.take(10)))
            return LocalDate.parse(newStart.take(10)).plusDays(days).toString()
        }
        val start = dates.dateTime(editedStart) ?: return editedEnd
        val end = dates.dateTime(editedEnd) ?: return editedEnd
        val from = dates.dateTime(newStart) ?: return editedEnd
        return wall(from.plus(java.time.Duration.between(start, end)))
    }

    fun wall(t: java.time.LocalDateTime): String = "%04d-%02d-%02dT%02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
}

/**
 * The reminder rows of the event form (`renderCalendarReminderSection` and friends,
 * public/pages/calendar.js): lead times before the event start, at most five. The start is a
 * wall time in the household zone; an all-day event counts from 09:00 (`reminderStartValue`).
 * `remind_at` is naive UTC (`YYYY-MM-DDTHH:MM:SS`).
 */
object EventReminders {
    const val MAX = 5
    const val AFTER_START = "after_start"
    val PRESETS = listOf(0, 15, 60, 1440, 2880, 10080, 20160)

    /** `REMINDER_OFFSETS()`: value to label key; `custom` opens amount and unit. */
    val OFFSET_KEYS = listOf(
        "0" to "reminders.offsetAtTime", "15" to "reminders.offset15min", "60" to "reminders.offset1hour",
        "1440" to "reminders.offset1day", "2880" to "reminders.offset2days", "10080" to "reminders.offset1week",
        "20160" to "reminders.offset2weeks", "custom" to "reminders.offsetCustom",
    )
    val UNITS = listOf("minutes" to "reminders.customMinutes", "hours" to "reminders.customHours", "days" to "reminders.customDays", "weeks" to "reminders.customWeeks")

    /** One row of the form. [storedAt] is set only for a reminder after the start (#1260). */
    data class Row(val offset: String = "0", val amount: Int = 1, val unit: String = "days", val storedAt: String? = null) {
        val keepsStoredInstant: Boolean get() = offset == AFTER_START && storedAt != null
    }

    fun anchorMillis(start: String, dates: CalendarDates): Long? {
        val value = if (start.contains('T')) start else "${start.take(10)}T09:00"
        return dates.dateTime(value)?.atZone(dates.zone)?.toInstant()?.toEpochMilli()
    }

    fun remindAtMillis(remindAt: String): Long? = runCatching {
        java.time.LocalDateTime.parse(remindAt.replace(' ', 'T').removeSuffix("Z").take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    /** `reminderRowForLead()`. */
    fun rowForLead(diffMin: Long, storedAt: String? = null): Row {
        if (diffMin < 0) return Row(AFTER_START, 1, "days", storedAt)
        val preset = PRESETS.firstOrNull { it.toLong() == diffMin }
        val (amount, unit) = when {
            diffMin >= 10080 && diffMin % 10080 == 0L -> diffMin / 10080 to "weeks"
            diffMin >= 1440 && diffMin % 1440 == 0L -> diffMin / 1440 to "days"
            diffMin >= 60 && diffMin % 60 == 0L -> diffMin / 60 to "hours"
            else -> maxOf(diffMin, 1) to "minutes"
        }
        return Row(preset?.toString() ?: "custom", amount.toInt(), unit)
    }

    /** `reminderRowFromReminder()`: a stored reminder as a row, against the event's anchor. */
    fun rowFrom(ev: CalendarEvent, r: StoredReminder, dates: CalendarDates): Row {
        val anchor = anchorMillis(ev.reminderAnchorStart ?: ev.startDatetime, dates) ?: return Row()
        val at = remindAtMillis(r.remindAt) ?: return Row()
        return rowForLead(Math.round((anchor - at) / 60000.0), r.remindAt)
    }

    fun customMinutes(amount: Int, unit: String): Int {
        val v = maxOf(amount, 1)
        return when (unit) {
            "weeks" -> v * 10080
            "days" -> v * 1440
            "hours" -> v * 60
            else -> v
        }
    }

    /** `canonicalReminderOffsets()`: distinct lead times in form order, at most five. */
    fun offsets(rows: List<Row>): List<Int> {
        val out = mutableListOf<Int>()
        for (row in rows) {
            val offset = when (row.offset) {
                "", AFTER_START -> continue
                "custom" -> customMinutes(row.amount, row.unit)
                else -> row.offset.toIntOrNull() ?: continue
            }
            if (offset < 0 || offset in out) continue
            out += offset
            if (out.size == MAX) break
        }
        return out
    }

    /**
     * The start the lead times were read against, and the stored instants, when that is not the
     * start the save computes from: an external series opened at an occurrence (2.84.0).
     */
    data class Untouched(val anchorStart: String, val storedAts: List<String>)

    /** `naiveUtc()` (utils/reminder-offset.js): an instant as `YYYY-MM-DDTHH:MM:SS` in UTC. */
    fun naiveUtc(value: String?): String? {
        val text = value?.trim()?.replace(' ', 'T').orEmpty()
        if (text.isEmpty()) return null
        val instant = runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
            ?: return null
        return utcKey(instant.toEpochMilli())
    }

    private fun utcKey(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDateTime().toString().let {
            if (it.length == 16) "$it:00" else it.take(19)
        }

    /**
     * `reminderTimesFromRows()`: what `PUT /reminders` gets; a row after the start keeps its
     * instant. With [untouched], a row whose lead time still lands on a stored instant from the
     * start it was read against keeps that instant instead of sliding onto the opened occurrence
     * (CAL-R29, upstream 2.84.0).
     */
    fun times(rows: List<Row>, anchorStart: String, dates: CalendarDates, untouched: Untouched? = null): List<String> {
        val anchor = anchorMillis(anchorStart, dates)
        val readAnchor = untouched?.let { anchorMillis(it.anchorStart, dates) }
        val out = mutableListOf<String>()
        for (row in rows) {
            val at = if (row.keepsStoredInstant) {
                row.storedAt!!.replace(' ', 'T').removeSuffix("Z").take(19)
            } else {
                val offset = offsets(listOf(row)).firstOrNull() ?: continue
                val kept = readAnchor?.let { utcKey(it - offset * 60000L) }
                if (kept != null && kept in untouched.storedAts) {
                    kept
                } else {
                    val a = anchor ?: continue
                    utcKey(a - offset * 60000L)
                }
            }
            if (at in out) continue
            out += at
            if (out.size == MAX) break
        }
        return out
    }

    /** `occurrenceReminderOffsets()`: null when a row has no lead time (the endpoints take only those). */
    fun occurrenceOffsets(rows: List<Row>): List<Int>? = if (rows.any { it.keepsStoredInstant }) null else offsets(rows)

    /** `reminderRowsUnchanged()`. */
    fun unchanged(rows: List<Row>, loaded: List<Row>): Boolean {
        if (rows.size != loaded.size) return false
        return rows.indices.all { i ->
            val now = rows[i]
            val before = loaded[i]
            if (before.storedAt != null && before.offset == AFTER_START) now.keepsStoredInstant && now.storedAt == before.storedAt
            else !now.keepsStoredInstant && offsets(listOf(now)).firstOrNull() == offsets(listOf(before)).firstOrNull()
        }
    }

    /** `reminderSummary()`: a label per reminder, the moment for custom ones or after the start. */
    fun summary(ev: CalendarEvent, reminders: List<StoredReminder>, dates: CalendarDates, s: Strings, moment: (String) -> String): String =
        reminders.mapNotNull { r ->
            val row = rowFrom(ev, r, dates)
            val key = OFFSET_KEYS.firstOrNull { it.first == row.offset && it.first != "custom" }?.second
            key?.let { s.t(it) } ?: moment(r.remindAt).takeIf { it.isNotEmpty() }
        }.joinToString(", ")
}
