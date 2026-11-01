package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Meals calls. Must match
 * `upstream/operations-used.d/meals.txt` exactly (ContractFilesTest). The week list and
 * meal creation are shared kitchen calls in the core `Operation` enum (`KitchenApi`).
 */
enum class MealsOperation(override val method: String, override val template: String) : ApiOperation {
    SUGGESTIONS("GET", "/api/v1/meals/suggestions"),
    APPLY_PLAN("POST", "/api/v1/meals/apply-plan"),
    MEAL_UPDATE("PUT", "/api/v1/meals/{id}"),
    MEAL_DELETE("DELETE", "/api/v1/meals/{id}"),
    INGREDIENT_ADD("POST", "/api/v1/meals/{id}/ingredients"),
    INGREDIENT_UPDATE("PATCH", "/api/v1/meals/ingredients/{ingId}"),
    INGREDIENT_DELETE("DELETE", "/api/v1/meals/ingredients/{ingId}"),
    TO_SHOPPING_LIST("POST", "/api/v1/meals/{id}/to-shopping-list"),
    WEEK_TO_SHOPPING_LIST("POST", "/api/v1/meals/week-to-shopping-list"),

    /** Only the meal plan reads the household's members (the cook picker and its avatars). */
    FAMILY_MEMBERS("GET", "/api/v1/family/members"),
}
