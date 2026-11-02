# Spec: Meals

Status: implemented · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011

## Purpose

The household's meal plan as a native screen, at parity with the web app's week plan: see the
week in the household's week start and meal types, plan meals by hand or from a recipe, edit and
delete them with their ingredients and weekly series, fill open slots at random, and send
ingredients to a shopping list, with the web app's messages and undo.

## Scope

| In this spec | Requirement IDs |
|---|---|
| Week plan: navigation, household week start and meal types, cards, read sheet, empty and error states, offline, read-only | R1–R8 |
| Meal form: name with suggestions, type, date, cook, ingredients, recipe and servings, save as recipe, notes, link, weekly repeat, series scope, delete | R9–R20 |
| Recipe rail, random plan, shopping-list transfers with undo | R21–R26 |

**Not native, and why:** dragging a card or a recipe onto a slot (the web app's pointer gesture;
the form's date and meal type, and the rail's tap, are the web app's own way without a pointer,
and the native ones here); recipe thumbnails (the image routes belong to Recipes); the
`?open=<id>` deep link of the overview (no caller in the app yet). Nothing of the page opens in
the browser.

## Upstream references

- `public/pages/meals.js` (`render`, `startWeek`, `loadWeek`, `renderWeekGrid`, `renderSlot`,
  `mealReadHtml`, `openMealReadModal`, `openMealModal`, `buildModalContent`, `saveModal`,
  `deleteMeal`, `transferMeal`, `openRandomizeModal`, `runRandomize`,
  `buildRandomMealAssignments`, `railRecipeTarget`, `mealTitleSuggestions`, `cookPickerHtml`,
  `showCookForScope`, `recipeIngredientsOf`, `emptyWeekOptions`, `readOnly`).
- `public/utils/meal-types.js`, `recipe-meal-types.js`, `recipe-to-meal.js`, `kitchen-transfer.js`,
  `servings-stepper.js`, `scale-quantity.js`, `ingredient-row.js`, `shopping-categories.js`,
  `date.js` (`startOfLocalWeekKey`, `weekStartIndex`), `friendly-error.js` (`errorSentence`).
- `server/routes/meals.js`, `server/services/meal-cook.js`, `server/routes/family.js`,
  `server/routes/preferences.js` (`visible_meal_types`, `meal_type_names`, `week_start`).

## Upstream operations

Shared kitchen calls (core `Operation`, `KitchenApi`): `GET /meals?week=` (`data`, the week's
meals with `ingredients`, `recipe_ingredient_count`, cook and series fields), `POST /meals`
(`data`, 201), `GET /recipes`, `POST /recipes`, `GET /shopping`, `GET /shopping/categories`,
`POST /shopping/items/undo-transfer`, and `GET /preferences`.

This module's own (`MealsOperation`, `upstream/operations-used.d/meals.txt`):

| Method | Path | Envelope | Used for |
|---|---|---|---|
| GET | `/meals/suggestions?q=` | `data` | titles of earlier meals for the name field |
| POST | `/meals/apply-plan` | `data`, 201 | the random plan, `{ assignments, replace_existing }` |
| PUT | `/meals/{id}` | `data` | edit one meal; `?scope=series` edits the series |
| DELETE | `/meals/{id}` | 204 | delete one meal; `?scope=future` ends a series, `?scope=series` deletes it |
| POST | `/meals/{id}/ingredients` | `data`, 201 | add an ingredient on a single-meal edit |
| PATCH | `/meals/ingredients/{ingId}` | `data` | change an ingredient on a single-meal edit |
| DELETE | `/meals/ingredients/{ingId}` | 204 | remove an ingredient on a single-meal edit |
| POST | `/meals/{id}/to-shopping-list` | `data` | a meal's open ingredients to a list, `{ listId }` |
| POST | `/meals/week-to-shopping-list` | `data` | the shown week's open ingredients, `{ listId, week }` |
| GET | `/family/members` | `data` | who can cook, and their pictures |

## Requirements

Status: **done** unless noted.

### Week plan

- **MEALS-R1** The Meals entry in the bar opens the week plan: a top bar with `nav.meals`, a week
  stepper (previous, the date range, next) and, while another week shows, `meals.today` behind it
  (`weekNavHtml`, `syncTodayButton`). `MealsRoutes.week(date)` opens the week containing `date`.
- **MEALS-R2** Loading: `GET /preferences` first (its `week_start` decides the week's first day,
  `startWeek`), then `GET /meals?week=<first day>`; recipes, lists, categories and members load
  alongside and keep their last value when they fail (`loadLists` & co.). "Today" is the
  household's day (`shell.time`).
- **MEALS-R3** The week is seven days from the household's week start (`monday`, `sunday`,
  `saturday`, anything else Monday). Each day is a card with the short day name, the date, a mark
  on today and an add button (`meals.addMealOnDay`); inside, one row per visible meal type in
  the fixed order breakfast, lunch, dinner, snack (`visible_meal_types`), labelled with the
  household's own name (`meal_type_names`) or the built-in one.
- **MEALS-R4** A meal card shows its title, a series mark (`meals.recurrenceBadge`), the cook's
  avatar (`meals.cookNamed`), and the ingredient count, its own or the recipe's while it has none,
  with ✓ when all own ingredients are on a list. Actions on the card: the linked recipe (opens
  `RecipesRoutes.recipe(id)`, while Recipes is reachable), else an external `http(s)` recipe link
  in the browser, and the cart while ingredients are open and the member may transfer (R24). An
  empty slot shows an add button (`meals.addMeal`). No delete on the card (the web app's named
  exception: delete lives in the form).
- **MEALS-R5** Tapping a card opens the read sheet (`mealReadHtml`): date with weekday, meal type,
  cook, ingredients (`qty · name (category)`, the recipe's while the meal has none), saved recipe,
  notes, link, "weekly" and the series end; empty values have no row. At `write` it offers
  `common.toShoppingList` (R24) and `common.edit`.
- **MEALS-R6** An empty week shows `meals.emptyTitle`; at `write` also the description, the hint
  (`meals.emptyHintRecipes` with recipes, else `emptyHint.meals`) and `meals.emptyAction`. A
  failed week load shows `meals.loadError` with retry, never a false empty week.
- **MEALS-R7** Read-only (`meals: read`): the week, cards and read sheet stay; the FAB, add buttons,
  tools menu, rail, cart and Edit are gone (`readOnly`, `READ_SAFE_ACTIONS`).
- **MEALS-R8** Offline (ADR 0007): preferences, lists and each loaded week are cached; a failed
  load shows the cached week read-only with the offline banner. Pull to refresh reloads; the
  screen reloads when it returns after more than a minute.

### Meal form

- **MEALS-R9** The form is a full screen (`meals.addMealTitle` / `meals.editMeal`), opened by the
  FAB (today, the first visible type), a day's add button, an empty slot, the empty state, the
  rail, or Edit. Order as the web app: the series scope for a recurring meal, the name, meal type
  chips, date, cook, ingredients, "more details", the transfer section, then Delete, Cancel and
  Save/Add.
- **MEALS-R10** The name is required (`common.nameRequired`). Typing asks
  `GET /meals/suggestions` after 200 ms and shows recipes containing the text (prefix matches
  first) then earlier meals not covered by a recipe, at most 8 (`mealTitleSuggestions`). A
  recipe suggestion applies the recipe (R13).
- **MEALS-R11** Cook: one member or `userMultiSelect.nobody`, from `/family/members` plus a
  stored cook who is no member any more; hidden in a household of one. A series edit sends the
  cook only when it was picked in this form; an untouched picker shows the series' cook in
  series scope (`showCookForScope`).
- **MEALS-R12** Ingredients: a stored meal shows them as a read list (the recipe's,
  `meals.recipeIngredientsLabel`, or its own) with `meals.recipeIngredientsEdit`, which turns them
  into editable rows; a new meal shows rows. A row has name, quantity, category (shopping
  categories without `Haushalt` and `Drogerie`, built-in names translated) and remove; rows
  without a name are dropped.
- **MEALS-R13** "More details" (`modal.moreDetails`, open when the meal has a recipe, notes or a
  link, or a recipe was chosen): saved recipe (grouped by source once mirrored recipes exist);
  choosing one fills title, notes, link and the rows; a servings stepper then rescales the rows
  (`servingsRange`, `scaleQuantityText`).
- **MEALS-R14** "Save as recipe" sends `POST /recipes` with title, notes, link and ingredients,
  selects the new recipe and confirms with `recipes.created`.
- **MEALS-R15** A new meal can repeat weekly (`meals.recurrenceLabel`) with an optional end date;
  the end must not be before the date (`meals.recurrenceUntilBeforeStart`).
- **MEALS-R16** Create sends `POST /meals` with `date`, `meal_type`, `title`, `notes`,
  `recipe_url`, `recipe_id`, `cook_user_id`, `ingredients`, `repeat_weekly`, `repeat_until` and an
  `Idempotency-Key`; `meals.mealSaved`.
- **MEALS-R17** A recurring meal asks for the scope (`meals.editScopeSingle` / `Series`). Series
  sends `PUT /meals/{id}?scope=series` with type, title, notes, link, recipe, ingredients,
  `repeat_until` (empty means no end) and the cook when picked; the date is not part of it.
- **MEALS-R18** A single edit sends `PUT /meals/{id}` with date, type, title, notes, link, recipe
  and cook, then removes deleted ingredients, adds new ones and **patches changed ones**
  (`PATCH /meals/ingredients/{ingId}`). The web app keeps edits of an existing row only in
  series scope; the app saves them in both. `meals.mealUpdated`.
- **MEALS-R19** Delete is in the form. A recurring meal asks `meals.deleteRecurringTitle` (this
  date, this and all following, whole series); a series end or delete happens at once with
  `meals.seriesEndedToast` / `meals.seriesDeletedToast`. One meal disappears at once with
  `meals.deletedToast` and Undo, and is deleted when the message goes (`scheduleUndoableDelete`).
- **MEALS-R20** Errors use upstream's sentences (`errorSentence`): offline
  `common.errorOfflineMutation`, 403 `common.errorNoPermission` (or `common.errorModuleNoAccess`,
  `common.errorFormExpired`), 429 `common.errorRateLimited`, `meals_selected_recipe_not_found`
  `recipes.errorRecipeGone`, else `common.errorGeneric`. A 404 on a call about one meal says
  `android.meals.gone` and reloads the week. Leaving a changed form asks `modal.unsavedChanges`.

### Recipes, random plan, shopping

- **MEALS-R21** Below the week, at `write`, a rail of recipes (`nav.recipes`, `recipes.railHint`)
  with ingredient count, meal types when a true subset (or `recipes.mealTypeNone`) and the
  source of mirrored ones. A tap opens a new meal with the recipe applied, on the meal of the hour
  and the first free day from today (`railRecipeTarget`). Without recipes, the recipes empty
  state with `recipes.emptyAction`. The tools menu hides or shows the rail.
- **MEALS-R22** "Randomize plan" (tools menu) previews how many open slots it fills and how many
  meals it overwrites with "replace existing" (`buildRandomMealAssignments`: recipes that declare
  the meal type, no recipe twice on a day or on two days running for one meal), then sends
  `POST /meals/apply-plan` and confirms `meals.randomizeSuccess`; a full week or no compatible
  recipe says so.
- **MEALS-R23** "Add the week's ingredients to the shopping list" (tools menu, app addition:
  upstream's route has no caller in the web app yet) sends `POST /meals/week-to-shopping-list`.
- **MEALS-R24** Transfers need `meals: write` and `shopping: write`
  (`mayTransferMealToShopping`). No list: `kitchen.noShoppingLists` with
  `kitchen.createShoppingList` to Shopping when allowed; one list is the target; more ask
  `common.toShoppingListWhich`. The form has its own list choice (`meals.transferLabel`).
- **MEALS-R25** After a transfer: `meals.transferSuccess` naming the list, plus
  `kitchen.transferSkipped` when some were already on it, with Undo while `shopping: write`
  (`POST /shopping/items/undo-transfer` with `ids` and `ingredient_ids`, then
  `kitchen.transferUndone`); nothing new is `kitchen.transferAllPresent`. The week reloads.
- **MEALS-R26** The plan's own strings come from upstream's `meals`, `kitchen`, `recipes`,
  `common`, `modal`, `calendar`, `shopping`, `emptyHint` and `userMultiSelect`; app-only:
  `android.meals.gone`, `android.meals.weekToShopping`.

## Acceptance criteria

| ID | Given / When / Then | Level | Status |
|---|---|---|---|
| MEALS-A1 | Given week starts monday, sunday, saturday and an unknown value then 2026-11-04 starts the week on 11-02, 11-01, 10-31 and 11-02. | unit | pass |
| MEALS-A2 | Given `visible_meal_types` `[dinner, breakfast, brunch]` and `dinner` named "Supper" then the rows are breakfast, dinner and dinner reads "Supper". | unit | pass |
| MEALS-A3 | Given a recipe tapped at 19:00 on a Wednesday with Wednesday dinner taken then it is planned for Thursday dinner; at 12:00 for a breakfast/dinner recipe, dinner. | unit | pass |
| MEALS-A4 | Given two lunch/dinner recipes and one taken slot then the random plan fills 13 of 14 slots without a recipe twice on a day; replacing lists the taken meal; a full week is `week_full`. | unit | pass |
| MEALS-A5 | Given "ridge" then "Ridge soup", "Porridge" (recipes) and "ridgeback stew" (history) are suggested in that order. | unit | pass |
| MEALS-A6 | Given factor 2 then "250 g" is "500 g", "1 1/2 cups" is "3 cups", "a pinch" stays, "1.000 g" stays with a decimal comma. | unit | pass |
| MEALS-A7 | Given a recipe meal with 4 recipe ingredients then the card counts 4 and offers the cart; all own ingredients on a list removes it. | unit | pass |
| MEALS-A8 | Given an edit that changes one row, removes one and adds one then the ingredient calls are one DELETE, one PATCH, one POST. | unit | pass |
| MEALS-A9 | Given the form then create, series and single bodies carry exactly upstream's fields; the series body has no date and the cook only when picked. | unit | pass |
| MEALS-A10 | Given each failure kind then the message key is upstream's sentence. | unit | pass |
| MEALS-A11 | Given the scripted week then dinner reads "Supper" and the card shows its ingredient count. | ui | pass |
| MEALS-A12 | Given one shopping list when the cart is tapped then `listId` 3 is sent and the message names "Groceries". | ui | pass |
| MEALS-A13 | Given one lunch/dinner recipe when the plan is randomized then the preview says 13 and `apply-plan` sends recipe 1 without replacing. | ui | pass |
| MEALS-A14 | Given the read sheet when Edit is used and the title changed then a single `PUT` carries the title and cook, no ingredient call is made, and `meals.mealUpdated` shows. | ui | pass |
| MEALS-A15 | Given the next-week button then `GET /meals?week=` asks for the next week's first day and the label shows its range. | ui | pass |
| MEALS-A16 | Given `meals: read` then there is no FAB, cart or tools menu, and the read sheet has no Edit. | ui | pass |
| MEALS-A17 | Given the seeded server as alex then preferences, the week, recipes, lists and categories decode and every meal is in its week. | contract | CI |
| MEALS-A18 | Given `/family/members` then alex is among them with names. | contract | CI |
| MEALS-A19 | Given a created meal then suggestions find it by its start, not by its middle. | contract | CI |
| MEALS-A20 | Given a create with cook and an ingredient when it is retried with the same key then one meal exists, with category `Sonstiges`. | contract | CI |
| MEALS-A21 | Given apply-plan twice into one slot, the second replacing, then only the second meal is there. | contract | CI |
| MEALS-A22 | Given apply-plan with an unknown recipe then 400 `meals_selected_recipe_not_found`. | contract | CI |
| MEALS-A23 | Given a single edit then date, type and cook change, and `null` clears the cook. | contract | CI |
| MEALS-A24 | Given a weekly series with an end when the series is renamed without a cook then next week's meal is renamed, keeps its cook and the end; past the end there is none. | contract | CI |
| MEALS-A25 | Given a deleted meal then deleting again is 404; `future` ends a series there, `series` removes it. | contract | CI |
| MEALS-A26 | Given an ingredient added, patched and deleted then each answer matches and a second delete is 404. | contract | CI |
| MEALS-A27 | Given a meal with two ingredients when sent to a list then 2 are added and marked, a second send adds none, undo unmarks them. | contract | CI |
| MEALS-A28 | Given a meal from a recipe without own ingredients then the card counts the recipe's, and the transfer materialises them. | contract | CI |
| MEALS-A29 | Given two meals in a week when the week is sent then both are marked; undo unmarks them. | contract | CI |
| MEALS-A30 | Given an unknown list then 404 and nothing is marked. | contract | CI |
| MEALS-A31 | Given "save as recipe" then the recipe appears in `GET /recipes` with its ingredient. | contract | CI |
