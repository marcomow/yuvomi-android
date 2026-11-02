package io.github.marcomow.yuvomi.feature.housekeeping

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Housekeeping (docs/spec/housekeeping.md). */
object HousekeepingFeature : Feature {
    override val id = "housekeeping"
    override val placement = Placement.MORE
    override val startRoute = HousekeepingRoutes.START
    override val routePrefixes = listOf("housekeeping")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(HousekeepingRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Housekeeping's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [HousekeepingFeature.register].
 */
object HousekeepingRoutes {
    const val START = "housekeeping"
}
