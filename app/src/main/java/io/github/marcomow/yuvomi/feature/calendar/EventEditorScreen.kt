package io.github.marcomow.yuvomi.feature.calendar

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.tasks.RRule
import io.github.marcomow.yuvomi.feature.tasks.RRuleText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** docs/spec/calendar.md, the event form (CAL-R15 to R19). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = rememberCalendarStore() ?: return
    val target = remember { store.editing ?: CalendarStore.EditTarget(date = shell.time.today()) }
    val vm = viewModel(key = "calendar-editor-${target.event?.occurrenceKey ?: "new"}") {
        EventEditorViewModel(store, target, shell.session.user.id) { shell.time.now().toLocalDateTime() }
    }
    val s by vm.state.collectAsState()
    val cs by store.state.collectAsState()
    val f = s.form
    val today = shell.time.today()
    val text = CalendarText(strings, store.dates, today)
    var discard by remember { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(s.isEdit && (f.description.isNotBlank() || f.visibility != "all" || f.countdown || target.event?.hasAttachment == true)) }
    var iconPicker by remember { mutableStateOf(false) }

    LaunchedEffect(s.messageKey) {
        s.messageKey?.let {
            vm.consumeMessage()
            shell.notify(strings.t(it), long = true)
        }
    }
    LaunchedEffect(s.outcome) {
        when (s.outcome) {
            EventEditorViewModel.Outcome.CREATED -> { close(); shell.notify(strings.t("calendar.createdToast")) }
            EventEditorViewModel.Outcome.SAVED -> { close(); shell.notify(strings.t("calendar.savedToast")) }
            EventEditorViewModel.Outcome.GONE, EventEditorViewModel.Outcome.CLOSED -> close()
            null -> Unit
        }
    }
    BackHandler(enabled = s.dirty) { discard = true }

    val members = cs.members
    val calendarsOffered = cs.calendars.size > 1 && (!s.isEdit || target.event?.localCalendarId != null)
    val visibilityOffered = members.size > 1 || !shell.session.soloHousehold

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (s.dirty) discard = true else close() }) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                title = { Text(t(if (s.isEdit) "calendar.editEvent" else "calendar.newEvent")) },
                actions = {
                    TextButton(onClick = { vm.save() }, enabled = !s.saving) { Text(t(if (s.isEdit) "common.save" else "common.add")) }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = f.title,
                onValueChange = { v -> vm.edit { it.copy(title = v) } },
                label = { Text(t("calendar.titleLabel")) },
                placeholder = { Text(t("calendar.titlePlaceholder")) },
                isError = EventForm.Field.TITLE in s.errors,
                supportingText = s.errors[EventForm.Field.TITLE]?.let { { Text(t(it)) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (calendarsOffered) {
                Picker(
                    label = t("calendar.localCalendarLabel"),
                    value = cs.calendars.firstOrNull { it.id == f.localCalendarId }?.name ?: t("calendar.defaultLocalCalendar"),
                    options = cs.calendars.map { it.id.toString() to it.name },
                    enabled = !f.leavesLocalCalendars,
                ) { id -> vm.edit { it.copy(localCalendarId = id.toInt()) } }
                if (cs.calendars.firstOrNull { it.id == f.localCalendarId }?.uploadsToIcloud == true) Hint(t("calendar.localCalendarUploadsHint"))
            }
            SwitchLine(t("calendar.allDayToggle"), f.allDay) { on -> vm.edit { it.copy(allDay = on) } }
            Label(t("calendar.fromLabel"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateButton(f.startDate, text, Modifier.weight(1f)) { d -> vm.edit { it.withStart(d, it.startTime) } }
                if (!f.allDay) TimeButton(f.startTime, text, t("calendar.startTimeLabel")) { tm -> vm.edit { it.withStart(it.startDate, tm) } }
            }
            Label(t("calendar.toLabel"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateButton(f.endDate, text, Modifier.weight(1f)) { d -> vm.edit { it.copy(endDate = d) } }
                if (!f.allDay) TimeButton(f.endTime, text, t("calendar.endTimeLabel")) { tm -> vm.edit { it.copy(endTime = tm) } }
            }
            s.errors[EventForm.Field.END]?.let { ErrorLine(t(it)) }

            if (members.size > 1 || f.assignees.isNotEmpty()) {
                Label(t("calendar.assignedLabel"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val people = members.map { Triple(it.id, it.displayName, it.avatarColor to it.avatarData) } +
                        (target.event?.assignedUsers.orEmpty().filter { p -> members.none { it.id == p.id } }.map { Triple(it.id, it.displayName, it.color to it.avatarData) })
                    people.forEach { (id, name, look) ->
                        FilterChip(
                            selected = id in f.assignees,
                            onClick = { vm.edit { it.copy(assignees = if (id in it.assignees) it.assignees - id else it.assignees + id) } },
                            label = { Text(name) },
                            leadingIcon = { MemberAvatar(name, look.first, look.second, size = 20.dp) },
                        )
                    }
                }
            }

            RecurrenceFields(f, target.event?.recurrenceRule, text, s.errors[EventForm.Field.UNTIL]) { p -> vm.edit { it.copy(rule = p) } }
            if (target.event?.let { RecurrenceScope.requiresWholeSeriesConfirmation(it) } == true) Hint(t("calendar.wholeSeriesOnlyNotice"), warn = true)

            ReminderFields(f, s.errors[EventForm.Field.REMINDERS], text, vm)

            OutlinedTextField(
                value = f.location,
                onValueChange = { v -> vm.edit { it.copy(location = v) } },
                label = { Text(t("calendar.locationLabel")) },
                placeholder = { Text(t("calendar.locationPlaceholder")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth().clickable { more = !more }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("modal.moreDetails"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (more) {
                OutlinedTextField(
                    value = f.description,
                    onValueChange = { v -> vm.edit { it.copy(description = v) } },
                    label = { Text(t("calendar.descriptionLabel")) },
                    placeholder = { Text(t("calendar.descriptionPlaceholder")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visibilityOffered) {
                    Label(t("common.visibility.label"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all", "assignees", "private").forEach { v ->
                            FilterChip(f.visibility == v, onClick = { vm.edit { it.copy(visibility = v) } }, label = { Text(t("common.visibility.$v")) })
                        }
                    }
                    Hint(t("common.visibility.hint"))
                    if (f.visibility == "assignees" && f.assignees.isEmpty()) Hint(t("common.visibility.assigneesNobodyHint"), warn = true)
                    if (f.visibility == "private" && f.assignees.any { it != shell.session.user.id }) Hint(t("common.visibility.privateAssignedHint"), warn = true)
                }
                SwitchLine(t("calendar.countdownToggle"), f.countdown) { on -> vm.edit { it.copy(countdown = on) } }
                Hint(t("calendar.countdownHint"))
                ColorField(f.color) { c -> vm.edit { it.copy(color = c) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("calendar.iconLabel"), Modifier.weight(1f))
                    OutlinedButton(onClick = { iconPicker = true }) {
                        Icon(CalendarIcons.glyph(f.icon), CalendarIcons.labelKey(f.icon)?.let { strings.t(it) } ?: t("calendar.iconLabel"))
                    }
                }
                SyncTargetField(f, s.syncTargets) { v -> vm.edit { it.copy(syncTarget = v) } }
                val ev = target.event
                if (ev != null && ev.attachmentLocked == true) {
                    Label(t("calendar.attachmentLabel"))
                    Hint(t("documentAttach.lockedPrivate"))
                } else if (ev != null && ev.hasAttachment && !f.removeAttachment && shell.session.access("documents") != "none") {
                    Label(t("calendar.attachmentLabel"))
                    Text(ev.attachmentName ?: t("calendar.attachmentFallback"))
                    OutlinedButton(onClick = { vm.edit { it.copy(removeAttachment = true) } }) { Text(t("calendar.attachmentRemove")) }
                }
            }
        }
    }

    if (discard) {
        ConfirmDialog(t("modal.unsavedChanges"), t("modal.unsavedChangesDetail"), t("modal.discardChanges")) { ok ->
            discard = false
            if (ok) close()
        }
    }
    when (val q = s.question) {
        EventEditorViewModel.Question.WholeSeriesOnly -> ConfirmDialog(
            t("calendar.editWholeSeriesOnlyTitle"),
            strings.t("calendar.editWholeSeriesOnlyDetail", "title" to (target.event?.title ?: "")),
            t("calendar.editWholeSeriesOnlyConfirm"),
        ) { ok -> vm.dismissQuestion(); if (ok) vm.save(confirmedWholeSeries = true) }
        is EventEditorViewModel.Question.Scope -> ScopeDialog(t("calendar.saveRecurringTitle"), target.event!!, text, q.allowThis) { choice ->
            if (choice == null) vm.dismissQuestion() else vm.saveWithScope(choice)
        }
        null -> Unit
    }
    if (iconPicker) IconPicker(f.icon, onPick = { name -> iconPicker = false; vm.edit { it.copy(icon = name) } }, close = { iconPicker = false })
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)

@Composable
private fun Hint(text: String, warn: Boolean = false) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (warn) Yuvomi.colors.warning else Yuvomi.colors.textSecondary)

@Composable
private fun ErrorLine(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.danger)

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) { Text(value, maxLines = 1) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(key) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(value: LocalDate, text: CalendarText, modifier: Modifier = Modifier, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) { OutlinedButton(onClick = { open = true }) { Text(text.dayHeading(value, long = false, year = true)) } }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
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
private fun TimeButton(value: LocalTime, text: CalendarText, label: String, onChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$label ${text.time(value)}" }) { Text(text.time(value)) }
    if (open) {
        val state = rememberTimePickerState(value.hour, value.minute)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false; onChange(LocalTime.of(state.hour, state.minute)) }) { Text(t("common.apply")) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
            text = { TimePicker(state) },
        )
    }
}

/** `renderRRuleFields('event', rule, { allowCount: true, expandsFromStart: true })`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceFields(f: EventForm, stored: String?, text: CalendarText, untilError: String?, onChange: (RRule.Parts) -> Unit) {
    val strings = LocalStrings.current
    val p = f.rule
    Label(t("rrule.labelRepeat"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RRule.FREQS.forEach { freq ->
            FilterChip(p.freq == freq, onClick = { onChange(p.copy(freq = freq)) }, label = { Text(RRuleText.freqLabel(freq, strings)) })
        }
    }
    if (p.freq.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("rrule.labelEvery"))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = p.interval.toString(),
            onValueChange = { v -> v.filter(Char::isDigit).take(2).toIntOrNull()?.let { onChange(p.copy(interval = it.coerceIn(1, 99))) } },
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
                FilterChip(d in p.byday, onClick = {
                    val days = if (d in p.byday) p.byday - d else RRule.WEEKDAYS.filter { it in p.byday || it == d }
                    onChange(p.copy(byday = days))
                }, label = { Text(RRuleText.dayLabel(d, strings)) })
            }
        }
    }
    if (p.freq == "MONTHLY") {
        SwitchLine(t("rrule.lastDayOfMonth"), p.lastDay) { onChange(p.copy(lastDay = it)) }
        if (p.lastDay) {
            // `monthEndHintText()` for a series that expands from its start.
            val start = f.startDate
            Hint(if (start.dayOfMonth == start.lengthOfMonth()) strings.t("rrule.lastDayOfMonthHintSame", "date" to text.date(start, year = true))
            else strings.t("rrule.lastDayOfMonthHintOverride", "startDate" to text.date(start, year = true), "firstDate" to text.date(start.withDayOfMonth(start.lengthOfMonth()), year = true)))
        }
    }
    Label(t("rrule.labelEnds"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(p.until.isEmpty() && p.count == null, onClick = { onChange(p.copy(until = "", count = null)) }, label = { Text(t("rrule.endNever")) })
        FilterChip(p.until.isNotEmpty() && p.count == null, onClick = {
            if (p.until.isEmpty() || p.count != null) onChange(p.copy(until = f.startDate.plusMonths(3).toString(), count = null))
        }, label = { Text(t("rrule.endOnDate")) })
        FilterChip(p.count != null, onClick = { if (p.count == null) onChange(p.copy(count = 10, until = "")) }, label = { Text(t("rrule.endAfter")) })
    }
    if (p.count == null && p.until.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("rrule.labelUntil"), Modifier.weight(1f))
            DateButton(runCatching { LocalDate.parse(p.until) }.getOrDefault(f.startDate), text) { d -> onChange(p.copy(until = d.toString())) }
        }
    }
    if (p.count != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = p.count.toString(),
                onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { onChange(p.copy(count = it.coerceAtLeast(1))) } },
                singleLine = true,
                label = { Text(t("rrule.labelCount")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(t("rrule.unitOccurrences"))
        }
    }
    untilError?.let { ErrorLine(t(it)) }
    if (RRule.hasForeignParts(stored)) Hint(t("android.tasks.ruleKept"), warn = true)
}

/** `renderCalendarReminderSection()`: up to five lead times, custom ones in minutes to weeks. */
@Composable
private fun ReminderFields(f: EventForm, error: String?, text: CalendarText, vm: EventEditorViewModel) {
    val strings = LocalStrings.current
    SwitchLine(t("reminders.enableLabel"), f.remindersOn) { on ->
        vm.edit { it.copy(remindersOn = on, reminders = if (on && it.reminders.isEmpty()) listOf(EventReminders.Row("0")) else it.reminders) }
    }
    if (!f.remindersOn) return
    f.reminders.forEachIndexed { i, row ->
        fun update(change: (EventReminders.Row) -> EventReminders.Row) =
            vm.edit { form -> form.copy(reminders = form.reminders.mapIndexed { j, r -> if (j == i) change(r) else r }) }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val options = (if (row.offset == EventReminders.AFTER_START) listOf(EventReminders.AFTER_START to "reminders.offsetAfterStart") else emptyList()) +
                    EventReminders.OFFSET_KEYS
                Box(Modifier.weight(1f)) {
                    Picker(
                        t("reminders.offsetLabel"),
                        strings.t(options.firstOrNull { it.first == row.offset }?.second ?: "reminders.offsetAtTime"),
                        options.map { it.first to strings.t(it.second) },
                    ) { v -> update { it.copy(offset = v, storedAt = if (v == EventReminders.AFTER_START) it.storedAt else null) } }
                }
                IconButton(onClick = { vm.edit { form -> form.copy(reminders = form.reminders.filterIndexed { j, _ -> j != i }) } }) {
                    Icon(Icons.Outlined.Close, t("reminders.removeReminder"))
                }
            }
            if (row.offset == EventReminders.AFTER_START && row.storedAt != null) {
                Hint(strings.t("reminders.afterStartHint", "when" to text.moment(row.storedAt)))
            }
            if (row.offset == "custom") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = row.amount.toString(),
                        onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { a -> update { it.copy(amount = a.coerceAtLeast(1)) } } },
                        singleLine = true,
                        label = { Text(t("reminders.customAmountLabel")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Picker(t("reminders.customUnitLabel"), strings.t(EventReminders.UNITS.first { it.first == row.unit }.second),
                        EventReminders.UNITS.map { it.first to strings.t(it.second) }) { u -> update { it.copy(unit = u) } }
                }
            }
        }
    }
    if (f.reminders.size < EventReminders.MAX) {
        TextButton(onClick = { vm.edit { it.copy(reminders = it.reminders + EventReminders.Row("15")) } }) { Text(t("reminders.addReminder")) }
    }
    error?.let { ErrorLine(t(it)) }
    if (f.assignees.isNotEmpty()) Hint(t("reminders.sharedWithAssignees"))
}

/** The one colour picker (R24, E6): "the assignee's colour" first, then the palette. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorField(value: String?, onChange: (String?) -> Unit) {
    val strings = LocalStrings.current
    Label(t("calendar.colorLabel"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Swatch(null, value == null, t("calendar.colorInherit")) { onChange(null) }
        CalendarRules.pickerColors(value).forEach { c ->
            val name = CalendarRules.EVENT_COLORS.firstOrNull { it.first.equals(c, ignoreCase = true) }?.second?.let { strings.t(it) } ?: t("calendar.colorCurrent")
            Swatch(c, value.equals(c, ignoreCase = true), name) { onChange(c) }
        }
    }
    if (value == null) Hint(t("calendar.colorInheritHint"))
}

@Composable
fun Swatch(hex: String?, selected: Boolean, label: String, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(3.dp, outline, CircleShape) else Modifier.border(1.dp, Yuvomi.colors.border, CircleShape))
            .background(if (hex == null) MaterialTheme.colorScheme.surfaceVariant else color(hex, Yuvomi.colors.textSecondary), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
    )
}

/** `loadSyncTargets()`: store locally, or a Google, CalDAV or Outlook calendar. */
@Composable
private fun SyncTargetField(f: EventForm, targets: SyncTargets, onChange: (String) -> Unit) {
    val strings = LocalStrings.current
    val options = buildList {
        add("" to t("calendar.syncTargetLocal"))
        targets.google.forEach { add("google:${it.id}" to "${strings.t("calendar.syncTargetGoogleGroup")} · ${it.summary ?: it.id}") }
        targets.caldav.forEach { add("caldav:${it.accountId}|${it.calendarUrl}" to "${strings.t("calendar.syncTargetCaldavGroup")} · ${it.accountName} · ${it.calendarName ?: it.calendarUrl}") }
        targets.outlook.forEach { add("outlook:${it.accountId}|${it.calendarId}" to "${strings.t("calendar.syncTargetOutlookGroup")} · ${it.accountName} · ${it.calendarName ?: it.calendarId}") }
        // A stored target the list no longer names stays as its own option (#1840).
        if (f.syncTarget.isNotEmpty() && none { it.first == f.syncTarget }) add(f.syncTarget to f.syncTarget.substringAfter(':').substringAfter('|'))
    }
    if (options.size <= 1) return
    Picker(t("calendar.syncTargetLabel"), options.firstOrNull { it.first == f.syncTarget }?.second ?: t("calendar.syncTargetLocal"), options) { onChange(it) }
    Hint(t("calendar.syncTargetHint"))
    if (f.syncTarget.startsWith("outlook:")) Hint(t("settings.outlookPushHint"))
}

/** `openIconPickerDialog()`: the icons by category. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(selected: String, onPick: (String) -> Unit, close: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("calendar.iconLabel")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarIcons.CATEGORIES.forEach { cat ->
                    Text(strings.t(cat.labelKey), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        cat.icons.forEach { (name, key) ->
                            IconButton(
                                onClick = { onPick(name) },
                                modifier = if (name == selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape) else Modifier,
                            ) { Icon(CalendarIcons.glyph(name), strings.t(key)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(t("common.close")) } },
    )
}
