package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Notes calls (docs/spec/notes.md). Must match
 * `upstream/operations-used.d/notes.txt` exactly (ContractFilesTest).
 */
enum class NotesOperation(override val method: String, override val template: String) : ApiOperation {
    NOTES_LIST("GET", "/api/v1/notes"),
    NOTE_CREATE("POST", "/api/v1/notes"),
    NOTE_UPDATE("PUT", "/api/v1/notes/{id}"),
    NOTE_DELETE("DELETE", "/api/v1/notes/{id}"),
    NOTE_PIN("PATCH", "/api/v1/notes/{id}/pin"),
    NOTE_CHECK("PATCH", "/api/v1/notes/{id}/check"),
    CATEGORIES_LIST("GET", "/api/v1/notes/categories"),
    CATEGORY_CREATE("POST", "/api/v1/notes/categories"),
    CATEGORIES_REORDER("PATCH", "/api/v1/notes/categories/reorder"),
    CATEGORY_UPDATE("PUT", "/api/v1/notes/categories/{id}"),
    CATEGORY_DELETE("DELETE", "/api/v1/notes/categories/{id}"),
}
