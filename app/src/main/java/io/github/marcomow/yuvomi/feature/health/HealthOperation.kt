package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Health calls (docs/spec/health.md). Must match
 * `upstream/operations-used.d/health.txt` exactly (ContractFilesTest). The household's members
 * come from `MealsOperation.FAMILY_MEMBERS`, the cycle opt-in from the core `GET /preferences`.
 */
enum class HealthOperation(override val method: String, override val template: String) : ApiOperation {
    // Shared by every area: who the member may record for, and their default visibility.
    CAREGIVERS_ME("GET", "/api/v1/health/caregivers/me"),
    VISIBILITY_DEFAULTS("GET", "/api/v1/health/visibility-defaults"),

    // Vitals.
    VITALS_LIST("GET", "/api/v1/health/vitals"),
    VITAL_CREATE("POST", "/api/v1/health/vitals"),
    VITAL_UPDATE("PATCH", "/api/v1/health/vitals/{id}"),
    VITAL_DELETE("DELETE", "/api/v1/health/vitals/{id}"),

    // Medications, schedules and the dose log.
    MEDS_LIST("GET", "/api/v1/health/medications"),
    MED_CREATE("POST", "/api/v1/health/medications"),
    MED_UPDATE("PATCH", "/api/v1/health/medications/{id}"),
    MED_DELETE("DELETE", "/api/v1/health/medications/{id}"),
    SCHEDULE_CREATE("POST", "/api/v1/health/medications/{id}/schedules"),
    SCHEDULE_DELETE("DELETE", "/api/v1/health/schedules/{id}"),
    DOSE_LOG("POST", "/api/v1/health/medications/{id}/logs"),
    LOG_TAKE("POST", "/api/v1/health/logs/{id}/take"),
    LOG_SKIP("POST", "/api/v1/health/logs/{id}/skip"),
    LOG_UNDO("POST", "/api/v1/health/logs/{id}/undo"),
    LOG_UPDATE("PATCH", "/api/v1/health/logs/{id}"),
    LOG_DELETE("DELETE", "/api/v1/health/logs/{id}"),

    // Fasting.
    FASTING_STATE("GET", "/api/v1/health/fasting/state"),
    FASTING_HISTORY("GET", "/api/v1/health/fasting/history"),
    FASTING_STATS("GET", "/api/v1/health/fasting/stats"),
    FASTING_CREATE("POST", "/api/v1/health/fasting"),
    FASTING_FINISH("POST", "/api/v1/health/fasting/{id}/finish"),
    FASTING_UPDATE("PATCH", "/api/v1/health/fasting/{id}"),
    FASTING_DELETE("DELETE", "/api/v1/health/fasting/{id}"),
    FASTING_SETTINGS("PUT", "/api/v1/health/fasting/settings"),

    // Cycle (own data only; caregiving does not apply here).
    PERIODS_LIST("GET", "/api/v1/health/cycle/periods"),
    PERIOD_CREATE("POST", "/api/v1/health/cycle/periods"),
    PERIOD_UPDATE("PATCH", "/api/v1/health/cycle/periods/{id}"),
    PERIOD_DELETE("DELETE", "/api/v1/health/cycle/periods/{id}"),
    CYCLE_LOGS_LIST("GET", "/api/v1/health/cycle/logs"),
    CYCLE_LOG_SAVE("POST", "/api/v1/health/cycle/logs"),
    CYCLE_LOG_DELETE("DELETE", "/api/v1/health/cycle/logs/{id}"),
    CYCLE_SETTINGS("GET", "/api/v1/health/cycle/settings"),
    CYCLE_SETTINGS_SAVE("PUT", "/api/v1/health/cycle/settings"),
    CYCLE_VISIBILITY("PATCH", "/api/v1/health/cycle/visibility"),

    // Preventive care and vaccinations.
    PREVENTION_TYPES("GET", "/api/v1/health/prevention/types"),
    PREVENTION_RECORDS("GET", "/api/v1/health/prevention/records"),
    PREVENTION_RECORD_CREATE("POST", "/api/v1/health/prevention/records"),
    PREVENTION_RECORD_UPDATE("PATCH", "/api/v1/health/prevention/records/{id}"),
    PREVENTION_RECORD_DELETE("DELETE", "/api/v1/health/prevention/records/{id}"),
    PREVENTION_DUE("GET", "/api/v1/health/prevention/due"),

    // Lab reports and their analytes.
    LABS_LIST("GET", "/api/v1/health/labs"),
    LAB_CREATE("POST", "/api/v1/health/labs"),
    LAB_UPDATE("PATCH", "/api/v1/health/labs/{id}"),
    LAB_DELETE("DELETE", "/api/v1/health/labs/{id}"),
    RESULT_CREATE("POST", "/api/v1/health/labs/{id}/results"),
    RESULT_UPDATE("PATCH", "/api/v1/health/results/{id}"),
    RESULT_DELETE("DELETE", "/api/v1/health/results/{id}"),

    // Activity.
    ACTIVITIES_LIST("GET", "/api/v1/health/activities"),
    ACTIVITY_CREATE("POST", "/api/v1/health/activities"),
    ACTIVITY_UPDATE("PATCH", "/api/v1/health/activities/{id}"),
    ACTIVITY_DELETE("DELETE", "/api/v1/health/activities/{id}"),

    // Nutrition.
    NUTRITION_ENTRIES("GET", "/api/v1/health/nutrition/entries"),
    NUTRITION_ENTRY_CREATE("POST", "/api/v1/health/nutrition/entries"),
    NUTRITION_ENTRY_UPDATE("PATCH", "/api/v1/health/nutrition/entries/{id}"),
    NUTRITION_ENTRY_DELETE("DELETE", "/api/v1/health/nutrition/entries/{id}"),
    NUTRITION_SUMMARY("GET", "/api/v1/health/nutrition/summary"),
    NUTRITION_TARGETS_SAVE("PUT", "/api/v1/health/nutrition/targets"),
}
