package io.github.marcomow.yuvomi.feature.documents

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One row of `GET /documents` (`documentSelect` + `normalizeDocument` in
 * server/routes/documents.js). `content_data` is never part of the list.
 */
@Serializable
data class Document(
    val id: Int,
    val name: String = "",
    val description: String? = null,
    val category: String = DocumentRules.OTHER,
    val status: String = DocumentRules.STATUS_ACTIVE,
    val visibility: String = DocumentRules.VISIBILITY_FAMILY,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("storage_provider") val storageProvider: String? = null,
    @SerialName("storage_backend") val storageBackend: String? = null,
    @SerialName("storage_key") val storageKey: String? = null,
    @SerialName("dms_account_id") val dmsAccountId: Int? = null,
    @SerialName("dms_provider") val dmsProvider: String? = null,
    @SerialName("external_url") val externalUrl: String? = null,
    @SerialName("folder_id") val folderId: Int? = null,
    @SerialName("folder_name") val folderName: String? = null,
    @SerialName("created_by") val createdBy: Int? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expiry_reminder_days") val expiryReminderDays: Int? = null,
    @SerialName("allowed_member_ids") val allowedMemberIds: List<Int> = emptyList(),
    /** Who the document is about (2.84.0, #1705); says nothing about who may see it (DOCS-R35). */
    @SerialName("subject_user_id") val subjectUserId: Int? = null,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("subject_color") val subjectColor: String? = null,
) {
    val archived: Boolean get() = status == DocumentRules.STATUS_ARCHIVED
}

/** `GET /documents/folders`: flat, with `parent_id`, siblings already in name order. */
@Serializable
data class DocumentFolder(
    val id: Int,
    val name: String = "",
    @SerialName("parent_id") val parentId: Int? = null,
    @SerialName("module_key") val moduleKey: String? = null,
    @SerialName("created_by") val createdBy: Int? = null,
)

@Serializable
data class DmsAccount(val id: Int, val name: String = "", val provider: String? = null)

/** `GET /documents/meta/options`. */
@Serializable
data class DocumentMeta(
    @SerialName("max_file_size") val maxFileSize: Long? = null,
    @SerialName("allowed_mime_types") val allowedMimeTypes: List<String> = emptyList(),
    @SerialName("active_upload_backend") val activeUploadBackend: String? = null,
    @SerialName("is_admin") val isAdmin: FlexBool = false,
    @SerialName("dms_accounts") val dmsAccounts: List<DmsAccount> = emptyList(),
)

/** `GET /family/members` and `GET /auth/users`: the fields the member picker reads. */
@Serializable
data class DocumentPerson(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
)

/** `GET /documents/folders/{id}/delete-impact`. */
@Serializable
data class FolderDeleteImpact(
    val id: Int = 0,
    @SerialName("removed_folders") val removedFolders: Int = 1,
    val documents: Int = 0,
    @SerialName("can_delete_documents") val canDeleteDocuments: FlexBool = true,
    /** Per module a count, or null where the member cannot read that module. */
    @SerialName("linked_records") val linkedRecords: Map<String, Int?> = emptyMap(),
    val snapshot: String = "",
)

@Serializable
data class FailedDocument(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("failure_stage") val failureStage: String? = null,
)

/** `DELETE /documents/folders/{id}`: 200 with `folder_deleted: true`, or 207 with `false`. */
@Serializable
data class FolderDeleteResult(
    val id: Int = 0,
    @SerialName("removed_folders") val removedFolders: Int = 0,
    @SerialName("unfiled_documents") val unfiledDocuments: Int = 0,
    @SerialName("deleted_documents") val deletedDocuments: Int = 0,
    @SerialName("failed_documents") val failedDocuments: List<FailedDocument> = emptyList(),
    @SerialName("contents_changed") val contentsChanged: FlexBool = false,
    @SerialName("folder_deleted") val folderDeleted: FlexBool = true,
)

/** One hit of `GET /documents/dms/search` (the adapter's `#toItem`). */
@Serializable
data class DmsItem(
    val id: JsonElement,
    val title: String = "",
    val filename: String? = null,
    val url: String? = null,
) {
    /** The DMS's own id, always sent back as a string (`String(req.body.dms_document_id)`). */
    val key: String get() = (id as? kotlinx.serialization.json.JsonPrimitive)?.content ?: id.toString()
}
