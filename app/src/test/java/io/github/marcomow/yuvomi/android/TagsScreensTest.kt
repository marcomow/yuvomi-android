package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import io.github.marcomow.yuvomi.feature.tags.TagIndex
import io.github.marcomow.yuvomi.feature.tags.TagStore
import io.github.marcomow.yuvomi.feature.tags.TaggedItem
import io.github.marcomow.yuvomi.feature.tags.TagsRoutes
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/tags.md: the Tags list and a tag's page against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class TagsScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private lateinit var strings: Strings
    private lateinit var container: AppContainer

    /** Everything but Tasks, Notes, Shopping and Pantry is switched off, to keep the script short. */
    private val disabled = """["calendar","schedule","meals","documents","health","budget"]"""
    private var taskTags = """[{"tag":"Garden","count":1}]"""

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB","role":"admin"},
        "permissions":{"admin":true},"householdSize":3,"csrfToken":"t"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val call = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$call ${request.body?.utf8().orEmpty()}" else reads += "$call?${request.url.encodedQuery}"
                return when (call) {
                    "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    "GET /api/v1/auth/me" -> json(payload)
                    "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null,"disabled_modules":$disabled}}""")
                    "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    "GET /api/v1/tasks" -> json(
                        """{"data":[{"id":7,"title":"Plant tomatoes","tags":["Garden"],"status":"open"},
                        {"id":8,"title":"Fix fence #garden","tags":[],"status":"done"}]}""",
                    )
                    "GET /api/v1/tasks/tags" -> json("""{"data":$taskTags}""")
                    "DELETE /api/v1/tasks/tags/Garden" -> json("""{"data":{"updated":1,"skipped":0,"tags":[]}}""").also { taskTags = "[]" }
                    "GET /api/v1/notes" -> json("""{"data":[{"id":3,"title":"Seeds","content":"Buy #garden seeds and #compost"}]}""")
                    "GET /api/v1/shopping" -> json("""{"data":[{"id":2,"name":"Weekly"}]}""")
                    "GET /api/v1/shopping/2/items" -> json("""{"data":[{"id":20,"name":"Soil","tags":["garden"]}]}""")
                    "GET /api/v1/pantry" -> json("""{"error":"Internal server error.","code":500}""", code = 500)
                    else -> json("""{"error":"Not found"}""", code = 404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun signIn() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        compose.offMain {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
    }

    private fun openTags(waitFor: String) {
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.more")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.more")).onFirst().performClick()
        compose.onNodeWithText(strings.t("android.tags.title")).performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
            println("READS: $reads")
            throw e
        }
    }

    /** TAGS-A1, A2, A3, A4: counts across modules, the failure note, search, a tag's page by module. */
    @Test fun `lists tags across modules and opens one`() {
        signIn()
        openTags("#garden")
        // Garden (task tag) + #garden (task text, note) + garden (shopping item tag): one tag, the most used spelling.
        compose.onNodeWithText("#Garden").assertDoesNotExist()
        compose.onNodeWithText("#compost").assertExists()
        // Pantry answers 500; sources this test does not script (404) are named too.
        val notLoaded = strings.t("android.tags.notLoaded", "modules" to "\u0000").substringBefore("\u0000")
        compose.onNode(hasText(notLoaded, substring = true) and hasText(strings.t("nav.pantry"), substring = true)).assertExists()
        // Every task status, later starts included.
        val tasksRead = reads.first { it.startsWith("GET /api/v1/tasks?") }
        assertTrue(tasksRead, tasksRead.contains("status=open") && tasksRead.contains("status=done") && tasksRead.contains("include_future=1") && tasksRead.contains("archived=1"))

        compose.onNode(hasSetTextAction()).performTextInput("comp")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("#garden").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("#compost").assertExists()
        compose.onNodeWithText(strings.t("android.tags.find", "tag" to "comp")).assertExists()

        compose.onNodeWithText("#compost").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Seeds").fetchSemanticsNodes().isNotEmpty() }
        // The note's other tag, as a chip.
        compose.onNodeWithContentDescription(strings.t("android.tags.open", "tag" to "garden")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Plant tomatoes").fetchSemanticsNodes().isNotEmpty() }
        listOf("Seeds", "Soil").forEach { compose.onNodeWithText(it).assertExists() }
        // TAGS-R14: the done task is hidden and counted, until completed items are shown.
        compose.onAllNodesWithText("Fix fence #garden").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText(strings.t("android.tags.doneHidden", "count" to 1)).assertExists()
        compose.onNodeWithText(strings.t("android.tags.showDone")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Fix fence #garden").fetchSemanticsNodes().isNotEmpty() }

        // A task opens the task.
        compose.onNodeWithText("Plant tomatoes").performClick()
        compose.waitUntil(10_000) { reads.any { it.startsWith("GET /api/v1/tasks/7") } }
    }

    /** TAGS-A2: a typed tag nobody uses opens an empty page. */
    @Test fun `find opens any typed tag`() {
        signIn()
        openTags("#garden")
        compose.onNode(hasSetTextAction()).performTextInput("#lawn")
        compose.onNodeWithText(strings.t("android.tags.find", "tag" to "lawn")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("android.tags.noItems", "tag" to "lawn")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** TAGS-A5: a real task tag can be removed everywhere, with upstream's confirmation and message. */
    @Test fun `deletes a task tag`() {
        signIn()
        openTags("#garden")
        compose.onNodeWithText("#garden").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Plant tomatoes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "garden")).performClick()
        compose.onNodeWithText(strings.t("tasks.tagDeleteLabel", "tag" to "Garden")).performClick()
        compose.onNodeWithText(strings.t("tasks.tagDeleteConfirm", "tag" to "Garden", "count" to 1)).assertExists()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("DELETE /api/v1/tasks/tags/Garden") } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("tasks.tagDeleted", "count" to 1)).fetchSemanticsNodes().isNotEmpty() }
        // The server's fresh list has no task tag left: no more management for this tag.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(strings.t("tasks.tagDeleteLabel", "tag" to "Garden")).fetchSemanticsNodes().isEmpty()
        }
    }

    /** TAGS-A7, ADR 0007: when no source answers, the last index is shown with the offline banner. */
    @Test fun `shows the cached index when nothing answers`() {
        signIn()
        val cached = listOf(TaggedItem("notes", "note:1", "Cached note", null, setOf("winter"), "notes/1"))
        container.cache.write(TagStore.CACHE_KEY, TagIndex.encode(cached))
        server.close() // every source now fails for want of a connection
        openTags("#winter")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(strings.t("offline.staleData", "time" to "x").substringBefore("x").trim(), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("#winter").assertExists()
    }

    @Test fun `tag routes drop the hash and encode`() {
        assertEquals("tags/garden", TagsRoutes.tag("#garden"))
        assertEquals("tags/Haus%2C%20Hof%2Fx", TagsRoutes.tag("Haus, Hof/x"))
    }
}
