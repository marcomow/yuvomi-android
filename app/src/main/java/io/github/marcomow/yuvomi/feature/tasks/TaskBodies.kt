package io.github.marcomow.yuvomi.feature.tasks

import io.github.marcomow.yuvomi.core.api.model.Task
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request bodies for `POST /tasks` and the partial `PUT /tasks/{id}` (TASKS-R15, R16, R29). Pure, for tests. */
object TaskBodies {

    /** `points` only when the user typed one, so the server applies its default. */
    fun create(f: TaskEditorViewModel.Form, parentId: Int?, solo: Boolean): JsonObject = JsonObject(buildMap {
        put("title", JsonPrimitive(f.title.trim()))
        put("description", f.description.ifBlank { null }.json())
        put("category", JsonPrimitive(f.category))
        put("priority", JsonPrimitive(f.priority))
        put("start_date", f.startDate?.toString().json())
        put("due_date", f.dueDate?.toString().json())
        put("due_time", f.dueTime?.toString().json())
        put("tags", JsonArray(f.tags.map(::JsonPrimitive)))
        if (!solo) {
            put("assigned_to", JsonArray(f.assignees.sorted().map(::JsonPrimitive)))
            put("visibility", JsonPrimitive(f.visibility))
        }
        put("locked", JsonPrimitive(f.locked))
        f.points.toIntOrNull()?.let { put("points", JsonPrimitive(it)) }
        parentId?.let { put("parent_task_id", JsonPrimitive(it)) }
        if (parentId == null) {
            val rule = RRule.build(f.rule)
            if (rule != null) {
                put("is_recurring", JsonPrimitive(true))
                put("recurrence_rule", JsonPrimitive(rule))
                put("recurrence_from_completion", JsonPrimitive(f.fromCompletion))
            }
        }
    })

    /** Only what changed; `countdown`, `sync_target` and an untouched rule are never sent. */
    fun diff(old: TaskEditorViewModel.Form, new: TaskEditorViewModel.Form, task: Task, solo: Boolean): JsonObject = JsonObject(buildMap {
        if (new.title.trim() != old.title) put("title", JsonPrimitive(new.title.trim()))
        if (new.description != old.description) put("description", new.description.ifBlank { null }.json())
        if (new.category != old.category) put("category", JsonPrimitive(new.category))
        if (new.priority != old.priority) put("priority", JsonPrimitive(new.priority))
        if (new.startDate != old.startDate) put("start_date", new.startDate?.toString().json())
        if (new.dueDate != old.dueDate) put("due_date", new.dueDate?.toString().json())
        if (new.dueTime != old.dueTime) put("due_time", new.dueTime?.toString().json())
        if (new.tags != old.tags) put("tags", JsonArray(new.tags.map(::JsonPrimitive)))
        if (!solo && new.assignees != old.assignees) put("assigned_to", JsonArray(assigneeOrder(new.assignees, task).map(::JsonPrimitive)))
        if (!solo && new.visibility != old.visibility) put("visibility", JsonPrimitive(new.visibility))
        if (new.locked != old.locked) put("locked", JsonPrimitive(new.locked))
        if (new.points != old.points && new.points.toIntOrNull() != null) put("points", JsonPrimitive(new.points.toInt()))
        if (task.parentTaskId == null && (new.rule != old.rule || new.fromCompletion != old.fromCompletion)) {
            val rule = RRule.resolve(new.rule, task.recurrenceRule)
            put("is_recurring", JsonPrimitive(rule != null))
            put("recurrence_rule", rule.json())
            put("recurrence_from_completion", JsonPrimitive(rule != null && new.fromCompletion))
        }
    })

    /**
     * TASKS-R44: the first id of `assigned_to` becomes the primary assignee, and
     * `assigned_users` lists the primary first (upstream 2.86.0, #2569). An edit of the people
     * keeps the current primary in front while they stay assigned; the rest follow by id.
     */
    internal fun assigneeOrder(ids: Set<Int>, task: Task): List<Int> {
        val primary = task.assignedUsers.firstOrNull()?.id?.takeIf { it in ids }
        return listOfNotNull(primary) + ids.filter { it != primary }.sorted()
    }

    private fun String?.json() = if (this == null) JsonNull else JsonPrimitive(this)
}
