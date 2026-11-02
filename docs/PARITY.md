# Parity with upstream

Where each part of upstream Yuvomi stands in this app. Updated whenever a spec lands or an
upstream sync PR is reviewed ([UPSTREAM-SYNC.md](UPSTREAM-SYNC.md)).

**Status values:** **Native** (a native screen, spec linked) · **Overview** (its data shows
read-only on the overview, the module itself opens in the browser) · **Browser** (opens the web
app in the browser, ADR 0006) · **Planned** (a spec is being written).

| Area | Upstream module | Status | Spec | Last checked |
|---|---|---|---|---|
| Sign-in | Login, 2FA | Native (password and TOTP only) | [signin](spec/signin.md) | 2.84.0 |
| Sign-in | Single sign-on, invites, setup, password reset | Browser | | 2.84.0 |
| Plan | Overview | Native (read-only tiles, task tiles interactive) | [overview](spec/overview.md) | 2.84.0 |
| Plan | Tasks | Native, tiers 1 and 2 | [tasks](spec/tasks.md) | 2.84.0 |
| Plan | Tasks, tier 3 (Kanban, manual order, archive, bulk, history, tag and category management, documents upload) | Browser | | 2.84.0 |
| Plan | Calendar | Native (new attachments and account connections stay in the browser) | [calendar](spec/calendar.md) | 2.84.0 |
| Plan | Rewards | Native (the wall-tablet display mode stays in the browser) | [rewards](spec/rewards.md) | 2.84.0 |
| Plan | Notes | Native | [notes](spec/notes.md) | 2.84.0 |
| Plan | Contacts | Native | [contacts](spec/contacts.md) | 2.84.0 |
| Plan | Schedule | Native (printing the statistics stays in the browser) | [schedule](spec/schedule.md) | 2.84.0 |
| Household | Meals | Native | [meals](spec/meals.md) | 2.84.0 |
| Household | Recipes | Native (connecting recipe sources stays in Settings, browser) | [recipes](spec/recipes.md) | 2.84.0 |
| Household | Shopping | Native | [shopping](spec/shopping.md) | 2.84.0 |
| Household | Pantry | Native | [pantry](spec/pantry.md) | 2.84.0 |
| Household | Housekeeping | Native | [housekeeping](spec/housekeeping.md) | 2.84.0 |
| Household | Waste collection | Native | [waste](spec/waste.md) | 2.84.0 |
| Household | Documents | Native (uploading a whole folder tree stays in the browser) | [documents](spec/documents.md) | 2.84.0 |
| Household | Inventory | Native | [inventory](spec/inventory.md) | 2.84.0 |
| People | Health | Native (CSV export/import, cycle calendar and trend charts, some fasting extras stay in the browser) | [health](spec/health.md) | 2.84.0 |
| People | Birthdays | Native | [birthdays](spec/birthdays.md) | 2.84.0 |
| Finance | Budget | Native | [budget](spec/budget.md) | 2.84.0 |
| Finance | Subscriptions | Native | [subscriptions](spec/subscriptions.md) | 2.84.0 |
| Finance | Split expenses | Native (attaching new receipts stays in the browser) | [splitexpenses](spec/splitexpenses.md) | 2.84.0 |
| Settings | Account, 2FA, preferences, modules, family, permissions, notification channels, e-mail, backups, API tokens | Native | [settings](spec/settings.md) | 2.84.0 |
| Settings | PWA install, web push, SSO, calendar sync and subscriptions, module install, backup restore, displays, document storage, feeds | Browser | [settings](spec/settings.md) | 2.84.0 |
| Settings | Language (per device) | Native | [signin](spec/signin.md) | 2.84.0 |
| Cross-cutting | Notifications (Web Push, channels) | Not in the app (ADR 0005) | | 2.84.0 |
| Cross-cutting | Third-party extension modules | Browser | | 2.84.0 |
| Cross-cutting | Wall mode, displays, screensaver | Browser | | 2.84.0 |
| Cross-cutting | Global search | Browser | | 2.84.0 |
| Cross-cutting | Tags across modules (app-only: real tags on tasks and shopping items, #hashtags elsewhere) | Native | [tags](spec/tags.md) | 2.84.0 |
