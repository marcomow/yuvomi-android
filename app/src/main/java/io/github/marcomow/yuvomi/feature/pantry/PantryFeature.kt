package io.github.marcomow.yuvomi.feature.pantry

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Pantry (docs/spec/pantry.md). */
object PantryFeature : Feature {
    override val id = "pantry"
    override val placement = Placement.MORE
    override val startRoute = PantryRoutes.START
    override val routePrefixes = listOf("pantry")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(PantryRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Pantry's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [PantryFeature.register].
 */
object PantryRoutes {
    const val START = "pantry"
}
