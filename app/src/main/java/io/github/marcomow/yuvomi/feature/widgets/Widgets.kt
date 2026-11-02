package io.github.marcomow.yuvomi.feature.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.MainActivity
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Connection
import io.github.marcomow.yuvomi.core.session.Session
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.feature.shopping.ShoppingApi
import io.github.marcomow.yuvomi.feature.shopping.ShoppingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate

/**
 * Reading and refreshing the home-screen widgets (docs/spec/widgets.md, ADR 0020). A widget
 * uses the app's own signed-in session and the same endpoints and permissions as its module;
 * it never signs in by itself and stores nothing but its last snapshot, in the app's private
 * files, removed on sign-out.
 */
object Widgets {
    /** Bumped to make a running widget read again ([refresh]). */
    val VERSION = longPreferencesKey("version")

    /** Extra of the intent a widget opens the app with: a route of the shell's navigation. */
    const val EXTRA_ROUTE = "io.github.marcomow.yuvomi.route"

    private const val TASKS = "tasks"
    private const val SHOPPING = "shopping"

    fun container(context: Context): AppContainer = (context.applicationContext as YuvomiApp).container

    /** An intent that opens the app at [route] (or its start when null). Unique per route, so each row's tap stays its own. */
    fun open(context: Context, route: String?): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (route != null) {
            putExtra(EXTRA_ROUTE, route)
            data = Uri.parse("yuvomi-widget://open/" + Uri.encode(route))
        }
    }

    private val ALLOWED = listOf(Regex("tasks"), Regex("task/[0-9]+"), Regex("shopping"), Regex("shopping\\?list=[0-9]+"))

    /**
     * [route] if it is one a widget opens. The activity is exported (it is the launcher), so any
     * app could hand it an extra; only these routes are followed.
     */
    fun allowedRoute(route: String): String? = route.takeIf { r -> ALLOWED.any { it.matches(r) } }

    private class Ready(val container: AppContainer, val session: Session, val connection: Connection, val strings: Strings)

    /** The app's session, restored if this process was started for the widget; null when signed out. */
    private suspend fun ready(context: Context): Pair<Ready?, Strings> {
        val c = container(context)
        val strings = c.strings.filterNotNull().first()
        withContext(Dispatchers.Main) { c.session.start() }
        val state = withTimeoutOrNull(20_000) { c.session.state.first { it !is SessionState.Starting } }
        val session = (state as? SessionState.SignedIn)?.session
        val conn = c.session.connection
        return (if (session != null && conn != null) Ready(c, session, conn, strings) else null) to strings
    }

    suspend fun tasks(context: Context): WidgetSnapshot = load(context, TASKS, "nav.tasks") { r ->
        val access = r.session.access("tasks")
        if (access == "none") return@load noAccess(r.strings, "nav.tasks")
        val tasks = TasksApi.decodeList(r.connection.tasks.list(TasksApi.Filter()))
        TaskWidgetLogic.snapshot(tasks, LocalDate.now(r.session.zone), r.strings, canWrite = access == "write")
    }

    suspend fun shopping(context: Context): WidgetSnapshot = load(context, SHOPPING, "nav.shopping") { r ->
        val access = r.session.access("shopping")
        if (access == "none") return@load noAccess(r.strings, "nav.shopping")
        val api = ShoppingApi(r.connection.client)
        val lists = ShoppingApi.decodeLists(api.lists())
        val remembered = r.container.cache.read(ShoppingViewModel.CACHE_LAST)?.json?.trim()?.toIntOrNull()
        val id = ShoppingWidgetLogic.choose(remembered, lists.map { it.id })
            ?: return@load WidgetSnapshot(r.strings.t("nav.shopping"), message = r.strings.t("shopping.emptyList"), canWrite = false)
        val answer = ShoppingApi.decodeItems(api.items(id))
        val name = answer.list?.name ?: lists.first { it.id == id }.name
        val writable = access == "write" && lists.first { it.id == id }.acceptsNewItems
        ShoppingWidgetLogic.snapshot(id, name, answer.data, r.strings, canWrite = writable)
    }

    private fun noAccess(strings: Strings, titleKey: String) =
        WidgetSnapshot(strings.t(titleKey), message = strings.t("common.errorModuleNoAccess"), canWrite = false)

    private suspend fun load(context: Context, name: String, titleKey: String, read: suspend (Ready) -> WidgetSnapshot): WidgetSnapshot {
        val (r, strings) = ready(context)
        val file = File(context.filesDir, "widgets/$name.json")
        if (r == null) {
            // WIDGETS-R5: signed out, nothing of the household stays on the home screen.
            file.delete()
            return WidgetSnapshot(strings.t(titleKey), message = strings.t("android.widgets.signedOut"), canWrite = false, signedIn = false)
        }
        return try {
            read(r).also { snapshot ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(ApiJson.encodeToString(WidgetSnapshot.serializer(), snapshot))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // WIDGETS-R6: the last snapshot, marked as old; else a sentence.
            runCatching { ApiJson.decodeFromString(WidgetSnapshot.serializer(), file.readText()) }.getOrNull()?.copy(stale = true, canWrite = false)
                ?: WidgetSnapshot(strings.t(titleKey), message = strings.t("android.widgets.loadFailed"), canWrite = false)
        }
    }

    /** Make every placed widget of [widget]'s kind read again. */
    suspend fun refresh(context: Context, widget: GlanceAppWidget) {
        val manager = GlanceAppWidgetManager(context)
        for (id in manager.getGlanceIds(widget.javaClass)) {
            updateAppWidgetState(context, id) { it[VERSION] = System.currentTimeMillis() }
            widget.update(context, id)
        }
    }

    /** All widgets, e.g. when the app goes to the background after changes, or on sign-out. */
    suspend fun refreshAll(context: Context) {
        refresh(context, TasksWidget())
        refresh(context, ShoppingWidget())
    }

    private var watching = false

    /**
     * WIDGETS-R1, R5: refresh the widgets when the app goes to the background (changes made in
     * the app show on the home screen) and when the member signs in or out; sign-out also deletes
     * the saved snapshots. Once per process.
     */
    fun watch(app: android.app.Application, container: AppContainer) {
        if (watching) return
        watching = true
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) container.scope.launch { runCatching { refreshAll(app) } }
            },
        )
        container.scope.launch {
            container.session.state
                .filter { it !is SessionState.Starting }
                .map { it is SessionState.SignedIn }
                .distinctUntilChanged()
                .drop(1)
                .collect { signedIn ->
                    if (!signedIn) forget(app)
                    runCatching { refreshAll(app) }
                }
        }
    }

    /** WIDGETS-R5: forget the saved snapshots (sign-out). */
    fun forget(context: Context) {
        File(context.filesDir, "widgets").deleteRecursively()
    }
}
