package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Drives the signed-in app through Overview, the task list, a task's detail and
 * the editor against a scripted server: the screens compose, read their data and
 * navigate without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class AppSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val today = LocalDate.now().toString()

    private fun json(body: String, cookie: String? = null) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","avatar_color":"#2563EB"},
        "permissions":{"admin":true},"householdSize":3,"csrfToken":"t"}"""
    private val task = """{"id":7,"title":"Take out the bins","status":"open","priority":"high","category":"household",
        "due_date":"$today","tags":["Garden"],"assigned_users":[{"id":2,"display_name":"Emma"}],"created_by":1,
        "description":"Remember:\n- [ ] paper\n- [x] glass","points":5,"is_recurring":1,"recurrence_rule":"FREQ=WEEKLY;BYDAY=MO",
        "subtasks":[{"id":8,"title":"Rinse","status":"open"}],"subtask_total":1,"subtask_done":0}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}") {
                "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                "POST /api/v1/auth/login" -> json(payload, "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                "GET /api/v1/auth/me" -> json(payload)
                "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
                "GET /api/v1/dashboard" -> json("""{"urgentTasks":[$task],"openTaskCount":4,"todayMeals":[{"meal_type":"dinner","title":"Lasagne"}],
                    "shoppingOpenCount":3,"users":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Emma"}],
                    "memberTodayTasks":[{"user_id":2,"open_count":1}],"birthdays":[{"name":"Leo","days_until":2,"next_age":7}],"birthdayTotal":1}""")
                "GET /api/v1/tasks" -> json("""{"data":[$task]}""")
                // Late on purpose: the editor's fields fill in only after the options and the task arrive.
                "GET /api/v1/tasks/meta/options" -> json("""{"users":[{"id":1,"display_name":"Alex Johnson"},{"id":2,"display_name":"Emma"}],
                    "categories":[{"key":"household","label_key":"tasks.categoryHousehold","sort_order":0},{"key":"misc","label_key":"tasks.categoryMisc","sort_order":7}],
                    "tags":[{"tag":"Garden","count":1}],"default_points":0}""")
                    .newBuilder().headersDelay(750, TimeUnit.MILLISECONDS).build()
                "GET /api/v1/tasks/7" -> json("""{"data":$task}""")
                // Late on purpose: the detail must not depend on comments arriving with the task.
                "GET /api/v1/tasks/7/comments" -> json("""{"data":[{"id":1,"task_id":7,"user_id":2,"comment":"@Alex Johnson done soon","created_at":"2026-11-01 08:00:00","author_name":"Emma"}]}""")
                    .newBuilder().headersDelay(750, TimeUnit.MILLISECONDS).build()
                else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun `overview, tasks, detail and editor render`() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        val container = AppContainer(app, PlainSecretBox())
        val strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }

        // Overview: the today sheet shows the due task and tonight's meal.
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Take out the bins").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
        compose.onNodeWithText("Lasagne").assertExists()
        compose.onAllNodesWithText(strings.t("dashboard.upcomingBirthdays")).onFirst().assertExists()

        // Tasks list.
        compose.onAllNodes(hasText(strings.t("nav.tasks"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("tasks.categoryHousehold")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Take out the bins").onFirst().performClick()

        // Detail: description checklist, subtask, comment with a mention, recurrence in words.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rinse").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("paper").assertExists()
        // Comments load in their own request, after the task itself.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("@Alex Johnson done soon", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.t("rrule.freqWeekly"), substring = true).assertExists()

        // Editor.
        compose.onNodeWithContentDescription(strings.t("tasks.editButton")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("tasks.editTask")).fetchSemanticsNodes().isNotEmpty() }
        // The form fills in once the options and the task have loaded; until the screen change
        // finishes, the detail's title is on screen too, so look for the editable field only.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Take out the bins")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
