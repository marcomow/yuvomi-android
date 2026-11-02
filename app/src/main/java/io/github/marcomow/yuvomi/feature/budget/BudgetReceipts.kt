package io.github.marcomow.yuvomi.feature.budget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** `isPreviewable` (`public/utils/document-preview.js`): images and PDFs open in the preview. */
internal fun previewable(mime: String?): Boolean = mime != null && (mime.startsWith("image/") || mime == "application/pdf")

/**
 * The receipts field (`renderDocumentAttachField`): linked documents with remove, link an
 * existing document, upload a new file (only with `documents: write`). Uploads wait for the save
 * (`commit()`), so a cancelled form leaves no orphan file.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptsField(vm: BudgetViewModel, receipts: List<Receipt>, canUpload: Boolean, onChange: (List<Receipt>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<List<BudgetDocument>?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "receipt"
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    Receipt.Pending(name, mime, bytes)
                }.getOrNull()
            }
            if (pending != null) onChange(receipts + pending)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("budget.receiptsLabel"), style = MaterialTheme.typography.labelLarge)
        Text(t("budget.receiptsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        if (receipts.isEmpty()) Text(t("documentAttach.emptyState"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            receipts.forEach { r ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(r.label, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp)) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            t("documentAttach.removeAction", "name" to r.label),
                            Modifier.size(16.dp).clickable { onChange(receipts - r) },
                        )
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { picking = vm.documents() } }) {
                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                Text(" " + t("documentAttach.pickAction"))
            }
            if (canUpload) {
                OutlinedButton(onClick = { pickFile.launch("*/*") }) {
                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                    Text(" " + t("documentAttach.uploadAction"))
                }
            }
        }
    }
    picking?.let { docs ->
        val linked = receipts.filterIsInstance<Receipt.Linked>().map { it.documentId }.toSet()
        var chosen by remember(docs) { mutableStateOf(emptySet<Int>()) }
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(t("documentAttach.pickerTitle")) },
            text = {
                val shown = docs.filter { it.id !in linked }
                if (shown.isEmpty()) {
                    Text(t("documentAttach.noDocuments"))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.id }) { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { chosen = if (d.id in chosen) chosen - d.id else chosen + d.id }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = d.id in chosen, onCheckedChange = { chosen = if (it) chosen + d.id else chosen - d.id })
                                Text(d.name.ifEmpty { d.originalName.orEmpty() })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = chosen.isNotEmpty(), onClick = {
                    onChange(receipts + docs.filter { it.id in chosen }.map { Receipt.Linked(it.id, it.name.ifEmpty { it.originalName.orEmpty() }, it.mimeType) })
                    picking = null
                }) { Text(t("documentAttach.confirmSelection")) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(t("common.cancel")) } },
        )
    }
}

/**
 * One receipt as a link (`attachmentLinksNode`): images and PDFs open in a native viewer (the
 * PDF through the platform's `PdfRenderer`), anything else in the browser's download.
 */
@Composable
fun ReceiptLink(vm: BudgetViewModel, receipt: Receipt.Linked) {
    val shell = LocalShell.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    Row(
        Modifier.fillMaxWidth().clickable {
            if (!previewable(receipt.mimeType)) {
                shell.openWeb(BudgetOperation.DOCUMENT_DOWNLOAD.path(mapOf("id" to receipt.documentId)))
                return@clickable
            }
            scope.launch {
                val bytes = vm.receipt(receipt.documentId, preview = true) ?: return@launch
                pages = withContext(Dispatchers.IO) { render(bytes, receipt.mimeType, context.cacheDir) }
                if (pages.isNullOrEmpty()) {
                    pages = null
                    shell.openWeb(BudgetOperation.DOCUMENT_DOWNLOAD.path(mapOf("id" to receipt.documentId)))
                }
            }
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp), tint = Yuvomi.colors.module("budget"))
        Text(" " + receipt.label, color = Yuvomi.colors.module("budget"))
    }
    pages?.let { list ->
        AlertDialog(
            onDismissRequest = { pages = null },
            title = { Text(receipt.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                }
            },
            confirmButton = { TextButton(onClick = { pages = null }) { Text(t("common.close")) } },
        )
    }
}

/** An image decodes directly; a PDF renders page by page from a temporary file. */
private fun render(bytes: ByteArray, mime: String?, cacheDir: File): List<ImageBitmap> = runCatching {
    if (mime == "application/pdf") {
        val file = File.createTempFile("receipt", ".pdf", cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    (0 until minOf(pdf.pageCount, 10)).map { i ->
                        pdf.openPage(i).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap.asImageBitmap()
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    } else {
        listOfNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap())
    }
}.getOrDefault(emptyList())

/**
 * The CSV export (`/api/v1/budget/export`): the server's file, saved where the member picks
 * through the system's document picker (no storage permission, no browser).
 */
@Composable
fun rememberCsvExport(vm: BudgetViewModel): (String, List<Pair<String, String>>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val query = pending
        pending = null
        if (uri == null || query == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = vm.export(query) ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            }
        }
    }
    return { name, query ->
        pending = query
        launcher.launch(name)
    }
}
