package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.model.Permissions
import io.github.marcomow.yuvomi.core.api.model.Preferences
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.core.api.model.DashCountdown
import io.github.marcomow.yuvomi.feature.overview.GlanceRow
import io.github.marcomow.yuvomi.feature.overview.KeyDates
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

    // ── Key dates (OVERVIEW-R17, OVERVIEW-A12) ──

    private val keyDates = JsonObject(
        dashboard + mapOf(
            "countdowns" to ApiJson.parseToJsonElement(
                """[
                  {"source":"task","id":5,"title":"Renew licence","date":"2026-10-30","days_until":-2,"icon":null,"color":null,"recurring":false},
                  {"source":"document","id":8,"title":"Passport","date":"2026-11-01","days_until":0,"icon":"file-text","color":null,"recurring":false,"person_name":"Emma"},
                  {"source":"event","id":9,"title":"Contract ends","date":"2027-03-01","days_until":120,"icon":"calendar","color":"#2563EB","recurring":false}
                ]""",
            ),
            "countdownTotal" to ApiJson.parseToJsonElement("8"),
        ),
    )

    @Test fun `OVERVIEW-A12 key dates list events, tasks and documents with the person`() {
        val tile = build(dash = keyDates).tiles.first { it.id == "countdown" } as Tile.Countdown
        assertEquals(listOf("task", "document", "event"), tile.items.map { it.source })
        assertEquals("Emma", tile.items[1].personName)
        assertEquals(8, tile.total)
        assertEquals(5, tile.more)
    }

    @Test fun `OVERVIEW-A12 a row follows its module's switch and rights, and no row means no tile`() {
        val noDocs = build(Preferences(disabledModules = listOf("documents")), dash = keyDates).tiles.first { it.id == "countdown" } as Tile.Countdown
        assertEquals(listOf("task", "event"), noDocs.items.map { it.source })
        val onlyDocs = build(permissions = Permissions(modules = mapOf("tasks" to "none", "calendar" to "none")), dash = keyDates)
            .tiles.first { it.id == "countdown" } as Tile.Countdown
        assertEquals(listOf(8), onlyDocs.items.map { it.id })
        val none = build(Preferences(disabledModules = listOf("documents", "tasks", "calendar")), dash = keyDates)
        assertTrue(none.tiles.none { it.id == "countdown" })
        val empty = JsonObject(dashboard + ("countdowns" to ApiJson.parseToJsonElement("[]")))
        assertTrue(build(dash = empty).tiles.none { it.id == "countdown" })
        // Malformed: the generic tile (OVERVIEW-R12).
        val broken = JsonObject(dashboard + ("countdowns" to ApiJson.parseToJsonElement("\"oops\"")))
        assertTrue(build(dash = broken).tiles.first { it.id == "countdown" } is Tile.Generic)
    }

    @Test fun `key dates phrases, ranks and the meta line follow countdown js`() {
        assertEquals("dashboard.countdownOverdue" to 3, KeyDates.phrase(-3))
        assertEquals("common.today" to null, KeyDates.phrase(0))
        assertEquals("common.tomorrow" to null, KeyDates.phrase(1))
        assertEquals("dashboard.daysLeft" to 30, KeyDates.phrase(30))
        assertEquals("dashboard.countdownWeeks" to 4, KeyDates.phrase(31))
        assertEquals("dashboard.countdownWeeks" to 9, KeyDates.phrase(60))
        assertEquals("dashboard.countdownMonths" to 2, KeyDates.phrase(61))
        assertEquals("dashboard.countdownMonths" to 12, KeyDates.phrase(364))
        assertEquals("dashboard.countdownYears" to 1, KeyDates.phrase(365))
        assertEquals("dashboard.countdownYears" to 3, KeyDates.phrase(1100))
        assertEquals(KeyDates.Rank.OVERDUE, KeyDates.rank(-1))
        assertEquals(KeyDates.Rank.NOW, KeyDates.rank(1))
        assertEquals(KeyDates.Rank.SOON, KeyDates.rank(30))
        assertEquals(KeyDates.Rank.LATER, KeyDates.rank(31))
        assertEquals("documents", KeyDates.module("document"))
        assertEquals("tasks", KeyDates.module("task"))
        assertEquals("calendar", KeyDates.module("event"))
        val passport = DashCountdown(source = "document", id = 1, title = "Passport", personName = "Emma")
        // "Today" is not said twice; the person joins the date.
        assertEquals("Emma", KeyDates.meta(passport, "Today", "Today"))
        assertEquals("Fri · Emma", KeyDates.meta(passport, "Fri", "4 days"))
        assertEquals("Fri", KeyDates.meta(passport.copy(personName = null), "Fri", "4 days"))
        assertEquals(0, KeyDates.more(null, 3))
        assertEquals(2, KeyDates.more(7, 7))
    }
}
