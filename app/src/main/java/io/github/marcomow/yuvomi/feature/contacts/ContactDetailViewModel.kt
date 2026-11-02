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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** docs/spec/contacts.md, the read view (CONT-R6 to R10, R13). */
class ContactDetailViewModel(
    private val container: AppContainer,
    private val host: ContactsHost,
    private val id: Int,
) : ViewModel() {

    data class UiState(
        val contact: Contact? = null,
        val categories: List<ContactCategory> = emptyList(),
        val loading: Boolean = true,
        val cachedAt: Long? = null,
        val error: String? = null,
        /** The contact was deleted (here or elsewhere): the screen goes back. */
        val gone: Boolean = false,
        val busy: Boolean = false,
    ) {
        val offline: Boolean get() = cachedAt != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: ContactsApi? get() = container.session.connection?.client?.let(::ContactsApi)
    private val cacheKey = "contact-$id"

    init {
        readCache()
        load()
        viewModelScope.launch { ContactsBus.version.drop(1).collect { load() } }
    }

    private fun readCache() {
        val cats = container.cache.read(ContactsViewModel.CACHE_CATEGORIES)?.let { runCatching { ContactsApi.decodeCategories(ApiJson.parseToJsonElement(it.json)) }.getOrNull() }
        // Without its own copy the list's row stands in until the full contact arrives.
        val own = container.cache.read(cacheKey)?.let { runCatching { ApiJson.decodeFromString(Contact.serializer(), it.json) }.getOrNull() }
        val fromList = own ?: container.cache.read(ContactsViewModel.CACHE_LIST)?.let { e ->
            runCatching { ContactsApi.decodeContacts(ApiJson.parseToJsonElement(e.json)) }.getOrNull()?.firstOrNull { it.id == id }
        }
        _state.update { it.copy(contact = fromList, categories = cats ?: emptyList(), loading = fromList == null) }
    }

    /** `fetchFullContact`: the contact with all numbers, addresses and the birthday suggestion. */
    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val contact = api.get(id)
                container.cache.write(cacheKey, ApiJson.encodeToString(Contact.serializer(), contact))
                _state.update { it.copy(contact = contact, loading = false, cachedAt = null, error = null) }
                if (state.value.categories.isEmpty()) {
                    runCatching { ContactsApi.decodeCategories(api.categories()) }.getOrNull()?.let { c -> _state.update { it.copy(categories = c) } }
                }
            } catch (e: ApiProblem) {
                if (ContactsRules.isGone(e)) {
                    host.notify(ContactsRules.errorSentence(e, host.strings))
                    _state.update { it.copy(gone = true) }
                    return@launch
                }
                _state.update {
                    val has = it.contact != null
                    it.copy(
                        loading = false,
                        cachedAt = if (has && e is ApiProblem.Network) container.cache.read(cacheKey)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        error = if (has) null else ContactsRules.loadErrorSentence(e, host.strings),
                    )
                }
            }
        }
    }

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(host.strings.t("common.errorOfflineMutation"))
        return true
    }

    /** CONT-R11 from the read view: back to the list, which hides it until the undo runs out. */
    fun delete() {
        val c = state.value.contact ?: return
        if (!host.canWrite || c.isMember) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(gone = true) }
            ContactsBus.delete(container, host, listOf(c.id), host.strings.t("contacts.deletedToast"))
        }
    }

    /** CONT-R9: "Add to birthdays" (`addContactToBirthdays`). */
    fun addToBirthdays() = birthdayWrite("contacts.addedToBirthdays") { api, c -> api.importBirthday(c.id, YuvomiClient.newIdempotencyKey()) }

    /** CONT-R10: "Link" of the suggestion (`linkContactToBirthday`). */
    fun linkBirthday() {
        val target = state.value.contact?.suggestedBirthdayId ?: return
        birthdayWrite("contacts.linkedToBirthday") { api, c -> api.linkBirthday(target, c.id) }
    }

    private fun birthdayWrite(success: String, write: suspend (ContactsApi, Contact) -> Unit) {
        val api = api ?: return
        val c = state.value.contact ?: return
        if (!host.mayWriteBirthdays || state.value.busy) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(busy = true) }
            try {
                write(api, c)
                val loaded = api.get(c.id)
                if (loaded.birthdayId == null) throw ApiProblem.Malformed("birthday not linked")
                container.cache.write(cacheKey, ApiJson.encodeToString(Contact.serializer(), loaded))
                _state.update { it.copy(contact = loaded, busy = false) }
                host.notify(host.strings.t(success))
                ContactsBus.changed()
            } catch (e: ApiProblem) {
                _state.update { it.copy(busy = false) }
                host.notify(ContactsRules.errorSentence(e, host.strings))
            }
        }
    }

    /** CONT-R15: the server's vCard, for the system's "save as". */
    suspend fun vcard(): ByteArray? {
        val api = api ?: return null
        return try {
            api.vcard(id)
        } catch (e: ApiProblem) {
            host.notify(ContactsRules.errorSentence(e, host.strings))
            null
        }
    }
}
