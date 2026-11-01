package io.github.marcomow.yuvomi.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.YuvomiTheme
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.core.session.SessionManager
import io.github.marcomow.yuvomi.core.session.SettingsStore
import io.github.marcomow.yuvomi.feature.signin.SignInScreen
import io.github.marcomow.yuvomi.feature.signin.SignInViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** SIGNIN-A3 on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class)
class SignInScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.close()

    @Test fun `a server that is not set up shows the setup message and the browser action`() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"app_name":"Yuvomi","setup_required":true}""").build())
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val session = SessionManager(
            SettingsStore(app, PlainSecretBox()),
            ResponseCache(File(app.cacheDir, "ui-${System.nanoTime()}")),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val strings = StringsLoader.load(app.assets, "en")
        val vm = SignInViewModel(session, "http://localhost:${server.port}", null, null)
        compose.setContent {
            YuvomiTheme(Tokens.load(app.assets)) {
                CompositionLocalProvider(LocalStrings provides strings) { SignInScreen(vm) }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(strings.t("android.signin.setupRequired")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.t("android.browser.open")).assertExists()
        compose.onNodeWithText(strings.t("android.signin.unofficial")).assertExists()
    }
}
