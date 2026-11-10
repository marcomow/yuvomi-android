package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.core.api.model.Member
import io.github.marcomow.yuvomi.core.api.model.Person
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.feature.tasks.TaskBodies
import io.github.marcomow.yuvomi.feature.tasks.TaskEditorViewModel.Form
import io.github.marcomow.yuvomi.feature.tasks.TaskSeries
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSeriesTest {

    private fun foreign(rule: String?, due: String?, time: String? = null, recurring: Boolean = rule != null) =
        Task(1, externalRrule = "FREQ=MONTHLY;BYMONTHDAY=15", isRecurring = recurring, recurrenceRule = rule, dueDate = due, dueTime = time)

    /** TASKS-A19: upstream's `repeatsElsewhere`. */
    @Test fun `a series another app owns is ticked off there unless Yuvomi follows its rule`() {
        assertFalse(TaskSeries.repeatsElsewhere(Task(1)))
        // No rule Yuvomi could follow: only the foreign rule.
        assertTrue(TaskSeries.repeatsElsewhere(foreign(null, "2026-11-15")))
        // A weekly rule with a due date: Yuvomi continues it.
        assertFalse(TaskSeries.repeatsElsewhere(foreign("FREQ=WEEKLY", "2026-11-15")))
        // Monthly on the 29th to 31st is clamped by Yuvomi, not by RFC 5545.
        assertTrue(TaskSeries.repeatsElsewhere(foreign("FREQ=MONTHLY", "2026-10-31")))
        assertFalse(TaskSeries.repeatsElsewhere(foreign("FREQ=MONTHLY", "2026-10-28")))
        assertFalse(TaskSeries.repeatsElsewhere(foreign("FREQ=MONTHLY;BYMONTHDAY=-1", "2026-10-31")))
        // Yearly on 29 February.
        assertTrue(TaskSeries.repeatsElsewhere(foreign("FREQ=YEARLY", "2028-02-29")))
        // An end counts only as a plain date on a series without a time.
        assertFalse(TaskSeries.repeatsElsewhere(foreign("FREQ=DAILY;UNTIL=20261201", "2026-11-15")))
        assertTrue(TaskSeries.repeatsElsewhere(foreign("FREQ=DAILY;UNTIL=20261201", "2026-11-15", "09:00")))
        assertTrue(TaskSeries.repeatsElsewhere(foreign("FREQ=DAILY;UNTIL=20261201T120000Z", "2026-11-15")))
        // Without a due date or as a subtask, the series is not continued here.
        assertTrue(TaskSeries.repeatsElsewhere(foreign("FREQ=WEEKLY", null)))
        assertTrue(TaskSeries.repeatsElsewhere(foreign("FREQ=WEEKLY", "2026-11-15").copy(parentTaskId = 4)))
    }

    /** TASKS-A20: upstream's `rotationDetailRow`. */
    @Test fun `the rotation row names the member the server names, or only the mode`() {
        val members = listOf(Member(2, "Kevin"), Member(3, "Anna"))
        val rotating = Task(1, rotationMode = "round_robin", rotationPool = listOf(2, 3), rotationNextUser = 3)
        assertEquals("Anna", TaskSeries.rotationNext(rotating, members))
        assertEquals("", TaskSeries.rotationNext(rotating.copy(rotationNextUser = 9), members))
        assertEquals("", TaskSeries.rotationNext(rotating.copy(rotationNextUser = null), members))
        assertNull(TaskSeries.rotationNext(rotating.copy(rotationPool = listOf(2)), members))
        assertNull(TaskSeries.rotationNext(rotating.copy(rotationMode = "random"), members))
        assertNull(TaskSeries.rotationNext(Task(1), members))
    }

    /** TASKS-A21: an edit of the people keeps the primary assignee first (#2569). */
    @Test fun `an assignee edit keeps the primary in front`() {
        val task = Task(1, assignedUsers = listOf(Person(5, "Max"), Person(2, "Kevin")))
        val old = Form(title = "Bins", assignees = setOf(5, 2))
        fun sent(ids: Set<Int>) = TaskBodies.diff(old, old.copy(assignees = ids), task, solo = false)["assigned_to"]!!
            .jsonArray.map { (it as JsonPrimitive).content.toInt() }
        assertEquals(listOf(5, 1, 2), sent(setOf(5, 2, 1)))
        assertEquals(listOf(1, 2), sent(setOf(2, 1)))
    }
}
