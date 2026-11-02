package io.github.marcomow.yuvomi.feature.inventory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Inventory (docs/spec/inventory.md). */
object InventoryFeature : Feature {
    override val id = "inventory"
    override val placement = Placement.MORE
    override val startRoute = InventoryRoutes.START
    override val routePrefixes = listOf("inventory")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers `inventory` and `inventory?category=vehicles` (`?category=` of the web app).
        graph.composable(
            InventoryRoutes.LIST,
            arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val host = rememberHost()
            val shell = LocalShell.current
            val category = entry.arguments?.getString("category")
            InventoryScreen(
                vm = viewModel(key = "inventory") { InventoryViewModel(shell.container, host, category) },
                host = host,
                openItem = { nav.navigate(InventoryRoutes.item(it)) },
                newItem = { nav.navigate(InventoryRoutes.editor(null)) },
                manage = { locations -> nav.navigate(if (locations) InventoryRoutes.LOCATIONS else InventoryRoutes.CATEGORIES) },
            )
        }
        graph.composable(InventoryRoutes.ITEM, arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val host = rememberHost()
            val shell = LocalShell.current
            ItemDetailScreen(
                vm = viewModel(key = "inventory-item-$id") { ItemDetailViewModel(shell.container, host, id) },
                host = host,
                back = { nav.popBackStack() },
                edit = { nav.navigate(InventoryRoutes.editor(it)) },
            )
        }
        graph.composable(InventoryRoutes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            val id = entry.arguments?.getInt("id")?.takeIf { it > 0 }
            val host = rememberHost()
            val shell = LocalShell.current
            ItemEditorScreen(
                vm = viewModel(key = "inventory-editor-${id ?: "new"}") { ItemEditorViewModel(shell.container, host, id) },
                host = host,
                close = { nav.popBackStack() },
            )
        }
        graph.composable(InventoryRoutes.CATEGORIES) {
            val host = rememberHost()
            val shell = LocalShell.current
            InventoryManagerScreen(viewModel(key = "inventory-categories") { InventoryManagerViewModel(shell.container, host, locations = false) }, back = { nav.popBackStack() })
        }
        graph.composable(InventoryRoutes.LOCATIONS) {
            val host = rememberHost()
            val shell = LocalShell.current
            InventoryManagerScreen(viewModel(key = "inventory-locations") { InventoryManagerViewModel(shell.container, host, locations = true) }, back = { nav.popBackStack() })
        }
    }
}

@Composable
private fun rememberHost(): InventoryHost {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    return remember(shell, strings) { ShellInventoryHost(shell, strings) }
}

/**
 * Inventory's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [InventoryFeature.register].
 */
object InventoryRoutes {
    const val START = "inventory"
    internal const val LIST = "inventory?category={category}"
    internal const val ITEM = "inventory/{id}"
    internal const val EDITOR = "inventory-editor?id={id}"
    internal const val CATEGORIES = "inventory-categories"
    internal const val LOCATIONS = "inventory-locations"

    /** One item's read view (the web app's `/inventory?open=<id>`). */
    fun item(id: Int) = "inventory/$id"

    /** The list with a category chip chosen (`/inventory?category=<key>`). */
    fun category(key: String) = "inventory?category=" + android.net.Uri.encode(key)

    internal fun editor(id: Int?) = if (id == null) "inventory-editor" else "inventory-editor?id=$id"
}
