package io.github.marcomow.yuvomi.android

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Formats
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * docs/spec/meals.md against a scripted server: the week in the household's names, a meal to
 * the shopping list, the random plan, an edit, the next week, and the read-only plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class MealsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val today = LocalDate.now(ZoneOffset.UTC)
    private val weekStart = today.minusDays(((today.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7).toLong())
    private var permissions = """{"admin":true}"""

    private fun json(body: String, cookie: String? = null) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload get() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},
        "permissions":$permissions,"householdSize":2,"csrfToken":"t"}"""
    private val lasagne get() = """{"id":5,"date":"$today","meal_type":"dinner","title":"Lasagne","cook_user_id":2,"cook_name":"Emma",
        "ingredients":[{"id":11,"meal_id":5,"name":"Pasta","quantity":"500 g","category":"Sonstiges","on_shopping_list":0},
        {"id":12,"meal_id":5,"name":"Cheese","quantity":null,"category":"Milchprodukte","on_shopping_list":0}],"recipe_ingredient_count":0}"""
    private val porridge get() = """{"id":6,"date":"$weekStart","meal_type":"breakfast","title":"Porridge","recurrence_template_id":3,
        "ingredients":[],"recipe_ingredient_count":0}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                requests += "$line?${request.url.encodedQuery.orEmpty()} ${request.body?.utf8().orEmpty()}"
                return when (line) {
                    "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    "POST /api/v1/auth/login" -> json(payload, "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    "GET /api/v1/auth/me" -> json(payload)
                    "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","visible_meal_types":["breakfast","lunch","dinner"],
                        "meal_type_names":{"dinner":"Supper"},"week_start":"monday"}}""")
                    "GET /api/v1/dashboard" -> json("{}")
                    "GET /api/v1/meals" -> json("""{"data":[$porridge,$lasagne]}""")
                    "GET /api/v1/recipes" -> json("""{"data":[{"id":1,"title":"Tomato soup","servings":2,"source":"native","meal_types":["lunch","dinner"],
                        "ingredients":[{"name":"Tomatoes","quantity":"4","category":"Sonstiges"}]}]}""")
                    "GET /api/v1/shopping" -> json("""{"data":[{"id":3,"name":"Groceries"}]}""")
                    "GET /api/v1/shopping/categories" -> json("""{"data":[{"id":1,"name":"Sonstiges"},{"id":2,"name":"Milchprodukte"}]}""")
                    "GET /api/v1/family/members" -> json("""{"data":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Emma"}]}""")
                    "GET /api/v1/meals/suggestions" -> json("""{"data":[]}""")
                    "POST /api/v1/meals/5/to-shopping-list" ->
                        json("""{"data":{"transferred":2,"skipped":0,"added_ids":[40,41],"ingredient_ids":[11,12]}}""")
                    "POST /api/v1/meals/apply-plan" -> json("""{"data":[]}""")
                    "PUT /api/v1/meals/5" -> json("""{"data":$lasagne}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private val strings by lazy { StringsLoader.load(ApplicationProvider.getApplicationContext<YuvomiApp>().assets, "en") }

    private fun start() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        // The bar's kitchen slot (an upstream default, BarSlots) opens Meals first.
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(strings.t("nav.kitchen"))
        compose.waitUntil(10_000) { compose.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tab).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Lasagne").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun waitFor(prefix: String) = try {
        compose.waitUntil(10_000) { requests.any { it.startsWith(prefix) } }
    } catch (e: Throwable) {
        println("REQUESTS:\n" + requests.joinToString("\n"))
        println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
        throw e
    }

    @Test fun `the week plans, transfers, randomizes and edits`() {
        start()
        // MEALS-A11: the household's own name for dinner and the cook's ingredient count.
        compose.onAllNodesWithText("Supper").onFirst().assertExists()
        compose.onAllNodesWithText(strings.t("meals.ingredientCount", "count" to 2)).onFirst().assertExists()
        assertTrue(requests.any { it.startsWith("GET /api/v1/meals?week=$weekStart") })

        // MEALS-A12: one list, so the cart sends at once and names the list.
        compose.onNodeWithContentDescription(strings.t("common.toShoppingListNamed", "title" to "Lasagne")).performClick()
        waitFor("POST /api/v1/meals/5/to-shopping-list")
        assertTrue(requests.first { it.startsWith("POST /api/v1/meals/5/to-shopping-list") }.contains("\"listId\":3"))
        val sent = strings.t("meals.transferSuccess", "count" to 2, "list" to "Groceries")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(sent).fetchSemanticsNodes().isNotEmpty() }

        // MEALS-A13: the random plan fills the open slots of the visible types.
        compose.onNodeWithContentDescription(strings.t("common.moreActions")).performClick()
        compose.onNodeWithText(strings.t("meals.randomizePlan")).performClick()
        compose.onNodeWithText(strings.t("meals.randomizeTitle")).assertExists()
        compose.onNodeWithText(strings.t("meals.randomizePreview", "count" to 13)).assertExists()
        compose.onAllNodesWithText(strings.t("meals.randomizePlan")).onFirst().performClick()
        waitFor("POST /api/v1/meals/apply-plan")
        val plan = requests.first { it.startsWith("POST /api/v1/meals/apply-plan") }
        assertTrue(plan, plan.contains("\"recipe_id\":1") && plan.contains("\"replace_existing\":false"))

        // MEALS-A14: the read sheet, then Edit; a changed title is a single-meal PUT.
        compose.onAllNodesWithText("Lasagne").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("500 g · Pasta", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.edit")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("Lasagne")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Lasagne")).performTextReplacement("Lasagne al forno")
        // The top bar's Save; the footer's sits below the fold.
        compose.onAllNodesWithText(strings.t("common.save")).onLast().performClick()
        waitFor("PUT /api/v1/meals/5")
        val put = requests.first { it.startsWith("PUT /api/v1/meals/5") }
        assertTrue(put, put.contains("\"title\":\"Lasagne al forno\"") && put.contains("\"cook_user_id\":2") && !put.contains("scope=series"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("meals.mealUpdated")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("unchanged ingredients are not touched", requests.none { it.contains("/ingredients") })

        // MEALS-A15: the next week loads from its first day and names its days.
        compose.onNodeWithContentDescription(strings.t("meals.nextWeek")).performClick()
        waitFor("GET /api/v1/meals?week=${weekStart.plusDays(7)}")
        val formats = Formats(strings, ZoneOffset.UTC)
        val label = "${formats.date(weekStart.plusDays(7), today)} – ${formats.date(weekStart.plusDays(13), today)}"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    // MEALS-A16
    @Test fun `at read the plan shows meals without any way to change them`() {
        permissions = """{"admin":false,"modules":{"meals":"read"}}"""
        start()
        compose.onAllNodesWithContentDescription(strings.t("meals.addMealTitle")).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(strings.t("common.toShoppingListNamed", "title" to "Lasagne")).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(strings.t("common.moreActions")).assertCountEquals(0)
        compose.onAllNodesWithText("Lasagne").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("500 g · Pasta", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("common.edit")).assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
        val count = fetchSemanticsNodes().size
        assertTrue("expected $n nodes, found $count", count == n)
    }
}
