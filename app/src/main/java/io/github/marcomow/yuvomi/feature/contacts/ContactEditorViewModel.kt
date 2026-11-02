package io.github.marcomow.yuvomi.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** docs/spec/contacts.md, the form (CONT-R7, R17). */
class ContactEditorViewModel(
    private val container: AppContainer,
    private val host: ContactsHost,
    private val contactId: Int?,
    /** The list's active category, preselected for a new contact. */
    private val presetCategory: String?,
) : ViewModel() {

    data class UiState(
        val original: Contact? = null,
        val categories: List<ContactCategory> = emptyList(),
        val draft: ContactDraft = ContactDraft(),
        val loading: Boolean = true,
        val error: String? = null,
        val saving: Boolean = false,
        /** The first-name field's own message (`reportFieldErrors`). */
        val nameError: Boolean = false,
        val nameTouched: Boolean = false,
        /** Set once saved, deleted or found gone: the screen closes. */
        val done: Boolean = false,
    ) {
        val isEdit: Boolean get() = original != null

        /** A category that no longer exists, kept as an option so the form does not change it. */
        val orphanCategory: String? get() = original?.category?.takeIf { key -> categories.none { it.key == key } }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: ContactsApi? get() = container.session.connection?.client?.let(::ContactsApi)
    private val idempotencyKey = YuvomiClient.newIdempotencyKey()

    val emailsLocked: Boolean get() = ContactsRules.emailsLocked(state.value.original, host.userId, host.isAdmin)

    init {
        load()
    }

    fun load() {
        val api = api ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val categories = try {
                    ContactsApi.decodeCategories(api.categories())
                } catch (e: ApiProblem.Network) {
                    container.cache.read(ContactsViewModel.CACHE_CATEGORIES)?.let { ContactsApi.decodeCategories(ApiJson.parseToJsonElement(it.json)) } ?: throw e
                }
                val original = contactId?.let { api.get(it) }
                val category = ContactsRules.defaultCategory(presetCategory, categories)
                _state.update { it.copy(original = original, categories = categories, draft = ContactsRules.draft(original, category), loading = false) }
            } catch (e: ApiProblem) {
                if (ContactsRules.isGone(e)) {
                    host.notify(ContactsRules.errorSentence(e, host.strings))
                    ContactsBus.changed()
                    _state.update { it.copy(done = true) }
                    return@launch
                }
                _state.update { it.copy(loading = false, error = ContactsRules.loadErrorSentence(e, host.strings)) }
            }
        }
    }

    fun edit(change: (ContactDraft) -> ContactDraft) = _state.update { it.copy(draft = change(it.draft)) }

    fun editName(first: String? = null, last: String? = null) = _state.update {
        it.copy(
            draft = it.draft.copy(firstName = first ?: it.draft.firstName, lastName = last ?: it.draft.lastName),
            nameTouched = true,
            nameError = false,
        )
    }

    fun save() {
        val api = api ?: return
        val s = state.value
        if (!host.canWrite || s.saving || s.loading) return
        if (!s.draft.birth.read().complete) {
            viewModelScope.launch { host.notify(host.strings.t("birthdays.yearlessIncomplete")) }
            return
        }
        if (ContactsRules.formName(s.draft, s.original, s.nameTouched).isBlank()) {
            _state.update { it.copy(nameError = true) }
            return
        }
        val categoryKnown = !(s.orphanCategory != null && s.draft.category == s.orphanCategory)
        val body = ContactsRules.body(s.draft, s.original, s.nameTouched, emailsLocked, categoryKnown)
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val original = s.original
                if (original == null) api.create(body, idempotencyKey) else api.update(original.id, body)
                ContactsBus.changed()
                _state.update { it.copy(saving = false, done = true) }
                host.notify(host.strings.t(if (original == null) "contacts.savedToast" else "contacts.updatedToast"))
            } catch (e: ApiProblem) {
                _state.update { it.copy(saving = false) }
                host.notify(ContactsRules.errorSentence(e, host.strings))
                when {
                    s.original != null && ContactsRules.isGone(e) -> {
                        ContactsBus.changed()
                        _state.update { it.copy(done = true) }
                    }
                    (e as? ApiProblem.Http)?.reason == "contacts_selected_category_not_found" -> offerCurrentCategories()
                }
            }
        }
    }

    /** `offerCurrentCategories`: the form stays open with today's categories. */
    private suspend fun offerCurrentCategories() {
        val api = api ?: return
        val categories = runCatching { ContactsApi.decodeCategories(api.categories()) }.getOrNull() ?: return
        _state.update { s ->
            val chosen = s.draft.category
            val keep = categories.any { it.key == chosen } || chosen == s.orphanCategory
            s.copy(categories = categories, draft = s.draft.copy(category = if (keep) chosen else ContactsRules.defaultCategory(null, categories)))
        }
        ContactsBus.changed()
    }

    /** The form's delete: the list's undoable delete, and the form closes. */
    fun delete() {
        val c = state.value.original ?: return
        if (!host.canWrite || c.isMember) return
        _state.update { it.copy(done = true) }
        ContactsBus.delete(container, host, listOf(c.id), host.strings.t("contacts.deletedToast"))
    }
}
