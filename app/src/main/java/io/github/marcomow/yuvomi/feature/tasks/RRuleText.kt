package io.github.marcomow.yuvomi.feature.tasks

import io.github.marcomow.yuvomi.core.i18n.Formats
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.time.LocalDate

/** Port of upstream's `describeRRule` and `intervalUnitLabel` (TASKS-R26). */
object RRuleText {

    fun freqLabel(freq: String, s: Strings) = when (freq) {
        "DAILY" -> s.t("rrule.freqDaily")
        "WEEKLY" -> s.t("rrule.freqWeekly")
        "MONTHLY" -> s.t("rrule.freqMonthly")
        "YEARLY" -> s.t("rrule.freqYearly")
        else -> s.t("rrule.freqNone")
    }

    fun unit(freq: String, count: Int, s: Strings): String {
        val many = count > 1
        return when (freq) {
            "DAILY" -> s.t(if (many) "rrule.unitDays" else "rrule.unitDay")
            "WEEKLY" -> s.t(if (many) "rrule.unitWeeks" else "rrule.unitWeek")
            "MONTHLY" -> s.t(if (many) "rrule.unitMonths" else "rrule.unitMonth")
            "YEARLY" -> s.t(if (many) "rrule.unitYears" else "rrule.unitYear")
            else -> ""
        }
    }

    fun dayLabel(code: String, s: Strings) = s.t("rrule.day" + code.lowercase().replaceFirstChar { it.uppercase() })

    fun describe(rule: String?, fromCompletion: Boolean, s: Strings, f: Formats, today: LocalDate): String {
        val p = RRule.parse(rule)
        if (p.freq.isEmpty()) return ""
        val parts = mutableListOf(
            if (p.interval > 1) "${s.t("rrule.labelEvery")} ${p.interval} ${unit(p.freq, p.interval, s)}" else freqLabel(p.freq, s),
        )
        if (p.freq == "WEEKLY" && p.byday.isNotEmpty()) {
            val days = p.byday.filter { it in RRule.WEEKDAYS }.map { dayLabel(it, s) }
            if (days.isNotEmpty()) parts += "(${days.joinToString(", ")})"
        }
        if (p.freq == "MONTHLY" && p.lastDay) parts += "(${s.t("rrule.lastDayOfMonth")})"
        var rhythm = parts.joinToString(" ")
        if (fromCompletion) rhythm += " · ${s.t("rrule.summaryFromCompletion")}"
        if (p.count != null) return "$rhythm · ${s.t("rrule.summaryCount", "count" to p.count)}"
        if (p.until.isNotEmpty()) {
            val until = runCatching { LocalDate.parse(p.until) }.getOrNull()
            if (until != null) return "$rhythm · ${s.t("rrule.summaryUntil", "date" to f.date(until, today))}"
        }
        return rhythm
    }
}
