package io.github.marcomow.yuvomi.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** docs/spec/contacts.md, the form (`buildContactForm`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorScreen(vm: ContactEditorViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    LaunchedEffect(s.done) { if (s.done) close() }
    val d = s.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (s.isEdit || (s.loading && s.original != null)) "contacts.editContact" else "contacts.newContact")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    TextButton(onClick = vm::save, enabled = !s.saving && !s.loading && s.error == null) {
                        Text(if (s.saving) "…" else t(if (s.isEdit) "common.save" else "common.add"))
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.error != null -> ErrorBox(s.error!!, onRetry = vm::load)
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(t("contacts.nameGroupLabel") + " *", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = d.firstName,
                        onValueChange = { vm.editName(first = it) },
                        label = { Text(t("contacts.firstNameLabel")) },
                        placeholder = { Text(t("contacts.firstNamePlaceholder")) },
                        isError = s.nameError,
                        supportingText = if (s.nameError) ({ Text(t("contacts.nameRequiredHint")) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = d.lastName,
                        onValueChange = { vm.editName(last = it) },
                        label = { Text(t("contacts.lastNameLabel")) },
                        placeholder = { Text(t("contacts.lastNamePlaceholder")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val options = buildList {
                        s.orphanCategory?.let { add(it to it) }
                        s.categories.forEach { add(it.key to ContactsRules.categoryLabel(it, strings)) }
                    }
                    Picker(
                        label = t("contacts.categoryLabel"),
                        value = options.firstOrNull { it.first == d.category }?.second ?: d.category,
                        options = options,
                    ) { key -> vm.edit { it.copy(category = key) } }

                    ValueList(
                        title = t("contacts.phoneLabel"),
                        rows = d.phones,
                        keyboard = KeyboardType.Phone,
                        placeholder = t("contacts.phonePlaceholder"),
                        addLabel = t("contacts.mvAddPhone"),
                        locked = false,
                        onChange = { rows -> vm.edit { it.copy(phones = rows) } },
                    )
                    ValueList(
                        title = t("contacts.emailLabel"),
                        rows = d.emails,
                        keyboard = KeyboardType.Email,
                        placeholder = t("contacts.emailPlaceholder"),
                        addLabel = t("contacts.mvAddEmail"),
                        locked = vm.emailsLocked,
                        onChange = { rows -> vm.edit { it.copy(emails = rows) } },
                    )

                    // `advancedSection`: open when editing a contact that has an address or notes.
                    var more by remember { mutableStateOf(s.isEdit && (d.address.isNotBlank() || d.notes.isNotBlank())) }
                    TextButton(onClick = { more = !more }) {
                        Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(t("modal.moreDetails"))
                            if (!more) {
                                Text(
                                    listOf(t("contacts.addressLabel"), t("contacts.birthdayLabel"), t("contacts.notesLabel")).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Yuvomi.colors.textSecondary,
                                )
                            }
                        }
                    }
                    if (more) {
                        OutlinedTextField(
                            value = d.address,
                            onValueChange = { v -> vm.edit { it.copy(address = v) } },
                            label = { Text(t("contacts.addressLabel")) },
                            placeholder = { Text(t("contacts.addressPlaceholder")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BirthdayField(d.birth) { b -> vm.edit { it.copy(birth = b) } }
                        OutlinedTextField(
                            value = d.notes,
                            onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                            label = { Text(t("contacts.notesLabel")) },
                            placeholder = { Text(t("contacts.notesPlaceholder")) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    val original = s.original
                    if (original != null && !original.isMember) {
                        OutlinedButton(onClick = vm::delete, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                            Text(t("common.delete"), color = Yuvomi.colors.danger, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

/** The phone or e-mail rows: value and label, a remove on every row but the first, and "add". */
@Composable
private fun ValueList(
    title: String,
    rows: List<Pair<String, String>>,
    keyboard: KeyboardType,
    placeholder: String,
    addLabel: String,
    locked: Boolean,
    onChange: (List<Pair<String, String>>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        rows.forEachIndexed { i, (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> onChange(rows.toMutableList().also { it[i] = label to v }) },
                    placeholder = { Text(placeholder) },
                    readOnly = locked,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    modifier = Modifier.weight(1.6f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { v -> onChange(rows.toMutableList().also { it[i] = v.take(50) to value }) },
                    placeholder = { Text(t("contacts.mvLabel")) },
                    readOnly = locked,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (!locked && i > 0) {
                    IconButton(onClick = { onChange(rows.toMutableList().also { it.removeAt(i) }) }) {
                        Icon(Icons.Outlined.Close, t("common.removeNamed", "name" to title))
                    }
                }
            }
        }
        if (locked) {
            Text(t("contacts.emailLockedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        } else {
            TextButton(onClick = { onChange(rows + ("" to "")) }) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(4.dp))
                Text(addLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) })
            }
        }
    }
}

/** `birthDateFieldHtml`: a date, or month and day with "Year unknown". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayField(value: BirthField, onChange: (BirthField) -> Unit) {
    val strings = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("contacts.birthdayLabel"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (!value.yearUnknown) {
                TextButton(onClick = { open = true }) {
                    Text(value.picker?.let { birthDateText(it, false, strings.locale) } ?: t("datepicker.openCalendar"))
                }
                if (value.picker != null) IconButton(onClick = { onChange(value.copy(picker = null)) }) { Icon(Icons.Outlined.Close, t("datepicker.clear")) }
            }
        }
        if (value.yearUnknown) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    label = t("birthdays.yearlessMonthLabel"),
                    value = value.month?.let { monthName(it, strings.locale) } ?: t("birthdays.yearlessMonthPlaceholder"),
                    options = (1..12).map { it to monthName(it, strings.locale) },
                    modifier = Modifier.weight(1.4f),
                ) { m -> onChange(value.withMonth(m)) }
                Picker(
                    label = t("birthdays.yearlessDayLabel"),
                    value = value.day?.toString() ?: t("birthdays.yearlessDayPlaceholder"),
                    options = value.month?.let { m -> (1..BirthField.daysInMonth(m)).map { it to it.toString() } } ?: emptyList(),
                    modifier = Modifier.weight(1f),
                ) { day -> onChange(value.copy(day = day)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = value.yearUnknown, onCheckedChange = { onChange(value.toggle(it)) })
            Spacer(Modifier.width(8.dp))
            Text(t("birthdays.yearUnknown"))
        }
        Text(t("contacts.birthdayHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
    if (open) {
        val initial = value.picker?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.of(1990, 1, 1)
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { onChange(value.copy(picker = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}
