# Spec: Housekeeping

Status: built · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's paid help as a native screen: check a housekeeper in and out, see the month's
visits and payments, keep the chores that come round every few days, read and settle each
visit's report, and (for admins) keep the housekeepers' profiles.

## Scope

Everything `public/pages/housekeeping.js` offers, on its four tabs: Visits (`dashboard`),
Chores (`tasks`), Reports (`reports`) and Housekeepers (`staff`), plus the `?editVisit=` link.

**Out:** nothing of the page stays in the browser. Upstream's other housekeeping routes
(`/summary`, `/work-sessions`, `/worker`, `/supply-requests`, `/maintenance-log`) are not called
by the page and not by the app. Differences are named "Differs:" below.

## Upstream references

- `public/pages/housekeeping.js` (all functions cited below), `public/utils/friendly-error.js`,
  `public/utils/money.js` and `amount-field.js` (amount fields), `public/permissions.js`
  (`canSeeHousekeepingAmounts`), `public/utils/color.js` (`USER_COLORS`).
- `server/routes/housekeeping.js` (`publicWorker`, `publicSession`, `publicDecayTask`,
  `visitCapabilities`, `receiptAccess`, `housekeepingDashboard`), `server/services/housekeeping-billing.js`.

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/housekeeping/dashboard?timezone=` | `data` | month glance, payments chart (`null` without amounts) |
| GET | `/housekeeping/decay-tasks` | `data` | chores, sorted by urgency by the server |
| GET | `/housekeeping/visits?month=&worker_id=` | `data: { month, visits, totals }` | recent visits, the report month, a person's log |
| GET | `/housekeeping/visits/{id}` | `data` | the `?editVisit=` link |
| PUT | `/housekeeping/visits/{id}` | `data` | edit date, rate or minutes, extras, receipt |
| DELETE | `/housekeeping/visits/{id}` | `data` | delete (after the undo window) |
| POST | `/housekeeping/visits/{id}/pay` · `/unpay` | `data` | settle a visit, take it back (admins) |
| GET | `/housekeeping/task-templates` | `data` | suggested chores (`key` names the translation) |
| GET | `/housekeeping/workers?timezone=` | `data`, `can_manage` | people, open and today's session |
| POST | `/housekeeping/worker` | `data`, 201/200 | create (no `id`) or edit a profile; admins only |
| POST | `/housekeeping/work-sessions/check-in` · `/check-out` | `data` | check in and out |
| POST | `/housekeeping/work-sessions/{id}/reopen` | `data` | undo a check-out (10 minutes) |
| POST | `/housekeeping/decay-tasks` | `data`, 201 | create a chore |
| PATCH | `/housekeeping/decay-tasks/{taskId}` | `data` | edit; `last_completed` for the undo |
| POST | `/housekeeping/decay-tasks/{taskId}/complete` | `data` | done now |
| DELETE | `/housekeeping/decay-tasks/{taskId}` | `data: null` | delete (after the undo window) |
| GET | `/preferences` (core) | `data.currency` | the household currency, default EUR |
| POST | `/documents` (DocumentsOperation) | `data.id` | the payment receipt of a visit |

Error reasons read: `housekeeping_worker_not_found`, `housekeeping_visit_not_found`,
`housekeeping_task_not_found`, `housekeeping_open_session_not_found`,
`housekeeping_already_checked_in`, `housekeeping_visit_open`, `housekeeping_reopen_expired`,
`housekeeping_username_taken`, `housekeeping_username_invalid`, `amounts_hidden` (403).

## Requirements

Status: all requirements below are built.

### Page and loading

- **HOUSE-R1** Top bar `housekeeping.title`; tabs `housekeeping.tabVisits`, `tabChores`,
  `reports`, `staffTitle` (`renderShell`). A link may name the tab.
- **HOUSE-R2** `loadData()`: dashboard, chores, the current month's visits, templates, workers
  and the currency in parallel, with `timezone` = the household zone (`dayTimeZone`). Only the
  latest load writes the page (`loadDataSeq`). The answer is cached and shown at once on the next
  open; a network failure keeps it read-only under `OfflineBanner` (ADR 0007). A first load that
  fails shows `housekeeping.loadError` with retry. Pull to refresh, and on resume after 60 s.
- **HOUSE-R3** Read-only members (`housekeeping: read`) see every value and no write affordance
  (`readOnly()`, hidden not disabled). Offline, every write says `common.errorOfflineMutation`.
- **HOUSE-R4** Amounts (R20): without `housekeeping_view_amounts` (admins always have it) or with
  a `null` from the server, no amount is shown, never "0.00" (`amountsHidden`).

### Visits tab (renderDashboard, renderWorkerSummary)

- **HOUSE-R5** Without housekeepers: empty state `noWorkerTitle` with `noWorkerHint` and
  "Create profile" for those who may manage, else `noWorkerHintAdminOnly`.
- **HOUSE-R6** One card per housekeeper: avatar, name, and the line: "Checked in at", "Here
  today: from - to", "Visit recorded at", the rate (daily with schedule, or hourly), or
  `notHereToday` without amounts. The button follows the open session: Check out, Check in
  again after a finished visit today, else Check in (#1133, #1138).
- **HOUSE-R7** Check-in sends `worker_id`, `daily_rate` (0 for hourly), `extras: 0`, `timezone`,
  and the event and payment texts (`visitTextPayload`); toast `checkedInNamed`. Check-out sends
  `worker_id`; toast `checkedOutNamed` with Undo, which reopens the session. Both reload.
- **HOUSE-R8** Read-only: an open session shows the sign `dashboard.housekeepingPresent`, no button.
- **HOUSE-R9** The month glance `visitsThisMonth` with "Due" and "Done this month" when non-zero;
  "Recent visits" (five, then `allVisitsInReport` to the Reports tab); the payments section with
  `pendingPayments`, a chart from two months with payments (paid solid, pending tinted, value
  over the bar, `chartMoney`), else `paymentsMonthWithOpen`/`AllPaid` or `noPaymentData`.
  Differs: the chart is drawn with Compose boxes, not CSS.

### Chores tab (renderTasks, taskRowHtml)

- **HOUSE-R10** Rows in the server's order: a ring to complete (`completeTask` label), name,
  "Overdue"/"Due today" coloured when due, area and `everyDays`. Empty: `noTasks` with Add.
- **HOUSE-R11** Tapping the row opens the editor (writers only). Swipe start completes, swipe
  end deletes; both spring back.
- **HOUSE-R12** Complete: `POST /complete`, toast `taskDoneNamed` with Undo, which PATCHes
  `last_completed` back to the previous value (null for never) and says `taskUndoneToast`.
- **HOUSE-R13** Delete: the row goes at once, toast `taskDeletedToast` with Undo; the DELETE runs
  after the toast; "already deleted" (`housekeeping_task_not_found`) is no error.
- **HOUSE-R14** FAB `addTask` (writers): a sheet with the templates not yet a chore by name
  (translated through `taskTemplateData.<key>`, one tap adds), and the custom form: name
  (200), area with the known areas as suggestions (100, `knownAreas`), every N days (≥ 1).
- **HOUSE-R15** The editor: name, area, frequency; Save PATCHes, `taskUpdatedToast`; Delete runs
  R13. A failed save keeps the sheet with its input.
- **HOUSE-R16** Create says `taskCreatedToast` and reloads; a double tap adds once.

### Visit rows, report and editor (visitRowHtml, openVisitReportModal, openVisitEditModal)

- **HOUSE-R17** One row grammar on all tabs: the date (weekday on Visits and the log, day and
  month on Reports), a "Pending" tag when unpaid, and the meta: person (not in a person's log),
  amount (or the time span without amounts), and the paid state with "only an admin" where
  settled (`visitPaymentMeta`). "Mark paid" sits in the row where the server allows
  (`can_mark_paid`); the menu holds Edit (`can_edit`), Mark paid (only without amounts) and
  Delete (`can_delete`).
- **HOUSE-R18** Tapping a row opens the report sheet: person and schedule, date and time,
  minutes and computed amount (hourly) or daily rate, extras, total, status, payment task and
  calendar event numbers, the receipt (name, or `documentAttach.lockedPrivate` when masked;
  nothing at `documents: none`). Footer: Edit, and Mark paid or Undo payment as allowed.
- **HOUSE-R19** Paying asks first (`markPaidConfirm`, detail naming the payment task when there
  is one); undoing asks `markUnpaidConfirm`. Toasts `visitPaidNamed`/`visitUnpaidNamed`; reload.
- **HOUSE-R20** Deleting a visit: the row goes, toast `visitDeletedNamed` with Undo, DELETE after.
- **HOUSE-R21** The editor: the date (date picker, the household day of `check_in`, sent with
  `original_date`), minutes with the live computed amount (quarter hours) for hourly visits or the
  daily rate, extras; amounts as text (grouping refused, currency precision, untouched stays).
  Hidden without amounts. Event and payment texts are sent again.
- **HOUSE-R22** The receipt: with `documents: write` a file picker (png, jpeg, webp, pdf, txt,
  csv; at most 5 MB, else `documents.fileTooLarge`) uploads to Documents first (`finance`,
  family, folder `housekeeping`, name `receiptDocumentName`), then the visit links it; read shows
  the name; a masked receipt is never replaced. A failed save keeps the sheet open.

### Reports tab (renderReports)

- **HOUSE-R23** A month stepper (previous, month, next, "Current" outside the current month).
- **HOUSE-R24** The glance: pending payments with visits and paid; without amounts the number
  of visits. The rows (R17); empty: `noVisitReports` or `noVisitReportsInMonth`.
- **HOUSE-R25** The stepped month survives every reload (#1137); a failed step returns to the
  month shown and says the load sentence (#1174); a later step wins over an earlier answer.
- **HOUSE-R26** "All visits in the report" from the Visits tab lands here.

### Housekeepers tab (renderStaff)

- **HOUSE-R27** Rows with avatar, name, phone or e-mail; tap selects and shows "{{name}} visits"
  with `staffLogHint`, a month stepper and the rows (R17, without the person). Differs: the
  month is a stepper, not an `<input type="month">`.
- **HOUSE-R28** Admins with write access (`can_manage`) get Edit on each row and the FAB
  `addWorker`; everyone else gets the read view (`openStaffReadModal`): only fields with a value,
  the rate only with amounts, colours with their value.
- **HOUSE-R29** The profile sheet: picture (picked, centre-cropped to 256 px JPEG; Differs: no
  crop dialog), name (128), username (3-64, `[a-zA-Z0-9._-]`), billing type, the rate of that type,
  payment schedule; "Contact, birthday, colors, notes" opens phone, e-mail, birthday with "Year
  unknown", calendar and profile colours (the household palette) and notes (5000). The rate of the
  other type is sent as stored. Taken or invalid usernames say their sentence.

### Links, errors, tags

- **HOUSE-R30** `HousekeepingRoutes.visit(id)` (`?editVisit=`): the editor where `can_edit` and
  writable, the report otherwise; a failure says its sentence, with Retry when transient.
- **HOUSE-R31** Errors use the app's sentences (`errorSentence`, `HousekeepingErrors`); a gone
  target or "already checked in" reloads the page (`reportWriteError`).
- **HOUSE-R32** POSTs carry an idempotency key.
- **HOUSE-R33** `HousekeepingTagSource`: `#hashtags` in a chore's name and area (route
  `HousekeepingRoutes.task(id)`) and a housekeeper's name and notes (`HousekeepingRoutes.worker(id)`);
  nothing when the module is hidden.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| HOUSE-A1 | Amounts hidden without the right or a value; admins see them. | unit | passing (`HousekeepingLogicTest`) |
| HOUSE-A2 | Amount fields: grouping, invalid, precision, untouched, decimal comma. | unit | passing |
| HOUSE-A3 | The hourly amount rounds minutes to the quarter hour. | unit | passing |
| HOUSE-A4 | The session line and the check button follow open and today's sessions. | unit | passing |
| HOUSE-A5 | Chart bars: share, minimum, empty months, paid share; paid months. | unit | passing |
| HOUSE-A6 | Known areas and the templates still offered; the chore form's checks. | unit | passing |
| HOUSE-A7 | Payment meta, masked receipts, month steps over the year. | unit | passing |
| HOUSE-A8 | Worker, visit and check-in bodies. | unit | passing |
| HOUSE-A9 | Error sentences, reloads and "already deleted". | unit | passing |
| HOUSE-A10 | The tag source's items and routes. | unit | passing |
| HOUSE-A11 | The Visits tab shows check-out, the glance and payments; reads carry `timezone`. | Robolectric | passing (`HousekeepingScreenTest`) |
| HOUSE-A12 | Check-out posts the worker with a key and offers Undo. | Robolectric | passing |
| HOUSE-A13 | A chore completes from its ring and says so. | Robolectric | passing |
| HOUSE-A14 | Read-only: presence sign, no ring, no FAB, no pay, no writes. | Robolectric | passing |
| HOUSE-A15 | Without amounts: no payments, visits counted, the month steps back. | Robolectric | passing |
| HOUSE-A16 | The page's reads decode the seed. | contract | written, runs in CI (`HousekeepingContractTest`) |
| HOUSE-A17 | A profile is created and edited; username reasons. | contract | written |
| HOUSE-A18 | Check in, out, reopen and their conflicts. | contract | written |
| HOUSE-A19 | A visit is edited with a receipt, paid, unpaid and deleted. | contract | written |
| HOUSE-A20 | A chore is created, completed, restored, edited and deleted. | contract | written |
| HOUSE-A21 | A child reads without amounts and cannot manage profiles. | contract | written |

## Strings

Upstream `housekeeping.*`, `common.*`, `documents.*`, `documentAttach.lockedPrivate`,
`dashboard.housekeepingPresent`, `birthdays.yearUnknown`, `calendar.dayRangeLabel`. App-only
(`assets/i18n-android/housekeeping/`): `android.housekeeping.usernameTaken` and
`usernameInvalid`, the texts of upstream's `join.usernameTaken` and `setup.errorUsernameInvalid`,
whose namespaces the app does not ship.
