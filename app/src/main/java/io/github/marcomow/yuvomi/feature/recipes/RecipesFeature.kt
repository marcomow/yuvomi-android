package io.github.marcomow.yuvomi.feature.recipes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Recipes (docs/spec/recipes.md). */
object RecipesFeature : Feature {
    override val id = "recipes"
    override val placement = Placement.MORE
    override val startRoute = RecipesRoutes.START
    override val routePrefixes = listOf("recipes")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(RecipesRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Recipes's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [RecipesFeature.register].
 */
object RecipesRoutes {
    const val START = "recipes"

    fun recipe(id: Int) = "recipes/$id"
}
