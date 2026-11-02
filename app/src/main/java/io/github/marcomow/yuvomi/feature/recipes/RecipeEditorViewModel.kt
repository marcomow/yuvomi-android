package io.github.marcomow.yuvomi.feature.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The recipe form (`openRecipeModal`, `saveRecipe`): name, meals, servings, ingredients with
 * shopping category, and behind "More details" notes, picture and link.
 */
class RecipeEditorViewModel(private val shared: RecipesViewModel, val existing: Recipe?) : ViewModel() {

    data class Row(val key: Long, val name: String = "", val quantity: String = "", val category: String = ShoppingCategories.DEFAULT)

    data class Form(
        val title: String = "",
        val mealTypes: Set<String> = MealTypes.KEYS.toSet(),
        val servings: String = "",
        val rows: List<Row> = emptyList(),
        val notes: String = "",
        val url: String = "",
        /** Null: untouched, the server keeps the stored picture. */
        val image: ImageChange? = null,
        val moreOpen: Boolean = false,
    )

    data class UiState(
        val form: Form,
        val titleErrorKey: String? = null,
        val servingsErrorKey: String? = null,
        val saving: Boolean = false,
        /** Set once the server stored the recipe: the screen closes. */
        val saved: Recipe? = null,
        /** The recipe was deleted meanwhile: the screen leaves it. */
        val gone: Boolean = false,
    )

    private var nextKey = 0L
    private fun key() = nextKey++

    private val _state = MutableStateFlow(UiState(initialForm()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One key per recipe the member is trying to create; reused on retry (ADR 0007). */
    private var idempotencyKey = YuvomiClient.newIdempotencyKey()

    private fun initialForm(): Form {
        val r = existing ?: return Form()
        val offered = ShoppingCategories.forIngredients(shared.state.value.categories)
        return Form(
            title = r.title,
            mealTypes = r.mealTypes.toSet(),
            servings = r.servings?.toString().orEmpty(),
            rows = r.ingredients.map { Row(key(), it.name, it.quantity.orEmpty(), ShoppingCategories.resolve(it.category, offered)) },
            notes = r.notes.orEmpty(),
            url = r.recipeUrl.orEmpty(),
            moreOpen = !r.notes.isNullOrEmpty() || !r.recipeUrl.isNullOrEmpty(),
        )
    }

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), titleErrorKey = null, servingsErrorKey = null) }

    fun toggleMealType(type: String) = edit { f -> f.copy(mealTypes = if (type in f.mealTypes) f.mealTypes - type else f.mealTypes + type) }

    fun addRow() {
        val offered = ShoppingCategories.forIngredients(shared.state.value.categories)
        edit { it.copy(rows = it.rows + Row(key(), category = ShoppingCategories.resolve(null, offered))) }
    }

    fun updateRow(key: Long, change: (Row) -> Row) = edit { f -> f.copy(rows = f.rows.map { if (it.key == key) change(it) else it }) }

    fun removeRow(key: Long) = edit { f -> f.copy(rows = f.rows.filter { it.key != key }) }

    /**
     * Validates and sends, in this holder's scope; the result lands in [UiState.saved] or
     * [UiState.gone], and the messages go through the module's holder so they outlive the form.
     */
    fun save(fb: RecipesViewModel.Feedback) {
        if (state.value.saving) return
        viewModelScope.launch {
            try {
                val saved = send() ?: return@launch
                shared.tell(fb, fb.strings.t(if (existing == null) "recipes.created" else "recipes.updated"))
                _state.update { it.copy(saved = saved) }
            } catch (e: ApiProblem) {
                shared.tell(fb, fb.strings.t(RecipeErrors.key(e)))
                if (RecipeErrors.isGone(e)) _state.update { it.copy(gone = true) }
            }
        }
    }

    /** Null when a field needs fixing; a server failure is thrown. */
    internal suspend fun send(): Recipe? {
        val f = state.value.form
        if (f.title.isBlank()) {
            _state.update { it.copy(titleErrorKey = "common.nameRequired") }
            return null
        }
        // Empty means "not given"; otherwise a whole number from 1 to 99.
        val raw = f.servings.trim()
        val servings = if (raw.isEmpty()) null else raw.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it in 1.0..99.0 }?.toInt()
        if (raw.isNotEmpty() && servings == null) {
            _state.update { it.copy(servingsErrorKey = "recipes.servingsInvalid") }
            return null
        }
        val body = RecipeBodies.save(
            title = f.title,
            notes = f.notes,
            url = f.url,
            mealTypes = f.mealTypes.toList(),
            servings = servings,
            rows = f.rows.map { RecipeBodies.IngredientInput(it.name, it.quantity, it.category) },
            image = f.image,
        )
        _state.update { it.copy(saving = true) }
        try {
            val saved = shared.save(existing, body, idempotencyKey)
            idempotencyKey = YuvomiClient.newIdempotencyKey()
            if (f.image != null) shared.images.forget(RecipesApi.imagePath(saved.id))
            return saved
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }
}
