package io.github.marcomow.yuvomi.feature.notes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Notes (docs/spec/notes.md). */
object NotesFeature : Feature {
    override val id = "notes"
    override val placement = Placement.MORE
    override val startRoute = NotesRoutes.START
    override val routePrefixes = listOf("notes")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(NotesRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Notes's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [NotesFeature.register].
 */
object NotesRoutes {
    const val START = "notes"

    fun note(id: Int) = "notes/$id"
}
