package io.github.marcomow.yuvomi.feature.shell

import io.github.marcomow.yuvomi.feature.settings.SettingsRegistry
import io.github.marcomow.yuvomi.feature.settings.SettingsRoutes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.browser.Browser
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.i18n.Formats
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.Features
import io.github.marcomow.yuvomi.feature.overview.OverviewScreen
import io.github.marcomow.yuvomi.feature.tasks.TaskDetailScreen
import io.github.marcomow.yuvomi.feature.tasks.TaskEditorScreen
import io.github.marcomow.yuvomi.feature.tasks.TasksScreen
import kotlinx.coroutines.launch

object Routes {
    const val OVERVIEW = "overview"
    const val TASKS = "tasks?assignee={assignee}"
    const val TASK = "task/{id}"
    const val EDITOR = "editor?id={id}&parent={parent}&category={category}&assignee={assignee}"
    const val MORE = "more"

    fun tasks(assignee: Int? = null) = "tasks" + (assignee?.let { "?assignee=$it" } ?: "")
    fun task(id: Int) = "task/$id"
    fun editor(id: Int? = null, parent: Int? = null, category: String? = null, assignee: Int? = null) = buildString {
        append("editor")
        val params = listOfNotNull(
            id?.let { "id=$it" }, parent?.let { "parent=$it" }, category?.let { "category=$it" }, assignee?.let { "assignee=$it" },
        )
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }
}

private data class Destination(val route: String, val prefixes: List<String>, val labelKey: String, val icon: ImageVector)

/**
 * The signed-in app: Overview, Tasks, the native modules in [Features.all], and "More", which
 * lists the remaining native modules and everything that opens in the web app (ADR 0006,
 * ADR 0010, ADR 0015).
 */
@Composable
fun MainShell(container: AppContainer, state: SessionState.SignedIn, deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val session = state.session
    val connection = container.session.connection ?: return
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var lastResume by remember { mutableStateOf(System.currentTimeMillis()) }

    val time = remember(session.zone) { HouseholdTime(session.zone) }
    val formats = remember(strings, session.zone) { Formats(strings, session.zone) }
    val shell = remember(session, connection, formats) {
        ShellContext(
            container, session, connection, time, formats, snackbar,
            openAccount = { showAccount = true },
            openWeb = { path -> Browser.open(context, connection.client.webUrl(path)) },
            navigate = { route -> nav.navigate(route) },
        )
    }

    // In-app updates (ADR 0018): the automatic check, at most twice a day; a waiting version
    // shows as the banner above every screen (UpdateBanner).
    LaunchedEffect(Unit) { container.updater.checkIfDue() }

    // Reminders on the phone (REM-R7): Android 13 and newer ask once whether the app may notify.
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) scope.launch { io.github.marcomow.yuvomi.feature.reminders.ReminderScheduler.refresh(context) } }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (io.github.marcomow.yuvomi.feature.reminders.ReminderScheduler.canNotify(context)) return@LaunchedEffect
        if (!container.settings.phoneRemindersNow() || !container.settings.firstTime("notify-permission")) return@LaunchedEffect
        askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // Foreground after more than 5 minutes: re-read the member and household (OVERVIEW-R14).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = System.currentTimeMillis()
        if (now - lastResume > 5 * 60_000) scope.launch { container.session.refresh() }
        lastResume = now
    }

    // A task link (TASKS-R27) or a widget's row (WIDGETS-R2, R3).
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            nav.navigate(deepLink)
            onDeepLinkHandled()
        }
    }

    fun feature(f: Feature): Destination? {
        val module = ModuleCatalog.get(f.id) ?: return null
        return if (shell.shows(f.id)) Destination(f.startRoute, f.routePrefixes, module.labelKey, module.icon) else null
    }
    val overview = Destination(Routes.OVERVIEW, listOf("overview"), "nav.dashboard", Icons.Outlined.Dashboard)
    val tasks = Destination(Routes.TASKS, listOf("tasks", "task/", "editor"), "nav.tasks", Icons.Outlined.CheckCircle)
        .takeIf { session.access("tasks") != "none" }
    val more = Destination(Routes.MORE, listOf("more"), "nav.more", Icons.Outlined.Apps)
    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    // The bar and the rail: Overview, the member's three slots (BarSlots), More.
    val shown = shell.shownModules()
    val slots = BarSlots.bar(session.preferences, shown)
    val kitchenFeatures = Features.all.filter { it.id in BarSlots.KITCHEN_MODULES }
    var lastKitchen by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        kitchenFeatures.firstOrNull { f -> f.routePrefixes.any { current?.startsWith(it) == true } }?.let { lastKitchen = it.id }
    }
    fun slot(id: String): Destination? = when (id) {
        "tasks" -> tasks
        BarSlots.KITCHEN -> BarSlots.kitchenTarget(lastKitchen, shown)
            ?.let { target -> kitchenFeatures.first { it.id == target } }
            ?.let { target ->
                Destination(target.startRoute, kitchenFeatures.flatMap { it.routePrefixes }, "nav.kitchen", Icons.Outlined.Restaurant)
            }
        else -> Features.all.firstOrNull { it.id == id }?.let(::feature)
    }
    val barDestinations = listOf(overview) + slots.mapNotNull(::slot) + more

    CompositionLocalProvider(LocalShell provides shell) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val destinations = barDestinations
            val selected = destinations.firstOrNull { d -> d.prefixes.any { current?.startsWith(it) == true } }?.route
                ?: Routes.MORE.takeIf { current != null && current != Routes.OVERVIEW }
            val go = { d: Destination ->
                nav.navigate(if (d.route == Routes.TASKS) "tasks" else d.route) {
                    popUpTo(Routes.OVERVIEW) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            destinations.forEach { d ->
                                NavigationBarItem(
                                    selected = selected == d.route,
                                    onClick = { go(d) },
                                    icon = { Icon(d.icon, contentDescription = null) },
                                    label = { Text(t(d.labelKey)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                    if (wide) {
                        NavigationRail {
                            destinations.forEach { d ->
                                NavigationRailItem(
                                    selected = selected == d.route,
                                    onClick = { go(d) },
                                    icon = { Icon(d.icon, contentDescription = null) },
                                    label = { Text(t(d.labelKey)) },
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        UpdateBanner(container.updater) { nav.navigate(SettingsRoutes.leaf(SettingsRegistry.APP_UPDATES)) }
                        if (session.showNewerNotice) NewerServerNotice { container.session.dismissNewerNotice() }
                        Box(Modifier.weight(1f)) { ShellNavHost(nav, state.offline) }
                    }
                }
            }
        }
        if (showAccount) AccountSheet(onDismiss = { showAccount = false })
    }
}

@Composable
private fun ShellNavHost(nav: NavHostController, offline: Boolean) {
    NavHost(nav, startDestination = Routes.OVERVIEW) {
        composable(Routes.OVERVIEW) {
            OverviewScreen(
                offlineSession = offline,
                openTask = { nav.navigate(Routes.task(it)) },
                openTasks = { assignee -> nav.navigate(Routes.tasks(assignee)) },
            )
        }
        composable(
            Routes.TASKS,
            arguments = listOf(navArgument("assignee") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            TasksScreen(
                presetAssignee = entry.arguments?.getString("assignee")?.toIntOrNull(),
                openTask = { nav.navigate(Routes.task(it)) },
                newTask = { category, assignee -> nav.navigate(Routes.editor(category = category, assignee = assignee)) },
            )
        }
        composable(
            Routes.TASK,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
            deepLinks = listOf(navDeepLink { uriPattern = "yuvomi-android://tasks/{id}" }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            TaskDetailScreen(
                taskId = id,
                back = { nav.popBackStack() },
                edit = { nav.navigate(Routes.editor(id = it)) },
                addSubtask = { parent -> nav.navigate(Routes.editor(parent = parent)) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("parent") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("assignee") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val args = entry.arguments
            TaskEditorScreen(
                taskId = args?.getString("id")?.toIntOrNull(),
                parentId = args?.getString("parent")?.toIntOrNull(),
                presetCategory = args?.getString("category"),
                presetAssignee = args?.getString("assignee")?.toIntOrNull(),
                close = { nav.popBackStack() },
                deleted = {
                    nav.popBackStack()
                    if (nav.currentDestination?.route == Routes.TASK) nav.popBackStack()
                },
            )
        }
        composable(Routes.MORE) { MoreScreen() }
        (Features.all + Features.pending).forEach { it.register(this, nav) }
    }
}
