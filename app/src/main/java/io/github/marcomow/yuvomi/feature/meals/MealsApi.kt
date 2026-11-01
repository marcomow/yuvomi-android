package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.api.KitchenApi
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The meal plan's calls (docs/spec/meals.md): the shared kitchen reads through [KitchenApi],
 * everything only this page does through [MealsOperation]. Reads return raw JSON so the
 * screen can cache the answer as it came (ADR 0007).
 */
class MealsApi(private val client: YuvomiClient) {
    private val kitchen = KitchenApi(client)

    suspend fun preferences(): JsonElement = client.call(Operation.PREFERENCES)
    suspend fun week(date: LocalDate): JsonElement = kitchen.meals(listOf("week" to date.toString()))
    suspend fun recipes(): JsonElement = kitchen.recipes()
    suspend fun lists(): JsonElement = kitchen.shoppingLists()
    suspend fun categories(): JsonElement = kitchen.shoppingCategories()
    suspend fun members(): JsonElement = client.call(MealsOperation.FAMILY_MEMBERS)

    suspend fun suggestions(query: String): List<MealSuggestion> =
        client.call(MealsOperation.SUGGESTIONS, query = listOf("q" to query)).decodeData(ListSerializer(MealSuggestion.serializer()))

    suspend fun create(body: JsonObject, idempotencyKey: String): Meal =
        kitchen.createMeal(body, idempotencyKey).decodeData(Meal.serializer())

    suspend fun applyPlan(assignments: List<JsonObject>, replaceExisting: Boolean, idempotencyKey: String): List<Meal> =
        client.call(MealsOperation.APPLY_PLAN, body = buildJsonObject {
            put("assignments", JsonArray(assignments))
            put("replace_existing", replaceExisting)
        }, idempotencyKey = idempotencyKey).decodeData(ListSerializer(Meal.serializer()))

    /** `PUT /meals/{id}`, with `?scope=series` for the whole series. */
    suspend fun update(id: Int, body: JsonObject, series: Boolean = false): Meal =
        client.call(
            MealsOperation.MEAL_UPDATE, mapOf("id" to id),
            query = if (series) listOf("scope" to "series") else emptyList(),
            body = body,
        ).decodeData(Meal.serializer())

    /** [scope] is null for one meal, `future` or `series` for a recurring one. */
    suspend fun delete(id: Int, scope: String? = null) {
        client.call(MealsOperation.MEAL_DELETE, mapOf("id" to id), query = listOfNotNull(scope?.let { "scope" to it }))
    }

    suspend fun addIngredient(mealId: Int, row: IngredientRow, idempotencyKey: String): MealIngredient =
        client.call(MealsOperation.INGREDIENT_ADD, mapOf("id" to mealId), body = row.toJson(), idempotencyKey = idempotencyKey)
            .decodeData(MealIngredient.serializer())

    suspend fun updateIngredient(ingId: Int, row: IngredientRow): MealIngredient =
        client.call(MealsOperation.INGREDIENT_UPDATE, mapOf("ingId" to ingId), body = row.toJson())
            .decodeData(MealIngredient.serializer())

    suspend fun deleteIngredient(ingId: Int) {
        client.call(MealsOperation.INGREDIENT_DELETE, mapOf("ingId" to ingId))
    }

    suspend fun toShoppingList(mealId: Int, listId: Int, idempotencyKey: String): TransferResult =
        client.call(MealsOperation.TO_SHOPPING_LIST, mapOf("id" to mealId), body = buildJsonObject { put("listId", listId) },
            idempotencyKey = idempotencyKey).decodeData(TransferResult.serializer())

    suspend fun weekToShoppingList(listId: Int, week: LocalDate, idempotencyKey: String): TransferResult =
        client.call(MealsOperation.WEEK_TO_SHOPPING_LIST, body = buildJsonObject {
            put("listId", listId)
            put("week", week.toString())
        }, idempotencyKey = idempotencyKey).decodeData(TransferResult.serializer())

    /** Takes a transfer back: `{ ids, ingredient_ids }` as `announceTransfer` in kitchen-transfer.js sends it. */
    suspend fun undoTransfer(result: TransferResult) {
        kitchen.undoTransfer(buildJsonObject {
            put("ids", JsonArray(result.addedIds.map { JsonPrimitive(it) }))
            if (result.ingredientIds.isNotEmpty()) put("ingredient_ids", JsonArray(result.ingredientIds.map { JsonPrimitive(it) }))
        })
    }

    suspend fun createRecipe(body: JsonObject, idempotencyKey: String): MealRecipe =
        kitchen.createRecipe(body, idempotencyKey).decodeData(MealRecipe.serializer())

    companion object {
        fun decodeWeek(json: JsonElement): List<Meal> = json.decodeData(ListSerializer(Meal.serializer()))
        fun decodeRecipes(json: JsonElement): List<MealRecipe> = json.decodeData(ListSerializer(MealRecipe.serializer()))
        fun decodeLists(json: JsonElement): List<ShoppingListRef> = json.decodeData(ListSerializer(ShoppingListRef.serializer()))
        fun decodeCategories(json: JsonElement): List<ShoppingCategoryRef> = json.decodeData(ListSerializer(ShoppingCategoryRef.serializer()))
        fun decodeMembers(json: JsonElement): List<FamilyMember> = json.decodeData(ListSerializer(FamilyMember.serializer()))
        fun decodePrefs(json: JsonElement): MealPrefs = json.decodeData(MealPrefs.serializer())
    }
}

/** One ingredient line of the meal form; [id] is set for a stored ingredient. */
data class IngredientRow(
    val name: String = "",
    val quantity: String = "",
    val category: String = MealPlan.DEFAULT_CATEGORY,
    val id: Int? = null,
    /** A stable key for the form's list while rows are added and removed. */
    val key: Long = nextKey(),
) {
    /** `{ name, quantity, category }` as `collectModalIngredients()` reads a row. */
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name.trim())
        val q = quantity.trim()
        if (q.isEmpty()) put("quantity", null as String?) else put("quantity", q)
        put("category", category.ifBlank { MealPlan.DEFAULT_CATEGORY })
    }

    companion object {
        private var counter = 0L

        @Synchronized fun nextKey(): Long = ++counter
    }
}
