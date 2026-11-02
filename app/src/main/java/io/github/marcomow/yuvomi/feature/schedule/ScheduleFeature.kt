package io.github.marcomow.yuvomi.feature.schedule

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Schedule (docs/spec/schedule.md). */
object ScheduleFeature : Feature {
    override val id = "schedule"
    override val placement = Placement.MORE
    override val startRoute = ScheduleRoutes.START
    override val routePrefixes = listOf("schedule")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(ScheduleRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Schedule's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [ScheduleFeature.register].
 */
object ScheduleRoutes {
    const val START = "schedule"
}
