# Spec: Documents

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's family documents as native screens, at parity with upstream's Documents page:
find a document by status, folder, category, deadline and search; look at it inline; download,
open or share it; upload and describe documents; file them in a folder tree; and, for admins,
bring documents in from a connected DMS or send them there.

## Scope

Everything the web page (`public/pages/documents.js`) lets a member do:

| Area | Requirement IDs |
|---|---|
| List: load, status, search, category/deadline/folder facets, sort, view, rows, previews, empty states | R1–R10 |
| Row actions, delete with undo, multi-select | R11–R13 |
| Viewer: inline image, PDF and text, details, stepping, actions, links from other modules | R14–R19 |
| Upload and edit dialog | R20–R25 |
| Folders: new, rename, move, delete with impact | R26–R29 |
| DMS: link from, upload to | R30–R31 |
| Permissions, errors, offline | R32–R34 |

**What stays in the browser.** Uploading a whole folder tree (`bindFolderUpload`,
`utils/folder-upload.js`: the upload plan with merge/duplicate/skip per conflict) is not native;
the upload dialog offers "or choose a folder" as a link that opens the web app's Documents page.
The document storage backend (local, local folder, WebDAV, Google Drive) and DMS accounts are
configured in Settings, as on the web; this page only names the active upload target to admins.

**Native differences.** The web viewer is a dialog; the app's viewer is a full-screen dialog
over the list. The web renders PDFs with the browser or pdf.js; the app draws the pages with the
platform's `PdfRenderer`. The web's Share uses the Web Share API; the app's "Open with" and
Share hand a private copy to another app through a `FileProvider`, and Download is the system's
"save as" (no storage permission). The web's single delete has no question and relies on undo;
the app asks first (`documents.deleteConfirm`) and keeps the undo. Sort, direction, view and the
expanded folders live for the screen's lifetime, not in persistent storage like the web's
`localStorage`. The web page has no `?open=<id>` handling at 2.83.0; the app opens the viewer
for `DocumentsRoutes.document(id)`, which Tasks links to.

## Upstream references

- `public/pages/documents.js` (`render`, `loadMembers`, `loadDocuments`, `loadFolders`,
  `loadMetaOptions`, `matchesFolder`, `applyFilters`, `documentsToolsMenuHtml`, `setSort`,
  `selectFolder`, `selectStatus`, `emptyStateFor`, `probeOtherStatusSearch`,
  `renderCategoryChips`, `renderCategoryBrowser`, `renderExpiringChip`, `renderFolderBrowser`,
  `renderBreadcrumb`, `openFolderMenu`, `moveFolder`, `renameFolder`, `deleteFolder`,
  `folderDeleteChoice`, `commitFolderDeletion`, `applyFolderDeleteResult`, `renderMeta`,
  `storageBadgeHtml`, `expiryChipHtml`, `expiryViewerHtml`, `renderActions`, `renderListItem`,
  `renderGridCard`, `openDocumentMenu`, `runDocumentAction`, `deleteDocuments`,
  `enterSelectMode`, `updateSelectUI`, `toggleSelectAll`, `archiveSelected`, `moveSelected`,
  `memberOptions`, `openDocumentModal`, `bindDropzone`, `saveDocument`, `openFolderModal`,
  `openDmsLinkModal`, `renderDmsResults`, `linkDmsDocument`, `formatFileSize`,
  `viewerDetailsHtml`, `viewerNeighbours`, `openDocumentViewer`, `renderViewerContent`,
  `readOnly`, `mayManage`, `errorText`, `reportError`).
- `public/utils/document-facets.js`, `folder-tree.js`, `date-status.js`, `document-expiry.js`,
  `document-preview.js`, `document-thumbs.js` (`documentStorageBackend`, `documentThumbKind`),
  `document-folder-delete.js`, `people-picker.js` (`withChosenPeople`), `friendly-error.js`
  (`errorSentence`, `isGone`, `CASE_PARAMS`), `module-access.js`, `household.js`
  (`hidesPrivacyControls`).
- `server/routes/documents.js`, `server/routes/dms.js`, `server/services/document-access.js`
  (`documentVisibleSql`, `canManageDocument`), `server/routes/family.js`, `server/auth.js`
  (`GET /users`).

## Upstream operations

All in `DocumentsOperation` (`upstream/operations-used.d/documents.txt`), wrapped by
`DocumentsApi`, except `GET /family/members`, which is Meals' `FAMILY_MEMBERS` and reused.

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/documents?status=` | `data` | the list of one status, newest change first; facets are client-side |
| POST | `/documents` | `data`, 201 | upload one file: the form plus `original_name` and `content_data` (data URL); `Idempotency-Key` |
| PUT | `/documents/{id}` | `data` | edit, move: the dialog's fields (`allowed_member_ids` is always replaced) |
| DELETE | `/documents/{id}` | 204 | delete after the undo window |
| PATCH | `/documents/{id}/archive` | `data: { id, status }` | archive (`archived: true`) or restore |
| GET | `/documents/{id}/preview` | bytes | inline image, PDF or text; 415 for other types |
| GET | `/documents/{id}/download` | bytes | save as, open with, share |
| GET | `/documents/{id}/thumbnail` | bytes | the DMS thumbnail of a Paperless document; 415 otherwise |
| GET | `/documents/meta/options` | `data` | `max_file_size`, `allowed_mime_types`, `active_upload_backend`, `is_admin`, `dms_accounts` (admins only) |
| GET | `/documents/folders` | `data` | the flat folder list with `parent_id` |
| POST | `/documents/folders` | `data`, 201 | new folder (`name`, `parent_id`) |
| PUT | `/documents/folders/{id}` | `data` | rename (`name`) or move (`parent_id`) |
| GET | `/documents/folders/{id}/delete-impact` | `data` | subtree size, visible documents, `can_delete_documents`, `linked_records`, `snapshot` |
| DELETE | `/documents/folders/{id}?documents=&expected_*` | `data`, 200 or 207 | unfile or delete with documents against the preview |
| GET | `/documents/dms/search?account_id&q` | `data` | admin DMS search |
| POST | `/documents/dms/link` | `data`, 201 | admin: link a DMS document |
| POST | `/documents/dms/push` | `data`, 202 | admin: upload a document to a DMS |
| GET | `/auth/users` | `data` | names of people a document is already shared with |
| GET | `/family/members` (Meals) | `data` | who can be chosen for "Selected members" |

### The document model (fields read)

`id, name, description, category, status, visibility, original_name, mime_type, file_size`
(null for some DMS documents), `storage_provider, storage_backend, storage_key, dms_account_id,
dms_provider, external_url, folder_id, folder_name, created_by, updated_at, expires_at,
expiry_reminder_days, allowed_member_ids[]`.

## Requirements

### List

- **DOCS-R1** On open: members and directory, folders and options in parallel, then the
  documents of the status (`render`). The last good list per status and the folders are cached.
  A failed member or option load is tolerated (empty picker, defaults), like `loadMetaOptions`.
- **DOCS-R2** Active/Archived is a segment; switching loads that status from the server and ends
  the selection (`selectStatus`).
- **DOCS-R3** Search matches name, description and file name, case-insensitively, on the client
  (`matchesDocumentQuery`).
- **DOCS-R4** Category chips: "All categories" and every category with documents in the chosen
  folder (not the search), plus the chosen one; each counts under the folder and the search; no
  chips in a view without documents unless one is chosen (`renderCategoryChips`,
  `categoryFacetCounts`).
- **DOCS-R5** Deadline chip: documents expiring within 30 days or expired, counted under
  category, folder and search; shown only when it finds something or is on (`renderExpiringChip`,
  `dateStatus`).
- **DOCS-R6** The folder chip leads the chip row and names the open folder; it opens a sheet with
  "All documents", "No folder" and the folder tree (expand toggles, subtree counts under the
  category and the search, folder menus), then the categories (the web's phone layout,
  `renderFolderBrowser`, `renderCategoryBrowser`). A folder shows its whole subtree
  (`matchesFolder`); choosing one with children opens its branch (`selectFolder`); a path with
  tappable steps stands above the list (`renderBreadcrumb`).
- **DOCS-R7** The header menu sets the view (list, grid), the sort key (last modified, name,
  size, expiry date) and the direction; a new key starts in its natural direction; documents
  without expiry stay last both ways (`setSort`, `sortDocuments`).
- **DOCS-R8** A row shows the preview or the category glyph, the name, and the meta line: expiry
  chip (short form, danger when expired, warning when soon), date · size, category, folder (not
  when it repeats the category or is the open folder), visibility (only when not "Entire family",
  never in a household of one) and where the file lives (`renderListItem`, `renderMeta`,
  `showsFolderChip`, `storageBadgeHtml`). A grid card adds the description (`renderGridCard`).
- **DOCS-R9** Previews: local images and PDFs up to 10 MB (first page), and the DMS thumbnail of
  Paperless documents; two at a time, kept per `id:updated_at`, a failure keeps the glyph
  (`documentThumbKind`, `docSupportsThumbnail`, `createDocumentThumbs`).
- **DOCS-R10** Empty states name their cause: search (term, reset, the other view when it has
  hits, reset filters), an empty folder (upload), filters without match (reset, upload), the empty
  archive (show active), nothing yet (upload, add folder; read-only: title only) (`emptyStateFor`,
  `probeOtherStatusSearch`).

### Row actions, delete, selection

- **DOCS-R11** Tapping a row opens the viewer; Download is on every row; managers get a menu with
  View, Edit, Move, Archive/Restore, Upload to DMS (when accounts exist and the file is not in a
  DMS) and Delete (`renderActions`, `openDocumentMenu`, `runDocumentAction`).
- **DOCS-R12** Delete asks, then the documents leave at once with an undo snackbar; after the
  window every delete runs, the first failure is reported; a gone document is not put back, the
  list reloads (`deleteDocuments`).
- **DOCS-R13** "Select several" (only with a manageable document in view) turns taps into
  selection; the bar shows the count, Move, Delete (asks, `bulkDeleteConfirmDetail`) and Done;
  the menu adds Select all and Archive/Restore by status (`enterSelectMode`, `updateSelectUI`,
  `toggleSelectAll`, `archiveSelected`, `moveSelected`).

### Viewer

- **DOCS-R14** `DocumentsRoutes.document(id)` (`documents?open=<id>`, from Tasks) opens the viewer
  once the list is there; a document in the archive switches to that view; one the member cannot
  see says `documents.errorDocumentGone`.
- **DOCS-R15** The viewer names category, folder (unless it repeats the category), size, the
  expiry line whenever a date is set (valid until / expires on / expired on, toned) and the
  storage (`openDocumentViewer`, `expiryViewerHtml`).
- **DOCS-R16** Inline: images (pinch to zoom), PDF pages drawn as they scroll in with "Page n of
  total", text in a monospace selectable block; other types say why and offer the file; a failed
  image or PDF says so and offers the file (`renderViewerContent`, `renderViewerFallback`).
- **DOCS-R17** Previous/next step through the visible list without wrapping (`viewerNeighbours`).
- **DOCS-R18** Whoever may not edit sees the edit dialog's values as a read view: description,
  visibility and the chosen people (not in a household of one), reminder lead time, archived
  (`viewerDetailsHtml`).
- **DOCS-R19** Actions: Edit (managers), Download, Open with, Share, Open in DMS (http(s) links
  only), and the manager's Move, Archive/Restore, Upload to DMS, Delete.

### Upload and edit

- **DOCS-R20** Upload: files from the system picker (several allowed, filtered to
  `allowed_mime_types`); the size is checked when picked and on save against `max_file_size`;
  admins see where new files are stored. One POST per file with its own idempotency key, kept
  across retries; the button shows "Uploading n of total" (`openDocumentModal`, `bindDropzone`,
  `saveDocument`).
- **DOCS-R21** One file takes the typed name, or its file name without extension; several files
  each take their own and the name field hides.
- **DOCS-R22** Fields: name (required when editing), category, folder (the tree, preset to the open
  folder), visibility (hidden in a household of one), the chosen people for "Selected members"
  (members plus anyone already granted, `withChosenPeople`), expiry date with the reminder lead
  time 0–365 shown only with a date and sent only with one, and folded description and status
  (`advancedSection`).
- **DOCS-R23** A gone document closes the dialog and reloads; a gone chosen folder keeps the
  dialog open, resets the folder and reloads the folders (`isSelectedFolderGone`).
- **DOCS-R24** Download is the system's "save as" of `/download`. Open with and Share write a
  private copy under the cache and hand it out through the FileProvider authority
  `${applicationId}.files`; the previous copy is removed first. Without the provider registered
  the app says `android.documents.handoffUnavailable`.
- **DOCS-R25** "Or choose a folder" (folder upload) opens the web app.

### Folders

- **DOCS-R26** New folder: name and parent (top level or the tree), preset to the menu's folder or
  the open folder; the parent opens and the new folder becomes the filter (`openFolderModal`).
- **DOCS-R27** Rename reloads folders and documents (`folder_name` comes from the server's join).
- **DOCS-R28** Move offers only possible parents (not the folder or its subtree) and opens the new
  parent (`moveFolder`).
- **DOCS-R29** Delete fetches the impact first. With documents, or documents the member may not
  delete, it asks: keep the documents, or delete them too (with the modules a deletion touches;
  disabled when not allowed); otherwise a plain confirmation. Keeping unfiles at once; deleting
  with documents hides the branch with undo and sends the preview's counts and snapshot. Results
  and failures (`FOLDER_CONTENT_CHANGED`, `FOLDER_DELETE_IN_PROGRESS`,
  `FOLDER_DOCUMENTS_NOT_MANAGEABLE`, partial 207) say what happened (`deleteFolder`,
  `folderDeleteChoice`, `applyFolderDeleteResult`, `handleFolderDeleteFailure`).

### DMS

- **DOCS-R30** Admins with DMS accounts get "Link from DMS": account choice, search (300 ms,
  empty lists the DMS), the ASN hint for Paperless, results with title and file/type, Link; a
  failed search offers retry (`openDmsLinkModal`, `renderDmsResults`, `linkDmsDocument`). The
  result thumbnails and their enlarged preview are not shown.
- **DOCS-R31** "Upload to DMS" sends to the one account, or asks which (`runDocumentAction`).

### Permissions, errors, offline

- **DOCS-R32** `documents: read` (or offline) hides upload, folder actions, menus, selection and
  DMS; viewing, Download, Open with and Share stay. Editing, moving, archiving and deleting a
  document need the creator or an admin (`mayManage`, `canManageDocument`).
- **DOCS-R33** Errors show upstream's sentences (`DocumentErrors`, `errorSentence` with the
  page's `common.unknownError`); a gone target reloads folders, documents and options
  (`reportError`). The server's English text is never shown.
- **DOCS-R34** Offline (ADR 0007): the cached list and folders show read-only under
  `OfflineBanner`.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| DOCS-A1 | Given the seeded server then list, folders, options and people decode; the list is newest first; nulls and flags decode. | unit + contract | passing / written |
| DOCS-A2 | Given the list then it loads `status=active` and shows rows with chips and counts. | ui | passing |
| DOCS-A3 | Given documents then search, category and deadline facets keep the right ones and count under the other axis. | unit + ui | passing |
| DOCS-A4 | Given the sort keys then each sorts in its direction and expiry-less documents stay last. | unit | passing |
| DOCS-A5 | Given a tree then subtree, path, flattened rows and move rules match folder-tree.js; the sheet filters by folder. | unit + ui | passing |
| DOCS-A6 | Given expiry dates then status, row chip and viewer line follow date-status.js and document-expiry.js. | unit | passing |
| DOCS-A7 | Given rows then the folder chip, visibility, previews and storage badges follow the page's rules. | unit + ui | passing |
| DOCS-A8 | Given the dialog then the payload, file names, data URL and idempotency key are sent; edit sends the form; the server answers the whole document. | unit + ui + contract | passing / written |
| DOCS-A9 | Given a file over the limit or none then nothing is sent; the server refuses a mismatched, disallowed, empty file or a gone folder with its reason. | unit + contract | passing / written |
| DOCS-A10 | Given each known reason then the app shows upstream's sentence; unknown ones the general sentence. | unit | passing |
| DOCS-A11 | Given a text document then the viewer shows it inline with its neighbours. | ui | passing |
| DOCS-A12 | Given a delete then it asks, hides, offers undo and sends nothing on undo; move sends the new folder; another member's document is 403. | ui + contract | passing / written |
| DOCS-A13 | Given `documents: read` then there is no add, menu or selection, viewing stays; a private document is not listed for others. | ui + contract | passing / written |
| DOCS-A14 | Given folders then create, rename, move, impact and unfile work and the dialog sends the previewed counts. | ui + contract | passing / written |
| DOCS-A15 | Given a folder delete with documents then a stale preview is 409 `FOLDER_CONTENT_CHANGED` and a fresh one deletes them. | contract | written |
| DOCS-A16 | Given DMS calls then members are refused and a missing account is named. | contract | written |
| DOCS-A17 | Given `documents?open=<id>` then the viewer opens, also from the archive; an unknown id says so. | ui | passing |

Contract tests run in CI only (no Docker here); "written" means the test exists and has not
been run against a server yet.

## Strings

App-only (`app/src/main/assets/i18n-android/documents/en.json`): `android.documents.clearDate`,
`handoffUnavailable`, `loadError`, `noApp`, `openWith`, `reminderRange`, `saved`. Everything else
uses upstream's keys from `documents`, `common`, `nav`, `settings` and `splitExpenses`.
