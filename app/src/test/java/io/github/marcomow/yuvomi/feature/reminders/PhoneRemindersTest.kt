package io.github.marcomow.yuvomi.feature.reminders

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shell.AppLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** REM-R2 to R4: what the phone schedules, says and opens. */
class PhoneRemindersTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }

    private fun row(id: Int, type: String, at: String, title: String? = "Thing", extra: String = "") = ApiJson.decodeFromString(
        UpcomingReminder.serializer(),
        """{"id":$id,"entity_type":"$type","entity_id":${id * 10},"entity_title":${title?.let { "\"$it\"" } ?: "null"},"fires_at":"$at","remind_at":"x","created_by":1$extra}""",
    )

    @Test fun `only future rows are scheduled, each once, earliest first`() {
        val now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        val rows = listOf(
            row(1, "task", "2026-11-03T08:00:00Z"), row(2, "event", "2026-11-02T13:00:00Z"),
            row(3, "task", "2026-11-02T11:00:00Z"), row(1, "task", "2026-11-03T08:00:00Z"), row(4, "task", "not a date"),
        )
        assertEquals(listOf(2, 1), PhoneReminders.plan(rows, now, strings).map { it.id })
    }

    @Test fun `the title is the origin's label, the body its title or sentence`() {
        val task = row(1, "task", "2026-11-03T08:00:00Z", "Buy milk")
        assertEquals("Tasks", PhoneReminders.title(task, strings))
        assertEquals("Buy milk", PhoneReminders.body(task, strings))
        assertEquals("Reminder", PhoneReminders.title(row(2, "something_new", "2026-11-03T08:00:00Z"), strings))
        assertEquals(strings.t("health.fasting.goalReached"), PhoneReminders.body(row(3, "fasting_goal", "2026-11-03T08:00:00Z"), strings))
        assertEquals("${strings.t("health.cycle.status.nextPeriod")} - 2026-11-20", PhoneReminders.body(row(4, "cycle_period", "2026-11-03T08:00:00Z", "2026-11-20"), strings))
        val inherited = row(5, "health_prevention_due", "2026-11-03T08:00:00Z", "Flu shot", ""","assigned_from":2,"prevention_subject_name":"Mia"""")
        assertEquals("Flu shot - Mia", PhoneReminders.body(inherited, strings))
    }

    @Test fun `a tap opens the item, or its module, and every route is one the app follows`() {
        assertEquals("task/10", PhoneReminders.route(row(1, "task", "2026-11-03T08:00:00Z")))
        assertEquals("calendar?open=20", PhoneReminders.route(row(2, "event", "2026-11-03T08:00:00Z")))
        assertEquals("documents?open=30", PhoneReminders.route(row(3, "document_expiry", "2026-11-03T08:00:00Z")))
        assertNull(PhoneReminders.route(row(4, "something_new", "2026-11-03T08:00:00Z")))
        val types = listOf(
            "task", "event", "subscription", "inventory_item", "inventory_tracked_date", "pantry_item", "cycle_period",
            "cycle_log_nudge", "health_prevention_due", "schedule_entry", "schedule_extra_entry", "waste_pickup",
            "document_expiry", "fasting_goal", "fasting_next_start", "shopping_move_returned",
        )
        for (t in types) {
            val route = PhoneReminders.route(row(7, t, "2026-11-03T08:00:00Z"))!!
            assertTrue("$t -> $route", AppLinks.allowed(route) == route)
        }
    }

    @Test fun `routes from outside the app are followed only when allowed`() {
        assertEquals("task/5", AppLinks.allowed("task/5"))
        assertEquals("shopping", AppLinks.allowed("shopping"))
        assertNull(AppLinks.allowed("settings/admin-backup?restore=1"))
        assertNull(AppLinks.allowed("task/5/../x"))
    }
}
