package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/documents.md: list, facets, viewer, delete and the read-only rules against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class DocumentsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private var countdowns = "[]"
    private lateinit var strings: Strings

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload get() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB","role":"admin"},
        "permissions":$permissions,"householdSize":3,"csrfToken":"t"}"""

    private val lease = """{"id":1,"name":"Tenancy Agreement","description":"Signed lease","category":"home","status":"active",
        "visibility":"restricted","original_name":"lease.pdf","mime_type":"application/pdf","file_size":320000,"storage_provider":"local",
        "storage_backend":"local","folder_id":1,"folder_name":"Homes","created_by":1,"updated_at":"2026-10-30 08:00:00","allowed_member_ids":[2],
        "subject_user_id":2,"subject_name":"Linda","subject_color":"#16A34A"}"""
    private val notes = """{"id":2,"name":"Packing list","description":"For the trip","category":"travel","status":"active",
        "visibility":"family","original_name":"packing.txt","mime_type":"text/plain","file_size":120,"storage_provider":"local",
        "storage_backend":"local","folder_id":null,"created_by":2,"updated_at":"2026-10-29 08:00:00","expires_at":"2020-01-01",
        "expiry_reminder_days":3,"allowed_member_ids":[],"subject_user_id":3,"subject_name":"Emma"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}".also {
                if (request.method != "GET") writes += "$it ${request.body?.utf8().orEmpty()}" else reads += "$it?${request.url.query.orEmpty()}"
            }) {
                "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.84.0"}""")
                "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                "GET /api/v1/auth/me" -> json(payload)
                "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
                "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[],"countdowns":$countdowns,"countdownTotal":1}""")
                "GET /api/v1/family/members" -> json("""{"data":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Linda"},{"id":3,"display_name":"Emma"}]}""")
                "GET /api/v1/auth/users" -> json("""{"data":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Linda"}]}""")
                "GET /api/v1/documents/meta/options" -> json("""{"data":{"max_file_size":5242880,"is_admin":${permissions.contains("true")},"dms_accounts":[]}}""")
                "GET /api/v1/documents/folders" -> json("""{"data":[{"id":1,"name":"Homes","parent_id":null}]}""")
                "GET /api/v1/documents" -> if (request.url.queryParameter("status") == "archived") json("""{"data":[]}""") else json("""{"data":[$lease,$notes]}""")
                "GET /api/v1/documents/2/preview" -> MockResponse.Builder().code(200).addHeader("Content-Type", "text/plain").body("Socks\nPassport").build()
                "DELETE /api/v1/documents/1" -> MockResponse.Builder().code(204).build()
                else -> json("""{"error":"Not found"}""", code = 404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openDocuments() {
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
        compose.onNodeWithText(strings.t("nav.documents")).performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Packing list").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    /** DOCS-A2, A3: the list, the active status on the server, the chips and the search. */
    @Test fun `list shows rows with their facets and searches`() {
        openDocuments()
        assertTrue(reads.toString(), reads.any { it == "GET /api/v1/documents?status=active" })
        compose.onNodeWithText("Tenancy Agreement").assertExists()
        // The expired deadline chip and the category chips with counts.
        compose.onNodeWithText("${strings.t("documents.expiringFilterLabel")}  1").assertExists()
        compose.onNodeWithText("${strings.t("documents.allCategories")}  2").assertExists()
        compose.onNodeWithText("${strings.t("documents.category.home")}  1").assertExists()
        // Restricted visibility is named on the row; the default is not.
        compose.onNodeWithText(strings.t("documents.visibility.restricted")).assertExists()

        compose.onNodeWithText("${strings.t("documents.expiringFilterLabel")}  1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tenancy Agreement").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("${strings.t("documents.expiringFilterLabel")}  1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tenancy Agreement").fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasSetTextAction()).performTextInput("lease")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Packing list").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Tenancy Agreement").assertExists()
    }

    /** DOCS-A11: a text document opens inline; a document of someone else shows its details read-only. */
    @Test fun `a row opens the viewer with the text and the details`() {
        openDocuments()
        compose.onNodeWithText("Packing list").performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Socks\nPassport").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
            throw e
        }
        assertTrue(reads.any { it.startsWith("GET /api/v1/documents/2/preview") })
        // The viewer line names the expiry date; admins manage everything, so the edit pencil shows.
        compose.onNodeWithContentDescription(strings.t("common.editNamed", "name" to "Packing list")).assertExists()
        compose.onNodeWithContentDescription(strings.t("documents.viewerPrevNamed", "name" to "Tenancy Agreement")).assertExists()
    }

    /** DOCS-A12: delete asks, hides the row at once, offers undo, and deletes when the window closes. */
    @Test fun `delete from the row menu asks and offers undo`() {
        openDocuments()
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Tenancy Agreement")).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("documents.bulkDeleteConfirmDetail")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("common.delete")).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tenancy Agreement").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tenancy Agreement").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(writes.toString(), writes.none { it.startsWith("DELETE") })
    }

    /** DOCS-A13: with `documents: read` there is no add, no row menu and no selection; viewing stays. */
    @Test fun `read-only members can look but not change`() {
        permissions = """{"admin":false,"modules":{"documents":"read"}}"""
        openDocuments()
        assertTrue(compose.onAllNodes(hasContentDescription(strings.t("documents.addButton"))).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription(strings.t("common.moreActionsNamed", "name" to "Tenancy Agreement"))).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("documents.downloadNamed", "name" to "Tenancy Agreement")).assertExists()
        compose.onNodeWithText("Packing list").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Socks\nPassport").fetchSemanticsNodes().isNotEmpty() }
        // The read view: description and the reminder lead time of someone else's document.
        compose.onNodeWithText("For the trip").assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription(strings.t("common.editNamed", "name" to "Packing list"))).fetchSemanticsNodes().isEmpty())
        assertTrue(writes.toString(), writes.none { it.contains("/documents") })
    }

    /** DOCS-A19: the person chip filters by who a document is about; rows name the person. */
    @Test fun `the person filter keeps one person's documents`() {
        openDocuments()
        // DOCS-R37: the person stands on the row.
        compose.onAllNodesWithText("Emma").onFirst().assertExists()
        compose.onNodeWithText(strings.t("documents.personFilterLabel")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("documents.personFilterAll")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Linda") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Packing list").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Tenancy Agreement").assertExists()
        // The category chips count under the person.
        compose.onNodeWithText("${strings.t("documents.allCategories")}  1").assertExists()
        // The chip names the person; "All people" shows everyone again.
        compose.onNodeWithContentDescription("${strings.t("documents.personFilterLabel")}: Linda").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("documents.personFilterAll")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("documents.personFilterAll")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Packing list").fetchSemanticsNodes().isNotEmpty() }
    }

    /** OVERVIEW-A12, A13: an expiring document stands in "Key dates" with its person and opens in the viewer. */
    @Test fun `a document in key dates opens the viewer`() {
        countdowns = """[{"source":"document","id":2,"title":"Packing list","date":"2026-11-05","days_until":3,"icon":"file-text",
            "color":null,"recurring":false,"person_name":"Emma"}]"""
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("dashboard.countdownTitle")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("dashboard.daysLeft", "count" to 3)).assertExists()
        compose.onNodeWithText("Emma", substring = true).assertExists()
        compose.onNodeWithText("Packing list").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Socks\nPassport").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
            throw e
        }
    }

    /** DOCS-A5: the folder chip opens the tree; a folder filters the list and shows its path. */
    @Test fun `the folder sheet filters by folder`() {
        openDocuments()
        compose.onNodeWithText(strings.t("documents.allDocuments")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("documents.folderBrowserTitle")).fetchSemanticsNodes().isNotEmpty() }
        // The row hides the folder (it repeats the category), so the only "Homes" is the sheet's row.
        compose.onNodeWithText("Homes").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Packing list").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Tenancy Agreement").assertExists()
    }
}
