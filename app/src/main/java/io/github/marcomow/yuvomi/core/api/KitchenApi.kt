package io.github.marcomow.yuvomi.core.api

import kotlinx.serialization.json.JsonElement

/**
 * The calls more than one kitchen module makes (Shopping, Meals, Recipes, Pantry): lists
 * for pickers and the transfers between them. Answers are raw JSON; each module decodes
 * what it shows with its own models. Calls only one module makes live in that module.
 */
class KitchenApi(private val client: YuvomiClient) {
    suspend fun shoppingLists(): JsonElement = client.call(Operation.SHOPPING_LISTS)
    suspend fun shoppingCategories(): JsonElement = client.call(Operation.SHOPPING_CATEGORIES)
    suspend fun undoTransfer(body: JsonElement): JsonElement = client.call(Operation.SHOPPING_UNDO_TRANSFER, body = body)
    suspend fun pantry(query: List<Pair<String, String>> = emptyList()): JsonElement = client.call(Operation.PANTRY_LIST, query = query)
    suspend fun recipes(query: List<Pair<String, String>> = emptyList()): JsonElement = client.call(Operation.RECIPES_LIST, query = query)
    suspend fun createRecipe(body: JsonElement, idempotencyKey: String? = null): JsonElement =
        client.call(Operation.RECIPE_CREATE, body = body, idempotencyKey = idempotencyKey)
    suspend fun meals(query: List<Pair<String, String>> = emptyList()): JsonElement = client.call(Operation.MEALS_LIST, query = query)
    suspend fun createMeal(body: JsonElement, idempotencyKey: String? = null): JsonElement =
        client.call(Operation.MEAL_CREATE, body = body, idempotencyKey = idempotencyKey)
    suspend fun summary(today: String): JsonElement = client.call(Operation.KITCHEN_SUMMARY, query = listOf("today" to today))
}
