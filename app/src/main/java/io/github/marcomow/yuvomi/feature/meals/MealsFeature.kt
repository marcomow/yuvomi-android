package io.github.marcomow.yuvomi.feature.meals

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Placement
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale

/** Meals (docs/spec/meals.md). */
object MealsFeature : Feature {
    override val id = "meals"
    override val placement = Placement.BAR
    override val startRoute = MealsRoutes.START
    override val routePrefixes = listOf("meals")

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable(
            MealsRoutes.WEEK,
            arguments = listOf(navArgument("week") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val shell = LocalShell.current
            val week = entry.arguments?.getString("week")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val vm = viewModel { MealsViewModel(shell.container, shell.time, week) }
            MealsScreen(vm) { mealId, date, type, recipeId -> nav.navigate(MealsRoutes.editor(mealId, date, type, recipeId)) }
        }
        graph.composable(
            MealsRoutes.EDITOR,
            arguments = listOf("id", "date", "type", "recipe").map { name ->
                navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
            },
        ) { entry ->
            val shell = LocalShell.current
            val strings = LocalStrings.current
            val args = entry.arguments
            // The editor works on the week it was opened from: that week's view model holds the
            // meal (upstream has no single-meal read) and reloads after a save.
            val parent = remember(entry) { runCatching { nav.getBackStackEntry(MealsRoutes.WEEK) }.getOrNull() }
            val date = args?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: shell.time.today()
            val week = if (parent != null) {
                viewModel(viewModelStoreOwner = parent) { MealsViewModel(shell.container, shell.time, date) }
            } else {
                viewModel(key = "meals-week-$date") { MealsViewModel(shell.container, shell.time, date) }
            }
            val separator = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(strings.locale)).decimalSeparator
            val editor = viewModel(key = "meal-editor-${entry.id}") {
                MealEditorViewModel(
                    week = week,
                    mealId = args?.getString("id")?.toIntOrNull(),
                    date = date,
                    presetType = args?.getString("type"),
                    presetRecipe = args?.getString("recipe")?.toIntOrNull(),
                    decimalSeparator = separator,
                )
            }
            MealEditorScreen(week, editor, close = { nav.popBackStack() })
        }
    }
}

/**
 * Meals's routes. Other modules link here through these helpers only; this module registers
 * a destination for each of them in [MealsFeature.register].
 */
object MealsRoutes {
    const val START = "meals"

    /** The week plan's destination; [START] and [week] both land here. */
    const val WEEK = "meals?week={week}"

    /** The meal form: a stored meal ([editor] with an id) or a new one. */
    const val EDITOR = "meals/edit?id={id}&date={date}&type={type}&recipe={recipe}"

    /** The week containing [date] (ISO yyyy-mm-dd), or this week when null. */
    fun week(date: String? = null) = "meals" + (date?.let { "?week=$it" } ?: "")

    fun editor(mealId: Int? = null, date: LocalDate, type: String? = null, recipeId: Int? = null) = buildString {
        append("meals/edit?date=").append(date)
        mealId?.let { append("&id=").append(it) }
        type?.let { append("&type=").append(it) }
        recipeId?.let { append("&recipe=").append(it) }
    }
}
