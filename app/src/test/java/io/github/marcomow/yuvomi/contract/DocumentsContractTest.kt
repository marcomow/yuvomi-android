package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.documents.DmsItem
import io.github.marcomow.yuvomi.feature.documents.Document
import io.github.marcomow.yuvomi.feature.documents.DocumentBodies
import io.github.marcomow.yuvomi.feature.documents.DocumentForm
import io.github.marcomow.yuvomi.feature.documents.DocumentRules
import io.github.marcomow.yuvomi.feature.documents.DocumentsApi
import io.github.marcomow.yuvomi.feature.documents.FolderDeleteChoice
import io.github.marcomow.yuvomi.feature.documents.PickedFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/spec/documents.md against upstream's image at the pinned version, seeded by `seed-demo.js`
 * (seven folders, fourteen documents, a private passport scan of alex's). Every test makes its own
 * folders and documents with unique names and removes them again.
 */
class DocumentsContractTest : ContractTestBase() {
    private class Member(val client: YuvomiClient, val id: Int) {
        val docs = DocumentsApi(client)
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

    private val text = "Socks\nPassport\n".toByteArray()

    private suspend fun Member.upload(name: String, folderId: Int? = null, file: PickedFile = PickedFile("list.txt", "text/plain", text)): Document =
        docs.create(DocumentBodies.upload(DocumentForm(name = name, folderId = folderId), file, 1), YuvomiClient.newIdempotencyKey())

    private suspend fun Member.cleanup(documents: List<Int> = emptyList(), folders: List<Int> = emptyList()) {
        documents.forEach { runCatching { docs.delete(it) } }
        folders.reversed().forEach { id -> runCatching { docs.deleteFolder(id, docs.deleteImpact(id), FolderDeleteChoice.UNFILE) } }
    }

    // ── GET /documents, GET /documents/folders, GET /documents/meta/options, members ──

    /** DOCS-A1. */
    @Test fun `DOCS-A1 the seeded list, folders, options and people decode`() = runBlocking {
        val alex = member("alex")
        val list = DocumentsApi.decodeDocuments(alex.docs.list(DocumentRules.STATUS_ACTIVE))
        assertTrue(list.size >= 14)
        assertTrue(list.all { it.status == DocumentRules.STATUS_ACTIVE && it.name.isNotEmpty() })
        assertTrue(list.any { it.visibility == DocumentRules.VISIBILITY_RESTRICTED })
        assertTrue(list.any { it.mimeType == "image/jpeg" && DocumentRules.thumbKind(it) == DocumentRules.PreviewKind.IMAGE })
        assertEquals(list.sortedByDescending { it.updatedAt }.map { it.id }, list.map { it.id })
        assertTrue(DocumentsApi.decodeDocuments(alex.docs.list(DocumentRules.STATUS_ARCHIVED)).all { it.archived })

        val folders = DocumentsApi.decodeFolders(alex.docs.folders())
        assertTrue(folders.size >= 7)
        assertTrue(list.filter { it.folderId != null }.all { d -> folders.any { it.id == d.folderId && it.name == d.folderName } })

        val meta = DocumentsApi.decodeMeta(alex.docs.meta())
        assertTrue(meta.isAdmin)
        assertTrue((meta.maxFileSize ?: 0) > 0)
        assertTrue("application/pdf" in meta.allowedMimeTypes && "text/plain" in meta.allowedMimeTypes)
        val emmaMeta = DocumentsApi.decodeMeta(member("emma").docs.meta())
        assertFalse(emmaMeta.isAdmin)
        assertTrue(emmaMeta.dmsAccounts.isEmpty())

        val members = DocumentsApi.decodePeople(alex.docs.members())
        assertTrue(members.any { it.id == alex.id })
        assertTrue(DocumentsApi.decodePeople(alex.docs.directory()).any { it.id == alex.id && it.displayName.isNotEmpty() })
    }

    /** DOCS-A13: a private document is only listed for its creator. */
    @Test fun `DOCS-A13 private documents stay with their creator`() = runBlocking {
        val alexIds = DocumentsApi.decodeDocuments(member("alex").docs.list("active")).filter { it.visibility == "private" }.map { it.id }
        assertTrue(alexIds.isNotEmpty())
        val emma = DocumentsApi.decodeDocuments(member("emma").docs.list("active")).map { it.id }
        assertTrue(alexIds.none { it in emma })
    }

    // ── POST /documents, PUT, PATCH archive, preview, download, thumbnail, DELETE ──

    /** DOCS-A8, A11, A12. */
    @Test fun `DOCS-A8 upload, edit, archive, read back and delete a document`() = runBlocking {
        val alex = member("alex")
        val name = unique("Doc")
        val created = alex.upload(name)
        try {
            assertEquals(name, created.name)
            assertEquals("list.txt", created.originalName)
            assertEquals("text/plain", created.mimeType)
            assertEquals(text.size.toLong(), created.fileSize)
            assertEquals(alex.id, created.createdBy)
            assertNull(created.folderId)

            assertArrayEquals(text, alex.docs.preview(created.id))
            assertArrayEquals(text, alex.docs.download(created.id))
            expectHttp(415) { alex.docs.thumbnail(created.id) }

            val form = DocumentForm.of(created).copy(
                description = "  packed  ", category = "travel", visibility = "restricted", allowedMemberIds = setOf(alex.id),
                expiresAt = "2030-01-31", reminderDays = "14",
            )
            val updated = alex.docs.update(created.id, DocumentBodies.payload(form))
            assertEquals("packed", updated.description)
            assertEquals("travel", updated.category)
            assertEquals("restricted", updated.visibility)
            assertEquals(listOf(alex.id), updated.allowedMemberIds)
            assertEquals("2030-01-31", updated.expiresAt?.take(10))
            assertEquals(14, updated.expiryReminderDays)

            val archived = alex.docs.archive(created.id, true).jsonObject["data"]!!.jsonObject
            assertEquals("archived", archived["status"]!!.jsonPrimitive.content)
            assertTrue(DocumentsApi.decodeDocuments(alex.docs.list("archived")).any { it.id == created.id })
            alex.docs.archive(created.id, false)
            assertTrue(DocumentsApi.decodeDocuments(alex.docs.list("active")).any { it.id == created.id })
        } finally {
            alex.cleanup(documents = listOf(created.id))
        }
        expectHttp(404, "documents_document_not_found") { alex.docs.delete(created.id) }
        expectHttp(404) { alex.docs.download(created.id) }
    }

    /** DOCS-A18, A20, OVERVIEW-A12 (2.84.0): who a document is about, and its row in "Key dates". */
    @Test fun `DOCS-A18 a document names its person and expires into key dates`() = runBlocking {
        val alex = member("alex")
        val emma = member("emma")
        val created = alex.upload(unique("Passport"))
        try {
            // Within its 7-day lead time: the server lists it among the countdowns, with the person.
            val soon = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString()
            val form = DocumentForm.of(created).copy(subjectUserId = emma.id, expiresAt = soon, reminderDays = "7")
            val updated = alex.docs.update(created.id, DocumentBodies.payload(form))
            assertEquals(emma.id, updated.subjectUserId)
            assertTrue(updated.subjectName.orEmpty().isNotEmpty())
            val listed = DocumentsApi.decodeDocuments(alex.docs.list("active")).first { it.id == created.id }
            assertEquals(emma.id, listed.subjectUserId)
            val dash = io.github.marcomow.yuvomi.core.api.HouseholdApi(signedIn("alex").first).dashboard()
            val rows = io.github.marcomow.yuvomi.core.api.ApiJson.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(io.github.marcomow.yuvomi.core.api.model.DashCountdown.serializer()), dash["countdowns"]!!,
            )
            val row = rows.firstOrNull { it.source == "document" && it.id == created.id }
            // The tile holds only the nearest few; when it is there, it names the person.
            if (row != null) assertEquals(updated.subjectName, row.personName)
            // No longer about anyone: null clears it; a stranger is refused.
            assertNull(alex.docs.update(created.id, DocumentBodies.payload(form.copy(subjectUserId = null))).subjectUserId)
            expectHttp(400) { alex.docs.update(created.id, DocumentBodies.payload(form.copy(subjectUserId = 999_999))) }
        } finally {
            alex.cleanup(documents = listOf(created.id))
        }
    }

    /** DOCS-A9: the server's own checks on an upload. */
    @Test fun `DOCS-A9 uploads that do not fit are refused with a reason`() = runBlocking {
        val alex = member("alex")
        expectHttp(400, "documents_file_content_mismatch") { alex.upload(unique("Fake"), file = PickedFile("fake.pdf", "application/pdf", "not a pdf".toByteArray())) }
        expectHttp(400, "documents_file_type_not_allowed") { alex.upload(unique("Svg"), file = PickedFile("a.svg", "image/svg+xml", "<svg/>".toByteArray())) }
        expectHttp(400, "documents_file_empty") { alex.upload(unique("Empty"), file = PickedFile("empty.txt", "text/plain", ByteArray(0))) }
        expectHttp(400, "documents_selected_folder_not_found") { alex.upload(unique("Lost"), folderId = 999_999) }
    }

    /** DOCS-A12, A13: only the creator or an admin changes a document. */
    @Test fun `DOCS-A12 someone else's document is refused to a member`() = runBlocking {
        val alex = member("alex")
        val doc = alex.upload(unique("Mine"))
        try {
            val emma = member("emma")
            expectHttp(403) { emma.docs.update(doc.id, DocumentBodies.payload(DocumentForm.of(doc).copy(name = "Theirs"))) }
            expectHttp(403) { emma.docs.archive(doc.id, true) }
            expectHttp(403) { emma.docs.delete(doc.id) }
            // A document made private by its owner is gone for everyone else.
            alex.docs.update(doc.id, DocumentBodies.payload(DocumentForm.of(doc).copy(visibility = "private")))
            expectHttp(404, "documents_document_not_found") { emma.docs.update(doc.id, DocumentBodies.payload(DocumentForm.of(doc))) }
        } finally {
            alex.cleanup(documents = listOf(doc.id))
        }
    }

    // ── Folders ──

    /** DOCS-A5, A14: create, rename, move, the delete preview and unfiling. */
    @Test fun `DOCS-A14 folders nest, rename, move and delete with their impact`() = runBlocking {
        val alex = member("alex")
        val parent = alex.docs.createFolder(unique("Parent"), null, YuvomiClient.newIdempotencyKey())
        val child = alex.docs.createFolder(unique("Child"), parent.id, YuvomiClient.newIdempotencyKey())
        val other = alex.docs.createFolder(unique("Other"), null, YuvomiClient.newIdempotencyKey())
        var doc: Document? = null
        try {
            assertEquals(parent.id, child.parentId)
            expectHttp(409, "documents_folder_exists") { alex.docs.createFolder(child.name, parent.id, YuvomiClient.newIdempotencyKey()) }
            expectHttp(400, "documents_selected_folder_not_found") { alex.docs.createFolder(unique("X"), 999_999, YuvomiClient.newIdempotencyKey()) }

            val renamed = alex.docs.updateFolder(child.id, DocumentBodies.rename("  ${child.name} 2 "))
            assertEquals("${child.name} 2", renamed.name)
            assertEquals(parent.id, renamed.parentId)
            val moved = alex.docs.updateFolder(child.id, DocumentBodies.moveFolder(other.id))
            assertEquals(other.id, moved.parentId)
            expectHttp(400) { alex.docs.updateFolder(other.id, DocumentBodies.moveFolder(child.id)) }

            doc = alex.upload(unique("Filed"), folderId = child.id)
            // The list's folder filter of the server is the subtree, like the app's facet.
            val inOther = DocumentsApi.decodeDocuments(alex.docs.list("active")).filter { d ->
                DocumentRules.matchesFolder(d, DocumentRules.Filter(folderId = other.id.toString()), DocumentsApi.decodeFolders(alex.docs.folders()))
            }
            assertEquals(listOf(doc.id), inOther.map { it.id })

            val impact = alex.docs.deleteImpact(other.id)
            assertEquals(2, impact.removedFolders)
            assertEquals(1, impact.documents)
            assertTrue(impact.canDeleteDocuments)
            assertEquals(64, impact.snapshot.length)
            val result = alex.docs.deleteFolder(other.id, impact, FolderDeleteChoice.UNFILE)
            assertTrue(result.folderDeleted)
            assertEquals(1, result.unfiledDocuments)
            val after = DocumentsApi.decodeDocuments(alex.docs.list("active")).first { it.id == doc.id }
            assertNull(after.folderId)
            expectHttp(404, "documents_folder_not_found") { alex.docs.deleteImpact(other.id) }
        } finally {
            alex.cleanup(documents = listOfNotNull(doc?.id), folders = listOf(parent.id, other.id))
        }
    }

    /** DOCS-A15: deleting a folder with its documents needs the preview's snapshot; a stale one is 409. */
    @Test fun `DOCS-A15 a folder deletes with its documents against the confirmed preview`() = runBlocking {
        val alex = member("alex")
        val folder = alex.docs.createFolder(unique("Bin"), null, YuvomiClient.newIdempotencyKey())
        val first = alex.upload(unique("One"), folderId = folder.id)
        var second: Document? = null
        try {
            val impact = alex.docs.deleteImpact(folder.id)
            second = alex.upload(unique("Two"), folderId = folder.id)
            expectHttp(409, "FOLDER_CONTENT_CHANGED") { alex.docs.deleteFolder(folder.id, impact, FolderDeleteChoice.DELETE) }
            val fresh = alex.docs.deleteImpact(folder.id)
            val result = alex.docs.deleteFolder(folder.id, fresh, FolderDeleteChoice.DELETE)
            assertTrue(result.folderDeleted)
            assertEquals(2, result.deletedDocuments)
            val ids = DocumentsApi.decodeDocuments(alex.docs.list("active")).map { it.id }
            assertFalse(first.id in ids || second.id in ids)
        } finally {
            alex.cleanup(documents = listOfNotNull(first.id, second?.id), folders = listOf(folder.id))
        }
    }

    // ── DMS (the seed connects none) ──

    /** DOCS-A16: DMS calls are for admins, and name the missing account. */
    @Test fun `DOCS-A16 DMS search, link and push refuse members and name a missing account`() = runBlocking {
        val alex = member("alex")
        val emma = member("emma")
        expectHttp(403) { emma.docs.dmsSearchRaw(1) }
        expectHttp(404) { alex.docs.dmsSearchRaw(999_999) }
        val item = DmsItem(JsonPrimitive("1"), "x")
        expectHttp(404, "dms_account_not_found") { alex.docs.dmsLink(999_999, item, YuvomiClient.newIdempotencyKey()) }
        expectHttp(403) { emma.docs.dmsLink(999_999, item, YuvomiClient.newIdempotencyKey()) }
        val doc = alex.upload(unique("Push"))
        try {
            expectHttp(404, "dms_account_not_found") { alex.docs.dmsPush(999_999, doc.id, YuvomiClient.newIdempotencyKey()) }
        } finally {
            alex.cleanup(documents = listOf(doc.id))
        }
    }

    /** The search's errors without the app's "unreachable" softening, for the contract. */
    private suspend fun DocumentsApi.dmsSearchRaw(accountId: Int) {
        dmsSearch(accountId, "")
    }
}
