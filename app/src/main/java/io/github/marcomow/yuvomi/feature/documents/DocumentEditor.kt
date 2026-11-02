package io.github.marcomow.yuvomi.feature.documents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `openDocumentModal`: upload (files first, the name comes from them) or the settings of one
 * document. Full screen, the files from the system picker (DOCS-R20 to R26).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DocumentEditor(doc: Document?, vm: DocumentsViewModel, close: () -> Unit) {
    val s by vm.state.collectAsState()
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember(doc?.id) { mutableStateOf(doc?.let(DocumentForm::of) ?: DocumentForm.new(s.filter.selectedFolder)) }
    var files by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var advanced by remember { mutableStateOf(doc != null && (!doc.description.isNullOrEmpty() || doc.archived)) }
    var picking by remember { mutableStateOf(false) }
    val max = s.meta.maxFileSize?.takeIf { it > 0 } ?: DocumentRules.DEFAULT_MAX_UPLOAD_BYTES

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val read = uris.mapNotNull { DocumentFiles.read(context, it) }
            if (read.size < uris.size) error = strings.t("documents.fileReadError")
            files = read
            keys = read.map { YuvomiClient.newIdempotencyKey() }
            // The size at once, not after the form is filled (`syncSelectedFile`).
            read.firstOrNull { it.size > max }?.let { error = strings.t("documents.fileTooLargeNamed", "name" to it.fileName) }
                ?: run { if (read.size == uris.size) error = null }
        }
    }

    fun save() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = vm.save(doc, form, files, keys) { current, total -> progress = current to total }
            busy = false
            progress = null
            when (result) {
                DocumentsViewModel.SaveResult.Saved, DocumentsViewModel.SaveResult.Gone -> close()
                is DocumentsViewModel.SaveResult.Problem -> {
                    error = strings.t(result.message.key, result.message.args)
                    if (result.folderGone) form = form.copy(folderId = null)
                }
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = close, enabled = !busy) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                        title = { Text(t(if (doc == null) "documents.newTitle" else "documents.editTitle")) },
                        actions = {
                            TextButton(onClick = ::save, enabled = !busy) {
                                val p = progress
                                Text(
                                    when {
                                        p != null && p.second > 1 -> t("documents.uploadProgress", "current" to p.first, "total" to p.second)
                                        doc == null -> t("documents.uploadAction")
                                        else -> t("common.save")
                                    },
                                )
                            }
                        },
                    )
                },
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (doc == null) {
                        Text(t("documents.fileLabel"), style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(
                            onClick = { runCatching { picker.launch(s.meta.allowedMimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()) } },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.UploadFile, null, Modifier.padding(end = 8.dp))
                            Text(
                                when (files.size) {
                                    0 -> t("documents.dropzoneTitleTouch")
                                    1 -> t("documents.selectedFileLabel", "name" to files.first().fileName)
                                    else -> t("documents.selectedFilesLabel", "count" to files.size)
                                },
                            )
                        }
                        Hint(t("documents.fileHint", "size" to DocumentRules.megabytes(max)))
                        if (vm.isAdmin) {
                            Hint(t("documents.activeUploadTarget", "target" to t(uploadTargetKey(s.meta.activeUploadBackend))))
                        }
                    }
                    // Several files each bring their own name; one shared name field would be meaningless.
                    if (files.size <= 1) {
                        OutlinedTextField(
                            value = form.name,
                            onValueChange = { form = form.copy(name = it.take(200)) },
                            label = { Text(t("documents.nameLabel") + if (doc != null) " *" else "") },
                            placeholder = if (doc == null) ({ Text(t("documents.namePlaceholder")) }) else null,
                            supportingText = if (doc == null) ({ Text(t("documents.nameHint")) }) else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Dropdown(
                        label = t("documents.categoryLabel"),
                        value = form.category,
                        options = DocumentRules.CATEGORIES.map { it to categoryLabelPlain(strings, it) },
                        onPick = { form = form.copy(category = it) },
                    )
                    Dropdown(
                        label = t("documents.folderLabel"),
                        value = form.folderId?.toString() ?: "",
                        options = listOf("" to t("documents.noFolder")) +
                            DocumentRules.flatten(s.shownFolders).map { it.folder.id.toString() to "  ".repeat(it.depth) + it.folder.name },
                        onPick = { form = form.copy(folderId = it.toIntOrNull()) },
                    )
                    // A household of one has one answer to "who may see this": not asked (`hidesPrivacyControls`).
                    if (!shell.session.soloHousehold) {
                        Dropdown(
                            label = t("documents.visibilityLabel"),
                            value = form.visibility,
                            options = DocumentRules.VISIBILITIES.map { it to strings.t("documents.visibility.$it") },
                            onPick = { form = form.copy(visibility = it) },
                        )
                        if (form.visibility == DocumentRules.VISIBILITY_RESTRICTED) {
                            Text(t("documents.allowedMembersLabel"), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                vm.pickerPeople(form.allowedMemberIds).forEach { person ->
                                    val on = person.id in form.allowedMemberIds
                                    FilterChip(
                                        selected = on,
                                        onClick = { form = form.copy(allowedMemberIds = if (on) form.allowedMemberIds - person.id else form.allowedMemberIds + person.id) },
                                        label = { Text(person.displayName) },
                                        leadingIcon = { MemberAvatar(person.displayName, person.avatarColor, null, size = 20.dp) },
                                    )
                                }
                            }
                        }
                    }
                    ExpiryField(form.expiresAt, onPick = { picking = true }, onClear = { form = form.copy(expiresAt = null) })
                    if (form.expiresAt != null) {
                        OutlinedTextField(
                            value = form.reminderDays,
                            onValueChange = { v -> form = form.copy(reminderDays = v.filter { it.isDigit() }.take(3)) },
                            label = { Text(t("documents.expiryReminderLabel")) },
                            placeholder = { Text(t("documents.expiryReminderPlaceholder")) },
                            supportingText = { Text(t("documents.expiryReminderHint")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // `advancedSection`: description and status, folded unless they hold something.
                    Row(
                        Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("documents.moreDetailsHint"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    if (advanced) {
                        OutlinedTextField(
                            value = form.description,
                            onValueChange = { form = form.copy(description = it.take(5000)) },
                            label = { Text(t("documents.descriptionLabel")) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Dropdown(
                            label = t("documents.statusLabel"),
                            value = form.status,
                            options = listOf(DocumentRules.STATUS_ACTIVE to t("documents.statusActive"), DocumentRules.STATUS_ARCHIVED to t("documents.statusArchived")),
                            onPick = { form = form.copy(status = it) },
                        )
                    }
                    error?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodyMedium) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = close, enabled = !busy) { Text(t("common.cancel")) }
                        Button(onClick = ::save, enabled = !busy) { Text(t(if (doc == null) "documents.uploadAction" else "common.save")) }
                    }
                }
            }
        }
    }

    if (picking) {
        val initial = form.expiresAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: shell.time.today()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { form = form.copy(expiresAt = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    picking = false
                }) { Text(t("common.apply")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

private fun categoryLabelPlain(strings: io.github.marcomow.yuvomi.core.i18n.Strings, category: String) = strings.t("documents.category.$category")

/** `uploadBackendLabel`. */
private fun uploadTargetKey(backend: String?) = when (backend) {
    "webdav" -> "documents.storageWebdav"
    "google_drive" -> "documents.storageGoogleDrive"
    "local_folder" -> "documents.storageLocalFolder"
    else -> "documents.storageLocal"
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)

@Composable
private fun ExpiryField(value: String?, onPick: () -> Unit, onClear: () -> Unit) {
    val shell = LocalShell.current
    val date = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            OutlinedTextField(
                // With the year always: `LocalDate.MIN` is never "this year".
                value = date?.let { shell.formats.date(it, LocalDate.of(1, 1, 1)) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(t("documents.expiresAtLabel")) },
                leadingIcon = { Icon(Icons.Outlined.EventBusy, null, Modifier.size(20.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Box(Modifier.matchParentSize().clickable(onClick = onPick))
        }
        if (date != null) IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, t("android.documents.clearDate")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second?.trim().orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onPick(key) }) }
        }
    }
}
