package io.github.marcomow.yuvomi.feature.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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

/**
 * "More": the native modules that are not in the bar (ADR 0015), then every module the member
 * may open that is not native yet; those open in the browser (ADR 0006).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    val shell = LocalShell.current
    val native = Features.all.filter { it.placement == Placement.MORE && shell.shows(it.id) }
    val modules = ModuleCatalog.all.filter { m -> m.id !in Features.nativeIds && shell.shows(m.id) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(t("nav.more")) },
            actions = { AccountButton() },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(native, key = { "native-" + it.id }) { f ->
                val m = ModuleCatalog.get(f.id) ?: return@items
                ListItem(
                    headlineContent = { Text(t(m.labelKey)) },
                    leadingContent = { ModuleBadge(m.icon, Yuvomi.colors.module(m.tone)) },
                    modifier = Modifier.clickable { shell.navigate(f.startRoute) },
                )
            }
            item(key = "web-header") {
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
