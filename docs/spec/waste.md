# Spec: Waste

Status: built · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

When which bin goes out, natively: the next pickups, the household's waste types with their
colours and icons, the schedules behind them (weekly, a fixed day or an nth weekday of the month),
moving or skipping one pickup, one-off pickups, municipal ICS calendars as files or URLs with their
label mappings, each member's own reminders, and the member's own feed link.

The module ships switched off upstream; the entry shows only where `shell.shows("waste")`.

## Scope

Everything `public/pages/waste.js` offers. The feed itself is managed (switched on, renewed,
filtered, switched off) in Settings (`public/settings/pages/personal-feeds.js`); this module shows
the member's own link read-only with Copy and Subscribe, and without a feed links to
`SettingsRoutes.START`. Differences are named "Differs:" below.

## Upstream references

- `public/pages/waste.js` (functions cited below), `public/utils/friendly-error.js`,
  `public/utils/date-format.js` (`dateWithDistanceText`, `dateRangeText`).
- `server/routes/waste/` (`types`, `schedules`, `pickups`, `occurrences`, `sources`, `import`,
  `reminder-settings`, `feed`, `helpers`), `server/services/waste-store.js`, `waste-domain.js`,
  `waste-import.js`, `waste-url-source.js`.

## Upstream operations

| Method | Path | Used for |
|---|---|---|
| GET | `/waste/types?include_archived=1` | types (`loadData`) |
| POST · PUT · DELETE | `/waste/types`, `/waste/types/{id}` | create, edit, archive, `sort_order`, delete (409 `waste_type_in_use`) |
| GET | `/waste/schedules` | schedules |
| POST · PUT · DELETE | `/waste/schedules`, `/waste/schedules/{id}` | schedule editor |
| PUT · DELETE | `/waste/schedules/{id}/overrides/{originalDate}` | move (`replacement_date`), skip (`null`), restore |
| GET | `/waste/occurrences?from=&to=` (CalendarOperation.WASTE_OCCURRENCES) | the next 90 days |
| GET | `/waste/pickups` | Tags view only (pickup notes) |
| POST · GET · PUT · DELETE | `/waste/pickups`, `/waste/pickups/{id}` | one-off pickups |
| POST | `/waste/import/preview`, `/waste/import/commit` | file import wizard |
| GET · POST | `/waste/sources` | sources; add a URL source |
| GET · PUT · DELETE | `/waste/sources/{id}` | source sheet (with `mappings`), rename, delete |
| POST | `/waste/sources/{id}/refresh` | "Check now" |
| PUT | `/waste/sources/{id}/mappings/{mappingId}` | `{type_ids}` or `{ignored:true}` |
| POST | `/waste/sources/{id}/reimport/preview`, `/reimport/commit` | re-import, review mapping |
| GET · POST · POST | `/waste/sources/{id}/mapping-profile/export`, `/import/preview`, `/import/commit` | mapping profiles |
| GET · PUT | `/waste/reminder-settings`, `/waste/reminder-settings/{typeId}` | the member's reminders |
| GET | `/waste/feed` | the member's feed link |

## Requirements

Status: all requirements below are built.

### Page

- **WASTE-R1** Top bar `waste.title` with the tools menu (`waste.moreActions`): for writers Add type,
  New one-off pickup (only with an active type, #1775), Import file, Add URL source, Reminders; for
  everyone the feed (`settings.wasteFeedTitle`). FAB for writers: `addSchedule` with an active
  type, else `addType` (`fabIntent`).
- **WASTE-R2** `loadData()`: types with archived, schedules, occurrences from the household's today
  to +90 days, sources, in parallel. Cached for offline reading (`OfflineBanner`, ADR 0007); a first
  load that fails shows `waste.loadErrorTitle` with retry. Pull to refresh; on resume after 60 s.
- **WASTE-R3** Read-only (`waste: read`) shows every value and no write affordance: no FAB, no row
  menus, no tools but the feed, rows do not open editors (sources still open read-only).
- **WASTE-R4** Onboarding without any type (`isOnboarding`): only `emptyTypesTitle`, the six presets
  (one tap creates name, icon and colour, `createPresetType`) and Import file; no upcoming or
  sources sections.

### Upcoming pickups

- **WASTE-R5** One row per type first (`splitUpcomingByType`), the rest behind
  `upcomingShowMore`/`upcomingShowLess`. Empty: `emptyUpcomingTitle`/`Description`.
- **WASTE-R6** A row: type icon in its colour, name, and "Put out today" as a tag today, else
  `dateWithDistanceText` ("Fri, Oct 30 · in 6 days"; today and tomorrow alone); badges
  `movedFromBadge` and `coalescedHint`.
- **WASTE-R7** The row menu follows the origin: series pickups Move (date picker), Skip (confirm
  `skipConfirm`), Restore when moved; one-off pickups Edit and Delete (confirm); imported ones have
  no menu at all.
- **WASTE-R8** Move/skip write the override under the series' original day; restore treats
  `waste_override_not_found` as done. Toast `scheduleSavedNamed`.
- **WASTE-R9** `?type=&date=` (`WasteRoutes.pickup`) expands the rest when needed, scrolls to the
  row or the type's card and highlights it for two seconds (`applyDeepLink`).

### Types and schedules

- **WASTE-R10** Cards for types with schedules or pickups, in `sort_order`: icon, name, Archived
  badge; menu Move up/down (swap `sort_order`, `moveType`), Add schedule. Tap edits.
- **WASTE-R11** Under the card each schedule (`recurrenceSummary`) with Paused and "No upcoming
  pickup"; a type without schedule shows its next one-off/imported pickup or `noSchedulesYet`
  with Add schedule (not archived).
- **WASTE-R12** Types without schedule and pickups share one "No schedule" card with Add schedule
  (`unscheduledTypesHtml`); remaining presets follow as chips (`remainingTypePresets`, by name or
  by icon and colour).
- **WASTE-R13** Type sheet: preset (new only), name (100), icon from `TYPE_ICON_SUGGESTIONS`, colour
  from the palette; Delete (confirm; `waste_type_in_use` says `typeDeleteRefused`), Archive/Restore.
  Differs: icons are drawn as the closest Material Symbols; the picker offers the 40 suggestions,
  not a search over all of Lucide (a stored unknown name is kept).
- **WASTE-R14** Toasts `createdNamed`, `savedNamed`, `deletedNamed`, `typeArchivedNamed`, `typeRestoredNamed`.
- **WASTE-R15** Schedule sheet: type (from the FAB; `defaultScheduleType`), kind (weekly, monthly
  fixed day, monthly nth weekday), weekday chips, day of month or last day, ordinal and weekday
  (anchor recomputed onto the rule, `nearestOrdinalAnchorDateKey`), interval stepper 1-52, first
  pickup, end date (clearable), Active only when editing, Delete (confirm).
- **WASTE-R16** A new weekly schedule follows the first pickup's weekday until the member picks days.
- **WASTE-R17** No weekday: `errorScheduleWeekdaysRequired` at the chips (also from the server).
- **WASTE-R18** A chosen type that went (`waste_selected_type_not_found`) keeps the sheet open and
  reloads (`keepDialogForMissingType`); other gone reasons close it and reload (`reportError`).
- **WASTE-R19** Server reasons map to the app's sentences (`WasteErrors`, `CASE_MESSAGES`), numbers
  from `params` or the server's constants. Differs: `ApiProblem` carries no `params`, so a fetch
  status from a failed call falls back to the general sentence (stored source errors carry theirs).
- **WASTE-R20** Toasts `scheduleSavedNamed`, `scheduleDeletedNamed`.

### One-off pickups

- **WASTE-R21** Sheet: type (active, plus its own), date, note (500); Delete (confirm).
- **WASTE-R22** `waste_pickup_exists` says `pickupDuplicateError`; toasts `pickupSavedNamed`, `pickupDeletedNamed`.
- **WASTE-R23** Editing loads the pickup first (`loadPickupAndEdit`).

### Sources and import

- **WASTE-R24** Rows: link or file icon, name, health badge (needs mapping, error, needs refresh),
  coverage range or `sourceNoCoverage`; the menu has the one action: Review mapping, Check now, or
  Re-import. Empty: `emptySourcesDescription` with Import file.
- **WASTE-R25** Source sheet: name, URL, coverage, last imported, the last failure's sentence,
  the badge; per mapping the type chips (several types per label, none = ignored); Export and
  Import mapping profile; Delete (confirm), the action, Save (rename, then changed mappings).
- **WASTE-R26** Import wizard: name (new), the `.ics` file (at most 5 MB, `errorImportTooLarge`),
  Preview; then the summary, diagnostics (blocking ones with "skip this event"), labels with type
  chips, "Create a new waste type" and its name; Commit. Errors stay in the sheet.
- **WASTE-R27** Commit refuses unresolved blocking diagnostics and missing new-type names before
  any request (`unresolvedBlockingDiagnostics`, `buildMappingDecisions`); a vanished type keeps the
  wizard and turns its chips into "create new" (`refreshImportTypeChoices`).
- **WASTE-R28** A re-import sends `expected_version`; a URL source sends no file and may answer
  `unchanged` (`sourceUnchangedToast`).
- **WASTE-R29** Toast `importCommittedToast` with added, changed, removed.
- **WASTE-R30** URL source sheet: name, https address, interval (60-43 200, default 1440); the
  outcome says committed, needs mapping or the fetch failure.
- **WASTE-R31** Check now: the same outcomes, or `sourceUnchangedToast`.
- **WASTE-R32** Mapping profile export writes `waste-mapping-profile-<name>.json` where the member
  chooses (system "save as"); import reads a JSON file, previews it (entries with status), applies
  with its digest and reopens the source.
- **WASTE-R33** Read-only members see sources and their mappings as text, without the URL and errors (the server redacts them).

### Per member

- **WASTE-R34** Reminders sheet: per type a switch, days before (0-14 stepper) and the time
  (time picker); only changed rows are sent. Toast `reminderSettingsSavedToast`.
- **WASTE-R35** The feed sheet: description, the address (selectable), hint, Copy
  (`wasteFeedCopied`) and Subscribe (`webcal:`); without a feed the way to Settings.
- **WASTE-R36** POSTs carry an idempotency key; offline every write says `common.errorOfflineMutation`.

### Links and tags

- **WASTE-R37** `WasteRoutes.START`, `WasteRoutes.pickup(typeId, date?)` (Calendar's `deep_link`).
- **WASTE-R38** `WasteTagSource`: `#hashtags` in type names (route to the type), one-off pickup
  notes (route to the pickup) and source names (START); nothing when the module is hidden.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| WASTE-A1 | Recurrence summaries of the three kinds with intervals. | unit | passing (`WasteLogicTest`) |
| WASTE-A2 | Upcoming split per type; a deep link into the rest expands. | unit | passing |
| WASTE-A3 | Ordinal anchors lie on the rule; weekday of a day. | unit | passing |
| WASTE-A4 | Unscheduled types, remaining presets, FAB, default type, order swaps. | unit | passing |
| WASTE-A5 | Schedule and pickup bodies; weekly needs a weekday. | unit | passing |
| WASTE-A6 | Occurrence origins: override day, one-off, moved-from. | unit | passing |
| WASTE-A7 | Source badge priority, row action, profile file name, mapping change. | unit | passing |
| WASTE-A8 | Import defaults, blocking diagnostics, decisions, commit body. | unit | passing |
| WASTE-A9 | Error sentences with numbers, gone and selected-type-gone. | unit | passing |
| WASTE-A10 | Tag source items and routes. | unit | passing |
| WASTE-A11 | Upcoming with "Put out today" and "Show 1 more"; schedules and sources with badge. | Robolectric | passing (`WasteScreenTest`) |
| WASTE-A12 | Skip puts an empty override under the series' day. | Robolectric | passing |
| WASTE-A13 | Read-only: no FAB, no row menus, only the feed in the tools. | Robolectric | passing |
| WASTE-A14 | Onboarding: a preset creates its type with a key. | Robolectric | passing |
| WASTE-A15 | The feed link with Copy. | Robolectric | passing |
| WASTE-A16 | Types: create, edit, archive, order, in-use refusal, delete. | contract | written, runs in CI (`WasteContractTest`) |
| WASTE-A17 | Schedules of three kinds, occurrences, move, skip, restore, reasons. | contract | written |
| WASTE-A18 | One-off pickup round trip with duplicate. | contract | written |
| WASTE-A19 | ICS import, source, mappings, profile, re-import, delete. | contract | written |
| WASTE-A20 | Reminder settings per type. | contract | written |
| WASTE-A21 | Feed status, URL check, pickups list. | contract | written |

## Strings

Upstream `waste.*`, `common.*`, `settings.wasteFeed*`, `calendar.dayRangeLabel`, `nav.settings`.
No app-only strings.
