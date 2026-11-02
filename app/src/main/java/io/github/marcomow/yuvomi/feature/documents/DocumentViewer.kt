package io.github.marcomow.yuvomi.feature.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `openDocumentViewer`: the document inline (image, PDF pages, text) or the way to a download,
 * its details, and stepping through the visible list. Full screen, as the web's `xl` dialog is
 * on a phone (DOCS-R14 to R19).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DocumentViewer(
    doc: Document,
    vm: DocumentsViewModel,
    transfers: DocumentTransfers,
    close: () -> Unit,
    edit: () -> Unit,
    actions: RowActions,
) {
    val s by vm.state.collectAsState()
    val shell = LocalShell.current
    val uri = LocalUriHandler.current
    val manage = vm.mayManage(doc)
    val (prev, next) = vm.neighbours(doc.id)
    val label = categoryLabel(doc.category)
    val dmsLink = DocumentRules.dmsLink(doc)

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.close")) } },
                        title = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = {
                            if (prev != null || next != null) {
                                IconButton(onClick = { prev?.let { vm.view(it.id) } }, enabled = prev != null) {
                                    Icon(Icons.Outlined.ChevronLeft, prev?.let { t("documents.viewerPrevNamed", "name" to it.name) })
                                }
                                IconButton(onClick = { next?.let { vm.view(it.id) } }, enabled = next != null) {
                                    Icon(Icons.Outlined.ChevronRight, next?.let { t("documents.viewerNextNamed", "name" to it.name) })
                                }
                            }
                            if (manage) IconButton(onClick = edit) { Icon(Icons.Outlined.Edit, t("common.editNamed", "name" to doc.name)) }
                            IconButton(onClick = { transfers.save(doc) }) { Icon(Icons.Outlined.Download, t("documents.downloadAction")) }
                            ViewerMenu(doc, s, manage, dmsLink, transfers, actions, openUrl = { runCatching { uri.openUri(it) } })
                        },
                    )
                },
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetaText(categoryIcon(doc.category), label)
                        if (!doc.folderName.isNullOrEmpty() && !DocumentRules.folderRepeatsCategory(doc.folderName, label)) MetaText(Icons.Outlined.Folder, doc.folderName)
                        MetaText(null, DocumentRules.fileSize(doc.fileSize))
                        DocumentRules.dateStatus(doc.expiresAt, s.today)?.let { status ->
                            val text = t(DocumentRules.expiryViewerKey(status), "date" to shell.formats.date(status.endDate, s.today))
                            when (status.state) {
                                DocumentRules.DateState.VALID -> MetaText(Icons.Outlined.EventBusy, text)
                                DocumentRules.DateState.EXPIRED -> Badge(Icons.Outlined.EventBusy, text, Yuvomi.colors.danger)
                                DocumentRules.DateState.EXPIRING -> Badge(Icons.Outlined.EventBusy, text, Yuvomi.colors.warning)
                            }
                        }
                        DocumentRules.storageBadgeKey(doc)?.let { MetaText(null, t(it)) }
                    }
                    if (!manage) ReadDetails(doc, vm)
                    HorizontalDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ViewerBody(doc, vm, transfers)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaText(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, tint = Yuvomi.colors.textSecondary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** The viewer's overflow: open in the DMS, open with, share, and the manager's actions. */
@Composable
private fun ViewerMenu(
    doc: Document,
    s: DocumentsViewModel.UiState,
    manage: Boolean,
    dmsLink: String?,
    transfers: DocumentTransfers,
    actions: RowActions,
    openUrl: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to doc.name)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (dmsLink != null) DocumentMenuItem(t("documents.dmsOpenExternal"), Icons.AutoMirrored.Outlined.OpenInNew) { open = false; openUrl(dmsLink) }
            DocumentMenuItem(t("android.documents.openWith"), Icons.AutoMirrored.Outlined.OpenInNew) { open = false; transfers.openWith(doc) }
            DocumentMenuItem(t("documents.shareAction"), Icons.Outlined.Share) { open = false; transfers.share(doc) }
            DocumentMenuItem(t("documents.downloadAction"), Icons.Outlined.Download) { open = false; transfers.save(doc) }
            if (manage) {
                HorizontalDivider()
                DocumentMenuItem(t("documents.moveAction"), Icons.Outlined.Folder) { open = false; actions.move(doc) }
                DocumentMenuItem(t(if (doc.archived) "documents.restoreAction" else "documents.archiveAction"), Icons.Outlined.Archive) { open = false; actions.archive(doc) }
                if (DocumentRules.storageBackend(doc) != "dms" && s.meta.dmsAccounts.isNotEmpty()) {
                    DocumentMenuItem(t("documents.pushToDms"), Icons.AutoMirrored.Outlined.InsertDriveFile) { open = false; actions.pushToDms(doc) }
                }
                DocumentMenuItem(t("common.delete"), Icons.Outlined.Delete) { open = false; actions.delete(doc) }
            }
        }
    }
}

/**
 * `viewerDetailsHtml`: what only the edit dialog shows, as a read view for whoever may not
 * edit: description, visibility with the chosen people, reminder lead time, archived.
 */
@Composable
private fun ReadDetails(doc: Document, vm: DocumentsViewModel) {
    val shell = LocalShell.current
    val privacy = !shell.session.soloHousehold
    Column(Modifier.padding(horizontal = 16.dp)) {
        ReadRow(Icons.AutoMirrored.Outlined.Notes, t("documents.descriptionLabel"), doc.description.orEmpty())
        if (privacy) {
            ReadRow(
                if (doc.visibility == DocumentRules.VISIBILITY_PRIVATE) Icons.Outlined.Lock else Icons.Outlined.Group,
                t("documents.visibilityLabel"),
                t("documents.visibility.${doc.visibility.takeIf { it in DocumentRules.VISIBILITIES } ?: DocumentRules.VISIBILITY_FAMILY}"),
            )
            if (doc.visibility == DocumentRules.VISIBILITY_RESTRICTED) {
                ReadRow(Icons.Outlined.PersonPin, t("documents.allowedMembersLabel"), doc.allowedMemberIds.mapNotNull(vm::personName).joinToString(", "))
            }
        }
        if (!doc.expiresAt.isNullOrEmpty() && doc.expiryReminderDays != null) {
            ReadRow(Icons.Outlined.Notifications, t("documents.expiryReminderLabel"), doc.expiryReminderDays.toString())
        }
        if (doc.archived) ReadRow(Icons.Outlined.Archive, t("documents.statusLabel"), t("documents.statusArchived"))
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Image(val bitmap: ImageBitmap) : Loaded
    data class Pdf(val pdf: DocumentFiles.Pdf) : Loaded
    data class Text(val text: String) : Loaded
    data object Failed : Loaded
}

/** `renderViewerContent`: the right renderer for the type, else the download fallback. */
@Composable
private fun ViewerBody(doc: Document, vm: DocumentsViewModel, transfers: DocumentTransfers) {
    val kind = DocumentRules.previewKind(doc.mimeType)
    if (kind == null) {
        Fallback(doc, transfers, "documents.viewerDownloadHint")
        return
    }
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { 1600.dp.roundToPx() }
    val loaded by produceState<Loaded>(Loaded.Loading, doc.id, doc.updatedAt) {
        value = try {
            val bytes = vm.bytes(doc, preview = true)
            withContext(Dispatchers.Default) {
                when (kind) {
                    DocumentRules.PreviewKind.IMAGE -> DocumentFiles.decodeImage(bytes, maxPx)?.let { Loaded.Image(it) }
                    DocumentRules.PreviewKind.PDF -> DocumentFiles.Pdf.open(context, bytes)?.let { Loaded.Pdf(it) }
                    DocumentRules.PreviewKind.TEXT -> Loaded.Text(bytes.toString(Charsets.UTF_8))
                } ?: Loaded.Failed
            }
        } catch (e: ApiProblem) {
            if (e !is ApiProblem.Http || e.status != 415) vm.report(e)
            Loaded.Failed
        }
    }
    val current = loaded
    DisposableEffect(current) { onDispose { (current as? Loaded.Pdf)?.pdf?.close() } }
    when (current) {
        Loaded.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Loaded.Image -> ZoomableImage(current.bitmap, doc.name)
        is Loaded.Pdf -> PdfPages(current.pdf)
        is Loaded.Text -> SelectionContainer {
            Text(
                current.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp),
            )
        }
        Loaded.Failed -> Fallback(
            doc,
            transfers,
            when (kind) {
                DocumentRules.PreviewKind.IMAGE -> "documents.viewerImageFallbackHint"
                DocumentRules.PreviewKind.PDF -> "documents.viewerPdfFallbackHint"
                DocumentRules.PreviewKind.TEXT -> "documents.viewerDownloadHint"
            },
        )
    }
}

@Composable
private fun ZoomableImage(bitmap: ImageBitmap, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale == 1f) {
                    offsetX = 0f
                    offsetY = 0f
                } else {
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
        )
    }
}

/** The PDF's pages, drawn one by one as they scroll into view, with "Page n of total". */
@Composable
private fun PdfPages(pdf: DocumentFiles.Pdf) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val total = pdf.pageCount
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(total) { index ->
                val page by produceState<ImageBitmap?>(null, index) { value = withContext(Dispatchers.Default) { pdf.render(index, widthPx) } }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val p = page
                    if (p != null) {
                        Image(p, contentDescription = t("documents.viewerPdfPageLabel", "n" to index + 1, "total" to total), modifier = Modifier.fillMaxWidth().aspectRatio(p.width.toFloat() / p.height))
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.707f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    Text(t("documents.viewerPdfPageLabel", "n" to index + 1, "total" to total), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                }
            }
        }
    }
}

/** `renderViewerFallback`/`renderViewerUnsupported`: say why, and offer the file. */
@Composable
private fun Fallback(doc: Document, transfers: DocumentTransfers, hintKey: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = documentsTone, modifier = Modifier.size(48.dp))
        Text(doc.originalName ?: doc.name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(t(hintKey), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { transfers.openWith(doc) }) { Text(t("android.documents.openWith")) }
            androidx.compose.material3.Button(onClick = { transfers.save(doc) }) { Text(t("documents.downloadAction")) }
        }
    }
}
