package io.github.marcomow.yuvomi.feature.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `renderFolderBrowser` + `renderCategoryBrowser`, as the phone layout of the web page shows
 * them: one sheet the folder chip opens, the folders as a tree with counts and their menus,
 * then the categories (DOCS-R6, R27 to R31).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderSheet(
    vm: DocumentsViewModel,
    readOnly: Boolean,
    close: () -> Unit,
    open: (DocumentsDialog) -> Unit,
) {
    val state by vm.state.collectAsState()
    val folders = state.shownFolders
    val counts = DocumentRules.folderCounts(state.shown, folders, state.filter)
    val showCounts = state.shown.isNotEmpty()
    val rows = DocumentRules.flatten(folders, state.expanded)
    val categoryCounts = DocumentRules.categoryCounts(state.shown, folders, state.filter)
    val present = DocumentRules.presentCategories(state.shown, folders, state.filter)
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("documents.folderBrowserTitle"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!readOnly) {
                        IconButton(onClick = { open(DocumentsDialog.NewFolder(state.filter.selectedFolder)) }) {
                            Icon(Icons.Outlined.CreateNewFolder, t("documents.addFolderButton"))
                        }
                    }
                }
            }
            item(key = "all") {
                FolderLine(Icons.Outlined.Folder, t("documents.allDocuments"), counts[""].takeIf { showCounts }, state.filter.folderId == "", depth = 0, lead = rows.isNotEmpty()) {
                    vm.selectFolder(DocumentRules.FOLDER_ALL); close()
                }
            }
            item(key = "none") {
                FolderLine(Icons.Outlined.FolderOff, t("documents.noFolder"), counts[DocumentRules.FOLDER_NONE].takeIf { showCounts }, state.filter.folderId == DocumentRules.FOLDER_NONE, depth = 0, lead = rows.isNotEmpty()) {
                    vm.selectFolder(DocumentRules.FOLDER_NONE); close()
                }
            }
            items(rows, key = { "f-${it.folder.id}" }) { row ->
                val id = row.folder.id
                val expanded = id in state.expanded
                FolderLine(
                    icon = if (row.hasChildren && expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                    name = row.folder.name,
                    count = counts[id.toString()].takeIf { showCounts },
                    active = state.filter.folderId == id.toString(),
                    depth = row.depth,
                    lead = true,
                    toggle = if (row.hasChildren) ({ vm.toggleExpanded(id) }) else null,
                    expanded = expanded,
                    menu = if (readOnly) null else ({ FolderMenu(row.folder, open, vm) }),
                ) { vm.selectFolder(id.toString()); close() }
            }
            item(key = "cat-head") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(t("documents.categoryLabel"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            item(key = "cat-all") {
                FolderLine(Icons.Outlined.Category, t("documents.allCategories"), categoryCounts[""].takeIf { showCounts }, state.filter.category.isEmpty(), 0, lead = false) {
                    vm.setCategory(""); close()
                }
            }
            items(present, key = { "c-$it" }) { category ->
                FolderLine(categoryIcon(category), categoryLabel(category), (categoryCounts[category] ?: 0).takeIf { showCounts }, state.filter.category == category, 0, lead = false) {
                    vm.setCategory(category); close()
                }
            }
            item(key = "end") { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun FolderLine(
    icon: ImageVector,
    name: String,
    count: Int?,
    active: Boolean,
    depth: Int,
    lead: Boolean,
    toggle: (() -> Unit)? = null,
    expanded: Boolean = false,
    menu: (@Composable () -> Unit)? = null,
    select: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) documentsTone.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = select)
            .padding(start = (8 + depth * 20).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            toggle != null -> IconButton(onClick = toggle) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                    t(if (expanded) "documents.folderCollapse" else "documents.folderExpand", "name" to name),
                )
            }
            lead -> Spacer(Modifier.size(48.dp))
            else -> Spacer(Modifier.size(8.dp))
        }
        Icon(icon, null, tint = documentsTone, modifier = Modifier.size(22.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp))
        if (count != null) Text("$count", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(end = 8.dp))
        if (menu != null) menu() else Spacer(Modifier.size(8.dp))
    }
}

/** `openFolderMenu`: new subfolder, rename, move, delete. */
@Composable
private fun FolderMenu(folder: DocumentFolder, open: (DocumentsDialog) -> Unit, vm: DocumentsViewModel) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("documents.folderActionsFor", "name" to folder.name)) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DocumentMenuItem(t("documents.newSubfolder"), Icons.Outlined.CreateNewFolder) { menu = false; open(DocumentsDialog.NewFolder(folder.id)) }
            DocumentMenuItem(t("documents.renameFolder"), Icons.Outlined.Edit) { menu = false; open(DocumentsDialog.RenameFolder(folder)) }
            DocumentMenuItem(t("documents.moveFolder"), Icons.AutoMirrored.Outlined.DriveFileMove) { menu = false; open(DocumentsDialog.MoveFolder(folder)) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(t("documents.deleteFolder"), color = Yuvomi.colors.danger) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                onClick = { menu = false; vm.askDeleteFolder(folder) },
            )
        }
    }
}

/**
 * `openFolderModal`: a name and the parent, preset to the open folder (the usual case is a
 * folder inside the one you are in). A gone parent keeps the dialog open on the top level.
 */
@Composable
internal fun NewFolderDialog(vm: DocumentsViewModel, parentId: Int?, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val s by vm.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(parentId?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val key = remember { YuvomiClient.newIdempotencyKey() }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(t("documents.newFolderTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(200) },
                    label = { Text(t("documents.folderNameLabel") + " *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Dropdown(
                    label = t("documents.folderParentLabel"),
                    value = parent,
                    options = listOf("" to t("documents.folderRootLevel")) +
                        DocumentRules.flatten(s.shownFolders).map { it.folder.id.toString() to "  ".repeat(it.depth) + it.folder.name },
                    onPick = { parent = it },
                )
                error?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    when (val result = vm.createFolder(name, parent.toIntOrNull(), key)) {
                        DocumentsViewModel.SaveResult.Saved, DocumentsViewModel.SaveResult.Gone -> close()
                        is DocumentsViewModel.SaveResult.Problem -> {
                            error = strings.t(result.message.key, result.message.args)
                            if (result.folderGone) parent = ""
                        }
                    }
                    busy = false
                }
            }) { Text(t("common.add")) }
        },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(t("common.cancel")) } },
    )
}

/** `renameFolder` (`promptModal`). */
@Composable
internal fun RenameFolderDialog(folder: DocumentFolder, close: () -> Unit, rename: (String) -> Unit) {
    var name by remember { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("documents.renameFolder")) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { close(); rename(name) }, enabled = name.isNotBlank()) { Text(t("common.save")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}

/**
 * `deleteFolder` + `folderDeleteChoice`: with documents in the branch (or some the member may
 * not delete), keep them or delete them too; otherwise a plain confirmation.
 */
@Composable
internal fun FolderDeleteDialog(folder: DocumentFolder, impact: FolderDeleteImpact, vm: DocumentsViewModel) {
    val subfolders = maxOf(0, impact.removedFolders - 1)
    val documents = maxOf(0, impact.documents)
    if (!FolderDeleteOutcome.asksChoice(impact)) {
        ConfirmDialog(
            title = t("documents.deleteFolderConfirm", "name" to folder.name),
            detail = t("documents.deleteFolderImpact", "folders" to subfolders, "documents" to 0),
            confirm = t("documents.deleteFolder"),
            onDismiss = vm::cancelFolderDelete,
        ) { vm.deleteFolder(FolderDeleteChoice.UNFILE) }
        return
    }
    val strings = LocalStrings.current
    val linked = FolderDeleteOutcome.linkedRecords(impact).joinToString(" · ") { (label, count) -> "${strings.t(label)}: $count" }
    AlertDialog(
        onDismissRequest = vm::cancelFolderDelete,
        title = { Text(t("documents.deleteFolderConfirm", "name" to folder.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("documents.deleteFolderImpact", "documents" to documents, "folders" to subfolders))
                if (linked.isNotEmpty()) Text(t("documents.deleteFolderLinkedRecords") + "\n" + linked)
                if (!impact.canDeleteDocuments) Text(t("documents.deleteFolderDocumentsUnavailable"), color = Yuvomi.colors.textSecondary)
                Spacer(Modifier.size(4.dp))
                OutlinedButton(onClick = { vm.deleteFolder(FolderDeleteChoice.UNFILE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(t("documents.deleteFolderKeepDocuments", "count" to documents))
                }
                OutlinedButton(
                    onClick = { vm.deleteFolder(FolderDeleteChoice.DELETE) },
                    enabled = impact.canDeleteDocuments,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t("documents.deleteFolderWithDocuments", "count" to documents), color = if (impact.canDeleteDocuments) Yuvomi.colors.danger else Yuvomi.colors.textTertiary) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::cancelFolderDelete) { Text(t("common.cancel")) } },
    )
}

/**
 * `openDmsLinkModal`: admins search a connected DMS (Paperless, Papra) and link a document
 * into the list; an empty term lists the DMS's documents (#449).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DmsLinkSheet(vm: DocumentsViewModel, close: () -> Unit) {
    val state by vm.state.collectAsState()
    val accounts = state.meta.dmsAccounts
    if (accounts.isEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(accounts.first()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DmsItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    var linking by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account.id, query, attempt) {
        delay(300)
        searching = true
        failed = false
        val found = vm.dmsSearch(account.id, query)
        failed = found == null
        results = found
        searching = false
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("documents.linkFromDms"), style = MaterialTheme.typography.titleMedium)
            if (accounts.size > 1) {
                Dropdown(t("documents.dmsAccountLabel"), account.id.toString(), accounts.map { it.id.toString() to it.name }) { id ->
                    accounts.firstOrNull { it.id.toString() == id }?.let { account = it }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(t("documents.dmsSearchLabel")) },
                placeholder = { Text(t("documents.dmsSearchPlaceholder")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (account.provider == "paperless") Text(t("documents.dmsAsnHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            val list = results
            when {
                searching -> Text(t("documents.dmsSearching"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("documents.dmsSearchError"), modifier = Modifier.weight(1f))
                    TextButton(onClick = { attempt++ }) { Text(t("common.retry")) }
                }
                list.isNullOrEmpty() -> Text(t("documents.dmsNoResults"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                else -> LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    items(list, key = { it.key }) { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, tint = documentsTone, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val type = item.filename?.substringAfterLast('.', "")?.uppercase().orEmpty()
                                val sub = listOfNotNull(item.filename, type.takeIf { it.isNotEmpty() }).filter { it != item.title }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1)
                            }
                            TextButton(enabled = linking == null, onClick = {
                                linking = item.key
                                scope.launch {
                                    if (vm.dmsLink(account.id, item, YuvomiClient.newIdempotencyKey())) close()
                                    linking = null
                                }
                            }) { Text(t("documents.dmsLinkBtn")) }
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
