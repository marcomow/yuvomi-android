package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.meals.IngredientRow
import io.github.marcomow.yuvomi.feature.meals.Meal
import io.github.marcomow.yuvomi.feature.meals.MealBodies
import io.github.marcomow.yuvomi.feature.meals.MealEditorViewModel
import io.github.marcomow.yuvomi.feature.meals.MealPlan
import io.github.marcomow.yuvomi.feature.meals.MealsApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/**
 * docs/spec/meals.md against the real server: one or more tests per operation of
 * `MealsOperation`, plus the shared week list as the plan reads it. Every fixture lives in a
 * far-future week of its own and is removed again.
 */
class MealsContractTest : ContractTestBase() {
    private fun alex(): MealsApi = MealsApi(signedIn("alex").first)

    /**
     * A Monday years ahead, different per test run, so slots start empty. Fixtures stay off
     * Friday dinner, where the seeded pizza series lands every week.
     */
    private fun freeWeek(): LocalDate =
        MealPlan.startOfWeek(LocalDate.of(2040, 1, 1).plusWeeks(Random.nextLong(0, 2000)), DayOfWeek.MONDAY)

    private fun key() = YuvomiClient.newIdempotencyKey()

    private fun form(title: String, date: LocalDate, type: String = "dinner", rows: List<IngredientRow> = emptyList(), weekly: Boolean = false) =
        MealEditorViewModel.Form(title = title, mealType = type, date = date, rows = rows, repeatWeekly = weekly)

    private suspend fun MealsApi.weekMeals(date: LocalDate): List<Meal> = MealsApi.decodeWeek(week(date))

    private suspend fun MealsApi.cleanup(vararg ids: Int) = ids.forEach { runCatching { delete(it, "series") }; runCatching { delete(it) } }

    @Test fun `MEALS-A17 the week, preferences and pickers decode for alex`() = runBlocking {
        val api = alex()
        val prefs = MealsApi.decodePrefs(api.preferences())
        assertTrue(MealPlan.visibleTypes(prefs).isNotEmpty())
        assertNotNull(prefs.weekStart)
        val start = MealPlan.startOfWeek(LocalDate.now(), MealPlan.weekStartDay(prefs.weekStart))
        api.weekMeals(start).forEach { assertTrue(MealPlan.inWeek(LocalDate.parse(it.date), start)) }
        assertTrue(MealsApi.decodeRecipes(api.recipes()).isNotEmpty())
        MealsApi.decodeCategories(api.categories())
        assertTrue(MealsApi.decodeLists(api.lists()).all { it.id > 0 })
    }

    @Test fun `MEALS-A18 family members are the cooks to pick`() = runBlocking {
        val (client, me) = signedIn("alex")
        val members = MealsApi.decodeMembers(MealsApi(client).members())
        assertTrue(members.any { it.id == me.user.id })
        assertTrue(members.all { it.displayName.isNotEmpty() })
    }

    @Test fun `MEALS-A19 suggestions find earlier meals by their start`() = runBlocking {
        val api = alex()
        val title = unique("Zucchini bake")
        val meal = api.create(MealBodies.create(form(title, freeWeek())), key())
        try {
            val hits = api.suggestions(title.take(14))
            assertTrue(hits.any { it.title == title && it.mealType == "dinner" })
            assertTrue(api.suggestions(title.drop(3)).none { it.title == title })
        } finally {
            api.cleanup(meal.id)
        }
    }

    @Test fun `MEALS-A20 create with cook and ingredients, idempotent on retry`() = runBlocking {
        val (client, me) = signedIn("alex")
        val api = MealsApi(client)
        val date = freeWeek().plusDays(2)
        val body = MealBodies.create(form(unique("Stew"), date, rows = listOf(IngredientRow("Carrots", "3"))).copy(cookId = me.user.id))
        val k = key()
        val meal = api.create(body, k)
        try {
            assertEquals(me.user.id, meal.cookUserId)
            assertEquals(listOf("Carrots"), meal.ingredients.map { it.name })
            assertEquals("Sonstiges", meal.ingredients.single().category)
            assertEquals(meal.id, api.create(body, k).id)
            assertEquals(1, api.weekMeals(date).count { it.id == meal.id })
        } finally {
            api.cleanup(meal.id)
        }
    }

    @Test fun `MEALS-A21 apply-plan creates from recipes and replaces a slot`() = runBlocking {
        val api = alex()
        val recipe = MealsApi.decodeRecipes(api.recipes()).first()
        val date = freeWeek()
        val first = api.applyPlan(listOf(MealPlan.payloadFromRecipe(recipe, date, "lunch")), replaceExisting = false, key())
        val second = api.applyPlan(listOf(MealPlan.payloadFromRecipe(recipe, date, "lunch")), replaceExisting = true, key())
        try {
            assertEquals(recipe.id, first.single().recipeId)
            val slot = api.weekMeals(date).filter { it.date == date.toString() && it.mealType == "lunch" }
            assertEquals(listOf(second.single().id), slot.map { it.id })
        } finally {
            api.cleanup(*(first + second).map { it.id }.toIntArray())
        }
    }

    @Test fun `MEALS-A22 apply-plan refuses an unknown recipe with its reason`() = runBlocking {
        val api = alex()
        val payload = buildJsonObject {
            put("date", freeWeek().toString())
            put("meal_type", "lunch")
            put("title", unique("Ghost"))
            put("recipe_id", 99_999_999)
        }
        try {
            api.applyPlan(listOf(payload), false, key()); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(400, e.status)
            assertEquals("meals_selected_recipe_not_found", e.reason)
        }
    }

    @Test fun `MEALS-A23 a single edit moves the meal and sets or clears the cook`() = runBlocking {
        val (client, me) = signedIn("alex")
        val api = MealsApi(client)
        val date = freeWeek()
        val meal = api.create(MealBodies.create(form(unique("Curry"), date)), key())
        try {
            val f = form("Curry moved", date.plusDays(1), type = "lunch").copy(cookId = me.user.id)
            val updated = api.update(meal.id, MealBodies.single(f))
            assertEquals(date.plusDays(1).toString(), updated.date)
            assertEquals("lunch", updated.mealType)
            assertEquals(me.user.id, updated.cookUserId)
            assertNull(api.update(meal.id, MealBodies.single(f.copy(cookId = null))).cookUserId)
        } finally {
            api.cleanup(meal.id)
        }
    }

    @Test fun `MEALS-A24 a series edit renames every week and keeps the cook unless sent`() = runBlocking {
        val (client, me) = signedIn("alex")
        val api = MealsApi(client)
        val date = freeWeek()
        val meal = api.create(
            MealBodies.create(form(unique("Taco night"), date, weekly = true).copy(cookId = me.user.id, repeatUntil = date.plusWeeks(3))), key(),
        )
        try {
            assertNotNull(meal.recurrenceTemplateId)
            val next = api.weekMeals(date.plusWeeks(1)).single { it.recurrenceTemplateId == meal.recurrenceTemplateId }
            val renamed = form("Taco night (series)", date).copy(repeatUntil = date.plusWeeks(3))
            api.update(meal.id, MealBodies.series(renamed), series = true)
            val after = api.weekMeals(date.plusWeeks(1)).single { it.id == next.id }
            assertEquals("Taco night (series)", after.title)
            assertEquals(me.user.id, after.cookUserId)
            assertEquals(date.plusWeeks(3).toString(), after.recurrenceEndDate)
            // Past the end there is nothing.
            assertTrue(api.weekMeals(date.plusWeeks(4)).none { it.recurrenceTemplateId == meal.recurrenceTemplateId })
        } finally {
            api.cleanup(meal.id)
        }
    }

    @Test fun `MEALS-A25 delete - one meal is gone, a second delete is a 404, future ends the series`() = runBlocking {
        val api = alex()
        val date = freeWeek()
        val single = api.create(MealBodies.create(form(unique("Soup"), date)), key())
        api.delete(single.id)
        try {
            api.delete(single.id); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(404, e.status)
        }
        val series = api.create(MealBodies.create(form(unique("Pizza Friday"), date, weekly = true)), key())
        try {
            val later = api.weekMeals(date.plusWeeks(2)).single { it.recurrenceTemplateId == series.recurrenceTemplateId }
            api.delete(later.id, "future")
            assertTrue(api.weekMeals(date.plusWeeks(2)).none { it.recurrenceTemplateId == series.recurrenceTemplateId })
            assertTrue(api.weekMeals(date.plusWeeks(1)).any { it.recurrenceTemplateId == series.recurrenceTemplateId })
            api.delete(series.id, "series")
            assertTrue(api.weekMeals(date.plusWeeks(1)).none { it.recurrenceTemplateId == series.recurrenceTemplateId })
        } finally {
            api.cleanup(series.id)
        }
    }

    @Test fun `MEALS-A26 ingredients are added, edited and deleted`() = runBlocking {
        val api = alex()
        val date = freeWeek()
        val meal = api.create(MealBodies.create(form(unique("Salad"), date)), key())
        try {
            val added = api.addIngredient(meal.id, IngredientRow("Lettuce", "1", "Obst & Gemüse"), key())
            assertEquals("Lettuce", added.name)
            assertFalse(added.onShoppingList)
            val edited = api.updateIngredient(added.id!!, IngredientRow("Rocket", "", "Sonstiges"))
            assertEquals("Rocket", edited.name)
            assertNull(edited.quantity)
            assertEquals("Sonstiges", edited.category)
            api.deleteIngredient(added.id!!)
            assertTrue(api.weekMeals(date).single { it.id == meal.id }.ingredients.isEmpty())
            try {
                api.deleteIngredient(added.id!!); fail()
            } catch (e: ApiProblem.Http) {
                assertEquals(404, e.status)
            }
        } finally {
            api.cleanup(meal.id)
        }
    }

    /** A shopping list of the test's own, removed afterwards. */
    private fun <T> withList(client: YuvomiClient, block: suspend (Int) -> T): T = runBlocking {
        val created = raw(client, "POST", "/api/v1/shopping", """{"name":"${unique("Meals contract")}"}""")
        val id = created.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.int
        try {
            block(id)
        } finally {
            raw(client, "DELETE", "/api/v1/shopping/$id", "", tolerate = true)
        }
    }

    @Test fun `MEALS-A27 a meal's ingredients go to a list once, and undo opens them again`() {
        val client = signedIn("alex").first
        val api = MealsApi(client)
        withList(client) { listId ->
            val date = freeWeek()
            val meal = api.create(MealBodies.create(form(unique("Chili"), date, rows = listOf(IngredientRow("Beans", "2"), IngredientRow("Onion", "1")))), key())
            try {
                val result = api.toShoppingList(meal.id, listId, key())
                assertEquals(2, result.transferred)
                assertEquals(2, result.addedIds.size)
                assertTrue(api.weekMeals(date).single { it.id == meal.id }.ingredients.all { it.onShoppingList })
                assertEquals(0, api.toShoppingList(meal.id, listId, key()).transferred)
                api.undoTransfer(result)
                assertTrue(api.weekMeals(date).single { it.id == meal.id }.ingredients.none { it.onShoppingList })
            } finally {
                api.cleanup(meal.id)
            }
        }
    }

    @Test fun `MEALS-A28 a recipe meal sends the recipe's ingredients`() {
        val client = signedIn("alex").first
        val api = MealsApi(client)
        withList(client) { listId ->
            val recipe = MealsApi.decodeRecipes(api.recipes()).first { it.ingredients.isNotEmpty() }
            val date = freeWeek()
            val meal = api.applyPlan(listOf(MealPlan.payloadFromRecipe(recipe, date, "dinner").let {
                buildJsonObject { it.forEach { (k, v) -> if (k != "ingredients") put(k, v) } }
            }), false, key()).single()
            try {
                val listed = api.weekMeals(date).single { it.id == meal.id }
                assertTrue(MealPlan.hasOpenIngredients(listed))
                assertEquals(recipe.ingredients.size, MealPlan.ingredientCount(listed))
                val result = api.toShoppingList(meal.id, listId, key())
                assertTrue(result.transferred > 0)
                assertTrue(api.weekMeals(date).single { it.id == meal.id }.ingredients.isNotEmpty())
                api.undoTransfer(result)
            } finally {
                api.cleanup(meal.id)
            }
        }
    }

    @Test fun `MEALS-A29 the whole week goes to a list`() {
        val client = signedIn("alex").first
        val api = MealsApi(client)
        withList(client) { listId ->
            val date = freeWeek()
            val a = api.create(MealBodies.create(form(unique("Ramen"), date, rows = listOf(IngredientRow("Noodles")))), key())
            val b = api.create(MealBodies.create(form(unique("Bowl"), date.plusDays(3), rows = listOf(IngredientRow("Rice")))), key())
            try {
                // The seeded Friday pizza series has its week here too, so at least our two.
                val result = api.weekToShoppingList(listId, date.plusDays(1), key())
                assertTrue(result.transferred >= 2)
                assertTrue(api.weekMeals(date).filter { it.id == a.id || it.id == b.id }.all { m -> m.ingredients.all { it.onShoppingList } })
                api.undoTransfer(result)
                assertTrue(api.weekMeals(date).filter { it.id == a.id || it.id == b.id }.all { m -> m.ingredients.none { it.onShoppingList } })
            } finally {
                api.cleanup(a.id, b.id)
            }
        }
    }

    @Test fun `MEALS-A30 an unknown list is a 404 and nothing is marked`() = runBlocking {
        val api = alex()
        val date = freeWeek()
        val meal = api.create(MealBodies.create(form(unique("Wraps"), date, rows = listOf(IngredientRow("Tortillas")))), key())
        try {
            api.toShoppingList(meal.id, 99_999_999, key()); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(404, e.status)
        } finally {
            assertTrue(api.weekMeals(date).single { it.id == meal.id }.ingredients.none { it.onShoppingList })
            api.cleanup(meal.id)
        }
    }

    @Test fun `MEALS-A31 save as recipe creates a recipe the plan can pick`() = runBlocking {
        val (client, _) = signedIn("alex")
        val api = MealsApi(client)
        val title = unique("Saved bake")
        val recipe = api.createRecipe(buildJsonObject {
            put("title", title)
            put("ingredients", MealBodies.ingredientsJson(listOf(IngredientRow("Potatoes", "1 kg"))))
        }, key())
        try {
            assertEquals(title, recipe.title)
            assertTrue(MealsApi.decodeRecipes(api.recipes()).any { it.id == recipe.id && it.ingredients.single().name == "Potatoes" })
        } finally {
            raw(client, "DELETE", "/api/v1/recipes/${recipe.id}", "", tolerate = true)
        }
    }
}
