package io.github.marcomow.yuvomi.feature.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

/**
 * The week plan (docs/spec/meals.md, MEALS-R1 to R8): the household's preferences, the week's
 * meals and the lists the plan needs (recipes, shopping lists and categories, members), cache
 * first (ADR 0007). Writes are suspend functions that throw [ApiProblem]; [MealActions] turns
 * them into the web app's messages.
 */
class MealsViewModel(
    private val container: AppContainer,
    private val time: HouseholdTime,
    initialDate: LocalDate?,
) : ViewModel() {

    data class UiState(
        val prefs: MealPrefs = MealPrefs(),
        val weekStart: LocalDate,
        val meals: List<Meal> = emptyList(),
        val recipes: List<MealRecipe> = emptyList(),
        val lists: List<ShoppingListRef> = emptyList(),
        val categories: List<ShoppingCategoryRef> = emptyList(),
        val members: List<FamilyMember> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val switching: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        /** Meals deleted with an undo still open (`scheduleUndoableDelete`). */
        val hidden: Set<Int> = emptySet(),
        /** A delete the editor asked for: the week screen runs it, with its undo. */
        val pendingDelete: PendingDelete? = null,
        /** Bumped after every load, so the screen can reveal today once per week. */
        val loadedWeek: LocalDate? = null,
    ) {
        val visible: List<String> get() = MealPlan.visibleTypes(prefs)
        val visibleOrdered: List<String> get() = MealPlan.visibleTypesOrdered(visible)
        val names: Map<String, String> get() = MealPlan.typeNames(prefs)
        val shownMeals: List<Meal> get() = meals.filter { it.id !in hidden }
        val offline: Boolean get() = cachedAt != null
        fun meal(id: Int): Meal? = meals.firstOrNull { it.id == id }
        fun recipe(id: Int?): MealRecipe? = id?.let { rid -> recipes.firstOrNull { it.id == rid } }
    }

    data class PendingDelete(val mealId: Int, val scope: String?)

    private val startDay get() = MealPlan.weekStartDay(_state.value.prefs.weekStart)
    private var anchor: LocalDate = initialDate ?: time.today()
    private val _state = MutableStateFlow(UiState(weekStart = MealPlan.startOfWeek(anchor, java.time.DayOfWeek.MONDAY)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val client: YuvomiClient? get() = container.session.connection?.client
    val api: MealsApi? get() = client?.let { MealsApi(it) }
    var lastLoaded = 0L
        private set

    init {
        readCache()
        refresh()
    }

    private fun weekKey(start: LocalDate) = "$CACHE_WEEK$start"

    private fun <T> cached(key: String, decode: (JsonElement) -> T): T? =
        container.cache.read(key)?.let { runCatching { decode(ApiJson.parseToJsonElement(it.json)) }.getOrNull() }

    private fun readCache() {
        val prefs = cached(CACHE_PREFS, MealsApi::decodePrefs) ?: _state.value.prefs
        val start = MealPlan.startOfWeek(anchor, MealPlan.weekStartDay(prefs.weekStart))
        val week = container.cache.read(weekKey(start))
        _state.update { s ->
            s.copy(
                prefs = prefs,
                weekStart = start,
                recipes = cached(CACHE_RECIPES, MealsApi::decodeRecipes) ?: s.recipes,
                lists = cached(CACHE_LISTS, MealsApi::decodeLists) ?: s.lists,
                categories = cached(CACHE_CATEGORIES, MealsApi::decodeCategories) ?: s.categories,
                members = cached(CACHE_MEMBERS, MealsApi::decodeMembers) ?: s.members,
            )
        }
        if (week != null) {
            runCatching { MealsApi.decodeWeek(ApiJson.parseToJsonElement(week.json)) }.getOrNull()?.let { meals ->
                _state.update { it.copy(meals = meals, loading = false, cachedAt = week.savedAt) }
            }
        }
    }

    /** MEALS-R2: preferences first (they decide the week's first day), the lists alongside. */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        viewModelScope.launch {
            coroutineScope {
                val others = listOf(
                    async { load(CACHE_RECIPES, api::recipes, MealsApi::decodeRecipes) { s, v -> s.copy(recipes = v) } },
                    async { load(CACHE_LISTS, api::lists, MealsApi::decodeLists) { s, v -> s.copy(lists = v) } },
                    async { load(CACHE_CATEGORIES, api::categories, MealsApi::decodeCategories) { s, v -> s.copy(categories = v) } },
                    async { load(CACHE_MEMBERS, api::members, MealsApi::decodeMembers) { s, v -> s.copy(members = v) } },
                )
                load(CACHE_PREFS, api::preferences, MealsApi::decodePrefs) { s, v -> s.copy(prefs = v) }
                loadWeek(MealPlan.startOfWeek(anchor, startDay))
                others.forEach { it.await() }
            }
        }
    }

    private suspend fun <T> load(
        key: String,
        call: suspend () -> JsonElement,
        decode: (JsonElement) -> T,
        apply: (UiState, T) -> UiState,
    ) {
        try {
            val json = call()
            val value = decode(json)
            container.cache.write(key, json.toString())
            _state.update { apply(it, value) }
        } catch (e: ApiProblem) {
            // A list the plan only needs for pickers keeps its last value (meals.js loadLists() & co.).
        }
    }

    /** `loadWeek()`: a failure shows the cached week read-only, else the load error (never a false empty week). */
    private suspend fun loadWeek(start: LocalDate) {
        val api = api ?: return
        try {
            val json = api.week(start)
            val meals = MealsApi.decodeWeek(json)
            container.cache.write(weekKey(start), json.toString())
            lastLoaded = System.currentTimeMillis()
            if (anchorStart() == start) {
                _state.update {
                    it.copy(
                        weekStart = start, meals = meals, loading = false, refreshing = false, switching = false,
                        cachedAt = null, errorKey = null, loadedWeek = start,
                    )
                }
            }
        } catch (e: ApiProblem) {
            if (anchorStart() != start) return
            val cache = container.cache.read(weekKey(start))
            val meals = cache?.let { runCatching { MealsApi.decodeWeek(ApiJson.parseToJsonElement(it.json)) }.getOrNull() }
            _state.update {
                if (meals != null) {
                    it.copy(weekStart = start, meals = meals, loading = false, refreshing = false, switching = false,
                        cachedAt = cache.savedAt, errorKey = null, loadedWeek = start)
                } else {
                    it.copy(weekStart = start, meals = emptyList(), loading = false, refreshing = false, switching = false,
                        cachedAt = null, errorKey = MealsErrors.loadKey(e), loadedWeek = start)
                }
            }
        }
    }

    private fun anchorStart() = MealPlan.startOfWeek(anchor, startDay)

    /** MEALS-R3: the week containing [date]. */
    fun showWeek(date: LocalDate) {
        val start = MealPlan.startOfWeek(date, startDay)
        anchor = start
        if (start == _state.value.weekStart && _state.value.errorKey == null && !_state.value.loading) return
        val cache = container.cache.read(weekKey(start))
        val cachedMeals = cache?.let { runCatching { MealsApi.decodeWeek(ApiJson.parseToJsonElement(it.json)) }.getOrNull() }
        _state.update { it.copy(weekStart = start, meals = cachedMeals ?: emptyList(), switching = true, errorKey = null, hidden = emptySet()) }
        viewModelScope.launch { loadWeek(start) }
    }

    fun previousWeek() = showWeek(_state.value.weekStart.minusDays(7))
    fun nextWeek() = showWeek(_state.value.weekStart.plusDays(7))
    fun thisWeek() = showWeek(time.today())

    /** Reloads the shown week after a write (`reloadAfterSave`). */
    suspend fun reload() {
        loadWeek(_state.value.weekStart)
    }

    suspend fun reloadRecipes() {
        val api = api ?: return
        load(CACHE_RECIPES, api::recipes, MealsApi::decodeRecipes) { s, v -> s.copy(recipes = v) }
    }

    suspend fun reloadMembers() {
        val api = api ?: return
        load(CACHE_MEMBERS, api::members, MealsApi::decodeMembers) { s, v -> s.copy(members = v) }
    }

    // ── Writes ──

    private fun requireApi(): MealsApi = api ?: throw ApiProblem.Malformed("no connection")

    suspend fun transfer(mealId: Int, listId: Int): TransferResult =
        requireApi().toShoppingList(mealId, listId, YuvomiClient.newIdempotencyKey()).also { reloadAfter(it) }

    suspend fun weekTransfer(listId: Int): TransferResult =
        requireApi().weekToShoppingList(listId, _state.value.weekStart, YuvomiClient.newIdempotencyKey()).also { reloadAfter(it) }

    private suspend fun reloadAfter(result: TransferResult) {
        if (result.transferred > 0 || result.skipped > 0) reload()
    }

    suspend fun undoTransfer(result: TransferResult) {
        requireApi().undoTransfer(result)
        reload()
    }

    /** MEALS-R13: fills the open slots with recipes; returns the number planned. */
    suspend fun randomize(plan: MealPlan.RandomPlan, replaceExisting: Boolean): Int {
        requireApi().applyPlan(plan.assignments.map { it.payload }, replaceExisting, YuvomiClient.newIdempotencyKey())
        reload()
        return plan.assignments.size
    }

    fun randomPlan(replaceExisting: Boolean, pick: () -> Double = Math::random): MealPlan.RandomPlan {
        val s = _state.value
        return MealPlan.randomPlan(s.weekStart, s.visible, s.meals, s.recipes, replaceExisting, pick)
    }

    /** A series ended or deleted (`?scope=future|series`). */
    suspend fun deleteSeries(mealId: Int, scope: String) {
        requireApi().delete(mealId, scope)
        reload()
    }

    fun hide(mealId: Int) = _state.update { it.copy(hidden = it.hidden + mealId) }
    fun unhide(mealId: Int) = _state.update { it.copy(hidden = it.hidden - mealId) }

    /** The delete behind the undo window; runs on the app's scope so leaving the screen still commits it. */
    suspend fun commitDelete(mealId: Int) {
        try {
            requireApi().delete(mealId)
        } catch (e: ApiProblem.Http) {
            // Already gone elsewhere: the meal is deleted either way.
            if (e.status != 404) {
                unhide(mealId)
                throw e
            }
        } catch (e: ApiProblem) {
            unhide(mealId)
            throw e
        }
        _state.update { it.copy(meals = it.meals.filterNot { m -> m.id == mealId }, hidden = it.hidden - mealId) }
    }

    fun requestDelete(mealId: Int, scope: String?) = _state.update { it.copy(pendingDelete = PendingDelete(mealId, scope)) }
    fun consumeDelete() = _state.update { it.copy(pendingDelete = null) }

    fun recipeCreated(recipe: MealRecipe) = _state.update { s -> s.copy(recipes = s.recipes.filterNot { it.id == recipe.id } + recipe) }

    companion object {
        const val CACHE_PREFS = "meals-prefs"
        const val CACHE_WEEK = "meals-week-"
        const val CACHE_RECIPES = "meals-recipes"
        const val CACHE_LISTS = "meals-lists"
        const val CACHE_CATEGORIES = "meals-categories"
        const val CACHE_MEMBERS = "meals-members"
    }
}
