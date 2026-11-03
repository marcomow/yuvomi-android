package io.github.marcomow.yuvomi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.marcomow.yuvomi.feature.shell.AppLinks
import io.github.marcomow.yuvomi.feature.shell.Routes
import io.github.marcomow.yuvomi.feature.shell.ServerLinks
import io.github.marcomow.yuvomi.feature.shell.ServerLinks.SHARED
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import io.github.marcomow.yuvomi.feature.widgets.Widgets

class MainActivity : ComponentActivity() {

    /** The route a link opens: `yuvomi-android://tasks/{id}` (TASKS-R27), a widget row or a reminder ([AppLinks]). */
    private val deepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink.value = routeOf(intent)
        val container = (application as YuvomiApp).container
        Widgets.watch(application, container)
        io.github.marcomow.yuvomi.feature.reminders.ReminderScheduler.watch(application, container)
        setContent {
            YuvomiRoot(container, deepLink.value) { deepLink.value = null }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { deepLink.value = it }
    }

    private fun routeOf(intent: Intent?): String? {
        if (intent == null) return null
        // Share → Yuvomi (LINKS-R2): the shell maps the address against the signed-in server.
        if (intent.action == Intent.ACTION_SEND) {
            return ServerLinks.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { SHARED + it }
        }
        intent.getStringExtra(AppLinks.EXTRA_ROUTE)?.let { return AppLinks.allowed(it) }
        val data = intent.data ?: return null
        if (data.scheme != "yuvomi-android" || data.host != "tasks") return null
        return data.lastPathSegment?.toIntOrNull()?.let { Routes.task(it) }
    }
}
