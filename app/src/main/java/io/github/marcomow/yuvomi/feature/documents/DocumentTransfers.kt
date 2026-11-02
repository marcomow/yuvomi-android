package io.github.marcomow.yuvomi.feature.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch

/**
 * Download, "Open with" and Share of the row and the viewer. Download is the system's
 * "save as" (no storage permission, no FileProvider); "Open with" and Share hand a private
 * copy to another app through the FileProvider (`DocumentFiles.handOff`).
 */
class DocumentTransfers(
    val save: (Document) -> Unit,
    val openWith: (Document) -> Unit,
    val share: (Document) -> Unit,
)

/** The save-as contract with the document's own type instead of a fixed one. */
private class SaveAs : ActivityResultContracts.CreateDocument("application/octet-stream") {
    var mime: String = "application/octet-stream"

    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).setType(mime)
}

@Composable
fun rememberDocumentTransfers(vm: DocumentsViewModel): DocumentTransfers {
    val context = LocalContext.current
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val contract = remember { SaveAs() }
    val pending = remember { arrayOfNulls<Document>(1) }
    val saver = rememberLauncherForActivityResult(contract) { uri: Uri? ->
        val doc = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val ok = DocumentFiles.save(context, uri, vm.bytes(doc, preview = false))
                shell.notify(strings.t(if (ok) "android.documents.saved" else "documents.fileReadError"))
            } catch (e: ApiProblem) {
                vm.report(e)
            }
        }
    }
    fun handOff(doc: Document, share: Boolean) {
        scope.launch {
            try {
                when (DocumentFiles.handOff(context, doc, vm.bytes(doc, preview = false), share)) {
                    DocumentFiles.Handoff.OPENED -> Unit
                    DocumentFiles.Handoff.NO_APP -> shell.notify(strings.t("android.documents.noApp"))
                    DocumentFiles.Handoff.UNAVAILABLE -> shell.notify(strings.t("android.documents.handoffUnavailable"))
                }
            } catch (e: ApiProblem) {
                vm.report(e)
            }
        }
    }
    return remember(vm) {
        DocumentTransfers(
            save = { doc ->
                pending[0] = doc
                contract.mime = DocumentFiles.mime(doc)
                runCatching { saver.launch(DocumentFiles.fileName(doc)) }.onFailure {
                    pending[0] = null
                    scope.launch { shell.notify(strings.t("android.documents.noApp")) }
                }
            },
            openWith = { handOff(it, share = false) },
            share = { handOff(it, share = true) },
        )
    }
}
