package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shapes of `server/routes/housekeeping.js` (docs/spec/housekeeping.md, "The models").
 * Every amount is `null` for a member without `housekeeping_view_amounts` (R20): a `null`
 * is never shown as "0.00" ([HousekeepingRules.amountsHidden]).
 */

/** `publicSession()`, and the visit rows of `GET /visits` with their extra fields. */
@Serializable
data class Visit(
    val id: Int,
    @SerialName("worker_id") val workerId: Int? = null,
    @SerialName("calendar_event_id") val calendarEventId: Int? = null,
    @SerialName("payment_task_id") val paymentTaskId: Int? = null,
    @SerialName("receipt_document_id") val receiptDocumentId: Int? = null,
    @SerialName("receipt_document_name") val receiptDocumentName: String? = null,
    /** `null` without Documents access; true with a receipt the server may not name (#1358). */
    @SerialName("has_receipt") val hasReceipt: Boolean? = null,
    @SerialName("check_in") val checkIn: String = "",
    @SerialName("check_out") val checkOut: String? = null,
    @SerialName("daily_rate") val dailyRate: Double? = null,
    val extras: Double? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("rate_type") val rateType: String = "daily",
    @SerialName("hourly_rate") val hourlyRate: Double? = null,
    @SerialName("minutes_worked") val minutesWorked: Double? = null,
    @SerialName("worker_name") val workerName: String? = null,
    @SerialName("worker_avatar_color") val workerAvatarColor: String? = null,
    @SerialName("worker_avatar_data") val workerAvatarData: String? = null,
    @SerialName("payment_schedule") val paymentSchedule: String? = null,
    @SerialName("payment_task_status") val paymentTaskStatus: String? = null,
    @SerialName("total_amount") val totalAmount: Double? = null,
    @SerialName("can_edit") val canEdit: FlexBool = false,
    @SerialName("can_delete") val canDelete: FlexBool = false,
    @SerialName("can_mark_paid") val canMarkPaid: FlexBool = false,
    @SerialName("can_mark_unpaid") val canMarkUnpaid: FlexBool = false,
) {
    val hourly: Boolean get() = rateType == "hourly"
    val paid: Boolean get() = paidAt != null
}

/** `publicWorker()`. */
@Serializable
data class Worker(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    val phone: String? = null,
    val email: String? = null,
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("birth_year_unknown") val birthYearUnknown: FlexBool = false,
    @SerialName("daily_rate") val dailyRate: Double? = null,
    @SerialName("rate_type") val rateType: String = "daily",
    @SerialName("hourly_rate") val hourlyRate: Double? = null,
    @SerialName("payment_schedule") val paymentSchedule: String? = null,
    @SerialName("calendar_color") val calendarColor: String? = null,
    /** The session still open: it carries the check-out button. */
    @SerialName("current_session") val currentSession: Visit? = null,
    /** Today's session, open or done: it carries the line under the name (#1133). */
    @SerialName("today_session") val todaySession: Visit? = null,
    val notes: String? = null,
) {
    val hourly: Boolean get() = rateType == "hourly"
}

/** `GET /workers`: the people and whether this member may create and edit profiles. */
@Serializable
data class WorkersAnswer(
    val data: List<Worker> = emptyList(),
    @SerialName("can_manage") val canManage: FlexBool = false,
)

/** `publicDecayTask()`: a chore with its decay state (`overdue`, `today`, `ok`). */
@Serializable
data class DecayTask(
    val id: Int,
    val name: String = "",
    val area: String = "",
    @SerialName("frequency_days") val frequencyDays: Int = 7,
    @SerialName("last_completed") val lastCompleted: String? = null,
    val urgency: Double? = null,
    @SerialName("urgency_status") val urgencyStatus: String = "ok",
    @SerialName("due_date") val dueDate: String? = null,
) {
    val due: Boolean get() = urgencyStatus == "overdue" || urgencyStatus == "today"
}

/** `TASK_TEMPLATES`: a suggested chore; [key] names its translation. */
@Serializable
data class TaskTemplate(
    val key: String? = null,
    val name: String = "",
    val area: String = "",
    @SerialName("frequency_days") val frequencyDays: Int = 7,
)

/** One month of the payments chart (`monthly_payments`). */
@Serializable
data class MonthPayment(val month: String = "", val total: Double = 0.0, val pending: Double = 0.0)

/** `housekeepingDashboard()`. */
@Serializable
data class Dashboard(
    @SerialName("visits_this_month") val visitsThisMonth: Int = 0,
    @SerialName("pending_tasks") val pendingTasks: Int = 0,
    @SerialName("finished_tasks_this_month") val finishedTasksThisMonth: Int = 0,
    @SerialName("pending_payments") val pendingPayments: Double? = null,
    @SerialName("paid_this_month") val paidThisMonth: Double? = null,
    @SerialName("monthly_payments") val monthlyPayments: List<MonthPayment>? = null,
)

@Serializable
data class VisitTotals(val total: Double = 0.0, val paid: Double = 0.0, val pending: Double = 0.0)

/** `GET /visits`: one month of visits with their totals (`null` without amounts). */
@Serializable
data class VisitReport(
    val month: String? = null,
    val visits: List<Visit> = emptyList(),
    val totals: VisitTotals? = null,
)

/** Everything `loadData()` reads, kept together for the offline cache (ADR 0007). */
@Serializable
data class HousekeepingData(
    val dashboard: Dashboard = Dashboard(),
    val tasks: List<DecayTask> = emptyList(),
    val current: VisitReport = VisitReport(),
    val templates: List<TaskTemplate> = emptyList(),
    val workers: List<Worker> = emptyList(),
    val canManage: Boolean = false,
    val currency: String = "EUR",
)
