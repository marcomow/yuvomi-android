# Spec: Pantry

Status: draft · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

What the household has at home, as a native screen: stock per product and batch with quantity,
unit, storage location and best-before date, the web app's expiry and stock warnings, quick
quantity steps, adding, editing and deleting, storage locations, and putting what runs low on a
shopping list.

## Scope

Everything `public/pages/pantry.js` lets a member do. Nothing of the page stays in the browser.
Not part of the page and therefore not here: taking checked shopping items into the pantry
(`POST /pantry/import-shopping` and its undo belong to Shopping), the kitchen tab bar (native
navigation replaces it), the overview's "expiring soon" tile (Overview).

## Upstream references

- `public/pages/pantry.js` (`productSections`, `flatItems`, `visibleItems`, `renderFilters`,
  `placeChips`, `rowEl`, `productEl`, `emptyProductEl`, `adjustQuantity`, `sendToShopping`,
  `shortfallText`, `openItemModal`, `saveItem`, `removeItem`, `removeProduct`,
  `openItemReadModal`, `openLocationManager`, `renderBulkBar`, `noResultsEl`, `emptyStateEl`).
- `public/utils/pantry-units.js`, `pantry-status.js`, `pantry-locations.js`,
  `shopping-categories.js`, `kitchen-transfer.js`, `friendly-error.js`, `ux.js`
  (`scheduleUndoableDelete`), `empty-state.js` (`searchEmptyOptions`).
- `public/components/category-manager.js` (the location manager).
- `server/routes/pantry.js`, `server/routes/shopping.js` (`/:listId/import-pantry`).

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/pantry` | `{ data, products, locations, categories }` | the stock (core, `KitchenApi.pantry`) |
| POST | `/pantry` | `data`, 201 | add a batch |
| PUT | `/pantry/{itemId}` | `data` | edit a batch (all fields) |
| PATCH | `/pantry/{itemId}` | `data` | stepper: `{ quantity }` only |
| DELETE | `/pantry/{itemId}` | 204 | delete a batch |
| DELETE | `/pantry/products/{productId}` | 204; 409 `pantry_product_has_batches` | delete a product without batches |
| GET | `/pantry/locations` | `data` | the location manager's list |
| POST | `/pantry/locations` | `data`, 201; 409 exists | add a location |
| PUT | `/pantry/locations/{locId}` | `data`; 409 exists | rename |
| PATCH | `/pantry/locations/reorder` | `data` (all locations) | move up or down |
| DELETE | `/pantry/locations/{locId}` | `{ ok, orphaned }`; 400 last one | delete |
| POST | `/shopping/{listId}/import-pantry` | `data: { added, skipped, added_ids }` | cart and "add all" |
| GET | `/shopping` | `data` | shopping lists to pick from (core, `KitchenApi.shoppingLists`) |
| POST | `/shopping/items/undo-transfer` | `data` | undo of a transfer (core, `KitchenApi.undoTransfer`) |

Upstream's pantry routes answer a missing batch or location with a 404 **without** `reason`, so
ARCHITECTURE.md rule 6 ("a 404 without reason means the operation is gone") does not apply to
them; only a 405 does.

## Requirements

Status: all requirements below are built unless noted.

### List

- **PANTRY-R1** Top bar `nav.pantry` with "Manage storage locations" (`pantry.manageLocations`)
  for members with pantry write access; FAB `pantry.addItem` likewise. A search field
  (`pantry.searchPlaceholder`) sits above the list.
- **PANTRY-R2** On open the screen shows the cached `GET /pantry` answer at once, then loads it
  (ADR 0007). A load error without cache shows `pantry.loadErrorTitle` with
  `common.loadErrorDescription` and retry (`renderLoadError`). Pull to refresh reloads, and so does
  coming back to the foreground after more than 60 seconds.
- **PANTRY-R3** "Today" is the household's day (`shell.time`), taken once per load as upstream
  freezes `state.todayKey` per render.
- **PANTRY-R4** Without a filter the list shows **products** in sections by category
  (`productSections`): sections in Shopping's category order (`categories[].sort_order`),
  unknown categories after them by name, no category counts as `Sonstiges`. Section heading:
  the category label (`categoryLabel`: the seeded German names translated through
  `shopping.cat*`, others as typed) and the number of products. Products by name
  (case-insensitive), then id. Batches of a product by location order, best-before (none last),
  id.
- **PANTRY-R5** A product shows in one of three shapes (`productEl`): one batch is a plain batch
  row; several batches are a product row with the total (`pantry.productTotal`), the most urgent
  expiry badge of its batches, and where they are (`pantry.productPlaces`, or
  `pantry.productBatches` plus the shared place), which opens and closes its batches (closed by
  default, remembered while the screen lives; opened when the search hits only through a batch);
  no batch is an out-of-stock row showing 0 in its unit (`emptyProductEl`).
- **PANTRY-R6** A batch row (`rowEl`): the name, or under its product the place, or the
  best-before date (`pantry.bestBefore` / `pantry.noBestBefore`) when all batches share one
  place. The meta line: quantity with the unit inflected by the amount (`pantryQuantityLabel`,
  `pantry.units.<unit>` plural forms, raw unit when unknown), then the badges, then the
  best-before date, the place (not under its product, not under a place chip), and in the
  filtered list the product's total when it has several batches.
- **PANTRY-R7** Badges (`pantryItemStatus`, `pantryExpiryPhrase`): expired (before today) in the
  danger colour, expiring within 7 days (today inclusive) in the warning colour, phrased
  `pantry.badgeExpiredYesterday`, `badgeExpiredDays`, `badgeExpiresToday`,
  `badgeExpiresTomorrow`, `badgeExpiresDays`; out of stock (quantity 0) `pantry.badgeOut`
  (danger), running low (at or below `min_quantity`, not out) `pantry.badgeLow` (warning).
- **PANTRY-R8** Filter chips (`renderFilters`, `placeChips`), single choice: `pantry.filterAll`,
  then `filterExpired`, `filterSoon`, `filterLow` (low includes out) with counts, each only when
  it has hits; then one chip per storage location that holds a batch, in location order, with
  the number of batches. The row is hidden when there is no chip besides "All" or no batch. An
  active chip that loses its last hit falls back to "All". The route `pantry?filter=expired|soon|low`
  opens with that chip (`pantryFilterFromSearch`).
- **PANTRY-R9** With a chip the list is flat (`flatItems`): a place by name, best-before (none
  last), id; expired and soon by best-before; low by quantity ascending.
- **PANTRY-R10** Search (`visibleItems`) matches a batch's name, note or place label,
  case-insensitively; a product without batches matches by name. Nothing found shows
  `common.searchNoResultsTitle`, `common.searchNoResultsDescription` (or
  `...FilteredDescription` with a chip), the term and the chip, and `common.searchReset`, which
  clears both (`noResultsEl`).
- **PANTRY-R11** No batches and no products: `pantry.emptyTitle`, and for writers
  `pantry.emptyDescription`, `emptyHint.pantry` and the action `pantry.emptyAction`.

### Quantity steps

- **PANTRY-R12** Writers get minus and plus on every batch row (`adjustQuantity`). One step is
  the unit's step (`PANTRY_UNIT_STEP`: pcs 1, g 100, kg 0.5, ml 100, l 0.5, pkg/can/bottle/jar/bag
  1, unknown 1); the result is rounded to two decimals and kept within 0 to 1,000,000; minus is
  disabled at 0. The row shows the new value at once (the "intent"); the write is
  `PATCH /pantry/{itemId}` `{ "quantity" }`, debounced 450 ms so several taps make one request.
  A later step supersedes an earlier one; a failure drops the intent (the row shows the server's
  value again) and says why; the answer's quantity becomes the server value. Leaving the screen
  sends a pending step at once. The row is not refiltered under the finger; the chips recount,
  and an active chip that loses its last hit falls back to "All".

### Add, edit, delete

- **PANTRY-R13** The editor (`openItemModal`) is a full-screen form: name (required,
  `common.nameRequired`), quantity (default 1), unit (the ten units), storage location
  (`pantry.unlocated` or a location), category (Shopping's categories; new items default to
  `Sonstiges`, else the first), best-before (`pantry.expiresHint`), and under "more details"
  (`pantry.moreDetailsHint`; open when editing an item with a minimum or note) minimum stock
  (`pantry.minQuantityHint`) and note. Editing starts from the row as shown, including a pending
  step. Save sends every field (`saveItem`): quantity normalized (fallback 1), `location_id` or
  null, `expires_on` or null, `min_quantity` normalized or null when empty, `notes` trimmed or
  null; create is `POST /pantry` with an `Idempotency-Key`, edit is `PUT /pantry/{itemId}`. Then
  the list reloads and `pantry.created` / `pantry.updated` confirms.
- **PANTRY-R14** Deleting a batch (swipe to the row end, or Delete in the editor) hides the row
  at once and offers `common.undo` with `pantry.deleted` (`scheduleUndoableDelete`); only when the
  snackbar ends does `DELETE /pantry/{itemId}` go out. Leaving the screen does not lose it. A
  failure brings the row back and says why. Product totals ignore a batch in its undo window.
- **PANTRY-R15** A product without batches is deleted the same way (swipe, or Delete in its row
  menu) with `DELETE /pantry/products/{productId}`; a `409 pantry_product_has_batches` brings it
  back with `pantry.errorProductHasStock` and reloads (`removeProduct`).
- **PANTRY-R16** With pantry access `read` (`readOnly`), the list, search and chips stay; there
  is no FAB, no stepper, no swipe, no editor, no location manager; tapping a row opens a read-only
  sheet (`openItemReadModal`) with quantity, storage location, category, best-before, minimum
  stock and note. The empty state is the title only.

### To the shopping list

- **PANTRY-R17** A cart button sits on batch rows that are out or low and on products without
  batches, for members with shopping write access (`mayTransferPantryToShopping`), independent
  of pantry access. It sends `POST /shopping/{listId}/import-pantry` with
  `{ items: [{ pantry_item_id, quantity }] }`, `quantity` being the shortfall to the minimum as
  display text (`shortfallText`, null without a minimum), or `{ pantry_product_id }` for a
  product. The list (`resolveShoppingTarget`): no list says `kitchen.noShoppingLists`, with
  `kitchen.createShoppingList` leading to Shopping when the member may create lists there; one
  list is used directly; several ask `common.toShoppingListWhich`. The lists load once per visit.
- **PANTRY-R18** The answer (`announceTransfer`, `transferMessage`): `added` 0 says
  `pantry.toShoppingNone`; otherwise `pantry.toShoppingDone` naming the list, followed by
  `kitchen.transferSkipped` when some were already on it, with `common.undo` when `added_ids` is
  not empty. Undo sends `POST /shopping/items/undo-transfer` `{ ids }` and says
  `kitchen.transferUndone`.
- **PANTRY-R19** With the "Running low" chip and at least one row, writers to Shopping get a bar
  (`renderBulkBar`): `pantry.bulkPillLabel` and `pantry.toShoppingAll`, which sends every visible
  row as in PANTRY-R17.

### Storage locations

- **PANTRY-R20** The manager (`openLocationManager` with `category-manager.js`) is a screen with
  `pantry.manageLocationsHint`, the locations in order from `GET /pantry/locations` (seeded German
  names translated through `pantry.loc*`, others as typed), and a field `pantry.addLocation` that
  adds with `POST /pantry/locations` `{ name }`. Each row's menu: rename (prompt
  `category.renamePrompt`, `PUT /pantry/locations/{locId}` `{ name }`, nothing sent when the name
  is unchanged), move up and down (`PATCH /pantry/locations/reorder` `{ order }`), delete (confirm
  `category.deleteConfirm` with `pantry.locationDeleteConfirmDetail`, then
  `DELETE /pantry/locations/{locId}`). Confirmations `category.added`, `category.renamed`,
  `category.deleted`, as upstream configures the shared manager. Upstream shows the server's
  English text for a duplicate name (409) and the last location (400); the app says
  `android.pantry.locationExists` and `android.pantry.locationLast`. The stock reloads after
  every change.

### Errors and offline

- **PANTRY-R21** Failures read as upstream's `errorSentence`: offline `common.errorOfflineMutation`;
  403 `common.errorFormExpired` (`csrf_invalid`), `common.errorModuleNoAccess`
  (`module_access_denied`), else `common.errorNoPermission`; 429 `common.errorRateLimited`;
  `shopping_list_not_found` `shopping.errorListGone`; `pantry_product_has_batches`
  `pantry.errorProductHasStock`; `restore_in_progress` `common.errorRestoreInProgress`; a 405
  `android.error.unsupported`; anything else `common.errorGeneric`.
- **PANTRY-R22** Offline with a cached answer: the list shows read-only under the offline
  banner; every write says `common.errorOfflineMutation` and changes nothing (ADR 0007).

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| PANTRY-A1 | Given the seeded server as `alex` then `GET /pantry` decodes with batches, products, locations and categories. | contract | |
| PANTRY-A2 | Given the units then steps, rounding (0.1 + 0.2), clamping and the inflected label ("6 cans", "1 can", "1.5 kg", raw unknown unit) match `pantry-units.js`. | unit | |
| PANTRY-A3 | Given today 2026-11-01 then expiry status and phrases for 10-30, 10-31, 11-01, 11-02, 11-05, 11-08, 11-09 and stock status for out, low and fine match `pantry-status.js`. | unit | |
| PANTRY-A4 | Given batches and products then sections, product order, batch order, shapes and totals match `productSections`. | unit | |
| PANTRY-A5 | Given batches then chips, counts, place chips and the fallback to "All" match `renderFilters`. | unit | |
| PANTRY-A6 | Given a chip then the flat order matches `flatItems`; search matches name, note and place. | unit | |
| PANTRY-A7 | Given a new batch then create, full edit, quantity patch and delete answer as the app expects, and the deleted batch is a 404. | contract | |
| PANTRY-A8 | Given a product without batches then its delete is a 204; with a batch it is a 409 `pantry_product_has_batches`. | contract | |
| PANTRY-A9 | Given a new location then it lists, renames, reorders and deletes (orphaning its batch); a duplicate name is a 409. | contract | |
| PANTRY-A10 | Given a low batch then import-pantry adds it with the shortfall, a second time skips it, and undo-transfer removes it. | contract | |
| PANTRY-A11 | Given the list on screen when plus is tapped three times then one `PATCH` goes out with the final quantity. | ui | |
| PANTRY-A12 | Given `pantry: read` then the list shows no FAB and no stepper, and a tap opens the details. | ui | |
| PANTRY-A13 | Given the editor's form then create sends the web app's payload with an `Idempotency-Key`. | ui | |
| PANTRY-A14 | Given a deleted batch when undo is not used then one `DELETE` goes out after the snackbar; with undo none. | ui | |
| PANTRY-A15 | Given several shopping lists when the cart is used then the picker asks, the transfer names the list, and undo sends the `added_ids`. | ui | |

## Strings

Upstream keys from `pantry`, `kitchen`, `common`, `shopping` (`cat*`, `errorListGone`),
`category` and `emptyHint`. App-only, in `app/src/main/assets/i18n-android/pantry/en.json`:
`android.pantry.locationExists`, `android.pantry.locationLast`. The "more details" fold is upstream's `modal.moreDetails`.
