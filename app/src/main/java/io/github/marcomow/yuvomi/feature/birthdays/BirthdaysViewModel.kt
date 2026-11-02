package io.github.marcomow.yuvomi.feature.birthdays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the birthday screens need from the shell; an interface so the view models are tested without one. */
interface BirthdaysHost {
    val strings: Strings

    /** `readOnly()`: the module's right is `calendar` (ModuleCatalog). */
    val readOnly: Boolean

    /** `moduleAccess('contacts') !== 'none'` and Contacts switched on: import and link. */
    val contactsVisible: Boolean

    suspend fun notify(message: String, action: String? = null): Boolean
}

/** docs/spec/birthdays.md: the list, delete with undo, linking, import and the feed (BDAY-R1 to R9, R14 to R17). */
class BirthdaysViewModel(private val container: AppContainer, private val host: BirthdaysHost) : ViewModel() {

    data class UiState(
        val birthdays: List<Birthday> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val query: String = "",
        /** Deleted, inside the undo window (`scheduleUndoableDelete`). */
        val pendingDeletes: Set<Int> = emptySet(),
    ) {
        val offline: Boolean get() = cachedAt != null
        val visible: List<Birthday> get() = BirthdaysRules.filtered(birthdays.filter { it.id !in pendingDeletes }, query)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: BirthdaysApi? get() = container.session.connection?.client?.let(::BirthdaysApi)
    private val strings get() = host.strings
    var lastLoaded = 0L
        private set
    private var seq = 0

    init {
        container.cache.read(CACHE_KEY)?.let { cached ->
            runCatching { BirthdaysApi.decodeList(io.github.marcomow.yuvomi.core.api.ApiJson.parseToJsonElement(cached.json)) }
                .onSuccess { list -> _state.update { it.copy(birthdays = list, loading = false) } }
        }
        refresh()
    }

    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val mine = ++seq
        _state.update { it.copy(refreshing = pulled, errorKey = null, loading = it.loading || it.errorKey != null) }
        viewModelScope.launch {
            try {
                val json = api.list()
                val list = BirthdaysApi.decodeList(json)
                if (mine != seq) return@launch
                container.cache.write(CACHE_KEY, json.toString())
                lastLoaded = System.currentTimeMillis()
                _state.update { it.copy(birthdays = list, loading = false, refreshing = false, cachedAt = null) }
            } catch (e: ApiProblem) {
                if (mine != seq) return@launch
                _state.update {
                    val hasData = !it.loading
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_KEY)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        errorKey = if (hasData) null else LOAD_ERROR,
                    )
                }
                if (!state.value.loading && state.value.errorKey == null && e !is ApiProblem.Network) host.notify(strings.t(BirthdaysRules.errorKey(e)))
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun find(id: Int): Birthday? = state.value.birthdays.firstOrNull { it.id == id }

    /** After the editor saved: the answer replaces the row at once, the reload confirms it. */
    fun saved(b: Birthday) {
        _state.update { s -> s.copy(birthdays = s.birthdays.filter { it.id != b.id } + b) }
        refresh()
    }

    /** The editor found the birthday gone: it leaves the list. */
    fun gone(id: Int) = _state.update { s -> s.copy(birthdays = s.birthdays.filter { it.id != id }) }

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(strings.t("common.errorOfflineMutation"))
        return true
    }

    /** BDAY-R7: the row goes at once; the delete goes out when the undo window closes. */
    fun delete(id: Int) {
        if (host.readOnly) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val api = api ?: return@launch
            _state.update { it.copy(pendingDeletes = it.pendingDeletes + id) }
            container.scope.launch {
                if (host.notify(strings.t("birthdays.deletedToast"), strings.t("common.undo"))) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - id) }
                    return@launch
                }
                try {
                    try {
                        api.delete(id)
                    } catch (e: ApiProblem) {
                        // Someone else was faster: deleted is deleted (#1909).
                        if (!BirthdaysRules.isGone(e)) throw e
                    }
                    _state.update { s -> s.copy(birthdays = s.birthdays.filter { it.id != id }, pendingDeletes = s.pendingDeletes - id) }
                    container.cache.write(CACHE_KEY, ApiJsonText.list(state.value.birthdays))
                } catch (e: ApiProblem) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - id) }
                    host.notify(strings.t(BirthdaysRules.errorKey(e)))
                }
            }
        }
    }

    /** BDAY-R10: "Link" on the quiet hint, and in the import list. Returns the fresh birthday or null. */
    suspend fun link(birthdayId: Int, contactId: Int): Birthday? {
        if (refuseOffline()) return null
        val api = api ?: return null
        return try {
            val fresh = api.link(birthdayId, contactId)
            host.notify(strings.t("birthdays.linkedToast"))
            refresh()
            // The answer of PUT carries no `contact_name`; the list's next answer does.
            fresh
        } catch (e: ApiProblem) {
            host.notify(strings.t(BirthdaysRules.errorKey(e)))
            null
        }
    }

    // ── BDAY-R14: import from contacts ──

    suspend fun candidates(): Result<ImportCandidates> {
        val api = api ?: return Result.failure(IllegalStateException())
        return try {
            Result.success(api.candidates())
        } catch (e: ApiProblem) {
            Result.failure(IllegalStateException(strings.t(if (e is ApiProblem.Network) "common.errorOffline" else BirthdaysRules.errorKey(e))))
        }
    }

    /** Imports in batches of 500 (the API's limit); returns the sentence of a failure or null. */
    suspend fun import(ids: List<Int>): String? {
        if (ids.isEmpty()) return strings.t("birthdays.importNothingSelected")
        if (state.value.offline) return strings.t("common.errorOfflineMutation")
        val api = api ?: return strings.t("common.errorGeneric")
        var imported = 0
        try {
            ids.chunked(500).forEach { batch -> imported += api.import(batch, YuvomiClient.newIdempotencyKey()).imported }
        } catch (e: ApiProblem) {
            val sentence = strings.t(BirthdaysRules.errorKey(e))
            if (imported > 0) {
                refresh()
                return "${strings.t("birthdays.importSuccess", "count" to imported)} $sentence"
            }
            return sentence
        }
        container.scope.launch { host.notify(strings.t("birthdays.importSuccess", "count" to imported)) }
        refresh()
        return null
    }

    // ── BDAY-R15 to R17: the birthday feed ──

    suspend fun feed(): Result<BirthdayFeed?> {
        val api = api ?: return Result.failure(IllegalStateException())
        return try {
            Result.success(api.feed())
        } catch (e: ApiProblem) {
            Result.failure(IllegalStateException(strings.t(BirthdaysRules.errorKey(e))))
        }
    }

    /** On (first address) or a new address; null on failure, which is told. */
    suspend fun regenerateFeed(): BirthdayFeed? {
        if (refuseOffline()) return null
        return try {
            api?.regenerateFeed()
        } catch (e: ApiProblem) {
            host.notify(strings.t(BirthdaysRules.errorKey(e)))
            null
        }
    }

    suspend fun disableFeed(): Boolean {
        if (refuseOffline()) return false
        return try {
            api?.disableFeed()
            true
        } catch (e: ApiProblem) {
            host.notify(strings.t(BirthdaysRules.errorKey(e)))
            false
        }
    }

    companion object {
        const val CACHE_KEY = "birthdays-list"
        const val LOAD_ERROR = "android.birthdays.loadError"
    }
}

/** The list back to the JSON shape of `GET /birthdays`, for the offline cache after a local change. */
internal object ApiJsonText {
    fun list(list: List<Birthday>): String {
        val data = io.github.marcomow.yuvomi.core.api.ApiJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Birthday.serializer()), list)
        return kotlinx.serialization.json.JsonObject(mapOf("data" to data)).toString()
    }
}
