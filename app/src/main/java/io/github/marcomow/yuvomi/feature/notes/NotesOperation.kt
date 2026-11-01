package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Notes calls. Must match
 * `upstream/operations-used.d/notes.txt` exactly (ContractFilesTest).
 */
enum class NotesOperation(override val method: String, override val template: String) : ApiOperation {
    ;
}
