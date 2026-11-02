package io.github.marcomow.yuvomi.feature.documents

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Documents calls (docs/spec/documents.md). Must match
 * `upstream/operations-used.d/documents.txt` exactly (ContractFilesTest).
 *
 * The family members for the "Selected members" picker come from Meals' `FAMILY_MEMBERS`
 * (`GET /family/members`), which is not declared again here.
 */
enum class DocumentsOperation(override val method: String, override val template: String) : ApiOperation {
    DOCUMENTS_LIST("GET", "/api/v1/documents"),
    DOCUMENT_CREATE("POST", "/api/v1/documents"),
    DOCUMENT_UPDATE("PUT", "/api/v1/documents/{id}"),
    DOCUMENT_DELETE("DELETE", "/api/v1/documents/{id}"),
    DOCUMENT_ARCHIVE("PATCH", "/api/v1/documents/{id}/archive"),
    DOCUMENT_PREVIEW("GET", "/api/v1/documents/{id}/preview"),
    DOCUMENT_DOWNLOAD("GET", "/api/v1/documents/{id}/download"),
    DOCUMENT_THUMBNAIL("GET", "/api/v1/documents/{id}/thumbnail"),
    META_OPTIONS("GET", "/api/v1/documents/meta/options"),
    FOLDERS_LIST("GET", "/api/v1/documents/folders"),
    FOLDER_CREATE("POST", "/api/v1/documents/folders"),
    FOLDER_UPDATE("PUT", "/api/v1/documents/folders/{id}"),
    FOLDER_DELETE("DELETE", "/api/v1/documents/folders/{id}"),
    FOLDER_DELETE_IMPACT("GET", "/api/v1/documents/folders/{id}/delete-impact"),
    DMS_SEARCH("GET", "/api/v1/documents/dms/search"),
    DMS_LINK("POST", "/api/v1/documents/dms/link"),
    DMS_PUSH("POST", "/api/v1/documents/dms/push"),

    /** The account directory: names of people a document is already shared with (#1207). */
}
