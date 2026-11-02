package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/notes.md: the board, a note, the editor and the read-only rules against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class NotesScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private lateinit var strings: Strings

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload get() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB","role":"admin"},
        "permissions":$permissions,"householdSize":3,"csrfToken":"t"}"""

    private val holiday = """{"id":1,"title":"Holiday","content":"- [ ] Passports\n- [x] Insurance","color":"#0EA5E9","pinned":1,
        "created_by":1,"creator_name":"Alex Johnson","creator_color":"#2563EB","visibility":"all","locked":0,
        "categories":[{"id":4,"name":"Travel","scope":"household"}]}"""
    private val shopping = """{"id":2,"title":"Groceries","content":"Milk and bread","color":"#EFE3BE","pinned":0,
        "created_by":2,"creator_name":"Linda","creator_color":"#DB2777","visibility":"all","locked":0,"categories":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}".also {
                if (request.method != "GET") writes += "$it ${request.body?.utf8().orEmpty()}"
            }) {
                "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                "GET /api/v1/auth/me" -> json(payload)
                "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
                "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                "GET /api/v1/notes" -> json("""{"data":[$holiday,$shopping]}""")
                "GET /api/v1/notes/categories" -> json("""{"data":[{"id":4,"name":"Travel","scope":"household","sort_order":0}],"meta":{"can_manage_household":true}}""")
                "PATCH /api/v1/notes/1/check" -> json("""{"data":${holiday.replace("- [ ] Passports", "- [x] Passports")}}""")
                "PATCH /api/v1/notes/1/pin" -> json("""{"data":{"id":1,"pinned":0}}""")
                "DELETE /api/v1/notes/2" -> MockResponse.Builder().code(204).build()
                else -> json("""{"error":"Not found"}""", code = 404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openBoard() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.more")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.more")).onFirst().performClick()
        compose.onNodeWithText(strings.t("nav.notes")).performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Groceries").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    /** NOTES-A4, A5, A11. */
    @Test fun `board groups pinned notes, ticks a line, pins and searches`() {
        openBoard()
        compose.onNodeWithText(strings.t("notes.groupPinned").uppercase()).assertExists()
        compose.onNodeWithText(strings.t("notes.groupOthers").uppercase()).assertExists()
        assertTrue(compose.onAllNodesWithText("Travel").fetchSemanticsNodes().size >= 2) // filter chip and badge

        // Ticking a checklist line on the card: line index and the line as shown.
        compose.onNodeWithContentDescription("Passports").performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("PATCH /api/v1/notes/1/check") } }
        val check = writes.first { it.startsWith("PATCH /api/v1/notes/1/check") }
        assertTrue(check, check.contains("\"line\":0") && check.contains("\"checked\":true") && check.contains("\"expect\":\"- [ ] Passports\""))

        // Unpinning moves the note out of the pinned group; the headers go with it.
        compose.onNodeWithContentDescription(strings.t("notes.unpinAction")).performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("PATCH /api/v1/notes/1/pin") } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("notes.groupPinned").uppercase()).fetchSemanticsNodes().isEmpty() }

        // Search narrows the board to title or text matches.
        compose.onNode(hasSetTextAction()).performTextInput("milk")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Holiday").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Groceries").assertExists()
    }

    /** NOTES-A7: delete hides the note at once; undo brings it back and nothing is deleted. */
    @Test fun `delete offers undo`() {
        openBoard()
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Groceries")).performScrollTo().performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Groceries").fetchSemanticsNodes().isEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
            println("WRITES: $writes")
            throw e
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Groceries").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(writes.any { it.startsWith("DELETE") })
    }

    /** NOTES-A6: a card opens the note's read view; Edit opens the editor with the note's text. */
    @Test fun `a note opens to read, then to edit`() {
        openBoard()
        compose.onNodeWithText("Milk and bread").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(strings.t("common.edit")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(strings.t("common.edit")).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Milk and bread")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.t("notes.editNote")).assertExists()
        compose.onNodeWithContentDescription(strings.t("markdown.checklist")).assertExists()

        // The read tab shows the text rendered, without a field.
        compose.onNodeWithText(strings.t("notes.modeRead")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Milk and bread").assertExists()
    }

    /** NOTES-A9: with `notes: read` the board has no add, pin, menu or tick; a pinned note keeps its pin as a sign. */
    @Test fun `read-only members see signs, not controls`() {
        permissions = """{"admin":false,"modules":{"notes":"read"}}"""
        openBoard()
        compose.onAllNodes(hasContentDescription(strings.t("notes.addNoteLabel"))).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(strings.t("notes.unpinAction"))).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(strings.t("notes.pinAction"))).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription(strings.t("common.moreActionsNamed", "name" to "Groceries"))).assertCountEquals(0)
        compose.onNodeWithContentDescription(strings.t("notes.pinnedState")).assertExists()
        compose.onNodeWithContentDescription("Passports: ${strings.t("notes.checklistOpen")}").assertExists()
        compose.onNodeWithContentDescription("Passports: ${strings.t("notes.checklistOpen")}").performClick()
        compose.waitForIdle()
        assertTrue(writes.toString(), writes.none { it.contains("/notes") })
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    val size = fetchSemanticsNodes().size
    assertTrue("expected $n nodes, found $size", size == n)
}
