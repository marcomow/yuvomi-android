package io.github.marcomow.yuvomi.feature.inventory

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Inventory (docs/spec/inventory.md). */
object InventoryFeature : Feature {
    override val id = "inventory"
    override val placement = Placement.MORE
    override val startRoute = InventoryRoutes.START
    override val routePrefixes = listOf("inventory")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(InventoryRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Inventory's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [InventoryFeature.register].
 */
object InventoryRoutes {
    const val START = "inventory"
}
