# Architecture

Yuvomi for Android is an **unofficial, proof-of-concept native client** for a self-hosted
[Yuvomi](https://github.com/ulsklyc/yuvomi) server. It sits *next to* Yuvomi, the way upstream
describes its own planned iOS client (`docs/SCOPE.md`, "Where the line falls in practice"): its own
repository, talking only to `/api/v1`, with the web app staying the complete product.

The reasoning behind every choice here is in the decision records under [`adr/`](adr/). The
brainstorm that led to them is kept in [`ARCHITECTURE-BRAINSTORM.md`](ARCHITECTURE-BRAINSTORM.md)
for history; where the two disagree, this file and the ADRs win.

Upstream reference: **v2.83.0** (pinned in [`upstream/VERSION`](../upstream/VERSION)).

## Decisions at a glance

| # | Decision | ADR |
|---|---|---|
| 1 | Kotlin, Jetpack Compose, Material 3. One Gradle module. Min SDK 26. | [0001](adr/0001-native-stack.md) |
| 2 | Material 3 structure with the Yuvomi skin taken from upstream design tokens. Dynamic colour off. | [0002](adr/0002-design-language.md) |
| 3 | Hand-written, typed API client for the operations we use. OpenAPI is a diff signal, not a code source. | [0003](adr/0003-api-client.md) |
| 4 | Session cookie and CSRF token, password plus optional TOTP. SSO-only households are unsupported. | [0004](adr/0004-authentication.md) |
| 5 | No notifications in the MVP. | [0005](adr/0005-notifications.md) |
| 6 | Modules that are not native yet open in the user's browser, never in a WebView. | [0006](adr/0006-non-native-modules.md) |
| 7 | Read-only offline cache, like the PWA. Writes need a connection. | [0007](adr/0007-offline.md) |
| 8 | Upstream sync: version pin, OpenAPI and locale snapshots, weekly diff, contract tests, runtime version gate. | [0008](adr/0008-upstream-sync.md) |
| 9 | Unofficial proof of concept, MIT licence, English code and docs. No contact with upstream yet. | [0009](adr/0009-project-identity.md) |
| 10 | MVP is Overview and Tasks, Tasks at tiers 1 and 2. | [0010](adr/0010-mvp-scope.md) |
| 11 | UI strings come from upstream's locale files, not from `strings.xml`. | [0011](adr/0011-strings-from-upstream.md) |
| 12 | OkHttp without Retrofit, JSON files instead of Room, versions that compile against SDK 36. | [0012](adr/0012-fewer-libraries.md) |

## System context

```
┌──────────────────────┐   HTTPS, /api/v1 only    ┌───────────────────────────┐
│  Yuvomi for Android  │ ───────────────────────▶ │  Household's Yuvomi server │
│  (this repository)   │ ◀─────────────────────── │  (ghcr.io/ulsklyc/yuvomi)  │
└─────────┬────────────┘   JSON, session cookie   └───────────────────────────┘
          │ "Open in browser" for modules that are not native (ADR 0006)
          ▼
┌──────────────────────┐
│  User's browser      │ ── the PWA, the complete product
└──────────────────────┘
```

There is no server of ours anywhere in the path. The app stores nothing outside the device.

## Module layout

One Gradle module, `app`, organised by feature. Splitting into Gradle modules waits until build
time or team size makes it worth it (ADR 0001).

```
app/src/main/java/<appId>/
├── core/
│   ├── api/        YuvomiClient (OkHttp), ApiOperation and Operation (core, Tasks and
│   │               shared kitchen calls), CSRF store, typed wrappers AuthApi,
│   │               HouseholdApi, TasksApi, KitchenApi, models
│   ├── data/       ResponseCache: last good answers as JSON files (ADR 0007)
│   ├── session/    SessionManager, server address rules, cookie jar, Keystore
│   │               secret box, settings (DataStore), version gate
│   ├── time/       household time: "today" in timezone_effective
│   ├── i18n/       loader for the upstream locale JSON, t(key, args), plurals, RTL
│   ├── design/     theme built from upstream tokens, module tones, typography,
│   │               shared components (member avatar, status chip, empty state)
│   └── browser/    "Open in browser" helper (Custom Tabs)
├── feature/
│   ├── Features.kt the native modules beyond Overview and Tasks, and every operation (ADR 0015)
│   ├── shell/      root, navigation bar/rail, Feature interface, account sheet, "More", gates
│   ├── signin/     server URL, password, TOTP
│   ├── overview/   widget normaliser, overview builder, today sheet, tiles
│   ├── tasks/      list, grouping, filters, detail, editor, recurrence, mentions,
│   │               checklists, Markdown
│   └── shopping/, meals/, recipes/, notes/, pantry/
│                   one package each: screens, view models, models, API wrapper,
│                   <Module>Operation, <Module>Routes (ADR 0015)
├── YuvomiApp.kt    AppContainer: manual dependency wiring
└── MainActivity.kt
app/src/main/assets/
├── i18n/<locale>.json   generated from upstream (ADR 0011)
├── i18n-android/        the app's own strings; <module>/<locale>.json per native module
└── tokens.json          generated from upstream (ADR 0002)
upstream/                pin, snapshots and sync scripts (ADR 0008)
```

## Runtime layering

```
Compose screen ──events──▶ ViewModel ──▶ TasksApi / HouseholdApi ──▶ YuvomiClient (OkHttp)
      ▲                        │
      └────── StateFlow ◀──────┴──▶ ResponseCache (last good answer, JSON files)
```

- **Screens** are stateless composables that render a `UiState` and emit events.
- **ViewModels** own one screen each, expose a single `StateFlow<UiState>`, and survive
  configuration changes. No state is shared between screens except through repositories.
- **ViewModels** follow cache-then-network for reads: show the cached copy at once, fetch, store,
  show again. Writes go straight to the network and refresh on success (ADR 0007). The logic
  that decides what to show (`OverviewBuilder`, `TaskGrouping`, `TaskBodies`, `RRule`, ...) is
  pure Kotlin and unit tested without Android.
- **Session** is the one app-wide state holder: server URL, current user, permissions,
  household timezone. It is the only place that knows about sign-out.

Dependency wiring is manual (`AppContainer` in `YuvomiApp.kt`) until it hurts; Hilt is the planned
next step, not a day-one dependency.

## Cross-cutting rules

These come straight from how upstream behaves, and each one has a matching acceptance criterion in
the specs.

1. **The server is the gate.** Permissions from `/auth/me` only hide or disable UI. They are
   fail-open, like the PWA's `permissions.js`: no permissions loaded means nothing is hidden. A
   `403` from the server is always shown, never swallowed.
2. **Unknown fields are ignored, missing fields are tolerated.** Every model decodes with
   `ignoreUnknownKeys` and nullable or defaulted properties, so a new upstream field never crashes
   the app and an omitted one degrades a single row, not the screen.
3. **"Today" is the household's day.** Date keys (`YYYY-MM-DD`) are computed in
   `timezone_effective` from `/preferences`, never in the device zone and never by slicing an
   ISO string. This mirrors upstream's `todayKey()` rule.
4. **Error reasons drive the message.** Upstream answers known failures with a stable `reason`
   (for example `tasks_task_not_found`, `task_locked`). The app maps `reason` to a localized
   message first and falls back to a generic one. It never shows the English `error` text.
5. **A household of one asks fewer questions.** Upstream hides assignee and visibility pickers when
   the household has a single member. So do we, using `householdSize` from `/auth/me`.
6. **A 404 or 405 on a known operation disables the feature** and shows a "needs a newer client"
   state instead of retrying, as upstream's `MODULES.md` advises third-party clients.

## What is deliberately not here

- Push or local notifications (ADR 0005).
- Single sign-on, API-token sign-in, QR pairing (ADR 0004).
- Any write queue, background sync or conflict resolution (ADR 0007).
- Any module other than Overview and Tasks as native screens (ADR 0010).
- Wall mode, displays, settings beyond sign-out and language.

## Specs

Each feature has a spec that names the exact upstream operations it uses, the behaviour, and the
acceptance criteria that become tests.

- [Sign-in and session](spec/signin.md)
- [Overview](spec/overview.md)
- [Tasks](spec/tasks.md)
- [Upstream sync](UPSTREAM-SYNC.md)
- [Parity tracker](PARITY.md)
- [Implementation plan](PLAN.md)
