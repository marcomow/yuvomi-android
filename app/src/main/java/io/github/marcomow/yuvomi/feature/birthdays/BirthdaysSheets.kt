package io.github.marcomow.yuvomi.feature.birthdays

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
private fun ReadRow(label: String, value: String, action: (@Composable () -> Unit)? = null) {
    if (value.isBlank() && action == null) return
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        trailingContent = action?.let { { it() } },
    )
}

@Composable
private fun SheetConfirm(question: String, detail: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(question) },
        text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Yuvomi.colors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** BDAY-R8: the read sheet (`openBirthdaySheet`, `birthdayPaneSections`, `birthdayDetailHead`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthdaySheet(
    b: Birthday,
    vm: BirthdaysViewModel,
    host: BirthdaysHost,
    writable: Boolean,
    format: BirthdaysFormat,
    openContact: ((Int) -> Unit)?,
    close: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var linking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                BirthdayAvatar(b, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(b.name, style = MaterialTheme.typography.titleLarge)
                    b.nextBirthday?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let {
                        Text(strings.t("birthdays.nextBirthdayOn", "date" to format.dateText(it, weekday = true)), style = MaterialTheme.typography.bodyMedium)
                    }
                    val facts = listOfNotNull(
                        b.nextAge?.let { strings.t("birthdays.turnsAge", "age" to it) },
                        b.daysUntil?.let { BirthdaysRules.countdown(it, strings, format) },
                    )
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
            Spacer(Modifier.size(8.dp))
            ReadRow(
                t("birthdays.birthDateLabel"),
                BirthdaysRules.birthDateText(b.birthDate, b.yearUnknown, format),
                action = if (b.contactId != null && openContact != null) ({
                    TextButton(onClick = { close(); openContact(b.contactId) }) { Text(t("birthdays.showContact")) }
                }) else null,
            )
            // The quiet hint (D7): a contact of the same name, not linked yet.
            if (b.suggestedContactId != null && b.contactId == null && writable) {
                ReadRow(t("birthdays.contactSuggestLabel"), t("birthdays.contactSuggestValue")) {
                    OutlinedButton(enabled = !linking, onClick = {
                        linking = true
                        scope.launch {
                            vm.link(b.id, b.suggestedContactId)
                            linking = false
                        }
                    }) { Text(t("birthdays.linkContact")) }
                }
            }
            val nameDay = BirthdaysRules.nameDayText(b.nameDay, format)
            ReadRow(
                t("birthdays.nameDay"),
                if (nameDay.isNotEmpty() && b.nameDayDaysUntil != null) "$nameDay · ${BirthdaysRules.countdown(b.nameDayDaysUntil, strings, format)}" else nameDay,
            )
            ReadRow(t("birthdays.notesLabel"), b.notes.orEmpty())
            ReadRow(t("reminders.offsetLabel"), BirthdaysRules.reminderText(b, strings, format))
            if (writable && !host.readOnly) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = delete) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = edit) { Text(t("common.edit")) }
                }
            }
        }
    }
}

/** BDAY-R14: import from contacts (`openImportModal`). */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ImportSheet(vm: BirthdaysViewModel, format: BirthdaysFormat, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<ImportCandidates?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<Int>() }
    val linked = remember { mutableStateListOf<Int>() }
    var reason by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var showWithout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.candidates().onSuccess { candidates = it }.onFailure { loadError = it.message ?: strings.t("common.errorGeneric") }
    }
    loadError?.let { message ->
        LaunchedEffect(message) {
            shell.container.scope.launch { shell.notify(message) }
            close()
        }
        return
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(t("birthdays.importTitle"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(t("birthdays.importIntro"), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Yuvomi.colors.textSecondary)
            val c = candidates
            if (c == null) {
                CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            val with = c.withBirthday.map { if (it.id in linked) it.copy(alreadyImported = true) else it }
            val selectable = with.filter { !it.alreadyImported && it.suggestedBirthdayId == null }.map { it.id }
            if (selectable.isNotEmpty()) {
                TextButton(onClick = {
                    if (selected.size == selectable.size) selected.clear() else { selected.clear(); selected.addAll(selectable) }
                }, enabled = !submitting, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(t(if (selected.size == selectable.size) "contacts.importDeselectAll" else "contacts.importSelectAll"))
                }
            }
            if (with.isEmpty()) Text(t("birthdays.importEmpty"), Modifier.padding(24.dp), color = Yuvomi.colors.textSecondary)
            with.forEach { candidate ->
                val date = BirthdaysRules.birthDateText(candidate.birthday, candidate.birthdayYearUnknown, format)
                when {
                    candidate.alreadyImported -> ListItem(
                        leadingContent = { Icon(Icons.Outlined.Check, null, tint = Yuvomi.colors.success) },
                        headlineContent = { Text(candidate.name) },
                        supportingContent = { Text("$date · ${strings.t("birthdays.importAlreadyAdded")}") },
                    )
                    candidate.suggestedBirthdayId != null -> ListItem(
                        headlineContent = { Text(candidate.name) },
                        supportingContent = { Text("${strings.t("birthdays.importLinkSuggest", "name" to candidate.suggestedBirthdayName.orEmpty())} · $date") },
                        trailingContent = {
                            OutlinedButton(onClick = {
                                scope.launch { if (vm.link(candidate.suggestedBirthdayId, candidate.id) != null) linked += candidate.id }
                            }) { Text(t("birthdays.linkContact")) }
                        },
                    )
                    else -> ListItem(
                        modifier = Modifier.clickable(enabled = !submitting) {
                            if (candidate.id in selected) selected.remove(candidate.id) else selected.add(candidate.id)
                            reason = null
                        },
                        leadingContent = { Checkbox(candidate.id in selected, null, enabled = !submitting) },
                        headlineContent = { Text(candidate.name) },
                        supportingContent = { Text(date) },
                    )
                }
            }
            if (c.withoutBirthday.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    modifier = Modifier.clickable { showWithout = !showWithout },
                    headlineContent = { Text("${strings.t("birthdays.importNoBirthdaySection")} (${c.withoutBirthday.size})") },
                    trailingContent = { Icon(if (showWithout) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                )
                if (showWithout) {
                    Text(t("birthdays.importNoBirthdayHint"), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        c.withoutBirthday.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            reason?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = Yuvomi.colors.danger) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text(t("common.cancel")) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !submitting, onClick = {
                    submitting = true
                    scope.launch {
                        val failure = vm.import(selected.toList())
                        submitting = false
                        if (failure == null) close() else reason = failure
                    }
                }) { Text(strings.t("birthdays.importSubmit", "count" to selected.size)) }
            }
        }
    }
}

/** BDAY-R15 to R17: the birthday feed (`personal-feeds.js`, part `birthdays`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedSheet(vm: BirthdaysViewModel, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var loaded by remember { mutableStateOf(false) }
    var feed by remember { mutableStateOf<BirthdayFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.feed().onSuccess { feed = it }.onFailure { error = it.message }
        loaded = true
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            when {
                !loaded -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                error != null -> Text(error!!, Modifier.padding(24.dp), color = Yuvomi.colors.danger)
                else -> {
                    val on = feed?.url != null
                    ListItem(
                        headlineContent = { Text(t("settings.birthdayFeedTitle")) },
                        supportingContent = { Text(t("settings.birthdayFeedDescription")) },
                        trailingContent = {
                            Switch(on, enabled = !busy, onCheckedChange = { next ->
                                if (!next) {
                                    confirm = "disable"
                                } else {
                                    busy = true
                                    scope.launch {
                                        vm.regenerateFeed()?.let {
                                            feed = it
                                            shell.notify(strings.t("settings.birthdayFeedTitle"))
                                        }
                                        busy = false
                                    }
                                }
                            })
                        },
                    )
                    val url = feed?.url
                    if (url != null) {
                        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t("settings.feedExportUrlLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                            SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium) }
                            Text(t("settings.birthdayFeedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(url))
                                    scope.launch { shell.notify(strings.t("settings.feedExportCopied")) }
                                }) { Text(t("settings.feedExportCopy")) }
                                OutlinedButton(onClick = {
                                    runCatching { uri.openUri(url.replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "webcal://")) }
                                        .onFailure { scope.launch { shell.notify(strings.t("common.errorGeneric")) } }
                                }) { Text(t("settings.feedExportSubscribe")) }
                            }
                            OutlinedButton(enabled = !busy, onClick = { confirm = "regenerate" }) { Text(t("settings.feedExportRegenerate")) }
                        }
                    }
                }
            }
        }
    }
    when (confirm) {
        "disable" -> SheetConfirm(
            t("settings.feedExportDisableConfirm"), t("settings.feedExportDisableConfirmDetail"), t("settings.feedExportDisable"),
            onDismiss = { confirm = null },
        ) {
            confirm = null
            busy = true
            scope.launch {
                if (vm.disableFeed()) feed = null
                busy = false
            }
        }
        "regenerate" -> SheetConfirm(
            t("settings.feedExportRegenerateConfirm"), t("settings.feedExportRegenerateConfirmDetail"), t("settings.feedExportRegenerate"),
            onDismiss = { confirm = null },
        ) {
            confirm = null
            busy = true
            scope.launch {
                vm.regenerateFeed()?.let { feed = it }
                busy = false
            }
        }
    }
}
