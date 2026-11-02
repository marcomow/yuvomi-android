package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Shared expenses in the Tags view (docs/spec/splitexpenses.md, SPLIT-R22): a `#hashtag` in an
 * expense's title or notes counts as a tag. Reads the member's active groups and each group's
 * expenses (all pages) with the tab's own calls; nothing when the member does not see Budget.
 * An item opens its group's tab.
 */
object SplitExpensesTagSource : TagSource {
    override val moduleId = "budget"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val connection = shell.container.session.connection ?: return emptyList()
        val api = SplitApi(connection.client)
        val out = ArrayList<TaggedItem>()
        for (g in api.groups("active", "")) {
            var cursor: ExpenseCursor? = null
            var pages = 0
            do {
                val page = api.expenses(g.id, "", 100, cursor)
                out += items(g, page.items)
                cursor = page.cursor
            } while (cursor != null && ++pages < 20)
        }
        return out
    }

    fun items(group: ExpenseGroup, expenses: List<Expense>): List<TaggedItem> = expenses.mapNotNull { e ->
        val tags = Hashtags.extract(e.title, e.description)
        if (tags.isEmpty()) null
        else TaggedItem(
            moduleId = moduleId,
            key = "split-expense:${e.id}",
            title = e.title,
            subtitle = group.name,
            tags = tags,
            route = SplitExpensesRoutes.group(group.id),
        )
    }
}
