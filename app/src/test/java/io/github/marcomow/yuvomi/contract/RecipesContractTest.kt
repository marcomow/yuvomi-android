package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.recipes.ImageChange
import io.github.marcomow.yuvomi.feature.recipes.Recipe
import io.github.marcomow.yuvomi.feature.recipes.RecipeBodies
import io.github.marcomow.yuvomi.feature.recipes.RecipeErrors
import io.github.marcomow.yuvomi.feature.recipes.RecipeJson
import io.github.marcomow.yuvomi.feature.recipes.RecipesApi
import io.github.marcomow.yuvomi.feature.recipes.ScaleQuantity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * docs/spec/recipes.md against upstream's image at the pinned version: one or more tests per
 * operation of `RecipesOperation`, each with its own fixtures, cleaned up afterwards.
 */
class RecipesContractTest : ContractTestBase() {

    private class Alex(val client: YuvomiClient) {
        val api = RecipesApi(client)
    }

    private fun alex() = Alex(signedIn("alex").first)

    private fun form(
        title: String,
        servings: Int? = 4,
        mealTypes: List<String> = listOf("dinner"),
        rows: List<RecipeBodies.IngredientInput> = listOf(
            RecipeBodies.IngredientInput("Leek", "2", "Sonstiges"),
            RecipeBodies.IngredientInput("Butter", "50 g", "Sonstiges"),
        ),
        image: ImageChange? = null,
    ): JsonObject = RecipeBodies.save(title, "Notes", "https://example.com/r", mealTypes, servings, rows, image)

    private fun Alex.create(body: JsonObject): Recipe = runBlocking {
        RecipeJson.recipe(api.create(body, YuvomiClient.newIdempotencyKey())) ?: error("no recipe in answer")
    }

    private fun Alex.cleanup(id: Int) = runBlocking { runCatching { api.delete(id) } }

    private inline fun expectHttp(status: Int, reason: String?, block: () -> Unit) {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    // ── PUT and DELETE /recipes/{id} ──

    @Test fun `RECIPES-C1 create, edit and delete a recipe with every form field`() = runBlocking {
        val alex = alex()
        val created = alex.create(form(unique("Contract soup")))
        try {
            assertEquals(4, created.servings)
            assertEquals(listOf("dinner"), created.mealTypes)
            assertEquals("native", created.source)
            assertEquals(listOf("Leek", "Butter"), created.ingredients.map { it.name })
            assertEquals("https://example.com/r", created.recipeUrl)

            val title = unique("Contract stew")
            val updated = RecipeJson.recipe(
                alex.api.update(
                    created.id,
                    form(title, servings = null, mealTypes = emptyList(), rows = listOf(RecipeBodies.IngredientInput("Onion", "", "Sonstiges"))),
                ),
            )!!
            assertEquals(title, updated.title)
            assertNull(updated.servings)
            // An empty choice is an answer ("no meal", #750), not "all four".
            assertEquals(emptyList<String>(), updated.mealTypes)
            assertEquals(listOf("Onion"), updated.ingredients.map { it.name })
            assertNull(updated.ingredients[0].quantity)
            assertTrue(RecipeJson.recipes(alex.api.recipes()).any { it.id == created.id && it.title == title })
        } finally {
            alex.api.delete(created.id)
        }
        // Already deleted: the reason the app treats as done.
        expectHttp(404, "recipes_recipe_not_found") { alex.api.delete(created.id) }
        expectHttp(404, "recipes_recipe_not_found") { alex.api.update(created.id, form("x")) }
    }

    @Test fun `RECIPES-C2 a duplicate gets all four meals and the servings`() = runBlocking {
        val alex = alex()
        val original = alex.create(form(unique("Contract original")))
        val copy = RecipeJson.recipe(alex.api.create(RecipeBodies.duplicate(original, "copy"), YuvomiClient.newIdempotencyKey()))!!
        try {
            assertEquals("${original.title} (copy)", copy.title)
            assertEquals(listOf("breakfast", "lunch", "dinner", "snack"), copy.mealTypes)
            assertEquals(4, copy.servings)
            assertEquals(original.ingredients.map { it.name }, copy.ingredients.map { it.name })
        } finally {
            alex.cleanup(original.id)
            alex.cleanup(copy.id)
        }
    }

    // ── GET /recipes/{id}/image ──

    @Test fun `RECIPES-C3 the own picture is served as bytes, kept on a partial edit, removed with null`() = runBlocking {
        val alex = alex()
        val created = alex.create(form(unique("Contract picture"), image = ImageChange.Set(PNG)))
        try {
            assertTrue(created.hasOwnImage)
            val bytes = alex.client.bytes(RecipesApi.imagePath(created.id))
            assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), bytes.copyOf(4))
            // No image_data in the body: the stored picture stays.
            val kept = RecipeJson.recipe(alex.api.update(created.id, form(created.title)))!!
            assertTrue(kept.hasOwnImage)
            val removed = RecipeJson.recipe(alex.api.update(created.id, form(created.title, image = ImageChange.Remove)))!!
            assertFalse(removed.hasOwnImage)
            expectHttp(404, null) { alex.client.bytes(RecipesApi.imagePath(created.id)) }
        } finally {
            alex.cleanup(created.id)
        }
    }

    // ── GET /recipes/{id}/provider-thumbnail ──

    @Test fun `RECIPES-C4 a native recipe has no provider thumbnail`() = runBlocking {
        val alex = alex()
        val created = alex.create(form(unique("Contract thumb")))
        try {
            assertNull(RecipesApi.thumbnailPath(created))
            expectHttp(404, null) {
                alex.client.bytes(io.github.marcomow.yuvomi.feature.recipes.RecipesOperation.RECIPE_PROVIDER_THUMBNAIL.path(mapOf("id" to created.id)))
            }
        } finally {
            alex.cleanup(created.id)
        }
    }

    // ── PUT /recipes/{id}/ingredient-match ──

    @Test fun `RECIPES-C5 link an ingredient to a pantry row, survive an edit, and unlink`() = runBlocking {
        val alex = alex()
        val created = alex.create(form(unique("Contract link")))
        val item = raw(alex.client, "POST", "/api/v1/pantry", """{"name":"${unique("Contract leek")}","quantity":1}""")
            .jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.int
        try {
            val linked = RecipeJson.match(alex.api.match(created.id, "  leek ", item))!!
            assertEquals("Leek", linked.name)
            assertEquals(item, linked.pantryItemId)
            // The link hangs on the name, so it survives a save that rewrites the ingredients.
            alex.api.update(created.id, form(created.title))
            val reloaded = RecipeJson.recipes(alex.api.recipes()).first { it.id == created.id }
            assertEquals(item, reloaded.ingredients.first { it.name == "Leek" }.pantryItemId)
            assertNotNull(reloaded.ingredients.first { it.name == "Leek" }.pantryItemName)
            assertNull(reloaded.ingredients.first { it.name == "Butter" }.pantryItemId)

            expectHttp(404, "recipes_ingredient_not_found") { alex.api.match(created.id, "Saffron", item) }
            expectHttp(404, "recipes_selected_pantry_item_not_found") { alex.api.match(created.id, "Leek", 999_999) }

            val cleared = RecipeJson.match(alex.api.match(created.id, "Leek", null))!!
            assertNull(cleared.pantryItemId)
        } finally {
            alex.cleanup(created.id)
            raw(alex.client, "DELETE", "/api/v1/pantry/$item", "", tolerate = true)
        }
    }

    // ── POST /recipes/{id}/to-shopping-list ──

    @Test fun `RECIPES-C6 ingredients onto a list, duplicates skipped, scaled quantities by row id, undo`() = runBlocking {
        val alex = alex()
        val created = alex.create(form(unique("Contract transfer")))
        val list = raw(alex.client, "POST", "/api/v1/shopping", """{"name":"${unique("Contract list")}"}""")
            .jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.int
        try {
            val first = RecipeJson.transfer(alex.api.toShoppingList(created.id, buildJsonObject { put("listId", list) }, YuvomiClient.newIdempotencyKey()))
            assertEquals(2, first.transferred)
            assertEquals(2, first.addedIds.size)
            val again = RecipeJson.transfer(alex.api.toShoppingList(created.id, buildJsonObject { put("listId", list) }, YuvomiClient.newIdempotencyKey()))
            assertEquals(0, again.transferred)
            assertEquals(2, again.skipped)

            alex.api.undoTransfer(first.addedIds)
            val scaled = RecipeBodies.transfer(list, created, ScaleQuantity.servingsFactor(4, 8.0), "en")
            val doubled = RecipeJson.transfer(alex.api.toShoppingList(created.id, scaled, YuvomiClient.newIdempotencyKey()))
            assertEquals(2, doubled.transferred)
            alex.api.undoTransfer(doubled.addedIds)

            // Quantities for row ids the recipe no longer has: 409 instead of a silent mix-up.
            val stale = buildJsonObject {
                put("listId", list)
                put("quantities", buildJsonArray { add(JsonPrimitive("1")); add(JsonPrimitive("2")) })
                put("ingredientIds", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
            }
            expectHttp(409, "recipes_ingredients_changed") { alex.api.toShoppingList(created.id, stale, YuvomiClient.newIdempotencyKey()) }
            expectHttp(404, "shopping_list_not_found") {
                alex.api.toShoppingList(created.id, buildJsonObject { put("listId", 999_999) }, YuvomiClient.newIdempotencyKey())
            }
        } finally {
            alex.cleanup(created.id)
            raw(alex.client, "DELETE", "/api/v1/shopping/$list", "", tolerate = true)
        }
        expectHttp(404, "recipes_recipe_not_found") {
            alex.api.toShoppingList(created.id, buildJsonObject { put("listId", list) }, YuvomiClient.newIdempotencyKey())
        }
    }

    // ── POST /meals from a recipe (core operation, the recipe's own body) ──

    @Test fun `RECIPES-C7 planning a recipe creates a meal linked to it`() = runBlocking {
        val alex = alex()
        val created = alex.create(form(unique("Contract plan")))
        try {
            val meal = alex.api.planMeal(RecipeBodies.meal(created, created.ingredients, LocalDate.now().plusDays(2), "dinner"), YuvomiClient.newIdempotencyKey())
            val data = meal.jsonObject["data"]!!.jsonObject
            assertEquals(created.id, data["recipe_id"]!!.jsonPrimitive.int)
            raw(alex.client, "DELETE", "/api/v1/meals/${data["id"]!!.jsonPrimitive.int}", "", tolerate = true)
        } finally {
            alex.cleanup(created.id)
        }
        try {
            alex.api.planMeal(RecipeBodies.meal(created, emptyList(), LocalDate.now(), "dinner"), YuvomiClient.newIdempotencyKey())
            fail("planned a deleted recipe")
        } catch (e: ApiProblem.Http) {
            assertEquals("meals_selected_recipe_not_found", e.reason)
            assertTrue(RecipeErrors.isGone(e))
        }
    }

    @Test fun `RECIPES-C8 the list decodes, with lists, categories, pantry and the week beside it`() = runBlocking {
        val alex = alex()
        val recipes = RecipeJson.recipes(alex.api.recipes())
        assertTrue("the seed has recipes", recipes.isNotEmpty())
        assertTrue(recipes.all { it.source == "native" || it.isMirrored })
        assertTrue(RecipeJson.categories(alex.api.categories()).isNotEmpty())
        RecipeJson.lists(alex.api.shoppingLists())
        RecipeJson.pantry(alex.api.pantry())
        // The seed plans the current week from its recipes.
        assertTrue(RecipeJson.plannedRecipeIds(alex.api.meals()).isNotEmpty())
    }

    private companion object {
        /** A 1x1 PNG, whose content matches its declared type (server/utils/file-signature.js). */
        const val PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
