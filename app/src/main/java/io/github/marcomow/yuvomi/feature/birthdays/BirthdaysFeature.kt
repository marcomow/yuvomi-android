package io.github.marcomow.yuvomi.feature.birthdays

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Birthdays (docs/spec/birthdays.md). */
object BirthdaysFeature : Feature {
    override val id = "birthdays"
    override val placement = Placement.MORE
    override val startRoute = BirthdaysRoutes.START
    override val routePrefixes = listOf("birthdays")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(BirthdaysRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Birthdays's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [BirthdaysFeature.register].
 */
object BirthdaysRoutes {
    const val START = "birthdays"
}
