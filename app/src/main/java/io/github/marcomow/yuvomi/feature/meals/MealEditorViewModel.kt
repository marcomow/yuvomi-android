package io.github.marcomow.yuvomi.feature.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The meal form (docs/spec/meals.md, MEALS-R9 to R20): `openMealModal()`, `buildModalContent()`
 * and `saveModal()` of `public/pages/meals.js`. It reads the meal and the pickers' lists from
 * the week's [MealsViewModel] (upstream has no single-meal read) and reloads that week after a
 * save.
 */
class MealEditorViewModel(
    private val week: MealsViewModel,
    private val mealId: Int?,
    private val date: LocalDate,
    private val presetType: String?,
    private val presetRecipe: Int?,
    private val decimalSeparator: Char = '.',
) : ViewModel() {

    enum class Field { TITLE, DATE, UNTIL }

    data class Form(
        val title: String = "",
        val mealType: String = "lunch",
        val date: LocalDate? = null,
        val cookId: Int? = null,
        /** `cookTouched`: someone picked the cook in this form (series edits send the cook only then). */
        val cookTouched: Boolean = false,
        val rows: List<IngredientRow> = emptyList(),
        /** False while a stored meal's ingredients show as a read list with "Adjust". */
        val rowsOpen: Boolean = true,
        val recipeId: Int? = null,
        /** The recipe whose ingredients fill the rows, for the servings stepper (`currentAppliedRecipe`). */
        val appliedRecipeId: Int? = null,
        val servings: Double? = null,
        val notes: String = "",
        val recipeUrl: String = "",
        val repeatWeekly: Boolean = false,
        val repeatUntil: LocalDate? = null,
        /** `single` or `series`, for a recurring meal. */
        val scope: String = "single",
    )

    enum class Done { CREATED, UPDATED }

    data class UiState(
        val ready: Boolean = false,
        val gone: Boolean = false,
        val meal: Meal? = null,
        val form: Form = Form(),
        val original: Form = Form(),
        val errors: Map<Field, String> = emptyMap(),
        val suggestions: List<MealPlan.TitleSuggestion> = emptyList(),
        val saving: Boolean = false,
        val savingRecipe: Boolean = false,
        val done: Done? = null,
        /** An i18n key to show once (a failed save, a saved recipe). */
        val message: String? = null,
    ) {
        val isEdit: Boolean get() = meal != null
        val dirty: Boolean get() = form != original
        /** The series' end only counts while the series is in play (`seriesScoped` in saveModal). */
        val seriesScoped: Boolean get() = if (meal == null) form.repeatWeekly else form.scope == "series"
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val createKey = YuvomiClient.newIdempotencyKey()
    private val recipeKey = YuvomiClient.newIdempotencyKey()
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            val s = week.state.first { !it.loading }
            if (mealId != null) {
                val meal = s.meal(mealId)
                if (meal == null) {
                    _state.update { it.copy(gone = true) }
                    return@launch
                }
                val form = formOf(meal, s)
                _state.update { it.copy(ready = true, meal = meal, form = form, original = form) }
            } else {
                val types = MealPlan.MEAL_TYPE_KEYS
                val type = presetType?.takeIf { it in types } ?: types.first()
                val form = Form(mealType = type, date = date)
                _state.update { it.copy(ready = true, form = form, original = form) }
                presetRecipe?.let { chooseRecipe(it) }
                // Opened from the rail, the recipe is part of what was asked for, not an edit.
                _state.update { it.copy(original = it.form) }
            }
        }
    }

    private fun categories() = MealPlan.mealCategories(week.state.value.categories)

    private fun formOf(meal: Meal, s: MealsViewModel.UiState): Form {
        val cats = MealPlan.mealCategories(s.categories)
        val rows = meal.ingredients.map {
            IngredientRow(it.name, it.quantity.orEmpty(), MealPlan.resolveCategory(it.category, cats), it.id)
        }
        val readList = readIngredients(meal, s)
        return Form(
            title = meal.title,
            mealType = meal.mealType.takeIf { it in MealPlan.MEAL_TYPE_KEYS } ?: MealPlan.MEAL_TYPE_KEYS.first(),
            date = runCatching { LocalDate.parse(meal.date) }.getOrNull(),
            cookId = meal.cookUserId,
            rows = rows,
            rowsOpen = readList.isEmpty(),
            recipeId = meal.recipeId,
            notes = meal.notes.orEmpty(),
            recipeUrl = meal.recipeUrl.orEmpty(),
            repeatUntil = meal.recurrenceEndDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
        )
    }

    /** What the read list shows: the recipe's ingredients, else the meal's own. */
    fun readIngredients(meal: Meal? = state.value.meal, s: MealsViewModel.UiState = week.state.value): List<RecipeIngredient> {
        if (meal == null) return emptyList()
        val fromRecipe = MealPlan.recipeIngredientsOf(meal, s.recipes)
        return fromRecipe.ifEmpty { meal.ingredients.map { RecipeIngredient(it.name, it.quantity, it.category) } }
    }

    fun readListIsRecipe(): Boolean = MealPlan.recipeIngredientsOf(state.value.meal, week.state.value.recipes).isNotEmpty()

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), errors = emptyMap()) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ── Title and suggestions ──

    fun setTitle(text: String) {
        edit { it.copy(title = text) }
        suggestionJob?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(200)
            val history = try {
                week.api?.suggestions(q).orEmpty()
            } catch (e: ApiProblem) {
                emptyList()
            }
            _state.update { it.copy(suggestions = MealPlan.titleSuggestions(q, week.state.value.recipes, history)) }
        }
    }

    fun pickSuggestion(s: MealPlan.TitleSuggestion) {
        suggestionJob?.cancel()
        _state.update { it.copy(suggestions = emptyList()) }
        edit { it.copy(title = s.title) }
        s.recipeId?.let { chooseRecipe(it) }
    }

    fun dismissSuggestions() = _state.update { it.copy(suggestions = emptyList()) }

    // ── Cook ──

    /** The people the cook picker offers (`withChosenPeople`): members plus a stored cook who left. */
    fun people(): List<FamilyMember> {
        val members = week.state.value.members
        val meal = state.value.meal ?: return members
        val cookId = meal.cookUserId ?: return members
        if (members.any { it.id == cookId }) return members
        return members + FamilyMember(cookId, meal.cookName.orEmpty(), meal.cookColor)
    }

    fun pickCook(id: Int?) = edit { it.copy(cookId = id, cookTouched = true) }

    /** `showCookForScope()`: an untouched picker shows the series' cook in series scope. */
    fun setScope(scope: String) {
        val meal = state.value.meal ?: return
        edit { f ->
            var next = f.copy(scope = scope)
            if (meal.isRecurring && !f.cookTouched) {
                val shown = if (scope == "series") meal.recurrenceCookUserId else meal.cookUserId
                if (shown == null || people().any { it.id == shown }) next = next.copy(cookId = shown)
            }
            next
        }
    }

    // ── Recipe, servings, ingredients ──

    /** `applyRecipe()`: title, notes, link and the ingredient rows come from the recipe. */
    fun chooseRecipe(id: Int?) {
        if (id == null) {
            edit { it.copy(recipeId = null, appliedRecipeId = null, servings = null) }
            return
        }
        val recipe = week.state.value.recipe(id) ?: return
        val base = recipe.servings?.takeIf { it > 0 }
        edit {
            it.copy(
                recipeId = recipe.id,
                appliedRecipeId = recipe.id,
                servings = base ?: 1.0,
                rowsOpen = true,
                title = recipe.title,
                notes = recipe.notes.orEmpty(),
                recipeUrl = recipe.recipeUrl.orEmpty(),
                rows = rowsFrom(recipe, 1.0),
            )
        }
    }

    private fun rowsFrom(recipe: MealRecipe, factor: Double): List<IngredientRow> {
        val cats = categories()
        return recipe.ingredients.map {
            IngredientRow(it.name, MealPlan.scaleQuantity(it.quantity.orEmpty(), factor, decimalSeparator).orEmpty(),
                MealPlan.resolveCategory(it.category, cats))
        }
    }

    /** The servings stepper (R24, E12): re-fills the rows from the applied recipe, scaled. */
    fun stepServings(direction: Int) {
        val f = state.value.form
        val recipe = week.state.value.recipe(f.appliedRecipeId) ?: return
        val base = recipe.servings?.takeIf { it > 0 }
        val range = MealPlan.servingsRange(base)
        val value = MealPlan.clampServings(base, (f.servings ?: base ?: 1.0) + direction * range.step)
        edit { it.copy(servings = value, rows = rowsFrom(recipe, MealPlan.servingsFactor(base, value))) }
    }

    /** "Adjust": the read list becomes editable rows (the recipe's become the meal's own). */
    fun openRows() {
        val meal = state.value.meal
        val fromRecipe = MealPlan.recipeIngredientsOf(meal, week.state.value.recipes)
        val cats = categories()
        edit { f ->
            if (fromRecipe.isNotEmpty()) {
                f.copy(rowsOpen = true, rows = fromRecipe.map { IngredientRow(it.name, it.quantity.orEmpty(), MealPlan.resolveCategory(it.category, cats)) })
            } else {
                f.copy(rowsOpen = true)
            }
        }
    }

    fun addRow() = edit { it.copy(rowsOpen = true, rows = it.rows + IngredientRow(category = MealPlan.resolveCategory(null, categories()))) }
    fun removeRow(key: Long) = edit { f -> f.copy(rows = f.rows.filterNot { it.key == key }) }
    fun editRow(key: Long, change: (IngredientRow) -> IngredientRow) = edit { f -> f.copy(rows = f.rows.map { if (it.key == key) change(it) else it }) }

    // ── Save ──

    /** `saveModal()`'s checks, all at once. */
    fun validate(s: UiState = state.value): Map<Field, String> = buildMap {
        val f = s.form
        if (f.title.isBlank()) put(Field.TITLE, "common.nameRequired")
        if (f.date == null) put(Field.DATE, "calendar.invalidDate")
        val until = if (s.seriesScoped) f.repeatUntil else null
        if (f.date != null && until != null && until.isBefore(f.date)) put(Field.UNTIL, "meals.recurrenceUntilBeforeStart")
    }

    /** The ingredient rows as sent (`collectModalIngredients()`): blank names are dropped. */
    private fun ingredientsJson(rows: List<IngredientRow>) = JsonArray(rows.filter { it.name.isNotBlank() }.map { it.toJson() })

    private fun nullable(value: String) = if (value.isBlank()) JsonNull else JsonPrimitive(value.trim())

    fun createBody(s: UiState = state.value): JsonObject {
        val f = s.form
        return buildJsonObject {
            put("date", f.date.toString())
            put("meal_type", f.mealType)
            put("title", f.title.trim())
            put("notes", nullable(f.notes))
            put("recipe_url", nullable(f.recipeUrl))
            put("recipe_id", f.recipeId)
            put("cook_user_id", f.cookId)
            put("ingredients", ingredientsJson(f.rows))
            put("repeat_weekly", f.repeatWeekly)
            put("repeat_until", if (f.repeatWeekly) f.repeatUntil?.toString().orEmpty() else "")
        }
    }

    /** `PUT /meals/{id}?scope=series`: no date; the cook only when picked here (#1679). */
    fun seriesBody(s: UiState = state.value): JsonObject {
        val f = s.form
        return buildJsonObject {
            put("meal_type", f.mealType)
            put("title", f.title.trim())
            put("notes", nullable(f.notes))
            put("recipe_url", nullable(f.recipeUrl))
            put("recipe_id", f.recipeId)
            put("ingredients", ingredientsJson(f.rows))
            put("repeat_until", f.repeatUntil?.toString().orEmpty())
            if (f.cookTouched) put("cook_user_id", f.cookId)
        }
    }

    /** `PUT /meals/{id}` for this meal only. */
    fun singleBody(s: UiState = state.value): JsonObject {
        val f = s.form
        return buildJsonObject {
            put("date", f.date.toString())
            put("meal_type", f.mealType)
            put("title", f.title.trim())
            put("notes", nullable(f.notes))
            put("recipe_url", nullable(f.recipeUrl))
            put("recipe_id", f.recipeId)
            put("cook_user_id", f.cookId)
        }
    }

    data class IngredientSync(val delete: List<Int>, val update: List<IngredientRow>, val add: List<IngredientRow>)

    /** The ingredient calls after a single-meal edit: removed rows deleted, changed ones patched, new ones added. */
    fun ingredientSync(meal: Meal, rows: List<IngredientRow>): IngredientSync {
        val kept = rows.filter { it.name.isNotBlank() }
        val keptIds = kept.mapNotNull { it.id }.toSet()
        val before = meal.ingredients.associateBy { it.id }
        val changed = kept.filter { row ->
            val old = row.id?.let { before[it] } ?: return@filter false
            old.name != row.name.trim() || old.quantity.orEmpty() != row.quantity.trim() ||
                (old.category ?: MealPlan.DEFAULT_CATEGORY) != row.category
        }
        return IngredientSync(
            delete = meal.ingredients.mapNotNull { it.id }.filter { it !in keptIds },
            update = changed,
            add = kept.filter { it.id == null },
        )
    }

    fun save() {
        val s = state.value
        val api = week.api ?: return
        if (s.saving || !s.ready) return
        val errors = validate(s)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val meal = s.meal
                if (meal == null) {
                    api.create(createBody(s), createKey)
                } else if (meal.isRecurring && s.form.scope == "series") {
                    api.update(meal.id, seriesBody(s), series = true)
                } else {
                    api.update(meal.id, singleBody(s))
                    val sync = ingredientSync(meal, s.form.rows)
                    sync.delete.forEach { api.deleteIngredient(it) }
                    sync.update.forEach { row -> api.updateIngredient(row.id!!, row) }
                    sync.add.forEach { row -> api.addIngredient(meal.id, row, YuvomiClient.newIdempotencyKey()) }
                }
                week.reload()
                _state.update { it.copy(saving = false, done = if (meal == null) Done.CREATED else Done.UPDATED) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(saving = false, message = MealsErrors.key(e, mealGoneOn404 = s.meal != null)) }
            }
        }
    }

    /** "Save as recipe": `POST /recipes` with the form's title, notes, link and ingredients. */
    fun saveAsRecipe() {
        val s = state.value
        val api = week.api ?: return
        if (s.savingRecipe) return
        if (s.form.title.isBlank()) {
            _state.update { it.copy(errors = mapOf(Field.TITLE to "common.nameRequired")) }
            return
        }
        _state.update { it.copy(savingRecipe = true) }
        viewModelScope.launch {
            try {
                val f = s.form
                val created = api.createRecipe(buildJsonObject {
                    put("title", f.title.trim())
                    put("notes", nullable(f.notes))
                    put("recipe_url", nullable(f.recipeUrl))
                    put("ingredients", ingredientsJson(f.rows))
                }, recipeKey)
                week.recipeCreated(created)
                edit { it.copy(recipeId = created.id) }
                _state.update { it.copy(savingRecipe = false, message = "recipes.created") }
            } catch (e: ApiProblem) {
                _state.update { it.copy(savingRecipe = false, message = MealsErrors.key(e)) }
            }
        }
    }

    /** `canTransfer` of the form: own ingredients that are not on a list yet. */
    fun hasOpenOwnIngredients(): Boolean = state.value.meal?.ingredients?.any { !it.onShoppingList } == true
}
