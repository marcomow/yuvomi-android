package io.github.marcomow.yuvomi.feature.contacts

import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem

/**
 * Contacts in the Tags view (docs/spec/contacts.md, CONT-R21): a `#hashtag` in a contact's
 * name or notes is a tag. Reads `GET /contacts` like the list does.
 */
object ContactsTagSource : TagSource {
    override val moduleId = "contacts"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val client = shell.connection.client
        return items(ContactsApi.decodeContacts(ContactsApi(client).list()))
    }

    /** The tagged contacts, each opening its read view. */
    fun items(contacts: List<Contact>): List<TaggedItem> = contacts.mapNotNull { c ->
        val tags = Hashtags.extract(c.name, c.notes)
        if (tags.isEmpty()) return@mapNotNull null
        TaggedItem(
            moduleId = moduleId,
            key = "contact:${c.id}",
            title = c.name,
            subtitle = c.phone?.takeIf { it.isNotBlank() } ?: c.email?.takeIf { it.isNotBlank() },
            tags = tags,
            route = ContactsRoutes.contact(c.id),
        )
    }
}
