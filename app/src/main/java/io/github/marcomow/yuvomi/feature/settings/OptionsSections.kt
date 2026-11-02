package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The module sheets' options that are plain preferences (SET-R40 to R48):
 * `modules-options.js`, `personal-tasks.js`, `personal-calendar.js`, `modules-rewards.js`,
 * `modules-countdowns.js` and the switches of `personal-health.js`.
 */
@Composable
private fun prefs(ctx: SectionContext): JsonObject? {
    val slots by ctx.vm.slots.collectAsState()
    val slot = slots[SettingsSheetViewModel.PREFERENCES]
    val value = slot?.value as? JsonObject
    if (value == null) {
        if (slot?.errorKey != null) SettingsGroup(footer = t(slot.errorKey)) { ActionRow(t("settings.retry")) { ctx.vm.loadPreferences(force = true) } }
        else Busy(true)
    }
    return value
}

/** `modules-options.js` TOGGLES: one switch each, saved at once, rolled back on failure. */
@Composable
fun OptionToggleSection(ctx: SectionContext, label: String, hint: String, checked: (JsonObject) -> Boolean, savedKey: String, payload: (Boolean) -> JsonObject, footer: (@Composable () -> Unit)? = null) {
    val p = prefs(ctx) ?: return
    SettingsGroup(footer = hint) {
        SwitchRow(label, checked(p), enabled = ctx.editable) { ctx.save(savedKey, payload(it)) }
    }
    footer?.invoke()
}

@Composable
fun OptionsBudget(ctx: SectionContext, openLeaf: (String) -> Unit) = OptionToggleSection(
    ctx, t("settings.budgetModePersonalLabel"), t("settings.budgetModeHint"), { it.str("budget_mode") == "personal" },
    "settings.budgetModeSaved", { buildJsonObject { put("budget_mode", if (it) "personal" else "shared") } },
) {
    SettingsGroup(footer = t("settings.currencyMovedHint")) { ActionRow(t("settings.regionTitle")) { openLeaf("personal-appearance") } }
}

@Composable
fun OptionsHealth(ctx: SectionContext) = OptionToggleSection(
    ctx, t("settings.healthCycleEnableLabel"), t("settings.healthCycleHint"), { it.bool("health_cycle_enabled", true) },
    "settings.healthCycleSaved", { buildJsonObject { put("health_cycle_enabled", it) } },
)

@Composable
fun OptionsHousekeeping(ctx: SectionContext) = OptionToggleSection(
    ctx, t("settings.housekeepingPaymentTasksLabel"), t("settings.housekeepingPaymentTasksHint"), { it.bool("housekeeping_payment_tasks") },
    "settings.housekeepingPaymentTasksSaved", { buildJsonObject { put("housekeeping_payment_tasks", it) } },
)

@Composable
fun OptionsTasks(ctx: SectionContext) = OptionToggleSection(
    ctx, t("settings.tasksSubtasksExpandedLabel"), t("settings.tasksSubtasksExpandedHint"), { it.bool("tasks_subtasks_expanded") },
    "settings.tasksSubtasksExpandedSaved", { buildJsonObject { put("tasks_subtasks_expanded", it) } },
)

/** The schedule's quickstart templates: a switch each, the hidden ones saved as a list. */
@Composable
fun OptionsSchedule(ctx: SectionContext) {
    val p = prefs(ctx) ?: return
    val hidden = p.strings("schedule_hidden_templates")
    val templates = listOf("work" to "schedule.templateWork", "school" to "schedule.templateSchool", "university" to "schedule.templateUniversity")
    SettingsGroup(footer = t("settings.scheduleTemplatesHint")) {
        templates.forEachIndexed { i, (key, label) ->
            if (i > 0) RowDivider()
            SwitchRow(t(label), key !in hidden, enabled = ctx.editable) { on ->
                val next = templates.map { it.first }.filter { k -> if (k == key) !on else k in hidden }
                ctx.save("settings.scheduleTemplatesSaved", buildJsonObject { put("schedule_hidden_templates", JsonArray(next.map(::JsonPrimitive))) })
            }
        }
    }
}

/** `modules-countdowns.js`: grace days for expired countdowns, 0 to 90, saved on confirm. */
@Composable
fun CountdownsSection(ctx: SectionContext) {
    val p = prefs(ctx) ?: return
    val persisted = p.int("countdown_grace_days") ?: 7
    var text by rememberSaveable(persisted) { mutableStateOf(persisted.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsGroup(title = t("settings.countdownGraceDaysTitle"), footer = t("settings.countdownGraceDaysHint")) {
        GroupBody {
            Field(t("settings.countdownGraceDaysLabel"), text, { text = it.filter(Char::isDigit).take(3) }, error, enabled = ctx.editable, keyboard = KeyboardType.Number)
            if (ctx.editable) SaveRow(enabled = text != persisted.toString()) {
                val n = OptionRules.graceDays(text)
                if (n == null) { error = ctx.strings.t("settings.countdownGraceDaysInvalid", "max" to OptionRules.MAX_GRACE_DAYS); return@SaveRow }
                error = null
                ctx.save("settings.countdownGraceDaysSaved", buildJsonObject { put("countdown_grace_days", n) })
            }
        }
    }
}

/** `modules-rewards.js`: approval switch and default points, with the offer to move open tasks along. */
@Composable
fun RewardsSection(ctx: SectionContext) {
    val p = prefs(ctx) ?: return
    val persisted = p.int("tasks_default_points") ?: 0
    var text by rememberSaveable(persisted) { mutableStateOf(persisted.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var rebase by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    SettingsGroup(footer = t("settings.rewardsApprovalHint")) {
        SwitchRow(t("settings.rewardsApprovalLabel"), p.bool("rewards_require_approval", true), enabled = ctx.editable) {
            ctx.save("settings.rewardsSaved", buildJsonObject { put("rewards_require_approval", it) })
        }
    }
    SettingsGroup(footer = t(if (persisted > 0) "settings.rewardsDefaultPointsHint" else "settings.rewardsDefaultPointsOffHint")) {
        GroupBody {
            Field(t("settings.rewardsDefaultPointsLabel"), text, { text = it.filter(Char::isDigit).take(5) }, error, enabled = ctx.editable, keyboard = KeyboardType.Number)
            if (ctx.editable) SaveRow(enabled = text != persisted.toString()) {
                val next = OptionRules.points(text)
                if (next == null) { error = ctx.strings.t("settings.rewardsDefaultPointsInvalid", "max" to OptionRules.MAX_POINTS); return@SaveRow }
                error = null
                val from = persisted
                ctx.scope.launch {
                    val failed = ctx.vm.savePreferences(buildJsonObject { put("tasks_default_points", next) })
                    if (failed != null) { error = ctx.strings.t(failed); return@launch }
                    ctx.notifyKey("settings.rewardsDefaultPointsSaved")
                    if (from <= 0) return@launch
                    val count = ctx.vm.attempt { it.data(SettingsOperation.TASKS_POINTS_AFFECTED, query = listOf("points" to from.toString())) }
                        .getOrNull()?.obj()?.int("count") ?: 0
                    if (count > 0) rebase = Triple(count, from, next)
                }
            }
        }
    }
    rebase?.let { (count, from, to) ->
        ConfirmDialog(
            ctx.strings.t("settings.rewardsDefaultPointsRebaseTitle", "count" to count, "from" to from, "to" to to),
            t("settings.rewardsDefaultPointsRebaseDetail"), t("settings.rewardsDefaultPointsRebaseConfirm"),
            onDismiss = { rebase = null },
        ) {
            rebase = null
            ctx.scope.launch {
                ctx.vm.attempt { it.data(SettingsOperation.TASKS_POINTS_REBASE, body = buildJsonObject { put("from", from); put("to", to) }) }
                    .onSuccess { ctx.notifyKey("settings.rewardsDefaultPointsRebased", "count" to (it.obj().int("updated") ?: 0)) }
                    .onFailure { ctx.notifyKey(SettingsErrors.key(it)) }
            }
        }
    }
}

/** `personal-tasks.js`: reminder list, visibility and lock for new tasks (mine). */
@Composable
fun PersonalTasksSection(ctx: SectionContext, openLeaf: (String) -> Unit) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) { ctx.vm.load("tasks-sync-targets") { it.data(SettingsOperation.TASKS_SYNC_TARGETS) } }
    val p = prefs(ctx) ?: return
    val targets = slots["tasks-sync-targets"]
    SettingsGroup(title = t("settings.tasksDefaultsTitle"), footer = t("settings.tasksDefaultsDescription")) {
        val lists = targets?.value?.obj()?.get("caldav").arr().map { it.obj() }
        val current = p.str("tasks_default_target").orEmpty()
        when {
            targets?.errorKey != null -> ValueRow(t("settings.tasksDefaultTargetLabel"), t("settings.tasksDefaultTargetLoadError"))
            lists == null -> Busy(targets?.loading == true)
            lists.isEmpty() && current.isEmpty() -> {
                ValueRow(t("settings.tasksDefaultTargetLabel"), t("settings.tasksDefaultTargetEmpty"))
                if (ctx.isAdmin) ActionRow(t("settings.pageSyncReminders"), hint = t("settings.tasksDefaultTargetEmptyAdmin")) { openLeaf("module-tasks") }
            }
            else -> {
                val options = listOf("" to t("tasks.syncTargetLocal")) +
                    lists.map { "caldav:${it.int("accountId")}|${it.str("listUrl")}" to listOfNotNull(it.str("accountName"), it.str("listName") ?: it.str("listUrl")).joinToString(" · ") } +
                    (if (current.isNotEmpty() && lists.none { "caldav:${it.int("accountId")}|${it.str("listUrl")}" == current }) listOf(current to t("settings.tasksDefaultTargetUnavailable")) else emptyList())
                ChoiceRow(t("settings.tasksDefaultTargetLabel"), options, current, ctx.editable, t("settings.tasksDefaultTargetHint")) {
                    ctx.save("settings.tasksDefaultsSaved", buildJsonObject { put("tasks_default_target", it) })
                }
            }
        }
        RowDivider()
        ChoiceRow(
            t("settings.tasksDefaultVisibilityLabel"),
            listOf("all", "assignees", "private").map { it to t("common.visibility.$it") },
            p.str("tasks_default_visibility") ?: "all", ctx.editable, t("settings.tasksDefaultVisibilityHint"),
        ) { ctx.save("settings.tasksDefaultsSaved", buildJsonObject { put("tasks_default_visibility", it) }) }
        RowDivider()
        SwitchRow(t("settings.tasksDefaultLockedLabel"), p.bool("tasks_default_locked"), ctx.editable, t("settings.tasksDefaultLockedHint")) {
            ctx.save("settings.tasksDefaultsSaved", buildJsonObject { put("tasks_default_locked", it) })
        }
    }
}

/** `personal-calendar.js`: assign me, target calendar, default reminders (at most five). */
@Composable
fun PersonalCalendarSection(ctx: SectionContext) {
    val slots by ctx.vm.slots.collectAsState()
    LaunchedEffect(Unit) { ctx.vm.load("calendar-sync-targets") { it.data(SettingsOperation.CALENDAR_SYNC_TARGETS) } }
    val p = prefs(ctx) ?: return
    val targets = slots["calendar-sync-targets"]?.value?.obj()
    val saved = p.strings("calendar_default_reminders").mapNotNull { it.toIntOrNull() }.toSet()
    var reminders by remember(saved) { mutableStateOf(saved) }
    SettingsGroup(footer = t("settings.calendarDefaultsScopeHint")) {
        SwitchRow(t("settings.calendarAssignMeLabel"), p.bool("calendar_default_assign_me"), ctx.editable) {
            ctx.save("settings.calendarDefaultsSaved", buildJsonObject { put("calendar_default_assign_me", it) })
        }
        if (targets != null) {
            RowDivider()
            val current = p.str("calendar_default_target").orEmpty()
            val options = OptionRules.calendarTargets(targets, current, t("calendar.syncTargetLocal"), t("calendar.syncTargetGoogleGroup"), t("calendar.syncTargetCaldavGroup"), t("settings.calendarDefaultTargetUnavailable"))
            ChoiceRow(t("settings.calendarDefaultTargetLabel"), options, current, ctx.editable, t("settings.calendarDefaultTargetHint")) {
                ctx.save("settings.calendarDefaultsSaved", buildJsonObject { put("calendar_default_target", it) })
            }
        }
    }
    SettingsGroup(title = t("settings.calendarDefaultRemindersLabel"), footer = t("settings.calendarDefaultRemindersHint")) {
        GroupBody {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionRules.REMINDER_OFFSETS.forEach { (value, key) ->
                    FilterChip(
                        selected = value in reminders, enabled = ctx.editable,
                        onClick = {
                            val next = if (value in reminders) reminders - value else reminders + value
                            if (next.size > OptionRules.MAX_DEFAULT_REMINDERS) {
                                ctx.notifyKey("settings.calendarDefaultRemindersMax", "count" to OptionRules.MAX_DEFAULT_REMINDERS)
                            } else reminders = next
                        },
                        label = { Text(t(key)) },
                    )
                }
            }
        }
    }
    // Debounced like the web page: quick taps make one save and one confirmation.
    LaunchedEffect(reminders) {
        if (reminders == saved) return@LaunchedEffect
        delay(500)
        val error = ctx.vm.savePreferences(buildJsonObject { put("calendar_default_reminders", JsonArray(reminders.sorted().map(::JsonPrimitive))) })
        if (error != null) { reminders = saved; ctx.notifyKey(error) } else ctx.notifyKey("settings.calendarDefaultsSaved")
    }
}

/** The switches of `personal-health.js`; the per-area visibility defaults stay in the web app. */
@Composable
fun PersonalHealthSection(ctx: SectionContext) {
    val p = prefs(ctx) ?: return
    val household = p.bool("health_cycle_enabled", true)
    SettingsGroup(title = t("health.tabs.cycle"), footer = if (household) t("settings.healthCyclePersonalHint") else t("settings.healthCyclePersonalHouseholdOff")) {
        SwitchRow(t("settings.healthCyclePersonalLabel"), p.bool("health_cycle_enabled_user", true), ctx.editable && household) {
            ctx.save("settings.healthCyclePersonalSaved", buildJsonObject { put("health_cycle_enabled_user", it) })
        }
    }
    SettingsGroup(title = t("health.tabs.prevention"), footer = t("settings.healthPreventionNotifyCaregiversHint")) {
        SwitchRow(t("settings.healthPreventionNotifyCaregiversLabel"), p.bool("health_prevention_notify_caregivers"), ctx.editable) {
            ctx.save("settings.healthPreventionNotifyCaregiversSaved", buildJsonObject { put("health_prevention_notify_caregivers", it) })
        }
    }
    SettingsGroup(title = t("settings.healthVisibilityTitle"), footer = t("settings.healthVisibilityHint")) {
        ActionRow(t("android.browser.openWebApp"), hint = t("android.browser.opensInBrowser")) { ctx.openWeb() }
    }
}

@Composable
fun SaveRow(enabled: Boolean = true, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onSave, enabled = enabled) { Text(t("common.save")) }
    }
}

/** Pure rules of the option sheets. */
object OptionRules {
    const val MAX_GRACE_DAYS = 90
    const val MAX_POINTS = 10000
    const val MAX_DEFAULT_REMINDERS = 5

    val REMINDER_OFFSETS = listOf(
        0 to "reminders.offsetAtTime", 15 to "reminders.offset15min", 60 to "reminders.offset1hour",
        1440 to "reminders.offset1day", 2880 to "reminders.offset2days", 10080 to "reminders.offset1week",
        20160 to "reminders.offset2weeks",
    )

    /** `parseGraceDaysInput`. */
    fun graceDays(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it in 0..MAX_GRACE_DAYS }

    fun points(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it in 0..MAX_POINTS }

    /** `buildSyncTargetOptions` with Google and CalDAV calendars, as the calendar defaults offer them. */
    fun calendarTargets(targets: JsonObject, current: String, local: String, google: String, caldav: String, unavailable: String): List<Pair<String, String>> {
        val options = mutableListOf("" to local)
        targets["google"].arr().map { it.obj() }.forEach { options += "google:${it.str("id")}" to "$google · ${it.str("summary") ?: it.str("id")}" }
        targets["caldav"].arr().map { it.obj() }.forEach {
            options += "caldav:${it.int("accountId")}|${it.str("calendarUrl")}" to "$caldav · ${it.str("accountName")} · ${it.str("calendarName") ?: it.str("calendarUrl")}"
        }
        if (current.isNotEmpty() && options.none { it.first == current }) options += current to unavailable
        return options
    }
}
