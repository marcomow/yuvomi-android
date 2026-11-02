package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.core.api.KitchenApi
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The calls of docs/spec/recipes.md: the shared kitchen ones through [KitchenApi], the rest here. */
class RecipesApi(private val client: YuvomiClient) {
    val kitchen = KitchenApi(client)

    suspend fun recipes(): JsonElement = kitchen.recipes()
    suspend fun create(body: JsonObject, idempotencyKey: String): JsonElement = kitchen.createRecipe(body, idempotencyKey)
    suspend fun update(id: Int, body: JsonObject): JsonElement =
        client.call(RecipesOperation.RECIPE_UPDATE, mapOf("id" to id), body = body)

    /** 204 without a body. */
    suspend fun delete(id: Int) {
        client.call(RecipesOperation.RECIPE_DELETE, mapOf("id" to id))
    }

    suspend fun shoppingLists(): JsonElement = kitchen.shoppingLists()
    suspend fun categories(): JsonElement = kitchen.shoppingCategories()
    suspend fun pantry(): JsonElement = kitchen.pantry()

    /** The current week of the plan: `GET /meals` without `week`. */
    suspend fun meals(): JsonElement = kitchen.meals()

    suspend fun preferences(): JsonElement = client.call(Operation.PREFERENCES)

    suspend fun planMeal(body: JsonObject, idempotencyKey: String): JsonElement = kitchen.createMeal(body, idempotencyKey)

    suspend fun toShoppingList(id: Int, body: JsonObject, idempotencyKey: String): JsonElement =
        client.call(RecipesOperation.RECIPE_TO_SHOPPING_LIST, mapOf("id" to id), body = body, idempotencyKey = idempotencyKey)

    suspend fun undoTransfer(ids: List<Int>): JsonElement =
        kitchen.undoTransfer(buildJsonObject { put("ids", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })

    /** `pantryItemId` null removes the link. */
    suspend fun match(id: Int, name: String, pantryItemId: Int?): JsonElement =
        client.call(
            RecipesOperation.RECIPE_INGREDIENT_MATCH,
            mapOf("id" to id),
            body = buildJsonObject {
                put("name", name)
                if (pantryItemId == null) put("pantryItemId", JsonNull) else put("pantryItemId", pantryItemId)
            },
        )

    companion object {
        /** `bildUrl` in utils/recipe-thumb.js: the own image first, else the provider's (versioned). */
        fun thumbnailPath(recipe: Recipe): String? = when {
            recipe.hasOwnImage -> imagePath(recipe.id)
            recipe.providerHasImage -> RecipesOperation.RECIPE_PROVIDER_THUMBNAIL.path(mapOf("id" to recipe.id)) +
                (recipe.providerUpdatedAt?.let { "?v=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "")
            else -> null
        }

        fun imagePath(id: Int): String = RecipesOperation.RECIPE_IMAGE.path(mapOf("id" to id))
    }
}
