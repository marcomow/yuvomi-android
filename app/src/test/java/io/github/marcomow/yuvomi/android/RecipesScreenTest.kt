package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/spec/recipes.md against a scripted server: the list, the detail with servings scaling,
 * the shopping-list transfer, planning, pantry links, the form, and a read-only member.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class RecipesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var modules = """{}"""

    private fun json(body: String, cookie: String? = null, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB"},
        "permissions":{"admin":false,"modules":$modules},"householdSize":3,"csrfToken":"t"}"""

    private val spaghetti = """{"id":1,"title":"Spaghetti Bolognese","notes":"Simmer for 45 minutes.","recipe_url":"https://example.com/bolo",
        "meal_types":["lunch","dinner"],"servings":4,"has_own_image":false,"source":"native",
        "ingredients":[{"id":11,"recipe_id":1,"name":"Spaghetti","quantity":"200 g","category":"Sonstiges","pantry_item_id":5,"pantry_item_name":"Pasta shelf"},
                       {"id":12,"recipe_id":1,"name":"Tomatoes","quantity":"1 1/2 cans","category":"Obst & Gemüse"}]}"""
    private val tikka = """{"id":2,"title":"Chicken Tikka","meal_types":["dinner"],"servings":null,"source":"mealie",
        "provider_account_name":"Home Mealie","provider_has_image":0,"ingredients":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}".also {
                if (request.method != "GET") writes += "$it ${request.body?.utf8().orEmpty()}"
            }) {
                "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                "POST /api/v1/auth/login" -> json(payload(), "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                "GET /api/v1/auth/me" -> json(payload())
                "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null,"meal_type_names":{"dinner":"Supper"}}}""")
                "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0}""")
                "GET /api/v1/recipes" -> json("""{"data":[$tikka,$spaghetti]}""")
                "GET /api/v1/shopping" -> json("""{"data":[{"id":1,"name":"Weekly"},{"id":2,"name":"Market"}]}""")
                "GET /api/v1/shopping/categories" -> json("""{"data":[{"id":1,"name":"Obst & Gemüse"},{"id":2,"name":"Haushalt"},{"id":3,"name":"Sonstiges"}]}""")
                "GET /api/v1/meals" -> json("""{"data":[{"id":40,"recipe_id":1,"title":"Spaghetti Bolognese"}]}""")
                "GET /api/v1/pantry" -> json("""{"data":[{"id":5,"name":"Pasta shelf","location_name":"Cupboard"},{"id":6,"name":"Tinned tomatoes"}]}""")
                "POST /api/v1/meals" -> json("""{"data":{"id":41}}""", code = 201)
                "POST /api/v1/recipes/1/to-shopping-list" -> json("""{"data":{"transferred":1,"skipped":1,"added_ids":[90]}}""")
                "PUT /api/v1/recipes/1/ingredient-match" -> json("""{"data":{"name":"Tomatoes","ingredient_key":"tomatoes","pantry_item_id":6,"pantry_item_name":"Tinned tomatoes"}}""")
                "POST /api/v1/recipes" -> json("""{"data":{"id":3,"title":"Leek soup","source":"native","meal_types":["breakfast","lunch","dinner","snack"],"ingredients":[{"id":31,"name":"Leek","quantity":"2"}]}}""", code = 201)
                "DELETE /api/v1/recipes/1" -> MockResponse.Builder().code(204).build()
                else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun start(): Strings {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        val strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.more")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.more")).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.recipes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.recipes")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Spaghetti Bolognese").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
        return strings
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun write(prefix: String): String {
        compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }
        return writes.first { it.startsWith(prefix) }.substringAfter("$prefix ")
    }

    @Test fun `list, detail, scaling, shopping list, plan and pantry link`() {
        val strings = start()

        // RECIPES-A7: the row tells the ingredient count, the source and "planned this week".
        compose.onNodeWithText(strings.t("recipes.plannedThisWeek"), substring = true).assertExists()
        compose.onNodeWithText(strings.t("recipes.sourceMealie") + " · Home Mealie").assertExists()
        compose.onNodeWithContentDescription(strings.t("recipes.sourceFilterLabel") + ": " + strings.t("recipes.sourceAll")).assertExists()

        // Search narrows by ingredient name.
        compose.onNode(hasSetTextAction()).performTextInput("tomato")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Chicken Tikka").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(strings.t("common.searchClear")).performClick()
        waitForText("Chicken Tikka")

        // RECIPES-A8: the detail scales with the servings stepper.
        compose.onNodeWithText("Spaghetti Bolognese").performClick()
        waitForText("200 g")
        compose.onNodeWithText("1 1/2 cans").assertExists()
        compose.onNodeWithText("Pasta shelf").assertExists()
        compose.onNodeWithText("Supper").assertExists()
        compose.onNodeWithContentDescription(strings.t("recipes.servingsMore")).performClick()
        waitForText("250 g")
        compose.onNodeWithText("1.88 cans").assertExists()

        // RECIPES-A9: two lists ask which one; the scaled quantities go with the row ids.
        compose.onNodeWithText(strings.t("common.toShoppingList")).performScrollTo().performClick()
        compose.onNodeWithText("Market").performClick()
        assertEquals("""{"listId":2,"quantities":["250 g","1.88 cans"],"ingredientIds":[11,12]}""", write("POST /api/v1/recipes/1/to-shopping-list"))
        waitForText(strings.t("recipes.toShoppingSuccess", "count" to 1, "list" to "Market"), substring = true)

        // RECIPES-A10: plan as a meal, dinner preselected, with the scaled ingredients.
        compose.onNodeWithText(strings.t("recipes.addToMeals")).performScrollTo().performClick()
        waitForText(strings.t("recipes.planTitle", "name" to "Spaghetti Bolognese"))
        compose.onAllNodesWithText(strings.t("common.apply")).onLast().performClick()
        val meal = write("POST /api/v1/meals")
        assertTrue(meal, meal.contains(""""meal_type":"dinner"""") && meal.contains(""""recipe_id":1""") && meal.contains(""""quantity":"250 g""""))

        // RECIPES-A11: change a link from the ingredient row; Yuvomi suggests nothing.
        compose.onNodeWithContentDescription(strings.t("recipes.ingredientMatchAction", "name" to "Spaghetti")).performScrollTo().performClick()
        waitForText(strings.t("recipes.ingredientMatchTitle", "name" to "Spaghetti"))
        waitForText("Tinned tomatoes")
        compose.onNodeWithText("Pasta shelf · Cupboard").assertExists()
        compose.onNodeWithText("Tinned tomatoes").performClick()
        compose.onNodeWithText(strings.t("common.save")).performClick()
        assertEquals("""{"name":"Spaghetti","pantryItemId":6}""", write("PUT /api/v1/recipes/1/ingredient-match"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pasta shelf").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Tinned tomatoes").assertExists()
        // The bulk entry offers the one ingredient without a link.
        compose.onNodeWithText(strings.t("recipes.ingredientMatchOpen", "count" to 1)).assertExists()
    }

    @Test fun `the form creates a recipe with its ingredients`() {
        val strings = start()
        compose.onNodeWithContentDescription(strings.t("recipes.addRecipe")).performClick()
        waitForText(strings.t("recipes.servingsHint"))
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Leek soup")
        compose.onNodeWithText(strings.t("meals.addIngredient")).performScrollTo().performClick()
        // Title, servings, then the new row's name and quantity.
        compose.onAllNodes(hasSetTextAction())[2].performTextInput("Leek")
        compose.onAllNodes(hasSetTextAction())[3].performTextInput("2")
        compose.onNodeWithText(strings.t("common.add")).performClick()
        assertEquals(
            """{"title":"Leek soup","notes":null,"recipe_url":null,"meal_types":["breakfast","lunch","dinner","snack"],"servings":null,""" +
                """"ingredients":[{"name":"Leek","quantity":"2","category":"Sonstiges"}]}""",
            write("POST /api/v1/recipes"),
        )
        waitForText("Leek soup")
    }

    @Test fun `delete waits for its undo window`() {
        val strings = start()
        val menu = strings.t("common.moreActionsNamed", "name" to "Spaghetti Bolognese")
        // RECIPES-A13: the row goes at once; undo brings it back and sends nothing.
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        waitForText(strings.t("recipes.deleted"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Spaghetti Bolognese").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        waitForText("Spaghetti Bolognese")
        assertTrue(writes.none { it.startsWith("DELETE") })
        // Without undo the delete is sent when the window closes.
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        write("DELETE /api/v1/recipes/1")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Spaghetti Bolognese").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun `a read-only member reads but cannot change`() {
        modules = """{"meals":"read","shopping":"write","pantry":"read"}"""
        val strings = start()
        // RECIPES-A12: no FAB and no row menu.
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("recipes.addRecipe")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Spaghetti Bolognese")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Spaghetti Bolognese").performClick()
        waitForText("200 g")
        // Category on the ingredient, all meals as chips, the link without its action.
        compose.onNodeWithText(strings.t("shopping.catFruitVeg")).assertExists()
        compose.onNodeWithText(strings.t("meals.typeLunch")).assertExists()
        compose.onNodeWithText("Pasta shelf").assertExists()
        assertTrue(compose.onAllNodesWithText(strings.t("recipes.addToMeals")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.edit")).fetchSemanticsNodes().isEmpty())
        // Shopping write keeps the transfer (`meals: read` is enough for that path).
        compose.onNodeWithText(strings.t("common.toShoppingList")).assertExists()
    }
}
