package io.github.marcomow.yuvomi.feature.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.marcomow.yuvomi.core.design.Tokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The note colours of the web app (`NOTE_COLORS` in `public/pages/notes.js`) and their names. */
object NoteColors {
    val palette: List<Pair<String, String>> = listOf(
        "#EFE3BE" to "notes.colorYellow",
        "#E7D2A9" to "notes.colorAmber",
        "#D2DEC6" to "notes.colorGreen",
        "#C7DED9" to "notes.colorTeal",
        "#CAD8E4" to "notes.colorBlue",
        "#D8D0E2" to "notes.colorPurple",
        "#EBD1C2" to "notes.colorOrange",
        "#FBFAF7" to "notes.colorWhite",
    )
    val default: String = palette.first().first

    fun isPalette(color: String?): Boolean = palette.any { it.first.equals(color, ignoreCase = true) }

    /** The card's ground: the note colour at 16 % over the surface, as upstream's `--tint-surface`. */
    fun tint(color: String?, surface: Color): Color =
        Tokens.parseColor(color)?.let { lerp(surface, it.copy(alpha = 1f), 0.16f) } ?: surface
}

/** What the editor holds (NOTES-R17, R18), and the body it sends. */
data class NoteForm(
    val title: String = "",
    val content: String = "",
    val categoryIds: List<Int> = emptyList(),
    val color: String = NoteColors.default,
    val pinned: Boolean = false,
    val visibility: String = Note.VISIBILITY_ALL,
    val locked: Boolean = false,
) {
    /** The "More details" section opens by itself when one of its fields is not at its default. */
    val detailsOpen: Boolean
        get() = pinned || !color.equals(NoteColors.default, ignoreCase = true) || visibility == Note.VISIBILITY_PRIVATE || locked

    companion object {
        fun of(note: Note?): NoteForm = if (note == null) NoteForm() else NoteForm(
            title = note.title.orEmpty(),
            content = note.content,
            categoryIds = note.categories.map { it.id },
            color = note.color?.takeIf { it.isNotBlank() } ?: NoteColors.default,
            pinned = note.pinned,
            visibility = if (note.isPrivate) Note.VISIBILITY_PRIVATE else Note.VISIBILITY_ALL,
            locked = note.locked,
        )
    }
}

object NoteBodies {
    /**
     * The save body, as the web editor sends it for create and edit alike: title (blank is
     * null), trimmed content, colour, pin as 1/0, every chosen category, and visibility and lock
     * only when this member may set them; a field that is not sent keeps its stored value.
     */
    fun body(form: NoteForm, sendVisibility: Boolean, sendLock: Boolean): JsonObject = buildJsonObject {
        val title = form.title.trim()
        put("title", if (title.isEmpty()) JsonNull else JsonPrimitive(title))
        put("content", form.content.trim())
        put("color", form.color)
        put("pinned", if (form.pinned) 1 else 0)
        put("category_ids", JsonArray(form.categoryIds.map { JsonPrimitive(it) }))
        if (sendVisibility) put("visibility", if (form.visibility == Note.VISIBILITY_PRIVATE) Note.VISIBILITY_PRIVATE else Note.VISIBILITY_ALL)
        if (sendLock) put("locked", if (form.locked) 1 else 0)
    }
}
