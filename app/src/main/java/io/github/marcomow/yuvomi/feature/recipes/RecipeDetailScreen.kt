package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.feature.tags.HashtagText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.browser.Browser
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** docs/spec/recipes.md, the detail (RECIPES-R10 to R22). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(vm: RecipesViewModel, recipeId: Int, back: () -> Unit, edit: (Int) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    val recipe = s.recipe(recipeId)?.takeIf { it.id !in s.pendingDelete }
    val access = RecipeAccess.of(shell.session)
    val readOnly = !access.canWrite
    val offline = s.offline
    val writable = access.canWrite && !offline
    val locale = strings.locale
    val fb = remember(shell, strings) { feedback(shell, strings) }

    var planning by remember { mutableStateOf(false) }
    var choosingList by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf<String?>(null) }
    var bulkMatching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.title ?: t("nav.recipes"), maxLines = 1) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    if (recipe != null && writable) {
                        // Mirrored recipes are read-only; a copy can still be made (`recipeActions`).
                        if (!recipe.isMirrored) IconButton(onClick = { edit(recipe.id) }) { Icon(Icons.Outlined.Edit, t("common.edit")) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("recipes.duplicate")) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menu = false; vm.duplicate(recipe, fb) },
                                )
                                if (!recipe.isMirrored) {
                                    DropdownMenuItem(
                                        text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                        onClick = {
                                            menu = false
                                            vm.delete(recipe, fb)
                                            back()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                recipe == null && s.loading -> LoadingBox()
                recipe == null -> EmptyState(t("recipes.errorRecipeGone"))
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (offline) OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (recipe.hasOwnImage) {
                            RecipeHero(vm.images, RecipesApi.imagePath(recipe.id), Modifier.fillMaxWidth().aspectRatio(3f / 2f))
                        }
                        if (recipe.isMirrored) SourceBadge(recipe)
                        MealTypeChips(recipe, readOnly, s.mealTypeNames)
                        if (recipe.ingredients.isNotEmpty()) {
                            Ingredients(
                                recipe = recipe,
                                chosen = s.chosen(recipe),
                                factor = s.factor(recipe),
                                locale = locale,
                                access = access,
                                offline = offline,
                                readOnly = readOnly,
                                onStep = { vm.stepServings(recipe, it) },
                                onMatch = { matching = it },
                                onBulkMatch = { bulkMatching = true },
                            )
                        }
                        if (!recipe.notes.isNullOrEmpty()) {
                            Section(t("recipes.notesLabel")) { HashtagText(recipe.notes, style = MaterialTheme.typography.bodyMedium) }
                        }
                        // The two ways out of the recipe, labelled, after seeing what is in it.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (access.canPlan && !offline) {
                                OutlinedButton(onClick = { planning = true }) { Text(t("recipes.addToMeals")) }
                            }
                            if (s.lists.isNotEmpty() && recipe.ingredients.isNotEmpty() && access.canTransfer && !offline) {
                                OutlinedButton(onClick = {
                                    if (s.lists.size == 1) vm.transfer(recipe, s.lists[0], access, locale, fb) else choosingList = true
                                }, enabled = recipe.id !in s.transferring) { Text(t("common.toShoppingList")) }
                            }
                            externalHref(recipe.recipeUrl)?.let { href ->
                                TextButton(onClick = { Browser.open(context, href) }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t("recipes.openLink"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (recipe != null) {
        if (planning) PlanDialog(vm, recipe, s.mealTypeNames, onDone = { planning = false })
        if (choosingList) {
            ListChoiceDialog(s.lists, onDismiss = { choosingList = false }) { list ->
                choosingList = false
                vm.transfer(recipe, list, access, locale, fb)
            }
        }
        matching?.let { name -> MatchDialog(vm, recipe, name, onDone = { matching = null }) }
        if (bulkMatching) BulkMatchDialog(vm, recipe, onDone = { bulkMatching = false })
    }
}

@Composable
private fun Section(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** RECIPES-R11: the meal chips when they tell something, "No meal" when there is none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealTypeChips(recipe: Recipe, readOnly: Boolean, names: Map<String, String>) {
    val strings = LocalStrings.current
    val chips = MealTypes.detailChips(recipe, readOnly) ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val labels = if (chips.isEmpty()) listOf(t("recipes.mealTypeNone")) else chips.map { MealTypes.label(it, names, strings) }
        labels.forEach { label ->
            Surface(shape = RoundedCornerShape(50), color = Yuvomi.colors.module("meals").copy(alpha = 0.14f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** RECIPES-R12 to R16: the servings stepper, scaled quantities, pantry links. */
@Composable
private fun Ingredients(
    recipe: Recipe,
    chosen: Double,
    factor: Double,
    locale: String,
    access: RecipeAccess,
    offline: Boolean,
    readOnly: Boolean,
    onStep: (Int) -> Unit,
    onMatch: (String) -> Unit,
    onBulkMatch: () -> Unit,
) {
    val strings = LocalStrings.current
    val base = recipe.servings
    val range = ScaleQuantity.range(base)
    val canMatch = access.canMatch && !offline
    Section(t("recipes.ingredientsLabel"), trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t(if (base != null) "recipes.servingsLabel" else "recipes.servingsMultipleLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            IconButton(onClick = { onStep(-1) }, enabled = chosen > range.min) {
                Icon(Icons.Outlined.Remove, t(if (base != null) "recipes.servingsFewer" else "recipes.servingsLess"))
            }
            val number = ScaleQuantity.valueNumber(chosen, locale)
            Text(
                if (base != null) number else strings.t("recipes.servingsTimes", "value" to number),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 28.dp),
            )
            IconButton(onClick = { onStep(1) }, enabled = chosen < range.max) {
                Icon(Icons.Outlined.Add, t(if (base != null) "recipes.servingsMore" else "recipes.servingsMoreMultiple"))
            }
        }
    }) {
        recipe.ingredients.forEach { ing ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The quantity has its own right-aligned column, so names start on one edge.
                Text(
                    ScaleQuantity.scale(ing.quantity ?: "", factor, locale).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    color = Yuvomi.colors.textSecondary,
                    modifier = Modifier.width(88.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ing.name, style = MaterialTheme.typography.bodyMedium)
                    // Read-only members never see the form, so the category shows here.
                    if (readOnly && !ing.category.isNullOrEmpty()) {
                        Text(ShoppingCategories.label(ing.category, strings), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
                // Only an existing link shows on the row; "not linked" is no statement (#1314).
                if (access.pantryVisible && ing.pantryItemId != null) {
                    val text = ing.pantryItemName.orEmpty()
                    if (canMatch) {
                        TextButton(
                            onClick = { onMatch(ing.name) },
                            modifier = Modifier.semantics { contentDescription = strings.t("recipes.ingredientMatchAction", "name" to ing.name) },
                        ) { Text(text) }
                    } else {
                        Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
            }
        }
        val open = recipe.ingredients.count { it.pantryItemId == null }
        if (canMatch && open > 0) {
            TextButton(onClick = onBulkMatch) {
                Icon(Icons.Outlined.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.t("recipes.ingredientMatchOpen", "count" to open))
            }
        }
    }
}
