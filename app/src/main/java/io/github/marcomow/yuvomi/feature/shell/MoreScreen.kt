package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.ModuleBadge
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.Features
import io.github.marcomow.yuvomi.feature.settings.SettingsRoutes

/**
 * "More": the native modules that are not in the bar, in the member's navigation order and without
 * the ones they hide (BarSlots), a way to change the bar, then any module that only the web app has
 * (third-party extensions); those open in the browser (ADR 0006).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    val shell = LocalShell.current
    val prefs = shell.session.preferences
    val shown = shell.shownModules()
    // Everything not in the bar, in the member's navigation order (BarSlots).
    val native = BarSlots.more(prefs, shown, BarSlots.bar(prefs, shown)).mapNotNull { id ->
        val m = ModuleCatalog.get(id) ?: return@mapNotNull null
        val route = if (id == "tasks") "tasks" else Features.all.firstOrNull { it.id == id }?.startRoute ?: return@mapNotNull null
        m to route
    }
    val modules = ModuleCatalog.all.filter { m -> m.id !in Features.nativeIds && m.id !in prefs.hiddenModules && shell.shows(m.id) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(t("nav.more")) },
            actions = { AccountButton() },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(native, key = { "native-" + it.first.id }) { (m, route) ->
                ListItem(
                    headlineContent = { Text(t(m.labelKey)) },
                    leadingContent = { ModuleBadge(m.icon, Yuvomi.colors.module(m.tone)) },
                    modifier = Modifier.clickable { shell.navigate(route) },
                )
            }
            item(key = "customise-bar") {
                ListItem(
                    headlineContent = { Text(t("android.more.customiseBar")) },
                    supportingContent = { Text(t("settings.mobileNavigationHint")) },
                    leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    modifier = Modifier.clickable { shell.navigate(SettingsRoutes.leaf("modules-navigation")) },
                )
            }
            if (modules.isNotEmpty()) item(key = "web-header") {
                Text(
                    t("android.more.hint"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Yuvomi.colors.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(modules, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(t(m.labelKey)) },
                    leadingContent = { ModuleBadge(m.icon, Yuvomi.colors.module(m.tone)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = t("android.browser.opensInBrowser")) },
                    modifier = Modifier.clickable { shell.openWeb(m.route) },
                )
            }
        }
    }
}
