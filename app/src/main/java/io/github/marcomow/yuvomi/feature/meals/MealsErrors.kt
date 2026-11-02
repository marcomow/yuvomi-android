package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiProblem

/**
 * A failed meal-plan call as the i18n key of its sentence: upstream's `errorSentence()`
 * (`public/utils/friendly-error.js`) for the cases the page meets, the app's own sentences for
 * what only the app has (a meal deleted from another device, an operation the server lost).
 */
object MealsErrors {
    fun key(e: Throwable, mealGoneOn404: Boolean = false): String =
        FriendlyError.finish(e, keyBase(e, mealGoneOn404), "common.errorGeneric")

    private fun keyBase(e: Throwable, mealGoneOn404: Boolean): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.status == 403 && e.reason == "module_access_denied" -> "common.errorModuleNoAccess"
            e.status == 403 && e.reason == "csrf_invalid" -> "common.errorFormExpired"
            e.status == 403 -> "common.errorNoPermission"
            e.status == 429 -> "common.errorRateLimited"
            e.reason == "meals_selected_recipe_not_found" -> "recipes.errorRecipeGone"
            e.reason == "shopping_list_not_found" -> "shopping.errorListGone"
            // `Meal not found.` / `Ingredient not found.` carry no reason: on a call about one
            // meal it means someone else deleted it in the meantime.
            e.status == 404 && mealGoneOn404 -> "android.meals.gone"
            e.operationUnsupported -> "android.error.unsupported"
            else -> "common.errorGeneric"
        }
        else -> "common.errorGeneric"
    }

    /** The load failure of the week: `meals.loadError`, or the app's sentence for a lost operation. */
    fun loadKey(e: Throwable): String =
        if (e is ApiProblem.Http && e.operationUnsupported) "android.error.unsupported" else "meals.loadError"
}
