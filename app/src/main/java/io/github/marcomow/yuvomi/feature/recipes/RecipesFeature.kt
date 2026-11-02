package io.github.marcomow.yuvomi.feature.recipes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Recipes (docs/spec/recipes.md). */
object RecipesFeature : Feature {
    override val id = "recipes"
    override val placement = Placement.MORE
    override val startRoute = RecipesRoutes.START
    override val routePrefixes = listOf("recipes")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(RecipesRoutes.START) { entry ->
            RecipesScreen(
                vm = recipesViewModel(nav, entry),
                open = { nav.navigate(RecipesRoutes.recipe(it)) },
                edit = { nav.navigate(RecipesRoutes.editor(it)) },
            )
        }
        graph.composable(RecipesRoutes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id")?.toIntOrNull() ?: return@composable
            RecipeDetailScreen(
                vm = recipesViewModel(nav, entry),
                recipeId = id,
                back = { nav.popBackStack() },
                edit = { nav.navigate(RecipesRoutes.editor(it)) },
            )
        }
        graph.composable(
            RecipesRoutes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            RecipeEditorScreen(
                shared = recipesViewModel(nav, entry),
                recipeId = entry.arguments?.getString("id")?.toIntOrNull(),
                close = { nav.popBackStack() },
                deleted = {
                    nav.popBackStack()
                    if (nav.currentDestination?.route == RecipesRoutes.DETAIL) nav.popBackStack()
                },
            )
        }
    }
}

/**
 * The module's one state holder, scoped to the navigation graph: list, detail and editor
 * see the same recipes (they are one page in the web app), and a new sign-in starts afresh.
 */
@Composable
internal fun recipesViewModel(nav: NavHostController, entry: NavBackStackEntry): RecipesViewModel {
    val shell = LocalShell.current
    val owner = remember(entry) { nav.getBackStackEntry(nav.graph.id) }
    return viewModel(viewModelStoreOwner = owner, key = "recipes-${shell.session.server}-${shell.session.user.id}") {
        RecipesViewModel(shell.container, shell.session.preferences.disabledModules.toSet())
    }
}

/**
 * Recipes's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [RecipesFeature.register].
 */
object RecipesRoutes {
    const val START = "recipes"
    const val DETAIL = "recipes/{id}"
    const val EDITOR = "recipes-edit?id={id}"

    /** A recipe's detail (upstream's `/recipes?open=<id>`). */
    fun recipe(id: Int) = "recipes/$id"

    /** The form: a new recipe when [id] is null. */
    fun editor(id: Int? = null) = "recipes-edit" + (id?.let { "?id=$it" } ?: "")
}
