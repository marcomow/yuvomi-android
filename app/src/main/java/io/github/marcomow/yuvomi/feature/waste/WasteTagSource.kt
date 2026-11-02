package io.github.marcomow.yuvomi.feature.waste

import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

/**
 * Waste in the Tags view (docs/spec/waste.md, WASTE-R38): the `#hashtags` in a type's name, in a
 * one-off pickup's note and in an import source's name. Schedules carry no text of their own.
 */
object WasteTagSource : TagSource {
    override val moduleId = "waste"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val client = shell.connection.client
        return coroutineScope {
            val types = async { client.call(WasteOperation.TYPES, query = listOf("include_archived" to "1")).decodeData(ListSerializer(WasteType.serializer())) }
            val pickups = async { client.call(WasteOperation.PICKUPS_LIST).decodeData(ListSerializer(WastePickup.serializer())) }
            val sources = async { client.call(WasteOperation.SOURCES).decodeData(ListSerializer(WasteSource.serializer())) }
            items(types.await(), pickups.await(), sources.await())
        }
    }

    /** The tagged items; pure, so it is tested without a server. */
    fun items(types: List<WasteType>, pickups: List<WastePickup>, sources: List<WasteSource>): List<TaggedItem> {
        val names = types.associate { it.id to it.name }
        val typeItems = types.mapNotNull { type ->
            val tags = Hashtags.extract(type.name)
            if (tags.isEmpty()) null else TaggedItem(moduleId, "type:${type.id}", type.name, null, tags, WasteRoutes.pickup(type.id))
        }
        val pickupItems = pickups.mapNotNull { p ->
            val tags = Hashtags.extract(p.note)
            if (tags.isEmpty()) null else TaggedItem(moduleId, "pickup:${p.id}", names[p.typeId] ?: p.note.orEmpty(), p.date, tags, WasteRoutes.pickup(p.typeId, p.date))
        }
        val sourceItems = sources.mapNotNull { src ->
            val tags = Hashtags.extract(src.name)
            if (tags.isEmpty()) null else TaggedItem(moduleId, "source:${src.id}", src.name, null, tags, WasteRoutes.START)
        }
        return typeItems + pickupItems + sourceItems
    }
}
