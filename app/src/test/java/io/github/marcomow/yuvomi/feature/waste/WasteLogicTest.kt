package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** docs/spec/waste.md, the pure rules (WASTE-A1 to A10). */
class WasteLogicTest {
    private val strings = Strings(
        "en",
        mapOf(
            "waste.weekdayMon" to "Mon", "waste.weekdayFri" to "Fri", "waste.weekdayTue" to "Tue",
            "waste.summaryWeekly" to "Weekly ({{days}})", "waste.summaryWeeklyInterval" to "Every {{interval}} weeks ({{days}})",
            "waste.summaryMonthly" to "Monthly, {{day}}", "waste.summaryMonthlyInterval" to "Every {{interval}} months, {{day}}",
            "waste.summaryMonthDayN" to "day {{day}}", "waste.monthDayLastDay" to "Last day of the month",
            "waste.ordinalSecond" to "Second", "waste.ordinalLast" to "Last",
            "waste.summaryOrdinalWeekday" to "Monthly, {{ordinal}} {{weekday}}",
            "waste.summaryOrdinalWeekdayInterval" to "Every {{interval}} months, {{ordinal}} {{weekday}}",
            "waste.presetGeneral" to "General waste", "waste.presetRecycling" to "Recycling", "waste.presetOrganic" to "Organic",
            "waste.presetPaper" to "Paper", "waste.presetGlass" to "Glass", "waste.presetBulky" to "Bulky waste",
            "waste.importDiagnosticSkippedUnparsable" to "{{count}} skipped", "waste.importDiagnosticOther" to "Other",
        ),
        emptyMap(),
    ) { "other" }

    private fun occ(type: Int, date: String, vararg origins: OccurrenceOrigin, moved: Boolean = false) =
        WasteOccurrence(key = "$type:$date", dateKey = date, typeId = type, typeName = "T$type", moved = moved, origins = origins.toList())

    private fun schedule(kind: String, weekdays: String? = null, monthDay: Int? = null, interval: Int = 1) =
        WasteSchedule(id = 1, typeId = 1, recurrenceKind = kind, anchorDate = "2026-11-02", interval = interval, weekdays = weekdays, monthDay = monthDay)

    /** WASTE-A1: `recurrenceSummary()` for the three kinds, with and without interval. */
    @Test fun `WASTE-A1 recurrence summaries`() {
        assertEquals("Weekly (Mon, Fri)", WasteRules.recurrenceSummary(schedule("weekly", "MO,FR"), strings))
        assertEquals("Every 2 weeks (Tue)", WasteRules.recurrenceSummary(schedule("weekly", "TU", interval = 2), strings))
        assertEquals("Monthly, day 15", WasteRules.recurrenceSummary(schedule("monthly_fixed_day", monthDay = 15), strings))
        assertEquals("Every 3 months, Last day of the month", WasteRules.recurrenceSummary(schedule("monthly_fixed_day", monthDay = -1, interval = 3), strings))
        assertEquals("Monthly, Second Tue", WasteRules.recurrenceSummary(schedule("monthly_ordinal_weekday", "TU", 2), strings))
        assertEquals("Every 2 months, Last Fri", WasteRules.recurrenceSummary(schedule("monthly_ordinal_weekday", "FR", -1, 2), strings))
    }

    /** WASTE-A2: one row per type first, the rest folded; a deep link into the rest expands it. */
    @Test fun `WASTE-A2 upcoming split and deep link`() {
        val list = listOf(occ(1, "2026-11-03"), occ(2, "2026-11-04"), occ(1, "2026-11-10"), occ(2, "2026-11-11"))
        val split = WasteRules.splitUpcoming(list)
        assertEquals(listOf("1:2026-11-03", "2:2026-11-04"), split.primary.map { it.key })
        assertEquals(listOf("1:2026-11-10", "2:2026-11-11"), split.rest.map { it.key })
        assertTrue(WasteRules.deepLinkNeedsExpand(list, 1, "2026-11-10"))
        assertFalse(WasteRules.deepLinkNeedsExpand(list, 1, "2026-11-03"))
        assertFalse(WasteRules.deepLinkNeedsExpand(list, 1, null))
    }

    /** WASTE-A3: ordinal anchors on the rule itself; the weekday of a day. */
    @Test fun `WASTE-A3 ordinal anchors`() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals(LocalDate.of(2026, 12, 8), WasteRules.nearestOrdinalAnchor(2, "TU", today))
        assertEquals(LocalDate.of(2026, 11, 27), WasteRules.nearestOrdinalAnchor(-1, "FR", today))
        assertEquals(LocalDate.of(2026, 11, 12), WasteRules.nearestOrdinalAnchor(2, "TH", today))
        assertEquals("TH", WasteRules.weekdayCodeOf(today))
        assertEquals(LocalDate.of(2027, 1, 4), WasteRules.nearestOrdinalAnchor(1, "MO", LocalDate.of(2026, 12, 8)))
    }

    /** WASTE-A4: types: unscheduled, remaining presets by name or look, FAB intent, default type, swaps. */
    @Test fun `WASTE-A4 types onboarding and order`() {
        val types = listOf(
            WasteType(1, "Restmüll", "trash-2", "#78808C", sortOrder = 0),
            WasteType(2, "Recycling", "box", "#000000", sortOrder = 1),
            WasteType(3, "Old", archived = true, sortOrder = 2),
            WasteType(4, "Bio", "leaf", "#16a34a", sortOrder = 3),
        )
        val schedules = listOf(schedule("weekly", "MO").copy(typeId = 1))
        assertFalse(WasteRules.isUnscheduled(types[0], schedules, emptyList()))
        assertTrue(WasteRules.isUnscheduled(types[1], schedules, emptyList()))
        assertFalse(WasteRules.isUnscheduled(types[1], schedules, listOf(occ(2, "2026-11-05"))))
        assertFalse(WasteRules.isUnscheduled(types[2], schedules, emptyList()))
        val left = WasteRules.remainingPresets(types) { strings.t(it.labelKey) }.map { it.key }
        assertEquals(listOf("paper", "glass", "bulky"), left)
        assertTrue(WasteRules.fabCreatesSchedule(types))
        assertFalse(WasteRules.fabCreatesSchedule(listOf(types[2])))
        assertEquals(2, WasteRules.defaultScheduleType(types, schedules)?.id)
        assertEquals((4 to 0) to (1 to 3), WasteRules.moveSwap(listOf(types[0], types[3]), 4, up = true))
        assertNull(WasteRules.moveSwap(listOf(types[0], types[3]), 1, up = true))
        assertTrue(WasteRules.isOnboarding(emptyList()))
    }

    /** WASTE-A5: schedule bodies for each kind; weekly needs a weekday. */
    @Test fun `WASTE-A5 schedule form and body`() {
        val today = LocalDate.of(2026, 11, 2)
        val fresh = WasteRules.scheduleForm(null, 7, today)
        assertEquals(setOf("MO"), fresh.weekdays)
        val weekly = WasteRules.scheduleBody(fresh.copy(weekdays = setOf("FR", "MO"), interval = 2))!!
        assertEquals(listOf("MO", "FR"), weekly["weekdays"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2", weekly["interval"]!!.jsonPrimitive.content)
        assertFalse("month_day" in weekly)
        assertEquals(JsonNull, weekly["valid_until"])
        assertNull(WasteRules.scheduleBody(fresh.copy(weekdays = emptySet())))
        assertNull(WasteRules.scheduleBody(fresh.copy(typeId = null)))
        val last = WasteRules.scheduleBody(fresh.copy(kind = "monthly_fixed_day", lastDay = true))!!
        assertEquals("-1", last["month_day"]!!.jsonPrimitive.content)
        assertFalse("weekdays" in last)
        val ordinal = WasteRules.scheduleBody(fresh.copy(kind = "monthly_ordinal_weekday", ordinal = -1, ordinalWeekday = "FR", validUntil = LocalDate.of(2027, 1, 1)))!!
        assertEquals("-1", ordinal["month_day"]!!.jsonPrimitive.content)
        assertEquals("FR", ordinal["weekdays"]!!.jsonPrimitive.content)
        assertEquals("2027-01-01", ordinal["valid_until"]!!.jsonPrimitive.content)
        val edited = WasteRules.scheduleForm(schedule("monthly_ordinal_weekday", "TU", 2).copy(active = false), null, today)
        assertEquals(2, edited.ordinal)
        assertEquals("TU", edited.ordinalWeekday)
        assertFalse(edited.active)
        val pickup = WasteRules.pickupBody(3, today, "  ")
        assertEquals(JsonNull, pickup["note"])
    }

    /** WASTE-A6: occurrence origins: series override day, one-off, moved-from. */
    @Test fun `WASTE-A6 occurrence origins`() {
        val moved = occ(1, "2026-11-05", OccurrenceOrigin("schedule", scheduleId = 9, originalDate = "2026-11-04", moved = true), moved = true)
        assertEquals(WasteRules.ScheduleOrigin(9, "2026-11-04"), WasteRules.scheduleOrigin(moved))
        assertEquals("2026-11-04", WasteRules.movedFrom(moved))
        val plain = occ(1, "2026-11-12", OccurrenceOrigin("schedule", scheduleId = 9))
        assertEquals(WasteRules.ScheduleOrigin(9, "2026-11-12"), WasteRules.scheduleOrigin(plain))
        val imported = occ(2, "2026-11-06", OccurrenceOrigin("import", sourceId = 4))
        assertNull(WasteRules.scheduleOrigin(imported))
        assertNull(WasteRules.oneOffOrigin(imported))
        assertFalse(WasteRules.hasScheduleOrigin(imported))
        assertEquals(plain, WasteRules.nextForSchedule(9, listOf(imported, plain)))
    }

    /** WASTE-A7: source health and the row action. */
    @Test fun `WASTE-A7 source badge and action`() {
        val url = WasteSource(1, kind = "url", needsMapping = true, lastError = "x", needsRefresh = true)
        assertEquals("waste.sourceNeedsMappingBadge", WasteRules.healthBadgeKey(url))
        assertEquals("waste.sourceErrorBadge", WasteRules.healthBadgeKey(url.copy(needsMapping = false)))
        assertEquals("waste.sourceNeedsRefreshBadge", WasteRules.healthBadgeKey(url.copy(needsMapping = false, lastError = null)))
        assertNull(WasteRules.healthBadgeKey(WasteSource(2)))
        assertEquals(WasteRules.SourceAction.REVIEW, WasteRules.sourceAction(url))
        assertEquals(WasteRules.SourceAction.REFRESH, WasteRules.sourceAction(url.copy(needsMapping = false)))
        assertEquals(WasteRules.SourceAction.REIMPORT, WasteRules.sourceAction(WasteSource(3)))
        assertEquals("waste-mapping-profile-abfall-2026-.json", WasteRules.profileFileName("Abfall 2026!"))
        assertTrue(WasteRules.mappingChanged(WasteMapping(1, typeIds = listOf(1, 2)), setOf(1)))
        assertFalse(WasteRules.mappingChanged(WasteMapping(1, typeIds = listOf(2, 1)), setOf(1, 2)))
    }

    /** WASTE-A8: the import wizard: defaults, blocking diagnostics, decisions, the commit body. */
    @Test fun `WASTE-A8 import decisions`() {
        assertEquals(WasteRules.LabelChoice(emptySet(), false, "Rest"), WasteRules.defaultChoice(ImportLabel("rest", "Rest", rememberedIgnored = true, suggestedTypeId = 4)))
        assertEquals(setOf(1, 2), WasteRules.defaultChoice(ImportLabel("rest", "Rest", rememberedTypeIds = listOf(1, 2))).typeIds)
        assertEquals(setOf(4), WasteRules.defaultChoice(ImportLabel("rest", "Rest", suggestedTypeId = 4)).typeIds)
        assertTrue(WasteRules.defaultChoice(ImportLabel("neu", "Neu")).createNew)
        val diagnostics = listOf(ImportDiagnostic("blocking", "unsupported_rdate", "uid:a"), ImportDiagnostic("info", "skipped_unparsable", count = 2))
        assertEquals(1, WasteRules.unresolvedBlocking(diagnostics, emptySet()).size)
        assertTrue(WasteRules.unresolvedBlocking(diagnostics, setOf("uid:a")).isEmpty())
        assertEquals("2 skipped", WasteRules.diagnosticText(diagnostics[1], strings))
        assertEquals("Other", WasteRules.diagnosticText(ImportDiagnostic(code = "new_code"), strings))
        val decisions = WasteRules.buildDecisions(listOf(
            "rest" to WasteRules.LabelChoice(emptySet(), false, ""),
            "bio" to WasteRules.LabelChoice(setOf(3, 1), true, " Bio "),
        )) as WasteRules.Decisions.Ok
        assertEquals(buildJsonObject { put("normalized_label", "rest"); put("ignored", true) }, decisions.mappings[0])
        val bio = decisions.mappings[1].jsonObject
        assertEquals(listOf(1, 3), bio["type_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("Bio", bio["new_type"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(WasteRules.buildDecisions(listOf("x" to WasteRules.LabelChoice(emptySet(), true, " "))) is WasteRules.Decisions.MissingName)
        val preview = ImportPreview(digest = "d1", expectedVersion = 3)
        val reimport = WasteRules.commitBody(JsonArray(emptyList()), setOf("uid:a"), preview, ics = null, reimport = true, name = null)
        assertEquals("3", reimport["expected_version"]!!.jsonPrimitive.content)
        assertFalse("ics" in reimport)
        assertFalse("name" in reimport)
        val fresh = WasteRules.commitBody(JsonArray(emptyList()), emptySet(), preview, ics = "BEGIN", reimport = false, name = "Kalender")
        assertEquals("Kalender", fresh["name"]!!.jsonPrimitive.content)
        assertEquals("BEGIN", fresh["ics"]!!.jsonPrimitive.content)
        assertEquals("d1", fresh["preview_digest"]!!.jsonPrimitive.content)
    }

    /** WASTE-A9: errors: case sentences with their numbers, gone, selected type gone, fetch failures. */
    @Test fun `WASTE-A9 error sentences`() {
        assertEquals(WasteMessage("waste.errorScheduleGone"), WasteErrors.message(ApiProblem.Http(404, "waste_schedule_not_found", null)))
        assertEquals(WasteMessage("waste.errorImportTooLarge", mapOf("limit_mb" to 5)), WasteErrors.message(ApiProblem.Http(400, "waste_import_file_too_large", null)))
        assertEquals(WasteMessage("waste.errorSourceInterval", mapOf("min" to 60, "max" to 43_200)), WasteErrors.message(ApiProblem.Http(400, "waste_source_interval_invalid", null)))
        // A fetch status the error does not carry: the general sentence of the place.
        assertEquals(WasteMessage("common.unknownError"), WasteErrors.message(ApiProblem.Http(502, "waste_source_fetch_http_status", null), "common.unknownError"))
        assertEquals(
            WasteMessage("waste.errorSourceFetchStatus", mapOf("status" to 404)),
            WasteErrors.fetchFailure("waste_source_fetch_http_status", buildJsonObject { put("status", JsonPrimitive(404)) }),
        )
        assertEquals(WasteMessage("waste.errorSourceFetch"), WasteErrors.fetchFailure(null, null))
        assertEquals(WasteMessage("common.errorNoPermission"), WasteErrors.message(ApiProblem.Http(403, "waste_type_not_found", null)))
        assertEquals(WasteMessage("common.errorOfflineMutation"), WasteErrors.message(ApiProblem.Network(IOException())))
        assertTrue(WasteErrors.isSelectedTypeGone(ApiProblem.Http(404, "waste_selected_type_not_found", null)))
        assertTrue(WasteErrors.alreadyDeleted(ApiProblem.Http(404, "waste_pickup_not_found", null), WasteErrors.PICKUP_GONE))
        assertFalse(WasteErrors.isGone(ApiProblem.Http(409, "waste_type_in_use", null)))
        assertEquals(WasteMessage("waste.typeDeleteRefused"), WasteErrors.message(ApiProblem.Http(409, "waste_type_in_use", null)))
    }

    /** WASTE-A10: the Tags view: hashtags in type names, pickup notes and source names. */
    @Test fun `WASTE-A10 tag source items`() {
        val items = WasteTagSource.items(
            listOf(WasteType(1, "Bio #garden"), WasteType(2, "Paper")),
            listOf(WastePickup(5, 2, "2026-11-20", "Big box #moving")),
            listOf(WasteSource(7, name = "City #2026cal")),
        )
        assertEquals(listOf("type:1", "pickup:5", "source:7"), items.map { it.key })
        assertEquals(WasteRoutes.pickup(1), items[0].route)
        assertEquals("Paper", items[1].title)
        assertEquals(WasteRoutes.pickup(2, "2026-11-20"), items[1].route)
        assertEquals(setOf("2026cal"), items[2].tags)
    }
}
