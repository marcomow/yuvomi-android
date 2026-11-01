package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskCategory
import io.github.marcomow.yuvomi.feature.tasks.Checklist
import io.github.marcomow.yuvomi.feature.tasks.Mentions
import io.github.marcomow.yuvomi.feature.tasks.RRule
import io.github.marcomow.yuvomi.feature.tasks.TaskBodies
import io.github.marcomow.yuvomi.feature.tasks.TaskEditorViewModel.Form
import io.github.marcomow.yuvomi.feature.tasks.TaskGrouping
import io.github.marcomow.yuvomi.feature.tasks.TaskGrouping.DueBucket
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskGroupingTest {
    private val today = LocalDate.parse("2026-11-01")

    /** TASKS-A3. */
    @Test fun `due buckets like upstream`() {
        assertEquals(DueBucket.OVERDUE, TaskGrouping.bucket("2026-10-31", today))
        assertEquals(DueBucket.TODAY, TaskGrouping.bucket("2026-11-01", today))
        assertEquals(DueBucket.THIS_WEEK, TaskGrouping.bucket("2026-11-04", today))
        assertEquals(DueBucket.NEXT_WEEK, TaskGrouping.bucket("2026-11-08", today))
        assertEquals(DueBucket.LATER, TaskGrouping.bucket("2026-11-09", today))
        assertEquals(DueBucket.NO_DATE, TaskGrouping.bucket(null, today))
    }

    @Test fun `category groups follow sort order and put hand-placed tasks first`() {
        val cats = listOf(TaskCategory("household", sortOrder = 0), TaskCategory("misc", sortOrder = 7))
        val tasks = listOf(Task(1, category = "misc"), Task(2, category = "household"), Task(3, category = "household", sortOrder = 1))
        val groups = TaskGrouping.group(tasks, TaskGrouping.Mode.CATEGORY, cats, today) { it }
        assertEquals(listOf("household", "misc"), groups.map { it.id })
        assertEquals(listOf(3, 2), groups[0].tasks.map { it.id })
    }
}

/** TASKS-A11, TASKS-A12: upstream's test/test-rrule-ui.js cases. */
class RRuleTest {
    @Test fun `parses with and without the RRULE prefix`() {
        val p = RRule.parse("RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=WE")
        assertEquals("WEEKLY", p.freq)
        assertEquals(listOf("WE"), p.byday)
        assertEquals(1, p.interval)
        assertEquals(RRule.parse("RRULE:FREQ=WEEKLY;BYDAY=WE"), RRule.parse("FREQ=WEEKLY;BYDAY=WE"))
    }

    @Test fun `an untouched series goes back verbatim`() {
        val rule = "RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=WE"
        assertEquals(rule, RRule.resolve(RRule.parse(rule), rule))
    }

    @Test fun `foreign parts survive when the form is unchanged`() {
        val rule = "RRULE:FREQ=MONTHLY;BYSETPOS=3;BYDAY=TH"
        assertEquals(rule, RRule.resolve(RRule.parse(rule), rule))
        assertTrue(RRule.hasForeignParts(rule))
        assertFalse(RRule.hasForeignParts("FREQ=WEEKLY;INTERVAL=1;BYDAY=WE"))
    }

    @Test fun `a real change wins over the source`() =
        assertEquals("FREQ=DAILY", RRule.resolve(RRule.parse("RRULE:FREQ=WEEKLY;BYDAY=WE").copy(freq = "DAILY"), "RRULE:FREQ=WEEKLY;BYDAY=WE"))

    @Test fun `removing the repetition stays removed`() =
        assertNull(RRule.resolve(RRule.parse("FREQ=WEEKLY;BYDAY=WE").copy(freq = ""), "FREQ=WEEKLY;BYDAY=WE"))

    @Test fun `last day of month is BYMONTHDAY=-1 and only for monthly`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", RRule.build(RRule.Parts(freq = "MONTHLY", lastDay = true)))
        assertEquals("FREQ=YEARLY", RRule.build(RRule.Parts(freq = "YEARLY", lastDay = true)))
        assertTrue(RRule.parse("FREQ=MONTHLY;BYMONTHDAY=-1").lastDay)
        assertFalse(RRule.parse("FREQ=MONTHLY;BYMONTHDAY=15").lastDay)
        assertNotEquals("FREQ=MONTHLY;BYMONTHDAY=15", RRule.build(RRule.parse("FREQ=MONTHLY;BYMONTHDAY=15")))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", RRule.build(RRule.parse("FREQ=MONTHLY;BYMONTHDAY=-1")))
    }

    @Test fun `count wins over until, until is a plain date`() {
        assertEquals("FREQ=DAILY;COUNT=3", RRule.build(RRule.Parts(freq = "DAILY", count = 3, until = "2026-12-01")))
        assertEquals("FREQ=DAILY;INTERVAL=2;UNTIL=20261201", RRule.build(RRule.Parts(freq = "DAILY", interval = 2, until = "2026-12-01")))
        assertEquals("2026-12-01", RRule.parse("FREQ=DAILY;UNTIL=20261201T235959Z").until)
    }
}

/** TASKS-A14: upstream's test/test-mentions.js cases. */
class MentionsTest {
    private val users = listOf(Mentions.Person(1, "Anna"), Mentions.Person(2, "Anna Maria"), Mentions.Person(3, "Ben"))

    @Test fun `a simple name`() {
        val s = Mentions.split("Kannst du das übernehmen, @Ben?", users)
        assertEquals(listOf("Text", "Mention", "Text"), s.map { it::class.simpleName })
        assertEquals("@Ben", s[1].text)
        assertEquals("?", s[2].text)
    }

    @Test fun `the longest name wins and ends at the word`() {
        assertEquals(listOf(2), Mentions.mentionedIds("@Anna Maria bringt die Leiter mit", users))
        assertEquals("@Anna Maria", Mentions.split("@Anna Maria, danke!", users)[0].text)
    }

    @Test fun `non-mentions`() {
        assertEquals(emptyList<Int>(), Mentions.mentionedIds("@Bernd kommt später", users))
        assertEquals(emptyList<Int>(), Mentions.mentionedIds("Schreib an anna@Ben.example", users))
        assertEquals(emptyList<Int>(), Mentions.mentionedIds("Kaffee4@Ben", users))
        assertEquals(listOf(Mentions.Segment.Text("Preis @ 5 Euro")), Mentions.split("Preis @ 5 Euro", users))
        assertEquals(emptyList<Mentions.Segment>(), Mentions.split("", users))
    }

    @Test fun `case does not matter and the text stays as typed`() {
        val m = Mentions.split("@ANNA hat Zeit", users)[0] as Mentions.Segment.Mention
        assertEquals("@ANNA", m.text)
        assertEquals(1, m.person.id)
    }

    @Test fun `each person once, in text order`() {
        assertEquals(listOf(3, 1), Mentions.mentionedIds("@Ben und @Anna, dann nochmal @Ben", users))
        assertEquals(listOf(3), Mentions.mentionedIds("Erledigt.\n@Ben übernimmt", users))
    }

    @Test fun `apply replaces the started name`() {
        assertEquals("@Alex Johnson " to 14, Mentions.apply("@Ale", 2, "Alex Johnson"))
        assertEquals("@Alex Johnson " to 14, Mentions.apply("@Ale", 4, "Alex Johnson"))
        assertEquals("@Anna Maria " to 12, Mentions.apply("@A", 2, "Anna Maria"))
        assertEquals("@Anna Maria ", Mentions.apply("@Anna Maria", 4, "Anna Maria")!!.first)
        assertEquals("@Anna Maria und Ben", Mentions.apply("@Anna Maria und Ben", 4, "Anna Maria")!!.first)
        assertEquals("@Anna Maria ", Mentions.apply("@Anna Ma", 4, "Anna Maria")!!.first)
        assertEquals("@Anna Maria und Ben", Mentions.apply("@Anna Ma und Ben", 4, "Anna Maria")!!.first)
    }
}

class ChecklistTest {
    @Test fun `matches like markdown-checklist js`() {
        assertEquals(false, Checklist.match("- [ ] milk")!!.checked)
        assertEquals(true, Checklist.match("   * [X] eggs")!!.checked)
        assertEquals("eggs", Checklist.match("+ [x] eggs")!!.text)
        assertNull(Checklist.match("    - [ ] four spaces is code"))
        assertNull(Checklist.match("- [] no space"))
    }

    @Test fun `line numbers are source lines across line endings`() {
        assertEquals(listOf("a", "- [ ] b", "c", ""), Checklist.lines("a\r\n- [ ] b\rc\n"))
        assertEquals("- [x] b", Checklist.toggled("- [ ] b", true))
    }
}

class TaskBodiesTest {
    private val task = Task(7, title = "Filter", recurrenceRule = "RRULE:FREQ=MONTHLY;BYSETPOS=3;BYDAY=TH", isRecurring = true)
    private val old = Form(title = "Filter", rule = RRule.parse(task.recurrenceRule), points = "5", assignees = setOf(1))

    /** TASKS-A5. */
    @Test fun `an edit sends only what changed`() {
        val body = TaskBodies.diff(old, old.copy(title = "Air filter"), task, solo = false)
        assertEquals(setOf("title"), body.keys)
    }

    @Test fun `an untouched foreign rule is never sent`() =
        assertFalse("recurrence_rule" in TaskBodies.diff(old, old.copy(priority = "high"), task, solo = false))

    @Test fun `a changed rule is rebuilt`() {
        val body = TaskBodies.diff(old, old.copy(rule = RRule.Parts(freq = "WEEKLY")), task, solo = false)
        assertEquals(JsonPrimitive("FREQ=WEEKLY"), body["recurrence_rule"])
    }

    /** TASKS-A18. */
    @Test fun `a household of one never sends assignees or visibility`() {
        val edit = TaskBodies.diff(old, old.copy(assignees = emptySet(), visibility = "private"), task, solo = true)
        assertTrue(edit.isEmpty())
        val create = TaskBodies.create(Form(title = "x"), null, solo = true)
        assertFalse("assigned_to" in create || "visibility" in create)
    }

    @Test fun `create leaves points to the server default and sends assignees as an array`() {
        val body = TaskBodies.create(Form(title = " Bins ", assignees = setOf(3, 1)), parentId = null, solo = false)
        assertEquals(JsonPrimitive("Bins"), body["title"])
        assertFalse("points" in body)
        assertEquals(listOf(1, 3), body["assigned_to"]!!.jsonArray.map { (it as JsonPrimitive).content.toInt() })
        assertFalse("parent_task_id" in body)
    }

    @Test fun `a subtask carries its parent and no recurrence`() {
        val body = TaskBodies.create(Form(title = "step", rule = RRule.Parts(freq = "DAILY")), parentId = 9, solo = false)
        assertEquals(JsonPrimitive(9), body["parent_task_id"])
        assertFalse("recurrence_rule" in body)
    }
}
