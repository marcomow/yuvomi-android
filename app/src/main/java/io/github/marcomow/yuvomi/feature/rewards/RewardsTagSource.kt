package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Rewards in the Tags view (docs/spec/tags.md): a `#hashtag` in a reward's name or description.
 * The catalogue as the tab loads it: parents see inactive rewards too (`?all=1`).
 */
object RewardsTagSource : TagSource {
    override val moduleId = "rewards"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val client = shell.container.session.connection?.client ?: return emptyList()
        val admin = shell.session.isAdmin || shell.session.user.role == "admin"
        return items(RewardsApi(client).catalog(all = admin))
    }

    /** One item per reward with at least one tag; it opens the catalogue tab. */
    fun items(catalog: List<RewardItem>): List<TaggedItem> = catalog.mapNotNull { item ->
        val tags = Hashtags.extract(item.name, item.description)
        if (tags.isEmpty()) {
            null
        } else {
            TaggedItem(
                moduleId = moduleId,
                key = "reward:${item.id}",
                title = listOfNotNull(item.icon, item.name).joinToString(" "),
                subtitle = item.description,
                tags = tags,
                route = RewardsRoutes.catalog(),
            )
        }
    }
}
