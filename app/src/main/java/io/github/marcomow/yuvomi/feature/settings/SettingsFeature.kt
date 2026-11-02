package io.github.marcomow.yuvomi.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.FeaturePlaceholder
import io.github.marcomow.yuvomi.feature.shell.Placement

/** Settings (docs/spec/settings.md). */
object SettingsFeature : Feature {
    override val id = "settings"
    override val placement = Placement.MORE
    override val startRoute = SettingsRoutes.START
    override val routePrefixes = listOf("settings")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(SettingsRoutes.START) { FeaturePlaceholder(id) }
    }
}

/**
 * Settings's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [SettingsFeature.register].
 */
object SettingsRoutes {
    const val START = "settings"
}
