package io.github.marcomow.yuvomi.feature.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.Instant

/** docs/spec/recipes.md, the list (RECIPES-R1 to R9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(vm: RecipesViewModel, open: (Int) -> Unit, edit: (Int?) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val access = RecipeAccess.of(shell.session)
    val readOnly = !access.canWrite
    val writable = access.canWrite && !s.offline
    val fb = remember(shell, strings) { feedback(shell, strings) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("nav.recipes")) },
                actions = {
                    if (s.sourceOptions.isNotEmpty()) SourceFilter(s.sourceOptions, s.source, vm::setSource)
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (writable) {
                FloatingActionButton(onClick = { edit(null) }) { Icon(Icons.Outlined.Add, contentDescription = t("recipes.addRecipe")) }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && s.recipes.isEmpty() -> LoadingBox()
                s.loadErrorKey != null -> ErrorBox(
                    strings.t(s.loadErrorKey!!) + "\n" + strings.t("common.loadErrorDescription"),
                    onRetry = { vm.refresh() },
                )
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        item { SearchField(s.query, vm::setQuery) }
                        val visible = s.visible
                        when {
                            s.listed.isEmpty() -> item {
                                // RECIPES-R7: read-only members get only the state, no invitation.
                                if (readOnly) {
                                    EmptyState(t("recipes.emptyTitle"))
                                } else {
                                    EmptyState(
                                        t("recipes.emptyTitle"),
                                        t("recipes.emptyDescription") + "\n" + t("emptyHint.recipes"),
                                        action = t("recipes.emptyAction").takeIf { writable },
                                        onAction = { edit(null) },
                                    )
                                }
                            }
                            visible.isEmpty() -> item {
                                EmptyState(
                                    t("common.searchNoResultsTitle"),
                                    t(if (s.source != "all") "common.searchNoResultsFilteredDescription" else "common.searchNoResultsDescription"),
                                    action = t("common.searchReset"),
                                    onAction = vm::resetSearch,
                                )
                            }
                            else -> items(visible, key = { it.id }) { recipe ->
                                RecipeRow(
                                    recipe = recipe,
                                    vm = vm,
                                    planned = RecipeList.showPlanned(recipe, s.recipes, s.pendingDelete, s.plannedIds),
                                    writable = writable,
                                    onOpen = {
                                        when {
                                            RecipeList.hasDetail(recipe, readOnly) || recipe.isMirrored -> open(recipe.id)
                                            writable -> edit(recipe.id)
                                            else -> open(recipe.id)
                                        }
                                    },
                                    onEdit = { edit(recipe.id) },
                                    onDuplicate = { vm.duplicate(recipe, fb) },
                                    onDelete = { vm.delete(recipe, fb) },
                                )
                                HorizontalDivider(color = Yuvomi.colors.border)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun feedback(shell: ShellContext, strings: io.github.marcomow.yuvomi.core.i18n.Strings) =
    RecipesViewModel.Feedback(strings) { message, action -> shell.notify(message, action) }

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(t("recipes.searchPlaceholder")) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** RECIPES-R3: All, Native and each provider, only when mirrored recipes exist. */
@Composable
private fun SourceFilter(options: List<String>, active: String, onPick: (String) -> Unit) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val label = t("recipes.sourceFilterLabel") + ": " + RecipeList.sourceLabel(active, strings)
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.FilterList, contentDescription = label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(RecipeList.sourceLabel(option, strings)) },
                    leadingIcon = { if (option == active) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.width(24.dp)) },
                    onClick = {
                        expanded = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** RECIPES-R4, R5: picture, name, source, ingredient count, "planned this week", row menu. */
@Composable
private fun RecipeRow(
    recipe: Recipe,
    vm: RecipesViewModel,
    planned: Boolean,
    writable: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (RecipeList.showsThumb(recipe)) {
            RecipeThumb(vm.images, RecipesApi.thumbnailPath(recipe))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(recipe.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recipe.isMirrored) SourceBadge(recipe)
                val meta = buildList {
                    add(strings.t("meals.ingredientCount", "count" to recipe.ingredients.size))
                    if (planned) add(strings.t("recipes.plannedThisWeek"))
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        }
        if (writable) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = t("common.moreActionsNamed", "name" to recipe.title))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // Mirrored recipes belong to their source: only a copy is possible.
                    if (!recipe.isMirrored) {
                        DropdownMenuItem(text = { Text(t("common.edit")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    }
                    DropdownMenuItem(text = { Text(t("recipes.duplicate")) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    if (!recipe.isMirrored) {
                        DropdownMenuItem(
                            text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/** `sourceBadge`: the provider's name; the account name tells several accounts apart. */
@Composable
internal fun SourceBadge(recipe: Recipe) {
    val strings = LocalStrings.current
    Surface(shape = RoundedCornerShape(6.dp), color = Yuvomi.colors.module("recipes").copy(alpha = 0.14f)) {
        Text(
            RecipeList.sourceLabel(recipe.source, strings) + (recipe.providerAccountName?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = Yuvomi.colors.module("recipes"),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
