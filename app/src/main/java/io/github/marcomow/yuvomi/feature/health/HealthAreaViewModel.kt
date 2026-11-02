package io.github.marcomow.yuvomi.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer

/**
 * One health area's screen: cache-then-network per person, offline read-only, writes that
 * refresh, deletes with undo (docs/spec/health.md, HEALTH-R3, R7, R8).
 *
 * The offline cache holds the member's OWN data only. Another person's rows are what the
 * server allowed at the time of the request; a copy on the device could outlive a change of
 * visibility or a revoked care grant, so another person's area is network-only.
 */
abstract class HealthAreaViewModel<D : Any>(
    protected val container: AppContainer,
    val hub: HealthHub,
    protected val host: HealthHost,
    private val serializer: KSerializer<D>,
    private val area: String,
) : ViewModel() {

    data class UiState<D>(
        val data: D? = null,
        val personId: Int? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        /** Set while a cached answer is shown because the server could not be reached. */
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val busy: Boolean = false,
        /** Rows in their undo window: hidden, not yet deleted (`scheduleUndoableDelete`). */
        val pendingDeletes: Set<String> = emptySet(),
    ) {
        val offline: Boolean get() = cachedAt != null
    }

    protected val _state = MutableStateFlow(UiState<D>())
    val state: StateFlow<UiState<D>> = _state.asStateFlow()
    protected val api: HealthApi? get() = container.session.connection?.client?.let(::HealthApi)
    private var loadSeq = 0
    private var loadJob: Job? = null

    protected abstract suspend fun fetch(api: HealthApi, personId: Int): D

    /** The key `health.<area>.loadError`, the area's own "could not be loaded". */
    protected open val loadErrorKey: String get() = "health.$area.loadError"

    init {
        viewModelScope.launch {
            // Lets the subclass finish its own construction before the first fetch.
            yield()
            hub.ensure()
            load(fromCache = true)
        }
    }

    private val cacheKey get() = "health-$area"

    private fun readCache(): D? {
        if (!hub.isOwn) return null
        val entry = container.cache.read(cacheKey) ?: return null
        return runCatching { ApiJson.decodeFromString(serializer, entry.json) }.getOrNull()
    }

    protected fun writeCache(personId: Int, data: D) {
        if (personId == hub.meId) container.cache.write(cacheKey, ApiJson.encodeToString(serializer, data))
    }

    private fun load(fromCache: Boolean = false, pulled: Boolean = false) {
        val api = api ?: return
        val person = hub.personId
        val seq = ++loadSeq
        val switching = state.value.personId != person
        _state.update { s ->
            val cached = if (fromCache || switching) readCache() else null
            s.copy(
                data = if (switching) cached else s.data ?: cached,
                personId = person,
                loading = (if (switching) cached == null else s.data == null && cached == null),
                refreshing = pulled,
                errorKey = null,
                pendingDeletes = if (switching) emptySet() else s.pendingDeletes,
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val data = fetch(api, person)
                if (seq != loadSeq) return@launch
                writeCache(person, data)
                _state.update { it.copy(data = data, loading = false, refreshing = false, cachedAt = null, errorKey = null) }
                loaded(data)
            } catch (e: ApiProblem) {
                if (seq != loadSeq) return@launch
                _state.update {
                    val has = it.data != null
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (has && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(cacheKey)?.savedAt ?: System.currentTimeMillis() else null,
                        errorKey = if (has) null else loadErrorKey,
                    )
                }
                if (state.value.data != null && e !is ApiProblem.Network) host.notify(host.strings.t(HealthErrors.key(e, loadErrorKey)))
            }
        }
    }

    /** After a fresh answer arrived; the meds area uses it to settle dose attempts. */
    protected open fun loaded(data: D) = Unit

    fun refresh(pulled: Boolean = false) {
        if (pulled) viewModelScope.launch { hub.ensure(force = true) }
        load(pulled = pulled)
    }

    /** The person switcher (`wirePersonSwitcher`): the choice holds for the whole module. */
    fun choosePerson(id: Int) {
        if (id == state.value.personId) return
        hub.choose(id)
        load()
    }

    /** Another area changed the person meanwhile (`adoptSessionPerson`). */
    fun adoptPerson() {
        if (state.value.personId != null && state.value.personId != hub.personId) load()
    }

    protected suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(host.strings.t("common.errorOfflineMutation"))
        return true
    }

    /**
     * A write: refused offline, then sent; on success [successKey] is shown and the area
     * reloads (`reloadAfterSave`); on failure upstream's sentence, and a row someone else
     * deleted meanwhile reloads the area (`reportError`, `isGone`).
     */
    protected fun write(
        fallbackKey: String,
        successKey: String? = null,
        onSuccess: () -> Unit = {},
        reload: Boolean = true,
        block: suspend (HealthApi) -> Unit,
    ) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(busy = true) }
            try {
                block(api)
                _state.update { it.copy(busy = false) }
                onSuccess()
                if (reload) load()
                successKey?.let { host.notify(host.strings.t(it)) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(busy = false) }
                host.notify(host.strings.t(HealthErrors.key(e, fallbackKey)))
                if (HealthErrors.isGone(e)) load()
            }
        }
    }

    /** `deleteRowWithUndo`: the row hides at once, the DELETE goes out when the snackbar ends without undo. */
    protected fun deleteWithUndo(key: String, messageKey: String, fallbackKey: String, call: suspend (HealthApi) -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(pendingDeletes = it.pendingDeletes + key) }
            container.scope.launch {
                val strings = host.strings
                if (host.notify(strings.t(messageKey), strings.t("common.undo"))) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - key) }
                    return@launch
                }
                try {
                    call(api)
                } catch (e: ApiProblem) {
                    // An entry someone else deleted meanwhile is gone either way.
                    if (!HealthErrors.isGone(e)) host.notify(strings.t(HealthErrors.key(e, fallbackKey)))
                }
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - key) }
                load()
            }
        }
    }

    /** Whether the row with [key] sits in its undo window. */
    fun hidden(key: String): Boolean = key in state.value.pendingDeletes
}
