package io.github.marcomow.yuvomi.android

import android.os.Looper
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
import androidx.compose.ui.test.performScrollToIndex
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

/** docs/spec/health.md, HEALTH-A13 to A17, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class HealthScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private var careFor = "[]"
    private lateinit var strings: Strings
    private lateinit var container: AppContainer
    private val today = LocalDate.now().toString()

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":{"admin":true,"modules":$modules},"householdSize":3,"csrfToken":"t"}"""

    private val members = """{"data":[{"id":1,"display_name":"Alex Johnson","avatar_color":"#4466aa"},
        {"id":2,"display_name":"Emma","avatar_color":"#aa4466"}]}"""

    private val meds = """{"data":[{"id":5,"user_id":1,"name":"Ibuprofen","active":1,"prn":0,"stock_qty":10,"visibility":"private",
        "schedules":[{"id":11,"medication_id":5,"time_of_day":"08:00","days_mask":null,"dose_qty":1,"active":1}],"logs":[]}]}"""

    private val vitals = """{"data":[
        {"id":21,"user_id":1,"type":"bp","value_num":120,"value_num2":80,"value_num3":64,"unit":"mmHg","measured_at":"${today}T07:30","visibility":"private"},
        {"id":22,"user_id":1,"type":"weight","value_num":72.5,"unit":"kg","measured_at":"${today}T07:00","visibility":"private"}]}"""

    @Before fun setUp() {
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
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","health_cycle_effective":true}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/family/members" -> json(members)
                    line == "GET /api/v1/health/caregivers/me" -> json("""{"data":$careFor}""")
                    line == "GET /api/v1/health/visibility-defaults" -> json("""{"data":{"defaults":{"meds":"family"}}}""")
                    line == "GET /api/v1/health/medications" -> json(meds)
                    line == "GET /api/v1/health/vitals" -> json(if (request.url.queryParameter("user_id") == "2") """{"data":[]}""" else vitals)
                    line == "GET /api/v1/health/cycle/settings" -> json("""{"data":{"cycle_length_avg":null,"track_fertility":1}}""")
                    path.startsWith("/api/v1/health/") && request.method == "GET" && path.endsWith("/summary") ->
                        json("""{"data":{"date":"$today","target":null,"totals":{"energy_kcal":0},"entryCount":0}}""")
                    line == "GET /api/v1/health/fasting/state" -> json("""{"data":{"active":null,"history":[],"canWrite":true,"acknowledged":false}}""")
                    path.startsWith("/api/v1/health/") && request.method == "GET" -> json("""{"data":[]}""")
                    line == "POST /api/v1/health/medications/5/logs" ->
                        json("""{"data":{"id":77,"medication_id":5,"schedule_id":11,"scheduled_at":"${today}T08:00","status":"taken"},"stock_deducted":1}""", 201)
                    line == "POST /api/v1/health/logs/77/undo" -> json("""{"data":{"id":77,"status":"pending"}}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openHealth() {
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
        // Health sits low in "More": scroll the list until its row is composed.
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(strings.t("nav.health")))
        compose.onAllNodesWithText(strings.t("nav.health")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("health.overview.dueToday.title")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }


    private fun waitText(text: String) = try {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
        throw e
    }

    /** Scrolls the area's list to a row that is not composed yet. */
    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    private fun healthWrites() = writes.filter { "/api/v1/health/" in it }

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    /** HEALTH-A13: the overview shows today's dose, the areas with their status, and the latest vitals. */
    @Test fun `the overview shows due doses, areas and the latest vitals`() {
        openHealth()
        waitText("Ibuprofen · 1×")
        scrollTo(strings.t("health.tabs.prevention"))
        // The meds status line names the active medication; the cycle area is on for this household.
        compose.onAllNodesWithText("Ibuprofen").onFirst().assertExists()
        scrollTo(strings.t("health.tabs.cycle"))
        // Fasting is a capability; admins have it.
        scrollTo(strings.t("health.tabs.fasting"))
        scrollTo("120/80 mmHg")
        // The plans and the dose log come in one request (`medsWithPlansPath`).
        assertTrue(reads.any { it.startsWith("GET /api/v1/health/medications?") && "include=schedules%2Clogs" in it && "user_id=1" in it })
    }

    /** HEALTH-A14: taking a dose is one POST with its key and stock deduction; undo credits what was deducted. */
    @Test fun `taking a dose books it with a key and undo gives the stock back`() {
        openHealth()
        val take = strings.t("health.meds.takeNamed", "name" to "Ibuprofen")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(take).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(take).performClick()
        waitForWrite("POST /api/v1/health/medications/5/logs")
        val body = writes.single { it.startsWith("POST /api/v1/health/medications/5/logs") }
        assertTrue(body, "\"status\":\"taken\"" in body && "\"scheduled_at\":\"${today}T08:00\"" in body && "\"schedule_id\":11" in body && "\"stock_deduct\":1.0" in body)
        assertNotNull(keys.firstOrNull { it.startsWith("POST /api/v1/health/medications/5/logs") })
        waitText(strings.t("health.meds.doseTakenNamed", "name" to "Ibuprofen"))
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        waitForWrite("POST /api/v1/health/logs/77/undo")
        assertTrue(writes.single { it.startsWith("POST /api/v1/health/logs/77/undo") }.endsWith("""{"stock_credit":1.0}"""))
    }

    /** HEALTH-A15: `health: read` keeps every value but no editing affordance. */
    @Test fun `a read-only member sees no dose actions and no capture button`() {
        modules = """{"health":"read"}"""
        openHealth()
        waitText("Ibuprofen · 1×")
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("health.meds.takeNamed", "name" to "Ibuprofen")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("health.overview.quick.title")).assertDoesNotExist()
        compose.onAllNodesWithText(strings.t("health.meds.status.pending")).onFirst().assertExists()
        assertTrue(healthWrites().isEmpty())
    }

    /** HEALTH-A16: the vitals area shows the cards and recent readings; another person loads with `user_id` and read-only. */
    @Test fun `vitals for another person load by user id and read only`() {
        openHealth()
        scrollTo(strings.t("health.tabs.vitals"))
        compose.onNodeWithText(strings.t("health.tabs.vitals")).performClick()
        waitText(strings.t("health.vitals.range.week"))
        compose.onNodeWithText("120/80").assertExists()
        scrollTo("120/80 mmHg · 64 /min")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithContentDescription(strings.t("health.vitals.personsLabel") + ": Alex Johnson · " + strings.t("health.vitals.you")).performClick()
        compose.onNodeWithText("Emma").performClick()
        waitText(strings.t("health.readOnlyBanner", "name" to "Emma"))
        waitText(strings.t("health.vitals.emptyTitle"))
        assertTrue(reads.any { it.startsWith("GET /api/v1/health/vitals?") && "user_id=2" in it })
        compose.onNodeWithText(strings.t("health.vitals.addShort"), useUnmergedTree = true).assertDoesNotExist()
    }

    /** HEALTH-A17: in a caregiver's view of the person in care, the area is writable and says for whom. */
    @Test fun `a caregiver records for the person in care`() {
        careFor = "[2]"
        openHealth()
        compose.onNodeWithContentDescription(strings.t("health.overview.personsLabel") + ": Alex Johnson · " + strings.t("health.vitals.you")).performClick()
        compose.onNodeWithText("Emma").performClick()
        waitText(strings.t("health.careBanner", "name" to "Emma"))
        compose.onNodeWithContentDescription(strings.t("health.overview.quick.title")).assertExists()
        // The choice holds for the whole module: the vitals area opens on Emma.
        scrollTo(strings.t("health.tabs.vitals"))
        compose.onNodeWithText(strings.t("health.tabs.vitals")).performClick()
        waitText(strings.t("health.vitals.addShort"))
        compose.onNodeWithText(strings.t("health.careBanner", "name" to "Emma")).assertExists()
        advance(100)
        assertEquals(0, healthWrites().size)
    }
}
