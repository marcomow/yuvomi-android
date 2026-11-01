package io.github.marcomow.yuvomi.feature.overview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Port of upstream's `public/utils/dashboard-widgets.js`: the widget ids, their
 * defaults, and `normalizeDashboardConfig` (OVERVIEW-R7).
 */
object WidgetConfig {

    data class Widget(val id: String, val visible: Boolean, val order: Int, val size: String, val hasOptions: Boolean = false)

    val WIDGET_IDS = listOf(
        "tasks", "calendar", "meals", "shopping", "pantry", "birthdays", "countdown", "budget", "rewards",
        "health", "cycle", "fasting", "nutrition", "housekeeping", "schedule", "waste", "family", "notes",
        "weather", "clock", "metrics", "quicklinks",
    )
    val COCKPIT_COVERED = setOf("tasks", "calendar", "shopping", "meals")
    val DEFAULT_HIDDEN = COCKPIT_COVERED + setOf(
        "budget", "rewards", "health", "cycle", "fasting", "nutrition", "housekeeping", "schedule", "waste",
        "pantry", "clock", "weather", "quicklinks",
    )
    private val PRESETS = listOf("1x1", "2x1", "1x2", "2x2")
    private val SIZE_OPTIONS = (PRESETS + listOf("1x3", "1x4", "2x3", "2x4", "3x1", "3x3", "3x4", "4x1", "4x3", "4x4")).toSet()

    fun defaultVisible(id: String) = id !in DEFAULT_HIDDEN

    fun defaultSize(id: String): String = when (id) {
        "tasks", "calendar", "rewards", "budget", "family", "notes", "birthdays", "countdown", "schedule", "waste", "pantry" -> "1x2"
        "weather", "shopping", "health", "cycle", "fasting", "nutrition", "meals", "clock", "quicklinks", "metrics" -> "2x1"
        else -> "1x1"
    }

    fun nearestPreset(size: String): String {
        if (size in PRESETS) return size
        val parts = size.split('x').map { it.toIntOrNull() }
        val cols = parts.getOrNull(0) ?: return "1x1"
        val rows = parts.getOrNull(1) ?: return "1x1"
        return "${if (cols >= 2) 2 else 1}x${if (rows >= 2) 2 else 1}"
    }

    private fun defaultInsertIndex(ordered: List<Widget>, missingId: String): Int {
        for (i in WIDGET_IDS.indexOf(missingId) - 1 downTo 0) {
            val at = ordered.indexOfFirst { it.id == WIDGET_IDS[i] }
            if (at != -1) return at + 1
        }
        return 0
    }

    fun normalize(input: JsonElement?): List<Widget> {
        val valid = (input as? JsonArray).orEmpty().mapIndexedNotNull { i, element ->
            val w = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = (w["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapIndexedNotNull null
            if (id !in WIDGET_IDS) return@mapIndexedNotNull null
            val visible = (w["visible"] as? JsonPrimitive)?.booleanOrNull != false
            val order = (w["order"] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() }
                ?.takeIf { it.isFinite() } ?: i.toDouble()
            val rawSize = (w["size"] as? JsonPrimitive)?.content
            val size = if (rawSize != null && rawSize in SIZE_OPTIONS) nearestPreset(rawSize) else defaultSize(id)
            val options = (w["options"] as? JsonObject)?.isNotEmpty() == true
            order to Widget(id, visible, 0, size, options)
        }
        val ordered = valid.sortedBy { it.first }.map { it.second }.toMutableList()
        val present = ordered.map { it.id }.toMutableSet()
        for (id in WIDGET_IDS) {
            if (id in present) continue
            ordered.add(defaultInsertIndex(ordered, id), Widget(id, defaultVisible(id), 0, defaultSize(id)))
            present += id
        }
        return ordered.mapIndexed { i, w -> w.copy(order = i) }
    }
}
