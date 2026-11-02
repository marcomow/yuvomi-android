# Spec: Budget

Status: implemented · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

Code: `app/src/main/java/io/github/marcomow/yuvomi/feature/budget/`. Pure ports: `Money`
(`utils/money.js`, `utils/amount-field.js`), `RegionPresets` (`settings/region-presets.js`),
`BudgetLedger` (list, chart, loans, statistics helpers), `BudgetText` (labels, `budgetError`,
`loanSaveError`), `BudgetForms` (the four forms and their bodies). `BudgetViewModel` holds the
page; `BudgetScreen` the frame and tabs; one file per tab and editor. Routes:
`budget?tab={tab}` (the page, also `BudgetRoutes.START` and `BudgetRoutes.tab(...)`),
`budget/entry`, `budget/transfer`, `budget/account`, `budget/loan`, `budget/categories`.
Subscriptions (`budget/subscriptions`) and Split expenses (`budget/split`) are their own
packages; this feature registers them and its tab row opens them.

## Purpose

The household budget as a native screen: the month's balance, categories and ledger; entries,
series, expected bookings and receipts; accounts and transfers; loans; the plan; statistics.

## Scope

Everything `public/pages/budget.js`, `budget-stats.js` and `budget-plans.js` let a member do,
with `components/budget-transfer-dialog.js`, `budget-recurrence-fields.js`,
`category-manager.js` (basePath `/budget/categories`) and `document-attach.js`. Not here: the
Subscriptions and Split expenses tabs (own packages). Differs/left out, see BUDGET-R30.

## Upstream operations

`BudgetOperation` (42) and `upstream/operations-used.d/budget.txt`: `/budget` (list, month,
`q=` search, `loan_id=`), create/update/delete, `/{id}/confirm`, `/{id}/series` (PUT, DELETE),
`/summary`, `/export`, `/meta`, `/categories` (+ reorder, `{key}`, subcategories, their reorder
and `{subKey}`), `/accounts`, `/transfers` (+ `{id}`, `{id}/series`), `/loans` (+ `preview`,
`{id}`, `{id}/payments`, `{id}/payments/{paymentId}`), `/plans`, `/plans/{category}`, `/stats`;
receipts: `GET/POST /documents`, `GET /documents/{id}/preview|download`. Shared: `GET
/preferences` (core) and `GET /family/members` (Meals' `FAMILY_MEMBERS`). Upstream's budget
routes answer a missing row with a 404 without `reason`; this module treats such a 404 as "not
found" (`common.errorNotFound`, `loadErrorSentence`), never as an unsupported operation.

## Requirements

### Money and the frame

- **BUDGET-R1** Amounts are formatted by `Money` with the household's number locale
  (`numberLocaleFor(region, currency, date_format, time_format)` from `/preferences`, else the UI
  language) and currency (or the account's, #2302), with the four roles of `money.js`: `flow`
  signed except zero, `total` unsigned, `balance` signed when negative, `plain`.
- **BUDGET-R2** Amount fields follow `amountInputProblem(..., decimalComma)`: grouped, invalid,
  not positive, more decimals than the currency (unless the untouched original), with
  `common.amount*` sentences; the currency symbol stands as the field's unit.
- **BUDGET-R3** Header: title, the period stepper (prev, label, next, "Current" only when the
  current period is off screen) on Overview, Plan and Statistics (with week/month/year), the
  quiet note on Accounts and Loans; Mine/Household segments in the personal budget mode. Tabs in
  the web page's order; Subscriptions and Split open their screens. A split guest goes straight
  to Split. FAB per tab (`TAB_CAPS.add`), none on Statistics.
- **BUDGET-R4** Loading reads the cached month, preferences, categories and accounts first
  (ADR 0007), then `/preferences`, members and `/meta`, then the month (entries, the month's and
  the previous month's summary, loans) and the accounts. The last request wins (#1781). A failed
  month load shows `budget.loadError` with retry, never an empty month; offline shows the cache
  with the banner and refuses writes with `common.errorOfflineMutation`. Pull to refresh.

### Overview

- **BUDGET-R5** Summary: balance (lead, coloured, neutral for expense-only months and forecast
  months, `summaryTitleForecast`), income, expenses, each with the trend against the previous
  month; "Expenses only" (#504) remembered on the device; transfers summary (one total, or
  incoming/outgoing on an account statement); `pendingSummary`. An empty month has no summary.
- **BUDGET-R6** Category chart: expenses, income and transfers blocks on their own scales,
  collapsed to the three largest of the leading block with the income line, "All categories (n)";
  expense bars in the category colours (`categoryColorIndex`), shares; a tap toggles the
  category filter; with a filter the subcategory breakdown, a tap filters by subcategory.
- **BUDGET-R7** Ledger head: account, responsible and category chips with x, starting balance and
  credit limit of a statement, sort note; search over all months (250 ms, `q=`, truncated note);
  tools menu: sort by date/amount, account filter, group by responsible, manage categories,
  CSV export (system document picker).
- **BUDGET-R8** Rows (`entryRows`): dot (ring when upcoming or expected), title with badges
  (household, amount only, expected, transfer), meta (date, upcoming, recurring/instance,
  virtual, receipts, category or subcategory under a category filter, account or transfer route,
  responsible avatars that filter), amount (`flow`, income green). Expected rows of a confirming
  series have Book (writers); expected subscription payments open Subscriptions; masked rows open
  nothing. Planned (ascending, three then "Show all") and Booked sections except in forecast
  months; filtered status line; empty and filter-empty states; grouping by responsible.
- **BUDGET-R9** A tap opens the read sheet (`entryReadSections`); writers get Edit.

### Entries

- **BUDGET-R10** Editor: type (expense, income; new: loan and, with two active accounts,
  transfer, which open those editors), amount, title, category with "+ category", subcategory
  (required with several, preselected with one) with "+ subcategory", date, account; "more
  details" (open when used): visibility (personal mode, with its hint), responsible chips (when
  more than one person), recurring with interval, every N, virtual, confirm first (not on an
  occurrence), receipts. A loan payment's type is locked. Field errors as `entryFormErrors`.
- **BUDGET-R11** Save: `POST /budget` with an idempotency key, or `PUT /budget/{id}`; editing an
  occurrence or a recurring anchor asks this one or all coming (`recurringChoiceModal`); the
  series gets only what changed (`occurrenceSeriesBody`, `anchorSeriesBody` with `start_date`).
  Refusals go to their field (`BUDGET_REFUSALS`).
- **BUDGET-R12** Delete (in the editor): series entries ask this one or the series (with the
  first-entry hint); the row and its sums go at once (`summaryWith`), the DELETE after the undo
  window; undo restores.
- **BUDGET-R13** Book an expected entry: amount and date, `PATCH /{id}/confirm`.
- **BUDGET-R14** Receipts: linked documents with remove, link existing (`GET /documents`), upload
  (`documents: write`) on save as `POST /documents` (finance, folder "budget"); opened natively
  (images, PDFs via `PdfRenderer`), other types in the browser. Hidden with `documents: none`.

### Categories, accounts, transfers, loans, plan, statistics

- **BUDGET-R15** Category manager: expense and income groups; add, rename, move up/down, delete
  (with confirmation and detail) categories and subcategories; `category.*` sentences; the page
  reloads its categories after each change.
- **BUDGET-R16** Accounts: net worth (approximate, or per currency), archived toggle, rows with
  type, credit meta, balance tone; read sheet with "Show transactions" (statement whose chip leads
  back) and Edit; editor with name, type, starting balance, bank and limit for cards, colour;
  archive/restore; delete with undo when empty, else confirmation.
- **BUDGET-R17** Transfers: amount in the source currency, from/to, default title, date, this or
  series for a series, category/subcategory, visibility, recurrence (only from the series);
  delete with confirmation of both halves.
- **BUDGET-R18** Loans: summary by direction, status segments, sort menu (remembered) with groups,
  loan filter, cards (progress, next due, projected end, book/receive installment with undo),
  payments list (newest first, six then all) with edit and delete (undo), report sheet with edit
  and delete (undo); editor with direction, partner, interest model and live preview, start
  month, due day, installments already paid (suggested), title, account, currency and rate, notes.
- **BUDGET-R19** Plan: historic note, savings ring, category rows (spent/planned, near/over,
  left/over by), add (categories without a budget), edit, delete with undo.
- **BUDGET-R20** Statistics: trend (running total in week/month, with today and future dashed,
  tap/drag readout, legend with planned rest and change), expense ring (seven segments),
  categories against the previous period with plan targets, CSV export, empty state that adds an
  entry dated into the period, error with retry. Drawn with Compose Canvas.
- **BUDGET-R21** `budget: read`: everything readable, no FAB, no Book, no Edit, no menus that write.

### Differs / left out

- **BUDGET-R30** Differs: editors are full screens; no swipe between periods (arrows only); bar
  textures (hatch) are not drawn; "Move to Split Expenses" of the entry editor is left out (it
  needs a prefill helper in the Split package); non-previewable receipts open the server URL in
  the browser.

## Acceptance criteria

All automated: `BudgetLogicTest` (A1–A12), `BudgetScreenTest` (A13–A22), `BudgetContractTest`.

- **BUDGET-A1** Money and region presets format like `money.js` — done.
- **BUDGET-A2** Amount input rules — done.
- **BUDGET-A3** Filters, sort, planned/booked split, responsible groups — done.
- **BUDGET-A4** Chart blocks, subcategory rows, category colours — done.
- **BUDGET-A5** `summaryWith` — done.
- **BUDGET-A6** Series bodies — done.
- **BUDGET-A7** Entry form errors and body; subcategory choice — done.
- **BUDGET-A8** Error sentences and labels — done.
- **BUDGET-A9** Loan order, payments, form checks — done.
- **BUDGET-A10** Statistics helpers — done.
- **BUDGET-A11** Transfer and account forms — done.
- **BUDGET-A12** Period helpers — done.
- **BUDGET-A13** Overview shows balance, categories, planned, booked — done.
- **BUDGET-A14** Category filter and subcategory breakdown — done.
- **BUDGET-A15** Read sheet and editor — done.
- **BUDGET-A16** Read-only member — done.
- **BUDGET-A17** Accounts, plan and statistics tabs — done.
- **BUDGET-A18** Create body with idempotency key — done.
- **BUDGET-A19** Series edit sends only changes — done.
- **BUDGET-A20** Delete after undo window — done.
- **BUDGET-A21** Confirm booking — done.
- **BUDGET-A22** Ledger search — done.
