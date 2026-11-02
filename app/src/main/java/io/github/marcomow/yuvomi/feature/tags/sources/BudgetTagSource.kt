package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.feature.budget.BudgetApi
import io.github.marcomow.yuvomi.feature.budget.BudgetEntry
import io.github.marcomow.yuvomi.feature.budget.BudgetRoutes
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.YearMonth

/**
 * Budget's part of the Tags view (TAGS-R2): hashtags in an entry's title, for the entries of
 * the last twelve months (`GET /budget?month=`, the current month and the eleven before). In the
 * personal budget mode both scopes are read (`mine` and `household`), like switching the page's
 * scope. A recurring series is one item; entries open the budget page (it has no per-entry route).
 */
object BudgetTagSource : TagSource {
    override val moduleId = "budget"
    const val MONTHS = 12L

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val api = BudgetApi(shell.connection.client)
        val personal = api.preferences().budgetMode == "personal"
        val views = if (personal) listOf(BudgetApi.View(true, "mine"), BudgetApi.View(true, "household")) else listOf(BudgetApi.View(false, "mine"))
        val entries = coroutineScope {
            months(shell.time.today()).flatMap { month -> views.map { view -> async { api.entries(month, view) } } }.awaitAll().flatten()
        }
        return items(entries, SourceText.of(shell))
    }

    /** `YYYY-MM` of this month and the [MONTHS] - 1 before it, newest first. */
    fun months(today: LocalDate): List<String> = (0 until MONTHS).map { YearMonth.from(today).minusMonths(it).toString() }

    fun items(entries: List<BudgetEntry>, text: SourceText): List<TaggedItem> =
        entries.groupBy { entryKey(it) }.mapNotNull { (key, rows) ->
            val newest = rows.maxBy { it.date }
            val tags = Hashtags.extract(newest.title)
            if (tags.isEmpty()) null else TaggedItem(moduleId, key, newest.title, text.date(newest.date), tags, BudgetRoutes.START)
        }

    /** A stored entry by its id; a recurring series' occurrences (virtual ones have no id) by the series. */
    private fun entryKey(e: BudgetEntry): String = when {
        e.recurrenceParentId != null -> "budget-series:${e.recurrenceParentId}"
        e.isRecurring && e.id != null -> "budget-series:${e.id}"
        e.id != null -> "budget:${e.id}"
        else -> "budget-virtual:${e.title}"
    }
}
