package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** docs/spec/notes.md, the editor (NOTES-R17 to R22). */
class NoteEditorViewModel(private val store: NotesStore, val noteId: Int?) : ViewModel() {

    enum class Outcome { CREATED, SAVED, GONE }

    data class UiState(
        val ready: Boolean = false,
        val note: Note? = null,
        val form: NoteForm = NoteForm(),
        val original: NoteForm = NoteForm(),
        val content: TextFieldValue = TextFieldValue(""),
        val categoryQuery: String = "",
        val creationScope: String = NoteCategory.SCOPE_PERSONAL,
        val contentMissing: Boolean = false,
        val categoryNameMissing: Boolean = false,
        val saving: Boolean = false,
        val creatingCategory: Boolean = false,
        /** A message to show once (an i18n key). */
        val messageKey: String? = null,
        val outcome: Outcome? = null,
    ) {
        val dirty: Boolean get() = form != original
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One key per editor: a retried create is the same create (ADR 0007). */
    private val idempotencyKey = YuvomiClient.newIdempotencyKey()

    init {
        if (noteId == null) {
            _state.update { it.copy(ready = true) }
        } else {
            viewModelScope.launch {
                if (store.state.value.note(noteId) == null) store.load()
                val note = store.state.value.note(noteId)
                if (note == null) {
                    _state.update { it.copy(ready = true, outcome = Outcome.GONE, messageKey = "notes.errorNoteGone") }
                } else {
                    val form = NoteForm.of(note)
                    _state.update {
                        it.copy(ready = true, note = note, form = form, original = form, content = TextFieldValue(form.content, TextRange(form.content.length)))
                    }
                }
            }
        }
    }

    fun edit(change: (NoteForm) -> NoteForm) = _state.update { it.copy(form = change(it.form)) }

    fun setContent(value: TextFieldValue) =
        _state.update { it.copy(content = value, form = it.form.copy(content = value.text), contentMissing = it.contentMissing && value.text.isBlank()) }

    /** NOTES-R17: the formatting toolbar, on the current selection. */
    fun format(format: MarkdownFormat.Format, words: MarkdownFormat.Placeholders) = _state.update { s ->
        val v = s.content
        val result = MarkdownFormat.apply(MarkdownFormat.Edit(v.text, v.selection.start, v.selection.end), format, words)
        val next = TextFieldValue(result.text, TextRange(result.start, result.end))
        s.copy(content = next, form = s.form.copy(content = next.text))
    }

    fun messageShown() = _state.update { it.copy(messageKey = null) }

    /**
     * A tick in the editor's read tab (NOTES-R17). Only offered while the text is the saved one,
     * so the line numbers are the server's (upstream #704); the fresh text replaces both.
     */
    suspend fun check(line: Int, checked: Boolean): String? {
        val id = noteId ?: return null
        val error = store.check(id, line, checked)
        val fresh = store.state.value.note(id) ?: return error
        _state.update { s ->
            if (s.form.content != s.original.content) {
                s
            } else {
                s.copy(
                    note = fresh,
                    form = s.form.copy(content = fresh.content),
                    original = s.original.copy(content = fresh.content),
                    content = TextFieldValue(fresh.content, TextRange(fresh.content.length)),
                )
            }
        }
        return error
    }

    // ── Categories ──

    fun setCategoryQuery(query: String) = _state.update { it.copy(categoryQuery = query.take(NotesRules.CATEGORY_NAME_MAX_LENGTH), categoryNameMissing = false) }

    fun setCreationScope(scope: String) = _state.update { it.copy(creationScope = scope) }

    fun selectCategory(category: NoteCategory) = _state.update { s ->
        if (category.id in s.form.categoryIds) s.copy(categoryQuery = "")
        else s.copy(form = s.form.copy(categoryIds = s.form.categoryIds + category.id), categoryQuery = "")
    }

    fun removeCategory(id: Int) = _state.update { it.copy(form = it.form.copy(categoryIds = it.form.categoryIds - id)) }

    /**
     * "Create and assign": an existing category of that name and scope is chosen instead of a
     * duplicate. Only personal categories, unless the member may manage the household's.
     */
    suspend fun createCategory(canManageHousehold: Boolean): String? {
        val s = state.value
        if (s.creatingCategory || s.saving) return null
        val name = s.categoryQuery.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(categoryNameMissing = true) }
            return null
        }
        val scope = if (canManageHousehold) s.creationScope else NoteCategory.SCOPE_PERSONAL
        NotesRules.exact(store.state.value.categories, name, scope)?.let {
            selectCategory(it)
            return null
        }
        _state.update { it.copy(creatingCategory = true) }
        return try {
            val created = store.createCategory(name, scope)
            selectCategory(created)
            null
        } catch (e: ApiProblem) {
            NoteErrors.key(e)
        } finally {
            _state.update { it.copy(creatingCategory = false) }
        }
    }

    // ── Save ──

    /** NOTES-R19, R20. The text is required; failures keep the editor open, except a note that is gone. */
    fun save(sendVisibility: Boolean, sendLock: Boolean) {
        val s = state.value
        if (s.saving || s.creatingCategory || !s.ready) return
        if (s.form.content.isBlank()) {
            _state.update { it.copy(contentMissing = true) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                store.save(s.note, NoteBodies.body(s.form, sendVisibility, sendLock), idempotencyKey)
                _state.update { it.copy(saving = false, original = it.form, outcome = if (s.note == null) Outcome.CREATED else Outcome.SAVED) }
            } catch (e: ApiProblem) {
                val key = NoteErrors.key(e)
                if (s.note != null && NoteErrors.isGone(e)) {
                    _state.update { it.copy(saving = false, messageKey = key, outcome = Outcome.GONE) }
                    return@launch
                }
                if (NoteErrors.isGone(e, NoteErrors.SELECTED_CATEGORY_GONE)) {
                    store.refreshCategories()
                    val known = store.state.value.categories.map { it.id }.toSet()
                    _state.update { it.copy(form = it.form.copy(categoryIds = it.form.categoryIds.filter { id -> id in known })) }
                }
                _state.update { it.copy(saving = false, messageKey = key) }
            }
        }
    }
}
