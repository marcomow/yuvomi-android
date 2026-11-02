package io.github.marcomow.yuvomi.feature.birthdays

import android.icu.text.DateFormat
import android.icu.text.MeasureFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Date
import kotlin.math.abs

/** [BirthdaysFormat] through ICU in the UI language (as `Intl` in the web app). */
class IcuBirthdaysFormat(language: String, private val today: () -> LocalDate) : BirthdaysFormat {
    private val locale = ULocale.forLanguageTag(language)

    private fun skeleton(pattern: String) = DateFormat.getInstanceForSkeleton(pattern, locale).apply { timeZone = TimeZone.GMT_ZONE }

    private fun LocalDate.asDate(): Date = Date.from(atStartOfDay().toInstant(ZoneOffset.UTC))

    override fun relative(value: Int, unit: String): String {
        val f = RelativeDateTimeFormatter.getInstance(locale)
        val u = when (unit) {
            "week" -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
            "month" -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
            "year" -> RelativeDateTimeFormatter.RelativeUnit.YEARS
            else -> RelativeDateTimeFormatter.RelativeUnit.DAYS
        }
        val direction = if (value < 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
        return f.format(abs(value).toDouble(), direction, u)
    }

    override fun duration(amount: Int, unit: String): String {
        val u = when (unit) {
            "minute" -> MeasureUnit.MINUTE
            "hour" -> MeasureUnit.HOUR
            "week" -> MeasureUnit.WEEK
            else -> MeasureUnit.DAY
        }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(amount, u))
    }

    override fun fullDate(d: LocalDate): String = skeleton("yMMMd").format(d.asDate())

    override fun monthDay(month: Int, day: Int): String = skeleton("MMMMd").format(LocalDate.of(2000, month, day).asDate())

    override fun dateText(d: LocalDate, weekday: Boolean): String {
        val year = d.year != today().year
        val pattern = (if (weekday) "EEE" else "") + (if (year) "yMMMd" else "MMMd")
        return skeleton(pattern).format(d.asDate())
    }

    override fun monthYear(ym: YearMonth): String = skeleton("yMMMM").format(ym.atDay(1).asDate())

    override fun monthName(month: Int): String = skeleton("MMMM").format(LocalDate.of(2000, month, 1).asDate())
}
