package io.github.marcomow.yuvomi.feature.reminders

import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.calendar.CalendarRoutes
import io.github.marcomow.yuvomi.feature.documents.DocumentsRoutes
import io.github.marcomow.yuvomi.feature.health.HealthRoutes
import io.github.marcomow.yuvomi.feature.shell.Routes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A row of `GET /reminders/upcoming` (upstream's `UpcomingReminder`), the fields the phone uses. */
@Serializable
data class UpcomingReminder(
    val id: Int,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: Int,
    @SerialName("entity_title") val entityTitle: String? = null,
    @SerialName("fires_at") val firesAt: String,
    @SerialName("assigned_from") val assignedFrom: Int? = null,
    @SerialName("cycle_anchor_kind") val cycleAnchorKind: String? = null,
    @SerialName("cycle_owner_name") val cycleOwnerName: String? = null,
    @SerialName("prevention_subject_name") val preventionSubjectName: String? = null,
    @SerialName("move_notice") val moveNotice: MoveNotice? = null,
)

@Serializable
data class MoveNotice(
    val reason: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("back_list_name") val backListName: String? = null,
    @SerialName("target_list_name") val targetListName: String? = null,
)

@Serializable
data class UpcomingAnswer(val data: List<UpcomingReminder> = emptyList())

/** One notification the phone has scheduled: what it says and where a tap goes, fixed at scheduling. */
@Serializable
data class Scheduled(val id: Int, val at: Long, val title: String, val body: String, val route: String?)

/**
 * What a reminder says and opens, as upstream's `public/reminders.js` shows it (REM-R3, R4).
 * Pure, so it is unit tested.
 */
object PhoneReminders {
    /** The window asked for: upstream allows at most 14 days. */
    const val WINDOW_DAYS = 14L

    /** `REMINDER_ORIGINS`: an origin's label, the notification's title. */
    private val LABELS = mapOf(
        "task" to "nav.tasks", "event" to "nav.calendar", "subscription" to "subscriptions.tabLabel",
        "inventory_item" to "nav.inventory", "inventory_tracked_date" to "nav.inventory", "pantry_item" to "nav.pantry",
        "cycle_period" to "health.cycle.title", "cycle_log_nudge" to "health.cycle.title",
        "schedule_entry" to "nav.schedule", "schedule_extra_entry" to "nav.schedule", "waste_pickup" to "nav.waste",
        "document_expiry" to "nav.documents", "health_prevention_due" to "health.tabs.prevention",
        "fasting_goal" to "health.fasting.title", "fasting_next_start" to "health.fasting.title",
        "shopping_move_returned" to "nav.shopping",
    )

    fun title(r: UpcomingReminder, strings: Strings): String = strings.t(LABELS[r.entityType] ?: "reminders.toastTitle")

    /** `reminderBody`: the move notice, fasting, cycle and prevention sentences, else the entity's title. */
    fun body(r: UpcomingReminder, strings: Strings): String {
        r.moveNotice?.takeIf { r.entityType == "shopping_move_returned" }?.let { n ->
            return strings.t(
                if (n.reason == "waited") "shopping.moveReturned.waited" else "shopping.moveReturned.refused",
                "item" to n.itemName, "back" to n.backListName, "target" to n.targetListName,
            )
        }
        when (r.entityType) {
            "fasting_goal" -> return strings.t("health.fasting.goalReached")
            "fasting_next_start" -> return strings.t("health.fasting.remindNext")
            "cycle_log_nudge" -> return strings.t("health.cycle.settings.remindLogDaily")
            "cycle_period" -> {
                val title = r.entityTitle.orEmpty()
                if (r.cycleAnchorKind == "partner_period") {
                    r.cycleOwnerName?.let { return strings.t("health.cycle.status.partnerNextPeriod", "name" to it, "date" to date(title, strings)) }
                    return "${strings.t("health.cycle.status.partnerNextPeriodNeutral")} - $title"
                }
                return "${strings.t("health.cycle.status.nextPeriod")} - $title"
            }
            "health_prevention_due" -> if (r.assignedFrom != null && r.preventionSubjectName != null) {
                return "${r.entityTitle.orEmpty()} - ${r.preventionSubjectName}"
            }
        }
        return r.entityTitle.orEmpty()
    }

    private fun date(text: String, strings: Strings): String =
        runCatching { LocalDate.parse(text.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(strings.locale))) }
            .getOrDefault(text)

    /** REM-R4: where a tap opens the app; the module's page when the origin has no page of its own. */
    fun route(r: UpcomingReminder): String? = when (r.entityType) {
        "task" -> Routes.task(r.entityId)
        "event" -> CalendarRoutes.event(r.entityId)
        "document_expiry" -> DocumentsRoutes.document(r.entityId)
        "cycle_period", "cycle_log_nudge" -> HealthRoutes.CYCLE
        "fasting_goal", "fasting_next_start" -> HealthRoutes.FASTING
        "health_prevention_due" -> HealthRoutes.PREVENTION
        "subscription" -> "budget"
        "inventory_item", "inventory_tracked_date" -> "inventory"
        "pantry_item" -> "pantry"
        "schedule_entry", "schedule_extra_entry" -> "schedule"
        "waste_pickup" -> "waste"
        "shopping_move_returned" -> "shopping"
        else -> null
    }

    fun firesAt(r: UpcomingReminder): Long? = runCatching { Instant.parse(r.firesAt).toEpochMilli() }.getOrNull()

    /** REM-R2: what to schedule from an answer: future rows only, each once. */
    fun plan(rows: List<UpcomingReminder>, now: Long, strings: Strings): List<Scheduled> =
        rows.distinctBy { it.id }.mapNotNull { r ->
            val at = firesAt(r) ?: return@mapNotNull null
            if (at <= now) null else Scheduled(r.id, at, title(r, strings), body(r, strings), route(r))
        }.sortedBy { it.at }
}
