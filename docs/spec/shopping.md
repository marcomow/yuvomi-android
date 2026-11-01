# Spec: Shopping

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's shopping lists as a native screen, used in the shop with a weak signal: switch
between lists, add items fast, tick them off instantly, and after the shop move what was bought
into the pantry. Everything the web app's `/shopping` page lets a member do is native; nothing of
it opens in the browser.

## Scope

| Area | Requirement IDs |
|---|---|
| Lists: chips, create, rename, duplicate, delete with undo, remember the last list | R1–R7 |
| Items: grouping, the "Checked off" section, quick add, suggestions, revive, edit, move, delete with undo, tick, reorder, clear checked | R8–R24 |
| Categories and shops: manage, reorder categories | R25–R27 |
| Send, meal plan import, pantry transfer | R28–R31 |
| Live updates, offline, permissions, errors | R32–R38 |

**Not in the web page either** (and so not here): a search field (the page has none; global
search reaches an item through `?highlight=<id>`, which [ShoppingRoutes.item] mirrors), category
colours or icons (the shared category manager has colours, the shopping page does not pass any).

## Upstream references

- `public/pages/shopping.js`: `listSections`, `toggleShoppingItem`, `deleteItemUndoable`,
  `listToolsItems`, `renderTabs`, `openSendListDialog`, `openDuplicateListDialog`, `renderItem`,
  `tickedItemNamed`, `reviveTickedItem`, `applyAutocompleteSuggestion`, `wireQuickAdd`,
  `resetQuickAddCategory`, `persistItemOrder`, `openItemDetails`, `parseShoppingQuantity`,
  `pantryTransferPreset`, `openPantryTransfer`, `updateCheckedActions`, `clearCheckedUndoable`,
  `itemMovedAway`, `openMealPlanImport`, `loadPageData`, `refreshFromFeed`, `reportError`,
  `wireListContentEvents` (rename, delete list), `openStoreManager`, `openCategoryManager`.
- `public/utils/shopping-categories.js` (`DEFAULT_CATEGORY_NAME`, `categoryLabel`),
  `utils/kitchen-transfer.js` (`mayImportMealPlan`, `mayTransferShoppingToPantry`,
  `announceTransfer`, `transferMessage`), `utils/live-feed.js`, `utils/friendly-error.js`,
  `utils/pantry-locations.js`, `utils/pantry-units.js`, `components/category-manager.js`.
- `server/routes/shopping.js`, `server/routes/pantry.js` (`/import-shopping`, `/undo`).

## Upstream operations

Shared kitchen calls (core `KitchenApi`): `GET /shopping`, `GET /shopping/categories`,
`POST /shopping/items/undo-transfer`, `GET /pantry`, plus `GET /preferences` for the currency.
This module's own (`ShoppingOperation`, 25):

| Method | Path | Envelope | Used for |
|---|---|---|---|
| POST | `/shopping` | `data`, 201 | new list `{ name }` |
| PUT | `/shopping/{listId}` | `data` | rename |
| DELETE | `/shopping/{listId}` | bare `{ ok }` | delete (after the undo window) |
| POST | `/shopping/{listId}/duplicate` | `data`, 201 | `{ name, resetChecked, keepQuantities, keepNotes }` |
| GET | `/shopping/{listId}/items` | `data` + `list`, `categories`, `version` | the items |
| POST | `/shopping/{listId}/items` | `data` + `list_change`, 201 | quick add `{ name, quantity, category }` |
| PATCH | `/shopping/items/{itemId}` | `data` + `list_change` | tick, edit, move (`list_id`), price, shop |
| DELETE | `/shopping/items/{itemId}` | bare `{ ok, list_change }` | delete (after the undo window) |
| PATCH | `/shopping/{listId}/items/reorder` | `data` + `list_change` | `{ category, order }`, every item of the category |
| DELETE | `/shopping/{listId}/items/checked` | bare `{ deleted, list_change }` | `{ ids }`: exactly the rows removed |
| POST | `/shopping/categories` | `data`, 201 | add category |
| PUT | `/shopping/categories/{catId}` | `data` | rename (the server renames the items too) |
| DELETE | `/shopping/categories/{catId}` | bare | delete (items move to the first remaining) |
| PATCH | `/shopping/categories/reorder` | `data` | `{ order: [ids] }` |
| GET | `/shopping/stores` | `data` | shops |
| POST | `/shopping/stores` | `data`, 201 or 200 | a typed shop name that does not exist yet |
| PUT | `/shopping/stores/{id}` | `data` | rename |
| DELETE | `/shopping/stores/{id}` | 204 | delete |
| GET | `/shopping/suggestions` | `data` | `?q=`; `{ name, category, quantity }[]`, newest first |
| GET | `/shopping/versions` | `data` | `{ list_id, version }[]`, the live feed |
| GET | `/shopping/send-recipients` | `data` | `{ id, display_name }[]` |
| POST | `/shopping/{listId}/send` | `data` | `{ userId }` |
| POST | `/shopping/{listId}/import-meal-plan` | `data` | `{ from, to, preview? }` |
| POST | `/pantry/import-shopping` | `data` | `{ list_id, items: [{ shopping_item_id, quantity, unit, location_id }] }` |
| POST | `/pantry/import-shopping/undo` | `data` | `{ changes }` from the import answer |

Fields read: list `id`, `name`, `item_total`, `item_checked`, `accepts_new_items`; item `id`,
`list_id`, `name`, `quantity`, `category`, `is_checked` (0/1), `notes`, `url`, `sort_order`,
`price_cents`, `store_id`, `tags[]`; category `id`, `name`, `sort_order`; shop `id`, `name`.

Error reasons mapped (`friendly-error.js`): `shopping_list_not_found`, `shopping_item_not_found`,
`shopping_store_not_found`, `shopping_store_exists`, `shopping_category_not_found`,
`category_exists`, `category_last`, `cross_module_access` (403), `recipient_no_email`,
`smtp_unconfigured`, `nothing_open` (422).

## Requirements

### Lists

- **SHOP-R1** The screen's top bar shows `nav.shopping`; below it a row of list chips, one per
  list in server order, with the open count (`item_total - item_checked`, a check mark when all
  are done), the active one selected; a `+` chip creates a list (`shopping.newListPrompt`).
  (`renderTabs`, `wireTabBar`)
- **SHOP-R2** The list menu (`shopping.listActionsLabel`) holds, in the web app's groups: when
  items are checked, "Into pantry" and "Remove checked (n)"; rename, duplicate, "From the meal
  plan" (only with meals write access), send; reorder mode, manage categories, manage shops;
  delete list. (`listToolsItems`)
- **SHOP-R3** Rename prompts with the current name; an unchanged or empty name sends nothing.
- **SHOP-R4** Duplicate asks for a name (default `shopping.duplicateDefaultName`) and the three
  switches (reset checked, keep quantities, keep notes; all on), then opens the copy.
- **SHOP-R5** Delete asks first, naming the item count (`deleteListConfirm` /
  `deleteListConfirmEmpty`, detail `deleteListConfirmDetail`), removes the list at once, switches
  to the first remaining one and offers undo (`deletedListToast`); the `DELETE` is sent only when
  the undo window closes. An already deleted list counts as success.
- **SHOP-R6** The route `shopping?list=<id>` (`ShoppingRoutes.list`) opens that list. Without
  one, the app opens the list this member opened last on this device, else the first list.
  Deviation: the web app always opens the first list; the app remembers the last one, per server
  and member, because a member usually shops from the same list.
- **SHOP-R7** No lists: `shopping.noLists` with description, hint and a "Create new list" action
  (title only for read-only members).

### Items

- **SHOP-R8** Items are grouped by category in the categories' order (unknown categories at the
  end); inside a category by `sort_order`, then id. Checked items leave their category for one
  "Checked off" section at the end (`shopping.tickedSection`, closed by default) ordered by
  category, then rank. (`listSections`)
- **SHOP-R9** Categories collapse; the collapsed set and the open state of "Checked off" are
  remembered per member and per list (keyed by category id, falling back to the name).
- **SHOP-R10** A row shows a check button, the name, link and note marks, the quantity, the
  first tag and `+N`; the row menu offers Edit and Delete. Tapping the row ticks it.
  Swiping from the start ticks or unticks, swiping from the end deletes. (`renderItem`,
  `wireSwipeGestures`)
- **SHOP-R11** Ticking is optimistic: the row changes at once, the `PATCH` follows; on failure
  the row returns and `common.errorCheckRolledBack` / `common.errorUncheckRolledBack` names it.
  A newer tap overrides an older one; a reload never undoes a tap whose request is still open.
  (`toggleShoppingItem`, `settleIntents`)
- **SHOP-R12** A ticked row stays where it is until the member pauses (1.2 s after the last own
  tap), then all waiting rows move together. (`holdPlacement`)
- **SHOP-R13** After the member's own tick into "checked", a bar shows "n items checked off"
  with "Into pantry" (when offered) and "Delete" (asks first) for five seconds.
  (`updateCheckedActions`)
- **SHOP-R14** The FAB opens the quick add above the list and focuses the name; with no list it
  creates one. Quick add has name, quantity and category (default "Sonstiges" when the household
  still has it, else the first category) and resets the category after each add.
  (`wireQuickAdd`, `resetQuickAddCategory`)
- **SHOP-R15** Typing a name asks `GET /shopping/suggestions` after 200 ms; picking one fills
  name, and category and quantity when known. A suggestion that is a checked item on this list
  says so (`shopping.itemStateChecked`) and fills that item's values.
  (`applyAutocompleteSuggestion`)
- **SHOP-R16** Adding a name that is checked off on this list (case-insensitive) brings that row
  back instead of a second one, with the typed quantity and a hand-picked category in the same
  `PATCH`, and says `shopping.itemBackToast`. (`tickedItemNamed`, `reviveTickedItem`)
- **SHOP-R17** A new item that is not in sight is announced (`shopping.itemAddedToast`); the app
  announces every add, since a phone screen rarely shows the target category.
- **SHOP-R18** Edit (bottom sheet): name (required), quantity, category, the list (only with
  more than one list that accepts new items), and under "More details" price, shop (pick or type;
  an unknown name creates the shop), link with "Open link", note; Delete, Cancel, Save. Price is
  read in the household currency (`GET /preferences` `currency`, default EUR) and sent as whole
  minor units; empty means no price. (`openItemDetails`)
- **SHOP-R19** Saving with another list moves the item (`list_id`) and says
  `shopping.itemMovedToast`. (`itemMovedAway`)
- **SHOP-R20** Delete (menu, swipe, editor) removes the row at once and offers undo
  (`shopping.itemDeletedToast`); `DELETE` is sent when the window closes. An item someone else
  already deleted counts as success. A reload during the window does not bring the row back.
  (`deleteItemUndoable`)
- **SHOP-R21** Reorder mode shows up and down buttons on open rows; a move sends the complete
  order of the category (open rows in shown order, then the rest), one request per category at a
  time. (`persistItemOrder`)
- **SHOP-R22** "Remove checked" asks (`shopping.clearCheckedConfirm`), removes the checked rows
  at once with undo (`itemsRemovedToast`) and then sends `DELETE …/items/checked` with exactly
  those ids. (`clearCheckedUndoable`)
- **SHOP-R23** Empty list: `shopping.emptyList`, description, and `emptyHint.shopping` when the
  pantry transfer is offered.
- **SHOP-R24** Read-only members see a static check mark, and a details button only when the item
  has price, shop, link or note, opening a read view. (`renderReadActions`, `itemReadHtml`)

### Categories and shops

- **SHOP-R25** Manage categories (sheet): add, rename, move up/down, delete with
  `shopping.categoryDeleteConfirmDetail`. Default German names show translated
  (`shopping.cat*`). After any change, categories and items reload.
- **SHOP-R26** Manage shops (sheet): add, rename, delete (`shopping.storeDeleteConfirm`,
  `storeDeleteConfirmDetail`). Shops have no order (the server sorts by name).
- **SHOP-R27** `category_exists`, `category_last` and `shopping_store_exists` show their sentences;
  an already deleted category or shop counts as deleted.

### Send, meal plan, pantry

- **SHOP-R28** Send: with no open items `shopping.sendListEmpty`; else recipients from
  `send-recipients` (none: `sendListNoRecipients`), a dialog with the open count, a recipient and
  the snapshot hint; success `sendListSent`; `recipient_no_email`, `smtp_unconfigured`,
  `nothing_open` map to their sentences, anything else `sendListError`.
- **SHOP-R29** From the meal plan (shopping and meals write): From (today) and To (today + 6),
  a live preview (`preview: true`) built like the web app's, Apply imports; nothing found shows
  `importMealsEmpty`, nothing new `kitchen.transferAllPresent`; success reloads and says
  `meals.transferSuccess` (+ `kitchen.transferSkipped`) with undo through
  `POST /shopping/items/undo-transfer` (`kitchen.transferUndone`).
- **SHOP-R30** Into pantry (pantry not switched off, pantry write): a sheet with one row per
  checked item: quantity, unit and storage place, preset from the pantry (`pantryTransferPreset`:
  same item's place and unit, else the place most used for that category, else the first);
  "All to" sets every row; "Remove items from the shopping list" (shopping write only, on).
- **SHOP-R31** The import answer's `added + merged` decides the message (`toPantryNothing`,
  `toPantryDone`, `toPantryDoneAt` when all went to one place). Undo takes back the pantry
  (`/import-shopping/undo` with `changes`) and, when the list was cleared, the cleared rows.

### Live, offline, permissions, errors

- **SHOP-R32** While the screen is in front, `GET /shopping/versions` is polled every 10 s;
  a moved list reloads the list chips and, when it is the active one, its items. The app's own
  writes acknowledge their `list_change` so they do not trigger a reload. A list deleted
  elsewhere switches to the first remaining one. (`startLiveFeed`, `refreshFromFeed`)
- **SHOP-R33** Pull to refresh reloads everything.
- **SHOP-R34** Offline (ADR 0007): the last lists, categories, shops and each list's items are
  cached per server and member and shown with `OfflineBanner` when the network fails. Ticking
  stays available, because it is optimistic and rolls back by itself (the web app also attempts
  it); every other write is hidden while the banner shows.
- **SHOP-R35** `shopping` access `read`: no FAB, quick add, list menu, `+` chip, swipe, row menu
  or reorder; check marks are static.
- **SHOP-R36** Errors are shown as the web app's sentences (`errorSentence`): the reasons above,
  403 `common.errorNoPermission` (or the refusal's own sentence), 429 `common.errorRateLimited`,
  no connection `common.errorOfflineMutation`, else the place's fallback.
- **SHOP-R37** A "no longer exists" reason (`isGone`) also reloads the page data; a dialog about
  the vanished list or item closes.
- **SHOP-R38** Creating calls (`POST`) carry one idempotency key per user action, reused when the
  same dialog is submitted again.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| SHOP-A1 | Given items in three categories, one checked, when sectioned then open items follow the category order, the checked one is in the ticked section, and a held row stays in its category. | unit | done |
| SHOP-A2 | Given a household with and without "Sonstiges" then the quick add default is "Sonstiges", else the first category. | unit | done |
| SHOP-A3 | Given `"500g"`, `"2 Dosen"`, `"1,5 l"` (de) and `"1,000 g"` (en) then the pantry quantity parser gives 500 g, 2 can, 1.5 l and the fallback 1 pcs. | unit | done |
| SHOP-A4 | Given a pantry with "Milk" in the fridge in l then a checked "milk" without unit is preset to the fridge in l; an unknown dairy item goes where most dairy is. | unit | done |
| SHOP-A5 | Given prices "1,50" (de, EUR), "1.5" (en, EUR), "300" (ja, JPY) then the cents are 150, 150 and 300; "1.200" (de) is refused as grouped, "1.505" (en, EUR) as too precise, "abc" as invalid. | unit | done |
| SHOP-A6 | Given the live feed has seen versions then an acknowledged own change does not trigger a reload, another change does, and a vanished list does. | unit | done |
| SHOP-A7 | Given a scripted server, when the member ticks a row then the row shows checked before the answer and the `PATCH` carries `is_checked: 1`; when the answer fails the row is open again. | ui | done |
| SHOP-A8 | Given the seeded server as `alex` then lists, items (with `version`, `categories`), categories and versions decode. | contract | done |
| SHOP-A9 | Given a new list, add, tick, edit (price, shop), move, reorder, clear checked, duplicate, rename and delete it, each answer decodes and reloading shows the change. | contract | done |
| SHOP-A10 | Given a category is added, renamed, reordered and deleted then items in it move to the first remaining category; adding an existing name is 409 `category_exists`. | contract | done |
| SHOP-A11 | Given `send` to a member then the answer is a success or 422 with `recipient_no_email` / `smtp_unconfigured`. | contract | done |
| SHOP-A12 | Given the meal plan has ingredients this week then preview and import agree, and undo removes the added items. | contract | done |
| SHOP-A13 | Given a checked item moved into the pantry then the pantry has it and undo removes it again. | contract | done |
| SHOP-A14 | Given `leo` (child) with shopping `read` then the app shows no FAB and no list menu. | ui | done |
| SHOP-A15 | Given the network fails after one good load then the cached list shows with the offline banner. | ui | done |

## Strings

App-only strings live in `app/src/main/assets/i18n-android/shopping/en.json` under
`android.shopping`: the read-only refusal sentence (`settings.permReadOnlyBanner` is not in the
shipped namespaces), the categories manager hint, reorder-mode labels, the "more details"
toggle, and the quick-add close label. Everything else reuses upstream's `shopping`, `kitchen`,
`common`, `category`, `pantry`, `calendar`, `meals`, `emptyHint` and `nav` keys.
