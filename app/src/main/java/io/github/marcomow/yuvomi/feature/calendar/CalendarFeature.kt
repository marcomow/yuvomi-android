package io.github.marcomow.yuvomi.feature.calendar

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Calendar (docs/spec/calendar.md). */
object CalendarFeature : Feature {
    override val id = "calendar"
    override val placement = Placement.MORE
    override val startRoute = CalendarRoutes.START
    override val routePrefixes = listOf("calendar")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(CalendarRoutes.START) { FeaturePlaceholder(id) }
        // Placeholder for the detail route; the module replaces both.
        graph.composable("calendar?open={open}") { FeaturePlaceholder(id) }
    }
}

/**
 * Calendar's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [CalendarFeature.register].
 */
object CalendarRoutes {
    const val START = "calendar"

    /** An event's detail (the web app's `/calendar?open=<id>`). */
    fun event(id: Int) = "calendar?open=$id"
}
