package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.core.api.ApiProblem

/**
 * The sentence for a failed note action, as upstream's `errorSentence`
 * (`public/utils/friendly-error.js`): a known reason has its own sentence, everything
 * else the general one. The server's English text is never shown (ARCHITECTURE.md, rule 4).
 */
object NoteErrors {
    const val NOTE_GONE = "notes_note_not_found"
    const val SELECTED_CATEGORY_GONE = "notes_selected_category_not_found"

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "android.notes.moduleReadOnly",
        "note_locked" to "notes.errorLocked",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    private val CASES = mapOf(
        NOTE_GONE to "notes.errorNoteGone",
        SELECTED_CATEGORY_GONE to "notes.errorSelectedCategoryGone",
        "category_exists" to "category.errorExists",
        "category_last" to "category.errorLast",
    )

    /** An i18n key for [e]. [fallback] is the general sentence of the place, as `errorSentence(err, fallbackKey)`. */
    fun key(e: Throwable, fallback: String = "common.errorGeneric"): String = when (e) {
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

    /** `isGone`: the thing the action aimed at no longer exists (never on a 403). */
    fun isGone(e: Throwable, reason: String = NOTE_GONE): Boolean =
        e is ApiProblem.Http && e.status != 403 && e.reason == reason

    /** A `409` from `/check`: the line moved or changed since it was shown. */
    fun isCheckConflict(e: Throwable): Boolean = e is ApiProblem.Http && e.status == 409
}
