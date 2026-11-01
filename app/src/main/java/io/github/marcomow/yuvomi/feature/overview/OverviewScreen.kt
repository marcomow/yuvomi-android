package io.github.marcomow.yuvomi.feature.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.tasks.TaskCompletion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** docs/spec/overview.md */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(offlineSession: Boolean, openTask: (Int) -> Unit, openTasks: (assignee: Int?) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm = viewModel { OverviewViewModel(shell.container) }
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val completion = TaskCompletion(shell.connection.tasks)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // OVERVIEW-R14: a new household day refreshes the overview.
    LaunchedEffect(shell.time.zone) {
        var day = shell.time.today()
        while (true) {
            delay(60_000)
            val now = shell.time.today()
            if (now != day) {
                day = now
                vm.refresh()
            }
        }
    }

    // OVERVIEW-R11: tick off with "who did it" and undo, then refresh.
    val tick: (Task, Pair<Int, String>?) -> Unit = { task, doneBy ->
        scope.launch {
            try {
                val receipt = completion.setDone(task, !task.isDone, doneBy, strings, shell.formats, shell.time.today())
                vm.refresh()
                if (receipt != null && shell.notify(receipt.message, strings.t("common.undo"))) {
                    completion.undo(task)
                    vm.refresh()
                }
            } catch (e: Exception) {
                shell.notify(strings.t(TaskCompletion.errorKey(e)))
            }
        }
    }

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text(t("dashboard.title")) },
            actions = { AccountButton() },
            scrollBehavior = scroll,
        )
        val model = s.model
        when {
            s.loading && model == null -> LoadingBox()
            model == null -> ErrorBox(strings.t(s.errorKey ?: "android.error.generic"), onRetry = { vm.refresh() })
            else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = if (maxWidth >= 600.dp) 2 else 1
                    val readOnly = shell.session.access("tasks") != "write" || s.cachedAt != null || offlineSession
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (s.cachedAt != null || offlineSession) {
                            item { OfflineBanner(s.cachedAt?.let { shell.formats.dateTime(java.time.Instant.ofEpochMilli(it).atZone(shell.time.zone)) }) }
                        }
                        item {
                            Text(
                                t(model.greetingKey, "name" to model.firstName),
                                style = MaterialTheme.typography.titleLarge,
                                color = Yuvomi.colors.textSecondary,
                            )
                        }
                        if (s.showFiltersHint) {
                            item {
                                Card {
                                    Row(Modifier.padding(start = 16.dp)) {
                                        Text(
                                            t("android.overview.filtersIgnored"),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                                        )
                                        TextButton(onClick = vm::dismissFiltersHint) { Text(t("common.close")) }
                                    }
                                }
                            }
                        }
                        model.glance?.let { rows ->
                            item {
                                TodayGlance(
                                    rows = rows,
                                    expanded = s.glanceExpanded,
                                    toggle = vm::toggleGlance,
                                    readOnly = readOnly,
                                    openTask = openTask,
                                    tick = tick,
                                    today = shell.time.today(),
                                )
                            }
                        }
                        val chunks = model.tiles.chunked(columns)
                        items(chunks, key = { chunk -> chunk.joinToString { it.id } }) { chunk ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                chunk.forEach { tile ->
                                    Column(Modifier.weight(1f)) {
                                        TileView(tile, readOnly, openTask, openTasks, tick, shell.time.today())
                                    }
                                }
                                if (chunk.size < columns) Column(Modifier.weight(1f)) {}
                            }
                        }
                    }
                }
            }
        }
    }
}
