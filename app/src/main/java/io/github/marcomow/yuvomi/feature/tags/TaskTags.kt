package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `{ tag, count }` of `GET /tasks/tags`: a tag on the tasks the member can see. */
@Serializable
data class TaskTagCount(val tag: String, val count: Int = 0)

/** `data` of `PUT` and `DELETE /tasks/tags/{tag}`. */
@Serializable
data class TaskTagChange(
    val updated: Int = 0,
    val skipped: Int = 0,
    /** The new spelling, on a rename. */
    val tag: String? = null,
    val tags: List<TaskTagCount> = emptyList(),
)

/** The task tag management of upstream's tag manager (TAGS-R9 to R12). */
class TaskTagsApi(private val client: YuvomiClient) {
    suspend fun list(): List<TaskTagCount> =
        client.call(TagsOperation.TASK_TAGS).decodeData(ListSerializer(TaskTagCount.serializer()))

    /** Renaming onto an existing tag merges the two (upstream `renameTag`). */
    suspend fun rename(from: String, to: String): TaskTagChange =
        client.call(TagsOperation.TASK_TAG_RENAME, mapOf("tag" to from), body = buildJsonObject { put("name", to) })
            .decodeData(TaskTagChange.serializer())

    suspend fun delete(tag: String): TaskTagChange =
        client.call(TagsOperation.TASK_TAG_DELETE, mapOf("tag" to tag)).decodeData(TaskTagChange.serializer())
}

/** Pure rules of the task tag management (tag-manager.js `_rename`, `withSkipped`; task-tags.js `normalizeTags`). */
object TaskTagRules {
    sealed interface Rename {
        /** Nothing to send: the name is unchanged (`!to || to === from` in `_rename`). */
        data object Unchanged : Rename

        /** The server would refuse it: `normalizeTags` drops it and answers 400. */
        data object Invalid : Rename

        data class Send(val to: String) : Rename
    }

    /** What a rename of [from] to [typed] does before any call. */
    fun rename(from: String, typed: String): Rename {
        val to = typed.trim()
        if (to.isEmpty() || to == from) return Rename.Unchanged
        val normalized = to.take(TagIndex.MAX_LEN).trim()
        if (normalized.isEmpty() || normalized == "." || normalized == "..") return Rename.Invalid
        return Rename.Send(to)
    }

    /** The task tag that [tag] names, if the member's tasks carry it. */
    fun find(tags: List<TaskTagCount>, tag: String): TaskTagCount? = tags.firstOrNull { TagKey.of(it.tag) == TagKey.of(tag) }

    /** The success message with the locked tasks left out (`withSkipped`). */
    fun message(base: String, skipped: Int, strings: Strings): String =
        if (skipped > 0) "$base ${strings.t("tasks.tagsSkippedLocked", "count" to skipped)}" else base

    fun renamed(change: TaskTagChange, strings: Strings) =
        message(strings.t("tasks.tagsUpdated", "count" to change.updated), change.skipped, strings)

    fun deleted(change: TaskTagChange, strings: Strings) =
        message(strings.t("tasks.tagDeleted", "count" to change.updated), change.skipped, strings)

    /**
     * Maps a failure to an i18n key. Upstream answers both of its refusals without a `reason`:
     * 404 "Tag not found." (no visible task carries it any more) and 400 "name must be a
     * non-empty tag."; the web app shows those English texts, the app its own sentences.
     */
    fun errorKey(e: Throwable): String = when (e) {
        is ApiProblem.Network -> "android.error.offline"
        is ApiProblem.Http -> when (e.status) {
            404 -> "android.tags.taskTagGone"
            400 -> "android.tags.nameInvalid"
            403 -> "android.error.forbidden"
            405 -> "android.error.unsupported"
            else -> "android.error.generic"
        }
        else -> "android.error.generic"
    }
}
