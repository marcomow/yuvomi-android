package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.i18n.t

/** Where a native module's entry sits: in the bottom bar, or at the top of "More". */
enum class Placement { BAR, MORE }

/**
 * A native module beyond Overview and Tasks (ADR 0015). Each one lives in its own
 * package with its own routes, strings (`assets/i18n-android/<id>/`), operations
 * (`upstream/operations-used.d/<id>.txt`) and spec, so modules can be built in
 * parallel without touching each other's files. `Features.all` lists them.
 */
interface Feature {
    /** The module id in [ModuleCatalog]: label, icon, tone, web route and permission come from there. */
    val id: String

    val placement: Placement

    /** The route the bar or "More" opens. */
    val startRoute: String

    /** Route prefixes that belong to this module, so its bar entry shows as selected. */
    val routePrefixes: List<String>

    /** Adds this module's destinations. Cross-module links use `LocalShell.current.navigate(route)`. */
    fun register(graph: NavGraphBuilder, nav: NavHostController)
}

/** Stand-in for a module whose native screens are not built yet; offers the web app instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturePlaceholder(id: String) {
    val shell = LocalShell.current
    val module = ModuleCatalog.get(id) ?: return
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(t(module.labelKey)) }, actions = { AccountButton() })
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { shell.openWeb(module.route) }) { Text(t("android.browser.openWebApp")) }
        }
    }
}
