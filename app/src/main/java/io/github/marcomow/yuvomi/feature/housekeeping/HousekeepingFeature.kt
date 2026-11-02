package io.github.marcomow.yuvomi.feature.housekeeping

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
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext

/** Housekeeping (docs/spec/housekeeping.md). */
object HousekeepingFeature : Feature {
    override val id = "housekeeping"
    override val placement = Placement.MORE
    override val startRoute = HousekeepingRoutes.START
    override val routePrefixes = listOf("housekeeping")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers `housekeeping` (START) and every link with its query (HOUSE-R30).
        graph.composable(
            HousekeepingRoutes.PAGE,
            arguments = listOf("tab", "visit", "task", "worker").map { name ->
                navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
        ) { entry ->
            val args = entry.arguments
            HousekeepingScreen(
                vm = housekeepingViewModel(entry, HousekeepingViewModel.Tab.of(args?.getString("tab"))),
                link = HousekeepingLink(
                    visit = args?.getString("visit")?.toIntOrNull(),
                    task = args?.getString("task")?.toIntOrNull(),
                    worker = args?.getString("worker")?.toIntOrNull(),
                ),
            )
        }
    }
}

@Composable
private fun housekeepingViewModel(owner: NavBackStackEntry, tab: HousekeepingViewModel.Tab?): HousekeepingViewModel {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellHousekeepingHost(shell, strings) }
    return viewModel(viewModelStoreOwner = owner, key = "housekeeping") { HousekeepingViewModel(shell.container, host, tab) }
}

/** [HousekeepingHost] on the signed-in shell. */
internal class ShellHousekeepingHost(private val shell: ShellContext, override val strings: Strings) : HousekeepingHost {
    override val time: HouseholdTime get() = shell.time

    override fun access(): String = shell.session.access("housekeeping")

    override fun documentsAccess(): String = if (shell.shows("documents")) shell.session.access("documents") else "none"

    override fun canSeeAmounts(): Boolean =
        HousekeepingRules.canSeeAmounts(shell.session.isAdmin, shell.session.permissions.capabilities)

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Housekeeping's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [HousekeepingFeature.register].
 */
object HousekeepingRoutes {
    const val START = "housekeeping"
    internal const val PAGE = "housekeeping?tab={tab}&visit={visit}&task={task}&worker={worker}"

    /** A visit: its editor where the member may edit it, its report otherwise (`?editVisit=`, from Calendar). */
    fun visit(id: Int) = "housekeeping?visit=$id"

    /** A chore, on the chores tab (its editor for members who may write). */
    fun task(id: Int) = "housekeeping?tab=tasks&task=$id"

    /** A housekeeper, selected on the staff tab with their visit log. */
    fun worker(id: Int) = "housekeeping?tab=staff&worker=$id"
}
