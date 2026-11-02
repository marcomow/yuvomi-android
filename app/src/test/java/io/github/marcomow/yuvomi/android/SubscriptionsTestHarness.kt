package io.github.marcomow.yuvomi.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.design.YuvomiTheme
import io.github.marcomow.yuvomi.core.i18n.Formats
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitExpensesGraph
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsGraph
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * Budget's own screen is built by another package, so these tests sign in against a scripted
 * server and host the two tabs' graphs directly, inside the same shell context the app provides.
 */
object SubscriptionsTestHarness {
    fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    fun me(permissions: String) = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB","role":"admin"},
        "permissions":$permissions,"householdSize":3,"csrfToken":"t"}"""

    /** Answers the sign-in calls, or null for the test's own routes. */
    fun signIn(path: String, permissions: String): MockResponse? = when (path) {
        "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
        "POST /api/v1/auth/login" -> json(me(permissions), cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
        "GET /api/v1/auth/me" -> json(me(permissions))
        "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
        else -> null
    }

    fun open(compose: ComposeContentTestRule, server: MockWebServer, start: String): Strings {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        val strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        val session = (container.session.state.value as SessionState.SignedIn).session
        val connection = container.session.connection!!
        compose.setContent {
            val snackbar = remember { SnackbarHostState() }
            val time = remember { HouseholdTime(session.zone) }
            val shell = remember {
                ShellContext(container, session, connection, time, Formats(strings, session.zone), snackbar, openAccount = {}, openWeb = {})
            }
            YuvomiTheme(container.tokens) {
                CompositionLocalProvider(LocalStrings provides strings, LocalShell provides shell) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = start) {
                                composable("home") {}
                                SubscriptionsGraph.register(this, nav)
                                SplitExpensesGraph.register(this, nav)
                            }
                        }
                    }
                }
            }
        }
        return strings
    }
}
