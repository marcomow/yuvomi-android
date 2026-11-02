package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.feature.schedule.ScheduleTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.BudgetTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.CalendarTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.DocumentsTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.HealthTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.MealsTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.NotesTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.PantryTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.RecipesTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.ShoppingTagSource
import io.github.marcomow.yuvomi.feature.tags.sources.TasksTagSource

/**
 * Every module's [TagSource]. The Tags view groups by [TagIndex.moduleRank] (the order of
 * ModuleCatalog), so the order here does not matter. Settings has no source: nothing there is
 * an item a member tags.
 */
object TagSources {
    val all: List<TagSource> = listOf(
        TasksTagSource,
        CalendarTagSource,
        NotesTagSource,
        ScheduleTagSource,
        MealsTagSource,
        RecipesTagSource,
        ShoppingTagSource,
        PantryTagSource,
        DocumentsTagSource,
        HealthTagSource,
        BudgetTagSource,
    )
}
