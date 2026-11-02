package io.github.marcomow.yuvomi.feature.recipes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** docs/spec/recipes.md, the form (RECIPES-R23 to R29). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(shared: RecipesViewModel, recipeId: Int?, close: () -> Unit, deleted: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store by shared.state.collectAsState()
    val access = RecipeAccess.of(shell.session)

    // The form needs the recipe as loaded; a deep link may arrive before the list.
    if (recipeId != null && store.recipe(recipeId) == null) {
        if (store.loading) LoadingBox() else LaunchedEffect(Unit) { close() }
        return
    }
    val existing = recipeId?.let(store::recipe)
    // Read-only members and mirrored recipes never reach the form (`openRecipeModal`).
    if (!access.canWrite || existing?.isMirrored == true) {
        LaunchedEffect(Unit) { close() }
        return
    }

    val vm = viewModel(key = "recipe-editor-$recipeId") { RecipeEditorViewModel(shared, existing) }
    val s by vm.state.collectAsState()
    val f = s.form
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fb = remember(shell, strings) { feedback(shell, strings) }

    LaunchedEffect(s.saved) { if (s.saved != null) close() }
    LaunchedEffect(s.gone) { if (s.gone) deleted() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { RecipeImageEncoder.encode(context, uri) }) {
                is RecipeImageEncoder.Result.Ok -> vm.edit { it.copy(image = ImageChange.Set(result.dataUrl)) }
                is RecipeImageEncoder.Result.Problem -> shared.tell(fb, strings.t(result.key))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (existing == null) "recipes.addRecipe" else "recipes.editRecipe")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = {
                            shared.delete(existing, fb)
                            deleted()
                        }) { Icon(Icons.Outlined.Delete, t("common.delete"), tint = Yuvomi.colors.danger) }
                    }
                    TextButton(onClick = { vm.save(fb) }, enabled = !s.saving) { Text(t(if (existing == null) "common.add" else "common.save")) }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = f.title,
                onValueChange = { v -> vm.edit { it.copy(title = v) } },
                label = { Text(t("common.nameLabel") + " *") },
                placeholder = { Text(t("recipes.titlePlaceholder")) },
                isError = s.titleErrorKey != null,
                supportingText = s.titleErrorKey?.let { key -> @Composable { Text(strings.t(key)) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(t("meals.mealTypeLabel"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealTypes.KEYS.forEach { type ->
                    FilterChip(type in f.mealTypes, onClick = { vm.toggleMealType(type) }, label = { Text(MealTypes.label(type, store.mealTypeNames, strings)) })
                }
            }

            OutlinedTextField(
                value = f.servings,
                onValueChange = { v -> vm.edit { it.copy(servings = v.filter { c -> c.isDigit() }.take(2)) } },
                label = { Text(t("recipes.servingsLabel")) },
                isError = s.servingsErrorKey != null,
                supportingText = { Text(strings.t(s.servingsErrorKey ?: "recipes.servingsHint")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(t("recipes.ingredientsLabel"), style = MaterialTheme.typography.labelLarge)
            val offered = ShoppingCategories.forIngredients(store.categories)
            f.rows.forEach { row -> IngredientRow(row, offered, vm) }
            OutlinedButton(onClick = vm::addRow) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("meals.addIngredient"))
            }

            // "More details": notes, picture, link (upstream's advancedSection).
            Row(
                Modifier.fillMaxWidth().clickable { vm.edit { it.copy(moreOpen = !it.moreOpen) } }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("modal.moreDetails"), style = MaterialTheme.typography.titleSmall)
                    Text(t("recipes.moreDetailsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Icon(if (f.moreOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (f.moreOpen) {
                OutlinedTextField(
                    value = f.notes,
                    onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                    label = { Text(t("recipes.notesLabel")) },
                    placeholder = { Text(t("recipes.notesPlaceholder")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ImageField(shared, existing, f.image, onPick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, onRemove = { vm.edit { it.copy(image = ImageChange.Remove) } })
                OutlinedTextField(
                    value = f.url,
                    onValueChange = { v -> vm.edit { it.copy(url = v) } },
                    label = { Text(t("recipes.urlLabel")) },
                    placeholder = { Text(t("recipes.urlPlaceholder")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** `ingredientRowHTML`: name, quantity, shopping category, remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientRow(row: RecipeEditorViewModel.Row, offered: List<String>, vm: RecipeEditorViewModel) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = row.name,
                onValueChange = { v -> vm.updateRow(row.key) { it.copy(name = v) } },
                placeholder = { Text(t("meals.ingredientNamePlaceholder")) },
                singleLine = true,
                modifier = Modifier.weight(1.6f),
            )
            OutlinedTextField(
                value = row.quantity,
                onValueChange = { v -> vm.updateRow(row.key) { it.copy(quantity = v) } },
                placeholder = { Text(t("meals.ingredientQtyPlaceholder")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            val name = row.name.trim()
            IconButton(onClick = { vm.removeRow(row.key) }) {
                Icon(Icons.Outlined.Close, if (name.isNotEmpty()) strings.t("common.removeNamed", "name" to name) else t("meals.removeIngredient"), tint = Yuvomi.colors.danger)
            }
        }
        var expanded by remember { mutableStateOf(false) }
        val options = offered.ifEmpty { listOf(ShoppingCategories.DEFAULT) }
        fun label(name: String) = if (offered.isEmpty()) strings.t("meals.ingredientCategoryDefault") else ShoppingCategories.label(name, strings)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = label(row.category),
                onValueChange = {},
                readOnly = true,
                label = { Text(t("meals.ingredientCategoryLabel")) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                singleLine = true,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { c ->
                    DropdownMenuItem(text = { Text(label(c)) }, onClick = { expanded = false; vm.updateRow(row.key) { it.copy(category = c) } })
                }
            }
        }
    }
}

/** RECIPES-R27: one picture per recipe, chosen, replaced or removed. */
@Composable
private fun ImageField(shared: RecipesViewModel, existing: Recipe?, change: ImageChange?, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("recipes.imageLabel"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val frame = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPick)
            when {
                change is ImageChange.Set -> {
                    val bitmap = remember(change.dataUrl) { RecipeImageEncoder.preview(change.dataUrl) }
                    if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = frame) else EmptyPicture(frame)
                }
                change == null && existing?.hasOwnImage == true -> Box(frame) { RecipeThumb(shared.images, RecipesApi.imagePath(existing.id), size = 72.dp) }
                else -> EmptyPicture(frame)
            }
            Column {
                OutlinedButton(onClick = onPick) { Text(t("recipes.imageChoose")) }
                TextButton(onClick = onRemove) { Text(t("recipes.imageRemove")) }
            }
        }
        Text(t("recipes.imageHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

@Composable
private fun EmptyPicture(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Yuvomi.colors.textSecondary)
    }
}
