package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A recipe as `GET /recipes` answers it (server/routes/recipes.js, `withSource`): the image
 * itself never comes along, only `has_own_image`; `source` is `native` or the provider type.
 */
@Serializable
data class Recipe(
    val id: Int,
    val title: String = "",
    val notes: String? = null,
    @SerialName("recipe_url") val recipeUrl: String? = null,
    @SerialName("meal_types") val mealTypesRaw: JsonElement? = null,
    val servings: Int? = null,
    @SerialName("has_own_image") val hasOwnImage: FlexBool = false,
    val source: String = "native",
    @SerialName("provider_account_name") val providerAccountName: String? = null,
    @SerialName("provider_has_image") val providerHasImage: FlexBool = false,
    @SerialName("provider_updated_at") val providerUpdatedAtRaw: JsonElement? = null,
    val ingredients: List<Ingredient> = emptyList(),
) {
    val mealTypes: List<String> get() = MealTypes.normalize(mealTypesRaw)
    val isMirrored: Boolean get() = source != "native"
    val providerUpdatedAt: String? get() = (providerUpdatedAtRaw as? JsonPrimitive)?.contentOrNull
}

/** `recipe_ingredients` plus the confirmed pantry link (`attachPantryMatches`). */
@Serializable
data class Ingredient(
    val id: Int = 0,
    val name: String = "",
    val quantity: String? = null,
    val category: String? = null,
    @SerialName("pantry_item_id") val pantryItemId: Int? = null,
    @SerialName("pantry_item_name") val pantryItemName: String? = null,
)

@Serializable
data class ShoppingListRef(val id: Int, val name: String = "")

@Serializable
data class ShoppingCategory(val name: String = "")

/** A pantry row as the link dialog offers it (`pantryOptionLabel`): name, place, best-before. */
@Serializable
data class PantryItem(
    val id: Int,
    val name: String = "",
    @SerialName("location_name") val locationName: String? = null,
    @SerialName("expires_on") val expiresOn: String? = null,
)

/** `POST /recipes/{id}/to-shopping-list` → `data`. */
@Serializable
data class TransferResult(
    val transferred: Int = 0,
    val skipped: Int = 0,
    @SerialName("added_ids") val addedIds: List<Int> = emptyList(),
)

/** `PUT /recipes/{id}/ingredient-match` → `data`. */
@Serializable
data class PantryLink(
    val name: String = "",
    @SerialName("pantry_item_id") val pantryItemId: Int? = null,
    @SerialName("pantry_item_name") val pantryItemName: String? = null,
)

/** Decoding of the `{ data: ... }` envelopes this module reads. */
object RecipeJson {
    private fun data(answer: JsonElement): JsonElement = (answer as? JsonObject)?.get("data") ?: JsonArray(emptyList())

    private fun <T> listOf(answer: JsonElement, item: kotlinx.serialization.KSerializer<T>): List<T> {
        val array = data(answer) as? JsonArray ?: return emptyList()
        // One malformed row loses that row, not the screen (ARCHITECTURE.md, rule 2).
        return array.mapNotNull { runCatching { ApiJson.decodeFromJsonElement(item, it) }.getOrNull() }
    }

    fun recipes(answer: JsonElement): List<Recipe> = listOf(answer, Recipe.serializer())
    fun recipe(answer: JsonElement): Recipe? = runCatching { ApiJson.decodeFromJsonElement(Recipe.serializer(), data(answer)) }.getOrNull()
    fun lists(answer: JsonElement): List<ShoppingListRef> = listOf(answer, ShoppingListRef.serializer())
    fun categories(answer: JsonElement): List<String> = listOf(answer, ShoppingCategory.serializer()).map { it.name }.filter { it.isNotEmpty() }
    fun pantry(answer: JsonElement): List<PantryItem> = listOf(answer, PantryItem.serializer())

    /** The recipe ids planned in the week `GET /meals` answers (the current one, without `week`). */
    fun plannedRecipeIds(answer: JsonElement): Set<Int> = (data(answer) as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("recipe_id") as? JsonPrimitive)?.intOrNull }
        .filter { it != 0 }
        .toSet()

    /** `GET /preferences` → `data.meal_type_names`: the household's own words for the four slots. */
    fun mealTypeNames(answer: JsonElement): Map<String, String> {
        val names = (data(answer) as? JsonObject)?.get("meal_type_names") as? JsonObject ?: return emptyMap()
        return MealTypes.KEYS.mapNotNull { key ->
            val value = (names[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (value.isEmpty()) null else key to value
        }.toMap()
    }

    fun transfer(answer: JsonElement): TransferResult =
        runCatching { ApiJson.decodeFromJsonElement(TransferResult.serializer(), data(answer)) }.getOrDefault(TransferResult())

    fun match(answer: JsonElement): PantryLink? =
        runCatching { ApiJson.decodeFromJsonElement(PantryLink.serializer(), data(answer)) }.getOrNull()

    fun encodeRecipes(recipes: List<Recipe>): String =
        ApiJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("data" to ApiJson.encodeToJsonElement(ListSerializer(Recipe.serializer()), recipes))))
}
