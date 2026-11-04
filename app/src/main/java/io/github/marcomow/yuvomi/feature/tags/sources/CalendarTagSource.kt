package io.github.marcomow.yuvomi.feature.tags.sources

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import io.github.marcomow.yuvomi.feature.documents.DocumentRules
import io.github.marcomow.yuvomi.feature.calendar.CalendarApi
import io.github.marcomow.yuvomi.feature.calendar.CalendarEvent
import io.github.marcomow.yuvomi.feature.calendar.CalendarRoutes
import io.github.marcomow.yuvomi.feature.documents.Document
import io.github.marcomow.yuvomi.feature.documents.DocumentsApi
import io.github.marcomow.yuvomi.feature.documents.DocumentsRoutes
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.tags.Hashtags
import io.github.marcomow.yuvomi.feature.tags.TagSource
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import java.time.LocalDate

/**
 * Calendar's part of the Tags view (TAGS-R2): hashtags in an event's title, description and
 * location, for the events from one year back to one year ahead (`GET /calendar?from&to`). A
 * series is one item however many of its occurrences the window holds; it shows the next one.
 */
object CalendarTagSource : TagSource {
    override val moduleId = "calendar"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        val today = shell.time.today()
        val (from, to) = window(today)
        val events = CalendarApi.decodeEvents(CalendarApi(shell.connection.client).events(from.toString(), to.toString()))
        return items(events, today, SourceText.of(shell))
    }

    fun window(today: LocalDate): Pair<LocalDate, LocalDate> = today.minusYears(1) to today.plusYears(1)

    fun items(events: List<CalendarEvent>, today: LocalDate, text: SourceText): List<TaggedItem> =
        events.groupBy { it.id }.mapNotNull { (id, occurrences) ->
            val first = occurrences.first()
            val tags = Hashtags.extract(first.title, first.description, first.location)
            if (tags.isEmpty()) return@mapNotNull null
            val dates = occurrences.mapNotNull { SourceText.parse(it.startDatetime) }.sorted()
            val shown = dates.firstOrNull { !it.isBefore(today) } ?: dates.lastOrNull()
            TaggedItem(moduleId, "event:$id", first.title, text.join(shown?.let { text.date(it.toString()) }, first.location), tags, CalendarRoutes.event(id))
        }
}

/** Documents' part of the Tags view (TAGS-R2): hashtags in a document's name and description. */
object DocumentsTagSource : TagSource {
    override val moduleId = "documents"

    override suspend fun load(shell: ShellContext): List<TaggedItem> {
        if (!shell.shows(moduleId)) return emptyList()
        // Active and archived documents; archived ones are [TaggedItem.done] (TAGS-R14).
        val api = DocumentsApi(shell.connection.client)
        return coroutineScope {
            val active = async { DocumentsApi.decodeDocuments(api.list(DocumentRules.STATUS_ACTIVE)) }
            val archived = async { DocumentsApi.decodeDocuments(api.list(DocumentRules.STATUS_ARCHIVED)) }
            items(active.await() + archived.await())
        }
    }

    fun items(documents: List<Document>): List<TaggedItem> = documents.mapNotNull { doc ->
        val tags = Hashtags.extract(doc.name, doc.description)
        if (tags.isEmpty()) null else TaggedItem(moduleId, "document:${doc.id}", doc.name, doc.folderName, tags, DocumentsRoutes.document(doc.id), done = doc.archived)
    }
}
