package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiProblem

/**
 * The sentence for a failed calendar action, as upstream's `errorSentence`
 * (public/utils/friendly-error.js) and the page's own `calendarSaveErrorMessage`,
 * `occurrenceErrorMessage` and `localCalendarErrorSentence`. The server's English text is
 * never shown (ARCHITECTURE.md, rule 4).
 */
object CalendarErrors {
    const val EVENT_GONE = "calendar_event_not_found"
    const val SERIES_GONE = "calendar_series_not_found"
    const val CALENDAR_GONE = "calendar_calendar_not_found"
    const val SELECTED_CALENDAR_GONE = "calendar_selected_calendar_not_found"
    const val ORDER_CHANGED = "calendar_order_changed"

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    private val CASES = mapOf(
        CALENDAR_GONE to "calendar.errorCalendarGone",
        "calendar_name_invalid" to "calendar.errorCalendarNameInvalid",
        ORDER_CHANGED to "calendar.errorCalendarOrderChanged",
        EVENT_GONE to "calendar.errorEventGone",
        SELECTED_CALENDAR_GONE to "calendar.errorSelectedCalendarGone",
        "calendar_series_no_occurrence" to "calendar.errorSeriesNoOccurrence",
        "calendar_series_ends_before_start" to "calendar.recurrenceEndBeforeStart",
        "calendar_assignee_not_member" to "settings.syncAssigneeNotMember",
        "calendar_assignee_unknown" to "settings.syncAssigneeUnknown",
    )

    /** `occurrenceErrorMessage()`: the reasons a series occurrence is refused with. */
    private fun occurrenceKey(e: ApiProblem.Http): String? = when {
        e.reason == "invalid_recurrence_id" -> "calendar.occurrenceGoneError"
        e.reason == "malformed_recurrence_id" -> "calendar.occurrenceMalformedError"
        e.status == 404 && e.reason == SERIES_GONE -> "calendar.errorEventGone"
        e.reason == "empty_successor_series" -> "calendar.errorSeriesNoOccurrence"
        else -> null
    }

    /** An i18n key for [e]; [fallback] is the place's general sentence. */
    fun key(e: Throwable, fallback: String = "common.errorGeneric"): String =
        FriendlyError.finish(e, keyBase(e, fallback), fallback)

    private fun keyBase(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.reason == "ATTACHMENT_CHANGE_REFUSED" -> "calendar.attachmentChangeRefused"
            e.reason == "ATTACHMENT_UPLOAD_REFUSED" -> "calendar.attachmentUploadRefused"
            e.status == 403 && e.reason == "calendar_feed_admin_only" -> "calendar.errorDeleteFeedAdminOnly"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            occurrenceKey(e) != null -> occurrenceKey(e)!!
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `eventGone()`: the event or its series no longer exists for this member. */
    fun eventGone(e: Throwable): Boolean =
        e is ApiProblem.Http && e.status != 403 && (e.reason == EVENT_GONE || (e.status == 404 && e.reason == SERIES_GONE))

    /** `isGone()` for one reason. */
    fun isGone(e: Throwable, reason: String): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason == reason

    /** `localCalendarsStale()`: the manager reloads itself. */
    fun calendarsStale(e: Throwable): Boolean =
        isGone(e, CALENDAR_GONE) || (e is ApiProblem.Http && e.status == 400 && e.reason == ORDER_CHANGED)

    /**
     * A 409 `calendar_override_orphans`: the series change would orphan edited occurrences.
     * The web page asks with the count from the answer and retries; [ApiProblem.Http] does not
     * carry `orphaned_override_count`, so the app says what happened instead (spec, CAL-R19).
     */
    fun orphanConflict(e: Throwable): Boolean = e is ApiProblem.Http && e.status == 409 && e.reason == null

    /** The load error of the page (`loadErrorSentence`). */
    fun loadKey(e: ApiProblem): String = when {
        e is ApiProblem.Network -> "android.error.offline"
        e is ApiProblem.Http && e.operationUnsupported -> "android.error.unsupported"
        e is ApiProblem.Http && e.status == 403 -> key(e)
        else -> "calendar.loadError"
    }
}
