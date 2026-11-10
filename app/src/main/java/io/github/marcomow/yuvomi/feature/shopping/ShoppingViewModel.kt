package io.github.marcomow.yuvomi.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * docs/spec/shopping.md: the whole shopping screen's state and every write it makes.
 *
 * `items` always holds the server's state; the member's own taps live beside it in
 * `intents` and win when shown (`checkedOf` in the web app), so a reload that started before
 * a tap was confirmed never turns the row back (SHOP-R11).
 */
class ShoppingViewModel(private val container: AppContainer, requestedList: Int?) : ViewModel() {

    data class UiState(
        val lists: List<ShoppingList> = emptyList(),
        val activeListId: Int? = null,
        val items: List<ShoppingItem> = emptyList(),
        val itemsListId: Int? = null,
        val intents: Map<Int, Boolean> = emptyMap(),
        val held: Map<Int, Boolean> = emptyMap(),
        val categories: List<ShoppingCategory> = emptyList(),
        val stores: List<ShoppingStore> = emptyList(),
        val currency: String = "EUR",
        val loading: Boolean = true,
        val itemsLoading: Boolean = false,
        val refreshing: Boolean = false,
        val listsError: String? = null,
        val itemsError: String? = null,
        val cachedAt: Long? = null,
        val collapsed: Set<String> = emptySet(),
        val tickedOpen: Boolean = false,
        val sorting: Boolean = false,
        val suggestions: List<Suggestion> = emptyList(),
        val pill: Boolean = false,
    ) {
        fun checked(item: ShoppingItem): Boolean = intents[item.id] ?: item.isChecked
        val activeList: ShoppingList? get() = lists.firstOrNull { it.id == activeListId }
        val checkedItems: List<ShoppingItem> get() = items.filter(::checked)
        val openCount: Int get() = items.count { !checked(it) }
        val offline: Boolean get() = cachedAt != null

        /** The chip's open count: the shown rows for the active list, the server's count for the others. */
        fun openOf(list: ShoppingList): Int = if (list.id == activeListId && itemsListId == list.id) openCount else list.open
        fun totalOf(list: ShoppingList): Int = if (list.id == activeListId && itemsListId == list.id) items.size else list.itemTotal
    }

    private val _state = MutableStateFlow(UiState(activeListId = requestedList))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Shows a message with an optional action; true when the action was tapped. Set by the screen. */
    var notifier: suspend (message: String, action: String?) -> Boolean = { _, _ -> false }
    var strings: Strings? = null

    private val api: ShoppingApi? get() = container.session.connection?.client?.let { ShoppingApi(it) }

    /** SHOP-R40: the item's last purchase, or null; a failure only leaves the line out. */
    suspend fun lastPurchase(item: ShoppingItem): Purchase? =
        runCatching { api?.lastPurchase(item.name, item.id) }.getOrNull()
    private val feed = LiveFeed()
    private val requested = requestedList
    var lastLoaded = 0L
        private set

    // Load ordering (`_loadSeq`, `settledAt`, `pendingRemovals` in the web app).
    private var loadSeq = 0
    private val settledAt = HashMap<Int, Int>()
    private val intentSeq = HashMap<Int, Int>()
    private var seqCounter = 0
    private class Removal(val listId: Int, var confirmedAt: Int? = null)
    private val pendingRemovals = HashMap<Int, Removal>()
    private val keys = HashMap<String, String>()
    private var holdJob: Job? = null
    private var pillJob: Job? = null
    private var pillPhase = "idle"
    private var pollJob: Job? = null
    private var suggestJob: Job? = null

    init {
        readCache()
        refresh()
    }

    private fun t(key: String, vararg args: Pair<String, Any?>): String = strings?.t(key, args.toMap()) ?: key

    /** One idempotency key per user action, reused on retry, dropped on success (SHOP-R38). */
    private fun key(action: String) = keys.getOrPut(action) { YuvomiClient.newIdempotencyKey() }

    // ── Cache (SHOP-R34) ──

    @Serializable
    private data class Ui(val collapsed: List<String> = emptyList(), val tickedOpen: Boolean = false)

    @Serializable
    private data class Extras(val currency: String = "EUR")

    private fun readCache() {
        val c = container.cache
        runCatching { c.read(CACHE_CATEGORIES)?.let { e -> _state.update { it.copy(categories = ShoppingApi.decodeCategories(ApiJson.parseToJsonElement(e.json))) } } }
        runCatching { c.read(CACHE_STORES)?.let { e -> _state.update { it.copy(stores = ShoppingApi.decodeStores(ApiJson.parseToJsonElement(e.json))) } } }
        runCatching { c.read(CACHE_EXTRAS)?.let { e -> _state.update { it.copy(currency = ApiJson.decodeFromString(Extras.serializer(), e.json).currency) } } }
        val lists = c.read(CACHE_LISTS) ?: return
        runCatching {
            val decoded = ShoppingApi.decodeLists(ApiJson.parseToJsonElement(lists.json))
            val active = chooseList(decoded, requested)
            _state.update { it.copy(lists = decoded, activeListId = active, loading = false, cachedAt = lists.savedAt) }
            if (active != null) {
                applyUi(active)
                c.read(itemsKey(active))?.let { e ->
                    val answer = ShoppingApi.decodeItems(ApiJson.parseToJsonElement(e.json))
                    _state.update { it.copy(items = answer.data, itemsListId = active, categories = answer.categories.ifEmpty { it.categories }) }
                }
            }
        }
    }

    private fun itemsKey(listId: Int) = "shopping-items-$listId"

    private fun rememberedList(): Int? = container.cache.read(CACHE_LAST)?.json?.trim()?.toIntOrNull()

    private fun chooseList(lists: List<ShoppingList>, preferred: Int?): Int? {
        val ids = lists.map { it.id }
        return listOfNotNull(preferred, state.value.activeListId, rememberedList()).firstOrNull { it in ids } ?: ids.firstOrNull()
    }

    private var uiFor: Int? = null

    private fun applyUi(listId: Int) {
        uiFor = listId
        val ui = container.cache.read("shopping-ui-$listId")?.let { runCatching { ApiJson.decodeFromString(Ui.serializer(), it.json) }.getOrNull() } ?: Ui()
        _state.update { it.copy(collapsed = ui.collapsed.toSet(), tickedOpen = ui.tickedOpen) }
    }

    private fun saveUi() {
        val s = state.value
        val listId = s.activeListId ?: return
        container.cache.write("shopping-ui-$listId", ApiJson.encodeToString(Ui.serializer(), Ui(s.collapsed.toList(), s.tickedOpen)))
    }

    // ── Loading ──

    /** Everything: categories, shops, currency and lists side by side, then the active list's items (`loadPageData`). */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        _state.update { it.copy(refreshing = pulled) }
        viewModelScope.launch {
            val target = state.value.activeListId ?: requested ?: rememberedList()
            coroutineScope {
                val started = ++loadSeq
                val prefetch = target?.let { id -> async { runCatching { api.items(id) } } }
                val cats = async { runCatching { api.categories() } }
                val stores = async { runCatching { api.stores() } }
                val currency = async { runCatching { api.currency() } }
                val lists = runCatching { api.lists() }
                cats.await().onSuccess { json ->
                    runCatching { ShoppingApi.decodeCategories(json) }.onSuccess { c ->
                        container.cache.write(CACHE_CATEGORIES, json.toString())
                        _state.update { it.copy(categories = c) }
                    }
                }
                stores.await().onSuccess { json ->
                    runCatching { ShoppingApi.decodeStores(json) }.onSuccess { st ->
                        container.cache.write(CACHE_STORES, json.toString())
                        _state.update { it.copy(stores = st) }
                    }
                }
                currency.await().onSuccess { cur ->
                    container.cache.write(CACHE_EXTRAS, ApiJson.encodeToString(Extras.serializer(), Extras(cur)))
                    _state.update { it.copy(currency = cur) }
                }
                val listsJson = lists.getOrElse { e ->
                    prefetch?.cancel()
                    failLists(e)
                    return@coroutineScope
                }
                val decoded = runCatching { ShoppingApi.decodeLists(listsJson) }.getOrElse { e -> failLists(e); return@coroutineScope }
                container.cache.write(CACHE_LISTS, listsJson.toString())
                val active = chooseList(decoded, if (pulled) null else requested.takeIf { state.value.activeListId == null })
                _state.update { it.copy(lists = decoded, activeListId = active, loading = false, refreshing = false, listsError = null, cachedAt = null) }
                if (active == null) {
                    _state.update { it.copy(items = emptyList(), itemsListId = null) }
                    return@coroutineScope
                }
                if (uiFor != active) applyUi(active)
                remember(active)
                val pre = prefetch?.takeIf { target == active }?.await()
                if (pre == null) prefetch?.cancel()
                loadItems(active, pre, started)
                lastLoaded = System.currentTimeMillis()
            }
        }
    }

    private fun failLists(e: Throwable) {
        _state.update {
            val cached = it.lists.isNotEmpty() && e is ApiProblem.Network
            it.copy(
                loading = false,
                refreshing = false,
                listsError = if (cached) null else ShoppingLogic.loadErrorKey(e),
                cachedAt = if (cached) it.cachedAt ?: System.currentTimeMillis() else it.cachedAt,
            )
        }
    }

    private fun remember(listId: Int) = container.cache.write(CACHE_LAST, listId.toString())

    /** Loads [listId]'s items, using a prefetched answer when there is one (`loadItems`). */
    private suspend fun loadItems(listId: Int, prefetched: Result<JsonElement>? = null, startedAt: Int = ++loadSeq) {
        val api = api ?: return
        val result = prefetched ?: runCatching { api.items(listId) }
        if (state.value.activeListId != listId) return
        result.onSuccess { json ->
            val answer = runCatching { ShoppingApi.decodeItems(json) }.getOrElse { e -> failItems(listId, e); return }
            container.cache.write(itemsKey(listId), json.toString())
            applyItems(listId, answer, startedAt)
        }.onFailure { e -> failItems(listId, e) }
    }

    private fun failItems(listId: Int, e: Throwable) {
        val cached = container.cache.read(itemsKey(listId))
        _state.update { s ->
            when {
                s.itemsListId == listId && e is ApiProblem.Network ->
                    s.copy(itemsLoading = false, refreshing = false, cachedAt = s.cachedAt ?: System.currentTimeMillis())
                cached != null && e is ApiProblem.Network -> runCatching {
                    val answer = ShoppingApi.decodeItems(ApiJson.parseToJsonElement(cached.json))
                    s.copy(items = answer.data, itemsListId = listId, itemsLoading = false, refreshing = false, itemsError = null, cachedAt = cached.savedAt)
                }.getOrElse { s.copy(itemsLoading = false, itemsError = ShoppingLogic.loadErrorKey(e)) }
                else -> s.copy(items = emptyList(), itemsListId = listId, itemsLoading = false, refreshing = false, itemsError = ShoppingLogic.loadErrorKey(e))
            }
        }
    }

    private fun applyItems(listId: Int, answer: ItemsAnswer, startedAt: Int) {
        val s = state.value
        val previous = s.items.associateBy { it.id }
        val fresh = answer.data.filter { item -> pendingRemovals[item.id]?.let { r -> !(r.confirmedAt != null && startedAt > r.confirmedAt!!) } != true }
            .map { item ->
                val confirmed = settledAt[item.id]
                if (confirmed != null && confirmed >= startedAt) previous[item.id]?.let { item.copy(isChecked = it.isChecked) } ?: item else item
            }
        // Fulfilled or overtaken intents fall (`settleIntents`).
        val intents = s.intents.filter { (id, wanted) ->
            val item = fresh.firstOrNull { it.id == id } ?: return@filter s.itemsListId != listId
            val confirmed = settledAt[id]
            !(item.isChecked == wanted || (confirmed != null && confirmed < startedAt))
        }
        pendingRemovals.entries.removeAll { (_, r) -> r.confirmedAt != null && r.confirmedAt!! < startedAt }
        feed.hold(listId, answer.version)
        _state.update {
            it.copy(
                items = fresh,
                itemsListId = listId,
                intents = intents,
                categories = answer.categories.ifEmpty { it.categories },
                lists = it.lists.map { l -> if (l.id == listId && answer.list != null) l.copy(name = answer.list.name) else l },
                itemsError = null,
                itemsLoading = false,
                refreshing = false,
                loading = false,
                cachedAt = null,
            )
        }
        updatePill(false)
    }

    /** SHOP-R6: another list; its collapsed state comes with it. */
    fun switchList(listId: Int) {
        if (listId == state.value.activeListId && state.value.itemsListId == listId) return
        holdJob?.cancel()
        pillJob?.cancel()
        pillPhase = "idle"
        _state.update { it.copy(activeListId = listId, held = emptyMap(), pill = false, itemsLoading = true, suggestions = emptyList()) }
        applyUi(listId)
        remember(listId)
        val cached = container.cache.read(itemsKey(listId))
        if (cached != null) runCatching {
            val answer = ShoppingApi.decodeItems(ApiJson.parseToJsonElement(cached.json))
            _state.update { it.copy(items = answer.data, itemsListId = listId) }
        }
        viewModelScope.launch { loadItems(listId) }
    }

    // ── Live updates (SHOP-R32) ──

    /** Polls `GET /shopping/versions` every [intervalMs] while the screen is in front. */
    fun setResumed(resumed: Boolean, intervalMs: Long = POLL_MS) {
        pollJob?.cancel()
        if (!resumed) return
        pollJob = viewModelScope.launch {
            while (true) {
                poll()
                delay(intervalMs)
            }
        }
    }

    suspend fun poll() {
        val api = api ?: return
        val rows = runCatching { api.versions() }.getOrNull() ?: return
        val moved = feed.apply(rows)
        if (moved.isEmpty()) return
        val lists = runCatching { ShoppingApi.decodeLists(api.lists()) }.getOrNull() ?: return
        val active = state.value.activeListId
        _state.update { it.copy(lists = lists) }
        if (active != null && lists.none { it.id == active }) {
            val next = lists.firstOrNull()?.id
            if (next != null) switchList(next) else _state.update { it.copy(activeListId = null, items = emptyList(), itemsListId = null) }
            return
        }
        if (active != null && active in moved) loadItems(active)
    }

    // ── Ticking (SHOP-R11 to R13) ──

    /**
     * Ticks or unticks [item] at once and writes it; [extra] fields go in the same `PATCH`
     * (the revive path). Returns the server's item, or null when the write failed.
     */
    suspend fun toggle(item: ShoppingItem, extra: JsonObject? = null, settleNow: Boolean = false): ShoppingItem? {
        val api = api ?: return null
        val s = state.value
        val listId = s.activeListId ?: return null
        val wanted = !s.checked(item)
        val seq = ++seqCounter
        intentSeq[item.id] = seq
        val shownTicked = s.checked(item)
        _state.update { st ->
            st.copy(
                intents = st.intents + (item.id to wanted),
                held = if (settleNow || item.id in st.held) st.held else st.held + (item.id to shownTicked),
            )
        }
        if (!settleNow) holdPlacement() else _state.update { it.copy(held = it.held - item.id) }
        updatePill(userChecked = wanted)
        return try {
            val body = buildJsonObject {
                extra?.forEach { (k, v) -> put(k, v) }
                put("is_checked", if (wanted) 1 else 0)
            }
            val answer = api.updateItem(item.id, body)
            feed.acknowledge(answer.listChange)
            settledAt[item.id] = loadSeq
            _state.update { st ->
                st.copy(items = st.items.map { if (it.id == item.id) answer.data.copy(isChecked = wanted) else it })
            }
            if (state.value.intents[item.id] == wanted && intentSeq[item.id] == seq) {
                _state.update { it.copy(intents = it.intents - item.id) }
            }
            answer.data
        } catch (e: ApiProblem) {
            if (intentSeq[item.id] != seq) return null
            _state.update { it.copy(intents = it.intents - item.id) }
            updatePill(false)
            val name = item.name
            report(e, if (wanted) "common.errorCheckRolledBack" else "common.errorUncheckRolledBack", "name" to name)
            null
        }
    }

    /** SHOP-R12 (`holdPlacement`): rows move together once the member pauses. */
    private fun holdPlacement() {
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            delay(SETTLE_PAUSE_MS)
            _state.update { it.copy(held = emptyMap()) }
        }
    }

    /** SHOP-R13 (`updateCheckedActions`): a five-second bar after the member's own tick. */
    private fun updatePill(userChecked: Boolean) {
        val count = state.value.checkedItems.size
        if (count == 0) {
            pillJob?.cancel()
            pillPhase = "idle"
            _state.update { it.copy(pill = false) }
            return
        }
        if (userChecked && pillPhase == "idle") {
            pillPhase = "visible"
            _state.update { it.copy(pill = true) }
            pillJob?.cancel()
            pillJob = viewModelScope.launch {
                delay(PILL_MS)
                pillPhase = "suppressed"
                _state.update { it.copy(pill = false) }
            }
        }
    }

    fun hidePill() {
        pillJob?.cancel()
        if (pillPhase == "visible") pillPhase = "suppressed"
        _state.update { it.copy(pill = false) }
    }

    // ── Quick add (SHOP-R14 to R17) ──

    fun suggest(text: String) {
        suggestJob?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(200)
            val found = runCatching { api?.suggestions(q) }.getOrNull().orEmpty()
            _state.update { it.copy(suggestions = found) }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _state.update { it.copy(suggestions = emptyList()) }
    }

    /** The checked item of this name on the open list, when the items belong to it. */
    fun tickedNamed(name: String): ShoppingItem? {
        val s = state.value
        if (s.itemsListId != s.activeListId) return null
        return ShoppingLogic.tickedItemNamed(s.items, name, s::checked)
    }

    /** Adds an item, or brings a checked one of the same name back. True when the form may clear. */
    suspend fun add(name: String, quantity: String?, category: String?, categoryChosen: Boolean): Boolean {
        val api = api ?: return false
        val listId = state.value.activeListId ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val qty = quantity?.trim()?.takeIf { it.isNotEmpty() }
        val revive = tickedNamed(trimmed)
        if (revive != null) {
            val extra = buildJsonObject {
                if (qty != null && qty != revive.quantity) put("quantity", qty)
                if (categoryChosen && category != null && category != revive.category) put("category", category)
            }
            val back = toggle(revive, extra.takeIf { it.isNotEmpty() }, settleNow = true) ?: return false
            _state.update { s -> s.copy(items = s.items.map { if (it.id == back.id) back.copy(isChecked = false) else it }) }
            notifier(t("shopping.itemBackToast", "name" to revive.name), null)
            return true
        }
        val action = "add:$listId:$trimmed:$qty:$category"
        return try {
            val answer = api.addItem(listId, trimmed, qty, category, key(action))
            keys.remove(action)
            feed.acknowledge(answer.listChange)
            if (state.value.activeListId == listId) _state.update { it.copy(items = it.items + answer.data) }
            clearSuggestions()
            val label = strings?.let { ShoppingLogic.categoryLabel(answer.data.category, it) } ?: answer.data.category.orEmpty()
            viewModelScope.launch { notifier(t("shopping.itemAddedToast", "name" to answer.data.name, "category" to label), null) }
            true
        } catch (e: ApiProblem) {
            report(e)
            false
        }
    }

    // ── Edit (SHOP-R18, SHOP-R19) ──

    data class ItemForm(
        val name: String,
        val quantity: String,
        val category: String?,
        val listId: Int,
        val price: String,
        val store: String,
        val url: String,
        val notes: String,
    )

    /** Saves the editor; returns an error sentence for the form, or null when done. */
    suspend fun save(item: ShoppingItem, form: ItemForm, locale: java.util.Locale): String? {
        val api = api ?: return t("android.error.generic")
        val name = form.name.trim()
        if (name.isEmpty()) return t("common.nameRequired")
        val currency = state.value.currency
        val priceText = form.price.trim()
        val cents: Long? = if (priceText.isEmpty()) null else when (val p = ShoppingLogic.parsePrice(priceText, currency, locale)) {
            is ShoppingLogic.Price.Ok -> p.cents
            ShoppingLogic.Price.Grouped -> return t("common.amountGrouped", "example" to ShoppingLogic.amountExample(currency, locale))
            ShoppingLogic.Price.Invalid -> return t("common.amountInvalid", "example" to ShoppingLogic.amountExample(currency, locale))
            ShoppingLogic.Price.Precision -> return t("common.amountPrecisionRequired", "currency" to currency, "step" to ShoppingLogic.smallestUnit(currency, locale))
        }
        val fromList = state.value.activeListId
        return try {
            val storeName = form.store.trim()
            val storeId: Int? = if (storeName.isEmpty()) null else {
                state.value.stores.firstOrNull { it.name.equals(storeName, ignoreCase = true) }?.id ?: run {
                    val action = "store:$storeName"
                    val created = api.addStore(storeName, key(action))
                    keys.remove(action)
                    _state.update { it.copy(stores = (it.stores + created).sortedBy { s -> s.name.lowercase() }) }
                    created.id
                }
            }
            val payload = buildJsonObject {
                put("name", name)
                put("quantity", form.quantity.trim().ifEmpty { null })
                put("category", form.category)
                put("notes", form.notes.trim().ifEmpty { null })
                put("url", form.url.trim().ifEmpty { null })
                put("price_cents", cents)
                put("store_id", storeId)
                if (form.listId != fromList) put("list_id", form.listId)
            }
            val answer = api.updateItem(item.id, payload)
            feed.acknowledge(answer.listChange)
            if (form.listId != fromList && answer.data.listId == form.listId) {
                movedAway(answer.data, fromList)
            } else {
                _state.update { s -> s.copy(items = s.items.map { if (it.id == item.id) answer.data.copy(isChecked = s.items.firstOrNull { i -> i.id == item.id }?.isChecked ?: answer.data.isChecked) else it }) }
            }
            null
        } catch (e: ApiProblem) {
            report(e)
            ""
        }
    }

    private suspend fun movedAway(moved: ShoppingItem, fromList: Int?) {
        _state.update { s -> s.copy(items = s.items.filter { it.id != moved.id }, intents = s.intents - moved.id) }
        val target = state.value.lists.firstOrNull { it.id == moved.listId }?.name.orEmpty()
        viewModelScope.launch { notifier(t("shopping.itemMovedToast", "name" to moved.name, "list" to target), null) }
        refreshLists()
    }

    private suspend fun refreshLists() {
        val api = api ?: return
        runCatching { ShoppingApi.decodeLists(api.lists()) }.onSuccess { l -> _state.update { it.copy(lists = l) } }
    }

    // ── Delete with undo (SHOP-R20, SHOP-R22) ──

    /** Removes [item] at once; the `DELETE` goes out when the undo window closes. */
    fun delete(item: ShoppingItem) {
        val listId = state.value.activeListId ?: return
        val snapshot = state.value.items.firstOrNull { it.id == item.id } ?: item
        pendingRemovals[item.id] = Removal(listId)
        _state.update { s -> s.copy(items = s.items.filter { it.id != item.id }) }
        updatePill(false)
        container.scope.launch {
            val undo = notifier(t("shopping.itemDeletedToast", "name" to snapshot.name), t("common.undo"))
            if (undo) {
                pendingRemovals.remove(item.id)
                restore(listId, listOf(snapshot))
                return@launch
            }
            val api = api ?: return@launch
            try {
                val answer = api.deleteItem(item.id)
                feed.acknowledge(answer.listChange)
                pendingRemovals[item.id]?.confirmedAt = loadSeq
            } catch (e: ApiProblem) {
                if (ShoppingLogic.isAlreadyDeleted(e, "shopping_item_not_found")) {
                    pendingRemovals[item.id]?.confirmedAt = loadSeq
                    reload()
                } else {
                    pendingRemovals.remove(item.id)
                    restore(listId, listOf(snapshot))
                    report(e)
                }
            }
        }
    }

    private fun restore(listId: Int, rows: List<ShoppingItem>) {
        if (state.value.activeListId != listId) return
        _state.update { s ->
            val have = s.items.map { it.id }.toSet()
            s.copy(items = (s.items + rows.filter { it.id !in have }).sortedBy { it.id })
        }
    }

    /** SHOP-R22: removes the checked rows at once, then `DELETE …/items/checked` with exactly them. */
    fun clearChecked(message: String? = null, onUndo: (suspend () -> Unit)? = null) {
        val listId = state.value.activeListId ?: return
        val checked = state.value.checkedItems
        if (checked.isEmpty()) return
        checked.forEach { pendingRemovals[it.id] = Removal(listId) }
        val ids = checked.map { it.id }.toSet()
        _state.update { s -> s.copy(items = s.items.filter { it.id !in ids }, held = s.held - ids) }
        updatePill(false)
        container.scope.launch {
            val undo = notifier(message ?: t("shopping.itemsRemovedToast", "count" to checked.size), t("common.undo"))
            if (undo) {
                ids.forEach { pendingRemovals.remove(it) }
                restore(listId, checked)
                onUndo?.invoke()
                return@launch
            }
            val api = api ?: return@launch
            try {
                val answer = api.deleteChecked(listId, ids.toList())
                if (answer.deleted == checked.size) feed.acknowledge(answer.listChange)
                ids.forEach { pendingRemovals[it]?.confirmedAt = loadSeq }
            } catch (e: ApiProblem) {
                ids.forEach { pendingRemovals.remove(it) }
                restore(listId, checked)
                report(e)
            }
        }
    }

    // ── Reorder (SHOP-R21) ──

    fun toggleSorting() = _state.update { it.copy(sorting = !it.sorting) }

    private val reorderRuns = HashMap<String, Boolean>()

    /** Moves [item] by [delta] among the open rows of its category and saves the whole category's order. */
    fun move(item: ShoppingItem, delta: Int) {
        val s = state.value
        val listId = s.activeListId ?: return
        val category = ShoppingLogic.categoryOf(item, s.categories)
        val sections = ShoppingLogic.sections(s.items, s.categories, s::checked)
        val open = sections.groups.firstOrNull { it.category == category }?.items.orEmpty().map { it.id }
        val all = s.items.filter { ShoppingLogic.categoryOf(it, s.categories) == category }.map { it.id }
        val order = ShoppingLogic.reorder(open, all, item.id, delta) ?: return
        val rank = order.withIndex().associate { (i, id) -> id to i + 1 }
        _state.update { st -> st.copy(items = st.items.map { rank[it.id]?.let { r -> it.copy(sortOrder = r) } ?: it }) }
        if (reorderRuns.containsKey(category)) {
            reorderRuns[category] = true
            return
        }
        reorderRuns[category] = false
        viewModelScope.launch {
            try {
                do {
                    reorderRuns[category] = false
                    val current = state.value.items.filter { ShoppingLogic.categoryOf(it, state.value.categories) == category }
                        .sortedWith(compareBy<ShoppingItem>({ state.value.checked(it) }, { it.sortOrder ?: 0 }, { it.id })).map { it.id }
                    val answer = api?.reorder(listId, category, current) ?: break
                    feed.acknowledge(answer.listChange)
                } while (reorderRuns[category] == true)
            } catch (e: ApiProblem) {
                report(e)
                if (state.value.activeListId == listId) loadItems(listId)
            } finally {
                reorderRuns.remove(category)
            }
        }
    }

    // ── Collapsing (SHOP-R9) ──

    fun toggleCollapsed(key: String) {
        _state.update { it.copy(collapsed = if (key in it.collapsed) it.collapsed - key else it.collapsed + key) }
        saveUi()
    }

    fun toggleTicked() {
        _state.update { it.copy(tickedOpen = !it.tickedOpen) }
        saveUi()
    }

    // ── Lists (SHOP-R1 to R5) ──

    suspend fun createList(name: String): Boolean {
        val api = api ?: return false
        val trimmed = name.trim().ifEmpty { return false }
        val action = "list:$trimmed"
        return try {
            val list = api.createList(trimmed, key(action))
            keys.remove(action)
            _state.update { it.copy(lists = it.lists + list.copy(itemTotal = 0, itemChecked = 0)) }
            switchList(list.id)
            true
        } catch (e: ApiProblem) {
            report(e)
            false
        }
    }

    suspend fun renameList(name: String): Boolean {
        val api = api ?: return false
        val list = state.value.activeList ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == list.name) return true
        return try {
            val renamed = api.renameList(list.id, trimmed)
            _state.update { s -> s.copy(lists = s.lists.map { if (it.id == list.id) it.copy(name = renamed.name) else it }) }
            true
        } catch (e: ApiProblem) {
            report(e)
            false
        }
    }

    suspend fun duplicateList(name: String, resetChecked: Boolean, keepQuantities: Boolean, keepNotes: Boolean): Boolean {
        val api = api ?: return false
        val source = state.value.activeList ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val action = "dup:${source.id}:$trimmed:$resetChecked:$keepQuantities:$keepNotes"
        return try {
            val copy = api.duplicateList(source.id, trimmed, resetChecked, keepQuantities, keepNotes, key(action))
            keys.remove(action)
            val s = state.value
            val checked = if (resetChecked) 0 else s.checkedItems.size
            _state.update { it.copy(lists = it.lists + copy.copy(itemTotal = s.items.size, itemChecked = checked)) }
            switchList(copy.id)
            true
        } catch (e: ApiProblem) {
            report(e, "shopping.duplicateError")
            false
        }
    }

    /** SHOP-R5: the list goes at once; `DELETE` when the undo window closes. */
    fun deleteList() {
        val s = state.value
        val list = s.activeList ?: return
        val index = s.lists.indexOf(list)
        val items = s.items
        val remaining = s.lists - list
        _state.update { it.copy(lists = remaining) }
        val next = remaining.firstOrNull()?.id
        if (next != null) switchList(next) else _state.update { it.copy(activeListId = null, items = emptyList(), itemsListId = null) }
        container.scope.launch {
            val undo = notifier(t("shopping.deletedListToast"), t("common.undo"))
            if (undo) {
                _state.update { st -> st.copy(lists = st.lists.toMutableList().apply { add(index.coerceIn(0, size), list) }) }
                _state.update { it.copy(activeListId = list.id, items = items, itemsListId = list.id) }
                applyUi(list.id)
                remember(list.id)
                return@launch
            }
            val api = api ?: return@launch
            try {
                api.deleteList(list.id)
                container.cache.write("shopping-ui-${list.id}", "{}")
            } catch (e: ApiProblem) {
                if (ShoppingLogic.isAlreadyDeleted(e, "shopping_list_not_found")) return@launch reload()
                _state.update { st -> st.copy(lists = st.lists.toMutableList().apply { add(index.coerceIn(0, size), list) }) }
                report(e, "common.unknownError")
            }
        }
    }

    // ── Categories (SHOP-R25, SHOP-R27) ──

    /** Runs a category or shop change; after it, categories, shops and items reload. */
    private suspend fun manage(fallback: String = "common.errorGeneric", alreadyDeleted: String? = null, block: suspend (ShoppingApi) -> Unit): Boolean {
        val api = api ?: return false
        return try {
            block(api)
            reloadMeta()
            true
        } catch (e: ApiProblem) {
            if (alreadyDeleted != null && ShoppingLogic.isAlreadyDeleted(e, alreadyDeleted)) {
                reloadMeta()
                return true
            }
            notifier(t(ShoppingLogic.errorKey(e, fallback)), null)
            reloadMeta()
            false
        }
    }

    private suspend fun reloadMeta() {
        val api = api ?: return
        runCatching { api.categories() }.onSuccess { json ->
            runCatching { ShoppingApi.decodeCategories(json) }.onSuccess { c -> _state.update { it.copy(categories = c) } }
        }
        runCatching { api.stores() }.onSuccess { json ->
            runCatching { ShoppingApi.decodeStores(json) }.onSuccess { st -> _state.update { it.copy(stores = st) } }
        }
        state.value.activeListId?.let { loadItems(it) }
    }

    suspend fun addCategory(name: String): Boolean {
        val n = name.trim().ifEmpty { return false }
        return manage { api -> val a = "cat:$n"; api.addCategory(n, key(a)); keys.remove(a); notifier(t("category.added"), null) }
    }

    suspend fun renameCategory(category: ShoppingCategory, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == category.name) return true
        return manage { api -> api.renameCategory(category.id, n); notifier(t("category.renamed"), null) }
    }

    suspend fun deleteCategory(category: ShoppingCategory): Boolean =
        manage(alreadyDeleted = "shopping_category_not_found") { api -> api.deleteCategory(category.id); notifier(t("category.deleted"), null) }

    suspend fun moveCategory(category: ShoppingCategory, delta: Int): Boolean {
        val ids = state.value.categories.map { it.id }
        val order = ShoppingLogic.reorder(ids, ids, category.id, delta) ?: return false
        val byId = state.value.categories.associateBy { it.id }
        _state.update { it.copy(categories = order.mapNotNull { id -> byId[id] }) }
        return manage { api ->
            val saved = api.reorderCategories(order)
            _state.update { it.copy(categories = saved) }
        }
    }

    // ── Shops (SHOP-R26) ──

    suspend fun addStore(name: String): Boolean {
        val n = name.trim().ifEmpty { return false }
        return manage { api -> val a = "store:$n"; api.addStore(n, key(a)); keys.remove(a); notifier(t("shopping.storeAdded"), null) }
    }

    suspend fun renameStore(store: ShoppingStore, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == store.name) return true
        return manage { api -> api.renameStore(store.id, n); notifier(t("shopping.storeRenamed"), null) }
    }

    suspend fun deleteStore(store: ShoppingStore): Boolean =
        manage(alreadyDeleted = "shopping_store_not_found") { api -> api.deleteStore(store.id); notifier(t("shopping.storeDeleted"), null) }

    // ── Send (SHOP-R28) ──

    /** The recipients, or null after telling why there is nothing to choose. */
    suspend fun recipients(): List<Recipient>? {
        val api = api ?: return null
        if (state.value.openCount == 0) {
            notifier(t("shopping.sendListEmpty"), null)
            return null
        }
        val members = try {
            api.recipients()
        } catch (e: ApiProblem) {
            notifier(t("common.errorGeneric"), null)
            return null
        }
        if (members.isEmpty()) {
            notifier(t("shopping.sendListNoRecipients"), null)
            return null
        }
        return members
    }

    suspend fun send(listId: Int, recipient: Recipient): Boolean {
        val api = api ?: return false
        val action = "send:$listId:${recipient.id}"
        return try {
            api.send(listId, recipient.id, key(action))
            keys.remove(action)
            notifier(t("shopping.sendListSent", "name" to recipient.displayName), null)
            true
        } catch (e: ApiProblem) {
            keys.remove(action)
            val byReason = when ((e as? ApiProblem.Http)?.reason) {
                "recipient_no_email" -> "shopping.sendListRecipientNoEmail"
                "smtp_unconfigured" -> "shopping.sendListSmtpMissing"
                "nothing_open" -> "shopping.sendListEmpty"
                else -> null
            }
            if (byReason != null) notifier(t(byReason), null) else report(e, "shopping.sendListError")
            false
        }
    }

    // ── Meal plan (SHOP-R29) ──

    /** The preview sentence for the range, or null when it cannot be computed. */
    suspend fun mealPreview(from: String, to: String): String? {
        val api = api ?: return null
        val listId = state.value.activeListId ?: return null
        val p = runCatching { api.importMealPlan(listId, from, to, preview = true, key = null) }.getOrNull() ?: return null
        if (p.transferred == 0) return t("shopping.importMealsEmpty")
        val parts = buildList {
            if (p.added > 0) add(t("shopping.importMealsPreview", "count" to p.added, "mealsText" to t("shopping.importMealsPreviewMeals", "count" to p.meals)))
            if (p.skipped > 0) add(t(if (p.added > 0) "shopping.importMealsPreviewSkipped" else "kitchen.transferAllPresent", "count" to p.skipped))
        }
        return parts.joinToString(" ")
    }

    /** Imports the range; returns a sentence to show in the dialog when nothing was found, else null. */
    suspend fun importMeals(from: String, to: String, canUndo: Boolean): String? {
        val api = api ?: return null
        val listId = state.value.activeListId ?: return null
        val action = "meals:$listId:$from:$to"
        val result = try {
            api.importMealPlan(listId, from, to, preview = false, key = key(action)).also { keys.remove(action) }
        } catch (e: ApiProblem) {
            report(e)
            return ""
        }
        if (result.transferred == 0) return t("shopping.importMealsEmpty")
        if (result.added == 0) {
            viewModelScope.launch { notifier(t("kitchen.transferAllPresent"), null) }
            return null
        }
        refreshLists()
        loadItems(listId)
        val listName = state.value.lists.firstOrNull { it.id == listId }?.name.orEmpty()
        val done = t("meals.transferSuccess", "count" to result.added, "list" to listName)
        val message = if (result.skipped > 0) "$done ${t("kitchen.transferSkipped", "count" to result.skipped)}" else done
        container.scope.launch {
            val undo = notifier(message, if (canUndo && result.addedIds.isNotEmpty()) t("common.undo") else null)
            if (!undo) return@launch
            try {
                api.undoTransfer(result.addedIds, result.ingredientIds)
                if (state.value.activeListId == listId) {
                    refreshLists()
                    loadItems(listId)
                }
                notifier(t("kitchen.transferUndone"), null)
            } catch (e: ApiProblem) {
                notifier(t(ShoppingLogic.errorKey(e)), null)
            }
        }
        return null
    }

    // ── Pantry (SHOP-R30, SHOP-R31) ──

    suspend fun pantry(): PantryStock? {
        val api = api ?: return null
        return try {
            api.pantry()
        } catch (e: ApiProblem) {
            report(e)
            null
        }
    }

    /** Moves the rows into the pantry; true when the sheet may close. */
    suspend fun toPantry(rows: List<PantryTransferRow>, clearList: Boolean, locations: List<PantryLocation>): Boolean {
        val api = api ?: return false
        val listId = state.value.activeListId ?: return false
        val action = "pantry:$listId:${rows.hashCode()}"
        val result = try {
            api.toPantry(listId, rows, key(action)).also { keys.remove(action) }
        } catch (e: ApiProblem) {
            report(e)
            return false
        }
        val stored = result.added + result.merged
        if (stored == 0) {
            viewModelScope.launch { notifier(t("shopping.toPantryNothing"), null) }
            return true
        }
        val places = rows.map { it.locationId }.toSet()
        val only = places.singleOrNull()
        val placeName = only?.let { id -> locations.firstOrNull { it.id == id }?.name }
        val message = if (placeName != null) {
            t("shopping.toPantryDoneAt", "count" to stored, "location" to (strings?.let { ShoppingLogic.locationLabel(placeName, it) } ?: placeName))
        } else {
            t("shopping.toPantryDone", "count" to stored)
        }
        val undoPantry: suspend () -> Unit = {
            if (result.changes.isNotEmpty()) {
                val a = "pantry-undo:${result.changes.hashCode()}"
                try {
                    api.undoPantry(result.changes, key(a))
                    keys.remove(a)
                } catch (e: ApiProblem) {
                    notifier(t(ShoppingLogic.errorKey(e)), null)
                }
            }
        }
        if (clearList) {
            clearChecked(message, undoPantry)
        } else {
            container.scope.launch { if (notifier(message, t("common.undo"))) undoPantry() }
        }
        return true
    }

    // ── Errors (SHOP-R36, SHOP-R37) ──

    private suspend fun report(e: ApiProblem, fallback: String = "common.errorGeneric", vararg params: Pair<String, Any?>) {
        val key = ShoppingLogic.errorKey(e, fallback)
        val message = if (key == fallback && params.isNotEmpty()) t(key, *params) else t(key)
        viewModelScope.launch { notifier(message, null) }
        if (ShoppingLogic.isGone(e)) reload()
    }

    /** Reload after "no longer exists": lists, categories, shops and the active items. */
    private suspend fun reload() {
        val api = api ?: return
        reloadMeta()
        val lists = runCatching { ShoppingApi.decodeLists(api.lists()) }.getOrNull() ?: return
        _state.update { it.copy(lists = lists) }
        val active = state.value.activeListId
        if (active != null && lists.none { it.id == active }) {
            val next = lists.firstOrNull()?.id
            if (next != null) switchList(next) else _state.update { it.copy(activeListId = null, items = emptyList(), itemsListId = null) }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
    }

    companion object {
        const val CACHE_LISTS = "shopping-lists"
        const val CACHE_CATEGORIES = "shopping-categories"
        const val CACHE_STORES = "shopping-stores"
        const val CACHE_EXTRAS = "shopping-extras"
        const val CACHE_LAST = "shopping-last-list"
        const val POLL_MS = 10_000L
        const val SETTLE_PAUSE_MS = 1_200L
        const val PILL_MS = 5_000L
    }
}
