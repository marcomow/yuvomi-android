package io.github.marcomow.yuvomi.feature.subscriptions

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * The budget page's Subscriptions tab (docs/spec/subscriptions.md). Not a module of its own: Budget's
 * feature registers these destinations, and their routes start with `budget` so the
 * Budget entry stays selected.
 */
object SubscriptionsGraph {
    fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(
            SubscriptionsRoutes.LIST,
            arguments = listOf(navArgument("open") { type = NavType.IntType; defaultValue = -1 }),
        ) { entry ->
            SubscriptionsScreen(
                openId = entry.arguments?.getInt("open")?.takeIf { it > 0 },
                back = { nav.popBackStack() },
                add = { nav.navigate(SubscriptionsRoutes.editor()) },
                edit = { nav.navigate(SubscriptionsRoutes.editor(it)) },
                manage = { nav.navigate(SubscriptionsRoutes.MANAGE) },
            )
        }
        graph.composable(
            SubscriptionsRoutes.EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.IntType; defaultValue = -1 }),
        ) { entry ->
            SubscriptionEditorScreen(id = entry.arguments?.getInt("id")?.takeIf { it > 0 }, close = { nav.popBackStack() })
        }
        graph.composable(SubscriptionsRoutes.MANAGE) { SubscriptionMetaScreen(back = { nav.popBackStack() }) }
    }
}

/** Subscriptions's routes; other code links here through these helpers only. */
object SubscriptionsRoutes {
    const val START = "budget/subscriptions"
    internal const val LIST = "budget/subscriptions?open={open}"
    internal const val EDITOR = "budget/subscriptions/edit?id={id}"
    const val MANAGE = "budget/subscriptions/manage"

    /**
     * The list with one subscription's read view open: where the budget ledger's expected
     * subscription row leads (`data-open-subscription`, budget.js). The tab's search and
     * filters are cleared first, as `clearSubscriptionFilters()` does.
     */
    fun subscription(id: Int) = "budget/subscriptions?open=$id"

    /** The editor: a new subscription (the budget FAB on this tab), or [id]. */
    fun editor(id: Int? = null) = if (id == null) "budget/subscriptions/edit" else "budget/subscriptions/edit?id=$id"
}
