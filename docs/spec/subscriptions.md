# Spec: Subscriptions (Budget tab)

Status: implemented · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The budget page's Subscriptions tab as native screens, at parity with upstream's
`public/pages/subscriptions.js`: recurring services with their cost per month, budget and
renewal forecast, the payments that move the next due date and book a budget entry, and the
categories, payment methods and settings behind them.

## Scope

Not a module of its own. `BudgetFeature.register` calls `SubscriptionsGraph.register(graph, nav)`;
every route starts with `budget/subscriptions`. Permission key: `budget` (`isNavModuleReadOnly('budget')`).

| Area | Requirement IDs |
|---|---|
| List: load, totals, search, filters, sort, rates, rows, empty states, analytics | R1–R12 |
| Payment with undo, delete with undo, read view | R13–R16 |
| Editor: identity and logo, billing, account, reminder, end, organisation, service | R17–R24 |
| Manager of categories and payment methods, settings | R25–R28 |
| Permissions, errors, offline, links, tags | R29–R33 |

**Nothing stays in the browser.** Native differences: the editor, the manager and the read view
are screens or sheets instead of modal dialogs; dragging categories into order is replaced by the
"Move up"/"Move down" items the web's row menu offers; the forecast is drawn as bars instead of an
area curve (same six values). SVG logos are stored and sent like the web does but shown as the
initials tile, since the platform decoder reads PNG, JPEG and WebP only.

## Upstream references

- `public/pages/subscriptions.js` (`loadOnce`, `render`, `activeFilters`, `renderSummary`,
  `renderOverBudgetNote`, `renewalForecast`, `dueAmount`, `subscriptionEndBoundary`, `cycleLabel`,
  `dueLabel`, `renderCard`, `subscriptionReadSections`, `openSubscriptionModal`, `saveSubscription`,
  `renewSubscription`, `deleteSubscription`, `openSettingsModal`, `openMetadataModal`,
  `openLogoPickerModal`, `fileToDataUrl`, `resetGoneFilter`).
- `public/utils/money.js` (`formatMoney`, `toAmountDecimalString`, `amountInputProblem`,
  `amountToInput`), `amount-field.js` (`amountProblemText`), `date-format.js` (`daysFromTodayText`),
  `interval-sentence.js` (`UNIT_SENTENCE_KEYS`), `friendly-error.js` (`errorSentence`, `isGone`,
  `isAlreadyDeleted`), `color-picker.js`, `permissions.js`.
- `server/routes/subscriptions.js`, `server/services/subscriptions.js`.

## Upstream operations

All in `SubscriptionsOperation` (`upstream/operations-used.d/subscriptions.txt`), wrapped by `SubscriptionsApi`.

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/budget/subscriptions` | `data: { subscriptions, summary, rates }` | the list; `q`, `category_id`, `payment_method_id`, `status`, `refresh_rates` |
| POST | `/budget/subscriptions` | `data`, 201 | create; `Idempotency-Key` |
| PUT | `/budget/subscriptions/{id}` | `data` | edit (the dialog sends every field) |
| DELETE | `/budget/subscriptions/{id}` | 204 | delete after the undo window |
| POST | `/budget/subscriptions/{id}/renew` | `data`, `undo.token` | one payment |
| POST | `/budget/subscriptions/{id}/renew/undo` | `data` | take it back: `{ token }`; 409 `subscriptions_renew_undo_stale` |
| GET | `/budget/subscriptions/meta` | `data` | categories, payment methods, cycles, budget mode, accounts |
| PUT | `/budget/subscriptions/meta/order` | `data` | `{ categories: [ids] }` or `{ payment_methods: [ids] }` |
| GET / PUT | `/budget/subscriptions/settings` | `data` | monthly budget and base currency |
| POST / PUT / DELETE | `/budget/subscriptions/categories[/{id}]` | `data` | the category manager |
| POST / PUT / DELETE | `/budget/subscriptions/payment-methods[/{id}]` | `data` | the payment method manager |
| POST | `/budget/subscriptions/logo-search` | `data.options[].logo_data` | "Find logo"; 404 `subscriptions_no_logo_found` |

## Requirements

- **SUBS-R1** Load list, meta and settings in parallel (`loadOnce`); an overtaken load applies nothing. The server filters (search after 250 ms, category, payment method, status); the sort is local.
- **SUBS-R2** Summary: monthly cost leads; with a budget, "Remaining budget" or "Over budget" (danger) with a bar (warning from 85 %); the yearly projection. Without a budget no budget line (#1607).
- **SUBS-R3** Over the budget, the note "<n> % budget used" and "Most expensive first", which sorts by monthly cost descending.
- **SUBS-R4** Filters (category, payment method, status) in a sheet, every choice applying at once; active ones as removable chips, "Reset filters" from two on. A filter on a deleted category or method goes back to "all" (`resetGoneFilter`).
- **SUBS-R5** Sort: next due date (then name), highest/lowest monthly cost (no conversion last), name, by the UI language's collation.
- **SUBS-R6** The rate status shows only when a subscription is in another currency than the base one: "Exchange rates unavailable", else "Refresh rates" (`refresh_rates=true`).
- **SUBS-R7** Row: logo or initials on the brand/category colour, name, description or category, status badge unless active, due date with `dueLabel` (overdue in danger; beyond 30 days in weeks, months or years), cycle as `cycleLabel`, the reminder only when it differs from three days, the end, an archived account; the amount and, when it differs, the monthly equivalent or "Conversion unavailable".
- **SUBS-R8** A completed subscription shows no due date, no reminder and no payment action (#2009, #2011).
- **SUBS-R9** Empty list: "No subscriptions yet" with Add (writers), or the search empty state with "Reset search" when filters or a search apply.
- **SUBS-R10** Analytics: the six-month renewal forecast with its peak month (`renewalForecast`, end boundaries, foreign amounts through the monthly ratio), and the breakdowns by category and by payment method with amount and share.
- **SUBS-R11** Pull to refresh; FAB "Add subscription" for writers.
- **SUBS-R12** Amounts are formatted with the household's currency rules in the UI language (`formatMoney`).
- **SUBS-R13** "Mark as renewed" (swipe to the row start, the row menu, the read view): no question; the snackbar names the next date (or "paid and completed") and offers Undo, which calls `/renew/undo` with the token.
- **SUBS-R14** Delete (swipe to the row end, the row menu, the editor): the row leaves at once, Undo brings it back; only without Undo is `DELETE` sent; "already deleted" counts as done.
- **SUBS-R15** Tap opens the read view: amount with monthly equivalent, status, description, cycle, next payment, reminder, end, category, payment method, account, registered under, notes; Mark as renewed and Edit for writers.
- **SUBS-R16** A completed renewal refused with `subscriptions_subscription_completed` or a gone subscription reloads the list.
- **SUBS-R17** Editor identity: logo picked from a file (PNG, JPEG, WebP, SVG, under 500 KB as a data URL) or found on the web, removable; name (required), description.
- **SUBS-R18** Billing: amount as text in the region's notation (0 allowed), currency, "Every N <unit>" with the unit words of the sentence, next payment date (completed: the hint that it only counts once included again), the account (active ones, the archived chosen one with its hint) when accounts exist.
- **SUBS-R19** Renewal: reminder switch and days (0–365), end: never, on a date, after a number of payments (1–1200).
- **SUBS-R20** "More details" (open when an edited subscription uses one): visibility in the personal budget mode with its hint, category, payment method, brand colour (default, family tones, a stored custom one), registered under (with the not-a-password hint), notes, "Include in budget".
- **SUBS-R21** All field errors at once (`saveSubscription`): name, date, currency, interval, reminder, end date before next payment, count not above payments made (skipped while a completed one stays completed), amount problems with upstream's sentences.
- **SUBS-R22** The body sends every field like the web; `account_id` only with accounts, `visibility` only in personal mode; creates carry an `Idempotency-Key`.
- **SUBS-R23** A selected category, payment method or account that is gone: the sentence, the field goes back to none, the editor stays open.
- **SUBS-R24** Logo search: query from the website or the name, results to pick from, "No supported logos found" and upstream's error sentence.
- **SUBS-R25** Manager: categories with colour dot and payment methods, each with a row menu: edit, move up/down, delete.
- **SUBS-R26** Add a category (name, colour) or payment method; a duplicate says `subscriptions.errorCategoryExists` / `...PaymentMethodExists`.
- **SUBS-R27** Rename keeps the canonical name when only the colour changed on a translated default; delete asks with upstream's consequences ("N subscriptions will lose this assignment"); "already deleted" is a success.
- **SUBS-R28** Settings: monthly budget (text, ≥ 0) and base currency, the Fixer hint; saving reloads with fresh rates.
- **SUBS-R29** `budget: read`: no FAB, no row menus or swipes, no manager or settings; sort, filters and read views stay. Admins always write.
- **SUBS-R30** Errors map `reason` to upstream's sentences (`friendly-error.js`), 403 to the refusal sentence, offline to `common.errorOfflineMutation`; never the server's text.
- **SUBS-R31** Offline: the last unfiltered answer from the cache, read-only with `OfflineBanner`.
- **SUBS-R32** Links: `SubscriptionsRoutes.subscription(id)` opens the list on that subscription's read view, after clearing search and filters (`clearSubscriptionFilters`), for the budget ledger's expected subscription rows; `SubscriptionsRoutes.editor()` for Budget's FAB on this tab.
- **SUBS-R33** Tags: `SubscriptionsTagSource` lists subscriptions with `#hashtags` in name, description or notes, opening `SubscriptionsRoutes.subscription(id)`.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| SUBS-A1 | Amounts format per currency and locale; "1.200" (de), "1,200" (en), "10 000" are grouped; JPY refuses decimals; a stored amount outside the grid stays savable. | unit | passing |
| SUBS-A2 | Cycles read "Monthly", "Every 2 months"; due labels cover overdue, today, tomorrow, days, weeks, months, years. | unit | passing |
| SUBS-A3 | The four sorts order as the web. | unit | passing |
| SUBS-A4 | The forecast counts six months, stops at the end, skips paused ones, converts foreign amounts. | unit | passing |
| SUBS-A5 | Over-budget percent, foreign-currency check, filter chips and gone filters. | unit | passing |
| SUBS-A6 | Editor errors come all at once with upstream's sentences; the body matches the web's, account and visibility only when shown; logo type and size checks. | unit | passing |
| SUBS-A7 | Reasons map to upstream's sentences; gone and already-deleted. | unit | passing |
| SUBS-A8 | The list answer decodes with flags as booleans or integers and a null monthly base. | unit | passing |
| SUBS-A9 | The tag source returns tagged subscriptions with their route. | unit | passing |
| SUBS-A10 | The list shows totals, the paused badge and the over-budget action; the row menu's payment posts `/renew` and offers Undo. | ui | passing |
| SUBS-A11 | A row opens its read view; Edit opens the editor. | ui | passing |
| SUBS-A12 | `budget: read` has no FAB, row menu or manager; sorting stays. | ui | passing |
| SUBS-A13 | The seeded list, meta and settings decode; status and search filter on the server. | contract | written |
| SUBS-A14 | Create, edit, renew (+1 month, token), undo (date back, second undo 409 stale), delete, second delete 404 gone. | contract | written |
| SUBS-A15 | Gone category, end before next payment, completed renewal and an empty logo query are refused with their reasons. | contract | written |
| SUBS-A16 | Category and payment method create, duplicate 409, rename, order, delete, 404 after. | contract | written |
| SUBS-A17 | Settings round trip; a negative budget is 400. | contract | written |

## Strings

Upstream's `subscriptions.*`, `common.*`, `budget.*`, `colorPicker.*`, `settings.permReadOnlyBanner`.
App-only (`assets/i18n-android/subscriptions/en.json`): `intervalRange`, `reminderRange`,
`moreDetails` (the web's `advancedSection` label is not in a shipped namespace), `removeLogo`.
