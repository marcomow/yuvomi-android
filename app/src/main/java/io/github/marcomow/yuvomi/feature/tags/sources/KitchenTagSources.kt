package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.feature.meals.Meal
import io.github.marcomow.yuvomi.feature.meals.MealsApi
import io.github.marcomow.yuvomi.feature.meals.MealsRoutes
import io.github.marcomow.yuvomi.feature.pantry.PantryApi
import io.github.marcomow.yuvomi.feature.pantry.PantryItem
import io.github.marcomow.yuvomi.feature.pantry.PantryRoutes
import io.github.marcomow.yuvomi.feature.recipes.Recipe
import io.github.marcomow.yuvomi.feature.recipes.RecipeJson
import io.github.marcomow.yuvomi.feature.recipes.RecipesApi
import io.github.marcomow.yuvomi.feature.recipes.RecipesRoutes
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/** Recipes' part of the Tags view (TAGS-R2): hashtags in a recipe's title and notes. */
object RecipesTagSource : TagSource {
    override val moduleId = "recipes"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        return items(RecipeJson.recipes(RecipesApi(shell.connection.client).recipes()))
    }

    fun items(recipes: List<Recipe>): List<TaggedItem> = recipes.mapNotNull { recipe ->
        val tags = Hashtags.extract(recipe.title, recipe.notes)
        if (tags.isEmpty()) null else TaggedItem(moduleId, "recipe:${recipe.id}", recipe.title, recipe.providerAccountName, tags, RecipesRoutes.recipe(recipe.id))
    }
}

/**
 * Meals' part of the Tags view (TAGS-R2): hashtags in a meal's title and notes. Upstream reads
 * the plan a week at a time (`GET /meals?week=`), so the source reads the weeks from
 * [WEEKS_BACK] before to [WEEKS_AHEAD] after this one; a meal opens its week.
 */
object MealsTagSource : TagSource {
    override val moduleId = "meals"
    const val WEEKS_BACK = 4L
    const val WEEKS_AHEAD = 8L

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val api = MealsApi(shell.connection.client)
        val meals = coroutineScope {
            weeks(shell.time.today()).map { day -> async { MealsApi.decodeWeek(api.week(day)) } }.awaitAll().flatten()
        }
        return items(meals, SourceText.of(shell))
    }

    /** One day in each week of the window, oldest first. */
    fun weeks(today: LocalDate): List<LocalDate> = (-WEEKS_BACK..WEEKS_AHEAD).map { today.plusWeeks(it) }

    fun items(meals: List<Meal>, text: SourceText): List<TaggedItem> = meals.distinctBy { it.id }.mapNotNull { meal ->
        val tags = Hashtags.extract(meal.title, meal.notes)
        if (tags.isEmpty()) return@mapNotNull null
        val type = when (meal.mealType) {
            "breakfast" -> text.label("meals.typeBreakfast")
            "lunch" -> text.label("meals.typeLunch")
            "dinner" -> text.label("meals.typeDinner")
            "snack" -> text.label("meals.typeSnack")
            else -> null
        }
        TaggedItem(moduleId, "meal:${meal.id}", meal.title, text.join(text.date(meal.date), type), tags, MealsRoutes.week(meal.date.take(10)))
    }
}

/**
 * Pantry's part of the Tags view (TAGS-R2): hashtags in a batch's name and notes. A batch opens
 * the pantry list: Pantry's item form only opens on top of its own list (PantryFeature).
 */
object PantryTagSource : TagSource {
    override val moduleId = "pantry"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        return items(PantryApi.decodeStock(PantryApi(shell.connection.client).stock()).items)
    }

    fun items(items: List<PantryItem>): List<TaggedItem> = items.mapNotNull { item ->
        val tags = Hashtags.extract(item.name, item.notes)
        if (tags.isEmpty()) null else TaggedItem(moduleId, "pantry:${item.id}", item.name, item.locationName, tags, PantryRoutes.START)
    }
}
