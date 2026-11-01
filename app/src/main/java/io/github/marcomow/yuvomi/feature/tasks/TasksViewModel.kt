package io.github.marcomow.yuvomi.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskMeta
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** docs/spec/tasks.md, TASKS-R1 to R11. */
class TasksViewModel(private val container: AppContainer, presetAssignee: Int?) : ViewModel() {

    data class UiState(
        val tasks: List<Task> = emptyList(),
        val meta: TaskMeta = TaskMeta(),
        val filter: TasksApi.Filter = TasksApi.Filter(),
        val mode: TaskGrouping.Mode = TaskGrouping.Mode.CATEGORY,
        val collapsed: Set<String> = emptySet(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val unsupported: Boolean = false,
    )

    private val default = TasksApi.Filter(assignees = listOfNotNull(presetAssignee))
    private val _state = MutableStateFlow(UiState(filter = default))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api get() = container.session.connection?.tasks
    var lastLoaded = 0L
        private set

    init {
        viewModelScope.launch {
            val mode = TaskGrouping.Mode.entries.firstOrNull { it.key == container.settings.grouping() } ?: TaskGrouping.Mode.CATEGORY
            _state.update { it.copy(mode = mode, collapsed = container.settings.collapsed()) }
        }
        readCache()
        refresh()
    }

    private fun cacheKey(filter: TasksApi.Filter) = if (filter == TasksApi.Filter()) CACHE_LIST else null

    private fun readCache() {
        val list = cacheKey(state.value.filter)?.let { container.cache.read(it) }
        val meta = container.cache.read(CACHE_META)
        runCatching {
            if (meta != null) _state.update { it.copy(meta = TasksApi.decodeMeta(ApiJson.parseToJsonElement(meta.json))) }
            if (list != null) {
                _state.update {
                    it.copy(tasks = TasksApi.decodeList(ApiJson.parseToJsonElement(list.json)), loading = false, cachedAt = list.savedAt)
                }
            }
        }
    }

    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val filter = state.value.filter
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        viewModelScope.launch {
            try {
                val metaJob = async { api.meta() }
                val listJson = api.list(filter)
                val metaJson = metaJob.await()
                val tasks = TasksApi.decodeList(listJson)
                val meta = TasksApi.decodeMeta(metaJson)
                cacheKey(filter)?.let { container.cache.write(it, listJson.toString()) }
                container.cache.write(CACHE_META, metaJson.toString())
                lastLoaded = System.currentTimeMillis()
                if (state.value.filter == filter) {
                    _state.update { it.copy(tasks = tasks, meta = meta, loading = false, refreshing = false, cachedAt = null) }
                }
            } catch (e: ApiProblem) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = it.cachedAt ?: if (it.tasks.isNotEmpty()) System.currentTimeMillis() else null,
                        errorKey = if (it.tasks.isEmpty() && it.cachedAt == null) TaskCompletion.errorKey(e) else null,
                        unsupported = (e as? ApiProblem.Http)?.operationUnsupported == true,
                    )
                }
            }
        }
    }

    fun setFilter(filter: TasksApi.Filter) {
        if (filter == state.value.filter) return
        _state.update { it.copy(filter = filter, loading = true, tasks = emptyList(), cachedAt = null) }
        readCache()
        refresh()
    }

    fun resetFilter() = setFilter(TasksApi.Filter())

    fun setMode(mode: TaskGrouping.Mode) {
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch { container.settings.setGrouping(mode.key) }
    }

    fun toggleCollapsed(groupId: String) {
        val key = "${state.value.mode.key}:$groupId"
        val next = state.value.collapsed.let { if (key in it) it - key else it + key }
        _state.update { it.copy(collapsed = next) }
        viewModelScope.launch { container.settings.setCollapsed(next) }
    }

    fun isCollapsed(groupId: String) = "${state.value.mode.key}:$groupId" in state.value.collapsed

    /** TASKS-R20: the row changes at once; [refresh] afterwards confirms or rolls back. */
    fun markLocally(taskId: Int, done: Boolean) = _state.update { s ->
        s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(status = if (done) Task.STATUS_DONE else Task.STATUS_OPEN) else it })
    }

    companion object {
        const val CACHE_LIST = "tasks-default"
        const val CACHE_META = "tasks-meta"
    }
}
