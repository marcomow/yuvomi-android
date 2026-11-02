package io.github.marcomow.yuvomi.feature.documents

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Documents (docs/spec/documents.md). */
object DocumentsFeature : Feature {
    override val id = "documents"
    override val placement = Placement.MORE
    override val startRoute = DocumentsRoutes.START
    override val routePrefixes = listOf("documents")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(DocumentsRoutes.START) { FeaturePlaceholder(id) }
        // Placeholder for the detail route; the module replaces both.
        graph.composable("documents?open={open}") { FeaturePlaceholder(id) }
    }
}

/**
 * Documents's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [DocumentsFeature.register].
 */
object DocumentsRoutes {
    const val START = "documents"

    /** A document's detail (the web app's `/documents?open=<id>`). */
    fun document(id: Int) = "documents?open=$id"
}
