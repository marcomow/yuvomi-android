package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The fields of the meal plan's answers the app reads (docs/spec/meals.md). Every field has a
 * default, so a new or missing upstream field degrades one row, not the screen.
 */

/** A row of `meal_ingredients` (`server/routes/meals.js`). */
@Serializable
data class MealIngredient(
    val id: Int? = null,
    @SerialName("meal_id") val mealId: Int? = null,
    val name: String = "",
    val quantity: String? = null,
    val category: String? = null,
    @SerialName("on_shopping_list") val onShoppingList: FlexBool = false,
)

/** A meal of `GET /meals?week=` (and of `POST /meals`, `PUT /meals/{id}`, `apply-plan`). */
@Serializable
data class Meal(
    val id: Int,
    val date: String = "",
    @SerialName("meal_type") val mealType: String = "",
    val title: String = "",
    val notes: String? = null,
    @SerialName("recipe_url") val recipeUrl: String? = null,
    @SerialName("recipe_id") val recipeId: Int? = null,
    @SerialName("recurrence_template_id") val recurrenceTemplateId: Int? = null,
    @SerialName("recurrence_end_date") val recurrenceEndDate: String? = null,
    @SerialName("recurrence_cook_user_id") val recurrenceCookUserId: Int? = null,
    @SerialName("cook_user_id") val cookUserId: Int? = null,
    @SerialName("cook_name") val cookName: String? = null,
    @SerialName("cook_color") val cookColor: String? = null,
    val ingredients: List<MealIngredient> = emptyList(),
    @SerialName("recipe_ingredient_count") val recipeIngredientCount: Int = 0,
) {
    val isRecurring: Boolean get() = recurrenceTemplateId != null
}

@Serializable
data class RecipeIngredient(
    val name: String = "",
    val quantity: String? = null,
    val category: String? = null,
)

/** The pieces of a recipe of `GET /recipes` the plan uses (`state.recipes` in meals.js). */
@Serializable
data class MealRecipe(
    val id: Int,
    val title: String = "",
    val notes: String? = null,
    @SerialName("recipe_url") val recipeUrl: String? = null,
    val servings: Double? = null,
    val source: String = "native",
    @SerialName("meal_types") val mealTypes: JsonElement? = null,
    val ingredients: List<RecipeIngredient> = emptyList(),
)

@Serializable
data class ShoppingListRef(val id: Int, val name: String = "")

@Serializable
data class ShoppingCategoryRef(val id: Int? = null, val name: String = "")

/** `GET /family/members`: who can cook (#1679), and their pictures. */
@Serializable
data class FamilyMember(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
)

/** The meal-plan fields of `GET /preferences`. */
@Serializable
data class MealPrefs(
    @SerialName("visible_meal_types") val visibleMealTypes: List<String>? = null,
    @SerialName("meal_type_names") val mealTypeNames: JsonElement? = null,
    @SerialName("week_start") val weekStart: String? = null,
)

/** `GET /meals/suggestions`: titles of earlier meals. */
@Serializable
data class MealSuggestion(val title: String = "", @SerialName("meal_type") val mealType: String? = null)

/** `POST /meals/{id}/to-shopping-list` and `/meals/week-to-shopping-list`. */
@Serializable
data class TransferResult(
    val transferred: Int = 0,
    val skipped: Int = 0,
    @SerialName("added_ids") val addedIds: List<Int> = emptyList(),
    @SerialName("ingredient_ids") val ingredientIds: List<Int> = emptyList(),
)
