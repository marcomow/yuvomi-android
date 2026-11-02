package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.WeakHashMap

/**
 * The tab's data, shared by the list, the editor and the manager, like the web page's
 * module-level `state` (docs/spec/subscriptions.md). One store per signed-in connection. The
 * server filters and computes the totals; every write reloads from it (the web's `reload()`).
 */
class SubscriptionsStore(private val cache: ResponseCache, val api: SubscriptionsApi, private val scope: CoroutineScope) {

    data class State(
        val subscriptions: List<Subscription> = emptyList(),
        val summary: Summary? = null,
        val rates: Rates? = null,
        val meta: SubscriptionMeta = SubscriptionMeta(),
        val settings: SubscriptionSettings = SubscriptionSettings(),
        val query: String = "",
        val filters: SubscriptionRules.Filters = SubscriptionRules.Filters(),
        val sort: String = SubscriptionRules.SORT_DUE,
        val loading: Boolean = true,
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val pendingDeletes: Set<Int> = emptySet(),
    ) {
        val shown: List<Subscription> get() = subscriptions.filter { it.id !in pendingDeletes }
        val offline: Boolean get() = cachedAt != null
        val baseCurrency: String get() = summary?.baseCurrency ?: settings.baseCurrency
        val hasActiveFilters: Boolean get() = query.isNotBlank() || filters.count > 0
        fun subscription(id: Int): Subscription? = subscriptions.firstOrNull { it.id == id }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var lastLoaded = 0L
        private set

    /** `loadTicket`: an overtaken load takes nothing over. */
    private var ticket = 0

    init {
        runCatching {
            val list = cache.read(CACHE_LIST)
            val meta = cache.read(CACHE_META)
            val settings = cache.read(CACHE_SETTINGS)
            if (list != null && meta != null && settings != null) {
                val l = SubscriptionsApi.decodeList(ApiJson.parseToJsonElement(list.json))
                _state.update {
                    it.copy(
                        subscriptions = l.subscriptions, summary = l.summary, rates = l.rates,
                        meta = SubscriptionsApi.decodeMeta(ApiJson.parseToJsonElement(meta.json)),
                        settings = SubscriptionsApi.decodeSettings(ApiJson.parseToJsonElement(settings.json)),
                        loading = false, cachedAt = list.savedAt,
                    )
                }
            }
        }
    }

    fun refresh(pulled: Boolean = false, refreshRates: Boolean = false) {
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        scope.launch { load(refreshRates) }
    }

    private fun query(s: State, refreshRates: Boolean) = SubscriptionQuery(
        q = s.query, categoryId = s.filters.categoryId, paymentMethodId = s.filters.paymentMethodId,
        status = s.filters.status, refreshRates = refreshRates,
    )

    /** `loadOnce()`: list, meta and settings in parallel. Returns false when nothing new was applied. */
    suspend fun load(refreshRates: Boolean = false): Boolean {
        val mine = ++ticket
        val s = _state.value
        return try {
            val (list, meta, settings) = coroutineScope {
                val l = async { api.list(query(s, refreshRates)) }
                val m = async { api.meta() }
                val st = async { api.settings() }
                Triple(l.await(), m.await(), st.await())
            }
            if (mine != ticket) return false
            val l = SubscriptionsApi.decodeList(list)
            val m = SubscriptionsApi.decodeMeta(meta)
            if (!s.hasActiveFilters) cache.write(CACHE_LIST, list.toString())
            cache.write(CACHE_META, meta.toString())
            cache.write(CACHE_SETTINGS, settings.toString())
            lastLoaded = System.currentTimeMillis()
            _state.update {
                it.copy(
                    subscriptions = l.subscriptions, summary = l.summary, rates = l.rates, meta = m,
                    settings = SubscriptionsApi.decodeSettings(settings),
                    loading = false, loaded = true, refreshing = false, cachedAt = null, errorKey = null,
                )
            }
            val pruned = SubscriptionRules.withoutGone(_state.value.filters, m)
            if (pruned != _state.value.filters) {
                _state.update { it.copy(filters = pruned) }
                return load(refreshRates)
            }
            true
        } catch (e: ApiProblem) {
            if (mine != ticket) return false
            _state.update {
                val hasData = it.subscriptions.isNotEmpty() || it.cachedAt != null
                it.copy(
                    loading = false, refreshing = false,
                    cachedAt = it.cachedAt ?: if (hasData && e is ApiProblem.Network) System.currentTimeMillis() else null,
                    errorKey = if (hasData && e is ApiProblem.Network) null else loadErrorKey(e),
                )
            }
            false
        }
    }

    private fun loadErrorKey(e: ApiProblem): String = when {
        e is ApiProblem.Network -> "android.error.offline"
        e is ApiProblem.Http && e.operationUnsupported -> "android.error.unsupported"
        e is ApiProblem.Http && e.status == 403 -> SubscriptionErrors.key(e)
        else -> "subscriptions.loadError"
    }

    // ── Search, filters, sort (server filters, local sort) ──

    fun setQuery(q: String) {
        if (q.trim() == _state.value.query.trim()) return
        _state.update { it.copy(query = q) }
        refresh()
    }

    fun setFilters(f: SubscriptionRules.Filters) {
        if (f == _state.value.filters) return
        _state.update { it.copy(filters = f) }
        refresh()
    }

    /** `resetFilters()` / `clearSubscriptionFilters()`: search and filters go, the sort stays. */
    fun resetFilters() {
        _state.update { it.copy(query = "", filters = SubscriptionRules.Filters()) }
        refresh()
    }

    fun setSort(sort: String) = _state.update { it.copy(sort = sort) }

    // ── Renew with undo (`renewSubscription()`) ──

    /** Result of a payment: the toast sentence, and the token "Undo" takes back, or an error key. */
    data class RenewResult(val completed: Boolean, val nextDate: String?, val token: String?, val errorKey: String?)

    suspend fun renew(s: Subscription): RenewResult = try {
        val r = api.renew(s.id, YuvomiClient.newIdempotencyKey())
        load()
        RenewResult(r.subscription.isCompleted, r.subscription.nextPaymentDate, r.undoToken, null)
    } catch (e: ApiProblem) {
        if (SubscriptionErrors.isGone(e) || SubscriptionErrors.reason(e) == SubscriptionErrors.COMPLETED) load()
        RenewResult(false, null, null, SubscriptionErrors.key(e))
    }

    /** POST `/renew/undo`; returns an error key or null. */
    suspend fun undoRenew(id: Int, token: String): String? = try {
        api.undoRenew(id, token, YuvomiClient.newIdempotencyKey())
        load()
        null
    } catch (e: ApiProblem) {
        load()
        SubscriptionErrors.key(e)
    }

    // ── Delete with undo (`deleteSubscription()`) ──

    fun scheduleDelete(s: Subscription, strings: Strings, notify: suspend (message: String, action: String?) -> Boolean) {
        _state.update { it.copy(pendingDeletes = it.pendingDeletes + s.id) }
        scope.launch {
            val undone = notify(strings.t("common.deletedNamed", "name" to s.name), strings.t("common.undo"))
            if (undone) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - s.id) }
                return@launch
            }
            try {
                api.delete(s.id)
            } catch (e: ApiProblem) {
                if (!SubscriptionErrors.isAlreadyDeleted(e, SubscriptionErrors.SUBSCRIPTION_GONE)) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - s.id) }
                    notify(strings.t(SubscriptionErrors.key(e)), null)
                    load()
                    return@launch
                }
            }
            _state.update { st -> st.copy(subscriptions = st.subscriptions.filter { it.id != s.id }, pendingDeletes = st.pendingDeletes - s.id) }
            load()
        }
    }

    // ── Create and edit (`saveSubscription()`) ──

    /** Saves, then reloads. Throws [ApiProblem] for the editor to explain. */
    suspend fun save(existing: Subscription?, body: JsonObject, idempotencyKey: String): Subscription {
        val saved = try {
            if (existing == null) api.create(body, idempotencyKey) else api.update(existing.id, body)
        } catch (e: ApiProblem) {
            if (SubscriptionErrors.isGone(e)) load()
            throw e
        }
        load()
        return saved
    }

    // ── Settings (`openSettingsModal()`) ──

    suspend fun saveSettings(budget: Double, currency: String): String? = try {
        api.saveSettings(budget, currency)
        load(refreshRates = true)
        null
    } catch (e: ApiProblem) {
        SubscriptionErrors.key(e)
    }

    // ── Categories and payment methods (`openMetadataModal()`, `saveMetadata()`) ──

    private suspend fun metaWrite(block: suspend () -> Unit): String? = try {
        block()
        load()
        null
    } catch (e: ApiProblem) {
        if (SubscriptionErrors.isGone(e)) load()
        SubscriptionErrors.key(e)
    }

    suspend fun createCategory(name: String, color: String) = metaWrite { api.createCategory(name.trim(), color, YuvomiClient.newIdempotencyKey()) }
    suspend fun createMethod(name: String) = metaWrite { api.createMethod(name.trim(), YuvomiClient.newIdempotencyKey()) }
    suspend fun updateCategory(id: Int, name: String, color: String) = metaWrite { api.updateCategory(id, name, color) }
    suspend fun updateMethod(id: Int, name: String) = metaWrite { api.updateMethod(id, name) }

    /** "Already deleted" is a success (#1909). */
    suspend fun deleteCategory(id: Int) = metaWrite {
        try { api.deleteCategory(id) } catch (e: ApiProblem) { if (!SubscriptionErrors.isAlreadyDeleted(e, SubscriptionErrors.CATEGORY_GONE)) throw e }
    }

    suspend fun deleteMethod(id: Int) = metaWrite {
        try { api.deleteMethod(id) } catch (e: ApiProblem) { if (!SubscriptionErrors.isAlreadyDeleted(e, SubscriptionErrors.METHOD_GONE)) throw e }
    }

    /** One step up (-1) or down (+1) of a category or payment method. */
    suspend fun move(kind: String, id: Int, delta: Int): String? {
        val ids = if (kind == KIND_CATEGORIES) state.value.meta.categories.map { it.id } else state.value.meta.paymentMethods.map { it.id }
        val next = SubscriptionRules.moved(ids, id, delta) ?: return null
        return metaWrite { api.reorder(kind, next) }
    }

    /** The logos the server finds; an error key when none or the search failed. */
    suspend fun findLogos(query: String): Pair<List<String>, String?> = try {
        val found = api.logoSearch(query, YuvomiClient.newIdempotencyKey())
        found to (if (found.isEmpty()) "subscriptions.logoSearchEmpty" else null)
    } catch (e: ApiProblem) {
        emptyList<String>() to SubscriptionErrors.key(e, "subscriptions.logoSearchError")
    }

    companion object {
        const val CACHE_LIST = "subscriptions-list"
        const val CACHE_META = "subscriptions-meta"
        const val CACHE_SETTINGS = "subscriptions-settings"
        const val KIND_CATEGORIES = "categories"
        const val KIND_METHODS = "payment_methods"

        private val stores = WeakHashMap<Connection, SubscriptionsStore>()

        fun of(container: AppContainer): SubscriptionsStore? {
            val connection = container.session.connection ?: return null
            return synchronized(stores) {
                stores.getOrPut(connection) { SubscriptionsStore(container.cache, SubscriptionsApi(connection.client), container.scope) }
            }
        }
    }
}
