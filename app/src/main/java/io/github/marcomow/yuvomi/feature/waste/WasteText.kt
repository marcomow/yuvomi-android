package io.github.marcomow.yuvomi.feature.waste

import android.icu.text.DateFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The page's words for days, in the UI language (`dateText`, `dateRangeText`,
 * `dateWithDistanceText`, `daysFromTodayText` of `public/utils/date-format.js`). Date keys
 * are calendar days of the household; nothing is shifted through a zone.
 */
class WasteText(val strings: Strings, private val today: LocalDate) {
    private val locale = ULocale.forLanguageTag(strings.locale)

    private fun fmt(skeleton: String, d: LocalDate): String =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
            .format(Date.from(d.atStartOfDay().toInstant(ZoneOffset.UTC)))

    fun parse(key: String?): LocalDate? = key?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** `dateText()`: the year only outside the current one. */
    fun date(d: LocalDate, weekday: Boolean = false): String {
        val text = fmt(if (d.year != today.year) "yMMMd" else "MMMd", d)
        return if (weekday) "${fmt("EEE", d)}, $text" else text
    }

    fun date(key: String?, weekday: Boolean = false): String = parse(key)?.let { date(it, weekday) } ?: key.orEmpty()

    /** `dateRangeText()`: the year at most once. */
    fun range(from: String?, to: String?): String {
        val a = parse(from)
        val b = parse(to)
        if (a == null || b == null) return date(from ?: to)
        if (a == b) return date(a)
        val left: String
        val right: String
        if (a.year == b.year && a.year == today.year) {
            left = fmt("MMMd", a); right = fmt("MMMd", b)
        } else if (a.year == b.year) {
            left = fmt("MMMd", a); right = fmt("yMMMd", b)
        } else {
            left = fmt("yMMMd", a); right = fmt("yMMMd", b)
        }
        return strings.t("calendar.dayRangeLabel", "from" to left, "to" to right)
    }

    /** `daysFromTodayText()`: today, tomorrow, yesterday, then days, weeks, months, years. */
    fun distance(days: Long): String = when (days) {
        0L -> strings.t("common.today")
        1L -> strings.t("common.tomorrow")
        -1L -> strings.t("common.yesterday")
        else -> {
            val a = abs(days)
            val direction = if (days < 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
            val (value, unit) = when {
                a <= 30 -> a.toDouble() to RelativeDateTimeFormatter.RelativeUnit.DAYS
                a <= 60 -> (a / 7.0).roundToLong().toDouble() to RelativeDateTimeFormatter.RelativeUnit.WEEKS
                a <= 364 -> (a / 30.44).roundToLong().toDouble() to RelativeDateTimeFormatter.RelativeUnit.MONTHS
                else -> max(1L, (a / 365.25).roundToLong()).toDouble() to RelativeDateTimeFormatter.RelativeUnit.YEARS
            }
            RelativeDateTimeFormatter.getInstance(locale).format(value, direction, unit)
        }
    }

    /** `pickupWhenLabel()`: "Fri, Oct 30 · in 6 days"; today and tomorrow alone; no distance in the past. */
    fun pickupWhen(key: String): String {
        val d = parse(key) ?: return key
        val days = ChronoUnit.DAYS.between(today, d)
        if (days == 0L || days == 1L) return distance(days)
        val text = date(d, weekday = true).replace(' ', ' ')
        if (days < 0) return text
        return "$text · ${distance(days).replace(' ', ' ')}"
    }

    fun isToday(key: String) = parse(key) == today
}
