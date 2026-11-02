package io.github.marcomow.yuvomi.feature.budget

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Date

/**
 * The page's date words in the UI language (`formatMonthYear`, `formatDayMonth`, `dateText`,
 * `dateRangeText`), through ICU skeletons so order and joining follow the language (#1607).
 */
class BudgetDates(locale: String) {
    private val uLocale = ULocale.forLanguageTag(locale)

    private fun format(skeleton: String, date: LocalDate): String =
        DateFormat.getInstanceForSkeleton(skeleton, uLocale).apply { timeZone = TimeZone.GMT_ZONE }
            .format(Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()))

    /** "October 2026" (`formatMonthLabel`). */
    fun monthYear(month: String): String = runCatching { format("yMMMM", YearMonth.parse(month).atDay(1)) }.getOrDefault(month)

    /** "Oct 2026". */
    fun monthYearShort(month: String): String = runCatching { format("yMMM", YearMonth.parse(month).atDay(1)) }.getOrDefault(month)

    /** "Oct", the previous month of the trend (`getMonthName(...).slice(0, 3)` keeps three letters). */
    fun monthShortName(month: String): String = runCatching {
        format("MMMM", YearMonth.parse(month).atDay(1)).take(3)
    }.getOrDefault(month)

    /** "Oct 19" in a list of one month (`formatDayMonth`). */
    fun dayMonth(date: String): String = parse(date)?.let { format("MMMd", it) } ?: date

    /** "Oct 19, 2026" (`dateText`). */
    fun date(date: String): String = parse(date)?.let { format("yMMMd", it) } ?: date

    /** "Monday, October 19, 2026" (`dateText(..., { weekday: true })`). */
    fun weekdayDate(date: String): String = parse(date)?.let { format("yMMMMEEEEd", it) } ?: date

    /** "Oct 12 – 18, 2026" (`dateRangeText`). */
    fun range(from: String, to: String): String {
        val a = parse(from) ?: return from
        val b = parse(to) ?: return to
        val f = android.icu.text.DateIntervalFormat.getInstance("yMMMd", uLocale)
        val interval = android.icu.util.DateInterval(
            Date.from(a.atStartOfDay(ZoneOffset.UTC).toInstant()).time,
            Date.from(b.atStartOfDay(ZoneOffset.UTC).toInstant()).time,
        )
        f.timeZone = TimeZone.GMT_ZONE
        return f.format(interval, StringBuffer(), java.text.FieldPosition(0)).toString()
    }

    /** The trend chart's axis labels (`axisLabels`): day numbers, or short months. */
    fun axisLabel(period: String, daily: Boolean, sameMonth: Boolean): String {
        if (!daily) return runCatching { format("MMM", YearMonth.parse(period).atDay(1)) }.getOrDefault(period)
        val d = parse(period) ?: return period
        return if (sameMonth) format("d", d) else format("Md", d)
    }

    /** `periodLabel` of the trend's points. */
    fun periodLabel(period: String): String =
        if (Regex("^\\d{4}-\\d{2}$").matches(period)) monthYearShort(period) else date(period)

    private fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}
