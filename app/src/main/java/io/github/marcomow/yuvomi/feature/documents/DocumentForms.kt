package io.github.marcomow.yuvomi.feature.documents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A file chosen for upload, already read (`readFileAsDataUrl`). */
data class PickedFile(val fileName: String, val mime: String, val bytes: ByteArray) {
    val size: Long get() = bytes.size.toLong()

    /** `data:<type>;base64,<content>`, the form `parseDataUrl` reads. */
    fun dataUrl(): String = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)

    override fun equals(other: Any?) = other is PickedFile && other.fileName == fileName && other.size == size
    override fun hashCode() = fileName.hashCode() * 31 + size.hashCode()
}

/**
 * The fields of the document dialog (`openDocumentModal`), shared by upload and edit.
 * [reminderDays] is the text of the number field.
 */
data class DocumentForm(
    val name: String = "",
    val description: String = "",
    val category: String = DocumentRules.OTHER,
    val folderId: Int? = null,
    val visibility: String = DocumentRules.VISIBILITY_FAMILY,
    val status: String = DocumentRules.STATUS_ACTIVE,
    val expiresAt: String? = null,
    val reminderDays: String = "",
    val allowedMemberIds: Set<Int> = emptySet(),
    /** "About" (DOCS-R35): one household member or no one. */
    val subjectUserId: Int? = null,
) {
    companion object {
        /** A new upload: the open folder is preselected (`presetFolderId`), never "all" or "no folder". */
        fun new(selectedFolder: Int?) = DocumentForm(folderId = selectedFolder)

        fun of(doc: Document) = DocumentForm(
            name = doc.name,
            description = doc.description.orEmpty(),
            category = doc.category.takeIf { it in DocumentRules.CATEGORIES } ?: DocumentRules.OTHER,
            folderId = doc.folderId,
            visibility = doc.visibility.takeIf { it in DocumentRules.VISIBILITIES } ?: DocumentRules.VISIBILITY_FAMILY,
            status = doc.status,
            expiresAt = doc.expiresAt?.take(10),
            reminderDays = doc.expiryReminderDays?.toString().orEmpty(),
            allowedMemberIds = doc.allowedMemberIds.toSet(),
            subjectUserId = doc.subjectUserId,
        )
    }
}

/** Request bodies and the checks the page makes before sending them (docs/spec/documents.md). */
object DocumentBodies {

    /** The number field's constraint (`min="0" max="365" step="1"`): an i18n key, or null when fine. */
    fun reminderProblem(form: DocumentForm): String? {
        if (form.expiresAt == null) return null
        val text = form.reminderDays.trim()
        if (text.isEmpty()) return null
        val value = text.toIntOrNull()
        return if (value == null || value < DocumentRules.REMINDER_MIN_DAYS || value > DocumentRules.REMINDER_MAX_DAYS) "android.documents.reminderRange" else null
    }

    /**
     * `saveDocument`'s payload: trimmed name, description or null, the reminder only with a
     * date, and the chosen members only for "Selected members".
     */
    fun payload(form: DocumentForm): JsonObject = buildJsonObject {
        put("name", form.name.trim())
        val description = form.description.trim()
        if (description.isEmpty()) put("description", JsonNull) else put("description", description)
        put("category", form.category)
        if (form.folderId == null) put("folder_id", JsonNull) else put("folder_id", form.folderId)
        put("visibility", form.visibility)
        put("status", form.status)
        if (form.expiresAt == null) put("expires_at", JsonNull) else put("expires_at", form.expiresAt)
        val days = form.reminderDays.trim().toIntOrNull()
        if (form.expiresAt != null && days != null) put("expiry_reminder_days", days) else put("expiry_reminder_days", JsonNull)
        // DOCS-R35: always sent, null for no one (`getSelectedUserIds(form, SUBJECT_INPUT)[0] ?? null`).
        if (form.subjectUserId == null) put("subject_user_id", JsonNull) else put("subject_user_id", form.subjectUserId)
        put(
            "allowed_member_ids",
            JsonArray(if (form.visibility == DocumentRules.VISIBILITY_RESTRICTED) form.allowedMemberIds.sorted().map { JsonPrimitive(it) } else emptyList()),
        )
    }

    /**
     * One file of an upload: the form's name only when exactly one file goes up and the name
     * is set, otherwise each file's own name without extension.
     */
    fun upload(form: DocumentForm, file: PickedFile, fileCount: Int): JsonObject {
        val base = payload(form)
        val name = form.name.trim().takeIf { fileCount == 1 && it.isNotEmpty() } ?: DocumentRules.nameFromFile(file.fileName)
        return JsonObject(base + mapOf("name" to JsonPrimitive(name), "original_name" to JsonPrimitive(file.fileName), "content_data" to JsonPrimitive(file.dataUrl())))
    }

    /** `moveSelected`: the document's own fields with the new folder. */
    fun move(doc: Document, folderId: Int?): JsonObject = buildJsonObject {
        put("name", doc.name)
        if (doc.description.isNullOrEmpty()) put("description", JsonNull) else put("description", doc.description)
        put("category", doc.category)
        if (folderId == null) put("folder_id", JsonNull) else put("folder_id", folderId)
        put("visibility", doc.visibility)
        put("status", doc.status)
        put("allowed_member_ids", JsonArray(doc.allowedMemberIds.map { JsonPrimitive(it) }))
    }

    fun archive(archived: Boolean): JsonObject = buildJsonObject { put("archived", archived) }

    fun folder(name: String, parentId: Int?): JsonObject = buildJsonObject {
        put("name", name.trim())
        if (parentId == null) put("parent_id", JsonNull) else put("parent_id", parentId)
    }

    fun rename(name: String): JsonObject = buildJsonObject { put("name", name.trim()) }

    fun moveFolder(parentId: Int?): JsonObject = buildJsonObject { if (parentId == null) put("parent_id", JsonNull) else put("parent_id", parentId) }

    /** `commitFolderDeletion`: the confirmed counts, and for `delete` the snapshot of the preview. */
    fun folderDeleteQuery(impact: FolderDeleteImpact, choice: FolderDeleteChoice): List<Pair<String, String>> = buildList {
        add("documents" to choice.wire)
        add("expected_documents" to impact.documents.toString())
        add("expected_folders" to impact.removedFolders.toString())
        if (choice == FolderDeleteChoice.DELETE) add("expected_snapshot" to impact.snapshot)
    }

    fun dmsLink(accountId: Int, item: DmsItem): JsonObject = buildJsonObject {
        put("account_id", accountId)
        put("dms_document_id", item.key)
        put("category", DocumentRules.OTHER)
        put("visibility", DocumentRules.VISIBILITY_FAMILY)
    }

    fun dmsPush(accountId: Int, documentId: Int): JsonObject = buildJsonObject {
        put("account_id", accountId)
        put("document_id", documentId)
    }
}

enum class FolderDeleteChoice(val wire: String) { UNFILE("unfile"), DELETE("delete") }

/** What a folder deletion says when it is done (`applyFolderDeleteResult`). */
object FolderDeleteOutcome {
    /** Whether the folder-delete dialog asks "keep or delete the documents" (`deleteFolder`). */
    fun asksChoice(impact: FolderDeleteImpact): Boolean = impact.documents > 0 || !impact.canDeleteDocuments

    /** The toast for [result]; null when nothing is said (the undo snackbar already said it). */
    fun message(result: FolderDeleteResult, choice: FolderDeleteChoice, showSuccess: Boolean = true): Message? {
        val nonConcurrency = result.failedDocuments.any { it.failureStage != "concurrency" }
        return when {
            !result.folderDeleted && result.contentsChanged && nonConcurrency -> Message("documents.folderDeleteContentsChangedWithFailuresToast")
            !result.folderDeleted && result.contentsChanged -> Message("documents.folderDeleteContentsChangedToast", mapOf("deleted" to result.deletedDocuments))
            !result.folderDeleted -> Message("documents.folderDeletePartialToast", mapOf("deleted" to result.deletedDocuments, "failed" to result.failedDocuments.size))
            showSuccess && choice == FolderDeleteChoice.DELETE -> Message("documents.folderDeletedWithDocumentsToast", mapOf("count" to result.deletedDocuments))
            showSuccess -> Message("documents.folderDeletedToast")
            else -> null
        }
    }

    /** `folderDeleteChoice`: the modules a deletion with documents touches, with their counts. */
    val LINKED_LABELS = listOf(
        "calendar" to "nav.calendar",
        "housekeeping" to "nav.housekeeping",
        "split_expenses" to "splitExpenses.title",
        "tasks" to "nav.tasks",
        "budget" to "nav.budget",
        "inventory" to "nav.inventory",
    )

    fun linkedRecords(impact: FolderDeleteImpact): List<Pair<String, Int>> =
        LINKED_LABELS.mapNotNull { (key, label) -> impact.linkedRecords[key]?.takeIf { it > 0 }?.let { label to it } }
}

/** Which empty state the list shows (`emptyStateFor`). */
sealed interface EmptyKind {
    /** The search found nothing; [otherStatusHits] offers the other view, [resetFilters] the facets. */
    data class Search(val otherStatusHits: Int, val resetFilters: Boolean) : EmptyKind

    /** A folder alone is a place: it is empty, and the way is uploading here. */
    data class EmptyFolder(val name: String?) : EmptyKind
    data object NoMatch : EmptyKind
    data object EmptyArchive : EmptyKind
    data object Nothing : EmptyKind

    companion object {
        fun of(filter: DocumentRules.Filter, folders: List<DocumentFolder>, otherStatusHits: Int): EmptyKind {
            if (filter.normalizedQuery.isNotEmpty()) return Search(otherStatusHits, filter.hasFacet)
            val folder = filter.selectedFolder
            if (folder != null && filter.category.isEmpty() && !filter.expiringSoon && filter.personId == null) return EmptyFolder(folders.firstOrNull { it.id == folder }?.name)
            if (filter.hasFacet) return NoMatch
            if (filter.status == DocumentRules.STATUS_ARCHIVED) return EmptyArchive
            return Nothing
        }
    }
}
