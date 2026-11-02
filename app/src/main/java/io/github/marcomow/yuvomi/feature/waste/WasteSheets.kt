package io.github.marcomow.yuvomi.feature.waste

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.documents.DocumentFiles
import io.github.marcomow.yuvomi.feature.settings.SettingsRoutes
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.waste.WasteViewModel.Overlay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The sheets and dialogs of the page (the `openModal` calls of waste.js). */
@Composable
internal fun WasteOverlays(vm: WasteViewModel, s: WasteViewModel.UiState, text: WasteText) {
    val close = { vm.show(null) }
    val scope = rememberCoroutineScope()
    when (val o = s.overlay) {
        null -> Unit
        is Overlay.TypeEdit -> TypeSheet(o.type, vm, s, close)
        is Overlay.ScheduleEdit -> ScheduleSheet(o.type, o.schedule, vm, s, text, close)
        is Overlay.PickupEdit -> PickupSheet(o.pickup, vm, s, text, close)
        is Overlay.Move -> DayPicker(
            initial = text.parse(o.occurrence.dateKey) ?: LocalDate.now(),
            title = t("waste.moveModalTitle"),
            onPick = { day -> scope.launch { vm.moveOccurrence(o.occurrence, day) } },
            onDismiss = close,
        )
        is Overlay.ConfirmSkip -> Confirm(t("waste.skipConfirm"), null, t("waste.skipAction"), danger = false, onConfirm = { vm.skipOccurrence(o.occurrence) }, onDismiss = close)
        is Overlay.ConfirmDeletePickup -> Confirm(
            t("waste.pickupDeleteConfirm"), t("waste.pickupDeleteConfirmDetail"), t("common.delete"), danger = true,
            onConfirm = { scope.launch { vm.deletePickup(o.pickupId, o.typeName) } }, onDismiss = close,
        )
        is Overlay.Source -> SourceSheet(o.source, vm, s, text, close)
        is Overlay.Import -> ImportSheet(o.source, vm, s, close)
        Overlay.UrlSource -> UrlSourceSheet(vm, close)
        is Overlay.Reminders -> RemindersSheet(o.settings, vm, close)
        is Overlay.Feed -> FeedSheet(o.feed, vm, close)
        is Overlay.ProfileImport -> ProfileSheet(o, vm)
    }
}

// ── Shared parts ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun Confirm(title: String, detail: String?, confirm: String, danger: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Yuvomi.colors.danger else Color.Unspecified) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(initial: LocalDate, title: String? = null, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                if (picked != null) onPick(picked) else onDismiss()
            }) { Text(t("common.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    ) { DatePicker(state, title = title?.let { { Text(it, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) } }) }
}

@Composable
private fun DateField(label: String, value: LocalDate?, text: WasteText, onClick: () -> Unit, onClear: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Text(if (value != null) "$label: ${text.date(value)}" else label)
        }
        if (onClear != null && value != null) TextButton(onClick = onClear) { Text(t("common.delete")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, value: T?, options: List<T>, display: (T) -> String, onPick: (T) -> Unit, placeholder: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value?.let(display) ?: placeholder.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = value == null && placeholder != null,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; onPick(option) }) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Footer(onDelete: (() -> Unit)?, busy: Boolean, saveLabel: String, onCancel: () -> Unit, onSave: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = t("common.delete"), tint = Yuvomi.colors.danger) }
        }
        extra()
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(t("common.cancel")) }
        Button(enabled = !busy, onClick = onSave) { Text(saveLabel) }
    }
}

/** Type choice chips for a label or mapping (`labelTypeChipsHtml`), active types plus those already chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(types: List<WasteType>, chosen: Set<Int>, onToggle: (Int) -> Unit, createNew: Boolean? = null, onToggleNew: (() -> Unit)? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        types.filter { !it.archived || it.id in chosen }.forEach { type ->
            FilterChip(
                selected = type.id in chosen,
                onClick = { onToggle(type.id) },
                label = { Text(type.name) },
                leadingIcon = { Icon(WasteIcons.of(type.icon), null, tint = Tokens.parseColor(type.color) ?: Color.Unspecified, modifier = Modifier.size(16.dp)) },
            )
        }
        if (createNew != null && onToggleNew != null) {
            FilterChip(selected = createNew, onClick = onToggleNew, label = { Text(t("waste.importCreateNewType")) }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) })
        }
    }
    if (chosen.isEmpty() && createNew != true) {
        Text(t("waste.importLabelIgnoredHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

private suspend fun readText(context: Context, uri: Uri): ByteArray? = DocumentFiles.read(context, uri)?.bytes

// ── Types (openTypeModal) ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeSheet(type: WasteType?, vm: WasteViewModel, s: WasteViewModel.UiState, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var name by remember(type?.id) { mutableStateOf(type?.name.orEmpty()) }
    var icon by remember(type?.id) { mutableStateOf(type?.icon ?: "trash-2") }
    var color by remember(type?.id) { mutableStateOf(type?.color ?: WasteRules.COLORS[2]) }
    var preset by remember { mutableStateOf<WasteRules.Preset?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Sheet(t(if (type == null) "waste.newType" else "waste.editType"), close) {
        if (type == null) {
            Choice(t("waste.typePresetLabel"), preset, WasteRules.PRESETS, { strings.t(it.labelKey) }, onPick = { p ->
                preset = p
                name = strings.t(p.labelKey); icon = p.icon; color = p.color
            }, placeholder = null)
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(WasteRules.TYPE_NAME_MAX) },
            label = { Text("${t("waste.typeNameLabel")} *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(t("waste.typeIconLabel"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val choices = if (WasteIcons.SUGGESTIONS.contains(icon) || icon == "trash-2") WasteIcons.SUGGESTIONS else listOf(icon) + WasteIcons.SUGGESTIONS
            choices.forEach { name ->
                val selected = name == icon || (icon == "trash-2" && name == "trash2")
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Yuvomi.colors.module("waste").copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { icon = name }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) { Icon(WasteIcons.of(name), null, tint = Tokens.parseColor(color) ?: Color.Unspecified) }
            }
        }
        Text(t("waste.typeColorLabel"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val palette = if (WasteRules.COLORS.any { it.equals(color, true) }) WasteRules.COLORS else WasteRules.COLORS + color
            palette.forEach { hex ->
                val selected = hex.equals(color, ignoreCase = true)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Tokens.parseColor(hex) ?: Color.Gray)
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { color = hex }.semantics { contentDescription = hex },
                    contentAlignment = Alignment.Center,
                ) { if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        Footer(
            onDelete = if (type != null) ({ confirmDelete = true }) else null,
            busy = s.busy,
            saveLabel = t(if (type == null) "common.add" else "common.save"),
            onCancel = close,
            onSave = { if (name.isNotBlank()) scope.launch { vm.saveType(type, name.trim(), icon.ifBlank { "trash-2" }, color) } },
        ) {
            if (type != null) {
                IconButton(onClick = { scope.launch { vm.toggleArchive(type) } }) {
                    Icon(if (type.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = t(if (type.archived) "waste.restoreAction" else "waste.archiveAction"))
                }
            }
        }
    }
    if (confirmDelete && type != null) {
        Confirm(t("waste.typeDeleteConfirm"), t("waste.typeDeleteConfirmDetail"), t("common.delete"), danger = true,
            onConfirm = { confirmDelete = false; scope.launch { vm.deleteType(type) } }, onDismiss = { confirmDelete = false })
    }
}

// ── Schedules (openScheduleModal) ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSheet(type: WasteType?, schedule: WasteSchedule?, vm: WasteViewModel, s: WasteViewModel.UiState, text: WasteText, close: () -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val today = shell.time.today()
    val pickType = type == null
    var f by remember(schedule?.id, type?.id) {
        mutableStateOf(WasteRules.scheduleForm(schedule, type?.id ?: WasteRules.defaultScheduleType(s.types, s.data.schedules)?.id, today))
    }
    var ownWeekdays by remember { mutableStateOf(schedule != null) }
    var weekdaysError by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val typeOptions = s.types.filter { !it.archived || it.id == f.typeId }

    Sheet(t(if (schedule == null) "waste.newSchedule" else "waste.editSchedule"), close) {
        if (pickType) {
            Choice(t("waste.typeSelectLabel"), s.type(f.typeId), typeOptions, { it.name }, onPick = { f = f.copy(typeId = it.id) }, placeholder = t("waste.typeSelectPlaceholder"))
        }
        Choice(
            t("waste.recurrenceKindLabel"), f.kind, listOf("weekly", "monthly_fixed_day", "monthly_ordinal_weekday"),
            {
                strings.t(
                    when (it) {
                        "weekly" -> "waste.recurrenceWeekly"
                        "monthly_fixed_day" -> "waste.recurrenceMonthly"
                        else -> "waste.recurrenceMonthlyOrdinal"
                    },
                )
            },
            onPick = { kind ->
                f = f.copy(kind = kind)
                if (kind == "monthly_ordinal_weekday") f = f.copy(anchor = WasteRules.nearestOrdinalAnchor(f.ordinal, f.ordinalWeekday, today))
            },
        )
        when (f.kind) {
            "weekly" -> {
                Text(t("waste.weekdaysLabel"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WasteRules.WEEKDAYS.forEach { code ->
                        FilterChip(
                            selected = code in f.weekdays,
                            onClick = {
                                ownWeekdays = true
                                weekdaysError = false
                                f = f.copy(weekdays = if (code in f.weekdays) f.weekdays - code else f.weekdays + code)
                            },
                            label = { Text(t(WasteRules.weekdayKey(code)!!)) },
                        )
                    }
                }
                if (weekdaysError) Text(t("waste.errorScheduleWeekdaysRequired"), color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            "monthly_fixed_day" -> {
                OutlinedTextField(
                    value = f.monthDay.toString(),
                    onValueChange = { v -> v.filter { it.isDigit() }.take(2).toIntOrNull()?.let { f = f.copy(monthDay = it.coerceIn(1, 31)) } },
                    label = { Text(t("waste.monthDayLabel")) },
                    enabled = !f.lastDay,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(t("waste.monthDayLastDay"), f.lastDay) { f = f.copy(lastDay = it) }
            }
            else -> {
                Choice(t("waste.ordinalPositionLabel"), f.ordinal, WasteRules.ORDINALS, { strings.t(WasteRules.ordinalKey(it)!!) }, onPick = {
                    f = f.copy(ordinal = it, anchor = WasteRules.nearestOrdinalAnchor(it, f.ordinalWeekday, today))
                })
                Choice(t("waste.ordinalWeekdayLabel"), f.ordinalWeekday, WasteRules.WEEKDAYS, { strings.t(WasteRules.weekdayKey(it)!!) }, onPick = {
                    f = f.copy(ordinalWeekday = it, anchor = WasteRules.nearestOrdinalAnchor(f.ordinal, it, today))
                })
            }
        }
        // "Every N weeks" as a stepper (R20), 1 to 52.
        Text(t("waste.intervalLabel"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { f = f.copy(interval = (f.interval - 1).coerceAtLeast(1)) }, enabled = f.interval > 1) {
                Icon(Icons.Outlined.Remove, contentDescription = t("waste.intervalDecrease"))
            }
            Text(
                t(if (f.kind == "weekly") "common.everyNWeeks" else "common.everyNMonths", "count" to f.interval),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            )
            IconButton(onClick = { f = f.copy(interval = (f.interval + 1).coerceAtMost(WasteRules.INTERVAL_MAX)) }, enabled = f.interval < WasteRules.INTERVAL_MAX) {
                Icon(Icons.Outlined.Add, contentDescription = t("waste.intervalIncrease"))
            }
        }
        DateField("${t("waste.anchorDateLabel")} *", f.anchor, text, onClick = { picking = "anchor" })
        DateField(t("waste.validUntilLabel"), f.validUntil, text, onClick = { picking = "until" }, onClear = { f = f.copy(validUntil = null) })
        // "Active" only when editing (R24): a new schedule is active.
        if (schedule != null) SwitchRow(t("waste.activeLabel"), f.active) { f = f.copy(active = it) }
        Footer(
            onDelete = if (schedule != null) ({ confirmDelete = true }) else null,
            busy = s.busy,
            saveLabel = t(if (schedule == null) "common.add" else "common.save"),
            onCancel = close,
            onSave = {
                if (f.typeId == null) return@Footer
                if (f.kind == "weekly" && f.weekdays.isEmpty()) {
                    weekdaysError = true
                    return@Footer
                }
                scope.launch {
                    if (vm.saveSchedule(schedule, f) == WasteViewModel.ScheduleResult.WeekdaysMissing) weekdaysError = true
                }
            },
        )
    }
    when (picking) {
        "anchor" -> DayPicker(f.anchor, onPick = { day ->
            f = f.copy(anchor = day)
            // A new weekly schedule follows the first pickup's weekday until the member picks days.
            if (schedule == null && !ownWeekdays && f.kind == "weekly") f = f.copy(weekdays = setOf(WasteRules.weekdayCodeOf(day)))
            picking = null
        }, onDismiss = { picking = null })
        "until" -> DayPicker(f.validUntil ?: f.anchor, onPick = { f = f.copy(validUntil = it); picking = null }, onDismiss = { picking = null })
    }
    if (confirmDelete && schedule != null) {
        Confirm(t("waste.scheduleDeleteConfirm"), t("waste.scheduleDeleteConfirmDetail"), t("common.delete"), danger = true,
            onConfirm = { confirmDelete = false; scope.launch { vm.deleteSchedule(schedule) } }, onDismiss = { confirmDelete = false })
    }
}

// ── One-off pickups (openPickupModal) ──

@Composable
private fun PickupSheet(pickup: WastePickup?, vm: WasteViewModel, s: WasteViewModel.UiState, text: WasteText, close: () -> Unit) {
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    var typeId by remember(pickup?.id) { mutableStateOf(pickup?.typeId ?: s.types.firstOrNull { !it.archived }?.id) }
    var date by remember(pickup?.id) { mutableStateOf(text.parse(pickup?.date) ?: shell.time.today()) }
    var note by remember(pickup?.id) { mutableStateOf(pickup?.note.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val options = s.types.filter { !it.archived || it.id == pickup?.typeId }
    Sheet(t(if (pickup == null) "waste.newPickup" else "waste.editPickup"), close) {
        Choice(t("waste.typeSelectLabel"), s.type(typeId), options, { it.name }, onPick = { typeId = it.id }, placeholder = t("waste.typeSelectPlaceholder"))
        DateField("${t("waste.pickupDateLabel")} *", date, text, onClick = { picking = true })
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(WasteRules.NOTE_MAX) },
            label = { Text(t("waste.pickupNoteLabel")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        Footer(
            onDelete = if (pickup != null) ({ confirmDelete = true }) else null,
            busy = s.busy,
            saveLabel = t(if (pickup == null) "common.add" else "common.save"),
            onCancel = close,
            onSave = { typeId?.let { id -> scope.launch { vm.savePickup(pickup, id, date, note) } } },
        )
    }
    if (picking) DayPicker(date, onPick = { date = it; picking = false }, onDismiss = { picking = false })
    if (confirmDelete && pickup != null) {
        Confirm(t("waste.pickupDeleteConfirm"), t("waste.pickupDeleteConfirmDetail"), t("common.delete"), danger = true,
            onConfirm = { confirmDelete = false; scope.launch { vm.deletePickup(pickup.id, s.type(pickup.typeId)?.name.orEmpty()) } },
            onDismiss = { confirmDelete = false })
    }
}

// ── A source (openSourceModal) ──

@Composable
private fun SourceSheet(source: WasteSource, vm: WasteViewModel, s: WasteViewModel.UiState, text: WasteText, close: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(source) { mutableStateOf(source.name) }
    val chosen = remember(source) { mutableStateMapOf<Int, Set<Int>>().apply { source.mappings.forEach { put(it.id, it.typeIds.toSet()) } } }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    val badge = WasteRules.healthBadgeKey(source)
    val writable = !vm.readOnly && !s.offline

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = exportJson
        exportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch { if (!DocumentFiles.save(context, uri, json.toByteArray())) vm.tell(strings.t("common.errorGeneric")) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readText(context, uri)
            if (bytes == null) vm.tell(strings.t("waste.mappingProfileInvalidFile")) else vm.previewProfile(source, bytes.decodeToString())
        }
    }

    Sheet(source.name, close) {
        if (writable) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(WasteRules.SOURCE_NAME_MAX) },
                label = { Text(t("waste.sourceNameLabel")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        val meta = listOfNotNull(
            if (source.isUrl && source.url != null) "${strings.t("waste.sourceUrlLabel")}: ${source.url}" else null,
            source.coverageStart?.let { "${strings.t("waste.sourceCoverageLabel")}: ${text.range(source.coverageStart, source.coverageEnd)}" },
            source.lastSuccessAt?.let { "${strings.t("waste.sourceLastImportedLabel")}: ${text.date(it)}" },
        )
        meta.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
        if (source.lastError != null || source.lastErrorReason != null) {
            val m = WasteErrors.fetchFailure(source.lastErrorReason, source.lastErrorParams)
            Text(strings.t(m.key, m.args), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.danger)
        }
        if (badge != null) Tag(t(badge), if (badge == "waste.sourceErrorBadge") Yuvomi.colors.danger else Yuvomi.colors.warning)
        HorizontalDivider()
        Text(t("waste.sourceMappingsTitle"), style = MaterialTheme.typography.titleSmall)
        if (writable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val json = vm.exportProfile(source) ?: return@launch
                        exportJson = json
                        save.launch(WasteRules.profileFileName(source.name))
                    }
                }) { Text(t("waste.mappingProfileExportAction")) }
                OutlinedButton(onClick = { open.launch(arrayOf("application/json", "*/*")) }) { Text(t("waste.mappingProfileImportAction")) }
            }
        }
        source.mappings.forEach { mapping ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mapping.originalLabel, fontWeight = FontWeight.SemiBold)
                val ids = chosen[mapping.id].orEmpty()
                if (writable) {
                    TypeChips(s.types, ids, onToggle = { id -> chosen[mapping.id] = if (id in ids) ids - id else ids + id })
                } else {
                    Text(
                        ids.mapNotNull { s.type(it)?.name }.joinToString(", ").ifEmpty { strings.t("waste.importLabelIgnoredHint") },
                        style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary,
                    )
                }
            }
        }
        if (writable) {
            Footer(
                onDelete = { confirmDelete = true },
                busy = s.busy,
                saveLabel = t("common.save"),
                onCancel = close,
                onSave = { scope.launch { vm.saveSource(source, name, chosen.toMap()) } },
            ) {
                TextButton(onClick = { vm.show(null); vm.sourceAction(source) }) { Text(t(WasteRules.sourceAction(source).labelKey)) }
            }
        }
    }
    if (confirmDelete) {
        Confirm(t("waste.sourceDeleteConfirm"), t("waste.sourceDeleteConfirmDetail"), t("common.delete"), danger = true,
            onConfirm = { confirmDelete = false; scope.launch { vm.deleteSource(source) } }, onDismiss = { confirmDelete = false })
    }
}

// ── The import wizard (openImportWizard) ──

@Composable
private fun ImportSheet(source: WasteSource?, vm: WasteViewModel, s: WasteViewModel.UiState, close: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reimport = source != null
    val urlSource = source?.isUrl == true
    var name by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf<String?>(null) }
    var ics by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    val skipped = remember { mutableStateListOf<String>() }
    val choices = remember { mutableStateMapOf<String, WasteRules.LabelChoice>() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = DocumentFiles.read(context, uri)
            when {
                file == null -> error = strings.t("waste.importReadError")
                file.bytes.size > WasteErrors.MAX_ICS_MB * 1024 * 1024 -> error = strings.t("waste.errorImportTooLarge", "limit_mb" to WasteErrors.MAX_ICS_MB)
                else -> { ics = file.bytes.decodeToString(); fileName = file.fileName; error = null }
            }
        }
    }

    Sheet(if (reimport) strings.t("waste.reimportModalTitle", "name" to source!!.name) else t("waste.importModalTitle"), close) {
        val p = preview
        if (p == null) {
            if (!reimport) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(WasteRules.SOURCE_NAME_MAX) },
                    label = { Text(t("waste.importNameLabel")) }, placeholder = { Text(t("waste.importNamePlaceholder")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!urlSource) {
                OutlinedButton(onClick = { pick.launch(arrayOf("text/calendar", "application/ics", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(fileName ?: t("waste.importFileLabel"))
                }
            }
        } else {
            Text(strings.t("waste.importSummary", "events" to p.counts.events, "candidates" to p.counts.candidates))
            p.diagnostics.forEach { d ->
                val line = WasteRules.diagnosticText(d, strings)
                if (d.severity == "blocking" && d.eventKey != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        if (d.eventKey in skipped) skipped.remove(d.eventKey) else skipped.add(d.eventKey)
                    }) {
                        Checkbox(checked = d.eventKey in skipped, onCheckedChange = { on -> if (on) skipped.add(d.eventKey) else skipped.remove(d.eventKey) })
                        Text("$line ${strings.t("waste.importSkipEventLabel")}", color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
            if (p.labels.isEmpty()) {
                Text(t("waste.importNoLabels"))
            } else {
                Text(t("waste.importLabelsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                p.labels.forEach { label ->
                    val c = choices[label.normalizedLabel] ?: WasteRules.defaultChoice(label)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${label.originalLabel} (${label.count})", fontWeight = FontWeight.SemiBold)
                        TypeChips(
                            s.types, c.typeIds,
                            onToggle = { id -> choices[label.normalizedLabel] = c.copy(typeIds = if (id in c.typeIds) c.typeIds - id else c.typeIds + id) },
                            createNew = c.createNew,
                            onToggleNew = { choices[label.normalizedLabel] = c.copy(createNew = !c.createNew) },
                        )
                        if (c.createNew) {
                            OutlinedTextField(
                                value = c.newName, onValueChange = { choices[label.normalizedLabel] = c.copy(newName = it.take(WasteRules.TYPE_NAME_MAX)) },
                                label = { Text(t("waste.typeNameLabel")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = close) { Text(t("common.cancel")) }
            if (p == null) {
                Button(enabled = !busy, onClick = {
                    if (!urlSource && ics == null) {
                        error = strings.t("waste.importNoFileError")
                        return@Button
                    }
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = vm.preview(source, ics)) {
                            is WasteViewModel.PreviewResult.Ready -> preview = r.preview
                            is WasteViewModel.PreviewResult.Failed -> error = r.message
                            WasteViewModel.PreviewResult.Closed -> Unit
                        }
                        busy = false
                    }
                }) { Text(t("waste.importPreviewAction")) }
            } else {
                Button(enabled = !busy, onClick = {
                    error = null
                    if (WasteRules.unresolvedBlocking(p.diagnostics, skipped.toSet()).isNotEmpty()) {
                        error = strings.t("waste.importUnresolvedBlockingError")
                        return@Button
                    }
                    val decisions = WasteRules.buildDecisions(p.labels.map { it.normalizedLabel to (choices[it.normalizedLabel] ?: WasteRules.defaultChoice(it)) })
                    if (decisions is WasteRules.Decisions.MissingName) {
                        error = strings.t("waste.importNewTypeNameRequiredError")
                        return@Button
                    }
                    val body = WasteRules.commitBody(
                        (decisions as WasteRules.Decisions.Ok).mappings, skipped.toSet(), p,
                        ics = if (urlSource) null else ics, reimport = reimport,
                        name = name.trim().ifEmpty { strings.t("waste.importDefaultSourceName") },
                    )
                    busy = true
                    scope.launch {
                        when (val r = vm.commit(source, body)) {
                            is WasteViewModel.PreviewResult.Failed -> {
                                error = r.message
                                // `refreshImportTypeChoices()`: chips for types that went fall back to "create new".
                                p.labels.forEach { label ->
                                    val c = choices[label.normalizedLabel] ?: WasteRules.defaultChoice(label)
                                    val kept = c.typeIds.filter { id -> vm.state.value.type(id) != null }.toSet()
                                    if (kept != c.typeIds) choices[label.normalizedLabel] = c.copy(typeIds = kept, createNew = c.createNew || kept.isEmpty())
                                }
                            }
                            else -> Unit
                        }
                        busy = false
                    }
                }) { Text(t("waste.importCommitAction")) }
            }
        }
    }
}

// ── A URL source (openUrlSourceWizard) ──

@Composable
private fun UrlSourceSheet(vm: WasteViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(WasteRules.URL_DEFAULT_INTERVAL.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Sheet(t("waste.urlSourceModalTitle"), close) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(WasteRules.SOURCE_NAME_MAX) },
            label = { Text(t("waste.importNameLabel")) }, placeholder = { Text(t("waste.importNamePlaceholder")) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() }, label = { Text(t("waste.sourceUrlLabel")) }, placeholder = { Text("https://") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = interval, onValueChange = { v -> interval = v.filter { it.isDigit() }.take(5) }, label = { Text(t("waste.refreshIntervalMinutesLabel")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Yuvomi.colors.danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = close) { Text(t("common.cancel")) }
            Button(enabled = !busy, onClick = {
                if (url.isBlank()) {
                    error = strings.t("waste.importNoFileError")
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    error = vm.createUrlSource(name.trim(), url, interval.toIntOrNull() ?: 0)
                    busy = false
                }
            }) { Text(t("common.add")) }
        }
    }
}

// ── Reminders (openReminderSettingsModal) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindersSheet(original: List<ReminderSetting>, vm: WasteViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rows = remember(original) { mutableStateListOf<ReminderSetting>().apply { addAll(original) } }
    var timeFor by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    Sheet(t("waste.reminderSettingsModalTitle"), close) {
        if (rows.isEmpty()) Text(t("waste.emptyTypesTitle"))
        rows.forEachIndexed { index, row ->
            Column {
                SwitchRow(row.typeName, row.enabled) { rows[index] = row.copy(enabled = it) }
                if (row.enabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("waste.reminderOffsetDaysLabel"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { rows[index] = row.copy(offsetDays = (row.offsetDays - 1).coerceAtLeast(0)) }, enabled = row.offsetDays > 0) {
                            Icon(Icons.Outlined.Remove, contentDescription = t("waste.intervalDecrease"))
                        }
                        Text(row.offsetDays.toString(), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { rows[index] = row.copy(offsetDays = (row.offsetDays + 1).coerceAtMost(WasteRules.REMINDER_MAX_OFFSET)) }, enabled = row.offsetDays < WasteRules.REMINDER_MAX_OFFSET) {
                            Icon(Icons.Outlined.Add, contentDescription = t("waste.intervalIncrease"))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("waste.reminderDeliveryTimeLabel"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { timeFor = index }) { Text(row.deliveryTime) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = close) { Text(t("common.cancel")) }
            Button(enabled = !busy && rows.isNotEmpty(), onClick = {
                busy = true
                scope.launch { vm.saveReminders(original, rows.toList()); busy = false }
            }) { Text(t("common.save")) }
        }
    }
    timeFor?.let { index ->
        val row = rows[index]
        val (h, m) = row.deliveryTime.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 8) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeFor = null },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    rows[index] = row.copy(deliveryTime = "%02d:%02d".format(state.hour, state.minute))
                    timeFor = null
                }) { Text(t("common.save")) }
            },
            dismissButton = { TextButton(onClick = { timeFor = null }) { Text(t("common.cancel")) } },
        )
    }
}

// ── The feed link (the member's own; managed in Settings) ──

@Composable
private fun FeedSheet(feed: WasteFeed?, vm: WasteViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val context = LocalContext.current
    Sheet(t("settings.wasteFeedTitle"), close) {
        Text(t("settings.wasteFeedDescription"), style = MaterialTheme.typography.bodyMedium)
        if (feed == null) {
            // The feed is switched on, renewed and filtered in Settings (`personal-feeds.js`).
            Button(onClick = { close(); shell.navigate(SettingsRoutes.START) }) { Text(t("nav.settings")) }
        } else {
            Text(t("settings.wasteFeedUrlLabel"), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(feed.url, style = MaterialTheme.typography.bodySmall) }
            Text(t("settings.wasteFeedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(strings.t("settings.wasteFeedTitle"), feed.url))
                    vm.tell(strings.t("settings.wasteFeedCopied"))
                }) { Text(t("settings.wasteFeedCopy")) }
                OutlinedButton(onClick = {
                    val webcal = feed.url.replaceFirst(Regex("^https?://"), "webcal://")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, webcal.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        vm.tell(strings.t("common.errorGeneric"))
                    }
                }) { Text(t("settings.wasteFeedSubscribe")) }
            }
        }
    }
}

// ── Mapping profile review (openMappingProfileImportModal) ──

@Composable
private fun ProfileSheet(o: Overlay.ProfileImport, vm: WasteViewModel) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Sheet(t("waste.mappingProfileImportModalTitle"), { vm.show(Overlay.Source(o.source)) }) {
        Text(strings.t("waste.mappingProfileApplicableCount", "count" to o.preview.applicableCount))
        o.preview.entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${e.originalLabel ?: e.pattern} → ${e.typeName}", style = MaterialTheme.typography.bodyMedium)
                }
                Tag(WasteRules.profileStatusKey(e.status)?.let(strings::t) ?: e.status, if (e.status == "applicable") Yuvomi.colors.success else Yuvomi.colors.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { vm.show(Overlay.Source(o.source)) }) { Text(t("common.cancel")) }
            Button(enabled = !busy && o.preview.applicableCount > 0, onClick = {
                busy = true
                scope.launch { vm.applyProfile(o); busy = false }
            }) { Text(t("waste.mappingProfileApplyAction")) }
        }
    }
}
