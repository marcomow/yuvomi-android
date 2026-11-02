package io.github.marcomow.yuvomi.feature.budget

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitExpensesGraph
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsGraph

/** Budget (docs/spec/budget.md). */
object BudgetFeature : Feature {
    override val id = "budget"
    override val placement = Placement.MORE
    override val startRoute = BudgetRoutes.START
    override val routePrefixes = listOf("budget")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(BudgetRoutes.START) { FeaturePlaceholder(id) }
        // The budget page's other tabs are built in their own packages.
        SubscriptionsGraph.register(graph, nav)
        SplitExpensesGraph.register(graph, nav)
    }
}

/**
 * Budget's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [BudgetFeature.register].
 */
object BudgetRoutes {
    const val START = "budget"
}
