package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.calendar.CalendarApi
import io.github.marcomow.yuvomi.feature.calendar.CalendarDates
import io.github.marcomow.yuvomi.feature.calendar.CalendarEvent
import io.github.marcomow.yuvomi.feature.calendar.EventForm
import io.github.marcomow.yuvomi.feature.calendar.RecurrenceScope
import io.github.marcomow.yuvomi.feature.tasks.RRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * docs/spec/calendar.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Every test makes its own events and calendars with unique names, far in the future, and
 * removes them again.
 */
class CalendarContractTest : ContractTestBase() {
    private class Member(val client: YuvomiClient, val id: Int) {
        val api = CalendarApi(client)
    }

    private fun member(username: String) = signedIn(username).let { (c, me) -> Member(c, me.user.id) }

    /** A day far ahead, different per test, so fixtures never meet the seeded events. */
    private val day = LocalDate.of(2031, 3, 3).plusDays((System.nanoTime() % 300))
    private val dates = CalendarDates(ZoneOffset.UTC)

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    private fun form(title: String, rule: RRule.Parts = RRule.Parts(), time: LocalTime = LocalTime.of(10, 0)) =
        EventForm(title = title, startDate = day, startTime = time, endDate = day, endTime = time.plusHours(1), rule = rule)

    private suspend fun Member.create(f: EventForm): CalendarEvent = api.create(f.body(null, calendarsOffered = false), YuvomiClient.newIdempotencyKey())

    private suspend fun Member.window(): List<CalendarEvent> =
        CalendarApi.decodeEvents(api.events(day.minusDays(1).toString(), day.plusDays(10).toString()))

    private suspend fun Member.cleanup(vararg ids: Int) = ids.forEach { runCatching { api.delete(it) } }

    // ── Reads ──

    /** CAL-A20: the window, holidays, calendars, members, preferences and the layers decode. */
    @Test fun `CAL-A20 the reads decode`() = runBlocking {
        val alex = member("alex")
        val today = LocalDate.now()
        val events = CalendarApi.decodeEvents(alex.api.events(today.minusDays(30).toString(), today.plusDays(60).toString()))
        assertTrue(events.all { it.startDatetime.isNotEmpty() })
        CalendarApi.decodeHolidays(alex.api.holidays(today.toString(), today.plusDays(30).toString()))
        val calendars = CalendarApi.decodeCalendars(alex.api.calendars())
        assertTrue(calendars.any { it.isDefault })
        assertTrue(CalendarApi.decodeMembers(alex.api.members()).size >= 2)
        val prefs = alex.api.preferences()
        assertTrue((prefs.defaultDuration ?: 60) > 0)
        CalendarApi.decodeTasks(alex.api.tasks())
        CalendarApi.decodeSchedule(alex.api.schedule(today.toString(), today.plusDays(6).toString()))
        CalendarApi.decodeWaste(alex.api.waste(today.toString(), today.plusDays(6).toString()))
        alex.api.syncTargets()
        Unit
    }

    /** CAL-A21: search finds an event by a word of its title; short queries answer nothing. */
    @Test fun `CAL-A21 search finds by title`() = runBlocking {
        val alex = member("alex")
        val word = "Zq" + unique("x").takeLast(8).filter { it.isLetterOrDigit() }
        val ev = alex.create(form("Appointment $word"))
        try {
            val (hits, total) = alex.api.search(word)
            assertTrue(hits.any { it.id == ev.id })
            assertTrue(total >= 1)
            assertEquals(0, alex.api.search("").first.size)
        } finally {
            alex.cleanup(ev.id)
        }
    }

    // ── Event writes ──

    /** CAL-A22: create answers the whole event; edit keeps what it does not send; delete is 204, then gone. */
    @Test fun `CAL-A22 create, read, edit and delete an event`() = runBlocking {
        val alex = member("alex")
        val title = unique("Event")
        val created = alex.create(form(title).copy(location = "Main St 1", assignees = listOf(alex.id), color = "#587DCE", countdown = true))
        try {
            assertEquals(title, created.title)
            assertEquals("${day}T10:00", created.startDatetime.take(16))
            assertEquals("#587DCE", created.color)
            assertTrue(created.countdown)
            assertEquals(listOf(alex.id), created.assignedUsers.map { it.id })
            assertEquals(created.id, alex.api.event(created.id).id)
            val updated = alex.api.update(created.id, buildJsonObject { put("title", "$title!") })
            assertEquals("$title!", updated.title)
            assertEquals("Main St 1", updated.location)
            assertEquals(created.startDatetime, updated.startDatetime)
            alex.api.delete(created.id)
            expectHttp(404, "calendar_event_not_found") { alex.api.event(created.id) }
            expectHttp(404, "calendar_event_not_found") { alex.api.delete(created.id) }
        } finally {
            alex.cleanup(created.id)
        }
    }

    /** CAL-A23: the server refuses a series that never occurs (#960, #1607), with the reason the app words. */
    @Test fun `CAL-A23 the server refuses an empty series`() {
        val alex = member("alex")
        expectHttp(400, "calendar_series_no_occurrence") {
            alex.create(form(unique("Bad"), RRule.Parts(freq = "DAILY", until = day.minusDays(2).toString())))
        }
    }

    /** CAL-A24: a retried create with the same key is one event (ADR 0007). */
    @Test fun `CAL-A24 idempotent create`() = runBlocking {
        val alex = member("alex")
        val key = YuvomiClient.newIdempotencyKey()
        val body = form(unique("Once")).body(null, false)
        val a = alex.api.create(body, key)
        val b = alex.api.create(body, key)
        try {
            assertEquals(a.id, b.id)
        } finally {
            alex.cleanup(a.id)
        }
    }

    /** CAL-A25: someone else's event is refused to a child without `calendar_edit_others`. */
    @Test fun `CAL-A25 a child cannot change the parents' event`() = runBlocking {
        val alex = member("alex")
        val emma = member("emma")
        val ev = alex.create(form(unique("Parents")))
        try {
            expectHttp(403, "not_owner") { emma.api.update(ev.id, buildJsonObject { put("title", "x") }) }
            expectHttp(403, "not_owner") { emma.api.delete(ev.id) }
        } finally {
            alex.cleanup(ev.id)
        }
    }

    /** CAL-A26: reset is for subscribed events only. */
    @Test fun `CAL-A26 reset refuses a local event`() = runBlocking {
        val alex = member("alex")
        val ev = alex.create(form(unique("Local")))
        try {
            expectHttp(400) { alex.api.reset(ev.id) }
            expectHttp(404, "calendar_event_not_found") { alex.api.reset(999_999) }
        } finally {
            alex.cleanup(ev.id)
        }
    }

    // ── Series (CAL-R19 to R21) ──

    private suspend fun Member.series(title: String): Pair<CalendarEvent, List<CalendarEvent>> {
        val master = create(form(title, RRule.Parts(freq = "DAILY", count = 5)))
        val occurrences = window().filter { it.id == master.id }.sortedBy { it.startDatetime }
        return master to occurrences
    }

    /** CAL-A27: a local series expands with its keys and the rights to change one occurrence. */
    @Test fun `CAL-A27 occurrences carry their series and key`() = runBlocking {
        val alex = member("alex")
        val (master, occ) = alex.series(unique("Daily"))
        try {
            assertEquals(5, occ.size)
            assertTrue(occ.all { it.seriesId == master.id && RecurrenceScope.recurrenceId(it) != null })
            assertTrue(occ.all { it.isLocalRecurringSeries && it.canOverrideOccurrence })
            assertFalse(RecurrenceScope.isExternalSeries(occ[0]))
        } finally {
            alex.cleanup(master.id)
        }
    }

    /** CAL-A28: "only this" edits one occurrence with its lead times; "only this" delete removes one. */
    @Test fun `CAL-A28 one occurrence is changed and deleted on its own`() = runBlocking {
        val alex = member("alex")
        val (master, occ) = alex.series(unique("Daily"))
        try {
            val second = occ[1]
            val edited = alex.api.updateOccurrence(master.id, RecurrenceScope.recurrenceId(second)!!, false, buildJsonObject {
                put("title", "Changed")
                put("reminder_offsets", JsonArray(listOf(JsonPrimitive(15))))
            })
            assertEquals("Changed", edited.title)
            alex.api.deleteOccurrence(master.id, RecurrenceScope.recurrenceId(occ[2])!!, following = false)
            val after = alex.window().filter { (it.seriesId ?: it.id) == master.id }
            assertEquals(4, after.size)
            assertTrue(after.any { it.title == "Changed" })
            expectHttp(400, "malformed_recurrence_id") { alex.api.deleteOccurrence(master.id, "not-a-key", following = false) }
        } finally {
            alex.cleanup(master.id)
        }
    }

    /** CAL-A29: "this and following" splits the series; deleting from there truncates it. */
    @Test fun `CAL-A29 this and following`() = runBlocking {
        val alex = member("alex")
        val (master, occ) = alex.series(unique("Daily"))
        var successor: Int? = null
        try {
            val split = alex.api.updateOccurrence(master.id, RecurrenceScope.recurrenceId(occ[2])!!, true, buildJsonObject { put("title", "Later") })
            successor = split.id
            assertEquals("Later", split.title)
            alex.api.deleteOccurrence(split.id, RecurrenceScope.recurrenceId(alex.window().first { it.id == split.id })!!, following = true)
            assertEquals(2, alex.window().count { it.id == master.id })
        } finally {
            alex.cleanup(master.id, successor ?: 0)
        }
    }

    /** CAL-A30: the legacy exception path and a truncated rule (`requestCalendarOccurrenceDelete`). */
    @Test fun `CAL-A30 an exception removes one day`() = runBlocking {
        val alex = member("alex")
        val (master, occ) = alex.series(unique("Daily"))
        try {
            alex.api.exception(master.id, RecurrenceScope.exceptionBody(RecurrenceScope.recurrenceId(occ[1])!!))
            assertEquals(4, alex.window().count { it.id == master.id })
            val rule = RecurrenceScope.truncateRuleBefore(master.recurrenceRule, occ[3].startDatetime.take(10))!!
            alex.api.update(master.id, buildJsonObject { put("recurrence_rule", rule) })
            assertEquals(2, alex.window().count { it.id == master.id })
            val single = alex.create(form(unique("Single")))
            try {
                expectHttp(400) { alex.api.exception(single.id, mapOf("date" to day.toString())) }
            } finally {
                alex.cleanup(single.id)
            }
        } finally {
            alex.cleanup(master.id)
        }
    }

    // ── Reminders ──

    /** CAL-A31: the form's reminders are stored as naive UTC moments and read back; clear removes them. */
    @Test fun `CAL-A31 reminders of an event`() = runBlocking {
        val alex = member("alex")
        val ev = alex.create(form(unique("Remind")))
        try {
            alex.api.setReminders(ev.id, listOf("${day.minusDays(1)}T10:00:00", "${day}T09:45:00"))
            val stored = alex.api.reminders(ev.id)
            assertEquals(2, stored.size)
            assertTrue(stored.all { it.remindAt.startsWith(day.minusDays(1).toString()) || it.remindAt.startsWith(day.toString()) })
            alex.api.clearReminders(ev.id)
            assertTrue(alex.api.reminders(ev.id).isEmpty())
        } finally {
            alex.cleanup(ev.id)
        }
    }

    // ── Local calendars (CAL-R23 to R26) ──

    /** CAL-A32: create, rename and recolour, reorder, export on and off, delete; the default stays. */
    @Test fun `CAL-A32 local calendars`() = runBlocking {
        val alex = member("alex")
        val name = unique("Cal")
        val created = alex.api.createCalendar(name, "#16A34A", YuvomiClient.newIdempotencyKey())
        try {
            assertEquals(name, created.name)
            val renamed = alex.api.updateCalendar(created.id, JsonObject(mapOf("name" to JsonPrimitive("$name 2"), "color" to JsonPrimitive("#EF4444"))))
            assertEquals("$name 2", renamed.name)
            assertEquals("#EF4444", renamed.color.uppercase())
            val order = CalendarApi.decodeCalendars(alex.api.calendars()).map { it.id }
            alex.api.reorderCalendars(order.reversed())
            assertEquals(order.reversed(), CalendarApi.decodeCalendars(alex.api.calendars()).map { it.id })
            alex.api.reorderCalendars(order)
            expectHttp(400, "calendar_order_changed") { alex.api.reorderCalendars(order.drop(1)) }
            alex.api.enableFeed(created.id)
            assertNotNull(CalendarApi.decodeCalendars(alex.api.calendars()).first { it.id == created.id }.feedUrl)
            alex.api.disableFeed(created.id)
            // An event in the calendar moves to the default one when it goes.
            val ev = alex.api.create(form(unique("In cal")).copy(localCalendarId = created.id).body(null, true), YuvomiClient.newIdempotencyKey())
            assertEquals(created.id, ev.localCalendarId)
            alex.api.deleteCalendar(created.id)
            expectHttp(404, "calendar_calendar_not_found") { alex.api.deleteCalendar(created.id) }
            val default = CalendarApi.decodeCalendars(alex.api.calendars()).first { it.isDefault }
            assertEquals(default.id, alex.api.event(ev.id).localCalendarId)
            expectHttp(400) { alex.api.deleteCalendar(default.id) }
            alex.cleanup(ev.id)
        } finally {
            runCatching { alex.api.deleteCalendar(created.id) }
        }
    }

    /** CAL-A33: a child without admin rights may not export a calendar. */
    @Test fun `CAL-A33 the export is admin-only`() = runBlocking {
        val emma = member("emma")
        val default = CalendarApi.decodeCalendars(emma.api.calendars()).first { it.isDefault }
        expectHttp(403) { emma.api.enableFeed(default.id) }
    }

    // ── Attachments ──

    /** CAL-A34: an image attachment becomes a document whose preview the session can read. */
    @Test fun `CAL-A34 an attachment preview`() = runBlocking {
        val alex = member("alex")
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        val body = JsonObject(form(unique("Pic")).body(null, false) + mapOf(
            "attachment_name" to JsonPrimitive("pixel.png"),
            "attachment_mime" to JsonPrimitive("image/png"),
            "attachment_data" to JsonPrimitive(png),
        ))
        val ev = alex.api.create(body, YuvomiClient.newIdempotencyKey())
        try {
            assertNotNull(ev.attachmentDocumentId)
            val documentId = ev.attachmentDocumentId!!
            assertTrue(alex.api.preview(documentId).isNotEmpty())
            val removed = alex.api.update(ev.id, buildJsonObject { put("remove_attachment", true) })
            assertEquals(null, removed.attachmentDocumentId)
        } finally {
            alex.cleanup(ev.id)
        }
    }
}
