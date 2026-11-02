package io.github.marcomow.yuvomi.feature.waste

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.waste.WasteViewModel.Overlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** docs/spec/waste.md: upcoming pickups, types and schedules, import sources (`renderPage`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WasteScreen(vm: WasteViewModel) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val text = remember(strings, shell.time.today()) { WasteText(strings, shell.time.today()) }
    val writable = !vm.readOnly && !s.offline
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("waste.title")) },
                actions = {
                    // The page's tools (`pageToolsMenuHtml`); writers only, like the web page. The feed is the member's own.
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = t("waste.moreActions")) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (writable) {
                                val createsSchedule = WasteRules.fabCreatesSchedule(s.types)
                                DropdownMenuItem(text = { Text(t("waste.addType")) }, onClick = { menu = false; vm.show(Overlay.TypeEdit(null)) })
                                if (createsSchedule) {
                                    DropdownMenuItem(text = { Text(t("waste.addPickup")) }, onClick = { menu = false; vm.show(Overlay.PickupEdit(null)) })
                                }
                                DropdownMenuItem(text = { Text(t("waste.importFileAction")) }, onClick = { menu = false; vm.show(Overlay.Import(null)) })
                                DropdownMenuItem(text = { Text(t("waste.addUrlSourceAction")) }, onClick = { menu = false; vm.show(Overlay.UrlSource) })
                                DropdownMenuItem(text = { Text(t("waste.reminderSettingsAction")) }, onClick = { menu = false; vm.openReminders() })
                            }
                            DropdownMenuItem(
                                text = { Text(t("settings.wasteFeedTitle")) },
                                leadingIcon = { Icon(Icons.Outlined.RssFeed, null) },
                                onClick = { menu = false; vm.openFeed() },
                            )
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            // `fabIntent()`: a schedule once there is an active type, a type before.
            if (writable && !s.loading && !s.loadFailed) {
                val schedule = WasteRules.fabCreatesSchedule(s.types)
                FloatingActionButton(onClick = { vm.show(if (schedule) Overlay.ScheduleEdit(null, null) else Overlay.TypeEdit(null)) }) {
                    Icon(Icons.Outlined.Add, contentDescription = t(if (schedule) "waste.addSchedule" else "waste.addType"))
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.loadFailed -> ErrorBox(t("waste.loadErrorTitle"), onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        val onboarding = WasteRules.isOnboarding(s.types)
                        if (!onboarding) upcoming(s, vm, text, writable)
                        types(s, vm, text, writable)
                        if (!onboarding) sources(s, vm, text, writable)
                    }
                }
            }
        }
    }

    // `applyDeepLink()`: scroll to the linked pickup (or its type), then let the highlight fade.
    val highlight = s.highlight
    LaunchedEffect(highlight, s.loading) {
        if (highlight == null || s.loading) return@LaunchedEffect
        val keys = listState.layoutInfo.totalItemsCount
        if (keys > 0) {
            val target = occurrenceKey(highlight)
            val index = indexOfKey(s, highlight, target)
            if (index >= 0) listState.animateScrollToItem(index)
        }
        delay(2000)
        vm.clearHighlight()
    }

    WasteOverlays(vm, s, text)
}

private fun occurrenceKey(link: WasteViewModel.Link) = "o-${link.typeId}:${link.date}"

/** The position of the linked row (or its type's card) in the list as [WasteScreen] lays it out. */
private fun indexOfKey(s: WasteViewModel.UiState, link: WasteViewModel.Link, key: String): Int {
    val split = WasteRules.splitUpcoming(s.data.occurrences)
    val shown = split.primary + if (s.upcomingExpanded) split.rest else emptyList()
    val more = if (split.rest.isNotEmpty()) 1 else 0
    val upcomingRows = if (s.data.occurrences.isEmpty()) 1 else shown.size + more
    val base = (if (s.offline) 1 else 0) + 1
    val at = shown.indexOfFirst { "o-${it.key}" == key }
    if (at >= 0) return base + at + if (at >= split.primary.size) more else 0
    val unscheduled = s.types.filter { WasteRules.isUnscheduled(it, s.data.schedules, s.data.occurrences) }
    val listed = s.types.filter { it !in unscheduled }
    val typesStart = base + upcomingRows + 1
    val typeAt = listed.indexOfFirst { it.id == link.typeId }
    return when {
        typeAt >= 0 -> typesStart + typeAt
        unscheduled.any { it.id == link.typeId } -> typesStart + listed.size
        else -> -1
    }
}

// ── Upcoming pickups (renderUpcoming) ──

private fun LazyListScope.upcoming(s: WasteViewModel.UiState, vm: WasteViewModel, text: WasteText, writable: Boolean) {
    item(key = "h-upcoming") { SectionTitle(t("waste.upcomingSectionTitle")) }
    val occurrences = s.data.occurrences
    if (occurrences.isEmpty()) {
        item(key = "no-upcoming") { EmptyState(t("waste.emptyUpcomingTitle"), t("waste.emptyUpcomingDescription")) }
        return
    }
    val split = WasteRules.splitUpcoming(occurrences)
    items(split.primary, key = { "o-${it.key}" }) { OccurrenceRow(it, vm, text, writable, s.highlight) }
    if (split.rest.isNotEmpty()) {
        item(key = "more") {
            TextButton(onClick = vm::toggleUpcoming, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(if (s.upcomingExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(if (s.upcomingExpanded) t("waste.upcomingShowLess") else t("waste.upcomingShowMore", "count" to split.rest.size))
            }
        }
        if (s.upcomingExpanded) items(split.rest, key = { "o-${it.key}" }) { OccurrenceRow(it, vm, text, writable, s.highlight) }
    }
}

@Composable
private fun OccurrenceRow(o: WasteOccurrence, vm: WasteViewModel, text: WasteText, writable: Boolean, highlight: WasteViewModel.Link?) {
    val tone = Tokens.parseColor(o.typeColor) ?: Yuvomi.colors.module("waste")
    val lit = highlight != null && highlight.typeId == o.typeId && highlight.date == o.dateKey
    Surface(color = if (lit) Yuvomi.colors.module("waste").copy(alpha = 0.12f) else Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(WasteIcons.of(o.typeIcon), null, tint = tone, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(o.typeName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (text.isToday(o.dateKey)) {
                        // Today is the most important row of the page: a tag that says what to do.
                        Tag(t("waste.putOutToday"), Yuvomi.colors.module("waste"))
                    } else {
                        Text(text.pickupWhen(o.dateKey), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
                val badges = listOfNotNull(
                    if (o.moved) t("waste.movedFromBadge", "date" to (WasteRules.movedFrom(o)?.let { text.date(it) } ?: "")) else null,
                    if (o.coalesced) t("waste.coalescedHint") else null,
                )
                if (badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { Tag(it, Yuvomi.colors.textSecondary) } }
                }
            }
            if (writable) OccurrenceMenu(o, vm, text)
        }
    }
}

/** The menu follows the origin: series pickups move, skip and restore; one-offs edit and delete; imports none. */
@Composable
private fun OccurrenceMenu(o: WasteOccurrence, vm: WasteViewModel, text: WasteText) {
    val series = WasteRules.hasScheduleOrigin(o)
    val oneOff = WasteRules.oneOffOrigin(o)
    if (!series && oneOff == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = t("common.moreActionsNamed", "name" to "${o.typeName.orEmpty()}, ${text.date(o.dateKey)}"))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (series) {
                DropdownMenuItem(text = { Text(t("waste.moveAction")) }, onClick = { open = false; vm.show(Overlay.Move(o)) })
                DropdownMenuItem(text = { Text(t("waste.skipAction")) }, onClick = { open = false; vm.show(Overlay.ConfirmSkip(o)) })
                if (o.moved) DropdownMenuItem(text = { Text(t("waste.restoreOccurrenceAction")) }, onClick = { open = false; vm.restoreOccurrence(o) })
            }
            if (oneOff?.oneOffId != null) {
                DropdownMenuItem(text = { Text(t("common.edit")) }, onClick = { open = false; vm.editPickup(oneOff.oneOffId) })
                DropdownMenuItem(
                    text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                    onClick = { open = false; vm.show(Overlay.ConfirmDeletePickup(oneOff.oneOffId, o.typeName.orEmpty())) },
                )
            }
        }
    }
}

// ── Types and schedules (renderTypes) ──

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.types(s: WasteViewModel.UiState, vm: WasteViewModel, text: WasteText, writable: Boolean) {
    item(key = "h-types") { SectionTitle(t("waste.typesSectionTitle")) }
    if (s.types.isEmpty()) {
        item(key = "onboarding") { Onboarding(vm, writable) }
        return
    }
    val unscheduled = s.types.filter { WasteRules.isUnscheduled(it, s.data.schedules, s.data.occurrences) }
    val listed = s.types.filter { it !in unscheduled }
    listed.forEachIndexed { index, type ->
        item(key = "t-${type.id}") { TypeCard(type, index, listed.size, s, vm, text, writable) }
    }
    if (unscheduled.isNotEmpty()) item(key = "unscheduled") { Unscheduled(unscheduled, vm, writable) }
    if (writable) {
        item(key = "presets") {
            val strings = LocalStrings.current
            val presets = WasteRules.remainingPresets(s.types) { strings.t(it.labelKey) }
            if (presets.isNotEmpty()) PresetChips(presets, vm, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun TypeCard(type: WasteType, index: Int, total: Int, s: WasteViewModel.UiState, vm: WasteViewModel, text: WasteText, writable: Boolean) {
    val strings = LocalStrings.current
    val schedules = s.data.schedules.filter { it.typeId == type.id }
    val lit = s.highlight?.typeId == type.id && s.highlight.date == null
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.background(if (lit) Yuvomi.colors.module("waste").copy(alpha = 0.12f) else Color.Transparent)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).then(if (writable) Modifier.clickable { vm.show(Overlay.TypeEdit(type)) } else Modifier).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(WasteIcons.of(type.icon), null, tint = Tokens.parseColor(type.color) ?: Yuvomi.colors.module("waste"), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(type.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (type.archived) {
                        Spacer(Modifier.width(8.dp))
                        Tag(t("waste.archived"), Yuvomi.colors.textSecondary)
                    }
                }
                if (writable) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = t("common.moreActionsNamed", "name" to type.name)) }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            if (index > 0) DropdownMenuItem(text = { Text(t("waste.moveTypeUp")) }, onClick = { open = false; vm.moveType(type, up = true) })
                            if (index < total - 1) DropdownMenuItem(text = { Text(t("waste.moveTypeDown")) }, onClick = { open = false; vm.moveType(type, up = false) })
                            DropdownMenuItem(text = { Text(t("waste.addSchedule")) }, onClick = { open = false; vm.show(Overlay.ScheduleEdit(type, null)) })
                        }
                    }
                }
            }
            if (schedules.isEmpty()) {
                // `typeWithoutScheduleHtml()`: the next one-off or imported pickup, else "no schedule" with the way there.
                val next = s.data.occurrences.firstOrNull { it.typeId == type.id }
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        next?.let { "${strings.t("waste.nextPickupLabel")}: ${text.pickupWhen(it.dateKey)}" } ?: strings.t("waste.noSchedulesYet"),
                        style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f),
                    )
                    if (writable && !type.archived && next == null) {
                        TextButton(onClick = { vm.show(Overlay.ScheduleEdit(type, null)) }) { Text(t("waste.addSchedule")) }
                    }
                }
            }
            schedules.forEach { schedule -> ScheduleRow(schedule, type, s, vm, writable) }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: WasteSchedule, type: WasteType, s: WasteViewModel.UiState, vm: WasteViewModel, writable: Boolean) {
    val strings = LocalStrings.current
    val next = WasteRules.nextForSchedule(schedule.id, s.data.occurrences)
    Row(
        Modifier.fillMaxWidth()
            .then(if (writable) Modifier.clickable { vm.show(Overlay.ScheduleEdit(type, schedule)) } else Modifier)
            .padding(start = 48.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(WasteRules.recurrenceSummary(schedule, strings), style = MaterialTheme.typography.bodyMedium)
            if (!schedule.active || next == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!schedule.active) Tag(t("waste.pausedBadge"), Yuvomi.colors.warning)
                    if (next == null) Text(t("waste.noUpcomingPickup"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
        }
    }
}

/** `unscheduledTypesHtml()`: every type without schedule and pickups in one card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Unscheduled(types: List<WasteType>, vm: WasteViewModel, writable: Boolean) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("waste.typesWithoutSchedule"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { type ->
                    AssistChip(
                        onClick = { if (writable) vm.show(Overlay.TypeEdit(type)) },
                        enabled = true,
                        label = { Text(type.name) },
                        leadingIcon = { Icon(WasteIcons.of(type.icon), null, tint = Tokens.parseColor(type.color) ?: Color.Unspecified, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (writable) {
                OutlinedButton(onClick = { vm.show(Overlay.ScheduleEdit(null, null)) }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("waste.addSchedule"))
                }
            }
        }
    }
}

/** The one onboarding block: the presets, then the import (`onboardingHtml`). */
@Composable
private fun Onboarding(vm: WasteViewModel, writable: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(t("waste.emptyTypesTitle"), if (writable) t("waste.emptyTypesDescription") else null)
        if (writable) {
            PresetChips(WasteRules.PRESETS, vm, Modifier)
            TextButton(onClick = { vm.show(Overlay.Import(null)) }) {
                Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(t("waste.importFileAction"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(presets: List<WasteRules.Preset>, vm: WasteViewModel, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.forEach { preset ->
            AssistChip(
                onClick = {
                    if (!busy) {
                        busy = true
                        scope.launch { vm.createPreset(preset); busy = false }
                    }
                },
                label = { Text(t(preset.labelKey)) },
                leadingIcon = { Icon(WasteIcons.of(preset.icon), null, tint = Tokens.parseColor(preset.color) ?: Color.Unspecified, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

// ── Import sources (renderSources) ──

private fun LazyListScope.sources(s: WasteViewModel.UiState, vm: WasteViewModel, text: WasteText, writable: Boolean) {
    item(key = "h-sources") { SectionTitle(t("waste.sourcesSectionTitle")) }
    if (s.data.sources.isEmpty()) {
        item(key = "no-sources") {
            EmptyState(
                title = t("waste.emptySourcesDescription"),
                action = if (writable) t("waste.importFileAction") else null,
                onAction = if (writable) ({ vm.show(Overlay.Import(null)) }) else null,
            )
        }
        return
    }
    items(s.data.sources, key = { "s-${it.id}" }) { source -> SourceRow(source, vm, text, writable) }
}

@Composable
private fun SourceRow(source: WasteSource, vm: WasteViewModel, text: WasteText, writable: Boolean) {
    val badge = WasteRules.healthBadgeKey(source)
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable { vm.openSource(source.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (source.isUrl) Icons.Outlined.Link else Icons.Outlined.Description, null, tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.name, style = MaterialTheme.typography.bodyLarge)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Tag(t(badge), if (badge == "waste.sourceErrorBadge") Yuvomi.colors.danger else Yuvomi.colors.warning)
                    }
                }
                Text(
                    if (source.coverageStart != null) text.range(source.coverageStart, source.coverageEnd) else t("waste.sourceNoCoverage"),
                    style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary,
                )
            }
        }
        if (writable) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = t("common.moreActionsNamed", "name" to source.name)) }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(t(WasteRules.sourceAction(source).labelKey)) }, onClick = { open = false; vm.sourceAction(source) })
                }
            }
        }
    }
}

// ── Small parts ──

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
internal fun Tag(label: String, tone: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = tone.copy(alpha = 0.15f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
