package io.github.marcomow.yuvomi.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One sheet's data (SET-R5, SET-R6): every answer a section loads lives in a named slot, so
 * it survives a rotation and two sections of one sheet share it (the preferences above all).
 * Reads are cache-then-network where [load] is asked to cache (ADR 0007); writes go straight
 * to the server and never queue.
 */
class SettingsSheetViewModel(private val container: AppContainer) : ViewModel() {

    data class Slot(
        val value: JsonElement? = null,
        val loading: Boolean = true,
        val errorKey: String? = null,
        /** Set while a cached answer stands in for an unreachable server. */
        val cachedAt: Long? = null,
    )

    private val _slots = MutableStateFlow<Map<String, Slot>>(emptyMap())
    val slots: StateFlow<Map<String, Slot>> = _slots.asStateFlow()

    val api: SettingsApi? get() = container.session.connection?.client?.let(::SettingsApi)

    /** True while any slot shows a cached answer: the sheet is read-only then (ADR 0007). */
    val offline: Boolean get() = _slots.value.values.any { it.cachedAt != null }

    /** Loads [key] unless it is loaded already (or [force]). */
    fun load(key: String, force: Boolean = false, cache: Boolean = false, fetch: suspend (SettingsApi) -> JsonElement) {
        val current = _slots.value[key]
        if (!force && current != null && (current.loading || current.value != null)) return
        val api = api ?: return
        if (current == null && cache) {
            container.cache.read(cacheKey(key))?.let { entry ->
                runCatching { ApiJson.parseToJsonElement(entry.json) }.getOrNull()?.let { put(key, Slot(it, loading = true)) }
            }
        }
        _slots.update { it + (key to (it[key] ?: Slot()).copy(loading = true, errorKey = null)) }
        viewModelScope.launch {
            try {
                val value = fetch(api)
                if (cache) container.cache.write(cacheKey(key), value.toString())
                put(key, Slot(value, loading = false))
            } catch (e: ApiProblem) {
                val had = _slots.value[key]?.value
                val savedAt = if (cache && e is ApiProblem.Network) container.cache.read(cacheKey(key))?.savedAt else null
                put(key, Slot(had, loading = false, errorKey = if (had == null) SettingsErrors.loadKey(e) else null, cachedAt = if (had != null) savedAt ?: System.currentTimeMillis().takeIf { e is ApiProblem.Network } else null))
            }
        }
    }

    fun put(key: String, slot: Slot) = _slots.update { it + (key to slot) }

    /** Replaces the value of [key] after a successful write. */
    fun set(key: String, value: JsonElement) = _slots.update { it + (key to (it[key] ?: Slot()).copy(value = value, loading = false, errorKey = null)) }

    fun preferences(): JsonObject? = _slots.value[PREFERENCES]?.value as? JsonObject

    fun loadPreferences(force: Boolean = false) = load(PREFERENCES, force, cache = true) { it.preferences() }

    /**
     * `savePreferences(patch)`: the server answers the whole new set, which replaces the
     * sheet's copy; the shell's copy follows, so modules switched off or a new time zone
     * show at once. Returns the error key, or null.
     */
    suspend fun savePreferences(patch: JsonObject): String? = write("settings.preferenceSaveFailed") { api ->
        val saved = api.savePreferences(patch)
        val merged = JsonObject((preferences() ?: JsonObject(emptyMap())) + saved)
        set(PREFERENCES, merged)
        container.cache.write(cacheKey(PREFERENCES), merged.toString())
        container.session.refreshPreferences()
    }

    /** Runs a write; returns null on success, else the i18n key of the sentence to show. */
    suspend fun write(fallback: String = "common.errorGeneric", action: suspend (SettingsApi) -> Unit): String? {
        val api = api ?: return "common.errorGeneric"
        return try {
            action(api)
            null
        } catch (e: ApiProblem) {
            SettingsErrors.key(e, fallback)
        }
    }

    /** Like [write], but hands the problem itself to [onError] for reason-specific handling. */
    suspend fun <T> attempt(action: suspend (SettingsApi) -> T): Result<T> {
        val api = api ?: return Result.failure(IllegalStateException("signed out"))
        return try {
            Result.success(action(api))
        } catch (e: ApiProblem) {
            Result.failure(e)
        }
    }

    /** The session's own refresh after the member changed their name, picture or role. */
    suspend fun refreshSession() = runCatching { container.session.refresh() }

    private fun cacheKey(key: String) = "settings.$key"

    companion object {
        const val PREFERENCES = "preferences"
    }
}
