package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate

/** docs/spec/meals.md: the plan's rules, ported from public/pages/meals.js and its utils. */
class MealPlanTest {
    private val strings = Strings(
        "en",
        mapOf("meals.typeBreakfast" to "Breakfast", "meals.typeDinner" to "Dinner", "shopping.catDairy" to "Dairy"),
        emptyMap(),
    ) { "other" }

    private fun recipe(id: Int, title: String = "R$id", types: List<String>? = null, ingredients: List<RecipeIngredient> = emptyList(), servings: Double? = null) =
        MealRecipe(id, title, mealTypes = types?.let { JsonArray(it.map(::JsonPrimitive)) }, ingredients = ingredients, servings = servings)

    private fun meal(id: Int, date: String, type: String, ingredients: List<MealIngredient> = emptyList(), recipeId: Int? = null, recipeCount: Int = 0) =
        Meal(id, date, type, "M$id", ingredients = ingredients, recipeId = recipeId, recipeIngredientCount = recipeCount)

    // MEALS-A1
    @Test fun `the week starts on the household's day`() {
        val wed = LocalDate.parse("2026-11-04")
        assertEquals(LocalDate.parse("2026-11-02"), MealPlan.startOfWeek(wed, MealPlan.weekStartDay("monday")))
        assertEquals(LocalDate.parse("2026-11-01"), MealPlan.startOfWeek(wed, MealPlan.weekStartDay("sunday")))
        assertEquals(LocalDate.parse("2026-10-31"), MealPlan.startOfWeek(wed, MealPlan.weekStartDay("saturday")))
        assertEquals(DayOfWeek.MONDAY, MealPlan.weekStartDay("nonsense"))
        assertEquals(DayOfWeek.MONDAY, MealPlan.weekStartDay(null))
        // A Sunday in a Monday week belongs to the week before.
        assertEquals(LocalDate.parse("2026-10-26"), MealPlan.startOfWeek(LocalDate.parse("2026-11-01"), DayOfWeek.MONDAY))
        assertEquals("meals.daySo", MealPlan.dayNameKey(LocalDate.parse("2026-11-01")))
        assertEquals(7, MealPlan.weekDays(wed).size)
    }

    // MEALS-A2
    @Test fun `visible types and the household's names for them`() {
        val prefs = ApiJson.decodeFromString(MealPrefs.serializer(),
            """{"visible_meal_types":["dinner","breakfast","brunch"],"meal_type_names":{"dinner":"  Supper ","lunch":""},"week_start":"sunday"}""")
        assertEquals(listOf("dinner", "breakfast"), MealPlan.visibleTypes(prefs))
        assertEquals(listOf("breakfast", "dinner"), MealPlan.visibleTypesOrdered(MealPlan.visibleTypes(prefs)))
        val names = MealPlan.typeNames(prefs)
        assertEquals("Supper", MealPlan.typeLabel("dinner", names, strings))
        assertEquals("Breakfast", MealPlan.typeLabel("breakfast", names, strings))
        assertEquals(MealPlan.MEAL_TYPE_KEYS, MealPlan.visibleTypes(MealPrefs()))
        assertEquals(emptyMap<String, String>(), MealPlan.typeNames(MealPrefs(mealTypeNames = JsonNull)))
    }

    @Test fun `recipe meal types - missing is all, empty is none`() {
        assertEquals(MealPlan.MEAL_TYPE_KEYS, MealPlan.recipeMealTypes(null))
        assertEquals(listOf("lunch", "dinner"), MealPlan.recipeMealTypes(JsonPrimitive("lunch, dinner,lunch,brunch")))
        val none = recipe(1, types = emptyList())
        assertFalse(MealPlan.recipeSupports(none, "lunch"))
        assertTrue(MealPlan.recipeAllows(none, "lunch"))
        val dinner = recipe(2, types = listOf("dinner"))
        assertFalse(MealPlan.recipeAllows(dinner, "lunch"))
    }

    // MEALS-A3
    @Test fun `a tapped recipe goes to the meal of the hour, on the first free day from today`() {
        val start = LocalDate.parse("2026-11-02")
        val today = LocalDate.parse("2026-11-04")
        val meals = listOf(meal(1, "2026-11-04", "dinner"))
        val any = recipe(1)
        assertEquals(MealPlan.Target(LocalDate.parse("2026-11-05"), "dinner"), MealPlan.railTarget(any, start, today, 19, MealPlan.MEAL_TYPE_KEYS, meals))
        assertEquals(MealPlan.Target(today, "breakfast"), MealPlan.railTarget(any, start, today, 7, MealPlan.MEAL_TYPE_KEYS, meals))
        // Lunch is not allowed: the next allowed one after it.
        val dinnerOnly = recipe(2, types = listOf("breakfast", "dinner"))
        assertEquals("dinner", MealPlan.railTarget(dinnerOnly, start, today, 12, MealPlan.MEAL_TYPE_KEYS, emptyList()).mealType)
        // Another week: from its first day.
        assertEquals(LocalDate.parse("2026-11-09"), MealPlan.railTarget(any, LocalDate.parse("2026-11-09"), today, 12, MealPlan.MEAL_TYPE_KEYS, emptyList()).date)
        // A full week stays on the first day.
        val full = MealPlan.weekDays(start).map { meal(it.dayOfMonth, it.toString(), "lunch") }
        assertEquals(today, MealPlan.railTarget(any, start, today, 12, MealPlan.MEAL_TYPE_KEYS, full).date)
    }

    // MEALS-A4
    @Test fun `the random plan fills open slots and avoids repeats`() {
        val start = LocalDate.parse("2026-11-02")
        val recipes = listOf(recipe(1, types = listOf("lunch", "dinner")), recipe(2, types = listOf("lunch", "dinner")), recipe(3, types = emptyList()))
        val meals = listOf(meal(9, "2026-11-02", "lunch"))
        val plan = MealPlan.randomPlan(start, listOf("lunch", "dinner"), meals, recipes, replaceExisting = false) { 0.0 }
        assertEquals(13, plan.assignments.size)
        assertTrue(plan.assignments.none { it.date == start && it.mealType == "lunch" })
        // Never the same recipe twice on one day, nor on two days running for one meal.
        plan.assignments.groupBy { it.date }.values.forEach { day -> assertEquals(day.size, day.map { it.recipe.id }.distinct().size) }
        assertTrue(plan.assignments.none { it.recipe.id == 3 })
        assertEquals(emptyList<Int>(), plan.deleteMealIds)

        val replace = MealPlan.randomPlan(start, listOf("lunch"), meals, recipes, replaceExisting = true) { 0.5 }
        assertEquals(7, replace.assignments.size)
        assertEquals(listOf(9), replace.deleteMealIds)

        val full = MealPlan.weekDays(start).map { meal(it.dayOfMonth, it.toString(), "lunch") }
        assertEquals("week_full", MealPlan.randomPlan(start, listOf("lunch"), full, recipes, false).reason)
        assertEquals("no_compatible_recipes", MealPlan.randomPlan(start, listOf("snack"), emptyList(), recipes, false).reason)
    }

    @Test fun `a recipe's payload carries its ingredients with the default category`() {
        val r = MealRecipe(4, "Soup", notes = "", recipeUrl = "https://x", ingredients = listOf(RecipeIngredient("Leek", "", null)))
        val body = MealPlan.payloadFromRecipe(r, LocalDate.parse("2026-11-03"), "dinner")
        assertEquals("2026-11-03", body["date"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["notes"])
        assertEquals(4, body["recipe_id"]!!.jsonPrimitive.content.toInt())
        val ing = body["ingredients"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, ing["quantity"])
        assertEquals("Sonstiges", ing["category"]!!.jsonPrimitive.content)
    }

    // MEALS-A5
    @Test fun `title suggestions - recipes by any part, prefix first, then earlier meals`() {
        val recipes = listOf(recipe(1, "Porridge"), recipe(2, "Ridge soup"), recipe(3, "Pasta"))
        val history = listOf(MealSuggestion("ridgeback stew"), MealSuggestion("Porridge"), MealSuggestion(""))
        val result = MealPlan.titleSuggestions("ridge", recipes, history)
        assertEquals(listOf("Ridge soup", "Porridge", "ridgeback stew"), result.map { it.title })
        assertEquals(listOf(2, 1, null), result.map { it.recipeId })
        assertEquals(emptyList<MealPlan.TitleSuggestion>(), MealPlan.titleSuggestions("  ", recipes, history))
    }

    // MEALS-A6
    @Test fun `quantities scale and free text stays`() {
        assertEquals("500 g", MealPlan.scaleQuantity("250 g", 2.0))
        assertEquals("3 cups", MealPlan.scaleQuantity("1 1/2 cups", 2.0))
        assertEquals("0.25", MealPlan.scaleQuantity("1/2", 0.5))
        assertEquals("1,5 kg", MealPlan.scaleQuantity("3 kg", 0.5, ','))
        assertEquals("3 kg", MealPlan.scaleQuantity("1,5 kg", 2.0, ','))
        assertEquals("a pinch", MealPlan.scaleQuantity("a pinch", 2.0))
        assertEquals("1.000 g", MealPlan.scaleQuantity("1.000 g", 2.0, ','))
        assertEquals("250 g", MealPlan.scaleQuantity("250 g", 1.0))
        assertEquals("0.67", MealPlan.format(2.0 / 3))
    }

    @Test fun `servings step within the stepper's range`() {
        assertEquals(MealPlan.ServingsRange(1.0, 99.0, 1.0), MealPlan.servingsRange(4.0))
        assertEquals(MealPlan.ServingsRange(0.5, 10.0, 0.5), MealPlan.servingsRange(null))
        assertEquals(1.5, MealPlan.servingsFactor(4.0, 6.0), 0.0)
        assertEquals(2.0, MealPlan.servingsFactor(null, 2.0), 0.0)
        assertEquals(0.5, MealPlan.clampServings(null, 0.0), 0.0)
        assertEquals(99.0, MealPlan.clampServings(4.0, 120.0), 0.0)
    }

    // MEALS-A7
    @Test fun `a card offers the transfer while ingredients are open, the recipe's included`() {
        assertTrue(MealPlan.hasOpenIngredients(meal(1, "d", "lunch", recipeId = 3, recipeCount = 4)))
        assertFalse(MealPlan.hasOpenIngredients(meal(1, "d", "lunch")))
        val done = listOf(MealIngredient(1, name = "a", onShoppingList = true))
        assertFalse(MealPlan.hasOpenIngredients(meal(1, "d", "lunch", done)))
        assertTrue(MealPlan.allOnList(meal(1, "d", "lunch", done)))
        assertTrue(MealPlan.hasOpenIngredients(meal(1, "d", "lunch", done + MealIngredient(2, name = "b"))))
        assertEquals(4, MealPlan.ingredientCount(meal(1, "d", "lunch", recipeId = 3, recipeCount = 4)))
        val withRecipe = meal(1, "d", "lunch", recipeId = 3)
        assertEquals(listOf("Milk"), MealPlan.recipeIngredientsOf(withRecipe, listOf(recipe(3, ingredients = listOf(RecipeIngredient("Milk"))))).map { it.name })
        assertEquals(emptyList<RecipeIngredient>(), MealPlan.recipeIngredientsOf(withRecipe.copy(ingredients = done), listOf(recipe(3, ingredients = listOf(RecipeIngredient("Milk"))))))
    }

    @Test fun `categories - non-food ones are not offered, built-in names are translated`() {
        val cats = listOf(ShoppingCategoryRef(1, "Milchprodukte"), ShoppingCategoryRef(2, "Haushalt"), ShoppingCategoryRef(3, "Drogerie"), ShoppingCategoryRef(4, "Bio"))
        assertEquals(listOf("Milchprodukte", "Bio"), MealPlan.mealCategories(cats).map { it.name })
        assertEquals("Dairy", MealPlan.categoryLabel("Milchprodukte", strings))
        assertEquals("Bio", MealPlan.categoryLabel("Bio", strings))
        assertEquals("Milchprodukte", MealPlan.resolveCategory("Unknown", MealPlan.mealCategories(cats)))
        assertEquals("Sonstiges", MealPlan.resolveCategory(null, emptyList()))
    }

    // MEALS-A8
    @Test fun `a single edit deletes removed, patches changed and adds new ingredients`() {
        val meal = meal(5, "2026-11-03", "lunch", listOf(
            MealIngredient(1, name = "Milk", quantity = "1 l", category = "Milchprodukte"),
            MealIngredient(2, name = "Eggs", quantity = "6"),
            MealIngredient(3, name = "Salt"),
        ))
        val rows = listOf(
            IngredientRow("Milk", "1 l", "Milchprodukte", 1),
            IngredientRow("Eggs", "10", "Sonstiges", 2),
            IngredientRow("Flour", "500 g"),
            IngredientRow("  ", ""),
        )
        val sync = MealBodies.ingredientSync(meal, rows)
        assertEquals(listOf(3), sync.delete)
        assertEquals(listOf(2), sync.update.map { it.id })
        assertEquals(listOf("Flour"), sync.add.map { it.name })
    }

    // MEALS-A9
    @Test fun `the form's bodies follow saveModal`() {
        val f = MealEditorViewModel.Form(
            title = " Soup ", mealType = "dinner", date = LocalDate.parse("2026-11-03"), cookId = 2,
            rows = listOf(IngredientRow("Leek", " 2 ", "Sonstiges"), IngredientRow("", "1")),
            notes = "", recipeUrl = " https://a ", recipeId = 7, repeatWeekly = false, repeatUntil = LocalDate.parse("2026-12-01"),
        )
        val create = MealBodies.create(f)
        assertEquals("Soup", create["title"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, create["notes"])
        assertEquals("https://a", create["recipe_url"]!!.jsonPrimitive.content)
        assertEquals("", create["repeat_until"]!!.jsonPrimitive.content)
        assertEquals(1, create["ingredients"]!!.jsonArray.size)
        assertEquals("2", create["ingredients"]!!.jsonArray[0].jsonObject["quantity"]!!.jsonPrimitive.content)
        assertEquals("2026-12-01", MealBodies.create(f.copy(repeatWeekly = true))["repeat_until"]!!.jsonPrimitive.content)

        val series = MealBodies.series(f)
        assertFalse(series.containsKey("date"))
        assertFalse("the cook goes to the series only when picked", series.containsKey("cook_user_id"))
        assertEquals(2, MealBodies.series(f.copy(cookTouched = true))["cook_user_id"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, MealBodies.series(f.copy(cookTouched = true, cookId = null))["cook_user_id"])

        val single = MealBodies.single(f.copy(cookId = null))
        assertEquals(setOf("date", "meal_type", "title", "notes", "recipe_url", "recipe_id", "cook_user_id"), single.keys)
        assertEquals(JsonNull, single["cook_user_id"])
    }

    // MEALS-A10
    @Test fun `errors map to upstream's sentences`() {
        assertEquals("common.errorOfflineMutation", MealsErrors.key(ApiProblem.Network(IOException("x"))))
        assertEquals("recipes.errorRecipeGone", MealsErrors.key(ApiProblem.Http(400, "meals_selected_recipe_not_found", null)))
        assertEquals("common.errorNoPermission", MealsErrors.key(ApiProblem.Http(403, "cross_module_access", null)))
        assertEquals("common.errorModuleNoAccess", MealsErrors.key(ApiProblem.Http(403, "module_access_denied", null)))
        assertEquals("android.meals.gone", MealsErrors.key(ApiProblem.Http(404, null, "Meal not found."), mealGoneOn404 = true))
        assertEquals("android.error.unsupported", MealsErrors.key(ApiProblem.Http(405, null, null)))
        assertEquals("common.errorGeneric", MealsErrors.key(ApiProblem.Http(400, null, "x")))
        assertEquals("common.errorRateLimited", MealsErrors.key(ApiProblem.Http(429, null, null)))
        assertEquals("meals.loadError", MealsErrors.loadKey(ApiProblem.Network(IOException("x"))))
    }

    @Test fun `answers decode with missing and extra fields`() {
        val week = MealsApi.decodeWeek(ApiJson.parseToJsonElement("""{"data":[{"id":1,"date":"2026-11-03","meal_type":"lunch","title":"Soup",
            "recurrence_template_id":null,"cook_user_id":2,"cook_name":"Emma","ingredients":[{"id":4,"name":"Leek","on_shopping_list":1}],
            "recipe_ingredient_count":0,"recipe_has_image":0,"something_new":true},{"id":2}],"weekStart":"2026-11-02"}"""))
        assertEquals(2, week.size)
        assertTrue(week[0].ingredients.single().onShoppingList)
        assertFalse(week[0].isRecurring)
        assertNull(week[1].recipeId)
        val recipes = MealsApi.decodeRecipes(ApiJson.parseToJsonElement("""{"data":[{"id":1,"title":"A","servings":4,"meal_types":["lunch"],"source":"mealie"}]}"""))
        assertEquals(4.0, recipes.single().servings!!, 0.0)
        val transfer = ApiJson.decodeFromJsonElement(TransferResult.serializer(),
            ApiJson.parseToJsonElement("""{"transferred":2,"skipped":1,"added_ids":[5,6],"ingredient_ids":[1,2]}"""))
        assertEquals(listOf(5, 6), transfer.addedIds)
        assertTrue(IngredientRow("x", "").toJson()["quantity"] is JsonNull)
        assertTrue(IngredientRow("x", "", "").toJson()["category"].let { it is JsonPrimitive && it.content == "Sonstiges" })
        assertEquals(JsonObject(emptyMap()), JsonObject(emptyMap()))
    }

    @Test fun `routes open the week and the form`() {
        assertEquals("meals", MealsRoutes.week())
        assertEquals("meals?week=2026-11-03", MealsRoutes.week("2026-11-03"))
        assertEquals("meals/edit?date=2026-11-03&id=5&type=lunch", MealsRoutes.editor(5, LocalDate.parse("2026-11-03"), "lunch"))
        assertEquals("meals/edit?date=2026-11-03&recipe=2", MealsRoutes.editor(date = LocalDate.parse("2026-11-03"), recipeId = 2))
        assertEquals("https://x.org/a", externalHref(" https://x.org/a "))
        assertNull(externalHref("javascript:alert(1)"))
    }
}
