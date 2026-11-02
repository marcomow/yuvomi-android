package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.schedule.ScheduleApi
import io.github.marcomow.yuvomi.feature.schedule.ScheduleBodies
import io.github.marcomow.yuvomi.feature.schedule.ScheduleErrors
import io.github.marcomow.yuvomi.feature.schedule.ScheduleRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * docs/spec/schedule.md against upstream's image at the pinned version, seeded by `seed-demo.js`
 * (early/late/night/on-call/vacation types, Linda's rotation, Alex's nights, two on-call extras
 * and Alex's vacation override). Each test makes its own rows with unique names, far in the
 * future so they meet no seeded day, and removes them again.
 */
class ScheduleContractTest : ContractTestBase() {
    private class Member(val client: YuvomiClient, val id: Int) {
        val api = ScheduleApi(client)
    }

    private fun member(username: String) = signedIn(username).let { (c, me) -> Member(c, me.user.id) }
    private fun key() = YuvomiClient.newIdempotencyKey()
    private val day = LocalDate.of(2031, 3, 3)

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    /** SCHED-A20: `load()` (shift types, fields, patterns with days, overrides, extras, entries, preferences, members). */
    @Test fun `SCHED-A20 the seeded schedule decodes`() = runBlocking {
        val alex = member("alex")
        val data = alex.api.load(LocalDate.now())
        assertTrue(data.types.any { it.shortCode == "N" && it.startTime == "22:00" })
        assertTrue(data.types.any { !it.timed })
        assertTrue(data.patterns.any { it.cycleLength == 14 && it.days.isNotEmpty() })
        assertTrue(data.overrides.isNotEmpty())
        assertTrue(data.extras.size >= 2)
        assertTrue(data.people.any { it.id == alex.id })
        assertTrue(data.users.any { it.id == alex.id && it.displayName.isNotEmpty() })
        val week = ScheduleRules.overviewFetchRange(LocalDate.now(), ScheduleRules.weekStart(data.household.weekStart))
        val entries = alex.api.entries(week.entriesFrom, week.to)
        assertTrue(entries.entries.any { it.source == "extra" && it.shiftType != null })
        assertTrue(entries.entries.any { it.source == "pattern" })
        assertTrue(alex.api.entries(week.from, week.to, alex.id).entries.all { it.userId == alex.id })
        alex.api.holidays(week.from, week.to)
        assertTrue(alex.api.preferences().overtimeEnabled)
    }

    @Test fun `SCHED-A21 entries refuse a range over two years`() = expectHttp(400) {
        member("alex").api.entries(day, day.plusDays(800))
    }

    /** SCHED-A22: shift types: create, update, attach fields, delete; in use answers 409. */
    @Test fun `SCHED-A22 shift types and custom fields`() = runBlocking {
        val alex = member("alex")
        val type = alex.api.createShiftType(ScheduleBodies.shiftType(unique("Shift"), "ZZ", "#3B82F6", "moon", "21:00", "05:00"), key())
        val field = alex.api.createCustomField(unique("Room"), key())
        try {
            assertEquals("21:00", type.startTime)
            val renamed = alex.api.updateShiftType(type.id, ScheduleBodies.shiftType(type.name + "x", "ZY", "#16A34A", null, null, null))
            assertNull(renamed.startTime)
            assertNull(renamed.icon)
            val withFields = alex.api.setShiftTypeFields(type.id, listOf(ScheduleBodies.AttachedField(field.id, true)))
            assertEquals(listOf(field.id), withFields.fields.map { it.id })
            assertTrue(withFields.fields.first().showInOverlay)
            assertEquals(field.name + "2", alex.api.renameCustomField(field.id, field.name + "2").name)
            // Emma did not make it: she may not change it.
            expectHttp(403) { member("emma").api.updateShiftType(type.id, ScheduleBodies.shiftType("x", "", "#16A34A", null, null, null)) }
            val extra = alex.api.createExtra(ScheduleBodies.extra(alex.id, type.id, "", null, emptyMap(), dateKey = day.toString()), key())
            expectHttp(409, "schedule_shift_type_in_use") { alex.api.deleteShiftType(type.id) }
            alex.api.deleteExtra(extra.id)
        } finally {
            runCatching { alex.api.deleteCustomField(field.id) }
            runCatching { alex.api.deleteShiftType(type.id) }
        }
        expectHttp(404, ScheduleErrors.SHIFT_TYPE_GONE) { alex.api.deleteShiftType(type.id) }
        expectHttp(404, ScheduleErrors.CUSTOM_FIELD_GONE) { alex.api.deleteCustomField(field.id) }
    }

    /** SCHED-A23: a plan with its days, a shorter cycle in the save order, delete. */
    @Test fun `SCHED-A23 patterns and their days`() = runBlocking {
        val alex = member("alex")
        val type = alex.api.shiftTypes().first { it.timed }
        val p = alex.api.createPattern(ScheduleBodies.pattern(alex.id, unique("Plan"), day.toString(), 7, false, day.toString(), day.plusDays(30).toString()), key())
        try {
            assertEquals(7, p.cycleLength)
            val days = alex.api.replacePatternDays(p.id, listOf(ScheduleBodies.DayRow(0, type.id), ScheduleBodies.DayRow(0, null), ScheduleBodies.DayRow(6, type.id)))
            assertEquals(3, days.size)
            assertEquals(3, alex.api.patternDays(p.id).size)
            expectHttp(400, "schedule_cycle_length_conflict") {
                alex.api.updatePattern(p.id, ScheduleBodies.pattern(null, p.name, day.toString(), 5, false, null, null))
            }
            for (step in ScheduleRules.patternSaveOrder(7, 5)) {
                if (step == ScheduleRules.SaveStep.DAYS) alex.api.replacePatternDays(p.id, listOf(ScheduleBodies.DayRow(0, type.id)))
                else assertEquals(5, alex.api.updatePattern(p.id, ScheduleBodies.pattern(null, p.name, day.toString(), 5, false, null, null)).cycleLength)
            }
            expectHttp(403) { member("emma").api.deletePattern(p.id) }
        } finally {
            runCatching { alex.api.deletePattern(p.id) }
        }
        expectHttp(404, ScheduleErrors.PATTERN_GONE) { alex.api.deletePattern(p.id) }
    }

    /** SCHED-A24: an override day, a filled range, a range delete, a reversed range. */
    @Test fun `SCHED-A24 overrides`() = runBlocking {
        val alex = member("alex")
        val from = day.toString()
        val to = day.plusDays(3).toString()
        try {
            val one = alex.api.putOverride(from, ScheduleBodies.override(alex.id, null, "Free #rest", emptyMap()))
            assertNull(one.shiftTypeId)
            assertEquals(4, alex.api.fillOverrides(ScheduleBodies.override(alex.id, null, "", emptyMap(), from, to), key()))
            assertEquals(4, alex.api.overrides(alex.id, from, to).size)
            assertEquals(2, alex.api.deleteOverrides(alex.id, day.plusDays(2).toString(), to))
            alex.api.deleteOverride(from, alex.id)
            expectHttp(404, ScheduleErrors.OVERRIDE_GONE) { alex.api.deleteOverride(from, alex.id) }
            expectHttp(400, "range_reversed") { alex.api.fillOverrides(ScheduleBodies.override(alex.id, null, "", emptyMap(), to, from), key()) }
            expectHttp(400, "schedule_selected_shift_type_not_found") { alex.api.putOverride(from, ScheduleBodies.override(alex.id, 999_999, "", emptyMap())) }
        } finally {
            runCatching { alex.api.deleteOverrides(alex.id, from, to) }
        }
    }

    /** SCHED-A25: an extra, a filled range, an edit, deletes. */
    @Test fun `SCHED-A25 extras`() = runBlocking {
        val alex = member("alex")
        val type = alex.api.shiftTypes().first()
        val from = day.toString()
        val to = day.plusDays(2).toString()
        try {
            val extra = alex.api.createExtra(ScheduleBodies.extra(alex.id, type.id, "On call", 30, emptyMap(), dateKey = from), key())
            assertEquals(30, extra.reminderOffsetMinutes)
            assertEquals(3, alex.api.fillExtras(ScheduleBodies.extra(alex.id, type.id, "", null, emptyMap(), from = from, to = to), key()))
            val edited = alex.api.updateExtra(extra.id, ScheduleBodies.extra(alex.id, type.id, "Changed", null, emptyMap(), dateKey = to))
            assertEquals("Changed", edited.note)
            assertEquals(to, edited.dateKey)
            expectHttp(403) { member("emma").api.deleteExtra(extra.id) }
        } finally {
            alex.api.extras(alex.id, from, to).forEach { runCatching { alex.api.deleteExtra(it.id) } }
        }
        expectHttp(404, ScheduleErrors.EXTRA_GONE) { alex.api.deleteExtra(999_999) }
    }

    /** SCHED-A26: the member's own feed: on, a new link, off. */
    @Test fun `SCHED-A26 the schedule feed`() = runBlocking {
        val emma = member("emma")
        val first = emma.api.regenerateFeed(key())
        assertNotNull(first)
        assertTrue(first!!.url.endsWith(".ics"))
        val second = emma.api.regenerateFeed(key())
        assertTrue(second!!.url != first.url)
        assertEquals(second.url, emma.api.feed()?.url)
        emma.api.disableFeed()
        assertNull(emma.api.feed())
    }
}
