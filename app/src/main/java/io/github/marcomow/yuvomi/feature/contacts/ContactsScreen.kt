package io.github.marcomow.yuvomi.feature.contacts

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysRoutes
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant

/** docs/spec/contacts.md, the list (CONT-R1 to R5, R11, R12, R14, R16). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    vm: ContactsViewModel,
    openContact: (Int) -> Unit,
    newContact: (String?) -> Unit,
    manageCategories: () -> Unit,
) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.state.collectAsState()
    val canWrite = shell.session.access("contacts") == "write"
    val editable = canWrite && !s.offline
    var confirmBulk by remember { mutableStateOf(false) }
    val export = rememberVCardSaver { id -> vm.vcard(id) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importText(readText(context, uri))
    }
    val openBirthdays by vm.openBirthdaysRequest.collectAsState()
    LaunchedEffect(openBirthdays) {
        if (openBirthdays) {
            vm.openBirthdaysRequest.value = false
            shell.navigate(BirthdaysRoutes.START)
        }
    }
    BackHandler(enabled = s.selecting) { vm.stopSelecting() }
    val label = { key: String -> ContactsRules.categoryLabel(key, s.categories, strings) }
    val notify: suspend (String) -> Unit = { shell.notify(it) }
    val noApp = strings.t("android.contacts.noApp")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("contacts.title")) },
                actions = {
                    if (s.selecting) {
                        TextButton(onClick = vm::stopSelecting) { Text(t("common.cancel")) }
                    } else if (editable) {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(t("contacts.selectButton")) },
                                leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                                onClick = { menu = false; vm.startSelecting() },
                            )
                            DropdownMenuItem(
                                text = { Text(t("contacts.importTooltip")) },
                                leadingIcon = { Icon(Icons.Outlined.Upload, null) },
                                onClick = {
                                    menu = false
                                    runCatching { picker.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "application/octet-stream", "*/*")) }
                                        .onFailure { scope.launch { shell.notify(noApp) } }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(t("contacts.manageCategories")) },
                                leadingIcon = { Icon(Icons.Outlined.Sell, null) },
                                onClick = { menu = false; manageCategories() },
                            )
                        }
                    }
                    AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (editable && !s.selecting) {
                FloatingActionButton(onClick = { newContact(s.category) }) { Icon(Icons.Outlined.Add, contentDescription = t("contacts.newContactLabel")) }
            }
        },
        bottomBar = {
            if (s.selecting) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("contacts.selectCount", "count" to s.selected.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::toggleSelectAll) { Text(t("contacts.selectAll")) }
                        if (s.selected.isNotEmpty()) {
                            Button(onClick = { confirmBulk = true }) { Text(t("common.delete")) }
                        }
                    }
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.error != null -> ErrorBox(s.error!!, onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        item(key = "search") { SearchField(s.query, vm::setQuery) }
                        if (s.chipKeys.isNotEmpty()) {
                            item(key = "chips") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item(key = "all") { FilterChip(selected = s.category == null, onClick = { vm.setCategory(null) }, label = { Text(t("contacts.filterAll")) }) }
                                    items(s.chipKeys, key = { it }) { key ->
                                        FilterChip(selected = s.category == key, onClick = { vm.setCategory(key) }, label = { Text(label(key)) })
                                    }
                                }
                            }
                        }
                        when {
                            s.visible.isEmpty() && (s.query.isNotBlank() || s.category != null) -> item(key = "none") {
                                NoResults(s.query, s.category?.let(label)) { vm.resetSearch() }
                            }
                            s.visible.isEmpty() -> item(key = "empty") {
                                // Read-only members get the title only (`contactsEmptyStateHtml`).
                                if (editable) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                        EmptyState(t("contacts.emptyTitle"), t("contacts.emptyDescription"), t("contacts.emptyAction")) { newContact(s.category) }
                                        Text(
                                            t("emptyHint.contacts"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Yuvomi.colors.textSecondary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 32.dp),
                                        )
                                    }
                                } else {
                                    EmptyState(t("contacts.emptyTitle"))
                                }
                            }
                            else -> s.groups.forEach { (category, contacts) ->
                                item(key = "g-$category") {
                                    Text(
                                        label(category).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = categoryTone(s.categories.firstOrNull { it.key == category }?.color) ?: Yuvomi.colors.textSecondary,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                    )
                                }
                                items(contacts, key = { "c-${it.id}" }) { c ->
                                    ContactRow(
                                        c = c,
                                        selecting = s.selecting,
                                        selected = c.id in s.selected,
                                        canDelete = editable && !c.isMember,
                                        onOpen = { if (s.selecting) vm.toggleSelected(c) else openContact(c.id) },
                                        onCall = { phone -> scope.launch { startOrSay(context, notify, noApp, ContactIntents.dialIntent(phone)) } },
                                        onEmail = { mail -> scope.launch { startOrSay(context, notify, noApp, ContactIntents.emailIntent(mail)) } },
                                        onMaps = { address -> scope.launch { startOrSay(context, notify, noApp, *ContactIntents.mapsIntents(address).toTypedArray()) } },
                                        onExport = { export(c) },
                                        onDelete = { vm.delete(c) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmBulk) {
        val n = s.selected.size
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text(t("contacts.bulkDeleteConfirm", "count" to n)) },
            confirmButton = {
                TextButton(onClick = { confirmBulk = false; vm.deleteSelected() }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text(t("common.cancel")) } },
        )
    }

    s.import?.let { choice -> ImportDialog(choice, vm) }
}

@Composable
private fun ContactRow(
    c: Contact,
    selecting: Boolean,
    selected: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onCall: (String) -> Unit,
    onEmail: (String) -> Unit,
    onMaps: (String) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !selecting || !c.isMember, onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onOpen() }, enabled = !c.isMember)
            Spacer(Modifier.width(4.dp))
        }
        ContactAvatar(c)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(c.phone?.takeIf { it.isNotBlank() }?.let { ContactPhones.display(it) }, c.email?.takeIf { it.isNotBlank() })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!selecting) {
            val phone = c.phone?.takeIf { it.isNotBlank() }
            if (phone != null) {
                IconButton(onClick = { onCall(phone) }) { Icon(Icons.Outlined.Phone, t("contacts.callNamed", "name" to c.name)) }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to c.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    c.email?.takeIf { it.isNotBlank() }?.let { mail ->
                        DropdownMenuItem(text = { Text(t("contacts.emailActionLabel")) }, leadingIcon = { Icon(Icons.Outlined.Email, null) }, onClick = { menu = false; onEmail(mail) })
                    }
                    c.address?.takeIf { it.isNotBlank() }?.let { address ->
                        DropdownMenuItem(text = { Text(t("contacts.mapsLabel")) }, leadingIcon = { Icon(Icons.Outlined.Map, null) }, onClick = { menu = false; onMaps(address) })
                    }
                    DropdownMenuItem(text = { Text(t("contacts.exportLabel")) }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = { menu = false; onExport() })
                    if (canDelete) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(t("contacts.searchPlaceholder")) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The one empty search result (`searchEmptyHTML`): term and category named, one reset. */
@Composable
private fun NoResults(query: String, category: String?, reset: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.SearchOff, null, tint = Yuvomi.colors.textSecondary)
        Text(t("common.searchNoResultsTitle"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            t(if (category != null) "common.searchNoResultsFilteredDescription" else "common.searchNoResultsDescription"),
            style = MaterialTheme.typography.bodyMedium,
            color = Yuvomi.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isNotBlank()) AssistChip(onClick = {}, label = { Text(query.trim()) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) })
            if (category != null) AssistChip(onClick = {}, label = { Text(category) }, leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp)) })
        }
        TextButton(onClick = reset) { Text(t("common.searchReset")) }
    }
}

/** `openImportSelectionModal`: existing names start unchecked and say so. */
@Composable
private fun ImportDialog(choice: ContactsViewModel.ImportChoice, vm: ContactsViewModel) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = { if (!choice.importing) vm.cancelImport() },
        title = { Text(t("contacts.importTitle")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(t("contacts.importIntro"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = vm::toggleImportAll, enabled = !choice.importing) {
                    Text(t(if (choice.selected.size == choice.named.size) "contacts.importDeselectAll" else "contacts.importSelectAll"))
                }
                choice.named.forEachIndexed { i, card ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !choice.importing) { vm.toggleImport(i) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = i in choice.selected, onCheckedChange = { vm.toggleImport(i) }, enabled = !choice.importing)
                        Column(Modifier.weight(1f)) {
                            Text(card.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                            val detail = card.phone ?: card.email
                            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                            if (i in choice.exists) Text(t("contacts.importExistsBadge"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.warning)
                        }
                        if (card.birthday != null) {
                            Icon(
                                Icons.Outlined.Cake,
                                birthDateText(card.birthday, card.birthdayYearUnknown, strings.locale),
                                tint = Yuvomi.colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (choice.skipped > 0) {
                    Text(t("contacts.importSkippedNote", "count" to choice.skipped), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                if (choice.nothingSelected) {
                    Text(t("birthdays.importNothingSelected"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.danger)
                }
            }
        },
        confirmButton = {
            Button(onClick = vm::submitImport, enabled = !choice.importing) {
                Text(if (choice.importing) t("contacts.importImporting") else t("contacts.importSubmit", "count" to choice.selected.size))
            }
        },
        dismissButton = { TextButton(onClick = vm::cancelImport, enabled = !choice.importing) { Text(t("common.cancel")) } },
    )
}
