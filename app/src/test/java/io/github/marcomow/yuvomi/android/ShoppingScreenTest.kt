package io.github.marcomow.yuvomi.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
import io.github.marcomow.yuvomi.feature.shopping.ShoppingScreen
import io.github.marcomow.yuvomi.feature.shopping.ShoppingViewModel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** docs/spec/shopping.md, the screen against a scripted server (SHOP-A7, SHOP-A14, SHOP-A15). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class ShoppingScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private var failTicks = false

    private fun json(body: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t").body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":$permissions,"householdSize":3,"csrfToken":"t"}"""

    private val items = """{"data":[
        {"id":11,"list_id":1,"name":"Milk","quantity":"2 l","category":"Milchprodukte","is_checked":0,"sort_order":1,"tags":["organic"]},
        {"id":12,"list_id":1,"name":"Apples","quantity":"1 kg","category":"Obst & Gemüse","is_checked":0,"sort_order":1,"tags":[]},
        {"id":13,"list_id":1,"name":"Bread","category":"Backwaren","is_checked":1,"sort_order":1,"tags":[]}],
        "list":{"id":1,"name":"Weekly Shop"},
        "categories":[{"id":1,"name":"Obst & Gemüse","sort_order":0},{"id":2,"name":"Backwaren","sort_order":1},{"id":3,"name":"Milchprodukte","sort_order":2},{"id":9,"name":"Sonstiges","sort_order":8}],
        "version":4}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$line ${request.body?.utf8().orEmpty()}"
                return when (line) {
                    "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    "POST /api/v1/auth/login" -> json(payload()).newBuilder().addHeader("Set-Cookie", "yuvomi.sid=abc; Path=/").build()
                    "GET /api/v1/auth/me" -> json(payload())
                    "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","currency":"EUR"}}""")
                    "GET /api/v1/shopping" -> json("""{"data":[{"id":1,"name":"Weekly Shop","item_total":3,"item_checked":1},{"id":2,"name":"DIY","item_total":0,"item_checked":0}]}""")
                    "GET /api/v1/shopping/categories" -> json("""{"data":[{"id":1,"name":"Obst & Gemüse","sort_order":0},{"id":2,"name":"Backwaren","sort_order":1},{"id":3,"name":"Milchprodukte","sort_order":2},{"id":9,"name":"Sonstiges","sort_order":8}]}""")
                    "GET /api/v1/shopping/stores" -> json("""{"data":[{"id":1,"name":"Corner shop"}]}""")
                    "GET /api/v1/shopping/versions" -> json("""{"data":[{"list_id":1,"version":4},{"list_id":2,"version":1}]}""")
                    "GET /api/v1/shopping/1/items" -> json(items)
                    "PATCH /api/v1/shopping/items/11" ->
                        if (failTicks) MockResponse.Builder().code(500).body("""{"error":"x","code":500}""").headersDelay(400, TimeUnit.MILLISECONDS).build()
                        else json("""{"data":{"id":11,"list_id":1,"name":"Milk","quantity":"2 l","category":"Milchprodukte","is_checked":1,"sort_order":1},
                            "list_change":{"list_id":1,"before":4,"after":5}}""").newBuilder().headersDelay(400, TimeUnit.MILLISECONDS).build()
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun signIn(): Pair<AppContainer, Strings> {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        val strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        return container to strings
    }

    private fun show(container: AppContainer, strings: Strings) {
        val state = container.session.state.value as SessionState.SignedIn
        val session = state.session
        val connection = container.session.connection!!
        val snackbar = SnackbarHostState()
        compose.setContent {
            YuvomiTheme(container.tokens) {
                val shell = ShellContext(
                    container, session, connection, HouseholdTime(session.zone), Formats(strings, session.zone), snackbar,
                    openAccount = {}, openWeb = {},
                )
                CompositionLocalProvider(LocalStrings provides strings, LocalShell provides shell) {
                    Box(Modifier.fillMaxSize()) {
                        ShoppingScreen(requestedList = null)
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
            throw e
        }
    }

    private fun waitForDescription(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
            throw e
        }
    }

    @Test fun `SHOP-A7 ticking shows at once, writes is_checked, and rolls back when the write fails`() {
        val (container, strings) = signIn()
        show(container, strings)
        waitForText("Milk")
        // Categories in their order, the checked item in the closed "Checked off" section.
        compose.onNodeWithText(strings.t("shopping.catFruitVeg")).assertExists()
        compose.onNodeWithText(strings.t("shopping.tickedSection")).assertExists()
        compose.onAllNodesWithText("Bread").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText("2 l · organic").assertExists()

        // Optimistic: the row says "uncheck" before the server has answered.
        compose.onNodeWithContentDescription(strings.t("shopping.markDoneLabel", "name" to "Milk")).performClick()
        compose.onNodeWithContentDescription(strings.t("shopping.markUndoneLabel", "name" to "Milk")).assertExists()
        compose.waitUntil(10_000) { writes.any { it.startsWith("PATCH /api/v1/shopping/items/11") } }
        assertTrue(writes.first { it.startsWith("PATCH /api/v1/shopping/items/11") }.contains("\"is_checked\":1"))

        // The list menu holds the web app's tools.
        compose.onNodeWithContentDescription(strings.t("shopping.listActionsLabel", "name" to "Weekly Shop")).performClick()
        compose.onNodeWithText(strings.t("shopping.duplicateListLabel")).assertExists()
        compose.onNodeWithText(strings.t("shopping.manageStores")).assertExists()
    }

    @Test fun `SHOP-A7 a failing tick puts the row back and names it`() {
        failTicks = true
        val (container, strings) = signIn()
        show(container, strings)
        waitForText("Milk")
        compose.onNodeWithContentDescription(strings.t("shopping.markDoneLabel", "name" to "Milk")).performClick()
        compose.onNodeWithContentDescription(strings.t("shopping.markUndoneLabel", "name" to "Milk")).assertExists()
        waitForText(strings.t("common.errorCheckRolledBack", "name" to "Milk"))
        compose.onNodeWithContentDescription(strings.t("shopping.markDoneLabel", "name" to "Milk")).assertExists()
    }

    @Test fun `SHOP-A14 a read-only member gets no FAB, no list menu and static check marks`() {
        permissions = """{"admin":false,"modules":{"shopping":"read"}}"""
        val (container, strings) = signIn()
        show(container, strings)
        waitForText("Milk")
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("shopping.addItemLabel")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("shopping.listActionsLabel", "name" to "Weekly Shop")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("shopping.markDoneLabel", "name" to "Milk")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Milk: ${strings.t("shopping.itemStateOpen")}").assertExists()
    }

    @Test fun `SHOP-A15 without a network the cached list shows with the offline banner`() {
        val (container, strings) = signIn()
        container.cache.write(ShoppingViewModel.CACHE_LISTS, """{"data":[{"id":1,"name":"Weekly Shop","item_total":3,"item_checked":1}]}""")
        container.cache.write("shopping-items-1", items)
        server.close()
        show(container, strings)
        waitForText("Milk")
        waitForText("Offline - as of:", substring = true)
        // Ticking stays offered (it rolls back by itself); adding does not.
        compose.onNodeWithContentDescription(strings.t("shopping.markDoneLabel", "name" to "Milk")).assertExists()
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("shopping.addItemLabel")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the FAB opens the quick add with the default category`() {
        val (container, strings) = signIn()
        show(container, strings)
        waitForText("Milk")
        waitForDescription(strings.t("shopping.addItemLabel"))
        compose.onNodeWithContentDescription(strings.t("shopping.addItemLabel")).performClick()
        waitForText(strings.t("shopping.itemNameLabel"))
        compose.onNodeWithText(strings.t("shopping.catMisc")).assertExists()
    }
}
