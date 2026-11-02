package io.github.marcomow.yuvomi.feature.documents

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.meals.MealsOperation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The calls of docs/spec/documents.md. */
class DocumentsApi(private val client: YuvomiClient) {

    /** Only the status is filtered on the server; category and folder are client facets. */
    suspend fun list(status: String): JsonElement = client.call(DocumentsOperation.DOCUMENTS_LIST, query = listOf("status" to status))

    suspend fun folders(): JsonElement = client.call(DocumentsOperation.FOLDERS_LIST)

    suspend fun meta(): JsonElement = client.call(DocumentsOperation.META_OPTIONS)

    /** `loadMembers`: who can be chosen ... */
    suspend fun members(): JsonElement = client.call(MealsOperation.FAMILY_MEMBERS)

    /** ... and the names of whoever already has access (staff and guests included). */
    suspend fun directory(): JsonElement = client.call(DocumentsOperation.AUTH_USERS)

    suspend fun create(body: JsonObject, idempotencyKey: String): Document =
        client.call(DocumentsOperation.DOCUMENT_CREATE, body = body, idempotencyKey = idempotencyKey).decodeData(Document.serializer())

    suspend fun update(id: Int, body: JsonObject): Document =
        client.call(DocumentsOperation.DOCUMENT_UPDATE, mapOf("id" to id), body = body).decodeData(Document.serializer())

    /** 204 without a body. */
    suspend fun delete(id: Int) {
        client.call(DocumentsOperation.DOCUMENT_DELETE, mapOf("id" to id))
    }

    suspend fun archive(id: Int, archived: Boolean): JsonElement =
        client.call(DocumentsOperation.DOCUMENT_ARCHIVE, mapOf("id" to id), body = DocumentBodies.archive(archived))

    /** Inline bytes of a previewable type (PDF, PNG, JPEG, WebP, text); 415 for anything else. */
    suspend fun preview(id: Int): ByteArray = client.bytes(DocumentsOperation.DOCUMENT_PREVIEW.path(mapOf("id" to id)))

    suspend fun download(id: Int): ByteArray = client.bytes(DocumentsOperation.DOCUMENT_DOWNLOAD.path(mapOf("id" to id)))

    /** The DMS's compact picture of a Paperless-linked document; 415 when it has none. */
    suspend fun thumbnail(id: Int): ByteArray = client.bytes(DocumentsOperation.DOCUMENT_THUMBNAIL.path(mapOf("id" to id)))

    suspend fun createFolder(name: String, parentId: Int?, idempotencyKey: String): DocumentFolder =
        client.call(DocumentsOperation.FOLDER_CREATE, body = DocumentBodies.folder(name, parentId), idempotencyKey = idempotencyKey)
            .decodeData(DocumentFolder.serializer())

    suspend fun updateFolder(id: Int, body: JsonObject): DocumentFolder =
        client.call(DocumentsOperation.FOLDER_UPDATE, mapOf("id" to id), body = body).decodeData(DocumentFolder.serializer())

    suspend fun deleteImpact(id: Int): FolderDeleteImpact =
        client.call(DocumentsOperation.FOLDER_DELETE_IMPACT, mapOf("id" to id)).decodeData(FolderDeleteImpact.serializer())

    suspend fun deleteFolder(id: Int, impact: FolderDeleteImpact, choice: FolderDeleteChoice): FolderDeleteResult =
        client.call(DocumentsOperation.FOLDER_DELETE, mapOf("id" to id), query = DocumentBodies.folderDeleteQuery(impact, choice))
            .decodeData(FolderDeleteResult.serializer())

    /** Admin only; an empty [query] lists the DMS's documents. */
    suspend fun dmsSearch(accountId: Int, query: String): List<DmsItem> =
        client.call(DocumentsOperation.DMS_SEARCH, query = listOf("account_id" to accountId.toString(), "q" to query))
            .decodeData(ListSerializer(DmsItem.serializer()))

    suspend fun dmsLink(accountId: Int, item: DmsItem, idempotencyKey: String): JsonElement =
        client.call(DocumentsOperation.DMS_LINK, body = DocumentBodies.dmsLink(accountId, item), idempotencyKey = idempotencyKey)

    suspend fun dmsPush(accountId: Int, documentId: Int, idempotencyKey: String): JsonElement =
        client.call(DocumentsOperation.DMS_PUSH, body = DocumentBodies.dmsPush(accountId, documentId), idempotencyKey = idempotencyKey)

    companion object {
        fun decodeDocuments(answer: JsonElement): List<Document> = answer.decodeData(ListSerializer(Document.serializer()))
        fun decodeFolders(answer: JsonElement): List<DocumentFolder> = answer.decodeData(ListSerializer(DocumentFolder.serializer()))
        fun decodeMeta(answer: JsonElement): DocumentMeta = answer.decodeData(DocumentMeta.serializer())
        fun decodePeople(answer: JsonElement): List<DocumentPerson> = answer.decodeData(ListSerializer(DocumentPerson.serializer()))
        fun decodeDocuments(json: String): List<Document> = decodeDocuments(ApiJson.parseToJsonElement(json))
    }
}
