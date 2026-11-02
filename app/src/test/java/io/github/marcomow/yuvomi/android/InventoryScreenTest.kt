package io.github.marcomow.yuvomi.android

import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.inventory.InventoryHost
import io.github.marcomow.yuvomi.feature.inventory.ItemDetailViewModel
import io.github.marcomow.yuvomi.feature.inventory.ItemEditorViewModel
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/inventory.md, INV-A11 to A15, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class InventoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private lateinit var strings: Strings
    private lateinit var container: AppContainer
    private val soon = LocalDate.now().plusDays(5).toString()
    private val overdue = LocalDate.now().minusDays(3).toString()

    private fun json(body: String, cookie: String? = null, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","role":"admin"},
        "permissions":{"admin":false,"modules":$modules},"householdSize":3,"csrfToken":"t"}"""

    private val car = """{"id":1,"name":"Family car","category":"vehicles","category_label_key":"inventory.categoryVehicles","location_id":3,
        "location_path":"Garage · Shelf","purchase_price":20000,"currency":"EUR","condition":"good","status":"active","created_by":1,
        "odometer":15000,"odometer_unit":"km","odometer_on":"2026-10-01",
        "tracked_dates":[{"id":4,"item_id":1,"label":"TÜV","date":"$overdue","reminder_offset_days":30,"interval_months":24,"interval_distance":null}],
        "linked_entries":[],"attachments":[]}"""
    private val items = """{"data":[$car,
        {"id":2,"name":"Laptop","category":"electronics","category_label_key":"inventory.categoryElectronics","location_id":2,"location_path":"Office",
         "purchase_price":1200,"currency":"EUR","condition":"good","status":"active","created_by":1,"tracked_dates":[{"id":5,"item_id":2,"label":"Battery","date":"$soon"}],
         "linked_entries":[],"attachments":[{"id":1,"document_id":7,"name":"Invoice"}]},
        {"id":3,"name":"Old phone","category":"electronics","category_label_key":"inventory.categoryElectronics","status":"sold","condition":"poor","created_by":2,
         "tracked_dates":[],"linked_entries":[],"attachments":[]}]}"""

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
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","currency":"EUR","region":"de-DE"}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/inventory/items" -> json(items)
                    line == "GET /api/v1/inventory/items/1" -> json("""{"data":$car}""")
                    line == "GET /api/v1/inventory/items/1/history" -> json("""{"data":{"timeline":[
                        {"type":"service_log","id":1,"date":"2026-05-01","label":"Oil change","odometer":12000,"vendor":"Garage Kurz","note":null}],"total":0}}""")
                    line == "GET /api/v1/inventory/categories" -> json("""{"data":[
                        {"key":"electronics","label_key":"inventory.categoryElectronics","sort_order":0,"tracks_odometer":0},
                        {"key":"vehicles","label_key":"inventory.categoryVehicles","sort_order":1,"tracks_odometer":1},
                        {"key":"other","label_key":"inventory.categoryOther","sort_order":2,"tracks_odometer":0}]}""")
                    line == "GET /api/v1/inventory/locations" -> json("""{"data":[
                        {"id":1,"name":"Garage","parent_id":null,"subcategories":[{"id":3,"name":"Shelf","parent_id":1}]},
                        {"id":2,"name":"Office","parent_id":null,"subcategories":[]}]}""")
                    line == "POST /api/v1/inventory/items" -> json("""{"data":{"id":9,"name":"Drill","category":"other"}}""", code = 201)
                    line == "POST /api/v1/inventory/items/1/dates/4/complete" -> json("""{"data":$car}""", code = 201)
                    line.startsWith("DELETE /api/v1/inventory/items/") -> MockResponse.Builder().code(204).build()
                    else -> json("""{"error":"Not found"}""", code = 404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun signIn() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
    }

    private fun openInventory() {
        signIn()
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.inventory")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.inventory")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Family car").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun pump(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out; writes=$writes")
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun host(write: Boolean = true) = object : InventoryHost {
        override val strings = this@InventoryScreenTest.strings
        override val userId = 1
        override val canWrite = write
        override val editOthers = false
        override val showsPrices = true
        override val showsBudget = true
        override val showsDocuments = true
        override val uploadsDocuments = true
        override fun today(): LocalDate = LocalDate.now()
        override suspend fun notify(message: String, action: String?) = false
    }

    /** INV-A11. */
    @Test fun `the list groups by category, badges deadlines and filters by attention and category`() {
        openInventory()
        compose.onNodeWithText(strings.t("inventory.categoryVehicles").uppercase()).assertExists()
        compose.onNodeWithText(strings.t("inventory.categoryElectronics").uppercase()).assertExists()
        compose.onNodeWithText(strings.t("inventory.deadlineChipOverdue", "label" to "TÜV")).assertExists()
        compose.onNodeWithText(strings.t("inventory.deadlineChipInDays", "label" to "Battery", "count" to 5)).assertExists()
        compose.onNodeWithText(strings.t("inventory.statusSold")).assertExists()
        compose.onNodeWithText("Garage · Shelf").assertExists()
        compose.onNodeWithContentDescription(strings.t("inventory.hasAttachmentsLabel")).assertExists()
        // The attention chip keeps the two items with a deadline.
        compose.onNodeWithText("${strings.t("inventory.metricAttentionLabel")}  2").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Old phone").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("${strings.t("inventory.metricAttentionLabel")}  2").performClick()
        // In a category the items group by place.
        compose.onNodeWithText("${strings.t("inventory.categoryVehicles")}  1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Laptop").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("GARAGE").assertExists()
    }

    /** INV-A12. */
    @Test fun `the read view shows deadlines, odometer and history, and Done for a due date`() {
        openInventory()
        compose.onNodeWithText("Family car").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Oil change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("TÜV").performScrollTo().assertExists()
        compose.onNodeWithText(strings.t("inventory.markDoneAction")).performScrollTo().assertExists()
        compose.onNodeWithText(strings.t("inventory.odometerChartTitle")).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(strings.t("common.edit")).assertExists()
    }

    /** INV-A13. */
    @Test fun `a read-only member sees no FAB, no tools, no edit and no Done`() {
        modules = """{"inventory":"read"}"""
        openInventory()
        compose.onNodeWithContentDescription(strings.t("inventory.addItem")).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.t("common.moreActions")).assertDoesNotExist()
        compose.onNodeWithText("Family car").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Oil change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(strings.t("common.edit")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("inventory.markDoneAction")).assertDoesNotExist()
        // `trackedDateFormOnlyValues`: the reminder and the repeat are shown instead.
        compose.onNodeWithText(strings.t("inventory.trackedDateRemindBeforeLabel") + ": 30", substring = true).performScrollTo().assertExists()
    }

    /** INV-A14. */
    @Test fun `a deleted item goes out after the snackbar, undo keeps it`() {
        openInventory()
        compose.onNodeWithText("Family car").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Oil change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(strings.t("common.moreActions")).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.onAllNodesWithText(strings.t("common.delete")).onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Family car").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(writes.none { it.startsWith("DELETE") })

        compose.onNodeWithText("Family car").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Oil change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(strings.t("common.moreActions")).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.onAllNodesWithText(strings.t("common.delete")).onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        advance(11_000)
        compose.waitUntil(10_000) { writes.any { it.startsWith("DELETE /api/v1/inventory/items/1") } }
    }

    /** INV-A15: create and the completion sheet send the web app's payloads with idempotency keys. */
    @Test fun `create and Done send the web app's payloads`() {
        signIn()
        val editor = ItemEditorViewModel(container, host(), null)
        pump { !editor.state.value.loading }
        editor.edit { it.copy(name = "Drill", price = "49,90", brand = "Bosch", dates = listOf(io.github.marcomow.yuvomi.feature.inventory.InventoryRules.DateRow("Service", "2027-03-01"))) }
        editor.save()
        pump { keys.any { it.startsWith("POST /api/v1/inventory/items ") } }
        val post = writes.single { it.startsWith("POST /api/v1/inventory/items ") }.substringAfter("POST /api/v1/inventory/items ")
        assertEquals(
            """{"name":"Drill","category":"other","location_id":null,"purchase_date":null,"purchase_price":49.9,"status":"active","brand":"Bosch",""" +
                """"model":null,"serial_number":null,"vendor":null,"account_username":null,"warranty_months":null,"condition":"good","notes":null,""" +
                """"tracked_dates":[{"label":"Service","date":"2027-03-01","reminder_offset_days":30,"interval_months":null,"interval_distance":null}],""" +
                """"photo_data":null,"odometer":null,"odometer_unit":null,"odometer_on":null,"attachment_document_ids":[]}""",
            post,
        )
        assertNotNull(keys.firstOrNull { it.startsWith("POST /api/v1/inventory/items ") })

        val detail = ItemDetailViewModel(container, host(), 1)
        pump { detail.state.value.item != null && detail.state.value.categories.isNotEmpty() }
        detail.startCompletion(detail.state.value.item!!.trackedDates.single())
        detail.complete("2026-11-02", "16000", "Kurz", "")
        pump { keys.any { it.startsWith("POST /api/v1/inventory/items/1/dates/4/complete") } }
        assertEquals(
            """POST /api/v1/inventory/items/1/dates/4/complete {"performed_on":"2026-11-02","odometer":16000,"vendor":"Kurz","note":null}""",
            writes.single { it.contains("/complete") },
        )
        assertNotNull(keys.firstOrNull { it.startsWith("POST /api/v1/inventory/items/1/dates/4/complete") })
    }
}
