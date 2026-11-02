package io.github.marcomow.yuvomi.feature.schedule

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
import io.github.marcomow.yuvomi.feature.settings.SettingsRoutes
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.LocalDate

/** Schedule (docs/spec/schedule.md). */
object ScheduleFeature : Feature {
    override val id = "schedule"
    override val placement = Placement.MORE
    override val startRoute = ScheduleRoutes.START
    override val routePrefixes = listOf("schedule")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        // The bare root decides the tab itself (`render`); `schedule/<tab>` is a deep link (S-10).
        graph.composable(ScheduleRoutes.START) { entry ->
            ScheduleScreen(vm = scheduleViewModel(entry, null, null), openSettings = { nav.navigate(SettingsRoutes.START) })
        }
        graph.composable(
            ScheduleRoutes.TAB,
            arguments = listOf(
                navArgument("tab") { type = NavType.StringType },
                navArgument("open") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ScheduleScreen(
                vm = scheduleViewModel(entry, ScheduleTab.of(entry.arguments?.getString("tab")), entry.arguments?.getString("open")),
                openSettings = { nav.navigate(SettingsRoutes.START) },
            )
        }
    }
}

/** The page's state, owned by its back stack entry; a new sign-in starts afresh. */
@Composable
internal fun scheduleViewModel(owner: NavBackStackEntry, tab: ScheduleTab?, open: String?): ScheduleViewModel {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellScheduleHost(shell, strings) }
    return viewModel(viewModelStoreOwner = owner, key = "schedule-${shell.session.server}-${shell.session.user.id}") {
        ScheduleViewModel(shell.container, host, tab, open)
    }
}

/** [ScheduleHost] on the signed-in shell. */
internal class ShellScheduleHost(private val shell: ShellContext, override val strings: Strings) : ScheduleHost {
    override val userId: Int get() = shell.session.user.id
    override val readOnly: Boolean get() = shell.session.access("schedule") != "write"

    /** `canManageOthers = user.role === 'admin'`. */
    override val isAdmin: Boolean get() = shell.session.user.role == "admin" || shell.session.isAdmin

    override fun today(): LocalDate = shell.time.today()

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Schedule's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [ScheduleFeature.register].
 */
object ScheduleRoutes {
    const val START = "schedule"
    internal const val TAB = "schedule/{tab}?open={open}"

    /** One tab, as the web's `/schedule/<tab>`: `overview`, `shifts`, `patterns` or `statistics`. */
    fun tab(segment: String) = "schedule/$segment"

    /** The plan's dialog on the Planning tab. */
    fun pattern(id: Int) = "schedule/patterns?open=pattern:$id"

    /** The shift type's dialog on the Shift types tab. */
    fun shiftType(id: Int) = "schedule/shifts?open=type:$id"

    /** An override row (a person's range from [from]) on the Planning tab. */
    fun override(userId: Int, from: String) = "schedule/patterns?open=override:$userId:$from"

    /** An extra-shift row, by its ids, on the Planning tab. */
    fun extra(ids: List<Int>) = "schedule/patterns?open=extra:${ids.joinToString("-")}"
}
