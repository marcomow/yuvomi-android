# Spec: Recipes

Status: implemented · Upstream checked: 2.87.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0012,
0015

## Purpose

The household's recipes as native screens: find a recipe, read it scaled to the servings at hand,
see which ingredients are linked to the pantry, plan it as a meal, put its ingredients on a
shopping list, and create, edit, duplicate and delete recipes with every field the web form has.

## Scope

Everything `public/pages/recipes.js` lets a member do. The web page is one screen with a list,
a detail (sheet or side column) and a form dialog; the app splits it into three destinations
that share one state holder (`RecipesViewModel`, scoped to the navigation graph), so a change in
one shows in the others at once, as on the web page.

| Destination | Route | Upstream |
|---|---|---|
| List | `recipes` | `render`, `buildRecipeList` |
| Detail | `recipes/{id}` (`RecipesRoutes.recipe(id)`, linked from Meals) | `fillRecipeDetail`, `openRecipeSheet`, `?open=<id>` |
| Form | `recipes-edit?id={id}` | `openRecipeModal`, `saveRecipe` |

**Out (stays in the browser):** connecting recipe sources (Mealie, Tandoor accounts, sync) under
Settings; the web page itself only links there. Moving the crop square of a picture: the app
takes the centre square (see RECIPES-R27).

## Upstream references

- `public/pages/recipes.js` and its utils: `scale-quantity.js`, `servings-stepper.js`,
  `money.js` (`toDecimalString`, `breaksOffAtSeparator`, `toStoredNumber`), `digits.js`,
  `recipe-meal-types.js`, `meal-types.js`, `recipe-to-meal.js`, `recipe-thumb.js`,
  `ingredient-row.js`, `ingredient-match-key.js`, `shopping-categories.js`, `kitchen-transfer.js`,
  `friendly-error.js`, `module-access.js`, `avatar-crop.js` (`pickCroppedImage`), `html.js`
  (`externalHref`).
- `server/routes/recipes.js`, `server/routes/meals.js` (`POST /`), `server/scopes.js`
  (`READ_LEVEL_WRITES`), `server/utils/file-signature.js`.

## Upstream operations

| Method | Path | Owner | Envelope | Used for |
|---|---|---|---|---|
| GET | `/recipes` | core (`KitchenApi`) | `data` | the list with ingredients and pantry links |
| POST | `/recipes` | core (`KitchenApi`) | `data`, 201 | create and duplicate, with an `Idempotency-Key` |
| PUT | `/recipes/{id}` | Recipes | `data` | edit (the whole form; `image_data` only when touched) |
| DELETE | `/recipes/{id}` | Recipes | 204 | delete after the undo window |
| GET | `/recipes/{id}/image` | Recipes | bytes | the own picture (`client.bytes`, same host) |
| GET | `/recipes/{id}/provider-thumbnail` | Recipes | bytes | a mirrored recipe's picture, `?v=provider_updated_at` |
| PUT | `/recipes/{id}/ingredient-match` | Recipes | `data` | link or unlink one ingredient to a pantry row |
| POST | `/recipes/{id}/to-shopping-list` | Recipes | `data` | ingredients onto a list |
| POST | `/shopping/items/undo-transfer` | core (`KitchenApi`) | `data` | undo of that transfer |
| GET | `/shopping`, `/shopping/categories` | core | `data` | lists to pick, categories for the form |
| GET | `/pantry` | core | `data` | rows for the link dialogs |
| GET | `/meals` | core | `data` | "planned this week" (no `week`: the current week) |
| POST | `/meals` | core | `data`, 201 | plan the recipe as a meal |
| GET | `/preferences` | core | `data` | `meal_type_names` (the household's words for the four meals) |

Error `reason` values read: `recipes_recipe_not_found`, `recipes_ingredient_not_found`,
`recipes_selected_pantry_item_not_found`, `recipes_ingredients_changed` (409),
`meals_selected_recipe_not_found`, `shopping_list_not_found`, `recipe_mirrored` (403),
`cross_module_access` (403).

## Requirements

### List

- **RECIPES-R1** The list loads `/recipes`, `/shopping/categories`, `/shopping` (not when Shopping
  is switched off), `/meals` (not when Meals is switched off) and `/preferences` in parallel; only
  the recipes are required, every other answer fails to empty (`loadRecipes`, `loadCategories`,
  `loadShoppingLists`, `loadPlannedRecipes`, `ensureMealTypeNames`). Cache first, then network
  (ADR 0007); the list reloads on pull and when it returns after more than a minute.
- **RECIPES-R2** Search over title, notes and ingredient names, case-insensitive, on the loaded
  list (`filteredRecipes`).
- **RECIPES-R3** Source filter (All, Native, each provider, sorted) in the top bar, only when a
  mirrored recipe exists; a source that disappears falls back to All (`renderSourceFilter`,
  `reportError`).
- **RECIPES-R4** A row shows the picture when the recipe has its own one or is mirrored
  (placeholder otherwise, `rowShowsThumb`, `recipeThumbEl`: own image first, else the provider
  thumbnail), the title, the source badge with the account name for mirrored recipes
  (`sourceBadge`), the ingredient count (`meals.ingredientCount`) and "Planned this week" unless
  every recipe is planned (R24).
- **RECIPES-R5** The row menu (writers only): Edit and Delete for native recipes, Duplicate for
  all (`ROW_ACTIONS`).
- **RECIPES-R6** Tapping a row opens the detail when there is something to read, for read-only
  members always, and for mirrored recipes; a native recipe without ingredients, notes or link
  opens the form (`buildRecipeList`, `openRecipeNarrow`; upstream's wide layout also shows a
  mirrored recipe without detail, which the app follows instead of an inert row).
- **RECIPES-R7** Empty states: load error (`recipes.loadError`, retry) before "none yet"
  (`recipes.emptyTitle`, with description, hint and the create action for writers only,
  `emptyListOptions`), and "no results" with "Reset search" (`mountSearchEmpty`).
- **RECIPES-R8** The FAB "Add recipe" for writers (`fabEl`).
- **RECIPES-R9** Offline: the cached list with the offline banner, read-only (no FAB, no menus,
  no write actions in the detail).

### Detail

- **RECIPES-R10** The own picture as the head, nothing when there is none or it fails
  (`recipeHeroEl`).
- **RECIPES-R11** Meal chips when the recipe is not for every meal, always for read-only members,
  "No meal" when it has none; labels are the household's names (`fillRecipeDetail`,
  `mealTypeLabel`).
- **RECIPES-R12** Ingredients with the quantity in its own right-aligned column and, for
  read-only members, the shopping category under the name (`ingredientsSectionEl`,
  `categoryLabel`).
- **RECIPES-R13** The servings stepper beside the heading: servings 1 to 99 in steps of 1 when the
  recipe has servings, else "Amount × n" from 0.5 to 10 in steps of 0.5; the choice lasts for the
  visit and is not saved (`servingsStepperHtml`, `setServingsValue`, `chosenServings`).
- **RECIPES-R14** Quantities scale exactly as `scaleQuantityText`: only the leading number
  (mixed fraction, fraction or decimal) of the free text; the rest of the line is kept from the
  original code point by code point; digits of any numbering system are read; a grouped leading
  number and a number cut off inside a separator stay unchanged; the result has at most two
  fraction digits, no grouping, the locale's digits and decimal separator. The format locale is
  the UI language (upstream falls back to it when the browser has no region stored).
- **RECIPES-R15** Pantry links (#1314): with pantry access an existing link shows its pantry row's
  name; members who may link (pantry `write` and meals `write`, `mayMatchIngredient`) tap it to
  change or remove the link in a dialog listing "Not linked" and every pantry row as
  name · place · best-before (`pantryOptionLabel`). Without pantry access nothing shows. A chosen
  row that is gone keeps the dialog open with the pantry as it is now
  (`offerCurrentPantryItems`).
- **RECIPES-R16** "Link N ingredients to your pantry" under the list opens one dialog with a
  picker per unlinked ingredient; only the chosen ones are saved, one by one; what was saved
  before a failure stays (`openPantryBulkMatchModal`).
- **RECIPES-R17** "To the meal plan" (meals `write`): a dialog with the date (today in the
  household zone) and the meal (the recipe's meals, all four when it has none; preselected: its
  only one, else dinner, else the first) sends `POST /meals` with `mealPayloadFromRecipe` and the
  scaled ingredients, then `recipes.planSuccess` (`planRecipe`).
- **RECIPES-R18** "Add to shopping list" when there are lists and ingredients and the member has
  meals `read` and shopping `write` (`mayTransferRecipeToShopping`); one list goes straight,
  several ask which. With a changed servings choice the body carries `quantities` and
  `ingredientIds`. The snackbar names the list and what was already on it (`transferMessage`) and
  offers undo of exactly `added_ids` when the member may write Shopping (`announceTransfer`);
  "all already on the list" and "no ingredients" have their own sentences. A 409
  `recipes_ingredients_changed` reloads the list, so the detail shows the recipe as it is now.
- **RECIPES-R19** "Open recipe link" for `http(s)` links only, in the browser (`externalHref`).
- **RECIPES-R20** Top bar: Edit for native recipes, Duplicate and Delete (native only) in the
  overflow, for writers (`recipeActions`).
- **RECIPES-R21** Notes as their own section (`detailSectionEl`).
- **RECIPES-R22** A recipe that is gone shows `recipes.errorRecipeGone`.

### Create, edit, duplicate, delete

- **RECIPES-R23** The form, in upstream's order: name (required, `common.nameRequired`), meals
  (four chips, all on for a new recipe), servings (empty or 1 to 99, `recipes.servingsInvalid`),
  ingredients (name, quantity, shopping category without household goods and drugstore, remove;
  `ingredientRowHTML` resolves an unknown category to the first offered one), and behind "More
  details" (open when the recipe has notes or a link) notes, picture and link.
- **RECIPES-R24** Create sends `POST /recipes` with an `Idempotency-Key` kept across retries;
  edit sends `PUT /recipes/{id}`; both with `title`, `notes`, `recipe_url`, `meal_types`,
  `servings`, `ingredients` (empty names dropped, empty quantity `null`, category default
  `Sonstiges`), and `image_data` only when the picture was touched (`saveRecipe`). Saving resets
  the servings choice. The saved recipe takes its place in the list in server order.
- **RECIPES-R25** Duplicate posts `<title> (<recipes.copySuffix>)` with notes, link, servings and
  ingredients and no meal types (the copy gets all four, as upstream's does) (`duplicateRecipe`).
- **RECIPES-R26** Delete hides the recipe at once and offers undo; the request goes when the
  snackbar closes; `404 recipes_recipe_not_found` counts as done; any other failure brings the
  recipe back with the error (`removeRecipe`, `scheduleUndoableDelete`, `isAlreadyDeleted`).
  Delete is also in the form for native recipes (`recipeModalFooterHtml`).
- **RECIPES-R27** The picture: PNG, JPEG or WebP up to 5 MB, cut to the centre square, stored as a
  256 px JPEG data URL (upstream's crop dialog output) of at most 768 KB; "Remove image" sends
  `image_data: null`. Not shown for mirrored recipes.
- **RECIPES-R28** Mirrored recipes never open the form; `403 recipe_mirrored` is shown with
  `recipes.errorMirrored`.
- **RECIPES-R29** Errors use upstream's sentences (`errorSentence`): the case the server names,
  else the general sentence of the place; "gone" reasons take exactly that thing out of the
  state (`dropGone`) and close the form or dialog it hung on.

### Permissions

- **RECIPES-R30** `meals` access decides writing (`readOnly`, `mayPlanRecipe`); read-only members
  see list, search, filter and detail, with no FAB, no row menu, no form, no plan and no pantry
  link action; the transfer follows its own two gates (R18).

## Acceptance criteria

| ID | Criterion | Test | Status |
|---|---|---|---|
| RECIPES-A1 | Given upstream's `test-meals.js` scaling cases (de, en-US, fr, fa, ar-EG, astral digits, grouping, cut-off separators, multiplier notation) then the port gives the same text. | unit | passing |
| RECIPES-A2 | Given servings 4 then stepping gives 5, clamps at 1 and 99; without servings it steps by 0.5 from 0.5 to 10, and the factor is the choice itself. | unit | passing |
| RECIPES-A3 | Given `meal_types` absent, `[]` or a comma list then normalising gives all four, none, or the known ones in order; the plan preselects dinner; chips show only when they tell something. | unit | passing |
| RECIPES-A4 | Given three recipes then search finds by ingredient and notes, the source filter narrows, a pending delete is not listed, and "planned this week" disappears when every recipe is planned. | unit | passing |
| RECIPES-A5 | Given a form then the body equals upstream's `saveRecipe` body; duplicate has no `meal_types`; the transfer with factor 2 carries `quantities` and `ingredientIds`. | unit | passing |
| RECIPES-A6 | Given the reasons above then the sentences are upstream's, and `isGone` excludes 403 and 409. | unit | passing |
| RECIPES-A7 | Given a native and a mirrored recipe then the list shows the source badge with account, the filter, and "planned this week" for the planned one; search narrows. | ui | passing |
| RECIPES-A8 | Given servings 4 and "200 g", "1 1/2 cans" when "More servings" is tapped then the detail shows "250 g" and "1.88 cans". | ui | passing |
| RECIPES-A9 | Given two lists when "Add to shopping list" picks the second then the body is `{"listId":2,"quantities":["250 g","1.88 cans"],"ingredientIds":[11,12]}` and the snackbar names the list. | ui | passing |
| RECIPES-A10 | Given a recipe for lunch and dinner when it is planned then `POST /meals` has `meal_type` dinner, `recipe_id` and the scaled quantity. | ui | passing |
| RECIPES-A11 | Given a linked ingredient when its link is changed then `PUT ingredient-match` sends name and the chosen row, and the detail shows the new row's name. | ui | passing |
| RECIPES-A12 | Given meals `read`, shopping `write`, pantry `read` then there is no FAB, no row menu, no plan, no edit; categories and all meals show; the link shows without action; the transfer stays. | ui | passing |
| RECIPES-A13 | Given a delete then the row goes at once, undo restores it without a request, and without undo `DELETE` is sent when the window closes. | ui | passing |
| RECIPES-A14 | The form posts the title, all four meals, `servings: null` and the typed ingredient with the default category. | ui | passing |
| RECIPES-C1…C8 | Against the pinned server: create and edit with every field (empty meals stay empty), 404 `recipes_recipe_not_found` after delete; duplicate; own picture as bytes, kept on a partial edit, removed with null; no provider thumbnail for a native recipe; link, survive an edit, 404 for an unknown ingredient and a gone pantry row, unlink; transfer, skip duplicates, scaled quantities, 409 for stale row ids, 404 for a gone list, undo; plan creates a linked meal and 400 `meals_selected_recipe_not_found` for a deleted recipe; the list and its neighbours decode. | contract | written, run in CI |

## Strings

Upstream keys from `recipes`, `meals`, `kitchen`, `shopping`, `common`, `modal` and `emptyHint`.
App-only strings in `app/src/main/assets/i18n-android/recipes/en.json`, each upstream's English
text of a namespace the app does not ship (`settings`): `android.recipes.readOnly`
(`settings.permReadOnlyBanner`), `android.recipes.imageType`, `android.recipes.imageFileTooLarge`,
`android.recipes.imageRead` (`settings.profilePicture*`).
