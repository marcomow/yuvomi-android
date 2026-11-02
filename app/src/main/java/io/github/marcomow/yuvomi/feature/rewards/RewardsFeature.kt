package io.github.marcomow.yuvomi.feature.rewards

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Rewards (docs/spec/rewards.md). */
object RewardsFeature : Feature {
    override val id = "rewards"
    override val placement = Placement.MORE
    override val startRoute = RewardsRoutes.START
    override val routePrefixes = listOf("rewards")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(RewardsRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Rewards's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [RewardsFeature.register].
 */
object RewardsRoutes {
    const val START = "rewards"
}
