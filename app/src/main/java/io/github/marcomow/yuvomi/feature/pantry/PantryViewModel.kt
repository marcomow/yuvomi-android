package io.github.marcomow.yuvomi.feature.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What the pantry screens need from the shell: snackbars, the household's today, the strings,
 * and the way to Shopping. An interface so the view model is tested without a shell.
 */
interface PantryHost {
    val strings: Strings

    fun today(): LocalDate

    /** Shows [message]; true when the member tapped [action]. */
    suspend fun notify(message: String, action: String? = null): Boolean

    /** The way out of "there is no shopping list yet", or null when it would lead nowhere. */
    val openShopping: (() -> Unit)?
}

/** docs/spec/pantry.md, the list (PANTRY-R2 to R19, R21, R22). */
class PantryViewModel(
    private val container: AppContainer,
    private val host: PantryHost,
    linkedFilter: String? = null,
) : ViewModel() {

    data class UiState(
        val stock: PantryStock = PantryStock(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        /** Set while a cached answer is shown because the server could not be reached. */
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val query: String = "",
        val filter: String = PantryView.ALL,
        val today: LocalDate = LocalDate.now(),
        /** Quantities the member stepped to and the server has not confirmed yet (`intents`). */
        val intents: Map<Int, Double> = emptyMap(),
        val expanded: Set<String> = emptySet(),
        val pendingDeletes: Set<Int> = emptySet(),
        val pendingProductDeletes: Set<Int> = emptySet(),
        /** Several shopping lists: the member picks one for these entries. */
        val pickList: ListChoice? = null,
        val transferring: Boolean = false,
    ) {
        val offline: Boolean get() = cachedAt != null

        /** The batches as the rows show them: steps applied, batches in their undo window left out. */
        val items: List<PantryItem> by lazy {
            stock.items.filter { it.id !in pendingDeletes }.map { item -> intents[item.id]?.let { item.copy(quantity = it) } ?: item }
        }
        val products: List<PantryProduct> by lazy { stock.products.filter { it.id !in pendingProductDeletes } }
    }

    data class ListChoice(val lists: List<ShoppingListRef>, val entries: List<PantryApi.TransferEntry>)

    private val _state = MutableStateFlow(UiState(filter = PantryStatus.filterFromLink(linkedFilter) ?: PantryView.ALL, today = host.today()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: PantryApi? get() = container.session.connection?.client?.let(::PantryApi)
    var lastLoaded = 0L
        private set

    /** A step waiting for its debounce (`intents` with `timer` and `flush`). */
    private class PendingStep(val quantity: Double, val seq: Int, var timer: Job?)

    private val steps = mutableMapOf<Int, PendingStep>()
    private var stepSeq = 0
    private var loadSeq = 0
    private var appliedLoad = 0
    private val settledAt = mutableMapOf<Int, Int>()
    private var lists: List<ShoppingListRef>? = null

    init {
        readCache()
        refresh()
    }

    private fun readCache() {
        val cached = container.cache.read(CACHE_KEY) ?: return
        runCatching { PantryApi.decodeStock(cached.json) }.onSuccess { stock ->
            _state.update { it.copy(stock = stock, loading = false) }
        }
    }

    /** PANTRY-R2; an older answer never turns back a quantity the server confirmed since (`loadPantry`). */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val startedAt = ++loadSeq
        _state.update { it.copy(refreshing = pulled, errorKey = null, loading = it.loading || it.errorKey != null) }
        viewModelScope.launch {
            try {
                val json = api.stock()
                val fresh = PantryApi.decodeStock(json)
                if (startedAt < appliedLoad) return@launch
                appliedLoad = startedAt
                container.cache.write(CACHE_KEY, json.toString())
                lastLoaded = System.currentTimeMillis()
                _state.update { s ->
                    val previous = s.stock.items.associateBy { it.id }
                    val items = fresh.items.map { item ->
                        val confirmed = settledAt[item.id]
                        val old = previous[item.id]
                        if (confirmed != null && confirmed >= startedAt && old != null) item.copy(quantity = old.quantity) else item
                    }
                    s.copy(
                        stock = fresh.copy(items = items),
                        loading = false,
                        refreshing = false,
                        cachedAt = null,
                        today = host.today(),
                    )
                }
            } catch (e: ApiProblem) {
                _state.update {
                    val hasData = !it.loading || it.stock.items.isNotEmpty() || it.stock.products.isNotEmpty()
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_KEY)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        errorKey = if (hasData) null else LOAD_ERROR,
                    )
                }
                if (!state.value.loading && state.value.errorKey == null && e !is ApiProblem.Network) host.notify(host.strings.t(PantryText.errorKey(e)))
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    /** "Reset search" of the empty result: query and chip both go (`noResultsEl`). */
    fun resetSearch() = _state.update { it.copy(query = "", filter = PantryView.ALL) }

    fun toggleProduct(key: String) = _state.update { s ->
        s.copy(expanded = if (key in s.expanded) s.expanded - key else s.expanded + key)
    }

    /** The batch as its row shows it, for the editor (PANTRY-R13). */
    fun itemForEdit(id: Int): PantryItem? = state.value.items.firstOrNull { it.id == id }

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(host.strings.t("common.errorOfflineMutation"))
        return true
    }

    // ── PANTRY-R12: quantity steps ──

    fun step(item: PantryItem, direction: Int) {
        if (state.value.offline) {
            viewModelScope.launch { refuseOffline() }
            return
        }
        val api = api ?: return
        val previous = state.value.intents[item.id] ?: state.value.stock.items.firstOrNull { it.id == item.id }?.quantity ?: item.quantity
        val next = PantryUnits.normalizeQuantity(previous + direction * PantryUnits.step(item.unit), fallback = previous)
        if (next == previous) return
        steps[item.id]?.timer?.cancel()
        val seq = ++stepSeq
        val pending = PendingStep(next, seq, null)
        steps[item.id] = pending
        _state.update { it.copy(intents = it.intents + (item.id to next)) }
        pending.timer = viewModelScope.launch {
            delay(STEP_DEBOUNCE_MS)
            pending.timer = null
            // The write itself outlives the screen: leaving it must not lose the step.
            container.scope.launch { sendStep(api, item.id, pending) }
        }
    }

    private suspend fun sendStep(api: PantryApi, id: Int, pending: PendingStep) {
        try {
            val answer = api.setQuantity(id, pending.quantity)
            val confirmed = PantryUnits.normalizeQuantity(answer.quantity, fallback = pending.quantity)
            settledAt[id] = loadSeq
            _state.update { s ->
                val stock = s.stock.copy(items = s.stock.items.map { if (it.id == id) it.copy(quantity = confirmed) else it })
                s.copy(stock = stock, intents = if (steps[id]?.seq == pending.seq) s.intents - id else s.intents)
            }
            if (steps[id]?.seq == pending.seq) steps.remove(id)
        } catch (e: ApiProblem) {
            if (steps[id]?.seq != pending.seq) return
            steps.remove(id)
            _state.update { it.copy(intents = it.intents - id) }
            host.notify(host.strings.t(PantryText.errorKey(e)))
        }
    }

    /** Sends a step still inside its debounce at once (`flush` on `pagehide`). */
    private fun flushSteps() {
        val api = api ?: return
        for ((id, pending) in steps.toMap()) {
            val timer = pending.timer ?: continue
            timer.cancel()
            pending.timer = null
            container.scope.launch { runCatching { api.setQuantity(id, pending.quantity) } }
        }
    }

    override fun onCleared() {
        flushSteps()
        super.onCleared()
    }

    // ── PANTRY-R14, R15: delete with undo ──

    fun delete(item: PantryItem) {
        val api = api ?: return
        if (state.value.offline) {
            viewModelScope.launch { refuseOffline() }
            return
        }
        steps.remove(item.id)?.timer?.cancel()
        _state.update { it.copy(pendingDeletes = it.pendingDeletes + item.id, intents = it.intents - item.id) }
        container.scope.launch {
            val strings = host.strings
            if (host.notify(strings.t("pantry.deleted"), strings.t("common.undo"))) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - item.id) }
                return@launch
            }
            try {
                api.delete(item.id)
                _state.update { s ->
                    s.copy(stock = s.stock.copy(items = s.stock.items.filter { it.id != item.id }), pendingDeletes = s.pendingDeletes - item.id)
                }
                container.cache.write(CACHE_KEY, ApiJsonText.stock(state.value.stock))
            } catch (e: ApiProblem) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - item.id) }
                host.notify(strings.t(PantryText.errorKey(e)))
                // A batch someone else deleted meanwhile is gone from the reloaded list.
                if (e is ApiProblem.Http && e.status == 404) refresh()
            }
        }
    }

    fun deleteProduct(productId: Int) {
        val api = api ?: return
        if (state.value.offline) {
            viewModelScope.launch { refuseOffline() }
            return
        }
        _state.update { it.copy(pendingProductDeletes = it.pendingProductDeletes + productId) }
        container.scope.launch {
            val strings = host.strings
            if (host.notify(strings.t("pantry.deleted"), strings.t("common.undo"))) {
                _state.update { it.copy(pendingProductDeletes = it.pendingProductDeletes - productId) }
                return@launch
            }
            try {
                api.deleteProduct(productId)
                _state.update { s ->
                    s.copy(
                        stock = s.stock.copy(products = s.stock.products.filter { it.id != productId }),
                        pendingProductDeletes = s.pendingProductDeletes - productId,
                    )
                }
                container.cache.write(CACHE_KEY, ApiJsonText.stock(state.value.stock))
            } catch (e: ApiProblem) {
                _state.update { it.copy(pendingProductDeletes = it.pendingProductDeletes - productId) }
                host.notify(strings.t(PantryText.errorKey(e)))
                if ((e as? ApiProblem.Http)?.reason == "pantry_product_has_batches") refresh()
            }
        }
    }

    // ── PANTRY-R17 to R19: to the shopping list ──

    /** The cart of a batch row. */
    fun cart(item: PantryItem) = toShopping(listOf(PantryApi.TransferEntry.Batch(item.id, PantryText.shortfall(item, host.strings))))

    /** The cart of a product without batches: by its id, without a quantity. */
    fun cartProduct(productId: Int) = toShopping(listOf(PantryApi.TransferEntry.Product(productId)))

    /** "Add all to shopping list": every row the low chip and the search show. */
    fun cartAll(items: List<PantryItem>) =
        toShopping(items.map { PantryApi.TransferEntry.Batch(it.id, PantryText.shortfall(it, host.strings)) })

    private fun toShopping(entries: List<PantryApi.TransferEntry>) {
        val api = api ?: return
        if (entries.isEmpty() || state.value.transferring) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val available = try {
                lists ?: api.shoppingLists().also { lists = it }
            } catch (e: ApiProblem) {
                host.notify(host.strings.t(PantryText.errorKey(e)))
                return@launch
            }
            when (available.size) {
                0 -> {
                    val open = host.openShopping
                    if (host.notify(host.strings.t("kitchen.noShoppingLists"), open?.let { host.strings.t("kitchen.createShoppingList") })) open?.invoke()
                }
                1 -> transfer(available.first(), entries)
                else -> _state.update { it.copy(pickList = ListChoice(available, entries)) }
            }
        }
    }

    fun pickList(list: ShoppingListRef?) {
        val choice = state.value.pickList ?: return
        _state.update { it.copy(pickList = null) }
        if (list != null) viewModelScope.launch { transfer(list, choice.entries) }
    }

    private suspend fun transfer(list: ShoppingListRef, entries: List<PantryApi.TransferEntry>) {
        val api = api ?: return
        val strings = host.strings
        _state.update { it.copy(transferring = true) }
        val result = try {
            api.toShopping(list.id, entries, YuvomiClient.newIdempotencyKey())
        } catch (e: ApiProblem) {
            host.notify(strings.t(PantryText.errorKey(e)))
            // A list deleted meanwhile: pick again from fresh lists next time.
            if ((e as? ApiProblem.Http)?.reason == "shopping_list_not_found") lists = null
            return
        } finally {
            _state.update { it.copy(transferring = false) }
        }
        if (result.added == 0) {
            host.notify(strings.t("pantry.toShoppingNone"))
            return
        }
        val message = PantryText.transferMessage(result.added, result.skipped, list.name, strings)
        container.scope.launch {
            val undo = result.addedIds.isNotEmpty()
            if (host.notify(message, if (undo) strings.t("common.undo") else null) && undo) {
                try {
                    api.undoTransfer(result.addedIds)
                    host.notify(strings.t("kitchen.transferUndone"))
                } catch (e: ApiProblem) {
                    host.notify(strings.t(PantryText.errorKey(e)))
                }
            }
        }
    }

    companion object {
        const val CACHE_KEY = "pantry-stock"
        const val STEP_DEBOUNCE_MS = 450L
        const val LOAD_ERROR = "pantry.loadErrorTitle"
    }
}

/** The stock back to the JSON shape of `GET /pantry`, for the offline cache after a local change. */
internal object ApiJsonText {
    fun stock(stock: PantryStock): String = io.github.marcomow.yuvomi.core.api.ApiJson.encodeToString(PantryStock.serializer(), stock)
}
