package io.github.marcomow.yuvomi.feature.meals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** docs/spec/meals.md, the meal form (MEALS-R9 to R20). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MealEditorScreen(week: MealsViewModel, vm: MealEditorViewModel, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val weekState by week.state.collectAsState()
    val scope = rememberCoroutineScope()
    val choice = remember { ChoiceState() }
    val actions = remember(shell, strings, week) { MealActions(shell, strings, week, choice) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val f = s.form

    // Writing needs `meals: write` (`openMealModal()` turns into the read view at `read`).
    LaunchedEffect(Unit) {
        if (shell.session.access("meals") != "write") close()
    }
    LaunchedEffect(s.gone) {
        if (s.gone) {
            shell.notify(strings.t("android.meals.gone"))
            close()
        }
    }
    LaunchedEffect(s.done) {
        val done = s.done ?: return@LaunchedEffect
        close()
        shell.notify(strings.t(if (done == MealEditorViewModel.Done.CREATED) "meals.mealSaved" else "meals.mealUpdated"))
    }
    LaunchedEffect(s.message) {
        val key = s.message ?: return@LaunchedEffect
        vm.consumeMessage()
        shell.notify(strings.t(key))
    }

    val leave = { if (s.dirty) confirmDiscard = true else close() }
    BackHandler(enabled = s.dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.Outlined.Close, t("common.close")) } },
                title = { Text(t(if (s.isEdit) "meals.editMeal" else "meals.addMealTitle")) },
                actions = {
                    TextButton(enabled = s.ready && !s.saving, onClick = vm::save) {
                        Text(t(if (s.isEdit) "common.save" else "common.add"))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (!s.ready) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val meal = s.meal
            // The series scope stands on top: it decides what Save does (R17 step 5).
            if (meal != null && meal.isRecurring) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(t("meals.recurrenceEditHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Label(t("meals.editScopeLabel"))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("single" to "meals.editScopeSingle", "series" to "meals.editScopeSeries").forEachIndexed { i, (value, key) ->
                        SegmentedButton(
                            selected = f.scope == value,
                            onClick = { vm.setScope(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(t(key)) }
                    }
                }
                if (f.scope == "series") UntilField(f.repeatUntil, s.errors[MealEditorViewModel.Field.UNTIL]) { d -> vm.edit { it.copy(repeatUntil = d) } }
            }

            // The name first, always: the required field, carrying the recipe suggestions.
            Column {
                OutlinedTextField(
                    value = f.title,
                    onValueChange = vm::setTitle,
                    label = { Text(t("common.nameLabel") + " *") },
                    placeholder = { Text(t("meals.titlePlaceholder")) },
                    isError = s.errors.containsKey(MealEditorViewModel.Field.TITLE),
                    supportingText = s.errors[MealEditorViewModel.Field.TITLE]?.let { key -> { Text(t(key)) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (s.suggestions.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            s.suggestions.forEach { sug ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.pickSuggestion(sug) }.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(sug.title, modifier = Modifier.weight(1f))
                                    if (sug.recipeId != null) {
                                        Text(t("meals.suggestionRecipe"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.module("meals"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Meal type as single-choice chips (Critique 2026-10-16, D4).
            Column {
                Label(t("meals.mealTypeLabel"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealPlan.MEAL_TYPE_KEYS.forEach { type ->
                        FilterChip(
                            selected = f.mealType == type,
                            onClick = { vm.edit { it.copy(mealType = type) } },
                            label = { Text(MealPlan.typeLabel(type, weekState.names, strings)) },
                        )
                    }
                }
            }

            // In series scope the date belongs to this meal only and is not sent.
            if (!(meal != null && meal.isRecurring && f.scope == "series")) {
                DateField(t("meals.dateLabel"), f.date, s.errors[MealEditorViewModel.Field.DATE]) { d -> vm.edit { it.copy(date = d) } }
            }

            CookPicker(vm, f)

            Ingredients(vm, s, weekState)

            Advanced(vm, s, weekState)

            if (meal != null && vm.hasOpenOwnIngredients() && actions.mayTransfer) {
                TransferSection(weekState.lists, onCreateList = { close(); shell.navigate(ShoppingRoutes.START) }) { list ->
                    actions.transferMealTo(meal.id, list)
                    close()
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meal != null) {
                    OutlinedButton(
                        onClick = {
                            // The scope question stands over the editor; it closes only when something is deleted.
                            scope.launch {
                                val answer = if (meal.isRecurring) actions.askDeleteScope() ?: return@launch else null
                                week.requestDelete(meal.id, answer)
                                close()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Yuvomi.colors.danger),
                    ) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("common.delete"))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = leave) { Text(t("common.cancel")) }
                Button(enabled = !s.saving, onClick = vm::save) { Text(t(if (s.isEdit) "common.save" else "common.add")) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(t("modal.unsavedChanges")) },
            text = { Text(t("modal.unsavedChangesDetail")) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; close() }) { Text(t("modal.discardChanges")) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(t("common.cancel")) } },
        )
    }
    ChoiceDialog(choice)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
}

/** A date as a field that opens the Material date picker; dates are household dates, kept in UTC millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate?, errorKey: String?, clearable: Boolean = false, onChange: (LocalDate?) -> Unit) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    Column {
        Label(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }) {
                Text(value?.let { shell.formats.weekdayDate(it) } ?: "–")
            }
            if (clearable && value != null) {
                IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("common.close")) }
            }
        }
        if (errorKey != null) Text(t(errorKey), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (value ?: shell.time.today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun UntilField(value: LocalDate?, errorKey: String?, onChange: (LocalDate?) -> Unit) {
    Column {
        DateField(t("meals.recurrenceUntilLabel"), value, errorKey, clearable = true, onChange = onChange)
        Text(t("meals.recurrenceUntilHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** `cookPickerHtml()`: one cook or no one; hidden in a household of one (utils/household.js). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CookPicker(vm: MealEditorViewModel, f: MealEditorViewModel.Form) {
    val shell = LocalShell.current
    val people = vm.people()
    if (shell.session.soloHousehold && people.size <= 1) return
    Column {
        Label(t("meals.cookLabel"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = f.cookId == null, onClick = { vm.pickCook(null) }, label = { Text(t("userMultiSelect.nobody")) })
            people.forEach { p ->
                FilterChip(
                    selected = f.cookId == p.id,
                    onClick = { vm.pickCook(p.id) },
                    label = { Text(p.displayName) },
                    leadingIcon = { MemberAvatar(p.displayName, p.avatarColor, p.avatarData, size = 20.dp) },
                )
            }
        }
    }
}

/** The ingredients: a read list with "Adjust" for a stored meal, editable rows otherwise. */
@Composable
private fun Ingredients(vm: MealEditorViewModel, s: MealEditorViewModel.UiState, weekState: MealsViewModel.UiState) {
    val strings = LocalStrings.current
    val f = s.form
    val categories = MealPlan.mealCategories(weekState.categories)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!f.rowsOpen) {
            val read = vm.readIngredients()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t(if (vm.readListIsRecipe()) "meals.recipeIngredientsLabel" else "meals.ingredientsLabel"),
                    style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::openRows) { Text(t("meals.recipeIngredientsEdit")) }
            }
            read.forEach { ing ->
                Row {
                    if (!ing.quantity.isNullOrEmpty()) {
                        Text(ing.quantity, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(ing.name)
                }
            }
            return@Column
        }
        Label(t("meals.ingredientsLabel"))
        f.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = row.name,
                            onValueChange = { v -> vm.editRow(row.key) { it.copy(name = v) } },
                            placeholder = { Text(t("meals.ingredientNamePlaceholder")) },
                            singleLine = true,
                            modifier = Modifier.weight(1.6f),
                        )
                        OutlinedTextField(
                            value = row.quantity,
                            onValueChange = { v -> vm.editRow(row.key) { it.copy(quantity = v) } },
                            placeholder = { Text(t("meals.ingredientQtyPlaceholder")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CategoryPicker(row.category, categories, strings) { c -> vm.editRow(row.key) { it.copy(category = c) } }
                }
                val name = row.name.trim()
                IconButton(onClick = { vm.removeRow(row.key) }) {
                    Icon(
                        Icons.Outlined.Close,
                        if (name.isNotEmpty()) t("common.removeNamed", "name" to name) else t("meals.removeIngredient"),
                        tint = Yuvomi.colors.danger,
                    )
                }
            }
        }
        OutlinedButton(onClick = vm::addRow) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(t("meals.addIngredient"))
        }
    }
}

@Composable
private fun CategoryPicker(value: String, categories: List<ShoppingCategoryRef>, strings: io.github.marcomow.yuvomi.core.i18n.Strings, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = categories.map { it.name }.ifEmpty { listOf(MealPlan.DEFAULT_CATEGORY) }
    Column {
        TextButton(onClick = { open = true }) {
            Text(t("meals.ingredientCategoryLabel") + ": " + if (categories.isEmpty()) t("meals.ingredientCategoryDefault") else MealPlan.categoryLabel(value, strings))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { name ->
                DropdownMenuItem(
                    text = { Text(if (categories.isEmpty()) t("meals.ingredientCategoryDefault") else MealPlan.categoryLabel(name, strings)) },
                    onClick = { open = false; onPick(name) },
                )
            }
        }
    }
}

/** `advancedSection()`: recipe, servings, save as recipe, notes, link and the weekly repeat. */
@Composable
private fun Advanced(vm: MealEditorViewModel, s: MealEditorViewModel.UiState, weekState: MealsViewModel.UiState) {
    val strings = LocalStrings.current
    val f = s.form
    val meal = s.meal
    val startOpen = (meal != null && (meal.recipeId != null || !meal.notes.isNullOrEmpty() || !meal.recipeUrl.isNullOrEmpty())) ||
        f.appliedRecipeId != null
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(t("modal.moreDetails"), style = MaterialTheme.typography.titleSmall)
                Text(t("meals.moreDetailsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (!open) return@Column

        RecipePicker(f.recipeId, weekState.recipes, strings, vm::chooseRecipe)

        weekState.recipe(f.appliedRecipeId)?.let { recipe ->
            val base = recipe.servings?.takeIf { it > 0 }
            val range = MealPlan.servingsRange(base)
            val value = f.servings ?: base ?: 1.0
            val number = MealPlan.format(value)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(if (base != null) "recipes.servingsLabel" else "recipes.servingsMultipleLabel"), modifier = Modifier.weight(1f))
                IconButton(enabled = value > range.min, onClick = { vm.stepServings(-1) }) {
                    Icon(Icons.Outlined.Remove, t(if (base != null) "recipes.servingsFewer" else "recipes.servingsLess"))
                }
                Text(if (base != null) number else t("recipes.servingsTimes", "value" to number), fontWeight = FontWeight.SemiBold)
                IconButton(enabled = value < range.max, onClick = { vm.stepServings(1) }) {
                    Icon(Icons.Outlined.Add, t(if (base != null) "recipes.servingsMore" else "recipes.servingsMoreMultiple"))
                }
            }
        }

        OutlinedButton(enabled = !s.savingRecipe, onClick = vm::saveAsRecipe) { Text(t("meals.saveAsRecipe")) }

        OutlinedTextField(
            value = f.notes,
            onValueChange = { v -> vm.edit { it.copy(notes = v) } },
            label = { Text(t("meals.notesLabel")) },
            placeholder = { Text(t("meals.notesPlaceholder")) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = f.recipeUrl,
            onValueChange = { v -> vm.edit { it.copy(recipeUrl = v) } },
            label = { Text(t("meals.recipeUrlLabel")) },
            placeholder = { Text(t("meals.recipeUrlPlaceholder")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (meal == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("meals.recurrenceLabel"))
                    Text(t("meals.recurrenceHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Switch(checked = f.repeatWeekly, onCheckedChange = { on -> vm.edit { it.copy(repeatWeekly = on) } })
            }
            if (f.repeatWeekly) UntilField(f.repeatUntil, s.errors[MealEditorViewModel.Field.UNTIL]) { d -> vm.edit { it.copy(repeatUntil = d) } }
        }
    }
}

@Composable
private fun RecipePicker(value: Int?, recipes: List<MealRecipe>, strings: io.github.marcomow.yuvomi.core.i18n.Strings, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = recipes.firstOrNull { it.id == value }
    Column {
        Label(t("meals.savedRecipeLabel"))
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(chosen?.title ?: t("meals.savedRecipePlaceholder"), modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("meals.savedRecipePlaceholder")) }, onClick = { open = false; onPick(null) })
            // Groups only once mirrored recipes exist (`hasMirroredRecipes`).
            val bySource = recipes.groupBy { it.source }
            val sources = listOf("native") + bySource.keys.filter { it != "native" }.sorted()
            val grouped = bySource.keys.any { it != "native" }
            sources.forEach { source ->
                val group = bySource[source].orEmpty()
                if (group.isEmpty()) return@forEach
                if (grouped) {
                    Text(
                        sourceLabel(source, strings) ?: strings.t("recipes.sourceNative"),
                        style = MaterialTheme.typography.labelMedium,
                        color = Yuvomi.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                group.forEach { r -> DropdownMenuItem(text = { Text(r.title) }, onClick = { open = false; onPick(r.id) }) }
            }
        }
    }
}

/** "Add ingredients to the shopping list" in the form; without a list, the shared hint and its way out. */
@Composable
private fun TransferSection(lists: List<ShoppingListRef>, onCreateList: () -> Unit, onTransfer: (ShoppingListRef) -> Unit) {
    val shell = LocalShell.current
    var chosen by remember(lists) { mutableStateOf(lists.firstOrNull()) }
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(6.dp))
            Text(t("meals.transferLabel"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
        }
        if (lists.isEmpty()) {
            Text(t("kitchen.noShoppingLists"), style = MaterialTheme.typography.bodyMedium)
            if (shell.shows("shopping") && shell.session.access("shopping") == "write") {
                OutlinedButton(onClick = onCreateList) { Text(t("kitchen.createShoppingList")) }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(chosen?.name.orEmpty(), modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    lists.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { open = false; chosen = l }) }
                }
            }
            Button(enabled = chosen != null, onClick = { chosen?.let(onTransfer) }) { Text(t("meals.transferNow")) }
        }
    }
}
