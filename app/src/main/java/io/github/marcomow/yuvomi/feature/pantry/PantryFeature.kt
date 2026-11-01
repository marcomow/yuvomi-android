package io.github.marcomow.yuvomi.feature.pantry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import java.time.LocalDate

/** Pantry (docs/spec/pantry.md). */
object PantryFeature : Feature {
    override val id = "pantry"
    override val placement = Placement.MORE
    override val startRoute = PantryRoutes.START
    override val routePrefixes = listOf("pantry")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers both `pantry` (START) and `pantry?filter=soon` (PANTRY-R8).
        graph.composable(
            PantryRoutes.LIST,
            arguments = listOf(navArgument("filter") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            PantryScreen(
                vm = pantryViewModel(entry, entry.arguments?.getString("filter")),
                openItem = { id -> nav.navigate(PantryRoutes.item(id)) },
                openLocations = { nav.navigate(PantryRoutes.LOCATIONS) },
            )
        }
        graph.composable(
            PantryRoutes.ITEM,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val list = remember(entry) { nav.previousBackStackEntry?.takeIf { it.destination.route == PantryRoutes.LIST } }
                ?: return@composable
            PantryEditorScreen(
                list = pantryViewModel(list, null),
                itemId = entry.arguments?.getString("id")?.toIntOrNull(),
                close = { nav.popBackStack() },
            )
        }
        graph.composable(PantryRoutes.LOCATIONS) { entry ->
            val list = remember(entry) { nav.previousBackStackEntry?.takeIf { it.destination.route == PantryRoutes.LIST } }
            val listVm = list?.let { pantryViewModel(it, null) }
            PantryLocationsScreen(
                // `onChanged` of the manager: the stock reloads after every change (PANTRY-R20).
                changed = { listVm?.refresh() },
                close = { nav.popBackStack() },
            )
        }
    }
}

/** The list's view model, owned by the list's back stack entry and shared with the editor. */
@Composable
internal fun pantryViewModel(owner: NavBackStackEntry, filter: String?): PantryViewModel {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellPantryHost(shell, strings) }
    return viewModel(viewModelStoreOwner = owner, key = "pantry") { PantryViewModel(shell.container, host, filter) }
}

/** [PantryHost] on the signed-in shell. */
internal class ShellPantryHost(private val shell: ShellContext, override val strings: Strings) : PantryHost {
    override fun today(): LocalDate = shell.time.today()

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)

    /** `missingShoppingListAnswer`: only when Shopping is on and the member may create lists there. */
    override val openShopping: (() -> Unit)?
        get() = if (shell.shows("shopping") && shell.session.access("shopping") == "write") ({ shell.navigate(ShoppingRoutes.START) }) else null
}

/**
 * Pantry's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [PantryFeature.register].
 */
object PantryRoutes {
    const val START = "pantry"
    internal const val LIST = "pantry?filter={filter}"
    internal const val ITEM = "pantry/item?id={id}"
    internal const val LOCATIONS = "pantry/locations"

    /** The list with a chip already chosen: `expired`, `soon` or `low` (PANTRY-R8). */
    fun filtered(filter: String) = "pantry?filter=$filter"

    internal fun item(id: Int?) = "pantry/item" + (id?.let { "?id=$it" } ?: "")
}
