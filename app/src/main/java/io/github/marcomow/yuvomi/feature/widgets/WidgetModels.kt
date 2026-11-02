package io.github.marcomow.yuvomi.feature.widgets

import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shopping.ShoppingItem
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One line of a widget: a task or a shopping item (docs/spec/widgets.md). */
@Serializable
data class WidgetRow(val id: Int, val title: String, val detail: String? = null, val urgent: Boolean = false)

/**
 * What a widget shows, already in the member's language. Saved after each successful read, so
 * a widget without a connection shows the last one, marked [stale] (WIDGETS-R6).
 */
@Serializable
data class WidgetSnapshot(
    val title: String,
    val rows: List<WidgetRow> = emptyList(),
    /** Shown instead of the rows: signed out, no access, nothing open, or the read failed with nothing saved. */
    val message: String? = null,
    /** The shopping list shown; null for tasks. */
    val listId: Int? = null,
    /** Rows beyond those shown. */
    val more: Int = 0,
    val stale: Boolean = false,
    /** False when the member may only read: no check boxes (WIDGETS-R4). */
    val canWrite: Boolean = true,
    /** False when the widget cannot open anything but the app's start (signed out). */
    val signedIn: Boolean = true,
)

/** Which tasks the tasks widget lists and how it labels their due dates. Pure, so it is unit tested. */
object TaskWidgetLogic {
    const val MAX_ROWS = 30

    /**
     * WIDGETS-R2: the member's open top-level tasks the task list shows by default (no future
     * start dates), overdue first, then due today, then later, then those without a date;
     * within a group by due date and time, then the list's own order.
     */
    fun pick(tasks: List<Task>, today: LocalDate): List<Task> = tasks
        .filter { !it.isDone && it.parentTaskId == null }
        .sortedWith(
            compareBy<Task>({ group(it, today) }, { it.dueDate ?: "" }, { it.dueTime ?: "99:99" }, { it.sortOrder ?: Int.MAX_VALUE }, { it.id }),
        )

    private fun due(t: Task): LocalDate? = t.dueDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun group(t: Task, today: LocalDate): Int {
        val d = due(t) ?: return 3
        return when {
            d.isBefore(today) -> 0
            d == today -> 1
            else -> 2
        }
    }

    fun overdue(t: Task, today: LocalDate): Boolean = due(t)?.isBefore(today) == true

    /** The due label: upstream's "Overdue", "Due today" (with the time), "Due tomorrow", else the short date. */
    fun detail(t: Task, today: LocalDate, strings: Strings): String? {
        val d = due(t) ?: return null
        val time = t.dueTime?.take(5)
        return when {
            d.isBefore(today) -> strings.t("tasks.overdue")
            d == today -> listOfNotNull(strings.t("tasks.dueToday"), time).joinToString(" · ")
            d == today.plusDays(1) -> listOfNotNull(strings.t("tasks.dueTomorrow"), time).joinToString(" · ")
            else -> d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(strings.locale)))
        }
    }

    fun snapshot(tasks: List<Task>, today: LocalDate, strings: Strings, canWrite: Boolean): WidgetSnapshot {
        val picked = pick(tasks, today)
        val rows = picked.take(MAX_ROWS).map { WidgetRow(it.id, it.title, detail(it, today, strings), overdue(it, today)) }
        return WidgetSnapshot(
            title = strings.t("nav.tasks"),
            rows = rows,
            message = if (rows.isEmpty()) strings.t("tasks.emptyTitle") else null,
            more = (picked.size - rows.size).coerceAtLeast(0),
            canWrite = canWrite,
        )
    }
}

/** Which shopping items the shopping widget lists. Pure, so it is unit tested. */
object ShoppingWidgetLogic {
    const val MAX_ROWS = 40

    /** WIDGETS-R3: the list's open items in the list's order, quantity as the detail. */
    fun snapshot(listId: Int, listName: String, items: List<ShoppingItem>, strings: Strings, canWrite: Boolean): WidgetSnapshot {
        val open = items.filter { !it.isChecked }.sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.id }))
        val rows = open.take(MAX_ROWS).map { WidgetRow(it.id, it.name, it.quantity?.takeIf { q -> q.isNotBlank() }) }
        return WidgetSnapshot(
            title = listName.ifBlank { strings.t("nav.shopping") },
            rows = rows,
            message = if (rows.isEmpty()) strings.t("shopping.emptyList") else null,
            listId = listId,
            more = (open.size - rows.size).coerceAtLeast(0),
            canWrite = canWrite,
        )
    }

    /** The list the widget shows: the one last opened in the app, else the first. */
    fun choose(remembered: Int?, lists: List<Int>): Int? = remembered?.takeIf { it in lists } ?: lists.firstOrNull()
}
