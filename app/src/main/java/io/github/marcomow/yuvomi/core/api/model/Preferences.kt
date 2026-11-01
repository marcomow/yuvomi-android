package io.github.marcomow.yuvomi.core.api.model

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The fields of `GET /preferences` the app reads (SIGNIN-R13, OVERVIEW-R7, TASKS-R13). */
@Serializable
data class Preferences(
    @SerialName("timezone_effective") val timezoneEffective: String? = null,
    @SerialName("disabled_modules") val disabledModules: List<String> = emptyList(),
    @SerialName("dashboard_widgets") val dashboardWidgets: JsonElement? = null,
    @SerialName("dashboard_today_glance") val dashboardTodayGlance: FlexBool = true,
    @SerialName("tasks_default_visibility") val tasksDefaultVisibility: String = "all",
    @SerialName("tasks_default_locked") val tasksDefaultLocked: FlexBool = false,
)
