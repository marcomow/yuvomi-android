package io.github.marcomow.yuvomi.feature.housekeeping

import android.icu.text.DateFormat
import android.icu.util.ULocale
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Date

/**
 * The page's words for dates, times and money, in the UI language (`dateText`, `formatDayMonth`,
 * `formatTime`, `timeSpanText`, `formatMonthLabel`, `money`). Instants are read on the
 * household's clock ([HouseholdTime]), never the device's.
 */
class HousekeepingText(val strings: Strings, private val time: HouseholdTime, val currency: String) {
    private val locale = ULocale.forLanguageTag(strings.locale)
    private val today: LocalDate get() = time.today()

    private fun fmt(skeleton: String, d: LocalDateTime): String =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
            .format(Date.from(d.toInstant(ZoneOffset.UTC)))

    /** The household day of a stored value (`zonedDateKey`): an instant, a wall time or a date. */
    fun day(value: String?): LocalDate? = time.parse(value)?.toLocalDate()

    private fun wall(value: String?): LocalDateTime? = time.parse(value)?.toLocalDateTime()

    /** `dateText()`: weekday on request, the year only outside the current one. */
    fun date(value: String?, weekday: Boolean = false): String {
        val d = day(value) ?: return value.orEmpty()
        return date(d, weekday)
    }

    fun date(d: LocalDate, weekday: Boolean = false): String {
        val text = fmt(if (d.year != today.year) "yMMMd" else "MMMd", d.atStartOfDay())
        return if (weekday) "${fmt("EEE", d.atStartOfDay())}, $text" else text
    }

    /** `formatDayMonth()`. */
    fun dayMonth(value: String?): String = day(value)?.let { fmt("MMMd", it.atStartOfDay()) } ?: value.orEmpty()

    /** `formatTime()`. */
    fun time(value: String?): String = wall(value)?.let { fmt("jmm", it) } ?: ""

    /** `timeSpanText(..., { suffix: false })`. */
    fun span(from: String?, to: String?): String =
        if (to == null || to == from) time(from) else strings.t("calendar.dayRangeLabel", "from" to time(from), "to" to time(to))

    /** `formatMonthLabel()`: "October 2026", or "Oct" for the chart. */
    fun month(ym: String, short: Boolean = false): String =
        runCatching { YearMonth.parse(ym) }.getOrNull()?.let { fmt(if (short) "MMM" else "yMMMM", it.atDay(1).atStartOfDay()) } ?: ym

    fun money(value: Double?): String = HousekeepingRules.money(value, currency, strings.locale)

    fun chartMoney(value: Double): String = HousekeepingRules.chartMoney(value, currency, strings.locale)

    /** `birthDateText()`: day and month only when the year is unknown. */
    fun birthDate(value: String?, yearUnknown: Boolean): String {
        val d = HouseholdTime.date(value) ?: return value.orEmpty()
        return fmt(if (yearUnknown) "MMMMd" else "yMMMd", d.atStartOfDay())
    }
}
