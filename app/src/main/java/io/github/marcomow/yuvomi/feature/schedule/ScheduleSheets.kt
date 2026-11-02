package io.github.marcomow.yuvomi.feature.schedule

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A sheet with a title, a scrolling body, and Cancel + the primary button (`modal-panel__footer`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    primary: String?,
    busy: Boolean,
    onPrimary: () -> Unit,
    dismissLabel: String = t("common.cancel"),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                if (primary != null) {
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onPrimary, enabled = !busy) { Text(primary) }
                }
            }
        }
    }
}

@Composable
private fun TextInput(label: String, value: String, onChange: (String) -> Unit, max: Int, error: String? = null, enabled: Boolean = true, number: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(max)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** `typeOptions`: the household's shift types, with "Free day" first where a day may be free. */
@Composable
private fun TypeSelect(types: List<ShiftType>, selected: Int?, includeFree: Boolean, onSelect: (Int?) -> Unit, error: String? = null, enabled: Boolean = true, label: String = t("schedule.shiftType")) {
    val options = buildList<Pair<Int?, String>> {
        if (includeFree) add(null to t("schedule.freeDay"))
        types.forEach { add(it.id to ScheduleRules.label(it)) }
    }
    SelectField(label, options, selected, onSelect, enabled = enabled, error = error)
}

/** `dayRowFieldsHtml`: an input per field of the chosen shift type. */
@Composable
private fun FieldValues(type: ShiftType?, values: Map<String, String>, onChange: (Map<String, String>) -> Unit, enabled: Boolean = true) {
    type?.fields.orEmpty().sortedBy { it.position }.forEach { field ->
        TextInput(field.name, values[field.id.toString()].orEmpty(), { onChange(values + (field.id.toString() to it)) }, max = 500, enabled = enabled)
    }
}

/** Only the values of the type's own fields go out (`collectFieldValues`). */
private fun valuesFor(type: ShiftType?, values: Map<String, String>): Map<String, String> {
    val ids = type?.fields.orEmpty().map { it.id.toString() }.toSet()
    return values.filterKeys { it in ids }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
}

/** `reminderOffsetField`: a switch and the lead time (presets, the stored value, or a custom one). */
@Composable
private fun ReminderField(on: Boolean, minutes: Int, onChange: (Boolean, Int) -> Unit) {
    val strings = LocalStrings.current
    var custom by remember { mutableStateOf(false) }
    SwitchRow(t("schedule.extraReminderOffset"), on, { onChange(it, minutes) })
    val values = (ScheduleRules.REMINDER_PRESETS + minutes).distinct().sorted()
    val options: List<Pair<Int, String>> = values.map { it to reminderLabel(it, strings) } + (-1 to t("schedule.reminderCustomOffset"))
    SelectField(t("schedule.reminderLeadTime"), options, minutes, { v -> if (v == -1) custom = true else onChange(on, v) }, enabled = on)
    if (custom) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(t("schedule.customReminderOffsetPrompt")) },
            text = { TextInput(t("schedule.reminderLeadTime"), input, { input = it.filter(Char::isDigit) }, max = 4, number = true) },
            confirmButton = {
                TextButton(onClick = {
                    custom = false
                    input.toIntOrNull()?.takeIf { it in 0..ScheduleRules.MAX_REMINDER_MINUTES }?.let { onChange(on, it) }
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(t("common.cancel")) } },
        )
    }
}

private fun reminderLabel(minutes: Int, strings: Strings) =
    if (minutes == 0) strings.t("schedule.reminderAtStart") else strings.t("schedule.reminderMinutesBefore", "minutes" to minutes)

/** `colorPickerHtml`: the ten swatches and a custom hex value. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(color: String, onChange: (String) -> Unit) {
    Text(t("schedule.color"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleRules.USER_COLORS.forEachIndexed { i, c ->
            val selected = c.equals(color, ignoreCase = true)
            val name = t("colorPicker.${COLOR_NAMES[i]}")
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(shiftColor(c))
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onChange(c) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Outlined.Check, null, tint = androidx.compose.ui.graphics.Color.White) }
        }
    }
    var hex by remember(color) { mutableStateOf(color) }
    TextInput(t("colorPicker.custom"), hex, { v ->
        hex = v
        if (Regex("^#[0-9a-fA-F]{6}$").matches(v)) onChange(v.uppercase())
    }, max = 7)
}

/** `pickShiftIcon`: the icon catalogue as a grid, "No icon" first. */
@Composable
private fun IconPicker(icon: String?, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("schedule.icon"), modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) {
            Icon(ScheduleIcons.of(icon) ?: Icons.Outlined.Add, null, Modifier.size(18.dp))
            Text(t("schedule.chooseIcon"), Modifier.padding(start = 6.dp))
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(t("iconPicker.title")) },
            text = {
                Column {
                    TextButton(onClick = { open = false; onChange(null) }) { Text(t("iconPicker.clear")) }
                    LazyVerticalGrid(GridCells.Adaptive(48.dp), modifier = Modifier.height(320.dp)) {
                        items(ScheduleIcons.catalog, key = { it.first }) { (name, vector) ->
                            IconButton(onClick = { open = false; onChange(name) }) {
                                Icon(vector, name, tint = if (name == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        )
    }
}

/** `shiftPresetOptions`: "Custom", then one group per visible template and the shared ones. */
@Composable
private fun PresetPicker(hidden: List<String>, onPick: (ScheduleRules.Preset) -> Unit) {
    val strings = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<ScheduleRules.Preset?>(null) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(t("schedule.preset") + ": " + (chosen?.let { ScheduleRules.presetName(it, strings) } ?: t("schedule.presetCustom")))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("schedule.presetCustom")) }, onClick = { open = false; chosen = null })
            ScheduleRules.presetGroups(hidden).forEach { group ->
                HorizontalDivider()
                DropdownMenuItem(text = { Text(t(group.labelKey), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary) }, onClick = {}, enabled = false)
                group.presets.forEach { p ->
                    DropdownMenuItem(text = { Text(ScheduleRules.presetName(p, strings)) }, onClick = { open = false; chosen = p; onPick(p) })
                }
            }
        }
    }
}

// ── Shift type (SCHED-R15 to R18) ──

/** `openScheduleCreateModal('shifts')` and `openShiftTypeEditModal`: one dialog, one Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShiftTypeSheet(type: ShiftType?, s: ScheduleViewModel.UiState, vm: ScheduleViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    var form by remember(type) { mutableStateOf(type?.let { ShiftTypeForm.of(it, s.data.customFields.isNotEmpty()) } ?: ShiftTypeForm()) }
    var tried by remember { mutableStateOf(false) }
    // "What Remove took, Add brings back" (#1775): the overlay switch of a field removed in this dialog.
    val removedOverlay = remember { mutableStateMapOf<Int, Boolean>() }
    val errors = if (tried) ScheduleChecks.shiftType(form) else emptyMap()
    FormSheet(
        title = t(if (type == null) "schedule.createShiftType" else "schedule.editShiftType"),
        onDismiss = close,
        primary = t(if (type == null) "common.add" else "schedule.save"),
        busy = s.busy,
        onPrimary = {
            tried = true
            if (ScheduleChecks.shiftType(form).isEmpty()) vm.saveShiftType(type, form, close)
        },
    ) {
        if (type == null) {
            PresetPicker(s.data.household.hiddenTemplates) { p ->
                form = form.withPreset(p, ScheduleRules.presetName(p, strings), ScheduleRules.presetShortCode(p, strings))
            }
        }
        TextInput(t("schedule.name") + " *", form.name, { form = form.copy(name = it) }, max = 200, error = errorText(errors["name"]))
        TextInput(t("schedule.shortCode"), form.shortCode, { form = form.copy(shortCode = it) }, max = 12)
        ColorPicker(form.color) { form = form.copy(color = it) }
        IconPicker(form.icon) { form = form.copy(icon = it) }
        TimeField(t("schedule.startTime"), form.start, { form = form.copy(start = it) }, error = errorText(errors["start_time"]))
        TimeField(t("schedule.endTime"), form.end, { form = form.copy(end = it) }, error = errorText(errors["end_time"]))
        val attached = form.fields
        if (type != null && attached != null) {
            var open by remember { mutableStateOf(attached.isNotEmpty()) }
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(t("schedule.attachedFields"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (open) {
                if (attached.isEmpty()) Text(t("schedule.noFieldsAttached"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                attached.forEachIndexed { i, f ->
                    val name = s.data.customFields.firstOrNull { it.id == f.customFieldId }?.name.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(f.showInOverlay, onCheckedChange = { on ->
                                    form = form.copy(fields = attached.toMutableList().also { it[i] = f.copy(showInOverlay = on) })
                                })
                                Text(t("schedule.showInOverlay"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        IconButton(onClick = {
                            if (i > 0) form = form.copy(fields = attached.toMutableList().also { it.add(i - 1, it.removeAt(i)) })
                        }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, t("schedule.moveUp")) }
                        IconButton(onClick = {
                            if (i < attached.lastIndex) form = form.copy(fields = attached.toMutableList().also { it.add(i + 1, it.removeAt(i)) })
                        }, enabled = i < attached.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, t("schedule.moveDown")) }
                        IconButton(onClick = {
                            removedOverlay[f.customFieldId] = f.showInOverlay
                            form = form.copy(fields = attached - f)
                        }) { Icon(Icons.Outlined.Close, t("common.removeNamed", "name" to name)) }
                    }
                }
                val available = ScheduleRules.availableTypeFields(s.data.customFields, attached.map { it.customFieldId })
                if (available.isNotEmpty()) {
                    var pick by remember(available) { mutableStateOf(available.first().id) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectField(t("schedule.fieldPickerLabel"), available.map { it.id to it.name }, pick, { pick = it }, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            form = form.copy(fields = attached + ScheduleBodies.AttachedField(pick, removedOverlay[pick] ?: false))
                        }) { Text(t("common.add")) }
                    }
                }
            }
        }
    }
}

/** `openCustomFieldModal`: one name. */
@Composable
internal fun CustomFieldDialog(field: CustomField?, busy: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(field?.name.orEmpty()) }
    var tried by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (field == null) "schedule.createCustomField" else "schedule.editCustomField")) },
        text = { TextInput(t("schedule.fieldName") + " *", name, { name = it }, max = 100, error = if (tried && name.isBlank()) t("common.required") else null) },
        confirmButton = {
            TextButton(onClick = { tried = true; if (name.isNotBlank()) onSave(name) }, enabled = !busy) {
                Text(t(if (field == null) "common.add" else "schedule.save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

// ── Plans, overrides and extras (SCHED-R21 to R29) ──

private data class Ask(val title: String, val detail: String, val action: String, val run: () -> Unit)

@Composable
private fun AskDialog(ask: Ask?, clear: () -> Unit) {
    if (ask != null) ConfirmDialog(ask.title, ask.detail, ask.action, danger = false, onConfirm = ask.run, onDismiss = clear)
}

/** `patternFields`: the window is the rare setting, open once a bound is set. */
@Composable
private fun PatternFields(form: PatternForm, onChange: (PatternForm) -> Unit, errors: Map<String, String>, enabled: Boolean, onLength: (String) -> Unit = { onChange(form.copy(cycleLength = it)) }) {
    val strings = LocalStrings.current
    TextInput(t("schedule.name") + " *", form.name, { onChange(form.copy(name = it)) }, max = 200, error = errorText(errors["name"]), enabled = enabled)
    DateField(t("schedule.anchorDate") + " *", form.anchor, { d -> if (d != null) onChange(form.copy(anchor = d)) }, enabled = enabled)
    TextInput(
        t("schedule.cycleLength") + " *", form.cycleLength, { onLength(it.filter(Char::isDigit)) }, max = 3, number = true, enabled = enabled,
        error = errors["cycle_length"]?.let { key -> strings.t(key, "min" to 1, "max" to ScheduleRules.MAX_CYCLE_LENGTH) },
    )
    SwitchRow(t("schedule.active"), form.active, { onChange(form.copy(active = it)) }, enabled = enabled)
    var advanced by remember { mutableStateOf(form.validFrom != null || form.validUntil != null) }
    Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
        Text("${t("schedule.validFrom")}, ${t("schedule.validUntil")}", style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
    if (advanced) {
        DateField(t("schedule.validFrom"), form.validFrom, { onChange(form.copy(validFrom = it)) }, enabled = enabled, clearable = true)
        DateField(t("schedule.validUntil"), form.validUntil, { onChange(form.copy(validUntil = it)) }, enabled = enabled, clearable = true, error = errorText(errors["valid_until"]))
    }
}

/** `openScheduleCreateModal('patterns')`: one dialog for a plan, an override and an extra shift. */
@Composable
internal fun CreateEntrySheet(
    initial: EntryMode,
    s: ScheduleViewModel.UiState,
    vm: ScheduleViewModel,
    viewer: ScheduleRules.Viewer,
    text: ScheduleText,
    close: () -> Unit,
) {
    val strings = LocalStrings.current
    val data = s.data
    val owners = ScheduleRules.ownerOptions(data, viewer)
    var mode by remember { mutableStateOf(initial) }
    var owner by remember { mutableStateOf(owners.firstOrNull { it.id == viewer.me }?.id ?: owners.firstOrNull()?.id) }
    var pattern by remember { mutableStateOf(PatternForm(anchor = s.today)) }
    var occ by remember { mutableStateOf(OccurrenceForm(from = s.today, to = s.today)) }
    var replaceType by remember { mutableStateOf<Int?>(null) }
    var addType by remember { mutableStateOf(data.types.firstOrNull()?.id) }
    var replaceValues by remember { mutableStateOf(emptyMap<String, String>()) }
    var addValues by remember { mutableStateOf(emptyMap<String, String>()) }
    var tried by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf<Ask?>(null) }

    val patternErrors = if (tried && mode == EntryMode.PATTERN) ScheduleChecks.pattern(pattern.copy(userId = owner)) else emptyMap()
    val occForm = occ.copy(userId = owner, shiftTypeId = if (mode == EntryMode.ADD) addType else replaceType)
    val occErrors = if (tried && mode != EntryMode.PATTERN) ScheduleChecks.occurrence(occForm, needsType = mode == EntryMode.ADD) else emptyMap()

    FormSheet(t("schedule.addEntry"), close, t("common.add"), s.busy, onPrimary = {
        tried = true
        when (mode) {
            EntryMode.PATTERN -> {
                val form = pattern.copy(userId = owner)
                if (ScheduleChecks.pattern(form).isNotEmpty()) return@FormSheet
                val overlap = vm.overlapFor(owner!!, form)
                if (overlap != null) {
                    question = Ask(
                        strings.t("schedule.patternOverlapConfirmTitle", "user" to ScheduleRules.userName(data.users, owner!!)),
                        strings.t("schedule.patternOverlapConfirmDetail", "name" to overlap.name),
                        strings.t("schedule.patternOverlapConfirmAction"),
                    ) { vm.createPattern(form, close) }
                } else {
                    vm.createPattern(form, close)
                }
            }
            EntryMode.REPLACE -> {
                val type = ScheduleRules.typeById(data.types, replaceType)
                val form = occForm.copy(fieldValues = valuesFor(type, replaceValues))
                if (ScheduleChecks.occurrence(form, needsType = false).isNotEmpty()) return@FormSheet
                if (form.singleDay) {
                    vm.createOverride(form, close)
                } else {
                    val label = type?.let(ScheduleRules::label) ?: strings.t("schedule.freeDay")
                    question = Ask(
                        strings.t("schedule.fillRangeConfirmTitle"),
                        strings.t("schedule.fillRangeConfirmDetail", "from" to text.day(form.from), "to" to text.day(form.to), "type" to label),
                        strings.t("schedule.fillRange"),
                    ) { vm.createOverride(form, close) }
                }
            }
            EntryMode.ADD -> {
                if (data.types.isEmpty()) return@FormSheet
                val type = ScheduleRules.typeById(data.types, addType)
                val form = occForm.copy(fieldValues = valuesFor(type, addValues))
                if (ScheduleChecks.occurrence(form, needsType = true).isEmpty()) vm.createExtra(form, close)
            }
        }
    }) {
        SelectField(t("schedule.owner") + " *", owners.map { it.id as Int? to it.displayName.ifEmpty { it.username } }, owner, { owner = it }, error = errorText(patternErrors["user_id"] ?: occErrors["user_id"]))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EntryMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, EntryMode.entries.size)) { Text(t(m.labelKey)) }
            }
        }
        if (mode == EntryMode.PATTERN) {
            PatternFields(pattern, { pattern = it }, patternErrors, enabled = true)
        } else {
            DateField(t("schedule.rangeFrom"), occ.from, { d -> if (d != null) occ = occ.withFrom(d) })
            DateField(t("schedule.rangeTo"), occ.to, { d -> if (d != null) occ = occ.copy(to = d) }, error = errorText(occErrors["to"]))
            TextInput(t("schedule.note"), occ.note, { occ = occ.copy(note = it) }, max = 5000)
        }
        if (mode == EntryMode.REPLACE) {
            TypeSelect(data.types, replaceType, includeFree = true, onSelect = { replaceType = it })
            FieldValues(ScheduleRules.typeById(data.types, replaceType), replaceValues, { replaceValues = it })
        }
        if (mode == EntryMode.ADD) {
            if (data.types.isEmpty()) {
                // S-02/S-07: without a shift type the hint replaces the empty choice, and saving names it.
                Text(t("schedule.noShiftTypesHint"), color = if (tried) Yuvomi.colors.danger else Yuvomi.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                TypeSelect(data.types, addType, includeFree = false, onSelect = { addType = it }, error = errorText(occErrors["shift_type_id"]))
            }
            ReminderField(occ.reminderOn, occ.reminderMinutes) { on, m -> occ = occ.copy(reminderOn = on, reminderMinutes = m) }
            FieldValues(ScheduleRules.typeById(data.types, addType), addValues, { addValues = it })
        }
    }
    AskDialog(question) { question = null }
}

/** `openPatternEditModal`: the plan's fields and its cycle days behind one Save; read-only without the right. */
@Composable
internal fun PatternSheet(
    pattern: Pattern,
    s: ScheduleViewModel.UiState,
    vm: ScheduleViewModel,
    viewer: ScheduleRules.Viewer,
    text: ScheduleText,
    close: () -> Unit,
) {
    val strings = LocalStrings.current
    val data = s.data
    val writable = viewer.canWrite(pattern.userId) && !s.offline
    var form by remember(pattern.id) { mutableStateOf(PatternForm.of(pattern, s.today)) }
    var seq by remember { mutableStateOf(0L) }
    val rows = remember(pattern.id) {
        mutableStateListOf<DayRowForm>().apply {
            val saved = pattern.days.groupBy { it.position }
            for (p in 0 until maxOf(pattern.cycleLength, (pattern.days.maxOfOrNull { it.position } ?: -1) + 1)) {
                val list = saved[p]
                if (list.isNullOrEmpty()) add(DayRowForm(seq++, p, null)) else list.forEach { add(DayRowForm(seq++, p, it.shiftTypeId, it.fieldValues)) }
            }
        }
    }
    // `syncPatternDayGroups`: positions beyond the typed length are hidden, not removed.
    var created by remember(pattern.id) { mutableStateOf(maxOf(pattern.cycleLength, (pattern.days.maxOfOrNull { it.position } ?: -1) + 1)) }
    var tried by remember { mutableStateOf(false) }
    var lengthError by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf<Ask?>(null) }
    val errors = if (tried) ScheduleChecks.pattern(form) else emptyMap()
    val length = form.length ?: pattern.cycleLength

    FormSheet(
        title = if (writable) t("schedule.editPattern") else pattern.name,
        onDismiss = close,
        primary = if (writable) t("schedule.save") else null,
        busy = s.busy,
        dismissLabel = t(if (writable) "common.cancel" else "common.close"),
        onPrimary = {
            tried = true
            if (ScheduleChecks.pattern(form).isNotEmpty()) return@FormSheet
            val n = form.length ?: return@FormSheet
            val conflict = ScheduleChecks.cycleConflict(rows.filter { it.position < n }, n)
            if (conflict != null) {
                lengthError = strings.t("schedule.cycleLengthTooShort", "from" to conflict.first, "to" to conflict.second)
                return@FormSheet
            }
            val save = { vm.savePattern(pattern, form, rows.toList(), close) }
            val overlap = if (form.active && !pattern.isActive) vm.overlapFor(pattern.userId, form, pattern.id) else null
            if (overlap != null) {
                question = Ask(
                    strings.t("schedule.patternOverlapConfirmTitle", "user" to ScheduleRules.userName(data.users, pattern.userId)),
                    strings.t("schedule.patternOverlapConfirmDetail", "name" to overlap.name),
                    strings.t("schedule.patternOverlapConfirmAction"),
                ) { save() }
            } else {
                save()
            }
        },
    ) {
        TextInput(t("schedule.owner"), ScheduleRules.userName(data.users, pattern.userId), {}, max = 200, enabled = false)
        PatternFields(form, { form = it; lengthError = null }, errors, enabled = writable, onLength = { v ->
            form = form.copy(cycleLength = v)
            lengthError = null
            val n = v.toIntOrNull()
            if (n != null && n in 1..ScheduleRules.MAX_CYCLE_LENGTH && n > created) {
                for (p in created until n) rows += DayRowForm(seq++, p, null)
                created = n
            }
        })
        if (lengthError != null) Text(lengthError!!, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(t("schedule.cycleDays"), style = MaterialTheme.typography.titleSmall)
        Text(t("schedule.cycleDaysHint", "count" to length), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        for (position in 0 until length) {
            // S-04: the head names the next real date of this position.
            val date = ScheduleRules.cycleDayDate(form.anchor, length, form.validFrom, form.validUntil, position + 1, s.today)
            Text("${position + 1} · ${dayShort(date)} ${text.day(date)}", style = MaterialTheme.typography.labelLarge)
            rows.filter { it.position == position }.forEach { row ->
                val type = ScheduleRules.typeById(data.types, row.shiftTypeId)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeSelect(data.types, row.shiftTypeId, includeFree = true, enabled = writable, onSelect = { id ->
                        val i = rows.indexOfFirst { it.key == row.key }
                        if (i >= 0) rows[i] = row.copy(shiftTypeId = id, fieldValues = emptyMap())
                    }, label = t("schedule.shiftType"))
                    if (writable) {
                        IconButton(onClick = { rows.removeAll { it.key == row.key } }) {
                            Icon(Icons.Outlined.Close, t("schedule.removeCycleDayShift", "day" to position + 1))
                        }
                    }
                }
                FieldValues(type, row.fieldValues, { v ->
                    val i = rows.indexOfFirst { it.key == row.key }
                    if (i >= 0) rows[i] = rows[i].copy(fieldValues = v)
                }, enabled = writable)
            }
            if (writable) TextButton(onClick = { rows += DayRowForm(seq++, position, null) }) { Text(t("common.add")) }
        }
    }
    AskDialog(question) { question = null }
}

/** `openOverrideEditModal`: the range stays editable; saving fills it and trims what fell out. */
@Composable
internal fun OverrideSheet(
    group: ScheduleRules.OverrideGroup,
    s: ScheduleViewModel.UiState,
    vm: ScheduleViewModel,
    text: ScheduleText,
    close: () -> Unit,
) {
    val strings = LocalStrings.current
    val data = s.data
    var form by remember(group.key) { mutableStateOf(OccurrenceForm.ofOverride(group)) }
    var tried by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf<Ask?>(null) }
    val errors = if (tried) ScheduleChecks.occurrence(form, needsType = false) else emptyMap()
    FormSheet(t("schedule.editOverride"), close, t("schedule.save"), s.busy, onPrimary = {
        tried = true
        val type = ScheduleRules.typeById(data.types, form.shiftTypeId)
        val f = form.copy(fieldValues = valuesFor(type, form.fieldValues))
        if (ScheduleChecks.occurrence(f, needsType = false).isNotEmpty()) return@FormSheet
        val label = type?.let(ScheduleRules::label) ?: strings.t("schedule.freeDay")
        question = Ask(
            strings.t("schedule.fillRangeConfirmTitle"),
            strings.t("schedule.fillRangeConfirmDetail", "from" to text.day(f.from), "to" to text.day(f.to), "type" to label),
            strings.t("schedule.save"),
        ) { vm.saveOverrideGroup(group, f, close) }
    }) {
        TextInput(t("schedule.owner"), ScheduleRules.userName(data.users, group.userId), {}, max = 200, enabled = false)
        DateField(t("schedule.rangeFrom"), form.from, { d -> if (d != null) form = form.withFrom(d) })
        DateField(t("schedule.rangeTo"), form.to, { d -> if (d != null) form = form.copy(to = d) }, error = errorText(errors["to"]))
        TypeSelect(data.types, form.shiftTypeId, includeFree = true, onSelect = { form = form.copy(shiftTypeId = it) })
        TextInput(t("schedule.note"), form.note, { form = form.copy(note = it) }, max = 5000)
        FieldValues(ScheduleRules.typeById(data.types, form.shiftTypeId), form.fieldValues, { form = form.copy(fieldValues = it) })
    }
    AskDialog(question) { question = null }
}

/** `openExtraGroupEditModal`: new rows for the range, then the old ones go. */
@Composable
internal fun ExtraSheet(group: ScheduleRules.ExtraGroup, s: ScheduleViewModel.UiState, vm: ScheduleViewModel, close: () -> Unit) {
    val data = s.data
    var form by remember(group.key) { mutableStateOf(OccurrenceForm.ofExtra(group)) }
    var tried by remember { mutableStateOf(false) }
    val errors = if (tried) ScheduleChecks.occurrence(form, needsType = true) else emptyMap()
    FormSheet(t("schedule.editExtraShift"), close, t("schedule.save"), s.busy, onPrimary = {
        tried = true
        val type = ScheduleRules.typeById(data.types, form.shiftTypeId)
        val f = form.copy(fieldValues = valuesFor(type, form.fieldValues))
        if (ScheduleChecks.occurrence(f, needsType = true).isEmpty()) vm.saveExtraGroup(group, f, close)
    }) {
        TextInput(t("schedule.owner"), ScheduleRules.userName(data.users, group.userId), {}, max = 200, enabled = false)
        DateField(t("schedule.rangeFrom"), form.from, { d -> if (d != null) form = form.withFrom(d) })
        DateField(t("schedule.rangeTo"), form.to, { d -> if (d != null) form = form.copy(to = d) }, error = errorText(errors["to"]))
        TypeSelect(data.types, form.shiftTypeId, includeFree = false, onSelect = { form = form.copy(shiftTypeId = it) }, error = errorText(errors["shift_type_id"]))
        TextInput(t("schedule.note"), form.note, { form = form.copy(note = it) }, max = 5000)
        ReminderField(form.reminderOn, form.reminderMinutes) { on, m -> form = form.copy(reminderOn = on, reminderMinutes = m) }
        FieldValues(ScheduleRules.typeById(data.types, form.shiftTypeId), form.fieldValues, { form = form.copy(fieldValues = it) })
    }
}

/** `renderScheduleEntryDetailContent`: one resolved entry, read-only, every field with a value. */
@Composable
internal fun EntryDetailDialog(entry: Entry, data: ScheduleData, text: ScheduleText, close: () -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val type = entry.shiftType
    val origin = when (entry.source) {
        Entry.SOURCE_PATTERN -> data.patterns.firstOrNull { it.id == entry.patternId }?.let { "${strings.t("schedule.pattern")} · ${it.name}" } ?: strings.t("schedule.pattern")
        Entry.SOURCE_OVERRIDE -> strings.t("schedule.override")
        else -> strings.t("schedule.extraBadgeLabel")
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("schedule.entryDetailTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeLeading(type)
                    Text(type?.let(ScheduleRules::label) ?: t("schedule.freeDay"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                }
                if (type != null) Text(ScheduleRules.clockLabel(type, strings, shell.formats::time), color = Yuvomi.colors.textSecondary)
                DetailRow(t("schedule.owner"), ScheduleRules.userName(data.users, entry.userId) + " · " + text.day(entry.dateKey))
                entry.note?.takeIf { it.isNotEmpty() }?.let { DetailRow(t("schedule.note"), it) }
                type?.fields.orEmpty().sortedBy { it.position }.forEach { f ->
                    entry.fieldValues[f.id.toString()]?.takeIf { it.isNotEmpty() }?.let { DetailRow(f.name, it) }
                }
                DetailRow(t("schedule.origin"), origin)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(t("common.close")) } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Text(value)
    }
}

// ── Feed (SCHED-R35, R36) ──

/**
 * The member's own schedule feed (`personal-feeds.js`, part `schedule`): a switch, and while it
 * is on the address with Copy, Subscribe and New link.
 */
@Composable
internal fun FeedSheet(feed: ScheduleViewModel.FeedState, vm: ScheduleViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf<Ask?>(null) }
    var danger by remember { mutableStateOf(false) }
    FormSheet(t("settings.scheduleFeedTitle"), close, primary = null, busy = feed.busy, onPrimary = {}, dismissLabel = t("common.close")) {
        when {
            feed.loading -> CircularProgressIndicator()
            feed.failed -> {
                Text(t("common.errorGeneric"))
                TextButton(onClick = vm::loadFeed) { Text(t("common.retry")) }
            }
            else -> {
                val current = feed.feed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("settings.scheduleFeedDescription"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(current != null, enabled = !feed.busy, onCheckedChange = { on ->
                        if (on) {
                            vm.setFeed(true)
                        } else {
                            danger = true
                            question = Ask(strings.t("settings.scheduleFeedDisableConfirm"), strings.t("settings.scheduleFeedDisableConfirmDetail"), strings.t("settings.scheduleFeedDisable")) {
                                vm.setFeed(false)
                            }
                        }
                    })
                }
                if (current != null) {
                    Text(t("settings.scheduleFeedUrlLabel"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
                    SelectionContainer {
                        Text(
                            current.url,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                        )
                    }
                    Text(t("settings.scheduleFeedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                ?.setPrimaryClip(ClipData.newPlainText(strings.t("settings.scheduleFeedUrlLabel"), current.url))
                            scope.launch { shell.notify(strings.t("settings.scheduleFeedCopied")) }
                        }) { Text(t("settings.scheduleFeedCopy")) }
                        OutlinedButton(onClick = {
                            val webcal = current.url.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "webcal://")
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webcal)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: ActivityNotFoundException) {
                                scope.launch { shell.notify(strings.t("android.schedule.noCalendarApp")) }
                            }
                        }) { Text(t("settings.scheduleFeedSubscribe")) }
                    }
                    TextButton(onClick = {
                        danger = true
                        question = Ask(strings.t("settings.scheduleFeedRegenerateConfirm"), strings.t("settings.scheduleFeedRegenerateConfirmDetail"), strings.t("settings.scheduleFeedRegenerate")) {
                            vm.setFeed(true, regenerate = true)
                        }
                    }, enabled = !feed.busy) { Text(t("settings.scheduleFeedRegenerate")) }
                }
            }
        }
    }
    question?.let { q -> ConfirmDialog(q.title, q.detail, q.action, danger = danger, onConfirm = q.run, onDismiss = { question = null }) }
}
