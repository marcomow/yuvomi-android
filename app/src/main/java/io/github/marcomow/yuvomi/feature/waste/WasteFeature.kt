package io.github.marcomow.yuvomi.feature.waste

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Waste (docs/spec/waste.md). */
object WasteFeature : Feature {
    override val id = "waste"
    override val placement = Placement.MORE
    override val startRoute = WasteRoutes.START
    override val routePrefixes = listOf("waste")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(WasteRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Waste's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [WasteFeature.register].
 */
object WasteRoutes {
    const val START = "waste"
}
