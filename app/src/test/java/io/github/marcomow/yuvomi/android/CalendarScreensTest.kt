package io.github.marcomow.yuvomi.android

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
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

/** docs/spec/calendar.md: the views, the read view, the form and the read-only rules against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class CalendarScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private lateinit var strings: Strings
    private val today = LocalDate.now(ZoneOffset.UTC)

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload get() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB","role":"admin"},
        "permissions":$permissions,"householdSize":3,"csrfToken":"t"}"""

    private val dentist get() = """{"id":11,"title":"Dentist","start_datetime":"${today}T09:00","end_datetime":"${today}T10:00","all_day":0,
        "location":"Main St 1","created_by":1,"creator_name":"Alex Johnson","visibility":"all","local_calendar_id":1,
        "assigned_users":[{"id":1,"display_name":"Alex Johnson","color":"#2563EB"}]}"""
    private val swim get() = """{"id":12,"title":"Swimming","start_datetime":"${today}T17:00","end_datetime":"${today}T18:00","all_day":0,
        "recurrence_rule":"FREQ=WEEKLY","series_id":12,"recurrence_id":"${today}T17:00:00","is_local_recurring_series":true,
        "can_override_occurrence":true,"created_by":2,"creator_name":"Linda","visibility":"all","assigned_users":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}".also {
                if (request.method != "GET") writes += "$it ${request.body?.utf8().orEmpty()}" else reads += "$it?${request.url.encodedQuery}"
            }) {
                "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                "GET /api/v1/auth/me" -> json(payload)
                "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null,"week_start":"monday","calendar_default_duration":60}}""")
                "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                "GET /api/v1/calendar" -> json("""{"data":[$dentist,$swim]}""")
                "GET /api/v1/tasks" -> json("""{"data":[{"id":5,"title":"Pay rent","due_date":"$today","status":"open"}]}""")
                "GET /api/v1/calendar/holidays" -> json("""{"data":[]}""")
                "GET /api/v1/schedule/entries" -> json("""{"data":{"entries":[],"warnings":[]}}""")
                "GET /api/v1/waste/occurrences" -> json("""{"data":[]}""")
                "GET /api/v1/calendar/calendars" -> json("""{"data":[{"id":1,"name":"Yuvomi","color":"#007AFF","is_default":true,"event_count":2}],"sources":[]}""")
                "GET /api/v1/family/members" -> json("""{"data":[{"id":1,"display_name":"Alex Johnson","avatar_color":"#2563EB"},{"id":2,"display_name":"Linda Johnson","avatar_color":"#DB2777"}]}""")
                "GET /api/v1/reminders/all" -> json("""{"data":[]}""")
                "GET /api/v1/calendar/sync-targets" -> json("""{"data":{"google":[],"caldav":[],"outlook":[]}}""")
                "POST /api/v1/calendar" -> json("""{"data":{"id":20,"title":"Piano","start_datetime":"${today}T09:00"}}""", code = 201)
                "PUT /api/v1/reminders", "DELETE /api/v1/reminders" -> MockResponse.Builder().code(204).build()
                "DELETE /api/v1/calendar/11" -> MockResponse.Builder().code(204).build()
                else -> json("""{"error":"Not found"}""", code = 404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    /** Waits for a server-side effect; touching the tree lets the main looper run in between. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(10_000) {
        compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        condition()
    }

    private fun openCalendar() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        // Calendar is one of upstream's default bar slots (BarSlots).
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(strings.t("nav.calendar"))
        compose.waitUntil(10_000) { compose.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tab).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Dentist").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            println("READS: $reads")
            throw e
        }
    }

    /** CAL-A13: the month shows today's list with events, the task layer, and loads the grid's window. */
    @Test fun `month lists today and the agenda lists the window`() {
        openCalendar()
        compose.onNodeWithText("Swimming").assertExists()
        compose.onNodeWithText("Pay rent").assertExists()
        assertTrue(reads.toString(), reads.any { it.startsWith("GET /api/v1/calendar?from=") })
        assertTrue(reads.toString(), reads.any { it.startsWith("GET /api/v1/tasks?include_future=1") })
        compose.onNodeWithText(strings.t("calendar.viewAgenda")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("common.today")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dentist").assertExists()
        compose.onNodeWithText(strings.t("calendar.viewDay")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Dentist").fetchSemanticsNodes().isNotEmpty() }
    }

    /** CAL-A14: an event opens to its read view; a single event deletes with undo. */
    @Test fun `read view and delete with undo`() {
        openCalendar()
        compose.onNodeWithText("Dentist").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("calendar.openInMap")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Main St 1").assertExists()
        compose.onNodeWithContentDescription(strings.t("common.edit")).assertExists()
        compose.onNodeWithText(strings.t("common.delete")).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Dentist").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(writes.toString(), writes.any { it.startsWith("DELETE") })
    }

    /** CAL-A15: deleting an occurrence of a local series asks which ones first. */
    @Test fun `a series asks the scope before deleting`() {
        openCalendar()
        compose.onNodeWithText("Swimming").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("common.delete")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.delete")).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("calendar.recurringScopeThis")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("calendar.recurringScopeFollowing")).assertExists()
        compose.onNodeWithText(strings.t("calendar.recurringScopeSeries")).assertExists()
        compose.onNodeWithText(strings.t("common.cancel")).performClick()
        compose.waitForIdle()
        assertFalse(writes.toString(), writes.any { it.startsWith("DELETE") })
    }

    /** CAL-A16: the FAB opens the form; Add posts the event with an idempotency key, then its reminders. */
    @Test fun `new event posts the form body`() {
        openCalendar()
        compose.onNodeWithContentDescription(strings.t("calendar.newEvent")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        // Empty title: the form says so and sends nothing.
        compose.onNodeWithText(strings.t("common.add")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("calendar.titleRequired")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Piano")
        compose.onNodeWithText(strings.t("common.add")).performClick()
        until { writes.any { it.startsWith("POST /api/v1/calendar ") } }
        val post = writes.first { it.startsWith("POST /api/v1/calendar ") }
        assertTrue(post, post.contains("\"title\":\"Piano\"") && post.contains("\"start_datetime\":\"${today}T") && post.contains("\"all_day\":false"))
        assertTrue(post, post.contains("\"recurrence_rule\":null") && post.contains("\"icon\":\"calendar\""))
        try {
            until { writes.any { it.startsWith("DELETE /api/v1/reminders") || it.startsWith("PUT /api/v1/reminders") } }
        } catch (e: Throwable) {
            println("WRITES: $writes\nREADS: $reads")
            throw e
        }
    }

    /** CAL-A17: with `calendar: read` there is no add, edit or delete. */
    @Test fun `read-only members see no editing`() {
        permissions = """{"admin":false,"modules":{"calendar":"read"}}"""
        openCalendar()
        compose.onAllNodes(hasContentDescription(strings.t("calendar.newEvent"))).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText("Dentist").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("calendar.openInMap")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.edit")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText(strings.t("common.delete"))).fetchSemanticsNodes().isEmpty())
    }
}
