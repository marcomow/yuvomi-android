package io.github.marcomow.yuvomi.feature.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * docs/spec/pantry.md, PANTRY-R20: the storage location manager, upstream's shared
 * `category-manager.js` as `openLocationManager` configures it.
 */
class PantryLocationsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val locations: List<PantryLocation> = emptyList(),
        val loading: Boolean = true,
        val errorKey: String? = null,
        val busy: Boolean = false,
    )

    /** What a change ended with: the confirmation or the failure, as an i18n key. */
    data class Outcome(val key: String, val ok: Boolean)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api get() = container.session.connection?.client?.let(::PantryApi)
    private var addKey = YuvomiClient.newIdempotencyKey()

    init {
        load()
    }

    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(locations = api.locations(), loading = false, errorKey = null) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(loading = false, errorKey = if (it.locations.isEmpty()) PantryText.errorKey(e) else null) }
            }
        }
    }

    private suspend fun change(deleting: Boolean = false, okKey: String, block: suspend (PantryApi) -> Unit): Outcome? {
        val api = api ?: return null
        if (state.value.busy) return null
        _state.update { it.copy(busy = true) }
        return try {
            block(api)
            Outcome(okKey, true)
        } catch (e: ApiProblem) {
            // A location someone else deleted meanwhile: reload, as the manager's `_fail` does.
            if (e is ApiProblem.Http && e.status == 404) load()
            Outcome(PantryText.locationErrorKey(e, deleting), false)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    suspend fun add(name: String): Outcome? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return change(okKey = "category.added") { api ->
            val created = api.createLocation(trimmed, addKey)
            addKey = YuvomiClient.newIdempotencyKey()
            _state.update { it.copy(locations = it.locations + created) }
        }
    }

    /** `_rename`: nothing is sent when the name is empty or what the row already says. */
    suspend fun rename(location: PantryLocation, name: String, shownLabel: String): Outcome? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == shownLabel) return null
        return change(okKey = "category.renamed") { api ->
            val renamed = api.renameLocation(location.id, trimmed)
            _state.update { s -> s.copy(locations = s.locations.map { if (it.id == renamed.id) renamed else it }) }
        }
    }

    /** `_move`: one step up (-1) or down (+1); the answer carries the whole new order. */
    suspend fun move(location: PantryLocation, delta: Int): Outcome? {
        val list = state.value.locations.toMutableList()
        val from = list.indexOfFirst { it.id == location.id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return null
        list.add(to, list.removeAt(from))
        return change(okKey = "") { api ->
            val fresh = api.reorderLocations(list.map { it.id })
            _state.update { it.copy(locations = fresh) }
        }
    }

    suspend fun delete(location: PantryLocation): Outcome? = change(deleting = true, okKey = "category.deleted") { api ->
        api.deleteLocation(location.id)
        _state.update { s -> s.copy(locations = s.locations.filter { it.id != location.id }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryLocationsScreen(changed: () -> Unit, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm = viewModel(key = "pantry-locations") { PantryLocationsViewModel(shell.container) }
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<PantryLocation?>(null) }
    var deleting by remember { mutableStateOf<PantryLocation?>(null) }

    fun run(block: suspend () -> PantryLocationsViewModel.Outcome?, after: () -> Unit = {}) {
        scope.launch {
            val outcome = block() ?: return@launch
            if (outcome.ok) {
                after()
                changed()
            }
            if (outcome.key.isNotEmpty()) shell.notify(strings.t(outcome.key))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("pantry.manageLocations")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.close")) } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        when {
            s.loading -> LoadingBox(Modifier.padding(padding))
            s.errorKey != null -> ErrorBox(strings.t(s.errorKey!!), onRetry = vm::load, modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().imePadding()) {
                item(key = "hint") {
                    Text(
                        t("pantry.manageLocationsHint"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Yuvomi.colors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                itemsIndexed(s.locations, key = { _, loc -> loc.id }) { index, loc ->
                    LocationRow(
                        label = PantryText.locationLabel(loc.name, strings),
                        first = index == 0,
                        last = index == s.locations.lastIndex,
                        enabled = !s.busy,
                        onRename = { renaming = loc },
                        onMove = { delta -> run({ vm.move(loc, delta) }) },
                        onDelete = { deleting = loc },
                    )
                }
                item(key = "add") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(MAX_NAME) },
                            placeholder = { Text(t("pantry.addLocation")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { run({ vm.add(input) }) { input = "" } }),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { run({ vm.add(input) }) { input = "" } }, enabled = input.isNotBlank() && !s.busy) { Text(t("common.add")) }
                    }
                }
            }
        }
    }

    renaming?.let { loc ->
        val shown = PantryText.locationLabel(loc.name, strings)
        RenameDialog(shown, onDismiss = { renaming = null }) { name ->
            renaming = null
            run({ vm.rename(loc, name, shown) })
        }
    }
    deleting?.let { loc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("category.deleteConfirm", "name" to PantryText.locationLabel(loc.name, strings))) },
            text = { Text(t("pantry.locationDeleteConfirmDetail")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    run({ vm.delete(loc) })
                }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("common.cancel")) } },
        )
    }
}

/** Upstream's add field caps names at 60 characters (`addMaxLength`). */
private const val MAX_NAME = 60

@Composable
private fun LocationRow(
    label: String,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val strings: Strings = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        // The name itself is the rename target, as in the web app's manager.
        modifier = Modifier.clickable(enabled = enabled, onClick = onRename),
        headlineContent = { Text(label) },
        leadingContent = { Icon(Icons.Outlined.Place, null, tint = Yuvomi.colors.textSecondary) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = strings.t("common.moreActionsNamed", "name" to label))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(strings.t("category.renameNamed", "name" to label)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.t("category.moveUpNamed", "name" to label)) },
                        leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                        enabled = !first,
                        onClick = { menu = false; onMove(-1) },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.t("category.moveDownNamed", "name" to label)) },
                        leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                        enabled = !last,
                        onClick = { menu = false; onMove(1) },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.t("category.deleteNamed", "name" to label), color = Yuvomi.colors.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
    HorizontalDivider(color = Yuvomi.colors.border.copy(alpha = 0.5f))
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("category.renamePrompt")) },
        text = {
            Column {
                OutlinedTextField(value = value, onValueChange = { value = it.take(MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(t("common.save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}
