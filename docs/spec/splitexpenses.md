# Spec: Split expenses (Budget tab)

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The budget page's Split tab ("Shared expenses") as native screens, at parity with upstream's
`public/pages/split-expenses.js`: groups of people who share costs, expenses with a payer and a
split, who owes whom, settling up, recurring expenses and the group's activity.

## Scope

Not a module of its own. `BudgetFeature.register` calls `SplitExpensesGraph.register(graph, nav)`;
every route starts with `budget/split`. Permission key: `budget` (`server/scopes.js` lists
`split-expenses` as a second prefix of `budget`).

| Area | Requirement IDs |
|---|---|
| Groups: list, search, active/archived, create, edit, members, guests, archive, restore, delete | R1–R7 |
| Expenses: list with paging and category filter, read view, add, edit, delete with undo | R8–R11 |
| Balances, totals, settlements and reversal, recurring series, activity | R12–R17 |
| Permissions, guests, errors, offline, links, tags | R18–R22 |

**Left in the browser:** attaching receipts and payment proofs (the web's document picker and
upload into Documents). The app shows attached receipts as links to the native document and keeps
them when an expense is edited (it never sends `attachment_document_ids`), but cannot attach new
ones. Native differences: the group list is a sheet behind the group switch (the web's narrow
layout), the editors are screens, the category filter is a row of chips.

## Upstream references

- `public/pages/split-expenses.js` (`loadInitial`, `loadGroups`, `loadGroupData`, `fetchExpenses`,
  `fetchActivity`, `loadMore*`, `renderSummary`, `ownGroupBalance`, `foldedDebt`, `debtSentence`,
  `renderBalances`, `renderExpenses`, `renderMemberTotals`, `renderCategoryTotals`,
  `renderRecurring`, `toggleRecurring`, `recurringResumeChoice`, `recurringPastDateChoice`,
  `activityItemHtml`, `reverseSettlement`, `archiveGroup`, `restoreGroup`, `deleteGroup`,
  `openGroupModal`, `syncEditedGroupMembers`, `renderGroupDefaults`, `openExpenseModal`,
  `deriveSplitValues`, `defaultSplitValues`, `validateSplitForm`, `collectSplitPayload`,
  `openRecurringModal`, `changedRecurringFields`, `openSettlementModal`, `openMemberModal`,
  `openGuestModal`, `prefillSplitExpense`).
- `public/utils/money.js`, `interval-date.js` (`nextRunNotBefore`, `addInterval`),
  `category-labels.js`, `friendly-error.js`.
- `server/routes/split-expenses.js`, `server/services/split-expenses.js`.

## Upstream operations

All in `SplitExpensesOperation` (`upstream/operations-used.d/splitexpenses.txt`), wrapped by
`SplitApi`. Also `MealsOperation.FAMILY_MEMBERS` (`GET /family/members`); `BUDGET_META`
(`GET /budget/meta`, the budget's expense categories) is under Budget's path.

| Method | Path | Used for |
|---|---|---|
| GET | `/split-expenses/meta`, `/dashboard` | types, methods, currencies, the reader's sums over all active groups |
| GET / POST | `/split-expenses/groups` | list (`status`, `q`), create |
| PATCH / DELETE | `/split-expenses/groups/{id}` | edit (with `default_split_method/config`), delete an empty group (409 with history) |
| POST | `/groups/{id}/archive`, `/unarchive` | archive, restore |
| GET / POST | `/groups/{id}/members` | members; add `{ user_id \| contact_id, role }` |
| DELETE | `/groups/{id}/members/{userId}` | remove (not the owner) |
| GET | `/groups/{id}/member-candidates` | the member editor and "Add member" |
| POST | `/groups/{id}/guests` | a guest account in the group |
| GET / POST | `/groups/{id}/expenses` | cursor pages (`limit`, `before_*`, `budget_category`), create |
| PUT / DELETE | `/split-expenses/expenses/{id}` | edit, delete |
| GET | `/groups/{id}/balances` | balances, simplified debts, per-person and per-category totals |
| POST | `/groups/{id}/settlements`, `/settlements/{settlementId}/reverse` | register and reverse a payment |
| GET | `/groups/{id}/activity` | cursor pages |
| GET / POST | `/groups/{id}/recurring` | series, create (`missed`) |
| PUT / DELETE | `/split-expenses/recurring/{id}` | partial edit, delete |
| POST | `/split-expenses/recurring/{id}/pause` | pause, or resume with `missed: skip \| book` |
| GET | `/budget/meta` | `expenseCategories` for the category picker and labels |

## Requirements

- **SPLIT-R1** Load meta, dashboard, groups, the household (not for guests) and the budget's categories, then the chosen group (the first, or `?group=`).
- **SPLIT-R2** The group switch names the chosen group, its type unless it repeats the name, and its member count; its sheet has the search, Active/Archived, "New group", the sum over all groups, and the list.
- **SPLIT-R3** The balance statement (`renderSummary`): the reader's single debt as the lead with the other's name; else "You owe", "You are owed" or "Settled up", both only in two currencies in both directions; per group ("<group>: You owe").
- **SPLIT-R4** New group: name, description, type, currency. Edit adds the member editor (household members, guests, contacts without an account; the owner locked) and the default split (equal, percentage, shares with per-member values; percentages must make 100) from two members on.
- **SPLIT-R5** Archive (with the question), restore (without, then the active list on that group), delete (with upstream's warning; 409 with history says so).
- **SPLIT-R6** "Add member": a candidate and a role (guest, admin), or "Create guest" (display name, optional username, temporary password ≥ 8, phone, e-mail, birth date).
- **SPLIT-R7** Search and status reload the groups; a search without hits shows the search empty state.
- **SPLIT-R8** Expenses: newest first, 30 a page with "Load more" (cursor), filter chips for the budget categories in use from two on; row: dot, title, "Paid by", date, category, receipts count, amount.
- **SPLIT-R9** Read view: amount, payer, date, category, method, each share, notes, receipts as links to Documents; Edit for writers outside the archive.
- **SPLIT-R10** Editor: amount (text) and currency, title, date, payer, split method, category, participants with per-person values (hidden for equal), notes. A new one starts with the group's currency and default split, everyone in, the reader paying; edit derives the values back (`deriveSplitValues`). The hint shows the running total.
- **SPLIT-R11** Checks before sending: title, amount, participants, totals of percentage (100) and exact (the amount), whole positive shares, positive exact shares; amounts go as decimal strings. Delete from the editor with undo.
- **SPLIT-R12** Balances: simplified debts in the reader's words; hidden when folded into the statement; "Settle" for writers.
- **SPLIT-R13** Totals per person (paid, own share) and per category.
- **SPLIT-R14** Settlement: payer and payee proposed from the debts (the reader's first), picking a payer proposes their payee and amount; same person warns; amount checked in the currency's grid.
- **SPLIT-R15** Recurring series: title, frequency, next date or "Paused", why it does not book, the failing note; pause/resume for those with `can_edit`; resuming with missed dates asks skip or book; editing sends only what changed (`changedRecurringFields`); a past start asks skip or book (`recurringPastDateChoice`); a former payer is refused; delete asks.
- **SPLIT-R16** Activity: each type's sentence, the payment or expense snapshot, actor and day in the household zone, "Reversed"/"Deleted" marks, "Reverse" where the server allows it (with the question), 12 a page with "Load more".
- **SPLIT-R17** Every write reloads the dashboard and the chosen group.
- **SPLIT-R18** `budget: read` and the archive: no add, settle, series actions, reversal or group menu; the read-only member sees the group's currency, default split and members in its header (`groupMetaHtml`).
- **SPLIT-R19** A guest (`access_scope: split_guest`) gets no household list, no candidates and no group management.
- **SPLIT-R20** Errors: offline, refusal and server sentences like `unhandledErrorSentence`; `group_archived`, `email_in_use` and a bare 409 get app sentences; the server's text is never shown.
- **SPLIT-R21** Offline: the last unfiltered state from the cache, read-only with `OfflineBanner`. Links: `SplitExpensesRoutes.group(id)`, `expense()`, and `newExpense(title, amount, date, currency, participantIds)` for Budget's hand-over (`prefillSplitExpense`, #1057).
- **SPLIT-R22** Tags: `SplitExpensesTagSource` lists expenses of the active groups with `#hashtags` in title or notes, opening their group.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| SPLIT-A1 | Split validation for the four methods and positive exact shares. | unit | passing |
| SPLIT-A2 | The expense body has decimal strings, member-ordered participants and the method's values. | unit | passing |
| SPLIT-A3 | Values come back from an expense and a group preset; the budget's hand-over keeps only members. | unit | passing |
| SPLIT-A4 | Own balance, folded debt, debt sentences, type and category labels. | unit | passing |
| SPLIT-A5 | `nextRunNotBefore` with the anchor day; an edit sends only what changed; a past start is detected. | unit | passing |
| SPLIT-A6 | Activity and series answers decode; activity details from snapshots. | unit | passing |
| SPLIT-A7 | Error keys never carry the server's text; guest checks; minor units. | unit | passing |
| SPLIT-A8 | The tag source returns tagged expenses with their group's route. | unit | passing |
| SPLIT-A9 | The tab shows the folded debt, expenses, the series (pause posts) and the payment's "Reverse". | ui | passing |
| SPLIT-A10 | A new expense posts the decimal amount, payer and participants. | ui | passing |
| SPLIT-A11 | `budget: read` hides add, settle, reversal, the group menu and the series toggle. | ui | passing |
| SPLIT-A12 | The seeded tab decodes: groups, expenses with splits, balances, activity, series, members, categories; filter and cursor paging work. | contract | written |
| SPLIT-A13 | A group is created, edited with a default split, gains and loses a member (the owner cannot be removed), and is deleted while empty. | contract | written |
| SPLIT-A14 | Exact and shares splits, a wrong total 400, debts, a settlement and its reversal (second 409), delete, group delete 409, archive refuses expenses with `group_archived`, restore. | contract | written |
| SPLIT-A15 | A series: create, partial edit, pause, resume, `invalid_missed`, delete. | contract | written |
| SPLIT-A16 | A guest joins with their own sign-in; a short password is 400. | contract | written |

## Strings

Upstream's `splitExpenses.*`, `budget.*`, `common.*`, `nav.contacts`, `settings.memberFormerBadge`.
App-only (`assets/i18n-android/splitexpenses/en.json`): `conflict`, `dateFormat`, `emailInUse`,
`groupArchived`, `passwordShort`.
