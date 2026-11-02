package io.github.marcomow.yuvomi.feature.splitexpenses

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder

/**
 * The budget page's SplitExpenses tab (docs/spec/splitexpenses.md). Not a module of its own: Budget's
 * feature registers these destinations, and their routes start with `budget` so the
 * Budget entry stays selected.
 */
object SplitExpensesGraph {
    fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(SplitExpensesRoutes.START) { FeaturePlaceholder("budget") }
    }
}

/** SplitExpenses's routes; other code links here through these helpers only. */
object SplitExpensesRoutes {
    const val START = "budget/split"
}
