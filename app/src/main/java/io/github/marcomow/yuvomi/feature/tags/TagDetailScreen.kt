package io.github.marcomow.yuvomi.feature.tags

import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.ModuleBadge
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * docs/spec/tags.md, a tag's page (TAGS-R6 to R12): everything carrying [tag], by module; for
 * a real task tag, rename and delete as upstream's tag manager does.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagDetailScreen(tag: String, back: () -> Unit, retag: (String) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { TagStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(store) { store.refreshIfStale(shell) }

    val settings = shell.container.settings
    val showDone by settings.tagsShowDone.collectAsState(initial = false)
    val archivedTags by settings.archivedTags.collectAsState(initial = emptySet())
    val tagKey = remember(tag) { TagKey.of(tag.removePrefix("#")) }
    val isArchived = tagKey in archivedTags
    // TAGS-R14: completed and archived items only when shown, with a count of what is hidden.
    val groups = remember(s.items, tag, showDone) { TagIndex.groups(TagIndex.visible(s.items, showDone), tag) }
    val hiddenDone = remember(s.items, tag, showDone) { if (showDone) 0 else TagIndex.doneCount(s.items, tag) }
    val label = remember(s.items, tag) { TagIndex.label(s.items, tag) }
    val taskTag = TaskTagRules.find(s.taskTags, tag)
    val canManage = taskTag != null && shell.session.access("tasks") == "write" && !s.offline

    fun failed(e: Exception) {
        scope.launch { shell.notify(strings.t(TaskTagRules.errorKey(e))) }
        if ((e as? ApiProblem.Http)?.status == 404) store.refresh(shell, force = true)
    }

    fun rename(typed: String) {
        val from = taskTag?.tag ?: return
        when (val r = TaskTagRules.rename(from, typed)) {
            TaskTagRules.Rename.Unchanged -> renaming = false
            TaskTagRules.Rename.Invalid -> scope.launch { shell.notify(strings.t("android.tags.nameInvalid")) }
            is TaskTagRules.Rename.Send -> {
                busy = true
                scope.launch {
                    try {
                        val change = store.renameTaskTag(from, r.to)
                        renaming = false
                        store.refresh(shell, force = true)
                        // The page is replaced by the new tag's; the message outlives it.
                        shell.container.scope.launch { shell.notify(TaskTagRules.renamed(change, strings)) }
                        retag(change.tag ?: r.to)
                    } catch (e: Exception) {
                        failed(e)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    fun delete() {
        val name = taskTag?.tag ?: return
        busy = true
        scope.launch {
            try {
                val change = store.deleteTaskTag(name)
                deleting = false
                store.refresh(shell, force = true)
                shell.notify(TaskTagRules.deleted(change, strings))
            } catch (e: Exception) {
                deleting = false
                failed(e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("#$label") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to label)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // TAGS-R15: archive any tag (kept on this phone); it moves to "Archived tags".
                        DropdownMenuItem(
                            text = { Text(t(if (isArchived) "android.tags.restore" else "android.tags.archive")) },
                            leadingIcon = { Icon(if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    settings.setTagArchived(tagKey, !isArchived)
                                    shell.notify(strings.t(if (isArchived) "android.tags.restored" else "android.tags.archived", "tag" to label))
                                }
                            },
                        )
                    if (canManage) {
                            DropdownMenuItem(
                                text = { Text(t("tasks.tagRenameLabel", "tag" to taskTag!!.tag)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text(t("tasks.tagDeleteLabel", "tag" to taskTag!!.tag), color = Yuvomi.colors.danger) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                onClick = { menu = false; deleting = true },
                            )
                    }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && s.items.isEmpty() -> LoadingBox()
                s.errorKey != null && s.items.isEmpty() -> ErrorBox(strings.t(s.errorKey!!), onRetry = { store.refresh(shell) })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { store.refresh(shell, pulled = true, force = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        item(key = "show-done") {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                ShowDoneChip(showDone) { on -> scope.launch { settings.setTagsShowDone(on) } }
                                if (hiddenDone > 0) {
                                    Text(
                                        t("android.tags.doneHidden", "count" to hiddenDone),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Yuvomi.colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                                if (isArchived) {
                                    Text(t("android.tags.isArchived"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                        if (s.offline) {
                            item { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        if (s.failed.isNotEmpty()) {
                            item { NotLoadedNote(s.failed, onRetry = { store.refresh(shell, force = true) }) }
                        }
                        if (groups.isEmpty()) {
                            item { EmptyState(t("android.tags.noItems", "tag" to label)) }
                        }
                        groups.forEach { group ->
                            item(key = "h-${group.moduleId}") { GroupHeader(group) }
                            items(group.items, key = { "i-${group.moduleId}-${it.key}" }) { item ->
                                ItemRow(item, tag, open = {
                                    when {
                                        item.route != null -> shell.navigate(item.route)
                                        item.webPath != null -> shell.openWeb(item.webPath)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming && taskTag != null) {
        var name by remember(taskTag.tag) { mutableStateOf(taskTag.tag) }
        AlertDialog(
            onDismissRequest = { if (!busy) renaming = false },
            title = { Text(t("tasks.tagRenameLabel", "tag" to taskTag.tag)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(TagIndex.MAX_LEN) },
                        singleLine = true,
                        label = { Text(t("tasks.tagsLabel")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(t("tasks.manageTagsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    Text(t("android.tags.taskOnly"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            },
            confirmButton = { TextButton(onClick = { rename(name) }, enabled = !busy) { Text(t("common.save")) } },
            dismissButton = { TextButton(onClick = { renaming = false }, enabled = !busy) { Text(t("common.cancel")) } },
        )
    }

    if (deleting && taskTag != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = false },
            title = { Text(t("tasks.tagDeleteLabel", "tag" to taskTag.tag)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("tasks.tagDeleteConfirm", "tag" to taskTag.tag, "count" to taskTag.count))
                    Text(t("android.tags.taskOnly"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = { delete() }, enabled = !busy) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text(t("common.cancel")) } },
        )
    }
}

@Composable
private fun GroupHeader(group: TagGroup) {
    val module = ModuleCatalog.get(group.moduleId)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (module != null) {
            ModuleBadge(module.icon, Yuvomi.colors.module(module.tone))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            module?.let { t(it.labelKey) } ?: group.moduleId,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text("${group.items.size}", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(item: TaggedItem, tag: String, open: () -> Unit) {
    val others = remember(item, tag) { TagIndex.otherTags(item, tag) }
    ListItem(
        headlineContent = {
            Text(
                item.title,
                color = if (item.done) Yuvomi.colors.textSecondary else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
        },
        supportingContent = if (item.subtitle == null && others.isEmpty()) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.subtitle?.let { Text(it, color = Yuvomi.colors.textSecondary) }
                    if (others.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            others.forEach { TagChip(it) }
                        }
                    }
                }
            }
        },
        modifier = Modifier.clickable(enabled = item.route != null || item.webPath != null, onClick = open),
    )
}
