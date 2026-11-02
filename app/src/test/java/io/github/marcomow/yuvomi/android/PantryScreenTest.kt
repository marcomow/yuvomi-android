package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.pantry.PantryCategory
import io.github.marcomow.yuvomi.feature.pantry.PantryEditorViewModel
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/pantry.md, PANTRY-A11 to A15, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class PantryScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private var lists = """[{"id":1,"name":"Weekly shop"},{"id":2,"name":"Market"}]"""
    private val soon = LocalDate.now().plusDays(2).toString()
    private lateinit var strings: Strings
    private lateinit var container: AppContainer

    private fun json(body: String, cookie: String? = null) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":{"admin":true,"modules":$modules},"householdSize":3,"csrfToken":"t"}"""

    private val stock = """{"data":[
        {"id":1,"name":"Passata","quantity":1,"unit":"can","location_id":1,"location_name":"Vorratsschrank","category":"Sonstiges","min_quantity":3,"product_id":1},
        {"id":2,"name":"Milk","quantity":2,"unit":"l","location_id":2,"location_name":"Kühlschrank","category":"Milchprodukte","expires_on":"$soon","product_id":2},
        {"id":3,"name":"Milk","quantity":1,"unit":"l","location_id":4,"location_name":"Keller","category":"Milchprodukte","product_id":2}],
        "products":[{"id":1,"name":"Passata","unit":"can","category":"Sonstiges","batch_count":1},
          {"id":2,"name":"Milk","unit":"l","category":"Milchprodukte","batch_count":2},
          {"id":3,"name":"Yeast","unit":"pkg","category":"Backwaren","batch_count":0}],
        "locations":[{"id":1,"name":"Vorratsschrank","sort_order":0},{"id":2,"name":"Kühlschrank","sort_order":1},{"id":4,"name":"Keller","sort_order":3}],
        "categories":[{"id":1,"name":"Milchprodukte","sort_order":0},{"id":2,"name":"Sonstiges","sort_order":1},{"id":3,"name":"Backwaren","sort_order":2}]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$line ${request.body?.utf8().orEmpty()}"
                request.headers["Idempotency-Key"]?.let { keys += "$line $it" }
                return when {
                    line == "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    line == "POST /api/v1/auth/login" -> json(payload(), "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    line == "GET /api/v1/auth/me" -> json(payload())
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/pantry" -> json(stock)
                    line.startsWith("PATCH /api/v1/pantry/1") -> json("""{"data":{"id":1,"name":"Passata","quantity":4,"unit":"can","product_id":1}}""")
                    line == "POST /api/v1/pantry" -> MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
                        .body("""{"data":{"id":9,"name":"Oats","quantity":2,"unit":"kg"}}""").build()
                    line.startsWith("DELETE /api/v1/pantry/") -> MockResponse.Builder().code(204).build()
                    line == "GET /api/v1/shopping" -> json("""{"data":$lists}""")
                    line == "POST /api/v1/shopping/1/import-pantry" -> json("""{"data":{"added":1,"skipped":0,"added_ids":[77]}}""")
                    line == "POST /api/v1/shopping/items/undo-transfer" -> json("""{"data":{"removed":1}}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openPantry() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.pantry")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.pantry")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Passata").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    /** Lets delays on the main thread (the step debounce, a snackbar's time) run out. */
    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    @Test fun `the stock shows products in sections with totals and badges`() {
        openPantry()
        compose.onNodeWithText(strings.t("shopping.catDairy")).assertExists()
        compose.onNodeWithText(strings.t("shopping.catBakery")).assertExists()
        compose.onNodeWithText("3 l in all").assertExists()
        compose.onNodeWithText(strings.t("pantry.productPlaces", "count" to 2)).assertExists()
        compose.onNodeWithText("1 can").assertExists()
        compose.onAllNodesWithText(strings.t("pantry.badgeLow")).onFirst().assertExists()
        // The yeast outlived its last batch: out of stock with 0 in its unit.
        compose.onNodeWithText("Yeast").assertExists()
        compose.onNodeWithText("0 packs").assertExists()
        // A product row opens its batches, titled by place.
        compose.onNodeWithText("Milk").performClick()
        compose.onNodeWithText(strings.t("pantry.locFridge")).assertExists()
        // The batch and, collapsed or not, its product say the most urgent expiry.
        assertEquals(2, compose.onAllNodesWithText(strings.t("pantry.badgeExpiresDays", "count" to 2)).fetchSemanticsNodes().size)
        // The "Expiring soon" chip lists the batch flat.
        compose.onNodeWithText("${strings.t("pantry.filterSoon")}  1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Passata").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Milk").assertExists()
    }

    /** PANTRY-A11. */
    @Test fun `three taps on plus make one PATCH with the final quantity`() {
        openPantry()
        val plus = strings.t("pantry.increase") + ": Passata"
        repeat(3) { compose.onNodeWithContentDescription(plus).performClick() }
        compose.onNodeWithText("4 cans").assertExists()
        assertTrue(writes.none { it.startsWith("PATCH") })
        advance(500)
        waitForWrite("PATCH /api/v1/pantry/1")
        compose.waitForIdle()
        val patches = writes.filter { it.startsWith("PATCH") }
        assertEquals(1, patches.size)
        assertEquals("""{"quantity":4.0}""", patches.single().substringAfter("/api/v1/pantry/1 "))
    }

    /** PANTRY-A12. */
    @Test fun `a read-only member sees no FAB and no stepper, and a tap shows the details`() {
        modules = """{"pantry":"read"}"""
        openPantry()
        compose.onNodeWithContentDescription(strings.t("pantry.addItem")).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("pantry.increase") + ": Passata").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("pantry.manageLocations")).assertDoesNotExist()
        // Shopping stays writable, so the cart stays (`mayTransferPantryToShopping`).
        compose.onNodeWithContentDescription(strings.t("common.toShoppingList") + ": Passata").assertExists()
        compose.onNodeWithText("Passata").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("pantry.minQuantityLabel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("3 cans").assertExists()
        assertTrue(writes.none { it.startsWith("PATCH") || it.startsWith("DELETE") })
    }

    /** PANTRY-A13. */
    @Test fun `create sends the web app's payload with an idempotency key`() {
        openPantry()
        val editor = PantryEditorViewModel(container, null, listOf(PantryCategory(2, "Sonstiges", 1)))
        editor.edit { it.copy(name = "Oats", quantity = "2", unit = "kg", locationId = 1, minQuantity = "1") }
        assertNull(runBlocking { editor.save() })
        val post = writes.single { it.startsWith("POST /api/v1/pantry ") }.substringAfter("POST /api/v1/pantry ")
        assertEquals(
            """{"name":"Oats","quantity":2.0,"unit":"kg","location_id":1,"category":"Sonstiges","expires_on":null,"min_quantity":1.0,"notes":null}""",
            post,
        )
        assertNotNull(keys.firstOrNull { it.startsWith("POST /api/v1/pantry ") })
        assertTrue(editor.state.value.saved)
    }

    /** PANTRY-A14. */
    @Test fun `a deleted batch goes out after the snackbar, undo keeps it`() {
        openPantry()
        val menu = strings.t("common.moreActionsNamed", "name" to "Yeast")
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Yeast").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Yeast").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(writes.none { it.startsWith("DELETE") })

        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(writes.none { it.startsWith("DELETE") })
        advance(11_000)
        waitForWrite("DELETE /api/v1/pantry/products/3")
        assertEquals(1, writes.count { it.startsWith("DELETE") })
    }

    /** PANTRY-A15. */
    @Test fun `the cart asks which list, names it, and undo sends the added ids`() {
        openPantry()
        compose.onNodeWithContentDescription(strings.t("common.toShoppingList") + ": Passata").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("common.toShoppingListWhich")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Weekly shop").performClick()
        waitForWrite("POST /api/v1/shopping/1/import-pantry")
        assertEquals(
            """{"items":[{"pantry_item_id":1,"quantity":"2 cans"}]}""",
            writes.single { it.startsWith("POST /api/v1/shopping/1/import-pantry") }.substringAfter("import-pantry "),
        )
        val message = strings.t("pantry.toShoppingDone", "count" to 1, "list" to "Weekly shop")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        waitForWrite("POST /api/v1/shopping/items/undo-transfer")
        assertTrue(writes.single { it.startsWith("POST /api/v1/shopping/items/undo-transfer") }.endsWith("""{"ids":[77]}"""))
    }
}
