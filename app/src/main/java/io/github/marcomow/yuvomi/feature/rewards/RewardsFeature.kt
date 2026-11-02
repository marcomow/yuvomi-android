package io.github.marcomow.yuvomi.feature.rewards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
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
import io.github.marcomow.yuvomi.feature.shell.Routes
import io.github.marcomow.yuvomi.feature.shell.ShellContext

/** Rewards (docs/spec/rewards.md). */
object RewardsFeature : Feature {
    override val id = "rewards"
    override val placement = Placement.MORE
    override val startRoute = RewardsRoutes.START
    override val routePrefixes = listOf("rewards")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers both `rewards` (START) and `rewards?tab=catalog`.
        graph.composable(
            RewardsRoutes.MAIN,
            arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val shell = LocalShell.current
            val strings = LocalStrings.current
            val host = remember(shell, strings) { ShellRewardsHost(shell, strings) }
            val tab = entry.arguments?.getString("tab")
            val vm = viewModel(viewModelStoreOwner = entry, key = "rewards") {
                RewardsViewModel(shell.container, host).apply { if (tab == RewardsViewModel.TAB_CATALOG) setTab(tab) }
            }
            RewardsScreen(
                vm = vm,
                host = host,
                openHistory = { nav.navigate(RewardsRoutes.HISTORY) },
                // Setup step 2 (`handleSetupStep('tasks')`): points are set on tasks.
                openTasks = { nav.navigate(Routes.tasks()) },
            )
        }
        graph.composable(RewardsRoutes.HISTORY) { entry ->
            val shell = LocalShell.current
            val vm = viewModel(viewModelStoreOwner = entry, key = "rewards-history") { RewardsHistoryViewModel(shell.container) }
            RewardsHistoryScreen(vm) {
                // Back to "Points": the entry before, or the page's own address after a deep link.
                if (!nav.popBackStack(RewardsRoutes.MAIN, inclusive = false)) nav.navigate(RewardsRoutes.START) { popUpTo(RewardsRoutes.HISTORY) { inclusive = true } }
            }
        }
    }
}

/** [RewardsHost] on the signed-in shell. */
internal class ShellRewardsHost(private val shell: ShellContext, override val strings: Strings) : RewardsHost {
    override val isAdmin: Boolean get() = shell.session.isAdmin || shell.session.user.role == "admin"
    override val readOnly: Boolean get() = shell.session.access("rewards") != "write"
    override val me: Int get() = shell.session.user.id

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Rewards's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [RewardsFeature.register].
 */
object RewardsRoutes {
    const val START = "rewards"
    internal const val MAIN = "rewards?tab={tab}"

    /** The catalogue tab ("Rewards"). */
    fun catalog() = "rewards?tab=catalog"

    /** The points history (`/rewards/history`, REW-R13). */
    const val HISTORY = "rewards/history"
}
