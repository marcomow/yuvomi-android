package io.github.marcomow.yuvomi.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** docs/spec/tasks.md, TASKS-R12 to R19 and R28, R29. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(
    taskId: Int?,
    parentId: Int?,
    presetCategory: String?,
    presetAssignee: Int?,
    close: () -> Unit,
    deleted: () -> Unit,
) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm = viewModel(key = "editor-$taskId-$parentId") { TaskEditorViewModel(shell.container, taskId, parentId, presetCategory, presetAssignee) }
    val s by vm.state.collectAsState()
    val f = s.form
    val editable = vm.definitionEditable
    val solo = shell.session.soloHousehold
    val me = shell.session.user.id
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(s.saved) {
        if (s.saved) {
            close()
            shell.notify(strings.t(if (taskId == null) "tasks.createdToast" else "tasks.savedToast"))
        }
    }
    LaunchedEffect(s.deleted) {
        if (s.deleted) {
            deleted()
            shell.notify(strings.t("tasks.deletedToast"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            parentId != null -> t("tasks.subtaskAdd")
                            taskId == null -> t("tasks.newTask")
                            else -> t("tasks.editTask")
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    TextButton(onClick = vm::save, enabled = !s.saving && !s.loading) { Text(t("common.save")) }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        if (s.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!editable) {
                Text(t("tasks.lockedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.warning)
            }
            s.errorKey?.let { Text(strings.t(it), color = Yuvomi.colors.danger) }

            OutlinedTextField(
                value = f.title,
                onValueChange = { v -> vm.edit { it.copy(title = v) } },
                label = { Text(t("tasks.titleLabel")) },
                placeholder = { Text(t("tasks.titlePlaceholder")) },
                enabled = editable,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.description,
                onValueChange = { v -> vm.edit { it.copy(description = v) } },
                label = { Text(t("tasks.descriptionLabel")) },
                placeholder = { Text(t("tasks.descriptionPlaceholder")) },
                supportingText = { Text(t("tasks.descriptionMarkdownHint")) },
                enabled = editable,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            // Category
            var categoryOpen by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = categoryOpen, onExpandedChange = { if (editable) categoryOpen = it }) {
                OutlinedTextField(
                    value = categoryLabel(f.category, s.meta.categories, strings),
                    onValueChange = {},
                    readOnly = true,
                    enabled = editable,
                    label = { Text(t("tasks.categoryLabel")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryOpen) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(categoryOpen, onDismissRequest = { categoryOpen = false }) {
                    s.meta.categories.sortedBy { it.sortOrder }.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(categoryLabel(c.key, s.meta.categories, strings)) },
                            onClick = { categoryOpen = false; vm.edit { it.copy(category = c.key) } },
                        )
                    }
                }
            }

            Label(t("tasks.priorityLabel"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Task.PRIORITIES.forEach { p ->
                    FilterChip(f.priority == p, onClick = { vm.edit { it.copy(priority = p) } }, enabled = editable, label = { Text(t(priorityKey(p))) })
                }
            }

            DateField(t("tasks.startDateLabel"), f.startDate, editable) { d -> vm.edit { it.copy(startDate = d) } }
            DateField(t("tasks.dueDateLabel"), f.dueDate, editable) { d -> vm.edit { it.copy(dueDate = d, dueTime = if (d == null) null else it.dueTime) } }
            if (f.dueDate != null) TimeField(t("tasks.dueTimeLabel"), f.dueTime, editable) { tm -> vm.edit { it.copy(dueTime = tm) } }

            // Assignees (TASKS-R14: hidden in a household of one; TASKS-R17: self only when locked)
            if (!solo && s.meta.users.isNotEmpty()) {
                Label(t("tasks.assignedLabel"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.meta.users.forEach { u ->
                        FilterChip(
                            selected = u.id in f.assignees,
                            enabled = editable || u.id == me,
                            onClick = { vm.edit { it.copy(assignees = if (u.id in it.assignees) it.assignees - u.id else it.assignees + u.id) } },
                            leadingIcon = { MemberAvatar(u.displayName, u.avatarColor, u.avatarData, size = 20.dp) },
                            label = { Text(u.displayName) },
                        )
                    }
                }
            }

            TagsField(f.tags, s.meta.tags.map { it.tag }, editable) { tags -> vm.edit { it.copy(tags = tags) } }

            if (parentId == null && s.task?.parentTaskId == null) {
                OutlinedTextField(
                    value = f.points,
                    onValueChange = { v -> vm.edit { it.copy(points = v.filter(Char::isDigit).take(5)) } },
                    label = { Text(t("tasks.pointsLabel")) },
                    placeholder = { if (s.isNew) Text(s.meta.defaultPoints.toString()) },
                    supportingText = { Text(t("tasks.pointsHint")) },
                    enabled = editable,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!solo) {
                Label(t("common.visibility.label"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Task.VISIBILITIES.forEach { v ->
                        FilterChip(f.visibility == v, onClick = { vm.edit { it.copy(visibility = v) } }, enabled = editable,
                            label = { Text(t("common.visibility.$v")) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("tasks.lockedToggle"))
                    Text(t("tasks.lockedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Switch(checked = f.locked, onCheckedChange = { v -> vm.edit { it.copy(locked = v) } }, enabled = editable)
            }

            if (!vm.isSubtask) {
                RecurrenceFields(f, s.task?.recurrenceRule, editable) { p, fc -> vm.edit { it.copy(rule = p, fromCompletion = fc) } }
            }

            if (s.task != null && editable) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                    Spacer(Modifier.width(8.dp))
                    Text(t("common.delete"), color = Yuvomi.colors.danger)
                }
            }
            Button(onClick = vm::save, enabled = !s.saving, modifier = Modifier.fillMaxWidth()) { Text(t("common.save")) }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(t("tasks.deleteConfirm"), s.task?.title, t("common.delete"), onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            vm.delete()
        }
    }
    s.serverMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissServerMessage,
            title = { Text(t("android.tasks.notSaved")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissServerMessage) { Text(t("common.close")) } },
        )
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate?, enabled: Boolean, onChange: (LocalDate?) -> Unit) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(value?.let { shell.formats.date(it, shell.time.today()) } ?: t("android.tasks.noDate"))
        }
        if (value != null && enabled) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (value ?: shell.time.today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(t("common.apply")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: LocalTime?, enabled: Boolean, onChange: (LocalTime?) -> Unit) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = { open = true }, enabled = enabled) { Text(value?.let { shell.formats.time(it) } ?: t("android.tasks.noTime")) }
        if (value != null && enabled) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
    }
    if (open) {
        val initial = value ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false; onChange(LocalTime.of(state.hour, state.minute)) }) { Text(t("common.apply")) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
            text = { TimePicker(state) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(tags: List<String>, suggestions: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var input by remember { mutableStateOf("") }
    fun add(raw: String) {
        val parts = raw.split(',').map { it.trim().take(64) }.filter { it.isNotEmpty() }
        var next = tags
        for (p in parts) if (next.none { it.equals(p, ignoreCase = true) } && next.size < 32) next = next + p
        onChange(next)
        input = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(t("tasks.tagsLabel"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    enabled = enabled,
                    onClick = { onChange(tags - tag) },
                    label = { Text(tag) },
                    trailingIcon = { Icon(Icons.Outlined.Close, t("tasks.tagRemove", "tag" to tag)) },
                )
            }
        }
        if (enabled) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.endsWith(",")) add(it) else input = it },
                placeholder = { Text(t("tasks.tagsPlaceholder")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            val open = suggestions.filter { s -> tags.none { it.equals(s, ignoreCase = true) } && (input.isBlank() || s.contains(input, ignoreCase = true)) }.take(8)
            if (open.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    open.forEach { s -> FilterChip(false, onClick = { add(s) }, label = { Text(s) }) }
                }
            }
        }
    }
}

/** TASKS-R28, TASKS-R29: the PWA's recurrence vocabulary for tasks (no COUNT, as upstream's task form). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceFields(f: TaskEditorViewModel.Form, storedRule: String?, enabled: Boolean, onChange: (RRule.Parts, Boolean) -> Unit) {
    val strings = LocalStrings.current
    val p = f.rule
    Label(t("rrule.labelRepeat"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RRule.FREQS.forEach { freq ->
            FilterChip(p.freq == freq, enabled = enabled, onClick = { onChange(p.copy(freq = freq), f.fromCompletion) },
                label = { Text(RRuleText.freqLabel(freq, strings)) })
        }
    }
    if (p.freq.isEmpty()) {
        Text(t("rrule.intervalHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("rrule.labelEvery"))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = p.interval.toString(),
            onValueChange = { v -> v.filter(Char::isDigit).take(2).toIntOrNull()?.let { onChange(p.copy(interval = it.coerceIn(1, 99)), f.fromCompletion) } },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(RRuleText.unit(p.freq, p.interval, strings))
    }
    if (p.freq == "WEEKLY") {
        Label(t("rrule.labelOnDays"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RRule.WEEKDAYS.forEach { d ->
                FilterChip(d in p.byday, enabled = enabled, onClick = {
                    val days = if (d in p.byday) p.byday - d else RRule.WEEKDAYS.filter { it in p.byday || it == d }
                    onChange(p.copy(byday = days), f.fromCompletion)
                }, label = { Text(RRuleText.dayLabel(d, strings)) })
            }
        }
    }
    if (p.freq == "MONTHLY") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("rrule.lastDayOfMonth"), modifier = Modifier.weight(1f))
            Switch(p.lastDay, onCheckedChange = { onChange(p.copy(lastDay = it), f.fromCompletion) }, enabled = enabled)
        }
        if (p.lastDay) Text(t("rrule.lastDayOfMonthHintNext"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
    Label(t("rrule.labelEnds"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(p.until.isEmpty(), enabled = enabled, onClick = { onChange(p.copy(until = "", count = null), f.fromCompletion) }, label = { Text(t("rrule.endNever")) })
        FilterChip(p.until.isNotEmpty(), enabled = enabled, onClick = {
            if (p.until.isEmpty()) onChange(p.copy(until = LocalDate.now().plusMonths(3).toString(), count = null), f.fromCompletion)
        }, label = { Text(t("rrule.endOnDate")) })
    }
    if (p.until.isNotEmpty()) {
        DateField(t("rrule.labelUntil"), runCatching { LocalDate.parse(p.until) }.getOrNull(), enabled) { d ->
            onChange(p.copy(until = d?.toString().orEmpty()), f.fromCompletion)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t("rrule.fromCompletionLabel"))
            Text(t("rrule.fromCompletionHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Switch(f.fromCompletion, onCheckedChange = { onChange(p, it) }, enabled = enabled)
    }
    if (RRule.hasForeignParts(storedRule)) {
        Text(t("android.tasks.ruleKept"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.warning)
    }
}
