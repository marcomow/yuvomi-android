package io.github.marcomow.yuvomi.feature.schedule

import io.github.marcomow.yuvomi.core.api.ApiProblem

/**
 * `scheduleErrorMessage` = `errorSentence` of `public/utils/friendly-error.js`: a known `reason`
 * has its own sentence (`REFUSAL_MESSAGES`, `CASE_MESSAGES`), everything else the general one,
 * `common.errorGeneric`. The server's English text is never shown (ARCHITECTURE.md, rule 4).
 */
object ScheduleErrors {
    const val SHIFT_TYPE_GONE = "schedule_shift_type_not_found"
    const val CUSTOM_FIELD_GONE = "schedule_custom_field_not_found"
    const val PATTERN_GONE = "schedule_pattern_not_found"
    const val OVERRIDE_GONE = "schedule_override_not_found"
    const val EXTRA_GONE = "schedule_extra_shift_not_found"
    const val FALLBACK = "common.errorGeneric"

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
    )

    private val CASES = mapOf(
        SHIFT_TYPE_GONE to "schedule.errorShiftTypeGone",
        CUSTOM_FIELD_GONE to "schedule.errorCustomFieldGone",
        PATTERN_GONE to "schedule.errorPatternGone",
        OVERRIDE_GONE to "schedule.errorEntryGone",
        EXTRA_GONE to "schedule.errorEntryGone",
        "schedule_selected_shift_type_not_found" to "schedule.errorSelectedShiftTypeGone",
        "schedule_selected_custom_field_not_found" to "schedule.errorSelectedCustomFieldGone",
        "schedule_shift_type_in_use" to "schedule.typeInUse",
        "schedule_cycle_length_conflict" to "schedule.cycleLengthConflictGeneric",
        "schedule_shift_type_required" to "schedule.shiftTypeRequiredError",
        "range_reversed" to "schedule.rangeOrderError",
    )

    /** `GONE_REASONS` of this page: the page reloads after them (`reportScheduleError`). */
    private val GONE = setOf(
        SHIFT_TYPE_GONE, CUSTOM_FIELD_GONE, PATTERN_GONE, OVERRIDE_GONE, EXTRA_GONE,
        "schedule_selected_shift_type_not_found", "schedule_selected_custom_field_not_found",
    )

    /** The i18n key of the sentence for [e]. */
    fun key(e: Throwable): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> FALLBACK
        }
        else -> FALLBACK
    }

    /** `isGone`: never on a 403. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    /** `isAlreadyDeleted`: deleting what someone else deleted first is no failure. */
    fun isAlreadyDeleted(e: Throwable, reason: String): Boolean = isGone(e) && (e as ApiProblem.Http).reason == reason
}
