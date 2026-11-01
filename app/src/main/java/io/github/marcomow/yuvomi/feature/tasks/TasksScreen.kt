package io.github.marcomow.yuvomi.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant

/** docs/spec/tasks.md, the list (TASKS-R1 to R11, R20 to R23). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(presetAssignee: Int?, openTask: (Int) -> Unit, newTask: (category: String?, assignee: Int?) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm = viewModel(key = "tasks-$presetAssignee") { TasksViewModel(shell.container, presetAssignee) }
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val completion = remember(shell.connection) { TaskCompletion(shell.connection.tasks) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val doer = rememberDoerState()
    val today = shell.time.today()
    val canWrite = shell.session.access("tasks") == "write"
    val offline = s.cachedAt != null

    // TASKS-R9: back in the foreground after more than a minute.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    fun tick(task: Task, doneBy: Pair<Int, String>? = null) {
        if (offline) {
            scope.launch { shell.notify(strings.t("android.error.offline")) }
            return
        }
        val done = !task.isDone
        vm.markLocally(task.id, done)
        scope.launch {
            try {
                val receipt = completion.setDone(task, done, doneBy, strings, shell.formats, today)
                vm.refresh()
                if (receipt != null && shell.notify(receipt.message, strings.t("common.undo"))) {
                    completion.undo(task)
                    vm.refresh()
                }
            } catch (e: Exception) {
                vm.refresh()
                shell.notify(strings.t(TaskCompletion.errorKey(e)))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("tasks.title")) },
                actions = {
                    IconButton(onClick = {
                        vm.setMode(if (s.mode == TaskGrouping.Mode.CATEGORY) TaskGrouping.Mode.DUE else TaskGrouping.Mode.CATEGORY)
                    }) {
                        Icon(
                            if (s.mode == TaskGrouping.Mode.CATEGORY) Icons.Outlined.Category else Icons.Outlined.CalendarToday,
                            contentDescription = t("tasks.groupToggleLabel"),
                        )
                    }
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (s.filter.activeCount > 0) Badge { Text("${s.filter.activeCount}") } }) {
                            Icon(Icons.Outlined.FilterList, contentDescription = t("common.filtersOpen"))
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (canWrite && !offline) {
                FloatingActionButton(onClick = { newTask(null, s.filter.assignees.singleOrNull()) }) {
                    Icon(Icons.Outlined.Add, contentDescription = t("tasks.newTask"))
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && s.tasks.isEmpty() -> LoadingBox()
                s.errorKey != null -> ErrorBox(strings.t(s.errorKey!!), onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    val groups = TaskGrouping.group(s.tasks, s.mode, s.meta.categories, today) { categoryLabel(it, s.meta.categories, strings) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (offline) {
                            item { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        if (s.tasks.isEmpty()) {
                            item {
                                if (s.filter == TasksApi.Filter()) {
                                    EmptyState(t("tasks.emptyTitle"), t("tasks.emptyDescription"))
                                } else {
                                    EmptyState(t("android.tasks.noMatch"), action = t("common.filtersResetDefault"), onAction = vm::resetFilter)
                                }
                            }
                        }
                        groups.forEach { group ->
                            val collapsed = vm.isCollapsed(group.id)
                            stickyHeader(key = "h-${s.mode}-${group.id}") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .clickable { vm.toggleCollapsed(group.id) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        group.label ?: t(group.labelKey!!),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (group.id == TaskGrouping.DueBucket.OVERDUE.name) Yuvomi.colors.danger else MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${group.tasks.size}", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                                    Icon(if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null)
                                }
                            }
                            if (!collapsed) {
                                items(group.tasks, key = { "t-${s.mode}-${group.id}-${it.id}" }) { task ->
                                    TaskRow(
                                        task = task,
                                        readOnly = !canWrite || offline,
                                        today = today,
                                        onOpen = { openTask(task.id) },
                                        onTick = { tick(task) },
                                        onPickDoer = if (!shell.session.soloHousehold && !task.isDone) ({ doer.task = task }) else null,
                                        showCategory = if (s.mode == TaskGrouping.Mode.DUE) categoryLabel(task.category, s.meta.categories, strings) else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    doer.task?.let { task ->
        DoerPicker(task.title, s.meta.users, onDismiss = { doer.task = null }) { member ->
            doer.task = null
            tick(task, member.id to member.displayName)
        }
    }

    if (showFilters) {
        FilterSheet(s, onDismiss = { showFilters = false }, apply = vm::setFilter, reset = vm::resetFilter)
    }
}

/** TASKS-R8. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(s: TasksViewModel.UiState, onDismiss: () -> Unit, apply: (TasksApi.Filter) -> Unit, reset: () -> Unit) {
    val strings = LocalStrings.current
    val f = s.filter
    fun <T> toggle(list: List<T>, value: T) = if (value in list) list - value else list + value

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("common.filters"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = reset) { Text(t("common.filtersResetDefault")) }
            }
            Section(t("tasks.filterGroupStatus")) {
                listOf(Task.STATUS_OPEN, Task.STATUS_IN_PROGRESS, Task.STATUS_DONE).forEach { st ->
                    FilterChip(f.statuses.contains(st), onClick = {
                        val next = toggle(f.statuses, st)
                        apply(f.copy(statuses = next.ifEmpty { listOf(Task.STATUS_OPEN) }))
                    }, label = { Text(t(statusKey(st))) })
                }
            }
            Section(t("tasks.filterGroupPriority")) {
                Task.PRIORITIES.reversed().forEach { p ->
                    FilterChip(p in f.priorities, onClick = { apply(f.copy(priorities = toggle(f.priorities, p))) }, label = { Text(t(priorityKey(p))) })
                }
            }
            if (s.meta.users.size > 1) {
                Section(t("tasks.filterGroupPerson")) {
                    s.meta.users.forEach { u ->
                        FilterChip(u.id in f.assignees, onClick = { apply(f.copy(assignees = toggle(f.assignees, u.id))) }, label = { Text(u.displayName) })
                    }
                }
            }
            if (s.meta.categories.isNotEmpty()) {
                Section(t("tasks.categoryLabel")) {
                    s.meta.categories.sortedBy { it.sortOrder }.forEach { c ->
                        FilterChip(c.key in f.categories, onClick = { apply(f.copy(categories = toggle(f.categories, c.key))) },
                            label = { Text(categoryLabel(c.key, s.meta.categories, strings)) })
                    }
                }
            }
            if (s.meta.tags.isNotEmpty()) {
                Section(t("tasks.filterGroupTag")) {
                    s.meta.tags.forEach { tag ->
                        FilterChip(tag.tag in f.tags, onClick = { apply(f.copy(tags = toggle(f.tags, tag.tag))) }, label = { Text(tag.tag) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("android.tasks.showLater"), modifier = Modifier.weight(1f))
                Switch(checked = f.includeFuture, onCheckedChange = { apply(f.copy(includeFuture = it)) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
