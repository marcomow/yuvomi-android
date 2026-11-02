package io.github.marcomow.yuvomi.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** docs/spec/contacts.md, the list (CONT-R1 to R5, R11 to R16, R18, R20). */
class ContactsViewModel(private val container: AppContainer, private val host: ContactsHost) : ViewModel() {

    /** The vCard import's selection step (`openImportSelectionModal`). */
    data class ImportChoice(
        val named: List<VCard.Parsed>,
        val skipped: Int,
        val exists: Set<Int>,
        val selected: Set<Int>,
        val importing: Boolean = false,
        val nothingSelected: Boolean = false,
    )

    data class UiState(
        val contacts: List<Contact> = emptyList(),
        val categories: List<ContactCategory> = emptyList(),
        val colors: List<String> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val error: String? = null,
        val query: String = "",
        val category: String? = null,
        val selecting: Boolean = false,
        val selected: Set<Int> = emptySet(),
        val pending: Set<Int> = emptySet(),
        val import: ImportChoice? = null,
    ) {
        val offline: Boolean get() = cachedAt != null
        val shown: List<Contact> by lazy { contacts.filter { it.id !in pending } }
        val visible: List<Contact> by lazy { ContactsRules.filter(shown, category, query) }
        val chipKeys: List<String> by lazy { ContactsRules.filterCategoryKeys(shown, categories, category) }
        val groups: List<Pair<String, List<Contact>>> by lazy { ContactsRules.groups(visible, categories) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: ContactsApi? get() = container.session.connection?.client?.let(::ContactsApi)
    private val client: YuvomiClient? get() = container.session.connection?.client

    init {
        readCache()
        refresh()
        viewModelScope.launch { ContactsBus.version.drop(1).collect { refresh() } }
        viewModelScope.launch { ContactsBus.pending.collect { p -> _state.update { it.copy(pending = p) } } }
    }

    private fun readCache() {
        val list = container.cache.read(CACHE_LIST) ?: return
        runCatching {
            val contacts = ContactsApi.decodeContacts(ApiJson.parseToJsonElement(list.json))
            val categories = container.cache.read(CACHE_CATEGORIES)?.let { ContactsApi.decodeCategories(ApiJson.parseToJsonElement(it.json)) } ?: emptyList()
            val colors = container.cache.read(CACHE_META)?.let { ContactsApi.decodeColors(ApiJson.parseToJsonElement(it.json)) } ?: emptyList()
            _state.update { it.copy(contacts = ContactsRules.sorted(contacts, categories), categories = categories, colors = colors, loading = false) }
        }
    }

    /** CONT-R1: the list, the categories, their tones and the household region, together. */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        _state.update { it.copy(refreshing = pulled, error = null, loading = it.loading || it.error != null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val list = async { api.list() }
                    val cats = async { api.categories() }
                    val meta = async { runCatching { api.meta() }.getOrNull() }
                    val prefs = async { runCatching { client?.call(Operation.PREFERENCES) }.getOrNull() }
                    val listJson = list.await()
                    val catsJson = cats.await()
                    val categories = ContactsApi.decodeCategories(catsJson)
                    val contacts = ContactsRules.sorted(ContactsApi.decodeContacts(listJson), categories)
                    val metaJson = meta.await()
                    ((prefs.await() as? JsonObject)?.get("data") as? JsonObject)?.get("region")?.let { r ->
                        ContactsBus.country = ContactsBus.countryFromRegion((r as? JsonPrimitive)?.contentOrNull)
                    }
                    container.cache.write(CACHE_LIST, listJson.toString())
                    container.cache.write(CACHE_CATEGORIES, catsJson.toString())
                    metaJson?.let { container.cache.write(CACHE_META, it.toString()) }
                    _state.update { s ->
                        s.copy(
                            contacts = contacts,
                            categories = categories,
                            colors = metaJson?.let { ContactsApi.decodeColors(it) } ?: s.colors,
                            loading = false,
                            refreshing = false,
                            cachedAt = null,
                            // A filter on a category that is gone would hide everything (`openContactCategoryManager`).
                            category = s.category?.takeIf { key -> categories.any { it.key == key } },
                            selected = s.selected.filterTo(HashSet()) { id -> contacts.any { it.id == id } },
                        )
                    }
                }
            } catch (e: ApiProblem) {
                _state.update {
                    val hasData = !it.loading || it.contacts.isNotEmpty()
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_LIST)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        error = if (hasData) null else ContactsRules.loadErrorSentence(e, host.strings),
                    )
                }
                if (state.value.error == null && e !is ApiProblem.Network) host.notify(ContactsRules.loadErrorSentence(e, host.strings))
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setCategory(key: String?) = _state.update { it.copy(category = key) }

    /** "Reset search": term and category both go. */
    fun resetSearch() = _state.update { it.copy(query = "", category = null) }

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(host.strings.t("common.errorOfflineMutation"))
        return true
    }

    // ── CONT-R11: delete with undo ──

    fun delete(contact: Contact) {
        if (!host.canWrite || contact.isMember) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            ContactsBus.delete(container, host, listOf(contact.id), host.strings.t("contacts.deletedToast"))
        }
    }

    // ── CONT-R12: select mode ──

    fun startSelecting() {
        if (host.canWrite) _state.update { it.copy(selecting = true, selected = emptySet()) }
    }

    fun stopSelecting() = _state.update { it.copy(selecting = false, selected = emptySet()) }

    fun toggleSelected(contact: Contact) {
        if (contact.isMember) return
        _state.update { s -> s.copy(selected = if (contact.id in s.selected) s.selected - contact.id else s.selected + contact.id) }
    }

    /** `toggleSelectAll`: every visible contact that is not a member, or none when all are chosen. */
    fun toggleSelectAll() = _state.update { s ->
        val selectable = s.visible.filter { !it.isMember }.map { it.id }
        val allOn = selectable.isNotEmpty() && selectable.all { it in s.selected }
        s.copy(selected = if (allOn) s.selected - selectable.toSet() else s.selected + selectable)
    }

    fun deleteSelected() {
        val ids = state.value.selected.toList()
        if (ids.isEmpty() || !host.canWrite) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            stopSelecting()
            ContactsBus.delete(container, host, ids, host.strings.t("contacts.bulkDeletedToast", "count" to ids.size))
        }
    }

    // ── CONT-R14: vCard import ──

    /** The file's text was read: parse, then ask which cards to create. */
    fun importText(text: String?) {
        viewModelScope.launch {
            if (!host.canWrite || refuseOffline()) return@launch
            if (text == null) {
                host.notify(host.strings.t("contacts.importError", "error" to host.strings.t("common.errorGeneric")))
                return@launch
            }
            val parsed = VCard.parseAll(text)
            val named = parsed.filter { !it.name.isNullOrBlank() }
            if (named.isEmpty()) {
                host.notify(host.strings.t("contacts.vcardNoName"))
                return@launch
            }
            val shown = state.value.contacts
            val exists = named.indices.filterTo(HashSet()) { ContactsRules.existsByName(named[it], shown) }
            _state.update { it.copy(import = ImportChoice(named, parsed.size - named.size, exists, named.indices.toSet() - exists)) }
        }
    }

    fun toggleImport(index: Int) = _state.update { s ->
        val c = s.import ?: return@update s
        s.copy(import = c.copy(selected = if (index in c.selected) c.selected - index else c.selected + index, nothingSelected = false))
    }

    fun toggleImportAll() = _state.update { s ->
        val c = s.import ?: return@update s
        val all = c.selected.size == c.named.size
        s.copy(import = c.copy(selected = if (all) emptySet() else c.named.indices.toSet(), nothingSelected = false))
    }

    fun cancelImport() = _state.update { it.copy(import = null) }

    fun submitImport() {
        val choice = state.value.import ?: return
        if (choice.importing) return
        val chosen = choice.selected.sorted().map { choice.named[it] }
        if (chosen.isEmpty()) {
            _state.update { it.copy(import = choice.copy(nothingSelected = true)) }
            return
        }
        _state.update { it.copy(import = choice.copy(importing = true)) }
        viewModelScope.launch {
            importCards(chosen)
            _state.update { it.copy(import = null) }
        }
    }

    /** `importParsedContacts` and `showImportResult`. */
    private suspend fun importCards(cards: List<VCard.Parsed>) {
        val api = api ?: return
        val strings = host.strings
        val categories = state.value.categories
        var imported = 0
        var withBirthday = 0
        var lastName: String? = null
        var lastError: Throwable? = null
        val failed = mutableListOf<VCard.Parsed>()
        val created = mutableListOf<Contact>()
        for (card in cards) {
            try {
                val category = ContactsRules.resolveCategory(card.categories, categories, strings) ?: ContactsRules.FALLBACK_CATEGORY
                val contact = api.create(ContactsRules.importBody(card, category), YuvomiClient.newIdempotencyKey())
                created += contact
                imported++
                if (!contact.birthday.isNullOrBlank()) withBirthday++
                lastName = contact.name
            } catch (e: ApiProblem) {
                failed += card
                lastError = e
            }
        }
        if (created.isNotEmpty()) {
            _state.update { s -> s.copy(contacts = ContactsRules.sorted(s.contacts + created, s.categories)) }
            ContactsBus.changed()
        }
        val details = buildList {
            if (withBirthday > 0) add(strings.t("contacts.importDetailBirthday", "count" to withBirthday))
            if (failed.isNotEmpty()) add(strings.t("contacts.importDetailFailed", "count" to failed.size))
        }
        val message = when {
            imported == 0 -> strings.t("contacts.importError", "error" to ContactsRules.errorSentence(lastError ?: ApiProblem.Malformed("import"), strings))
            imported == 1 && details.isEmpty() -> strings.t("contacts.importedToast", "name" to lastName)
            else -> (listOf(if (imported == 1) strings.t("contacts.importedCountToastSingular", "count" to imported) else strings.t("contacts.importedCountToast", "count" to imported)) + details).joinToString(" · ")
        }
        val retry = failed.isNotEmpty()
        val openBirthdays = !retry && withBirthday > 0 && host.mayWriteBirthdays
        val action = when {
            retry -> strings.t("contacts.importRetry")
            openBirthdays -> strings.t("contacts.importOpenBirthdays")
            else -> null
        }
        container.scope.launch {
            if (host.notify(message, action)) {
                if (retry) importCards(failed) else if (openBirthdays) openBirthdaysRequest.value = true
            }
        }
    }

    /** CONT-R15 from the row menu. */
    suspend fun vcard(id: Int): ByteArray? {
        val api = api ?: return null
        return try {
            api.vcard(id)
        } catch (e: ApiProblem) {
            host.notify(ContactsRules.errorSentence(e, host.strings))
            null
        }
    }

    /** Set when the import's "Go to Birthdays" was tapped; the screen navigates and clears it. */
    val openBirthdaysRequest = MutableStateFlow(false)

    companion object {
        const val CACHE_LIST = "contacts-list"
        const val CACHE_CATEGORIES = "contacts-categories"
        const val CACHE_META = "contacts-meta"
    }
}
