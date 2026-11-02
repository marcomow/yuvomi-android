package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A field attached to a shift type (`fieldsForShiftTypes` in server/services/schedule.js). */
@Serializable
data class ShiftField(
    val id: Int,
    val name: String = "",
    val position: Int = 0,
    @SerialName("show_in_overlay") val showInOverlay: FlexBool = false,
)

/** `schedule_shift_types` with its attached fields (`typeColumns`, `withFields`). */
@Serializable
data class ShiftType(
    val id: Int,
    val name: String = "",
    @SerialName("short_code") val shortCode: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    val color: String = ScheduleRules.SHIFT_COLOR_DEFAULT,
    val icon: String? = null,
    @SerialName("created_by") val createdBy: Int? = null,
    val fields: List<ShiftField> = emptyList(),
) {
    val timed: Boolean get() = !startTime.isNullOrEmpty() && !endTime.isNullOrEmpty()
}

/** `schedule_custom_fields`: defined once, attached to any number of shift types. */
@Serializable
data class CustomField(
    val id: Int,
    val name: String = "",
    @SerialName("created_by") val createdBy: Int? = null,
)

/** One row of a cycle day; a position may carry several (a timetable). */
@Serializable
data class PatternDay(
    val id: Int = 0,
    @SerialName("pattern_id") val patternId: Int = 0,
    val position: Int = 0,
    @SerialName("shift_type_id") val shiftTypeId: Int? = null,
    @SerialName("field_values") val fieldValues: Map<String, String> = emptyMap(),
)

/** `schedule_patterns`; [days] is filled from `GET /patterns/{id}/days` (`load`). */
@Serializable
data class Pattern(
    val id: Int,
    @SerialName("user_id") val userId: Int = 0,
    val name: String = "",
    @SerialName("anchor_date") val anchorDate: String = "",
    @SerialName("cycle_length") val cycleLength: Int = 7,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("is_active") val isActive: FlexBool = true,
    val days: List<PatternDay> = emptyList(),
)

/** `schedule_overrides`: one day of one person replaced; no shift type means a free day. */
@Serializable
data class Override(
    val id: Int,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("date_key") val dateKey: String = "",
    @SerialName("shift_type_id") val shiftTypeId: Int? = null,
    val note: String? = null,
    @SerialName("field_values") val fieldValues: Map<String, String> = emptyMap(),
)

/** `schedule_extra_shifts`: additive to the pattern and the override, any number per day. */
@Serializable
data class Extra(
    val id: Int,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("date_key") val dateKey: String = "",
    @SerialName("shift_type_id") val shiftTypeId: Int? = null,
    val note: String? = null,
    @SerialName("reminder_offset_minutes") val reminderOffsetMinutes: Int? = null,
    @SerialName("field_values") val fieldValues: Map<String, String> = emptyMap(),
)

/** A resolved entry of `GET /schedule/entries` (`scheduleData`). */
@Serializable
data class Entry(
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("date_key") val dateKey: String = "",
    val source: String = SOURCE_PATTERN,
    @SerialName("pattern_id") val patternId: Int? = null,
    val position: Int? = null,
    @SerialName("pattern_day_id") val patternDayId: Int? = null,
    @SerialName("override_id") val overrideId: Int? = null,
    @SerialName("extra_id") val extraId: Int? = null,
    @SerialName("shift_type_id") val shiftTypeId: Int? = null,
    val note: String? = null,
    @SerialName("is_free") val isFree: FlexBool = false,
    @SerialName("shift_type") val shiftType: ShiftType? = null,
    @SerialName("crosses_midnight") val crossesMidnight: FlexBool = false,
    @SerialName("field_values") val fieldValues: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SOURCE_PATTERN = "pattern"
        const val SOURCE_OVERRIDE = "override"
        const val SOURCE_EXTRA = "extra"
    }
}

/** Two active patterns of one person on one day: the newer one is shown. */
@Serializable
data class OverlapWarning(
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("date_key") val dateKey: String = "",
    @SerialName("pattern_ids") val patternIds: List<Int> = emptyList(),
)

@Serializable
data class Entries(val entries: List<Entry> = emptyList(), val warnings: List<OverlapWarning> = emptyList())

/** `GET /auth/users`: the account directory that names every row's owner (`userName`). */
@Serializable
data class Account(
    val id: Int,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

/** `GET /schedule/household-members`: the people who may get a lane or a plan. */
@Serializable
data class Person(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

/** `GET /schedule/preferences`: the member's own values (the Statistics tab reads them). */
@Serializable
data class MyPreferences(
    val reminderOffsetMinutes: Int? = null,
    val weeklyHours: Int? = null,
    val overtimeEnabled: Boolean = true,
)

/** The household's choices on `GET /preferences` this page reads. */
@Serializable
data class HouseholdChoices(
    @SerialName("week_start") val weekStart: String? = null,
    @SerialName("schedule_hidden_templates") val hiddenTemplates: List<String> = emptyList(),
)

/** One holiday or school-holiday band of `GET /calendar/holidays`. */
@Serializable
data class Holiday(
    val name: String = "",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    val color: String? = null,
)

/** The member's own schedule feed (`GET /schedule/feed`); null data means it is off. */
@Serializable
data class Feed(val token: String? = null, val url: String = "")

/** Everything `load()` reads, kept together for the offline cache (ADR 0007). */
@Serializable
data class ScheduleData(
    val users: List<Account> = emptyList(),
    val types: List<ShiftType> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    val patterns: List<Pattern> = emptyList(),
    val overrides: List<Override> = emptyList(),
    val extras: List<Extra> = emptyList(),
    val warnings: List<OverlapWarning> = emptyList(),
    val preferences: MyPreferences = MyPreferences(),
    val household: HouseholdChoices = HouseholdChoices(),
    val people: List<Person> = emptyList(),
)
