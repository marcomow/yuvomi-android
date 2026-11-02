package io.github.marcomow.yuvomi.feature.calendar

import android.icu.text.DateFormat
import android.icu.util.ULocale
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Date

/**
 * The calendar's words for dates and times, in the UI language through ICU skeletons (the
 * web page's `formatMonthYear`, `dateText`, `dayHeading`, `timeSpanText`, `eventWhenText`).
 * Dates are formatted as they are, never shifted through a zone.
 */
class CalendarText(val s: Strings, private val dates: CalendarDates, private val today: LocalDate) {
    private val locale = ULocale.forLanguageTag(s.locale)

    private fun fmt(skeleton: String, d: LocalDateTime): String =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
            .format(Date.from(d.toInstant(ZoneOffset.UTC)))

    fun monthYear(d: LocalDate) = fmt("yMMMM", d.atStartOfDay())
    fun dayMonth(d: LocalDate) = fmt("MMMd", d.atStartOfDay())

    /** `dateText()`: the year only outside the current one, unless asked for. */
    fun date(d: LocalDate, year: Boolean = false) = fmt(if (year || d.year != today.year) "yMMMd" else "MMMd", d.atStartOfDay())
    fun weekdayShort(d: LocalDate) = fmt("EEE", d.atStartOfDay())
    fun weekdayNarrow(d: LocalDate) = fmt("EEEEE", d.atStartOfDay())
    fun weekdayLong(d: LocalDate) = fmt("EEEE", d.atStartOfDay())

    /** `dayHeadingText()`: "Saturday, Oct 24". */
    fun dayHeading(d: LocalDate, long: Boolean = true, year: Boolean = false) =
        "${if (long) weekdayLong(d) else weekdayShort(d)}, ${date(d, year)}"

    /** `dayHeading()`: today, tomorrow or yesterday, else null. */
    fun relative(d: LocalDate): String? = when (d) {
        today -> s.t("common.today")
        today.plusDays(1) -> s.t("common.tomorrow")
        today.minusDays(1) -> s.t("common.yesterday")
        else -> null
    }

    fun time(t: LocalTime) = fmt("jmm", LocalDate.of(2000, 1, 1).atTime(t))

    /** `clockText()`: a stored datetime's time in the household zone. */
    fun clock(value: String?) = time(dates.localTime(value))

    /** `timeSpanText()`: "10:00 - 11:30", or the start alone. */
    fun span(start: String, end: String?): String =
        if (end == null || !end.contains('T')) clock(start) else s.t("calendar.dayRangeLabel", "from" to clock(start), "to" to clock(end))

    /** `eventTimeText()` for the row of [day]: from, until, the span, or "All day". */
    fun rowTime(ev: CalendarEvent, day: LocalDate): String = when (dates.segment(ev, day)) {
        CalendarDates.Segment.ALL_DAY, CalendarDates.Segment.MIDDLE -> s.t("calendar.allDay")
        CalendarDates.Segment.START -> s.t("calendar.spanFrom", "time" to clock(ev.startDatetime))
        CalendarDates.Segment.END -> s.t("calendar.spanUntil", "time" to clock(ev.endDatetime))
        CalendarDates.Segment.SINGLE -> span(ev.startDatetime, ev.endDatetime)
    }

    /** `multiDayPositionText()`: "Day 2 of 3". */
    fun position(ev: CalendarEvent, day: LocalDate): String? =
        dates.position(ev, day)?.let { (n, total) -> s.t("calendar.multiDayPosition", "day" to n, "total" to total) }

    /** `eventWhenRelative()` and `eventWhenText()`: the read view's head. */
    fun whenText(ev: CalendarEvent): String {
        val start = dates.startDate(ev)
        if (dates.isMultiDay(ev)) {
            if (ev.allDay) return "${s.t("calendar.dayRangeLabel", "from" to date(start), "to" to date(dates.endDate(ev)))} · ${s.t("calendar.allDay")}"
            val end = ev.endDatetime!!
            return s.t(
                "calendar.dayRangeLabel",
                "from" to "${date(start)} ${clock(ev.startDatetime)}",
                "to" to "${date(dates.localDate(end) ?: start)} ${clock(end)}",
            )
        }
        val day = relative(start) ?: dayHeading(start)
        if (ev.allDay || !ev.startDatetime.contains('T')) return "$day · ${s.t("calendar.allDay")}"
        return "$day, ${span(ev.startDatetime, ev.endDatetime)}"
    }

    /** The period title (`updateLabel`). */
    fun period(view: CalendarView, cursor: LocalDate, wide: Boolean): String = when (view) {
        CalendarView.MONTH -> monthYear(cursor)
        CalendarView.WEEK -> if (wide) {
            s.t("calendar.weekNumberLabel", "week" to dates.weekNumber(cursor), "month" to fmt("LLLL", cursor.atStartOfDay()), "year" to cursor.year)
        } else {
            s.t("calendar.dayRangeLabel", "from" to dayMonth(cursor), "to" to date(cursor.plusDays(2), year = true))
        }
        CalendarView.DAY -> dayHeading(cursor, long = wide, year = true)
        CalendarView.AGENDA -> s.t("calendar.dayRangeLabel", "from" to dayMonth(cursor), "to" to date(cursor.plusDays(CalendarDates.AGENDA_DAYS.toLong()), year = true))
    }

    /** A naive-UTC `remind_at` in the household zone (`reminderWhenText`). */
    fun moment(remindAt: String): String {
        val millis = EventReminders.remindAtMillis(remindAt) ?: return ""
        val local = java.time.Instant.ofEpochMilli(millis).atZone(dates.zone).toLocalDateTime()
        return "${date(local.toLocalDate())} ${time(local.toLocalTime())}"
    }

    /** A schedule shift's time span (`shiftTimeText`). */
    fun shift(type: ShiftType): String {
        val start = type.startTime?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() } ?: return ""
        val end = type.endTime?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
        return if (end == null) time(start) else s.t("calendar.dayRangeLabel", "from" to time(start), "to" to time(end))
    }
}
