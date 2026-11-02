package io.github.marcomow.yuvomi.feature.widgets

import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shopping.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class WidgetLogicTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()) +
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n-android/widgets/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }
    private val today = LocalDate.of(2026, 11, 2)

    private fun task(id: Int, due: String? = null, time: String? = null, status: String = Task.STATUS_OPEN, parent: Int? = null, order: Int? = null) =
        Task(id, "T$id", status = status, dueDate = due, dueTime = time, parentTaskId = parent, sortOrder = order)

    @Test fun `overdue, then today, then later, then undated, done and subtasks left out`() {
        val tasks = listOf(
            task(1), task(2, "2026-11-05"), task(3, "2026-11-02", "18:00"), task(4, "2026-10-30"),
            task(5, "2026-11-02", "09:00"), task(6, status = Task.STATUS_DONE), task(7, parent = 1),
        )
        assertEquals(listOf(4, 5, 3, 2, 1), TaskWidgetLogic.pick(tasks, today).map { it.id })
    }

    @Test fun `due labels follow the task list`() {
        assertEquals("Overdue", TaskWidgetLogic.detail(task(1, "2026-11-01"), today, strings))
        assertEquals("Due today · 18:00", TaskWidgetLogic.detail(task(1, "2026-11-02", "18:00:00"), today, strings))
        assertEquals("Due tomorrow", TaskWidgetLogic.detail(task(1, "2026-11-03"), today, strings))
        assertNull(TaskWidgetLogic.detail(task(1), today, strings))
    }

    @Test fun `nothing open says so, too many say how many more`() {
        assertEquals("No tasks - all done?", TaskWidgetLogic.snapshot(emptyList(), today, strings, true).message)
        val many = TaskWidgetLogic.snapshot((1..35).map { task(it) }, today, strings, true)
        assertEquals(TaskWidgetLogic.MAX_ROWS, many.rows.size)
        assertEquals(5, many.more)
        assertEquals("5 more", strings.t("android.widgets.more", "count" to many.more))
    }

    @Test fun `shopping lists the open items in order with their quantity`() {
        val items = listOf(
            ShoppingItem(1, name = "Milk", quantity = "2", sortOrder = 2),
            ShoppingItem(2, name = "Bread", sortOrder = 1),
            ShoppingItem(3, name = "Eggs", isChecked = true),
        )
        val s = ShoppingWidgetLogic.snapshot(9, "Weekly", items, strings, canWrite = true)
        assertEquals(listOf("Bread", "Milk"), s.rows.map { it.title })
        assertEquals("2", s.rows[1].detail)
        assertEquals(9, s.listId)
        assertTrue(s.canWrite)
    }

    @Test fun `the widget shows the list last opened in the app, else the first`() {
        assertEquals(5, ShoppingWidgetLogic.choose(5, listOf(3, 5)))
        assertEquals(3, ShoppingWidgetLogic.choose(8, listOf(3, 5)))
        assertNull(ShoppingWidgetLogic.choose(null, emptyList()))
    }
}
