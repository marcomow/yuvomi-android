package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiProblem

/**
 * The sentence for a failed housekeeping action, as upstream's `errorSentence()` and
 * `loadErrorSentence()` (`public/utils/friendly-error.js`, `REFUSAL_MESSAGES`, `CASE_MESSAGES`)
 * for the reasons `server/routes/housekeeping.js` sends. The server's English text is never
 * shown (ARCHITECTURE.md, rule 4).
 */
object HousekeepingErrors {
    const val WORKER_GONE = "housekeeping_worker_not_found"
    const val VISIT_GONE = "housekeeping_visit_not_found"
    const val TASK_GONE = "housekeeping_task_not_found"
    const val NO_OPEN_SESSION = "housekeeping_open_session_not_found"
    const val ALREADY_CHECKED_IN = "housekeeping_already_checked_in"
    const val REOPEN_EXPIRED = "housekeeping_reopen_expired"
    const val FALLBACK = "common.errorGeneric"

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "amounts_hidden" to "housekeeping.errorAmountsHidden",
        "not_owner" to "common.errorNotOwner",
    )

    private val CASES = mapOf(
        WORKER_GONE to "housekeeping.errorWorkerGone",
        VISIT_GONE to "housekeeping.errorVisitGone",
        TASK_GONE to "housekeeping.errorTaskGone",
        NO_OPEN_SESSION to "housekeeping.errorAlreadyCheckedOut",
        ALREADY_CHECKED_IN to "housekeeping.errorAlreadyCheckedIn",
        // `join.usernameTaken` and `setup.errorUsernameInvalid` upstream; those namespaces are
        // not shipped, so the app carries the same sentences.
        "housekeeping_username_taken" to "android.housekeeping.usernameTaken",
        "housekeeping_username_invalid" to "android.housekeeping.usernameInvalid",
    )

    /** `GONE_REASONS` of this module: the page reloads after them. */
    private val GONE = setOf(WORKER_GONE, VISIT_GONE, TASK_GONE, NO_OPEN_SESSION)

    /** The i18n key for [e]; [fallback] is the general sentence of the place. */
    fun key(e: Throwable, fallback: String = FALLBACK): String =
        FriendlyError.finish(e, keyBase(e, fallback), fallback)

    private fun keyBase(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `isGone()`: never on a 403. */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    /** `reportWriteError()` reloads after a gone target and after "already checked in". */
    fun reloads(e: Throwable): Boolean = isGone(e) || (e as? ApiProblem.Http)?.reason == ALREADY_CHECKED_IN

    /** `isAlreadyDeleted()`: deleting what someone else deleted is done, not an error. */
    fun alreadyDeleted(e: Throwable, reason: String): Boolean = isGone(e) && (e as ApiProblem.Http).reason == reason
}
