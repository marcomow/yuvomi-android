package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.waste.WasteApi
import io.github.marcomow.yuvomi.feature.waste.WasteRules
import io.github.marcomow.yuvomi.feature.waste.WasteType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * docs/spec/waste.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). The seed
 * switches every module on but brings no waste data, so each test creates its own types,
 * schedules, pickups and sources and removes them again (sources before their types).
 */
class WasteContractTest : ContractTestBase() {

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun alex(): WasteApi = WasteApi(signedIn("alex").first)

    private fun key() = YuvomiClient.newIdempotencyKey()

    private suspend fun expectHttp(status: Int, block: suspend () -> Unit): ApiProblem.Http {
        try {
            block()
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            return e
        }
        fail("expected HTTP $status")
        error("unreachable")
    }

    private suspend fun newType(api: WasteApi, name: String = unique("Bin")): WasteType = api.createType(name, "recycle", "#3B82F6", key())

    private suspend fun load(api: WasteApi) = api.load(today.toString(), today.plusDays(WasteRules.UPCOMING_WINDOW_DAYS).toString())

    /** WASTE-A16 (types: list with archived, create, update, archive, order, delete and its refusal). */
    @Test fun `WASTE-A16 a type is created, edited, archived and deleted`() = runBlocking<Unit> {
        val api = alex()
        val type = newType(api)
        try {
            assertEquals("recycle", type.icon)
            val edited = api.updateType(type.id, WasteApi.typeBody("${type.name}!", "leaf", "#16A34A"))
            assertEquals("leaf", edited.icon)
            assertTrue(api.updateType(type.id, buildJsonObject { put("archived", true) }).archived)
            assertTrue(load(api).types.single { it.id == type.id }.archived)
            assertEquals(42, api.updateType(type.id, buildJsonObject { put("sort_order", 42) }).sortOrder)
            assertFalse(api.updateType(type.id, buildJsonObject { put("archived", false) }).archived)
            val pickup = api.createPickup(WasteRules.pickupBody(type.id, today.plusDays(5), ""), key())
            assertEquals("waste_type_in_use", expectHttp(409) { api.deleteType(type.id) }.reason)
            api.deletePickup(pickup.id)
        } finally {
            api.deleteType(type.id)
        }
        assertEquals("waste_type_not_found", expectHttp(404) { api.deleteType(type.id) }.reason)
    }

    /** WASTE-A17 (schedules of the three kinds, occurrences, move, skip and restore, the reasons). */
    @Test fun `WASTE-A17 schedules expand, move, skip and restore`() = runBlocking<Unit> {
        val api = alex()
        val type = newType(api)
        val created = mutableListOf<Int>()
        try {
            val weekly = WasteRules.ScheduleForm(typeId = type.id, weekdays = setOf(WasteRules.weekdayCodeOf(today)), anchor = today)
            val schedule = api.createSchedule(WasteRules.scheduleBody(weekly)!!, key()).also { created += it.id }
            assertEquals("weekly", schedule.recurrenceKind)
            val ordinalAnchor = WasteRules.nearestOrdinalAnchor(-1, "FR", today)
            val ordinal = WasteRules.ScheduleForm(typeId = type.id, kind = "monthly_ordinal_weekday", ordinal = -1, ordinalWeekday = "FR", anchor = ordinalAnchor)
            val last = api.createSchedule(WasteRules.scheduleBody(ordinal)!!, key()).also { created += it.id }
            assertEquals(-1, last.monthDay)
            assertEquals("FR", last.weekdays)
            val wrongAnchor = expectHttp(400) {
                api.createSchedule(WasteRules.scheduleBody(ordinal.copy(anchor = ordinalAnchor.plusDays(1)))!!, key())
            }
            assertEquals("waste_schedule_anchor_not_ordinal_weekday", wrongAnchor.reason)
            val noDays = expectHttp(400) {
                api.createSchedule(buildJsonObject {
                    put("type_id", type.id); put("recurrence_kind", "weekly"); put("anchor_date", today.toString()); put("interval", 1)
                    put("weekdays", kotlinx.serialization.json.JsonArray(emptyList()))
                }, key())
            }
            assertEquals("waste_schedule_weekdays_required", noDays.reason)
            val gone = expectHttp(400) { api.createSchedule(WasteRules.scheduleBody(weekly.copy(typeId = 999_999))!!, key()) }
            assertEquals("waste_selected_type_not_found", gone.reason)

            val occurrences = api.occurrences(today.toString(), today.plusDays(30).toString()).filter { it.typeId == type.id }
            val first = occurrences.first { o -> o.origins.any { it.scheduleId == schedule.id } }
            assertEquals(today.toString(), first.dateKey)
            assertEquals("${type.id}:$today", first.key)
            val origin = WasteRules.scheduleOrigin(first)!!
            api.setOverride(origin.scheduleId, origin.originalDate, today.plusDays(1).toString())
            val moved = api.occurrences(today.toString(), today.plusDays(30).toString()).first { it.typeId == type.id && it.dateKey == today.plusDays(1).toString() }
            assertTrue(moved.moved)
            assertEquals(today.toString(), WasteRules.movedFrom(moved))
            assertEquals("waste_override_same_date", expectHttp(400) { api.setOverride(schedule.id, today.toString(), today.toString()) }.reason)
            assertEquals("waste_occurrence_not_found", expectHttp(400) { api.setOverride(schedule.id, today.plusDays(1).toString(), null) }.reason)
            api.setOverride(schedule.id, today.toString(), null)
            assertTrue(api.occurrences(today.toString(), today.toString()).none { it.typeId == type.id })
            api.deleteOverride(schedule.id, today.toString())
            assertEquals("waste_override_not_found", expectHttp(404) { api.deleteOverride(schedule.id, today.toString()) }.reason)
            val paused = api.updateSchedule(schedule.id, WasteRules.scheduleBody(weekly.copy(active = false, interval = 2))!!)
            assertFalse(paused.active)
            assertEquals(2, paused.interval)
            assertTrue(load(api).schedules.any { it.id == schedule.id && !it.active })
        } finally {
            created.forEach { runCatching { api.deleteSchedule(it) } }
            api.deleteType(type.id)
        }
        assertEquals("waste_schedule_not_found", expectHttp(404) { api.deleteSchedule(created.first()) }.reason)
    }

    /** WASTE-A18 (one-off pickups: create, read, list, edit, duplicate, delete). */
    @Test fun `WASTE-A18 a one-off pickup round trip`() = runBlocking<Unit> {
        val api = alex()
        val type = newType(api)
        try {
            val day = today.plusDays(12)
            val pickup = api.createPickup(WasteRules.pickupBody(type.id, day, "Big box #moving"), key())
            assertEquals(day.toString(), pickup.date)
            assertEquals(pickup, api.pickup(pickup.id))
            val dup = expectHttp(409) { api.createPickup(WasteRules.pickupBody(type.id, day, ""), key()) }
            assertEquals("waste_pickup_exists", dup.reason)
            val edited = api.updatePickup(pickup.id, WasteRules.pickupBody(type.id, day.plusDays(1), ""))
            assertNull(edited.note)
            val upcoming = api.occurrences(today.toString(), today.plusDays(30).toString()).single { it.typeId == type.id }
            assertEquals(pickup.id, WasteRules.oneOffOrigin(upcoming)?.oneOffId)
            api.deletePickup(pickup.id)
            assertEquals("waste_pickup_not_found", expectHttp(404) { api.pickup(pickup.id) }.reason)
            assertEquals("waste_selected_type_not_found", expectHttp(400) { api.createPickup(WasteRules.pickupBody(999_999, day, ""), key()) }.reason)
        } finally {
            api.deleteType(type.id)
        }
    }

    private fun ics(vararg events: Pair<String, LocalDate>) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//yuvomi-android//contract//EN\r\n")
        events.forEachIndexed { i, (label, day) ->
            append("BEGIN:VEVENT\r\nUID:contract-$i-${day}@yuvomi\r\nDTSTART;VALUE=DATE:${day.toString().replace("-", "")}\r\nSUMMARY:$label\r\nEND:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    /** WASTE-A19 (file import: preview, commit with a new type; the source: read, rename, mappings, profile, re-import, delete). */
    @Test fun `WASTE-A19 an ICS file imports, maps and re-imports`() = runBlocking<Unit> {
        val api = alex()
        val label = unique("Gelb").replace(" ", "")
        val file = ics(label to today.plusDays(10), label to today.plusDays(24))
        val name = unique("Source")
        var sourceId: Int? = null
        val typeIds = mutableSetOf<Int>()
        try {
            val preview = api.preview(null, file, key())
            assertEquals(2, preview.counts.candidates)
            val l = preview.labels.single()
            val decisions = WasteRules.buildDecisions(listOf(l.normalizedLabel to WasteRules.defaultChoice(l))) as WasteRules.Decisions.Ok
            val result = api.commit(null, WasteRules.commitBody(decisions.mappings, emptySet(), preview, file, reimport = false, name = name), key())
            assertEquals(2, result.diff?.added)
            val source = load(api).sources.single { it.name == name }
            sourceId = source.id
            assertFalse(source.isUrl)
            val detail = api.source(source.id)
            val mapping = detail.mappings.single()
            typeIds += mapping.typeIds
            assertEquals(1, mapping.typeIds.size)
            assertTrue(load(api).types.any { it.id in mapping.typeIds && it.name == l.originalLabel })
            api.renameSource(source.id, "$name!")
            val extra = newType(api).also { typeIds += it.id }
            api.updateMapping(source.id, mapping.id, (mapping.typeIds + extra.id).sorted())
            assertEquals((mapping.typeIds + extra.id).toSet(), api.source(source.id).mappings.single().typeIds.toSet())
            val profile = api.exportProfile(source.id)
            val reviewed = api.previewProfile(source.id, profile, key())
            assertTrue(reviewed.entries.all { it.status == "unchanged" })
            assertEquals(0, reviewed.applicableCount)
            val again = api.preview(source.id, file, key())
            assertEquals(detail.version, again.expectedVersion)
            assertEquals((mapping.typeIds + extra.id).toSet(), again.labels.single().rememberedTypeIds.toSet())
            val redo = WasteRules.buildDecisions(again.labels.map { it.normalizedLabel to WasteRules.defaultChoice(it) }) as WasteRules.Decisions.Ok
            api.commit(source.id, WasteRules.commitBody(redo.mappings, emptySet(), again, file, reimport = true, name = null), key())
            assertEquals("waste_source_not_url", expectHttp(400) { api.refreshSource(source.id, key()) }.reason)
            assertEquals("waste_import_empty_file", expectHttp(400) { api.preview(null, " ", key()) }.reason)
        } finally {
            sourceId?.let { runCatching { api.deleteSource(it) } }
            typeIds.forEach { runCatching { api.deleteType(it) } }
        }
        sourceId?.let { id -> assertEquals("waste_source_not_found", expectHttp(404) { api.source(id) }.reason) }
    }

    /** WASTE-A20 (the member's reminders: read, set, refuse an offset, switch off). */
    @Test fun `WASTE-A20 reminder settings per type`() = runBlocking<Unit> {
        val api = alex()
        val type = newType(api)
        try {
            val setting = api.reminderSettings().single { it.typeId == type.id }
            assertFalse(setting.enabled)
            assertEquals(1, setting.offsetDays)
            assertEquals("08:00", setting.deliveryTime)
            api.updateReminder(setting.copy(enabled = true, offsetDays = 2, deliveryTime = "07:30"))
            val stored = api.reminderSettings().single { it.typeId == type.id }
            assertTrue(stored.enabled)
            assertEquals(2, stored.offsetDays)
            assertEquals("07:30", stored.deliveryTime)
            assertEquals("waste_reminder_offset_invalid", expectHttp(400) { api.updateReminder(stored.copy(offsetDays = 99)) }.reason)
            api.updateReminder(stored.copy(enabled = false))
        } finally {
            api.deleteType(type.id)
        }
    }

    /** WASTE-A21 (the feed status, the URL source's address check, the pickups list of the Tags view). */
    @Test fun `WASTE-A21 feed, url check and pickups list`() = runBlocking<Unit> {
        val api = alex()
        val feed = api.feed()
        if (feed != null) assertTrue(feed.url.contains("/feed/waste/"))
        assertEquals("waste_source_url_invalid", expectHttp(400) { api.createUrlSource(unique("U"), "not a url", 1440, key()) }.reason)
        val type = newType(api)
        try {
            val pickup = api.createPickup(WasteRules.pickupBody(type.id, today.plusDays(3), "#contract"), key())
            val (client, _) = signedIn("alex")
            val listed = client.call(io.github.marcomow.yuvomi.feature.waste.WasteOperation.PICKUPS_LIST)
            assertNotNull(listed)
            assertTrue(listed.toString().contains("#contract"))
            api.deletePickup(pickup.id)
        } finally {
            api.deleteType(type.id)
        }
    }
}
