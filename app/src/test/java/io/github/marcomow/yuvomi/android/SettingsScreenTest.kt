package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/settings.md, SET-A13 to A17, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private var admin = true
    private var prefs = """{"timezone_effective":"Europe/Berlin","timezone":null,"currency":"EUR","date_format":"dmy","time_format":"24h",
        "region":"de-DE","language":null,"language_auto":"de","disabled_modules":[],"hidden_modules":[],"module_order":[],"mobile_nav_order":[]}"""
    private lateinit var strings: Strings

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val user get() = """{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#3B82F6","role":"${if (admin) "admin" else "member"}",
        "email":"alex@example.com","phone":null,"birth_date":null}"""
    private val payload get() = """{"user":$user,"permissions":{"admin":$admin,"modules":{}},"householdSize":3,"csrfToken":"t"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$line ${request.body?.utf8().orEmpty()}"
                return when (line) {
                    "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    "GET /api/v1/auth/me" -> json(payload)
                    "GET /api/v1/preferences" -> json("""{"data":$prefs}""")
                    "PUT /api/v1/preferences" -> json("""{"data":${request.body?.utf8()}}""")
                    "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    "GET /api/v1/auth/2fa" -> json("""{"data":{"enabled":false,"pending":false,"recovery_remaining":0,"required":false}}""")
                    "GET /api/v1/auth/oidc/link" -> json("""{"enabled":false,"linked":false}""")
                    "PATCH /api/v1/auth/me/profile" -> json("""{"user":$user}""")
                    "PATCH /api/v1/auth/me/password" -> json("""{"error":"Current password is incorrect.","code":400,"reason":"wrong_password"}""", 400)
                    "GET /api/v1/modules" -> json("""{"data":[]}""")
                    else -> json("""{"error":"Not found"}""", code = 404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openSettings() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.more")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.more")).onFirst().performClick()
        // "More" is a lazy list: Settings, its last native entry, is composed once scrolled to.
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(strings.t("nav.settings")))
        compose.onAllNodesWithText(strings.t("nav.settings")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("settings.pageAccount")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun open(labelKey: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(strings.t(labelKey)))
        // The sheet's row, after a domain title of the same name ("Account").
        compose.onAllNodesWithText(strings.t(labelKey)).onLast().performClick()
    }

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    /** SET-A13. */
    @Test fun `an admin sees the household domain and a member does not`() {
        openSettings()
        compose.onNodeWithText(strings.t("settings.domainHousehold")).assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(strings.t("settings.pageFamilyRoles")))
    }

    @Test fun `a member sees no household sheets`() {
        admin = false
        openSettings()
        compose.onNodeWithText(strings.t("settings.domainHousehold")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("settings.pageFamilyRoles")).assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(strings.t("settings.pageNavigation")))
    }

    /** SET-A14: a date format choice is one PUT with that key alone. */
    @Test fun `the date format saves through PUT preferences`() {
        admin = false
        openSettings()
        open("settings.pageAppearance")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("settings.dateFormatLabel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("settings.dateFormatLabel")).performScrollTo().performClick()
        compose.onNodeWithText("YYYY-MM-DD").performClick()
        waitForWrite("PUT /api/v1/preferences")
        assertEquals("""PUT /api/v1/preferences {"date_format":"ymd"}""", writes.single { it.startsWith("PUT") })
        // A member reads the household's zone but cannot change it.
        compose.onNodeWithText(strings.t("settings.timezoneLabel")).assertDoesNotExist()
    }

    /** SET-A15: the profile form sends the web app's body; a wrong current password stays at its field. */
    @Test fun `the profile and the password save as the web app does`() {
        openSettings()
        open("settings.pageAccount")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("@alex").fetchSemanticsNodes().isNotEmpty() }
        // The password first: the profile's confirmation snackbar would cover the button below.
        compose.onNode(hasSetTextAction() and hasText(strings.t("settings.currentPasswordLabel"))).performScrollTo().performTextReplacement("wrong")
        compose.onNode(hasSetTextAction() and hasText(strings.t("settings.newPasswordLabel"))).performTextReplacement("new password")
        compose.onNode(hasSetTextAction() and hasText(strings.t("settings.confirmPasswordLabel"))).performTextReplacement("new password")
        compose.onNodeWithText(strings.t("settings.savePassword")).performScrollTo().performClick()
        waitForWrite("PATCH /api/v1/auth/me/password")
        assertTrue(writes.single { it.startsWith("PATCH /api/v1/auth/me/password") }.endsWith("""{"current_password":"wrong","new_password":"new password"}"""))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("settings.currentPasswordWrong")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasSetTextAction() and hasText("Alex Johnson")).performTextReplacement("Alex J.")
        compose.onAllNodesWithText(strings.t("common.save")).onFirst().performScrollTo().performClick()
        waitForWrite("PATCH /api/v1/auth/me/profile")
        val body = writes.single { it.startsWith("PATCH /api/v1/auth/me/profile") }.substringAfter("profile ")
        assertEquals(
            """{"display_name":"Alex J.","avatar_color":"#3B82F6","avatar_data":null,"phone":null,"email":"alex@example.com","birth_date":null,"birth_year_unknown":false}""",
            body,
        )
    }

    /** SET-A16: switching a module off writes the household's whole list. */
    @Test fun `active modules write disabled_modules`() {
        openSettings()
        open("settings.pageActiveModules")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.notes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("nav.notes")).performScrollTo().performClick()
        waitForWrite("PUT /api/v1/preferences")
        assertEquals("""PUT /api/v1/preferences {"disabled_modules":["notes"]}""", writes.single { it.startsWith("PUT") })
    }

    /** SET-A17: a section the app does not have opens the web app, named and marked. */
    @Test fun `a browser-only section names itself and its way out`() {
        openSettings()
        open("settings.pageDisplays")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("settings.pageDisplaysDescription")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("android.browser.opensInBrowser")).fetchSemanticsNodes().isNotEmpty())
    }
}
