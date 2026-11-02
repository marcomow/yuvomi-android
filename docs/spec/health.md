# Spec: Health

Status: implemented · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0012, 0015

Code: `app/src/main/java/io/github/marcomow/yuvomi/feature/health/`. Pure ports: `HealthVitals`,
`HealthMeds`, `HealthCycle`, `HealthLogic` (activity, labs, prevention, nutrition, fasting, error
sentences) and `HealthForms` (every editor's body). `HealthHub` holds what the areas share,
`HealthAreaViewModel` the load/cache/write/undo rules, `HealthAreas` one view model per area.
Routes, one per web address (`HEALTH_ROUTES` in `utils/health-tabs.js`): `health` (overview,
`HealthRoutes.START`), `health/vitals`, `health/cycle`, `health/fasting`, `health/meds`,
`health/prevention`, `health/labs`, `health/activity`, `health/nutrition`
(`HealthRoutes.area(id)`).

## Purpose

The household's health records as native screens: an overview of what is due today and what
changed, and every area of the web page with recording, editing, deleting, charts and the
person switcher, honouring exactly the server's visibility and caregiving rules.

## Scope

Everything `public/pages/health.js` and `public/pages/health-fasting.js` (with
`components/fasting-controls.js`) let a member do, except what is listed under "Left in the web
app". Not part of these pages and therefore not here: the Settings sheets that manage caregivers
(`settings/pages/admin-family.js`), the personal default visibility (`personal-health.js`) and
the prevention types (`modules-health.js`); the page links to them and so does the app
(`shell.navigate(SettingsRoutes.START)`). The dashboard's health tiles belong to Overview.

### Left in the web app

- **CSV exports** (`/health/export/*`, `/health/export/fasting`, `openHealthExport`): a browser
  download. The overview's top bar opens `/health` in the browser (`shell.openWeb`).
- **Cycle calendar, trend charts and tools**: the month calendar (`buildCycleCalendar`), the
  cycle-length, BBT, symptom and feeling charts, the PMS window, pain summary and heavy-bleeding
  signal, the CSV import (`POST /cycle/import`) and the predicted ICS feed (`/cycle/feed`). The
  cycle screen's top bar opens `/health/cycle` in the browser. The partner reminder
  (`notify_partner_*`) is kept unchanged on every settings save but not editable natively.
- **Fasting extras**: the educational dial (`zone_mode`), the clock display preference
  (`clock_mode`), the history's completion-date filters, "Add completed fast" (backfill) and
  the weekly stats chart.

## Upstream references

- `public/pages/health.js` (`loadHealthPrefs`, `loadVisibilityDefaults`, `loadCareGrants`,
  `readOnly`, `canEditFor`, `cycleCanEdit`, `ownerField`, `loadHealthMembers`,
  `readOnlyBannerMarkup`, `AREA_STATUS`, `overviewGridMarkup`, `overviewBriefMarkup`,
  `renderVitalsShell`, `renderCards`, `renderDetail`, `recentMeasurementsMarkup`,
  `openVitalModal`, `collectVitalBody`, `vitalPatchBody`, `renderMedsShell`, `dueTodayMarkup`,
  `prnRowMarkup`, `handlePrnDose`, `sendScheduledDose`, `acknowledgeDose`, `adherenceMarkup`,
  `medLogHistoryMarkup`, `openMedLogModal`, `openMedModal`, `collectMedBody`,
  `renderSchedEditor`, `renderLabsShell`, `openLabModal`, `renderResultEditor`,
  `renderActivityShell`, `openActivityModal`, `renderPreventionShell`, `openPreventionModal`,
  `renderNutritionShell`, `openNutritionModal`, `openNutritionTargetModal`, the cycle panel,
  `reportError`, `deleteRowWithUndo`).
- `public/pages/health-fasting.js`, `public/components/fasting-controls.js`.
- `public/utils/health-tabs.js`, `health-person.js`, `health-person-switcher.js`,
  `health-vitals.js`, `health-meds.js`, `health-overview.js`, `health-cycle.js`,
  `health-labs.js`, `health-activity.js`, `health-prevention.js`, `health-nutrition.js`,
  `health-fasting.js`, `friendly-error.js`.
- `server/routes/health.js`, `server/routes/health/*.js`, `server/services/fasting.js`,
  `prevention-due.js`, `health-nutrition.js`.

## Upstream operations

All under `/api/v1/health` unless noted; reads pass `user_id` for the chosen person, writes for
a person in the member's care carry `user_id` in the body (`ownerField`), own writes never.

| Method | Path | Used for |
|---|---|---|
| GET | `/family/members` | the person switcher (`MealsOperation.FAMILY_MEMBERS`) |
| GET | `/preferences` | `health_cycle_effective` (core `Operation.PREFERENCES`) |
| GET | `/caregivers/me` | ids the member may record for |
| GET | `/visibility-defaults` | default visibility per area (sparse, missing = private) |
| GET, POST | `/vitals` | list; record (201, `Idempotency-Key`) |
| PATCH, DELETE | `/vitals/{id}` | edit; delete (204); 404 `health_vital_not_found`; 400 `health_vital_out_of_range`, `health_vital_bp_order` |
| GET, POST | `/medications` | list with `include=schedules,logs&logs_from&logs_to`; create with `schedules` (201) |
| PATCH, DELETE | `/medications/{id}` | edit; delete; 404 `health_medication_not_found` |
| POST | `/medications/{id}/schedules` | add a time slot to an existing medication |
| DELETE | `/schedules/{id}` | remove a slot; 404 `health_schedule_not_found` |
| POST | `/medications/{id}/logs` | book a planned or as-needed dose with `stock_deduct`; answers `stock_deducted` |
| POST | `/logs/{id}/take`, `/logs/{id}/skip` | book a row the scheduler made |
| POST | `/logs/{id}/undo` | the snackbar's undo with `stock_credit`; 409 `health_dose_pending` |
| PATCH, DELETE | `/logs/{id}` | correct an entry; delete one without schedule (409 for scheduled); 404 `health_dose_not_found` |
| GET | `/fasting/state`, `/fasting/history`, `/fasting/stats` | the fasting area (history pages by cursor) |
| POST | `/fasting`, `/fasting/{id}/finish` | start (with `acknowledge_safety`), finish |
| PATCH, DELETE | `/fasting/{id}` | edit, undo the end (`end_at: null`), delete; all with `expected_revision`; 409 `FASTING_REVISION_CONFLICT` |
| PUT | `/fasting/settings` | goal (with `active_id`), reminders |
| GET, POST | `/cycle/periods` | list; add |
| PATCH, DELETE | `/cycle/periods/{id}` | edit, end today; delete; 404 `health_cycle_period_not_found` |
| GET, POST | `/cycle/logs` | list; save a day (upsert by date) |
| DELETE | `/cycle/logs/{id}` | delete a day; 404 `health_cycle_log_not_found` |
| GET, PUT | `/cycle/settings` | own settings; PUT replaces every field |
| PATCH | `/cycle/visibility` | "apply to all entries" |
| GET | `/prevention/types`, `/prevention/records`, `/prevention/due` | the prevention area |
| POST, PATCH, DELETE | `/prevention/records[/{id}]` | add, edit, delete; 400 `health_prevention_selected_type_not_found` |
| GET, POST | `/labs` | reports with their results; add |
| PATCH, DELETE | `/labs/{id}` | edit the head; delete |
| POST | `/labs/{id}/results` | add an analyte |
| PATCH, DELETE | `/results/{id}` | edit, delete an analyte |
| GET, POST, PATCH, DELETE | `/activities[/{id}]` | the activity area |
| GET, POST, PATCH, DELETE | `/nutrition/entries[/{id}]` | the nutrition diary |
| GET | `/nutrition/summary` | today's totals and target (target only for own and in care) |
| PUT | `/nutrition/targets` | the daily target; all empty deletes it |

A 404 without `reason` on these routes means the operation is gone (ARCHITECTURE.md rule 6).

## Requirements

Status: all requirements below are built. Where the app knowingly differs from the web page, the
requirement says so ("Differs:").

### Shared

- **HEALTH-R1** Health sits in "More" (`Placement.MORE`), permission key `health`. The overview's
  "All categories" lists the areas in `HEALTH_AREAS` order; cycle only when
  `health_cycle_effective` is not false, fasting only for admins or with the capability
  `health_use_fasting: allow` (`canUseFasting`).
- **HEALTH-R2** One person for the whole module (`health-person.js`): the switcher
  (`personSwitcherMarkup`) lists the member first ("· You"), then the household; it is hidden for
  a household of one. The choice holds in every area for the app's lifetime and falls back to
  the member when the person left the list. In the own view the menu offers "Who can see this?"
  (`health.whoSees`), which opens Settings.
- **HEALTH-R3** Writing is allowed for own data and for a person in care (`/caregivers/me`),
  never with `health: read` (`canEditFor`); the cycle only for own data (`cycleCanEdit`).
  Without the right, every editing affordance is hidden, not disabled. Another person's view
  shows `health.readOnlyBanner`, or `health.careBanner` when recording for them.
- **HEALTH-R4** Every form's visibility starts at the member's default for that area
  (`defaultVisibility`, `vital:<type>` per metric); nutrition writes `all` for the open value.
- **HEALTH-R5** Creates send an `Idempotency-Key`; dose bookings keep their key across a retry
  while the outcome is unknown (network, 408, 429, 5xx, 409 `idempotency_in_progress`) for two
  minutes (`createDoseAttempts`).
- **HEALTH-R6** Failures read as `errorSentence`: the `reason` table of `friendly-error.js` for
  every health reason; offline `common.errorOfflineMutation`; 403 `common.errorFormExpired`,
  `common.errorModuleNoAccess` or `common.errorNoPermission`; 429 `common.errorRateLimited`;
  else the area's own sentence. A "gone" reason reloads the area (`reportError`).
- **HEALTH-R7** Deletes hide the row and offer undo; the `DELETE` goes out when the snackbar
  ends without undo (`deleteRowWithUndo`). Medications and lab reports ask first
  (`health.meds.deleteConfirm`, `health.labs.deleteConfirm`) as the web page does.
- **HEALTH-R8** Offline (ADR 0007): the last answer of an area is shown read-only under the
  offline banner; writes say `common.errorOfflineMutation`. Only the member's **own** data is
  cached; another person's area is network-only, so a copy cannot outlive a change of
  visibility or a revoked care grant. Differs: the PWA caches nothing of `/health`.

### Overview

- **HEALTH-R9** The overview (`renderOverviewShell`): the brief (`overviewBriefMarkup`), today's
  planned doses with the check ring and a skip menu, as-needed medications with their
  countdown, "All categories" with each area's status line (own view only, `AREA_STATUS`), the
  latest value per measured vital, adherence over seven days with the streak, the cycle tile
  (next period or pregnancy week) when the cycle is on, the doses still open today, and the
  disclaimer. The FAB menu (`overviewCaptureItems`) opens the vitals or activity editor, the
  medications, or the cycle (own view). Differs: the phone layout's "Show all values" fold is
  not needed in a native list.

### Vitals

- **HEALTH-R10** Nine kinds (`VITAL_METRICS`): blood pressure (systolic/diastolic/pulse), glucose,
  weight, height, head circumference, SpO2, temperature, sleep (hours and minutes) and mood
  (five steps).
- **HEALTH-R11** One period row (day, week, month, year) and a stepper over cards and chart; the
  current week and month roll (last 7/30 days), others are calendar periods
  (`buildVitalBuckets`, `stepVitalAnchor`). Weeks start on Monday.
- **HEALTH-R12** A card per measured kind with the latest value in the most frequent unit
  (values converted, `vitalDisplayUnit`) and the change to the reading before; kinds without a
  value sit behind "More measurements".
- **HEALTH-R13** The selected kind's chart (Canvas, ADR 0012): bucket averages, the pulse on its
  own axis, a day reading by reading, a minimum span per unit; "Too few readings" under two
  points. Below it the eight newest readings, which open the editor.
- **HEALTH-R14** The editor (`openVitalModal`): fields per kind, date and time, note,
  visibility; the plausibility line (`vitalPlausibility`): the impossible and a systolic not
  above the diastolic stop the save, the unusual only warns. Create sends `collectVitalBody`,
  edit `vitalPatchBody` (cleared channels and note as null, the unit only when changed).
- **HEALTH-R52** Units (2.84.0, #2375, `utils/vital-units.js`): the dialog still offers only the
  listed units, but the hard and soft limits are read on the converted value
  (`vitalLimitReading`): another spelling of a listed unit ("LB", "lbs") counts as it, a unit
  without limits of its own ("g", "stone") is converted to the kind's first unit. Weights in
  stone also convert in cards and chart. Editing judges the hard limits only when the patch
  changes the measurement (`vitalMeasureChanged`: a value or the sent unit), so a legacy row
  outside them still takes a note, as on the server. The server refuses any other unit with
  `health_vital_unit_invalid` (upstream's sentence, FriendlyErrorTable).
- **HEALTH-R15** Delete from the editor, with undo (`deleteVitalWithUndo`).
- **HEALTH-R16** An empty area shows `health.vitals.emptyTitle` with the add action for writers.

### Medications

- **HEALTH-R17** "Due today": planned doses of active medications for the household's day
  (`computeDueDoses`, weekday mask, start and end), each with its state (taken, skipped,
  open); writers tap the ring to take and use the row menu to skip.
- **HEALTH-R18** A dose is ONE request with the stock deduction (#2069): a row the scheduler
  made is booked by `/logs/{id}/take|skip`, otherwise `POST /medications/{id}/logs` with
  `scheduled_at`, `schedule_id`, `dose_qty`, `taken_at` (household wall time) and
  `stock_deduct`. The snackbar names the medication (`health.meds.doseTakenNamed`) and its undo
  sends `/logs/{id}/undo` with what the booking really deducted.
- **HEALTH-R19** As needed (`prnDoseState`): the next allowed time and the time left, or "Available
  now" with the last dose, or "Not taken yet"; taking early asks first
  (`health.meds.prn.earlyConfirm`).
- **HEALTH-R20** Adherence over seven days counts planned doses of active medications only
  (`computeAdherence`); the overview adds the streak (`computeAdherenceStreak`).
- **HEALTH-R21** The intake log: the ten newest entries; writers open one to correct status and
  time (`PATCH /logs/{id}`); only an entry without a schedule can be deleted.
- **HEALTH-R22** "All medications": name, inactive and as-needed badges, dosage and form, stock
  with "Refill" or "Empty" (`refillState`).
- **HEALTH-R23** The editor (`openMedModal`): name (required), dosage, form, active, as needed
  with interval and dose, the schedule, and under "More details" stock, unit, threshold and
  note; visibility. A NEW medication sends its slots in the same request (#2054); an existing
  one adds and removes slots at once.
- **HEALTH-R24** Delete asks (`health.meds.deleteConfirm` with its detail).
- **HEALTH-R25** The log window is read once (29 days) and cut per medication to its own window
  (`medLogWindowDays`, `splitMedPlans`).

### Fasting

- **HEALTH-R26** The fasting area reads `/fasting/state` and `/fasting/stats` (`user_id` only for
  another person) and shows the running clock, the start, the goal with the time left and
  target, or the time since the last fast.
- **HEALTH-R27** Writing is the own view only, with `health: write` and the capability
  (`requireFastingWrite`); another person's journal is read-only (`health.fasting.readOnly`).
- **HEALTH-R28** Start asks the safety note first until acknowledged (`acknowledge_safety`);
  finish saves the end and offers "Undo end" (`PATCH end_at: null`).
- **HEALTH-R29** Goal presets and "No goal" set the default and the running fast's goal in one
  write; the two reminders toggle.
- **HEALTH-R30** Statistics: all time, this year, last 30 days, current and longest streak.
- **HEALTH-R31** History pages by cursor ("Load 10 more"); a row opens the editor (start, end,
  rating, note, delete with undo); the running fast's start is editable ("Edit start").

### Cycle

- **HEALTH-R32** Own data only for writes; another person's family-visible periods and days are
  shown read-only. The cycle area is absent when the cycle is off.
- **HEALTH-R33** The status (`predictCycle`): cycle day and phase, next period with countdown (a
  range in perimenopause mode), fertile window and ovulation (confirmed by a temperature shift),
  hormonal contraception pausing the window, averages, regularity and their source; the
  pregnancy week and due date in pregnancy mode.
- **HEALTH-R34** Today: "Period started" or "Period ended" (`endPeriodToday`), "Log day".
- **HEALTH-R35** History: every period with its cycle length and heaviest flow; "Add past
  period"; the editor warns about a future start and overlaps, refuses an end before the start.
- **HEALTH-R36** The day editor: flow, symptoms with intensity, feelings, basal temperature
  (34-42 °C, 93-108 °F), cervical mucus, LH and pregnancy tests, intimacy (owner-only), note,
  visibility; one entry per day, delete with undo.
- **HEALTH-R37** Settings: averages, luteal phase, fertile window, PMS, contraception,
  perimenopause, pregnancy with due date, period reminder, daily reminder, default visibility;
  every field goes back on save. "Apply to all entries" confirms, then
  `PATCH /cycle/visibility`.
- **HEALTH-R38** Recent day entries (14) list flow, symptoms, feelings and temperature.

### Prevention, labs, activity, nutrition

- **HEALTH-R39** Prevention: "Due / overdue" sorted by days left with overdue, soon (7 days) and
  later tones; records grouped by type or name, newest first, with dose, provider, batch, note.
- **HEALTH-R40** The prevention editor (`collectPreventionBody`): type or free name, date, dose
  number, provider, batch, interval in months or years (1-600 months), next due override,
  reminder lead time (0-365), note, visibility; delete with undo.
- **HEALTH-R41** Prevention types are managed in Settings (admins), not here.
- **HEALTH-R42** Labs: the trend of one analyte across reports with its reference range shaded
  (`analyteTrend`, `defaultTrendAnalyte`); reports with analyte count and abnormal badge.
- **HEALTH-R43** A report opens its analytes with value, unit, reference and flag; a tap shows
  that analyte's trend.
- **HEALTH-R44** Writers edit the head (date, lab, note, visibility), add, edit and delete
  analytes (flag derived from the range, `deriveFlag`), delete the report after asking. A new
  report opens its analytes right after saving.
- **HEALTH-R45** The visibility of analytes follows their report.
- **HEALTH-R46** Activity: a week stepper (rolling while in the current week,
  `stepActivityAnchor`), totals, a bar chart of minutes per weekday, the week's log.
- **HEALTH-R47** The activity editor (`collectActivityBody`): type or custom type, date and time,
  duration, distance, calories, intensity, note, visibility; delete with undo.
- **HEALTH-R48** Activity rows show type, time, intensity and figures.
- **HEALTH-R49** Nutrition: today's totals per nutrient against the target with a progress bar
  (`nutrientProgress`: a 0 target is passed by anything); the target is edited by the owner or a
  caregiver only.
- **HEALTH-R50** The diary: every entry with time and meal; the editor (`collectNutritionBody`):
  name, date and time, meal, eight nutrients (empty = not tracked, negative refused), note,
  visibility (private or all); delete with undo.
- **HEALTH-R51** The target editor sends all eight values; all empty removes the target.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| HEALTH-A1 | Given weights in kg and lb then the week series converts to kg, buckets seven rolling days and gives the delta; month and year stepping walk rolling and calendar windows. | unit | passing (`HealthLogicTest`) |
| HEALTH-A2 | Given readings then hard limits, soft ranges and the blood pressure order match `vitalPlausibility`; durations, mood and the brief read as the web app. | unit | passing (`HealthLogicTest`) |
| HEALTH-A27 | Given a legacy row in g or stone then the limits apply to the converted value, an unchanged edit is not judged, and stone converts to kg. | unit | passing (`HealthLogicTest`) |
| HEALTH-A3 | Given the vitals form then create sends `user_id` only for someone else and the patch clears emptied fields and keeps the unit out. | unit | passing (`HealthLogicTest`) |
| HEALTH-A4 | Given schedules then due doses follow the mask, and inactive medications drop out. | unit | passing (`HealthLogicTest`) |
| HEALTH-A5 | Given logs then adherence counts planned doses only and the streak needs every dose of a day. | unit | passing (`HealthLogicTest`) |
| HEALTH-A6 | Given an as-needed medication then the countdown and the refill state match `prnDoseState`, `refillState`. | unit | passing (`HealthLogicTest`) |
| HEALTH-A7 | Given doses and the medication form then the bodies carry `stock_deduct`, slots with `days_mask` and the owner. | unit | passing (`HealthLogicTest`) |
| HEALTH-A8 | Given a dose attempt with an unknown outcome then the retry reuses the key; after a clear answer it does not. | unit | passing (`HealthLogicTest`) |
| HEALTH-A9 | Given five periods then stats, phase, next start, ovulation, contraception and pregnancy match `predictCycle`. | unit | passing (`HealthLogicTest`) |
| HEALTH-A10 | Given the day and settings forms then every field is sent and invalid temperatures refused. | unit | passing (`HealthLogicTest`) |
| HEALTH-A11 | Given lab reports and prevention records then flags, the default analyte, due texts and bodies match. | unit | passing (`HealthLogicTest`) |
| HEALTH-A12 | Given activities and nutrition then the week, totals, progress, target body and error sentences match. | unit | passing (`HealthLogicTest`) |
| HEALTH-A13 | Given a scripted server then the overview shows today's dose, the areas with cycle and fasting, the latest vitals, and loads plans and logs in one request. | ui | passing (`HealthScreenTest`) |
| HEALTH-A14 | Given a due dose when the ring is tapped then one POST with key and `stock_deduct` goes out, and undo sends `stock_credit`. | ui | passing (`HealthScreenTest`) |
| HEALTH-A15 | Given `health: read` then no dose action and no capture FAB show and nothing is written. | ui | passing (`HealthScreenTest`) |
| HEALTH-A16 | Given the vitals area when another person is chosen then vitals load with `user_id` and show read-only. | ui | passing (`HealthScreenTest`) |
| HEALTH-A17 | Given a care grant when the person in care is chosen then the overview says so, the choice holds in vitals, and recording is offered. | ui | passing (`HealthScreenTest`) |
| HEALTH-A18 | Given the seed as alex then `/caregivers/me` lists the children and the defaults decode. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A19 | Given vitals then create (idempotent), list, patch, the limit reasons, delete and the gone reason answer as expected; linda's private rows stay hidden, recording for her is a 403, recording for emma works. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A20 | Given a medication with schedules then dose, undo with stock, take, skip, correct, the 409 on deleting a scheduled entry, an as-needed dose, slots and delete answer as expected. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A21 | Given fasting then create, update with revision (and its conflict), history, stats, start, finish, undo of the end, delete and settings answer as expected. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A22 | Given the cycle then periods, the day upsert with owner-only fields, bulk visibility, settings and deletes answer as expected. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A23 | Given prevention then records, the due list of a typed record and the unknown-type reason answer as expected. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A24 | Given a lab report then analytes are added with a derived flag, edited, deleted, and the report deleted. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A25 | Given an activity then create, edit to a custom type and delete answer as expected. | contract | written, runs in CI (`HealthContractTest`) |
| HEALTH-A26 | Given nutrition then an entry adds to today's summary, edits, and the target is set and removed. | contract | written, runs in CI (`HealthContractTest`) |

## Strings

Upstream keys from `health`, `common`, `newLabel` (`healthVitals`, `healthActivity`, ...),
`nav`, `modal.moreDetails`, `rrule.unitMonths`/`unitYears`, `datepicker.clear` and
`android.browser.open`. No app-only strings: `app/src/main/assets/i18n-android/health/en.json`
stays empty.
