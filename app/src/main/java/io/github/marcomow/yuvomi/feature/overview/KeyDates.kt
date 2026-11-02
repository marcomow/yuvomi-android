package io.github.marcomow.yuvomi.feature.overview

import io.github.marcomow.yuvomi.core.api.model.DashCountdown
import kotlin.math.roundToInt

/**
 * The rules of the "Key dates" tile (`renderCountdowns` in public/pages/dashboard.js and
 * public/utils/countdown.js), pure so they are unit tested (OVERVIEW-R17).
 */
object KeyDates {
    /** `COUNTDOWN_EXACT_DAYS`, `COUNTDOWN_WEEKS_UNTIL`, `COUNTDOWN_MONTHS_UNTIL`. */
    const val EXACT_DAYS = 30
    const val WEEKS_UNTIL = 60
    const val MONTHS_UNTIL = 364
    private const val DAYS_PER_MONTH = 30.44
    private const val DAYS_PER_YEAR = 365.25

    /** Rows the tile shows, like the other list tiles of the overview. */
    const val ROWS = 5

    /** `COUNTDOWN_MODULE`: the module a row belongs to; anything else is read as an event, like upstream. */
    fun module(source: String): String = when (source) {
        "task" -> "tasks"
        "document" -> "documents"
        else -> "calendar"
    }

    /** `countdownPhrase`: the locale key and, where it counts, the number. */
    fun phrase(days: Int): Pair<String, Int?> = when {
        days < 0 -> "dashboard.countdownOverdue" to -days
        days == 0 -> "common.today" to null
        days == 1 -> "common.tomorrow" to null
        days <= EXACT_DAYS -> "dashboard.daysLeft" to days
        days <= WEEKS_UNTIL -> "dashboard.countdownWeeks" to (days / 7.0).roundToInt()
        days <= MONTHS_UNTIL -> "dashboard.countdownMonths" to (days / DAYS_PER_MONTH).roundToInt()
        else -> "dashboard.countdownYears" to maxOf(1, (days / DAYS_PER_YEAR).roundToInt())
    }

    enum class Rank { OVERDUE, NOW, SOON, LATER }

    /** `countdownRank`: the colour of the count follows how near it is, not where it comes from. */
    fun rank(days: Int): Rank = when {
        days < 0 -> Rank.OVERDUE
        days <= 1 -> Rank.NOW
        days <= EXACT_DAYS -> Rank.SOON
        else -> Rank.LATER
    }

    /**
     * The meta line: the relative date unless the count already says it ("Today" twice), and
     * the person a document is about (#1705).
     */
    fun meta(item: DashCountdown, whenText: String, label: String): String =
        listOf(whenText.takeIf { it.isNotEmpty() && it != label }.orEmpty(), item.personName.orEmpty())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /** "+N more" against the server's total (`countdownTotal`), else the loaded length. */
    fun more(total: Int?, shown: Int): Int = maxOf(0, (total ?: shown) - minOf(shown, ROWS))
}
