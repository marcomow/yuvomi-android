package io.github.marcomow.yuvomi.feature.notes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Notes (docs/spec/notes.md). */
object NotesFeature : Feature {
    override val id = "notes"
    override val placement = Placement.MORE
    override val startRoute = NotesRoutes.START
    override val routePrefixes = listOf("notes")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(NotesRoutes.START) {
            NotesBoardScreen(
                openNote = { nav.navigate(NotesRoutes.note(it)) },
                newNote = { nav.navigate(NotesRoutes.editor()) },
                manageCategories = { nav.navigate(NotesRoutes.CATEGORIES) },
            )
        }
        graph.composable(NotesRoutes.NOTE, arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            NoteScreen(id, back = { nav.popBackStack() }, edit = { nav.navigate(NotesRoutes.editor(it)) })
        }
        graph.composable(
            NotesRoutes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.IntType; defaultValue = -1 }),
        ) { entry ->
            val id = entry.arguments?.getInt("id")?.takeIf { it > 0 }
            NoteEditorScreen(
                noteId = id,
                close = { nav.popBackStack() },
                deleted = {
                    // The note's read view goes too: there is nothing left to read.
                    nav.popBackStack()
                    if (id != null && nav.currentBackStackEntry?.destination?.route == NotesRoutes.NOTE) nav.popBackStack()
                },
            )
        }
        graph.composable(NotesRoutes.CATEGORIES) { NoteCategoriesScreen(back = { nav.popBackStack() }) }
    }
}

/**
 * Notes's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [NotesFeature.register].
 */
object NotesRoutes {
    const val START = "notes"
    const val NOTE = "notes/{id}"
    const val EDITOR = "notes-editor?id={id}"
    const val CATEGORIES = "notes-categories"

    /** One note, read view first (the web's `/notes?open=<id>`). */
    fun note(id: Int) = "notes/$id"

    fun editor(id: Int? = null) = if (id == null) "notes-editor" else "notes-editor?id=$id"
}
