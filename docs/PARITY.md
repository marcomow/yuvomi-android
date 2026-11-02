# Parity with upstream

Where each part of upstream Yuvomi stands in this app. Updated whenever a spec lands or an
upstream sync PR is reviewed ([UPSTREAM-SYNC.md](UPSTREAM-SYNC.md)).

**Status values:** **Native** (a native screen, spec linked) · **Overview** (its data shows
read-only on the overview, the module itself opens in the browser) · **Browser** (opens the web
app in the browser, ADR 0006) · **Planned** (a spec is being written).

| Area | Upstream module | Status | Spec | Last checked |
|---|---|---|---|---|
| Sign-in | Login, 2FA | Native (password and TOTP only) | [signin](spec/signin.md) | 2.83.0 |
| Sign-in | Single sign-on, invites, setup, password reset | Browser | | 2.83.0 |
| Plan | Overview | Native (read-only tiles, task tiles interactive) | [overview](spec/overview.md) | 2.83.0 |
| Plan | Tasks | Native, tiers 1 and 2 | [tasks](spec/tasks.md) | 2.83.0 |
| Plan | Tasks, tier 3 (Kanban, manual order, archive, bulk, history, tag and category management, documents upload) | Browser | | 2.83.0 |
| Plan | Calendar | Overview | | 2.83.0 |
| Plan | Rewards | Browser | | 2.83.0 |
| Plan | Notes | Native | [notes](spec/notes.md) | 2.83.0 |
| Plan | Contacts | Browser | | 2.83.0 |
| Plan | Schedule | Browser | | 2.83.0 |
| Household | Meals | Native | [meals](spec/meals.md) | 2.83.0 |
| Household | Recipes | Native (connecting recipe sources stays in Settings, browser) | [recipes](spec/recipes.md) | 2.83.0 |
| Household | Shopping | Native | [shopping](spec/shopping.md) | 2.83.0 |
| Household | Pantry | Native | [pantry](spec/pantry.md) | 2.83.0 |
| Household | Housekeeping | Browser | | 2.83.0 |
| Household | Waste collection | Browser | | 2.83.0 |
| Household | Documents | Browser | | 2.83.0 |
| Household | Inventory | Browser | | 2.83.0 |
| People | Health | Browser | | 2.83.0 |
| People | Birthdays | Overview | | 2.83.0 |
| Finance | Budget, shared expenses, subscriptions | Browser | | 2.83.0 |
| Settings | Family, reminders, API tokens, backup, modules | Browser | | 2.83.0 |
| Settings | Language (per device) | Native | [signin](spec/signin.md) | 2.83.0 |
| Cross-cutting | Notifications (Web Push, channels) | Not in the app (ADR 0005) | | 2.83.0 |
| Cross-cutting | Third-party extension modules | Browser | | 2.83.0 |
| Cross-cutting | Wall mode, displays, screensaver | Browser | | 2.83.0 |
| Cross-cutting | Global search | Browser | | 2.83.0 |
