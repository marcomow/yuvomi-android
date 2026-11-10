# Spec: Schedule

Status: implemented · Upstream checked: 2.87.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's shift and timetable planner as native screens, at parity with upstream's
Schedule page: compare people's weeks on a time grid, keep the shift types (with custom fields),
plan repeating cycles, overrides and extra shifts, and read the statistics of a period.

## Scope

Everything `public/pages/schedule.js` lets a member do, on its four tabs (`utils/schedule-tabs.js`):

| Area | Requirement IDs |
|---|---|
| Page: load, tabs, start tab, warnings, FAB, routes | R1–R5 |
| Overview: week/day grid, lanes, people, night continuation, holidays, details | R6–R13 |
| Shift types and custom fields, quick start, presets | R14–R20 |
| Planning: plans with cycle days, overrides, extra shifts | R21–R29 |
| Statistics: filter, tiles, bar charts, overtime | R30–R34 |
| Schedule feed | R35–R36 |
| Permissions, errors, offline, tags | R37–R41 |

**What stays elsewhere.** The member's own reminder lead time, weekly hours and overtime switch
are edited in Settings (`settings/pages/personal-schedule.js`), as on the web; the overflow menu
links to native Settings (`SettingsRoutes.START`). The household's hidden templates are a
Settings option too; this page only honours them. **Print** of the statistics (`window.print()`)
is not offered natively.

**Native differences.** The feed (`settings/pages/personal-feeds.js`, part `schedule`) is also
reachable from the page's overflow menu, with Copy (clipboard), Subscribe (a `webcal://` intent)
and New link. Icons: upstream stores Lucide names; the app shows a catalogue of ~50 common ones
in Material's set and keeps any other name (shown as a clock). Colour: the ten preset swatches
plus a hex field. Month fields are steppers. Drag-reordering of attached fields is replaced by
up/down buttons (the web has both). Overview selection and week/day mode are kept per device in
the response cache folder (the web's `localStorage`). Tapping a list row opens its edit dialog
(the web has only the row menu).

## Upstream references

- `public/pages/schedule.js` (`load`, `render`, `activateView`, `refreshOverview`,
  `refreshStatistics`, `overviewFetchRange`, `buildOverviewLanes`, `overviewEntryBlock`,
  `openOverviewPeople`, `initialOverviewSelection`, `shiftTypeGroups`, `shiftTypeRow`,
  `customFieldsSection`, `emptyShiftTypesState`, `PRESET_TEMPLATES`, `presetShortCode`,
  `presetAlreadyThere`, `shiftPresetOptgroups`, `openScheduleCreateModal`, `saveCreatedSchedule`,
  `openShiftTypeEditModal`, `saveShiftTypeEdit`, `openPatternEditModal`, `savePatternEdit`,
  `patternSaveOrder`, `syncPatternDayGroups`, `cycleDayNextDate`, `cycleDayHeaderLabel`,
  `findOverlappingActivePattern`, `resolveWinningPatternId`, `overrideGroups`, `extraGroups`,
  `rangeDifference`, `openOverrideEditModal`, `openExtraGroupEditModal`, `statisticsSummary`,
  `overtimeInfo`, `statisticBounds`, `renderStatistics`, `renderScheduleEntryDetailContent`,
  `overlayMeta`, `scheduleFabIntent`, `canWrite`, `canEditType`, `userOptions`, `action`).
- `public/utils/shift-label.js`, `date-format.js` (`shiftTimeText`, `dateRangeText`),
  `schedule-reminder-offset.js`, `time-grid.js` (`alignedHourScroll`), `member-order.js`,
  `initials.js` (`sharedSurname`), `date.js` (`weekStartIndex`, `startOfLocalWeekKey`),
  `color.js` (`USER_COLORS`), `friendly-error.js` (`errorSentence`, `isGone`).
- `server/routes/schedule.js`, `schedule-extras.js`, `schedule-feed.js`,
  `schedule-preferences.js`, `server/services/schedule.js` (`scheduleData`, `resolveEntries`).

## Upstream operations

`ScheduleOperation` (32, `upstream/operations-used.d/schedule.txt`): every `/api/v1/schedule/*`
route the page and the feed use (entries, household-members, shift-types and their fields,
custom-fields, patterns and days, overrides with fill and range delete, extras with fill, feed,
preferences GET) and `GET /calendar/holidays`. Also used: core `GET /preferences` (week start,
hidden templates) and `DocumentsOperation.AUTH_USERS` (`GET /auth/users`, names of every owner).
`PUT /schedule/preferences` belongs to Settings.

## Requirements

### Page
- **SCHED-R1** `load()`: all lists at once, then each plan's days; cached for offline. ✅
- **SCHED-R2** Tabs Overview, Shift types, Planning, Statistics; `schedule/<tab>` deep links win;
  the bare root starts on Shift types while nothing exists, else Overview (`render`). ✅
- **SCHED-R3** The overview tab hides while nothing exists unless it is the chosen one. ✅
- **SCHED-R4** Overlap warnings of today above the tabs (`renderScheduleWarnings`). ✅
- **SCHED-R5** FAB (`scheduleFabIntent`): "Shift type" on Shift types or while none exists,
  else "Schedule" (the create dialog); none on Statistics or for read-only members. ✅

### Overview
- **SCHED-R6** Week (household week start) or day, with prev/next, Today when off-screen. ✅
- **SCHED-R7** A fixed lane per chosen person in household order; lane heads without the shared surname. ✅
- **SCHED-R8** Timed blocks by start and length (min 30 min), all-day entries in the head. ✅
- **SCHED-R9** A night shift continues on the next day from 00:00 ("…continues until"); the fetch
  starts a day early. ✅
- **SCHED-R10** Holidays over their days (optional call). ✅
- **SCHED-R11** People sheet; the selection defaults to oneself and is kept on the device. ✅
- **SCHED-R12** The grid opens on the current hour (`alignedHourScroll`); a day head opens the day. ✅
- **SCHED-R13** A block opens the read-only entry detail (owner, note, all fields, origin). ✅

### Shift types
- **SCHED-R14** Grouped "With times" (by start) and "All day" (by name); label "Code · Name". ✅
- **SCHED-R15** Create with a preset picker (grouped, hidden templates honoured), name, code,
  colour, icon, start/end (both or neither). ✅
- **SCHED-R16** Edit: same fields plus attached custom fields (attach, remove with its overlay
  switch remembered, reorder, "show on calendar"), written after the details. ✅
- **SCHED-R17** Delete asks; already deleted is no failure; in use answers `typeInUse`. ✅
- **SCHED-R18** Only the creator or an admin edits; others read who owns it. ✅
- **SCHED-R19** Quick start per template creates only presets not there yet; counted toast. ✅
- **SCHED-R20** Custom fields: create, rename, delete (asks with the number of types). ✅

### Planning
- **SCHED-R21** Plans in household order with "Takes precedence"; someone else's opens read-only. ✅
- **SCHED-R22** Create dialog with three modes: plan, override (replace), extra (add). ✅
- **SCHED-R23** A new active plan overlapping another asks first. ✅
- **SCHED-R24** Edit: fields and cycle days (several rows per day, fields per row, date heads),
  hidden beyond the typed length; saved in `patternSaveOrder`; re-activation asks. ✅
- **SCHED-R25** Delete asks with the number of cycle days. ✅
- **SCHED-R26** Override: one day via PUT, a range via fill after asking. ✅
- **SCHED-R27** Override rows grouped; edit fills the new range and deletes leftovers. ✅
- **SCHED-R28** Extra: one or a range, reminder lead time, fields; "no shift type" hint. ✅
- **SCHED-R29** Extra rows grouped; edit creates new then deletes old; delete asks, loops. ✅

### Statistics
- **SCHED-R30** Owner (non-admins only themselves), current month / full months / custom. ✅
- **SCHED-R31** Every change reloads at once; the newest request wins; invalid period says so. ✅
- **SCHED-R32** Tiles: shift count (with free days), total hours, overtime when on and above. ✅
- **SCHED-R33** Two bar charts (count, hours) drawn on a Canvas, scaled to the largest. ✅
- **SCHED-R34** One empty state naming person and period. ✅

### Feed
- **SCHED-R35** Feed switch (on = regenerate, off asks), address, Copy, Subscribe. ✅
- **SCHED-R36** New link asks first. ✅

### Permissions, errors, offline, tags
- **SCHED-R37** `schedule: read` hides every editing affordance; viewing stays. ✅
- **SCHED-R38** Errors map `reason` to upstream's sentences (`ScheduleErrors`); gone reloads. ✅
- **SCHED-R39** Offline: cached data with the banner, writes refused. ✅
- **SCHED-R40** Creates carry an idempotency key. ✅
- **SCHED-R41** `ScheduleTagSource`: hashtags in type names, plan names, notes and field values. ✅

## Acceptance criteria

- **SCHED-A1–A14** pure rules (`ScheduleLogicTest`): labels, times, permissions, groups, presets,
  override/extra grouping, range difference, pattern rules, statistics, lanes, overlay, bodies,
  errors, tag source. ✅
- **SCHED-A15–A19** UI against a scripted server (`ScheduleScreenTest`): overview and details,
  shift type ownership, plan delete, read-only, statistics. ✅
- **SCHED-A20–A26** contract tests against the pinned image (`ScheduleContractTest`). ✅ (CI)

## Strings

Upstream's `schedule.*`, `calendar.*`, `common.*`, `colorPicker.*`, `iconPicker.*`,
`newLabel.*`, `settings.scheduleFeed*`; app-only: `android.schedule.noCalendarApp`.
