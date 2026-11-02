package io.github.marcomow.yuvomi.core.i18n

import android.icu.text.DateFormat
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Date

/** Dates and times in the UI language, through ICU skeletons. */
class Formats(private val strings: Strings, private val zone: ZoneId) {
    private val locale = ULocale.forLanguageTag(strings.locale)

    private fun skeleton(pattern: String) = DateFormat.getInstanceForSkeleton(pattern, locale).apply {
        timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
    }

    private fun LocalDate.asDate(): Date = Date.from(atStartOfDay(zone).toInstant())

    /** `Nov 4`, or `Nov 4, 2027` outside the current year. */
    fun date(d: LocalDate, today: LocalDate): String =
        skeleton(if (d.year == today.year) "MMMd" else "yMMMd").format(d.asDate())

    fun weekdayDate(d: LocalDate): String = skeleton("EEEEMMMMd").format(d.asDate())

    /**
     * `relativeDateLabel` (utils/day-label.js): today, tomorrow, the short weekday up to six days
     * ahead, else the date (with the year outside the current one).
     */
    fun relativeDate(d: LocalDate, today: LocalDate): String {
        val ahead = ChronoUnit.DAYS.between(today, d)
        return when {
            ahead == 0L -> strings.t("common.today")
            ahead == 1L -> strings.t("common.tomorrow")
            ahead in 2..6 -> skeleton("EEE").format(d.asDate())
            else -> date(d, today)
        }
    }

    fun time(t: LocalTime): String =
        skeleton("jmm").format(Date.from(t.atDate(LocalDate.of(2000, 1, 1)).atZone(zone).toInstant()))

    fun time(z: ZonedDateTime): String = skeleton("jmm").format(Date.from(z.toInstant()))

    fun dateTime(z: ZonedDateTime): String = skeleton("MMMdjmm").format(Date.from(z.toInstant()))

    /** The PWA's due label: overdue by N days, today, tomorrow, or the date; with the time when set. */
    fun due(dueDate: LocalDate, dueTime: String?, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, dueDate)
        val day = when {
            diff < 0 -> strings.t("tasks.overdueDay", "count" to -diff)
            diff == 0L -> strings.t("tasks.dueToday")
            diff == 1L -> strings.t("tasks.dueTomorrow")
            else -> date(dueDate, today)
        }
        val time = dueTime?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
        return if (time != null) "$day · ${time(time)}" else day
    }
}
