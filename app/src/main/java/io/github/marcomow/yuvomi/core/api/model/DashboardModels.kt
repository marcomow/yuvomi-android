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
