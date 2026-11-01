package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.model.Permissions
import io.github.marcomow.yuvomi.core.api.model.Preferences
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.overview.GlanceRow
import io.github.marcomow.yuvomi.feature.overview.OverviewBuilder
import io.github.marcomow.yuvomi.feature.overview.Tile
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class OverviewBuilderTest {

    private val la = ZoneId.of("America/Los_Angeles")
    // 2026-11-01T07:00Z is 2026-11-01 00:00 in Los Angeles (PDT ends later that day).
    private val time = HouseholdTime(la) { Instant.parse("2026-11-01T17:00:00Z") } // 09:00 / 10:00 local

    private val dashboard = ApiJson.parseToJsonElement(
        """
        {
          "urgentTasks": [
            {"id": 1, "title": "Bins", "due_date": "2026-10-30", "status": "open"},
            {"id": 2, "title": "Call", "due_date": "2026-11-01", "status": "open"},
            {"id": 3, "title": "Later", "due_date": "2026-11-09", "status": "open"}
          ],
          "openTaskCount": 9, "overdueTaskCount": 1,
          "upcomingEvents": [
            {"id": 10, "title": "Dentist", "start_datetime": "2026-11-01T15:00", "end_datetime": "2026-11-01T16:00"},
            {"id": 11, "title": "Holiday", "start_datetime": "2026-11-01", "all_day": 1},
            {"id": 12, "title": "Ended", "start_datetime": "2026-11-01T06:00", "end_datetime": "2026-11-01T07:00"},
            {"id": 13, "title": "Tomorrow", "start_datetime": "2026-11-02T09:00"}
          ],
          "todayMeals": [{"meal_type": "dinner", "title": "Lasagne"}],
          "shoppingOpenCount": 12,
          "users": [{"id": 1, "display_name": "Alex Johnson"}],
          "memberTodayTasks": [{"user_id": 1, "open_count": 2}],
          "birthdays": [{"name": "Emma", "days_until": 3, "next_age": 9}],
          "birthdayTotal": 4
        }
        """,
    ) as JsonObject

    private fun build(prefs: Preferences = Preferences(), permissions: Permissions = Permissions(), dash: JsonObject = dashboard) =
        OverviewBuilder.build(dash, prefs, permissions, time, "Alex Johnson")

    @Test fun `greeting follows the household clock, not the device`() {
        assertEquals("dashboard.greetingMorning", build().greetingKey)
        assertEquals("Alex", build().firstName)
        val lateUtc = HouseholdTime(la) { Instant.parse("2026-11-02T07:00:00Z") } // 23:00 in LA
        assertEquals("dashboard.greetingEvening",
            OverviewBuilder.build(dashboard, Preferences(), Permissions(), lateUtc, "Alex").greetingKey)
    }

    @Test fun `today sheet has all-day first, coming events, due tasks, meals, shopping`() {
        val rows = build().glance!!
        val events = rows.filterIsInstance<GlanceRow.Event>().map { it.event.title }
        assertEquals(listOf("Holiday", "Dentist"), events)
        assertEquals(listOf(1, 2), rows.filterIsInstance<GlanceRow.TaskRow>().map { it.task.id })
        assertTrue(rows.filterIsInstance<GlanceRow.TaskRow>().first().overdue)
        assertEquals(1, rows.filterIsInstance<GlanceRow.Meal>().size)
        assertEquals(12, rows.filterIsInstance<GlanceRow.Shopping>().single().count)
    }

    @Test fun `the sheet can be switched off`() = assertNull(build(Preferences(dashboardTodayGlance = false)).glance)

    @Test fun `OVERVIEW-A11 a visible tasks tile takes tasks out of the sheet`() {
        val prefs = Preferences(dashboardWidgets = ApiJson.parseToJsonElement("""[{"id":"tasks","visible":true,"order":0}]"""))
        val model = build(prefs)
        assertTrue(model.glance!!.none { it is GlanceRow.TaskRow })
        assertTrue(model.tiles.first() is Tile.Tasks)
    }

    @Test fun `default tiles are birthdays, countdown, family, notes, metrics`() =
        assertEquals(listOf("birthdays", "countdown", "family", "notes", "metrics"), build().tiles.map { it.id })

    @Test fun `OVERVIEW-A7 a malformed piece degrades only its tile`() {
        val broken = JsonObject(dashboard + ("birthdays" to ApiJson.parseToJsonElement("\"oops\"")))
        val tiles = build(dash = broken).tiles
        assertTrue(tiles.first { it.id == "birthdays" } is Tile.Generic)
        assertTrue(tiles.first { it.id == "family" } is Tile.Family)
    }

    @Test fun `OVERVIEW-A5 a module without access hides its tile and its sheet rows`() {
        val prefs = Preferences(dashboardWidgets = ApiJson.parseToJsonElement("""[{"id":"budget","visible":true,"order":0}]"""))
        val model = build(prefs, Permissions(modules = mapOf("budget" to "none", "meals" to "none")))
        assertTrue(model.tiles.none { it.id == "budget" })
        assertTrue(model.glance!!.none { it is GlanceRow.Meal })
    }

    @Test fun `a module switched off for the household is treated like none`() {
        val model = build(Preferences(disabledModules = listOf("shopping")))
        assertTrue(model.glance!!.none { it is GlanceRow.Shopping })
    }
}
