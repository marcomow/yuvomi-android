package io.github.marcomow.yuvomi.feature.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysRoutes
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant

/** docs/spec/contacts.md, the read view (`openContactDetail`, `renderContactDetail`, `contactCardEl`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(vm: ContactDetailViewModel, back: () -> Unit, edit: (Int) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.state.collectAsState()
    val host = remember(shell, strings) { ShellContactsHost(shell, strings) }
    val editable = host.canWrite && !s.offline
    val export = rememberVCardSaver { vm.vcard() }
    var confirmDelete by remember { mutableStateOf(false) }
    val notify: suspend (String) -> Unit = { shell.notify(it) }
    val noApp = strings.t("android.contacts.noApp")
    fun launch(vararg intents: android.content.Intent) {
        scope.launch { startOrSay(context, notify, noApp, *intents) }
    }

    LaunchedEffect(s.gone) { if (s.gone) back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.contact?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    val c = s.contact
                    if (c != null) {
                        if (editable) IconButton(onClick = { edit(c.id) }) { Icon(Icons.Outlined.Edit, t("common.edit")) }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(t("contacts.exportLabel")) }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = { menu = false; export(c) })
                                if (editable && !c.isMember) {
                                    DropdownMenuItem(
                                        text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                        onClick = { menu = false; confirmDelete = true },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        val c = s.contact
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                c == null && s.loading -> LoadingBox()
                c == null -> ErrorBox(s.error ?: strings.t("common.errorGeneric"), onRetry = vm::load)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (s.offline) OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
                    ContactCard(c, s.categories, onCall = { launch(ContactIntents.dialIntent(it)) }, onEmail = { launch(ContactIntents.emailIntent(it)) }, onMaps = { launch(*ContactIntents.mapsIntents(it).toTypedArray()) })
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ContactsRules.phones(c).forEach { p ->
                                ValueRow(Icons.Outlined.Phone, t("contacts.phoneLabel"), ContactPhones.display(p.value), ContactsRules.subLabel(p.label)) { launch(ContactIntents.dialIntent(p.value)) }
                            }
                            ContactsRules.emails(c).forEach { e ->
                                ValueRow(Icons.Outlined.Email, t("contacts.emailLabel"), e.value, ContactsRules.subLabel(e.label)) { launch(ContactIntents.emailIntent(e.value)) }
                            }
                            ContactsRules.addresses(c).forEach { (text, sub) ->
                                ValueRow(Icons.Outlined.Place, t("contacts.addressLabel"), text, sub) { launch(*ContactIntents.mapsIntents(text).toTypedArray()) }
                            }
                            BirthdayRow(c, host, s.busy || s.offline, vm, open = { shell.navigate(BirthdaysRoutes.START) })
                            c.website?.takeIf { it.isNotBlank() }?.let { site ->
                                ValueRow(Icons.Outlined.Language, t("contacts.websiteLabel"), site, null) { launch(ContactIntents.webIntent(site)) }
                            }
                            c.nickname?.takeIf { it.isNotBlank() }?.let { ValueRow(Icons.Outlined.FormatQuote, t("contacts.nicknameLabel"), it, null, null) }
                            ValueRow(Icons.Outlined.Folder, t("contacts.categoryLabel"), ContactsRules.categoryLabel(c.category, s.categories, strings), null, null)
                            c.notes?.takeIf { it.isNotBlank() }?.let { ValueRow(Icons.AutoMirrored.Outlined.Notes, t("contacts.notesLabel"), it, null, null) }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        val c = s.contact
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("contacts.deletePersonConfirm", "name" to c?.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(t("common.delete"), color = Yuvomi.colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("common.cancel")) } },
        )
    }
}

/** `contactCardEl`: the person, their organisation, and call / e-mail / maps for the first of each. */
@Composable
private fun ContactCard(c: Contact, categories: List<ContactCategory>, onCall: (String) -> Unit, onEmail: (String) -> Unit, onMaps: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContactAvatar(c, size = 72.dp)
        val sub = listOfNotNull(c.organization?.takeIf { it.isNotBlank() }, c.jobTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, textAlign = TextAlign.Center)
        val tone = categoryTone(categories.firstOrNull { it.key == c.category }?.color)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContactsRules.firstPhone(c)?.let { phone -> QuickAction(Icons.Outlined.Phone, t("contacts.callLabel"), tone) { onCall(phone) } }
            ContactsRules.firstEmail(c)?.let { mail -> QuickAction(Icons.Outlined.Email, t("contacts.emailActionLabel"), tone) { onEmail(mail) } }
            ContactsRules.firstAddress(c)?.let { address -> QuickAction(Icons.Outlined.Map, t("contacts.mapsLabel"), tone) { onMaps(address) } }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, tone: androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(18.dp), tint = tone ?: MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun ValueRow(icon: ImageVector, label: String, value: String, sub: String?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
}

/** CONT-R8 to R10: the birthday, its way to Birthdays, and the quiet "same name" suggestion. */
@Composable
private fun BirthdayRow(c: Contact, host: ContactsHost, busy: Boolean, vm: ContactDetailViewModel, open: () -> Unit) {
    val strings = LocalStrings.current
    val shown = ContactsRules.birthdayShown(c)
    val action = ContactsRules.birthdayAction(c, host.mayOpenBirthdays, host.mayWriteBirthdays)
    if (shown != null || action != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cake, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t("contacts.birthdayLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                if (shown != null) Text(birthDateText(shown.first, shown.second, strings.locale), style = MaterialTheme.typography.bodyLarge)
            }
            when (action) {
                ContactsRules.BirthdayAction.SHOW -> TextButton(onClick = open) { Text(t("contacts.showBirthday")) }
                ContactsRules.BirthdayAction.ADD -> TextButton(onClick = vm::addToBirthdays, enabled = !busy) { Text(t("contacts.addToBirthdays")) }
                null -> Unit
            }
        }
    }
    if (ContactsRules.showsSuggestion(c, host.mayWriteBirthdays)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cake, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t("nav.birthdays"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                Text(
                    c.suggestedBirthdayName?.let { strings.t("contacts.birthdaySuggestSameDate", "name" to it) } ?: strings.t("contacts.birthdaySuggestValue"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = vm::linkBirthday, enabled = !busy) {
                Icon(Icons.Outlined.Link, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("birthdays.linkContact"))
            }
        }
    }
}
