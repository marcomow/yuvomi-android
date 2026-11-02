package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One tag across all modules, as the Tags list shows it (TAGS-R4). */
data class TagSummary(
    /** [TagKey] of the tag: the identity. */
    val key: String,
    /** The spelling most items use; on a tie the one seen first. */
    val label: String,
    /** How many items carry it. */
    val count: Int,
    /** The modules it appears in, in [TagIndex.moduleRank] order. */
    val modules: List<String>,
)

/** The items of one module that carry a tag (TAGS-R6). */
data class TagGroup(val moduleId: String, val items: List<TaggedItem>)

/** The pure logic of the Tags view (docs/spec/tags.md). */
object TagIndex {
    /** Upstream's `MAX_TAG_LEN` (server/utils/task-tags.js). */
    const val MAX_LEN = 64

    /** Where a module's group sits: the order of [ModuleCatalog.all], unknown ids last. */
    fun moduleRank(moduleId: String): Int =
        ModuleCatalog.all.indexOfFirst { it.id == moduleId }.let { if (it < 0) Int.MAX_VALUE else it }

    /** [first] then [more], one spelling per [TagKey], the first one kept (upstream's `normalizeTags`). */
    fun union(first: Collection<String>, more: Collection<String> = emptyList()): Set<String> {
        val seen = HashSet<String>()
        val out = LinkedHashSet<String>()
        for (tag in first + more) {
            val clean = tag.trim()
            if (clean.isEmpty()) continue
            if (seen.add(TagKey.of(clean))) out += clean
        }
        return out
    }

    /** Every tag of [items] with its count, most used first, then by name (upstream's `allTags` order). */
    fun summarize(items: List<TaggedItem>): List<TagSummary> {
        data class Acc(val spellings: LinkedHashMap<String, Int> = LinkedHashMap(), var count: Int = 0, val modules: MutableSet<String> = HashSet())
        val byKey = LinkedHashMap<String, Acc>()
        for (item in items) {
            for (tag in union(item.tags)) {
                val acc = byKey.getOrPut(TagKey.of(tag)) { Acc() }
                acc.count++
                acc.spellings[tag] = (acc.spellings[tag] ?: 0) + 1
                acc.modules += item.moduleId
            }
        }
        return byKey.map { (key, acc) ->
            val best = acc.spellings.maxOf { it.value }
            TagSummary(key, acc.spellings.entries.first { it.value == best }.key, acc.count, acc.modules.sortedBy(::moduleRank))
        }.sortedWith(compareByDescending<TagSummary> { it.count }.thenBy { it.label.lowercase() })
    }

    /** The tag a member typed into the search field: without `#`, trimmed, at most [MAX_LEN]; null when nothing is left. */
    fun typedTag(query: String): String? {
        val tag = query.trim().trimStart('#').trim().take(MAX_LEN).trim()
        return tag.takeIf { it.isNotEmpty() }
    }

    /** TAGS-R5: the tags whose key contains the typed text. */
    fun filter(summaries: List<TagSummary>, query: String): List<TagSummary> {
        val needle = typedTag(query)?.let(TagKey::of) ?: return summaries
        return summaries.filter { needle in it.key }
    }

    /** TAGS-R5: offer "find #tag" when the typed tag is not one of [summaries] exactly. */
    fun offersFind(summaries: List<TagSummary>, query: String): String? {
        val tag = typedTag(query) ?: return null
        return tag.takeIf { typed -> summaries.none { it.key == TagKey.of(typed) } }
    }

    /** TAGS-R6: the items carrying [tag], by module in [moduleRank] order, by title within a module. */
    fun groups(items: List<TaggedItem>, tag: String): List<TagGroup> {
        val key = TagKey.of(tag)
        return items.filter { item -> item.tags.any { TagKey.of(it) == key } }
            .groupBy { it.moduleId }
            .map { (module, list) -> TagGroup(module, list.sortedBy { it.title.lowercase() }) }
            .sortedBy { moduleRank(it.moduleId) }
    }

    /** The spelling of [tag] that items use most, for the detail's title. */
    fun label(items: List<TaggedItem>, tag: String): String =
        summarize(items).firstOrNull { it.key == TagKey.of(tag) }?.label ?: tag

    /** TAGS-R8: the other tags of an item, for its chips. */
    fun otherTags(item: TaggedItem, tag: String): List<String> {
        val key = TagKey.of(tag)
        return item.tags.filter { TagKey.of(it) != key }.sortedBy { it.lowercase() }
    }

    // ── Cache (ADR 0007) ──

    @Serializable
    private data class Stored(
        val moduleId: String,
        val key: String,
        val title: String,
        val subtitle: String? = null,
        val tags: List<String> = emptyList(),
        val route: String? = null,
        val webPath: String? = null,
    )

    fun encode(items: List<TaggedItem>): String = ApiJson.encodeToString(
        ListSerializer(Stored.serializer()),
        items.map { Stored(it.moduleId, it.key, it.title, it.subtitle, it.tags.toList(), it.route, it.webPath) },
    )

    fun decode(json: String): List<TaggedItem> =
        ApiJson.decodeFromString(ListSerializer(Stored.serializer()), json)
            .map { TaggedItem(it.moduleId, it.key, it.title, it.subtitle, it.tags.toSet(), it.route, it.webPath) }
}
