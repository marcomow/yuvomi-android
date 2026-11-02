package io.github.marcomow.yuvomi.feature.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * The state of the Recipes module, shared by its list, detail and editor (they are one page
 * in the web app, `public/pages/recipes.js`): the loaded recipes and the lists the page needs
 * beside them, the search, the source filter and the servings chosen per recipe.
 */
class RecipesViewModel(private val container: AppContainer, private val disabledModules: Set<String>) : ViewModel() {

    data class UiState(
        val recipes: List<Recipe> = emptyList(),
        val lists: List<ShoppingListRef> = emptyList(),
        val categories: List<String> = emptyList(),
        val plannedIds: Set<Int> = emptySet(),
        val mealTypeNames: Map<String, String> = emptyMap(),
        val query: String = "",
        val source: String = "all",
        /** R24: the servings chosen per recipe for this visit, not saved. */
        val servings: Map<Int, Double> = emptyMap(),
        /** Recipes whose delete waits in its undo window: kept, but not listed. */
        val pendingDelete: Set<Int> = emptySet(),
        val transferring: Set<Int> = emptySet(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loaded: Boolean = false,
        val cachedAt: Long? = null,
        val loadErrorKey: String? = null,
    ) {
        val listed: List<Recipe> get() = RecipeList.listed(recipes, pendingDelete)
        val visible: List<Recipe> get() = RecipeList.filtered(recipes, pendingDelete, query, source)
        val sourceOptions: List<String> get() = RecipeList.sourceOptions(recipes)
        val offline: Boolean get() = cachedAt != null

        fun recipe(id: Int): Recipe? = recipes.firstOrNull { it.id == id }

        /** `chosenServings`: the choice, else the stored servings, else 1. */
        fun chosen(recipe: Recipe): Double = servings[recipe.id] ?: recipe.servings?.toDouble() ?: 1.0

        /** `chosenFactor`. */
        fun factor(recipe: Recipe): Double = ScaleQuantity.servingsFactor(recipe.servings, chosen(recipe))
    }

    /** How a flow that outlives its screen (undo windows) talks to the member. */
    class Feedback(val strings: Strings, val notify: suspend (message: String, action: String?) -> Boolean)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val client: YuvomiClient? get() = container.session.connection?.client
    private var apiFor: Pair<YuvomiClient, RecipesApi>? = null
    val api: RecipesApi?
        get() {
            val c = client ?: return null
            apiFor?.let { (owner, api) -> if (owner === c) return api }
            return RecipesApi(c).also { apiFor = c to it }
        }
    val images = RecipeImages { client }
    var lastLoaded = 0L
        private set

    init {
        readCache()
        refresh()
    }

    private fun readCache() {
        val recipes = container.cache.read(CACHE_RECIPES) ?: return
        runCatching {
            fun json(key: String) = container.cache.read(key)?.let { ApiJson.parseToJsonElement(it.json) }
            _state.update {
                it.copy(
                    recipes = RecipeJson.recipes(ApiJson.parseToJsonElement(recipes.json)),
                    lists = json(CACHE_LISTS)?.let(RecipeJson::lists).orEmpty(),
                    categories = json(CACHE_CATEGORIES)?.let(RecipeJson::categories).orEmpty(),
                    plannedIds = json(CACHE_MEALS)?.let(RecipeJson::plannedRecipeIds).orEmpty(),
                    mealTypeNames = json(CACHE_PREFERENCES)?.let(RecipeJson::mealTypeNames).orEmpty(),
                    loading = false,
                    cachedAt = recipes.savedAt,
                )
            }
        }
    }

    /**
     * `render`: the recipes, shopping categories, shopping lists, this week's plan and the
     * household's meal names in parallel. Only the recipes are required; each of the others
     * fails to an empty answer, as upstream's loaders do.
     */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        _state.update { it.copy(refreshing = pulled, loadErrorKey = null) }
        viewModelScope.launch {
            suspend fun optional(key: String, call: suspend () -> JsonElement): JsonElement? =
                runCatching { call() }.getOrNull()?.also { container.cache.write(key, it.toString()) }

            val categories = async { optional(CACHE_CATEGORIES) { api.categories() } }
            val lists = async { if ("shopping" in disabledModules) null else optional(CACHE_LISTS) { api.shoppingLists() } }
            val meals = async { if ("meals" in disabledModules) null else optional(CACHE_MEALS) { api.meals() } }
            val prefs = async { optional(CACHE_PREFERENCES) { api.preferences() } }
            try {
                val answer = api.recipes()
                container.cache.write(CACHE_RECIPES, answer.toString())
                val recipes = RecipeJson.recipes(answer)
                val c = categories.await()
                val l = lists.await()
                val m = meals.await()
                val p = prefs.await()
                lastLoaded = System.currentTimeMillis()
                _state.update { s ->
                    // A source that no longer exists falls back to "all" (renderSourceFilter).
                    val source = if (s.source == "all" || recipes.any { it.source == s.source }) s.source else "all"
                    s.copy(
                        recipes = recipes,
                        categories = c?.let(RecipeJson::categories) ?: s.categories,
                        lists = if ("shopping" in disabledModules) emptyList() else l?.let(RecipeJson::lists) ?: emptyList(),
                        plannedIds = if ("meals" in disabledModules) emptySet() else m?.let(RecipeJson::plannedRecipeIds) ?: emptySet(),
                        mealTypeNames = p?.let(RecipeJson::mealTypeNames) ?: s.mealTypeNames,
                        source = source,
                        loading = false,
                        refreshing = false,
                        loaded = true,
                        cachedAt = null,
                    )
                }
            } catch (e: ApiProblem) {
                _state.update {
                    val hasCache = it.cachedAt != null || it.loaded
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (e is ApiProblem.Network && it.recipes.isNotEmpty()) it.cachedAt ?: System.currentTimeMillis() else it.cachedAt,
                        loadErrorKey = if (hasCache && it.recipes.isNotEmpty()) null else loadErrorKey(e),
                    )
                }
            }
        }
    }

    private fun loadErrorKey(e: ApiProblem): String =
        if (e is ApiProblem.Http && e.operationUnsupported) "android.error.unsupported" else "recipes.loadError"

    /**
     * A message that must outlive the dialog or screen that closes right before it: shown
     * from this graph-scoped holder, so leaving the screen does not cancel it.
     */
    fun tell(fb: Feedback, message: String) {
        viewModelScope.launch { fb.notify(message, null) }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setSource(source: String) = _state.update { it.copy(source = source) }

    fun resetSearch() = _state.update { it.copy(query = "") }

    /** The stepper (R24, E12): clamped and snapped, kept for this visit only. */
    fun stepServings(recipe: Recipe, direction: Int) = _state.update { s ->
        s.copy(servings = s.servings + (recipe.id to ScaleQuantity.step(recipe.servings, s.chosen(recipe), direction)))
    }

    private fun writeCache() {
        container.cache.write(CACHE_RECIPES, RecipeJson.encodeRecipes(state.value.recipes))
    }

    private fun put(recipe: Recipe) {
        _state.update { s ->
            val others = s.recipes.filter { it.id != recipe.id }
            s.copy(recipes = RecipeList.sorted(others + recipe))
        }
        writeCache()
    }

    /**
     * `dropGone`: takes out of the state what the server reported as gone, by id or name,
     * and nothing else; a late answer does not reload the whole list.
     */
    fun dropGone(e: Throwable, recipeId: Int? = null, ingredientName: String? = null, listId: Int? = null) {
        if (!RecipeErrors.isGone(e)) return
        when (RecipeErrors.reason(e)) {
            "recipes_recipe_not_found", "meals_selected_recipe_not_found" -> if (recipeId != null) {
                _state.update { it.copy(recipes = it.recipes.filter { r -> r.id != recipeId }) }
            }
            "recipes_ingredient_not_found" -> if (recipeId != null && ingredientName != null) {
                val key = ingredientMatchKey(ingredientName)
                _state.update { s ->
                    s.copy(recipes = s.recipes.map { r -> if (r.id != recipeId) r else r.copy(ingredients = r.ingredients.filter { ingredientMatchKey(it.name) != key }) })
                }
            }
            "shopping_list_not_found" -> if (listId != null) _state.update { it.copy(lists = it.lists.filter { l -> l.id != listId }) }
        }
        // The last recipe of the chosen source gone: back to "all".
        _state.update { s -> if (s.source != "all" && s.recipes.none { it.source == s.source }) s.copy(source = "all") else s }
        writeCache()
    }

    // ── Create, edit, duplicate, delete ──

    /** `saveRecipe`: create (with an idempotency key) or replace; the answer is the recipe as stored. */
    suspend fun save(existing: Recipe?, body: JsonObject, idempotencyKey: String): Recipe {
        val api = api ?: throw ApiProblem.Malformed("not signed in")
        try {
            val answer = if (existing == null) api.create(body, idempotencyKey) else api.update(existing.id, body)
            val saved = RecipeJson.recipe(answer) ?: throw ApiProblem.Malformed("recipe answer without data")
            // New servings: the stepper starts again from the stored number.
            if (existing != null) _state.update { it.copy(servings = it.servings - existing.id) }
            put(saved)
            return saved
        } catch (e: ApiProblem) {
            if (existing != null) dropGone(e, recipeId = existing.id)
            throw e
        }
    }

    /** `duplicateRecipe`: a native copy, which can then be edited freely. */
    fun duplicate(recipe: Recipe, fb: Feedback) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val answer = api.create(RecipeBodies.duplicate(recipe, fb.strings.t("recipes.copySuffix")), YuvomiClient.newIdempotencyKey())
                put(RecipeJson.recipe(answer) ?: throw ApiProblem.Malformed("recipe answer without data"))
                fb.notify(fb.strings.t("recipes.duplicated"), null)
            } catch (e: ApiProblem) {
                fb.notify(fb.strings.t(RecipeErrors.key(e)), null)
            }
        }
    }

    /**
     * `removeRecipe` with `scheduleUndoableDelete`: the row goes at once, the delete is sent
     * when the undo window closes. Already deleted by someone else counts as done.
     */
    fun delete(recipe: Recipe, fb: Feedback) {
        _state.update { it.copy(pendingDelete = it.pendingDelete + recipe.id) }
        viewModelScope.launch {
            val undone = fb.notify(fb.strings.t("recipes.deleted"), fb.strings.t("common.undo"))
            if (undone) {
                _state.update { it.copy(pendingDelete = it.pendingDelete - recipe.id) }
                return@launch
            }
            try {
                api?.delete(recipe.id) ?: throw ApiProblem.Malformed("not signed in")
            } catch (e: ApiProblem) {
                if (!(RecipeErrors.isGone(e) && RecipeErrors.reason(e) == "recipes_recipe_not_found")) {
                    _state.update { it.copy(pendingDelete = it.pendingDelete - recipe.id) }
                    fb.notify(fb.strings.t(RecipeErrors.key(e, "common.unknownError")), null)
                    return@launch
                }
            }
            _state.update { it.copy(recipes = it.recipes.filter { r -> r.id != recipe.id }, pendingDelete = it.pendingDelete - recipe.id) }
            writeCache()
        }
    }

    // ── Plan, shopping list ──

    /** `planRecipe`: one meal with the chosen servings' quantities. Throws on failure. */
    suspend fun plan(recipe: Recipe, date: LocalDate, mealType: String, idempotencyKey: String, locale: String) {
        val api = api ?: throw ApiProblem.Malformed("not signed in")
        val factor = state.value.factor(recipe)
        val ingredients = if (factor == 1.0) recipe.ingredients else ScaleQuantity.scaledIngredients(recipe.ingredients, factor, locale)
        try {
            api.planMeal(RecipeBodies.meal(recipe, ingredients, date, mealType), idempotencyKey)
        } catch (e: ApiProblem) {
            dropGone(e, recipeId = recipe.id)
            throw e
        }
    }

    /**
     * `transferRecipe` and `announceTransfer`: the ingredients onto [list], with the scaled
     * quantities when the servings changed; the snackbar names the list, what was already on
     * it, and offers undo for exactly the added items.
     */
    fun transfer(recipe: Recipe, list: ShoppingListRef, access: RecipeAccess, locale: String, fb: Feedback) {
        val api = api ?: return
        if (recipe.id in state.value.transferring) return
        _state.update { it.copy(transferring = it.transferring + recipe.id) }
        viewModelScope.launch {
            val t = fb.strings
            try {
                val body = RecipeBodies.transfer(list.id, recipe, state.value.factor(recipe), locale)
                val result = RecipeJson.transfer(api.toShoppingList(recipe.id, body, YuvomiClient.newIdempotencyKey()))
                _state.update { it.copy(transferring = it.transferring - recipe.id) }
                when {
                    result.transferred > 0 -> {
                        val done = t.t("recipes.toShoppingSuccess", "count" to result.transferred, "list" to list.name)
                        val message = if (result.skipped > 0) "$done ${t.t("kitchen.transferSkipped", "count" to result.skipped)}" else done
                        val canUndo = access.canUndoTransfer && result.addedIds.isNotEmpty()
                        if (fb.notify(message, if (canUndo) t.t("common.undo") else null) && canUndo) {
                            try {
                                api.undoTransfer(result.addedIds)
                                fb.notify(t.t("kitchen.transferUndone"), null)
                            } catch (e: ApiProblem) {
                                fb.notify(t.t(RecipeErrors.key(e)), null)
                            }
                        }
                    }
                    result.skipped > 0 -> fb.notify(t.t("kitchen.transferAllPresent"), null)
                    else -> fb.notify(t.t("recipes.toShoppingNoIngredients"), null)
                }
            } catch (e: ApiProblem) {
                _state.update { it.copy(transferring = it.transferring - recipe.id) }
                dropGone(e, recipeId = recipe.id, listId = list.id)
                // 409: the recipe changed since it was loaded; show it as it is now.
                if (RecipeErrors.reason(e) == "recipes_ingredients_changed") refresh()
                fb.notify(t.t(RecipeErrors.key(e)), null)
            }
        }
    }

    // ── Pantry links (#1314) ──

    /** `GET /pantry` for the link dialogs; throws when it fails. */
    suspend fun pantryItems(): List<PantryItem> = RecipeJson.pantry(api?.pantry() ?: throw ApiProblem.Malformed("not signed in"))

    /**
     * One confirmed link (`PUT /recipes/{id}/ingredient-match`); `null` removes it. The
     * answer carries the two fields that changed, and only those are taken over.
     */
    suspend fun match(recipe: Recipe, ingredientName: String, pantryItemId: Int?) {
        val api = api ?: throw ApiProblem.Malformed("not signed in")
        try {
            val result = RecipeJson.match(api.match(recipe.id, ingredientName, pantryItemId))
            _state.update { s ->
                s.copy(
                    recipes = s.recipes.map { r ->
                        if (r.id != recipe.id) {
                            r
                        } else {
                            r.copy(
                                ingredients = r.ingredients.map { ing ->
                                    if (ing.name != ingredientName) ing else ing.copy(pantryItemId = result?.pantryItemId, pantryItemName = result?.pantryItemName)
                                },
                            )
                        }
                    },
                )
            }
            writeCache()
        } catch (e: ApiProblem) {
            if (RecipeErrors.reason(e) != "recipes_selected_pantry_item_not_found") dropGone(e, recipeId = recipe.id, ingredientName = ingredientName)
            throw e
        }
    }

    companion object {
        const val CACHE_RECIPES = "recipes-list"
        const val CACHE_LISTS = "recipes-shopping-lists"
        const val CACHE_CATEGORIES = "recipes-shopping-categories"
        const val CACHE_MEALS = "recipes-meals-week"
        const val CACHE_PREFERENCES = "recipes-preferences"
    }
}
