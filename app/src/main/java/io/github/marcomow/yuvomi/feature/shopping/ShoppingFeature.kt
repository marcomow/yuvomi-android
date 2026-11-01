package io.github.marcomow.yuvomi.feature.shopping

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Shopping (docs/spec/shopping.md). */
object ShoppingFeature : Feature {
    override val id = "shopping"
    override val placement = Placement.BAR
    override val startRoute = ShoppingRoutes.START
    override val routePrefixes = listOf("shopping")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(ShoppingRoutes.START) { FeaturePlaceholder(id) }
    }
}

/** Shopping's routes. Other modules link here through these helpers only. */
object ShoppingRoutes {
    const val START = "shopping"
}
