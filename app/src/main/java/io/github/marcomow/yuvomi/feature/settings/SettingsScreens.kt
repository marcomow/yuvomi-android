package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ModuleBadge
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/** The viewer as the registry needs them. */
internal fun ShellContext.viewer() = SettingsRegistry.Viewer(session.isAdmin, appUpdates = container.updater.enabled) { session.access(it) }

internal fun leafIcon(leaf: SettingsRegistry.Leaf): ImageVector = leaf.module?.let { ModuleCatalog.get(it)?.icon } ?: when (leaf.id) {
    "personal-account" -> Icons.Outlined.AccountCircle
    "personal-appearance" -> Icons.Outlined.Palette
    "personal-device" -> Icons.Outlined.Smartphone
    "personal-notifications" -> Icons.Outlined.Notifications
    "personal-weather" -> Icons.Outlined.Cloud
    SettingsRegistry.APP_UPDATES -> Icons.Outlined.SystemUpdate
    "admin-family" -> Icons.Outlined.Group
    "admin-permissions" -> Icons.Outlined.Security
    "admin-displays" -> Icons.Outlined.TabletAndroid
    "admin-email" -> Icons.Outlined.Mail
    "admin-backup" -> Icons.Outlined.Backup
    "admin-system" -> Icons.Outlined.Info
    "admin-api" -> Icons.Outlined.Api
    "admin-integrations" -> Icons.Outlined.Power
    "modules-active" -> Icons.Outlined.ToggleOn
    "modules-navigation" -> Icons.Outlined.Menu
    "module-kitchen" -> Icons.Outlined.Kitchen
    "module-calendar-sync" -> Icons.Outlined.Sync
    "modules-install" -> Icons.Outlined.Extension
    else -> Icons.Outlined.Settings
}

@Composable
private fun leafTone(leaf: SettingsRegistry.Leaf): Color =
    Yuvomi.colors.module(leaf.module?.let { ModuleCatalog.get(it)?.tone ?: if (it == "kitchen") "meals" else null } ?: "settings")

/**
 * SET-R1 to R4: the domains and their sheets this member sees, with a search over sheets,
 * sections and their descriptions (`searchSettings`, without per-option hits).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(vm: SettingsSheetViewModel, open: (String) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val viewer = shell.viewer()
    var query by rememberSaveable { mutableStateOf("") }
    val slots by vm.slots.collectAsState()
    LaunchedEffect(vm) { vm.loadPreferences() }
    val order = (slots[SettingsSheetViewModel.PREFERENCES]?.value as? kotlinx.serialization.json.JsonObject)?.strings("module_order").orEmpty()
    Scaffold(
        topBar = { TopAppBar(title = { Text(t("settings.title")) }, actions = { AccountButton() }) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(t("settings.searchLabel")) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotBlank()) {
                val hits = SettingsSearch.search(query, viewer, strings::t)
                item { Hint(strings.t("settings.searchResults", "count" to hits.size)) }
                if (hits.isEmpty()) item { EmptyState(t("settings.searchResults", "count" to 0)) }
                items(hits, key = { it.key }) { hit ->
                    Card(Modifier.padding(vertical = 4.dp)) {
                        ListItem(
                            headlineContent = { Text(hit.label) },
                            supportingContent = { Text(t(hit.leaf.labelKey), color = Yuvomi.colors.textSecondary) },
                            leadingContent = { ModuleBadge(leafIcon(hit.leaf), leafTone(hit.leaf)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { open(hit.leaf.id) },
                        )
                    }
                }
            } else {
                SettingsRegistry.domainsFor(viewer).forEach { domain ->
                    item(key = domain.id) { GroupTitle(t(domain.labelKey)) }
                    item(key = domain.id + ":leaves") {
                        Card {
                            Column {
                                SettingsRegistry.leavesOf(domain.id, viewer, order).forEachIndexed { i, leaf ->
                                    if (i > 0) RowDivider()
                                    ListItem(
                                        headlineContent = { Text(t(leaf.labelKey)) },
                                        supportingContent = { Text(t(leaf.descriptionKey), color = Yuvomi.colors.textSecondary, maxLines = 2) },
                                        leadingContent = { ModuleBadge(leafIcon(leaf), leafTone(leaf)) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.clickable { open(leaf.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What a section composable gets: the shell, the sheet's data, and a way to run writes. */
class SectionContext(
    val shell: ShellContext,
    val vm: SettingsSheetViewModel,
    val strings: Strings,
    val scope: CoroutineScope,
    val section: SettingsRegistry.Section,
) {
    val editable: Boolean get() = !vm.offline
    val isAdmin: Boolean get() = shell.session.isAdmin

    fun notify(message: String) {
        scope.launch { shell.notify(message) }
    }

    fun notifyKey(key: String, vararg args: Pair<String, Any?>) = notify(strings.t(key, args.toMap()))

    /** Runs [action] (returns an error key or null) and says [success] or the error. */
    fun run(success: String?, action: suspend () -> String?) {
        scope.launch {
            val error = action()
            val key = error ?: success ?: return@launch
            shell.notify(strings.t(key))
        }
    }

    /** Saves a preferences patch and confirms with [savedKey] (`savePreferences` with a toast). */
    fun save(savedKey: String?, patch: kotlinx.serialization.json.JsonObject) = run(savedKey) { vm.savePreferences(patch) }

    fun openWeb() = shell.openWeb(SettingsRegistry.webPath(section))

    /** A server timestamp (UTC, with or without `Z`) in the household's zone. */
    fun dateTime(iso: String?): String? {
        val v = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val utc = if (v.length > 10 && !v.endsWith("Z") && !Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(v.substring(10))) v.replace(' ', 'T') + "Z" else v
        return shell.time.parse(utc)?.let { shell.formats.dateTime(it) } ?: v
    }
}

/** SET-R5: one sheet, its visible sections in order, "for me" before "for the household". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheetScreen(leafId: String, vm: SettingsSheetViewModel, back: () -> Unit, openLeaf: (String) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val leaf = SettingsRegistry.leaf(leafId)
    val viewer = shell.viewer()
    val slots by vm.slots.collectAsState()
    LaunchedEffect(vm) { vm.loadPreferences() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(leaf?.let { t(it.labelKey) } ?: t("settings.title")) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val cachedAt = slots.values.firstNotNullOfOrNull { it.cachedAt }
            if (cachedAt != null) OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(cachedAt).atZone(shell.time.zone)))
            if (leaf == null || !SettingsRegistry.leafVisible(leaf, viewer)) {
                EmptyState(t("settings.adminOnly"))
                return@Column
            }
            val sections = SettingsRegistry.sectionsOf(leaf, viewer)
            val scoped = leaf.module != null && sections.map { it.scope }.distinct().size > 1
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                var lastScope: String? = null
                sections.forEach { section ->
                    if (scoped && section.scope != lastScope) {
                        val heading = if (section.scope == SettingsRegistry.MINE) "settings.scopeMine" else "settings.scopeHousehold"
                        item(key = "scope:" + section.scope) {
                            Text(t(heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                        }
                    }
                    lastScope = section.scope
                    item(key = section.id) {
                        val ctx = remember(section, vm) { SectionContext(shell, vm, strings, scope, section) }
                        Column {
                            if (sections.size > 1 && section.id != leaf.id) GroupTitle(t(section.labelKey))
                            SectionContent(ctx, openLeaf)
                        }
                    }
                }
            }
        }
    }
}
