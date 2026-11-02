# Spec: Rewards

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

Code: `app/src/main/java/io/github/marcomow/yuvomi/feature/rewards/` (`RewardsRules` and
`RewardsMoney` are the pure ports; `RewardsViewModel` the two tabs and every form,
`RewardsHistoryViewModel` the history; `RewardsScreen`, `RewardsSheets`, `RewardsHistoryScreen` the
UI; `RewardsTagSource` the Tags view's source). Routes: `rewards?tab={tab}` (the page, also
`RewardsRoutes.START`; `RewardsRoutes.catalog()` opens the "Rewards" tab), `rewards/history`
(`RewardsRoutes.HISTORY`, upstream's `/rewards/history`).

## Purpose

The household's point system as a native screen: points per member with the way to the next
reward, the rewards catalogue, redeeming and requests with the parents' decision, bonus points and
corrections, pocket money (accounts, requests, direct bookings, scheduled credit) and the points
history.

## Scope

Everything `public/pages/rewards.js` lets a member do. Nothing of the page stays in the browser.
Not here: the household switch "redemptions need approval" (`rewards_require_approval`, a
Settings sheet), points on tasks (Tasks), the overview tile (Overview). The wall tablet mode of
the page (`actingAsDisplay`, `/displays/people`) is not built: a display account does not sign in
to this app.

## Upstream references

- `public/pages/rewards.js` (`loadOverview`, `loadMoney`, `renderOverview`, `renderOwnCard`,
  `renderStandingRow`, `renderSetupHints`, `renderPendingPanel`, `renderMoneySection`,
  `renderMoneyRow`, `renderCatalog`, `renderRewardCard`, `renderLedger`, `renderRecentLedger`,
  `ledgerReason`, `openRedeemModal`, `decideRedemption`, `decisionSentence`, `openBonusModal`,
  `openRewardModal`, `openParticipantsModal`, `openMemberDetail`, `openMoneyRequestModal`,
  `openMoneyBookModal`, `openMoneyPlanModal`, `openMoneyAccountModal`, `closeMoneyAccount`,
  `removeMoneyPlan`, `openMoneyDetail`, `moneyRowKind`, `moneyPlanLine`, `rewardsErrorSentence`,
  `updateRewardsFab`).
- `public/utils/reward-goal.js` (`isRedeemable`, `nextRewardGoal`), `public/utils/money.js`
  (`amountInputProblem`, `toAmountDecimalString`), `public/utils/amount-field.js`
  (`amountProblemText`), `public/utils/friendly-error.js`.
- `server/routes/rewards.js`, `server/services/rewards.js` (`CATALOG_SELECT`),
  `server/services/reward-money.js` (`listMoneyAccounts`, `listMoneyLedger`, `readPlanInput`).

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/rewards/overview` | `data: { balances, catalog, pendingCount, moneyPendingCount, isAdmin, me, setup }` | the points tab |
| GET | `/rewards/participants` | `data`; 403 for non-admins | participants sheet |
| PUT | `/rewards/participants/{userId}` | `data: { user_id, enabled }` | switch one member |
| GET | `/rewards/catalog` | `data`; `?all=1` adds inactive ones for admins | catalogue tab |
| POST | `/rewards/catalog` | `data`, 201 | add a reward |
| PATCH | `/rewards/catalog/{id}` | `data`; 404 `rewards_reward_not_found` | edit, (de)activate |
| DELETE | `/rewards/catalog/{id}` | `{ ok }`; 404 `rewards_reward_not_found` | delete |
| GET | `/rewards/ledger` | `data`; `user_id`, `limit` | history, recent entries, a member's entries |
| GET | `/rewards/redemptions` | `data`; `status`, `kind=all` | pending requests |
| POST | `/rewards/redemptions` | `data`, 201; 400 `rewards_insufficient_points`, `rewards_not_participating`, `insufficient_funds`, `no_money_account`; 409 `out_of_stock` | redeem, money requests (`kind`) |
| PATCH | `/rewards/redemptions/{id}` | `data`; 409 `rewards_redemption_decided`, `out_of_stock`, `insufficient_funds`, `rewards_redemption_open`, `rewards_reopen_expired` | fulfill, reject, cancel, reopen (undo) |
| POST | `/rewards/bonus` | `data: { user_id, balance }`, 201 | bonus and correction |
| GET | `/rewards/money` | `data: { currency, minor_unit, accounts, candidates }` | pocket money section |
| GET | `/rewards/money/ledger` | `data`; `user_id`, `limit` | an account's entries |
| POST | `/rewards/money/entries` | `data`, 201; 400 `insufficient_funds`; 409 `account_deactivated` | parents book directly |
| POST | `/rewards/money/accounts` | `data`, 201; 409 `money_account_exists` | open an account |
| DELETE | `/rewards/money/accounts/{userId}` | `{ ok }`; 409 `money_account_not_empty`; 404 `rewards_money_account_not_found` | close an empty account |
| PUT | `/rewards/money/plans/{userId}` | `data: { user_id, plan, balance_minor }` | scheduled credit |
| DELETE | `/rewards/money/plans/{userId}` | `{ ok }`; 404 `rewards_money_plan_not_found` | end the plan |

Money amounts go out as decimal strings (`"12.50"`) and come back in minor units with the
object's own `currency` and `minor_unit`.

## Requirements

Status: all requirements below are built. Where the app knowingly differs from the web page, the
requirement says so ("Differs:").

### Page

- **REW-R1** Top bar `rewards.title`, two tabs `rewards.tabPoints` and `rewards.tabCatalog`
  (`TABS`). The history is a level below "Points" with its own route (`REWARDS_HISTORY_PATH`).
- **REW-R2** On open the page shows the cached answers at once, then loads overview, requests,
  the six latest entries, the money and (admins) the whole catalogue (ADR 0007). A failed recent
  entries or money call only leaves its section out (`loadRecentLedger`, `loadMoney`). A load
  error without cache shows `rewards.loadError` with retry; offline the cached page shows with
  `OfflineBanner` and no action. Pull to refresh; back in the foreground after 60 s reloads.
  Differs: the web page loads per tab; the app loads both tabs at once.
- **REW-R3** "Admin" is upstream's `isAdmin()` (role admin); "read-only" is `rewards: read`
  (`readOnly()`): a read-only member sees everything and no action (hidden, not disabled).
- **REW-R4** FAB (`updateRewardsFab`): parents with write access see "Add reward" on the catalogue
  and "Grant bonus" on the points when someone participates; nobody else sees one.

### Points

- **REW-R5** A member who is not a parent and collects points sees their own card first
  (`renderOwnCard`): balance (counting up), next target with icon, name and cost, the bar and its
  sentence, "Your entries" (REW-R11) and the redeem action when the balance covers any redeemable
  reward. The standings list then shows the others.
- **REW-R6** Pending requests (`renderPendingPanel`): parents see all open ones
  (`?status=pending&kind=all`) with "Approve" and "Reject" in the row menu; others see only their
  own (`?kind=all`, filtered to themselves and pending) with "Cancel". A decision leaves the list
  at once and offers "Undo" in the snackbar, which sends `reopen` (`offerUndo`). `out_of_stock`
  says `rewards.outOfStock`, `insufficient_funds` `rewards.money.insufficientOnApprove`; stale
  cases reload the list (`decideRedemption`).
- **REW-R7** Parents read next to a request the member's balance when it is below zero
  (`rewards.pendingBalanceBelowZero`), or for money requests the account balance.
- **REW-R8** Redeem (`openRedeemModal`): parents pick the member (when more than one) and redeem
  directly, others send a request. Only redeemable rewards are offered (active, not sold out);
  with none the page says `rewards.emptyCatalogMember`. A summary shows balance, cost and what
  remains (red below zero); submitting below zero says `rewards.notEnoughPoints`. Optional note
  (500 characters). Body `{ catalog_id, user_id, note? }` with an idempotency key.
- **REW-R9** Standings (`renderStandingRow`): avatar, name, points, the bar toward the next goal
  (`nextRewardGoal`, or `rewards.balanceBelowZero` below zero, or `rewards.noRewardsYet`), and the
  redeem action for parents and for the member themselves, disabled when the balance covers no
  reward. Parents with write access manage participants from the section head.
- **REW-R10** Bonus (`openBonusModal`): member, credit or deduction, a whole number of points
  (1 or more), optional reason (200); the button names the action. `delta` is negative for a
  deduction.
- **REW-R11** A member's sheet (`openMemberDetail`): balance, hint, the last 12 entries, and the
  redeem action as in REW-R9.
- **REW-R12** Participants (`openParticipantsModal`): every member with a switch that saves at
  once; a failure turns the switch back and says why. "Done" reloads the page.
- **REW-R13** History (`renderLedger`): chips "All" and one per participant (`personChipsHtml`),
  entries with icon, reason (`ledgerReason`: the task, "Reopened: …" for a negative reversal, else
  the type's name), person · date and the signed points. The recent entries on the points tab
  use the same row and lead here.
- **REW-R14** Setup hints (`renderSetupHints`) for parents with write access while not all three
  steps are done: enable members (participants sheet), assign points (Tasks), create rewards
  (catalogue tab).

### Catalogue

- **REW-R15** Cards (`renderRewardCard`): icon, name, "inactive" and "Sold out" tags, description,
  units left, cost; parents edit; the redeem action when the reward is active, not sold out and
  (for others) affordable, else "N more needed". Empty state with "Add reward" for parents.
- **REW-R16** Reward form (`openRewardModal`): icon (4), name (required, 120), cost (required,
  1 or more), quantity (empty is unlimited, else 1 or more), description (500), "Active" on edit;
  the web page's sentences for each check. Delete asks first (`rewards.confirmDeleteReward` with
  its detail); a reward already gone counts as deleted.

### Pocket money

- **REW-R17** The section (`renderMoneySection`) shows the accounts the server sends; parents with
  write access open one for a child without an account (parent roles are not offered,
  `PARENT_ROLES`). Without accounts it shows `rewards.money.empty` to who can open one, else
  nothing.
- **REW-R18** A row: avatar, name (former accounts tagged `settings.memberFormerBadge`), balance in
  the account's currency and decimals, the plan line (`moneyPlanLine`). Parents: "Book" and a menu
  with "Scheduled credit" and, when the server says so, "Close account"; a former account only
  "Deduct". The child: "Withdraw" (disabled at zero) and "Pay in" in the menu.
- **REW-R19** Requests (`openMoneyRequestModal`): amount and note; the hint that parents confirm;
  a withdrawal above the balance says `rewards.money.insufficient` before sending.
- **REW-R20** Booking (`openMoneyBookModal`): credit or deduct (former: deduct only), amount,
  reason.
- **REW-R21** Plan (`openMoneyPlanModal`): amount, weekly or monthly, weekday or day of month,
  pause; "End plan" asks first. The field opens with the stored amount in the server's decimals
  (`minorToAmountInput`).
- **REW-R22** Amounts are checked as the web page does (`moneyAmountProblem`): grouping, invalid,
  not positive, more decimals than `minor_unit`, with `common.amount*` sentences. Closing an
  account asks first; closing and ending a plan reload the page either way.
- **REW-R23** An account's sheet (`openMoneyDetail`): balance, plan line, the last 20 entries
  (`moneyRowKind`: scheduled credit with its date, deposit, withdrawal, credit, deduction).

### Errors and tags

- **REW-R24** Errors are the app's sentences (`rewardsErrorSentence`): the page's money reasons
  first (not on a 403), then `rewards_*` cases, then refusals, else the place's general sentence.
- **REW-R25** Tags view (`RewardsTagSource`): every reward of the catalogue the member sees whose
  name or description carries a `#hashtag`; it opens the catalogue tab.

## Acceptance criteria

| ID | Criterion | Test |
|---|---|---|
| REW-A1 | The goal is the cheapest redeemable reward above the balance; sold out and inactive ones never count | `RewardsLogicTest` |
| REW-A2 | Ledger reasons follow `ledgerReason`, including reopened tasks and refunds | `RewardsLogicTest` |
| REW-A3 | Decision sentences name reward and member | `RewardsLogicTest` |
| REW-A4 | Errors map to the page's sentences, never the server's text | `RewardsLogicTest` |
| REW-A5 | Money is formatted and read in the server's decimals | `RewardsLogicTest` |
| REW-A6 | A parent sees requests, standings, pocket money and recent entries | `RewardsScreenTest` |
| REW-A7 | Approving goes out at once; undo sends `reopen` | `RewardsScreenTest` |
| REW-A8 | A read-only parent sees requests but no action | `RewardsScreenTest` |
| REW-A9 | A child sees their own card first and what is missing in the catalogue | `RewardsScreenTest` |
| REW-A10 | Redeeming sends `{ catalog_id, user_id, note }` with an idempotency key and refuses too few points | `RewardsScreenTest` |
| REW-A11 | The history filters by person | `RewardsScreenTest` |
| REW-A12 | Rewards with hashtags become tagged items that open the catalogue | `RewardsLogicTest` |
| REW-C1–C9 | Every operation against the real server | `RewardsContractTest` |
