package io.github.marcomow.yuvomi.feature.documents

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Documents calls (docs/spec/documents.md). Must match
 * `upstream/operations-used.d/documents.txt` exactly (ContractFilesTest).
 */
enum class DocumentsOperation(override val method: String, override val template: String) : ApiOperation
