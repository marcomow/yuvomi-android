package io.github.marcomow.yuvomi.feature.meals

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Meals (docs/spec/meals.md). */
object MealsFeature : Feature {
    override val id = "meals"
    override val placement = Placement.BAR
    override val startRoute = MealsRoutes.START
    override val routePrefixes = listOf("meals")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(MealsRoutes.START) { FeaturePlaceholder(id) }
    }
}

/** Meals's routes. Other modules link here through these helpers only. */
object MealsRoutes {
    const val START = "meals"
}
