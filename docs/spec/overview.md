# Spec: Overview

Status: ready for implementation · Upstream checked: 2.87.0 · Decisions: ADR 0002, 0006, 0007,
0010

## Purpose

The first screen after sign-in, answering "what matters today" for this member in two seconds, as
upstream's overview does. It shows the member's own tile arrangement from upstream. Task tiles are
interactive; every other tile is read-only and opens its module in the browser.

## Scope

**In:** greeting, the "Today at a glance" sheet in its phone form (a list, no timeline), the
member's visible tiles in their saved order, native renderers for seven tiles, a generic tile for
the rest, pull to refresh, offline cache, navigation to Tasks and to the web app.

**Out:** customize mode (rearranging, resizing, hiding tiles, household default), widget options
editing, the wide-screen timeline, wall mode, quick links management, weather masthead.
Customising happens in the web app and is picked up on the next refresh.

## Upstream references

- `docs/SPEC.md`, "The today sheet" and "The board belongs to the member" (dashboard section).
- `public/pages/dashboard.js` (`greeting()`, today sheet), `public/utils/dashboard-widgets.js`
  (`WIDGET_IDS`, `normalizeDashboardConfig`, `DEFAULT_HIDDEN_WIDGETS`, `defaultWidgetSize`).
- `server/routes/dashboard.js` and the description of `GET /dashboard` in
  `server/openapi/paths/dashboard.js`.

The dashboard answer has **no response schema** upstream: only its top-level keys are described
in prose. This spec therefore names every inner field it reads, the contract tests pin them, and
each tile degrades on its own when its fields are missing (OVERVIEW-R12).

## Upstream operations

| Method | Path | Envelope | Reads |
|---|---|---|---|
| GET | `/dashboard` | bare | see the per-tile table below, plus `users` |
| GET | `/preferences` | `data` | `dashboard_widgets`, `dashboard_today_glance`, `disabled_modules`, `timezone_effective` |
| PATCH | `/tasks/{id}/status` | `data` | ticking off from the tasks tile (as specified in [tasks.md](tasks.md), TASKS-R20) |

`GET /dashboard` is called **without query parameters** in the MVP. The PWA derives filters
(`events_scope`, `tasks_category`, `notes_category`, `events_limit`, `calendars`, ...) from each
tile's saved `options`; translating those is a follow-up (OVERVIEW-R15).

## Layout

```
┌──────────────────────────────────────┐
│ Overview                    (avatar) │  top app bar, large title collapses on scroll
│ Good morning, Alex                   │  greeting (OVERVIEW-R2)
├──────────────────────────────────────┤
│ Today at a glance                    │  today sheet (OVERVIEW-R3..R6), if switched on
│  ● 09:30  Dentist (Emma)             │
│  ☐ Take out the bins      overdue    │
│  🍽 Dinner: Lasagne                   │
│  🛒 12 items on 2 lists            › │
│  +3 more                             │
├──────────────────────────────────────┤
│ [Tasks tile]                         │  tiles in saved order (OVERVIEW-R7..R11)
│ [Calendar tile]                      │  one column on phones,
│ [Family tile]                        │  two columns from 600 dp width
│ [Birthdays tile]                     │
│ …                                    │
└──────────────────────────────────────┘
 Overview · Tasks · More in the web app   bottom navigation (navigation bar / rail ≥ 600 dp)
```

## Requirements

### Screen

- **OVERVIEW-R1** The overview is the start destination. Top app bar: `dashboard.title`, the
  member's avatar opening the account sheet ([signin.md](signin.md), SIGNIN-R16).
- **OVERVIEW-R2** Greeting under the title: `dashboard.greetingMorning` from 05:00 to 11:59,
  `dashboard.greetingDay` from 12:00 to 17:59, `dashboard.greetingEvening` otherwise, with the
  member's first name (`display_name` up to the first space). The hour is read in
  `timezone_effective`, as upstream's `nowFields()` does.

### Today at a glance

- **OVERVIEW-R3** Shown when `dashboard_today_glance` is not `false`. Title `dashboard.todayTitle`.
- **OVERVIEW-R4** Rows, in this order. A source contributes rows only if its module is accessible
  (OVERVIEW-R9) **and its tile is not visible on the board**: upstream shows each domain either in
  the sheet or as a tile, never both (`sourceSpeaks()` in `public/utils/today-sheet.js`). Upstream
  has more sources (medication doses, approvals, waste, birthdays, pantry, shifts, reminders,
  housekeeping); the MVP implements these four:
  1. Appointments from `upcomingEvents` that belong to today in `timezone_effective` and have not
     ended: all-day ones first, then by `start_datetime`. A running one says
     `dashboard.todayTimelineRunning` instead of its start time.
  2. Tasks from `urgentTasks` that are overdue or due today (`due_date` ≤ today), keeping the
     server's order. Each has a checkbox (OVERVIEW-R10).
  3. Today's meals from `todayMeals`, one row per meal: meal type and `title`.
  4. Shopping: one row with `shoppingOpenCount` items (`dashboard.todayShoppingCount`) when it is
     above zero, opening the web app's `/shopping`.
- **OVERVIEW-R5** At most five rows are shown; the rest fold behind `dashboard.todayMore`
  (`+N more`), which expands in place.
- **OVERVIEW-R6** With no rows the sheet shows `dashboard.todayFree`.

### Tiles

- **OVERVIEW-R7** Tile list = `dashboard_widgets` from `/preferences`, normalised as upstream's
  `normalizeDashboardConfig` does: unknown ids dropped, sorted by `order`, `visible: false`
  skipped. When the list is empty or missing, upstream's defaults apply: every id of `WIDGET_IDS`
  in that order, minus `DEFAULT_HIDDEN_WIDGETS`. The normaliser is ported with its upstream test
  cases.
- **OVERVIEW-R8** The `tasks`, `calendar`, `shopping` and `meals` tiles are hidden by default
  because the today sheet covers them (`COCKPIT_COVERED_WIDGETS`). A member who saved one of them as
  visible gets the tile, and that domain then leaves the today sheet (OVERVIEW-R4).
- **OVERVIEW-R9** A tile is shown only if its module access is not `none`, its widget permission
  is not `none`, and its module is not in `disabled_modules`. Missing permission entries mean
  allowed (fail-open, ADR 0004).
- **OVERVIEW-R10** Native tiles and what they read:

  | Tile id | Header tone | Reads | Shows | Tap |
  |---|---|---|---|---|
  | `tasks` | `module-tasks` | `urgentTasks[]` (`id`, `title`, `due_date`, `due_time`, `priority`, `status`, `assigned_users[].display_name`/`color`/`avatar_data`), `openTaskCount`, `overdueTaskCount` | up to 5 tasks with checkbox, due label, assignee avatars; counts in the header | row: task detail; header: Tasks list; checkbox: tick off |
  | `calendar` | `module-calendar` | `upcomingEvents[]` (`id`, `title`, `start_datetime`, `end_datetime`, `all_day`, `color`, `location`) | up to 5 coming appointments grouped by day | browser `/calendar?open=<id>` |
  | `shopping` | `module-shopping` | `shoppingTile.lists[]` (`id`, `name`, `open_count`), `shoppingTile.openCount`; else `shoppingOpenCount`, `shoppingOpenLists` | open count, up to 3 lists with their counts | browser `/shopping` |
  | `meals` | `module-meals` | `todayMeals[]` (`meal_type`, `title`) | today's meals by type | browser `/meals` |
  | `birthdays` | `module-birthdays` | `birthdays[]` (`name`, `kind` = `birthday` or `name_day`, `days_until`, `next_age`, `next_date`), `birthdayTotal` | up to 5, "today", "tomorrow" or "in N days", the coming age when known | browser `/birthdays` |
  | `family` | `module-dashboard` | `users[]` (`id`, `display_name`, `avatar_color`, `avatar_data`), `memberTodayTasks[]` (`user_id`, `open_count`) | each member with avatar and open tasks due by today | member row: Tasks list filtered to that member |
  | `countdown` | `module-dashboard` | `countdowns[]` (`source`, `id`, `title`, `date`, `days_until`, `color`, `person_name`), `countdownTotal` | "Key dates", OVERVIEW-R17 | row: its task, event or document |

  Every other tile id renders the **generic tile**: module icon and name in the module tone, one
  line `android.overview.openInWebApp`, tap opens the module's web route in the browser
  (ADR 0006). The `countdown` tile is native since 2.84.0 (OVERVIEW-R17); contract test
  OVERVIEW-A6 prints the item fields.
- **OVERVIEW-R11** Ticking a task off in the today sheet or the tasks tile follows
  [tasks.md](tasks.md) TASKS-R20 to R23 (who did it, points, recurring follow-up, undo), then
  refreshes the overview.
- **OVERVIEW-R12** A tile whose fields are missing, `null`, or the wrong type renders the generic
  tile instead and logs once per session; it never takes the screen down. A top-level answer that
  is not JSON is a load error for the whole screen.

- **OVERVIEW-R17** "Key dates" (`dashboard.countdownTitle`, `renderCountdowns`): the rows of
  `countdowns[]` as the server sorts them (nearest first, expired ones within the grace period on
  top): marked events and tasks and, since 2.84.0, active documents within their expiry lead time
  (DOCS-R38, `documentCountdowns` in services/countdowns.js). A row's module (`event` calendar,
  `task` tasks, `document` documents, `COUNTDOWN_MODULE`) must not be switched off
  (`disabled_modules`) or `none`; the server already leaves out what the member may not see and
  archived documents. Up to 5 rows: the source's glyph in the event's colour or the module tone,
  the title, one meta line with the relative date (`relativeDateLabel`, left out when the count
  already says it) and the document's `person_name`, joined with " · ", and the count
  (`countdownPhrase`: `common.today`, `common.tomorrow`, `dashboard.daysLeft` up to 30 days, then
  `dashboard.countdownWeeks` to 60, `dashboard.countdownMonths` to 364, `dashboard.countdownYears`;
  `dashboard.countdownOverdue` after the date), toned by `countdownRank` (danger, warning, text,
  secondary). The header counts `countdownTotal`; `dashboard.countdownMore` names what is not
  shown, against that total. A task row opens the task, an event the event
  (`CalendarRoutes.event`), a document the viewer (`DocumentsRoutes.document`). Without a row
  left the tile is not there at all (`countdownAvailable`); a missing or malformed `countdowns`
  is the generic tile (OVERVIEW-R12).

- **OVERVIEW-R18** The phone arrangement (2.85.0, #2109): when `dashboard_widgets_phone` in
  `/preferences` is a list, the overview takes its tile order and visibility from it, while
  size and options follow `dashboard_widgets` (`mergePhoneDisplayCfg`); `null` follows the
  desktop arrangement. Arranging the phone tiles stays in the web app.

### Loading, refresh, offline

- **OVERVIEW-R13** Opening the screen shows the cached overview at once when there is one, then
  loads `/preferences` and `/dashboard` in parallel and replaces it. Without a cache it shows
  skeleton tiles shaped like the saved layout.
- **OVERVIEW-R14** Pull to refresh reloads both. The screen also refreshes when it returns to the
  foreground after more than 5 minutes, and when the date changes in `timezone_effective`.
- **OVERVIEW-R15** Tile `options` from `dashboard_widgets` are ignored in the MVP, so filtered
  tiles may show more than in the PWA. The overview shows a one-time hint
  `android.overview.filtersIgnored` when any visible tile has options.
- **OVERVIEW-R16** Offline: cached content with the offline banner (ADR 0007); checkboxes are
  disabled and say `offline` on tap.

## Acceptance criteria

| ID | Given / When / Then | Level |
|---|---|---|
| OVERVIEW-A1 | Given household zone `America/Los_Angeles` and device zone `Europe/Berlin` at 07:00 UTC then the greeting is the evening one (23:00 in LA). | unit |
| OVERVIEW-A2 | Given `dashboard_widgets` is `null` then the tiles are upstream's default set and order, without the hidden ones. | unit |
| OVERVIEW-A3 | Given the normaliser cases from upstream's `test/test-dashboard.js` ("Widget-Konfiguration") then the ported normaliser gives the same results. | unit |
| OVERVIEW-A4 | Given the seeded server signed in as `alex` when `/dashboard` is fetched then `urgentTasks`, `openTaskCount`, `upcomingEvents`, `todayMeals`, `users`, `birthdays` exist with the fields this spec reads, with the types the models expect. | contract |
| OVERVIEW-A5 | Given signed in as `leo` (child, no budget) then `/dashboard` still decodes and the budget tile, if saved as visible, is not shown. | contract + ui |
| OVERVIEW-A6 | Given the seeded server then `countdowns[]` items decode into the countdown model; the test documents the field names. | contract |
| OVERVIEW-A7 | Given `todayMeals` is `"oops"` instead of a list then the meals tile renders as the generic tile and every other tile still renders. | unit + ui |
| OVERVIEW-A8 | Given a cached overview and no network when the app starts then the cached tiles and the offline banner show within one frame of the first draw. | ui |
| OVERVIEW-A9 | Given 7 rows for the today sheet then 5 show and `+2 more` expands to 7. | ui |
| OVERVIEW-A11 | Given the `tasks` tile saved as visible then the today sheet has no task rows and the tasks tile shows them; given it hidden then the sheet has them. | unit |
| OVERVIEW-A12 | Given `countdowns` with an event, a task and a document about Emma then "Key dates" shows them with the count phrases and "… · Emma", and "+N more" against `countdownTotal`; with Documents switched off the document row is gone; with no row left the tile is gone. | unit + ui |
| OVERVIEW-A13 | Given a document row in "Key dates" when it is tapped then the documents viewer route `documents?open=<id>` opens. | ui |
| OVERVIEW-A10 | Given the calendar tile when an appointment is tapped then the browser opens `<server>/calendar?open=<id>`. | ui |
| OVERVIEW-A14 | Given a phone arrangement with the calendar first and shopping hidden then the overview orders and hides tiles by it, with the desktop's sizes; without one it follows the desktop. | unit |

## Strings

App-only strings live in `app/src/main/assets/i18n-android/en.json`; the original list was:

`android.overview.openInWebApp` "Open in the web app" · `android.overview.filtersIgnored` "Tile
filters you set in the web app are not applied here yet." · `android.overview.tileUnavailable`
"This tile could not be shown."
