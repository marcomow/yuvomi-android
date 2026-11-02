package io.github.marcomow.yuvomi.feature.documents

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/** docs/spec/documents.md: the pure rules ported from upstream's utils and page helpers. */
class DocumentLogicTest {
    private val today = LocalDate.of(2026, 11, 2)

    private fun doc(
        id: Int,
        name: String = "Doc $id",
        category: String = "other",
        folder: Int? = null,
        expires: String? = null,
        size: Long? = 1000,
        updated: String = "2026-10-0${id % 9 + 1} 10:00:00",
        createdBy: Int = 1,
        description: String? = null,
        original: String? = null,
        mime: String? = "application/pdf",
    ) = Document(
        id = id, name = name, category = category, folderId = folder, expiresAt = expires, fileSize = size, updatedAt = updated,
        createdBy = createdBy, description = description, originalName = original, mimeType = mime,
    )

    // Home(1) > Rent(2) > 2026(3); Car(4)
    private val folders = listOf(
        DocumentFolder(1, "Home"),
        DocumentFolder(2, "Rent", parentId = 1),
        DocumentFolder(3, "2026", parentId = 2),
        DocumentFolder(4, "Car"),
    )

    // ── Search and facets (DOCS-A3) ──

    @Test fun `search matches name, description and file name, case-insensitively`() {
        val d = doc(1, name = "Tenancy Agreement", description = "Signed lease", original = "lease.PDF")
        assertTrue(DocumentRules.matchesQuery(d, DocumentRules.normalizeQuery("  TENANCY ")))
        assertTrue(DocumentRules.matchesQuery(d, "signed"))
        assertTrue(DocumentRules.matchesQuery(d, ".pdf"))
        assertFalse(DocumentRules.matchesQuery(d, "passport"))
        assertTrue(DocumentRules.matchesQuery(d, ""))
    }

    @Test fun `a folder shows its whole subtree, no folder shows unfiled documents`() {
        val docs = listOf(doc(1, folder = 1), doc(2, folder = 3), doc(3, folder = 4), doc(4))
        fun ids(folder: String) = docs.filter { DocumentRules.matchesFolder(it, DocumentRules.Filter(folderId = folder), folders) }.map { it.id }
        assertEquals(listOf(1, 2), ids("1"))
        assertEquals(listOf(2), ids("2"))
        assertEquals(listOf(4), ids(DocumentRules.FOLDER_NONE))
        assertEquals(listOf(1, 2, 3, 4), ids(DocumentRules.FOLDER_ALL))
        assertEquals(emptyList<Int>(), ids("99"))
    }

    @Test fun `each facet counts under the other axis and the search, never under itself`() {
        val docs = listOf(
            doc(1, category = "home", folder = 1, name = "Lease"),
            doc(2, category = "home", folder = 3, name = "Rent 2026"),
            doc(3, category = "vehicle", folder = 4, name = "Registration"),
            doc(4, category = "finance", name = "Invoice"),
        )
        val inHome = DocumentRules.Filter(folderId = "1", category = "home")
        val categories = DocumentRules.categoryCounts(docs, folders, inHome)
        assertEquals(2, categories[""])
        assertEquals(2, categories["home"])
        assertNull(categories["vehicle"])
        val foldersCount = DocumentRules.folderCounts(docs, folders, inHome)
        assertEquals(2, foldersCount[""])
        assertEquals(2, foldersCount["1"])
        assertEquals(1, foldersCount["3"])
        assertEquals(0, foldersCount["4"])
        assertEquals(0, foldersCount[DocumentRules.FOLDER_NONE])
        // The search shrinks the counts; the chips that stand follow the stock, not the search.
        val searching = DocumentRules.Filter(query = "rent")
        assertEquals(1, DocumentRules.categoryCounts(docs, folders, searching)["home"])
        assertEquals(listOf("finance", "home", "vehicle"), DocumentRules.presentCategories(docs, folders, searching))
        // The chosen category stays as a chip even at zero.
        assertEquals(listOf("medical"), DocumentRules.presentCategories(emptyList(), folders, DocumentRules.Filter(category = "medical")))
    }

    @Test fun `deadline facet counts expiring and expired, and shows only when it finds something`() {
        val docs = listOf(doc(1, expires = "2026-11-10"), doc(2, expires = "2026-10-01"), doc(3, expires = "2027-06-01"), doc(4))
        val filter = DocumentRules.Filter()
        assertEquals(2, DocumentRules.expiringCount(docs, folders, filter, today))
        assertTrue(DocumentRules.showsExpiringChip(docs, folders, filter, today))
        assertFalse(DocumentRules.showsExpiringChip(listOf(doc(3, expires = "2027-06-01")), folders, filter, today))
        assertTrue(DocumentRules.showsExpiringChip(emptyList(), folders, filter.copy(expiringSoon = true), today))
        val visible = DocumentRules.visible(docs, folders, filter.copy(expiringSoon = true), "updated", "desc", today)
        assertEquals(setOf(1, 2), visible.map { it.id }.toSet())
    }

    // ── Sorting (DOCS-A4) ──

    @Test fun `sorts keep their natural direction and missing expiry dates stay last both ways`() {
        val a = doc(1, name = "beta", size = 10, updated = "2026-10-01 10:00:00", expires = "2027-01-01")
        val b = doc(2, name = "Alpha", size = 30, updated = "2026-10-03 10:00:00")
        val c = doc(3, name = "gamma", size = 20, updated = "2026-10-02 10:00:00", expires = "2026-12-01")
        val docs = listOf(a, b, c)
        assertEquals("desc", DocumentRules.defaultDirection("updated"))
        assertEquals("asc", DocumentRules.defaultDirection("name"))
        assertEquals(listOf(2, 3, 1), DocumentRules.sort(docs, "updated", null).map { it.id })
        assertEquals(listOf(2, 1, 3), DocumentRules.sort(docs, "name", null, Locale.ENGLISH).map { it.id })
        assertEquals(listOf(2, 3, 1), DocumentRules.sort(docs, "size", null).map { it.id })
        assertEquals(listOf(3, 1, 2), DocumentRules.sort(docs, "expiring", "asc").map { it.id })
        assertEquals(listOf(1, 3, 2), DocumentRules.sort(docs, "expiring", "desc").map { it.id })
        assertEquals(listOf(2, 3, 1), DocumentRules.sort(docs, "bogus", null).map { it.id })
    }

    // ── Folder tree (DOCS-A5) ──

    @Test fun `subtree, path and the flattened tree follow folder-tree js`() {
        assertEquals(setOf(1, 2, 3), DocumentRules.subtreeIds(folders, 1))
        assertEquals(setOf(99), DocumentRules.subtreeIds(folders, 99))
        assertEquals(listOf("Home", "Rent", "2026"), DocumentRules.folderPath(folders, 3).map { it.name })
        assertEquals(emptyList<DocumentFolder>(), DocumentRules.folderPath(folders, 99))
        val collapsed = DocumentRules.flatten(folders, emptySet())
        assertEquals(listOf(1, 4), collapsed.map { it.folder.id })
        assertTrue(collapsed.first().hasChildren)
        val open = DocumentRules.flatten(folders, setOf(1))
        assertEquals(listOf(1 to 0, 2 to 1, 4 to 0), open.map { it.folder.id to it.depth })
        assertEquals(listOf(1, 2, 3, 4), DocumentRules.flatten(folders).map { it.folder.id })
        // A folder whose parent is missing becomes a root instead of vanishing.
        val orphan = DocumentRules.flatten(folders + DocumentFolder(5, "Lost", parentId = 42))
        assertEquals(0, orphan.first { it.folder.id == 5 }.depth)
    }

    @Test fun `move rules refuse self, descendants, missing parents and depth over five`() {
        assertNull(DocumentRules.moveIssue(folders, 4, null))
        assertEquals(DocumentRules.MoveIssue.SELF, DocumentRules.moveIssue(folders, 1, 1))
        assertEquals(DocumentRules.MoveIssue.DESCENDANT, DocumentRules.moveIssue(folders, 1, 3))
        assertEquals(DocumentRules.MoveIssue.MISSING_PARENT, DocumentRules.moveIssue(folders, 4, 42))
        assertNull(DocumentRules.moveIssue(folders, 4, 3))
        val deep = (1..5).map { DocumentFolder(it, "L$it", parentId = if (it == 1) null else it - 1) }
        assertEquals(DocumentRules.MoveIssue.TOO_DEEP, DocumentRules.moveIssue(deep, null, 5))
        assertNull(DocumentRules.moveIssue(deep, null, 4))
        assertEquals(listOf(4), DocumentRules.folderMoveTargets(folders, 1).map { it.folder.id })
    }

    // ── Expiry (DOCS-A6) ──

    @Test fun `expiry status, row chip and viewer line`() {
        val expired = DocumentRules.dateStatus("2026-10-28", today)!!
        assertEquals(DocumentRules.DateState.EXPIRED, expired.state)
        val chip = DocumentRules.expiryChip(expired)!!
        assertTrue(chip.expired)
        assertEquals(5L, chip.count)
        assertEquals("documents.expiredDays", chip.key)
        val soon = DocumentRules.dateStatus("2026-12-02", today)!!
        assertEquals(DocumentRules.DateState.EXPIRING, soon.state)
        assertEquals("documents.expiringShort", DocumentRules.expiryChip(soon)!!.shortKey)
        val valid = DocumentRules.dateStatus("2026-12-03", today)!!
        assertEquals(DocumentRules.DateState.VALID, valid.state)
        assertNull(DocumentRules.expiryChip(valid))
        assertEquals("documents.expiryValidUntil", DocumentRules.expiryViewerKey(valid))
        assertEquals("documents.expiryEndedOn", DocumentRules.expiryViewerKey(expired))
        assertEquals("documents.expiryEndsOn", DocumentRules.expiryViewerKey(soon))
        assertNull(DocumentRules.dateStatus(null, today))
        assertNull(DocumentRules.dateStatus("garbage", today))
    }

    // ── Row details (DOCS-A7) ──

    @Test fun `folder chip hides when it repeats the category or is the open folder`() {
        assertTrue(DocumentRules.folderRepeatsCategory("School", "School"))
        assertTrue(DocumentRules.folderRepeatsCategory("Insurances", "Insurance"))
        assertTrue(DocumentRules.folderRepeatsCategory("Versicherungen", "Versicherung"))
        assertFalse(DocumentRules.folderRepeatsCategory("Arbeitsvertrag", "Arbeit"))
        assertFalse(DocumentRules.folderRepeatsCategory("Schulbus", "Schule"))
        assertFalse(DocumentRules.folderRepeatsCategory("", "School"))
        val d = doc(1, folder = 3).copy(folderName = "2026")
        assertTrue(DocumentRules.showsFolderChip(d, "Home", ""))
        assertFalse(DocumentRules.showsFolderChip(d, "Home", "3"))
        assertFalse(DocumentRules.showsFolderChip(d.copy(folderName = "Homes"), "Home", ""))
        assertFalse(DocumentRules.showsVisibility("family"))
        assertFalse(DocumentRules.showsVisibility(null))
        assertTrue(DocumentRules.showsVisibility("private"))
    }

    @Test fun `preview kinds, list previews and storage`() {
        assertEquals(DocumentRules.PreviewKind.PDF, DocumentRules.previewKind("application/pdf"))
        assertEquals(DocumentRules.PreviewKind.IMAGE, DocumentRules.previewKind("IMAGE/JPEG"))
        assertEquals(DocumentRules.PreviewKind.TEXT, DocumentRules.previewKind("text/plain; charset=utf-8"))
        assertNull(DocumentRules.previewKind("application/msword"))
        assertNull(DocumentRules.previewKind("image/svg+xml"))
        val image = doc(1, mime = "image/png", size = 2000)
        assertEquals(DocumentRules.PreviewKind.IMAGE, DocumentRules.thumbKind(image))
        assertNull(DocumentRules.thumbKind(image.copy(fileSize = 11L * 1024 * 1024)))
        assertNull(DocumentRules.thumbKind(image.copy(fileSize = 0)))
        assertNull(DocumentRules.thumbKind(image.copy(storageBackend = "webdav")))
        assertNull(DocumentRules.thumbKind(doc(2, mime = "text/plain")))
        assertEquals("dms", DocumentRules.storageBackend(image.copy(storageProvider = "external")))
        val paperless = image.copy(storageBackend = "dms", dmsAccountId = 3, dmsProvider = "paperless", externalUrl = "https://dms.example/documents/9")
        assertTrue(DocumentRules.hasDmsThumbnail(paperless))
        assertFalse(DocumentRules.hasDmsThumbnail(paperless.copy(dmsProvider = "papra")))
        assertEquals("https://dms.example/documents/9", DocumentRules.dmsLink(paperless))
        assertNull(DocumentRules.dmsLink(paperless.copy(externalUrl = "javascript:alert(1)")))
        assertNull(DocumentRules.dmsLink(image.copy(externalUrl = "https://x")))
        assertEquals("documents.storageDms", DocumentRules.storageBadgeKey(paperless))
        assertEquals("documents.storageDmsUnavailable", DocumentRules.storageBadgeKey(paperless.copy(dmsAccountId = null)))
        assertNull(DocumentRules.storageBadgeKey(image))
        assertEquals("documents.storageLocalFolder", DocumentRules.storageBadgeKey(image.copy(storageKey = "a/b.pdf")))
    }

    @Test fun `file sizes read like the web page`() {
        assertEquals("-", DocumentRules.fileSize(null))
        assertEquals("0 KB", DocumentRules.fileSize(0))
        assertEquals("1 KB", DocumentRules.fileSize(100))
        assertEquals("121 KB", DocumentRules.fileSize(124_000))
        assertEquals("1.5 MB", DocumentRules.fileSize(1_572_864))
        assertEquals("passport.scan", DocumentRules.nameFromFile("passport.scan.pdf"))
        assertEquals("README", DocumentRules.nameFromFile("README"))
    }

    @Test fun `only creators and admins with write access manage a document`() {
        val mine = doc(1, createdBy = 7)
        assertTrue(DocumentRules.mayManage(mine, canWrite = true, userId = 7, isAdmin = false))
        assertFalse(DocumentRules.mayManage(mine, canWrite = true, userId = 8, isAdmin = false))
        assertTrue(DocumentRules.mayManage(mine, canWrite = true, userId = 8, isAdmin = true))
        assertFalse(DocumentRules.mayManage(mine, canWrite = false, userId = 7, isAdmin = true))
    }

    // ── Bodies (DOCS-A8, A9) ──

    @Test fun `the dialog's payload trims, nulls and only sends what applies`() {
        val form = DocumentForm(
            name = "  Lease ", description = "  ", category = "home", folderId = 2, visibility = "family",
            expiresAt = null, reminderDays = "30", allowedMemberIds = setOf(3, 2),
        )
        val body = DocumentBodies.payload(form)
        assertEquals("Lease", body["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["description"])
        assertEquals(2, body["folder_id"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, body["expires_at"])
        assertEquals(JsonNull, body["expiry_reminder_days"])
        assertEquals(JsonArray(emptyList()), body["allowed_member_ids"])
        val restricted = DocumentBodies.payload(form.copy(visibility = "restricted", expiresAt = "2027-01-01", folderId = null))
        assertEquals(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(3))), restricted["allowed_member_ids"])
        assertEquals("2027-01-01", restricted["expires_at"]!!.jsonPrimitive.content)
        assertEquals(30, restricted["expiry_reminder_days"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, restricted["folder_id"])
        assertNull(DocumentBodies.reminderProblem(form))
        assertEquals("android.documents.reminderRange", DocumentBodies.reminderProblem(form.copy(expiresAt = "2027-01-01", reminderDays = "400")))
        assertNull(DocumentBodies.reminderProblem(form.copy(expiresAt = "2027-01-01", reminderDays = "")))
        assertNull(DocumentBodies.reminderProblem(form.copy(expiresAt = "2027-01-01", reminderDays = "0")))
    }

    @Test fun `an upload names each file, and only one file takes the typed name`() {
        val file = PickedFile("scan.final.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        assertEquals("data:image/jpeg;base64,AQID", file.dataUrl())
        val one = DocumentBodies.upload(DocumentForm(name = "Passport"), file, 1)
        assertEquals("Passport", one["name"]!!.jsonPrimitive.content)
        assertEquals("scan.final.jpg", one["original_name"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", one["content_data"]!!.jsonPrimitive.content)
        assertEquals("scan.final", DocumentBodies.upload(DocumentForm(name = "Passport"), file, 2)["name"]!!.jsonPrimitive.content)
        assertEquals("scan.final", DocumentBodies.upload(DocumentForm(name = " "), file, 1)["name"]!!.jsonPrimitive.content)
    }

    @Test fun `move keeps the document's fields and folder deletion sends the confirmed preview`() {
        val d = doc(5, folder = 1).copy(visibility = "restricted", allowedMemberIds = listOf(2), status = "archived")
        val move = DocumentBodies.move(d, null)
        assertEquals(JsonNull, move["folder_id"])
        assertEquals("restricted", move["visibility"]!!.jsonPrimitive.content)
        assertEquals("archived", move["status"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(2))), move["allowed_member_ids"])
        val impact = FolderDeleteImpact(id = 1, removedFolders = 3, documents = 4, snapshot = "ab".repeat(32))
        assertEquals(
            listOf("documents" to "unfile", "expected_documents" to "4", "expected_folders" to "3"),
            DocumentBodies.folderDeleteQuery(impact, FolderDeleteChoice.UNFILE),
        )
        assertEquals("expected_snapshot" to "ab".repeat(32), DocumentBodies.folderDeleteQuery(impact, FolderDeleteChoice.DELETE).last())
        assertEquals(JsonNull, DocumentBodies.moveFolder(null)["parent_id"])
    }

    @Test fun `folder deletion outcomes say what happened`() {
        assertTrue(FolderDeleteOutcome.asksChoice(FolderDeleteImpact(documents = 1)))
        assertTrue(FolderDeleteOutcome.asksChoice(FolderDeleteImpact(documents = 0, canDeleteDocuments = false)))
        assertFalse(FolderDeleteOutcome.asksChoice(FolderDeleteImpact(documents = 0)))
        assertEquals("documents.folderDeletedToast", FolderDeleteOutcome.message(FolderDeleteResult(), FolderDeleteChoice.UNFILE)!!.key)
        assertEquals(
            Message("documents.folderDeletedWithDocumentsToast", mapOf("count" to 2)),
            FolderDeleteOutcome.message(FolderDeleteResult(deletedDocuments = 2), FolderDeleteChoice.DELETE),
        )
        assertNull(FolderDeleteOutcome.message(FolderDeleteResult(deletedDocuments = 2), FolderDeleteChoice.DELETE, showSuccess = false))
        val partial = FolderDeleteResult(deletedDocuments = 1, failedDocuments = listOf(FailedDocument(9, "x", "storage")), folderDeleted = false)
        assertEquals(Message("documents.folderDeletePartialToast", mapOf("deleted" to 1, "failed" to 1)), FolderDeleteOutcome.message(partial, FolderDeleteChoice.DELETE, false))
        assertEquals("documents.folderDeleteContentsChangedWithFailuresToast", FolderDeleteOutcome.message(partial.copy(contentsChanged = true), FolderDeleteChoice.DELETE)!!.key)
        val changed = partial.copy(contentsChanged = true, failedDocuments = listOf(FailedDocument(9, "x", "concurrency")))
        assertEquals("documents.folderDeleteContentsChangedToast", FolderDeleteOutcome.message(changed, FolderDeleteChoice.DELETE)!!.key)
        val linked = FolderDeleteOutcome.linkedRecords(FolderDeleteImpact(linkedRecords = mapOf("tasks" to 2, "budget" to 0, "calendar" to null)))
        assertEquals(listOf("nav.tasks" to 2), linked)
    }

    @Test fun `empty states name their cause`() {
        assertEquals(EmptyKind.Search(3, false), EmptyKind.of(DocumentRules.Filter(query = "x"), folders, 3))
        assertEquals(EmptyKind.Search(0, true), EmptyKind.of(DocumentRules.Filter(query = "x", category = "home"), folders, 0))
        assertEquals(EmptyKind.EmptyFolder("Car"), EmptyKind.of(DocumentRules.Filter(folderId = "4"), folders, 0))
        assertEquals(EmptyKind.NoMatch, EmptyKind.of(DocumentRules.Filter(folderId = "4", category = "home"), folders, 0))
        assertEquals(EmptyKind.NoMatch, EmptyKind.of(DocumentRules.Filter(folderId = DocumentRules.FOLDER_NONE), folders, 0))
        assertEquals(EmptyKind.EmptyArchive, EmptyKind.of(DocumentRules.Filter(status = "archived"), folders, 0))
        assertEquals(EmptyKind.Nothing, EmptyKind.of(DocumentRules.Filter(), folders, 0))
    }

    // ── Errors (DOCS-A10) ──

    @Test fun `known reasons get upstream's sentence, never the server's text`() {
        fun key(status: Int, reason: String?) = DocumentErrors.message(ApiProblem.Http(status, reason, "English")).key
        assertEquals("documents.errorDocumentGone", key(404, "documents_document_not_found"))
        assertEquals("documents.errorSelectedFolderGone", key(400, "documents_selected_folder_not_found"))
        assertEquals("documents.errorFolderExists", key(409, "documents_folder_exists"))
        assertEquals("documents.errorFileContentMismatch", key(400, "documents_file_content_mismatch"))
        assertEquals("documents.errorStorageFailed", key(502, "documents_storage_failed"))
        assertEquals("documents.folderDeleteInProgressToast", key(409, "FOLDER_DELETE_IN_PROGRESS"))
        assertEquals("documents.dmsSearchError", key(502, "dms_unreachable"))
        assertEquals("common.errorNoPermission", key(403, null))
        assertEquals("common.errorFormExpired", key(403, "csrf_invalid"))
        assertEquals("common.unknownError", key(500, null))
        assertEquals("common.unknownError", key(400, "something_new"))
        val deep = DocumentErrors.message(ApiProblem.Http(400, "documents_folder_too_deep", null))
        assertEquals(Message("documents.errorFolderTooDeep", mapOf("limit" to 5)), deep)
        assertEquals("common.errorOfflineMutation", DocumentErrors.message(ApiProblem.Network(IOException("x"))).key)
        assertTrue(DocumentErrors.isGone(ApiProblem.Http(404, "documents_document_not_found", null)))
        assertFalse(DocumentErrors.isGone(ApiProblem.Http(403, "documents_document_not_found", null)))
        assertFalse(DocumentErrors.isGone(ApiProblem.Http(404, null, null)))
        assertTrue(DocumentErrors.isSelectedFolderGone(ApiProblem.Http(400, "documents_selected_folder_not_found", null)))
    }

    // ── Decoding (DOCS-A1) ──

    @Test fun `the list, folders, options and impact decode with nulls and flags`() {
        val list = DocumentsApi.decodeDocuments(
            """{"data":[{"id":4,"name":"Car","category":"vehicle","status":"active","visibility":"restricted","original_name":"car.pdf",
            "mime_type":"application/pdf","file_size":null,"storage_provider":"local","storage_backend":null,"folder_id":2,"folder_name":"Vehicle",
            "created_by":1,"updated_at":"2026-10-30 08:00:00","expires_at":"2027-03-01","expiry_reminder_days":14,"allowed_member_ids":[2,3],"extra":1}]}""",
        )
        val d = list.single()
        assertEquals(listOf(2, 3), d.allowedMemberIds)
        assertNull(d.fileSize)
        assertEquals("local", DocumentRules.storageBackend(d))
        assertEquals(14, d.expiryReminderDays)
        val meta = DocumentsApi.decodeMeta(ApiJson.parseToJsonElement("""{"data":{"max_file_size":5242880,"is_admin":true,"dms_accounts":[{"id":1,"name":"Paperless","provider":"paperless"}]}}"""))
        assertTrue(meta.isAdmin)
        assertEquals(5L, DocumentRules.megabytes(meta.maxFileSize!!))
        val impact = ApiJson.decodeFromJsonElement(
            FolderDeleteImpact.serializer(),
            ApiJson.parseToJsonElement("""{"id":1,"removed_folders":2,"documents":3,"can_delete_documents":false,"linked_records":{"tasks":1,"budget":null},"snapshot":"x"}""").jsonObject,
        )
        assertFalse(impact.canDeleteDocuments)
        assertNull(impact.linkedRecords["budget"])
        val item = ApiJson.decodeFromJsonElement(DmsItem.serializer(), ApiJson.parseToJsonElement("""{"id":"42","title":"Bill","filename":"bill.pdf"}"""))
        assertEquals("42", item.key)
        assertEquals("42", DocumentBodies.dmsLink(1, item)["dms_document_id"]!!.jsonPrimitive.content)
    }

    // ── Who a document is about, lead time (2.84.0: DOCS-A18 to A20) ──

    private fun about(id: Int, person: Int?, name: String? = null, category: String = "identity", folder: Int? = null, expires: String? = null, lead: Int? = null) =
        doc(id, category = category, folder = folder, expires = expires).copy(subjectUserId = person, subjectName = name, expiryReminderDays = lead)

    private val members = listOf(DocumentPerson(1, "Alex"), DocumentPerson(2, "Linda", avatarData = "data:x"), DocumentPerson(3, "Emma"))

    @Test fun `DOCS-A20 the chip and the deadline filter follow the document's own lead time`() {
        // 2026-11-02 + 60 days = 2027-01-01.
        val ninety = about(1, null, expires = "2027-01-01", lead = 90)
        val seven = about(2, null, expires = "2026-11-23", lead = 7) // 21 days ahead
        val none = about(3, null, expires = "2026-11-23")
        val zeroToday = about(4, null, expires = "2026-11-02", lead = 0)
        val zeroTomorrow = about(5, null, expires = "2026-11-03", lead = 0)
        val expired = about(6, null, expires = "2026-10-01", lead = 0)
        assertEquals(DocumentRules.DateState.EXPIRING, DocumentRules.expiryStatus(ninety, today)!!.state)
        assertEquals(DocumentRules.DateState.VALID, DocumentRules.expiryStatus(seven, today)!!.state)
        assertEquals(DocumentRules.DateState.EXPIRING, DocumentRules.expiryStatus(none, today)!!.state)
        assertEquals(DocumentRules.DateState.EXPIRING, DocumentRules.expiryStatus(zeroToday, today)!!.state)
        assertEquals(DocumentRules.DateState.VALID, DocumentRules.expiryStatus(zeroTomorrow, today)!!.state)
        assertEquals(DocumentRules.DateState.EXPIRED, DocumentRules.expiryStatus(expired, today)!!.state)
        assertEquals(30, DocumentRules.expiryLeadDays(none))
        assertEquals(0, DocumentRules.expiryLeadDays(zeroToday))
        assertNull(DocumentRules.expiryChip(DocumentRules.expiryStatus(seven, today)))
        assertEquals("documents.expiringShort", DocumentRules.expiryChip(DocumentRules.expiryStatus(ninety, today))!!.shortKey)
        val docs = listOf(ninety, seven, none, zeroToday, zeroTomorrow, expired)
        val filter = DocumentRules.Filter(expiringSoon = true)
        assertEquals(listOf(1, 3, 4, 6), DocumentRules.visible(docs, emptyList(), filter, "updated", "asc", today).map { it.id }.sorted())
        assertEquals(4, DocumentRules.expiringCount(docs, emptyList(), DocumentRules.Filter(), today))
        assertNull(DocumentRules.expiryStatus(about(7, null), today))
    }

    @Test fun `DOCS-A19 the person filter narrows the list and every facet`() {
        val docs = listOf(
            about(1, 2, "Linda", category = "identity", folder = 1, expires = "2026-11-10"),
            about(2, 3, "Emma", category = "school", folder = 1),
            about(3, null, category = "identity"),
            about(4, 2, "Linda", category = "medical", folder = 2),
        )
        val folders = listOf(DocumentFolder(1, "Home"), DocumentFolder(2, "Car"))
        // In the household's order, each once; nobody without a document.
        assertEquals(listOf(2, 3), DocumentRules.subjectPeople(docs, members).map { it.id })
        val linda = DocumentRules.subjectPeople(docs, members).first()
        assertEquals("Linda", linda.displayName)
        assertEquals("data:x", linda.avatarData)
        // A chosen person missing from the view stays in the menu; no subjects and none chosen: no chip.
        assertEquals(listOf(1), DocumentRules.personFilterPeople(listOf(docs[2]), members, 1).map { it.id })
        assertTrue(DocumentRules.personFilterPeople(listOf(docs[2]), members, null).isEmpty())

        val f = DocumentRules.Filter(personId = 2)
        assertTrue(f.hasFacet)
        assertEquals(listOf(1, 4), DocumentRules.visible(docs, folders, f, "updated", "asc", today).map { it.id }.sorted())
        val categories = DocumentRules.categoryCounts(docs, folders, f)
        assertEquals(2, categories[""])
        assertEquals(1, categories["identity"])
        assertNull(categories["school"])
        assertEquals(listOf("medical", "identity"), DocumentRules.presentCategories(docs, folders, f))
        val byFolder = DocumentRules.folderCounts(docs, folders, f)
        assertEquals(1, byFolder["1"])
        assertEquals(1, byFolder["2"])
        assertEquals(0, byFolder[DocumentRules.FOLDER_NONE])
        assertEquals(1, DocumentRules.expiringCount(docs, folders, f, today))
        assertFalse(DocumentRules.showsExpiringChip(docs, folders, DocumentRules.Filter(personId = 3), today))
        // A folder with a person is a filtered view, not the plain empty folder.
        assertEquals(EmptyKind.NoMatch, EmptyKind.of(DocumentRules.Filter(folderId = "1", personId = 2), folders, 0))
    }

    @Test fun `DOCS-A18 the subject is sent always and the no-access preview follows documentVisibleSql`() {
        val form = DocumentForm(name = "Passport", subjectUserId = 3)
        assertEquals(3, DocumentBodies.payload(form)["subject_user_id"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, DocumentBodies.payload(form.copy(subjectUserId = null))["subject_user_id"])
        assertEquals(2, DocumentForm.of(about(1, 2, "Linda")).subjectUserId)
        // Nobody chosen, the filer, the whole family or a chosen member may open it.
        assertTrue(DocumentRules.subjectMayOpen(null, 1, "private", emptySet()))
        assertTrue(DocumentRules.subjectMayOpen(1, 1, "private", emptySet()))
        assertTrue(DocumentRules.subjectMayOpen(3, 1, "family", emptySet()))
        assertTrue(DocumentRules.subjectMayOpen(3, 1, "restricted", setOf(3)))
        assertFalse(DocumentRules.subjectMayOpen(3, 1, "restricted", setOf(2)))
        assertFalse(DocumentRules.subjectMayOpen(3, 1, "private", setOf(3)))
        // The row's subject decodes from the list answer.
        val decoded = DocumentsApi.decodeDocuments(
            """{"data":[{"id":5,"name":"Passport","subject_user_id":3,"subject_name":"Emma","subject_color":"#F00"}]}""",
        ).single()
        assertEquals(DocumentPerson(3, "Emma", "#F00", null), DocumentRules.subject(decoded, emptyList()))
        assertNull(DocumentRules.subject(doc(6), members))
    }
}
