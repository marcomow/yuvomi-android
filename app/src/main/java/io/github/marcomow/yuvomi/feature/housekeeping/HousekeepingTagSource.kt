package io.github.marcomow.yuvomi.feature.housekeeping

import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decode
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

/**
 * Housekeeping in the Tags view (docs/spec/housekeeping.md, HOUSE-R33): the `#hashtags` in a
 * chore's name and area, and in a housekeeper's name and notes. Visits carry no text of their own.
 */
object HousekeepingTagSource : TagSource {
    override val moduleId = "housekeeping"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val client = shell.connection.client
        val (tasks, workers) = fetch(client, shell.time.zone.id)
        return items(tasks, workers)
    }

    private suspend fun fetch(client: YuvomiClient, zone: String): Pair<List<DecayTask>, List<Worker>> = coroutineScope {
        val tasks = async { client.call(HousekeepingOperation.TASKS).decodeData(ListSerializer(DecayTask.serializer())) }
        val workers = async { client.call(HousekeepingOperation.WORKERS, query = listOf("timezone" to zone)).decode(WorkersAnswer.serializer()).data }
        tasks.await() to workers.await()
    }

    /** The tagged items of the two lists; pure, so it is tested without a server. */
    fun items(tasks: List<DecayTask>, workers: List<Worker>): List<TaggedItem> =
        tasks.mapNotNull { task ->
            val tags = Hashtags.extract(task.name, task.area)
            if (tags.isEmpty()) null else TaggedItem(
                moduleId = moduleId,
                key = "chore:${task.id}",
                title = task.name,
                subtitle = task.area,
                tags = tags,
                route = HousekeepingRoutes.task(task.id),
            )
        } + workers.mapNotNull { worker ->
            val tags = Hashtags.extract(worker.displayName, worker.notes)
            if (tags.isEmpty()) null else TaggedItem(
                moduleId = moduleId,
                key = "worker:${worker.id}",
                title = worker.displayName,
                subtitle = null,
                tags = tags,
                route = HousekeepingRoutes.worker(worker.id),
            )
        }
}
