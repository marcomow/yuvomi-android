package io.github.marcomow.yuvomi.feature

import io.github.marcomow.yuvomi.core.api.ApiOperation
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysFeature
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysOperation
import io.github.marcomow.yuvomi.feature.budget.BudgetFeature
import io.github.marcomow.yuvomi.feature.budget.BudgetOperation
import io.github.marcomow.yuvomi.feature.calendar.CalendarFeature
import io.github.marcomow.yuvomi.feature.calendar.CalendarOperation
import io.github.marcomow.yuvomi.feature.contacts.ContactsFeature
import io.github.marcomow.yuvomi.feature.contacts.ContactsOperation
import io.github.marcomow.yuvomi.feature.documents.DocumentsFeature
import io.github.marcomow.yuvomi.feature.documents.DocumentsOperation
import io.github.marcomow.yuvomi.feature.health.HealthFeature
import io.github.marcomow.yuvomi.feature.health.HealthOperation
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingFeature
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingOperation
import io.github.marcomow.yuvomi.feature.inventory.InventoryFeature
import io.github.marcomow.yuvomi.feature.inventory.InventoryOperation
import io.github.marcomow.yuvomi.feature.meals.MealsFeature
import io.github.marcomow.yuvomi.feature.meals.MealsOperation
import io.github.marcomow.yuvomi.feature.notes.NotesFeature
import io.github.marcomow.yuvomi.feature.notes.NotesOperation
import io.github.marcomow.yuvomi.feature.pantry.PantryFeature
import io.github.marcomow.yuvomi.feature.pantry.PantryOperation
import io.github.marcomow.yuvomi.feature.recipes.RecipesFeature
import io.github.marcomow.yuvomi.feature.recipes.RecipesOperation
import io.github.marcomow.yuvomi.feature.rewards.RewardsFeature
import io.github.marcomow.yuvomi.feature.rewards.RewardsOperation
import io.github.marcomow.yuvomi.feature.schedule.ScheduleFeature
import io.github.marcomow.yuvomi.feature.schedule.ScheduleOperation
import io.github.marcomow.yuvomi.feature.settings.SettingsFeature
import io.github.marcomow.yuvomi.feature.settings.SettingsOperation
import io.github.marcomow.yuvomi.feature.shell.Feature
import io.github.marcomow.yuvomi.feature.shopping.ShoppingFeature
import io.github.marcomow.yuvomi.feature.shopping.ShoppingOperation
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitExpensesOperation
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsOperation
import io.github.marcomow.yuvomi.feature.tags.TagsFeature
import io.github.marcomow.yuvomi.feature.tags.TagsOperation
import io.github.marcomow.yuvomi.feature.waste.WasteFeature
import io.github.marcomow.yuvomi.feature.waste.WasteOperation

/** The native modules beyond Overview and Tasks, in bar and "More" order (ADR 0015). */
object Features {
    val all: List<Feature> = listOf(
        ShoppingFeature, MealsFeature, TagsFeature, RecipesFeature, NotesFeature, PantryFeature,
        CalendarFeature, RewardsFeature, ScheduleFeature, HousekeepingFeature, WasteFeature, DocumentsFeature,
        InventoryFeature, ContactsFeature, BirthdaysFeature, HealthFeature, BudgetFeature, SettingsFeature,
    )

    /**
     * Modules whose native screens are still being built. Their routes are registered so links
     * from finished modules land on a placeholder that offers the web app, but they are not
     * native yet: "More" lists them as web modules.
     */
    val pending: List<Feature> = emptyList()

    /** Every upstream operation the app calls; checked against upstream/operations-used*.txt. */
    val operations: List<ApiOperation> = listOf(
        Operation.entries, ShoppingOperation.entries, MealsOperation.entries,
        RecipesOperation.entries, NotesOperation.entries, PantryOperation.entries,
        CalendarOperation.entries, RewardsOperation.entries, ScheduleOperation.entries,
        HousekeepingOperation.entries, WasteOperation.entries, DocumentsOperation.entries,
        InventoryOperation.entries, ContactsOperation.entries, BirthdaysOperation.entries,
        HealthOperation.entries, BudgetOperation.entries, SettingsOperation.entries,
        SubscriptionsOperation.entries, SplitExpensesOperation.entries, TagsOperation.entries,
        io.github.marcomow.yuvomi.feature.reminders.RemindersOperation.entries,
    ).flatten()

    /** Module ids that have native screens: Overview, Tasks and [all]. */
    val nativeIds: Set<String> = setOf("dashboard", "tasks") + all.map { it.id }
}
