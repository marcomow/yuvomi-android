package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.feature.notes.Note
import io.github.marcomow.yuvomi.feature.notes.NotesApi
import io.github.marcomow.yuvomi.feature.notes.NotesRoutes
import io.github.marcomow.yuvomi.feature.notes.NotesRules
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/** Notes' part of the Tags view (TAGS-R2): hashtags in a note's title and text. */
object NotesTagSource : TagSource {
    override val moduleId = "notes"

    /** A heading mark or a list / checklist bullet at the start of a line. */
    private val MARKUP = Regex("""^(#{1,6}\s+|[-*+]\s+(\[[ xX]]\s+)?)""")

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        return items(NotesApi.decodeNotes(NotesApi(shell.connection.client).list()), SourceText.of(shell))
    }

    fun items(notes: List<Note>, text: SourceText): List<TaggedItem> = notes.mapNotNull { note ->
        val tags = Hashtags.extract(note.title, note.content)
        if (tags.isEmpty()) return@mapNotNull null
        val title = note.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: note.content.lineSequence().map { it.trim().replace(MARKUP, "").trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
            ?: text.label("notes.viewNote")
        // An archived note (NOTES-R31) is [TaggedItem.done] (TAGS-R14).
        val archived = NotesRules.archiveCategory(note.categories, setOf(text.label("android.notes.archiveCategory"), "Archive")) != null
        TaggedItem(moduleId, "note:${note.id}", title, note.creatorName, tags, NotesRoutes.note(note.id), done = archived)
    }
}
