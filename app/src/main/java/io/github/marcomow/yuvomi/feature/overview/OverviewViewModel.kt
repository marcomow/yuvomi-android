package io.github.marcomow.yuvomi.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** docs/spec/overview.md, OVERVIEW-R13 to R16. */
class OverviewViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val model: OverviewModel? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        /** Set while showing cached content (ADR 0007). */
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val glanceExpanded: Boolean = false,
        val showFiltersHint: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var dashboard: JsonObject? = null

    init {
        container.cache.read(CACHE_KEY)?.let { entry ->
            val json = runCatching { ApiJson.parseToJsonElement(entry.json) as? JsonObject }.getOrNull()
            if (json != null) {
                dashboard = json
                rebuild()
                _state.update { it.copy(loading = false, cachedAt = entry.savedAt) }
            }
        }
        refresh()
    }

    fun refresh(pulled: Boolean = false) {
        val conn = container.session.connection ?: return
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        viewModelScope.launch {
            try {
                val prefs = async { container.session.refreshPreferences() }
                val dash = conn.household.dashboard()
                prefs.await()
                dashboard = dash
                container.cache.write(CACHE_KEY, dash.toString())
                rebuild()
                _state.update { it.copy(loading = false, refreshing = false, cachedAt = null) }
                val model = state.value.model
                if (model?.tilesWithOptions == true && container.settings.firstTime("overview.filtersIgnored")) {
                    _state.update { it.copy(showFiltersHint = true) }
                }
            } catch (e: ApiProblem) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = it.cachedAt ?: if (it.model != null) System.currentTimeMillis() else null,
                        errorKey = if (it.model == null) errorKey(e) else null,
                    )
                }
            }
        }
    }

    fun toggleGlance() = _state.update { it.copy(glanceExpanded = !it.glanceExpanded) }
    fun dismissFiltersHint() = _state.update { it.copy(showFiltersHint = false) }

    private fun rebuild() {
        val session = (container.session.state.value as? SessionState.SignedIn)?.session ?: return
        val dash = dashboard ?: return
        val model = OverviewBuilder.build(dash, session.preferences, session.permissions, HouseholdTime(session.zone), session.user.displayName)
        _state.update { it.copy(model = model) }
    }

    private fun errorKey(e: ApiProblem) = when (e) {
        is ApiProblem.Network -> "android.error.offline"
        is ApiProblem.Malformed -> "android.overview.loadFailed"
        else -> "android.error.generic"
    }

    companion object {
        const val CACHE_KEY = "dashboard"
    }
}
