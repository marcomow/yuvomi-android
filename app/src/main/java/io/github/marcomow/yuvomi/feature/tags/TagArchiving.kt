package io.github.marcomow.yuvomi.feature.tags

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.notes.NotesApi
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.shopping.ShoppingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * TAGS-R16: archiving a tag archives what carries it, with the shared tag `#archived`
 * ([Archived]): its tasks get the real tag `archived`, its notes and shopping items `#archived`
 * in their text. Restoring takes it off again. Items of other modules cannot be written here and
 * are counted as skipped; a tag with such an unfinished item is not archived (TAGS-R15).
 */
object TagArchiving {
    data class Result(val changed: Int = 0, val failed: Int = 0, val skipped: Int = 0)

    /** Which of [items] an archive (or restore) would write, by module. */
    fun targets(items: List<TaggedItem>, archive: Boolean): List<TaggedItem> =
        items.filter { if (archive) !it.archived else Archived.inTags(it.tags) }

    fun idOf(item: TaggedItem): Int? = item.key.substringAfter(':').toIntOrNull()

    /** The shopping list of an item, from its route (`shopping?list=<id>&highlight=<id>`). */
    fun listOf(item: TaggedItem): Int? = item.route?.substringAfter("list=", "")?.substringBefore('&')?.toIntOrNull()

    suspend fun apply(shell: ShellContext, carrying: List<TaggedItem>, archive: Boolean): Result {
        val targets = targets(carrying, archive)
        var changed = 0
        var failed = 0
        var skipped = 0
        val client = shell.connection.client
        val notes by lazy { NotesApi(client) }
        val shopping by lazy { ShoppingApi(client) }
        var noteList: List<io.github.marcomow.yuvomi.feature.notes.Note>? = null
        val itemsByList = HashMap<Int, List<io.github.marcomow.yuvomi.feature.shopping.ShoppingItem>>()
        for (item in targets) {
            val id = idOf(item)
            try {
                when {
                    id == null -> skipped++
                    item.moduleId == "tasks" && item.key.startsWith("task:") -> {
                        val task = shell.connection.tasks.get(id)
                        val tags = Archived.tags(task.tags, archive)
                        if (tags != task.tags) {
                            shell.connection.tasks.update(id, buildJsonObject { put("tags", JsonArray(tags.map(::JsonPrimitive))) })
                            changed++
                        }
                    }
                    item.moduleId == "notes" && item.key.startsWith("note:") -> {
                        val all = noteList ?: NotesApi.decodeNotes(notes.list()).also { noteList = it }
                        val note = all.firstOrNull { it.id == id } ?: run { failed++; null }
                        if (note != null) {
                            val content = Archived.text(note.content, archive)
                            // Restoring also clears a `#archived` someone wrote in the title.
                            val title = if (!archive && note.title != null) Archived.text(note.title, false) else note.title
                            notes.update(id, buildJsonObject {
                                put("content", content)
                                if (title != note.title) put("title", title)
                            })
                            changed++
                        }
                    }
                    item.moduleId == "shopping" && item.key.startsWith("item:") -> {
                        val listId = listOf(item) ?: run { skipped++; null }
                        if (listId != null) {
                            val rows = itemsByList.getOrPut(listId) { ShoppingApi.decodeItems(shopping.items(listId)).data }
                            val row = rows.firstOrNull { it.id == id } ?: run { failed++; null }
                            if (row != null) {
                                shopping.updateItem(id, buildJsonObject { put("notes", Archived.text(row.notes, archive)) })
                                changed++
                            }
                        }
                    }
                    else -> skipped++
                }
            } catch (e: ApiProblem) {
                failed++
            }
        }
        return Result(changed, failed, skipped)
    }

    /** The snackbar sentence for [r]. */
    fun message(r: Result, label: String, archive: Boolean, strings: Strings): String {
        val head = strings.t(if (archive) "android.tags.archivedItems" else "android.tags.restoredItems", "tag" to label, "count" to r.changed)
        val tail = listOfNotNull(
            r.failed.takeIf { it > 0 }?.let { strings.t("android.tags.archiveFailed", "count" to it) },
            r.skipped.takeIf { it > 0 }?.let { strings.t("android.tags.archiveSkipped", "count" to it) },
        )
        return (listOf(head) + tail).joinToString(" ")
    }
}
