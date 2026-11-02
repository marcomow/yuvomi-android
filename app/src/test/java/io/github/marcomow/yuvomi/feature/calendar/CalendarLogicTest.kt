package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.feature.tasks.RRule
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** docs/spec/calendar.md: the pure parts, with cases from upstream's own rules. */
class CalendarLogicTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val dates = CalendarDates(berlin, DayOfWeek.MONDAY)
    private fun d(s: String) = LocalDate.parse(s)
    private fun ev(start: String, end: String? = null, allDay: Boolean = false, id: Int = 1) =
        CalendarEvent(id = id, title = "E$id", startDatetime = start, endDatetime = end, allDay = allDay)

    /** CAL-A1. */
    @Test fun `datetimes read in the household zone`() {
        assertEquals(d("2026-11-02"), dates.localDate("2026-11-02T09:30"))
        assertEquals(LocalTime.of(9, 30), dates.localTime("2026-11-02T09:30"))
        // An instant late in UTC is the next day in Berlin (#829).
        assertEquals(d("2026-11-03"), dates.localDate("2026-11-02T23:30:00Z"))
        assertEquals(LocalTime.of(0, 30), dates.localTime("2026-11-02T23:30:00Z"))
        assertEquals(LocalTime.MIDNIGHT, dates.localTime("2026-11-02"))
        assertEquals(LocalTime.of(10, 0), dates.localTime("2026-11-02T09:00:00+0000"))
    }

    /** CAL-A2: `eventEndDate` (#804) and `isAllDayLike`. */
    @Test fun `an event ending at midnight stays on its day`() {
        assertEquals(d("2026-11-06"), dates.endDate(ev("2026-11-06T21:00", "2026-11-07T00:00")))
        assertFalse(dates.isMultiDay(ev("2026-11-06T21:00", "2026-11-07T00:00")))
        // All-day ends are inclusive.
        assertEquals(d("2026-11-09"), dates.endDate(ev("2026-11-07", "2026-11-09", allDay = true)))
        assertTrue(dates.isMultiDay(ev("2026-11-07", "2026-11-09", allDay = true)))
        assertTrue(dates.isAllDayLike(ev("2026-11-07T10:00", "2026-11-09T11:00")))
        assertFalse(dates.isAllDayLike(ev("2026-11-07T22:00", "2026-11-08T02:00")))
        assertEquals(CalendarDates.Segment.START, dates.segment(ev("2026-11-07T22:00", "2026-11-08T02:00"), d("2026-11-07")))
        assertEquals(CalendarDates.Segment.END, dates.segment(ev("2026-11-07T22:00", "2026-11-08T02:00"), d("2026-11-08")))
        assertEquals(2 to 3, dates.position(ev("2026-11-07", "2026-11-09", allDay = true), d("2026-11-08")))
        // `timeRangeForEvent`: clamped to the day, at least 30 minutes.
        assertEquals(22 * 60 until 24 * 60, dates.minutes(ev("2026-11-07T22:00", "2026-11-08T02:00"), d("2026-11-07")))
        assertEquals(0 until 120, dates.minutes(ev("2026-11-07T22:00", "2026-11-08T02:00"), d("2026-11-08")))
        assertEquals(600 until 630, dates.minutes(ev("2026-11-07T10:00", "2026-11-07T10:05"), d("2026-11-07")))
    }

    /** CAL-A3: periods, steps and the date of a new event. */
    @Test fun `periods follow the web page`() {
        // November 2026 starts on a Sunday: the grid starts on Monday 26 October, six weeks.
        val grid = dates.monthGrid(d("2026-11-15"))
        assertEquals(d("2026-10-26"), grid.first())
        assertEquals(42, grid.size)
        assertEquals(d("2026-09-27"), CalendarDates(berlin, DayOfWeek.SUNDAY).monthGrid(d("2026-10-01")).first())
        // From the 31st the next month is the 1st, never a skipped month; today when it is in it.
        assertEquals(d("2026-02-01"), dates.step(CalendarView.MONTH, d("2026-01-31"), 1, false, d("2026-11-02")))
        assertEquals(d("2026-11-02"), dates.step(CalendarView.MONTH, d("2026-10-31"), 1, false, d("2026-11-02")))
        assertEquals(d("2026-11-05"), dates.step(CalendarView.WEEK, d("2026-11-02"), 1, false, d("2026-11-02")))
        assertEquals(d("2026-11-09"), dates.step(CalendarView.WEEK, d("2026-11-02"), 1, true, d("2026-11-02")))
        assertEquals(d("2026-12-02"), dates.step(CalendarView.AGENDA, d("2026-11-02"), 1, false, d("2026-11-02")))
        // The phone's week is three days from the cursor; it loads the whole week around them too.
        assertEquals(listOf(d("2026-11-08"), d("2026-11-09"), d("2026-11-10")), dates.weekDays(d("2026-11-08"), false))
        assertEquals(d("2026-11-02") to d("2026-11-10"), dates.range(CalendarView.WEEK, d("2026-11-08"), false))
        assertEquals(d("2026-11-02") to d("2026-11-09"), dates.range(CalendarView.DAY, d("2026-11-02"), false))
        // `newEventDate`: today when in view, else the first day shown.
        assertEquals(d("2026-11-04"), dates.newEventDate(CalendarView.MONTH, d("2026-11-04"), d("2026-11-02"), false))
        assertEquals(d("2026-11-02"), dates.newEventDate(CalendarView.AGENDA, d("2026-11-01"), d("2026-11-02"), false))
        assertEquals(d("2026-12-01"), dates.newEventDate(CalendarView.AGENDA, d("2026-12-01"), d("2026-11-02"), false))
        // `defaultNewEventTime`: 09:00, or today the next half hour.
        assertEquals(LocalTime.of(9, 0), dates.defaultNewEventTime(d("2026-11-03"), LocalDateTime.parse("2026-11-02T14:10")))
        assertEquals(LocalTime.of(14, 30), dates.defaultNewEventTime(d("2026-11-02"), LocalDateTime.parse("2026-11-02T14:10")))
        assertEquals(LocalTime.of(15, 0), dates.defaultNewEventTime(d("2026-11-02"), LocalDateTime.parse("2026-11-02T14:40")))
        assertEquals(LocalTime.of(9, 0), dates.defaultNewEventTime(d("2026-11-02"), LocalDateTime.parse("2026-11-02T23:45")))
        assertEquals(DayOfWeek.SATURDAY, CalendarDates.weekStartOf("saturday"))
        assertEquals(DayOfWeek.MONDAY, CalendarDates.weekStartOf(null))
    }

    /** CAL-A4: overlap lanes (`assignLanes`). */
    @Test fun `overlapping events share the column`() {
        val ranges = mapOf("a" to (600 until 660), "b" to (630 until 700), "c" to (660 until 720), "d" to (800 until 830))
        val lanes = CalendarDates.lanes(ranges.keys.toList()) { ranges.getValue(it) }
        assertEquals(0 to 2, lanes["a"])
        assertEquals(1 to 2, lanes["b"])
        assertEquals(0 to 2, lanes["c"])
        assertEquals(0 to 1, lanes["d"])
    }

    /** CAL-A5: colours (`resolveEventColor`) and the picker (#856). */
    @Test fun `colour comes from the event, the person, the calendar`() {
        val person = EventPerson(2, "Linda", color = "#DB2777")
        assertEquals("#587DCE", CalendarRules.color(ev("2026-11-02").copy(color = "#587DCE", assignedUsers = listOf(person))))
        assertEquals("#DB2777", CalendarRules.color(ev("2026-11-02").copy(assignedUsers = listOf(person), calColor = "#000000")))
        assertEquals(CalendarRules.FALLBACK_COLOR, CalendarRules.color(ev("2026-11-02").copy(assignedUsers = listOf(EventPerson(3, "X")))))
        assertEquals("#123456", CalendarRules.color(ev("2026-11-02").copy(localCalendarColor = "#123456")))
        assertEquals(11, CalendarRules.pickerColors("#ABCDEF").size)
        assertEquals(10, CalendarRules.pickerColors("#587dce").size)
    }

    /** CAL-A6: the filters (`passesPersonFilters`, `eventSourceKey`, `calendarSources`). */
    @Test fun `filters hide by layer, source and person`() {
        val linda = EventPerson(2, "Linda")
        val mine = ev("2026-11-02").copy(assignedUsers = listOf(EventPerson(1, "Alex")))
        val hers = ev("2026-11-02", id = 2).copy(assignedUsers = listOf(linda), subscriptionId = 7, calName = "School")
        val nobody = ev("2026-11-02", id = 3).copy(localCalendarId = 4)
        val birthday = ev("2026-11-02", id = 4).copy(birthdayName = "Emma")
        val all = CalendarFilter()
        assertTrue(listOf(mine, hers, nobody, birthday).all { CalendarRules.shows(it, all, 1) })
        val meOnly = all.copy(assignedToMe = true)
        assertEquals(listOf(1, 4), listOf(mine, hers, nobody, birthday).filter { CalendarRules.shows(it, meOnly, 1) }.map { it.id })
        val lindaOnly = all.copy(people = setOf("2"))
        assertEquals(listOf(2, 4), listOf(mine, hers, nobody, birthday).filter { CalendarRules.shows(it, lindaOnly, 1) }.map { it.id })
        val unassigned = all.copy(people = setOf(CalendarFilter.UNASSIGNED))
        assertEquals(listOf(3, 4), listOf(mine, hers, nobody, birthday).filter { CalendarRules.shows(it, unassigned, 1) }.map { it.id })
        assertFalse(CalendarRules.shows(birthday, all.copy(layerBirthdays = false), 1))
        assertEquals("sub:7", CalendarRules.sourceKey(hers))
        assertEquals("local:4", CalendarRules.sourceKey(nobody))
        assertNull(CalendarRules.sourceKey(mine))
        assertFalse(CalendarRules.shows(hers, all.copy(hiddenSources = mapOf("sub:7" to HiddenSource("School"))), 1))
        val sources = CalendarRules.sources(listOf(LocalCalendar(4, "Yuvomi")), listOf(hers), mapOf("cal:9" to HiddenSource("Work", "#111111")))
        assertEquals(listOf("School", "Work", "Yuvomi"), sources.map { it.name })
        // Choosing every person and "unassigned" is no filter (`toggleCalendarPersonChip`).
        var f = CalendarFilter()
        f = CalendarRules.togglePerson(f, "1", 2)
        f = CalendarRules.togglePerson(f, "2", 2)
        assertEquals(setOf("1", "2"), f.people)
        assertEquals(emptySet<String>(), CalendarRules.togglePerson(f, CalendarFilter.UNASSIGNED, 2).people)
        assertEquals(2, CalendarRules.activeCount(lindaOnly.copy(layerBirthdays = false), CalendarPrefs(), false))
    }

    /** CAL-A7: who may change an event (R23, D5). */
    @Test fun `own and assigned events are changeable without edit-others`() {
        val e = ev("2026-11-02").copy(createdBy = 2, assignedUsers = listOf(EventPerson(3, "Emma")))
        assertTrue(CalendarRules.mayChange(e, 2, write = true, editOthers = false))
        assertTrue(CalendarRules.mayChange(e, 3, write = true, editOthers = false))
        assertFalse(CalendarRules.mayChange(e, 4, write = true, editOthers = false))
        assertTrue(CalendarRules.mayChange(e, 4, write = true, editOthers = true))
        assertFalse(CalendarRules.mayChange(e, 2, write = false, editOthers = true))
        assertTrue(CalendarRules.calendarTasks(listOf(
            CalendarTask(1, dueDate = "2026-11-02"), CalendarTask(2, dueDate = "2026-11-02", status = "done"),
            CalendarTask(3), CalendarTask(4, dueDate = "2026-11-02", archivedAt = "x"),
        )).map { it.id } == listOf(1))
    }

    /** CAL-A8: the scope arithmetic of public/utils/recurrence-scope.js. */
    @Test fun `recurrence scope helpers match upstream`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH;UNTIL=20261108", RecurrenceScope.truncateRuleBefore("RRULE:FREQ=WEEKLY;BYDAY=MO,TH;COUNT=10", "2026-11-09"))
        assertEquals("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=-1;UNTIL=20261130", RecurrenceScope.truncateRuleBefore("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=-1;UNTIL=20271231", "2026-12-01"))
        assertEquals("FREQ=DAILY;UNTIL=20261231", RecurrenceScope.truncateRuleBefore("FREQ=DAILY;INTERVAL=1;BYMONTHDAY=-1", "2027-01-01"))
        assertNull(RecurrenceScope.truncateRuleBefore(null, "2026-11-09"))
        assertTrue(RecurrenceScope.seriesEndsBeforeStart("FREQ=DAILY;UNTIL=20260930", "2026-10-02T10:00"))
        assertFalse(RecurrenceScope.seriesEndsBeforeStart("FREQ=DAILY;UNTIL=20261002", "2026-10-02"))
        assertFalse(RecurrenceScope.seriesEndsBeforeStart("FREQ=DAILY", "2026-10-02"))
        assertEquals("2026-11-12T11:00", RecurrenceScope.shiftSeriesStart("2026-11-05T10:00", "2026-11-19T10:00", "2026-11-26T11:00", false, dates))
        assertEquals("2026-11-12T12:30", RecurrenceScope.shiftEndForStart("2026-11-12T11:00", "2026-11-26T11:00", "2026-11-26T12:30", false, dates))
        assertEquals("2026-11-07", RecurrenceScope.shiftSeriesStart("2026-11-05", "2026-11-19", "2026-11-21", true, dates))
        assertEquals(mapOf("recurrence_id" to "2026-11-09", "date" to "2026-11-09"), RecurrenceScope.exceptionBody("2026-11-09"))
        assertEquals(mapOf("recurrence_id" to "2026-11-09T09:00:00Z"), RecurrenceScope.exceptionBody("2026-11-09T09:00:00Z"))
        // An instant key falls on the day of the series zone (#2034).
        val la = ev("2026-11-01T19:30:00-08:00").copy(tzid = "America/Los_Angeles")
        assertEquals("2026-11-09", RecurrenceScope.seriesDay(la, "2026-11-10T03:30:00Z"))
        val series = ev("2026-11-02T10:00").copy(recurrenceRule = "FREQ=DAILY", seriesId = 1, isLocalRecurringSeries = true, canOverrideOccurrence = true)
        assertFalse(RecurrenceScope.isExternalSeries(series))
        assertTrue(RecurrenceScope.isExternalSeries(series.copy(isLocalRecurringSeries = false)))
        assertTrue(RecurrenceScope.requiresWholeSeriesConfirmation(series.copy(canOverrideOccurrence = false)))
        assertTrue(RecurrenceScope.followingMeansWholeSeries(series))
        assertFalse(RecurrenceScope.followingMeansWholeSeries(series.copy(isRecurringInstance = true)))
        assertTrue(RecurrenceScope.followingMeansWholeSeries(series.copy(isRecurringInstance = true, isSeriesStart = true)))
        assertEquals(DeleteQuestion.Scope, deleteQuestion(series))
        assertEquals(DeleteQuestion.WholeSeriesOnly, deleteQuestion(series.copy(canOverrideOccurrence = false)))
        assertEquals(DeleteQuestion.External("calendar.deleteSubscribedSeries"), deleteQuestion(series.copy(isLocalRecurringSeries = false, subscriptionId = 3)))
        assertEquals(DeleteQuestion.External("calendar.deleteBirthdayEvent"), deleteQuestion(series.copy(isLocalRecurringSeries = false, birthdayName = "Emma")))
        assertNull(deleteQuestion(ev("2026-11-02")))
    }

    /** CAL-A9: reminders (lead times, the 09:00 anchor of all-day events, after-start rows). */
    @Test fun `reminder rows convert like the web form`() {
        // 10:00 Berlin in November is 09:00 UTC.
        assertEquals(listOf("2026-11-02T09:00:00", "2026-11-02T08:45:00", "2026-11-01T09:00:00"),
            EventReminders.times(listOf(EventReminders.Row("0"), EventReminders.Row("15"), EventReminders.Row("1440"), EventReminders.Row("15")), "2026-11-02T10:00", dates))
        // All-day: 09:00 household time.
        assertEquals(listOf("2026-11-01T08:00:00"), EventReminders.times(listOf(EventReminders.Row("custom", 1, "days")), "2026-11-02", dates))
        assertEquals(listOf(0, 90, 2880), EventReminders.offsets(listOf(EventReminders.Row("0"), EventReminders.Row("custom", 90, "minutes"), EventReminders.Row("custom", 2, "days"))))
        assertEquals(EventReminders.Row("1440", 1, "days"), EventReminders.rowForLead(1440))
        assertEquals(EventReminders.Row("custom", 3, "hours"), EventReminders.rowForLead(180))
        assertEquals(EventReminders.Row("custom", 3, "weeks"), EventReminders.rowForLead(30240))
        val e = ev("2026-11-02T10:00")
        assertEquals("60", EventReminders.rowFrom(e, StoredReminder(1, "2026-11-02T08:00:00"), dates).offset)
        // A reminder after the start keeps its moment (#1260) and has no lead time for the occurrence endpoints.
        val after = EventReminders.rowFrom(e, StoredReminder(2, "2026-11-02T10:00:00"), dates)
        assertEquals(EventReminders.AFTER_START, after.offset)
        assertTrue(after.keepsStoredInstant)
        assertEquals(listOf("2026-11-02T10:00:00"), EventReminders.times(listOf(after), "2026-11-03T10:00", dates))
        assertNull(EventReminders.occurrenceOffsets(listOf(after)))
        assertEquals(listOf(15), EventReminders.occurrenceOffsets(listOf(EventReminders.Row("15"))))
        assertTrue(EventReminders.unchanged(listOf(after), listOf(after)))
        assertFalse(EventReminders.unchanged(listOf(EventReminders.Row("15")), listOf(after)))
    }

    /** CAL-A35 (CAL-R29, 2.84.0): an external series opened at an occurrence keeps an unchanged reminder. */
    @Test fun `an unchanged reminder of an untouched external series keeps its stored instant`() {
        // Series start 2026-10-05 10:00 Berlin (08:00 UTC), opened at 2026-11-02 10:00 (09:00 UTC).
        val untouched = EventReminders.Untouched("2026-10-05T10:00", listOf(EventReminders.naiveUtc("2026-10-05 07:00:00Z")!!))
        assertEquals("2026-10-05T07:00:00", untouched.storedAts.single())
        // 60 minutes before the series start hits the stored instant and stays; a new 15-minute row counts from the occurrence.
        assertEquals(listOf("2026-10-05T07:00:00", "2026-11-02T08:45:00"),
            EventReminders.times(listOf(EventReminders.Row("60"), EventReminders.Row("15")), "2026-11-02T10:00", dates, untouched))
        // Without it, the row slides onto the occurrence, as before.
        assertEquals(listOf("2026-11-02T08:00:00"), EventReminders.times(listOf(EventReminders.Row("60")), "2026-11-02T10:00", dates))
        assertEquals("2026-10-05T08:00:00", EventReminders.naiveUtc("2026-10-05T10:00:00+02:00"))
        assertNull(EventReminders.naiveUtc(""))
    }

    /** CAL-A10: the form body, checks and duration memory. */
    @Test fun `the form builds the web body`() {
        val form = EventForm.create(d("2026-11-02"), LocalTime.of(9, 0), 90, 1, CalendarPrefs(defaultAssignMe = true, defaultReminders = listOf(15, 15, 60)), 4)
        assertEquals(LocalTime.of(10, 30), form.endTime)
        assertEquals(listOf(1), form.assignees)
        assertEquals(listOf("15", "60"), form.reminders.map { it.offset })
        val body = form.copy(title = " Dentist ", rule = RRule.Parts(freq = "WEEKLY", byday = listOf("MO")), syncTarget = "caldav:3|https://x/cal/").body(null, calendarsOffered = true)
        assertEquals(JsonPrimitive("Dentist"), body["title"])
        assertEquals(JsonPrimitive("2026-11-02T09:00"), body["start_datetime"])
        assertEquals(JsonPrimitive("FREQ=WEEKLY;BYDAY=MO"), body["recurrence_rule"])
        assertEquals(JsonPrimitive(3), body["target_caldav_account_id"])
        assertEquals(JsonPrimitive("https://x/cal/"), body["target_caldav_calendar_url"])
        assertEquals(JsonNull, body["color"])
        assertEquals(JsonNull, body["description"])
        // A CalDAV target leaves the local calendars.
        assertFalse(body.containsKey("local_calendar_id"))
        assertEquals(JsonPrimitive(4), form.body(null, calendarsOffered = true)["local_calendar_id"])
        assertEquals(1, body["assigned_to"]!!.jsonArray.size)
        val allDay = form.copy(allDay = true, endDate = d("2026-11-04")).body(null, true)
        assertEquals(JsonPrimitive("2026-11-02"), allDay["start_datetime"])
        assertEquals(JsonPrimitive("2026-11-04"), allDay["end_datetime"])
        // Checks: title, end before start, repeat end before start.
        val errors = form.copy(endDate = d("2026-11-01"), rule = RRule.Parts(freq = "DAILY", until = "2026-10-30")).errors(null, dates)
        assertEquals(setOf(EventForm.Field.TITLE, EventForm.Field.END, EventForm.Field.UNTIL), errors.keys)
        // A stored rule the form does not change goes back verbatim (#756).
        val stored = ev("2026-11-02T09:00").copy(recurrenceRule = "RRULE:FREQ=MONTHLY;BYDAY=3TH;WKST=MO")
        val edit = EventForm.edit(stored, emptyList(), dates, 60)
        assertEquals("RRULE:FREQ=MONTHLY;BYDAY=3TH;WKST=MO", edit.ruleToSave(stored.recurrenceRule))
        // Moving the start keeps the duration (#441).
        val moved = form.withStart(d("2026-11-03"), LocalTime.of(23, 0))
        assertEquals(d("2026-11-04"), moved.endDate)
        assertEquals(LocalTime.of(0, 30), moved.endTime)
    }

    /** CAL-A11: error sentences, never the server's text. */
    @Test fun `errors map to upstream sentences`() {
        assertEquals("calendar.errorEventGone", CalendarErrors.key(ApiProblem.Http(404, "calendar_event_not_found", "x")))
        assertEquals("calendar.occurrenceGoneError", CalendarErrors.key(ApiProblem.Http(400, "invalid_recurrence_id", "x")))
        assertEquals("calendar.errorEventGone", CalendarErrors.key(ApiProblem.Http(404, "calendar_series_not_found", "x")))
        assertEquals("common.errorNotOwner", CalendarErrors.key(ApiProblem.Http(403, "not_owner", "x")))
        assertEquals("calendar.errorDeleteFeedAdminOnly", CalendarErrors.key(ApiProblem.Http(403, "calendar_feed_admin_only", "x")))
        assertEquals("calendar.attachmentUploadRefused", CalendarErrors.key(ApiProblem.Http(403, "ATTACHMENT_UPLOAD_REFUSED", "x")))
        assertEquals("calendar.errorSeriesNoOccurrence", CalendarErrors.key(ApiProblem.Http(400, "calendar_series_no_occurrence", "x")))
        assertEquals("calendar.saveError", CalendarErrors.key(ApiProblem.Http(400, null, "x"), "calendar.saveError"))
        assertEquals("common.errorOfflineMutation", CalendarErrors.key(ApiProblem.Network(IOException("x"))))
        assertTrue(CalendarErrors.eventGone(ApiProblem.Http(404, "calendar_event_not_found", null)))
        assertFalse(CalendarErrors.eventGone(ApiProblem.Http(403, "calendar_event_not_found", null)))
        assertTrue(CalendarErrors.calendarsStale(ApiProblem.Http(400, "calendar_order_changed", null)))
    }

    /** CAL-A12: the event shape decodes with flags as numbers, and the pending-delete overlay. */
    @Test fun `events decode and pending deletes hide by scope`() {
        val json = """{"data":[{"id":5,"title":"Swim","start_datetime":"2026-11-02T17:00","end_datetime":"2026-11-02T18:00","all_day":0,
            "recurrence_rule":"FREQ=WEEKLY","series_id":5,"recurrence_id":"2026-11-02T17:00:00","is_local_recurring_series":true,
            "can_override_occurrence":true,"assigned_users":[{"id":3,"display_name":"Emma","color":"#16A34A"}],"countdown":1,"unknown":"x"}]}"""
        val list = CalendarApi.decodeEvents(ApiJson.parseToJsonElement(json))
        assertEquals(1, list.size)
        assertTrue(list[0].countdown)
        assertEquals("2026-11-02T17:00:00", RecurrenceScope.recurrenceId(list[0]))
        val a = list[0]
        val b = a.copy(startDatetime = "2026-11-09T17:00")
        val c = a.copy(startDatetime = "2026-11-16T17:00")
        val other = ev("2026-11-09T10:00", id = 9)
        val following = CalendarStore.PendingDelete(5, RecurrenceScope.Scope.FOLLOWING, b.startDatetime)
        assertEquals(listOf(false, true, true, false), listOf(a, b, c, other).map { following.hides(it) })
        val one = CalendarStore.PendingDelete(5, RecurrenceScope.Scope.THIS, b.startDatetime)
        assertEquals(listOf(false, true, false, false), listOf(a, b, c, other).map { one.hides(it) })
    }
}
