package io.github.marcomow.yuvomi.feature.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The four views of the web page (`VIEWS`, public/pages/calendar.js). */
enum class CalendarView(val key: String, val labelKey: String) {
    MONTH("month", "calendar.viewMonth"),
    WEEK("week", "calendar.viewWeek"),
    DAY("day", "calendar.viewDay"),
    AGENDA("agenda", "calendar.viewAgenda");

    companion object {
        fun of(key: String?): CalendarView = entries.firstOrNull { it.key == key } ?: MONTH
    }
}

/**
 * The date arithmetic of the web page (docs/spec/calendar.md, CAL-R1 to R6), pure and in the
 * household's zone. Event datetimes come as a date (all-day), a wall time without zone
 * (household zone), or an instant with `Z`/offset (synced events): `localDate`/`localTime`
 * read all three in the household zone, like the page's `zonedDateKey` (#829).
 */
class CalendarDates(val zone: ZoneId, val weekStart: DayOfWeek = DayOfWeek.MONDAY) {

    // ── Event datetimes ──

    fun dateTime(value: String?): LocalDateTime? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length <= 10) return runCatching { LocalDate.parse(v).atStartOfDay() }.getOrNull()
        val text = v.replace(' ', 'T')
        val zoned = v.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text.substring(10))
        return runCatching {
            if (zoned) {
                val normalised = text.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
                OffsetDateTime.parse(normalised).atZoneSameInstant(zone).toLocalDateTime()
            } else {
                LocalDateTime.parse(if (text.length == 16) "$text:00" else text.take(19))
            }
        }.getOrNull()
    }

    /** `localDate()`: the calendar day of a stored datetime in the household zone. */
    fun localDate(value: String?): LocalDate? {
        val v = value ?: return null
        if (v.length <= 10) return runCatching { LocalDate.parse(v.take(10)) }.getOrNull()
        return dateTime(v)?.toLocalDate()
    }

    /** `localTime()`: `00:00` for a plain date. */
    fun localTime(value: String?): LocalTime {
        val v = value ?: return LocalTime.MIDNIGHT
        if (v.length <= 10) return LocalTime.MIDNIGHT
        return dateTime(v)?.toLocalTime() ?: LocalTime.MIDNIGHT
    }

    fun hasTime(value: String?): Boolean = value != null && value.contains('T')

    /**
     * `eventEndDate()`: the last day an event stands on. A timed event ending at exactly
     * midnight does not take the next day (#804); an all-day end is inclusive.
     */
    fun endDate(ev: CalendarEvent): LocalDate {
        val start = localDate(ev.startDatetime) ?: LocalDate.MIN
        val endRaw = ev.endDatetime ?: return start
        val end = localDate(endRaw) ?: return start
        if (!end.isAfter(start)) return start
        if (ev.allDay || !endRaw.contains('T')) return end
        return if (localTime(endRaw) == LocalTime.MIDNIGHT) end.minusDays(1) else end
    }

    fun startDate(ev: CalendarEvent): LocalDate = localDate(ev.startDatetime) ?: LocalDate.MIN

    fun isMultiDay(ev: CalendarEvent): Boolean = ev.endDatetime != null && startDate(ev) != endDate(ev)

    private fun spansFullDayOrLonger(ev: CalendarEvent): Boolean {
        val end = ev.endDatetime ?: return false
        val days = ChronoUnit.DAYS.between(startDate(ev), localDate(end) ?: return false)
        val span = days * 1440 + localTime(end).toSecondOfDay() / 60 - localTime(ev.startDatetime).toSecondOfDay() / 60
        return span >= 1440
    }

    /** `isAllDayLike()`: all-day, a plain date, or several days and at least 24 hours. */
    fun isAllDayLike(ev: CalendarEvent): Boolean =
        ev.allDay || !ev.startDatetime.contains('T') || (isMultiDay(ev) && spansFullDayOrLonger(ev))

    /** `agendaSegmentKind()`. */
    fun segment(ev: CalendarEvent, day: LocalDate): Segment = when {
        ev.allDay || !ev.startDatetime.contains('T') -> Segment.ALL_DAY
        !isMultiDay(ev) -> Segment.SINGLE
        day == startDate(ev) -> Segment.START
        day == endDate(ev) -> Segment.END
        else -> Segment.MIDDLE
    }

    enum class Segment { ALL_DAY, SINGLE, START, END, MIDDLE }

    /** `multiDayPosition()`: day N of M, or null for a one-day event. */
    fun position(ev: CalendarEvent, day: LocalDate): Pair<Int, Int>? {
        if (!isMultiDay(ev)) return null
        val start = startDate(ev)
        val count = ChronoUnit.DAYS.between(start, endDate(ev)).toInt() + 1
        val n = ChronoUnit.DAYS.between(start, day).toInt() + 1
        return if (n in 1..count) n to count else null
    }

    /** `timeRangeForEvent()`: minutes since midnight on [day], clamped to it, at least 30. */
    fun minutes(ev: CalendarEvent, day: LocalDate): IntRange {
        val startsEarlier = day.isAfter(startDate(ev))
        val start = if (startsEarlier) 0 else localTime(ev.startDatetime).toSecondOfDay() / 60
        val endRaw = ev.endDatetime
        val endsLater = endRaw != null && localDate(endRaw)?.let { day.isBefore(it) } == true
        val end = when {
            endRaw == null -> start + 60
            endsLater -> 1440
            else -> localTime(endRaw).toSecondOfDay() / 60
        }
        return start until maxOf(end, start + 30)
    }

    // ── Days of an event ──

    /** Whether [ev] stands on [day] (`buildDayIndex`). */
    fun on(ev: CalendarEvent, day: LocalDate): Boolean = !startDate(ev).isAfter(day) && !endDate(ev).isBefore(day)

    // ── Periods ──

    fun startOfWeek(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(weekStart))

    /** The weekdays in grid order, from the household's week start. */
    fun weekdays(): List<DayOfWeek> = (0 until 7).map { weekStart.plus(it.toLong()) }

    /** `monthGridSpan()`: whole weeks from the week start around the first of the month. */
    fun monthGrid(cursor: LocalDate): List<LocalDate> {
        val first = cursor.withDayOfMonth(1)
        val start = startOfWeek(first)
        val offset = ChronoUnit.DAYS.between(start, first).toInt()
        val weeks = (offset + cursor.lengthOfMonth() + 6) / 7
        return (0 until weeks * 7).map { start.plusDays(it.toLong()) }
    }

    /**
     * The days the week view draws: the week on a wide screen, three days from the cursor on
     * a phone (`mobileWeekDays`, R20).
     */
    fun weekDays(cursor: LocalDate, wide: Boolean): List<LocalDate> =
        if (wide) (0 until 7).map { startOfWeek(cursor).plusDays(it.toLong()) } else (0 until 3).map { cursor.plusDays(it.toLong()) }

    /** `getRangeForView()`: the days a view loads. */
    fun range(view: CalendarView, cursor: LocalDate, wide: Boolean): Pair<LocalDate, LocalDate> = when (view) {
        CalendarView.MONTH -> monthGrid(cursor).let { it.first() to it.last() }
        CalendarView.WEEK -> {
            val week = startOfWeek(cursor)
            if (wide) {
                week to week.plusDays(6)
            } else {
                minOf(week, cursor) to maxOf(week.plusDays(6), cursor.plusDays(2))
            }
        }
        CalendarView.DAY -> cursor to cursor.plusDays(DAY_RAIL_DAYS.toLong())
        CalendarView.AGENDA -> cursor to cursor.plusDays(AGENDA_DAYS.toLong())
    }

    /** `visibleDayRange()`: what a view shows, for the date a new event gets. */
    fun visible(view: CalendarView, cursor: LocalDate, wide: Boolean): Pair<LocalDate, LocalDate> = when (view) {
        CalendarView.MONTH -> cursor.withDayOfMonth(1) to cursor.withDayOfMonth(cursor.lengthOfMonth())
        CalendarView.WEEK -> weekDays(cursor, wide).let { it.first() to it.last() }
        CalendarView.DAY -> cursor to cursor
        CalendarView.AGENDA -> cursor to cursor.plusDays(AGENDA_DAYS.toLong())
    }

    /**
     * `newEventDate()`: the month picks a day (its list shows it), everything else proposes
     * today when it is in view, else the first day shown (`defaultDateInPeriod`, #737).
     */
    fun newEventDate(view: CalendarView, cursor: LocalDate, today: LocalDate, wide: Boolean): LocalDate {
        if (view == CalendarView.MONTH) return cursor
        val (from, to) = visible(view, cursor, wide)
        return if (!today.isBefore(from) && !today.isAfter(to)) today else from
    }

    /** `periodStepOf()` and `navigate()`: the cursor one period on. */
    fun step(view: CalendarView, cursor: LocalDate, dir: Int, wide: Boolean, today: LocalDate): LocalDate = when (view) {
        CalendarView.MONTH -> {
            // `monthStepCursor`: from the first of the month, so the 31st never skips a month.
            val month = cursor.withDayOfMonth(1).plusMonths(dir.toLong())
            if (today.year == month.year && today.month == month.month) today else month
        }
        CalendarView.WEEK -> cursor.plusDays((if (wide) 7L else 3L) * dir)
        CalendarView.DAY -> cursor.plusDays(dir.toLong())
        CalendarView.AGENDA -> cursor.plusDays(AGENDA_DAYS.toLong() * dir)
    }

    /** The labels of the arrows (`periodArrowLabels`). */
    fun arrowKeys(view: CalendarView, wide: Boolean): Pair<String, String> = when (view) {
        CalendarView.MONTH -> "calendar.prevMonth" to "calendar.nextMonth"
        CalendarView.WEEK -> if (wide) "calendar.prevWeek" to "calendar.nextWeek" else "calendar.prevDays" to "calendar.nextDays"
        CalendarView.DAY -> "calendar.prevDay" to "calendar.nextDay"
        CalendarView.AGENDA -> "calendar.prevDays" to "calendar.nextDays"
    }

    fun stepDays(view: CalendarView, wide: Boolean): Int = when (view) {
        CalendarView.WEEK -> if (wide) 7 else 3
        CalendarView.AGENDA -> AGENDA_DAYS
        else -> 1
    }

    /** ISO week number (`getWeekNumber`). */
    fun weekNumber(d: LocalDate): Int = d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** `defaultNewEventTime()`: 09:00, or today the next half hour. */
    fun defaultNewEventTime(date: LocalDate, now: LocalDateTime): LocalTime {
        if (date != now.toLocalDate()) return LocalTime.of(9, 0)
        val (hour, minute) = if (now.minute <= 30) now.hour to 30 else now.hour + 1 to 0
        return if (hour > 23) LocalTime.of(9, 0) else LocalTime.of(hour, minute)
    }

    companion object {
        const val DAY_RAIL_DAYS = 7
        const val AGENDA_DAYS = 30

        fun weekStartOf(pref: String?): DayOfWeek = when (pref) {
            "sunday" -> DayOfWeek.SUNDAY
            "saturday" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }

        /** Overlap lanes by time (`assignLanes`, public/utils/overlap-lanes.js). */
        fun <T> lanes(items: List<T>, range: (T) -> IntRange): Map<T, Pair<Int, Int>> {
            val sorted = items.sortedWith(compareBy({ range(it).first }, { range(it).last }))
            val out = LinkedHashMap<T, Pair<Int, Int>>()
            var group = mutableListOf<T>()
            var groupEnd = Int.MIN_VALUE
            fun flush() {
                val columns = mutableListOf<Int>()
                val placed = group.map { item ->
                    val r = range(item)
                    var col = columns.indexOfFirst { it <= r.first }
                    if (col == -1) {
                        col = columns.size
                        columns += r.last + 1
                    } else {
                        columns[col] = r.last + 1
                    }
                    item to col
                }
                val total = maxOf(columns.size, 1)
                placed.forEach { (item, col) -> out[item] = col to total }
                group = mutableListOf()
            }
            for (item in sorted) {
                val r = range(item)
                if (group.isNotEmpty() && r.first > groupEnd) flush()
                group += item
                groupEnd = maxOf(groupEnd, r.last)
            }
            if (group.isNotEmpty()) flush()
            return out
        }
    }
}
