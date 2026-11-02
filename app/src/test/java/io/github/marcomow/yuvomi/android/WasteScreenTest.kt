package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/waste.md, WASTE-A11 to A15, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class WasteScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private var types = """[{"id":1,"name":"General waste","icon":"trash-2","color":"#78808C","sort_order":0,"archived":0},
        {"id":2,"name":"Paper","icon":"newspaper","color":"#D97706","sort_order":1,"archived":0}]"""
    private lateinit var strings: Strings
    private val today = LocalDate.now(ZoneOffset.UTC)

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":{"admin":true,"modules":$modules},"householdSize":3,"csrfToken":"t"}"""

    private fun occ(type: Int, name: String, day: LocalDate) =
        """{"key":"$type:$day","date_key":"$day","type_id":$type,"type_name":"$name","type_icon":"trash-2","type_color":"#78808C","moved":false,"coalesced":false,
        "origins":[{"kind":"schedule","schedule_id":$type,"original_date":null,"moved":false}]}"""

    private fun occurrences() = if (types == "[]") "[]" else
        "[${occ(1, "General waste", today)},${occ(2, "Paper", today.plusDays(3))},${occ(1, "General waste", today.plusDays(7))}]"

    @Before fun setUp() {
        strings = StringsLoader.load(ApplicationProvider.getApplicationContext<YuvomiApp>().assets, "en")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val line = "${request.method} $path"
                if (request.method == "GET") reads += "$line?${request.url.encodedQuery.orEmpty()}"
                else writes += "$line ${request.body?.utf8().orEmpty()}"
                request.headers["Idempotency-Key"]?.let { keys += "$line $it" }
                return when {
                    line == "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    line == "POST /api/v1/auth/login" -> json(payload(), cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    line == "GET /api/v1/auth/me" -> json(payload())
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC"}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/waste/types" -> json("""{"data":$types}""")
                    line == "GET /api/v1/waste/schedules" -> json(if (types == "[]") """{"data":[]}""" else """{"data":[
                        {"id":1,"type_id":1,"recurrence_kind":"weekly","anchor_date":"$today","interval":1,"weekdays":"MO,TH","month_day":null,"valid_until":null,"active":1},
                        {"id":2,"type_id":2,"recurrence_kind":"monthly_ordinal_weekday","anchor_date":"$today","interval":1,"weekdays":"TU","month_day":2,"valid_until":null,"active":1}]}""")
                    line == "GET /api/v1/waste/occurrences" -> json("""{"data":${occurrences()}}""")
                    line == "GET /api/v1/waste/sources" -> json(if (types == "[]") """{"data":[]}""" else """{"data":[
                        {"id":5,"kind":"url","name":"City calendar","coverage_start":"$today","coverage_end":"${today.plusDays(60)}","needs_mapping":1,"needs_refresh":0}]}""")
                    line == "GET /api/v1/waste/feed" -> json("""{"data":{"token":"abc","url":"https://yuvomi.example/feed/waste/abc.ics","type_ids":null}}""")
                    line == "POST /api/v1/waste/types" -> json("""{"data":{"id":9,"name":"Recycling","icon":"recycle","color":"#3B82F6","sort_order":0,"archived":0}}""", 201)
                    request.method == "PUT" && path.startsWith("/api/v1/waste/schedules/1/overrides/") -> json("""{"data":{"original_date":"$today","replacement_date":null}}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun open(expect: String) {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.waste")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.waste")).onFirst().performClick()
        waitText(expect)
    }

    private fun waitText(text: String) = try {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
        throw e
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    /** WASTE-A11: the upcoming pickups, one per type with the rest folded, then the types and sources. */
    @Test fun `the page shows the next pickups, the schedules and the sources`() {
        open(strings.t("waste.upcomingSectionTitle"))
        compose.onNodeWithText(strings.t("waste.putOutToday"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(strings.t("waste.upcomingShowMore", "count" to 1)).assertExists()
        assertTrue(reads.any { it.startsWith("GET /api/v1/waste/occurrences?") && "from=$today" in it && "to=${today.plusDays(90)}" in it })
        assertTrue(reads.any { it.startsWith("GET /api/v1/waste/types?") && "include_archived=1" in it })
        scrollTo(strings.t("waste.summaryWeekly", "days" to "Mon, Thu"))
        scrollTo(strings.t("waste.summaryOrdinalWeekday", "ordinal" to "Second", "weekday" to "Tue"))
        scrollTo("City calendar")
        compose.onNodeWithText(strings.t("waste.sourceNeedsMappingBadge"), useUnmergedTree = true).assertExists()
    }

    /** WASTE-A12: skipping a series pickup files an override without replacement. */
    @Test fun `skipping a pickup puts an empty override`() {
        open(strings.t("waste.upcomingSectionTitle"))
        compose.onAllNodesWithContentDescription("General waste, ", substring = true).onFirst().performClick()
        compose.onNodeWithText(strings.t("waste.skipAction")).performClick()
        waitText(strings.t("waste.skipConfirm"))
        compose.onNodeWithText(strings.t("waste.skipAction")).performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("PUT /api/v1/waste/schedules/1/overrides/$today") } }
        assertTrue(writes.single { it.startsWith("PUT /api/v1/waste/schedules/1/overrides/") }.endsWith("""{"replacement_date":null}"""))
    }

    /** WASTE-A13: read-only members keep every value, but no FAB, no row menus and only the feed in the tools. */
    @Test fun `a read-only member sees no write affordance`() {
        modules = """{"waste":"read"}"""
        open(strings.t("waste.upcomingSectionTitle"))
        compose.onNodeWithContentDescription(strings.t("waste.addSchedule")).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithContentDescription("General waste, ", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("waste.moreActions")).performClick()
        compose.onNodeWithText(strings.t("waste.addType")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("settings.wasteFeedTitle")).assertExists()
        assertTrue(writes.none { "/waste/" in it })
    }

    /** WASTE-A14: without types the page onboards: a preset creates its type in one tap. */
    @Test fun `a preset creates a type`() {
        types = "[]"
        open(strings.t("waste.emptyTypesTitle"))
        compose.onNodeWithText(strings.t("waste.upcomingSectionTitle")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("waste.presetRecycling")).performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("POST /api/v1/waste/types") } }
        val body = writes.single { it.startsWith("POST /api/v1/waste/types") }
        assertTrue(body, "\"name\":\"Recycling\"" in body && "\"icon\":\"recycle\"" in body && "\"color\":\"#3B82F6\"" in body)
        // The dispatcher records the key right after the body: wait, do not race it.
        compose.waitUntil(10_000) { keys.any { it.startsWith("POST /api/v1/waste/types") } }
    }

    /** WASTE-A15: the feed sheet shows the member's own link with copy. */
    @Test fun `the feed link is shown with copy`() {
        open(strings.t("waste.upcomingSectionTitle"))
        compose.onNodeWithContentDescription(strings.t("waste.moreActions")).performClick()
        compose.onNodeWithText(strings.t("settings.wasteFeedTitle")).performClick()
        waitText("https://yuvomi.example/feed/waste/abc.ics")
        compose.onNodeWithText(strings.t("settings.wasteFeedCopy")).performClick()
        waitText(strings.t("settings.wasteFeedCopied"))
    }
}
