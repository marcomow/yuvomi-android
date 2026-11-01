package io.github.marcomow.yuvomi.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskMeta
import io.github.marcomow.yuvomi.core.session.Session
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalTime

/** docs/spec/tasks.md, create and edit (TASKS-R12 to R19, R28, R29). */
class TaskEditorViewModel(
    private val container: AppContainer,
    private val taskId: Int?,
    private val parentId: Int?,
    presetCategory: String?,
    presetAssignee: Int?,
) : ViewModel() {

    data class Form(
        val title: String = "",
        val description: String = "",
        val category: String = "misc",
        val priority: String = "none",
        val startDate: LocalDate? = null,
        val dueDate: LocalDate? = null,
        val dueTime: LocalTime? = null,
        val assignees: Set<Int> = emptySet(),
        val tags: List<String> = emptyList(),
        val points: String = "",
        val visibility: String = "all",
        val locked: Boolean = false,
        val rule: RRule.Parts = RRule.Parts(),
        val fromCompletion: Boolean = false,
    )

    data class UiState(
        val form: Form = Form(),
        val original: Form? = null,
        val task: Task? = null,
        val meta: TaskMeta = TaskMeta(),
        val loading: Boolean = true,
        val saving: Boolean = false,
        val errorKey: String? = null,
        /** A server 400 explains a rule; shown in a dialog (TASKS-R18). */
        val serverMessage: String? = null,
        val saved: Boolean = false,
        val deleted: Boolean = false,
    ) {
        val isNew: Boolean get() = task == null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: TasksApi? get() = container.session.connection?.tasks
    private val session: Session? get() = (container.session.state.value as? SessionState.SignedIn)?.session

    /** One key per creation attempt, reused on retry (TASKS-A17). */
    private val createKey = YuvomiClient.newIdempotencyKey()

    val isSubtask: Boolean get() = parentId != null || state.value.task?.parentTaskId != null

    init {
        val prefs = session?.preferences
        _state.update {
            it.copy(
                form = Form(
                    category = presetCategory ?: "misc",
                    assignees = setOfNotNull(presetAssignee),
                    visibility = prefs?.tasksDefaultVisibility ?: "all",
                    locked = prefs?.tasksDefaultLocked ?: false,
                ),
            )
        }
        load()
    }

    private fun load() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val meta = TasksApi.decodeMeta(api.meta())
                _state.update { it.copy(meta = meta) }
                if (taskId != null) {
                    val task = api.get(taskId)
                    val form = formOf(task)
                    _state.update { it.copy(task = task, form = form, original = form, loading = false) }
                } else {
                    _state.update { it.copy(loading = false) }
                }
            } catch (e: ApiProblem) {
                _state.update { it.copy(loading = false, errorKey = TaskCompletion.errorKey(e)) }
            }
        }
    }

    private fun formOf(t: Task) = Form(
        title = t.title,
        description = t.description.orEmpty(),
        category = t.category,
        priority = t.priority,
        startDate = HouseholdTime.date(t.startDate),
        dueDate = HouseholdTime.date(t.dueDate),
        dueTime = t.dueTime?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() },
        assignees = t.assignedUsers.map { it.id }.toSet(),
        tags = t.tags,
        points = t.points.toString(),
        visibility = t.visibility,
        locked = t.locked,
        rule = if (t.isRecurring) RRule.parse(t.recurrenceRule) else RRule.Parts(),
        fromCompletion = t.recurrenceFromCompletion,
    )

    /** TASKS-R17: the definition is closed to everyone but the creator and admins. */
    val definitionEditable: Boolean
        get() {
            val task = state.value.task ?: return true
            val s = session ?: return false
            return !task.locked || task.createdBy == s.user.id || s.isAdmin
        }

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), errorKey = null) }
    fun dismissServerMessage() = _state.update { it.copy(serverMessage = null) }

    /** TASKS-R18. Returns an i18n key or null. */
    fun validate(f: Form): String? = when {
        f.title.isBlank() -> "android.tasks.titleRequired"
        f.dueTime != null && f.dueDate == null -> "android.tasks.timeNeedsDate"
        f.startDate != null && f.dueDate != null && f.startDate.isAfter(f.dueDate) -> "android.tasks.startAfterDue"
        f.points.isNotBlank() && (f.points.toIntOrNull() == null || f.points.toInt() !in 0..10000) -> "android.tasks.pointsRange"
        else -> null
    }

    fun save() {
        val s = state.value
        val api = api ?: return
        if (s.saving) return
        validate(s.form)?.let { key -> _state.update { it.copy(errorKey = key) }; return }
        _state.update { it.copy(saving = true, errorKey = null) }
        viewModelScope.launch {
            try {
                if (s.task == null) {
                    api.create(createBody(s.form), createKey)
                } else {
                    val changes = diff(s.original ?: formOf(s.task), s.form, s.task)
                    if (changes.isNotEmpty()) api.update(s.task.id, changes)
                }
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: ApiProblem) {
                _state.update {
                    if (e is ApiProblem.Http && e.status == 400) {
                        it.copy(saving = false, serverMessage = e.serverMessage ?: "")
                    } else {
                        it.copy(saving = false, errorKey = TaskCompletion.errorKey(e))
                    }
                }
            }
        }
    }

    fun delete() {
        val task = state.value.task ?: return
        val api = api ?: return
        viewModelScope.launch {
            try {
                api.delete(task.id)
                _state.update { it.copy(deleted = true) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(errorKey = TaskCompletion.errorKey(e)) }
            }
        }
    }

    private val solo: Boolean get() = session?.soloHousehold == true

    private fun createBody(f: Form): JsonObject = TaskBodies.create(f, parentId, solo)

    private fun diff(old: Form, new: Form, task: Task): JsonObject = TaskBodies.diff(old, new, task, solo)
}
