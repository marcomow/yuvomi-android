package io.github.marcomow.yuvomi.feature.waste

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
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.LocalDate

/** Waste (docs/spec/waste.md). The module ships switched off; `shell.shows("waste")` decides. */
object WasteFeature : Feature {
    override val id = "waste"
    override val placement = Placement.MORE
    override val startRoute = WasteRoutes.START
    override val routePrefixes = listOf("waste")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers `waste` (START) and `waste?type=&date=` (WASTE-R37).
        graph.composable(
            WasteRoutes.PAGE,
            arguments = listOf("type", "date").map { name -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } },
        ) { entry ->
            val link = WasteViewModel.Link(
                typeId = entry.arguments?.getString("type")?.toIntOrNull()?.takeIf { it > 0 },
                date = entry.arguments?.getString("date")?.takeIf { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) },
            )
            WasteScreen(wasteViewModel(entry, link))
        }
    }
}

@Composable
private fun wasteViewModel(owner: NavBackStackEntry, link: WasteViewModel.Link): WasteViewModel {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellWasteHost(shell, strings) }
    return viewModel(viewModelStoreOwner = owner, key = "waste") { WasteViewModel(shell.container, host, link) }
}

/** [WasteHost] on the signed-in shell. */
internal class ShellWasteHost(private val shell: ShellContext, override val strings: Strings) : WasteHost {
    override fun today(): LocalDate = shell.time.today()

    override fun access(): String = shell.session.access("waste")

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Waste's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [WasteFeature.register].
 */
object WasteRoutes {
    const val START = "waste"
    internal const val PAGE = "waste?type={type}&date={date}"

    /** A pickup of [typeId] on [date] (`deep_link` of `/waste/occurrences`), or the type's card without a date. */
    fun pickup(typeId: Int, date: String? = null) = "waste?type=$typeId" + (date?.let { "&date=$it" } ?: "")
}
