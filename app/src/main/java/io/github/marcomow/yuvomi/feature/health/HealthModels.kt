package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A household member as `GET /family/members` lists them (the person switcher). */
@Serializable
data class HealthMember(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
)

/** `health_vitals` (server/routes/health/vitals.js). */
@Serializable
data class Vital(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    val type: String = "",
    @SerialName("value_num") val value1: Double? = null,
    @SerialName("value_num2") val value2: Double? = null,
    @SerialName("value_num3") val value3: Double? = null,
    val unit: String? = null,
    @SerialName("measured_at") val measuredAt: String = "",
    val note: String? = null,
    val visibility: String = "private",
) {
    fun channel(i: Int): Double? = when (i) {
        0 -> value1
        1 -> value2
        else -> value3
    }
}

/** `medication_schedules`. */
@Serializable
data class MedSchedule(
    val id: Int = 0,
    @SerialName("medication_id") val medicationId: Int? = null,
    @SerialName("time_of_day") val timeOfDay: String = "00:00",
    @SerialName("days_mask") val daysMask: Int? = null,
    @SerialName("dose_qty") val doseQty: Double? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val active: FlexBool = true,
)

/** `medication_logs`. */
@Serializable
data class MedLog(
    val id: Int,
    @SerialName("medication_id") val medicationId: Int = 0,
    @SerialName("schedule_id") val scheduleId: Int? = null,
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    val status: String = "pending",
    @SerialName("taken_at") val takenAt: String? = null,
    @SerialName("dose_qty") val doseQty: Double? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** `COALESCE(scheduled_at, taken_at, created_at)`: the server's order and filter. */
    val whenKey: String get() = scheduledAt ?: takenAt ?: createdAt.orEmpty()
}

/** `medications`, with `?include=schedules,logs`. */
@Serializable
data class Medication(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    val name: String = "",
    @SerialName("dosage_text") val dosageText: String? = null,
    val form: String? = null,
    val active: FlexBool = true,
    val prn: FlexBool = false,
    @SerialName("stock_qty") val stockQty: Double? = null,
    @SerialName("stock_unit") val stockUnit: String? = null,
    @SerialName("refill_threshold") val refillThreshold: Double? = null,
    val note: String? = null,
    val visibility: String = "private",
    @SerialName("min_interval_hours") val minIntervalHours: Double? = null,
    @SerialName("prn_dose_qty") val prnDoseQty: Double? = null,
    val schedules: List<MedSchedule> = emptyList(),
    val logs: List<MedLog> = emptyList(),
)

/** A booked dose: the log row and what the booking took from the stock (`stock_deducted`). */
data class DoseBooking(val log: MedLog?, val stockDeducted: Double)

/** `health_fasts`. */
@Serializable
data class Fast(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    val revision: Int = 1,
    @SerialName("start_at") val startAt: String = "",
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("start_tzid") val startTzid: String? = null,
    @SerialName("goal_minutes") val goalMinutes: Int? = null,
    val rating: Int? = null,
    val note: String? = null,
    val visibility: String = "private",
)

@Serializable
data class FastingSettings(
    @SerialName("default_goal_minutes") val defaultGoalMinutes: Int? = null,
    @SerialName("zone_mode") val zoneMode: String? = null,
    @SerialName("clock_mode") val clockMode: String? = null,
    @SerialName("remind_goal") val remindGoal: FlexBool = false,
    @SerialName("remind_next_start") val remindNextStart: FlexBool = false,
)

@Serializable
data class FastingCursor(@SerialName("before_at") val beforeAt: String, @SerialName("before_id") val beforeId: Int)

/** `GET /fasting/state` (services/fasting.js, `getFastingState`). */
@Serializable
data class FastingState(
    @SerialName("server_now") val serverNow: String? = null,
    val active: Fast? = null,
    val history: List<Fast> = emptyList(),
    @SerialName("history_has_more") val historyHasMore: FlexBool = false,
    @SerialName("history_next_cursor") val historyNextCursor: FastingCursor? = null,
    val settings: FastingSettings? = null,
    val canWrite: FlexBool = false,
    val acknowledged: FlexBool = false,
)

@Serializable
data class FastingSummary(
    val count: Int = 0,
    val totalMinutes: Double = 0.0,
    val averageMinutes: Double? = null,
    val longestMinutes: Double? = null,
)

/** `GET /fasting/stats`. */
@Serializable
data class FastingStats(
    val allTime: FastingSummary = FastingSummary(),
    val year: FastingSummary = FastingSummary(),
    val last30Days: FastingSummary = FastingSummary(),
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
)

/** `cycle_periods`. */
@Serializable
data class CyclePeriod(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String? = null,
    val note: String? = null,
    val visibility: String = "private",
)

@Serializable
data class CycleSymptom(val key: String, val intensity: Int? = null)

/** `cycle_day_logs`, with symptoms and feelings. The owner-only fields come only for own rows. */
@Serializable
data class CycleDayLog(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("log_date") val logDate: String = "",
    val flow: String? = null,
    val note: String? = null,
    val visibility: String = "private",
    @SerialName("basal_temp") val basalTemp: Double? = null,
    @SerialName("basal_temp_unit") val basalTempUnit: String? = null,
    @SerialName("cervix_mucus") val cervixMucus: String? = null,
    @SerialName("lh_test") val lhTest: String? = null,
    @SerialName("pregnancy_test") val pregnancyTest: String? = null,
    val intimacy: String? = null,
    val symptoms: List<CycleSymptom> = emptyList(),
    val feelings: List<String> = emptyList(),
)

/** `cycle_settings`; `PUT` replaces every field, so the screen sends them all back. */
@Serializable
data class CycleSettings(
    @SerialName("cycle_length_avg") val cycleLengthAvg: Int? = null,
    @SerialName("period_length_avg") val periodLengthAvg: Int? = null,
    @SerialName("luteal_length") val lutealLength: Int? = 14,
    @SerialName("track_fertility") val trackFertility: FlexBool = true,
    @SerialName("pregnancy_mode") val pregnancyMode: FlexBool = false,
    @SerialName("pregnancy_due_date") val pregnancyDueDate: String? = null,
    @SerialName("default_visibility") val defaultVisibility: String = "private",
    @SerialName("remind_period_days_before") val remindPeriodDaysBefore: Int? = null,
    @SerialName("remind_log_daily") val remindLogDaily: FlexBool = false,
    val contraception: String? = null,
    @SerialName("perimenopause_mode") val perimenopauseMode: FlexBool = false,
    @SerialName("show_pms") val showPms: FlexBool = true,
    @SerialName("notify_partner_user_id") val notifyPartnerUserId: Int? = null,
    @SerialName("notify_partner_days_before") val notifyPartnerDaysBefore: Int? = null,
)

@Serializable
data class PreventionType(
    val id: Int,
    val name: String = "",
    val kind: String? = null,
    val icon: String? = null,
    @SerialName("default_interval_months") val defaultIntervalMonths: Int? = null,
)

@Serializable
data class PreventionRecord(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("type_id") val typeId: Int? = null,
    val name: String? = null,
    @SerialName("given_on") val givenOn: String = "",
    @SerialName("dose_number") val doseNumber: Double? = null,
    val batch: String? = null,
    val provider: String? = null,
    val note: String? = null,
    @SerialName("interval_months") val intervalMonths: Int? = null,
    @SerialName("next_due_on") val nextDueOn: String? = null,
    @SerialName("reminder_offset_days") val reminderOffsetDays: Int? = null,
    val visibility: String = "private",
)

/** `GET /prevention/due` (services/prevention-due.js). */
@Serializable
data class PreventionDue(
    @SerialName("type_id") val typeId: Int? = null,
    @SerialName("type_name") val typeName: String = "",
    @SerialName("record_id") val recordId: Int? = null,
    @SerialName("due_on") val dueOn: String = "",
    @SerialName("days_left") val daysLeft: Int = 0,
)

@Serializable
data class LabResult(
    val id: Int = 0,
    @SerialName("report_id") val reportId: Int? = null,
    val analyte: String = "",
    @SerialName("value_num") val value: Double? = null,
    val unit: String? = null,
    @SerialName("ref_low") val refLow: Double? = null,
    @SerialName("ref_high") val refHigh: Double? = null,
    val flag: String? = null,
)

@Serializable
data class LabReport(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("report_date") val reportDate: String = "",
    @SerialName("lab_name") val labName: String? = null,
    val note: String? = null,
    val visibility: String = "private",
    val results: List<LabResult> = emptyList(),
)

@Serializable
data class Activity(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    val type: String = "",
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    val intensity: String? = null,
    val calories: Double? = null,
    @SerialName("performed_at") val performedAt: String = "",
    val note: String? = null,
    val visibility: String = "private",
)

@Serializable
data class NutritionEntry(
    val id: Int,
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("consumed_at") val consumedAt: String = "",
    @SerialName("meal_type") val mealType: String? = null,
    val title: String = "",
    @SerialName("energy_kcal") val energyKcal: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("saturated_fat_g") val saturatedFatG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("salt_g") val saltG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    val note: String? = null,
    val visibility: String = "private",
) {
    fun nutrient(key: String): Double? = when (key) {
        "energy_kcal" -> energyKcal
        "fat_g" -> fatG
        "saturated_fat_g" -> saturatedFatG
        "carbs_g" -> carbsG
        "sugar_g" -> sugarG
        "protein_g" -> proteinG
        "salt_g" -> saltG
        "fiber_g" -> fiberG
        else -> null
    }
}

/** `GET /nutrition/summary` (services/health-nutrition.js, `nutritionSummaryFor`). */
@Serializable
data class NutritionSummary(
    val date: String? = null,
    val target: Map<String, Double?>? = null,
    val totals: Map<String, Double> = emptyMap(),
    val entryCount: Int = 0,
)
