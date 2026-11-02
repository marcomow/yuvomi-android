package io.github.marcomow.yuvomi.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.browser.Browser
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.recipes.RecipesRoutes
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.Instant
import java.time.LocalDate

/** Who may do what on the plan (`readOnly()`, `mayTransferMealToShopping()`, `recipesReachable()`). */
internal data class MealsAccess(val write: Boolean, val transfer: Boolean, val recipes: Boolean)

internal fun ShellContext.mealsAccess(offline: Boolean): MealsAccess {
    val write = session.access("meals") == "write" && !offline
    return MealsAccess(
        write = write,
        transfer = write && session.access("shopping") == "write",
        recipes = shows("recipes"),
    )
}

/** Opens a recipe of the household natively; the web app while the Recipes screen is not there. */
internal fun ShellContext.openRecipe(id: Int) {
    runCatching { navigate(RecipesRoutes.recipe(id)) }.onFailure { openWeb("/recipes?open=$id") }
}

/** docs/spec/meals.md: the week plan (MEALS-R1 to R8, R21 to R25). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealsScreen(vm: MealsViewModel, openEditor: (mealId: Int?, date: LocalDate, type: String?, recipeId: Int?) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val choice = remember { ChoiceState() }
    val actions = remember(shell, strings, vm) { MealActions(shell, strings, vm, choice) }
    val today = shell.time.today()
    val access = shell.mealsAccess(s.offline)
    var menu by remember { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<Int?>(null) }
    var randomizing by rememberSaveable { mutableStateOf(false) }
    var railHidden by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val isCurrentWeek = s.weekStart == MealPlan.startOfWeek(today, MealPlan.weekStartDay(s.prefs.weekStart))
    val days = MealPlan.weekDays(s.weekStart)
    val firstType = s.visible.firstOrNull() ?: "lunch"

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    // A delete the editor asked for runs here, with its undo (the editor is gone by then).
    LaunchedEffect(s.pendingDelete) {
        val pending = s.pendingDelete ?: return@LaunchedEffect
        vm.consumeDelete()
        actions.delete(pending.mealId, pending.scope)
    }

    // `revealToday()`: the stacked days scroll to today once the week has loaded.
    LaunchedEffect(s.loadedWeek) {
        val index = days.indexOf(today)
        if (s.loadedWeek != null && index > 0 && s.shownMeals.isNotEmpty()) {
            listState.scrollToItem(index + if (s.offline) 1 else 0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("nav.meals")) },
                actions = {
                    // `mealsToolsMenuHtml()`: nothing to offer at `read`.
                    if (access.write) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("meals.randomizePlan")) },
                                    onClick = { menu = false; randomizing = true },
                                )
                                if (access.transfer && s.shownMeals.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(t("android.meals.weekToShopping")) },
                                        onClick = { menu = false; actions.transferWeek() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(t(if (railHidden) "meals.showRecipes" else "meals.hideRecipes")) },
                                    onClick = { menu = false; railHidden = !railHidden },
                                )
                            }
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (access.write) {
                FloatingActionButton(onClick = { openEditor(null, today, firstType, null) }) {
                    Icon(Icons.Outlined.Add, contentDescription = t("meals.addMealTitle"))
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            WeekStepper(
                label = weekLabel(shell, s.weekStart, today),
                showToday = !isCurrentWeek,
                onPrevious = vm::previousWeek,
                onNext = vm::nextWeek,
                onToday = vm::thisWeek,
            )
            HorizontalDivider()
            when {
                s.loading && s.meals.isEmpty() && s.errorKey == null -> LoadingBox()
                s.errorKey != null -> ErrorBox(strings.t(s.errorKey!!) + "\n" + strings.t("common.loadErrorDescription"), onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (s.offline) {
                            item(key = "offline") {
                                OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
                            }
                        }
                        if (s.shownMeals.isEmpty() && !s.switching) {
                            item(key = "empty") { EmptyWeek(s, access) { openEditor(null, s.weekStart, firstType, null) } }
                        } else {
                            items(days, key = { "day-$it" }) { day ->
                                DayCard(
                                    day = day,
                                    today = today,
                                    s = s,
                                    access = access,
                                    strings = strings,
                                    onAdd = { type -> openEditor(null, day, type ?: firstType, null) },
                                    onOpen = { reading = it.id },
                                    onTransfer = { actions.transferMeal(it.id) },
                                )
                            }
                        }
                        if (access.write && !railHidden) {
                            item(key = "rail") {
                                RecipeRail(s, strings) { recipe ->
                                    val target = MealPlan.railTarget(recipe, s.weekStart, today, shell.time.now().hour, s.visible, s.meals)
                                    openEditor(null, target.date, target.mealType, recipe.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    reading?.let { id ->
        val meal = s.meal(id)
        if (meal == null) {
            reading = null
        } else {
            MealReadSheet(
                meal = meal,
                s = s,
                access = access,
                onDismiss = { reading = null },
                onEdit = { reading = null; openEditor(meal.id, LocalDate.parse(meal.date), meal.mealType, null) },
                onTransfer = { reading = null; actions.transferMeal(meal.id) },
            )
        }
    }

    if (randomizing) RandomizeDialog(vm, actions, onDismiss = { randomizing = false })
    ChoiceDialog(choice)
}

/** `formatWeekLabel()`: the first and the last day, the year only outside the current one. */
private fun weekLabel(shell: ShellContext, start: LocalDate, today: LocalDate): String =
    "${shell.formats.date(start, today)} – ${shell.formats.date(start.plusDays(6), today)}"

@Composable
private fun WeekStepper(label: String, showToday: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("meals.prevWeek")) }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, t("meals.nextWeek")) }
        // "Today" is a reset behind the stepper, there only while another week shows (`syncTodayButton()`).
        if (showToday) TextButton(onClick = onToday) { Text(t("meals.today")) } else Spacer(Modifier.width(8.dp))
    }
}

/** `emptyWeekOptions()`: at `read` only the state, no invitation. */
@Composable
private fun EmptyWeek(s: MealsViewModel.UiState, access: MealsAccess, onAdd: () -> Unit) {
    if (!access.write) {
        EmptyState(t("meals.emptyTitle"))
        return
    }
    val hint = if (s.recipes.isNotEmpty()) t("meals.emptyHintRecipes") else t("emptyHint.meals")
    EmptyState(t("meals.emptyTitle"), t("meals.emptyDescription") + "\n" + hint, t("meals.emptyAction"), onAdd)
}

@Composable
private fun DayCard(
    day: LocalDate,
    today: LocalDate,
    s: MealsViewModel.UiState,
    access: MealsAccess,
    strings: Strings,
    onAdd: (String?) -> Unit,
    onOpen: (Meal) -> Unit,
    onTransfer: (Meal) -> Unit,
) {
    val shell = LocalShell.current
    val isToday = day == today
    val dayMeals = s.shownMeals.filter { it.date == day.toString() }
    val longName = strings.t("calendar.dayLong" + day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() })
    Card {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t(MealPlan.dayNameKey(day)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shell.formats.date(day, today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else Yuvomi.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (isToday) {
                    Text(t("meals.today"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (access.write) {
                    IconButton(onClick = { onAdd(null) }) {
                        Icon(Icons.Outlined.Add, t("meals.addMealOnDay", "day" to longName))
                    }
                } else {
                    Spacer(Modifier.size(12.dp, 40.dp))
                }
            }
            s.visibleOrdered.forEach { type ->
                val label = MealPlan.typeLabel(type, s.names, strings)
                val slotMeals = dayMeals.filter { it.mealType == type }
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Yuvomi.colors.textSecondary,
                        modifier = Modifier.width(84.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (slotMeals.isEmpty()) {
                            if (access.write) {
                                // The plus only in empty slots (Critique 2026-10-16, D5).
                                TextButton(onClick = { onAdd(type) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        t("meals.addMeal", "type" to label),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.semantics {
                                            contentDescription = strings.t("meals.addMealTypeOnDay", "type" to label, "day" to longName)
                                        },
                                    )
                                }
                            } else {
                                Text("–", color = Yuvomi.colors.textTertiary, modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp))
                            }
                        }
                        slotMeals.forEach { meal -> MealCard(meal, s, access, onOpen = { onOpen(meal) }, onTransfer = { onTransfer(meal) }) }
                    }
                }
            }
        }
    }
}

/** `renderSlot()`'s card: title, series mark, cook and ingredient count; recipe and cart actions. */
@Composable
private fun MealCard(meal: Meal, s: MealsViewModel.UiState, access: MealsAccess, onOpen: () -> Unit, onTransfer: () -> Unit) {
    val shell = LocalShell.current
    val context = LocalContext.current
    val count = MealPlan.ingredientCount(meal)
    val cook = meal.cookUserId?.let { id -> CookView(id, meal.cookName.orEmpty(), meal.cookColor, s.members.firstOrNull { it.id == id }?.avatarData) }
    val external = externalHref(meal.recipeUrl)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Yuvomi.colors.module("meals").copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meal.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (meal.isRecurring) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Repeat, t("meals.recurrenceBadge"), Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
                    }
                }
                if (cook != null || count > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (cook != null) {
                            val named = t("meals.cookNamed", "name" to cook.name)
                            MemberAvatar(cook.name, cook.color, cook.picture, size = 18.dp,
                                modifier = Modifier.semantics { contentDescription = named })
                            Spacer(Modifier.width(6.dp))
                        }
                        if (count > 0) {
                            Text(
                                t("meals.ingredientCount", "count" to count) + if (MealPlan.allOnList(meal)) " ✓" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Yuvomi.colors.textSecondary,
                            )
                        }
                    }
                }
            }
            if (meal.recipeId != null && access.recipes) {
                IconButton(onClick = { shell.openRecipe(meal.recipeId) }) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, t("meals.viewRecipeNamed", "title" to meal.title), Modifier.size(20.dp))
                }
            } else if (external != null) {
                IconButton(onClick = { Browser.open(context, external) }) {
                    Icon(Icons.Outlined.Link, t("meals.openRecipeNamed", "title" to meal.title), Modifier.size(20.dp))
                }
            }
            if (access.transfer && MealPlan.hasOpenIngredients(meal)) {
                IconButton(onClick = onTransfer) {
                    Icon(Icons.Outlined.ShoppingCart, t("common.toShoppingListNamed", "title" to meal.title), Modifier.size(20.dp))
                }
            }
        }
    }
}

internal data class CookView(val id: Int, val name: String, val color: String?, val picture: String?)

/** `externalHref()`: only http and https links leave the app. */
internal fun externalHref(url: String?): String? = url?.trim()?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }

/** The rail of recipes below the plan (`renderRecipeSidebar()`): a tap plans the recipe. */
@Composable
private fun RecipeRail(s: MealsViewModel.UiState, strings: Strings, onPlan: (MealRecipe) -> Unit) {
    val shell = LocalShell.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(t("nav.recipes"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp))
        if (s.recipes.isEmpty()) {
            EmptyState(
                t("recipes.emptyTitle"), t("recipes.emptyDescription"),
                if (shell.shows("recipes")) t("recipes.emptyAction") else null,
            ) { runCatching { shell.navigate(RecipesRoutes.START) }.onFailure { shell.openWeb("/recipes") } }
            return
        }
        Text(t("recipes.railHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(s.recipes, key = { "r-${it.id}" }) { recipe ->
                val types = MealPlan.recipeMealTypes(recipe.mealTypes)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 0.5.dp,
                    modifier = Modifier
                        .width(168.dp)
                        .clickable { onPlan(recipe) }
                        .semantics { contentDescription = strings.t("meals.planRecipeNamed", "name" to recipe.title) },
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(recipe.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        if (recipe.ingredients.isNotEmpty()) {
                            Text(t("meals.ingredientCount", "count" to recipe.ingredients.size), style = MaterialTheme.typography.bodySmall,
                                color = Yuvomi.colors.textSecondary)
                        }
                        // Meal chips only for a true subset; none means "only by hand" (#750).
                        val label = when {
                            types.isEmpty() -> strings.t("recipes.mealTypeNone")
                            types.size < MealPlan.MEAL_TYPE_KEYS.size -> types.joinToString(" · ") { MealPlan.typeLabel(it, s.names, strings) }
                            else -> null
                        }
                        if (label != null) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.module("meals"), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        sourceLabel(recipe.source, strings)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

/** `recipes.source<Name>` for a mirrored recipe, null for the household's own. */
internal fun sourceLabel(source: String, strings: Strings): String? = when (source) {
    "native" -> null
    "mealie" -> strings.t("recipes.sourceMealie")
    "tandoor" -> strings.t("recipes.sourceTandoor")
    else -> source
}

/** `openMealReadModal()` / `mealReadHtml()`: the meal as values; Edit and the transfer at `write`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealReadSheet(
    meal: Meal,
    s: MealsViewModel.UiState,
    access: MealsAccess,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onTransfer: () -> Unit,
) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val date = runCatching { LocalDate.parse(meal.date) }.getOrNull()
    val ingredients = meal.ingredients.map { RecipeIngredient(it.name, it.quantity, it.category) }
        .ifEmpty { MealPlan.recipeIngredientsOf(meal, s.recipes) }
        .joinToString("\n") { ing ->
            val line = if (!ing.quantity.isNullOrEmpty()) "${ing.quantity} · ${ing.name}" else ing.name
            if (!ing.category.isNullOrEmpty()) "$line (${MealPlan.categoryLabel(ing.category, strings)})" else line
        }
    val recipe = s.recipe(meal.recipeId)
    val cook = meal.cookUserId?.let { id -> CookView(id, meal.cookName.orEmpty(), meal.cookColor, s.members.firstOrNull { it.id == id }?.avatarData) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(meal.title, style = MaterialTheme.typography.titleLarge)
            ReadRow(Icons.Outlined.CalendarMonth, t("meals.dateLabel"), date?.let { shell.formats.weekdayDate(it) })
            ReadRow(Icons.Outlined.Restaurant, t("meals.mealTypeLabel"), MealPlan.typeLabel(meal.mealType, s.names, strings))
            if (cook != null) {
                ReadRow(Icons.Outlined.Person, t("meals.cookLabel"), cook.name) {
                    MemberAvatar(cook.name, cook.color, cook.picture, size = 24.dp)
                }
            }
            ReadRow(Icons.AutoMirrored.Outlined.ViewList, t("meals.ingredientsLabel"), ingredients)
            ReadRow(Icons.AutoMirrored.Outlined.MenuBook, t("meals.savedRecipeLabel"), recipe?.title,
                onClick = if (recipe != null && access.recipes) ({ onDismiss(); shell.openRecipe(recipe.id) }) else null)
            ReadRow(Icons.AutoMirrored.Outlined.Notes, t("meals.notesLabel"), meal.notes)
            val url = meal.recipeUrl?.trim()
            ReadRow(Icons.Outlined.Link, t("meals.recipeUrlLabel"), url,
                onClick = externalHref(url)?.let { href -> { Browser.open(context, href) } })
            if (meal.isRecurring) {
                ReadRow(Icons.Outlined.Repeat, t("meals.recurrenceBadge"), t("meals.recurrenceWeekly"))
                val until = meal.recurrenceEndDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                ReadRow(Icons.Outlined.EventRepeat, t("meals.recurrenceUntilLabel"), until?.let { shell.formats.date(it, shell.time.today()) })
            }
            if (access.write) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (access.transfer && MealPlan.hasOpenIngredients(meal)) {
                        OutlinedButton(onClick = onTransfer) {
                            Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("common.toShoppingList"))
                        }
                    }
                    Button(onClick = onEdit) { Text(t("common.edit")) }
                }
            }
        }
    }
}

/** `readRowHtml()`: an empty value is no row. */
@Composable
private fun ReadRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = Yuvomi.colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** `openRandomizeModal()`: a preview of what the run fills and overwrites, computed on the loaded week. */
@Composable
private fun RandomizeDialog(vm: MealsViewModel, actions: MealActions, onDismiss: () -> Unit) {
    val s by vm.state.collectAsState()
    var replace by rememberSaveable { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val plan = remember(s.meals, s.recipes, s.visible, s.weekStart, replace) { vm.randomPlan(replace) }
    val fill = plan.assignments.size
    val overwrite = plan.deleteMealIds.size
    val preview = when {
        fill == 0 && plan.reason == "week_full" -> t("meals.randomizeWeekFull")
        fill == 0 -> t("meals.randomizeNoRecipes")
        overwrite > 0 -> t("meals.randomizePreview", "count" to fill) + " " + t("meals.randomizePreviewReplace", "count" to overwrite)
        else -> t("meals.randomizePreview", "count" to fill)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("meals.randomizeTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("meals.randomizeReplaceExisting"), modifier = Modifier.weight(1f))
                    Switch(checked = replace, onCheckedChange = { replace = it })
                }
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(enabled = fill > 0 && !running, onClick = {
                running = true
                actions.randomize(replace, onDone = onDismiss, onFailed = { running = false })
            }) { Text(t("meals.randomizePlan")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}
