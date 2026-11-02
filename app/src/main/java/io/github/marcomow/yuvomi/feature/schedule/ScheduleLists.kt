package io.github.marcomow.yuvomi.feature.schedule

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** `emptyStateHTML({ compact: true })`: one sentence, the way to create below. */
@Composable
private fun CompactEmpty(text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

// ── Shift types (SCHED-R14 to R20) ──

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ShiftTypesTab(
    s: ScheduleViewModel.UiState,
    vm: ScheduleViewModel,
    viewer: ScheduleRules.Viewer,
    writable: Boolean,
    onOpen: (Overlay) -> Unit,
    ask: (Overlay.Confirm) -> Unit,
) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val data = s.data
    val templates = ScheduleRules.visibleTemplates(data.household.hiddenTemplates)
    PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (data.types.isEmpty()) {
                // `emptyShiftTypesState`: the FAB is the one way; the templates are offered beside it.
                item(key = "empty") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp)) {
                        Text(t("schedule.emptyShiftTypesTitle"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.size(8.dp))
                        Text(t("schedule.emptyShiftTypesDescription"), color = Yuvomi.colors.textSecondary, textAlign = TextAlign.Center)
                        if (writable) {
                            Spacer(Modifier.size(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                templates.forEach { (key, label) ->
                                    OutlinedButton(onClick = { vm.quickStart(key) }, enabled = !s.busy) {
                                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                                        Text(t(label), Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val groups = ScheduleRules.shiftTypeGroups(data.types)
                groups.forEach { group ->
                    // One group alone needs no title: the tab already says "Shift types".
                    if (groups.size > 1) {
                        item(key = "g-${group.key}") {
                            SectionTitle(t(if (group.key == ScheduleRules.GROUP_TIMED) "schedule.typesWithTime" else "schedule.allDay"))
                        }
                    }
                    items(group.types, key = { "t-${it.id}" }) { type ->
                        ShiftTypeRow(type, data, viewer, strings, shell.formats::time, onEdit = { onOpen(Overlay.ShiftTypeEditor(type)) }, onDelete = {
                            ask(Overlay.Confirm(strings.t("schedule.deleteShiftTypeTitle"), strings.t("schedule.deleteShiftTypeDetail", "name" to type.name), strings.t("schedule.delete"), true) {
                                vm.deleteShiftType(type)
                            })
                        })
                    }
                }
                // The quick start stays reachable after the first type, quietly below the list.
                if (writable && templates.isNotEmpty()) {
                    item(key = "quick") {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(t("schedule.quickStartShiftTypes"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                            templates.forEach { (key, label) ->
                                AssistChip(onClick = { vm.quickStart(key) }, enabled = !s.busy, label = { Text(t(label)) }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
                customFields(s, vm, viewer, writable, onOpen, ask, strings)
            }
        }
    }
}

@Composable
private fun ShiftTypeRow(
    type: ShiftType,
    data: ScheduleData,
    viewer: ScheduleRules.Viewer,
    strings: Strings,
    clock: (java.time.LocalTime) -> String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val editable = viewer.canEdit(type.createdBy)
    val name = ScheduleRules.label(type)
    // `shiftTypeRow`: who may change it, when it is not the reader.
    val owner = if (editable || viewer.readOnly) null else if (type.createdBy == null) strings.t("schedule.typeOrphaned")
    else strings.t("schedule.typeOwnedBy", "user" to ScheduleRules.userName(data.users, type.createdBy))
    val meta = listOfNotNull(ScheduleRules.clockLabel(type, strings, clock), owner).joinToString(" · ")
    ListItem(
        leadingContent = { TypeLeading(type) },
        headlineContent = { Text(name) },
        supportingContent = { Text(meta) },
        trailingContent = if (editable) ({ RowMenu(name, onEdit, onDelete) }) else null,
        modifier = if (editable) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}

/** `customFieldsSection`: only once a shift type exists to attach a field to. */
private fun LazyListScope.customFields(
    s: ScheduleViewModel.UiState,
    vm: ScheduleViewModel,
    viewer: ScheduleRules.Viewer,
    writable: Boolean,
    onOpen: (Overlay) -> Unit,
    ask: (Overlay.Confirm) -> Unit,
    strings: Strings,
) {
    val fields = s.data.customFields
    item(key = "cf-title") {
        SectionTitle(t("schedule.customFields")) {
            if (fields.isNotEmpty() && writable) {
                TextButton(onClick = { onOpen(Overlay.CustomFieldEditor(null)) }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Text(t("schedule.createCustomField"))
                }
            }
        }
    }
    if (fields.isEmpty()) {
        item(key = "cf-empty") {
            CompactEmpty(t("schedule.emptyCustomFieldsDescription"), if (writable) t("schedule.createCustomField") else null) { onOpen(Overlay.CustomFieldEditor(null)) }
        }
        return
    }
    items(fields, key = { "cf-${it.id}" }) { field ->
        val editable = viewer.canEdit(field.createdBy) && writable
        ListItem(
            headlineContent = { Text(field.name) },
            trailingContent = if (editable) ({
                RowMenu(field.name, onEdit = { onOpen(Overlay.CustomFieldEditor(field)) }, onDelete = {
                    val affected = s.data.types.count { t -> t.fields.any { it.id == field.id } }
                    ask(Overlay.Confirm(strings.t("schedule.deleteCustomFieldTitle", "name" to field.name), strings.t("schedule.deleteCustomFieldDetail", "count" to affected), strings.t("schedule.delete"), true) {
                        vm.deleteCustomField(field)
                    })
                })
            }) else null,
        )
    }
}

// ── Planning (SCHED-R21 to R29) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanningTab(
    s: ScheduleViewModel.UiState,
    vm: ScheduleViewModel,
    viewer: ScheduleRules.Viewer,
    writable: Boolean,
    text: ScheduleText,
    onOpen: (Overlay) -> Unit,
    ask: (Overlay.Confirm) -> Unit,
) {
    val strings = LocalStrings.current
    val data = s.data
    PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "p-title") { SectionTitle(t("schedule.patterns")) }
            if (ScheduleRules.planningNeedsShiftTypes(data)) {
                item(key = "needs") { NeedsTypesState { vm.selectTab(ScheduleTab.SHIFTS) } }
                return@LazyColumn
            }
            if (data.patterns.isEmpty()) {
                item(key = "p-empty") { EmptyPatternState(data, writable) { onOpen(Overlay.CreateEntry(EntryMode.PATTERN)) } }
            } else {
                items(ScheduleRules.patternsInMemberOrder(data.patterns, data.users), key = { "p-${it.id}" }) { p ->
                    PatternRow(p, data, viewer.canWrite(p.userId) && writable, s.today.toString(), onOpen = { onOpen(Overlay.PatternEditor(p)) }, onDelete = {
                        ask(Overlay.Confirm(strings.t("schedule.deletePatternTitle", "name" to p.name), strings.t("schedule.deletePatternDetail", "count" to p.cycleLength), strings.t("schedule.delete"), true) {
                            vm.deletePattern(p)
                        })
                    })
                }
            }

            item(key = "o-title") { SectionTitle(t("schedule.overrides")) }
            val overrides = ScheduleRules.overrideGroups(data.overrides, data.users)
            if (overrides.isEmpty()) {
                item(key = "o-empty") {
                    CompactEmpty(t("schedule.emptyOverridesDescription"), if (writable) t("schedule.createOverride") else null) { onOpen(Overlay.CreateEntry(EntryMode.REPLACE)) }
                }
            } else {
                items(overrides, key = { it.key }) { g ->
                    val type = ScheduleRules.typeById(data.types, g.shiftTypeId)
                    val label = type?.let(ScheduleRules::label) ?: t("schedule.freeDay")
                    val whenText = text.range(g.from, g.to, strings)
                    OccurrenceRow(
                        type, label, listOfNotNull(whenText, ScheduleRules.userName(data.users, g.userId), g.note?.ifEmpty { null }).joinToString(" · "),
                        extra = false,
                        editable = viewer.canWrite(g.userId) && writable,
                        menuName = "$label, $whenText",
                        onEdit = { onOpen(Overlay.OverrideEditor(g)) },
                        onDelete = {
                            ask(Overlay.Confirm(strings.t("schedule.deleteOverrideRangeTitle"), deleteRangeDetail(g.from, g.to, g.userId, data, text, strings), strings.t("schedule.delete"), true) {
                                vm.deleteOverrideGroup(g)
                            })
                        },
                    )
                }
            }

            item(key = "e-title") { SectionTitle(t("schedule.extraShifts")) }
            val extras = ScheduleRules.extraGroups(data.extras, data.users)
            if (extras.isEmpty()) {
                item(key = "e-empty") {
                    CompactEmpty(t("schedule.emptyExtraShiftsDescription"), if (writable) t("schedule.addExtraShift") else null) { onOpen(Overlay.CreateEntry(EntryMode.ADD)) }
                }
            } else {
                items(extras, key = { it.key }) { g ->
                    val type = ScheduleRules.typeById(data.types, g.shiftTypeId)
                    val label = type?.let(ScheduleRules::label) ?: t("schedule.extraBadgeLabel")
                    val whenText = text.range(g.from, g.to, strings)
                    OccurrenceRow(
                        type, label, listOfNotNull(whenText, ScheduleRules.userName(data.users, g.userId), g.note?.ifEmpty { null }).joinToString(" · "),
                        extra = true,
                        editable = viewer.canWrite(g.userId) && writable,
                        menuName = "$label, $whenText",
                        onEdit = { onOpen(Overlay.ExtraEditor(g)) },
                        onDelete = {
                            ask(Overlay.Confirm(strings.t("schedule.deleteOverrideRangeTitle"), deleteRangeDetail(g.from, g.to, g.userId, data, text, strings), strings.t("schedule.delete"), true) {
                                vm.deleteExtraGroup(g)
                            })
                        },
                    )
                }
            }
        }
    }
}

private fun deleteRangeDetail(from: String, to: String, userId: Int, data: ScheduleData, text: ScheduleText, strings: Strings) =
    strings.t("schedule.deleteOverrideRangeDetail", "from" to text.day(from), "to" to text.day(to), "user" to ScheduleRules.userName(data.users, userId))

/** `patternRow`: name and the "takes precedence" badge; someone else's plan opens read-only. */
@Composable
private fun PatternRow(p: Pattern, data: ScheduleData, editable: Boolean, today: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    val wins = ScheduleRules.winningPatternId(data.patterns, p.userId, today) == p.id
    val details = t("common.showDetails") + ": " + p.name
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, modifier = Modifier.weight(1f, fill = false))
                if (wins) {
                    Text(
                        t("schedule.patternWinsBadge"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        supportingContent = { Text(ScheduleRules.userName(data.users, p.userId)) },
        trailingContent = {
            if (editable) RowMenu(p.name, onOpen, onDelete) else IconButton(onClick = onOpen) { Icon(Icons.Outlined.Visibility, details) }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

/** `overrideRows` / `extraRows`: the shift is the title, when and for whom below. */
@Composable
private fun OccurrenceRow(
    type: ShiftType?,
    label: String,
    meta: String,
    extra: Boolean,
    editable: Boolean,
    menuName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeLeading(type)
                if (extra) Icon(Icons.Outlined.Layers, t("schedule.extraBadgeLabel"), Modifier.padding(start = 4.dp).size(16.dp), tint = Yuvomi.colors.textSecondary)
            }
        },
        headlineContent = { Text(label) },
        supportingContent = { Text(meta) },
        trailingContent = if (editable) ({ RowMenu(menuName, onEdit, onDelete) }) else null,
        modifier = if (editable) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}
