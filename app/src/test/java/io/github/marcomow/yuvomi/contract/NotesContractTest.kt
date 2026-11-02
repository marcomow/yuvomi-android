package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.notes.Note
import io.github.marcomow.yuvomi.feature.notes.NoteBodies
import io.github.marcomow.yuvomi.feature.notes.NoteCategory
import io.github.marcomow.yuvomi.feature.notes.NoteForm
import io.github.marcomow.yuvomi.feature.notes.NoteMarkdown
import io.github.marcomow.yuvomi.feature.notes.NotesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/spec/notes.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Every test makes its own notes and categories with unique names and removes them again.
 */
class NotesContractTest : ContractTestBase() {
    private class Member(val client: YuvomiClient, val id: Int) {
        val notes = NotesApi(client)
    }

    private fun member(username: String) = signedIn(username).let { (c, me) -> Member(c, me.user.id) }

    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    private suspend fun Member.create(form: NoteForm, visibility: Boolean = true, lock: Boolean = true): Note =
        notes.create(NoteBodies.body(form, visibility, lock), YuvomiClient.newIdempotencyKey())

    private suspend fun Member.cleanup(vararg ids: Int) = ids.forEach { runCatching { notes.delete(it) } }

    // ── GET /notes, GET /notes/categories ──

    /** NOTES-A1. */
    @Test fun `NOTES-A1 the seeded board decodes with pinned notes first`() = runBlocking {
        val notes = NotesApi.decodeNotes(member("alex").notes.list())
        assertTrue(notes.size >= 8)
        assertEquals(notes.sortedByDescending { it.pinned }.map { it.id }, notes.map { it.id })
        assertTrue(notes.all { !it.creatorName.isNullOrEmpty() })
        assertTrue(notes.any { n -> NoteMarkdown.parse(n.content).any { it is NoteMarkdown.Check } })
    }

    /** NOTES-A2: the household catalogue is managed by admins only, unless granted. */
    @Test fun `NOTES-A2 categories answer with the manage flag`() = runBlocking {
        assertTrue(NotesApi.decodeCategories(member("alex").notes.categories()).canManageHousehold)
        assertFalse(NotesApi.decodeCategories(member("emma").notes.categories()).canManageHousehold)
    }

    // ── POST /notes, PUT /notes/{id}, DELETE /notes/{id} ──

    /** NOTES-A6. */
    @Test fun `NOTES-A6 create and edit send the editor's body and answer the whole note`() = runBlocking {
        val alex = member("alex")
        val category = alex.notes.createCategory(unique("Cat"), NoteCategory.SCOPE_PERSONAL, YuvomiClient.newIdempotencyKey())
        val title = unique("Note")
        val created = alex.create(NoteForm(title = title, content = "- [ ] one\n- [ ] two", categoryIds = listOf(category.id), color = "#C7DED9", pinned = true))
        try {
            assertEquals(title, created.title)
            assertTrue(created.pinned)
            assertEquals("#C7DED9", created.color)
            assertEquals(alex.id, created.createdBy)
            assertEquals(listOf(category.id), created.categories.map { it.id })
            assertTrue(NotesApi.decodeNotes(alex.notes.list()).any { it.id == created.id })

            val form = NoteForm.of(created).copy(title = "", content = "  changed  ", categoryIds = emptyList(), pinned = false, visibility = Note.VISIBILITY_PRIVATE, locked = true)
            val edited = alex.notes.update(created.id, NoteBodies.body(form, sendVisibility = true, sendLock = true))
            assertNull(edited.title)
            assertEquals("changed", edited.content)
            assertFalse(edited.pinned)
            assertTrue(edited.isPrivate)
            assertTrue(edited.locked)
            assertTrue(edited.categories.isEmpty())
        } finally {
            alex.cleanup(created.id)
            runCatching { alex.notes.deleteCategory(category.id) }
        }
    }

    /** NOTES-A17 (as TASKS-A17): a retried create with the same key makes one note. */
    @Test fun `NOTES-A17 a retried create reuses its idempotency key`() = runBlocking {
        val alex = member("alex")
        val body = NoteBodies.body(NoteForm(title = unique("Once"), content = "x"), sendVisibility = true, sendLock = true)
        val key = YuvomiClient.newIdempotencyKey()
        val first = alex.notes.create(body, key)
        val second = alex.notes.create(body, key)
        try {
            assertEquals(first.id, second.id)
        } finally {
            alex.cleanup(first.id, second.id)
        }
    }

    @Test fun `NOTES-A6 a note needs text`() {
        val alex = member("alex")
        expectHttp(400) { alex.create(NoteForm(title = unique("Empty"), content = "  ")) }
    }

    /** NOTES-A7: deleting twice is "already deleted", which the app counts as done. */
    @Test fun `NOTES-A7 delete answers 204, then notes_note_not_found`() = runBlocking {
        val alex = member("alex")
        val note = alex.create(NoteForm(content = unique("Delete me")))
        alex.notes.delete(note.id)
        expectHttp(404, "notes_note_not_found") { alex.notes.delete(note.id) }
        assertFalse(NotesApi.decodeNotes(alex.notes.list()).any { it.id == note.id })
    }

    /** NOTES-A8: without `notes_edit_others` a child reads but cannot change someone else's note. */
    @Test fun `NOTES-A8 a child cannot edit, pin or delete a parent's note but can tick it`() = runBlocking {
        val alex = member("alex")
        val emma = member("emma")
        val note = alex.create(NoteForm(content = "- [ ] a\n- [ ] b"))
        try {
            expectHttp(403, "not_owner") { emma.notes.update(note.id, buildJsonObject { put("content", "mine") }) }
            expectHttp(403, "not_owner") { emma.notes.pin(note.id) }
            expectHttp(403, "not_owner") { emma.notes.delete(note.id) }
            assertEquals("- [x] a\n- [ ] b", emma.notes.check(note.id, 0, true, "- [ ] a").content)
        } finally {
            alex.cleanup(note.id)
        }
    }

    /** NOTES-A9: a locked note is closed to everyone but its creator and admins, ticks included; visibility is the creator's alone. */
    @Test fun `NOTES-A9 lock and visibility`() = runBlocking {
        val linda = member("linda")
        val alex = member("alex")
        val emma = member("emma")
        val locked = linda.create(NoteForm(content = "- [ ] a", locked = true))
        val private = linda.create(NoteForm(content = unique("secret"), visibility = Note.VISIBILITY_PRIVATE))
        try {
            expectHttp(403, "note_locked") { emma.notes.check(locked.id, 0, true, "- [ ] a") }
            assertEquals("- [x] a", alex.notes.check(locked.id, 0, true, "- [ ] a").content)
            // An admin may not make someone else's note private.
            expectHttp(403, "not_owner") { alex.notes.update(locked.id, buildJsonObject { put("visibility", "private") }) }
            // Someone else's private note does not exist for anyone else.
            assertFalse(NotesApi.decodeNotes(alex.notes.list()).any { it.id == private.id })
            expectHttp(404, "notes_note_not_found") { alex.notes.pin(private.id) }
        } finally {
            linda.cleanup(locked.id, private.id)
        }
    }

    // ── PATCH /notes/{id}/pin ──

    /** NOTES-A4. */
    @Test fun `NOTES-A4 pin toggles and answers the new state`() = runBlocking {
        val alex = member("alex")
        val note = alex.create(NoteForm(content = unique("Pin me")))
        try {
            assertTrue(alex.notes.pin(note.id))
            assertEquals(note.id, NotesApi.decodeNotes(alex.notes.list()).first { it.pinned && it.id == note.id }.id)
            assertFalse(alex.notes.pin(note.id))
        } finally {
            alex.cleanup(note.id)
        }
    }

    // ── PATCH /notes/{id}/check ──

    /** NOTES-A5. */
    @Test fun `NOTES-A5 check ticks one line by index and refuses a stale one`() = runBlocking {
        val alex = member("alex")
        val note = alex.create(NoteForm(content = "# List\r\n- [ ] milk\r\n- [ ] bread"))
        try {
            val line = 2
            val expect = NoteMarkdown.expectAt(note.content, line)!!
            val ticked = alex.notes.check(note.id, line, true, expect)
            assertEquals("# List\r\n- [ ] milk\r\n- [x] bread", ticked.content)
            assertEquals(NoteMarkdown.toggle(note.content, line, true, expect), ticked.content)
            // The same tick again is no error (both agree).
            assertEquals(ticked.content, alex.notes.check(note.id, line, true, "- [x] bread").content)
            expectHttp(409, "stale") { alex.notes.check(note.id, line, false, expect) }
            expectHttp(409, "not_a_checklist_line") { alex.notes.check(note.id, 0, true, "# List") }
        } finally {
            alex.cleanup(note.id)
        }
    }

    // ── Categories ──

    /** NOTES-A12. */
    @Test fun `NOTES-A12 categories are created per scope and unique by name`() = runBlocking {
        val alex = member("alex")
        val emma = member("emma")
        val name = unique("Garden")
        val personal = alex.notes.createCategory(name, NoteCategory.SCOPE_PERSONAL, YuvomiClient.newIdempotencyKey())
        val household = alex.notes.createCategory(name, NoteCategory.SCOPE_HOUSEHOLD, YuvomiClient.newIdempotencyKey())
        try {
            assertTrue(personal.isPersonal)
            assertEquals(alex.id, personal.ownerUserId)
            assertFalse(household.isPersonal)
            expectHttp(409, "category_exists") { alex.notes.createCategory(name.uppercase(), NoteCategory.SCOPE_PERSONAL, YuvomiClient.newIdempotencyKey()) }
            expectHttp(403) { emma.notes.createCategory(unique("Kid"), NoteCategory.SCOPE_HOUSEHOLD, YuvomiClient.newIdempotencyKey()) }
            // Someone else's personal category does not exist for them.
            assertFalse(NotesApi.decodeCategories(emma.notes.categories()).categories.any { it.id == personal.id })
        } finally {
            runCatching { alex.notes.deleteCategory(personal.id) }
            runCatching { alex.notes.deleteCategory(household.id) }
        }
    }

    /** NOTES-A13: rename, reorder within one scope, delete; a deleted category leaves its notes. */
    @Test fun `NOTES-A13 rename, reorder and delete categories`() = runBlocking {
        val alex = member("alex")
        val a = alex.notes.createCategory(unique("A"), NoteCategory.SCOPE_PERSONAL, YuvomiClient.newIdempotencyKey())
        val b = alex.notes.createCategory(unique("B"), NoteCategory.SCOPE_PERSONAL, YuvomiClient.newIdempotencyKey())
        val note = alex.create(NoteForm(content = unique("Sorted"), categoryIds = listOf(a.id)))
        try {
            val newName = unique("Renamed")
            assertEquals(newName, alex.notes.renameCategory(a.id, "  $newName ").name)
            expectHttp(409, "category_exists") { alex.notes.renameCategory(a.id, b.name) }

            val personal = NotesApi.decodeCategories(alex.notes.categories()).categories.filter { it.isPersonal }.map { it.id }
            val reversed = personal.reversed()
            val answer = alex.notes.reorderCategories(reversed, NoteCategory.SCOPE_PERSONAL)
            assertEquals(reversed, answer.categories.filter { it.isPersonal }.map { it.id })
            expectHttp(400) { alex.notes.reorderCategories(listOf(a.id, a.id), NoteCategory.SCOPE_PERSONAL) }

            alex.notes.deleteCategory(a.id)
            assertTrue(NotesApi.decodeNotes(alex.notes.list()).first { it.id == note.id }.categories.isEmpty())
            expectHttp(404) { alex.notes.deleteCategory(a.id) }
            // A note saved with a category that is gone is refused with its own reason.
            expectHttp(400, "notes_selected_category_not_found") {
                alex.notes.update(note.id, NoteBodies.body(NoteForm.of(note).copy(categoryIds = listOf(a.id)), sendVisibility = true, sendLock = true))
            }
        } finally {
            alex.cleanup(note.id)
            runCatching { alex.notes.deleteCategory(a.id) }
            runCatching { alex.notes.deleteCategory(b.id) }
        }
    }
}
