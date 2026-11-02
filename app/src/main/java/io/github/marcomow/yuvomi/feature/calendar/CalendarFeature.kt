package io.github.marcomow.yuvomi.feature.calendar

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Calendar (docs/spec/calendar.md). */
object CalendarFeature : Feature {
    override val id = "calendar"
    override val placement = Placement.MORE
    override val startRoute = CalendarRoutes.START
    override val routePrefixes = listOf("calendar")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        val openEditor = { nav.navigate(CalendarRoutes.EDITOR) }
        graph.composable(CalendarRoutes.START) {
            CalendarScreen(
                openId = null,
                openEvent = { nav.navigate(CalendarRoutes.EVENT) },
                openEditor = openEditor,
                manageCalendars = { nav.navigate(CalendarRoutes.CALENDARS) },
            )
        }
        graph.composable(CalendarRoutes.OPEN, arguments = listOf(navArgument("open") { type = NavType.IntType; defaultValue = -1 })) { entry ->
            val id = entry.arguments?.getInt("open")?.takeIf { it > 0 }
            CalendarScreen(
                openId = id,
                openEvent = { nav.navigate(CalendarRoutes.EVENT) },
                openEditor = openEditor,
                manageCalendars = { nav.navigate(CalendarRoutes.CALENDARS) },
            )
        }
        graph.composable(CalendarRoutes.EVENT) {
            EventDetailScreen(back = { nav.popBackStack() }, edit = { nav.navigate(CalendarRoutes.EDITOR) })
        }
        graph.composable(CalendarRoutes.EDITOR) {
            EventEditorScreen(close = { nav.popBackStack() })
        }
        graph.composable(CalendarRoutes.CALENDARS) { CalendarsScreen(back = { nav.popBackStack() }) }
    }
}

/**
 * Calendar's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [CalendarFeature.register].
 */
object CalendarRoutes {
    const val START = "calendar"
    const val OPEN = "calendar?open={open}"
    const val EVENT = "calendar-event"
    const val EDITOR = "calendar-editor"
    const val CALENDARS = "calendar-calendars"

    /** An event's detail (the web app's `/calendar?open=<id>`). */
    fun event(id: Int) = "calendar?open=$id"
}
