package io.github.marcomow.yuvomi.feature.birthdays

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

/** A labelled choice from a short list (the web page's `<select>`). */
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T?, text: (T) -> String, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.let(text).orEmpty(), Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = { open = false; onSelect(option) }) }
            }
        }
    }
}

/** docs/spec/birthdays.md, BDAY-R11 to R13: the editor as its own screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayEditorScreen(vm: BirthdayEditorViewModel, format: BirthdaysFormat, close: () -> Unit, saved: (Birthday) -> Unit, gone: (Int) -> Unit, delete: (Int) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.state.collectAsState()
    val f = s.form
    var message by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }

    LaunchedEffect(s.saved) { s.saved?.let { saved(it); close() } }
    LaunchedEffect(s.gone) { if (s.gone) vm.birthday?.let { gone(it.id); close() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            when (val r = BirthdayPhoto.encode(context, uri)) {
                is BirthdayPhoto.Result.Ok -> vm.edit { it.copy(photoData = r.dataUrl) }
                is BirthdayPhoto.Result.Problem -> scope.launch { shell.notify(strings.t(r.key)) }
            }
        }
    }

    val submit: () -> Unit = {
        scope.launch {
            message = vm.save()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (vm.isEdit) "birthdays.editTitle" else "birthdays.newTitle")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    TextButton(onClick = submit, enabled = !s.saving) { Text(t(if (vm.isEdit) "common.save" else "common.add")) }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The picture: the disc is the choice (`.media-picker`); "Remove picture" only with one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(88.dp).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (f.photoData == null && f.name.isBlank()) {
                        Icon(Icons.Outlined.CameraAlt, t("birthdays.photoLabel"), Modifier.size(36.dp), tint = Yuvomi.colors.textSecondary)
                    } else {
                        MemberAvatar(f.name.ifBlank { "?" }, BirthdaysRules.NEUTRAL, f.photoData, size = 88.dp)
                    }
                }
                Column(Modifier.padding(start = 16.dp)) {
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(t("birthdays.photoLabel")) }
                    if (f.photoData != null) TextButton(onClick = { vm.edit { it.copy(photoData = null) } }) { Text(t("birthdays.removePhoto")) }
                }
            }
            val fieldError = { field: String -> s.error?.takeIf { it.first == field }?.second }
            OutlinedTextField(
                f.name, { v -> vm.edit { it.copy(name = v.take(200)) } }, Modifier.fillMaxWidth(),
                label = { Text("${t("birthdays.nameLabel")} *") }, singleLine = true,
                isError = fieldError("name") != null, supportingText = fieldError("name")?.let { { Text(it) } },
            )
            // Birth date, or day and month without a year (#2095).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("birthdays.yearUnknown"), Modifier.weight(1f))
                Switch(f.yearUnknown, { on -> vm.edit { it.copy(yearUnknown = on, month = it.month ?: it.birthDate?.monthValue, day = it.day ?: it.birthDate?.dayOfMonth) } })
            }
            if (!f.yearUnknown) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${t("birthdays.birthDateLabel")} *", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(f.birthDate?.let(format::fullDate) ?: t("birthdays.birthDateLabel"), Modifier.weight(1f))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(t("birthdays.yearlessMonthLabel"), (1..12).toList(), f.month, format::monthName, Modifier.weight(1f)) { m ->
                        vm.edit { it.copy(month = m, day = it.day?.takeIf { d -> d <= BirthdaysRules.daysInMonth(m) }) }
                    }
                    Choice(t("birthdays.yearlessDayLabel"), (1..BirthdaysRules.daysInMonth(f.month)).toList(), f.day, { it.toString() }, Modifier.weight(1f), enabled = f.month != null) { d ->
                        vm.edit { it.copy(day = d) }
                    }
                }
            }
            fieldError("date")?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall) }
            Text(t("birthdays.calendarHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)

            HorizontalDivider()
            // Link to a contact (D7): the linked one, every contact without a birthday.
            val linkable = s.linkable
            if (vm.mayLink && (vm.birthday?.contactId != null || !linkable.isNullOrEmpty())) {
                val ids = listOf("") + (linkable?.map { it.id.toString() } ?: listOfNotNull(vm.birthday?.contactId?.toString()))
                val label = { id: String ->
                    if (id.isEmpty()) strings.t("birthdays.contactLinkNone")
                    else linkable?.firstOrNull { it.id.toString() == id }?.name ?: vm.birthday?.contactName ?: strings.t("birthdays.contactSuggestLabel")
                }
                Choice(t("birthdays.contactLinkLabel"), ids, f.contactId, label) { id -> vm.edit { it.copy(contactId = id) } }
                Text(t("birthdays.contactLinkHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            // Name day: month and day, cleared with one tap.
            Text(t("birthdays.nameDayLabel"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Choice(t("birthdays.nameDayMonthLabel"), (1..12).toList(), f.nameDayMonth, format::monthName, Modifier.weight(1f)) { m ->
                    vm.edit { it.copy(nameDayMonth = m, nameDayDay = it.nameDayDay?.takeIf { d -> d <= BirthdaysRules.daysInMonth(m) }) }
                }
                Choice(t("birthdays.nameDayDayLabel"), (1..BirthdaysRules.daysInMonth(f.nameDayMonth)).toList(), f.nameDayDay, { it.toString() }, Modifier.weight(1f), enabled = f.nameDayMonth != null) { d ->
                    vm.edit { it.copy(nameDayDay = d) }
                }
                IconButton(onClick = { vm.edit { it.copy(nameDayMonth = null, nameDayDay = null) } }) { Icon(Icons.Outlined.Close, t("birthdays.nameDayClear")) }
            }
            Text(fieldError("nameDay") ?: t("birthdays.nameDayHint"), style = MaterialTheme.typography.bodySmall,
                color = if (fieldError("nameDay") != null) Yuvomi.colors.danger else Yuvomi.colors.textSecondary)
            OutlinedTextField(
                f.notes, { v -> vm.edit { it.copy(notes = v) } }, Modifier.fillMaxWidth(),
                label = { Text(t("birthdays.notesLabel")) }, placeholder = { Text(t("birthdays.notesPlaceholder")) }, minLines = 3,
            )
            // Reminder: presets, a stored value no preset knows, or a custom amount and unit.
            val options = BirthdaysRules.reminderOptions(vm.birthday, strings, format)
            Choice(t("reminders.offsetLabel"), options, options.firstOrNull { it.value == f.reminder.offset }, { it.label }) { o ->
                vm.edit { it.copy(reminder = it.reminder.copy(offset = o.value)) }
            }
            if (f.reminder.offset == "custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        f.reminder.amount, { v -> vm.edit { it.copy(reminder = it.reminder.copy(amount = v.filter(Char::isDigit).take(4))) } },
                        Modifier.weight(1f), label = { Text(t("reminders.customAmountLabel")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = fieldError("reminder") != null,
                    )
                    val units = BirthdaysRules.REMINDER_UNITS
                    val unitLabel = { u: String ->
                        strings.t(when (u) { "minutes" -> "reminders.customMinutes"; "hours" -> "reminders.customHours"; "weeks" -> "reminders.customWeeks"; else -> "reminders.customDays" })
                    }
                    Choice(t("reminders.customUnitLabel"), units, f.reminder.unit, unitLabel, Modifier.weight(1f)) { u ->
                        vm.edit { it.copy(reminder = it.reminder.copy(unit = u)) }
                    }
                }
                fieldError("reminder")?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall) }
            }
            message?.takeIf { s.error == null }?.let { Text(it, color = Yuvomi.colors.danger) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val b = vm.birthday
                if (b != null) TextButton(onClick = { close(); delete(b.id) }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Button(onClick = submit, enabled = !s.saving) { Text(t(if (vm.isEdit) "common.save" else "common.add")) }
            }
        }
    }

    if (pickDate) {
        val today = shell.time.today()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = f.birthDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.edit { it.copy(birthDate = d) }
                    }
                    pickDate = false
                }) { Text(t("common.save")) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}
