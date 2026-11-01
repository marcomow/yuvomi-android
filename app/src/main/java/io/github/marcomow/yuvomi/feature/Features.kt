package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.core.api.ApiOperation
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.feature.meals.MealsFeature
import io.github.marcomow.yuvomi.feature.meals.MealsOperation
import io.github.marcomow.yuvomi.feature.notes.NotesFeature
import io.github.marcomow.yuvomi.feature.notes.NotesOperation
import io.github.marcomow.yuvomi.feature.pantry.PantryFeature
import io.github.marcomow.yuvomi.feature.pantry.PantryOperation
import io.github.marcomow.yuvomi.feature.recipes.RecipesFeature
import io.github.marcomow.yuvomi.feature.recipes.RecipesOperation
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shopping.ShoppingFeature
import io.github.marcomow.yuvomi.feature.shopping.ShoppingOperation

/** The native modules beyond Overview and Tasks, in bar and "More" order (ADR 0015). */
object Features {
    val all: List<Feature> = listOf(ShoppingFeature, MealsFeature, RecipesFeature, NotesFeature, PantryFeature)

    /** Every upstream operation the app calls; checked against upstream/operations-used*.txt. */
    val operations: List<ApiOperation> = Operation.entries + ShoppingOperation.entries + MealsOperation.entries +
        RecipesOperation.entries + NotesOperation.entries + PantryOperation.entries

    /** Module ids that have native screens: Overview, Tasks and [all]. */
    val nativeIds: Set<String> = setOf("dashboard", "tasks") + all.map { it.id }
}
