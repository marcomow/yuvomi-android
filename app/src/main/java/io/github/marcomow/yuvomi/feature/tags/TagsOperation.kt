package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Tags calls itself (docs/spec/tags.md): the task tag management of
 * upstream's tag manager (`public/components/tag-manager.js`, `server/routes/tasks.js` `/tags`).
 * Everything else the Tags view reads goes through the other modules' own APIs. Must match
 * `upstream/operations-used.d/tags.txt` exactly (ContractFilesTest).
 */
enum class TagsOperation(override val method: String, override val template: String) : ApiOperation {
    TASK_TAGS("GET", "/api/v1/tasks/tags"),
    TASK_TAG_RENAME("PUT", "/api/v1/tasks/tags/{tag}"),
    TASK_TAG_DELETE("DELETE", "/api/v1/tasks/tags/{tag}"),
}
