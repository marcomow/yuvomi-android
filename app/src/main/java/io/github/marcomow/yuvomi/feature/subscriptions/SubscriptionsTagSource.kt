package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Subscriptions in the Tags view (docs/spec/subscriptions.md, SUBS-R33): a `#hashtag` in a
 * subscription's name, description or notes counts as a tag. Reads the unfiltered list the
 * tab reads; nothing when the member does not see Budget. Each item opens its read view.
 */
object SubscriptionsTagSource : TagSource {
    override val moduleId = "budget"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val connection = shell.container.session.connection ?: return emptyList()
        val list = SubscriptionsApi.decodeList(SubscriptionsApi(connection.client).list(SubscriptionQuery()))
        return items(list.subscriptions)
    }

    fun items(rows: List<Subscription>): List<TaggedItem> = rows.mapNotNull { s ->
        val tags = Hashtags.extract(s.name, s.description, s.notes)
        if (tags.isEmpty()) null
        else TaggedItem(
            moduleId = moduleId,
            key = "subscription:${s.id}",
            title = s.name,
            subtitle = s.description?.takeIf { it.isNotBlank() },
            tags = tags,
            route = SubscriptionsRoutes.subscription(s.id),
        )
    }
}
