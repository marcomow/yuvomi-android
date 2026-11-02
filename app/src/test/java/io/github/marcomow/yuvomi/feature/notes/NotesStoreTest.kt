package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.core.api.CsrfStore
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/notes.md: the shared store and the editor's view model against a scripted server. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesStoreTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val responses = HashMap<String, MockResponse>()
    private lateinit var store: NotesStore
    private val strings = Strings("en", mapOf("common.undo" to "Undo", "common.deletedNamed" to "“{{name}}” deleted."), emptyMap()) { "other" }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val note1 = """{"id":1,"title":"List","content":"- [ ] Milk\n- [ ] Bread","pinned":0,"created_by":1,"categories":[{"id":4,"name":"Home","scope":"household"}]}"""
    private val note2 = """{"id":2,"title":"Other","content":"x","pinned":1,"created_by":2,"categories":[]}"""

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        responses["GET /api/v1/notes"] = json("""{"data":[$note2,$note1]}""")
        responses["GET /api/v1/notes/categories"] = json(
            """{"data":[{"id":4,"name":"Home","scope":"household","sort_order":0},{"id":5,"name":"Work","scope":"household","sort_order":1}],"meta":{"can_manage_household":true}}""",
        )
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.url.encodedPath}"
                requests += "$key ${request.body?.utf8().orEmpty()} key=${request.headers["Idempotency-Key"]}"
                return responses[key] ?: json("""{"error":"Not found"}""", 404)
            }
        }
        server.start()
        val client = YuvomiClient("http://localhost:${server.port}", OkHttpClient(), CsrfStore())
        val cache = ResponseCache(Files.createTempDirectory("notes").toFile())
        store = NotesStore(cache, NotesApi(client), CoroutineScope(Dispatchers.Unconfined))
        runBlocking { assertTrue(store.load()) }
    }

    @After fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun sent(prefix: String) = requests.filter { it.startsWith(prefix) }

    /** NOTES-A5, NOTES-R16: a 409 rolls the tick back, reloads and says the note changed. */
    @Test fun `check conflict reloads`() = runBlocking {
        responses["PATCH /api/v1/notes/1/check"] = json("""{"error":"changed","code":409,"reason":"stale"}""", 409)
        val before = requests.count { it.startsWith("GET /api/v1/notes ") }
        assertEquals("notes.checkConflict", store.check(1, 1, true))
        assertTrue(sent("PATCH /api/v1/notes/1/check").single().contains("\"expect\":\"- [ ] Bread\""))
        assertEquals(before + 1, requests.count { it.startsWith("GET /api/v1/notes ") })
        assertEquals("- [ ] Milk\n- [ ] Bread", store.state.value.note(1)!!.content)
    }

    @Test fun `check success keeps the server's text, and a gone note leaves the board`() = runBlocking {
        responses["PATCH /api/v1/notes/1/check"] = json("""{"data":${note1.replace("- [ ] Milk", "- [x] Milk")}}""")
        assertNull(store.check(1, 0, true))
        assertEquals("- [x] Milk\n- [ ] Bread", store.state.value.note(1)!!.content)

        responses["PATCH /api/v1/notes/2/pin"] = json("""{"error":"Note not found.","code":404,"reason":"notes_note_not_found"}""", 404)
        assertEquals("notes.errorNoteGone", store.togglePin(store.state.value.note(2)!!))
        assertNull(store.state.value.note(2))
    }

    /** NOTES-A4: pinning answers the new state and the board keeps pinned notes first. */
    @Test fun `pin toggles and sorts`() = runBlocking {
        responses["PATCH /api/v1/notes/1/pin"] = json("""{"data":{"id":1,"pinned":1}}""")
        assertNull(store.togglePin(store.state.value.note(1)!!))
        assertTrue(store.state.value.note(1)!!.pinned)
        assertEquals(listOf(2, 1), store.state.value.shown.map { it.id })
    }

    /** NOTES-A7: no undo deletes on the server; "already deleted" counts as done. */
    @Test fun `delete without undo deletes, and a gone note is no error`() = runBlocking {
        responses["DELETE /api/v1/notes/1"] = json("""{"error":"Note not found.","code":404,"reason":"notes_note_not_found"}""", 404)
        val messages = mutableListOf<String>()
        store.scheduleDelete(store.state.value.note(1)!!, strings) { m, _ -> messages += m; false }
        withTimeout(5_000) { store.state.first { s -> s.notes.none { it.id == 1 } } }
        assertEquals(listOf("“List” deleted."), messages)
        assertEquals(1, sent("DELETE /api/v1/notes/1").size)
        assertTrue(store.state.value.pendingDeletes.isEmpty())
    }

    @Test fun `undo keeps the note and sends nothing`() = runBlocking {
        store.scheduleDelete(store.state.value.note(2)!!, strings) { _, _ -> true }
        withTimeout(5_000) { store.state.first { it.pendingDeletes.isEmpty() } }
        assertTrue(sent("DELETE").isEmpty())
        assertEquals(setOf(1, 2), store.state.value.shown.map { it.id }.toSet())
    }

    /** NOTES-A13: reorder sends the new order of one scope; delete drops the category from notes. */
    @Test fun `category move and delete`() = runBlocking {
        responses["PATCH /api/v1/notes/categories/reorder"] = json(
            """{"data":[{"id":5,"name":"Work","scope":"household","sort_order":0},{"id":4,"name":"Home","scope":"household","sort_order":1}],"meta":{"can_manage_household":true}}""",
        )
        val home = store.state.value.categories.first { it.id == 4 }
        assertNull(store.moveCategory(home, 1))
        assertTrue(sent("PATCH /api/v1/notes/categories/reorder").single().contains("{\"order\":[5,4],\"scope\":\"household\"}"))
        assertNull(store.moveCategory(home, -5))

        responses["DELETE /api/v1/notes/categories/4"] = MockResponse.Builder().code(204).build()
        responses["GET /api/v1/notes/categories"] = json("""{"data":[{"id":5,"name":"Work","scope":"household"}],"meta":{"can_manage_household":true}}""")
        responses["GET /api/v1/notes"] = json("""{"data":[${note1.replace(""""categories":[{"id":4,"name":"Home","scope":"household"}]""", """"categories":[]""")}]}""")
        store.setFilter(BoardFilter(categoryIds = listOf(4)))
        assertNull(store.deleteCategory(home))
        assertTrue(store.state.value.note(1)!!.categories.isEmpty())
        assertEquals(emptyList<Int>(), store.state.value.filter.categoryIds)

        responses["PUT /api/v1/notes/categories/5"] = json("""{"error":"exists","code":409,"reason":"category_exists"}""", 409)
        assertEquals("category.errorExists", store.renameCategory(store.state.value.categories.single(), "Home"))
    }

    /** NOTES-A6, NOTES-R20: a chosen category that is gone keeps the editor open and drops it from the choice. */
    @Test fun `editor saves, and a gone category is dropped from the choice`() = runBlocking {
        responses["PUT /api/v1/notes/1"] = json("""{"error":"x","code":400,"reason":"notes_selected_category_not_found"}""", 400)
        responses["GET /api/v1/notes/categories"] = json("""{"data":[{"id":5,"name":"Work","scope":"household"}],"meta":{"can_manage_household":true}}""")
        val vm = NoteEditorViewModel(store, 1)
        withTimeout(5_000) { vm.state.first { it.ready } }
        assertEquals(listOf(4), vm.state.value.form.categoryIds)
        vm.edit { it.copy(title = "Groceries") }
        vm.save(sendVisibility = true, sendLock = true)
        withTimeout(5_000) { vm.state.first { !it.saving && it.messageKey != null } }
        assertEquals("notes.errorSelectedCategoryGone", vm.state.value.messageKey)
        assertEquals(emptyList<Int>(), vm.state.value.form.categoryIds)
        assertNull(vm.state.value.outcome)
        val body = sent("PUT /api/v1/notes/1").single()
        assertTrue(body, body.contains("\"title\":\"Groceries\"") && body.contains("\"category_ids\":[4]") && body.contains("\"visibility\":\"all\""))

        responses["PUT /api/v1/notes/1"] = json("""{"data":${note1.replace("\"List\"", "\"Groceries\"")}}""")
        vm.save(sendVisibility = true, sendLock = true)
        withTimeout(5_000) { vm.state.first { it.outcome != null } }
        assertEquals(NoteEditorViewModel.Outcome.SAVED, vm.state.value.outcome)
        assertEquals("Groceries", store.state.value.note(1)!!.title)
    }

    @Test fun `a new note needs text, goes first, and a retry reuses its key`() = runBlocking {
        val vm = NoteEditorViewModel(store, null)
        vm.save(sendVisibility = true, sendLock = true)
        assertTrue(vm.state.value.contentMissing)
        assertTrue(sent("POST").isEmpty())

        responses["POST /api/v1/notes"] = json("""{"error":"x","code":500}""", 500)
        vm.setContent(androidx.compose.ui.text.input.TextFieldValue("Call the plumber"))
        vm.save(sendVisibility = true, sendLock = true)
        withTimeout(5_000) { vm.state.first { !it.saving && it.messageKey != null } }
        assertEquals("common.errorGeneric", vm.state.value.messageKey)

        responses["POST /api/v1/notes"] = json("""{"data":{"id":9,"title":null,"content":"Call the plumber","pinned":0,"created_by":1}}""", 201)
        vm.save(sendVisibility = true, sendLock = true)
        withTimeout(5_000) { vm.state.first { it.outcome != null } }
        assertEquals(NoteEditorViewModel.Outcome.CREATED, vm.state.value.outcome)
        assertEquals(9, store.state.value.shown.first().id)
        val keys = requests.filter { it.startsWith("POST /api/v1/notes ") }.map { it.substringAfter("key=") }
        assertEquals(2, keys.size)
        assertEquals(1, keys.toSet().size)
        assertFalse(keys.first() == "null")
        assertFalse(store.state.value.offline)
    }

    /** NOTES-R22: "create and assign" picks an existing category of that name instead of a duplicate. */
    @Test fun `creating a category that exists selects it`() = runBlocking {
        val vm = NoteEditorViewModel(store, null)
        vm.setCreationScope(NoteCategory.SCOPE_HOUSEHOLD)
        vm.setCategoryQuery("home")
        assertNull(vm.createCategory(canManageHousehold = true))
        assertEquals(listOf(4), vm.state.value.form.categoryIds)
        assertTrue(sent("POST /api/v1/notes/categories").isEmpty())

        responses["POST /api/v1/notes/categories"] = json("""{"data":{"id":7,"name":"Garden","scope":"personal","owner_user_id":1,"sort_order":0}}""", 201)
        vm.setCategoryQuery("Garden ")
        assertNull(vm.createCategory(canManageHousehold = false))
        assertTrue(sent("POST /api/v1/notes/categories").single().contains("{\"name\":\"Garden\",\"scope\":\"personal\"}"))
        assertEquals(listOf(4, 7), vm.state.value.form.categoryIds)
        assertTrue(store.state.value.categories.any { it.id == 7 })
    }
}
