package io.github.marcomow.yuvomi.feature.housekeeping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.documents.DocumentFiles
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingViewModel.Overlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The sheets and dialogs of the page (`openModal` calls of housekeeping.js). */
@Composable
internal fun HousekeepingOverlays(vm: HousekeepingViewModel, s: HousekeepingViewModel.UiState, text: HousekeepingText, writable: Boolean) {
    val close = { vm.show(null) }
    when (val o = s.overlay) {
        null -> Unit
        is Overlay.VisitReport -> VisitReportSheet(o.visit, vm, text, writable, close)
        is Overlay.VisitEdit -> VisitEditSheet(o.visit, vm, s, text, close)
        is Overlay.ConfirmPay -> Confirm(
            title = t("housekeeping.markPaidConfirm"),
            detail = t(if (o.visit.paymentTaskId != null) "housekeeping.markPaidConfirmDetailTask" else "housekeeping.markPaidConfirmDetail"),
            confirm = t("housekeeping.markPaid"),
            onConfirm = { vm.pay(o.visit) },
            onDismiss = { vm.show(if (o.closesReport) Overlay.VisitReport(o.visit) else null) },
        )
        is Overlay.ConfirmUnpay -> Confirm(
            title = t("housekeeping.markUnpaidConfirm"),
            detail = t("housekeeping.markUnpaidConfirmDetail"),
            confirm = t("housekeeping.markUnpaid"),
            onConfirm = { vm.unpay(o.visit) },
            onDismiss = { vm.show(if (o.closesReport) Overlay.VisitReport(o.visit) else null) },
        )
        Overlay.TaskCreate -> TaskCreateSheet(vm, s, close)
        is Overlay.TaskEdit -> TaskEditSheet(o.task, vm, s, close)
        is Overlay.WorkerEdit -> WorkerEditSheet(o.worker, vm, s, close)
        is Overlay.WorkerRead -> WorkerReadSheet(o.worker, vm, text, close)
    }
}

@Composable
private fun Confirm(title: String, detail: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f))
    }
}

// ── The visit report: the read view of a visit (openVisitReportModal) ──

@Composable
private fun VisitReportSheet(visit: Visit, vm: HousekeepingViewModel, text: HousekeepingText, writable: Boolean, close: () -> Unit) {
    val hidden = HousekeepingRules.amountsHidden(vm.canSeeAmounts, visit.totalAmount)
    val rateHidden = HousekeepingRules.amountsHidden(vm.canSeeAmounts, visit.dailyRate)
    val docs = vm.documentsAccess
    Sheet(t("housekeeping.visitReportDetails"), close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(visit.workerName ?: "HK", visit.workerAvatarColor, visit.workerAvatarData, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(visit.workerName ?: t("housekeeping.unnamedWorker"), fontWeight = FontWeight.SemiBold)
                if (!hidden) Text(t(HousekeepingRules.scheduleKey(visit.paymentSchedule)), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        }
        HorizontalDivider()
        Detail(t("housekeeping.visitDate"), "${text.date(visit.checkIn, weekday = true)} · ${if (hidden) text.span(visit.checkIn, visit.checkOut) else text.time(visit.checkIn)}")
        // `visitWorkDetailsHtml()`.
        when {
            rateHidden -> if (visit.hourly && visit.minutesWorked != null) Detail(t("housekeeping.minutesWorked"), minutes(visit.minutesWorked))
            visit.hourly -> {
                Detail(t("housekeeping.minutesWorked"), minutes(visit.minutesWorked ?: 0.0))
                Detail(t("housekeeping.computedAmount"), text.money(visit.dailyRate))
            }
            else -> Detail(t("housekeeping.dailyRate"), text.money(visit.dailyRate))
        }
        if (!hidden) {
            Detail(t("housekeeping.extras"), text.money(visit.extras))
            Detail(t("housekeeping.totalPayment"), text.money(visit.totalAmount))
            Detail(t("housekeeping.paymentStatus"), t(if (visit.paid) "housekeeping.paymentPaid" else "housekeeping.paymentPending"))
        }
        visit.paymentTaskId?.let { Detail(t("housekeeping.paymentTask"), "#$it") }
        visit.calendarEventId?.let { Detail(t("housekeeping.calendarEvent"), "#$it") }
        // `visitReceiptDetailHtml()`.
        if (docs != "none") {
            when {
                HousekeepingRules.receiptHidden(visit) -> Detail(t("housekeeping.receiptLabel"), t("documentAttach.lockedPrivate"))
                !visit.receiptDocumentName.isNullOrBlank() -> Detail(t("housekeeping.receiptLabel"), visit.receiptDocumentName)
            }
        }
        val editable = writable && visit.canEdit
        val payable = writable && visit.canMarkPaid
        val unpayable = writable && !visit.canMarkPaid && visit.canMarkUnpaid
        if (editable || payable || unpayable) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (editable) {
                    val edit = @Composable { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("common.edit")) }
                    if (payable) OutlinedButton(onClick = { vm.show(Overlay.VisitEdit(visit)) }) { edit() } else Button(onClick = { vm.show(Overlay.VisitEdit(visit)) }) { edit() }
                }
                if (payable) {
                    Button(onClick = { vm.show(Overlay.ConfirmPay(visit, closesReport = true)) }) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("housekeeping.markPaid"))
                    }
                } else if (unpayable) {
                    OutlinedButton(onClick = { vm.show(Overlay.ConfirmUnpay(visit, closesReport = true)) }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("housekeeping.markUnpaid"))
                    }
                }
            }
        }
    }
}

private fun minutes(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else value.toString()

// ── The visit editor (openVisitEditModal) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisitEditSheet(visit: Visit, vm: HousekeepingViewModel, s: HousekeepingViewModel.UiState, text: HousekeepingText, close: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currency = s.data.currency
    val locale = strings.locale
    val visitDay = remember(visit.id) { text.day(visit.checkIn) ?: LocalDate.now() }
    var date by remember(visit.id) { mutableStateOf(visitDay) }
    var minutesText by remember(visit.id) { mutableStateOf(minutes(visit.minutesWorked ?: 0.0)) }
    var rateText by remember(visit.id) { mutableStateOf(HousekeepingRules.amountToInput(visit.dailyRate, currency, locale)) }
    var extrasText by remember(visit.id) { mutableStateOf(HousekeepingRules.amountToInput(visit.extras, currency, locale)) }
    var receipt by remember(visit.id) { mutableStateOf<HousekeepingViewModel.Receipt?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var rateError by remember { mutableStateOf<String?>(null) }
    var extrasError by remember { mutableStateOf<String?>(null) }
    val amountsHidden = HousekeepingRules.amountsHidden(vm.canSeeAmounts, visit.dailyRate)
    val docs = vm.documentsAccess
    val receiptHidden = HousekeepingRules.receiptHidden(visit)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = DocumentFiles.read(context, uri)
            if (file == null) vm.tell(strings.t("documents.fileReadError")) else receipt = HousekeepingViewModel.Receipt(file.fileName, file.mime, file.bytes)
        }
    }

    Sheet(t("housekeeping.editVisit"), close) {
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${t("housekeeping.visitDate")}: ${text.date(date)}")
        }
        if (!amountsHidden) {
            if (visit.hourly) {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { v -> minutesText = v.filter { it.isDigit() } },
                    label = { Text(t("housekeeping.minutesWorked")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Detail(t("housekeeping.computedAmount"), text.money(HousekeepingRules.hourlyAmount(minutesText.toDoubleOrNull() ?: 0.0, visit.hourlyRate)))
            } else {
                AmountField(t("housekeeping.dailyRate"), rateText, rateError) { rateText = it; rateError = null }
            }
            AmountField(t("housekeeping.extras"), extrasText, extrasError) { extrasText = it; extrasError = null }
        }
        // `receiptFieldHtml()`: the three levels of the Documents right.
        when {
            docs != "none" && receiptHidden -> Detail(t("housekeeping.receiptLabel"), t("documentAttach.lockedPrivate"))
            docs == "write" -> OutlinedButton(onClick = {
                pick.launch(arrayOf("image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain", "text/csv"))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Receipt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("housekeeping.receiptUploadTitle"))
                    val selected = receipt?.let { strings.t("documents.selectedFileLabel", "name" to it.fileName) } ?: visit.receiptDocumentName
                    Text(selected ?: t("housekeeping.receiptUploadHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
            docs == "read" && !visit.receiptDocumentName.isNullOrBlank() -> Detail(t("housekeeping.receiptLabel"), visit.receiptDocumentName)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = close) { Text(t("common.cancel")) }
            Button(enabled = !busy, onClick = {
                // The amounts as text (D1): "1.200" under de is refused at the field, never 1.2.
                if (!amountsHidden) {
                    if (!visit.hourly) rateError = HousekeepingRules.amountProblem(rateText, currency, locale, visit.dailyRate)?.let { HousekeepingRules.amountProblemText(it, currency, locale, strings) }
                    extrasError = HousekeepingRules.amountProblem(extrasText, currency, locale, visit.extras)?.let { HousekeepingRules.amountProblemText(it, currency, locale, strings) }
                    if (rateError != null || extrasError != null) return@Button
                }
                busy = true
                scope.launch {
                    val edit = HousekeepingViewModel.VisitEdit(
                        date = date,
                        minutes = if (visit.hourly) minutesText.toDoubleOrNull() ?: 0.0 else null,
                        dailyRate = if (visit.hourly) null else HousekeepingRules.parseAmount(rateText, locale) ?: 0.0,
                        extras = HousekeepingRules.parseAmount(extrasText, locale) ?: 0.0,
                    )
                    val ok = vm.saveVisit(visit, edit, receipt)
                    busy = false
                    if (ok) close()
                }
            }) { Text(t("common.save")) }
        }
    }
    if (picking) {
        DayPicker(date, onPick = { date = it; picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun AmountField(label: String, value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                if (picked != null) onPick(picked) else onDismiss()
            }) { Text(t("common.apply")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    ) { DatePicker(state) }
}

// ── Chores: create with templates, edit with delete (openTaskCreateModal, openTaskEditModal) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCreateSheet(vm: HousekeepingViewModel, s: HousekeepingViewModel.UiState, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val templates = HousekeepingRules.remainingTemplates(s.data.templates, s.tasks) { HousekeepingRules.templateLabel(it, area = false, strings) }
    val run: (suspend () -> Boolean) -> Unit = { action ->
        // A double tap on a template added it twice.
        if (!busy) {
            busy = true
            scope.launch {
                val ok = action()
                busy = false
                if (ok) close()
            }
        }
    }
    Sheet(t("housekeeping.addTask"), close) {
        if (templates.isNotEmpty()) {
            Text(t("housekeeping.taskTemplates"), style = MaterialTheme.typography.titleSmall)
            Text(t("housekeeping.taskTemplatesHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            templates.forEach { template ->
                ListItem(
                    headlineContent = { Text(HousekeepingRules.templateLabel(template, area = false, strings)) },
                    supportingContent = {
                        Text("${HousekeepingRules.templateLabel(template, area = true, strings)} · ${strings.t("housekeeping.everyDays", "days" to template.frequencyDays)}")
                    },
                    trailingContent = { Icon(Icons.Outlined.Add, null) },
                    modifier = Modifier.clickable(enabled = !busy) { run { vm.createFromTemplate(template) } },
                )
            }
            HorizontalDivider()
        }
        Text(t("housekeeping.addCustomTask"), style = MaterialTheme.typography.titleSmall)
        TaskForm(null, s, busy, submitLabel = t("common.add"), onCancel = close, onDelete = null) { name, area, days ->
            run { vm.createTask(name, area, days) }
        }
    }
}

@Composable
private fun TaskEditSheet(task: DecayTask, vm: HousekeepingViewModel, s: HousekeepingViewModel.UiState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Sheet(t("housekeeping.editTask"), close) {
        TaskForm(task, s, busy, submitLabel = t("common.save"), onCancel = close, onDelete = { vm.deleteTask(task) }) { name, area, days ->
            busy = true
            scope.launch {
                val ok = vm.saveTask(task, name, area, days)
                busy = false
                if (ok) close()
            }
        }
    }
}

@Composable
private fun TaskForm(
    task: DecayTask?,
    s: HousekeepingViewModel.UiState,
    busy: Boolean,
    submitLabel: String,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSubmit: (String, String, Int) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var area by remember(task?.id) { mutableStateOf(task?.area.orEmpty()) }
    var days by remember(task?.id) { mutableStateOf((task?.frequencyDays ?: 7).toString()) }
    val areas = remember(s.tasks, s.data.templates) {
        HousekeepingRules.knownAreas(s.tasks, s.data.templates.map { HousekeepingRules.templateLabel(it, area = true, strings) })
    }
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(HousekeepingRules.NAME_MAX) },
        label = { Text("${t("housekeeping.taskName")} *") }, placeholder = { Text(t("housekeeping.taskNamePlaceholder")) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    AreaField(area, areas) { area = it.take(HousekeepingRules.AREA_MAX) }
    OutlinedTextField(
        value = days, onValueChange = { v -> days = v.filter { it.isDigit() }.take(5) },
        label = { Text("${t("housekeeping.taskFrequency")} *") },
        supportingText = { Text(t("common.everyNDays", "count" to (days.toIntOrNull() ?: 0))) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = strings.t("common.deleteNamed", "name" to (task?.name ?: "")) }) {
                Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("common.delete"), color = Yuvomi.colors.danger)
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(t("common.cancel")) }
        Button(
            enabled = !busy && HousekeepingRules.taskFormValid(name, area, days),
            onClick = { onSubmit(name.trim(), area.trim(), days.trim().toInt()) },
        ) { Text(submitLabel) }
    }
}

/** The area: a choice with free text (`<datalist>`, R24). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaField(value: String, areas: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matching = areas.filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }
    ExposedDropdownMenuBox(expanded = expanded && matching.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); expanded = true },
            label = { Text("${t("housekeeping.taskArea")} *") },
            placeholder = { Text(t("housekeeping.taskAreaPlaceholder")) },
            singleLine = true,
            trailingIcon = { if (areas.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded && matching.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matching.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}

// ── Profiles (openStaffModal, openStaffReadModal) ──

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkerEditSheet(worker: Worker?, vm: HousekeepingViewModel, s: HousekeepingViewModel.UiState, close: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currency = s.data.currency
    val locale = strings.locale
    var f by remember(worker?.id) { mutableStateOf(HousekeepingRules.workerForm(worker, currency, locale)) }
    var busy by remember { mutableStateOf(false) }
    var rateError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var more by remember(worker?.id) { mutableStateOf(worker != null && listOf(worker.phone, worker.email, worker.birthDate, worker.notes).any { !it.isNullOrBlank() }) }
    val picture = when (val c = f.avatarChange) {
        is HousekeepingRules.AvatarChange.Set -> c.dataUrl
        HousekeepingRules.AvatarChange.Remove -> null
        null -> worker?.avatarData
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { avatarDataUrl(context, uri) }
            if (dataUrl == null) vm.tell(strings.t("common.errorGeneric")) else f = f.copy(avatarChange = HousekeepingRules.AvatarChange.Set(dataUrl))
        }
    }

    Sheet(t(if (worker == null) "housekeeping.addWorker" else "housekeeping.editWorker"), close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .semantics { contentDescription = strings.t("housekeeping.profilePicture") }) {
                MemberAvatar(f.displayName.ifBlank { "HK" }, f.avatarColor, picture, size = 64.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = f.displayName, onValueChange = { f = f.copy(displayName = it.take(HousekeepingRules.WORKER_NAME_MAX)) },
                    label = { Text("${t("housekeeping.workerName")} *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = f.username, onValueChange = { f = f.copy(username = it.take(HousekeepingRules.USERNAME_MAX)); usernameError = null },
                    label = { Text(t("housekeeping.workerUsername")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = usernameError != null, supportingText = usernameError?.let { { Text(it) } },
                )
            }
        }
        Choice(t("housekeeping.rateType"), f.rateType, listOf("daily", "hourly"), { t(if (it == "hourly") "housekeeping.rateHourly" else "housekeeping.rateDaily") }) {
            f = f.copy(rateType = it); rateError = null
        }
        if (f.hourly) {
            AmountField(t("housekeeping.hourlyRate"), f.hourlyRate, rateError) { f = f.copy(hourlyRate = it); rateError = null }
        } else {
            AmountField(t("housekeeping.dailyRate"), f.dailyRate, rateError) { f = f.copy(dailyRate = it); rateError = null }
        }
        Choice(t("housekeeping.paymentSchedule"), f.paymentSchedule, HousekeepingRules.SCHEDULES, { t(HousekeepingRules.scheduleKey(it)) }) {
            f = f.copy(paymentSchedule = it)
        }
        // "More details" (`advancedSection`), open when one of them has a value.
        TextButton(onClick = { more = !more }) { Text(t("housekeeping.workerMoreHint")) }
        if (more) {
            OutlinedTextField(
                value = f.phone, onValueChange = { f = f.copy(phone = it) }, label = { Text(t("housekeeping.workerPhone")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.email, onValueChange = { f = f.copy(email = it) }, label = { Text(t("housekeeping.workerEmail")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                    val shown = f.birthDate?.let { vm.text.birthDate(it, f.birthYearUnknown) }
                    Text(if (shown != null) "${t("housekeeping.workerBirthDate")}: $shown" else t("housekeeping.workerBirthDate"))
                }
                if (f.birthDate != null) TextButton(onClick = { f = f.copy(birthDate = null, birthYearUnknown = false) }) { Text(t("common.delete")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("birthdays.yearUnknown"), modifier = Modifier.weight(1f))
                Switch(checked = f.birthYearUnknown, onCheckedChange = { f = f.copy(birthYearUnknown = it) }, enabled = f.birthDate != null)
            }
            ColorChoice(t("housekeeping.calendarColor"), f.calendarColor) { f = f.copy(calendarColor = it) }
            ColorChoice(t("housekeeping.profileColor"), f.avatarColor) { f = f.copy(avatarColor = it) }
            OutlinedTextField(
                value = f.notes, onValueChange = { f = f.copy(notes = it.take(HousekeepingRules.NOTES_MAX)) },
                label = { Text(t("housekeeping.workerNotes")) }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = close) { Text(t("common.cancel")) }
            Button(enabled = !busy && f.displayName.isNotBlank(), onClick = {
                rateError = HousekeepingRules.workerRateProblem(f, worker, currency, locale)?.let { HousekeepingRules.amountProblemText(it, currency, locale, strings) }
                usernameError = if (HousekeepingRules.usernameValid(f.username)) null else strings.t("android.housekeeping.usernameInvalid")
                if (rateError != null || usernameError != null) return@Button
                busy = true
                scope.launch {
                    val ok = vm.saveWorker(f, worker)
                    busy = false
                    if (ok) close()
                }
            }) { Text(t(if (worker == null) "common.add" else "common.save")) }
        }
    }
    if (picking) {
        val initial = f.birthDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.of(1990, 1, 1)
        DayPicker(initial, onPick = { f = f.copy(birthDate = it.toString()); picking = false }, onDismiss = { picking = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, display: @Composable (T) -> String, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = options.map { it to display(it) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels.firstOrNull { it.first == value }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            labels.forEach { (option, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onPick(option) }) }
        }
    }
}

/** `colorPickerHtml()`: the household palette as swatches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoice(label: String, value: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val palette = if (HousekeepingRules.COLORS.any { it.equals(value, ignoreCase = true) }) HousekeepingRules.COLORS else HousekeepingRules.COLORS + value
            palette.forEach { hex ->
                val selected = hex.equals(value, ignoreCase = true)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Tokens.parseColor(hex) ?: Color.Gray)
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { onPick(hex) }
                        .semantics { contentDescription = hex },
                    contentAlignment = Alignment.Center,
                ) { if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

/** The picked picture, square and small enough for `avatar_data` (`pickCroppedImage`, 768 KB at most). */
private fun avatarDataUrl(context: Context, uri: Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= AVATAR_PX && bounds.outHeight / (sample * 2) >= AVATAR_PX) sample *= 2
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val scaled = square.scale(AVATAR_PX, AVATAR_PX)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

private const val AVATAR_PX = 256

@Composable
private fun WorkerReadSheet(worker: Worker, vm: HousekeepingViewModel, text: HousekeepingText, close: () -> Unit) {
    Sheet(t("housekeeping.profileTitle"), close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(worker.displayName, worker.avatarColor, worker.avatarData, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Text(worker.displayName, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
        // An empty field gets no row: a dash would be a value that does not exist.
        worker.username?.takeIf { it.isNotBlank() }?.let { Detail(t("housekeeping.workerUsername"), it) }
        worker.phone?.takeIf { it.isNotBlank() }?.let { Detail(t("housekeeping.workerPhone"), it) }
        worker.email?.takeIf { it.isNotBlank() }?.let { Detail(t("housekeeping.workerEmail"), it) }
        worker.birthDate?.takeIf { it.isNotBlank() }?.let { Detail(t("housekeeping.workerBirthDate"), text.birthDate(it, worker.birthYearUnknown)) }
        if (!HousekeepingRules.amountsHidden(vm.canSeeAmounts, worker.dailyRate)) {
            Detail(t("housekeeping.rateType"), t(if (worker.hourly) "housekeeping.rateHourly" else "housekeeping.rateDaily"))
            if (worker.hourly) Detail(t("housekeeping.hourlyRate"), text.money(worker.hourlyRate)) else Detail(t("housekeeping.dailyRate"), text.money(worker.dailyRate))
            Detail(t("housekeeping.paymentSchedule"), t(HousekeepingRules.scheduleKey(worker.paymentSchedule)))
        }
        worker.calendarColor?.let { ColorDetail(t("housekeeping.calendarColor"), it) }
        worker.avatarColor?.let { ColorDetail(t("housekeeping.profileColor"), it) }
        worker.notes?.takeIf { it.isNotBlank() }?.let {
            Text(t("housekeeping.workerNotes"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
            Text(it)
        }
    }
}

@Composable
private fun ColorDetail(label: String, hex: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
        Box(Modifier.size(16.dp).clip(CircleShape).background(Tokens.parseColor(hex) ?: Color.Gray))
        Spacer(Modifier.width(6.dp))
        Text(hex, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f - 0.2f))
    }
}
