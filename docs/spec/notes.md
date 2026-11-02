# Spec: Notes

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's board of notes ("Board" in the web app) as native screens, at parity with
upstream's Notes page: read the board, find a note, tick its checklist lines, write and change
notes, pin them, sort them into categories and manage those categories.

## Scope

Everything the web page (`public/pages/notes.js`) lets a member do:

| Area | Requirement IDs |
|---|---|
| Board: load, search, creator and category filters, pinned group, cards, empty states | R1–R10 |
| Note read view, pin, delete with undo, checklist ticks | R11–R16 |
| Editor: title, Markdown text with the toolbar, read tab, colour, pin, visibility, lock, categories | R17–R22 |
| Category manager: add, rename, move, delete | R23–R26 |
| Permissions, errors, offline | R27–R30 |

**Nothing stays in the browser.** The web page's only link out is the household permission for
categories, which lives in Settings and is not part of this page. Note categories have no colour
upstream (the shared category manager offers colours only to callers that pass a palette, and
Notes passes none), so there is no category colour here either.

**Native differences.** The web app shows a note and its editor as one dialog over the board
with a Read and an Edit tab; the app shows the note as its own screen (`NotesRoutes.note(id)`,
the web's `/notes?open=<id>`), and Edit opens the editor screen, which keeps the Read and Edit
tabs. Dragging categories into order is replaced by the "Move up"/"Move down" items the web
manager's row menu also offers.

## Upstream references

- `public/pages/notes.js` (`render`, `renderFilters`, `visibleNotes`, `drawGrid`,
  `notesEmptyStateHtml`, `noteName`, `renderNoteCard`, `pinMarkup`, `renderAccessMarks`,
  `renderAccessFields`, `openNoteModal`, `openNoteReadModal`, `toggleCheck`, `dropGoneNote`,
  `togglePin`, `deleteNote`, `openNoteCategoryManager`).
- `public/utils/note-access.js`, `note-category-filter.js`, `note-category-picker.js`,
  `note-category-name.js`, `markdown-checklist.js`, `markdown-toolbar.js` (`applyFormat`),
  `html.js` (`renderMarkdownLight`, `inlineMarkdown`), `friendly-error.js` (`errorSentence`,
  `isGone`, `isAlreadyDeleted`), `permissions.js` (`canChangeRecord`, `canEditOthers`).
- `public/components/category-manager.js` (as configured by `openNoteCategoryManager`).
- `server/routes/notes.js`, `server/services/note-categories.js`, `server/permissions.js`
  (`notes_edit_others`, `notes_manage_household_categories`).

## Upstream operations

All in `NotesOperation` (`upstream/operations-used.d/notes.txt`), wrapped by `NotesApi`.

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/notes` | `data` | the board: pinned first, then newest change; `creator_*`, `visibility`, `locked`, `categories[]` |
| POST | `/notes` | `data`, 201 | create; `Idempotency-Key` |
| PUT | `/notes/{id}` | `data` | edit; omitted fields keep their stored value |
| DELETE | `/notes/{id}` | 204 | delete after the undo window |
| PATCH | `/notes/{id}/pin` | `data: { id, pinned }` | flip the pin |
| PATCH | `/notes/{id}/check` | `data` (the note) | tick one checklist line: `line`, `checked`, `expect` |
| GET | `/notes/categories` | `data`, `meta.can_manage_household` | categories the member sees |
| POST | `/notes/categories` | `data`, 201 | create `{ name, scope }`; `Idempotency-Key` |
| PATCH | `/notes/categories/reorder` | `data`, `meta` | new order of one scope: `{ order: [ids], scope }` |
| PUT | `/notes/categories/{id}` | `data` | rename `{ name }` |
| DELETE | `/notes/categories/{id}` | 204 | delete; assignments go with it |

### The note model (fields read)

| Field | Type | Notes |
|---|---|---|
| `id` | int | |
| `title` | string? | optional, at most 200 characters |
| `content` | string | required Markdown, at most 5000 characters |
| `color` | `#RRGGBB` | the eight pastels, or an older colour outside them |
| `pinned` | 0/1 | |
| `created_by`, `creator_name`, `creator_color`, `creator_avatar` | | `creator_avatar` is a same-server URL |
| `visibility` | `all` \| `private` | private notes exist only for their creator, admins included |
| `locked` | 0/1 | closed to all but the creator and admins, ticks included |
| `categories` | `{ id, name, scope, owner_user_id, sort_order }[]` | only those this member sees |

Error `reason` values read: `notes_note_not_found` (404), `notes_selected_category_not_found`
(400/409), `note_locked` (403), `not_owner` (403), `category_exists` (409), `stale`,
`out_of_range`, `not_a_checklist_line` (all 409 from `/check`).

## Requirements

### Board

- **NOTES-R1** "Notes" sits at the top of "More". The board's top bar shows `notes.title`, a
  menu with `category.manageTitle` (hidden for read-only members), and a FAB
  `notes.addNoteLabel`.
- **NOTES-R2** On open it loads `GET /notes` and `GET /notes/categories` in parallel, cache
  first (ADR 0007), and again after a minute in the background and on pull to refresh.
- **NOTES-R3** A search field (`notes.searchPlaceholder`, clear `common.searchClear`) keeps the
  notes whose title or text contains the query, ignoring case (`visibleNotes`).
- **NOTES-R4** The creator chips (`notes.filterCreatorLabel`, "All" plus one chip per name with
  the person's colour disc) appear from two creators on; a second tap on the active chip clears
  it.
- **NOTES-R5** The category chips (`noteCategories.categories`) show the categories some note
  carries plus those still selected, with the scope glyph; several are AND-ed
  (`noteMatchesCategories`); "All" clears them. Selections of categories that disappeared are
  dropped on load (`pruneMissingNoteCategoryIds`).
- **NOTES-R6** Search and filters are kept while the app runs, as the web page keeps them in its
  module state.
- **NOTES-R7** Pinned notes come first. When both pinned and other notes are shown, two headers
  `notes.groupPinned` and `notes.groupOthers` separate them (`drawGrid`).
- **NOTES-R8** A card shows the title, the text as Markdown, the category badges, the creator's
  disc and name, the "Only me" eye mark (`common.visibility.private`) and the lock mark
  (`notes.lockedBadge`), on the note colour at 16 % over the surface. On a phone the preview is
  three text lines (two under a title), on wide screens twelve; checklist lines are never cut.
  The grid has one column on a phone and more on wider screens.
- **NOTES-R9** The card opens the note (R11); its pin, menu and checkboxes act without opening it.
- **NOTES-R10** Empty states: `notes.emptyTitle` with `notes.emptyDescription`, `emptyHint.notes`
  and `notes.emptyAction` (title only for read-only members); with a search or filter,
  `common.searchNoResultsTitle`, the filtered or plain description, the terms, and
  `common.searchReset`.

### Note, pin, delete, ticks

- **NOTES-R11** `NotesRoutes.note(id)` shows the note: title (else `notes.viewNote`), the
  categories, the text rendered (headings, rule, quote, lists, checklists, bold, italic,
  strikethrough, underline, code, `http`/`https`/`mailto` links), or `notes.readEmpty`, and the
  creator line. A note the member cannot change shows `common.foreignReadOnlyHint` instead of an
  Edit action. A note that is not on the board says `notes.errorNoteGone`.
- **NOTES-R12** Pin: `PATCH /notes/{id}/pin`, from the card's pin (`notes.pinAction` /
  `notes.unpinAction`) or the note's menu. For whoever may not change the note, a pinned note
  keeps its pin as a sign named `notes.pinnedState`; an unpinned one shows none (`pinMarkup`).
- **NOTES-R13** Delete, from the card's menu, the note's menu or the editor: the note leaves the
  board at once and a snackbar names it (`common.deletedNamed`, or `notes.deletedToast` without a
  title) with `common.undo`. Only when it closes without undo is `DELETE` sent. A
  `notes_note_not_found` answer counts as done (`isAlreadyDeleted`); any other failure brings the
  note back and says why.
- **NOTES-R14** A failure with `notes_note_not_found` on pin, tick or save removes the note from
  the board without a reload (`dropGoneNote`) and shows `notes.errorNoteGone`.
- **NOTES-R15** Checklist lines are tappable on the card and in the note for whoever may tick.
  The tick shows at once and sends `PATCH /notes/{id}/check` with `line` (zero-based source line,
  counting `\r\n`, `\n`, `\r`), `checked`, and `expect` (that line as stored); the answer's text
  replaces the note's. Which lines are checklist lines follows `markdown-checklist.js`, ported.
- **NOTES-R16** A `409`, or a line the local copy no longer has, rolls the tick back, reloads the
  board and says `notes.checkConflict`. Other failures roll back and show their sentence.

### Editor

- **NOTES-R17** `notes-editor` (new) and `notes-editor?id=` (edit) are full screens with Close,
  Delete (edit, when allowed) and Add/Save. Read and Edit tabs (`notes.modeRead`,
  `notes.modeEdit`): Read renders the current text, with live checkboxes only while the text is
  the saved one. Edit has the title (`notes.titleLabel`), the formatting toolbar
  (`markdown.*`: bold, italic, underline, strikethrough, heading, list, numbered list, checklist,
  link, code, quote, divider; `applyFormat` ported) and the text (`notes.contentPlaceholder`,
  `notes.contentMarkdownHint`).
- **NOTES-R18** Then categories (R22) and "More details" (`modal.moreDetails`), open by itself
  when the note is pinned, coloured, private or locked: colour (the eight named pastels, plus a
  stored colour outside them), `notes.pinnedLabel`, visibility (`common.visibility.*`) for the
  creator only, and `notes.lockedToggle` with `notes.lockedHint` for creator and admins. In a
  household of one the visibility and lock fields are hidden but still sent.
- **NOTES-R19** Save sends `{ title (blank is null), content (trimmed), color, pinned 1/0,
  category_ids, visibility?, locked 1/0? }`, visibility and lock only when the member may set
  them; a create carries one `Idempotency-Key` per editor, reused on retry. Empty text shows
  `common.contentRequired` and sends nothing. Success closes with `notes.createdToast` or
  `notes.savedToast`; a new note goes first on the board, an edited one stays in place.
- **NOTES-R20** A save failure keeps the editor open with its sentence, except
  `notes_note_not_found`, which closes it (R14). `notes_selected_category_not_found` reloads the
  categories and drops the missing ones from the choice.
- **NOTES-R21** Leaving with unsaved changes asks `modal.unsavedChanges` /
  `modal.unsavedChangesDetail` / `modal.discardChanges`.
- **NOTES-R22** Categories in the editor: chosen ones as removable chips
  (`noteCategories.removeAction`), a search (`noteCategories.searchPlaceholder`, 80 characters)
  suggesting up to eight unchosen categories, matching ignoring case and accents; "Create and
  assign" (`noteCategories.createAction`) when no category of that name exists in the chosen
  scope; an exact match is chosen instead of creating a duplicate. The scope choice
  (`noteCategories.scopeLabel`, `scopeHelp`) appears only with `can_manage_household`; otherwise
  new categories are personal.

### Category manager

- **NOTES-R23** `notes-categories` shows `category.manageHint` (or
  `noteCategories.personalManagementHint` without household management), the personal group,
  and the household group only with `can_manage_household`.
- **NOTES-R24** One add form: name (`category.addPlaceholder`, 80 characters), the scope when
  there is a choice, `common.add`; `category.added` on success.
- **NOTES-R25** Each row's menu: `category.renameNamed` (a prompt, `category.renamePrompt`;
  `category.renamed`), `category.moveUpNamed` / `category.moveDownNamed` (disabled at the ends;
  `PATCH /notes/categories/reorder` with the scope's new order), `category.deleteNamed` (asks
  `category.deleteConfirm` with `noteCategories.deleteDetail`; `category.deleted`). Tapping the
  name renames.
- **NOTES-R26** A deleted category leaves every note and the filter at once; after every change
  the board's categories and notes are read again, as the web manager's `refresh` does.

### Permissions, errors, offline

- **NOTES-R27** Access `read` (`isNavModuleReadOnly('notes')`): no FAB, no manage menu, no pin
  control, no card menu, no Edit or Delete, checkboxes as signs named "item: Done/Open"
  (`notes.checklistDone` / `notes.checklistOpen`). Access `none` removes Notes from "More".
- **NOTES-R28** Per note (`note-access.js`, `mayChange`/`mayCheck`): changing needs write
  access, no lock against the member, and own note or `notes_edit_others` (always for admins);
  ticking needs write access and no lock against the member.
- **NOTES-R29** Errors show upstream's sentence (`errorSentence`): `note_locked` →
  `notes.errorLocked`, `not_owner` → `common.errorNotOwner`, other 403 →
  `common.errorNoPermission`, `module_read_only` → `android.notes.moduleReadOnly` (upstream's
  `settings.permReadOnlyBanner`, whose namespace the app does not ship), the gone reasons above,
  `category_exists`, `common.errorRateLimited`, offline `common.errorOfflineMutation`, else
  `common.errorGeneric`. The server's English text is never shown.
- **NOTES-R30** Offline (ADR 0007): the cached board and notes show read-only under
  `OfflineBanner`; every write affordance is hidden.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| NOTES-A1 | Given the seeded server as `alex` when the board loads then every note decodes, pinned ones first, each with a creator, and some carry checklists. | contract | written |
| NOTES-A2 | Given `alex` then `can_manage_household` is true; for `emma` it is false. | contract | written |
| NOTES-A3 | Given notes by two creators with categories then search, creator and category filters (AND) keep the right notes. | unit | passing |
| NOTES-A4 | Given a pinned and an unpinned note then both group headers show; unpinning answers `pinned: 0`, sorts, and the headers go. | unit + ui + contract | passing |
| NOTES-A5 | Given a checklist when line 2 is ticked then `/check` gets `line`, `checked` and `expect`, CRLF is kept, a repeat tick is no error, a stale `expect` is 409 `stale` and the app reloads. | unit + ui + contract | passing |
| NOTES-A6 | Given the editor then the body has the trimmed text, null for a blank title, `category_ids`, pin 1/0, visibility and lock as allowed; empty text sends nothing; a card opens to read, Edit opens the editor, its Read tab renders. | unit + ui + contract | passing |
| NOTES-A7 | Given a delete then the note hides at once; undo sends nothing; no undo sends `DELETE`; `notes_note_not_found` is success. | unit + ui + contract | passing |
| NOTES-A8 | Given `emma` (no `notes_edit_others`) and `alex`'s note then edit, pin and delete are 403 `not_owner`, ticking works. | unit + contract | passing |
| NOTES-A9 | Given `notes: read` then the board has no add, pin, menu or tick, and a pinned note keeps a pin sign; a locked note refuses `emma`'s tick with 403 `note_locked`; an admin cannot make someone else's note private; someone else's private note is 404. | unit + ui + contract | passing |
| NOTES-A10 | Given each known reason then the app shows upstream's sentence, never the server's text. | unit | passing |
| NOTES-A12 | Given categories then the identity key folds like the server's (`categoryNameKey` cases), suggestions ignore accents, an exact match is chosen instead of a duplicate, and the server refuses a duplicate with 409 `category_exists` and a child's household category with 403. | unit + contract | passing |
| NOTES-A13 | Given two personal categories then rename trims, reorder answers the new order, delete removes the category from its notes and a second delete is 404; saving with a gone category is 400 `notes_selected_category_not_found` and the editor drops it. | unit + contract | passing |
| NOTES-A17 | Given a failed create then a retry sends the same `Idempotency-Key` and the server holds one note. | unit + contract | passing |

Contract tests run in CI only (no Docker here); "written" means the test exists and has not
been run against a server yet.

## Strings

App-only (`app/src/main/assets/i18n-android/notes/en.json`): `android.notes.moduleReadOnly`.
Everything else uses upstream's keys from `notes`, `noteCategories`, `category`, `common`,
`markdown`, `modal`, `emptyHint` and `nav`.
