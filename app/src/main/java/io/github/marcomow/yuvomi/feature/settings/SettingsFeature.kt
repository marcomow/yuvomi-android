package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.runtime.Composable
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

/** Settings (docs/spec/settings.md). */
object SettingsFeature : Feature {
    override val id = "settings"
    override val placement = Placement.MORE
    override val startRoute = SettingsRoutes.START
    override val routePrefixes = listOf("settings")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(SettingsRoutes.START) { entry ->
            SettingsRootScreen(vm = sheetViewModel(entry)) { nav.navigate(SettingsRoutes.leaf(it)) }
        }
        graph.composable(SettingsRoutes.LEAF, arguments = listOf(navArgument("leafId") { type = NavType.StringType })) { entry ->
            val leafId = entry.arguments?.getString("leafId") ?: return@composable
            SettingsSheetScreen(
                leafId = leafId,
                vm = sheetViewModel(entry),
                back = { if (!nav.popBackStack()) nav.navigate(SettingsRoutes.START) },
                openLeaf = { nav.navigate(SettingsRoutes.leaf(it)) },
            )
        }
    }
}

@Composable
private fun sheetViewModel(owner: NavBackStackEntry): SettingsSheetViewModel {
    val shell = LocalShell.current
    return viewModel(viewModelStoreOwner = owner, key = "settings") { SettingsSheetViewModel(shell.container) }
}

/**
 * Settings's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [SettingsFeature.register].
 */
object SettingsRoutes {
    const val START = "settings"
    internal const val LEAF = "settings/{leafId}"

    /**
     * One sheet, by upstream's leaf id (`registry.js` `SETTINGS_LEAVES`), for example
     * `personal-account`, `module-tasks` or `admin-family`. A sheet the member may not see
     * shows a short notice instead.
     */
    fun leaf(id: String) = "settings/$id"
}
