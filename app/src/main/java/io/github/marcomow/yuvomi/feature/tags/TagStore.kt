package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.session.Connection
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Everything that carries a tag, shared by the Tags list and a tag's page (docs/spec/tags.md).
 * One store per signed-in connection. Every [TagSource] loads in parallel; a source that fails
 * keeps its items from the last good answer and is named in [State.failed] (TAGS-R3). Reads are
 * cache-then-network (ADR 0007).
 */
class TagStore(
    private val cache: ResponseCache,
    private val tasks: TaskTagsApi,
    private val scope: CoroutineScope,
    private val sources: List<TagSource> = TagSources.all,
) {
    data class State(
        val items: List<TaggedItem> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        /** Module ids whose source failed on the last load; their items are the last known ones. */
        val failed: List<String> = emptyList(),
        /** When every source failed and the items came from the cache (OfflineBanner). */
        val cachedAt: Long? = null,
        /** Nothing to show at all: the i18n key of why. */
        val errorKey: String? = null,
        /** `GET /tasks/tags`: the real task tags the member sees, for rename and delete (TAGS-R9). */
        val taskTags: List<TaskTagCount> = emptyList(),
    ) {
        val offline: Boolean get() = cachedAt != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** When the last load finished, for "refresh when older than a minute". */
    var lastLoaded = 0L
        private set
    private var job: Job? = null

    init {
        runCatching {
            cache.read(CACHE_KEY)?.let { entry ->
                _state.update { it.copy(items = TagIndex.decode(entry.json), loading = false) }
            }
        }
    }

    /** Loads unless a load is running already; [pulled] shows the pull-to-refresh spinner. */
    fun refresh(shell: ShellContext, pulled: Boolean = false, force: Boolean = false) {
        if (job?.isActive == true) {
            if (!force) return
            job?.cancel()
        }
        _state.update { it.copy(refreshing = pulled) }
        job = scope.launch { load(shell) }
    }

    /** Refreshes when nothing came from the server yet or the last load is older than [maxAgeMs]. */
    fun refreshIfStale(shell: ShellContext, maxAgeMs: Long = 60_000) {
        if (System.currentTimeMillis() - lastLoaded > maxAgeMs) refresh(shell)
    }

    suspend fun load(shell: ShellContext) {
        val outcome = coroutineScope {
            val tagJob = async {
                if (shell.shows("tasks")) attempt { tasks.list() }.getOrNull() else emptyList()
            }
            val results = sources.map { source -> async { source.moduleId to attempt { source.load(shell) } } }.awaitAll()
            results to tagJob.await()
        }
        val (results, taskTags) = outcome
        val previous = state.value.items
        val failed = results.filter { it.second.isFailure }.map { it.first }
        val merged = results.flatMap { (module, result) ->
            result.getOrElse { previous.filter { it.moduleId == module } }
        }
        val errors = results.mapNotNull { it.second.exceptionOrNull() }
        lastLoaded = System.currentTimeMillis()
        // Offline: every source that tried failed for want of a connection (sources of switched-off
        // modules answer at once with nothing). The last known items stay, read-only, under the banner.
        val offline = errors.isNotEmpty() && errors.all { it is ApiProblem.Network }
        val nothing = merged.isEmpty() && errors.isNotEmpty() && (offline || errors.size == results.size)
        if (offline || nothing) {
            val savedAt = cache.read(CACHE_KEY)?.savedAt
            _state.update {
                it.copy(
                    items = merged,
                    loading = false,
                    refreshing = false,
                    failed = if (offline) emptyList() else failed,
                    cachedAt = if (offline && merged.isNotEmpty()) savedAt ?: System.currentTimeMillis() else null,
                    errorKey = if (merged.isEmpty()) errorKey(errors.first()) else null,
                    taskTags = taskTags ?: it.taskTags,
                )
            }
            return
        }
        runCatching { cache.write(CACHE_KEY, TagIndex.encode(merged)) }
        _state.update {
            it.copy(
                items = merged,
                loading = false,
                refreshing = false,
                failed = failed,
                cachedAt = null,
                errorKey = null,
                taskTags = taskTags ?: it.taskTags,
            )
        }
    }

    /** TAGS-R10: renames a task tag on every task the member sees; the answer carries the fresh task tag list. */
    suspend fun renameTaskTag(from: String, to: String): TaskTagChange =
        tasks.rename(from, to).also { change -> _state.update { it.copy(taskTags = change.tags) } }

    /** TAGS-R11: takes a task tag off every task the member sees. */
    suspend fun deleteTaskTag(tag: String): TaskTagChange =
        tasks.delete(tag).also { change -> _state.update { it.copy(taskTags = change.tags) } }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val CACHE_KEY = "tags-index"

        private val stores = WeakHashMap<Connection, TagStore>()

        /** The store of the signed-in connection, or null when signed out. */
        fun of(container: AppContainer): TagStore? {
            val connection = container.session.connection ?: return null
            return synchronized(stores) {
                stores.getOrPut(connection) { TagStore(container.cache, TaskTagsApi(connection.client), container.scope) }
            }
        }

        fun errorKey(e: Throwable?): String = when (e) {
            is ApiProblem.Network -> "android.error.offline"
            is ApiProblem.Http -> if (e.status == 403) "android.error.forbidden" else "android.tags.loadFailed"
            else -> "android.tags.loadFailed"
        }
    }
}
