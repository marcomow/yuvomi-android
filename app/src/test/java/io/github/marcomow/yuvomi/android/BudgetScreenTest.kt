package io.github.marcomow.yuvomi.android

import android.os.Looper
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import io.github.marcomow.yuvomi.feature.budget.BudgetEntry
import io.github.marcomow.yuvomi.feature.budget.BudgetHost
import io.github.marcomow.yuvomi.feature.budget.BudgetViewModel
import io.github.marcomow.yuvomi.feature.budget.EntryForm
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/budget.md, BUDGET-A13 to A20, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class BudgetScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private lateinit var strings: Strings
    private lateinit var container: AppContainer
    private val today = LocalDate.now(ZoneOffset.UTC)
    private val month = today.toString().take(7)
    private val first = "$month-01"

    private fun json(body: String, cookie: String? = null, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":{"admin":true,"modules":$modules},"householdSize":2,"csrfToken":"t"}"""

    private val entries get() = """{"data":[
        {"id":1,"title":"Rent","amount":-900,"category":"housing","subcategory":"rent_mortgage","date":"$first","account_id":1},
        {"id":2,"title":"Salary","amount":3000,"category":"Erwerbseinkommen","subcategory":"","date":"$first"},
        {"id":3,"title":"Weekly shop","amount":-50.25,"category":"food","subcategory":"groceries","date":"$first",
          "responsible_users":[{"id":2,"display_name":"Linda","color":"#336699"}]},
        {"id":4,"title":"Insurance","amount":-120,"category":"housing","subcategory":"rent_mortgage","date":"$first","is_pending":1},
        {"id":5,"title":"Gym","amount":-30,"category":"food","subcategory":"bakery","date":"$first","is_recurring":0,"recurrence_parent_id":9}
        ]}"""

    private val summary = """{"data":{"month":"$month","income":3000,"expenses":-980.25,"balance":2019.75,
        "byCategory":[{"category":"housing","income":0,"expenses":-900,"total":-900},
          {"category":"food","income":0,"expenses":-80.25,"total":-80.25},
          {"category":"Erwerbseinkommen","income":3000,"expenses":0,"total":3000}],
        "transfers":0,"pending":{"count":1,"income":0,"expenses":-120}}}"""

    private val meta = """{"data":{"expenseCategories":[{"key":"housing","name":"Wohnen"},{"key":"food","name":"Essen"}],
        "incomeCategories":[{"key":"Erwerbseinkommen","name":"Erwerbseinkommen"}],
        "subcategories":{"food":[{"key":"groceries","name":"Lebensmittel"},{"key":"bakery","name":"Bäckerei"}],
          "housing":[{"key":"rent_mortgage","name":"Miete"}]}}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val line = "${request.method} $path"
                if (request.method == "GET") reads += "$line?${request.url.encodedQuery.orEmpty()}" else writes += "$line ${request.body?.utf8().orEmpty()}"
                request.headers["Idempotency-Key"]?.let { keys += "$line $it" }
                return when {
                    line == "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    line == "POST /api/v1/auth/login" -> json(payload(), "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    line == "GET /api/v1/auth/me" -> json(payload())
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","currency":"EUR","budget_mode":"shared","date_format":"mdy","time_format":"12h"}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/family/members" -> json("""{"data":[{"id":1,"display_name":"Alex"},{"id":2,"display_name":"Linda"}]}""")
                    line == "GET /api/v1/budget/meta" -> json(meta)
                    line == "GET /api/v1/budget" && request.url.queryParameter("q") != null ->
                        json("""{"data":[{"id":1,"title":"Rent","amount":-900,"category":"housing","date":"2026-01-01"}],"meta":{"truncated":false}}""")
                    line == "GET /api/v1/budget" -> json(entries)
                    line == "GET /api/v1/budget/summary" -> json(summary)
                    line == "GET /api/v1/budget/loans" -> json("""{"data":{"loans":[],"summary":{"active_count":0}}}""")
                    line == "GET /api/v1/budget/accounts" -> json("""{"data":{"accounts":[{"id":1,"name":"Giro","type":"checking","current_balance":1000,"starting_balance":500,"entry_count":1}],"net_worth":1000}}""")
                    line == "GET /api/v1/budget/stats" -> json(
                        """{"data":{"range":"month","from":"$first","to":"$first","totals":{"income":3000,"expenses":-980.25,"balance":2019.75},
                        "series":[{"period":"$first","income":3000,"expenses":-980.25,"balance":2019.75}],
                        "byCategory":[{"category":"food","income":0,"expenses":-80.25,"total":-80.25}],"comparison":{"income":0,"expenses":0,"balance":0},"plans":{}}}""",
                    )
                    line == "GET /api/v1/budget/plans" -> json("""{"data":{"month":"$month","isCurrentMonth":true,"plans":[{"category":"food","planned":100,"actual":80.25,"remaining":19.75,"ratio":0.8025,"over":false}],"savings":null}}""")
                    line == "POST /api/v1/budget" -> json("""{"data":{"id":7,"title":"Milk","amount":-3.2,"category":"food","date":"$first"}}""", code = 201)
                    line.startsWith("PUT /api/v1/budget/") -> json("""{"data":{"id":5,"title":"Gym","amount":-30,"category":"food","date":"$first"}}""")
                    line.startsWith("PATCH /api/v1/budget/") -> json("""{"data":{"id":4}}""")
                    line.startsWith("DELETE /api/v1/budget/") -> MockResponse.Builder().code(204).build()
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
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
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
    }

    private fun openBudget() {
        signIn()
        compose.setContent { io.github.marcomow.yuvomi.feature.shell.YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        // Budget sits low in "More": scroll the list until its row is composed.
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(strings.t("nav.budget")))
        compose.onAllNodesWithText(strings.t("nav.budget")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("€2,019.75").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    /** Scrolls the lazy list until a node with [text] is composed. */
    private fun show(text: String) = compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))

    /** A host for the view model tests: the undo is tapped when [undo] says so. */
    private inner class Host(var undo: Boolean = false, override val writable: Boolean = true) : BudgetHost {
        val messages = CopyOnWriteArrayList<String>()
        override val strings: Strings get() = this@BudgetScreenTest.strings
        override fun today(): LocalDate = today
        override suspend fun notify(message: String, action: String?): Boolean {
            messages += message
            return action != null && undo
        }
        override val documentsAccess: String = "write"
    }

    private fun pump(timeoutMs: Long = 5_000, until: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("condition not met in time", until())
    }

    /** BUDGET-A13. */
    @Test fun `the overview shows the balance, the categories, planned and booked`() {
        openBudget()
        compose.onNodeWithText("€2,019.75").assertExists()
        compose.onNodeWithText(strings.t("budget.categoryHousing")).assertExists()
        compose.onNodeWithText(strings.t("budget.pendingSummary", "count" to 1, "amount" to "-€120.00")).assertExists()
        show("${strings.t("budget.plannedTitle")} · 1")
        compose.onNodeWithText("${strings.t("budget.plannedTitle")} · 1").assertExists()
        show(strings.t("budget.bookedTitle"))
        compose.onNodeWithText(strings.t("budget.bookedTitle")).assertExists()
        show("Insurance")
        compose.onNodeWithText("Insurance").assertExists()
        show("-€900.00")
        compose.onNodeWithText("-€900.00").assertExists()
        show("+€3,000.00")
        compose.onNodeWithText("+€3,000.00").assertExists()
        assertTrue(reads.any { it.startsWith("GET /api/v1/budget?month=$month") })
        assertTrue(reads.any { it.startsWith("GET /api/v1/budget/summary?month=") })
    }

    /** BUDGET-A14. */
    @Test fun `a category bar filters the ledger and splits it by subcategory`() {
        openBudget()
        compose.onNodeWithText(strings.t("budget.categoryFood")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Rent").fetchSemanticsNodes().isEmpty() }
        show("Weekly shop")
        compose.onNodeWithText("Weekly shop").assertExists()
        show(strings.t("budget.subcatGroceries"))
        compose.onNodeWithText(strings.t("budget.subcatGroceries")).assertExists()
        show(strings.t("budget.ledgerFilterSummary", "count" to 2, "sum" to "-€80.25"))
        compose.onNodeWithText(strings.t("budget.ledgerFilterSummary", "count" to 2, "sum" to "-€80.25")).assertExists()
    }

    /** BUDGET-A15. */
    @Test fun `a tap opens the read sheet, and Edit the editor`() {
        openBudget()
        show("Rent")
        compose.onNodeWithText("Rent").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("budget.detailDateLabel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Giro").assertExists()
        compose.onNodeWithText(strings.t("common.edit")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("budget.editEntry")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("900.00").assertExists()
    }

    /** BUDGET-A16. */
    @Test fun `a read-only member sees no add, no confirm and no edit`() {
        modules = """{"budget":"read"}"""
        openBudget()
        compose.onNodeWithContentDescription(strings.t("budget.newEntryFabLabel")).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithContentDescription("${strings.t("budget.confirmAction")}: Insurance").fetchSemanticsNodes().isEmpty())
        show("Rent")
        compose.onNodeWithText("Rent").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("budget.detailDateLabel")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.edit")).assertDoesNotExist()
        assertTrue(writes.none { "/budget" in it })
    }

    /** BUDGET-A17. */
    @Test fun `accounts, plan and statistics load their tabs`() {
        openBudget()
        compose.onNodeWithText(strings.t("budget.accountsTab")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("budget.netWorth")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Giro").assertExists()
        compose.onNodeWithText(strings.t("budget.periodNoteAccounts")).assertExists()
        compose.onNodeWithText(strings.t("budget.planTab")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("budget.planCategoryBudgets")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("budget.planLeft", "amount" to "€19.75")).assertExists()
        compose.onNodeWithText(strings.t("budget.reportsTab")).performScrollTo().performClick()
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("budget.statsTrendTitleCumulative")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            println("READS: $reads")
            throw e
        }
        assertTrue(reads.any { it.startsWith("GET /api/v1/budget/stats?range=month&anchor=") })
    }

    /** BUDGET-A18. */
    @Test fun `a new expense posts the web page's body with an idempotency key`() {
        signIn()
        val vm = BudgetViewModel(container, Host())
        pump { vm.state.value.meta.expenseCategories.isNotEmpty() && vm.state.value.accounts.accounts.isNotEmpty() }
        val form = EntryForm(amount = "3,20", title = "Milk", category = "food", subcategory = "groceries", date = LocalDate.parse(first), accountId = 1, responsible = setOf(2))
        val result = runBlocking { vm.saveEntry(null, form, series = false, idempotencyKey = "k-1") }
        assertEquals(BudgetViewModel.SaveResult.Saved, result)
        val post = writes.single { it.startsWith("POST /api/v1/budget ") }.substringAfter("POST /api/v1/budget ")
        assertEquals(
            """{"title":"Milk","amount":-3.2,"category":"food","subcategory":"groceries","date":"$first","is_recurring":0,""" +
                """"recurrence_interval":"monthly","recurrence_interval_count":1,"recurrence_virtual":0,"recurrence_confirm":0,""" +
                """"account_id":1,"responsible_user_ids":[2],"attachment_document_ids":[]}""",
            post,
        )
        assertTrue(keys.any { it == "POST /api/v1/budget k-1" })
    }

    /** BUDGET-A19. */
    @Test fun `a series edit from an occurrence sends only the changes to the series route`() {
        signIn()
        val vm = BudgetViewModel(container, Host())
        pump { vm.state.value.entries.isNotEmpty() && vm.state.value.accounts.accounts.isNotEmpty() }
        val gym = vm.state.value.entries.single { it.id == 5 }
        val form = EntryForm.of(gym, vm.state.value.money, "EUR", vm.state.value.subcategories("food"), vm.state.value.meta.expenseCategories).copy(title = "Gym club")
        runBlocking { vm.saveEntry(gym, form, series = true, idempotencyKey = "k") }
        assertEquals("""PUT /api/v1/budget/5/series {"title":"Gym club"}""", writes.single { it.startsWith("PUT") })
    }

    /** BUDGET-A20. */
    @Test fun `a delete waits for the undo window, an undo sends nothing`() {
        signIn()
        val host = Host(undo = true)
        val vm = BudgetViewModel(container, host)
        pump { vm.state.value.entries.isNotEmpty() }
        val rent = vm.state.value.entries.single { it.id == 1 }
        vm.deleteEntry(rent)
        pump { host.messages.contains(strings.t("budget.deletedToast")) }
        pump { vm.state.value.pendingDeletes.isEmpty() }
        assertTrue(writes.none { it.startsWith("DELETE") })
        host.undo = false
        vm.deleteEntry(rent)
        pump { writes.any { it.startsWith("DELETE /api/v1/budget/1 ") } }
        val pending = vm.state.value.entries.single { it.id == 4 }
        vm.deleteEntry(pending, series = false)
        pump { writes.any { it.startsWith("DELETE /api/v1/budget/4") } }
    }

    /** BUDGET-A21. */
    @Test fun `confirming a booking checks the amount and sends amount and date`() {
        signIn()
        val vm = BudgetViewModel(container, Host())
        pump { vm.state.value.entries.isNotEmpty() }
        val e: BudgetEntry = vm.state.value.entries.single { it.id == 4 }
        assertEquals(strings.t("common.amountNotPositive"), runBlocking { vm.confirm(e, "", null) })
        assertNull(runBlocking { vm.confirm(e, "118,50", LocalDate.parse(first)) })
        assertEquals("""PATCH /api/v1/budget/4/confirm {"amount":118.5,"date":"$first"}""", writes.single { it.startsWith("PATCH") })
    }

    /** BUDGET-A22. */
    @Test fun `the ledger search asks the server over all months`() {
        signIn()
        val vm = BudgetViewModel(container, Host())
        pump { vm.state.value.entries.isNotEmpty() }
        vm.setQuery("rent")
        pump { vm.state.value.results != null }
        assertTrue(reads.any { it == "GET /api/v1/budget?q=rent" })
        assertEquals(listOf(1), vm.state.value.results!!.map { it.id })
    }
}
