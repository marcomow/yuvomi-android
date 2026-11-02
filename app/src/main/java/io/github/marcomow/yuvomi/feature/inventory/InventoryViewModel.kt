package io.github.marcomow.yuvomi.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** docs/spec/inventory.md, the list (INV-R1 to R6, R12). */
class InventoryViewModel(
    private val container: AppContainer,
    private val host: InventoryHost,
    linkedCategory: String? = null,
) : ViewModel() {

    data class UiState(
        val items: List<InventoryItem> = emptyList(),
        val categories: List<InventoryCategory> = emptyList(),
        val locations: List<InventoryLocation> = emptyList(),
        val money: HouseholdMoney? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val error: String? = null,
        val query: String = "",
        /** The category chip; an address naming a category no item has falls back to all (`isShownCategory`). */
        val category: String? = null,
        val attention: Boolean = false,
        val pending: Set<Int> = emptySet(),
        val today: LocalDate = LocalDate.now(),
    ) {
        val offline: Boolean get() = cachedAt != null
        val shown: List<InventoryItem> by lazy { items.filter { it.id !in pending } }

        /** `isShownCategory`. */
        val activeCategory: String? get() = category?.takeIf { key -> shown.any { it.category == key } }
        val attentionCount: Int by lazy { InventoryRules.countDeadlines(shown, today) }
    }

    private val _state = MutableStateFlow(UiState(category = linkedCategory, today = host.today()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: InventoryApi? get() = container.session.connection?.client?.let(::InventoryApi)

    init {
        readCache()
        refresh()
        viewModelScope.launch { InventoryBus.version.drop(1).collect { refresh() } }
        viewModelScope.launch { InventoryBus.pending.collect { p -> _state.update { it.copy(pending = p) } } }
    }

    private fun readCache() {
        val items = container.cache.read(CACHE_ITEMS) ?: return
        runCatching {
            _state.update {
                it.copy(
                    items = InventoryApi.decodeItems(ApiJson.parseToJsonElement(items.json)),
                    categories = container.cache.read(CACHE_CATEGORIES)?.let { c -> InventoryApi.decodeCategories(ApiJson.parseToJsonElement(c.json)) } ?: emptyList(),
                    locations = container.cache.read(CACHE_LOCATIONS)?.let { l -> InventoryApi.decodeLocations(ApiJson.parseToJsonElement(l.json)) } ?: emptyList(),
                    loading = false,
                )
            }
        }
    }

    /** INV-R1: locations, categories, items and the household's currency together. */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        _state.update { it.copy(refreshing = pulled, error = null, loading = it.loading || it.error != null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val locations = async { api.locations() }
                    val categories = async { api.categories() }
                    val items = async { api.items() }
                    val money = async { InventoryBus.money(container, host.strings) }
                    val l = locations.await()
                    val c = categories.await()
                    val i = items.await()
                    container.cache.write(CACHE_LOCATIONS, l.toString())
                    container.cache.write(CACHE_CATEGORIES, c.toString())
                    container.cache.write(CACHE_ITEMS, i.toString())
                    val m = money.await()
                    _state.update {
                        it.copy(
                            items = InventoryApi.decodeItems(i),
                            categories = InventoryApi.decodeCategories(c),
                            locations = InventoryApi.decodeLocations(l),
                            money = m,
                            loading = false,
                            refreshing = false,
                            cachedAt = null,
                            today = host.today(),
                        )
                    }
                }
            } catch (e: ApiProblem) {
                val m = runCatching { InventoryBus.money(container, host.strings) }.getOrNull()
                _state.update {
                    val hasData = !it.loading || it.items.isNotEmpty()
                    it.copy(
                        money = it.money ?: m,
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_ITEMS)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        error = if (hasData) null else InventoryRules.loadErrorSentence(e, host.strings),
                    )
                }
                if (state.value.error == null && e !is ApiProblem.Network) host.notify(InventoryRules.loadErrorSentence(e, host.strings))
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setCategory(key: String?) = _state.update { it.copy(category = key) }

    fun toggleAttention() = _state.update { it.copy(attention = !it.attention) }

    /** `resetFilters`: the term and the attention filter go; the category stays. */
    fun resetFilters() = _state.update { it.copy(query = "", attention = false) }

    companion object {
        const val CACHE_ITEMS = "inventory-items"
        const val CACHE_CATEGORIES = "inventory-categories"
        const val CACHE_LOCATIONS = "inventory-locations"
    }
}
