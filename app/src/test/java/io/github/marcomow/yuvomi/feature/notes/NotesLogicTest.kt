package io.github.marcomow.yuvomi.feature.notes

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.feature.notes.MarkdownFormat.Format
import io.github.marcomow.yuvomi.feature.notes.NoteMarkdown.Mark
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** docs/spec/notes.md: the pure parts, with cases from upstream's own tests where they exist. */
class NotesRulesTest {
    private val home = NoteCategory(1, "Home", NoteCategory.SCOPE_HOUSEHOLD)
    private val mine = NoteCategory(2, "Mine", NoteCategory.SCOPE_PERSONAL, ownerUserId = 1)
    private val school = NoteCategory(3, "School", NoteCategory.SCOPE_HOUSEHOLD)

    private val notes = listOf(
        Note(1, "Holiday", "- [ ] Passports", pinned = true, createdBy = 1, creatorName = "Alex", categories = listOf(home, mine)),
        Note(2, null, "Milk and bread", createdBy = 2, creatorName = "Linda", categories = listOf(home)),
        Note(3, "Emma's school", "Class 3b", pinned = true, createdBy = 2, creatorName = "Linda"),
    )

    /** NOTES-R31: archived notes leave the board until the Archive category is chosen. */
    @Test fun `archived notes are off the board unless the Archive category is selected`() {
        val archive = NoteCategory(9, "archive", NoteCategory.SCOPE_PERSONAL, ownerUserId = 1)
        val withArchived = notes + Note(4, "Old", "x", categories = listOf(home, archive))
        assertEquals(listOf(1, 2, 3), NotesRules.visible(withArchived, BoardFilter(), archiveId = 9).map { it.id })
        assertEquals(listOf(4), NotesRules.visible(withArchived, BoardFilter(categoryIds = listOf(9)), archiveId = 9).map { it.id })
        // The household's Archive wins over a personal one; names match in any case and spelling.
        val shared = NoteCategory(10, "Archive", NoteCategory.SCOPE_HOUSEHOLD)
        assertEquals(10, NotesRules.archiveCategory(listOf(archive, shared, home), setOf("Archiv", "Archive"))?.id)
        assertNull(NotesRules.archiveCategory(listOf(home, school), setOf("Archive")))
        assertTrue(NotesRules.isArchived(withArchived.last(), 9))
    }

    /** NOTES-A3. */
    @Test fun `search matches title or text, case-insensitively, after the creator and categories`() {
        assertEquals(listOf(2), NotesRules.visible(notes, BoardFilter(query = "  MILK ")).map { it.id })
        assertEquals(listOf(3), NotesRules.visible(notes, BoardFilter(query = "school")).map { it.id })
        assertEquals(listOf(2, 3), NotesRules.visible(notes, BoardFilter(creator = "Linda")).map { it.id })
        // Several categories are AND-ed (noteMatchesCategories).
        assertEquals(listOf(1, 2), NotesRules.visible(notes, BoardFilter(categoryIds = listOf(1))).map { it.id })
        assertEquals(listOf(1), NotesRules.visible(notes, BoardFilter(categoryIds = listOf(1, 2))).map { it.id })
        assertEquals(emptyList<Int>(), NotesRules.visible(notes, BoardFilter(creator = "Linda", categoryIds = listOf(2))).map { it.id })
        assertTrue(BoardFilter(query = "x").isFiltered)
        assertFalse(BoardFilter(query = "  ").isFiltered)
    }

    @Test fun `filter row shows creators in order of first appearance and only categories in use or chosen`() {
        assertEquals(listOf("Alex", "Linda"), NotesRules.creators(notes).map { it.name })
        assertEquals(listOf(home, mine), NotesRules.filterCategories(notes, listOf(home, mine, school), emptyList()))
        assertEquals(listOf(home, mine, school), NotesRules.filterCategories(notes, listOf(home, mine, school), listOf(3)))
        assertEquals(listOf(1), NotesRules.pruneMissing(listOf(1, 9), listOf(home)))
    }

    /** NOTES-A4. */
    @Test fun `pinned notes come first in a stable order and are headed only when both groups exist`() {
        val sorted = NotesRules.sortPinned(notes)
        assertEquals(listOf(1, 3, 2), sorted.map { it.id })
        assertTrue(NotesRules.groups(sorted).headed)
        assertFalse(NotesRules.groups(sorted.filter { it.pinned }).headed)
    }

    @Test fun `a note's name is its title, else its first text line without marks`() {
        assertEquals("Holiday", NotesRules.name(notes[0]))
        assertEquals("Passports", NotesRules.name(Note(9, "  ", "\n- [ ] **Passports**")))
        assertEquals("Heading", NotesRules.name(Note(9, null, "## Heading")))
        assertEquals("a".repeat(40) + "…", NotesRules.name(Note(9, null, "a".repeat(50))))
        assertNull(NotesRules.name(Note(9, null, "")))
    }

    /** NOTES-A8, NOTES-A9: upstream's `note-access.js` rules. */
    @Test fun `who may change, tick, set visibility and lock`() {
        val alex = NoteViewer(userId = 1, isAdmin = true, readOnly = false, canEditOthers = true)
        val emma = NoteViewer(userId = 3, isAdmin = false, readOnly = false, canEditOthers = true)
        val child = NoteViewer(userId = 4, isAdmin = false, readOnly = false, canEditOthers = false)
        val reader = NoteViewer(userId = 5, isAdmin = false, readOnly = true, canEditOthers = true)
        val lindas = Note(7, "x", "- [ ] a", createdBy = 2)
        val lockedLindas = lindas.copy(locked = true)

        assertTrue(emma.mayChange(lindas))
        assertFalse(child.mayChange(lindas))
        assertTrue(child.mayCheck(lindas))
        assertTrue(child.mayChange(Note(8, null, "x", createdBy = 4)))
        // A lock closes everything to everyone but the creator and admins, ticks included.
        assertFalse(emma.mayChange(lockedLindas))
        assertFalse(emma.mayCheck(lockedLindas))
        assertTrue(alex.mayChange(lockedLindas))
        assertTrue(alex.mayCheck(lockedLindas))
        // Read-only: nothing.
        assertFalse(reader.mayChange(Note(9, null, "x", createdBy = 5)))
        assertFalse(reader.mayCheck(lindas))
        // Visibility is the creator's alone, the lock also the admins'.
        assertFalse(alex.mayChangeVisibility(lindas))
        assertTrue(alex.mayChangeLock(lindas))
        assertFalse(emma.mayChangeLock(lindas))
        assertTrue(emma.mayChangeVisibility(null))
        assertTrue(emma.mayChangeLock(null))
    }

    @Test fun `a deleted category leaves every note`() {
        val after = NotesRules.withoutCategory(notes, 1)
        assertEquals(listOf(mine), after[0].categories)
        assertEquals(emptyList<NoteCategory>(), after[1].categories)
        assertTrue(after[2] === notes[2])
    }

    /** Upstream's test-note-categories.js. */
    @Test fun `category identity key folds case, compatibility forms and sharp s like the server`() {
        val k = NotesRules::categoryNameKey
        assertEquals(k("Česká"), k("česká"))
        assertEquals(k("Café"), k("Café"))
        assertEquals(k("Μάϊος"), k("ΜΆΪΟΣ"))
        assertEquals(k("Straße"), k("STRASSE"))
        assertEquals(k("ẞ"), k("ß"))
        assertEquals(k("ß"), k("SS"))
        assertEquals(k(k("ẞ")), k("ẞ"))
    }

    /** NOTES-A12: the picker suggests ignoring accents, but an exact match keeps them, per scope. */
    @Test fun `category picker suggestions, exact match and creation`() {
        val cats = listOf(home, mine, NoteCategory(4, "Café", NoteCategory.SCOPE_PERSONAL))
        assertEquals(listOf(4), NotesRules.suggestions(cats, listOf(1), "cafe").map { it.id })
        assertEquals(listOf(2, 4), NotesRules.suggestions(cats, listOf(1), "").map { it.id })
        assertEquals(2, NotesRules.suggestions(cats, emptyList(), "", limit = 2).size)
        assertNull(NotesRules.exact(cats, "Cafe"))
        assertEquals(4, NotesRules.exact(cats, " café ")?.id)
        assertNull(NotesRules.exact(cats, "home", NoteCategory.SCOPE_PERSONAL))
        assertFalse(NotesRules.canCreate(cats, "HOME", NoteCategory.SCOPE_HOUSEHOLD))
        assertTrue(NotesRules.canCreate(cats, "HOME", NoteCategory.SCOPE_PERSONAL))
        assertFalse(NotesRules.canCreate(cats, "  ", NoteCategory.SCOPE_PERSONAL))
    }
}

class NoteMarkdownTest {
    /** Upstream's test-notes-checklist.js. */
    @Test fun `checklist lines as upstream matches them`() {
        assertEquals(false, NoteMarkdown.matchChecklist("- [ ] Milch")?.checked)
        assertEquals(true, NoteMarkdown.matchChecklist("- [x] Milch")?.checked)
        assertEquals(true, NoteMarkdown.matchChecklist("- [X] Milch")?.checked)
        assertEquals(false, NoteMarkdown.matchChecklist("  * [ ] Milch")?.checked)
        assertEquals(false, NoteMarkdown.matchChecklist("+ [ ] Milch")?.checked)
        assertNull(NoteMarkdown.matchChecklist("- Milch"))
        assertNull(NoteMarkdown.matchChecklist("Text [ ] mitten drin"))
        assertNull(NoteMarkdown.matchChecklist(""))
        assertEquals("**Milch** kaufen", NoteMarkdown.matchChecklist("- [ ] **Milch** kaufen")?.text)
    }

    /** NOTES-A5: one line changes, everything else stays character for character. */
    @Test fun `toggle changes exactly one line`() {
        assertEquals("# Einkauf\n\n- [x] Milch\n- [ ] Brot\n- [x] Butter", NoteMarkdown.toggle("# Einkauf\n\n- [ ] Milch\n- [ ] Brot\n- [x] Butter", 2, true, null))
        assertEquals("- [ ] Milch", NoteMarkdown.toggle("- [x] Milch", 0, false, null))
        assertEquals("- [ ] eins\r\n  * [x] zwei\r\n- [ ] drei", NoteMarkdown.toggle("- [ ] eins\r\n  * [ ] zwei\r\n- [ ] drei", 1, true, null))
        assertEquals("- [x] Milch", NoteMarkdown.toggle("- [x] Milch", 0, true, null))
        assertNull(NoteMarkdown.toggle("# Einkauf\n- [ ] Milch", 0, true, null))
        assertNull(NoteMarkdown.toggle("- [ ] Milch", 9, true, null))
        assertNull(NoteMarkdown.toggle("- [ ] Milch", -1, true, null))
        assertNull(NoteMarkdown.toggle("- [x] Milch", 0, true, "- [ ] Milch"))
        assertEquals("- [ ] Milch\n- [x] Brot", NoteMarkdown.toggle("- [ ] Milch\n- [ ] Brot", 1, true, "- [ ] Brot"))
    }

    @Test fun `line numbers count source lines, blank ones and CRLF alike`() {
        val checks = NoteMarkdown.parse("# Einkauf\n\n- [ ] Milch\n- [x] Brot").filterIsInstance<NoteMarkdown.Check>()
        assertEquals(listOf(2 to false, 3 to true), checks.map { it.line to it.checked })
        assertEquals(3, NoteMarkdown.parse("\n\n\n- [ ] spaet").filterIsInstance<NoteMarkdown.Check>().single().line)
        assertEquals(2, NoteMarkdown.parse("a\r\nb\r\n- [ ] c").filterIsInstance<NoteMarkdown.Check>().single().line)
        assertEquals("- [ ] c", NoteMarkdown.expectAt("a\r\nb\r\n- [ ] c", 2))
        assertNull(NoteMarkdown.expectAt("a", 4))
    }

    @Test fun `blocks follow renderMarkdownLight`() {
        val blocks = NoteMarkdown.parse("# Title ##\n---\n> quoted\n- one\n1. first\n7. second\n\ntext\nmore")
        assertEquals(
            listOf(
                NoteMarkdown.Heading(1, "Title"),
                NoteMarkdown.Rule,
                NoteMarkdown.Quote("quoted"),
                NoteMarkdown.Bullet("one"),
                NoteMarkdown.Numbered(1, "first"),
                NoteMarkdown.Numbered(2, "second"),
                NoteMarkdown.Gap,
                NoteMarkdown.Paragraph(listOf("text", "more")),
            ),
            blocks,
        )
        assertEquals(emptyList<NoteMarkdown.Block>(), NoteMarkdown.parse(""))
    }

    @Test fun `inline marks, code first and safe links only`() {
        assertEquals(
            listOf(NoteMarkdown.Text("Milch", setOf(Mark.BOLD)), NoteMarkdown.Text(" kaufen", emptySet())),
            NoteMarkdown.inline("**Milch** kaufen"),
        )
        assertEquals(listOf(NoteMarkdown.Text("**a**", setOf(Mark.CODE))), NoteMarkdown.inline("`**a**`"))
        assertEquals(listOf(NoteMarkdown.Link("site", "https://example.com", emptySet())), NoteMarkdown.inline("[site](https://example.com)"))
        assertEquals(listOf(NoteMarkdown.Text("[x](javascript:alert)", emptySet())), NoteMarkdown.inline("[x](javascript:alert)"))
        assertEquals(
            listOf(NoteMarkdown.Text("a", setOf(Mark.STRIKE)), NoteMarkdown.Text(" ", emptySet()), NoteMarkdown.Text("b", setOf(Mark.ITALIC)),
                NoteMarkdown.Text(" ", emptySet()), NoteMarkdown.Text("c", setOf(Mark.UNDERLINE))),
            NoteMarkdown.inline("~~a~~ *b* <u>c</u>"),
        )
        assertEquals("Milch kaufen", NoteMarkdown.plain("**Milch** [kaufen](https://x.y)"))
    }
}

class MarkdownFormatTest {
    private val words = MarkdownFormat.Placeholders("Text", "Code", "Link text", "url")
    private fun apply(text: String, format: Format, start: Int = text.length, end: Int = start) =
        MarkdownFormat.apply(MarkdownFormat.Edit(text, start, end), format, words).text

    /** Upstream's test-markdown-toolbar.js. */
    @Test fun `toolbar writes what the renderer reads`() {
        assertEquals("**Milch** kaufen", apply("Milch kaufen", Format.BOLD, 0, 5))
        assertEquals("*Milch* kaufen", apply("Milch kaufen", Format.ITALIC, 0, 5))
        assertEquals("<u>Milch</u> kaufen", apply("Milch kaufen", Format.UNDERLINE, 0, 5))
        assertEquals("~~Milch~~ kaufen", apply("Milch kaufen", Format.STRIKETHROUGH, 0, 5))
        assertEquals("`Milch` kaufen", apply("Milch kaufen", Format.CODE, 0, 5))
        assertEquals("- ", apply("", Format.LIST))
        assertEquals("1. ", apply("", Format.ORDERED_LIST))
        assertEquals("- [ ] ", apply("", Format.CHECKLIST))
        assertEquals("> ", apply("", Format.QUOTE))
        assertEquals("Einkauf\n- [ ] ", apply("Einkauf", Format.CHECKLIST))
        assertEquals("Einkauf\n- ", apply("Einkauf", Format.LIST))
        val list = "Milch\nBrot\nButter"
        assertEquals("- [ ] Milch\n- [ ] Brot\n- [ ] Butter", apply(list, Format.CHECKLIST, 0, list.length))
        assertEquals("- Milch\n- Brot\n- Butter", apply(list, Format.LIST, 0, list.length))
        assertEquals("> Milch\n> Brot\n> Butter", apply(list, Format.QUOTE, 0, list.length))
        assertEquals("- [ ] Milch\n- [ ] Brot", apply("- [ ] Milch\nBrot", Format.CHECKLIST, 0, 16))
        assertEquals("1. Milch\n2. Brot", apply("3. Milch\nBrot", Format.ORDERED_LIST, 0, 13))
        assertEquals("## Titel", apply("Titel", Format.HEADING, 5))
        assertEquals("### Titel", apply("## Titel", Format.HEADING, 8))
        assertEquals("Titel", apply("### Titel", Format.HEADING, 9))
        assertEquals("Text\n\n---\n\n", apply("Text", Format.DIVIDER))
    }

    @Test fun `bold without a selection selects the placeholder, a link selects what is left to fill`() {
        val bold = MarkdownFormat.apply(MarkdownFormat.Edit("", 0, 0), Format.BOLD, words)
        assertEquals("**Text**", bold.text)
        assertEquals(2 to 6, bold.start to bold.end)
        val link = MarkdownFormat.apply(MarkdownFormat.Edit("Rezept", 0, 6), Format.LINK, words)
        assertEquals("[Rezept](url)", link.text)
        assertEquals("url", link.text.substring(link.start, link.end))
        val empty = MarkdownFormat.apply(MarkdownFormat.Edit("", 0, 0), Format.LINK, words)
        assertEquals("Link text", empty.text.substring(empty.start, empty.end))
    }
}

class NoteBodiesTest {
    /** NOTES-A6: the body the web editor sends; visibility and lock only when the member may set them. */
    @Test fun `save body`() {
        val form = NoteForm(title = "  ", content = " - [ ] Milk \n", categoryIds = listOf(3, 1), color = "#C7DED9", pinned = true,
            visibility = Note.VISIBILITY_PRIVATE, locked = true)
        val body = NoteBodies.body(form, sendVisibility = true, sendLock = false)
        assertEquals(JsonNull, body["title"])
        assertEquals(JsonPrimitive("- [ ] Milk"), body["content"])
        assertEquals(JsonPrimitive(1), body["pinned"])
        assertEquals(listOf(3, 1), body["category_ids"]!!.jsonArray.map { (it as JsonPrimitive).content.toInt() })
        assertEquals(JsonPrimitive("private"), body["visibility"])
        assertFalse("locked" in body)
        assertEquals(JsonPrimitive(1), NoteBodies.body(form, sendVisibility = false, sendLock = true)["locked"])
        assertFalse("visibility" in NoteBodies.body(form, sendVisibility = false, sendLock = true))
    }

    @Test fun `form from a note, and when More details opens by itself`() {
        val note = ApiJson.decodeFromString(Note.serializer(),
            """{"id":5,"title":"T","content":"c","color":"#0EA5E9","pinned":1,"locked":0,"visibility":"all","created_by":1,
               "categories":[{"id":4,"name":"Home","scope":"household"}],"creator_name":"Alex"}""")
        val form = NoteForm.of(note)
        assertEquals(listOf(4), form.categoryIds)
        assertEquals("#0EA5E9", form.color)
        assertTrue(form.detailsOpen)
        assertFalse(NoteForm().detailsOpen)
        assertFalse(NoteColors.isPalette("#0EA5E9"))
        assertTrue(NoteColors.isPalette("#efe3be"))
    }

    @Test fun `notes and categories decode from the server's shapes`() {
        val notes = NotesApi.decodeNotes(ApiJson.parseToJsonElement(
            """{"data":[{"id":1,"title":null,"content":"x","color":"#EFE3BE","pinned":0,"created_by":2,"creator_name":"Linda",
               "creator_avatar":"/api/v1/people/2/avatar?v=abc","visibility":"private","locked":1,"categories":[]}]}"""))
        assertTrue(notes.single().isPrivate)
        assertTrue(notes.single().locked)
        val cats = NotesApi.decodeCategories(ApiJson.parseToJsonElement(
            """{"data":[{"id":1,"name":"Home","scope":"household","owner_user_id":null,"sort_order":0}],"meta":{"can_manage_household":true}}"""))
        assertTrue(cats.canManageHousehold)
        assertFalse(cats.categories.single().isPersonal)
        assertFalse(NotesApi.decodeCategories(ApiJson.parseToJsonElement("""{"data":[]}""")).canManageHousehold)
    }
}

class NoteErrorsTest {
    private fun http(status: Int, reason: String? = null) = ApiProblem.Http(status, reason, "English text")

    /** NOTES-A10: upstream's sentences, never the server's. */
    @Test fun `errors map to upstream's sentences`() {
        assertEquals("notes.errorLocked", NoteErrors.key(http(403, "note_locked")))
        assertEquals("common.errorNotOwner", NoteErrors.key(http(403, "not_owner")))
        assertEquals("android.notes.moduleReadOnly", NoteErrors.key(http(403, "module_read_only")))
        assertEquals("common.errorNoPermission", NoteErrors.key(http(403)))
        assertEquals("notes.errorNoteGone", NoteErrors.key(http(404, "notes_note_not_found")))
        assertEquals("notes.errorSelectedCategoryGone", NoteErrors.key(http(400, "notes_selected_category_not_found")))
        assertEquals("category.errorExists", NoteErrors.key(http(409, "category_exists")))
        assertEquals("common.errorRateLimited", NoteErrors.key(http(429)))
        assertEquals("common.errorGeneric", NoteErrors.key(http(400)))
        assertEquals("android.error.unsupported", NoteErrors.key(http(405)))
        assertEquals("common.errorOfflineMutation", NoteErrors.key(ApiProblem.Network(IOException("x"))))
        assertTrue(NoteErrors.isGone(http(404, "notes_note_not_found")))
        assertFalse(NoteErrors.isGone(http(403, "notes_note_not_found")))
        assertTrue(NoteErrors.isCheckConflict(http(409, "stale")))
    }

    @Test fun `operations match the snapshot paths`() {
        assertEquals("/api/v1/notes/7/check", NotesOperation.NOTE_CHECK.path(mapOf("id" to 7)))
        assertEquals(11, NotesOperation.entries.size)
        assertTrue(NotesOperation.entries.all { it.template.startsWith("/api/v1/notes") })
    }
}
