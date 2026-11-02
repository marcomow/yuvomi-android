package io.github.marcomow.yuvomi.android

import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
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
import io.github.marcomow.yuvomi.feature.rewards.RewardsHost
import io.github.marcomow.yuvomi.feature.rewards.RewardsViewModel
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/rewards.md, REW-A6 to REW-A11, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class RewardsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private var me = 1
    private var admin = true
    private lateinit var strings: Strings
    private lateinit var container: AppContainer

    private fun json(body: String, cookie: String? = null) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":$me,"username":"u$me","display_name":"User $me","role":"${if (admin) "admin" else "member"}"},
        "permissions":{"admin":$admin,"modules":$modules},"householdSize":4,"csrfToken":"t"}"""

    private fun overview() = """{"data":{"balances":[
          {"id":4,"display_name":"Leo","avatar_color":"#2563EB","balance":65,"rank":1},
          {"id":3,"display_name":"Emma","avatar_color":"#DB2777","balance":30,"rank":2}],
        "catalog":[{"id":1,"name":"Screen time","cost":20,"icon":"📺","is_active":1,"remaining":null},
          {"id":4,"name":"Gaming","cost":40,"icon":"🎮","is_active":1,"remaining":null},
          {"id":6,"name":"Sleepover","cost":60,"icon":"🛌","is_active":1,"quantity":1,"remaining":0}],
        "pendingCount":1,"moneyPendingCount":1,"isAdmin":$admin,"me":$me,
        "setup":{"participantCount":2,"catalogCount":3,"pointedTaskCount":4}}}"""

    private val requests = """{"data":[
        {"id":7,"user_id":4,"catalog_id":4,"kind":"reward","reward_name":"Gaming","reward_icon":"🎮","cost":40,"status":"pending",
         "note":"Please?","user_name":"Leo","user_balance":65},
        {"id":8,"user_id":3,"kind":"withdrawal","currency":"EUR","reward_name":"withdrawal","cost":1000,"status":"pending",
         "user_name":"Emma","user_balance":2500,"minor_unit":2}]}"""

    private val ledger = """{"data":[
        {"id":21,"user_id":4,"delta":15,"type":"earn","reason":"Homework done","created_at":"2026-11-01T16:30:00Z","user_name":"Leo"},
        {"id":20,"user_id":3,"delta":-25,"type":"redeem","reason":"Ice cream trip","created_at":"2026-10-30T19:00:00Z","user_name":"Emma"}]}"""

    private val money = """{"data":{"currency":"EUR","minor_unit":2,"accounts":[
        {"id":3,"display_name":"Emma","balance_minor":2500,"former":false,"currency":"EUR","minor_unit":2,
         "plan":{"amount_minor":1000,"currency":"EUR","minor_unit":2,"frequency":"monthly","anchor_day":1,"next_run_date":"2026-12-01","paused":false},
         "closable":false}],"candidates":[{"id":4,"display_name":"Leo","family_role":"son"}]}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$line ${request.body?.utf8().orEmpty()}" else reads += "$line?${request.url.encodedQuery.orEmpty()}"
                request.headers["Idempotency-Key"]?.let { keys += "$line $it" }
                return when {
                    line == "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    line == "POST /api/v1/auth/login" -> json(payload(), "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    line == "GET /api/v1/auth/me" -> json(payload())
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/rewards/overview" -> json(overview())
                    line == "GET /api/v1/rewards/redemptions" -> json(requests)
                    line == "GET /api/v1/rewards/ledger" -> json(ledger)
                    line == "GET /api/v1/rewards/money" -> json(money)
                    line == "GET /api/v1/rewards/catalog" -> json("""{"data":[
                        {"id":1,"name":"Screen time","cost":20,"icon":"📺","is_active":1,"remaining":null},
                        {"id":4,"name":"Gaming","cost":40,"icon":"🎮","is_active":1,"remaining":null},
                        {"id":6,"name":"Sleepover","cost":60,"icon":"🛌","is_active":1,"quantity":1,"remaining":0},
                        {"id":9,"name":"Old prize","cost":10,"is_active":0,"remaining":null}]}""")
                    line.startsWith("PATCH /api/v1/rewards/redemptions/") -> json("""{"data":{"id":7,"status":"fulfilled","reward_name":"Gaming","cost":40}}""")
                    line == "POST /api/v1/rewards/redemptions" -> MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
                        .body("""{"data":{"id":30,"user_id":3,"reward_name":"Screen time","cost":20,"status":"pending"}}""").build()
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

    private fun scrollTo(text: String) = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()

    private fun openRewards(waitFor: () -> String = { "Gaming" }) {
        signIn()
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.rewards")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.rewards")).onFirst().performClick()
        try {
            val text = waitFor()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    /** REW-A6. */
    @Test fun `a parent sees requests, standings, pocket money and the latest entries`() {
        openRewards()
        compose.onNodeWithText(strings.t("rewards.pendingApprovals")).assertExists()
        compose.onNodeWithText("Leo · 40 points · „Please?“").assertExists()
        // The money request names its kind and the balance next to it.
        compose.onNodeWithText(strings.t("rewards.money.ledgerWithdrawal")).assertExists()
        assertTrue(reads.any { it == "GET /api/v1/rewards/redemptions?status=pending&kind=all" })
        compose.onNodeWithText(strings.t("rewards.grantBonus"), useUnmergedTree = true).assertExists()
        scrollTo(strings.t("rewards.money.title"))
        compose.onNodeWithText("€25.00").assertExists()
        scrollTo(strings.t("rewards.standings"))
        // Emma's bar runs toward the cheapest reward above her 30 points.
        scrollTo("10 more to “Gaming”")
        scrollTo("Homework done")
        // All setup steps are done: no hints.
        assertTrue(compose.onAllNodesWithText(strings.t("rewards.setupTitle")).fetchSemanticsNodes().isEmpty())
    }

    /** REW-A7. */
    @Test fun `approving goes out at once and undo reopens the request`() {
        openRewards()
        compose.onAllNodesWithText(strings.t("rewards.approve")).onFirst().performClick()
        waitForWrite("PATCH /api/v1/rewards/redemptions/7")
        assertEquals("""{"action":"fulfill"}""", writes.first { it.startsWith("PATCH /api/v1/rewards/redemptions/7") }.substringAfter("/7 "))
        val sentence = strings.t("rewards.toastApprovedNamed", "reward" to "Gaming", "name" to "Leo")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(sentence).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(5_000) { writes.count { it.startsWith("PATCH /api/v1/rewards/redemptions/7") } == 2 }
        assertTrue(writes.last { it.startsWith("PATCH") }.endsWith("""{"action":"reopen"}"""))
    }

    /** REW-A8. */
    @Test fun `a read-only parent sees the requests but no action`() {
        modules = """{"rewards":"read"}"""
        openRewards()
        compose.onNodeWithText("Leo · 40 points · „Please?“").assertExists()
        assertTrue(compose.onAllNodesWithText(strings.t("rewards.approve")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(strings.t("rewards.grantBonus"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(strings.t("rewards.manageParticipants")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(strings.t("rewards.redeem")).fetchSemanticsNodes().isEmpty())
        tab(strings.t("rewards.tabCatalog"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Old prize").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.editNamed", "name" to "Gaming")).fetchSemanticsNodes().isEmpty())
    }

    /** REW-A9. */
    @Test fun `a child sees their own card first and what is missing in the catalogue`() {
        me = 3
        admin = false
        openRewards(waitFor = { strings.t("rewards.yourPoints") })
        compose.onNodeWithText(strings.t("rewards.ownLedger")).assertExists()
        compose.onNodeWithText(strings.t("rewards.request")).assertExists()
        // Children ask for every request of theirs, then keep only their own open ones.
        assertTrue(reads.any { it == "GET /api/v1/rewards/redemptions?kind=all" })
        tab(strings.t("rewards.tabCatalog"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("rewards.pointsShort", "points" to "10")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("rewards.soldOut")).assertExists()
        // A child adds no rewards.
        assertTrue(compose.onAllNodesWithText(strings.t("rewards.addReward"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** REW-A10. */
    @Test fun `redeeming sends the web app's payload with an idempotency key and refuses too few points`() {
        signIn()
        val host = object : RewardsHost {
            override val strings = this@RewardsScreenTest.strings
            override val isAdmin = true
            override val readOnly = false
            override val me = 1
            override suspend fun notify(message: String, action: String?) = false
        }
        val vm = RewardsViewModel(container, host)
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.state.value.loading) return@repeat
            Thread.sleep(25)
        }
        assertTrue(!vm.state.value.loading)
        assertEquals(strings.t("rewards.notEnoughPoints"), runBlocking { vm.redeem(3, 4, "") })
        assertNull(runBlocking { vm.redeem(3, 1, " for Saturday ") })
        val post = writes.single { it.startsWith("POST /api/v1/rewards/redemptions") }.substringAfter("redemptions ")
        assertEquals("""{"catalog_id":1,"user_id":3,"note":"for Saturday"}""", post)
        assertTrue(keys.any { it.startsWith("POST /api/v1/rewards/redemptions ") })
        // The sold-out reward is not offered.
        assertEquals(listOf(1, 4), vm.redeemable().map { it.id })
    }

    /** REW-A11. */
    @Test fun `the history filters by person`() {
        openRewards()
        scrollTo(strings.t("rewards.tabLedger"))
        compose.onNodeWithText(strings.t("rewards.tabLedger")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("rewards.all")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Leo").performClick()
        compose.waitUntil(5_000) { reads.any { it == "GET /api/v1/rewards/ledger?user_id=4" } }
        compose.onNodeWithContentDescription(strings.t("rewards.tabPoints")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("rewards.standings")).fetchSemanticsNodes().isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    }
}
