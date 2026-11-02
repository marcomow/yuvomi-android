package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.runtime.Composable

/**
 * The section bodies (SET-R5). Native where the app can do what the web page does; the rest
 * stays in the browser behind [WebSectionCard] (ADR 0006, "Left in the browser" in the spec).
 */
@Composable
fun SectionContent(ctx: SectionContext, openLeaf: (String) -> Unit) {
    when (ctx.section.id) {
        "personal-account" -> AccountSection(ctx)
        "personal-appearance" -> AppearanceSection(ctx)
        "options-budget" -> OptionsBudget(ctx, openLeaf)
        "options-health" -> OptionsHealth(ctx)
        "options-housekeeping" -> OptionsHousekeeping(ctx)
        "options-tasks" -> OptionsTasks(ctx)
        "options-schedule" -> OptionsSchedule(ctx)
        "modules-countdowns" -> CountdownsSection(ctx)
        "modules-rewards" -> RewardsSection(ctx)
        "personal-tasks" -> PersonalTasksSection(ctx, openLeaf)
        "personal-calendar" -> PersonalCalendarSection(ctx)
        "personal-health" -> PersonalHealthSection(ctx)
        else -> WebSectionCard(ctx.section, ctx.shell.openWeb)
    }
}

/** The sections with native bodies; every other one opens in the web app. */
val NATIVE_SECTIONS: Set<String> = setOf(
    "personal-account", "personal-appearance", "options-budget", "options-health", "options-housekeeping",
    "options-tasks", "options-schedule", "modules-countdowns", "modules-rewards", "personal-tasks",
    "personal-calendar", "personal-health",
)
