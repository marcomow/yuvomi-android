package io.github.marcomow.yuvomi.feature.birthdays

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/** Birthdays in the Tags view (docs/spec/tags.md): a `#hashtag` in a birthday's name or notes. */
object BirthdaysTagSource : TagSource {
    override val moduleId = "birthdays"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val client = shell.container.session.connection?.client ?: return emptyList()
        return items(BirthdaysApi.decodeList(BirthdaysApi(client).list()))
    }

    /** One item per birthday with at least one tag; it opens the birthday's sheet. */
    fun items(list: List<Birthday>): List<TaggedItem> = list.mapNotNull { b ->
        val tags = Hashtags.extract(b.name, b.notes)
        if (tags.isEmpty()) {
            null
        } else {
            TaggedItem(
                moduleId = moduleId,
                key = "birthday:${b.id}",
                title = b.name,
                subtitle = b.notes,
                tags = tags,
                route = BirthdaysRoutes.birthday(b.id),
            )
        }
    }
}
