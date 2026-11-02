package io.github.marcomow.yuvomi.feature.recipes

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Session
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** `public/utils/recipe-meal-types.js` and `meal-types.js`. */
object MealTypes {
    val KEYS = listOf("breakfast", "lunch", "dinner", "snack")

    private val LABEL_KEYS = mapOf(
        "breakfast" to "meals.typeBreakfast",
        "lunch" to "meals.typeLunch",
        "dinner" to "meals.typeDinner",
        "snack" to "meals.typeSnack",
    )

    /**
     * `normalizeRecipeMealTypes`: absent means all four; an empty list is an answer
     * ("no meal", #750); unknown keys and duplicates drop out.
     */
    fun normalize(value: JsonElement?): List<String> {
        if (value == null || value is JsonNull) return KEYS
        val source = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> value.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return source.filter { it in KEYS }.distinct()
    }

    /** `mealTypeLabel`: the household's word, else the built-in one. */
    fun label(key: String, names: Map<String, String>, strings: Strings): String =
        names[key] ?: strings.t(LABEL_KEYS[key] ?: key)

    /**
     * `planRecipe`: a recipe without meal types may still be planned into any slot; the
     * preselection is its only type, else dinner when offered, else the first one.
     */
    fun planChoices(recipe: Recipe): List<String> = recipe.mealTypes.ifEmpty { KEYS }

    fun planPreselect(types: List<String>): String =
        if (types.size == 1) types[0] else if ("dinner" in types) "dinner" else types.first()

    /**
     * `fillRecipeDetail`: chips only when they tell something, that is when the recipe is not
     * for every meal, or always for a read-only member (who cannot open the form). Null means
     * no chips; an empty list means the "No meal" chip.
     */
    fun detailChips(recipe: Recipe, readOnly: Boolean): List<String>? {
        val types = recipe.mealTypes
        return when {
            types.isEmpty() -> emptyList()
            readOnly || types.size < KEYS.size -> KEYS.filter { it in types }
            else -> null
        }
    }
}

/** `public/utils/ingredient-match-key.js`. */
fun ingredientMatchKey(name: String?): String = (name ?: "").trim().replace(Regex("\\s+"), " ").lowercase()

/** `public/utils/shopping-categories.js`. */
object ShoppingCategories {
    const val DEFAULT = "Sonstiges"

    private val I18N = mapOf(
        "Obst & Gemüse" to "shopping.catFruitVeg",
        "Backwaren" to "shopping.catBakery",
        "Milchprodukte" to "shopping.catDairy",
        "Fleisch & Fisch" to "shopping.catMeatFish",
        "Tiefkühl" to "shopping.catFrozen",
        "Getränke" to "shopping.catDrinks",
        "Haushalt" to "shopping.catHousehold",
        "Drogerie" to "shopping.catDrugstore",
        "Sonstiges" to "shopping.catMisc",
    )

    fun label(name: String, strings: Strings): String = I18N[name]?.let { strings.t(it) } ?: name

    /** `mealCategories`: the shopping categories without household goods and drugstore. */
    fun forIngredients(all: List<String>): List<String> = all.filter { it != "Haushalt" && it != "Drogerie" }

    /** `ingredientRowHTML`: the stored category when offered, else the first one, else the default. */
    fun resolve(category: String?, offered: List<String>): String {
        val wanted = category ?: DEFAULT
        return if (wanted in offered) wanted else offered.firstOrNull() ?: DEFAULT
    }
}

/**
 * What this member may do here (`readOnly`, `mayPlanRecipe`, `mayTransferRecipeToShopping`,
 * `pantryAccess`, `mayMatchIngredient` in recipes.js). Path rights come from the member's
 * permissions (`mayWritePath`); the pantry view also folds in a switched-off module.
 */
data class RecipeAccess(val meals: String = "write", val shopping: String = "write", val pantry: String = "write") {
    val canWrite: Boolean get() = meals == "write"
    val canPlan: Boolean get() = meals == "write"
    /** `/recipes/{id}/to-shopping-list` needs `meals: read` (server/scopes.js) and `shopping: write`. */
    val canTransfer: Boolean get() = meals != "none" && shopping == "write"
    val canUndoTransfer: Boolean get() = shopping == "write"
    val pantryVisible: Boolean get() = pantry != "none"
    val canMatch: Boolean get() = pantry == "write" && meals == "write"

    companion object {
        fun of(session: Session) = RecipeAccess(
            meals = session.permissions.module("meals"),
            shopping = session.permissions.module("shopping"),
            pantry = session.access("pantry"),
        )
    }
}

/** List rules of recipes.js: search, source filter, rows. */
object RecipeList {
    /** `listedRecipes`: without the ones whose delete is in its undo window. */
    fun listed(recipes: List<Recipe>, pending: Set<Int>) = recipes.filter { it.id !in pending }

    /** `filteredRecipes`: source, then the query in title, notes and ingredient names. */
    fun filtered(recipes: List<Recipe>, pending: Set<Int>, query: String, source: String): List<Recipe> {
        val q = query.trim().lowercase()
        return listed(recipes, pending).filter { r ->
            if (source != "all" && r.source != source) return@filter false
            if (q.isEmpty()) return@filter true
            r.title.lowercase().contains(q) ||
                r.notes.orEmpty().lowercase().contains(q) ||
                r.ingredients.any { it.name.lowercase().contains(q) }
        }
    }

    /** `renderSourceFilter`: All, Native, then each provider in order; none without mirrored recipes. */
    fun sourceOptions(recipes: List<Recipe>): List<String> {
        if (recipes.none { it.isMirrored }) return emptyList()
        return listOf("all", "native") + recipes.map { it.source }.filter { it != "native" }.distinct().sorted()
    }

    fun sourceLabel(source: String, strings: Strings): String {
        if (source == "all") return strings.t("recipes.sourceAll")
        val key = "recipes.source" + source.replaceFirstChar { it.uppercase() }
        return if (strings.has(key)) strings.t(key) else source.replaceFirstChar { it.uppercase() }
    }

    /** "Planned this week" only tells something when not every recipe has it (R24). */
    fun showPlanned(recipe: Recipe, recipes: List<Recipe>, pending: Set<Int>, planned: Set<Int>): Boolean =
        recipe.id in planned && !listed(recipes, pending).all { it.id in planned }

    /** `rowShowsThumb`: an own image, or a mirrored recipe (image or placeholder). */
    fun showsThumb(recipe: Recipe) = recipe.isMirrored || recipe.hasOwnImage

    /** `buildRecipeList`: a row opens the recipe when there is something to read. */
    fun hasDetail(recipe: Recipe, readOnly: Boolean) =
        readOnly || recipe.ingredients.isNotEmpty() || !recipe.notes.isNullOrEmpty() || !recipe.recipeUrl.isNullOrEmpty()

    /** Server order: title without case, then newest first. */
    fun sorted(recipes: List<Recipe>) = recipes.sortedWith(compareBy<Recipe> { it.title.lowercase() }.thenByDescending { it.id })
}

/** `externalHref` in utils/html.js: only http(s) links leave the app. */
fun externalHref(value: String?): String? = value?.trim()?.takeIf { Regex("^https?://\\S", RegexOption.IGNORE_CASE).containsMatchIn(it) }

/** Request bodies, as recipes.js and its utils build them. */
object RecipeBodies {
    data class IngredientInput(val name: String, val quantity: String, val category: String)

    /** One ingredient as the form sends it: trimmed, empty names dropped, empty quantity null. */
    private fun ingredients(rows: List<IngredientInput>) = buildJsonArray {
        rows.forEach { row ->
            val name = row.name.trim()
            if (name.isEmpty()) return@forEach
            add(
                buildJsonObject {
                    put("name", name)
                    put("quantity", row.quantity.trim().ifEmpty { null })
                    put("category", row.category.ifEmpty { ShoppingCategories.DEFAULT })
                },
            )
        }
    }

    /**
     * `saveRecipe`: the whole form. [image] is null when the image was not touched (the server
     * keeps it), [ImageChange.Remove] to delete it, or the new data URL.
     */
    fun save(
        title: String,
        notes: String,
        url: String,
        mealTypes: List<String>,
        servings: Int?,
        rows: List<IngredientInput>,
        image: ImageChange?,
    ): JsonObject = buildJsonObject {
        put("title", title.trim())
        put("notes", notes.trim().ifEmpty { null })
        put("recipe_url", url.trim().ifEmpty { null })
        put("meal_types", buildJsonArray { MealTypes.KEYS.filter { it in mealTypes }.forEach { add(JsonPrimitive(it)) } })
        put("servings", servings)
        put("ingredients", ingredients(rows))
        when (image) {
            null -> Unit
            ImageChange.Remove -> put("image_data", JsonNull)
            is ImageChange.Set -> put("image_data", image.dataUrl)
        }
    }

    /** `duplicateRecipe`: no meal types (the copy gets all four, as upstream's does), no image. */
    fun duplicate(recipe: Recipe, copySuffix: String): JsonObject = buildJsonObject {
        put("title", "${recipe.title} ($copySuffix)")
        put("notes", recipe.notes?.ifEmpty { null })
        put("recipe_url", recipe.recipeUrl?.ifEmpty { null })
        put("servings", recipe.servings)
        put(
            "ingredients",
            buildJsonArray {
                recipe.ingredients.forEach { ing ->
                    add(
                        buildJsonObject {
                            put("name", ing.name)
                            put("quantity", ing.quantity?.ifEmpty { null })
                            put("category", ing.category?.ifEmpty { null } ?: ShoppingCategories.DEFAULT)
                        },
                    )
                }
            },
        )
    }

    /** `mealPayloadFromRecipe` in utils/recipe-to-meal.js, with the (scaled) ingredients. */
    fun meal(recipe: Recipe, ingredients: List<Ingredient>, date: LocalDate, mealType: String): JsonObject = buildJsonObject {
        put("date", date.toString())
        put("meal_type", mealType)
        put("title", recipe.title)
        put("notes", recipe.notes?.ifEmpty { null })
        put("recipe_url", recipe.recipeUrl?.ifEmpty { null })
        put("recipe_id", recipe.id)
        put(
            "ingredients",
            buildJsonArray {
                ingredients.forEach { ing ->
                    add(
                        buildJsonObject {
                            put("name", ing.name)
                            put("quantity", ing.quantity?.ifEmpty { null })
                            put("category", ing.category?.ifEmpty { null } ?: ShoppingCategories.DEFAULT)
                        },
                    )
                }
            },
        )
    }

    /**
     * `transferRecipe`: the list, and with a changed servings choice the scaled quantity per
     * ingredient row id, so the server answers 409 instead of mixing them up.
     */
    fun transfer(listId: Int, recipe: Recipe, factor: Double, locale: String): JsonObject = buildJsonObject {
        put("listId", listId)
        if (factor != 1.0) {
            val scaled = ScaleQuantity.scaledIngredients(recipe.ingredients, factor, locale)
            put("quantities", buildJsonArray { scaled.forEach { add(it.quantity?.let(::JsonPrimitive) ?: JsonNull) } })
            put("ingredientIds", buildJsonArray { recipe.ingredients.forEach { add(JsonPrimitive(it.id)) } })
        }
    }
}

sealed interface ImageChange {
    data object Remove : ImageChange
    data class Set(val dataUrl: String) : ImageChange
}

/**
 * `errorSentence` (utils/friendly-error.js) for what this page meets: the case the server
 * names, else the page's general sentence, never the server's text.
 */
object RecipeErrors {
    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "android.recipes.readOnly",
        "recipe_mirrored" to "recipes.errorMirrored",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )
    private val CASES = mapOf(
        "shopping_list_not_found" to "shopping.errorListGone",
        "recipes_recipe_not_found" to "recipes.errorRecipeGone",
        "recipes_ingredient_not_found" to "recipes.errorIngredientGone",
        "recipes_selected_pantry_item_not_found" to "recipes.errorSelectedPantryItemGone",
        "recipes_ingredients_changed" to "recipes.errorIngredientsChanged",
        "meals_selected_recipe_not_found" to "recipes.errorRecipeGone",
    )
    private val GONE = setOf(
        "shopping_list_not_found", "recipes_recipe_not_found", "recipes_ingredient_not_found",
        "recipes_selected_pantry_item_not_found", "meals_selected_recipe_not_found",
    )

    fun key(e: Throwable, fallback: String = "common.errorGeneric"): String =
        FriendlyError.finish(e, keyBase(e, fallback), fallback)

    private fun keyBase(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `isGone`: the thing the action aimed at no longer exists. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    fun reason(e: Throwable): String? = (e as? ApiProblem.Http)?.reason
}
