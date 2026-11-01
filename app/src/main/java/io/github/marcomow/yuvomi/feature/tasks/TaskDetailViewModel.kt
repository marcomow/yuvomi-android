package io.github.marcomow.yuvomi.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.model.Comment
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/** docs/spec/tasks.md: detail (R25), subtasks (R30 to R32), checklists (R33, R34), comments (R35 to R37). */
class TaskDetailViewModel(private val container: AppContainer, private val taskId: Int) : ViewModel() {

    data class UiState(
        val task: Task? = null,
        val meta: TaskMeta = TaskMeta(),
        val comments: List<Comment>? = null,
        val commentsFailed: Boolean = false,
        val loading: Boolean = true,
        val gone: Boolean = false,
        val errorKey: String? = null,
        val sendingComment: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: TasksApi? get() = container.session.connection?.tasks

    /** One key per comment the user is trying to send; reused on retry (ADR 0007). */
    private var commentKey: String = YuvomiClient.newIdempotencyKey()

    init {
        container.cache.read("task-$taskId")?.let { e ->
            runCatching { ApiJson.decodeFromString(Task.serializer(), e.json) }.getOrNull()?.let { t ->
                _state.update { it.copy(task = t, loading = false) }
            }
        }
        container.cache.read(TasksViewModel.CACHE_META)?.let { e ->
            runCatching { TasksApi.decodeMeta(ApiJson.parseToJsonElement(e.json)) }.getOrNull()?.let { m -> _state.update { it.copy(meta = m) } }
        }
        load()
    }

    fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val task = api.get(taskId)
                container.cache.write("task-$taskId", ApiJson.encodeToString(Task.serializer(), task))
                _state.update { it.copy(task = task, loading = false, errorKey = null) }
            } catch (e: ApiProblem) {
                if (e is ApiProblem.Http && e.status == 404 && e.reason != null) {
                    _state.update { it.copy(gone = true, loading = false) }
                } else {
                    _state.update { it.copy(loading = false, errorKey = if (it.task == null) TaskCompletion.errorKey(e) else null) }
                }
            }
        }
        viewModelScope.launch {
            runCatching { TasksApi.decodeMeta(api.meta()) }.getOrNull()?.let { m -> _state.update { it.copy(meta = m) } }
        }
        loadComments()
    }

    fun loadComments() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val comments = api.comments(taskId)
                _state.update { it.copy(comments = comments, commentsFailed = false) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(commentsFailed = true) }
            }
        }
    }

    /** TASKS-R33: optimistic, then the server's description replaces it. Returns an error key or null. */
    suspend fun checkLine(line: Int, checked: Boolean, shown: String): String? {
        val api = api ?: return "android.error.generic"
        val task = state.value.task ?: return null
        val lines = Checklist.lines(task.description.orEmpty()).toMutableList()
        if (line in lines.indices) lines[line] = Checklist.toggled(shown, checked)
        _state.update { it.copy(task = task.copy(description = lines.joinToString("\n"))) }
        return try {
            val result = api.checkLine(taskId, line, checked, shown)
            _state.update { s -> s.copy(task = s.task?.copy(description = result.description ?: s.task.description)) }
            null
        } catch (e: ApiProblem) {
            load()
            if (e is ApiProblem.Http && e.status == 409) "android.tasks.checklistChanged" else TaskCompletion.errorKey(e)
        }
    }

    /** TASKS-R32: subtasks have no "who did it" and no undo. */
    suspend fun toggleSubtask(sub: Task): String? {
        val api = api ?: return null
        val done = !sub.isDone
        _state.update { s -> s.copy(task = s.task?.copy(subtasks = s.task.subtasks.map { if (it.id == sub.id) it.copy(status = if (done) Task.STATUS_DONE else Task.STATUS_OPEN) else it })) }
        return try {
            api.setStatus(sub.id, if (done) Task.STATUS_DONE else Task.STATUS_OPEN)
            load()
            null
        } catch (e: ApiProblem) {
            load()
            TaskCompletion.errorKey(e)
        }
    }

    /** TASKS-R30: rename from the row menu, `PUT /tasks/{id}` with the title only. */
    suspend fun renameSubtask(sub: Task, title: String): String? {
        val api = api ?: return null
        _state.update { s -> s.copy(task = s.task?.copy(subtasks = s.task.subtasks.map { if (it.id == sub.id) it.copy(title = title) else it })) }
        return try {
            api.update(sub.id, buildJsonObject { put("title", title) })
            load()
            null
        } catch (e: ApiProblem) {
            load()
            TaskCompletion.errorKey(e)
        }
    }

    suspend fun deleteSubtask(sub: Task): String? {
        val api = api ?: return null
        return try {
            api.delete(sub.id)
            load()
            null
        } catch (e: ApiProblem) {
            TaskCompletion.errorKey(e)
        }
    }

    /** TASKS-R24: Start / Reopen. */
    suspend fun setStatus(status: String): String? {
        val api = api ?: return null
        return try {
            api.setStatus(taskId, status)
            load()
            null
        } catch (e: ApiProblem) {
            TaskCompletion.errorKey(e)
        }
    }

    suspend fun addComment(text: String): String? {
        val api = api ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        _state.update { it.copy(sendingComment = true) }
        return try {
            api.addComment(taskId, trimmed, commentKey)
            commentKey = YuvomiClient.newIdempotencyKey()
            loadComments()
            null
        } catch (e: ApiProblem) {
            TaskCompletion.errorKey(e)
        } finally {
            _state.update { it.copy(sendingComment = false) }
        }
    }

    suspend fun editComment(comment: Comment, text: String): String? {
        val api = api ?: return null
        return try {
            api.editComment(taskId, comment.id, text.trim())
            loadComments()
            null
        } catch (e: ApiProblem) {
            loadComments()
            TaskCompletion.errorKey(e)
        }
    }

    suspend fun deleteComment(comment: Comment): String? {
        val api = api ?: return null
        return try {
            api.deleteComment(taskId, comment.id)
            loadComments()
            null
        } catch (e: ApiProblem) {
            loadComments()
            TaskCompletion.errorKey(e)
        }
    }
}
