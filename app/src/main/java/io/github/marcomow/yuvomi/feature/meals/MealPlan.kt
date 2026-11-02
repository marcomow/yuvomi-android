package io.github.marcomow.yuvomi.feature.meals

import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The meal plan's rules as pure functions, each a port of the named upstream function
 * (`public/pages/meals.js` and the utils it imports), so they are unit tested without Android.
 */
object MealPlan {
    /** `MEAL_TYPE_KEYS` (utils/meal-types.js): the four slots in display order. */
    val MEAL_TYPE_KEYS = listOf("breakfast", "lunch", "dinner", "snack")

    private val BUILT_IN_LABELS = mapOf(
        "breakfast" to "meals.typeBreakfast",
        "lunch" to "meals.typeLunch",
        "dinner" to "meals.typeDinner",
        "snack" to "meals.typeSnack",
    )

    /** `DEFAULT_CATEGORY_NAME` (utils/shopping-categories.js); the server's default too. */
    const val DEFAULT_CATEGORY = "Sonstiges"

    /** `DEFAULT_CATEGORY_I18N`: the built-in shopping categories are stored in German. */
    private val CATEGORY_KEYS = mapOf(
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

    /** `EXCLUDED_MEAL_CATEGORY_NAMES`: household and drugstore are no food. */
    private val EXCLUDED_MEAL_CATEGORIES = setOf("Haushalt", "Drogerie")

    /** `DAY_NAMES` in Monday-first order. */
    private val DAY_KEYS = listOf("meals.dayMo", "meals.dayDi", "meals.dayMi", "meals.dayDo", "meals.dayFr", "meals.daySa", "meals.daySo")

    // ── Week ──

    /** `weekStartIndex()` (utils/date.js): unknown values fall back to Monday. */
    fun weekStartDay(value: String?): DayOfWeek = when (value) {
        "sunday" -> DayOfWeek.SUNDAY
        "saturday" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.MONDAY
    }

    /** `startOfLocalWeekKey()`: the first day of the household week containing [date]. */
    fun startOfWeek(date: LocalDate, start: DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.value - start.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    fun weekDays(start: LocalDate): List<LocalDate> = (0L until 7L).map { start.plusDays(it) }

    fun dayNameKey(date: LocalDate): String = DAY_KEYS[date.dayOfWeek.value - 1]

    // ── Meal types ──

    /** The household's own names for the slots (`meal_type_names`, #1058), trimmed, blanks dropped. */
    fun typeNames(prefs: MealPrefs): Map<String, String> {
        val obj = prefs.mealTypeNames as? JsonObject ?: return emptyMap()
        return MEAL_TYPE_KEYS.mapNotNull { key ->
            val value = ((obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)?.trim()
            if (value.isNullOrEmpty()) null else key to value
        }.toMap()
    }

    /** `mealTypeLabel()`: the household's word, else the built-in one. */
    fun typeLabel(key: String, names: Map<String, String>, strings: Strings): String =
        names[key] ?: BUILT_IN_LABELS[key]?.let { strings.t(it) } ?: key

    /** `state.visibleMealTypes`: the preference as stored, all four when it is missing. */
    fun visibleTypes(prefs: MealPrefs): List<String> =
        (prefs.visibleMealTypes ?: MEAL_TYPE_KEYS).filter { it in MEAL_TYPE_KEYS }

    /** The visible slots in display order (`MEAL_TYPES().filter(...)` in renderWeekGrid). */
    fun visibleTypesOrdered(visible: List<String>): List<String> = MEAL_TYPE_KEYS.filter { it in visible }

    /** `mealTypeForHour()`: breakfast before 10, lunch before 15, then dinner. */
    fun mealTypeForHour(hour: Int): String = when {
        hour < 10 -> "breakfast"
        hour < 15 -> "lunch"
        else -> "dinner"
    }

    // ── Recipe meal types (utils/recipe-meal-types.js) ──

    /** `normalizeRecipeMealTypes()`: missing means all four, an empty list means "none". */
    fun recipeMealTypes(value: JsonElement?): List<String> {
        if (value == null || value is JsonNull) return MEAL_TYPE_KEYS
        val source = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> value.contentOrNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return source.filter { it in MEAL_TYPE_KEYS }.distinct()
    }

    /** `recipeSupportsMealType()`: what the automatic plan may pick. */
    fun recipeSupports(recipe: MealRecipe, type: String): Boolean = type in recipeMealTypes(recipe.mealTypes)

    /** `recipeAllowsMealType()`: what a person may place by hand (a recipe for no meal fits anywhere). */
    fun recipeAllows(recipe: MealRecipe, type: String): Boolean =
        recipeMealTypes(recipe.mealTypes).let { it.isEmpty() || type in it }

    // ── Planning a recipe ──

    data class Target(val date: LocalDate, val mealType: String)

    /** `railRecipeTarget()`: where a tapped recipe from the rail is planned. */
    fun railTarget(recipe: MealRecipe, weekStart: LocalDate, today: LocalDate, hour: Int, visible: List<String>, meals: List<Meal>): Target {
        val shown = MEAL_TYPE_KEYS.filter { it in visible }
        val allowed = shown.filter { recipeAllows(recipe, it) }
        val pool = allowed.ifEmpty { shown.ifEmpty { listOf("lunch") } }
        val wanted = mealTypeForHour(hour)
        val rank = { key: String -> MEAL_TYPE_KEYS.indexOf(key) }
        val mealType = if (wanted in pool) wanted else pool.firstOrNull { rank(it) > rank(wanted) } ?: pool.first()
        val week = weekDays(weekStart)
        val days = if (today in week) week.drop(week.indexOf(today)) else week
        val date = days.firstOrNull { d -> meals.none { it.date == d.toString() && it.mealType == mealType } } ?: days.first()
        return Target(date, mealType)
    }

    /** `mealPayloadFromRecipe()` (utils/recipe-to-meal.js): the `POST /meals` body for a recipe. */
    fun payloadFromRecipe(recipe: MealRecipe, date: LocalDate, mealType: String): JsonObject = buildJsonObject {
        put("date", date.toString())
        put("meal_type", mealType)
        put("title", recipe.title)
        put("notes", recipe.notes?.takeIf { it.isNotEmpty() })
        put("recipe_url", recipe.recipeUrl?.takeIf { it.isNotEmpty() })
        put("recipe_id", recipe.id)
        put("ingredients", buildJsonArray {
            recipe.ingredients.forEach { ing ->
                add(buildJsonObject {
                    put("name", ing.name)
                    put("quantity", ing.quantity?.takeIf { it.isNotEmpty() })
                    put("category", ing.category?.takeIf { it.isNotEmpty() } ?: DEFAULT_CATEGORY)
                })
            }
        })
    }

    data class Assignment(val date: LocalDate, val mealType: String, val recipe: MealRecipe) {
        val payload: JsonObject get() = payloadFromRecipe(recipe, date, mealType)
    }

    data class RandomPlan(val assignments: List<Assignment>, val deleteMealIds: List<Int>, val reason: String?)

    /** `buildRandomMealAssignments()`: one recipe per open slot, avoiding repeats on the same day and type. */
    fun randomPlan(
        weekStart: LocalDate,
        visible: List<String>,
        meals: List<Meal>,
        recipes: List<MealRecipe>,
        replaceExisting: Boolean,
        pick: () -> Double = Math::random,
    ): RandomPlan {
        val assignments = mutableListOf<Assignment>()
        val deleteIds = LinkedHashSet<Int>()
        val previousDayByType = HashMap<String, Int>()
        var hasOpenSlot = false
        var hasCompatibleSlot = false
        for (date in weekDays(weekStart)) {
            var previousSameDay: Int? = null
            for (type in visible) {
                val slotMeals = meals.filter { it.date == date.toString() && it.mealType == type }
                if (!replaceExisting && slotMeals.isNotEmpty()) continue
                hasOpenSlot = true
                val compatible = recipes.filter { recipeSupports(it, type) }
                if (compatible.isEmpty()) continue
                hasCompatibleSlot = true
                val blocked = setOfNotNull(previousSameDay, previousDayByType[type])
                val pool = compatible.filter { it.id !in blocked }.ifEmpty { compatible }
                val r = pick().takeIf { it.isFinite() } ?: 0.0
                val index = floor(r.coerceIn(0.0, 0.999999) * pool.size).toInt()
                val recipe = pool.getOrElse(index) { pool.first() }
                assignments += Assignment(date, type, recipe)
                previousSameDay = recipe.id
                previousDayByType[type] = recipe.id
                if (replaceExisting) deleteIds += slotMeals.map { it.id }
            }
        }
        val reason = when {
            assignments.isNotEmpty() -> null
            !hasOpenSlot -> "week_full"
            !hasCompatibleSlot -> "no_compatible_recipes"
            else -> "no_assignments"
        }
        return RandomPlan(assignments, deleteIds.toList(), reason)
    }

    data class TitleSuggestion(val title: String, val recipeId: Int?)

    /** `mealTitleSuggestions()`: recipes containing the text first (prefix matches ahead), then earlier meals. */
    fun titleSuggestions(query: String, recipes: List<MealRecipe>, history: List<MealSuggestion>, limit: Int = 8): List<TitleSuggestion> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val recipeHits = recipes
            .filter { it.title.lowercase().contains(needle) }
            .sortedWith(compareBy<MealRecipe> { if (it.title.lowercase().startsWith(needle)) 0 else 1 }.thenBy { it.title })
            .map { TitleSuggestion(it.title, it.id) }
        val seen = recipeHits.map { it.title.lowercase() }.toMutableSet()
        val historyHits = history.mapNotNull { h ->
            h.title.takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }?.let { TitleSuggestion(it, null) }
        }
        return (recipeHits + historyHits).take(limit)
    }

    // ── Ingredients ──

    /** `recipeIngredientsOf()`: a meal from a recipe uses the recipe's ingredients until it has its own. */
    fun recipeIngredientsOf(meal: Meal?, recipes: List<MealRecipe>): List<RecipeIngredient> {
        if (meal?.recipeId == null || meal.ingredients.isNotEmpty()) return emptyList()
        return recipes.firstOrNull { it.id == meal.recipeId }?.ingredients.orEmpty()
    }

    /** The card's ingredient count: its own, or the recipe's while it has none. */
    fun ingredientCount(meal: Meal): Int = meal.ingredients.size.takeIf { it > 0 } ?: meal.recipeIngredientCount

    /** All own ingredients are already on a list (the card's check mark). */
    fun allOnList(meal: Meal): Boolean = meal.ingredients.isNotEmpty() && meal.ingredients.all { it.onShoppingList }

    /** `mealCanTransfer()` without the permission half: something is left to send. */
    fun hasOpenIngredients(meal: Meal): Boolean {
        val own = meal.ingredients.size
        val done = meal.ingredients.count { it.onShoppingList }
        val fromRecipe = if (own == 0) meal.recipeIngredientCount else 0
        return fromRecipe > 0 || (own > 0 && done < own)
    }

    /** `mealCategories()`: shopping categories without the non-food ones. */
    fun mealCategories(categories: List<ShoppingCategoryRef>): List<ShoppingCategoryRef> =
        categories.filter { it.name !in EXCLUDED_MEAL_CATEGORIES }

    /** `categoryLabel()`: built-in categories translated, the household's own as named. */
    fun categoryLabel(name: String, strings: Strings): String = CATEGORY_KEYS[name]?.let { strings.t(it) } ?: name

    /** `ingredientRowHTML()`'s category choice: the wanted one if offered, else the first offered. */
    fun resolveCategory(wanted: String?, categories: List<ShoppingCategoryRef>): String {
        val name = wanted?.takeIf { it.isNotBlank() } ?: DEFAULT_CATEGORY
        if (categories.isEmpty() || categories.any { it.name == name }) return name
        return categories.first().name
    }

    // ── Servings (utils/servings-stepper.js, utils/scale-quantity.js) ──

    data class ServingsRange(val min: Double, val max: Double, val step: Double)

    fun servingsRange(base: Double?): ServingsRange =
        if (base != null && base > 0) ServingsRange(1.0, 99.0, 1.0) else ServingsRange(0.5, 10.0, 0.5)

    /** `setServingsValue()`: clamped to the range, on its step. */
    fun clampServings(base: Double?, value: Double): Double {
        val r = servingsRange(base)
        return ((value / r.step).roundToLong() * r.step).coerceIn(r.min, r.max)
    }

    /** `servingsFactor()`: servings over the recipe's, or the multiple itself without a base. */
    fun servingsFactor(base: Double?, chosen: Double): Double {
        if (!chosen.isFinite() || chosen <= 0) return 1.0
        return if (base != null && base > 0) chosen / base else chosen
    }

    /**
     * `scaleQuantityText()`: scales the number at the start of a free-text quantity
     * ("1 1/2 cups", "1/2", "250 g", "1,5 kg") and keeps the rest; anything else stays as it is.
     */
    fun scaleQuantity(quantity: String?, factor: Double, decimalSeparator: Char = '.'): String? {
        if (quantity.isNullOrEmpty() || factor == 1.0) return quantity
        val original = quantity.trim()
        fun breaksOff(rest: String) = rest.length >= 2 && (rest[0] == '.' || rest[0] == ',') && rest[1].isDigit()
        Regex("""^(\d+)\s+(\d+)/(\d+)(.*)$""").matchEntire(original)?.let { m ->
            val rest = m.groupValues[4]
            if (breaksOff(rest)) return quantity
            val den = m.groupValues[3].toDouble()
            if (den > 0) return format((m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / den) * factor, decimalSeparator) + rest
        }
        Regex("""^(\d+)/(\d+)(.*)$""").matchEntire(original)?.let { m ->
            val rest = m.groupValues[3]
            if (breaksOff(rest)) return quantity
            val den = m.groupValues[2].toDouble()
            if (den > 0) return format(m.groupValues[1].toDouble() / den * factor, decimalSeparator) + rest
        }
        val sep = Regex.escape(decimalSeparator.toString())
        Regex("""^(\d+(?:$sep\d+)?)(.*)$""").matchEntire(original)?.let { m ->
            val rest = m.groupValues[2]
            if (breaksOff(rest)) return quantity
            val base = m.groupValues[1].replace(decimalSeparator, '.').toDoubleOrNull() ?: return quantity
            return format(base * factor, decimalSeparator) + rest
        }
        return quantity
    }

    /** `toStoredNumber()`: at most two decimals, no grouping. */
    fun format(value: Double, decimalSeparator: Char = '.'): String {
        val rounded = Math.round(value * 100) / 100.0
        val text = if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString().trimEnd('0').trimEnd('.')
        return text.replace('.', decimalSeparator)
    }

    // ── Dates ──

    /** Days between two dates, for tests and "is in this week". */
    fun inWeek(date: LocalDate, weekStart: LocalDate): Boolean = ChronoUnit.DAYS.between(weekStart, date) in 0..6
}
