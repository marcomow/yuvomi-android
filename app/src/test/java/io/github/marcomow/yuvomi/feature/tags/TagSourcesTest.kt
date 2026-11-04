package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.feature.budget.BudgetEntry
import io.github.marcomow.yuvomi.feature.budget.BudgetRoutes
import io.github.marcomow.yuvomi.feature.calendar.CalendarEvent
import io.github.marcomow.yuvomi.feature.calendar.CalendarRoutes
import io.github.marcomow.yuvomi.feature.documents.Document
import io.github.marcomow.yuvomi.feature.documents.DocumentsRoutes
import io.github.marcomow.yuvomi.feature.health.Activity
import io.github.marcomow.yuvomi.feature.health.HealthRoutes
import io.github.marcomow.yuvomi.feature.health.Medication
import io.github.marcomow.yuvomi.feature.health.PreventionRecord
import io.github.marcomow.yuvomi.feature.health.PreventionType
import io.github.marcomow.yuvomi.feature.health.Vital
import io.github.marcomow.yuvomi.feature.meals.Meal
import io.github.marcomow.yuvomi.feature.meals.MealsRoutes
import io.github.marcomow.yuvomi.feature.notes.Note
import io.github.marcomow.yuvomi.feature.notes.NotesRoutes
import io.github.marcomow.yuvomi.feature.pantry.PantryItem
import io.github.marcomow.yuvomi.feature.pantry.PantryRoutes
import io.github.marcomow.yuvomi.feature.recipes.Recipe
import io.github.marcomow.yuvomi.feature.recipes.RecipesRoutes
import io.github.marcomow.yuvomi.feature.shell.Routes
import io.github.marcomow.yuvomi.feature.shopping.ShoppingItem
import io.github.marcomow.yuvomi.feature.shopping.ShoppingList
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import io.github.marcomow.yuvomi.feature.tags.sources.BudgetTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.CalendarTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.DocumentsTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.HealthTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.MealsTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.NotesTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.PantryTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.RecipesTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.ShoppingTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.SourceText
import io.github.marcomow.yuvomi.feature.tags.sources.TasksTagSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** docs/spec/tags.md, TAGS-R2: what each module's source turns into tagged items. */
class TagSourcesTest {
    private val text = SourceText.plain

    @Test fun `tasks carry real tags and hashtags, untagged tasks are left out`() {
        val tasks = listOf(
            Task(id = 1, title = "Plant #tomatoes", tags = listOf("Garden"), dueDate = "2026-11-04"),
            Task(id = 2, title = "Fix fence", description = "with #garden tools", status = Task.STATUS_DONE),
            Task(id = 3, title = "Nothing here"),
        )
        val items = TasksTagSource.items(tasks, text)
        assertEquals(listOf("task:1", "task:2"), items.map { it.key })
        assertEquals(setOf("Garden", "tomatoes"), items[0].tags)
        assertEquals(Routes.task(1), items[0].route)
        assertEquals("2026-11-04", items[0].subtitle)
        assertEquals("tasks.statusDone", items[1].subtitle)
        assertEquals(listOf("open", "in_progress", "done"), TasksTagSource.filter.statuses)
        assertTrue(TasksTagSource.filter.includeFuture)
        assertTrue(TasksTagSource.filter.includeArchived)
        assertTrue("archived" to "1" in TasksTagSource.filter.query())
    }

    /** TAGS-R14: done and archived tasks, checked items, archived documents and notes are done. */
    @Test fun `completed and archived items are marked done`() {
        val tasks = TasksTagSource.items(
            listOf(
                Task(id = 1, title = "#a open"),
                Task(id = 2, title = "#a done", status = Task.STATUS_DONE),
                Task(id = 3, title = "#a old", archivedAt = "2026-10-01 10:00:00"),
            ),
            text,
        )
        assertEquals(listOf(false, true, true), tasks.map { it.finished })
        assertEquals(listOf(false, false, true), tasks.map { it.archived })
        assertEquals("tasks.statusArchived", tasks[2].subtitle)
        val docs = DocumentsTagSource.items(
            listOf(Document(id = 1, name = "#a now"), Document(id = 2, name = "#a old", status = io.github.marcomow.yuvomi.feature.documents.DocumentRules.STATUS_ARCHIVED)),
        )
        assertEquals(listOf(false, true), docs.map { it.archived })
        val notes = NotesTagSource.items(
            listOf(
                io.github.marcomow.yuvomi.feature.notes.Note(1, "#a kept"),
                io.github.marcomow.yuvomi.feature.notes.Note(2, "#a gone", "text\n\n#archived"),
            ),
            text,
        )
        assertEquals(listOf(false, true), notes.map { it.archived })
        val tagged = TasksTagSource.items(listOf(Task(id = 4, title = "#a", tags = listOf("Archived"))), text).single()
        assertTrue(tagged.archived && tagged.finished && !tagged.done)
    }

    @Test fun `shopping items keep their mirrored tags and lists with a hashtag count too`() {
        val list = ShoppingList(id = 4, name = "Party #bbq")
        val rows = listOf(
            ShoppingItem(id = 40, name = "Charcoal", tags = listOf("BBQ"), quantity = "2 bags"),
            ShoppingItem(id = 41, name = "Milk", notes = "for #breakfast"),
            ShoppingItem(id = 42, name = "Bread"),
        )
        val items = ShoppingTagSource.items(listOf(list to rows))
        assertEquals(listOf("list:4", "item:40", "item:41"), items.map { it.key })
        assertEquals(ShoppingRoutes.list(4), items[0].route)
        assertEquals(ShoppingRoutes.item(4, 40), items[1].route)
        assertEquals("Party #bbq · 2 bags", items[1].subtitle)
        assertEquals(setOf("breakfast"), items[2].tags)
    }

    @Test fun `notes take their title or first line`() {
        val notes = listOf(
            Note(id = 1, title = "Seeds", content = "Buy #garden seeds"),
            Note(id = 2, title = null, content = "## Trip #holiday\nmore", creatorName = "Linda"),
            Note(id = 3, title = "Plain", content = "no tags"),
        )
        val items = NotesTagSource.items(notes, text)
        assertEquals(listOf("Seeds", "Trip #holiday"), items.map { it.title })
        assertEquals(NotesRoutes.note(2), items[1].route)
        assertEquals("Linda", items[1].subtitle)
    }

    @Test fun `kitchen sources read titles and notes`() {
        val recipes = RecipesTagSource.items(listOf(Recipe(id = 5, title = "Pesto", notes = "#vegan"), Recipe(id = 6, title = "Stew")))
        assertEquals(listOf(RecipesRoutes.recipe(5)), recipes.map { it.route })

        val meals = MealsTagSource.items(
            listOf(
                Meal(id = 7, date = "2026-11-03", mealType = "dinner", title = "Pizza #friday"),
                Meal(id = 7, date = "2026-11-03", mealType = "dinner", title = "Pizza #friday"),
                Meal(id = 8, date = "2026-11-04", mealType = "lunch", title = "Soup"),
            ),
            text,
        )
        assertEquals(1, meals.size)
        assertEquals(MealsRoutes.week("2026-11-03"), meals[0].route)
        assertEquals("2026-11-03 · meals.typeDinner", meals[0].subtitle)
        assertEquals(13, MealsTagSource.weeks(LocalDate.of(2026, 11, 2)).size)

        val pantry = PantryTagSource.items(listOf(PantryItem(id = 9, name = "Rice", notes = "#bulk", locationName = "Cellar")))
        assertEquals(PantryRoutes.START, pantry.single().route)
        assertEquals("Cellar", pantry.single().subtitle)
    }

    @Test fun `a calendar series is one item showing its next occurrence`() {
        val today = LocalDate.of(2026, 11, 2)
        val events = listOf(
            CalendarEvent(id = 10, title = "Choir #music", startDatetime = "2026-10-26T19:00"),
            CalendarEvent(id = 10, title = "Choir #music", startDatetime = "2026-11-09T19:00"),
            CalendarEvent(id = 11, title = "Dentist", location = "Clinic #health"),
            CalendarEvent(id = 12, title = "Lunch"),
        )
        val items = CalendarTagSource.items(events, today, text)
        assertEquals(listOf("event:10", "event:11"), items.map { it.key })
        assertEquals("2026-11-09", items[0].subtitle)
        assertEquals(CalendarRoutes.event(10), items[0].route)
        assertEquals(LocalDate.of(2025, 11, 2) to LocalDate.of(2027, 11, 2), CalendarTagSource.window(today))
    }

    @Test fun `documents read name and description`() {
        val items = DocumentsTagSource.items(listOf(Document(id = 3, name = "Lease", description = "#flat contract", folderName = "Home")))
        assertEquals(DocumentsRoutes.document(3), items.single().route)
        assertEquals(setOf("flat"), items.single().tags)
    }

    @Test fun `health items open their area`() {
        val data = HealthTagSource.Data(
            vitals = listOf(Vital(id = 1, type = "weight", measuredAt = "2026-11-01T08:00", note = "after #holiday")),
            medications = listOf(Medication(id = 2, name = "Ibuprofen", note = "#knee")),
            prevention = listOf(PreventionRecord(id = 3, typeId = 9, givenOn = "2026-01-10", note = "#travel")),
            preventionTypes = listOf(PreventionType(id = 9, name = "Tetanus")),
            activities = listOf(Activity(id = 4, type = "running", performedAt = "2026-11-02", note = "#knee")),
        )
        val items = HealthTagSource.items(data, text)
        assertEquals(listOf(HealthRoutes.VITALS, HealthRoutes.MEDS, HealthRoutes.PREVENTION, HealthRoutes.ACTIVITY), items.map { it.route })
        assertEquals("health.vitals.metric.weight", items[0].title)
        assertEquals("health.tabs.vitals · 2026-11-01", items[0].subtitle)
        assertEquals("Tetanus", items[2].title)
        assertEquals("health.activity.type.running", items[3].title)
    }

    @Test fun `budget series are one item, months reach back a year`() {
        val entries = listOf(
            BudgetEntry(id = 1, title = "Rent #flat", date = "2026-10-01", isRecurring = true),
            BudgetEntry(id = null, title = "Rent #flat", date = "2026-11-01", recurrenceParentId = 1),
            BudgetEntry(id = 3, title = "Pizza", date = "2026-10-20"),
        )
        val items = BudgetTagSource.items(entries, text)
        assertEquals(listOf("budget-series:1"), items.map { it.key })
        assertEquals("2026-11-01", items.single().subtitle)
        assertEquals(BudgetRoutes.START, items.single().route)
        val months = BudgetTagSource.months(LocalDate.of(2026, 11, 2))
        assertEquals(12, months.size)
        assertEquals("2026-11", months.first())
        assertEquals("2025-12", months.last())
    }

    /** TAGS-R1: every native module without a source of its own elsewhere is registered. */
    @Test fun `registry lists the sources once each`() {
        // One object per source; Budget's page has three (budget, subscriptions, split expenses).
        assertEquals(TagSources.all.distinct(), TagSources.all)
        assertEquals(
            setOf(
                "tasks", "calendar", "notes", "schedule", "meals", "recipes", "shopping", "pantry", "documents", "health",
                "budget", "rewards", "birthdays", "contacts", "inventory", "housekeeping", "waste",
            ),
            TagSources.all.map { it.moduleId }.toSet(),
        )
    }
}
