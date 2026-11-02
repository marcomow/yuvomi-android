package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/schedule.md: the tabs, the overview grid, deletes and the read-only rules against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class ScheduleScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private var permissions = """{"admin":true}"""
    private var role = "admin"
    private lateinit var strings: Strings
    private val today = LocalDate.now(ZoneOffset.UTC).toString()

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload get() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB","role":"$role"},
        "permissions":$permissions,"householdSize":3,"csrfToken":"t"}"""

    private val early = """{"id":1,"name":"Early shift","short_code":"E","start_time":"06:00","end_time":"14:00","color":"#0891B2","icon":"sunrise","created_by":2,"fields":[]}"""
    private val vacation = """{"id":2,"name":"Vacation","short_code":"V","start_time":null,"end_time":null,"color":"#78808C","icon":"tree-palm","created_by":1,"fields":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}".also {
                if (request.method != "GET") writes += "$it?${request.url.query.orEmpty()} ${request.body?.utf8().orEmpty()}" else reads += "$it?${request.url.query.orEmpty()}"
            }) {
                "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                "POST /api/v1/auth/login" -> json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                "GET /api/v1/auth/me" -> json(payload)
                "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null,"week_start":"monday"}}""")
                "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                "GET /api/v1/auth/users" -> json("""{"data":[{"id":1,"username":"alex","display_name":"Alex Johnson","sort_order":0},{"id":2,"username":"linda","display_name":"Linda Johnson","sort_order":1}]}""")
                "GET /api/v1/schedule/household-members" -> json("""{"data":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Linda Johnson"}]}""")
                "GET /api/v1/schedule/shift-types" -> json("""{"data":[$early,$vacation]}""")
                "GET /api/v1/schedule/custom-fields" -> json("""{"data":[]}""")
                "GET /api/v1/schedule/patterns" -> json("""{"data":[{"id":5,"user_id":1,"name":"Rotation","anchor_date":"$today","cycle_length":7,"valid_from":null,"valid_until":null,"is_active":1}]}""")
                "GET /api/v1/schedule/patterns/5/days" -> json("""{"data":[{"id":9,"pattern_id":5,"position":0,"shift_type_id":1,"field_values":{}}]}""")
                "GET /api/v1/schedule/overrides" -> json("""{"data":[{"id":3,"user_id":1,"date_key":"$today","shift_type_id":2,"note":"Beach","field_values":{}}]}""")
                "GET /api/v1/schedule/extras" -> json("""{"data":[]}""")
                "GET /api/v1/schedule/preferences" -> json("""{"data":{"reminderOffsetMinutes":null,"weeklyHours":null,"overtimeEnabled":true}}""")
                "GET /api/v1/schedule/entries" -> json(
                    """{"data":{"entries":[
                    {"user_id":1,"date_key":"$today","source":"pattern","pattern_id":5,"position":0,"pattern_day_id":9,"shift_type_id":1,"is_free":false,"shift_type":$early,"field_values":{}}
                    ],"warnings":[]}}""",
                )
                "GET /api/v1/calendar/holidays" -> json("""{"data":[]}""")
                "DELETE /api/v1/schedule/patterns/5" -> MockResponse.Builder().code(204).build()
                else -> json("""{"error":"Not found"}""", code = 404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openSchedule() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.more")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.more")).onFirst().performClick()
        compose.onNodeWithText(strings.t("nav.schedule")).performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("schedule.shiftTypes")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** SCHED-A15: the page opens on the overview with the member's own lane, and a block opens its details. */
    @Test fun `overview shows the week grid and an entry's details`() {
        openSchedule()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("E · Early shift · Alex Johnson")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(reads.toString(), reads.any { it.startsWith("GET /api/v1/schedule/entries?from=") && !it.contains("user_id") })
        compose.onNodeWithContentDescription(strings.t("schedule.overviewPeopleCount", "count" to 1)).assertExists()
        compose.onNodeWithContentDescription("E · Early shift · Alex Johnson").performClick()
        waitFor(strings.t("schedule.entryDetailTitle"))
        compose.onNodeWithText("${strings.t("schedule.pattern")} · Rotation").assertExists()
    }

    /** SCHED-A16: shift types list with their spans; a type someone else made names its creator to a member. */
    @Test fun `shift types tab lists types`() {
        role = "member"
        permissions = """{"admin":false}"""
        openSchedule()
        compose.onNodeWithText(strings.t("schedule.shiftTypes")).performClick()
        waitFor("E · Early shift")
        compose.onNodeWithText("V · Vacation").assertExists()
        compose.onNode(hasText(strings.t("schedule.typeOwnedBy", "user" to "Linda Johnson"), substring = true)).assertExists()
        // Vacation is the member's own: it has a menu; Early is not.
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "V · Vacation")).assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription(strings.t("common.moreActionsNamed", "name" to "E · Early shift"))).fetchSemanticsNodes().isEmpty())
    }

    /** SCHED-A17: deleting a plan asks first and then sends the delete. */
    @Test fun `planning deletes a plan after asking`() {
        openSchedule()
        compose.onNodeWithText(strings.t("schedule.planning")).performClick()
        waitFor("Rotation")
        compose.onNodeWithText("V · Vacation").assertExists()
        compose.onNodeWithContentDescription(strings.t("schedule.addPlan")).assertExists()
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Rotation")).performClick()
        compose.onNodeWithText(strings.t("schedule.delete")).performClick()
        waitFor(strings.t("schedule.deletePatternTitle", "name" to "Rotation"))
        compose.onAllNodesWithText(strings.t("schedule.delete")).onFirst().performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("DELETE /api/v1/schedule/patterns/5") } }
    }

    /** SCHED-A18: read-only members see the plans but no add, no menus. */
    @Test fun `read-only members can look but not change`() {
        role = "member"
        permissions = """{"admin":false,"modules":{"schedule":"read"}}"""
        openSchedule()
        compose.onNodeWithText(strings.t("schedule.planning")).performClick()
        waitFor("Rotation")
        assertTrue(compose.onAllNodes(hasContentDescription(strings.t("schedule.addPlan"))).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription(strings.t("common.moreActionsNamed", "name" to "Rotation"))).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(strings.t("common.showDetails") + ": Rotation").assertExists()
        assertTrue(writes.toString(), writes.none { it.contains("/schedule") })
    }

    /** SCHED-A19: statistics count the member's shifts for the current month. */
    @Test fun `statistics show totals`() {
        openSchedule()
        compose.onNodeWithText(strings.t("schedule.statistics")).performClick()
        compose.waitUntil(10_000) { reads.any { it.contains("/schedule/entries") && it.contains("user_id=1") } }
        waitFor(strings.t("schedule.totalHours"))
        compose.onAllNodesWithText(strings.t("schedule.hoursValue", "value" to "8")).onFirst().assertExists()
    }
}
