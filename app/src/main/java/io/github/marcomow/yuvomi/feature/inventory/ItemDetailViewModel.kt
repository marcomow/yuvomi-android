package io.github.marcomow.yuvomi.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** docs/spec/inventory.md, the read view (INV-R7 to R11). */
class ItemDetailViewModel(
    private val container: AppContainer,
    private val host: InventoryHost,
    private val id: Int,
) : ViewModel() {

    data class UiState(
        val item: InventoryItem? = null,
        val history: ItemHistory? = null,
        val historyFailed: Boolean = false,
        val categories: List<InventoryCategory> = emptyList(),
        val money: HouseholdMoney? = null,
        val loading: Boolean = true,
        val cachedAt: Long? = null,
        val error: String? = null,
        val gone: Boolean = false,
        /** The tracked date the completion sheet is open for. */
        val completing: TrackedDate? = null,
        val busy: Boolean = false,
        val today: LocalDate = LocalDate.now(),
    ) {
        val offline: Boolean get() = cachedAt != null
    }

    private val _state = MutableStateFlow(UiState(today = host.today()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: InventoryApi? get() = container.session.connection?.client?.let(::InventoryApi)
    private val cacheKey = "inventory-item-$id"
    private var completionKey = YuvomiClient.newIdempotencyKey()

    /** `mayChangeItem`. */
    val mayChange: Boolean get() = InventoryRules.mayChange(state.value.item, host.canWrite, host.userId, host.editOthers) && !state.value.offline

    init {
        readCache()
        load()
        viewModelScope.launch { InventoryBus.version.drop(1).collect { load() } }
    }

    private fun readCache() {
        val cats = container.cache.read(InventoryViewModel.CACHE_CATEGORIES)?.let { runCatching { InventoryApi.decodeCategories(ApiJson.parseToJsonElement(it.json)) }.getOrNull() }
        val own = container.cache.read(cacheKey)?.let { runCatching { ApiJson.decodeFromString(InventoryItem.serializer(), it.json) }.getOrNull() }
        val item = own ?: container.cache.read(InventoryViewModel.CACHE_ITEMS)?.let { e ->
            runCatching { InventoryApi.decodeItems(ApiJson.parseToJsonElement(e.json)) }.getOrNull()?.firstOrNull { it.id == id }
        }
        _state.update { it.copy(item = item, categories = cats.orEmpty(), loading = item == null) }
    }

    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val item = api.item(id)
                container.cache.write(cacheKey, ApiJson.encodeToString(InventoryItem.serializer(), item))
                _state.update { it.copy(item = item, loading = false, cachedAt = null, error = null, today = host.today()) }
                val money = InventoryBus.money(container, host.strings)
                val categories = runCatching { InventoryApi.decodeCategories(api.categories()) }.getOrNull()
                _state.update { it.copy(money = money, categories = categories ?: it.categories) }
                // `openItemDetail`: a failed history is said in its own row, the rest stands.
                val history = runCatching { api.history(id) }
                _state.update { it.copy(history = history.getOrNull(), historyFailed = history.isFailure) }
            } catch (e: ApiProblem) {
                if (InventoryRules.isGone(e, "inventory_item_not_found")) {
                    host.notify(InventoryRules.errorSentence(e, host.strings))
                    InventoryBus.changed()
                    _state.update { it.copy(gone = true) }
                    return@launch
                }
                val money = runCatching { InventoryBus.money(container, host.strings) }.getOrNull()
                _state.update {
                    val has = it.item != null
                    it.copy(
                        money = it.money ?: money,
                        loading = false,
                        historyFailed = true,
                        cachedAt = if (has && e is ApiProblem.Network) container.cache.read(cacheKey)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        error = if (has) null else InventoryRules.loadErrorSentence(e, host.strings),
                    )
                }
            }
        }
    }

    fun delete() {
        val item = state.value.item ?: return
        if (!mayChange) return
        _state.update { it.copy(gone = true) }
        InventoryBus.delete(container, host, item)
    }

    fun startCompletion(date: TrackedDate) {
        if (!mayChange) return
        completionKey = YuvomiClient.newIdempotencyKey()
        _state.update { it.copy(completing = date) }
    }

    fun cancelCompletion() = _state.update { it.copy(completing = null) }

    /** INV-R10: "Done" logs the service and rolls a repeating date forward (`openCompletionSheet`). */
    fun complete(performedOn: String, odometer: String, vendor: String, note: String) {
        val api = api ?: return
        val s = state.value
        val item = s.item ?: return
        val date = s.completing ?: return
        if (s.busy || performedOn.isBlank()) return
        val tracks = InventoryRules.tracksOdometer(item.category, s.categories)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                api.complete(item.id, date.id, InventoryRules.completion(performedOn, odometer, vendor, note, tracks), completionKey)
                _state.update { it.copy(busy = false, completing = null) }
                host.notify(host.strings.t("inventory.completed"))
                InventoryBus.changed()
            } catch (e: ApiProblem) {
                _state.update { it.copy(busy = false) }
                host.notify(InventoryRules.errorSentence(e, host.strings))
                if (InventoryRules.isGone(e)) {
                    _state.update { it.copy(completing = null) }
                    InventoryBus.changed()
                }
            }
        }
    }
}
