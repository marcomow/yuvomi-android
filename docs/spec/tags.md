# Spec: Tags

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

One place that gathers everything sharing a tag, across modules. App-only: the web app has no
such page; its closest are the task list filtered by a tag and the tag manager of the tasks
page. Upstream stores real tags only on tasks (`task_tags`, editable) and shopping items
(`shopping_item_tags`, read-only, mirrored from CalDAV `CATEGORIES`); everywhere else a
`#hashtag` in an item's own text counts as a tag (`Hashtags.kt`).

## Scope

| Tier | In this spec | Requirement IDs |
|---|---|---|
| 1 | Sources per module, the tag list with counts, search and "find #tag", a tag's page by module, offline cache, partial failure note, `TagChip` | R1–R8 |
| 2 | Rename and delete a real task tag, as upstream's tag manager | R9–R12 |

**Out:** adding tags to items (each module's own editor), bulk tag apply (`POST /tasks/tags/apply`,
the tasks page's multi-select), renaming hashtags inside texts.

## Upstream references

- `server/utils/task-tags.js` (`tagKey`, `normalizeTags`, `allTags`, `renameTag`, `removeTagEverywhere`).
- `server/routes/tasks.js` (`GET /tags`, `PUT /tags/:tag`, `DELETE /tags/:tag`, `GET /` `tag` filter).
- `public/components/tag-manager.js` (`_rename`, `_delete`, `withSkipped`), `public/pages/tasks.js`
  (`openTagManager`, only when not read-only).
- `public/locales/en.json`: `tasks.manageTagsHint`, `tagRenameLabel`, `tagDeleteLabel`,
  `tagDeleteConfirm`, `tagsUpdated`, `tagDeleted`, `tagsSkippedLocked`.

## Upstream operations (`TagsOperation`)

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/tasks/tags` | `data: [{ tag, count }]` | which tags are real task tags, and their count for the delete question |
| PUT | `/tasks/tags/{tag}` | `data: { updated, skipped, tag, tags }` | rename (merges onto an existing tag); 400 empty name, 404 no visible task carries it (no `reason`) |
| DELETE | `/tasks/tags/{tag}` | `data: { updated, skipped, tags }` | remove everywhere; 404 as above |

Everything else is read through the modules' own API classes (see R2).

## Requirements

- **TAGS-R1** `TagSources.all` lists one `TagSource` per module: Tasks, Calendar, Notes,
  Schedule (`ScheduleTagSource`), Meals, Recipes, Shopping, Pantry, Documents, Health, Budget.
  Settings has none. Rewards, Birthdays, Contacts, Inventory, Housekeeping, Waste, Subscriptions
  and Split expenses bring their own (registered by the integrator). A source returns nothing when
  `shell.shows(module)` is false.
- **TAGS-R2** What each source reads (`feature/tags/sources/`):
  - Tasks: `GET /tasks?status=open&status=in_progress&status=done&include_future=1`; real `tags` plus hashtags in title and description; opens `Routes.task(id)`. Subtasks are not in the list answer and are left out.
  - Shopping: `GET /shopping`, then every list's items; item `tags` plus hashtags in name and notes (opens `ShoppingRoutes.item`); a list whose name has a hashtag (opens the list).
  - Notes: title and content (`NotesRoutes.note`). Recipes: title and notes (`RecipesRoutes.recipe`).
  - Meals: title and notes, weeks −4 to +8 (`GET /meals?week=`, upstream reads a week at a time); opens the week.
  - Pantry: batch name and notes; opens the pantry list (the item form only opens on top of its list).
  - Calendar: title, description, location of events one year back to one year ahead; a series is one item, showing its next occurrence; `CalendarRoutes.event(id)`.
  - Documents: active documents' name and description (`DocumentsRoutes.document`).
  - Health: the member's OWN records only (no `user_id`): vitals, medications, preventive records, lab reports, activities, nutrition (last year), and periods and cycle days when the cycle area is on; each opens its area. Fasting is left out (its history is paged).
  - Budget: entry titles of the current and eleven previous months (both scopes in the personal mode); a series is one item; opens the budget page (no per-entry route).
- **TAGS-R3** All sources load in parallel. A failing source keeps its items from the last good
  answer and is named in a note with Retry. When every failure is a lost connection, the last
  index is shown read-only with `OfflineBanner` (ADR 0007). The merged index is cached
  (`tags-index`). The list refreshes when older than a minute.
- **TAGS-R4** The list shows each tag once by `TagKey` (upstream's `tagKey`), in the spelling most
  items use, with its item count and the icons of the modules it appears in; most used first,
  then by name (as `allTags`).
- **TAGS-R5** A search field narrows the list by key; any typed tag that is not exactly a listed
  one is offered as "Show everything tagged #tag".
- **TAGS-R6** A tag's page (`tags/{tag}`) groups its items by module (ModuleCatalog order) under
  the module's badge in its tone, items by title.
- **TAGS-R7** Tapping an item opens its native route (or its web path when it has no route).
- **TAGS-R8** An item's other tags show as `TagChip`s; `TagChip(tag)` is a reusable composable
  that opens `TagsRoutes.tag(tag)`.
- **TAGS-R9** Rename and delete are offered only for a real task tag (in `GET /tasks/tags`), when
  the member has `tasks: write` and is online (upstream: `openTagManager` returns when read-only).
- **TAGS-R10** Rename: unchanged or empty name sends nothing (`_rename`); `.`/`..` are refused
  locally (`normalizeTags` drops them, the server answers 400). On success the toast is
  `tasks.tagsUpdated` plus `tasks.tagsSkippedLocked` when locked tasks were skipped
  (`withSkipped`), and the page is replaced by the new tag's.
- **TAGS-R11** Delete asks `tasks.tagDeleteConfirm` with the task count, then toasts
  `tasks.tagDeleted` (+ skipped). Only task tags change; hashtags in texts and shopping tags stay
  (said in the dialogs).
- **TAGS-R12** Errors: 404 → "no task you can see carries this tag any more" and a reload; 400 →
  "enter a name"; 403 → forbidden; network → offline. (The web app shows the server's English
  text; the app never does, ARCHITECTURE rule 4.)

## Acceptance

| ID | Criterion | Status | Test |
|---|---|---|---|
| TAGS-A1 | One row per key across modules, with counts and module order | pass | TagLogicTest, TagsScreensTest |
| TAGS-A2 | Search narrows; a typed unknown tag opens an empty page | pass | TagLogicTest, TagsScreensTest |
| TAGS-A3 | A tag's page groups by module; an item opens its route; chips open other tags | pass | TagsScreensTest |
| TAGS-A4 | A failing source is named and keeps its last items | pass | TagsScreensTest |
| TAGS-A5 | Delete of a task tag asks, calls DELETE, toasts, then management disappears | pass | TagsScreensTest |
| TAGS-A6 | Rename rules and messages as the tag manager | pass | TagLogicTest |
| TAGS-A7 | Offline: cached index with the banner | pass | TagsScreensTest |
| TAGS-A8 | Each source turns its module's data into the right items and routes | pass | TagSourcesTest |
| TAGS-A9 | The three task tag operations behave as above against the real server | CI | TagsContractTest |
