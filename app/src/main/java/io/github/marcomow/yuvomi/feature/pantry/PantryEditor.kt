package io.github.marcomow.yuvomi.feature.pantry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** docs/spec/pantry.md, PANTRY-R13 (`openItemModal`, `saveItem`). */
class PantryEditorViewModel(
    private val container: AppContainer,
    /** The batch as its row shows it (a pending step included), or null for a new one. */
    val item: PantryItem?,
    categories: List<PantryCategory>,
) : ViewModel() {

    data class Form(
        val name: String = "",
        val quantity: String = "1",
        val unit: String = PantryUnits.DEFAULT,
        val locationId: Int? = null,
        val category: String = PantryView.DEFAULT_CATEGORY,
        val expiresOn: LocalDate? = null,
        val minQuantity: String = "",
        val notes: String = "",
        /** The minimum as the field first showed it: a save sends it only when it changed (PANTRY-R27). */
        val initialMinQuantity: String = "",
        /** The "more details" fold: open when editing a batch with a minimum or a note. */
        val detailsOpen: Boolean = false,
    )

    data class UiState(
        val form: Form,
        val nameMissing: Boolean = false,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val errorKey: String? = null,
    )

    private val _state = MutableStateFlow(UiState(initialForm(item, categories)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One key per new batch, reused when the member retries (ADR 0007). */
    private val idempotencyKey = YuvomiClient.newIdempotencyKey()

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), nameMissing = false, errorKey = null) }

    /** Returns null on success, or the failure; `saved` in the state turns true. */
    suspend fun save(): Throwable? {
        val fields = fieldsOf(state.value.form)
        if (fields == null) {
            _state.update { it.copy(nameMissing = true) }
            return null
        }
        val api = container.session.connection?.client?.let(::PantryApi) ?: return null
        _state.update { it.copy(saving = true, errorKey = null) }
        return try {
            if (item == null) api.create(fields, idempotencyKey) else api.update(item.id, fields)
            _state.update { it.copy(saving = false, saved = true) }
            null
        } catch (e: ApiProblem) {
            _state.update { it.copy(saving = false, errorKey = PantryText.errorKey(e)) }
            e
        }
    }

    fun saveAsync() {
        viewModelScope.launch { save() }
    }

    companion object {
        /** The payload exactly as `saveItem` builds it; null without a name. */
        fun fieldsOf(form: Form): PantryApi.ItemFields? {
            val name = form.name.trim()
            if (name.isEmpty()) return null
            val minRaw = form.minQuantity.trim()
            return PantryApi.ItemFields(
                name = name,
                quantity = PantryUnits.normalizeQuantity(PantryUnits.parse(form.quantity), fallback = 1.0),
                unit = form.unit,
                locationId = form.locationId,
                category = form.category,
                expiresOn = form.expiresOn?.toString(),
                minQuantity = if (minRaw.isEmpty()) null else PantryUnits.normalizeQuantity(PantryUnits.parse(minRaw), fallback = 0.0),
                notes = form.notes.trim().ifEmpty { null },
                // The minimum is the product's: a new batch, or a rename onto another product, must
                // not overwrite it with what the field happened to show.
                sendMinQuantity = minRaw != form.initialMinQuantity.trim(),
            )
        }

        fun initialForm(item: PantryItem?, categories: List<PantryCategory>): Form = if (item == null) {
            Form(category = categories.firstOrNull { it.name == PantryView.DEFAULT_CATEGORY }?.name ?: categories.firstOrNull()?.name ?: PantryView.DEFAULT_CATEGORY)
        } else {
            Form(
                name = item.name,
                quantity = plain(item.quantity),
                unit = item.unit,
                locationId = item.locationId,
                category = item.category ?: PantryView.DEFAULT_CATEGORY,
                expiresOn = item.expiresOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                minQuantity = item.minQuantity?.let(::plain).orEmpty(),
                notes = item.notes.orEmpty(),
                initialMinQuantity = item.minQuantity?.let(::plain).orEmpty(),
                detailsOpen = item.minQuantity != null || !item.notes.isNullOrEmpty(),
            )
        }

        /** `String(1.5)`: "1.5", and "2" rather than "2.0". */
        fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryEditorScreen(list: PantryViewModel, itemId: Int?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val stock = list.state.collectAsState().value.stock
    val item = remember(itemId) { itemId?.let(list::itemForEdit) }
    if (itemId != null && item == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val vm = viewModel(key = "pantry-editor-$itemId") { PantryEditorViewModel(shell.container, item, stock.categories) }
    val s by vm.state.collectAsState()
    val f = s.form

    LaunchedEffect(s.saved) {
        if (s.saved) {
            // `reloadAfterSave`: the location's name comes from the list's join, not the answer.
            list.refresh()
            close()
            shell.notify(strings.t(if (item == null) "pantry.created" else "pantry.updated"))
        }
    }
    LaunchedEffect(s.errorKey) { s.errorKey?.let { shell.notify(strings.t(it)) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (item == null) "pantry.addItem" else "common.editItem")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    TextButton(onClick = vm::saveAsync, enabled = !s.saving) { Text(t(if (item == null) "common.add" else "common.save")) }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = f.name,
                onValueChange = { v -> vm.edit { it.copy(name = v) } },
                label = { Text(t("common.nameLabel") + " *") },
                placeholder = { Text(t("pantry.namePlaceholder")) },
                isError = s.nameMissing,
                supportingText = if (s.nameMissing) ({ Text(t("common.nameRequired")) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = f.quantity,
                    onValueChange = { v -> vm.edit { it.copy(quantity = v) } },
                    label = { Text(t("pantry.quantityLabel")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Picker(
                    label = t("pantry.unitLabel"),
                    value = PantryUnits.unitLabel(f.unit, strings),
                    options = (PantryUnits.ALL + listOfNotNull(f.unit.takeIf { it !in PantryUnits.ALL })).map { it to PantryUnits.unitLabel(it, strings) },
                    modifier = Modifier.weight(1f),
                ) { u -> vm.edit { it.copy(unit = u) } }
            }
            Picker(
                label = t("pantry.locationLabel"),
                value = stock.locations.firstOrNull { it.id == f.locationId }?.let { PantryText.locationLabel(it.name, strings) } ?: t("pantry.unlocated"),
                options = listOf<Pair<Int?, String>>(null to strings.t("pantry.unlocated")) +
                    stock.locations.map { it.id to PantryText.locationLabel(it.name, strings) },
            ) { id -> vm.edit { it.copy(locationId = id) } }
            Picker(
                label = t("pantry.categoryLabel"),
                value = PantryText.categoryLabel(f.category, strings),
                options = (stock.categories.map { it.name } + listOf(f.category).filter { c -> stock.categories.none { it.name == c } })
                    .map { it to PantryText.categoryLabel(it, strings) },
            ) { c -> vm.edit { it.copy(category = c) } }
            ExpiryField(f.expiresOn) { d -> vm.edit { it.copy(expiresOn = d) } }

            // `advancedSection`: minimum stock and note behind "More details".
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.edit { it.copy(detailsOpen = !it.detailsOpen) } }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("modal.moreDetails"), style = MaterialTheme.typography.titleSmall)
                    Text(t("pantry.moreDetailsHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Icon(if (f.detailsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            AnimatedVisibility(f.detailsOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = f.minQuantity,
                        onValueChange = { v -> vm.edit { it.copy(minQuantity = v) } },
                        label = { Text(t("pantry.minQuantityLabel")) },
                        supportingText = { Text(t("pantry.minQuantityHint")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = f.notes,
                        onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                        label = { Text(t("pantry.notesLabel")) },
                        placeholder = { Text(t("pantry.notesPlaceholder")) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (item != null) {
                // The fixed place of delete (`#pantry-delete`): close, then the undo snackbar of the list.
                OutlinedButton(
                    onClick = {
                        close()
                        list.delete(item)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                    Text(t("common.delete"), color = Yuvomi.colors.danger, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("pantry.expiresLabel"), modifier = Modifier.weight(1f))
            TextButton(onClick = { open = true }) {
                Text(value?.let { shell.formats.date(it, shell.time.today()) } ?: t("pantry.noBestBefore"))
            }
            if (value != null) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("datepicker.clear")) }
        }
        Text(t("pantry.expiresHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (value ?: shell.time.today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}
