package io.github.marcomow.yuvomi.feature.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.LocalDate
import java.time.LocalDateTime

/** Health (docs/spec/health.md). */
object HealthFeature : Feature {
    override val id = "health"
    override val placement = Placement.MORE
    override val startRoute = HealthRoutes.START
    override val routePrefixes = listOf("health")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        val back: () -> Unit = { if (!nav.popBackStack()) nav.navigate(HealthRoutes.START) }
        val open: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
        graph.composable(HealthRoutes.START) { HealthOverviewScreen(healthViewModel("overview", ::OverviewViewModel), open) }
        graph.composable(HealthRoutes.VITALS) { HealthVitalsScreen(healthViewModel("vitals", ::VitalsViewModel), back) }
        graph.composable(HealthRoutes.MEDS) { HealthMedsScreen(healthViewModel("meds", ::MedsViewModel), back) }
        graph.composable(HealthRoutes.FASTING) { HealthFastingScreen(healthViewModel("fasting", ::FastingViewModel), back) }
        graph.composable(HealthRoutes.CYCLE) { HealthCycleScreen(healthViewModel("cycle", ::CycleViewModel), back) }
        graph.composable(HealthRoutes.PREVENTION) { HealthPreventionScreen(healthViewModel("prevention", ::PreventionViewModel), back) }
        graph.composable(HealthRoutes.LABS) { HealthLabsScreen(healthViewModel("labs", ::LabsViewModel), back) }
        graph.composable(HealthRoutes.ACTIVITY) { HealthActivityScreen(healthViewModel("activity", ::ActivityViewModel), back) }
        graph.composable(HealthRoutes.NUTRITION) { HealthNutritionScreen(healthViewModel("nutrition", ::NutritionViewModel), back) }
    }
}

/** An area's view model, owned by its back stack entry, on the shared hub of the signed-in member. */
@Composable
internal inline fun <reified V : HealthAreaViewModel<*>> healthViewModel(area: String, crossinline make: (AppContainer, HealthHub, HealthHost) -> V): V {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val host = remember(shell, strings) { ShellHealthHost(shell, strings) }
    val hub = remember(shell) { HealthHub.of(shell.container, shell.session) }
    return viewModel(key = "health-$area") { make(shell.container, hub, host) }
}

/** [HealthHost] on the signed-in shell. */
internal class ShellHealthHost(private val shell: ShellContext, override val strings: Strings) : HealthHost {
    override fun today(): LocalDate = shell.time.today()

    override fun now(): LocalDateTime = shell.time.now().toLocalDateTime()

    override fun wall(instant: String): LocalDateTime? = shell.time.parse(instant)?.toLocalDateTime()

    override fun date(key: String): String = HealthTimes.date(key)?.let { shell.formats.date(it, today()) } ?: key

    override suspend fun notify(message: String, action: String?): Boolean = shell.notify(message, action)
}

/**
 * Health's routes, one per area like the web app's (`HEALTH_ROUTES` in utils/health-tabs.js).
 * Other modules link here through these helpers only; this module registers a destination for
 * each of them in [HealthFeature.register].
 */
object HealthRoutes {
    const val START = "health"
    const val VITALS = "health/vitals"
    const val CYCLE = "health/cycle"
    const val FASTING = "health/fasting"
    const val MEDS = "health/meds"
    const val PREVENTION = "health/prevention"
    const val LABS = "health/labs"
    const val ACTIVITY = "health/activity"
    const val NUTRITION = "health/nutrition"

    /** `healthAreaRoute`: an area by its id; unknown ids land on the overview. */
    fun area(id: String?): String = when (id) {
        "vitals" -> VITALS
        "cycle" -> CYCLE
        "fasting" -> FASTING
        "meds" -> MEDS
        "prevention" -> PREVENTION
        "labs" -> LABS
        "activity" -> ACTIVITY
        "nutrition" -> NUTRITION
        else -> START
    }

    /** `HEALTH_AREAS`: the overview's list; cycle and fasting only when they are on. */
    fun areas(cycleEnabled: Boolean, fastingEnabled: Boolean): List<String> =
        listOf("vitals") + (if (cycleEnabled) listOf("cycle") else emptyList()) + (if (fastingEnabled) listOf("fasting") else emptyList()) +
            listOf("meds", "prevention", "labs", "activity", "nutrition")
}
