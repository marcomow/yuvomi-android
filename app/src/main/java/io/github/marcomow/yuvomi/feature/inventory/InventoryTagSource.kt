package io.github.marcomow.yuvomi.feature.inventory

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Inventory in the Tags view (docs/spec/inventory.md, INV-R22): a `#hashtag` in an item's name,
 * brand, model or notes is a tag. Reads `GET /inventory/items` like the list does.
 */
object InventoryTagSource : TagSource {
    override val moduleId = "inventory"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        return items(InventoryApi.decodeItems(InventoryApi(shell.connection.client).items()))
    }

    /** The tagged items, each opening its read view. */
    fun items(items: List<InventoryItem>): List<TaggedItem> = items.mapNotNull { item ->
        val tags = Hashtags.extract(item.name, item.brand, item.model, item.notes)
        if (tags.isEmpty()) return@mapNotNull null
        TaggedItem(
            moduleId = moduleId,
            key = "item:${item.id}",
            title = item.name,
            subtitle = item.locationPath,
            tags = tags,
            route = InventoryRoutes.item(item.id),
        )
    }
}
