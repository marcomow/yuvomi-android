package io.github.marcomow.yuvomi.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What the documents screens need from the shell: who is signed in and what they may do,
 * snackbars, the household's today and the strings. An interface so the view model is tested
 * without a shell.
 */
interface DocumentsHost {
    val strings: Strings
    val userId: Int
    val isAdmin: Boolean

    /** `mayWritePath('/documents')`: the module's write right. */
    val canWrite: Boolean

    fun today(): LocalDate

    /**
     * DOCS-R35: the server reads `subject_user_id` (2.84.0 on). An older one would drop the
     * choice without a word, so the dialog does not offer it there.
     */
    val knowsSubjects: Boolean get() = true

    /** Shows [message]; true when the member tapped [action]. */
    suspend fun notify(message: String, action: String? = null): Boolean
}

/** docs/spec/documents.md: the page's module-level `state` and every action on it. */
class DocumentsViewModel(
    private val container: AppContainer,
    private val host: DocumentsHost,
    openId: Int? = null,
) : ViewModel() {

    data class UiState(
        /** `state.allDocuments`: everything of the current status, before facets. */
        val documents: List<Document> = emptyList(),
        val folders: List<DocumentFolder> = emptyList(),
        val meta: DocumentMeta = DocumentMeta(),
        val members: List<DocumentPerson> = emptyList(),
        val directory: List<DocumentPerson> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val listLoading: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val filter: DocumentRules.Filter = DocumentRules.Filter(),
        val sort: String = "updated",
        val direction: String = "desc",
        val grid: Boolean = false,
        val expanded: Set<Int> = emptySet(),
        /** Documents in their undo window. */
        val pendingDeletes: Set<Int> = emptySet(),
        /** Folder subtrees whose delete-with-documents is in its undo window. */
        val pendingFolders: Set<Int> = emptySet(),
        val selectMode: Boolean = false,
        val selected: Set<Int> = emptySet(),
        /** The document open in the viewer. */
        val viewing: Int? = null,
        /** `otherStatusHits`: hits of the current search in the other view, for exactly this status and term. */
        val otherHits: Triple<String, String, Int>? = null,
        /** A folder whose deletion is being asked about. */
        val folderDelete: Pair<DocumentFolder, FolderDeleteImpact>? = null,
        val today: LocalDate = LocalDate.now(),
    ) {
        val offline: Boolean get() = cachedAt != null

        /** Documents without those in an undo window or in a folder being deleted. */
        val shown: List<Document> by lazy {
            documents.filter { it.id !in pendingDeletes && (it.folderId == null || it.folderId !in pendingFolders) }
        }
        val shownFolders: List<DocumentFolder> by lazy { folders.filter { it.id !in pendingFolders } }

        val visible: List<Document> by lazy {
            DocumentRules.visible(shown, shownFolders, filter, sort, direction, today, java.util.Locale.getDefault())
        }

        fun document(id: Int): Document? = documents.firstOrNull { it.id == id }

        val otherStatusHits: Int get() = otherHits?.takeIf { it.first == filter.status && it.second == filter.normalizedQuery }?.third ?: 0
    }

    private val _state = MutableStateFlow(UiState(today = host.today()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: DocumentsApi? get() = container.session.connection?.client?.let(::DocumentsApi)
    var lastLoaded = 0L
        private set
    private var pendingOpen: Int? = openId
    private var loadSeq = 0
    private val strings get() = host.strings

    init {
        readCache(DocumentRules.STATUS_ACTIVE)
        refresh()
    }

    // ── Permissions ──

    /** Write affordances need the module's write right and a connection (ADR 0007). */
    val readOnly: Boolean get() = !host.canWrite || state.value.offline

    /** `mayManage`: write right, and the document is the member's own or the member is an admin. */
    fun mayManage(doc: Document): Boolean =
        DocumentRules.mayManage(doc, !readOnly, host.userId, host.isAdmin || state.value.meta.isAdmin)

    val isAdmin: Boolean get() = host.isAdmin || state.value.meta.isAdmin

    // ── Loading ──

    private fun cacheKey(status: String) = "documents-$status"

    private fun readCache(status: String) {
        runCatching {
            container.cache.read(FOLDERS_CACHE)?.let { f ->
                _state.update { it.copy(folders = DocumentsApi.decodeFolders(ApiJson.parseToJsonElement(f.json))) }
            }
            container.cache.read(cacheKey(status))?.let { d ->
                _state.update { it.copy(documents = DocumentsApi.decodeDocuments(d.json), loading = false) }
            }
        }
    }

    /** `render`: members, folders and options in parallel, then the documents of the status. */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val seq = ++loadSeq
        _state.update { it.copy(refreshing = pulled, errorKey = null, loading = it.loading || it.errorKey != null, today = host.today()) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val people = async { runCatching { DocumentsApi.decodePeople(api.members()) to DocumentsApi.decodePeople(api.directory()) }.getOrNull() }
                    val meta = async { runCatching { DocumentsApi.decodeMeta(api.meta()) }.getOrNull() }
                    val foldersJson = async { api.folders() }
                    val docsJson = api.list(state.value.filter.status)
                    val folders = foldersJson.await()
                    if (seq != loadSeq) return@coroutineScope
                    val docs = DocumentsApi.decodeDocuments(docsJson)
                    val folderList = DocumentsApi.decodeFolders(folders)
                    container.cache.write(cacheKey(state.value.filter.status), docsJson.toString())
                    container.cache.write(FOLDERS_CACHE, folders.toString())
                    lastLoaded = System.currentTimeMillis()
                    val (members, directory) = people.await() ?: (state.value.members to state.value.directory)
                    _state.update { s ->
                        s.copy(
                            documents = docs,
                            folders = folderList,
                            meta = meta.await() ?: DocumentMeta(),
                            members = members,
                            directory = directory,
                            loading = false,
                            refreshing = false,
                            listLoading = false,
                            cachedAt = null,
                            errorKey = null,
                            filter = pruneFolder(s.filter, folderList),
                            selected = s.selected.filter { id -> docs.any { it.id == id } }.toSet(),
                        )
                    }
                    openPending()
                }
            } catch (e: ApiProblem) {
                _state.update {
                    val hasData = it.documents.isNotEmpty() || container.cache.read(cacheKey(it.filter.status)) != null
                    it.copy(
                        loading = false,
                        refreshing = false,
                        listLoading = false,
                        cachedAt = if (hasData) container.cache.read(cacheKey(it.filter.status))?.savedAt ?: System.currentTimeMillis() else null,
                        errorKey = if (hasData) null else loadErrorKey(e),
                        selectMode = false,
                        selected = emptySet(),
                    )
                }
                if (state.value.offline) openPending()
            }
        }
    }

    /** A folder that is gone is no longer the filter. */
    private fun pruneFolder(filter: DocumentRules.Filter, folders: List<DocumentFolder>): DocumentRules.Filter {
        val id = filter.selectedFolder ?: return filter
        return if (folders.any { it.id == id }) filter else filter.copy(folderId = DocumentRules.FOLDER_ALL)
    }

    private fun loadErrorKey(e: ApiProblem): String = when {
        e is ApiProblem.Network -> "android.error.offline"
        e is ApiProblem.Http && e.operationUnsupported -> "android.error.unsupported"
        e is ApiProblem.Http && e.status == 403 -> DocumentErrors.message(e).key
        else -> "android.documents.loadError"
    }

    /** `loadDocuments` alone, after a write. */
    private suspend fun reloadDocuments(): Boolean {
        val api = api ?: return false
        return try {
            val status = state.value.filter.status
            val json = api.list(status)
            val docs = DocumentsApi.decodeDocuments(json)
            container.cache.write(cacheKey(status), json.toString())
            lastLoaded = System.currentTimeMillis()
            _state.update { s -> if (s.filter.status != status) s else s.copy(documents = docs, listLoading = false, cachedAt = null) }
            true
        } catch (e: ApiProblem) {
            false
        }
    }

    private suspend fun reloadFolders(): Boolean {
        val api = api ?: return false
        return try {
            val json = api.folders()
            val folders = DocumentsApi.decodeFolders(json)
            container.cache.write(FOLDERS_CACHE, json.toString())
            _state.update { s -> s.copy(folders = folders, filter = pruneFolder(s.filter, folders)) }
            true
        } catch (e: ApiProblem) {
            false
        }
    }

    private suspend fun reloadAll() {
        coroutineScope {
            val f = async { reloadFolders() }
            reloadDocuments()
            f.await()
        }
    }

    /**
     * `reportError`: the sentence, and when the target is gone the page reloads what is
     * there instead of guessing.
     */
    suspend fun report(e: Throwable, fallback: String = DocumentErrors.FALLBACK) {
        val message = DocumentErrors.message(e, fallback)
        if (DocumentErrors.isGone(e)) {
            viewModelScope.launch {
                reloadAll()
                api?.let { a -> runCatching { DocumentsApi.decodeMeta(a.meta()) }.getOrNull()?.let { m -> _state.update { it.copy(meta = m) } } }
            }
        }
        host.notify(strings.t(message.key, message.args))
    }

    private fun say(message: Message) = viewModelScope.launch { host.notify(strings.t(message.key, message.args)) }

    private fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        say(Message("common.errorOfflineMutation"))
        return true
    }

    // ── Filters ──

    fun setQuery(query: String) = _state.update { it.copy(filter = it.filter.copy(query = query)) }

    fun clearSearch() = setQuery("")

    fun setCategory(category: String) = _state.update { it.copy(filter = it.filter.copy(category = category)) }

    fun toggleExpiring() = _state.update { it.copy(filter = it.filter.copy(expiringSoon = !it.filter.expiringSoon)) }

    /** DOCS-R36: the person filter; null shows everyone's documents. */
    fun setPerson(id: Int?) = _state.update { it.copy(filter = it.filter.copy(personId = id)) }

    fun resetFilters() = _state.update {
        it.copy(filter = it.filter.copy(category = "", folderId = DocumentRules.FOLDER_ALL, expiringSoon = false, personId = null))
    }

    /** `selectFolder`: choosing a folder with children opens its branch. */
    fun selectFolder(id: String) = _state.update { s ->
        val chosen = id.toIntOrNull()
        val expand = chosen != null && s.folders.any { it.parentId == chosen }
        s.copy(filter = s.filter.copy(folderId = id), expanded = if (expand) s.expanded + chosen else s.expanded)
    }

    fun toggleExpanded(id: Int) = _state.update { s -> s.copy(expanded = if (id in s.expanded) s.expanded - id else s.expanded + id) }

    /** `selectStatus`: the server filters the status, so the list loads again. */
    fun setStatus(status: String) {
        if (state.value.filter.status == status) return
        _state.update { it.copy(filter = it.filter.copy(status = status), selectMode = false, selected = emptySet(), listLoading = true, documents = emptyList()) }
        readCache(status)
        viewModelScope.launch {
            if (!reloadDocuments()) {
                _state.update { s ->
                    val cached = container.cache.read(cacheKey(status))
                    s.copy(listLoading = false, cachedAt = cached?.savedAt ?: s.cachedAt)
                }
            }
        }
    }

    /** `setSort`: a new key starts in its natural direction. */
    fun setSort(sort: String, direction: String? = null) = _state.update {
        val key = sort.takeIf { s -> s in DocumentRules.SORTS } ?: "updated"
        it.copy(sort = key, direction = direction?.takeIf { d -> d == "asc" || d == "desc" } ?: DocumentRules.defaultDirection(key))
    }

    fun setGrid(grid: Boolean) = _state.update { it.copy(grid = grid) }

    /**
     * `probeOtherStatusSearch`: an empty search asks the other view once per term and status,
     * counting only the term.
     */
    fun probeOtherStatus() {
        val s = state.value
        val query = s.filter.normalizedQuery
        if (query.isEmpty() || s.offline) return
        if (s.otherHits?.first == s.filter.status && s.otherHits.second == query) return
        val api = api ?: return
        val status = s.filter.status
        viewModelScope.launch {
            val docs = runCatching { DocumentsApi.decodeDocuments(api.list(DocumentRules.otherStatus(status))) }.getOrNull() ?: return@launch
            val now = state.value
            if (now.filter.status != status || now.filter.normalizedQuery != query) return@launch
            _state.update { it.copy(otherHits = Triple(status, query, docs.count { d -> DocumentRules.matchesQuery(d, query) })) }
        }
    }

    // ── Viewer ──

    fun view(id: Int?) = _state.update { it.copy(viewing = id) }

    /** `viewerNeighbours`: the neighbours in the visible list, without wrapping round. */
    fun neighbours(id: Int): Pair<Document?, Document?> {
        val docs = state.value.visible
        val index = docs.indexOfFirst { it.id == id }
        if (index < 0) return null to null
        return docs.getOrNull(index - 1) to docs.getOrNull(index + 1)
    }

    /**
     * A link from another module (`documents?open=<id>`, Tasks): the viewer opens once the list
     * is there; a document in the archive switches to that view; one the member cannot see says so.
     */
    private fun openPending() {
        val id = pendingOpen ?: return
        pendingOpen = null
        if (state.value.document(id) != null) {
            view(id)
            return
        }
        val api = api
        if (api == null || state.value.offline) {
            say(Message("documents.errorDocumentGone"))
            return
        }
        viewModelScope.launch {
            val other = DocumentRules.otherStatus(state.value.filter.status)
            val found = runCatching { DocumentsApi.decodeDocuments(api.list(other)) }.getOrNull()?.firstOrNull { it.id == id }
            if (found == null) {
                host.notify(strings.t("documents.errorDocumentGone"))
                return@launch
            }
            _state.update { it.copy(filter = it.filter.copy(status = other), selectMode = false, selected = emptySet(), listLoading = true, documents = emptyList()) }
            if (reloadDocuments() && state.value.document(id) != null) view(id)
        }
    }

    // ── Archive, move, delete (runDocumentAction, archiveSelected, moveSelected, deleteDocuments) ──

    fun archive(doc: Document) {
        if (!mayManage(doc) || refuseOffline()) return
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.archive(doc.id, !doc.archived)
                host.notify(strings.t(if (doc.archived) "documents.restoredToast" else "documents.archivedToast"))
                reloadDocuments()
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    fun archiveSelected() {
        val docs = selectedDocuments()
        if (docs.isEmpty() || refuseOffline()) return
        val api = api ?: return
        val archived = state.value.filter.status == DocumentRules.STATUS_ARCHIVED
        exitSelect()
        viewModelScope.launch {
            try {
                coroutineScope { docs.map { d -> async { api.archive(d.id, !archived) } }.forEach { it.await() } }
                host.notify(strings.t(if (archived) "documents.bulkRestoredToast" else "documents.bulkArchivedToast", "count" to docs.size))
                reloadDocuments()
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    /** Moves [docs] (one from a row menu, or the selection) into [folderId], null for "no folder". */
    fun move(docs: List<Document>, folderId: Int?) {
        val manageable = docs.filter(::mayManage)
        if (manageable.isEmpty() || refuseOffline()) return
        val api = api ?: return
        exitSelect()
        viewModelScope.launch {
            try {
                coroutineScope { manageable.map { d -> async { api.update(d.id, DocumentBodies.move(d, folderId)) } }.forEach { it.await() } }
                host.notify(strings.t("documents.bulkMovedToast", "count" to manageable.size))
                reloadDocuments()
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    /**
     * `deleteDocuments`: the documents leave at once and come back on undo; after the window
     * every delete runs to its end, the first failure is reported. A gone document is not put
     * back (its 404 may also mean "no longer visible"), the list reloads instead.
     */
    fun delete(docs: List<Document>) {
        val manageable = docs.filter(::mayManage)
        if (manageable.isEmpty() || refuseOffline()) return
        val api = api ?: return
        val ids = manageable.map { it.id }.toSet()
        exitSelect()
        _state.update { it.copy(pendingDeletes = it.pendingDeletes + ids, viewing = it.viewing?.takeIf { v -> v !in ids }) }
        container.scope.launch {
            val message = if (manageable.size == 1) strings.t("documents.deletedToast") else strings.t("documents.bulkDeletedToast", "count" to manageable.size)
            if (host.notify(message, strings.t("common.undo"))) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - ids) }
                return@launch
            }
            val failures = coroutineScope {
                manageable.map { d -> async { runCatching { api.delete(d.id) }.exceptionOrNull() } }.mapNotNull { it.await() }
            }
            val failure = failures.firstOrNull()
            if (failure == null || DocumentErrors.isGone(failure)) {
                _state.update { s -> s.copy(documents = s.documents.filter { it.id !in ids }, pendingDeletes = s.pendingDeletes - ids) }
                if (failure != null) report(failure) else reloadDocuments()
                return@launch
            }
            _state.update { it.copy(pendingDeletes = it.pendingDeletes - ids) }
            report(failure)
        }
    }

    // ── Selection ──

    fun enterSelect() {
        if (readOnly) return
        _state.update { it.copy(selectMode = true, selected = emptySet()) }
    }

    fun exitSelect() = _state.update { it.copy(selectMode = false, selected = emptySet()) }

    fun toggleSelected(doc: Document) {
        if (!mayManage(doc)) return
        _state.update { s -> s.copy(selected = if (doc.id in s.selected) s.selected - doc.id else s.selected + doc.id) }
    }

    /** `toggleSelectAll`: all manageable visible documents, or none when all are chosen. */
    fun toggleSelectAll() = _state.update { s ->
        val visible = s.visible.filter(::mayManage).map { it.id }.toSet()
        val allOn = visible.isNotEmpty() && s.selected.containsAll(visible)
        s.copy(selected = if (allOn) s.selected - visible else s.selected + visible)
    }

    fun selectedDocuments(): List<Document> = state.value.shown.filter { it.id in state.value.selected && mayManage(it) }

    // ── Upload and edit (saveDocument) ──

    sealed interface SaveResult {
        data object Saved : SaveResult

        /** The dialog stays open with [message]; [folderGone] resets its folder to "no folder". */
        data class Problem(val message: Message, val folderGone: Boolean = false) : SaveResult

        /** The document is gone: the dialog closes and the list reloads. */
        data object Gone : SaveResult
    }

    /**
     * Saves the dialog. A new upload sends one POST per file with its own idempotency key
     * ([keys], kept by the dialog across retries); [progress] reports `current of total`.
     */
    suspend fun save(
        existing: Document?,
        form: DocumentForm,
        files: List<PickedFile>,
        keys: List<String>,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): SaveResult {
        if (existing != null && !mayManage(existing)) return SaveResult.Problem(Message("common.errorNoPermission"))
        if (existing == null && readOnly) return SaveResult.Problem(Message("common.errorNoPermission"))
        if (state.value.offline) return SaveResult.Problem(Message("common.errorOfflineMutation"))
        DocumentBodies.reminderProblem(form)?.let { return SaveResult.Problem(Message(it)) }
        val api = api ?: return SaveResult.Problem(Message(DocumentErrors.FALLBACK))
        try {
            if (existing != null) {
                if (form.name.isBlank()) return SaveResult.Problem(Message("common.required"))
                api.update(existing.id, DocumentBodies.payload(form))
                host.notify(strings.t("documents.savedToast"))
            } else {
                if (files.isEmpty()) return SaveResult.Problem(Message("documents.fileRequired"))
                val max = state.value.meta.maxFileSize?.takeIf { it > 0 } ?: DocumentRules.DEFAULT_MAX_UPLOAD_BYTES
                files.firstOrNull { it.size > max }?.let { return SaveResult.Problem(Message("documents.fileTooLargeNamed", mapOf("name" to it.fileName))) }
                files.forEachIndexed { index, file ->
                    progress(index + 1, files.size)
                    api.create(DocumentBodies.upload(form, file, files.size), keys.getOrElse(index) { YuvomiClient.newIdempotencyKey() })
                }
                host.notify(strings.t(if (files.size > 1) "documents.bulkUploadedToast" else "documents.uploadedToast", "count" to files.size))
            }
            viewModelScope.launch { reloadDocuments() }
            return SaveResult.Saved
        } catch (e: ApiProblem) {
            if (DocumentErrors.isGone(e) && !DocumentErrors.isSelectedFolderGone(e)) {
                viewModelScope.launch { report(e) }
                return SaveResult.Gone
            }
            if (DocumentErrors.isSelectedFolderGone(e)) {
                viewModelScope.launch { reloadAll() }
                return SaveResult.Problem(DocumentErrors.message(e), folderGone = true)
            }
            return SaveResult.Problem(DocumentErrors.message(e))
        }
    }

    /** `withChosenPeople`: household members, plus whoever already has access (from the directory). */
    fun pickerPeople(selected: Set<Int>): List<DocumentPerson> {
        val s = state.value
        val seen = s.members.map { it.id }.toMutableSet()
        val granted = selected.mapNotNull { id -> s.directory.firstOrNull { it.id == id } }.filter { seen.add(it.id) }
        return s.members + granted
    }

    /** DOCS-R35: the "About" choices, `withChosenPeople(state.members, [documentSubject(doc)])`. */
    val offersSubject: Boolean get() = host.knowsSubjects

    fun subjectPeople(doc: Document?): List<DocumentPerson> {
        val members = state.value.members
        val chosen = doc?.let { DocumentRules.subject(it, members) } ?: return members
        return if (members.any { it.id == chosen.id }) members else members + chosen
    }

    /** DOCS-R35: `syncSubjectAccess`, the name for `documents.subjectNoAccess`, or null when the person may open it. */
    fun subjectNoAccessName(doc: Document?, form: DocumentForm): String? {
        val person = form.subjectUserId ?: return null
        val creator = doc?.createdBy ?: host.userId
        if (DocumentRules.subjectMayOpen(person, creator, form.visibility, form.allowedMemberIds)) return null
        return subjectPeople(doc).firstOrNull { it.id == person }?.displayName.orEmpty()
    }

    fun personName(id: Int): String? = state.value.let { s -> (s.directory + s.members).firstOrNull { it.id == id }?.displayName }

    // ── Folders ──

    /** `openFolderModal`'s save: an error message for the dialog, or null when created. */
    suspend fun createFolder(name: String, parentId: Int?, idempotencyKey: String): SaveResult {
        if (readOnly) return SaveResult.Problem(Message("common.errorNoPermission"))
        if (name.isBlank()) return SaveResult.Problem(Message("common.required"))
        val api = api ?: return SaveResult.Problem(Message(DocumentErrors.FALLBACK))
        return try {
            val created = api.createFolder(name, parentId, idempotencyKey)
            _state.update { s ->
                s.copy(
                    expanded = if (parentId != null) s.expanded + parentId else s.expanded,
                    filter = s.filter.copy(folderId = created.id.toString()),
                    folders = (s.folders + created).distinctBy { it.id },
                )
            }
            host.notify(strings.t("documents.folderCreatedToast"))
            viewModelScope.launch { reloadAll() }
            SaveResult.Saved
        } catch (e: ApiProblem) {
            if (DocumentErrors.isSelectedFolderGone(e)) {
                reloadAll()
                SaveResult.Problem(DocumentErrors.message(e), folderGone = true)
            } else {
                SaveResult.Problem(DocumentErrors.message(e))
            }
        }
    }

    fun renameFolder(folder: DocumentFolder, name: String) {
        if (readOnly || name.isBlank() || name.trim() == folder.name) return
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.updateFolder(folder.id, DocumentBodies.rename(name))
                host.notify(strings.t("documents.folderRenamedToast"))
                // `folder_name` comes from the server's join: the rows need it fresh.
                reloadAll()
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    /** `moveFolder`: the new parent opens, or the folder would seem to vanish. */
    fun moveFolder(folder: DocumentFolder, parentId: Int?) {
        if (readOnly) return
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.updateFolder(folder.id, DocumentBodies.moveFolder(parentId))
                host.notify(strings.t("documents.folderMovedToast"))
                if (parentId != null) _state.update { it.copy(expanded = it.expanded + parentId) }
                reloadAll()
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    /** `deleteFolder`: the impact first; the question comes from it. */
    fun askDeleteFolder(folder: DocumentFolder) {
        if (readOnly) return
        val api = api ?: return
        viewModelScope.launch {
            try {
                val impact = api.deleteImpact(folder.id)
                _state.update { it.copy(folderDelete = folder to impact) }
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    fun cancelFolderDelete() = _state.update { it.copy(folderDelete = null) }

    fun deleteFolder(choice: FolderDeleteChoice) {
        val (folder, impact) = state.value.folderDelete ?: return
        _state.update { it.copy(folderDelete = null) }
        val api = api ?: return
        val subtree = DocumentRules.subtreeIds(state.value.folders, folder.id)
        if (choice == FolderDeleteChoice.UNFILE) {
            viewModelScope.launch {
                try {
                    val result = api.deleteFolder(folder.id, impact, choice)
                    applyFolderResult(result, choice, subtree, showSuccess = true)
                } catch (e: ApiProblem) {
                    folderDeleteFailure(e, folder, delayed = false)
                }
            }
            return
        }
        // `scheduleFolderDeleteWithUndo`: the branch and its documents leave at once.
        _state.update { it.copy(pendingFolders = it.pendingFolders + subtree, filter = if (it.filter.selectedFolder in subtree) it.filter.copy(folderId = "") else it.filter) }
        container.scope.launch {
            val undone = host.notify(strings.t("documents.folderDeletedWithDocumentsToast", "count" to impact.documents), strings.t("common.undo"))
            if (undone) {
                _state.update { it.copy(pendingFolders = it.pendingFolders - subtree) }
                return@launch
            }
            try {
                val result = api.deleteFolder(folder.id, impact, choice)
                applyFolderResult(result, choice, subtree, showSuccess = false)
            } catch (e: ApiProblem) {
                folderDeleteFailure(e, folder, delayed = true)
            } finally {
                _state.update { it.copy(pendingFolders = it.pendingFolders - subtree) }
            }
        }
    }

    private suspend fun applyFolderResult(result: FolderDeleteResult, choice: FolderDeleteChoice, subtree: Set<Int>, showSuccess: Boolean) {
        FolderDeleteOutcome.message(result, choice, showSuccess)?.let { m -> say(m) }
        if (result.folderDeleted) {
            _state.update { s -> if (s.filter.selectedFolder in subtree) s.copy(filter = s.filter.copy(folderId = "")) else s }
        }
        reloadAll()
    }

    /** `handleFolderDeleteFailure`. */
    private suspend fun folderDeleteFailure(e: ApiProblem, folder: DocumentFolder, delayed: Boolean) {
        when {
            DocumentErrors.isReason(e, 409, DocumentErrors.FOLDER_CONTENT_CHANGED) ->
                if (delayed) host.notify(strings.t("documents.folderDeleteContentsChangedBeforeCommitToast")) else askDeleteFolder(folder)
            DocumentErrors.isReason(e, 409, DocumentErrors.FOLDER_DELETE_IN_PROGRESS) -> host.notify(strings.t("documents.folderDeleteInProgressToast"))
            DocumentErrors.isReason(e, 403, DocumentErrors.FOLDER_DOCUMENTS_NOT_MANAGEABLE) -> host.notify(strings.t("documents.folderDeleteNotManageableToast"))
            else -> report(e)
        }
        reloadAll()
    }

    // ── DMS (admin) ──

    suspend fun dmsSearch(accountId: Int, query: String): List<DmsItem>? = runCatching { api?.dmsSearch(accountId, query.trim()) }.getOrNull()

    /** `linkDmsDocument`: true when linked (the sheet closes). */
    suspend fun dmsLink(accountId: Int, item: DmsItem, idempotencyKey: String): Boolean {
        val api = api ?: return false
        return try {
            api.dmsLink(accountId, item, idempotencyKey)
            reloadDocuments()
            true
        } catch (e: ApiProblem) {
            report(e)
            false
        }
    }

    fun pushToDms(doc: Document, accountId: Int) {
        if (!mayManage(doc) || refuseOffline()) return
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.dmsPush(accountId, doc.id, YuvomiClient.newIdempotencyKey())
                host.notify(strings.t("documents.pushToDmsQueued"))
            } catch (e: ApiProblem) {
                report(e)
            }
        }
    }

    /** The bytes behind the viewer, Save and Open with. */
    suspend fun bytes(doc: Document, preview: Boolean): ByteArray {
        val api = api ?: throw ApiProblem.SessionExpired()
        return if (preview) api.preview(doc.id) else api.download(doc.id)
    }

    suspend fun thumbnail(doc: Document): ByteArray? = runCatching {
        val api = api ?: return null
        if (DocumentRules.hasDmsThumbnail(doc)) api.thumbnail(doc.id) else api.preview(doc.id)
    }.getOrNull()

    companion object {
        const val FOLDERS_CACHE = "documents-folders"
    }
}
