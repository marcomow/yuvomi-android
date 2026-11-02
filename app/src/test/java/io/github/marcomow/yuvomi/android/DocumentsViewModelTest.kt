package io.github.marcomow.yuvomi.android

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.documents.DocumentForm
import io.github.marcomow.yuvomi.feature.documents.DocumentsHost
import io.github.marcomow.yuvomi.feature.documents.DocumentsViewModel
import io.github.marcomow.yuvomi.feature.documents.FolderDeleteChoice
import io.github.marcomow.yuvomi.feature.documents.Message
import io.github.marcomow.yuvomi.feature.documents.PickedFile
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * docs/spec/documents.md: the dialogs' logic through the view model (a text field in a dialog
 * never lets Compose go idle under Robolectric), against a scripted server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class)
class DocumentsViewModelTest {
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private val notices = CopyOnWriteArrayList<String>()
    private lateinit var strings: Strings
    private lateinit var container: AppContainer
    private var folderGone = false

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload = """{"user":{"id":1,"username":"alex","display_name":"Alex","role":"admin"},"permissions":{"admin":true},"householdSize":3,"csrfToken":"t"}"""
    private val lease = """{"id":1,"name":"Lease","category":"home","status":"active","visibility":"family","original_name":"lease.pdf",
        "mime_type":"application/pdf","file_size":320000,"folder_id":1,"folder_name":"Home","created_by":1,"updated_at":"2026-10-30 08:00:00"}"""
    private val old = """{"id":7,"name":"Old passport","category":"identity","status":"archived","visibility":"family","created_by":1,"updated_at":"2020-01-01 08:00:00"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") {
                    writes += "$line?${request.url.query.orEmpty()} ${request.body?.utf8().orEmpty()}"
                    request.headers["Idempotency-Key"]?.let { keys += it }
                }
                return when (line) {
                    "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    "GET /api/v1/auth/me" -> json(payload)
                    "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC"}}""")
                    "GET /api/v1/family/members", "GET /api/v1/auth/users" -> json("""{"data":[{"id":1,"display_name":"Alex"}]}""")
                    "GET /api/v1/documents/meta/options" -> json("""{"data":{"max_file_size":10,"is_admin":true,"dms_accounts":[]}}""")
                    "GET /api/v1/documents/folders" -> json("""{"data":[{"id":1,"name":"Home","parent_id":null},{"id":2,"name":"Rent","parent_id":1}]}""")
                    "GET /api/v1/documents" -> json(if (request.url.queryParameter("status") == "archived") """{"data":[$old]}""" else """{"data":[$lease]}""")
                    "POST /api/v1/documents" -> json("""{"data":${lease.replace("\"id\":1", "\"id\":9")}}""", code = 201)
                    "PUT /api/v1/documents/1" -> if (folderGone) {
                        json("""{"error":"Folder not found.","code":400,"reason":"documents_selected_folder_not_found"}""", code = 400)
                    } else {
                        json("""{"data":$lease}""")
                    }
                    "GET /api/v1/documents/folders/1/delete-impact" ->
                        json("""{"data":{"id":1,"removed_folders":2,"documents":1,"can_delete_documents":true,"linked_records":{"tasks":0},"snapshot":"${"a".repeat(64)}"}}""")
                    "DELETE /api/v1/documents/folders/1" -> json("""{"data":{"id":1,"removed_folders":2,"unfiled_documents":1,"deleted_documents":0,"failed_documents":[],"folder_deleted":true}}""")
                    else -> json("""{"error":"Not found"}""", code = 404)
                }
            }
        }
        server.start()
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
    }

    @After fun tearDown() = server.close()

    private val host get() = object : DocumentsHost {
        override val strings: Strings get() = this@DocumentsViewModelTest.strings
        override val userId = 1
        override val isAdmin = true
        override val canWrite = true
        override fun today(): LocalDate = LocalDate.of(2026, 11, 2)
        override suspend fun notify(message: String, action: String?): Boolean {
            notices += message
            return false
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $what; writes=$writes notices=$notices")
    }

    private fun loaded(openId: Int? = null) = DocumentsViewModel(container, host, openId).also { vm ->
        waitFor("list") { !vm.state.value.loading && vm.state.value.documents.isNotEmpty() }
    }

    /** DOCS-A8: one POST per file, with the dialog's idempotency key, the data URL and the form. */
    @Test fun `upload sends the file as a data URL with its idempotency key`() {
        val vm = loaded()
        val file = PickedFile("list.txt", "text/plain", "hi".toByteArray())
        val result = runBlocking { vm.save(null, DocumentForm(name = "", folderId = 2), listOf(file), listOf("key-1")) }
        assertEquals(DocumentsViewModel.SaveResult.Saved, result)
        val post = writes.single { it.startsWith("POST /api/v1/documents?") }
        assertTrue(post, post.contains("\"name\":\"list\"") && post.contains("\"original_name\":\"list.txt\"") && post.contains("\"folder_id\":2"))
        assertTrue(post, post.contains("\"content_data\":\"data:text/plain;base64,aGk=\""))
        assertEquals(listOf("key-1"), keys)
        assertTrue(notices.contains(strings.t("documents.uploadedToast")))

        // Larger than the server's `max_file_size` (10 bytes here): refused before sending.
        val big = PickedFile("big.txt", "text/plain", ByteArray(11))
        val refused = runBlocking { vm.save(null, DocumentForm(), listOf(big), listOf("key-2")) }
        assertEquals(DocumentsViewModel.SaveResult.Problem(Message("documents.fileTooLargeNamed", mapOf("name" to "big.txt"))), refused)
        val none = runBlocking { vm.save(null, DocumentForm(), emptyList(), emptyList()) }
        assertEquals(DocumentsViewModel.SaveResult.Problem(Message("documents.fileRequired")), none)
        assertEquals(1, writes.count { it.startsWith("POST /api/v1/documents?") })
    }

    /** DOCS-A8: editing sends the whole form; a chosen folder that went keeps the dialog open. */
    @Test fun `edit saves the form and a gone folder keeps the dialog open`() {
        val vm = loaded()
        val doc = vm.state.value.documents.single()
        val ok = runBlocking { vm.save(doc, DocumentForm.of(doc).copy(name = "Lease 2", expiresAt = "2027-01-01", reminderDays = "7"), emptyList(), emptyList()) }
        assertEquals(DocumentsViewModel.SaveResult.Saved, ok)
        val put = writes.single { it.startsWith("PUT /api/v1/documents/1") }
        assertTrue(put, put.contains("\"name\":\"Lease 2\"") && put.contains("\"expires_at\":\"2027-01-01\"") && put.contains("\"expiry_reminder_days\":7"))
        folderGone = true
        val gone = runBlocking { vm.save(doc, DocumentForm.of(doc), emptyList(), emptyList()) }
        assertEquals(DocumentsViewModel.SaveResult.Problem(Message("documents.errorSelectedFolderGone"), folderGone = true), gone)
        val badReminder = runBlocking { vm.save(doc, DocumentForm.of(doc).copy(expiresAt = "2027-01-01", reminderDays = "999"), emptyList(), emptyList()) }
        assertEquals(DocumentsViewModel.SaveResult.Problem(Message("android.documents.reminderRange")), badReminder)
        val blank = runBlocking { vm.save(doc, DocumentForm.of(doc).copy(name = "  "), emptyList(), emptyList()) }
        assertEquals(DocumentsViewModel.SaveResult.Problem(Message("common.required")), blank)
    }

    /** DOCS-A18: "About" is saved with the form, and the dialog names a person who cannot open the document. */
    @Test fun `the subject is saved and a person without access is named`() {
        val vm = loaded()
        assertTrue(vm.offersSubject)
        // Leo is the stored person but no longer a member: still a choice (`withChosenPeople`).
        val doc = vm.state.value.documents.single().copy(subjectUserId = 4, subjectName = "Leo")
        assertEquals(listOf(1, 4), vm.subjectPeople(doc).map { it.id })
        val form = DocumentForm.of(doc)
        assertEquals(4, form.subjectUserId)
        assertEquals(null, vm.subjectNoAccessName(doc, form))
        assertEquals("Leo", vm.subjectNoAccessName(doc, form.copy(visibility = "private")))
        assertEquals(null, vm.subjectNoAccessName(doc, form.copy(visibility = "restricted", allowedMemberIds = setOf(4))))
        assertEquals(null, vm.subjectNoAccessName(doc, form.copy(visibility = "private", subjectUserId = 1)))
        // A new upload: the member filing it may always open it.
        assertEquals(null, vm.subjectNoAccessName(null, DocumentForm(visibility = "private", subjectUserId = 1)))
        val ok = runBlocking { vm.save(doc, form.copy(subjectUserId = 1), emptyList(), emptyList()) }
        assertEquals(DocumentsViewModel.SaveResult.Saved, ok)
        val put = writes.single { it.startsWith("PUT /api/v1/documents/1") }
        assertTrue(put, put.contains("\"subject_user_id\":1"))
        runBlocking { vm.save(doc, form.copy(subjectUserId = null), emptyList(), emptyList()) }
        assertTrue(writes.toString(), writes.last { it.startsWith("PUT /api/v1/documents/1") }.contains("\"subject_user_id\":null"))
    }

    /** DOCS-A18: an older server (no `subject_user_id`) is not asked "About". */
    @Test fun `an older server is not offered the subject`() {
        val old = object : DocumentsHost by host {
            override val knowsSubjects = false
        }
        val vm = DocumentsViewModel(container, old)
        assertTrue(!vm.offersSubject)
    }

    /** DOCS-A19: the person filter is a facet the reset clears. */
    @Test fun `the person filter is set and reset`() {
        val vm = loaded()
        vm.setPerson(2)
        assertEquals(2, vm.state.value.filter.personId)
        assertTrue(vm.state.value.visible.isEmpty())
        vm.resetFilters()
        assertEquals(null, vm.state.value.filter.personId)
        assertEquals(1, vm.state.value.visible.size)
    }

    /** DOCS-A14: the folder delete asks with the server's preview and sends the confirmed counts. */
    @Test fun `folder delete keeps documents with the previewed counts`() {
        val vm = loaded()
        vm.selectFolder("2")
        val folder = vm.state.value.folders.first { it.id == 1 }
        vm.askDeleteFolder(folder)
        waitFor("impact") { vm.state.value.folderDelete != null }
        assertEquals(1, vm.state.value.folderDelete!!.second.documents)
        vm.deleteFolder(FolderDeleteChoice.UNFILE)
        waitFor("delete") { writes.any { it.startsWith("DELETE /api/v1/documents/folders/1") } }
        val delete = writes.single { it.startsWith("DELETE /api/v1/documents/folders/1") }
        assertTrue(delete, delete.contains("documents=unfile&expected_documents=1&expected_folders=2"))
        assertTrue(delete, !delete.contains("expected_snapshot"))
        waitFor("toast") { notices.contains(strings.t("documents.folderDeletedToast")) }
        // The open folder was inside the deleted branch: back to all documents.
        assertEquals("", vm.state.value.filter.folderId)
    }

    /** DOCS-A12: move keeps the document's fields and sends the new folder. */
    @Test fun `move sends the document with its new folder`() {
        val vm = loaded()
        vm.move(vm.state.value.documents, null)
        waitFor("move") { writes.any { it.startsWith("PUT /api/v1/documents/1") } }
        val put = writes.single { it.startsWith("PUT /api/v1/documents/1") }
        assertTrue(put, put.contains("\"folder_id\":null") && put.contains("\"name\":\"Lease\"") && put.contains("\"visibility\":\"family\""))
        waitFor("toast") { notices.contains(strings.t("documents.bulkMovedToast", "count" to 1)) }
    }

    /** DOCS-A17: a link from Tasks opens the viewer; an archived document switches to the archive. */
    @Test fun `a link opens the document, also from the archive`() {
        val vm = loaded(openId = 1)
        waitFor("viewer") { vm.state.value.viewing == 1 }
        val archived = DocumentsViewModel(container, host, 7)
        waitFor("archive") { archived.state.value.viewing == 7 }
        assertEquals("archived", archived.state.value.filter.status)
        val missing = DocumentsViewModel(container, host, 42)
        waitFor("gone") { notices.contains(strings.t("documents.errorDocumentGone")) }
        assertEquals(null, missing.state.value.viewing)
    }
}
