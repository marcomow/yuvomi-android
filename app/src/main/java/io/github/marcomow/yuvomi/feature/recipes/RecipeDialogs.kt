package io.github.marcomow.yuvomi.feature.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * RECIPES-R17, R18: "For when?" (`planRecipe`): a date, today by default, and one of the
 * recipe's meals; the meal is created right here, with the chosen servings' quantities.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanDialog(vm: RecipesViewModel, recipe: Recipe, names: Map<String, String>, onDone: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val fb = remember(shell, strings) { feedback(shell, strings) }
    val scope = rememberCoroutineScope()
    val types = remember(recipe) { MealTypes.planChoices(recipe) }
    var date by remember { mutableStateOf(shell.time.today()) }
    var type by remember { mutableStateOf(MealTypes.planPreselect(types)) }
    var pickDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // One key per dialog: a retry after a network failure never plans twice (ADR 0007).
    val key = remember { YuvomiClient.newIdempotencyKey() }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(t("recipes.planTitle", "name" to recipe.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("meals.dateLabel"), modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickDate = true }) { Text(shell.formats.date(date, shell.time.today())) }
                }
                Text(t("meals.mealTypeLabel"), style = MaterialTheme.typography.labelLarge)
                types.forEach { key ->
                    Choice(MealTypes.label(key, names, strings), selected = key == type) { type = key }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        vm.plan(recipe, date, type, key, strings.locale)
                        vm.tell(fb, strings.t("recipes.planSuccess", "name" to recipe.title, "date" to shell.formats.weekdayDate(date)))
                        onDone()
                    } catch (e: ApiProblem) {
                        busy = false
                        vm.tell(fb, strings.t(RecipeErrors.key(e)))
                        if (RecipeErrors.isGone(e)) onDone()
                    }
                }
            }) { Text(t("common.apply")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(t("common.cancel")) } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }) { Text(t("common.apply")) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

/** `resolveShoppingTarget` with several lists: ask once which one. */
@Composable
internal fun ListChoiceDialog(lists: List<ShoppingListRef>, onDismiss: () -> Unit, onPick: (ShoppingListRef) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("common.toShoppingListWhich")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lists.forEach { list ->
                    Text(
                        list.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(list) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** `pantryOptionLabel`: name, place and best-before tell batches apart. */
internal fun pantryLabel(item: PantryItem, format: (LocalDate) -> String): String =
    listOfNotNull(
        item.name,
        item.locationName?.takeIf { it.isNotEmpty() },
        item.expiresOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }?.let(format),
    ).joinToString(" · ")

/** Loads the pantry for a link dialog; null while loading, and closes the dialog when it fails. */
@Composable
private fun rememberPantry(vm: RecipesViewModel, onFail: () -> Unit): Pair<List<PantryItem>?, suspend () -> List<PantryItem>?> {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    var items by remember { mutableStateOf<List<PantryItem>?>(null) }
    val reload: suspend () -> List<PantryItem>? = { runCatching { vm.pantryItems() }.getOrNull()?.also { items = it } }
    LaunchedEffect(Unit) {
        try {
            items = vm.pantryItems()
        } catch (e: ApiProblem) {
            vm.tell(feedback(shell, strings), strings.t(RecipeErrors.key(e, "recipes.ingredientMatchLoadError")))
            onFail()
        }
    }
    return items to reload
}

/**
 * RECIPES-R15: link one ingredient to a pantry row, or remove the link (`openPantryMatchModal`).
 * Yuvomi suggests nothing; the member picks.
 */
@Composable
internal fun MatchDialog(vm: RecipesViewModel, recipe: Recipe, ingredientName: String, onDone: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val fb = remember(shell, strings) { feedback(shell, strings) }
    val scope = rememberCoroutineScope()
    val ing = recipe.ingredients.firstOrNull { it.name == ingredientName }
    if (ing == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val (items, reload) = rememberPantry(vm, onDone)
    var chosen by remember { mutableStateOf(ing.pantryItemId) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(t("recipes.ingredientMatchTitle", "name" to ingredientName)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("recipes.ingredientMatchHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                when {
                    items == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    items.isEmpty() -> Text(t("recipes.ingredientMatchEmpty"))
                    else -> {
                        Text(t("recipes.ingredientMatchLabel"), style = MaterialTheme.typography.labelLarge)
                        Choice(t("recipes.ingredientMatchNone"), chosen == null) { chosen = null }
                        items.forEach { item ->
                            Choice(pantryLabel(item) { shell.formats.date(it, shell.time.today()) }, chosen == item.id) { chosen = item.id }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!items.isNullOrEmpty()) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    val pick = chosen
                    scope.launch {
                        try {
                            vm.match(recipe, ingredientName, pick)
                            vm.tell(fb, strings.t(if (pick == null) "recipes.ingredientMatchCleared" else "recipes.ingredientMatchSaved"))
                            onDone()
                        } catch (e: ApiProblem) {
                            busy = false
                            vm.tell(fb, strings.t(RecipeErrors.key(e)))
                            if (RecipeErrors.reason(e) == "recipes_selected_pantry_item_not_found") {
                                // The chosen row is gone: offer the pantry as it is now.
                                val fresh = reload()
                                if (fresh != null && fresh.none { it.id == chosen }) chosen = null
                            } else if (RecipeErrors.isGone(e)) {
                                onDone()
                            }
                        }
                    }
                }) { Text(t("common.save")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(t(if (items?.isEmpty() == true) "common.close" else "common.cancel")) }
        },
    )
}

/**
 * RECIPES-R16: every unlinked ingredient in one dialog (`openPantryBulkMatchModal`); only
 * the chosen ones are saved, one by one through the same call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkMatchDialog(vm: RecipesViewModel, recipe: Recipe, onDone: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val fb = remember(shell, strings) { feedback(shell, strings) }
    val scope = rememberCoroutineScope()
    val open = remember(recipe.id) { recipe.ingredients.filter { it.pantryItemId == null } }
    val (items, reload) = rememberPantry(vm, onDone)
    val chosen = remember { mutableStateMapOf<Int, Int>() }
    var busy by remember { mutableStateOf(false) }
    val format = { d: LocalDate -> shell.formats.date(d, shell.time.today()) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(t("recipes.ingredientMatchBulkTitle")) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("recipes.ingredientMatchBulkHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                when {
                    items == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    items.isEmpty() -> Text(t("recipes.ingredientMatchEmpty"))
                    else -> open.forEachIndexed { index, ing ->
                        var expanded by remember { mutableStateOf(false) }
                        val selected = chosen[index]?.let { id -> items.firstOrNull { it.id == id } }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = selected?.let { pantryLabel(it, format) } ?: t("recipes.ingredientMatchNone"),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(if (!ing.quantity.isNullOrEmpty()) "${ing.quantity} · ${ing.name}" else ing.name) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text(t("recipes.ingredientMatchNone")) }, onClick = { expanded = false; chosen.remove(index) })
                                items.forEach { item ->
                                    DropdownMenuItem(text = { Text(pantryLabel(item, format)) }, onClick = { expanded = false; chosen[index] = item.id })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!items.isNullOrEmpty()) {
                TextButton(enabled = !busy, onClick = {
                    val picks = chosen.toSortedMap().map { (index, id) -> open[index] to id }
                    if (picks.isEmpty()) {
                        onDone()
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            for ((ing, id) in picks) {
                                vm.match(recipe, ing.name, id)
                                chosen.remove(open.indexOf(ing))
                            }
                            vm.tell(fb, strings.t("recipes.ingredientMatchSaved"))
                            onDone()
                        } catch (e: ApiProblem) {
                            // What was saved before the failure stays saved; the dialog stays for the rest.
                            busy = false
                            vm.tell(fb, strings.t(RecipeErrors.key(e)))
                            if (RecipeErrors.reason(e) == "recipes_selected_pantry_item_not_found") {
                                val fresh = reload()
                                if (fresh != null) chosen.entries.removeAll { (_, id) -> fresh.none { it.id == id } }
                            } else if (RecipeErrors.isGone(e)) {
                                onDone()
                            }
                        }
                    }
                }) { Text(t("common.save")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(t(if (items?.isEmpty() == true) "common.close" else "common.cancel")) }
        },
    )
}
