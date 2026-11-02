package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pure logic of docs/spec/recipes.md, against upstream's own test cases where it has them. */
class RecipeLogicTest {

    private fun scaled(locale: String, quantity: String, factor: Double, expected: String) =
        assertEquals("$locale: \"$quantity\" x$factor", expected, ScaleQuantity.scale(quantity, factor, locale))

    // ── RECIPES-A1: test/test-meals.js, "Skalieren: ..." ──

    @Test fun `ordinary quantities scale`() {
        scaled("de", "250 g", 2.0, "500 g")
        scaled("de", "1 kg", 2.0, "2 kg")
        scaled("de", "3 EL", 0.5, "1,5 EL")
        scaled("de", "1 Zwiebel", 3.0, "3 Zwiebel")
        scaled("de", "1 1/2 Tassen", 2.0, "3 Tassen")
        scaled("de", "1/2 TL", 3.0, "1,5 TL")
        scaled("de", "eine Prise", 2.0, "eine Prise")
        scaled("de", "1.5 kg", 1.0, "1.5 kg")
    }

    @Test fun `separator and digits of the output follow the locale`() {
        scaled("de", "1.5 kg", 3.0, "4,5 kg")
        scaled("en-US", "1,5 kg", 3.0, "1,5 kg")
        scaled("en-US", "1.5 kg", 3.0, "4.5 kg")
        scaled("de", "1,5 kg", 2.0, "3 kg")
    }

    @Test fun `a grouped quantity is refused, not guessed`() {
        scaled("en-US", "1,000 g", 2.0, "1,000 g")
        scaled("de", "1.000 g", 2.0, "1.000 g")
        scaled("ar-EG", "٢٬٠٠٠ g", 2.0, "٢٬٠٠٠ g")
        scaled("de", "2 Dosen à 1.000 ml", 2.0, "4 Dosen à 1.000 ml")
        scaled("en-US", "2 cans à 1,000 ml", 2.0, "4 cans à 1,000 ml")
        scaled("de", "1,000 g", 2.0, "2 g")
        scaled("en-US", "1.000 g", 2.0, "2 g")
    }

    @Test fun `eastern digits are read`() {
        scaled("fa", "۲۵۰ g", 2.0, "۵۰۰ g")
        scaled("ar-EG", "١٫٥ kg", 2.0, "٣ kg")
        scaled("fa", "١٫٥ kg", 2.0, "۳ kg")
    }

    @Test fun `the rest of the line keeps its own digits`() {
        scaled("fa", "۲ x ۵۰۰ g", 2.0, "۴ x ۵۰۰ g")
        scaled("fa", "۲ x ۱٫۵ kg", 2.0, "۴ x ۱٫۵ kg")
        scaled("fa", "۱ ۱/۲ Tassen", 2.0, "۳ Tassen")
        scaled("de", "2 x 500 g", 2.0, "4 x 500 g")
        scaled("de", "2 Dosen à 0,5 l", 2.0, "4 Dosen à 0,5 l")
        scaled("de", "3 Glaeser à 250 ml", 2.0, "6 Glaeser à 250 ml")
        scaled("de", "  250 g  ", 2.0, "500 g")
        scaled("de", "𞥒 x 500 g", 2.0, "4 x 500 g")
        scaled("de", "𞥒 kg", 2.0, "4 kg")
        scaled("de", "𑜲𑜵𑜰 g", 2.0, "500 g")
    }

    @Test fun `the conversion is positional`() {
        for (locale in listOf("de", "en-US", "fa", "ar-EG")) {
            for (probe in listOf("۲ x ۵۰۰ g", "٢٬٠٠٠ g", "1,5 kg", "eine Prise", "🍎 2 kg", "1.000", "1 1/2 Tassen")) {
                val out = ScaleQuantity.toDecimalString(probe, locale)
                assertTrue("$locale: $probe -> $out", out.isEmpty() || out.length == probe.trim().length)
            }
        }
    }

    @Test fun `the multiplier notation stays readable`() {
        scaled("de", "2x500 g", 2.0, "4x500 g")
        scaled("de", "3x Dose", 2.0, "6x Dose")
        scaled("de", "2×500 ml", 2.0, "4×500 ml")
        scaled("de", "2 × 500 ml", 2.0, "4 × 500 ml")
    }

    @Test fun `a number cut off inside a separator stays as it is`() {
        scaled("fa", "1,5 kg", 2.0, "1,5 kg")
        scaled("ar-EG", "1,5 kg", 2.0, "1,5 kg")
        scaled("de", "1'000 g", 2.0, "1'000 g")
        scaled("de", "1/2,5 cup", 2.0, "1/2,5 cup")
        scaled("de", "1 1/2,5 Tassen", 2.0, "1 1/2,5 Tassen")
        scaled("en-US", "1/2.5 cup", 2.0, "1/2.5 cup")
        scaled("ar-EG", "١/٢٫٥ cup", 2.0, "١/٢٫٥ cup")
        scaled("ar-EG", "١ ١/٢٫٥ cup", 2.0, "١ ١/٢٫٥ cup")
        scaled("de", "1/2 cup", 2.0, "1 cup")
        scaled("ar-EG", "١ ١/٢ cup", 2.0, "٣ cup")
    }

    @Test fun `the written number stays server-readable`() {
        scaled("de", "1000 g", 2.0, "2000 g")
        scaled("en-US", "1000 g", 2.0, "2000 g")
        scaled("fr", "1,5 kg", 3.0, "4,5 kg")
    }

    @Test fun `empty and missing quantities pass through`() {
        assertNull(ScaleQuantity.scale(null, 2.0, "en"))
        assertEquals("", ScaleQuantity.scale("", 2.0, "en"))
        val scaled = ScaleQuantity.scaledIngredients(listOf(Ingredient(1, "Salt"), Ingredient(2, "Flour", "200 g")), 2.0, "en")
        assertEquals(listOf(null, "400 g"), scaled.map { it.quantity })
    }

    // ── RECIPES-A2: servings stepper (utils/servings-stepper.js) ──

    @Test fun `servings factor and stepper`() {
        assertEquals(1.5, ScaleQuantity.servingsFactor(4, 6.0), 0.0)
        assertEquals(2.0, ScaleQuantity.servingsFactor(null, 2.0), 0.0)
        assertEquals(1.0, ScaleQuantity.servingsFactor(4, 0.0), 0.0)
        assertEquals(5.0, ScaleQuantity.step(4, 4.0, 1), 0.0)
        assertEquals(1.0, ScaleQuantity.step(4, 1.0, -1), 0.0)
        assertEquals(99.0, ScaleQuantity.step(4, 99.0, 1), 0.0)
        assertEquals(1.5, ScaleQuantity.step(null, 1.0, 1), 0.0)
        assertEquals(0.5, ScaleQuantity.step(null, 0.5, -1), 0.0)
        assertEquals(10.0, ScaleQuantity.step(null, 10.0, 1), 0.0)
        assertEquals("1,5", ScaleQuantity.valueNumber(1.5, "de"))
    }

    // ── RECIPES-A3: meal types and links ──

    @Test fun `meal types normalise like upstream`() {
        assertEquals(MealTypes.KEYS, MealTypes.normalize(null))
        assertEquals(MealTypes.KEYS, MealTypes.normalize(JsonNull))
        assertEquals(emptyList<String>(), MealTypes.normalize(JsonArray(emptyList())))
        assertEquals(listOf("dinner", "lunch"), MealTypes.normalize(JsonPrimitive("dinner, lunch,dinner,brunch")))
        assertEquals("dinner", MealTypes.planPreselect(MealTypes.KEYS))
        assertEquals("lunch", MealTypes.planPreselect(listOf("lunch")))
        assertEquals("breakfast", MealTypes.planPreselect(listOf("breakfast", "snack")))
        assertEquals(MealTypes.KEYS, MealTypes.planChoices(recipe(mealTypes = "[]")))
    }

    @Test fun `meal chips only when they tell something`() {
        assertNull(MealTypes.detailChips(recipe(), readOnly = false))
        assertEquals(MealTypes.KEYS, MealTypes.detailChips(recipe(), readOnly = true))
        assertEquals(listOf("lunch", "dinner"), MealTypes.detailChips(recipe(mealTypes = """["dinner","lunch"]"""), readOnly = false))
        assertEquals(emptyList<String>(), MealTypes.detailChips(recipe(mealTypes = "[]"), readOnly = false))
    }

    @Test fun `the match key folds case and space only`() {
        assertEquals("olive oil", ingredientMatchKey("  Olive   OIL "))
        assertEquals("öl", ingredientMatchKey("Öl"))
        assertEquals("", ingredientMatchKey(null))
    }

    @Test fun `categories resolve like the ingredient row`() {
        val offered = ShoppingCategories.forIngredients(listOf("Obst & Gemüse", "Haushalt", "Drogerie", "Sonstiges"))
        assertEquals(listOf("Obst & Gemüse", "Sonstiges"), offered)
        assertEquals("Sonstiges", ShoppingCategories.resolve(null, offered))
        assertEquals("Obst & Gemüse", ShoppingCategories.resolve("Gone", offered))
        assertEquals("Sonstiges", ShoppingCategories.resolve("Gone", emptyList()))
    }

    // ── RECIPES-A4: search, source filter, rows ──

    @Test fun `search covers title, notes and ingredients, and the source filter narrows`() {
        val a = recipe(id = 1, title = "Soup", notes = "warm")
        val b = recipe(id = 2, title = "Cake", ingredients = """[{"id":1,"name":"Flour"}]""")
        val c = recipe(id = 3, title = "Curry", source = "mealie")
        val all = listOf(a, b, c)
        assertEquals(listOf(b), RecipeList.filtered(all, emptySet(), "FLOUR", "all"))
        assertEquals(listOf(a), RecipeList.filtered(all, emptySet(), " warm ", "all"))
        assertEquals(listOf(c), RecipeList.filtered(all, emptySet(), "", "mealie"))
        assertEquals(listOf(a, c), RecipeList.filtered(all, setOf(2), "", "all"))
        assertEquals(listOf("all", "native", "mealie"), RecipeList.sourceOptions(all))
        assertEquals(emptyList<String>(), RecipeList.sourceOptions(listOf(a, b)))
        assertTrue(RecipeList.showPlanned(a, all, emptySet(), setOf(1)))
        assertFalse(RecipeList.showPlanned(a, listOf(a), emptySet(), setOf(1)))
        assertTrue(RecipeList.showsThumb(c))
        assertFalse(RecipeList.showsThumb(a))
        assertFalse(RecipeList.hasDetail(recipe(id = 4), readOnly = false))
        assertTrue(RecipeList.hasDetail(recipe(id = 4), readOnly = true))
        assertEquals("https://x.y", externalHref(" https://x.y "))
        assertNull(externalHref("javascript:alert(1)"))
    }

    // ── RECIPES-A5: request bodies ──

    @Test fun `the form body is upstream's saveRecipe body`() {
        val body = RecipeBodies.save(
            title = " Soup ", notes = "  ", url = "", mealTypes = listOf("dinner", "breakfast"), servings = 4,
            rows = listOf(RecipeBodies.IngredientInput(" Leek ", " 2 ", "Obst & Gemüse"), RecipeBodies.IngredientInput(" ", "1", "")),
            image = null,
        )
        assertEquals(
            """{"title":"Soup","notes":null,"recipe_url":null,"meal_types":["breakfast","dinner"],"servings":4,""" +
                """"ingredients":[{"name":"Leek","quantity":"2","category":"Obst & Gemüse"}]}""",
            body.toString(),
        )
        assertEquals(JsonNull, RecipeBodies.save("a", "", "", emptyList(), null, emptyList(), ImageChange.Remove)["image_data"])
        assertFalse("image_data" in RecipeBodies.save("a", "", "", emptyList(), null, emptyList(), null))
    }

    @Test fun `duplicate, meal and transfer bodies`() {
        val r = recipe(id = 9, title = "Soup", notes = "", servings = 2, ingredients = """[{"id":11,"name":"Leek","quantity":"2"},{"id":12,"name":"Salt"}]""")
        val copy = RecipeBodies.duplicate(r, "copy")
        assertEquals("Soup (copy)", (copy["title"] as JsonPrimitive).content)
        assertFalse("meal_types" in copy)
        assertEquals(JsonNull, copy["notes"])
        assertEquals("Sonstiges", (copy["ingredients"]!!.jsonArray[1].jsonObject["category"] as JsonPrimitive).content)

        val meal = RecipeBodies.meal(r, ScaleQuantity.scaledIngredients(r.ingredients, 2.0, "en"), LocalDate.of(2026, 11, 4), "dinner")
        assertEquals("2026-11-04", (meal["date"] as JsonPrimitive).content)
        assertEquals(9, (meal["recipe_id"] as JsonPrimitive).content.toInt())
        assertEquals("4", (meal["ingredients"]!!.jsonArray[0].jsonObject["quantity"] as JsonPrimitive).content)

        assertEquals("""{"listId":3}""", RecipeBodies.transfer(3, r, 1.0, "en").toString())
        assertEquals("""{"listId":3,"quantities":["4",null],"ingredientIds":[11,12]}""", RecipeBodies.transfer(3, r, 2.0, "en").toString())
    }

    // ── RECIPES-A6: errors (utils/friendly-error.js) ──

    @Test fun `errors map to upstream's sentences`() {
        assertEquals("recipes.errorRecipeGone", RecipeErrors.key(ApiProblem.Http(404, "recipes_recipe_not_found", null)))
        assertEquals("recipes.errorMirrored", RecipeErrors.key(ApiProblem.Http(403, "recipe_mirrored", null)))
        assertEquals("common.errorNoPermission", RecipeErrors.key(ApiProblem.Http(403, "cross_module_access", null)))
        assertEquals("recipes.errorIngredientsChanged", RecipeErrors.key(ApiProblem.Http(409, "recipes_ingredients_changed", null)))
        assertEquals("shopping.errorListGone", RecipeErrors.key(ApiProblem.Http(404, "shopping_list_not_found", null)))
        assertEquals("common.errorRateLimited", RecipeErrors.key(ApiProblem.Http(429, null, null)))
        assertEquals("common.unknownError", RecipeErrors.key(ApiProblem.Http(500, null, null), "common.unknownError"))
        assertEquals("common.errorOfflineMutation", RecipeErrors.key(ApiProblem.Network(java.io.IOException())))
        assertTrue(RecipeErrors.isGone(ApiProblem.Http(404, "recipes_ingredient_not_found", null)))
        assertFalse(RecipeErrors.isGone(ApiProblem.Http(409, "recipes_ingredients_changed", null)))
        assertFalse(RecipeErrors.isGone(ApiProblem.Http(403, "recipes_recipe_not_found", null)))
    }

    @Test fun `access follows the two gates of each path`() {
        val readMeals = RecipeAccess(meals = "read", shopping = "write", pantry = "write")
        assertFalse(readMeals.canWrite)
        assertTrue(readMeals.canTransfer)
        assertFalse(readMeals.canMatch)
        assertFalse(RecipeAccess(meals = "write", shopping = "read").canTransfer)
        assertFalse(RecipeAccess(pantry = "read").canMatch)
        assertTrue(RecipeAccess(pantry = "read").pantryVisible)
        assertFalse(RecipeAccess(pantry = "none").pantryVisible)
    }

    @Test fun `answers decode leniently`() {
        val answer = ApiJson.parseToJsonElement(
            """{"data":[{"id":1,"title":"A","meal_types":["lunch"],"has_own_image":true,"servings":null,
               "provider_has_image":1,"provider_updated_at":"2026-10-01T10:00:00Z","source":"mealie",
               "ingredients":[{"id":5,"name":"Salt","pantry_item_id":7,"pantry_item_name":"Sea salt","extra":1}]},{"bad":true}]}""",
        )
        val recipes = RecipeJson.recipes(answer)
        assertEquals(1, recipes.size)
        assertEquals(listOf("lunch"), recipes[0].mealTypes)
        assertTrue(recipes[0].hasOwnImage)
        assertEquals("/api/v1/recipes/1/image", RecipesApi.thumbnailPath(recipes[0]))
        assertEquals(
            "/api/v1/recipes/1/provider-thumbnail?v=2026-10-01T10%3A00%3A00Z",
            RecipesApi.thumbnailPath(recipes[0].copy(hasOwnImage = false)),
        )
        assertEquals(recipes, RecipeJson.recipes(ApiJson.parseToJsonElement(RecipeJson.encodeRecipes(recipes))))
        assertEquals(setOf(3), RecipeJson.plannedRecipeIds(ApiJson.parseToJsonElement("""{"data":[{"recipe_id":3},{"recipe_id":null}]}""")))
        assertEquals(
            mapOf("dinner" to "Supper"),
            RecipeJson.mealTypeNames(ApiJson.parseToJsonElement("""{"data":{"meal_type_names":{"dinner":" Supper ","lunch":""}}}""")),
        )
    }

    @Test fun `meal type labels use the household's words`() {
        val strings = Strings("en", mapOf("meals.typeDinner" to "Dinner", "meals.typeLunch" to "Lunch"), emptyMap()) { "other" }
        assertEquals("Supper", MealTypes.label("dinner", mapOf("dinner" to "Supper"), strings))
        assertEquals("Lunch", MealTypes.label("lunch", emptyMap(), strings))
    }

    private fun recipe(
        id: Int = 1,
        title: String = "R",
        notes: String? = null,
        source: String = "native",
        servings: Int? = null,
        mealTypes: String? = null,
        ingredients: String = "[]",
    ): Recipe = ApiJson.decodeFromString(
        Recipe.serializer(),
        """{"id":$id,"title":"$title","notes":${notes?.let { "\"$it\"" } ?: "null"},"source":"$source","servings":${servings ?: "null"},""" +
            (mealTypes?.let { "\"meal_types\":$it," } ?: "") + """"ingredients":$ingredients}""",
    )
}
