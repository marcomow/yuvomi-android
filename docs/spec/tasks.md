# Spec: Tasks

Status: ready for implementation · Upstream checked: 2.84.0 · Decisions: ADR 0003, 0006, 0007,
0010

## Purpose

The household's tasks as a native Android screen: see what is open, add and edit tasks with the
same fields and rules as the web app, tick them off and name who did it, work through subtasks and
checklists, and talk about a task in its comments.

## Scope

| Tier | In this spec | Requirement IDs |
|---|---|---|
| 1 | List with grouping and filters; create, edit, delete; title, description, start and due date, due time, priority, category, tags, assignees, points, visibility, lock; tick off with "who did it" and undo; recurring tasks shown read-only | R1–R27 |
| 2 | Edit recurrence; subtasks; tick description checklists; comments with mentions; linked documents read-only | R28–R40 |

**Out (tier 3, stays in the browser):** Kanban board, manual order, archive and restore, bulk
actions, completion history, tag rename and delete, category management, default points rebase,
CalDAV sync target, document upload and linking, the countdown flag (kept as it is on edit).

## Upstream references

- `docs/SPEC.md`: "Tasks", "Task Tags", "Task Categories", "Task Assignments", "Task Documents",
  "Task Comments", "Task Completions".
- `server/routes/tasks.js` and `server/openapi/paths/tasks.js`.
- `public/pages/tasks.js` (`groupBy`, `DEFAULT_STATUS_FILTER`, `toggleTaskStatus`),
  `public/components/task-detail.js` (who did it), `public/utils/task-fields.js`
  (`seriesDoneText`), `public/rrule-ui.js` (`parseRRule`, `buildRRule`).

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/tasks` | `data` | the list, with `status`, `priority`, `assigned_to`, `category`, `tag` (each repeatable), `include_future` |
| GET | `/tasks/meta/options` | **bare** | `users[]` (`id`, `display_name`, `avatar_color`, `sort_order`), `priorities[]`, `statuses[]`, `categories[]` (`key`, `name`, `label_key`, `sort_order`), `tags[]` (`tag`, `count`), `default_points` |
| GET | `/tasks/{id}` | `data` | detail: the task with full `subtasks[]` and `documents` |
| POST | `/tasks` | `data`, 201 | create a task or a subtask (`parent_task_id`) |
| PUT | `/tasks/{id}` | `data` | edit; **partial**: omitted fields keep their stored value |
| DELETE | `/tasks/{id}` | bare `{ ok }` | delete |
| PATCH | `/tasks/{id}/status` | `data` | tick off, reopen, set in progress; reads `next_due_date` |
| PATCH | `/tasks/{id}/check` | `data` | tick one `- [ ]` line of the description |
| GET | `/tasks/{id}/comments` | `data` | comments, oldest first |
| POST | `/tasks/{id}/comments` | `data`, 201 | add a comment |
| PATCH | `/tasks/{id}/comments/{commentId}` | `data` | edit own comment |
| DELETE | `/tasks/{id}/comments/{commentId}` | bare | delete own comment (admins: any) |
| GET | `/preferences` | `data` | `tasks_default_visibility`, `tasks_default_locked`, `timezone_effective` |

### The task model (fields read)

| Field | Type | Notes |
|---|---|---|
| `id` | int | |
| `title` | string | required |
| `description` | string? | Markdown; `- [ ]` / `- [x]` lines are a checklist |
| `status` | `open` \| `in_progress` \| `done` | |
| `priority` | `none` \| `low` \| `medium` \| `high` \| `urgent` | |
| `category` | string | a category `key` |
| `tags` | string[] | free text, at most 32 of 64 characters, case-insensitive unique |
| `start_date` | `YYYY-MM-DD`? | a future start hides the task unless `include_future` |
| `due_date` | `YYYY-MM-DD`? | |
| `due_time` | `HH:MM`? | |
| `assigned_users` | `{ id, display_name, color, avatar_data }[]` | `avatar_data` as in [signin.md](signin.md) SIGNIN-R18 |
| `created_by` | int | for the lock rule |
| `points` | int | 0 to 10000 |
| `visibility` | `all` \| `assignees` \| `private` | |
| `locked` | 0/1 | |
| `is_recurring` | 0/1 | |
| `recurrence_rule` | string? | RRULE body, possibly with a `RRULE:` prefix |
| `recurrence_from_completion` | 0/1 | |
| `parent_task_id` | int? | subtasks only; two levels at most |
| `sort_order` | int? | read for tier 3 parity only; the app does not reorder |
| `archived_at` | string? | archived tasks are excluded by default; the app never asks for them |
| `subtask_total`, `subtask_done` | int | list only, already visibility-filtered |
| `subtasks` | list: `{ id, title, status }[]`; detail: full tasks | |
| `document_count` | int? | `null` means "not allowed to know", not zero |
| `documents` | `{ id, ... }[]`? | detail only; `null` without Documents access |
| `countdown` | 0/1 | never sent by the app; kept by partial PUT |

Error `reason` values the app maps to messages: `tasks_task_not_found` (404), `task_locked`
(403), `tasks_parent_not_found` (404), `tasks_comment_not_found` (404), `cross_module_access`
(403), and the `409` of `/check` when the line moved.

## Tier 1 requirements

### List

- **TASKS-R1** The Tasks destination shows a top app bar with `tasks.title`, a filter action, a
  grouping toggle, and a FAB `tasks.newTask`.
- **TASKS-R2** On open it loads `GET /tasks/meta/options` and `GET /tasks` in parallel, cache
  first (ADR 0007).
- **TASKS-R3** Default filter: `status=open` only, matching upstream's `DEFAULT_STATUS_FILTER`.
  Tasks in progress and done ones are one filter tap away.
- **TASKS-R4** Grouping, as upstream's `groupBy`:
  - **By category** (default): one section per category in `sort_order`, then by label.
  - **By due date**: `tasks.groupOverdue` (before today), `tasks.groupToday`,
    `tasks.groupThisWeek` (1 to 3 days ahead), `tasks.groupNextWeek` (4 to 7 days),
    `tasks.groupLater`, `tasks.groupNoDate`. "Today" is computed in `timezone_effective`.
  Sections collapse; the collapsed state and the grouping are remembered per device.
- **TASKS-R5** Order inside a section is the server's order (status, priority, due date, newest),
  except in category grouping, where tasks with a `sort_order` come first in that order, as the
  PWA's manual sort does.
- **TASKS-R6** A row shows: checkbox, title, due label (overdue in the danger colour), priority
  mark for `high` and `urgent`, up to three assignee avatars, up to three tag chips then `+N`,
  a subtask count `subtask_done/subtask_total` when there are subtasks, a paperclip with
  `document_count` when above zero, a repeat mark when `is_recurring`, a lock mark when `locked`,
  a people or lock mark for `assignees` or `private` visibility, and points when above zero.
- **TASKS-R7** Category labels: `label_key` translated through upstream's strings when present,
  else `name`.
- **TASKS-R8** Filter sheet: status (open, in progress, done), priority, assignee, category,
  tags (from `meta/options.tags`), and "show tasks that start later" (`include_future=1`). Several
  values of one kind are OR-ed; several tags must all match, as upstream does. The active filter
  count shows on the filter action; "Reset filters" returns to the default.
- **TASKS-R9** Pull to refresh reloads options and list. The list also reloads after every write
  the app makes and when the screen returns to the foreground after more than 60 seconds.
- **TASKS-R10** Empty states: `tasks.emptyTitle` with no tasks at all; a "no tasks match"
  message with "Reset filters" when filters hide everything.
- **TASKS-R11** A member with `tasks` access `read` sees the list and details, but no FAB, no
  checkboxes, no edit or delete actions; upstream's read-only sign takes their place. Access
  `none` removes Tasks from the navigation.

### Create and edit

- **TASKS-R12** The editor is a full-screen sheet with, in the PWA's order: title (required),
  description (multi-line, Markdown as plain text), category, priority, start date, due date, due
  time, assignees (multi-select), tags (chips with suggestions from `meta/options.tags`, free
  entry), points, visibility, lock. Recurrence shows read-only in tier 1 (TASKS-R26).
- **TASKS-R13** New task defaults: category `misc` unless the list is grouped by category and the
  FAB was used inside a section; priority `none`; points omitted from the request so the server
  applies `default_points`; visibility from `tasks_default_visibility` (default `all`); lock from
  `tasks_default_locked`. With a filter on one assignee, that member is preselected.
- **TASKS-R14** In a household of one (`householdSize` 1) the assignee and visibility fields are
  hidden and their values left untouched, as upstream does.
- **TASKS-R15** Create sends `POST /tasks` with `title`, `description`, `category`, `priority`,
  `start_date`, `due_date`, `due_time`, `assigned_to` (array of user ids), `tags` (array),
  `points` (only when the user changed it), `visibility`, `locked`, and an `Idempotency-Key`.
- **TASKS-R16** Edit sends `PUT /tasks/{id}` with only the fields the user changed. Untouched
  fields, including `countdown`, `recurrence_*`, `sync_target`, are never sent.
- **TASKS-R17** Lock: when `locked` is 1 and the member is neither `created_by` nor an admin
  (`permissions.admin`), the editor opens read-only except for assigning or unassigning
  themselves; tick-off and comments stay available. The hint `tasks.lockedHint` explains why. A
  `403 task_locked` from the server is shown with the same hint.
- **TASKS-R18** Validation before sending: title not blank; due time requires a due date; start
  date not after due date; points an integer from 0 to 10000. A `400` from the server shows its
  message in a dialog rather than a toast, because it explains a rule.
- **TASKS-R19** Delete is in the editor's overflow menu, asks for confirmation naming the task,
  and is unavailable under the lock rule of TASKS-R17. A subtask's delete is on its row
  (TASKS-R30).

### Tick off, who did it, undo

- **TASKS-R20** Tapping the checkbox of an open or in-progress task sends
  `PATCH /tasks/{id}/status` `{ "status": "done" }`; on a done task, `{ "status": "open" }`. The
  row updates at once and rolls back on failure.
- **TASKS-R21** Long-pressing the checkbox, or "Done by…" in the detail, opens a member picker
  (household members from `meta/options.users`, in their order) and sends
  `{ "status": "done", "done_by_user_id": <id> }`. The confirmation names the member
  (`tasks.doneByToast`). Hidden in a household of one.
- **TASKS-R22** After ticking off, a snackbar confirms with `common.undo`, for 5 seconds. Undo
  sends `{ "status": "open" }`; upstream withdraws the points and an untouched recurring
  follow-up itself.
- **TASKS-R23** When the answer carries `next_due_date`, the confirmation names the next
  occurrence (`tasks.seriesDoneNamed`, or `tasks.seriesDoneByToast` with a named member), as
  upstream's `seriesDoneText`. The list then reloads to show the follow-up.
- **TASKS-R24** The detail offers "Start" (`in_progress`) and "Reopen" (`open`) as the PWA's
  status field does.

### Detail

- **TASKS-R25** Tapping a row opens the detail (`GET /tasks/{id}`): title, status, the row's marks
  in full words, description rendered as Markdown (headings, lists, links, bold, italic, code,
  checklists), dates, assignees with names, tags, points, created-by. Edit action in the top bar.
  A `404 tasks_task_not_found` closes the detail with "This task was deleted or is no longer
  visible to you." and reloads the list.
- **TASKS-R26** Recurring tasks (tier 1): the detail and editor show the rule in words,
  generated from `recurrence_rule` the way the PWA's `rrule-ui.js` describes it, plus "repeats from
  completion" when `recurrence_from_completion` is 1. A rule the describer cannot read shows
  "Repeats (custom rule)". Editing it is tier 2.
- **TASKS-R27** Deep links: `yuvomi-android://tasks/{id}` opens a task's detail; the overview uses
  it internally.

## Tier 2 requirements

### Recurrence editing

- **TASKS-R28** The editor's "Repeat" field offers the PWA's task vocabulary: never, daily, weekly
  (with weekdays), monthly (on the start day, or on the last day `BYMONTHDAY=-1`), yearly; every
  N; ends never or on a date (`UNTIL` as a plain date); and "repeat from completion". Upstream's
  task form offers no "after N times" (`allowCount` is off for tasks), so neither does the app;
  a stored `COUNT` is kept by TASKS-R29. Saving sends `is_recurring`, `recurrence_rule` (body
  without `RRULE:` prefix) and `recurrence_from_completion`.
- **TASKS-R29** Upstream's "wording rule" (#756, `getRRuleValues` in `rrule-ui.js`): the form shows
  what it can parse. If the user does not change the repetition, the app does not send it at all,
  so parts the form cannot show (for example `BYSETPOS`, or a `BYMONTHDAY` other than `-1` from
  a CalDAV mirror) survive. When the user changes it and the parsed source builds to the same
  rule, the source goes back verbatim; otherwise the rule is rebuilt from the form. A rule with
  such parts shows the hint `android.tasks.ruleKept`.

### Subtasks

- **TASKS-R30** The detail lists `subtasks` with checkbox, title and assignee avatars; done ones
  are struck through; progress shows as `done/total`. As in the web app, the whole row is the
  checkbox: tapping it ticks the subtask (TASKS-R32). A subtask has no detail or editor of its
  own, because upstream's `GET /tasks/{id}` answers only for top-level tasks (`404
  tasks_task_not_found` for a subtask). The row's menu offers **Edit**, which renames it with
  `PUT /tasks/{id}` and the title only, and **Delete**, which asks first.
- **TASKS-R31** "Add subtask" creates one with `POST /tasks` and `parent_task_id`. The server
  inherits the lock; a `403 task_locked` follows TASKS-R17. A subtask cannot have subtasks of its
  own (two levels), so the action is absent in a subtask's detail.
- **TASKS-R32** Ticking a subtask uses `PATCH /tasks/{id}/status` as in TASKS-R20, without the
  "who did it" picker and without undo snackbar (a subtask records no completion upstream).

### Description checklists

- **TASKS-R33** `- [ ]` and `- [x]` lines in the rendered description are tappable. A tap sends
  `PATCH /tasks/{id}/check` with `line` (zero-based source line), `checked`, and `expect` (the
  line as currently shown). The answer's `data.description` replaces the description. This works
  on locked tasks, as upstream intends. Which lines count as checklist items follows upstream's
  `public/utils/markdown-checklist.js` (`matchChecklistLine`), ported with its tests.
- **TASKS-R34** A `409` means someone else changed that line: the app reloads the task and says
  "This list changed in the meantime. Try again."

### Comments

- **TASKS-R35** The detail has a comments section loaded from `GET /tasks/{id}/comments`: author
  avatar and name (`author_name`, `author_color`), relative time from `created_at`, "edited" when
  `updated_at` is set, text with `@Name` mentions highlighted.
- **TASKS-R36** A composer at the bottom posts `{ "comment" }` (at most 5000 characters) with an
  `Idempotency-Key`. Typing `@` suggests household members; the longest matching display name
  wins, case-insensitively, and an `@` after a word character is not a mention, as upstream's
  `public/utils/mentions.js` (ported with its tests). Notifying mentioned members is the server's
  job.
- **TASKS-R37** Own comments can be edited (`PATCH`) and deleted (`DELETE`, with confirmation).
  Admins can delete anyone's comment. A `404 tasks_comment_not_found` reloads the comments.

### Linked documents

- **TASKS-R38** When `documents` is a non-empty list, the detail shows them by name with a file
  type icon. Tapping one opens the web app's Documents view of it in the browser (ADR 0006).
- **TASKS-R39** When `documents` is `null`, the section is not shown at all; the member has no
  Documents access, and "no documents" would be a false statement.
- **TASKS-R40** Linking, unlinking and uploading documents are tier 3 and offered as
  "Manage in the web app".

## Acceptance criteria

| ID | Given / When / Then | Level |
|---|---|---|
| TASKS-A1 | Given the seeded server as `alex` when the list loads with the default filter then every task has `status` `open`, and decoding all of them succeeds. | contract |
| TASKS-A2 | Given `?tag=a&tag=b` is requested then the client sends two `tag` parameters, never `tag=a,b`. | unit |
| TASKS-A3 | Given today is 2026-11-01 in the household zone then due dates 10-31, 11-01, 11-04, 11-08, 11-09 and none fall into overdue, today, this week, next week, later and no date. | unit |
| TASKS-A4 | Given a task created as `alex` with title, two assignees, tags `Garten` and `garten` then the server stores one tag and two assignees, and the list shows the task. | contract |
| TASKS-A5 | Given an existing task with `countdown` 1 when only its title is edited then the request body contains only `title`, and after reload `countdown` is still 1. | unit + contract |
| TASKS-A6 | Given a locked task created by `alex` when `emma` edits the title then the app shows the editor read-only; when the request is forced then the server answers 403 `task_locked`. | ui + contract |
| TASKS-A7 | Given `alex` ticks off a 10-point task assigned to `emma` naming `leo` as the doer then the status is `done` and the response is a success; reopening it returns the status to `open`. | contract |
| TASKS-A8 | Given a weekly recurring task when it is ticked off then `next_due_date` is a date 7 days after the old due date, the snackbar names it, and undo removes the follow-up. | contract + ui |
| TASKS-A9 | Given a private task of `linda` then it is not in `alex`'s list and `GET /tasks/{id}` as `alex` is 404. | contract |
| TASKS-A10 | Given `leo` has tasks access `read` then the list shows no FAB and no checkboxes. | ui |
| TASKS-A11 | Given a stored `FREQ=MONTHLY;BYSETPOS=3;BYDAY=TH` then an edit of another field sends no `recurrence_rule`, and an unchanged form resolves to the source verbatim; a changed frequency rebuilds it. | unit |
| TASKS-A12 | Given the `parseRRule`/`buildRRule` cases from upstream's `test:rrule-ui` then the port gives the same results. | unit |
| TASKS-A13 | Given a description with two checklist lines when line 2 is ticked then `/check` gets `line` 1, `checked` true and `expect` equal to the shown line, and the description updates. | contract |
| TASKS-A14 | Given the cases of upstream's mention parser tests then the ported parser finds the same members. | unit |
| TASKS-A15 | Given `emma` comments on a task then `alex` sees the comment and cannot edit it, but can delete it as an admin. | contract |
| TASKS-A16 | Given a member without Documents access then `documents` is `null` and the detail has no documents section. | contract + ui |
| TASKS-A17 | Given a network failure on create then a retry sends the same `Idempotency-Key` and the server holds one task. | contract |
| TASKS-A18 | Given a household of one then the editor has no assignee or visibility field and a saved edit sends neither. | ui |

## Strings

App-only strings live in `app/src/main/assets/i18n-android/en.json`; the original list was:

`android.tasks.noMatch` "No tasks match these filters." · `android.tasks.gone` "This task was
deleted or is no longer visible to you." · `android.tasks.checklistChanged` "This list changed in
the meantime. Try again." · `android.tasks.ruleInWebApp` "Edit this rule in the web app" ·
`android.tasks.ruleCustom` "Repeats (custom rule)" · `android.tasks.manageInWebApp` "Manage in
the web app"

Everything else reuses the PWA's keys from `tasks`, `rrule`, `common`, `category`,
`userMultiSelect` and `markdown`. Where the PWA takes a label from another namespace (visibility
labels, for example), that namespace is added to `upstream/i18n-namespaces.txt` and re-synced.
