package io.github.marcomow.yuvomi.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * docs/spec/inventory.md, INV-R19 and R20: the two managers of the page, upstream's shared
 * `category-manager.js` on `/inventory/categories` (one level) and `/inventory/locations`
 * (places with sub-places, under the path segment `subcategories`).
 */
class InventoryManagerViewModel(
    private val container: AppContainer,
    private val host: InventoryHost,
    val locations: Boolean,
) : ViewModel() {

    /** One row of the manager: a category (key) or a place (id, parent). */
    data class Row(val key: String, val id: Int?, val parentId: Int?, val label: String, val children: List<Row> = emptyList())

    data class UiState(
        val rows: List<Row> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: InventoryApi? get() = container.session.connection?.client?.let(::InventoryApi)

    init {
        load()
    }

    private suspend fun fetch(api: InventoryApi): List<Row> = if (locations) {
        InventoryApi.decodeLocations(api.locations()).map { r ->
            Row(r.id.toString(), r.id, null, r.name, r.subcategories.map { c -> Row(c.id.toString(), c.id, r.id, c.name) })
        }
    } else {
        InventoryApi.decodeCategories(api.categories()).map { c -> Row(c.key, null, null, InventoryRules.categoryLabel(c, host.strings)) }
    }

    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(rows = fetch(api), loading = false, error = null) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(loading = false, error = InventoryRules.loadErrorSentence(e, host.strings)) }
            }
        }
    }

    private fun run(success: String?, block: suspend (InventoryApi) -> Unit) {
        val api = api ?: return
        if (!host.canWrite || state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(api)
                success?.let { host.notify(host.strings.t(it)) }
            } catch (e: ApiProblem) {
                host.notify(InventoryRules.managerError(e, host.strings))
            } finally {
                // The manager reloads after every change (`_notifyChanged`), and so do the list and the read view.
                runCatching { fetch(api) }.getOrNull()?.let { rows -> _state.update { it.copy(rows = rows) } }
                InventoryBus.changed()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun add(name: String, parent: Row? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        run("category.added") { api ->
            if (locations) api.createLocation(trimmed, parent?.id, YuvomiClient.newIdempotencyKey())
            else api.createCategory(trimmed, YuvomiClient.newIdempotencyKey())
        }
    }

    fun rename(row: Row, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == row.label) return
        run("category.renamed") { api ->
            if (locations) api.renameLocation(row.id!!, row.parentId, trimmed) else api.renameCategory(row.key, trimmed)
        }
    }

    fun move(row: Row, direction: Int) {
        val siblings = if (row.parentId == null) state.value.rows else state.value.rows.firstOrNull { it.id == row.parentId }?.children ?: return
        val i = siblings.indexOfFirst { it.key == row.key }
        val j = i + direction
        if (i == -1 || j !in siblings.indices) return
        val order = siblings.toMutableList().apply { add(j, removeAt(i)) }
        run(null) { api ->
            if (locations) api.reorderLocations(order.map { it.id!! }, row.parentId) else api.reorderCategories(order.map { it.key })
        }
    }

    /** "Other" cannot be deleted (`categoryOtherNotDeletable`); everything else can, items are kept. */
    fun deletable(row: Row): Boolean = locations || row.key != "other"

    fun delete(row: Row) {
        if (!deletable(row)) return
        run("category.deleted") { api ->
            if (locations) api.deleteLocation(row.id!!, row.parentId) else api.deleteCategory(row.key)
        }
    }
}
