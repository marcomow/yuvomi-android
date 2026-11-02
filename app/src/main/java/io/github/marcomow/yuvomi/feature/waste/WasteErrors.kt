package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A sentence to show: an i18n key and its arguments. */
data class WasteMessage(val key: String, val args: Map<String, Any?> = emptyMap())

/**
 * The sentence for a failed waste action, as upstream's `errorSentence()` with the waste
 * entries of `REFUSAL_MESSAGES`, `CASE_MESSAGES`, `CASE_PARAMS` and `GONE_REASONS`
 * (`public/utils/friendly-error.js`). The server's English text is never shown.
 */
object WasteErrors {
    const val SELECTED_TYPE_GONE = "waste_selected_type_not_found"
    const val TYPE_GONE = "waste_type_not_found"
    const val SCHEDULE_GONE = "waste_schedule_not_found"
    const val PICKUP_GONE = "waste_pickup_not_found"
    const val SOURCE_GONE = "waste_source_not_found"
    const val OVERRIDE_GONE = "waste_override_not_found"
    const val WEEKDAYS_REQUIRED = "waste_schedule_weekdays_required"

    /** `MAX_ICS_MB`, `MAX_VEVENTS`, `MAX_CANDIDATE_INSTANCES`, `MAX_DISTINCT_LABELS` (server/services/waste-import.js). */
    const val MAX_ICS_MB = 5
    private const val MAX_EVENTS = 5000
    private const val MAX_LABELS = 200

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    private val CASES = mapOf(
        TYPE_GONE to "waste.errorTypeGone",
        SELECTED_TYPE_GONE to "waste.errorSelectedTypeGone",
        SCHEDULE_GONE to "waste.errorScheduleGone",
        PICKUP_GONE to "waste.errorPickupGone",
        SOURCE_GONE to "waste.errorSourceGone",
        "waste_mapping_not_found" to "waste.errorMappingGone",
        OVERRIDE_GONE to "waste.errorOverrideGone",
        "waste_occurrence_not_found" to "waste.errorOccurrenceGone",
        "waste_type_in_use" to "waste.typeDeleteRefused",
        "waste_pickup_exists" to "waste.pickupDuplicateError",
        "waste_schedule_anchor_date_invalid" to "waste.errorScheduleAnchorRequired",
        "waste_schedule_ends_before_anchor" to "waste.errorScheduleEndsBeforeStart",
        WEEKDAYS_REQUIRED to "waste.errorScheduleWeekdaysRequired",
        "waste_schedule_interval_invalid" to "waste.errorScheduleInterval",
        "waste_schedule_month_day_invalid" to "waste.errorScheduleMonthDay",
        "waste_schedule_anchor_not_month_day" to "waste.errorScheduleAnchorNotMonthDay",
        "waste_schedule_anchor_not_last_day" to "waste.errorScheduleAnchorNotLastDay",
        "waste_schedule_anchor_not_ordinal_weekday" to "waste.errorScheduleAnchorNotWeekday",
        "waste_pickup_date_invalid" to "waste.errorDateRequired",
        "waste_override_date_invalid" to "waste.errorDateRequired",
        "waste_override_same_date" to "waste.errorOverrideSameDate",
        "waste_reminder_offset_invalid" to "waste.errorReminderOffset",
        "waste_reminder_time_invalid" to "waste.errorReminderTime",
        "waste_import_empty_file" to "waste.errorImportEmpty",
        "waste_import_file_too_large" to "waste.errorImportTooLarge",
        "waste_import_event_cap_exceeded" to "waste.errorImportTooManyEvents",
        "waste_import_instance_cap_exceeded" to "waste.errorImportTooManyPickups",
        "waste_import_label_cap_exceeded" to "waste.errorImportTooManyLabels",
        "waste_import_no_events" to "waste.errorImportNoEvents",
        "preview_changed" to "waste.importPreviewChangedError",
        "source_changed" to "waste.importSourceChangedError",
        "waste_profile_invalid" to "waste.mappingProfileInvalidFile",
        "waste_profile_changed" to "waste.errorProfileChanged",
        "waste_source_url_invalid" to "waste.errorSourceUrlInvalid",
        "waste_source_url_not_https" to "waste.errorSourceUrlNotHttps",
        "waste_source_interval_invalid" to "waste.errorSourceInterval",
        "waste_source_not_url" to "waste.errorSourceNotUrl",
        "refresh_in_progress" to "waste.errorSourceRefreshRunning",
        "waste_source_fetch_http_status" to "waste.errorSourceFetchStatus",
        "waste_source_fetch_too_large" to "waste.errorSourceFetchTooLarge",
        "waste_source_fetch_blocked" to "waste.errorSourceFetchBlocked",
        "waste_source_fetch_unreachable" to "waste.errorSourceFetchUnreachable",
        "waste_source_fetch_no_events" to "waste.errorSourceFetchNoEvents",
    )

    /** `CASE_PARAMS`: the numbers a sentence fills in. */
    private val PARAMS = mapOf(
        "waste_import_file_too_large" to listOf("limit_mb"),
        "waste_import_event_cap_exceeded" to listOf("limit"),
        "waste_import_instance_cap_exceeded" to listOf("limit"),
        "waste_import_label_cap_exceeded" to listOf("limit"),
        "waste_source_interval_invalid" to listOf("min", "max"),
        "waste_source_fetch_http_status" to listOf("status"),
        "waste_source_fetch_too_large" to listOf("limit_mb"),
    )

    /**
     * Stand-ins for the numbers the server names in `params`, for an answer without them: the
     * limits are the server's constants; a fetch status is unknown, so that sentence falls back.
     */
    private val KNOWN_PARAMS: Map<String, Map<String, Any>> = mapOf(
        "waste_import_file_too_large" to mapOf("limit_mb" to MAX_ICS_MB),
        "waste_import_event_cap_exceeded" to mapOf("limit" to MAX_EVENTS),
        "waste_import_instance_cap_exceeded" to mapOf("limit" to MAX_EVENTS),
        "waste_import_label_cap_exceeded" to mapOf("limit" to MAX_LABELS),
        "waste_source_interval_invalid" to mapOf("min" to WasteRules.URL_MIN_INTERVAL, "max" to WasteRules.URL_MAX_INTERVAL),
        "waste_source_fetch_too_large" to mapOf("limit_mb" to MAX_ICS_MB),
    )

    private val GONE = setOf(TYPE_GONE, SELECTED_TYPE_GONE, SCHEDULE_GONE, PICKUP_GONE, SOURCE_GONE, "waste_mapping_not_found", OVERRIDE_GONE, "waste_occurrence_not_found")

    /** The sentence for a reason with its numbers, or null when the case is unknown or a number is missing. */
    fun caseMessage(reason: String?, params: JsonObject?): WasteMessage? =
        caseMessage(reason, io.github.marcomow.yuvomi.core.api.FriendlyError.readParams(params))

    /** [caseMessage] with the numbers already read ([ApiProblem.Http.params]). */
    fun caseMessage(reason: String?, params: Map<String, Long>): WasteMessage? {
        val key = CASES[reason ?: return null] ?: return null
        val names = PARAMS[reason] ?: return WasteMessage(key)
        val args = names.associateWith { name -> params[name]?.toInt() ?: KNOWN_PARAMS[reason]?.get(name) }
        return if (args.values.any { it == null }) null else WasteMessage(key, args)
    }

    /** `errorSentence(err, fallback)`. */
    fun message(e: Throwable, fallback: String = "common.errorGeneric"): WasteMessage = when (e) {
        is ApiProblem.Network -> WasteMessage("common.errorOfflineMutation")
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> WasteMessage("common.errorRestoreInProgress")
            e.status == 403 -> WasteMessage(REFUSALS[e.reason] ?: "common.errorNoPermission")
            e.status == 429 -> caseMessage(e.reason, e.params) ?: WasteMessage("common.errorRateLimited")
            else -> caseMessage(e.reason, e.params) ?: if (e.operationUnsupported) WasteMessage("android.error.unsupported") else WasteMessage(fallback)
        }
        else -> WasteMessage(fallback)
    }

    /** `fetchFailureSentence()`: a URL source's failed fetch, from the answer or the stored row. */
    fun fetchFailure(reason: String?, params: JsonObject?): WasteMessage =
        caseMessage(reason, params) ?: WasteMessage("waste.errorSourceFetch")

    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    fun isSelectedTypeGone(e: Throwable): Boolean = isGone(e) && (e as ApiProblem.Http).reason == SELECTED_TYPE_GONE

    fun alreadyDeleted(e: Throwable, reason: String): Boolean = isGone(e) && (e as ApiProblem.Http).reason == reason

    fun reason(e: Throwable): String? = (e as? ApiProblem.Http)?.reason
}
