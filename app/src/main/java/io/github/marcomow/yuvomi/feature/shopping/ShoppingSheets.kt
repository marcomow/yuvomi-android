package io.github.marcomow.yuvomi.feature.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Opens the dialog or sheet for [o]. */
@Composable
internal fun ShoppingOverlay(
    o: Overlay,
    s: ShoppingViewModel.UiState,
    vm: ShoppingViewModel,
    rights: Rights,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val list = s.activeList
    when (o) {
        Overlay.NewList -> TextPrompt(t("shopping.newListPrompt"), "", close) { name -> scope.launch { if (vm.createList(name)) close() } }
        Overlay.RenameList -> if (list != null) TextPrompt(t("shopping.renameListPrompt"), list.name, close) { name ->
            scope.launch { if (vm.renameList(name)) close() }
        } else close()
        Overlay.Duplicate -> if (list != null) DuplicateDialog(list, vm, close) else close()
        Overlay.DeleteList -> if (list != null) {
            val count = s.items.size
            Confirm(
                question = if (count > 0) t("shopping.deleteListConfirm", "name" to list.name, "count" to count) else t("shopping.deleteListConfirmEmpty", "name" to list.name),
                detail = t("shopping.deleteListConfirmDetail"),
                confirm = t("common.delete"),
                danger = true,
                onDismiss = close,
            ) { close(); vm.deleteList() }
        } else close()
        Overlay.ClearChecked -> {
            val count = s.checkedItems.size
            if (count == 0) close() else Confirm(t("shopping.clearCheckedConfirm", "count" to count), null, t("common.delete"), false, close) {
                close()
                vm.clearChecked()
            }
        }
        Overlay.Categories -> CategoryManager(s, vm, close)
        Overlay.Stores -> StoreManager(s, vm, close)
        Overlay.MealImport -> MealImportDialog(vm, rights, close)
        is Overlay.EditItem -> {
            val item = s.items.firstOrNull { it.id == o.id }
            // SHOP-R37: the item is gone (deleted elsewhere): its editor closes.
            if (item == null) LaunchedEffect(o) { close() } else ItemEditor(item, s, vm, close)
        }
        is Overlay.ReadItem -> {
            val item = s.items.firstOrNull { it.id == o.id }
            if (item == null) LaunchedEffect(o) { close() } else ItemReadView(item, s, close)
        }
        is Overlay.Send -> SendDialog(o, s, vm, close)
        is Overlay.Pantry -> PantrySheet(o.stock, s, vm, rights, close)
    }
}

/** A single-line name prompt (`promptModal`). */
@Composable
internal fun TextPrompt(title: String, initial: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text(t("common.save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@Composable
internal fun Confirm(question: String, detail: String?, confirm: String, danger: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(question) },
        text = detail?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** A read-only text field that opens a menu of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Picker(
    label: String,
    value: T?,
    options: List<T>,
    display: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value?.let(display).orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; onPick(option) })
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** SHOP-R4 (`openDuplicateListDialog`). */
@Composable
private fun DuplicateDialog(list: ShoppingList, vm: ShoppingViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(strings.t("shopping.duplicateDefaultName", "name" to list.name)) }
    var reset by remember { mutableStateOf(true) }
    var quantities by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("shopping.duplicateListTitle", "name" to list.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text(t("shopping.duplicateListNameLabel")) },
                    isError = name.isBlank(), supportingText = if (name.isBlank()) ({ Text(t("common.nameRequired")) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(t("shopping.duplicateResetChecked"), reset) { reset = it }
                SwitchRow(t("shopping.duplicateKeepQuantities"), quantities) { quantities = it }
                SwitchRow(t("shopping.duplicateKeepNotes"), notes) { notes = it }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                scope.launch { if (vm.duplicateList(name, reset, quantities, notes)) close() else busy = false }
            }) { Text(t("shopping.duplicateSubmit")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}

/** SHOP-R28 (`openSendListDialog`). */
@Composable
private fun SendDialog(o: Overlay.Send, s: ShoppingViewModel.UiState, vm: ShoppingViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(o.recipients.first()) }
    var busy by remember { mutableStateOf(false) }
    // The list changed while the recipients loaded: this dialog no longer means the shown list.
    if (s.activeListId != o.listId) {
        LaunchedEffect(Unit) { close() }
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("shopping.sendListTitle")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("shopping.sendListDescription", "count" to s.openCount))
                Text(t("shopping.sendListRecipient"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                o.recipients.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = r == chosen, role = Role.RadioButton) { chosen = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = r == chosen, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(r.displayName)
                    }
                }
                Text(t("shopping.sendListSnapshotHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { if (vm.send(o.listId, chosen)) close() else busy = false }
            }) { Text(t("shopping.sendListSubmit")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}

/** SHOP-R29 (`openMealPlanImport`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealImportDialog(vm: ShoppingViewModel, rights: Rights, close: () -> Unit) {
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val today = shell.time.today()
    var from by remember { mutableStateOf(today) }
    var to by remember { mutableStateOf(today.plusDays(6)) }
    var preview by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(from, to) { preview = vm.mealPreview(from.toString(), to.toString()).orEmpty() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("shopping.importMealsTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateRow(t("calendar.fromLabel"), from) { picking = "from" }
                DateRow(t("calendar.toLabel"), to) { picking = "to" }
                if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val message = vm.importMeals(from.toString(), to.toString(), canUndo = rights.write)
                    busy = false
                    if (message == null) close() else if (message.isNotEmpty()) preview = message
                }
            }) { Text(t("common.apply")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
    picking?.let { which ->
        val initial = if (which == "from") from else to
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "from") from = d else to = d
                    }
                    picking = null
                }) { Text(t("common.apply")) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun DateRow(label: String, value: LocalDate, onClick: () -> Unit) {
    val shell = LocalShell.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(shell.formats.date(value, shell.time.today())) }
    }
}

/** SHOP-R18, SHOP-R19 (`openItemDetails`): the editor as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditor(item: ShoppingItem, s: ShoppingViewModel.UiState, vm: ShoppingViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val locale = Locale.forLanguageTag(strings.locale)
    var name by remember { mutableStateOf(item.name) }
    var qty by remember { mutableStateOf(item.quantity.orEmpty()) }
    var category by remember { mutableStateOf(item.category ?: ShoppingLogic.categoryOf(item, s.categories)) }
    var listId by remember { mutableIntStateOf(s.activeListId ?: item.listId ?: 0) }
    var price by remember { mutableStateOf(item.priceCents?.let { ShoppingLogic.centsToInput(it, s.currency, locale) }.orEmpty()) }
    var store by remember { mutableStateOf(s.stores.firstOrNull { it.id == item.storeId }?.name.orEmpty()) }
    var url by remember { mutableStateOf(item.url.orEmpty()) }
    var notes by remember { mutableStateOf(item.notes.orEmpty()) }
    var more by remember { mutableStateOf(item.priceCents != null || item.storeId != null || !item.url.isNullOrBlank() || !item.notes.isNullOrBlank()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val targets = s.lists.filter { it.id == s.activeListId || it.acceptsNewItems }

    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(t("common.editItem"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                name, { name = it; error = null }, label = { Text(t("common.nameLabel") + " *") }, singleLine = true,
                isError = error != null && name.isBlank(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(qty, { qty = it }, label = { Text(t("shopping.itemQtyLabel")) }, placeholder = { Text(t("shopping.itemQtyPlaceholder")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Picker(t("shopping.categoryLabel"), category, s.categories.map { it.name }, { ShoppingLogic.categoryLabel(it, strings) }, { category = it })
            if (targets.size > 1) {
                Picker(t("shopping.itemListLabel"), targets.firstOrNull { it.id == listId }, targets, { it.name }, { listId = it.id })
            }
            Row(
                Modifier.fillMaxWidth().clickable { more = !more }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("modal.moreDetails"), style = MaterialTheme.typography.titleSmall)
                    Text(t("shopping.moreDetailsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (more) {
                OutlinedTextField(
                    price, { price = it; error = null }, label = { Text(t("shopping.priceLabel")) },
                    placeholder = { Text(ShoppingLogic.centsToInput(0, s.currency, locale)) },
                    suffix = { Text(s.currency) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                StoreField(store, s.stores) { store = it }
                OutlinedTextField(
                    url, { url = it }, label = { Text(t("shopping.urlLabel")) }, placeholder = { Text(t("shopping.urlPlaceholder")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())) {
                    TextButton(onClick = { runCatching { uri.openUri(url.trim()) } }) { Text(t("shopping.openLink")) }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text(t("shopping.notesLabel")) }, placeholder = { Text(t("shopping.notesPlaceholder")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { close(); vm.delete(item) }) {
                    Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                    Spacer(Modifier.width(4.dp))
                    Text(t("common.delete"), color = Yuvomi.colors.danger)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = close) { Text(t("common.cancel")) }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val form = ShoppingViewModel.ItemForm(name, qty, category, listId, price, store, url, notes)
                        val problem = vm.save(item, form, locale)
                        busy = false
                        when {
                            problem == null -> close()
                            problem.isNotEmpty() -> { error = problem; if (name.isNotBlank()) more = true }
                        }
                    }
                }) { Text(t("common.save")) }
            }
        }
    }
}

/** The shop: pick a known one or type a new name, which is created on save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreField(value: String, stores: List<ShoppingStore>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matches = stores.filter { value.isBlank() || it.name.contains(value.trim(), ignoreCase = true) }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); expanded = true },
            label = { Text(t("shopping.storeLabel")) },
            placeholder = { Text(t("shopping.storePlaceholder")) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { st -> DropdownMenuItem(text = { Text(st.name) }, onClick = { onChange(st.name); expanded = false }) }
        }
    }
}

/** SHOP-R24 (`itemReadHtml`): the read view for members without write access. */
@Composable
private fun ItemReadView(item: ShoppingItem, s: ShoppingViewModel.UiState, close: () -> Unit) {
    val strings = LocalStrings.current
    val uri = LocalUriHandler.current
    val locale = Locale.forLanguageTag(strings.locale)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadRow(t("shopping.itemQtyLabel"), item.quantity)
                ReadRow(t("shopping.categoryLabel"), item.category?.let { ShoppingLogic.categoryLabel(it, strings) })
                ReadRow(t("shopping.priceLabel"), item.priceCents?.let { ShoppingLogic.formatCents(it, s.currency, locale) })
                ReadRow(t("shopping.storeLabel"), s.stores.firstOrNull { it.id == item.storeId }?.name)
                item.url?.takeIf { it.isNotBlank() }?.let { link ->
                    Column {
                        Text(t("shopping.urlLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                        Text(
                            link,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(link)) Modifier.clickable { runCatching { uri.openUri(link) } } else Modifier,
                        )
                    }
                }
                ReadRow(t("shopping.notesLabel"), item.notes)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(t("common.close")) } },
    )
}

@Composable
private fun ReadRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        io.github.marcomow.yuvomi.feature.tags.HashtagText(value)
    }
}

/** SHOP-R30, SHOP-R31 (`openPantryTransfer`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantrySheet(stock: PantryStock, s: ShoppingViewModel.UiState, vm: ShoppingViewModel, rights: Rights, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val checked = remember { s.checkedItems }
    val words = remember(strings) { ShoppingLogic.unitWords { ShoppingLogic.unitLabels(strings, it) } }
    val separator = remember(strings) { java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag(strings.locale)).decimalSeparator }
    val fallback = stock.locations.firstOrNull()?.id
    class Line(val item: ShoppingItem, qty: String, unit: String, place: Int?) {
        var qty by mutableStateOf(qty)
        var unit by mutableStateOf(unit)
        var place by mutableStateOf(place)
    }
    val rows = remember {
        mutableStateListOf<Line>().apply {
            checked.forEach { item ->
                val p = ShoppingLogic.preset(item, stock, words, fallback, separator)
                add(Line(item, formatQuantity(p.quantity), p.unit, p.locationId))
            }
        }
    }
    var clearList by remember { mutableStateOf(rights.write) }
    var busy by remember { mutableStateOf(false) }
    val places: List<PantryLocation?> = listOf<PantryLocation?>(null) + stock.locations
    val placeLabel = { l: PantryLocation? -> l?.let { ShoppingLogic.locationLabel(it.name, strings) } ?: strings.t("pantry.unlocated") }

    if (checked.isEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(t("shopping.toPantryTitle"), style = MaterialTheme.typography.titleLarge)
            Text(t("shopping.toPantryDescription", "count" to checked.size), style = MaterialTheme.typography.bodyMedium)
            var all by remember { mutableStateOf<PantryLocation?>(null) }
            var allChosen by remember { mutableStateOf(false) }
            Picker(
                t("shopping.toPantryAllTo"),
                if (allChosen) all else null,
                places,
                { placeLabel(it) },
                { l -> all = l; allChosen = true; rows.forEach { it.place = l?.id } },
            )
            if (!allChosen) Text(t("shopping.toPantryAllToPlaceholder"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            HorizontalDivider()
            rows.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.item.name, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            row.qty, { row.qty = it }, label = { Text(t("pantry.quantityLabel")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(0.7f),
                        )
                        Picker(t("pantry.unitLabel"), row.unit, ShoppingLogic.PANTRY_UNITS, { strings.t("pantry.units.$it") }, { row.unit = it }, Modifier.weight(1f))
                    }
                    Picker(t("pantry.locationLabel"), stock.locations.firstOrNull { it.id == row.place }, places, { placeLabel(it) }, { row.place = it?.id })
                }
            }
            if (rights.write) SwitchRow(t("shopping.toPantryClearList"), clearList) { clearList = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = close) { Text(t("common.cancel")) }
                Button(enabled = !busy, onClick = {
                    busy = true
                    val transfer = rows.map { r ->
                        val q = r.qty.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                        PantryTransferRow(r.item.id, q, r.unit, r.place)
                    }
                    scope.launch {
                        if (vm.toPantry(transfer, clearList && rights.write, stock.locations)) close() else busy = false
                    }
                }) { Text(t("common.apply")) }
            }
        }
    }
}

private fun formatQuantity(q: Double): String = if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()

/** SHOP-R25, SHOP-R27 (`openCategoryManager`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManager(s: ShoppingViewModel.UiState, vm: ShoppingViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<ShoppingCategory?>(null) }
    var deleting by remember { mutableStateOf<ShoppingCategory?>(null) }
    ManagerSheet(
        title = t("shopping.manageCategories"),
        hint = t("android.shopping.categoriesHint"),
        placeholder = t("category.addPlaceholder"),
        onAdd = { name -> vm.addCategory(name) },
        close = close,
    ) {
        s.categories.forEachIndexed { index, c ->
            val label = ShoppingLogic.categoryLabel(c.name, strings)
            ManagerRow(
                label,
                onRename = { renaming = c },
                renameLabel = t("category.renameNamed", "name" to label),
                onDelete = { deleting = c },
                deleteLabel = t("category.deleteNamed", "name" to label),
                onUp = if (index > 0) ({ scope.launch { vm.moveCategory(c, -1) } }) else null,
                onDown = if (index < s.categories.lastIndex) ({ scope.launch { vm.moveCategory(c, 1) } }) else null,
                upLabel = t("category.moveUpNamed", "name" to label),
                downLabel = t("category.moveDownNamed", "name" to label),
            )
        }
    }
    renaming?.let { c ->
        TextPrompt(t("category.renamePrompt"), c.name, { renaming = null }) { name ->
            scope.launch { if (vm.renameCategory(c, name)) renaming = null }
        }
    }
    deleting?.let { c ->
        Confirm(
            t("category.deleteConfirm", "name" to ShoppingLogic.categoryLabel(c.name, strings)),
            t("shopping.categoryDeleteConfirmDetail"),
            t("common.delete"),
            true,
            { deleting = null },
        ) {
            deleting = null
            scope.launch { vm.deleteCategory(c) }
        }
    }
}

/** SHOP-R26 (`openStoreManager`). */
@Composable
private fun StoreManager(s: ShoppingViewModel.UiState, vm: ShoppingViewModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<ShoppingStore?>(null) }
    var deleting by remember { mutableStateOf<ShoppingStore?>(null) }
    ManagerSheet(
        title = t("shopping.manageStores"),
        hint = t("shopping.storesHint"),
        placeholder = t("shopping.addStore"),
        onAdd = { name -> vm.addStore(name) },
        close = close,
    ) {
        s.stores.forEach { st ->
            ManagerRow(
                st.name,
                onRename = { renaming = st },
                renameLabel = t("category.renameNamed", "name" to st.name),
                onDelete = { deleting = st },
                deleteLabel = t("shopping.storeDeleteNamed", "name" to st.name),
            )
        }
    }
    renaming?.let { st ->
        TextPrompt(t("category.renamePrompt"), st.name, { renaming = null }) { name -> scope.launch { if (vm.renameStore(st, name)) renaming = null } }
    }
    deleting?.let { st ->
        Confirm(t("shopping.storeDeleteConfirm", "name" to st.name), t("shopping.storeDeleteConfirmDetail"), t("common.delete"), true, { deleting = null }) {
            deleting = null
            scope.launch { vm.deleteStore(st) }
        }
    }
}

/** The shared manager (`components/category-manager.js`): a hint, the rows, and an add field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerSheet(
    title: String,
    hint: String,
    placeholder: String,
    onAdd: suspend (String) -> Boolean,
    close: () -> Unit,
    rows: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            rows()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = name.isNotBlank(), onClick = { scope.launch { if (onAdd(name)) name = "" } }) { Text(t("common.add")) }
            }
        }
    }
}

@Composable
private fun ManagerRow(
    label: String,
    onRename: () -> Unit,
    renameLabel: String,
    onDelete: () -> Unit,
    deleteLabel: String,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    upLabel: String = "",
    downLabel: String = "",
    showOrder: Boolean = upLabel.isNotEmpty(),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).clickable(onClick = onRename).padding(vertical = 12.dp))
        if (showOrder) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Outlined.ArrowUpward, upLabel) }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Outlined.ArrowDownward, downLabel) }
        }
        IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, renameLabel) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, deleteLabel, tint = Yuvomi.colors.danger) }
    }
}
