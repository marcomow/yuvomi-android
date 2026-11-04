package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.text.Normalizer

/**
 * One thing that carries tags, as the Tags view lists it (docs/spec/tags.md). Tags are the
 * real ones upstream stores (tasks, shopping items) and the `#hashtags` in the item's own
 * text ([Hashtags]).
 */
data class TaggedItem(
    /** The [io.github.marcomow.yuvomi.core.design.ModuleCatalog] id: icon, tone and group label. */
    val moduleId: String,
    /** Unique within the module, e.g. "note:12"; the list key. */
    val key: String,
    val title: String,
    val subtitle: String? = null,
    /** Tags as written (display form); compared through [TagKey]. */
    val tags: Set<String>,
    /** The native route that opens the item, or null when [webPath] opens it in the browser. */
    val route: String?,
    val webPath: String? = null,
    /** Completed (a done task, a checked item); hidden unless the member shows them (TAGS-R14). */
    val done: Boolean = false,
    /** Archived by its module's own means (an archived document, a task upstream archived). */
    val archivedNatively: Boolean = false,
) {
    /** Archived: natively, or by the reserved tag `#archived` ([Archived]). */
    val archived: Boolean get() = archivedNatively || Archived.inTags(tags)

    /** Hidden while completed and archived items are hidden (TAGS-R14). */
    val finished: Boolean get() = done || archived
}

/**
 * A module's contribution to the Tags view. Each module implements one in its own package;
 * [TagSources.all] lists them. [load] returns every item the signed-in member may see that
 * carries at least one tag, reading the same endpoints (and permissions) the module itself
 * uses, and throws on a network or server failure so the view can say which module is missing.
 */
interface TagSource {
    val moduleId: String
    suspend fun load(shell: ShellContext): List<TaggedItem>
}

/** Upstream's `tagKey` (server/utils/task-tags.js): NFC, then lower case. Two tags are one when their keys match. */
object TagKey {
    fun of(tag: String): String = Normalizer.normalize(tag, Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
}
