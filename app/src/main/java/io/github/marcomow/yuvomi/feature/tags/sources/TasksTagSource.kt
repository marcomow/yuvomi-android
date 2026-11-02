package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.feature.shell.Routes
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagIndex
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Tasks' part of the Tags view (TAGS-R2): the task's real tags (`task_tags`) and the hashtags
 * in its title and description. Every status that is not archived, later starts included, like
 * the board with all status filters on (`GET /tasks?status=open&status=in_progress&status=done&include_future=1`).
 */
object TasksTagSource : TagSource {
    override val moduleId = "tasks"

    val filter = TasksApi.Filter(
        statuses = listOf(Task.STATUS_OPEN, Task.STATUS_IN_PROGRESS, Task.STATUS_DONE),
        includeFuture = true,
    )

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        return items(TasksApi.decodeList(shell.connection.tasks.list(filter)), SourceText.of(shell))
    }

    fun items(tasks: List<Task>, text: SourceText): List<TaggedItem> = tasks.mapNotNull { task ->
        val tags = TagIndex.union(task.tags, Hashtags.extract(task.title, task.description))
        if (tags.isEmpty()) return@mapNotNull null
        val status = when (task.status) {
            Task.STATUS_DONE -> text.label("tasks.statusDone")
            Task.STATUS_IN_PROGRESS -> text.label("tasks.statusInProgress")
            else -> null
        }
        TaggedItem(moduleId, "task:${task.id}", task.title, text.join(text.date(task.dueDate), status), tags, Routes.task(task.id))
    }
}
