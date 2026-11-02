package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import io.github.marcomow.yuvomi.feature.tasks.TaskDetailViewModel
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
    private val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
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
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}".also {
                if (request.method != "GET") writes += "$it ${request.body?.utf8().orEmpty()}"
            }) {
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
                // Subtasks are not served on their own: upstream's GET /tasks/{id} excludes them.
                "GET /api/v1/tasks/8" -> MockResponse.Builder().code(404)
                    .body("""{"error":"Task not found.","code":404,"reason":"tasks_task_not_found"}""").build()
                "PATCH /api/v1/tasks/8/status" -> json("""{"data":{"id":8,"status":"done"}}""")
                "PUT /api/v1/tasks/8" -> json("""{"data":{"id":8,"title":"Rinse well","status":"open","parent_task_id":7}}""")
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
        compose.offMain {
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

        // Tapping a subtask ticks it and stays on the parent, as in the web app.
        compose.onNodeWithText("Rinse").performScrollTo().performClick()
        compose.waitUntil(10_000) { writes.any { it.startsWith("PATCH /api/v1/tasks/8/status") } }
        assertTrue(writes.first { it.startsWith("PATCH /api/v1/tasks/8/status") }.contains("\"done\""))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(strings.t("tasks.editButton")).assertExists()

        // The row menu offers Edit and Delete; Delete asks first.
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Rinse")).performScrollTo().performClick()
        compose.onNodeWithText(strings.t("common.edit")).assertExists()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.onNodeWithText(strings.t("tasks.subtaskDeleteConfirm", "title" to "Rinse")).assertExists()
        compose.onNodeWithText(strings.t("common.cancel")).performClick()
        assertTrue(writes.none { it.startsWith("DELETE") })

        // Renaming sends only the title. Typing into a dialog's field does not work under
        // Robolectric, so this goes through the screen's view model.
        val detail = TaskDetailViewModel(container, 7)
        val sub = compose.runOnIdle { detail.state.value.task?.subtasks?.first() } ?: error("task not loaded")
        assertEquals(null, runBlocking { detail.renameSubtask(sub, "Rinse well") })
        assertEquals("""{"title":"Rinse well"}""", writes.first { it.startsWith("PUT /api/v1/tasks/8 ") }.substringAfter("PUT /api/v1/tasks/8 "))

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
