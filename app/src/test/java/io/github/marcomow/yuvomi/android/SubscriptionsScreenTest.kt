package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsRoutes
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

/** docs/spec/subscriptions.md: the list, a payment, the read view and the read-only rules against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class SubscriptionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val queries = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private lateinit var strings: Strings
    private val next = LocalDate.now().plusDays(5).toString()

    private val netflix = """{"id":1,"name":"Netflix","description":"Standard plan","amount":17.99,"currency":"EUR","billing_cycle":"monthly","cycle_interval":1,
        "next_payment_date":"$next","category_id":1,"category_name":"Entertainment","payment_method_id":1,"payment_method_name":"Credit Card",
        "reminder_days":3,"reminder_enabled":true,"enabled":true,"status":"active","end_type":"never","notes":"Shared #family profile",
        "monthly_native":17.99,"monthly_base":17.99,"base_currency":"EUR","brand_color":"#E50914"}"""
    private val prime = """{"id":2,"name":"Amazon Prime","amount":89.90,"currency":"EUR","billing_cycle":"yearly","cycle_interval":1,
        "next_payment_date":"$next","reminder_days":3,"reminder_enabled":true,"enabled":false,"status":"paused","end_type":"never",
        "monthly_native":7.49,"monthly_base":7.49,"base_currency":"EUR"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$path ${request.body?.utf8().orEmpty()}"
                SubscriptionsTestHarness.signIn(path, permissions)?.let { return it }
                return when (path) {
                    "GET /api/v1/budget/subscriptions" -> {
                        queries += request.url.encodedQuery.orEmpty()
                        SubscriptionsTestHarness.json("""{"data":{"subscriptions":[$netflix,$prime],"summary":{"active_count":1,"disabled_count":1,
                            "monthly_total":17.99,"monthly_budget":10,"remaining_budget":-7.99,"base_currency":"EUR",
                            "by_category":[{"id":1,"name":"Entertainment","amount":17.99}],"by_payment_method":[]},"rates":{"source":"cache"}}}""")
                    }
                    "GET /api/v1/budget/subscriptions/meta" -> SubscriptionsTestHarness.json("""{"data":{"categories":[{"id":1,"name":"Entertainment","color":"#E50914"}],
                        "payment_methods":[{"id":1,"name":"Credit Card"}],"billing_cycles":["daily","weekly","monthly","yearly"],"budget_mode":"shared","accounts":[]}}""")
                    "GET /api/v1/budget/subscriptions/settings" -> SubscriptionsTestHarness.json("""{"data":{"monthly_budget":10,"base_currency":"EUR"}}""")
                    "POST /api/v1/budget/subscriptions/1/renew" -> SubscriptionsTestHarness.json("""{"data":${netflix.replace(next, LocalDate.parse(next).plusMonths(1).toString())},"undo":{"token":"abc"}}""")
                    else -> SubscriptionsTestHarness.json("""{"error":"Not found"}""", code = 404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun open() {
        strings = SubscriptionsTestHarness.open(compose, server, SubscriptionsRoutes.START)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Netflix").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    /** SUBS-A10: the list with totals, paused badge, over-budget note; a payment from the row menu. */
    @Test fun `list shows totals and marks a payment`() {
        open()
        compose.onNodeWithText(strings.t("subscriptions.monthlyCost")).assertExists()
        compose.onNodeWithText(strings.t("subscriptions.overBudget")).assertExists()
        compose.onNodeWithText(strings.t("subscriptions.disabled")).assertExists()
        compose.onNodeWithText(strings.t("subscriptions.overBudgetAction")).assertExists()
        compose.onNodeWithContentDescription(strings.t("subscriptions.add")).assertExists()
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Netflix")).performClick()
        compose.onNodeWithText(strings.t("subscriptions.markRenewed")).performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("POST /api/v1/budget/subscriptions/1/renew") } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** SUBS-A11: tapping a row opens its read view; Edit leads to the editor. */
    @Test fun `row opens the read view`() {
        open()
        compose.onNodeWithText("Netflix").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shared #family profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.edit")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("subscriptions.editTitle")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** SUBS-A12: `budget: read` shows everything without add, row menus or the manager. */
    @Test fun `read-only hides every writing affordance`() {
        permissions = """{"admin":false,"modules":{"budget":"read"}}"""
        open()
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("subscriptions.add")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Netflix")).fetchSemanticsNodes().isEmpty())
        compose.onAllNodesWithContentDescription(strings.t("common.moreActions")).onFirst().performClick()
        assertTrue(compose.onAllNodesWithText(strings.t("subscriptions.manageMetadata")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(strings.t("subscriptions.sortName")).assertExists()
    }
}
