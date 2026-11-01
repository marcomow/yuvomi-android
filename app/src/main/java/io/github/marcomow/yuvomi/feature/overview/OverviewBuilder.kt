package io.github.marcomow.yuvomi.feature.overview

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.model.DashBirthday
import io.github.marcomow.yuvomi.core.api.model.DashEvent
import io.github.marcomow.yuvomi.core.api.model.DashMeal
import io.github.marcomow.yuvomi.core.api.model.Member
import io.github.marcomow.yuvomi.core.api.model.MemberOpenTasks
import io.github.marcomow.yuvomi.core.api.model.Permissions
import io.github.marcomow.yuvomi.core.api.model.Preferences
import io.github.marcomow.yuvomi.core.api.model.ShoppingTile
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.ZonedDateTime

/** What the overview shows, built from `/dashboard` and `/preferences` (docs/spec/overview.md). */
data class OverviewModel(
    val greetingKey: String,
    val firstName: String,
    val glance: List<GlanceRow>?,
    val tiles: List<Tile>,
    val tilesWithOptions: Boolean,
)

sealed interface GlanceRow {
    data class Event(val event: DashEvent, val start: ZonedDateTime?, val running: Boolean) : GlanceRow
    data class TaskRow(val task: Task, val overdue: Boolean) : GlanceRow
    data class Meal(val meal: DashMeal) : GlanceRow
    data class Shopping(val count: Int) : GlanceRow
}

sealed interface Tile {
    val id: String

    data class Tasks(val tasks: List<Task>, val openCount: Int?, val overdueCount: Int?) : Tile {
        override val id = "tasks"
    }

    data class Calendar(val days: List<Pair<LocalDate, List<Pair<DashEvent, ZonedDateTime?>>>>) : Tile {
        override val id = "calendar"
    }

    data class Shopping(val openCount: Int, val lists: List<io.github.marcomow.yuvomi.core.api.model.ShoppingListSummary>) : Tile {
        override val id = "shopping"
    }

    data class Meals(val meals: List<DashMeal>) : Tile {
        override val id = "meals"
    }

    data class Birthdays(val birthdays: List<DashBirthday>, val total: Int) : Tile {
        override val id = "birthdays"
    }

    data class Family(val members: List<Pair<Member, Int>>) : Tile {
        override val id = "family"
    }

    /** Any other tile: module name and "open in the web app" (ADR 0006). */
    data class Generic(override val id: String) : Tile
}

object OverviewBuilder {

    /** Widget id to the permission module it depends on (upstream `permissions.js`). */
    private val WIDGET_MODULE = mapOf(
        "tasks" to "tasks", "calendar" to "calendar", "meals" to "meals", "shopping" to "shopping",
        "pantry" to "pantry", "birthdays" to "calendar", "budget" to "budget", "rewards" to "rewards",
        "health" to "health", "cycle" to "health", "fasting" to "health", "nutrition" to "health",
        "housekeeping" to "housekeeping", "schedule" to "schedule", "waste" to "waste", "notes" to "notes",
    )
    private const val GLANCE_TASK_LIMIT = 5

    fun build(
        dashboard: JsonObject,
        prefs: Preferences,
        permissions: Permissions,
        time: HouseholdTime,
        displayName: String,
    ): OverviewModel {
        val now = time.now()
        val today = now.toLocalDate()
        val widgets = WidgetConfig.normalize(prefs.dashboardWidgets)

        fun moduleOn(widget: String): Boolean {
            if (widget in prefs.disabledModules) return false
            val module = WIDGET_MODULE[widget] ?: return true
            return module !in prefs.disabledModules && permissions.module(module) != "none"
        }
        fun canSee(widget: String) = moduleOn(widget) && permissions.widgetAllowed(widget)
        val tileShown = { id: String -> widgets.any { it.id == id && it.visible } }

        // ── Today at a glance (OVERVIEW-R3..R6) ──
        val glance: List<GlanceRow>? = if (!prefs.dashboardTodayGlance) null else buildList {
            fun speaks(widget: String) = canSee(widget) && !tileShown(widget)
            if (speaks("calendar")) {
                dashboard.list("upcomingEvents", DashEvent.serializer()).orEmpty()
                    .mapNotNull { e ->
                        val start = time.parse(e.startDatetime)
                        val end = time.parse(e.endDatetime)
                        val startDay = start?.toLocalDate() ?: return@mapNotNull null
                        val endDay = if (e.allDay) (end?.toLocalDate() ?: startDay) else (end?.toLocalDate() ?: startDay)
                        if (today < startDay || today > endDay) return@mapNotNull null
                        val ended = !e.allDay && end != null && !end.isAfter(now)
                        if (ended) return@mapNotNull null
                        val running = !e.allDay && start <= now && (end == null || end.isAfter(now))
                        GlanceRow.Event(e, if (e.allDay) null else start, running)
                    }
                    .sortedWith(compareBy<GlanceRow.Event>({ !it.event.allDay }, { it.start }))
                    .forEach(::add)
            }
            if (speaks("tasks")) {
                dashboard.list("urgentTasks", Task.serializer()).orEmpty()
                    .filter { HouseholdTime.date(it.dueDate)?.let { d -> !d.isAfter(today) } == true }
                    .take(GLANCE_TASK_LIMIT)
                    .forEach { add(GlanceRow.TaskRow(it, HouseholdTime.date(it.dueDate)!!.isBefore(today))) }
            }
            if (speaks("meals")) {
                dashboard.list("todayMeals", DashMeal.serializer()).orEmpty().forEach { add(GlanceRow.Meal(it)) }
            }
            if (speaks("shopping")) {
                val count = (dashboard["shoppingOpenCount"] as? JsonPrimitive)?.intOrNull ?: 0
                if (count > 0) add(GlanceRow.Shopping(count))
            }
        }

        // ── Tiles (OVERVIEW-R7..R10, R12) ──
        val visible = widgets.filter { it.visible && canSee(it.id) }
        val tiles = visible.map { w -> tileFor(w.id, dashboard, time, today) ?: Tile.Generic(w.id) }

        return OverviewModel(
            greetingKey = greetingKey(now.hour),
            firstName = displayName.trim().substringBefore(' '),
            glance = glance,
            tiles = tiles,
            tilesWithOptions = visible.any { it.hasOptions },
        )
    }

    /** OVERVIEW-R2, upstream's `greeting()`. */
    fun greetingKey(hour: Int): String = when (hour) {
        in 5..11 -> "dashboard.greetingMorning"
        in 12..17 -> "dashboard.greetingDay"
        else -> "dashboard.greetingEvening"
    }

    private fun tileFor(id: String, d: JsonObject, time: HouseholdTime, today: LocalDate): Tile? = when (id) {
        "tasks" -> d.list("urgentTasks", Task.serializer())?.let {
            Tile.Tasks(it.take(5), d.int("openTaskCount"), d.int("overdueTaskCount"))
        }
        "calendar" -> d.list("upcomingEvents", DashEvent.serializer())?.let { events ->
            val dated = events.map { it to time.parse(it.startDatetime) }
            val grouped = dated.take(5).groupBy { (_, start) -> start?.toLocalDate() ?: today }
            Tile.Calendar(grouped.entries.sortedBy { it.key }.map { it.key to it.value })
        }
        "shopping" -> {
            val tile = d.piece("shoppingTile", ShoppingTile.serializer())
            if (tile != null) {
                Tile.Shopping(tile.openCount, tile.lists)
            } else {
                d.int("shoppingOpenCount")?.let { Tile.Shopping(it, emptyList()) }
            }
        }
        "meals" -> d.list("todayMeals", DashMeal.serializer())?.let { Tile.Meals(it) }
        "birthdays" -> d.list("birthdays", DashBirthday.serializer())?.let {
            Tile.Birthdays(it.take(5), d.int("birthdayTotal") ?: it.size)
        }
        "family" -> d.list("users", Member.serializer())?.let { users ->
            val open = d.list("memberTodayTasks", MemberOpenTasks.serializer()).orEmpty().associate { it.userId to it.openCount }
            Tile.Family(users.map { it to (open[it.id] ?: 0) })
        }
        else -> null
    }

    private fun <T> JsonObject.list(key: String, item: KSerializer<T>): List<T>? =
        piece(key, ListSerializer(item))

    /** OVERVIEW-R12: a missing or malformed piece is `null`, never an exception. */
    private fun <T> JsonObject.piece(key: String, strategy: DeserializationStrategy<T>): T? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { ApiJson.decodeFromJsonElement(strategy, element) }.getOrNull()
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
