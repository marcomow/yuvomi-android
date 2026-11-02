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
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/housekeeping.md, HOUSE-A11 to A15, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class HousekeepingScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private var admin = true
    private var capabilities = "{}"
    private var amounts = true
    private lateinit var strings: Strings
    private val today = LocalDate.now().toString()
    private val month = today.take(7)

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":{"admin":$admin,"modules":$modules,"capabilities":$capabilities},"householdSize":3,"csrfToken":"t"}"""

    private fun money(v: Double) = if (amounts) v.toString() else "null"

    private fun session(id: Int, checkOut: String?, paid: Boolean = false) = """{"id":$id,"worker_id":4,"check_in":"${today}T08:30:00Z",
        "check_out":${checkOut?.let { "\"$it\"" } ?: "null"},"daily_rate":${money(45.0)},"extras":${money(0.0)},"rate_type":"daily",
        "paid_at":${if (paid && amounts) "\"${today}T18:00:00Z\"" else "null"}}"""

    private fun visit(id: Int, day: String, paid: Boolean) = """{"id":$id,"worker_id":4,"check_in":"${day}T08:30:00Z","check_out":"${day}T12:00:00Z",
        "daily_rate":${money(45.0)},"extras":${money(0.0)},"rate_type":"daily","paid_at":${if (paid && amounts) "\"${day}T18:00:00Z\"" else "null"},
        "worker_name":"Maria","payment_schedule":"twice_monthly","total_amount":${money(45.0)},
        "can_edit":${!paid},"can_delete":${!paid},"can_mark_paid":${!paid && amounts},"can_mark_unpaid":${paid && amounts}}"""

    private fun workers() = """{"data":[{"id":4,"user_id":9,"username":"maria","display_name":"Maria Santos","avatar_color":"#7C3AED",
        "daily_rate":${money(45.0)},"rate_type":"daily","payment_schedule":"twice_monthly","calendar_color":"#7C3AED",
        "current_session":${session(30, null)},"today_session":${session(30, null)},"notes":"Comes Mon, Wed & Fri"}],"can_manage":$admin}"""

    private fun visits(shown: String) = """{"data":{"month":"$shown","visits":[${visit(30, today, false)},${visit(29, today, true)}],
        "totals":${if (amounts) """{"total":90,"paid":45,"pending":45}""" else "null"}}}"""

    private val tasks = """{"data":[
        {"id":11,"name":"Change bed linens","area":"Bedrooms","frequency_days":14,"last_completed":"2026-01-01T09:00:00Z","urgency_status":"overdue","urgency":1.2},
        {"id":12,"name":"Clean bathrooms","area":"Bathrooms","frequency_days":7,"last_completed":"${today}T07:00:00Z","urgency_status":"ok","urgency":0.1}]}"""

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
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","currency":"EUR"}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/housekeeping/dashboard" -> json("""{"data":{"visits_this_month":7,"pending_tasks":1,"finished_tasks_this_month":3,
                        "pending_payments":${money(45.0)},"paid_this_month":${money(45.0)},
                        "monthly_payments":${if (amounts) """[{"month":"$month","total":90,"pending":45}]""" else "null"}}}""")
                    line == "GET /api/v1/housekeeping/decay-tasks" -> json(tasks)
                    line == "GET /api/v1/housekeeping/visits" -> json(visits(request.url.queryParameter("month") ?: month))
                    line == "GET /api/v1/housekeeping/task-templates" -> json("""{"data":[{"key":"cleanBathrooms","name":"Clean bathrooms","area":"Bathrooms","frequency_days":7},
                        {"key":"cleanWindows","name":"Clean windows","area":"Whole house","frequency_days":30}]}""")
                    line == "GET /api/v1/housekeeping/workers" -> json(workers())
                    line == "POST /api/v1/housekeeping/work-sessions/check-out" -> json("""{"data":${session(30, "${today}T12:00:00Z")}}""")
                    line == "POST /api/v1/housekeeping/decay-tasks/11/complete" ->
                        json("""{"data":{"id":11,"name":"Change bed linens","area":"Bedrooms","frequency_days":14,"last_completed":"${today}T10:00:00Z","urgency_status":"ok"}}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun open() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.housekeeping")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.housekeeping")).onFirst().performClick()
        waitText("Maria Santos")
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

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    /** HOUSE-A11: the visits tab shows the worker with check-out, the glance, the visits and the payments sentence. */
    @Test fun `the visits tab shows presence, the month and the payments`() {
        open()
        compose.onNodeWithText(strings.t("housekeeping.checkOut")).assertExists()
        compose.onNodeWithText(strings.t("housekeeping.visitsThisMonth")).assertExists()
        compose.onNodeWithText("7").assertExists()
        scrollTo(strings.t("housekeeping.payments"))
        compose.onAllNodesWithText(strings.t("housekeeping.paymentPending")).onFirst().assertExists()
        // The day's zone goes along, so the server reads "today" on the household's clock (#1556).
        assertTrue(reads.any { it.startsWith("GET /api/v1/housekeeping/dashboard?") && "timezone=UTC" in it })
        assertTrue(reads.any { it.startsWith("GET /api/v1/housekeeping/workers?") && "timezone=UTC" in it })
    }

    /** HOUSE-A12: checking out sends the worker and offers the way back. */
    @Test fun `checking out posts the worker and says who`() {
        open()
        compose.onNodeWithText(strings.t("housekeeping.checkOut")).performClick()
        waitForWrite("POST /api/v1/housekeeping/work-sessions/check-out")
        assertTrue(writes.single { it.startsWith("POST /api/v1/housekeeping/work-sessions/check-out") }.endsWith("""{"worker_id":4}"""))
        compose.waitUntil(10_000) { keys.any { it.startsWith("POST /api/v1/housekeeping/work-sessions/check-out") } }
        waitText(strings.t("housekeeping.checkedOutNamed", "name" to "Maria Santos"))
        compose.onNodeWithText(strings.t("common.undo")).assertExists()
    }

    /** HOUSE-A13: the chores tab lists by urgency and completes with the ring. */
    @Test fun `a chore completes from its ring`() {
        open()
        compose.onNodeWithText(strings.t("housekeeping.tabChores")).performClick()
        waitText("Change bed linens")
        compose.onNodeWithText(strings.t("housekeeping.overdue") + " · ", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(strings.t("housekeeping.completeTask", "name" to "Change bed linens")).performClick()
        waitForWrite("POST /api/v1/housekeeping/decay-tasks/11/complete")
        waitText(strings.t("housekeeping.taskDoneNamed", "name" to "Change bed linens"))
    }

    /** HOUSE-A14: `housekeeping: read` keeps the values, shows presence as a sign, and offers no action. */
    @Test fun `a read-only member sees presence but no actions`() {
        modules = """{"housekeeping":"read"}"""
        admin = false
        open()
        compose.onNodeWithText(strings.t("housekeeping.checkOut")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("dashboard.housekeepingPresent")).assertExists()
        compose.onNodeWithText(strings.t("housekeeping.tabChores")).performClick()
        waitText("Change bed linens")
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("housekeeping.completeTask", "name" to "Change bed linens")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("housekeeping.addTask")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("housekeeping.reports")).performClick()
        waitText(strings.t("housekeeping.pendingPayments"))
        assertTrue(compose.onAllNodesWithText(strings.t("housekeeping.markPaidShort")).fetchSemanticsNodes().isEmpty())
        assertTrue(writes.none { "/housekeeping/" in it })
    }

    /** HOUSE-A15: without the amounts right the page shows times, no payments and no pay button; the month steps. */
    @Test fun `without amounts the report counts visits and the month steps back`() {
        admin = false
        capabilities = """{"housekeeping_view_amounts":"none"}"""
        amounts = false
        open()
        compose.onNodeWithText(strings.t("housekeeping.payments")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("housekeeping.reports")).performClick()
        waitText(strings.t("housekeeping.reportVisitsCount"))
        assertTrue(compose.onAllNodesWithText(strings.t("housekeeping.markPaidShort")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("housekeeping.prevMonth")).performClick()
        val previous = java.time.YearMonth.parse(month).minusMonths(1).toString()
        compose.waitUntil(10_000) { reads.any { it.startsWith("GET /api/v1/housekeeping/visits?") && "month=$previous" in it } }
        waitText(strings.t("housekeeping.currentMonth"))
    }
}
