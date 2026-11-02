package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

/** docs/spec/tags.md: the index of the Tags view and the task tag rules. */
class TagLogicTest {
    private val strings = Strings(
        "en",
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()),
        emptyMap(),
    ) { if (it == 1.0) "one" else "other" }

    private fun item(module: String, key: String, vararg tags: String, title: String = key) =
        TaggedItem(module, key, title, null, tags.toSet(), "route/$key")

    private val items = listOf(
        item("tasks", "task:1", "Garden", "Kids"),
        item("notes", "note:2", "garden"),
        item("shopping", "item:3", "garden", title = "Soil"),
        item("shopping", "item:4", "BBQ", title = "Charcoal"),
        item("calendar", "event:5", "bbq"),
    )

    /** TAGS-A1, TAGS-R4: one row per key, the most used spelling, most used first then by name. */
    @Test fun `summarize counts across modules by key`() {
        val tags = TagIndex.summarize(items)
        assertEquals(listOf("garden", "BBQ", "Kids"), tags.map { it.label })
        assertEquals(listOf(3, 2, 1), tags.map { it.count })
        // Modules in the order of ModuleCatalog: tasks, calendar, notes, ..., shopping.
        assertEquals(listOf("tasks", "notes", "shopping"), tags[0].modules)
        assertEquals(listOf("calendar", "shopping"), tags[1].modules)
    }

    @Test fun `a tie between spellings keeps the first one seen`() =
        assertEquals("BBQ", TagIndex.summarize(listOf(item("a", "1", "BBQ"), item("b", "2", "bbq"))).single().label)

    /** TAGS-A2: search narrows by key and offers any typed tag that is not one exactly. */
    @Test fun `search filters and offers find`() {
        val tags = TagIndex.summarize(items)
        assertEquals(listOf("garden"), TagIndex.filter(tags, "#GAR").map { it.label })
        assertEquals(tags, TagIndex.filter(tags, "  "))
        assertEquals("gar", TagIndex.offersFind(tags, "#gar"))
        assertNull(TagIndex.offersFind(tags, "#Garden"))
        assertNull(TagIndex.offersFind(tags, "#"))
        assertEquals("Haus, Hof", TagIndex.typedTag(" #Haus, Hof "))
        assertEquals(64, TagIndex.typedTag("x".repeat(80))!!.length)
    }

    /** TAGS-A3: a tag's page groups by module in catalog order, items by title. */
    @Test fun `groups by module`() {
        val groups = TagIndex.groups(items, "GARDEN")
        assertEquals(listOf("tasks", "notes", "shopping"), groups.map { it.moduleId })
        assertEquals(listOf("Soil"), groups[2].items.map { it.title })
        assertEquals("garden", TagIndex.label(items, "Garden"))
        assertEquals("unknown", TagIndex.label(items, "unknown"))
        assertEquals(listOf("Kids"), TagIndex.otherTags(items[0], "garden"))
        assertEquals(emptyList<TagGroup>(), TagIndex.groups(items, "nothing"))
    }

    @Test fun `union keeps one spelling per key`() =
        assertEquals(setOf("Garten", "Äpfel"), TagIndex.union(listOf("Garten", " ", "Äpfel"), listOf("garten", "äpfel")))

    /** TAGS-R3: the cache round-trips every field. */
    @Test fun `cache encodes and decodes items`() {
        val stored = listOf(TaggedItem("calendar", "event:1", "Fair", "Nov 4", setOf("a", "B"), null, "/calendar"))
        assertEquals(stored, TagIndex.decode(TagIndex.encode(stored)))
    }

    /** TAGS-A5: rename rules as `_rename` and `normalizeTags`. */
    @Test fun `rename sends only a changed, valid name`() {
        assertEquals(TaskTagRules.Rename.Unchanged, TaskTagRules.rename("garden", "  garden "))
        assertEquals(TaskTagRules.Rename.Unchanged, TaskTagRules.rename("garden", "   "))
        assertEquals(TaskTagRules.Rename.Send("Garden"), TaskTagRules.rename("garden", "Garden"))
        assertEquals(TaskTagRules.Rename.Invalid, TaskTagRules.rename("garden", ".."))
        assertEquals(TaskTagRules.Rename.Invalid, TaskTagRules.rename("garden", "."))
        assertEquals(TaskTagRules.Rename.Send("Haus, Hof"), TaskTagRules.rename("haus", "Haus, Hof"))
        assertEquals("Garden", TaskTagRules.find(listOf(TaskTagCount("Garden", 2)), "garden")?.tag)
        assertNull(TaskTagRules.find(listOf(TaskTagCount("Garden", 2)), "lawn"))
    }

    /** TAGS-A6: the success messages name the locked tasks left out (`withSkipped`). */
    @Test fun `messages count updates and skipped locked tasks`() {
        assertEquals("1 task updated", TaskTagRules.renamed(TaskTagChange(updated = 1), strings))
        assertEquals("3 tasks updated 1 locked task skipped.", TaskTagRules.renamed(TaskTagChange(updated = 3, skipped = 1), strings))
        assertEquals("Tag removed from 2 tasks", TaskTagRules.deleted(TaskTagChange(updated = 2), strings))
    }

    @Test fun `errors map to the app's sentences`() {
        assertEquals("android.tags.taskTagGone", TaskTagRules.errorKey(ApiProblem.Http(404, null, "Tag not found.")))
        assertEquals("android.tags.nameInvalid", TaskTagRules.errorKey(ApiProblem.Http(400, null, "name must be a non-empty tag.")))
        assertEquals("android.error.forbidden", TaskTagRules.errorKey(ApiProblem.Http(403, "forbidden", null)))
        assertEquals("android.error.offline", TaskTagRules.errorKey(ApiProblem.Network(IOException("down"))))
        assertEquals("android.error.offline", TagStore.errorKey(ApiProblem.Network(IOException("down"))))
        assertEquals("android.tags.loadFailed", TagStore.errorKey(ApiProblem.Http(500, null, null)))
    }
}
