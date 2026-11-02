package io.github.marcomow.yuvomi.feature.budget

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
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitExpensesGraph
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsGraph
import java.time.LocalDate

/** Budget (docs/spec/budget.md). */
object BudgetFeature : Feature {
    override val id = "budget"
    override val placement = Placement.MORE
    override val startRoute = BudgetRoutes.START
    override val routePrefixes = listOf("budget")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // One destination answers `budget` (START) and `budget?tab=loans` (BudgetRoutes.tab).
        graph.composable(
            BudgetRoutes.LIST,
            arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val tab = BudgetTab.entries.firstOrNull { it.id == entry.arguments?.getString("tab") } ?: BudgetTab.OVERVIEW
            BudgetScreen(vm = budgetViewModel(entry, tab), nav = BudgetNav(nav))
        }
        graph.composable(BudgetRoutes.ENTRY) { entry ->
            val owner = remember(entry) { listEntry(nav) } ?: return@composable
            EntryEditorScreen(budgetViewModel(owner, BudgetTab.OVERVIEW), BudgetNav(nav))
        }
        graph.composable(BudgetRoutes.TRANSFER) { entry ->
            val owner = remember(entry) { listEntry(nav) } ?: return@composable
            TransferEditorScreen(budgetViewModel(owner, BudgetTab.OVERVIEW), close = { nav.popBackStack() })
        }
        graph.composable(BudgetRoutes.ACCOUNT) { entry ->
            val owner = remember(entry) { listEntry(nav) } ?: return@composable
            AccountEditorScreen(budgetViewModel(owner, BudgetTab.OVERVIEW), close = { nav.popBackStack() })
        }
        graph.composable(BudgetRoutes.LOAN) { entry ->
            val owner = remember(entry) { listEntry(nav) } ?: return@composable
            LoanEditorScreen(budgetViewModel(owner, BudgetTab.OVERVIEW), close = { nav.popBackStack() })
        }
        graph.composable(BudgetRoutes.CATEGORIES) { entry ->
            val owner = remember(entry) { listEntry(nav) }
            val main = owner?.let { budgetViewModel(it, BudgetTab.OVERVIEW) }
            BudgetCategoriesScreen(changed = { main?.reloadMeta() }, close = { nav.popBackStack() })
        }
        // The budget page's other tabs are built in their own packages.
        SubscriptionsGraph.register(graph, nav)
        SplitExpensesGraph.register(graph, nav)
    }

    private fun listEntry(nav: NavHostController): NavBackStackEntry? =
        runCatching { nav.getBackStackEntry(BudgetRoutes.LIST) }.getOrNull()
}

/** The ways out of the budget screen, as plain functions for the screens. */
class BudgetNav(private val nav: NavHostController) {
    fun entryEditor() = nav.navigate(BudgetRoutes.ENTRY)
    fun transferEditor() = nav.navigate(BudgetRoutes.TRANSFER)
    fun accountEditor() = nav.navigate(BudgetRoutes.ACCOUNT)
    fun loanEditor() = nav.navigate(BudgetRoutes.LOAN)
    fun categories() = nav.navigate(BudgetRoutes.CATEGORIES)
    fun back() = nav.popBackStack()

    /** Replaces the open editor with another one (the type segment's Loan and Transfer). */
    fun switchTo(route: String) {
        nav.popBackStack()
        nav.navigate(route)
    }
}

/** The page's view model, owned by the list's back stack entry and shared with every editor. */
@Composable
internal fun budgetViewModel(owner: NavBackStackEntry, tab: BudgetTab): BudgetViewModel {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellBudgetHost(shell, strings) }
    return viewModel(viewModelStoreOwner = owner, key = "budget") { BudgetViewModel(shell.container, host, tab) }
}

/** [BudgetHost] on the signed-in shell. */
internal class ShellBudgetHost(private val shell: ShellContext, override val strings: Strings) : BudgetHost {
    override fun today(): LocalDate = shell.time.today()

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)

    override val writable: Boolean get() = shell.session.access("budget") == "write"

    override val documentsAccess: String
        get() = if ("documents" in shell.session.preferences.disabledModules) "none" else shell.session.access("documents")
}

/**
 * Budget's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [BudgetFeature.register].
 */
object BudgetRoutes {
    const val START = "budget"
    internal const val LIST = "budget?tab={tab}"
    internal const val ENTRY = "budget/entry"
    internal const val TRANSFER = "budget/transfer"
    internal const val ACCOUNT = "budget/account"
    internal const val LOAN = "budget/loan"
    internal const val CATEGORIES = "budget/categories"

    /** The page on one tab: `budget`, `accounts`, `plan`, `loans` or `reports` (`?tab=` of the web page). */
    fun tab(tab: String) = "budget?tab=$tab"
}
