package io.github.marcomow.yuvomi.feature.health

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Health (docs/spec/health.md). */
object HealthFeature : Feature {
    override val id = "health"
    override val placement = Placement.MORE
    override val startRoute = HealthRoutes.START
    override val routePrefixes = listOf("health")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(HealthRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Health's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [HealthFeature.register].
 */
object HealthRoutes {
    const val START = "health"
}
