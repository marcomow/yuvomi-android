package io.github.marcomow.yuvomi.feature.tasks

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.i18n.Formats
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.time.LocalDate

/**
 * Ticking off, naming who did it, and undo (TASKS-R20 to R23), shared by the
 * task list, the task detail and the overview (OVERVIEW-R11).
 */
class TaskCompletion(private val api: TasksApi) {

    data class Receipt(val message: String, val undoable: Boolean)

    /** Returns the confirmation for a transition into `done`, or null for any other one. */
    suspend fun setDone(
        task: Task,
        done: Boolean,
        doneBy: Pair<Int, String>? = null,
        strings: Strings,
        formats: Formats,
        today: LocalDate,
    ): Receipt? {
        val change = api.setStatus(task.id, if (done) Task.STATUS_DONE else Task.STATUS_OPEN, doneBy?.first)
        if (!done || task.parentTaskId != null) return null
        val next = change.nextDueDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        val message = when {
            next != null && doneBy != null ->
                strings.t("tasks.seriesDoneByToast", "name" to doneBy.second, "date" to formats.due(next, null, today))
            next != null -> strings.t("tasks.seriesDoneNamed", "title" to task.title, "date" to formats.due(next, null, today))
            doneBy != null -> strings.t("tasks.doneByToast", "name" to doneBy.second)
            else -> strings.t("tasks.checkedDoneNamed", "title" to task.title)
        }
        return Receipt(message, undoable = true)
    }

    suspend fun undo(task: Task) {
        api.setStatus(task.id, Task.STATUS_OPEN)
    }

    companion object {
        /** Maps a failure to an i18n key (ARCHITECTURE.md, rule 4). */
        fun errorKey(e: Throwable): String = when (e) {
            is ApiProblem.Network -> "android.error.offline"
            is ApiProblem.Http -> when {
                e.reason == "tasks_task_not_found" -> "android.tasks.gone"
                e.reason == "task_locked" -> "tasks.errorLocked"
                e.reason == "tasks_comment_not_found" -> "tasks.errorCommentGone"
                e.reason == "tasks_parent_not_found" -> "android.tasks.gone"
                e.operationUnsupported -> "android.error.unsupported"
                e.status == 403 -> "android.error.forbidden"
                else -> "android.error.generic"
            }
            else -> "android.error.generic"
        }
    }
}
