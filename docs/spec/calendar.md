# Spec: Calendar

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's calendar as native screens, at parity with upstream's Calendar page: read the
month, week, day and agenda, find an event, see what else lands on a day (tasks, holidays,
shifts, waste collections, birthdays), create and change events with repetition, reminders,
people, colours and icons, change or delete one occurrence of a series or the whole series, and
keep the household's local calendars.

## Scope

Everything the web page (`public/pages/calendar.js`) lets a member do:

| Area | Requirement IDs |
|---|---|
| Views and periods: month, week, day, agenda, arrows, Today | R1–R6 |
| Loading, offline, the layers (tasks, holidays, schedule, waste, birthdays) | R7–R11 |
| Filter sheet: people, "assigned to me", calendars, layers | R12 |
| Read view of an event | R13 |
| Search | R14 |
| Event form: fields, checks, body, reminders, sync target | R15–R18 |
| Series: the scope question on save and delete, the endpoints per scope | R19–R21 |
| Delete with undo | R22 |
| Local calendar manager | R23–R26 |
| Permissions, errors | R27–R28 |

**In the browser or elsewhere.** Subscriptions (ICS URLs), the calendar feed and connecting
Google, Apple, Outlook or CalDAV accounts are not on the Calendar page: upstream keeps them in
Settings (`public/settings/pages/personal-calendar-subscriptions.js`, `/settings/modules/calendar`),
and the page links there ("Subscriptions and sync"). The app's link goes to the native Settings
route (`SettingsRoutes.START`); the Settings module owns those screens (the OAuth connections
of Google and Outlook open in the browser there). As on the web page, a shift opens its
details, a waste collection opens Waste (`WasteRoutes.START`), a task opens its task
(`Routes.task(id)`), an attachment opens its document (`DocumentsRoutes.document(id)`), and a
housekeeping visit opens Housekeeping (`HousekeepingRoutes.START`).

**Native differences.** The month is split on every width (grid above, the chosen day's list
below), as the web page does on a phone; a tap on a day's heading opens the day view. The week is
three days from the cursor on a phone and the whole week from 600 dp, as the web page decides at
its phone breakpoint. Periods change with the arrows, not by swiping. The read view of an event
is its own screen (`calendar-event`), the form is its own screen (`calendar-editor`), the
manager is its own screen (`calendar-calendars`); `CalendarRoutes.event(id)` opens the calendar
at the event's day and then its read view, like the web's `/calendar?open=<id>`. Event icons are
the closest Material glyphs for upstream's Lucide names. The web's desktop sidebar (mini month,
"up next") has no phone equivalent and is not built.

## Upstream references

- `public/pages/calendar.js`: `getRangeForView`, `monthGridSpan`, `monthStepCursor`,
  `periodStepOf`, `mobileWeekDays`, `getWeekRange`, `getAgendaRange`, `visibleDayRange`,
  `newEventDefaultDate`, `localDate`, `localTime`, `eventEndDate`, `isMultiDayEvent`,
  `isAllDayLike`, `agendaSegmentKind`, `multiDayPosition`, `timeRangeForEvent`, `buildDayIndex`,
  `eventsOnDay`, `tasksOnDay`, `holidaysOnDay`, `scheduleEntriesOnDay`, `wasteOccurrencesOnDay`,
  `belongsToMe`, `matchesPeopleFilter`, `passesPersonFilters`, `eventSourceKey`,
  `calendarSources`, `activeFilterCount`, `availableLayers`, `toggleCalendarPersonChip`,
  `loadRange`, `loadLocalCalendars`, `loadUsers`, `applyEventFormPreferences`, `updateLabel`,
  `renderMonthView`, `renderWeekView`, `renderDayView`, `renderAgendaView`, `dayGroupHtml`,
  `renderAgendaEvent`, `eventTimeText`, `clickedTime`, `openEventDetail`, `renderEventDetail`,
  `eventWhenText`, `eventWhenRelative`, `reminderSummary`, `mapRowAction`, `runCalendarSearch`,
  `buildEventModalContent`, `defaultNewEventTime`, `saveEvent`, `seriesEndConflict`,
  `eventTimesUntouched`, `eventFormChanged`, `loadSyncTargets`, `applyDefaultSyncTarget`,
  the reminder helpers (`reminderRowForLead` … `reminderRowsUnchanged`), `requestDeleteEvent`,
  `confirmExternalSeriesDelete`, `recurringScopeChoice`, `deleteEvent`, `deleteThisAndFollowing`,
  `deleteSingleOccurrence`, `openLocalCalendarsModal`, `localCalendarSheet`, `moveLocalCalendar`,
  `localCalendarErrorSentence`, `calendarSaveErrorMessage`, `occurrenceErrorMessage`, `eventGone`.
- `public/utils/recurrence-scope.js` (all of it), `event-color.js`, `birthday-event.js`,
  `overlap-lanes.js` (`assignLanes`), `event-icon.js`, `color.js`, `date.js`
  (`weekStartIndex`, `defaultDateInPeriod`), `friendly-error.js`, `permissions.js`
  (`canChangeRecord`, `canEditOthers`), `public/rrule-ui.js`.
- `server/routes/calendar/read.js`, `crud.js`, `local-calendars.js`, `feed.js` (`/holidays`),
  `sync-targets.js`, `helpers.js` (`serializeEvent`), `server/routes/reminders.js`,
  `server/routes/schedule.js` (`/entries`), `server/routes/waste/occurrences.js`.

## Upstream operations

In `CalendarOperation` (`upstream/operations-used.d/calendar.txt`), wrapped by `CalendarApi`.
Also used from other enums: `Operation.PREFERENCES`, `Operation.TASKS_LIST` (with
`include_future=1`) and `MealsOperation.FAMILY_MEMBERS`.

| Method | Path | Used for |
|---|---|---|
| GET | `/calendar?from&to` | the window of a view, one day wider each side (`fetchWindow`) |
| POST | `/calendar` | create; `Idempotency-Key` |
| GET | `/calendar/search?q` | search, `data` and `total` |
| GET | `/calendar/holidays?from&to` | the holiday layer |
| GET | `/calendar/{id}` | the deep link, the series master for a whole-series edit |
| PUT | `/calendar/{id}` | edit an event or a whole series; truncating a rule (legacy path) |
| DELETE | `/calendar/{id}` | delete an event or a whole series, 204 |
| POST | `/calendar/{id}/reset` | reset a locally changed subscribed event |
| POST | `/calendar/{id}/exceptions` | the legacy "only this" for series without linked overrides |
| PUT / DELETE | `/calendar/{seriesId}/occurrences/{recurrenceId}` | "only this" |
| PUT / DELETE | `/calendar/{seriesId}/occurrences/{recurrenceId}/following` | "this and following" |
| GET / POST | `/calendar/calendars` | local calendars, create |
| PATCH | `/calendar/calendars/reorder` | move up/down |
| PUT / DELETE | `/calendar/calendars/{id}` | rename and recolour, delete |
| POST / DELETE | `/calendar/calendars/{id}/feed[/regenerate]` | export link on and off (admins) |
| GET | `/calendar/sync-targets` | the sync-target picker of the form |
| GET | `/reminders/all?entity_type=event&entity_id` | the member's reminders of an event |
| PUT / DELETE | `/reminders?entity_type=event&entity_id` | write (`remind_ats`) or clear them |
| GET | `/schedule/entries?from&to` | the schedule layer |
| GET | `/waste/occurrences?from&to` | the waste layer |
| GET | `/documents/{id}/preview` | an image attachment in the read view |

## Requirements

| ID | Requirement | Upstream | Status |
|---|---|---|---|
| CAL-R1 | Four views: month, week, day, agenda; the view is remembered on the device. | `VIEWS`, `setSavedCalendarView` | done |
| CAL-R2 | The month grid runs whole weeks from the household's week start (`week_start`). | `monthGridSpan`, `weekStartIndex` | done |
| CAL-R3 | Arrows step a month (from the 1st; today when in that month), three days or a week, a day, or 30 days, and are named for what they do. | `monthStepCursor`, `periodStepOf`, `periodArrowLabels` | done |
| CAL-R4 | Today jumps to the household's today; it is inactive on today. | `goToday`, `syncTodayButton` | done |
| CAL-R5 | The period title: month and year; the three days or "W45 · month year"; the long day; the agenda's 31 days. | `updateLabel` | done |
| CAL-R6 | A new event without a chosen day starts on the month's chosen day, else today when in view, else the first day shown; at 09:00, or today the next half hour; with the household's default duration. | `newEventDate`, `defaultNewEventTime`, `calendar_default_duration` | done |
| CAL-R7 | A view loads events, tasks, holidays, schedule and waste of its window in parallel; a failing layer is empty, a failing event load is the error. Times are read in the household zone. | `loadRange`, `fetchWindow`, `localDate` | done |
| CAL-R8 | Offline the last window answer shows read-only with the banner; adding needs a connection. | ADR 0007 | done |
| CAL-R9 | The month cell shows the day, today marked, and dots for its entries; the chosen day's list follows the grid. | `renderMonthView`, `monthTitles: false` | done |
| CAL-R10 | Week and day draw a time grid with an all-day band (all-day and day-long events, holidays, shifts, waste, tasks), timed events side by side where they overlap, the now line; a long press creates an event at the half hour. | `renderWeekView`, `renderDayView`, `assignLanes`, `clickedTime` | done |
| CAL-R11 | The agenda lists the 31 days from the cursor that have something, and today always; rows show time (from/until/all day), "Day n of m", place, calendar, visibility, people. | `renderAgendaView`, `renderAgendaEvent`, `eventTimeText` | done |
| CAL-R12 | The filter sheet: "assigned to me", people chips with "unassigned" (none = all; all = none), calendars and subscriptions on/off (remembered with name and colour), layers (holidays and school holidays when the household shows them, schedule and waste when readable, birthdays); the filter button counts active filters; reset. | `openCalendarFilters`, `calendarSources`, `activeFilterCount` | done |
| CAL-R13 | The read view: colour, the time in words, people, calendar, repetition, place with "Open in Maps" (OpenStreetMap search), a free-text assignee, reminders in words, visibility, countdown, description (500 characters), attachment (image preview, link to the document, or "private"), the foreign read-only hint; Edit and Delete only for who may change it; reset for a changed subscribed event. | `openEventDetail`, `renderEventDetail` | done |
| CAL-R14 | Search from two characters, debounced, with the count ("n of m" when capped), empty and error states; a hit opens its read view. | `runCalendarSearch` | done |
| CAL-R15 | The form: title, calendar (with more than one local calendar; not with a Google or CalDAV target), all day, from/to (date, time), people, repetition (none/daily/weekly/monthly/yearly, interval, weekdays, month end, ends never/on date/after n), reminders, location; behind "More details": description, visibility (when someone else reads the calendar), countdown, colour (the assignee's, the palette, the event's own), icon, sync target, attachment removal. A new event takes the default assignee and reminders from preferences. | `buildEventModalContent` | done |
| CAL-R16 | Moving the start keeps the duration. | `wireDurationMemory` | done |
| CAL-R17 | Save checks all at once: title, end before start, repeat end before start when the rule or start day changes; a stored rule the form does not change goes back verbatim. | `saveEvent`, `seriesEndConflict`, `getRRuleValues` (#756) | done |
| CAL-R18 | The body is the web's; an untouched time is not sent on edit (#1958); reminders are written with `PUT /reminders` (or cleared) unless the occurrence endpoint carries them. | `saveEvent` | done |
| CAL-R19 | Editing an occurrence of a local series asks "only this / this and following / whole series" (no "only this" when the calendar changed), nothing when nothing changed; a series the member may only change whole asks to confirm. "This and following" at the start is the series. The whole series moves its start by the shift of the opened occurrence. A 409 for orphaned overrides is reported, not retried with a count (see below). | `recurringScopeChoice`, `requestCalendarOccurrenceMutation`, `shiftSeriesStart` | done, 409 retry partial |
| CAL-R20 | Deleting: a synced, subscribed, birthday or name-day series confirms with its own sentence and deletes the series; a local series asks the scope; a whole-series-only one confirms. | `requestDeleteEvent`, `confirmExternalSeriesDelete` | done |
| CAL-R21 | The endpoint per scope, and the legacy path (new event + exception, truncated rule + new event) for series without linked overrides. | `recurrence-scope.js` | done |
| CAL-R22 | Delete hides at once with Undo; only without undo does the request go; "already deleted" is success. | `scheduleCalendarDeleteWithUndo`, `deleteUnlessGone` | done |
| CAL-R23 | The manager lists the calendars with colour and event count; write access adds and edits (name, family-tone colour). | `renderLocalCalendarRow`, `localCalendarSheet` | done |
| CAL-R24 | Move up/down; a changed order reloads the list. | `moveLocalCalendar` | done |
| CAL-R25 | Admins enable, copy and disable the export link (confirming the disable). | `calendar-enable-feed` … | done |
| CAL-R26 | Delete (not the default) confirms with the sentence about the default calendar (iCloud variant); "already deleted" is success; the link "Subscriptions and sync" goes to Settings. | `calendar-delete` | done |
| CAL-R27 | `calendar: read` hides add, edit, delete and the manager's writes; changing someone else's event needs `calendar_edit_others` unless created by or assigned to the member. | `readOnly`, `mayChangeEvent` | done |
| CAL-R28 | Errors show upstream's sentences (`calendar.*`, `common.*`), never the server's text. | `errorSentence`, `CASE_MESSAGES` | done |

**Not built** (each small): the per-type filter under the waste layer, uploading a new
attachment from the form (removing one works; adding one stays in the browser), the schedule
"display" switch and the month "event titles" switch, swipe between periods, the web desktop
sidebar, the sync target suggested from the assignees (`assigneeSyncTarget`), the orphaned
overrides confirmation with its count (needs `orphaned_override_count` from the 409 answer,
which `ApiProblem.Http` does not carry), and the time-zone-aware `UNTIL` reading of synced series
(`untilDayOf` with `tzid`); the form reads UNTIL's date part, which is exact for series made in
Yuvomi.

## Acceptance criteria

| ID | Criterion | Kind | Status |
|---|---|---|---|
| CAL-A1 | Datetimes read in the household zone: wall times as they are, instants converted, a date at midnight. | unit | passing |
| CAL-A2 | A timed event ending at midnight stays on its day; all-day ends are inclusive; segments, "day n of m" and clamped minutes. | unit | passing |
| CAL-A3 | Month grids, steps (no skipped month), the phone's three days and its load window, the new-event date and time. | unit | passing |
| CAL-A4 | Overlapping events share the column like `assignLanes`. | unit | passing |
| CAL-A5 | Colour from event, assignee, calendar; the picker adds a foreign colour. | unit | passing |
| CAL-A6 | Filters hide by layer, source and person; unassigned; sources include hidden ones; all people is no filter; the count. | unit | passing |
| CAL-A7 | Own and assigned events are changeable without edit-others; the task layer keeps open dated tasks. | unit | passing |
| CAL-A8 | `truncateRuleBefore`, `seriesEndsBeforeStart`, `shiftSeriesStart`, `shiftEndForStart`, exception bodies, the series day of an instant key, and which delete question applies. | unit | passing |
| CAL-A9 | Reminder rows to naive-UTC moments (09:00 for all-day), lead times, after-start rows kept. | unit | passing |
| CAL-A10 | The form body, checks, verbatim rule and duration memory. | unit | passing |
| CAL-A11 | Error reasons map to upstream's sentences. | unit | passing |
| CAL-A12 | Events decode with numeric flags; pending deletes hide by scope. | unit | passing |
| CAL-A13 | The month lists today's events and tasks; the agenda and day views show them; the window and task layer are requested. | ui | passing |
| CAL-A14 | An event opens its read view; delete offers undo and sends nothing when undone. | ui | passing |
| CAL-A15 | Deleting an occurrence of a local series asks the three scopes. | ui | passing |
| CAL-A16 | The form refuses an empty title, then posts the web body and writes or clears reminders. | ui | passing |
| CAL-A17 | `calendar: read` has no add, edit or delete. | ui | passing |
| CAL-A20 | The reads (window, holidays, calendars, members, preferences, tasks, schedule, waste, sync targets) decode against the seeded server. | contract | written |
| CAL-A21 | Search finds by title; an empty query answers nothing. | contract | written |
| CAL-A22 | Create, read, edit (omitted fields kept), delete, then 404 `calendar_event_not_found`. | contract | written |
| CAL-A23 | A series without occurrence is 400 `calendar_series_no_occurrence`. | contract | written |
| CAL-A24 | A repeated create with one key is one event. | contract | written |
| CAL-A25 | `emma` cannot change `alex`'s event: 403 `not_owner`. | contract | written |
| CAL-A26 | Reset refuses a local event (400) and a missing one (404). | contract | written |
| CAL-A27 | A local series expands with `series_id`, `recurrence_id` and override rights. | contract | written |
| CAL-A28 | "Only this" edit with lead times and delete; a malformed key is 400. | contract | written |
| CAL-A29 | "This and following" splits; deleting following truncates. | contract | written |
| CAL-A30 | An exception removes one day; a truncated rule ends the series; an exception on a single event is 400. | contract | written |
| CAL-A31 | Reminders are written, read back and cleared. | contract | written |
| CAL-A32 | Local calendars: create, rename and recolour, reorder (400 `calendar_order_changed` for an incomplete order), export on/off, delete moves events to the default, a second delete is 404, the default cannot go. | contract | written |
| CAL-A33 | A child cannot enable the export (403). | contract | written |
| CAL-A34 | An image attachment's preview is readable through the session; remove clears it. | contract | written |

## Strings

Upstream's `calendar`, `rrule`, `reminders`, `common`, `colorPicker`, `category`, `modal`,
`birthdays`, `schedule`, `waste`, `documentAttach`, `dashboard` and `settings` namespaces. No
app-only strings beyond the shared `android.error.*` and `android.tasks.ruleKept`.
