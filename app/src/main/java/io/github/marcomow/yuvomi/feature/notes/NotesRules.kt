package io.github.marcomow.yuvomi.feature.notes

import java.text.Normalizer
import java.util.Locale

/**
 * Who may do what to a note (NOTES-R27, R28), ported from upstream's
 * `public/utils/note-access.js` and the `mayChange`/`mayCheck` rules of
 * `public/pages/notes.js`.
 */
data class NoteViewer(
    val userId: Int?,
    val isAdmin: Boolean,
    /** `notes` access is `read`, or the board shows cached data offline. */
    val readOnly: Boolean,
    /** `notes_edit_others` (always for admins). */
    val canEditOthers: Boolean,
) {
    private fun own(note: Note) = note.createdBy != null && note.createdBy == userId

    /** `noteLockedFor`: a locked note is closed to everyone but its creator and admins, ticks included. */
    fun lockedFor(note: Note): Boolean = note.locked && !isAdmin && !own(note)

    /** Edit, pin, delete. Without `notes_edit_others` only one's own notes. */
    fun mayChange(note: Note): Boolean = !readOnly && !lockedFor(note) && (own(note) || canEditOthers)

    /** Ticking a checklist line is using the note, not rewriting it: open to every writer unless locked. */
    fun mayCheck(note: Note): Boolean = !readOnly && !lockedFor(note)

    /** Visibility is the creator's alone (a new note counts as the viewer's own). */
    fun mayChangeVisibility(note: Note?): Boolean = note == null || own(note)

    /** The lock: creator and admins. */
    fun mayChangeLock(note: Note?): Boolean = note == null || isAdmin || own(note)
}

/** The board's search and filter row (NOTES-R3 to R6). */
data class BoardFilter(
    val query: String = "",
    val creator: String = "",
    val categoryIds: List<Int> = emptyList(),
    /** NOTES-R31: the archived notes (`#archived`) instead of the others. */
    val archived: Boolean = false,
) {
    val isFiltered: Boolean get() = query.trim().isNotEmpty() || creator.isNotEmpty() || categoryIds.isNotEmpty() || archived
}

/** One person in the creator filter: the name is the filter value, as upstream filters by `creator_name`. */
data class Creator(val name: String, val color: String?, val avatar: String?)

object NotesRules {

    /**
     * `visibleNotes()`: creator, then every selected category (AND), then title or text containing
     * the query. Archived notes (`#archived`, NOTES-R31) show only in the Archived filter, and
     * only they do there.
     */
    fun visible(notes: List<Note>, filter: BoardFilter): List<Note> {
        val q = filter.query.trim().lowercase()
        return notes.filter { n ->
            if (isArchived(n) != filter.archived) return@filter false
            if (filter.creator.isNotEmpty() && n.creatorName != filter.creator) return@filter false
            if (!matchesCategories(n, filter.categoryIds)) return@filter false
            q.isEmpty() || n.title.orEmpty().lowercase().contains(q) || n.content.lowercase().contains(q)
        }
    }

    /** NOTES-R31: archived through the shared tag `#archived` in its title or text. */
    fun isArchived(note: Note): Boolean = io.github.marcomow.yuvomi.feature.tags.Archived.inText(note.title, note.content)

    fun matchesCategories(note: Note, selected: List<Int>): Boolean {
        if (selected.isEmpty()) return true
        val assigned = note.categories.map { it.id }.toSet()
        return selected.all { it in assigned }
    }

    /** The creator chips: one per name, in order of first appearance; shown from two creators on. */
    fun creators(notes: List<Note>): List<Creator> {
        val byName = LinkedHashMap<String, Note>()
        notes.filter { !it.creatorName.isNullOrEmpty() }.forEach { byName[it.creatorName!!] = it }
        return byName.values.map { Creator(it.creatorName!!, it.creatorColor, it.creatorAvatar) }
    }

    /** Category chips: those some note carries, plus those still selected. */
    fun filterCategories(notes: List<Note>, categories: List<NoteCategory>, selected: List<Int>): List<NoteCategory> {
        val occupied = notes.flatMap { n -> n.categories.map { it.id } }.toSet()
        return categories.filter { it.id in occupied || it.id in selected }
    }

    fun pruneMissing(selected: List<Int>, categories: List<NoteCategory>): List<Int> {
        val ids = categories.map { it.id }.toSet()
        return selected.filter { it in ids }
    }

    /** Pinned first, otherwise the order stays (`sort((a, b) => b.pinned - a.pinned)` is stable). */
    fun sortPinned(notes: List<Note>): List<Note> = notes.sortedByDescending { it.pinned }

    /** Two headed groups only when both exist (`drawGrid`). */
    data class Groups(val pinned: List<Note>, val others: List<Note>) {
        val headed: Boolean get() = pinned.isNotEmpty() && others.isNotEmpty()
    }

    fun groups(visible: List<Note>): Groups = Groups(visible.filter { it.pinned }, visible.filterNot { it.pinned })

    private val NAME_PREFIX = Regex("""^\s*(?:#{1,6}\s+|[-*+]\s+(?:\[[ xX]]\s+)?|\d+[.)]\s+|>\s*)""")

    /** `noteName()`: the title, else the first text line without Markdown marks (40 characters), else null. */
    fun name(note: Note): String? {
        note.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val line = note.content.split('\n')
            .map { it.replace(NAME_PREFIX, "").replace(Regex("[*_`~]"), "").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length > 40) line.take(40).trimEnd() + "…" else line
    }

    /** `removeNoteCategoryFromState`: a deleted category leaves every note and the filter at once. */
    fun withoutCategory(notes: List<Note>, categoryId: Int): List<Note> = notes.map { n ->
        val kept = n.categories.filter { it.id != categoryId }
        if (kept.size == n.categories.size) n else n.copy(categories = kept)
    }

    // ── Category picker (public/utils/note-category-picker.js) ──

    const val CATEGORY_NAME_MAX_LENGTH = 80

    /** `categoryNameKey`: the identity the server enforces as unique. */
    fun categoryNameKey(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKC)
        s = s.uppercase(Locale.ROOT).lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
    }

    private fun identityKey(value: String) = categoryNameKey(value.trim())

    private fun searchKey(value: String) =
        Normalizer.normalize(identityKey(value), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    fun suggestions(categories: List<NoteCategory>, selected: Collection<Int>, query: String, limit: Int = 8): List<NoteCategory> {
        val needle = searchKey(query)
        return categories.filter { it.id !in selected }
            .filter { needle.isEmpty() || searchKey(it.name).contains(needle) }
            .take(limit.coerceAtLeast(0))
    }

    fun exact(categories: List<NoteCategory>, query: String, scope: String? = null): NoteCategory? {
        val needle = identityKey(query)
        if (needle.isEmpty()) return null
        return categories.firstOrNull { (scope == null || it.scope == scope) && identityKey(it.name) == needle }
    }

    /** `categoryCreationState`: may the typed name be created in [scope]? */
    fun canCreate(categories: List<NoteCategory>, query: String, scope: String): Boolean =
        query.trim().isNotEmpty() && exact(categories, query, scope) == null
}
