# Spec: Inventory

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

Code: `app/src/main/java/io/github/marcomow/yuvomi/feature/inventory/` (`InventoryRules` is the pure
port; `InventoryViewModel` the list, `ItemDetailViewModel` the read view, `ItemEditorViewModel`
the form, `InventoryManagerViewModel` both managers; `InventoryBus` carries changes, pending
deletes and the household's money between them). Routes: `inventory?category={category}` (list,
also `InventoryRoutes.START` and `InventoryRoutes.category(key)`), `inventory/{id}` (read view,
`InventoryRoutes.item(id)`), `inventory-editor?id={id}` (form), `inventory-categories`,
`inventory-locations`.

## Purpose

What the household owns, as a native screen: items with category, place, purchase, warranty,
tracked dates (TÜV, service, insurance) with reminders and repeats, a service history with the
odometer, linked budget bookings, documents and a photo; adding, editing, deleting; categories
and places.

## Scope

Everything `public/pages/inventory.js` lets a member do. Nothing of the page stays in the browser.
Not part of the page and therefore not here: the deadlines calendar feed (`/inventory/deadlines-feed`,
Settings' calendar subscriptions), "which items does this booking link" (`/inventory/entries/{id}/items`,
Budget's page), and the bare service-log routes (`/items/{id}/service-log`), which the page never
calls: it writes the service log only through "Done" on a tracked date and reads it through
`/history`. The web page's list-and-detail split view and its navigation badge have no phone
counterpart.

## Upstream references

- `public/pages/inventory.js` (`visibleGroups`, `groupItemsByCategory`, `groupItemsByLocation`,
  `categoryChips`, `computeMetrics`, `renderMetrics`, `renderItemRow`, `deadlineChipHtml`,
  `renderItemDetail`, `trackedDatesDetailNode`, `historyDetailNode`, `odometerChartPoints`,
  `openCompletionSheet`, `openBookingPicker`, `renderLinkedEntries`, `buildItemForm`, `saveItem`,
  `collectTrackedDates`, `offerCurrentLocations`, `removeItem`, `openLocationManager`,
  `openCategoryManager`, `mayChangeItem`).
- `public/utils/inventory-warranty.js`, `date-status.js`, `money.js` and `amount-field.js` (through
  Budget's `Money` port), `avatar-crop.js`, `friendly-error.js`; `public/components/category-manager.js`,
  `document-attach.js`; `public/permissions.js` (`canChangeRecord`, `canSeeInventoryPrices`).
- `server/routes/inventory/*.js`.

## Upstream operations

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/inventory/items` | `data` (with tracked dates, links, attachments) | the list (also the Tags view) |
| POST | `/inventory/items` | `data`, 201; 400 `inventory_selected_location_not_found`; 404 `inventory_booking_not_found` | "Add" (with `entry_id` when a booking was picked) |
| GET | `/inventory/items/{id}` | `data`; 404 `inventory_item_not_found` | read view, form prefill |
| PUT | `/inventory/items/{id}` | `data`; 403 `not_owner` | "Save" |
| DELETE | `/inventory/items/{id}` | 204 | delete |
| GET | `/inventory/items/{id}/history` | `data: { timeline, total }` | history and odometer chart |
| POST | `/inventory/items/{id}/entries` | `data` (the item), 201; 409 `inventory_booking_already_linked` | "Add booking" with a role |
| DELETE | `/inventory/items/{id}/entries/{entryId}` | `data` (the item) | remove a booking |
| POST | `/inventory/items/{id}/dates/{dateId}/complete` | 201; 404 `inventory_tracked_date_not_found` | "Done" |
| GET/POST | `/inventory/categories` | `data` | category manager, form |
| PUT | `/inventory/categories/{key}` | `data`; 409 `category_exists` | rename |
| PATCH | `/inventory/categories/reorder` | `data` | move |
| DELETE | `/inventory/categories/{key}` | `{ ok, reassigned }`; 400 for `other` | delete (items fall back to "other") |
| GET/POST | `/inventory/locations` | `data` (tree) | place manager, form |
| PUT/DELETE | `/inventory/locations/{id}` | `data` / `{ ok, … }`; 409 `inventory_location_exists` | rename, delete |
| PATCH | `/inventory/locations/reorder` | `data` | move |
| POST/PUT/DELETE | `/inventory/locations/{parentId}/subcategories[/{id}]` | `data` | sub-places |
| PATCH | `/inventory/locations/{parentId}/subcategories/reorder` | `data: true` | move a sub-place |

Calls of other modules, through their own enums: `GET /budget?month=` (`BudgetOperation.ENTRIES_LIST`,
the booking picker), `GET /documents` and `POST /documents` (`DocumentsOperation`, the documents
field), `GET /preferences` (core, the household's currency and number locale).

## Requirements

### List

- **INV-R1** The list loads places, categories, items and the household's currency together.
- **INV-R2** Without a category chip, items group by category in the categories' order (unknown
  keys after); with one, they group by top-level place, then "No location" (`visibleGroups`).
  Groups show their count.
- **INV-R3** Chips: "All" with the count and one per category in use with its count; a category
  named by the route that no item has falls back to "All" (`isShownCategory`). Search matches
  name, brand, model and serial number, and its placeholder names the chosen category.
- **INV-R4** The metrics line: the item count and, with the price capability, the total value in
  the household's currency (`computeMetrics`); the "Needs attention" chip with its count while an
  item has a deadline or the chip is on, which then keeps only those items.
- **INV-R5** A row shows the name, a paperclip with attachments, a receipt with bookings, the
  status when not "Owned", the deadline chip (`deadlineChipSpec`: overdue first, then the soonest
  within 30 days, a warranty that ended at most 30 days ago), the place path and, with the price
  capability, the price.
- **INV-R6** Empty states: "Your inventory is still empty" (with description and "Add item" only
  with write), the search's one reset (term and attention filter), and "Nothing needs attention"
  with "Clear filter".

### Read view

- **INV-R7** The read view loads the item and its history; it shows the photo, category, place,
  the purchase group (brand, model, serial, purchase date, price with the capability, vendor,
  "Registered under"), the warranty with its state, tracked dates, condition, status, odometer
  with its date, notes, linked bookings, documents (opening in Documents, only when Documents is
  visible) and the history (`renderItemDetail`). Empty rows are left out.
- **INV-R8** A tracked date shows its date and countdown, the distance interval; read-only members
  also see "Remind me" and "Repeats every" (`trackedDateFormOnlyValues`).
- **INV-R9** History: the odometer chart from two readings on (the service log's readings plus the
  item's own), then the timeline (service entries with vendor, odometer and note; bookings with
  role and amount; documents) and the total cost; a failed history load says
  `inventory.historyLoadError` in its place.
- **INV-R10** A due date (overdue or within 30 days) offers "Done" to whoever may change the item:
  date (today), odometer where the category tracks it, vendor, note; success says
  `inventory.completed` and reloads. A gone item or date says so and reloads.
- **INV-R11** Edit and delete are offered to who may change the item: `inventory: write` and own
  item or `inventory_edit_others` (`mayChangeItem`).

### Writing

- **INV-R12** Delete hides the item and sends `DELETE` when the snackbar ("“name” deleted.") goes
  without "Undo"; an already deleted item counts as deleted (`removeItem`).
- **INV-R13** The form: name (required, `common.nameRequired`), category, place ("Parent · Child"),
  status; when editing and Budget is visible the linked bookings with remove, total and "Add
  booking". Under "More details": purchase date, price (with the capability; checked like
  `amountFieldError`, never negative), "Link a booking" when creating, photo, brand, model,
  serial, vendor, "Registered under", warranty months with its live state, condition, odometer,
  unit and reading date when the category tracks it, up to ten tracked dates, notes, the documents'
  category and the documents field.
- **INV-R14** The payload is the web app's (`saveItem`): trimmed texts or null, `tracked_dates`
  with label and date only (blank offset 30), the odometer only for an odometer category,
  `purchase_price` only with the capability, `attachment_document_ids` after uploading new files
  into the "Inventory" folder, and `entry_id` of a picked booking. Creating sends an
  `Idempotency-Key`.
- **INV-R15** The booking picker lists a month of booked entries (no generated instances, nothing
  pending) with month navigation; when creating, the pick fills an empty price with its amount;
  when editing, a role is asked and the link is made at once.
- **INV-R16** The photo is a centred square of 256 px as JPEG (`pickCroppedImage`).
- **INV-R17** A save that names a deleted place reloads the places and clears the choice; a gone
  booking clears the pick; a gone item closes the form.
- **INV-R18** Offline (cached list) shows the banner and no writing affordance.

### Managers, errors, tags

- **INV-R19** Categories: add, rename, move up and down, delete with "fall back to Other";
  "Other" offers no delete.
- **INV-R20** Places: add, rename, move and delete places and their sub-places; deleting keeps the
  items without a place.
- **INV-R21** Errors use `friendly-error.js`'s sentences; the managers' `category_exists` and
  `category_last` their own.
- **INV-R22** `InventoryTagSource`: a `#hashtag` in an item's name, brand, model or notes is a tag;
  each tagged item opens its read view.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| INV-A1 | Given the seeded server then items with tracked dates, categories ("vehicles" tracks the odometer) and the place tree decode. | contract | written, runs in CI (`InventoryContractTest`) |
| INV-A2 | Given a new item then create, read, history, update and delete answer as the app expects; the deleted item is a 404 `inventory_item_not_found`. | contract | written, runs in CI |
| INV-A3 | Given due dates then "Done" rolls a repeating one forward, removes a one-off, moves the odometer, logs both, and refuses a falling odometer and a gone date. | contract | written, runs in CI |
| INV-A4 | Given a seeded booking then it links with a role, refuses a second link, unlinks, and `entry_id` on create links it as the purchase. | contract | written, runs in CI |
| INV-A5 | Given a new category then it renames, reorders, refuses a duplicate, and deleting moves its item to "other"; "other" cannot be deleted. | contract | written, runs in CI |
| INV-A6 | Given a new place with sub-places then they rename, reorder and delete, the item keeps no place, and a save naming it is refused with its reason. | contract | written, runs in CI |
| INV-A7 | Given the form then Budget's month and the documents list answer. | contract | written, runs in CI |
| INV-A8 | Given dates then the countdown, warranty end and the deadline chip match `date-status.js` and `inventory-warranty.js`. | unit | passing (`InventoryLogicTest`) |
| INV-A9 | Given items then groups, search, attention filter, value, place options and odometer points match the page. | unit | passing |
| INV-A10 | Given forms then the payloads of save and "Done" match the page; labels, permissions and errors resolve; the tag source lists tagged items. | unit | passing |
| INV-A11 | Given the list on screen then groups, deadline and status badges, the attention chip and a category chip (grouping by place) work. | ui | passing (`InventoryScreenTest`) |
| INV-A12 | Given an item then the read view shows tracked dates with "Done", the odometer chart and the history. | ui | passing |
| INV-A13 | Given `inventory: read` then there is no FAB, no tools, no edit and no "Done", and the form-only values show. | ui | passing |
| INV-A14 | Given a delete from the read view when undo is used then no `DELETE` goes out; without undo one goes out. | ui | passing |
| INV-A15 | Given the form and the completion sheet then their payloads match the web app's, with idempotency keys. | ui | passing |

## Strings

Upstream keys from `inventory`, `category`, `documents`, `documentAttach`, `common`, `modal` and
`datepicker`. No app-only strings.

## Differences from the web app

- The photo is cropped to the centre square instead of the web app's crop dialog.
- The odometer chart is a plain line with grid; values and dates are in its content description,
  not in a table.
- Dates use the locale's medium date, not the household's date-format preference.
