package io.github.marcomow.yuvomi.feature.subscriptions

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder

/**
 * The budget page's Subscriptions tab (docs/spec/subscriptions.md). Not a module of its own: Budget's
 * feature registers these destinations, and their routes start with `budget` so the
 * Budget entry stays selected.
 */
object SubscriptionsGraph {
    fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(SubscriptionsRoutes.START) { FeaturePlaceholder("budget") }
    }
}

/** Subscriptions's routes; other code links here through these helpers only. */
object SubscriptionsRoutes {
    const val START = "budget/subscriptions"
}
