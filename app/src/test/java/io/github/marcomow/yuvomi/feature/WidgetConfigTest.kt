package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.feature.overview.WidgetConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OVERVIEW-A2, OVERVIEW-A3: upstream's "Widget-Konfiguration" cases from test/test-dashboard.js. */
class WidgetConfigTest {

    private val ids = WidgetConfig.WIDGET_IDS

    private fun entry(id: String, order: Int, visible: Boolean = WidgetConfig.defaultVisible(id), size: String = WidgetConfig.defaultSize(id),
                      options: JsonObject? = null) = buildJsonObject {
        put("id", id); put("order", order); put("visible", visible); put("size", size)
        if (options != null) put("options", options)
    }

    private fun defaults(): List<JsonObject> = ids.mapIndexed { i, id -> entry(id, i) }
    private fun without(vararg drop: String): JsonElement =
        JsonArray(defaults().filter { (it["id"] as JsonPrimitive).content !in drop }.mapIndexed { i, o -> JsonObject(o + ("order" to JsonPrimitive(i))) })

    @Test fun `the widget ids match upstream`() = assertEquals(
        listOf("tasks", "calendar", "meals", "shopping", "pantry", "birthdays", "countdown", "budget", "rewards", "health", "cycle",
            "fasting", "nutrition", "housekeeping", "schedule", "waste", "family", "notes", "weather", "clock", "metrics", "quicklinks"),
        ids,
    )

    @Test fun `null means the shipped defaults`() {
        val merged = WidgetConfig.normalize(JsonNull)
        assertEquals(ids, merged.map { it.id })
        assertEquals(listOf("birthdays", "countdown", "family", "notes", "metrics"), merged.filter { it.visible }.map { it.id })
    }

    @Test fun `a missing id lands at its default position`() {
        val wrong = ids.filter { id -> WidgetConfig.normalize(without(id)).map { it.id } != ids }
        assertTrue("misplaced: $wrong", wrong.isEmpty())
    }

    @Test fun `two missing neighbours keep their order`() =
        assertEquals(ids, WidgetConfig.normalize(without("meals", "shopping")).map { it.id })

    @Test fun `a missing first id lands first`() = assertEquals("tasks", WidgetConfig.normalize(without("tasks"))[0].id)

    @Test fun `a reordered layout lets the newcomer follow its predecessor`() {
        val demo = listOf("weather", "family", "budget", "birthdays", "rewards", "notes", "tasks", "calendar", "shopping", "meals", "housekeeping", "health", "cycle")
            .mapIndexed { i, id -> entry(id, i, visible = i < 6, size = "1x1") }
        val visible = WidgetConfig.normalize(JsonArray(demo)).filter { it.visible }.map { it.id }
        assertEquals("weather,metrics,family,budget,birthdays,countdown,rewards,notes", visible.joinToString(","))
    }

    @Test fun `stored order wins over array position`() {
        val mixed = JsonArray((without("notes") as JsonArray).reversed())
        assertEquals(ids, WidgetConfig.normalize(mixed).map { it.id })
    }

    @Test fun `stored size and visibility stay, a new opt-in widget stays hidden`() {
        val saved = JsonArray(defaults().filter { (it["id"] as JsonPrimitive).content != "metrics" }.map {
            if ((it["id"] as JsonPrimitive).content == "tasks") entry("tasks", 0, visible = true, size = "2x2") else it
        })
        val tasks = WidgetConfig.normalize(saved).first { it.id == "tasks" }
        assertEquals("2x2", tasks.size)
        assertTrue(tasks.visible)
        assertFalse(WidgetConfig.normalize(without("health")).first { it.id == "health" }.visible)
    }

    @Test fun `options are noticed, empty options are not`() {
        val saved = JsonArray(defaults().map {
            if ((it["id"] as JsonPrimitive).content == "tasks") entry("tasks", 0, options = buildJsonObject { put("x", true) }) else it
        })
        val merged = WidgetConfig.normalize(saved)
        assertTrue(merged.first { it.id == "tasks" }.hasOptions)
        assertFalse(merged.first { it.id == "weather" }.hasOptions)
    }

    @Test fun `legacy sizes snap to the nearest preset`() {
        assertEquals("2x2", WidgetConfig.nearestPreset("3x3"))
        assertEquals("1x2", WidgetConfig.nearestPreset("1x4"))
        assertEquals("2x1", WidgetConfig.nearestPreset("4x1"))
    }

    /** OVERVIEW-A14: the phone takes its own order and visibility, size from the desktop. */
    @Test fun `the phone arrangement wins order and visibility`() {
        fun w(id: String, order: Int, visible: Boolean = true, size: String? = null) = buildJsonObject {
            put("id", id); put("order", order); put("visible", visible); if (size != null) put("size", size)
        }
        val desktop = JsonArray(listOf(w("tasks", 0, size = "2x2"), w("shopping", 1), w("calendar", 2)))
        val phone = JsonArray(listOf(w("calendar", 0), w("tasks", 1), w("shopping", 2, visible = false)))
        val merged = WidgetConfig.forPhone(desktop, phone)
        val mine = setOf("calendar", "tasks", "shopping")
        assertEquals(listOf("calendar", "tasks", "shopping"), merged.map { it.id }.filter { it in mine })
        assertEquals("2x2", merged.first { it.id == "tasks" }.size)
        assertFalse(merged.first { it.id == "shopping" }.visible)
        // Without a phone arrangement (null) the phone follows the desktop.
        assertEquals(WidgetConfig.normalize(desktop), WidgetConfig.forPhone(desktop, JsonNull))
        assertEquals(WidgetConfig.normalize(desktop), WidgetConfig.forPhone(desktop, null))
    }
}
