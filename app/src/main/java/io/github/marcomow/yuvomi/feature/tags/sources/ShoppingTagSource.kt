package io.github.marcomow.yuvomi.feature.tags.sources

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.shopping.ShoppingApi
import io.github.marcomow.yuvomi.feature.shopping.ShoppingItem
import io.github.marcomow.yuvomi.feature.shopping.ShoppingList
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagIndex
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Shopping's part of the Tags view (TAGS-R2): on every list, each item's real tags (read-only,
 * mirrored from CalDAV `CATEGORIES`, `shopping_item_tags`) and the hashtags in its name and
 * notes; a list whose name has a hashtag is an item of its own.
 */
object ShoppingTagSource : TagSource {
    override val moduleId = "shopping"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val api = ShoppingApi(shell.connection.client)
        val lists = ShoppingApi.decodeLists(api.lists())
        val items = coroutineScope {
            lists.map { list -> async { list to ShoppingApi.decodeItems(api.items(list.id)).data } }.awaitAll()
        }
        return items(items)
    }

    fun items(lists: List<Pair<ShoppingList, List<ShoppingItem>>>): List<TaggedItem> = buildList {
        for ((list, rows) in lists) {
            val listTags = Hashtags.extract(list.name)
            if (listTags.isNotEmpty()) add(TaggedItem(moduleId, "list:${list.id}", list.name, null, listTags, ShoppingRoutes.list(list.id)))
            for (item in rows) {
                val tags = TagIndex.union(item.tags, Hashtags.extract(item.name, item.notes))
                if (tags.isEmpty()) continue
                val subtitle = listOfNotNull(list.name, item.quantity?.takeIf { it.isNotBlank() }).joinToString(" · ")
                add(TaggedItem(moduleId, "item:${item.id}", item.name, subtitle, tags, ShoppingRoutes.item(list.id, item.id)))
            }
        }
    }
}
