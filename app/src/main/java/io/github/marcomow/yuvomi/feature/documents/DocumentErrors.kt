package io.github.marcomow.yuvomi.feature.documents

import io.github.marcomow.yuvomi.core.api.ApiProblem

/** A sentence to show: an i18n key and its arguments. */
data class Message(val key: String, val args: Map<String, Any?> = emptyMap())

/**
 * The sentence for a failed documents action, as upstream's `errorText` in
 * `public/pages/documents.js`: a known `reason` has its own sentence (`errorSentence` with
 * `REFUSAL_MESSAGES`, `CASE_MESSAGES`, `CASE_PARAMS` of `public/utils/friendly-error.js`),
 * everything else the page's general one, `common.unknownError`. The server's English text is
 * never shown (ARCHITECTURE.md, rule 4).
 */
object DocumentErrors {
    const val DOCUMENT_GONE = "documents_document_not_found"
    const val FOLDER_GONE = "documents_folder_not_found"
    const val SELECTED_FOLDER_GONE = "documents_selected_folder_not_found"
    const val FOLDER_CONTENT_CHANGED = "FOLDER_CONTENT_CHANGED"
    const val FOLDER_DELETE_IN_PROGRESS = "FOLDER_DELETE_IN_PROGRESS"
    const val FOLDER_DOCUMENTS_NOT_MANAGEABLE = "FOLDER_DOCUMENTS_NOT_MANAGEABLE"
    const val FALLBACK = "common.unknownError"

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    private val CASES = mapOf(
        FOLDER_GONE to "documents.errorFolderGone",
        SELECTED_FOLDER_GONE to "documents.errorSelectedFolderGone",
        "documents_folder_exists" to "documents.errorFolderExists",
        "documents_folder_too_deep" to "documents.errorFolderTooDeep",
        FOLDER_DELETE_IN_PROGRESS to "documents.folderDeleteInProgressToast",
        DOCUMENT_GONE to "documents.errorDocumentGone",
        "documents_file_type_not_allowed" to "documents.errorFileTypeNotAllowed",
        "documents_file_content_mismatch" to "documents.errorFileContentMismatch",
        "documents_file_empty" to "documents.errorFileEmpty",
        "documents_storage_failed" to "documents.errorStorageFailed",
        "dms_account_not_found" to "documents.errorDmsAccountGone",
        "dms_document_not_found" to "documents.errorDmsDocumentGone",
        "dms_document_already_linked" to "documents.errorDmsAlreadyLinked",
        "dms_document_already_stored" to "documents.errorDmsAlreadyStored",
        "dms_unreachable" to "documents.dmsSearchError",
    )

    /** `GONE_REASONS` of this page: the target of the action no longer exists, or is no longer visible. */
    private val GONE = setOf(FOLDER_GONE, SELECTED_FOLDER_GONE, DOCUMENT_GONE, "dms_account_not_found", "dms_document_not_found")

    /** The sentence for [e]; [fallback] is the general sentence of the place. */
    fun message(e: Throwable, fallback: String = FALLBACK): Message = when (e) {
        is ApiProblem.Network -> Message("common.errorOfflineMutation")
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> Message("common.errorRestoreInProgress")
            e.status == 403 -> Message(REFUSALS[e.reason] ?: "common.errorNoPermission")
            e.status == 429 -> Message(CASES[e.reason] ?: "common.errorRateLimited")
            // CASE_PARAMS: the server's `params.limit`, else the shared constant.
            e.reason == "documents_folder_too_deep" -> Message(CASES.getValue(e.reason), mapOf("limit" to (e.params["limit"] ?: DocumentRules.MAX_FOLDER_DEPTH)))
            e.reason != null && e.reason in CASES -> Message(CASES.getValue(e.reason))
            e.operationUnsupported -> Message("android.error.unsupported")
            else -> Message(fallback)
        }
        else -> Message(fallback)
    }

    /** `isGone`: never on a 403. A document's 404 also means "no longer visible to you". */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    /** `isSelectedFolderGone`: the folder chosen in a form went; the form stays open. */
    fun isSelectedFolderGone(e: Throwable): Boolean = isGone(e) && (e as ApiProblem.Http).reason == SELECTED_FOLDER_GONE

    fun isReason(e: Throwable, status: Int, reason: String): Boolean = e is ApiProblem.Http && e.status == status && e.reason == reason
}
