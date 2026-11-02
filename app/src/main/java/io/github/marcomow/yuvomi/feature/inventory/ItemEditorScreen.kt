package io.github.marcomow.yuvomi.feature.inventory

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** docs/spec/inventory.md, the form (`buildItemForm`, `saveItem`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(vm: ItemEditorViewModel, host: InventoryHost, close: () -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.state.collectAsState()
    LaunchedEffect(s.done) { if (s.done) close() }
    val f = s.form
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { ItemPhoto.encode(context, uri) }
            if (data == null) shell.notify(strings.t("common.errorGeneric")) else vm.edit { it.copy(photo = data) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (s.isEdit) "inventory.editItem" else "inventory.addItem")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    TextButton(onClick = vm::save, enabled = !s.saving && !s.loading && s.error == null) {
                        Text(if (s.saving) "…" else t(if (s.isEdit) "common.save" else "common.add"))
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.error != null -> ErrorBox(s.error!!, onRetry = vm::load)
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = f.name,
                        onValueChange = { v -> vm.edit { it.copy(name = v) } },
                        label = { Text(t("common.nameLabel") + " *") },
                        placeholder = { Text(t("inventory.namePlaceholder")) },
                        isError = s.nameError,
                        supportingText = if (s.nameError) ({ Text(t("common.nameRequired")) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Picker(
                        t("inventory.categoryLabel"),
                        s.categories.firstOrNull { it.key == f.category }?.let { InventoryRules.categoryLabel(it, strings) } ?: f.category,
                        s.categories.map { it.key to InventoryRules.categoryLabel(it, strings) },
                    ) { key -> vm.edit { it.copy(category = key) } }
                    val places = InventoryRules.locationOptions(s.locations, strings)
                    Picker(t("inventory.locationLabel"), places.firstOrNull { it.first == f.locationId }?.second ?: strings.t("inventory.unlocated"), places) { id ->
                        vm.edit { it.copy(locationId = id) }
                    }
                    Picker(t("inventory.statusLabel"), InventoryRules.statusLabel(f.status, strings), InventoryRules.STATUSES.map { it to InventoryRules.statusLabel(it, strings) }) { v ->
                        vm.edit { it.copy(status = v) }
                    }
                    val original = s.original
                    if (original != null && host.showsBudget) LinkedBookings(vm, original, s)

                    var more by remember {
                        mutableStateOf(
                            original != null && (!original.brand.isNullOrBlank() || !original.model.isNullOrBlank() || !original.serialNumber.isNullOrBlank() ||
                                !original.notes.isNullOrBlank() || original.photoData != null || original.attachments.isNotEmpty() || original.odometer != null ||
                                InventoryRules.tracksOdometer(original.category, s.categories)),
                        )
                    }
                    TextButton(onClick = { more = !more }) {
                        Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(t("modal.moreDetails"))
                            if (!more) Text(t("inventory.moreDetailsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        }
                    }
                    if (more) MoreDetails(vm, s, host, onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })

                    if (original != null && InventoryRules.mayChange(original, host.canWrite, host.userId, host.editOthers)) {
                        OutlinedButton(onClick = vm::delete, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                            Text(t("common.delete"), color = Yuvomi.colors.danger, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    s.picker?.let { BookingPicker(vm, it, s) }
    s.documents?.let { DocumentPicker(vm, it, s.attachments) }
}

@Composable
private fun LinkedBookings(vm: ItemEditorViewModel, item: InventoryItem, s: ItemEditorViewModel.UiState) {
    val strings = LocalStrings.current
    val money = s.money
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t("inventory.linkedBookingsLabel"), style = MaterialTheme.typography.labelLarge)
        if (item.linkedEntries.isEmpty()) {
            Text(t("inventory.noLinkedBookings"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        } else {
            item.linkedEntries.forEach { link ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(link.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${InventoryRules.roleLabel(link.role, strings)} · ${dateText(link.date)}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                    Text(money?.money?.format(link.amount, money.currency) ?: link.amount.toString())
                    IconButton(onClick = { vm.unlink(link) }) { Icon(Icons.Outlined.Close, t("inventory.removeBookingAction", "title" to link.title)) }
                }
            }
            Row {
                Text(t("inventory.totalLinkedLabel"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(money?.money?.format(item.linkedEntriesTotal, money.currency) ?: item.linkedEntriesTotal.toString())
            }
        }
        OutlinedButton(onClick = { vm.openPicker(includeRole = true) }) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Text(" " + t("inventory.addBooking"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreDetails(vm: ItemEditorViewModel, s: ItemEditorViewModel.UiState, host: InventoryHost, onPhoto: () -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = s.form
    val date = dateFormatter()
    DateField(t("inventory.purchaseDateLabel"), f.purchaseDate) { v -> vm.edit { it.copy(purchaseDate = v) } }
    if (host.showsPrices) {
        OutlinedTextField(
            value = f.price,
            onValueChange = { v -> vm.edit { it.copy(price = v) } },
            label = { Text(t("inventory.purchasePriceLabel")) },
            suffix = { Text(s.money?.money?.symbol(s.currency) ?: s.currency) },
            placeholder = { Text(s.money?.money?.placeholder(s.currency).orEmpty()) },
            isError = s.priceError != null,
            supportingText = s.priceError?.let { e -> { Text(e) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!s.isEdit && host.showsBudget) {
        Column {
            OutlinedButton(onClick = { vm.openPicker(includeRole = false) }) {
                Icon(Icons.Outlined.Link, null, Modifier.size(18.dp))
                Text(" " + t("inventory.linkBooking"))
            }
            s.pickedBooking?.let { b ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(t("inventory.pendingBookingLabel", "title" to b.title)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, t("inventory.removeBookingAction", "title" to b.title), Modifier.size(16.dp).clickable { vm.clearPickedBooking() }) },
                )
            }
        }
    }
    // The photo (`inventory-photo-editor`): tap to choose, remove.
    Text(t("inventory.photoLabel"), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val bitmap = remember(f.photo) { ItemPhoto.decode(f.photo) }
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onPhoto),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap, t("inventory.photoLabel"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Outlined.Image, t("inventory.photoLabel"))
        }
        if (f.photo != null) TextButton(onClick = { vm.edit { it.copy(photo = null) } }) { Text(t("inventory.removePhoto")) }
    }
    Field(t("inventory.brandLabel"), f.brand) { v -> vm.edit { it.copy(brand = v) } }
    Field(t("inventory.modelLabel"), f.model) { v -> vm.edit { it.copy(model = v) } }
    Field(t("inventory.serialNumberLabel"), f.serial) { v -> vm.edit { it.copy(serial = v) } }
    Field(t("inventory.vendorLabel"), f.vendor) { v -> vm.edit { it.copy(vendor = v) } }
    OutlinedTextField(
        value = f.account,
        onValueChange = { v -> vm.edit { it.copy(account = v.take(200)) } },
        label = { Text(t("inventory.accountUsernameLabel")) },
        supportingText = { Text(t("inventory.accountUsernameHint")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = f.warranty,
        onValueChange = { v -> vm.edit { it.copy(warranty = v.filter { c -> c.isDigit() }.take(3)) } },
        label = { Text(t("inventory.warrantyMonthsLabel")) },
        supportingText = InventoryRules.warrantyStatus(f.purchaseDate, f.warranty.toIntOrNull(), s.today)?.let { st -> { Text(InventoryRules.warrantyStatusText(st, date, strings)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Picker(t("inventory.conditionLabel"), InventoryRules.conditionLabel(f.condition, strings), InventoryRules.CONDITIONS.map { it to InventoryRules.conditionLabel(it, strings) }) { v ->
        vm.edit { it.copy(condition = v) }
    }
    if (s.tracksOdometer) {
        OutlinedTextField(
            value = f.odometer,
            onValueChange = { v -> vm.edit { it.copy(odometer = v.filter { c -> c.isDigit() }) } },
            label = { Text(t("inventory.odometerLabel")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Picker(t("inventory.odometerUnitLabel"), InventoryRules.odometerUnitLabel(f.odometerUnit, strings), listOf("km" to strings.t("inventory.odometerUnitKm"), "mi" to strings.t("inventory.odometerUnitMi"))) { v ->
            vm.edit { it.copy(odometerUnit = v) }
        }
        DateField(t("inventory.odometerOnLabel"), f.odometerOn) { v -> vm.edit { it.copy(odometerOn = v) } }
    }
    TrackedDates(vm, f.dates, s)
    OutlinedTextField(
        value = f.notes,
        onValueChange = { v -> vm.edit { it.copy(notes = v) } },
        label = { Text(t("inventory.notesLabel")) },
        placeholder = { Text(t("inventory.notesPlaceholder")) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    if (host.showsDocuments) {
        Picker(t("inventory.attachmentCategoryLabel"), strings.t("documents.category.${f.attachmentCategory}"), InventoryRules.DOCUMENT_CATEGORIES.map { it to strings.t("documents.category.$it") }) { v ->
            vm.edit { it.copy(attachmentCategory = v) }
        }
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val pending = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            ?: uri.lastPathSegment ?: "file"
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                        ItemEditorViewModel.Attachment.Pending(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
                    }.getOrNull()
                }
                if (pending != null) vm.setAttachments(s.attachments + pending)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("inventory.attachmentsLabel"), style = MaterialTheme.typography.labelLarge)
            Text(t("inventory.attachmentsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            if (s.attachments.isEmpty()) Text(t("documentAttach.emptyState"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.attachments.forEach { a ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(a.label, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Outlined.Close, t("documentAttach.removeAction", "name" to a.label), Modifier.size(16.dp).clickable { vm.setAttachments(s.attachments - a) }) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::loadDocuments) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                    Text(" " + t("documentAttach.pickAction"))
                }
                if (host.uploadsDocuments) {
                    OutlinedButton(onClick = { pickFile.launch("*/*") }) {
                        Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                        Text(" " + t("documentAttach.uploadAction"))
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = { onChange(it.take(100)) },
    label = { Text(label) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)

/** `trackedDateRowHtml`: label, date, days before, the repeat in months or distance, and remove; at most ten. */
@Composable
private fun TrackedDates(vm: ItemEditorViewModel, rows: List<InventoryRules.DateRow>, s: ItemEditorViewModel.UiState) {
    val strings = LocalStrings.current
    fun set(i: Int, change: (InventoryRules.DateRow) -> InventoryRules.DateRow) = vm.edit { f -> f.copy(dates = f.dates.toMutableList().also { it[i] = change(it[i]) }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("inventory.trackedDatesLabel"), style = MaterialTheme.typography.labelLarge)
        Text(t("inventory.trackedDatesHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        rows.forEachIndexed { i, r ->
            io.github.marcomow.yuvomi.core.design.Card {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = r.label,
                            onValueChange = { v -> set(i) { it.copy(label = v.take(100)) } },
                            placeholder = { Text(t("inventory.trackedDateLabelPlaceholder")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.edit { f -> f.copy(dates = f.dates.toMutableList().also { it.removeAt(i) }) } }) {
                            Icon(Icons.Outlined.Close, t("inventory.removeTrackedDateAction"))
                        }
                    }
                    DateField(InventoryRules.countdown(r.date, s.today, strings).ifEmpty { strings.t("inventory.completePerformedOnLabel") }, r.date) { v -> set(i) { it.copy(date = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(t("inventory.trackedDateRemindBeforeLabel"), r.offset, null, Modifier.weight(1f)) { v -> set(i) { it.copy(offset = v) } }
                        NumberField(t("inventory.trackedDateIntervalMonthsLabel"), r.intervalMonths, t("inventory.trackedDateIntervalMonthsPlaceholder"), Modifier.weight(1f)) { v -> set(i) { it.copy(intervalMonths = v) } }
                    }
                    NumberField(t("inventory.trackedDateIntervalDistanceLabel"), r.intervalDistance, t("inventory.trackedDateIntervalDistancePlaceholder"), Modifier.fillMaxWidth()) { v -> set(i) { it.copy(intervalDistance = v) } }
                }
            }
        }
        OutlinedButton(onClick = { vm.edit { it.copy(dates = it.dates + InventoryRules.DateRow()) } }, enabled = rows.size < InventoryRules.MAX_TRACKED_DATES) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Text(" " + t("inventory.addTrackedDate"))
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, placeholder: String?, modifier: Modifier, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(7)) },
    label = { Text(label) },
    placeholder = placeholder?.let { p -> { Text(p) } },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    singleLine = true,
    modifier = modifier,
)

/** `openBookingPicker`: month by month, booked entries only; a saved item asks the role next. */
@Composable
private fun BookingPicker(vm: ItemEditorViewModel, p: ItemEditorViewModel.Picker, s: ItemEditorViewModel.UiState) {
    val strings = LocalStrings.current
    val ym = YearMonth.parse(p.month)
    val monthLabel = "${ym.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag(strings.locale))} ${ym.year}"
    AlertDialog(
        onDismissRequest = vm::closePicker,
        title = { Text(t("inventory.bookingPickerTitle")) },
        text = {
            val picked = p.picked
            if (picked != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(picked.title, style = MaterialTheme.typography.bodyLarge)
                    Picker(t("inventory.roleLabel"), InventoryRules.roleLabel(p.role, strings), InventoryRules.ROLES.map { it to InventoryRules.roleLabel(it, strings) }) { vm.setRole(it) }
                }
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.shiftPicker(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, t("inventory.bookingPickerPrevMonth")) }
                        Text(monthLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { vm.shiftPicker(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, t("inventory.bookingPickerNextMonth")) }
                    }
                    when {
                        p.failed -> Text(t("common.errorGeneric"))
                        p.entries == null -> Text(t("common.loading"))
                        p.entries.isEmpty() -> Text(t("inventory.noBookingsThisMonth"))
                        else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(p.entries, key = { it.id }) { e ->
                                Row(Modifier.fillMaxWidth().clickable { vm.pickBooking(e) }.padding(vertical = 8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(dateText(e.date), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                                    }
                                    Text(s.money?.money?.format(e.amount, s.money.currency) ?: e.amount.toString())
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (p.picked != null) TextButton(onClick = vm::confirmLink) { Text(t("inventory.addBooking")) }
        },
        dismissButton = {
            if (p.picked != null) TextButton(onClick = { vm.pickBooking(null) }) { Text(t("common.back")) }
            else TextButton(onClick = vm::closePicker) { Text(t("common.cancel")) }
        },
    )
}

/** The document attach field's "Existing document" picker. */
@Composable
private fun DocumentPicker(vm: ItemEditorViewModel, docs: List<LinkableDocument>, attachments: List<ItemEditorViewModel.Attachment>) {
    val linked = attachments.filterIsInstance<ItemEditorViewModel.Attachment.Linked>().map { it.documentId }.toSet()
    var chosen by remember(docs) { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = vm::closeDocuments,
        title = { Text(t("documentAttach.pickerTitle")) },
        text = {
            val shown = docs.filter { it.id !in linked }
            if (shown.isEmpty()) {
                Text(t("documentAttach.noDocuments"))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { d ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (d.id in chosen) chosen - d.id else chosen + d.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = d.id in chosen, onCheckedChange = { chosen = if (it) chosen + d.id else chosen - d.id })
                            Text(d.name.ifEmpty { d.originalName.orEmpty() })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                vm.setAttachments(attachments + docs.filter { it.id in chosen }.map { ItemEditorViewModel.Attachment.Linked(it.id, it.name.ifEmpty { it.originalName.orEmpty() }) })
                vm.closeDocuments()
            }) { Text(t("documentAttach.confirmSelection")) }
        },
        dismissButton = { TextButton(onClick = vm::closeDocuments) { Text(t("common.cancel")) } },
    )
}
