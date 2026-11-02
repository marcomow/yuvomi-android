package io.github.marcomow.yuvomi.core.api.model

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The pieces of `GET /dashboard` the overview reads (docs/spec/overview.md,
 * OVERVIEW-R10). Upstream gives this answer no schema, so each piece is decoded
 * on its own and a broken piece degrades one tile, not the screen (OVERVIEW-R12).
 */

@Serializable
data class DashEvent(
    val id: Int? = null,
    val title: String = "",
    @SerialName("start_datetime") val startDatetime: String? = null,
    @SerialName("end_datetime") val endDatetime: String? = null,
    @SerialName("all_day") val allDay: FlexBool = false,
    val color: String? = null,
    val location: String? = null,
)

@Serializable
data class DashMeal(
    @SerialName("meal_type") val mealType: String = "",
    val title: String = "",
)

@Serializable
data class ShoppingListSummary(
    val id: Int? = null,
    val name: String = "",
    @SerialName("open_count") val openCount: Int = 0,
)

@Serializable
data class ShoppingTile(
    val lists: List<ShoppingListSummary> = emptyList(),
    val openCount: Int = 0,
    val listCount: Int = 0,
)

@Serializable
data class DashBirthday(
    val name: String = "",
    val kind: String = "birthday",
    @SerialName("days_until") val daysUntil: Int? = null,
    @SerialName("next_age") val nextAge: Int? = null,
    @SerialName("next_date") val nextDate: String? = null,
)

@Serializable
data class MemberOpenTasks(
    @SerialName("user_id") val userId: Int,
    @SerialName("open_count") val openCount: Int = 0,
)

/**
 * One row of `countdowns[]` (`DashboardCountdown`, services/countdowns.js): a marked event or
 * task, or since 2.84.0 a document within its expiry lead time (OVERVIEW-R17).
 */
@Serializable
data class DashCountdown(
    val source: String = "event",
    val id: Int,
    val title: String = "",
    val date: String = "",
    @SerialName("days_until") val daysUntil: Int = 0,
    val icon: String? = null,
    val color: String? = null,
    val recurring: FlexBool = false,
    /** Only on `document`: who the document is about. */
    @SerialName("person_name") val personName: String? = null,
)
