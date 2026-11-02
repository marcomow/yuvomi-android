package io.github.marcomow.yuvomi.feature.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** What sits over the page: one dialog or sheet at a time. */
internal sealed interface Overlay {
    data class ShiftTypeEditor(val type: ShiftType?) : Overlay
    data class CustomFieldEditor(val field: CustomField?) : Overlay
    data class CreateEntry(val mode: EntryMode) : Overlay
    data class PatternEditor(val pattern: Pattern) : Overlay
    data class OverrideEditor(val group: ScheduleRules.OverrideGroup) : Overlay
    data class ExtraEditor(val group: ScheduleRules.ExtraGroup) : Overlay
    data class EntryDetail(val entry: Entry) : Overlay
    data class Confirm(val title: String, val detail: String?, val action: String, val danger: Boolean, val onConfirm: () -> Unit) : Overlay
    data object People : Overlay
    data object Feed : Overlay
}

/** docs/spec/schedule.md: the page with its four tabs (SCHED-R1 to R5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel, openSettings: () -> Unit) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val viewer = vm.viewer
    val writable = !viewer.readOnly && !s.offline
    val text = rememberScheduleText(s.today)
    val needsTypes = ScheduleRules.planningNeedsShiftTypes(s.data)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (vm.lastLoaded != 0L && System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    // A route that names a row opens its dialog once the data is there (ScheduleRoutes.pattern, ...).
    LaunchedEffect(s.pendingOpen, s.loading) {
        val key = s.pendingOpen ?: return@LaunchedEffect
        if (s.loading) return@LaunchedEffect
        vm.consumeOpen()
        overlay = openFor(key, s.data, viewer)
    }

    val ask: (Overlay.Confirm) -> Unit = { overlay = it }
    val tab = s.tab ?: ScheduleTab.OVERVIEW

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("schedule.title")) },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(t("settings.scheduleFeedTitle")) }, onClick = {
                                menu = false
                                vm.loadFeed()
                                overlay = Overlay.Feed
                            })
                            if (shell.shows("settings")) {
                                DropdownMenuItem(text = { Text(t("schedule.mySettings")) }, onClick = { menu = false; openSettings() })
                            }
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            // `updateScheduleFab`: hidden for read-only members and on Statistics; the noun of what is made.
            val fab = when {
                !writable || tab == ScheduleTab.STATISTICS || s.loading || s.failed -> null
                tab == ScheduleTab.SHIFTS || needsTypes -> "newLabel.scheduleShiftType" to "schedule.createShiftType"
                else -> "newLabel.schedulePlan" to "schedule.addPlan"
            }
            if (fab != null) {
                val action = t(fab.second)
                ExtendedFloatingActionButton(
                    onClick = {
                        overlay = if (fab.first == "newLabel.scheduleShiftType") Overlay.ShiftTypeEditor(null) else Overlay.CreateEntry(EntryMode.PATTERN)
                    },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(t(fab.first)) },
                    // The accessible name is the action (`aria-label`), the visible word the noun.
                    modifier = Modifier.semantics { contentDescription = action },
                )
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.failed -> ErrorBox("${t("common.errorOccurred")}\n${t("common.loadErrorDescription")}", onRetry = { vm.refresh() })
                else -> {
                    // `renderPage`: the overview tab hides while there is nothing to compare, unless chosen.
                    val tabs = ScheduleTab.entries.filter { it != ScheduleTab.OVERVIEW || !needsTypes || tab == ScheduleTab.OVERVIEW }
                    PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 8.dp) {
                        tabs.forEach { item ->
                            Tab(selected = item == tab, onClick = { vm.selectTab(item) }, text = { Text(t(item.labelKey)) })
                        }
                    }
                    if (s.offline) OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
                    // `renderScheduleWarnings`: two active plans of one person today.
                    s.data.warnings.forEach { w ->
                        Surface(color = Yuvomi.colors.warning.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                t("schedule.overlapWarning", "date" to text.day(w.dateKey), "user" to ScheduleRules.userName(s.data.users, w.userId)),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    when (tab) {
                        ScheduleTab.OVERVIEW -> OverviewTab(s, vm, text, writable, onOpen = { overlay = it })
                        ScheduleTab.SHIFTS -> ShiftTypesTab(s, vm, viewer, writable, onOpen = { overlay = it }, ask = ask)
                        ScheduleTab.PATTERNS -> PlanningTab(s, vm, viewer, writable, text, onOpen = { overlay = it }, ask = ask)
                        ScheduleTab.STATISTICS -> StatisticsTab(s, vm, viewer, text)
                    }
                }
            }
        }
    }

    val close = { overlay = null }
    when (val o = overlay) {
        null -> Unit
        is Overlay.ShiftTypeEditor -> ShiftTypeSheet(o.type, s, vm, close)
        is Overlay.CustomFieldEditor -> CustomFieldDialog(o.field, s.busy, onSave = { name -> vm.saveCustomField(o.field, name, close) }, onDismiss = close)
        is Overlay.CreateEntry -> CreateEntrySheet(o.mode, s, vm, viewer, text, close)
        is Overlay.PatternEditor -> PatternSheet(o.pattern, s, vm, viewer, text, close)
        is Overlay.OverrideEditor -> OverrideSheet(o.group, s, vm, text, close)
        is Overlay.ExtraEditor -> ExtraSheet(o.group, s, vm, close)
        is Overlay.EntryDetail -> EntryDetailDialog(o.entry, s.data, text, close)
        is Overlay.Confirm -> ConfirmDialog(o.title, o.detail, o.action, o.danger, onConfirm = o.onConfirm, onDismiss = close)
        Overlay.People -> PeopleSheet(s, vm, close)
        Overlay.Feed -> FeedSheet(s.feed, vm, close)
    }
}

/** The dialog a route key names, if the member may open it. */
internal fun openFor(key: String, data: ScheduleData, viewer: ScheduleRules.Viewer): Overlay? {
    val parts = key.split(':')
    return when (parts.firstOrNull()) {
        "pattern" -> parts.getOrNull(1)?.toIntOrNull()?.let { id -> data.patterns.firstOrNull { it.id == id } }?.let { Overlay.PatternEditor(it) }
        "type" -> parts.getOrNull(1)?.toIntOrNull()?.let { id -> data.types.firstOrNull { it.id == id } }
            ?.takeIf { viewer.canEdit(it.createdBy) }?.let { Overlay.ShiftTypeEditor(it) }
        "override" -> {
            val user = parts.getOrNull(1)?.toIntOrNull()
            val from = parts.getOrNull(2)
            ScheduleRules.overrideGroups(data.overrides, data.users).firstOrNull { it.userId == user && it.from == from }
                ?.takeIf { viewer.canWrite(it.userId) }?.let { Overlay.OverrideEditor(it) }
        }
        "extra" -> {
            val ids = parts.getOrNull(1)
            ScheduleRules.extraGroups(data.extras, data.users).firstOrNull { it.ids.joinToString("-") == ids }
                ?.takeIf { viewer.canWrite(it.userId) }?.let { Overlay.ExtraEditor(it) }
        }
        else -> null
    }
}
