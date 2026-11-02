package io.github.marcomow.yuvomi.feature.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant

/** What the screen asks about; one dialog or sheet at a time. */
internal sealed interface DocumentsDialog {
    data class Editor(val doc: Document?) : DocumentsDialog
    data object Folders : DocumentsDialog
    data class NewFolder(val parentId: Int?) : DocumentsDialog
    data class RenameFolder(val folder: DocumentFolder) : DocumentsDialog
    data class MoveFolder(val folder: DocumentFolder) : DocumentsDialog
    data class MoveDocuments(val docs: List<Document>) : DocumentsDialog
    data class ConfirmDelete(val docs: List<Document>) : DocumentsDialog
    data class PushToDms(val doc: Document) : DocumentsDialog
    data object DmsLink : DocumentsDialog
}

/** docs/spec/documents.md, the list (DOCS-R1 to R13). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(vm: DocumentsViewModel) {
    val shell = LocalShell.current
    val context = LocalContext.current
    val s by vm.state.collectAsState()
    val readOnly = vm.readOnly
    var dialog by remember { mutableStateOf<DocumentsDialog?>(null) }
    val transfers = rememberDocumentTransfers(vm)
    val thumbs = remember(vm) { DocumentThumbs(context.applicationContext) { vm.thumbnail(it) } }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (vm.lastLoaded != 0L && System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }

    val visible = s.visible
    val actions = remember(vm, transfers) {
        RowActions(
            view = { vm.view(it.id) },
            download = transfers.save,
            edit = { dialog = DocumentsDialog.Editor(it) },
            move = { dialog = DocumentsDialog.MoveDocuments(listOf(it)) },
            archive = vm::archive,
            pushToDms = { doc ->
                val accounts = vm.state.value.meta.dmsAccounts
                if (accounts.size == 1) vm.pushToDms(doc, accounts.first().id) else dialog = DocumentsDialog.PushToDms(doc)
            },
            delete = { dialog = DocumentsDialog.ConfirmDelete(listOf(it)) },
        )
    }
    val canSelect = !readOnly && s.shown.any(vm::mayManage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.selectMode) t("documents.selectCount", "count" to s.selected.size) else t("documents.title")) },
                navigationIcon = {
                    if (s.selectMode) IconButton(onClick = vm::exitSelect) { Icon(Icons.Outlined.Close, t("documents.selectDone")) }
                },
                actions = {
                    if (!s.selectMode && !readOnly && vm.isAdmin && s.meta.dmsAccounts.isNotEmpty()) {
                        IconButton(onClick = { dialog = DocumentsDialog.DmsLink }) { Icon(Icons.Outlined.Link, t("documents.linkFromDms")) }
                    }
                    ToolsMenu(s, vm, canSelect)
                    if (!s.selectMode) AccountButton()
                },
            )
        },
        floatingActionButton = {
            if (!readOnly && !s.selectMode) {
                FloatingActionButton(onClick = { dialog = DocumentsDialog.Editor(null) }) { Icon(Icons.Outlined.Add, t("documents.addButton")) }
            }
        },
        bottomBar = {
            if (s.selectMode) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("documents.selectCount", "count" to s.selected.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (s.selected.isNotEmpty()) {
                            OutlinedButton(onClick = { dialog = DocumentsDialog.MoveDocuments(vm.selectedDocuments()) }) { Text(t("documents.moveAction")) }
                            OutlinedButton(onClick = { dialog = DocumentsDialog.ConfirmDelete(vm.selectedDocuments()) }) {
                                Text(t("common.delete"), color = Yuvomi.colors.danger)
                            }
                        }
                        Button(onClick = vm::exitSelect) { Text(t("documents.selectDone")) }
                    }
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && s.documents.isEmpty() -> LoadingBox()
                s.errorKey != null && s.documents.isEmpty() -> ErrorBox(LocalStrings.current.t(s.errorKey!!), onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyVerticalGrid(
                        columns = if (s.grid) GridCells.Adaptive(168.dp) else GridCells.Fixed(1),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (s.grid) 12.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(if (s.grid) 12.dp else 0.dp),
                    ) {
                        header(s, vm, openFolders = { dialog = DocumentsDialog.Folders })
                        when {
                            s.listLoading -> full("loading") { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
                            visible.isEmpty() -> full("empty") {
                                EmptyView(s, vm, readOnly, upload = { dialog = DocumentsDialog.Editor(null) }, addFolder = { dialog = DocumentsDialog.NewFolder(s.filter.selectedFolder) })
                            }
                            s.grid -> items(visible, key = { it.id }) { doc ->
                                Box(Modifier.padding(horizontal = 6.dp)) {
                                    DocumentCard(doc, s, vm.mayManage(doc), thumbs, actions, onTap = { if (s.selectMode) vm.toggleSelected(doc) else vm.view(doc.id) })
                                }
                            }
                            else -> items(visible, key = { it.id }) { doc ->
                                DocumentRow(doc, s, vm.mayManage(doc), thumbs, actions, onTap = { if (s.selectMode) vm.toggleSelected(doc) else vm.view(doc.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    s.viewing?.let { id ->
        val doc = s.document(id)
        if (doc == null) {
            LaunchedEffect(id) { vm.view(null) }
        } else {
            DocumentViewer(
                doc = doc,
                vm = vm,
                transfers = transfers,
                close = { vm.view(null) },
                edit = { dialog = DocumentsDialog.Editor(doc) },
                actions = actions,
            )
        }
    }

    s.folderDelete?.let { (folder, impact) -> FolderDeleteDialog(folder, impact, vm) }

    when (val d = dialog) {
        null -> Unit
        is DocumentsDialog.Editor -> DocumentEditor(d.doc, vm, close = { dialog = null })
        DocumentsDialog.Folders -> FolderSheet(
            vm = vm,
            readOnly = readOnly,
            close = { dialog = null },
            open = { dialog = it },
        )
        is DocumentsDialog.NewFolder -> NewFolderDialog(vm, d.parentId, close = { dialog = null })
        is DocumentsDialog.RenameFolder -> RenameFolderDialog(d.folder, close = { dialog = null }) { vm.renameFolder(d.folder, it) }
        is DocumentsDialog.MoveFolder -> ChoiceDialog(
            title = t("documents.moveFolderTo", "name" to d.folder.name),
            choices = folderChoices(s.folders, "documents.folderRootLevel", DocumentRules.folderMoveTargets(s.folders, d.folder.id)),
            selected = d.folder.parentId?.toString() ?: "",
            onDismiss = { dialog = null },
        ) { value ->
            dialog = null
            vm.moveFolder(d.folder, value.toIntOrNull())
        }
        is DocumentsDialog.MoveDocuments -> ChoiceDialog(
            title = t("documents.moveToFolder"),
            choices = folderChoices(s.folders, "documents.noFolder"),
            selected = d.docs.singleOrNull()?.let { it.folderId?.toString() ?: "" },
            onDismiss = { dialog = null },
        ) { value ->
            dialog = null
            vm.move(d.docs, value.toIntOrNull())
        }
        is DocumentsDialog.ConfirmDelete -> ConfirmDialog(
            title = d.docs.singleOrNull()?.let { t("documents.deleteConfirm", "name" to it.name) } ?: t("documents.bulkDeleteConfirm", "count" to d.docs.size),
            detail = t("documents.bulkDeleteConfirmDetail"),
            confirm = t("common.delete"),
            onDismiss = { dialog = null },
        ) {
            dialog = null
            vm.delete(d.docs)
        }
        is DocumentsDialog.PushToDms -> ChoiceDialog(
            title = t("documents.pushToDms"),
            choices = s.meta.dmsAccounts.map { Choice(it.id.toString(), it.name) },
            onDismiss = { dialog = null },
        ) { value ->
            dialog = null
            value.toIntOrNull()?.let { vm.pushToDms(d.doc, it) }
        }
        DocumentsDialog.DmsLink -> DmsLinkSheet(vm, close = { dialog = null })
    }
}

/** The row and viewer actions, in one place so both offer the same. */
internal class RowActions(
    val view: (Document) -> Unit,
    val download: (Document) -> Unit,
    val edit: (Document) -> Unit,
    val move: (Document) -> Unit,
    val archive: (Document) -> Unit,
    val pushToDms: (Document) -> Unit,
    val delete: (Document) -> Unit,
)

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** Search, the status segment, the facet chips and the folder path above the list. */
private fun LazyGridScope.header(s: DocumentsViewModel.UiState, vm: DocumentsViewModel, openFolders: () -> Unit) {
    if (s.offline) {
        full("offline") {
            val shell = LocalShell.current
            OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
        }
    }
    full("search") {
        OutlinedTextField(
            value = s.filter.query,
            onValueChange = vm::setQuery,
            placeholder = { Text(t("documents.searchPlaceholder")) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (s.filter.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    full("status") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(DocumentRules.STATUS_ACTIVE to "documents.statusActive", DocumentRules.STATUS_ARCHIVED to "documents.statusArchived").forEachIndexed { i, (status, key) ->
                SegmentedButton(
                    selected = s.filter.status == status,
                    onClick = { vm.setStatus(status) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(t(key)) }
            }
        }
    }
    full("chips") { FacetChips(s, vm, openFolders) }
    s.filter.selectedFolder?.let { folderId ->
        val chain = DocumentRules.folderPath(s.shownFolders, folderId)
        if (chain.isNotEmpty()) full("path") { Breadcrumb(chain, vm) }
    }
}

/** `renderCategoryChips`, `renderExpiringChip` and the folder chip that leads the row. */
@Composable
private fun FacetChips(s: DocumentsViewModel.UiState, vm: DocumentsViewModel, openFolders: () -> Unit) {
    val folders = s.shownFolders
    val folderName = when (s.filter.folderId) {
        DocumentRules.FOLDER_ALL -> t("documents.allDocuments")
        DocumentRules.FOLDER_NONE -> t("documents.noFolder")
        else -> folders.firstOrNull { it.id.toString() == s.filter.folderId }?.name ?: t("documents.allDocuments")
    }
    val counts = DocumentRules.categoryCounts(s.shown, folders, s.filter)
    val present = DocumentRules.presentCategories(s.shown, folders, s.filter)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = s.filter.folderId.isNotEmpty(),
            onClick = openFolders,
            label = { Text(folderName, maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        PersonChip(s, vm)
        if (DocumentRules.showsExpiringChip(s.shown, folders, s.filter, s.today)) {
            FilterChip(
                selected = s.filter.expiringSoon,
                onClick = vm::toggleExpiring,
                label = { Text("${t("documents.expiringFilterLabel")}  ${DocumentRules.expiringCount(s.shown, folders, s.filter, s.today)}") },
                leadingIcon = { Icon(Icons.Outlined.EventBusy, null, Modifier.size(18.dp)) },
            )
        }
        // An empty view (the empty archive) gets no category facet, unless one is chosen.
        if (s.shown.isNotEmpty() || s.filter.category.isNotEmpty()) {
            FilterChip(
                selected = s.filter.category.isEmpty(),
                onClick = { vm.setCategory("") },
                label = { Text("${t("documents.allCategories")}  ${counts[""] ?: 0}") },
            )
            present.forEach { category ->
                FilterChip(
                    selected = s.filter.category == category,
                    onClick = { vm.setCategory(category) },
                    label = { Text("${categoryLabel(category)}  ${counts[category] ?: 0}") },
                    leadingIcon = { Icon(categoryIcon(category), null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/**
 * DOCS-R36, `renderPersonChip`: one chip with a menu instead of a chip per person, shown once a
 * loaded document names a person (or one is chosen).
 */
@Composable
private fun PersonChip(s: DocumentsViewModel.UiState, vm: DocumentsViewModel) {
    val people = DocumentRules.personFilterPeople(s.shown, s.members, s.filter.personId)
    if (people.isEmpty() && s.filter.personId == null) return
    val chosen = people.firstOrNull { it.id == s.filter.personId }
    var open by remember { mutableStateOf(false) }
    val label = t("documents.personFilterLabel")
    Box {
        FilterChip(
            selected = chosen != null,
            onClick = { open = true },
            label = { Text(chosen?.displayName ?: label, maxLines = 1) },
            leadingIcon = {
                if (chosen != null) {
                    MemberAvatar(chosen.displayName, chosen.avatarColor, chosen.avatarData, size = 20.dp)
                } else {
                    Icon(Icons.Outlined.Person, null, Modifier.size(18.dp))
                }
            },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.semantics { contentDescription = chosen?.let { "$label: ${it.displayName}" } ?: label },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = @Composable { id: Int?, text: String ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; vm.setPerson(id) },
                    leadingIcon = { if (id == s.filter.personId || (id == null && chosen == null)) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
                )
            }
            item(null, t("documents.personFilterAll"))
            people.forEach { item(it.id, it.displayName) }
        }
    }
}

/** `renderBreadcrumb`: every step but the last can be tapped. */
@Composable
private fun Breadcrumb(chain: List<DocumentFolder>, vm: DocumentsViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.selectFolder(DocumentRules.FOLDER_ALL) }) { Text(t("documents.allDocuments")) }
        chain.forEachIndexed { i, folder ->
            Text("/", color = Yuvomi.colors.textSecondary)
            if (i == chain.lastIndex) {
                Text(folder.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                TextButton(onClick = { vm.selectFolder(folder.id.toString()) }) { Text(folder.name) }
            }
        }
    }
}

/** The header's menu: view, sort key and direction, and the selection (`documentsToolsMenuHtml`). */
@Composable
private fun ToolsMenu(s: DocumentsViewModel.UiState, vm: DocumentsViewModel, canSelect: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, t("documents.toolsMenuLabel")) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            MenuLabel(t("documents.viewToggle"))
            listOf(false to "documents.listView", true to "documents.gridView").forEach { (grid, key) ->
                CheckItem(t(key), s.grid == grid) { vm.setGrid(grid) }
            }
            HorizontalDivider()
            MenuLabel(t("documents.sortLabel"))
            mapOf("updated" to "documents.sortUpdated", "name" to "documents.sortName", "size" to "documents.sortSize", "expiring" to "documents.sortExpiring")
                .forEach { (sort, key) -> CheckItem(t(key), s.sort == sort) { if (s.sort != sort) vm.setSort(sort) } }
            HorizontalDivider()
            MenuLabel(t("documents.sortDirectionLabel"))
            listOf("asc" to "documents.sortAscending", "desc" to "documents.sortDescending").forEach { (direction, key) ->
                CheckItem(t(key), s.direction == direction) { vm.setSort(s.sort, direction) }
            }
            if (!s.selectMode && canSelect) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(t("documents.selectLabel")) }, onClick = { open = false; vm.enterSelect() }, leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) })
            }
            if (s.selectMode) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(t("documents.selectAll")) }, onClick = { open = false; vm.toggleSelectAll() })
                val archived = s.filter.status == DocumentRules.STATUS_ARCHIVED
                DropdownMenuItem(
                    text = { Text(t(if (archived) "documents.restoreAction" else "documents.archiveAction")) },
                    enabled = s.selected.isNotEmpty(),
                    onClick = { open = false; vm.archiveSelected() },
                    leadingIcon = { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                )
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { if (checked) Icon(Icons.Outlined.CheckCircle, null) else Spacer(Modifier.size(24.dp)) },
    )
}

/** `renderListItem`: glyph or preview, name, meta, and download plus a menu for managers. */
@Composable
private fun DocumentRow(
    doc: Document,
    s: DocumentsViewModel.UiState,
    manage: Boolean,
    thumbs: DocumentThumbs,
    actions: RowActions,
    onTap: () -> Unit,
) {
    val selected = doc.id in s.selected
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.selectMode && manage) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = t("documents.selectDocument", "name" to doc.name),
                tint = if (selected) MaterialTheme.colorScheme.primary else Yuvomi.colors.textSecondary,
                modifier = Modifier.size(44.dp).padding(8.dp),
            )
        } else {
            DocumentThumb(doc, thumbs)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(doc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DocumentMetaLine(doc, s.filter.folderId, s.today, members = s.members)
        }
        if (!s.selectMode) {
            IconButton(onClick = { actions.download(doc) }) { Icon(Icons.Outlined.Download, t("documents.downloadNamed", "name" to doc.name)) }
            if (manage) RowMenu(doc, s, actions) else Spacer(Modifier.width(4.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = 72.dp), color = Yuvomi.colors.border)
}

/** `renderGridCard`: preview, name, description, meta and the actions. */
@Composable
private fun DocumentCard(
    doc: Document,
    s: DocumentsViewModel.UiState,
    manage: Boolean,
    thumbs: DocumentThumbs,
    actions: RowActions,
    onTap: () -> Unit,
) {
    val shell = LocalShell.current
    Card(Modifier.clickable(onClick = onTap)) {
        Column {
            Box {
                DocumentThumb(doc, thumbs, size = 168.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1.4f))
                if (s.selectMode && manage) {
                    Icon(
                        if (doc.id in s.selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = t("documents.selectDocument", "name" to doc.name),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp).size(28.dp),
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                doc.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DocumentMetaLine(doc, s.filter.folderId, s.today, withStats = false, members = s.members)
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    HouseholdTime.date(doc.updatedAt)?.let { shell.formats.date(it, s.today) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Yuvomi.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!s.selectMode) {
                    IconButton(onClick = { actions.download(doc) }) { Icon(Icons.Outlined.Download, t("documents.downloadNamed", "name" to doc.name)) }
                    if (manage) RowMenu(doc, s, actions)
                }
            }
        }
    }
}

/** `openDocumentMenu`: the actions only creator and admins may take. */
@Composable
internal fun RowMenu(doc: Document, s: DocumentsViewModel.UiState, actions: RowActions, includeView: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val canPush = DocumentRules.storageBackend(doc) != "dms" && s.meta.dmsAccounts.isNotEmpty()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to doc.name)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (includeView) DocumentMenuItem(t("documents.viewAction"), Icons.Outlined.Visibility) { open = false; actions.view(doc) }
            DocumentMenuItem(t("common.edit"), Icons.Outlined.Edit) { open = false; actions.edit(doc) }
            DocumentMenuItem(t("documents.moveAction"), Icons.AutoMirrored.Outlined.DriveFileMove) { open = false; actions.move(doc) }
            DocumentMenuItem(
                t(if (doc.archived) "documents.restoreAction" else "documents.archiveAction"),
                if (doc.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
            ) { open = false; actions.archive(doc) }
            if (canPush) DocumentMenuItem(t("documents.pushToDms"), Icons.Outlined.Upload) { open = false; actions.pushToDms(doc) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                onClick = { open = false; actions.delete(doc) },
            )
        }
    }
}

@Composable
internal fun DocumentMenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** `emptyStateFor`: five empty states, each naming its cause and the way out. */
@Composable
private fun EmptyView(s: DocumentsViewModel.UiState, vm: DocumentsViewModel, readOnly: Boolean, upload: () -> Unit, addFolder: () -> Unit) {
    val kind = EmptyKind.of(s.filter, s.shownFolders, s.otherStatusHits)
    when (kind) {
        is EmptyKind.Search -> {
            LaunchedEffect(s.filter.normalizedQuery, s.filter.status) { vm.probeOtherStatus() }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(t("common.searchNoResultsTitle"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                AssistChip(onClick = {}, label = { Text(s.filter.query.trim()) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) })
                TextButton(onClick = vm::clearSearch) { Text(t("common.searchReset")) }
                if (kind.otherStatusHits > 0) {
                    val active = s.filter.status == DocumentRules.STATUS_ACTIVE
                    OutlinedButton(onClick = { vm.setStatus(DocumentRules.otherStatus(s.filter.status)) }) {
                        Text(t(if (active) "documents.searchArchivedAction" else "documents.searchActiveAction"))
                    }
                }
                if (kind.resetFilters) OutlinedButton(onClick = vm::resetFilters) { Text(t("documents.resetFiltersAction")) }
            }
        }
        is EmptyKind.EmptyFolder -> EmptyBlock(
            icon = Icons.Outlined.FolderOpen,
            title = kind.name?.let { t("documents.emptyFolderTitle", "name" to it) } ?: t("documents.emptyFilterTitle"),
            buttons = if (readOnly) emptyList() else listOf(t("documents.emptyPrimary") to upload),
        )
        EmptyKind.NoMatch -> EmptyBlock(
            icon = Icons.Outlined.FolderOpen,
            title = t("documents.emptyFilterTitle"),
            body = t("documents.emptyFilterDescription"),
            buttons = listOf(t("documents.resetFiltersAction") to vm::resetFilters) + if (readOnly) emptyList() else listOf(t("documents.emptyPrimary") to upload),
        )
        EmptyKind.EmptyArchive -> EmptyBlock(
            icon = Icons.Outlined.Archive,
            title = t("documents.emptyArchivedTitle"),
            body = t("documents.emptyArchivedDescription"),
            buttons = listOf(t("documents.showActiveAction") to { vm.setStatus(DocumentRules.STATUS_ACTIVE) }),
        )
        EmptyKind.Nothing -> if (readOnly) {
            EmptyState(t("documents.emptyTitle"))
        } else {
            EmptyBlock(
                icon = Icons.Outlined.FolderOpen,
                title = t("documents.emptyTitle"),
                body = t("documents.emptyDescription"),
                buttons = listOf(t("documents.emptyPrimary") to upload, t("documents.emptySecondary") to addFolder),
            )
        }
    }
}

@Composable
private fun EmptyBlock(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String? = null, buttons: List<Pair<String, () -> Unit>>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, textAlign = TextAlign.Center)
        buttons.forEach { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
    }
}

@Composable
internal fun CreateFolderIcon() = Icon(Icons.Outlined.CreateNewFolder, t("documents.addFolderButton"))
