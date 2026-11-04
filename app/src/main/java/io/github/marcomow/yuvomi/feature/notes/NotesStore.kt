package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * The board's data, shared by the board, a note's read view, the editor and the category
 * manager, like the web page's module-level `state` (docs/spec/notes.md). One store per
 * signed-in connection. Reads are cache-then-network (ADR 0007); writes go to the server and
 * update the shared state from its answer.
 */
class NotesStore(private val cache: ResponseCache, private val api: NotesApi, private val scope: CoroutineScope) {

    data class State(
        val notes: List<Note> = emptyList(),
        val categories: List<NoteCategory> = emptyList(),
        val canManageHousehold: Boolean = false,
        val loading: Boolean = true,
        /** True once a server answer (not only the cache) arrived. */
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        /** When the shown data came from the cache because the server could not be reached. */
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val filter: BoardFilter = BoardFilter(),
        /** Notes deleted on screen whose undo window is still open (NOTES-R13). */
        val pendingDeletes: Set<Int> = emptySet(),
    ) {
        /** The board without notes waiting for their delete. */
        val shown: List<Note> get() = notes.filter { it.id !in pendingDeletes }
        val offline: Boolean get() = cachedAt != null
        fun note(id: Int): Note? = shown.firstOrNull { it.id == id }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var lastLoaded = 0L
        private set

    init {
        readCache()
    }

    private fun readCache() {
        runCatching {
            val notes = cache.read(CACHE_NOTES)
            val categories = cache.read(CACHE_CATEGORIES)
            if (categories != null) {
                val list = NotesApi.decodeCategories(ApiJson.parseToJsonElement(categories.json))
                _state.update { it.copy(categories = list.categories, canManageHousehold = list.canManageHousehold) }
            }
            if (notes != null) {
                _state.update { it.copy(notes = NotesApi.decodeNotes(ApiJson.parseToJsonElement(notes.json)), loading = false, cachedAt = notes.savedAt) }
            }
        }
    }

    /** NOTES-R2: the notes and the categories in parallel. */
    fun refresh(pulled: Boolean = false) {
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        scope.launch { load() }
    }

    /** Loads both lists; returns false when the server could not answer. */
    suspend fun load(): Boolean = try {
        val (notesJson, categoriesJson) = kotlinx.coroutines.coroutineScope {
            val categories = async { api.categories() }
            api.list() to categories.await()
        }
        val notes = NotesApi.decodeNotes(notesJson)
        val categories = NotesApi.decodeCategories(categoriesJson)
        cache.write(CACHE_NOTES, notesJson.toString())
        cache.write(CACHE_CATEGORIES, categoriesJson.toString())
        lastLoaded = System.currentTimeMillis()
        _state.update {
            it.copy(
                notes = notes,
                categories = categories.categories,
                canManageHousehold = categories.canManageHousehold,
                filter = it.filter.copy(categoryIds = NotesRules.pruneMissing(it.filter.categoryIds, categories.categories)),
                loading = false,
                loaded = true,
                refreshing = false,
                cachedAt = null,
                errorKey = null,
            )
        }
        true
    } catch (e: ApiProblem) {
        _state.update {
            val hasData = it.notes.isNotEmpty() || it.cachedAt != null
            it.copy(
                loading = false,
                refreshing = false,
                cachedAt = it.cachedAt ?: if (hasData) System.currentTimeMillis() else null,
                errorKey = if (hasData) null else loadErrorKey(e),
            )
        }
        false
    }

    private fun loadErrorKey(e: ApiProblem): String = when {
        e is ApiProblem.Network -> "android.error.offline"
        e is ApiProblem.Http && e.operationUnsupported -> "android.error.unsupported"
        e is ApiProblem.Http && e.status == 403 -> NoteErrors.key(e)
        else -> "notes.loadError"
    }

    fun setFilter(filter: BoardFilter) = _state.update { it.copy(filter = filter) }

    private fun replace(note: Note) = _state.update { s -> s.copy(notes = s.notes.map { if (it.id == note.id) note else it }) }

    /** `dropGoneNote`: someone else deleted it, or made it private; it leaves the board without a reload. */
    private fun drop(id: Int) = _state.update { s -> s.copy(notes = s.notes.filter { it.id != id }) }

    // ── Pin (NOTES-R12) ──

    /** Returns an error key, or null on success. */
    suspend fun togglePin(note: Note): String? = try {
        val pinned = api.pin(note.id)
        _state.update { s -> s.copy(notes = NotesRules.sortPinned(s.notes.map { if (it.id == note.id) it.copy(pinned = pinned) else it })) }
        null
    } catch (e: ApiProblem) {
        if (NoteErrors.isGone(e)) drop(note.id)
        NoteErrors.key(e)
    }

    // ── Checklist (NOTES-R15, R16) ──

    /**
     * Ticks line [line] of note [noteId] at once and confirms it with `/check`. A `409`, or a
     * line the local copy no longer has, reloads the board and says so (`notes.checkConflict`);
     * any other failure rolls the tick back. Returns an error key, or null on success.
     */
    suspend fun check(noteId: Int, line: Int, checked: Boolean): String? {
        val note = state.value.note(noteId) ?: return null
        val expect = NoteMarkdown.expectAt(note.content, line)
        if (expect == null) {
            load()
            return "notes.checkConflict"
        }
        NoteMarkdown.toggle(note.content, line, checked, expect)?.let { replace(note.copy(content = it)) }
        return try {
            val fresh = api.check(noteId, line, checked, expect)
            _state.update { s ->
                s.copy(notes = s.notes.map { if (it.id == noteId) it.copy(content = fresh.content, updatedAt = fresh.updatedAt) else it })
            }
            null
        } catch (e: ApiProblem) {
            replace(note)
            when {
                NoteErrors.isCheckConflict(e) -> {
                    load()
                    "notes.checkConflict"
                }
                else -> {
                    if (NoteErrors.isGone(e)) drop(noteId)
                    NoteErrors.key(e)
                }
            }
        }
    }

    // ── Create and edit (NOTES-R17 to R19) ──

    /** Saves and puts the answer on the board: a new note first, an edited one in place. Throws [ApiProblem]. */
    suspend fun save(existing: Note?, body: JsonObject, idempotencyKey: String): Note {
        return try {
            if (existing == null) {
                val created = api.create(body, idempotencyKey)
                _state.update { it.copy(notes = listOf(created) + it.notes.filter { n -> n.id != created.id }) }
                created
            } else {
                val updated = api.update(existing.id, body)
                _state.update { s -> s.copy(notes = NotesRules.sortPinned(s.notes.map { if (it.id == updated.id) updated else it })) }
                updated
            }
        } catch (e: ApiProblem) {
            if (existing != null && NoteErrors.isGone(e)) drop(existing.id)
            throw e
        }
    }

    // ── Delete with undo (NOTES-R13) ──

    /**
     * `deleteNote`: the note leaves the board at once, a snackbar offers undo, and only when it
     * closes without undo is the note deleted on the server. "Already deleted" counts as done;
     * any other failure brings the note back and says why. Runs in the app's scope, so leaving
     * the screen does not cancel it.
     */
    fun scheduleDelete(note: Note, strings: Strings, notify: suspend (message: String, action: String?) -> Boolean) {
        _state.update { it.copy(pendingDeletes = it.pendingDeletes + note.id) }
        scope.launch {
            val name = note.title?.trim().orEmpty()
            val message = if (name.isNotEmpty()) strings.t("common.deletedNamed", "name" to name) else strings.t("notes.deletedToast")
            val undone = notify(message, strings.t("common.undo"))
            if (undone) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - note.id) }
                return@launch
            }
            try {
                api.delete(note.id)
            } catch (e: ApiProblem) {
                if (!NoteErrors.isGone(e)) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - note.id) }
                    notify(strings.t(NoteErrors.key(e)), null)
                    return@launch
                }
            }
            _state.update { s -> s.copy(notes = s.notes.filter { it.id != note.id }, pendingDeletes = s.pendingDeletes - note.id) }
        }
    }

    // ── Categories (NOTES-R22 to R26) ──

    /** `offerCurrentCategories`: fresh categories; returns false when they could not be read. */
    suspend fun refreshCategories(): Boolean = try {
        val list = NotesApi.decodeCategories(api.categories())
        _state.update {
            it.copy(
                categories = list.categories,
                canManageHousehold = list.canManageHousehold,
                filter = it.filter.copy(categoryIds = NotesRules.pruneMissing(it.filter.categoryIds, list.categories)),
            )
        }
        true
    } catch (e: ApiProblem) {
        false
    }

    /**
     * NOTES-R31: archiving adds `#archived` on a line of its own at the end of the text, restoring
     * takes every `#archived` out of the title and text. The text is a shared edit, so it needs
     * the right to change the note. Returns an error key, or null.
     */
    suspend fun setArchived(note: Note, archived: Boolean): String? = try {
        val tag = io.github.marcomow.yuvomi.feature.tags.Archived
        val title = if (archived) note.title else note.title?.let { tag.text(it, false) }
        val updated = api.update(note.id, kotlinx.serialization.json.buildJsonObject {
            put("content", tag.text(note.content, archived))
            if (title != note.title) put("title", title)
        })
        replace(updated)
        null
    } catch (e: ApiProblem) {
        if (NoteErrors.isGone(e)) drop(note.id)
        NoteErrors.key(e)
    }

    /** Creates a category and adds it to the shared list. Throws [ApiProblem]. */
    suspend fun createCategory(name: String, scopeKey: String, idempotencyKey: String = YuvomiClient.newIdempotencyKey()): NoteCategory {
        val created = api.createCategory(name.trim(), scopeKey, idempotencyKey)
        _state.update { s -> if (s.categories.any { it.id == created.id }) s else s.copy(categories = s.categories + created) }
        return created
    }

    /** Returns an error key, or null; a gone category reloads the list. */
    suspend fun renameCategory(category: NoteCategory, name: String): String? = categoryWrite {
        val renamed = api.renameCategory(category.id, name.trim())
        _state.update { s -> s.copy(categories = s.categories.map { if (it.id == renamed.id) renamed else it }) }
    }

    /** Moves [category] one place up (-1) or down (+1) within its scope. */
    suspend fun moveCategory(category: NoteCategory, delta: Int): String? {
        val group = state.value.categories.filter { it.scope == category.scope }.toMutableList()
        val from = group.indexOfFirst { it.id == category.id }
        val to = from + delta
        if (from < 0 || to !in group.indices) return null
        group[from] = group[to].also { group[to] = group[from] }
        return categoryWrite {
            val list = api.reorderCategories(group.map { it.id }, category.scope)
            _state.update { it.copy(categories = list.categories, canManageHousehold = list.canManageHousehold) }
        }
    }

    /** Deletes [category]; it leaves every note and the filter at once. "Already gone" is a success. */
    suspend fun deleteCategory(category: NoteCategory): String? = categoryWrite {
        try {
            api.deleteCategory(category.id)
        } catch (e: ApiProblem.Http) {
            if (e.status != 404) throw e
        }
        _state.update { s ->
            s.copy(
                categories = s.categories.filter { it.id != category.id },
                notes = NotesRules.withoutCategory(s.notes, category.id),
                filter = s.filter.copy(categoryIds = s.filter.categoryIds - category.id),
            )
        }
    }

    /** A category change, then the web manager's `refresh`: categories and notes from the server. */
    private suspend fun categoryWrite(block: suspend () -> Unit): String? = try {
        block()
        load()
        null
    } catch (e: ApiProblem) {
        if (e is ApiProblem.Http && e.status == 404) load()
        NoteErrors.key(e)
    }

    companion object {
        const val CACHE_NOTES = "notes-list"
        const val CACHE_CATEGORIES = "notes-categories"

        private val stores = WeakHashMap<Connection, NotesStore>()

        /** The store of the signed-in connection, or null when signed out. */
        fun of(container: AppContainer): NotesStore? {
            val connection = container.session.connection ?: return null
            return synchronized(stores) {
                stores.getOrPut(connection) { NotesStore(container.cache, NotesApi(connection.client), container.scope) }
            }
        }
    }
}
