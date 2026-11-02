package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitExpensesRoutes
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

/** docs/spec/splitexpenses.md: the tab, an expense and the read-only rules against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class SplitExpensesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private lateinit var strings: Strings

    private val expense = """{"id":11,"group_id":7,"title":"Weekly groceries","amount":"142.30","currency":"EUR","amount_minor":14230,"payer_id":1,
        "payer_name":"Alex Johnson","budget_category":"food","split_method":"equal","expense_date":"2026-11-01","created_at":"2026-11-01T10:00:00Z",
        "splits":[{"user_id":1,"amount_minor":7115,"amount":"71.15","currency":"EUR","display_name":"Alex Johnson"},
                  {"user_id":2,"amount_minor":7115,"amount":"71.15","currency":"EUR","display_name":"Linda"}],"attachments":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$path ${request.body?.utf8().orEmpty()}"
                SubscriptionsTestHarness.signIn(path, permissions)?.let { return it }
                val j = SubscriptionsTestHarness::json
                return when (path) {
                    "GET /api/v1/split-expenses/meta" -> j("""{"data":{"group_types":["household","travel","general"],"split_methods":["equal","exact","percentage","shares"],
                        "currencies":["EUR","USD"],"frequencies":["weekly","monthly","yearly"],"default_currency":"EUR"}}""", 200, null)
                    "GET /api/v1/split-expenses/dashboard" -> j("""{"data":{"total_owed":[{"currency":"EUR","amount":"71.15"}],"total_owing":[]}}""", 200, null)
                    "GET /api/v1/split-expenses/groups" -> j("""{"data":[{"id":7,"name":"Household","type":"household","default_currency":"EUR","default_split_method":"equal","status":"active","member_count":2,"member_role":"owner"}]}""", 200, null)
                    "GET /api/v1/family/members" -> j("""{"data":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Linda"}]}""", 200, null)
                    "GET /api/v1/budget/meta" -> j("""{"data":{"expenseCategories":[{"key":"food","name":"Food"}]}}""", 200, null)
                    "GET /api/v1/split-expenses/groups/7/expenses" -> j("""{"data":[$expense],"pagination":{"limit":30,"has_more":false,"next_cursor":null}}""", 200, null)
                    "GET /api/v1/split-expenses/groups/7/balances" -> j("""{"data":{"balances":[{"currency":"EUR","user_id":1,"display_name":"Alex Johnson","net_minor":7115,"net":"71.15"},
                        {"currency":"EUR","user_id":2,"display_name":"Linda","net_minor":-7115,"net":"-71.15"}],
                        "simplified_debts":[{"from_user_id":2,"from_name":"Linda","to_user_id":1,"to_name":"Alex Johnson","currency":"EUR","amount_minor":7115,"amount":"71.15"}],
                        "member_totals":[],"budget_category_totals":[{"budget_category":"food","currency":"EUR","total":"142.30"}]}}""", 200, null)
                    "GET /api/v1/split-expenses/groups/7/activity" -> j("""{"data":[{"id":3,"type":"payment_registered","entity_type":"settlement","entity_id":5,"actor_name":"Linda",
                        "created_at":"2026-11-01T10:00:00Z","settlement":{"id":5,"payer_name":"Linda","payee_name":"Alex Johnson","amount":"50.00","currency":"EUR","can_reverse":true}}],
                        "pagination":{"limit":12,"has_more":false}}""", 200, null)
                    "GET /api/v1/split-expenses/groups/7/members" -> j("""{"data":[{"user_id":1,"role":"owner","display_name":"Alex Johnson"},{"user_id":2,"role":"admin","display_name":"Linda"}]}""", 200, null)
                    "GET /api/v1/split-expenses/groups/7/recurring" -> j("""{"data":[{"id":4,"title":"Rent share","amount":"1450.00","currency":"EUR","split_method":"equal",
                        "frequency":"monthly","next_run_date":"2026-12-01","participants":[1,2],"splits":[],"can_edit":true}]}""", 200, null)
                    "POST /api/v1/split-expenses/groups/7/expenses" -> j("""{"data":$expense}""", 201, null)
                    "POST /api/v1/split-expenses/recurring/4/pause" -> j("""{"data":{"id":4,"title":"Rent share","paused_at":"2026-11-02T10:00:00Z"}}""", 200, null)
                    else -> j("""{"error":"Not found"}""", 404, null)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun open() {
        strings = SubscriptionsTestHarness.open(compose, server, SplitExpensesRoutes.START)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Weekly groceries").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    /** SPLIT-A9: the folded debt as the lead figure, the expense, series and the payment in the activity. */
    @Test fun `tab shows balance, expenses, series and activity`() {
        open()
        compose.onNodeWithText(strings.t("splitExpenses.debtOwesYou", "name" to "Linda")).assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Rent share"))
        compose.onNodeWithContentDescription("Rent share - ${strings.t("splitExpenses.recurring.pause")}").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { writes.any { it.startsWith("POST /api/v1/split-expenses/recurring/4/pause") } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(strings.t("splitExpenses.reversePayment")))
        compose.onNodeWithText(strings.t("splitExpenses.reversePayment")).assertExists()
    }

    /** SPLIT-A10: a new expense sends the decimal amount, the payer and everyone as participants. */
    @Test fun `adds an expense`() {
        open()
        compose.onNodeWithContentDescription(strings.t("splitExpenses.addExpense")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText(strings.t("splitExpenses.titleLabel") + " *")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText(strings.t("splitExpenses.amount") + " *")).performTextInput("20.50")
        compose.onNode(hasSetTextAction() and hasText(strings.t("splitExpenses.titleLabel") + " *")).performTextInput("Pizza")
        compose.onNodeWithText(strings.t("common.add")).performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("POST /api/v1/split-expenses/groups/7/expenses") } }
        val body = writes.first { it.startsWith("POST /api/v1/split-expenses/groups/7/expenses") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Weekly groceries").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(body, body.contains("\"amount\":\"20.50\"") && body.contains("\"title\":\"Pizza\"") && body.contains("\"participants\":[1,2]") && body.contains("\"payer_id\":1"))
    }

    /** SPLIT-A11: `budget: read` hides add, settle, series toggles, reversal and the group menu. */
    @Test fun `read-only hides every writing affordance`() {
        permissions = """{"admin":false,"modules":{"budget":"read"}}"""
        open()
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("splitExpenses.addExpense")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(strings.t("splitExpenses.settle")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(strings.t("splitExpenses.reversePayment")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.moreActions")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Rent share - ${strings.t("splitExpenses.recurring.pause")}").fetchSemanticsNodes().isEmpty())
    }
}
