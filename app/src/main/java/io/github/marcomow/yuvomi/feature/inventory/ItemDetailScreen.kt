package io.github.marcomow.yuvomi.feature.inventory

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.documents.DocumentsRoutes
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

/** docs/spec/inventory.md, the read view (`openItemDetail`, `renderItemDetail`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(vm: ItemDetailViewModel, host: InventoryHost, back: () -> Unit, edit: (Int) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val date = dateFormatter()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(s.gone) { if (s.gone) back() }
    val mayChange = vm.mayChange
    val numbers = remember(strings.locale) { NumberFormat.getIntegerInstance(Locale.forLanguageTag(strings.locale)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.item?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    val item = s.item
                    if (item != null && mayChange) {
                        IconButton(onClick = { edit(item.id) }) { Icon(Icons.Outlined.Edit, t("common.edit")) }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        val item = s.item
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                item == null && s.loading -> LoadingBox()
                item == null -> ErrorBox(s.error ?: strings.t("common.errorGeneric"), onRetry = vm::load)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                    if (s.offline) OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone)))
                    val money = s.money?.money
                    val unit = InventoryRules.odometerUnitLabel(item.odometerUnit, strings)
                    ItemPhoto.decode(item.photoData)?.let { bitmap ->
                        Image(bitmap, t("inventory.photoLabel"), contentScale = ContentScale.Crop, modifier = Modifier.padding(16.dp).size(160.dp).clip(RoundedCornerShape(16.dp)).align(Alignment.CenterHorizontally))
                    }
                    Section(null) {
                        Field(t("inventory.categoryLabel"), InventoryRules.itemCategoryLabel(item, strings))
                        Field(t("inventory.locationLabel"), item.locationPath)
                    }
                    Section(t("inventory.detailGroupPurchase")) {
                        Field(t("inventory.brandLabel"), item.brand)
                        Field(t("inventory.modelLabel"), item.model)
                        Field(t("inventory.serialNumberLabel"), item.serialNumber)
                        Field(t("inventory.purchaseDateLabel"), item.purchaseDate?.let { dateText(it) })
                        if (host.showsPrices) Field(t("inventory.purchasePriceLabel"), item.purchasePrice?.let { money?.format(it, item.currency) ?: it.toString() })
                        Field(t("inventory.vendorLabel"), item.vendor)
                        Field(t("inventory.accountUsernameLabel"), item.accountUsername)
                    }
                    Section(t("inventory.detailGroupWarranty")) {
                        Field(t("documents.category.warranty"), InventoryRules.warrantyText(item, s.today, date, strings).ifEmpty { null })
                        if (item.trackedDates.isNotEmpty()) {
                            Label(t("inventory.trackedDatesLabel"))
                            item.trackedDates.forEach { d ->
                                TrackedDateRow(d, s, unit, numbers, readOnly = !host.canWrite, onDone = if (mayChange && InventoryRules.isDue(d, s.today)) ({ vm.startCompletion(d) }) else null)
                            }
                        }
                    }
                    Section(t("inventory.detailGroupCondition")) {
                        Field(t("inventory.conditionLabel"), InventoryRules.conditionLabel(item.condition, strings))
                        Field(t("inventory.statusLabel"), InventoryRules.statusLabel(item.status, strings))
                        Field(t("inventory.odometerLabel"), item.odometer?.let { v -> "${numbers.format(v)} $unit" + (item.odometerOn?.let { " · ${dateText(it)}" } ?: "") })
                        Field(t("inventory.notesLabel"), item.notes)
                    }
                    Section(t("inventory.detailGroupRecords")) {
                        if (item.linkedEntries.isNotEmpty()) {
                            Label(t("inventory.linkedBookingsLabel"))
                            item.linkedEntries.forEach { link ->
                                Line("${link.title} · ${money?.format(link.amount, s.money?.currency) ?: link.amount}", "${InventoryRules.roleLabel(link.role, strings)} · ${dateText(link.date)}")
                            }
                        }
                        val attachments = if (host.showsDocuments) item.attachments.filter { it.documentId != null } else emptyList()
                        if (attachments.isNotEmpty()) {
                            Label(t("inventory.attachmentsLabel"))
                            attachments.forEach { a ->
                                Text(
                                    a.label,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().clickable { shell.navigate(DocumentsRoutes.document(a.documentId!!)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }
                        History(s, item, unit, numbers)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("common.delete") + ": " + s.item?.name.orEmpty()) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(t("common.delete"), color = Yuvomi.colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("common.cancel")) } },
        )
    }

    s.completing?.let { d -> CompletionDialog(vm, d, InventoryRules.tracksOdometer(s.item?.category ?: "", s.categories), s.busy, shell.time.today().toString()) }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    if (title != null) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    } else {
        Spacer(Modifier.height(8.dp))
    }
    Card(Modifier.padding(horizontal = 16.dp)) { Column(Modifier.padding(vertical = 6.dp)) { content() } }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))

/** One row of the read view; empty values are left out, as the detail view does. */
@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun Line(text: String, sub: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** `trackedDatesDetailNode`: label, date with countdown, distance, and "Done" while due. */
@Composable
private fun TrackedDateRow(d: TrackedDate, s: ItemDetailViewModel.UiState, unit: String, numbers: NumberFormat, readOnly: Boolean, onDone: (() -> Unit)?) {
    val strings = LocalStrings.current
    val countdown = InventoryRules.countdown(d.date, s.today, strings)
    val parts = buildList {
        add(if (countdown.isNotEmpty()) "${dateText(d.date)} · $countdown" else dateText(d.date))
        d.intervalDistance?.let { add(strings.t("inventory.trackedDateDistanceHint", "value" to numbers.format(it), "count" to it, "unit" to unit)) }
        // `trackedDateFormOnlyValues`: read-only members see what only the form shows.
        if (readOnly) {
            d.reminderOffsetDays?.let { add("${strings.t("inventory.trackedDateRemindBeforeLabel")}: $it") }
            d.intervalMonths?.let { add("${strings.t("inventory.trackedDateIntervalMonthsLabel")}: $it") }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.label, style = MaterialTheme.typography.bodyMedium)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        if (onDone != null) OutlinedButton(onClick = onDone) { Text(t("inventory.markDoneAction")) }
    }
}

/** `historyDetailNode`: the odometer chart, the timeline and the total of the bookings. */
@Composable
private fun History(s: ItemDetailViewModel.UiState, item: InventoryItem, unit: String, numbers: NumberFormat) {
    val strings = LocalStrings.current
    if (s.historyFailed) {
        Label(t("inventory.historyLabel"))
        Text(t("inventory.historyLoadError"), color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        return
    }
    val history = s.history
    val points = InventoryRules.odometerPoints(history, item)
    val timeline = history?.timeline.orEmpty()
    if (points.size < 2 && timeline.isEmpty()) return
    Label(t("inventory.historyLabel"))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OdometerChart(points, points.joinToString { "${it.date}: ${numbers.format(it.value)} $unit" })
        timeline.forEach { e ->
            val sub = buildList {
                add(dateText(e.date))
                if (e.type == "service_log") {
                    e.vendor?.takeIf { it.isNotBlank() }?.let(::add)
                    e.odometer?.let { add(strings.t("inventory.historyOdometerValue", "value" to numbers.format(it), "count" to it, "unit" to unit)) }
                    e.note?.takeIf { it.isNotBlank() }?.let(::add)
                } else if (e.type == "budget_entry") {
                    e.role?.let { add(InventoryRules.roleLabel(it, strings)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = MaterialTheme.typography.bodyMedium)
                    Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                if (e.type == "budget_entry" && e.amount != null) Text(s.money?.money?.format(e.amount, s.money.currency) ?: e.amount.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (history != null && history.total != 0.0) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(t("inventory.historyTotalLabel"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(s.money?.money?.format(history.total, s.money.currency) ?: history.total.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** `openCompletionSheet`: date (today), odometer where the category tracks it, vendor, note. */
@Composable
private fun CompletionDialog(vm: ItemDetailViewModel, d: TrackedDate, tracksOdometer: Boolean, busy: Boolean, today: String) {
    var performedOn by remember(d.id) { mutableStateOf<String?>(today) }
    var odometer by remember(d.id) { mutableStateOf("") }
    var vendor by remember(d.id) { mutableStateOf("") }
    var note by remember(d.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = vm::cancelCompletion,
        title = { Text(t("inventory.completeSheetTitle", "label" to d.label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(t("inventory.completePerformedOnLabel"), performedOn, clearable = false) { performedOn = it }
                if (tracksOdometer) {
                    OutlinedTextField(
                        value = odometer,
                        onValueChange = { v -> odometer = v.filter { it.isDigit() } },
                        label = { Text(t("inventory.odometerLabel")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                OutlinedTextField(value = vendor, onValueChange = { vendor = it }, label = { Text(t("inventory.vendorLabel")) }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(t("inventory.notesLabel")) }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(onClick = { performedOn?.let { vm.complete(it, odometer, vendor, note) } }, enabled = !busy && performedOn != null) { Text(t("inventory.markDoneAction")) }
        },
        dismissButton = { TextButton(onClick = vm::cancelCompletion) { Text(t("common.cancel")) } },
    )
}
