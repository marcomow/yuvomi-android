package io.github.marcomow.yuvomi.feature.rewards

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** docs/spec/rewards.md, the history (REW-R13): every points entry, filtered by person. */
class RewardsHistoryViewModel(private val container: AppContainer) : ViewModel() {

    @Serializable
    data class Cached(val people: List<RewardBalance> = emptyList(), val entries: List<LedgerEntry> = emptyList())

    data class UiState(
        val people: List<RewardBalance> = emptyList(),
        val entries: List<LedgerEntry> = emptyList(),
        /** `ledgerFilter`: a member id, or null for everyone. */
        val filter: Int? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: RewardsApi? get() = container.session.connection?.client?.let(::RewardsApi)
    private var seq = 0

    init {
        container.cache.read(CACHE_KEY)?.let { cached ->
            runCatching { ApiJson.decodeFromString(Cached.serializer(), cached.json) }.onSuccess { c ->
                _state.update { it.copy(people = c.people, entries = c.entries, loading = false) }
            }
        }
        refresh()
    }

    fun setFilter(id: Int?) {
        _state.update { it.copy(filter = id) }
        refresh()
    }

    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val mine = ++seq
        val filter = state.value.filter
        _state.update { it.copy(refreshing = pulled, failed = false) }
        viewModelScope.launch {
            try {
                val (people, entries) = coroutineScope {
                    val o = async { api.overview() }
                    val l = async { api.ledger(userId = filter) }
                    o.await().balances to l.await()
                }
                if (mine != seq) return@launch
                if (filter == null) container.cache.write(CACHE_KEY, ApiJson.encodeToString(Cached.serializer(), Cached(people, entries)))
                _state.update { it.copy(people = people, entries = entries, loading = false, refreshing = false, cachedAt = null) }
            } catch (e: ApiProblem) {
                if (mine != seq) return@launch
                _state.update {
                    val hasData = !it.loading
                    it.copy(
                        loading = false,
                        refreshing = false,
                        failed = !hasData,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_KEY)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                    )
                }
            }
        }
    }

    companion object {
        const val CACHE_KEY = "rewards-history"
    }
}
