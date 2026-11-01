package io.github.marcomow.yuvomi.core.api.model

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A person as upstream embeds them in tasks (`assigned_users`). */
@Serializable
data class Person(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    val color: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
)

/**
 * A task, with every field docs/spec/tasks.md lists. Every field has a default, so
 * the same class decodes the full task, a list row and the `{ id, title, status }`
 * subtask summary of the list answer.
 */
@Serializable
data class Task(
    val id: Int,
    val title: String = "",
    val description: String? = null,
    val status: String = STATUS_OPEN,
    val priority: String = "none",
    val category: String = "misc",
    val tags: List<String> = emptyList(),
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("assigned_users") val assignedUsers: List<Person> = emptyList(),
    @SerialName("created_by") val createdBy: Int? = null,
    val points: Int = 0,
    val visibility: String = "all",
    val locked: FlexBool = false,
    @SerialName("is_recurring") val isRecurring: FlexBool = false,
    @SerialName("recurrence_rule") val recurrenceRule: String? = null,
    @SerialName("recurrence_from_completion") val recurrenceFromCompletion: FlexBool = false,
    @SerialName("parent_task_id") val parentTaskId: Int? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
    @SerialName("subtask_total") val subtaskTotal: Int = 0,
    @SerialName("subtask_done") val subtaskDone: Int = 0,
    val subtasks: List<Task> = emptyList(),
    @SerialName("document_count") val documentCount: Int? = null,
    val documents: List<TaskDocument>? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** Never sent by the app; read only to prove partial updates keep it (TASKS-A5). */
    val countdown: FlexBool = false,
) {
    val isDone: Boolean get() = status == STATUS_DONE

    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_DONE = "done"
        val PRIORITIES = listOf("none", "low", "medium", "high", "urgent")
        val VISIBILITIES = listOf("all", "assignees", "private")
    }
}

@Serializable
data class TaskDocument(
    val id: Int,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: originalName ?: "#$id"
}

@Serializable
data class TaskCategory(
    val key: String,
    val name: String? = null,
    @SerialName("label_key") val labelKey: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Member(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class TagCount(val tag: String, val count: Int = 0)

/** `GET /tasks/meta/options` (bare, not enveloped). */
@Serializable
data class TaskMeta(
    val users: List<Member> = emptyList(),
    val categories: List<TaskCategory> = emptyList(),
    val tags: List<TagCount> = emptyList(),
    @SerialName("default_points") val defaultPoints: Int = 0,
)

@Serializable
data class StatusChange(
    val id: Int? = null,
    val status: String? = null,
    @SerialName("next_due_date") val nextDueDate: String? = null,
)

@Serializable
data class Comment(
    val id: Int,
    @SerialName("task_id") val taskId: Int = 0,
    @SerialName("user_id") val userId: Int? = null,
    val comment: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("author_color") val authorColor: String? = null,
)

@Serializable
data class CheckResult(val id: Int? = null, val description: String? = null)
