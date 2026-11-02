package io.github.marcomow.yuvomi.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** docs/spec/contacts.md, the category manager (CONT-R16; `category-manager.js` on `/contacts/categories`). */
class ContactCategoriesViewModel(private val container: AppContainer, private val host: ContactsHost) : ViewModel() {

    data class UiState(
        val categories: List<ContactCategory> = emptyList(),
        val colors: List<String> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: ContactsApi? get() = container.session.connection?.client?.let(::ContactsApi)

    init {
        load()
    }

    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val categories = ContactsApi.decodeCategories(api.categories())
                val colors = runCatching { ContactsApi.decodeColors(api.meta()) }.getOrDefault(emptyList())
                _state.update { it.copy(categories = categories, colors = colors, loading = false, error = null) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(loading = false, error = ContactsRules.loadErrorSentence(e, host.strings)) }
            }
        }
    }

    private fun run(success: String?, block: suspend (ContactsApi) -> Unit) {
        val api = api ?: return
        if (!host.canWrite || state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(api)
                ContactsBus.changed()
                success?.let { host.notify(host.strings.t(it)) }
            } catch (e: ApiProblem) {
                host.notify(ContactsRules.categoryError(e, host.strings))
                // A category someone else changed meanwhile: show today's list.
                if (e is ApiProblem.Http && (e.status == 404 || e.status == 409)) {
                    runCatching { ContactsApi.decodeCategories(api.categories()) }.getOrNull()?.let { c -> _state.update { it.copy(categories = c) } }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun add(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        run("category.added") { api ->
            val created = api.createCategory(trimmed, YuvomiClient.newIdempotencyKey())
            _state.update { it.copy(categories = it.categories + created) }
        }
    }

    fun rename(key: String, name: String) {
        val trimmed = name.trim()
        val current = state.value.categories.firstOrNull { it.key == key } ?: return
        if (trimmed.isEmpty() || trimmed == ContactsRules.categoryLabel(current, host.strings)) return
        run("category.renamed") { api ->
            val updated = api.renameCategory(key, trimmed)
            _state.update { s -> s.copy(categories = s.categories.map { if (it.key == key) updated else it }) }
        }
    }

    fun recolor(key: String, color: String?) = run(null) { api ->
        val updated = api.recolorCategory(key, color)
        _state.update { s -> s.copy(categories = s.categories.map { if (it.key == key) updated else it }) }
    }

    /** Up (-1) or down (+1) by one place (the manager's menu entries). */
    fun move(key: String, direction: Int) {
        val list = state.value.categories
        val i = list.indexOfFirst { it.key == key }
        val j = i + direction
        if (i == -1 || j !in list.indices) return
        val order = list.toMutableList().apply { add(j, removeAt(i)) }
        _state.update { it.copy(categories = order) }
        run(null) { api -> _state.update { it.copy(categories = api.reorderCategories(order.map { c -> c.key })) } }
    }

    fun delete(key: String) = run("category.deleted") { api ->
        api.deleteCategory(key)
        _state.update { s -> s.copy(categories = s.categories.filter { it.key != key }) }
    }
}
