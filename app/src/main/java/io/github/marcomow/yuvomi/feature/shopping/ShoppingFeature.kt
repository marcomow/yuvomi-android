package io.github.marcomow.yuvomi.feature.shopping

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Shopping (docs/spec/shopping.md). */
object ShoppingFeature : Feature {
    override val id = "shopping"
    override val placement = Placement.BAR
    override val startRoute = ShoppingRoutes.START
    override val routePrefixes = listOf("shopping")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination for START, list(id) and item(list, id): both arguments are optional.
        graph.composable(
            ShoppingRoutes.PATTERN,
            arguments = listOf(
                navArgument("list") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("highlight") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ShoppingScreen(
                requestedList = entry.arguments?.getString("list")?.toIntOrNull(),
                highlight = entry.arguments?.getString("highlight")?.toIntOrNull(),
            )
        }
    }
}

/**
 * Shopping's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [ShoppingFeature.register].
 */
object ShoppingRoutes {
    const val START = "shopping"
    internal const val PATTERN = "shopping?list={list}&highlight={highlight}"

    /** A list, or the last one opened when [id] is null. */
    fun list(id: Int? = null) = "shopping" + (id?.let { "?list=$it" } ?: "")

    /** An item on a list, scrolled to and unfolded (the web app's `?highlight=<id>`). */
    fun item(listId: Int, itemId: Int) = "shopping?list=$listId&highlight=$itemId"
}
