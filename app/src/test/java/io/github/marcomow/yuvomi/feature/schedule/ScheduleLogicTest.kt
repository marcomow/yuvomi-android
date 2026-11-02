package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** docs/spec/schedule.md: the pure rules ported from `public/pages/schedule.js` and its utils. */
class ScheduleLogicTest {
    private val strings = Strings(
        "en",
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()),
        emptyMap(),
    ) { if (it == 1.0) "one" else "other" }
    private val clock: (LocalTime) -> String = { "%02d:%02d".format(it.hour, it.minute) }

    private fun type(id: Int, name: String, code: String? = null, start: String? = null, end: String? = null, by: Int? = 1) =
        ShiftType(id, name, code, start, end, createdBy = by)

    private val early = type(1, "Early shift", "E", "06:00", "14:00")
    private val night = type(2, "Night shift", "N", "22:00", "06:00")
    private val vacation = type(3, "Vacation", "V")
    private val accounts = listOf(Account(1, "alex", "Alex Johnson", sortOrder = 1), Account(2, "linda", "Linda Johnson", sortOrder = 0))

    private fun entry(date: String, t: ShiftType?, user: Int = 1, source: String = Entry.SOURCE_PATTERN, extraId: Int? = null) =
        Entry(userId = user, dateKey = date, source = source, shiftType = t, shiftTypeId = t?.id, extraId = extraId, patternId = 9)

    /** SCHED-A1: `shiftTypeLabel` names the code only when it adds something. */
    @Test fun `labels skip a code the name already says`() {
        assertEquals("E · Early shift", ScheduleRules.label(early))
        assertEquals("24-hour shift", ScheduleRules.label(type(4, "24-hour shift", "24")))
        assertEquals("1. Stunde", ScheduleRules.label(type(5, "1. Stunde", "1")))
        assertEquals("F · Frühschicht", ScheduleRules.label(type(6, "Frühschicht", "F")))
        assertEquals("Vacation", ScheduleRules.label(type(7, "Vacation", "")))
    }

    /** SCHED-A2: spans, over midnight, round the clock, all day; and the minutes. */
    @Test fun `shift times and lengths`() {
        assertEquals("06:00 - 14:00", ScheduleRules.shiftTimeText(early, strings, clock))
        assertEquals("22:00 - 06:00 +1", ScheduleRules.shiftTimeText(night, strings, clock))
        assertEquals("10:00 - 10:00 +1 · 24 h", ScheduleRules.shiftTimeText(type(8, "24", null, "10:00", "10:00"), strings, clock))
        assertEquals("All day", ScheduleRules.clockLabel(vacation, strings, clock))
        assertEquals(480, ScheduleRules.shiftMinutes(early))
        assertEquals(480, ScheduleRules.shiftMinutes(night))
        assertEquals(1440, ScheduleRules.shiftMinutes(type(8, "24", null, "10:00", "10:00")))
        assertNull(ScheduleRules.shiftMinutes(vacation))
        assertEquals("7.5 h", ScheduleRules.formatHours(450, strings))
        assertEquals("9 h", ScheduleRules.formatHours(540, strings))
    }

    /** SCHED-A3: owners of rows and the read-only member. */
    @Test fun `who may write what`() {
        val member = ScheduleRules.Viewer(me = 1, readOnly = false, manageOthers = false)
        assertTrue(member.canWrite(1))
        assertFalse(member.canWrite(2))
        assertTrue(member.canEdit(1))
        assertFalse(member.canEdit(null))
        val admin = ScheduleRules.Viewer(me = 1, readOnly = false, manageOthers = true)
        assertTrue(admin.canWrite(2) && admin.canEdit(null))
        val reader = ScheduleRules.Viewer(me = 1, readOnly = true, manageOthers = true)
        assertFalse(reader.canWrite(1) || reader.canEdit(1))
        val data = ScheduleData(users = accounts + Account(3, "maria", "Maria"), people = listOf(Person(1), Person(2)))
        assertEquals(listOf(1), ScheduleRules.ownerOptions(data, member).map { it.id })
        assertEquals(listOf(1, 2), ScheduleRules.ownerOptions(data, admin).map { it.id })
        assertEquals("Maria", ScheduleRules.userName(data.users, 3))
        assertEquals("7", ScheduleRules.userName(data.users, 7))
    }

    /** SCHED-A4: shift types grouped by time of day, all-day apart. */
    @Test fun `shift type groups`() {
        val groups = ScheduleRules.shiftTypeGroups(listOf(vacation, night, early, type(9, "Day", null, "08:00", "16:00")))
        assertEquals(listOf("timed", "allDay"), groups.map { it.key })
        assertEquals(listOf("Early shift", "Day", "Night shift"), groups[0].types.map { it.name })
        assertEquals(listOf(listOf("Vacation")), ScheduleRules.shiftTypeGroups(listOf(vacation)).map { g -> g.types.map { it.name } })
    }

    /** SCHED-A5: quick start skips presets the household has, by code, name or look. */
    @Test fun `quick start presets`() {
        val work = ScheduleRules.TEMPLATES.getValue("work")
        assertEquals(7, ScheduleRules.quickStartPresets("work", emptyList(), strings).size)
        val have = listOf(type(1, "Something", "E"), type(2, "late shift"), ShiftType(3, "Mond", icon = "moon", color = "#3b82f6"))
        assertEquals(listOf("day", "fullDay", "vacation", "sick"), ScheduleRules.quickStartPresets("work", have, strings).map { it.key })
        assertEquals("24", ScheduleRules.presetShortCode(work.first { it.key == "fullDay" }, strings))
        assertEquals("LE", ScheduleRules.presetShortCode(ScheduleRules.TEMPLATES.getValue("university").first(), strings))
        assertEquals(listOf("schedule.templateWork", "schedule.presetShared"), ScheduleRules.presetGroups(listOf("school", "university")).map { it.labelKey })
        assertEquals(listOf("work"), ScheduleRules.visibleTemplates(listOf("school", "university")).map { it.first })
    }

    /** SCHED-A6: consecutive overrides and extras of one person collapse into one row. */
    @Test fun `override and extra groups`() {
        val overrides = listOf(
            Override(1, 1, "2026-11-02", 3), Override(2, 1, "2026-11-03", 3), Override(3, 1, "2026-11-05", 3),
            Override(4, 2, "2026-11-02", null, note = "x"), Override(5, 2, "2026-11-03", null, note = "y"),
        )
        val groups = ScheduleRules.overrideGroups(overrides, accounts)
        // Linda (position 0) first, then Alex.
        assertEquals(listOf(2, 2, 1, 1), groups.map { it.userId })
        assertEquals(listOf(1, 2), groups[2].ids)
        assertEquals("2026-11-03", groups[2].to)
        val extras = listOf(Extra(1, 1, "2026-11-02", 1), Extra(2, 1, "2026-11-02", 1), Extra(3, 1, "2026-11-03", 1, reminderOffsetMinutes = 10))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), ScheduleRules.extraGroups(extras, accounts).map { it.ids })
    }

    /** SCHED-A7: what an edited range leaves behind. */
    @Test fun `range difference`() {
        assertEquals(listOf(ScheduleRules.Span("2026-11-01", "2026-11-02"), ScheduleRules.Span("2026-11-09", "2026-11-10")),
            ScheduleRules.rangeDifference("2026-11-01", "2026-11-10", "2026-11-03", "2026-11-08"))
        assertTrue(ScheduleRules.rangeDifference("2026-11-03", "2026-11-04", "2026-11-01", "2026-11-10").isEmpty())
        assertTrue(ScheduleRules.rangeReversed("2026-11-05", "2026-11-04"))
        val f = OccurrenceForm(from = LocalDate.of(2026, 11, 2), to = LocalDate.of(2026, 11, 3)).withFrom(LocalDate.of(2026, 11, 8))
        assertEquals(LocalDate.of(2026, 11, 8), f.to)
    }

    /** SCHED-A8: cycle positions, windows, the winning plan and the save order. */
    @Test fun `pattern rules`() {
        val anchor = LocalDate.of(2026, 11, 2)
        val today = LocalDate.of(2026, 11, 12)
        assertEquals(LocalDate.of(2026, 11, 16), ScheduleRules.cycleDayNextDate(anchor, 7, 1, today))
        assertEquals(LocalDate.of(2026, 11, 12), ScheduleRules.cycleDayNextDate(anchor, 7, 4, today))
        assertEquals(LocalDate.of(2026, 11, 2), ScheduleRules.cycleDayDate(anchor, 7, null, LocalDate.of(2026, 11, 8), 1, today))
        assertEquals(1 to 2, ScheduleRules.daysExceedingCycle(listOf(0, 1), 0))
        assertEquals(6 to 7, ScheduleRules.daysExceedingCycle(listOf(5, 6, 2), 5))
        assertNull(ScheduleRules.daysExceedingCycle(listOf(1, 2), 7))
        val a = Pattern(1, 1, "A", validFrom = null)
        val b = Pattern(2, 1, "B", validFrom = "2026-10-01")
        val c = Pattern(3, 1, "C", validFrom = "2027-01-01", isActive = true)
        assertEquals(2, ScheduleRules.winningPatternId(listOf(a, b, c), 1, "2026-11-12"))
        assertNull(ScheduleRules.winningPatternId(listOf(a), 1, "2026-11-12"))
        assertEquals(a, ScheduleRules.overlappingActivePattern(listOf(a), 1, "2026-12-01", null))
        assertNull(ScheduleRules.overlappingActivePattern(listOf(b.copy(validUntil = "2026-10-31")), 1, "2026-11-01", null))
        assertEquals(listOf(ScheduleRules.SaveStep.DAYS, ScheduleRules.SaveStep.PATTERN), ScheduleRules.patternSaveOrder(14, 7))
        assertEquals(listOf(ScheduleRules.SaveStep.PATTERN, ScheduleRules.SaveStep.DAYS), ScheduleRules.patternSaveOrder(7, 14))
        assertEquals(mapOf("cycle_length" to "common.fieldMax"), ScheduleChecks.pattern(PatternForm(1, "x", anchor, "400")))
    }

    /** SCHED-A9: the statistics summary, bounds and the rolling-week overtime. */
    @Test fun `statistics`() {
        val entries = listOf(entry("2026-11-02", early), entry("2026-11-03", early), entry("2026-11-04", night), entry("2026-11-05", null), entry("2026-11-06", vacation))
        val summary = ScheduleRules.summary(entries)
        assertEquals(listOf("Early shift", "Night shift", "Vacation"), summary.values.map { it.type.name })
        assertEquals(4, summary.totalCount)
        assertEquals(1, summary.freeDays)
        assertEquals(1440, summary.totalMinutes)
        assertEquals(2, summary.hourValues.size)
        val week = (2..8).map { entry("2026-11-0$it", early) }
        assertEquals(ScheduleRules.Overtime(true, 16 * 60), ScheduleRules.overtime(week, 40))
        assertFalse(ScheduleRules.overtime(week.take(5), 40).over)
        val today = LocalDate.of(2026, 11, 12)
        assertEquals(LocalDate.of(2026, 11, 30), ScheduleRules.statisticBounds(ScheduleRules.StatsRange.CURRENT, today, null, null, null, null)!!.to)
        assertNull(ScheduleRules.statisticBounds(ScheduleRules.StatsRange.MONTHS, today, YearMonth.of(2026, 12), YearMonth.of(2026, 11), null, null))
        assertEquals(LocalDate.of(2027, 2, 28), ScheduleRules.statisticBounds(ScheduleRules.StatsRange.MONTHS, today, YearMonth.of(2026, 11), YearMonth.of(2027, 2), null, null)!!.to)
        assertNull(ScheduleRules.statisticBounds(ScheduleRules.StatsRange.CUSTOM, today, null, null, today, today.minusDays(1)))
    }

    /** SCHED-A10: overview lanes, the night continued, the fetch range and the week start. */
    @Test fun `overview lanes`() {
        val days = listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        val entries = listOf(entry("2026-11-02", night), entry("2026-11-03", null), entry("2026-11-03", early, user = 2))
        val lanes = ScheduleRules.buildLanes(days, listOf(2, 1), entries)
        assertEquals(listOf(2, 1), lanes[0].lanes.map { it.userId })
        val alexTuesday = lanes[1].lanes[1].entries
        assertEquals(1, alexTuesday.size)
        assertTrue(alexTuesday[0].continuation)
        assertEquals(0 to 360, alexTuesday[0].span())
        assertEquals(1320 to 1440, lanes[0].lanes[1].entries[0].span())
        val range = ScheduleRules.overviewFetchRange(LocalDate.of(2026, 11, 5), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 1), range.entriesFrom)
        assertEquals(LocalDate.of(2026, 11, 8), range.to)
        assertEquals(LocalDate.of(2026, 11, 1), ScheduleRules.startOfWeek(LocalDate.of(2026, 11, 5), ScheduleRules.weekStart("sunday")))
        assertEquals(listOf(2), ScheduleRules.initialSelection(null, listOf(1, 2), 2))
        assertEquals(listOf(1, 2), ScheduleRules.initialSelection(listOf(2, 1, 9), listOf(1, 2), 2))
        assertEquals(emptyList<Int>(), ScheduleRules.initialSelection(emptyList(), listOf(1, 2), 2))
        assertEquals("Johnson", ScheduleRules.sharedSurname(listOf("Alex Johnson", "Linda Johnson")))
        assertEquals("Alex", ScheduleRules.shortName("Alex Johnson", "Johnson"))
        assertEquals(7, ScheduleRules.topHour(9, 10, null))
        assertEquals(6, ScheduleRules.topHour(9, 10, 6))
        assertEquals(11, ScheduleRules.topHour(20, 10, null))
        assertEquals(2, ScheduleRules.topHour(3, 10, null))
    }

    /** SCHED-A11: the overlay line and the entry key. */
    @Test fun `overlay meta and entry key`() {
        val room = ShiftField(5, "Room", showInOverlay = true)
        val hidden = ShiftField(6, "Secret")
        val e = Entry(userId = 1, dateKey = "2026-11-02", source = Entry.SOURCE_EXTRA, extraId = 4, note = "Bring keys",
            shiftType = early.copy(fields = listOf(room, hidden)), fieldValues = mapOf("5" to "B12", "6" to "x"))
        assertEquals("Bring keys · Room: B12", ScheduleRules.overlayMeta(e))
        assertEquals("2026-11-02:1:extra:4", ScheduleRules.entryKey(e))
        assertEquals("2026-11-02:1:pattern:p9", ScheduleRules.entryKey(entry("2026-11-02", null)))
    }

    /** SCHED-A12: request bodies as the page sends them. */
    @Test fun `bodies`() {
        val type = ScheduleBodies.shiftType(" Late ", "L", "#D946EF", null, null, null)
        assertEquals(JsonNull, type["start_time"])
        assertEquals("Late", (type["name"] as JsonPrimitive).content)
        val days = ScheduleBodies.days(listOf(ScheduleBodies.DayRow(0, 1, mapOf("5" to " B ", "6" to " ")), ScheduleBodies.DayRow(1, null)))
        val first = days["days"]!!.jsonArray[0].jsonObject
        assertEquals(mapOf("5" to JsonPrimitive("B")), first["field_values"]!!.jsonObject.toMap())
        assertEquals(JsonNull, days["days"]!!.jsonArray[1].jsonObject["shift_type_id"])
        val fill = ScheduleBodies.override(1, null, "", emptyMap(), "2026-11-02", "2026-11-04")
        assertEquals(JsonNull, fill["shift_type_id"])
        val extra = ScheduleBodies.extra(1, 2, "", 15, emptyMap(), dateKey = "2026-11-02")
        assertEquals(15, (extra["reminder_offset_minutes"] as JsonPrimitive).content.toInt())
        val fields = ScheduleBodies.typeFields(listOf(ScheduleBodies.AttachedField(4, true), ScheduleBodies.AttachedField(2, false)))
        assertEquals("1", fields["fields"]!!.jsonArray[1].jsonObject["position"].toString())
    }

    /** SCHED-A13: upstream's own sentences for its reasons, never the server text. */
    @Test fun `error sentences`() {
        assertEquals("schedule.typeInUse", ScheduleErrors.key(ApiProblem.Http(409, "schedule_shift_type_in_use", "Shift type is in use.")))
        assertEquals("schedule.rangeOrderError", ScheduleErrors.key(ApiProblem.Http(400, "range_reversed", null)))
        assertEquals("common.errorNoPermission", ScheduleErrors.key(ApiProblem.Http(403, null, "Forbidden.")))
        assertEquals("common.errorGeneric", ScheduleErrors.key(ApiProblem.Http(400, null, "The range must not exceed 100 days.")))
        assertEquals("common.errorOfflineMutation", ScheduleErrors.key(ApiProblem.Network(IOException("x"))))
        assertTrue(ScheduleErrors.isAlreadyDeleted(ApiProblem.Http(404, ScheduleErrors.PATTERN_GONE, null), ScheduleErrors.PATTERN_GONE))
        assertFalse(ScheduleErrors.isGone(ApiProblem.Http(403, ScheduleErrors.PATTERN_GONE, null)))
        assertTrue(strings.has("schedule.typeInUse"))
    }

    /** SCHED-A14: the Tags source lists only items whose own text carries a hashtag. */
    @Test fun `tag source`() {
        val data = ScheduleData(
            users = accounts,
            types = listOf(early, type(10, "Gym #fitness")),
            patterns = listOf(Pattern(3, 2, "Rotation #work"), Pattern(4, 2, "Plain", days = listOf(PatternDay(1, 4, 0, 1, mapOf("5" to "Room #lab"))))),
            overrides = listOf(Override(1, 1, "2026-11-02", null, note = "#holiday"), Override(2, 1, "2026-11-03", null, note = "#holiday"), Override(3, 1, "2026-11-09", 1, note = "none")),
            extras = listOf(Extra(7, 2, "2026-11-04", 1, note = "On call #oncall")),
        )
        val items = ScheduleTagSource.items(data, strings)
        assertEquals(listOf("type:10", "pattern:3", "pattern:4", "override:1:2026-11-02", "extra:7"), items.map { it.key })
        assertEquals(ScheduleRoutes.pattern(4), items[2].route)
        assertEquals(setOf("lab"), items[2].tags)
        assertEquals("schedule/patterns?open=override:1:2026-11-02", items[3].route)
        assertEquals("Free day", items[3].title)
        assertEquals("schedule/shifts?open=type:10", items[0].route)
    }
}
