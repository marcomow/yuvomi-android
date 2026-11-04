package io.github.marcomow.yuvomi.feature.tags

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** docs/spec/tags.md, the list (TAGS-R3 to R5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(openTag: (String) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { TagStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(store) { store.refreshIfStale(shell) }
    // Back in the foreground, or back from a tag's page, after more than a minute.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refreshIfStale(shell) }

    val settings = shell.container.settings
    val showDone by settings.tagsShowDone.collectAsState(initial = false)
    val archived by settings.archivedTags.collectAsState(initial = emptySet())
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // TAGS-R14: completed and archived items count only when shown; TAGS-R15: archived tags apart.
    val visibleItems = remember(s.items, showDone) { TagIndex.visible(s.items, showDone) }
    val summaries = remember(visibleItems) { TagIndex.summarize(visibleItems) }
    val (active, archivedTags) = remember(summaries, archived) { TagIndex.split(summaries, archived) }
    val shown = remember(active, query) { TagIndex.filter(active, query) }
    val shownArchived = remember(archivedTags, query) { TagIndex.filter(archivedTags, query) }
    val find = remember(summaries, query) { TagIndex.offersFind(summaries, query) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(t("android.tags.title")) }, actions = { AccountButton() }) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(t("android.tags.search")) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, t("common.searchClear")) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ShowDoneChip(showDone) { on -> scope.launch { settings.setTagsShowDone(on) } }
            when {
                s.loading && s.items.isEmpty() -> LoadingBox()
                s.errorKey != null && s.items.isEmpty() -> ErrorBox(strings.t(s.errorKey!!), onRetry = { store.refresh(shell) })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { store.refresh(shell, pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        if (s.failed.isNotEmpty()) {
                            item { NotLoadedNote(s.failed, onRetry = { store.refresh(shell) }) }
                        }
                        if (find != null) {
                            item(key = "find") {
                                ListItem(
                                    headlineContent = { Text(t("android.tags.find", "tag" to find)) },
                                    leadingContent = { Icon(Icons.Outlined.Search, null) },
                                    modifier = Modifier.clickable { openTag(find) },
                                )
                                HorizontalDivider()
                            }
                        }
                        if (summaries.isEmpty()) {
                            item { EmptyState(t("android.tags.emptyTitle"), t("android.tags.emptyBody")) }
                        } else if (shown.isEmpty() && find == null) {
                            item { EmptyState(t("android.tags.noMatch")) }
                        }
                        items(shown, key = { "tag-${it.key}" }) { tag -> TagRow(tag, onClick = { openTag(tag.label) }) }
                        if (shownArchived.isNotEmpty()) {
                            item(key = "archived-header") {
                                ListItem(
                                    headlineContent = { Text(t("android.tags.archivedSection", "count" to shownArchived.size)) },
                                    leadingContent = { Icon(Icons.Outlined.Inventory2, null) },
                                    trailingContent = { Icon(if (archivedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                                    modifier = Modifier.clickable { archivedOpen = !archivedOpen },
                                )
                            }
                            if (archivedOpen || query.isNotBlank()) {
                                items(shownArchived, key = { "archived-${it.key}" }) { tag -> TagRow(tag, onClick = { openTag(tag.label) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** TAGS-R14: the switch for completed and archived items, shared by the list and a tag's page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShowDoneChip(showDone: Boolean, modifier: Modifier = Modifier.padding(horizontal = 16.dp), onChange: (Boolean) -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = showDone,
        onClick = { onChange(!showDone) },
        label = { Text(t("android.tags.showDone")) },
        leadingIcon = if (showDone) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
        modifier = modifier,
    )
}

@Composable
private fun TagRow(tag: TagSummary, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("#${tag.label}", fontWeight = FontWeight.Medium) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                tag.modules.forEach { id ->
                    val module = ModuleCatalog.get(id) ?: return@forEach
                    Icon(module.icon, t(module.labelKey), Modifier.size(16.dp), tint = Yuvomi.colors.module(module.tone))
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.Sell, null, tint = Yuvomi.colors.module("tasks")) },
        trailingContent = {
            Text(t("android.tags.itemCount", "count" to tag.count), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** TAGS-R3: which modules could not be read; their items are the ones from last time. */
@Composable
internal fun NotLoadedNote(failed: List<String>, onRetry: () -> Unit) {
    val names = failed.mapNotNull { ModuleCatalog.get(it) }.map { t(it.labelKey) }.joinToString(", ")
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text(
                t("android.tags.notLoaded", "modules" to names),
                style = MaterialTheme.typography.bodySmall,
                color = Yuvomi.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(t("common.retry")) }
        }
    }
}
