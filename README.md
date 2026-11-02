# Yuvomi for Android (unofficial)

> **An unofficial proof of concept.** This is not part of the
> [Yuvomi](https://github.com/ulsklyc/yuvomi) project and is not endorsed by it. Yuvomi's web app
> remains the complete product; this repository explores a native Android client next to it.

A native Android client for a self-hosted Yuvomi family planner. It talks to your own
server's documented `/api/v1`, with no cloud of ours in between, and opens the web app in your
browser for everything it does not do natively yet. The one exception is the update check:
it asks GitHub about this app's releases, and you can switch that off
([ADR 0018](docs/adr/0018-in-app-updates.md)).

**Status:** the MVP (sign-in, overview, tasks) has been tried on a phone. Shopping, Meals,
Recipes, Notes and Pantry are implemented and tested with unit, Robolectric and contract tests,
but not yet tried by hand. Contract tests against a real upstream server run in
CI. See the [plan](docs/PLAN.md) for what is next.

## Install

Download `yuvomi-android-<version>.apk` from the latest
[release](https://github.com/marcomow/yuvomi-android/releases) on the phone and open it
(Android 8.0 or newer; allow installing from your browser when asked). Releases are
pre-releases of a proof of concept; every change to the app merged into `main` publishes a new
one. How they are made: [RELEASING.md](docs/RELEASING.md).

After that, the app updates itself: open your account (the avatar at the top right), tap
**Install** when a newer version is shown, and allow the app to install updates the first time
Android asks. It checks on its own about twice a day unless you switch "Check automatically" off.

## What is native

- Sign in with your Yuvomi username and password, plus your second factor if you use one.
- **Overview:** your own tile arrangement from the web app, today at a glance, tasks you can tick
  off right there.
- **Tasks:** list, filters, create and edit with every field the web app has, tick off and name
  who did it, recurring tasks, subtasks, checklists and comments.
- **Shopping:** several lists, quick add with suggestions, instant ticking that works on a weak
  signal, categories and shops, sending a list, meal plan import, moving checked items to the
  pantry.
- **Meals:** the week plan with the household's meal types, meals from recipes, ingredients,
  a random plan, sending ingredients to a shopping list.
- **Recipes:** search, pictures, servings scaling, pantry links, planning as a meal, adding to a
  shopping list, the full editor.
- **Notes:** the board with pinned notes, colours, categories, checklists you can tick, the
  editor with formatting.
- **Pantry:** stock by location with expiry warnings, quick quantity steps, locations, sending
  what runs low to a shopping list.
- **Calendar:** month, week, day and agenda views with tasks, schedule, waste and birthday
  layers, the full event form with recurrence and reminders, your calendars.
- **Schedule:** the shift grid per person, shift types, patterns, overrides and extra shifts,
  statistics, the feed link.
- **Documents:** folders, search and filters, upload, viewer for images, PDFs and text, open
  with or share, expiry reminders.
- **Health:** vitals with charts, medications and doses, fasting, cycle, prevention, labs,
  activity and nutrition, for you and the people you care for.
- **Budget:** the month with entries, categories, accounts, transfers, loans, plans and
  statistics; subscriptions and split expenses.
- **Settings:** your account and two-factor sign-in, language and region, modules, family and
  permissions, notification channels, e-mail, backups and API tokens.
- **Rewards:** points, requests, the rewards catalogue, pocket money and history.
- **Birthdays, Contacts:** the birthday list with name days and the calendar feed; contacts
  with call, e-mail and map buttons, categories, vCard import and export.
- **Inventory, Housekeeping, Waste:** items with tracked dates and service logs; housekeeping
  visits, chores, reports and payments; waste pickups, schedules, ICS sources and reminders.
- **Tags:** everything that shares a tag, across modules. Tasks and shopping items have real
  tags; anywhere else, a `#hashtag` in the text counts.
- Everything else opens in your browser.

Not native yet: notifications, single sign-on, third-party extension modules.

## How it is built

Kotlin, Jetpack Compose and Material 3, dressed in Yuvomi's own colours, which are taken from
upstream's design tokens, as are all 27 of its languages. Details:

- [Architecture](docs/ARCHITECTURE.md) and the [decision records](docs/adr/)
- [Specs](docs/spec/README.md): sign-in, overview, tasks, shopping, meals, recipes, notes,
  pantry
- [Parity with upstream](docs/PARITY.md)

## Build and test

Needs JDK 17 or newer and the Android SDK (compile SDK 36). `local.properties` with `sdk.dir`
points Gradle at the SDK.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit, Robolectric and end-to-end smoke tests
./gradlew lintDebug
```

CI (`.github/workflows/android.yml`) runs the same, keeps the debug APK as an artifact, and runs
the contract tests against `ghcr.io/ulsklyc/yuvomi` at the pinned version. Every app change on `main`
publishes a signed release APK (`.github/workflows/release.yml`, [RELEASING.md](docs/RELEASING.md)).

## Staying in sync with upstream

`upstream/VERSION` pins the Yuvomi release this app is built against (currently **2.83.0**). A
weekly workflow checks for a newer release, regenerates the OpenAPI snapshot, strings and colours
from upstream's source, and opens a pull request that reports which of the operations this app
uses changed. See [UPSTREAM-SYNC.md](docs/UPSTREAM-SYNC.md).

```bash
./upstream/sync.sh          # re-sync the pinned version
./upstream/sync.sh 2.84.0   # move the pin
```

## Licence

MIT, like upstream. Translations and design tokens under `app/src/main/assets/` come from
[ulsklyc/yuvomi](https://github.com/ulsklyc/yuvomi) (MIT, © 2026 ulsklyc).
