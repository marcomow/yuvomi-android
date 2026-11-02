package io.github.marcomow.yuvomi.android

import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.birthdays.BirthdayEditorViewModel
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysHost
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysViewModel
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/birthdays.md, BDAY-A5 to BDAY-A11, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class BirthdaysScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val writes = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private var modules = "{}"
    private lateinit var strings: Strings
    private lateinit var container: AppContainer

    private fun json(body: String, cookie: String? = null, code: Int = 200) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").addHeader("X-CSRF-Token", "t")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private fun payload() = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson","role":"admin"},
        "permissions":{"admin":true,"modules":$modules},"householdSize":4,"csrfToken":"t"}"""

    private val list = """{"data":[
        {"id":2,"name":"Grandma Rose","birth_date":"1950-11-22","year_unknown":0,"name_day":"08-30","notes":"Loves roses",
         "reminder_offset":"1440","days_until":20,"next_birthday":"2026-11-22","next_age":76,"next_name_day":"2027-08-30","name_day_days_until":301},
        {"id":1,"name":"Uncle Mike Johnson","birth_date":"1985-11-02","year_unknown":0,"reminder_offset":"1440",
         "days_until":0,"next_birthday":"2026-11-02","next_age":41,"suggested_contact_id":9},
        {"id":3,"name":"Leo","birth_date":"2000-05-20","year_unknown":1,"family_user_id":4,"family_display_name":"Leo Johnson",
         "family_avatar_color":"#2563EB","days_until":199,"next_birthday":"2027-05-20"}]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.url.encodedPath}"
                if (request.method != "GET") writes += "$line ${request.body?.utf8().orEmpty()}"
                request.headers["Idempotency-Key"]?.let { keys += "$line $it" }
                return when {
                    line == "GET /api/v1/version" -> json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""")
                    line == "POST /api/v1/auth/login" -> json(payload(), "yuvomi.sid=abc; Path=/; Max-Age=7776000")
                    line == "GET /api/v1/auth/me" -> json(payload())
                    line == "GET /api/v1/preferences" -> json("""{"data":{"timezone_effective":"UTC","dashboard_widgets":null}}""")
                    line == "GET /api/v1/dashboard" -> json("""{"urgentTasks":[],"openTaskCount":0,"users":[]}""")
                    line == "GET /api/v1/birthdays" -> json(list)
                    line == "POST /api/v1/birthdays" -> json("""{"data":{"id":7,"name":"Ada","birth_date":"1990-04-01","days_until":150}}""", code = 201)
                    line.startsWith("PUT /api/v1/birthdays/") -> json("""{"data":{"id":1,"name":"Uncle Mike Johnson","birth_date":"1985-11-02","contact_id":9}}""")
                    line.startsWith("DELETE /api/v1/birthdays/") && !line.endsWith("/feed") -> MockResponse.Builder().code(204).build()
                    line == "GET /api/v1/birthdays/import/candidates" -> json("""{"data":{"withBirthday":[
                        {"id":9,"name":"Uncle Mike Johnson","birthday":"1985-11-02","already_imported":false,"suggested_birthday_id":1,"suggested_birthday_name":"Uncle Mike Johnson"},
                        {"id":10,"name":"Dr. Weber","birthday":"1970-02-03","already_imported":false}],
                        "withoutBirthday":[{"id":11,"name":"Plumber","already_imported":false}]}}""")
                    line == "POST /api/v1/birthdays/import" -> json("""{"data":{"imported":1,"skipped":0}}""", code = 201)
                    line == "GET /api/v1/birthdays/feed" -> json("""{"data":null}""")
                    line == "POST /api/v1/birthdays/feed/regenerate" -> json("""{"data":{"token":"abc","url":"http://localhost/feed/birthdays/abc.ics"}}""")
                    line == "DELETE /api/v1/birthdays/feed" -> json("""{"data":{"token":null}}""")
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
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
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
    }

    private fun openBirthdays() {
        signIn()
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.birthdays")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.birthdays")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Grandma Rose").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private val host get() = object : BirthdaysHost {
        override val strings = this@BirthdaysScreenTest.strings
        override val readOnly = false
        override val contactsVisible = true
        override suspend fun notify(message: String, action: String?) = false
    }

    private fun idleUntil(done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(25)
        }
    }

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    /** BDAY-A5. */
    @Test fun `the list runs by the next birthday with countdown, age and name day`() {
        openBirthdays()
        compose.onNodeWithText("Uncle Mike Johnson 🎂").assertExists()
        compose.onNodeWithText(strings.t("common.today")).assertExists()
        compose.onAllNodesWithText(strings.t("birthdays.turnsAge", "age" to 41), substring = true).onFirst().assertExists()
        compose.onNodeWithText(strings.t("birthdays.celebratesNameDay"), substring = true).assertExists()
        compose.onNodeWithText("Loves roses").assertExists()
        compose.onNodeWithContentDescription(strings.t("birthdays.addButton")).assertExists()
        // The detail sheet offers the quiet link to the contact of the same name.
        compose.onNodeWithText("Uncle Mike Johnson 🎂").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("birthdays.contactSuggestValue")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("birthdays.linkContact")).performClick()
        waitForWrite("PUT /api/v1/birthdays/1")
        assertEquals("""{"contact_id":9}""", writes.single { it.startsWith("PUT") }.substringAfter("/1 "))
    }

    /** BDAY-A6. */
    @Test fun `with calendar read there is no add, no row menu, and the sheet only reads`() {
        modules = """{"calendar":"read"}"""
        openBirthdays()
        compose.onNodeWithContentDescription(strings.t("birthdays.addButton")).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Grandma Rose")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Grandma Rose").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("reminders.offset1day")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(strings.t("common.edit")).assertDoesNotExist()
        compose.onNodeWithText(strings.t("birthdays.linkContact")).assertDoesNotExist()
    }

    /** BDAY-A7. */
    @Test fun `a deleted birthday goes out after the snackbar, undo keeps it`() {
        openBirthdays()
        val menu = strings.t("common.moreActionsNamed", "name" to "Grandma Rose")
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Grandma Rose").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Grandma Rose").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(writes.none { it.startsWith("DELETE") })
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(11_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11_000))
        waitForWrite("DELETE /api/v1/birthdays/2")
    }

    /** BDAY-A8. */
    @Test fun `a new birthday sends the web app's payload with an idempotency key`() {
        signIn()
        val editor = BirthdayEditorViewModel(container, host, null) { LocalDate.parse("2026-11-02") }
        assertEquals(strings.t("common.nameRequired"), runBlocking { editor.save() })
        editor.edit { it.copy(name = " Ada ", birthDate = LocalDate.parse("1990-04-01"), nameDayMonth = 4) }
        assertEquals(strings.t("birthdays.nameDayIncomplete"), runBlocking { editor.save() })
        editor.edit { it.copy(nameDayDay = 1, notes = "Tea") }
        assertNull(runBlocking { editor.save() })
        assertEquals(
            """{"name":"Ada","birth_date":"1990-04-01","year_unknown":false,"name_day":"04-01","notes":"Tea","photo_data":null,""" +
                """"reminder_offset":"1440","reminder_custom_amount":"1","reminder_custom_unit":"days"}""",
            writes.single { it.startsWith("POST /api/v1/birthdays ") }.substringAfter("POST /api/v1/birthdays "),
        )
        assertTrue(keys.any { it.startsWith("POST /api/v1/birthdays ") })
        assertNotNull(editor.state.value.saved)
    }

    /** BDAY-A9. */
    @Test fun `editing without touching the reminder leaves it out, a birthday without a year keeps the placeholder`() {
        signIn()
        val list = BirthdaysViewModel(container, host)
        idleUntil { !list.state.value.loading }
        val mike = list.find(1)!!
        val editor = BirthdayEditorViewModel(container, host, mike) { LocalDate.parse("2026-11-02") }
        editor.edit { it.copy(yearUnknown = true, month = 11, day = 2, contactId = "9") }
        assertNull(runBlocking { editor.save() })
        val body = writes.single { it.startsWith("PUT /api/v1/birthdays/1") }.substringAfter("/1 ")
        assertTrue(body, body.contains(""""birth_date":"2000-11-02","year_unknown":true"""))
        assertTrue(body, body.contains(""""contact_id":9"""))
        assertTrue(body, !body.contains("reminder_offset"))
    }

    /** BDAY-A10. */
    @Test fun `the import sends the chosen contacts and says how many were added`() {
        signIn()
        val list = BirthdaysViewModel(container, host)
        idleUntil { !list.state.value.loading }
        val candidates = runBlocking { list.candidates() }.getOrThrow()
        assertEquals(2, candidates.withBirthday.size)
        assertEquals(strings.t("birthdays.importNothingSelected"), runBlocking { list.import(emptyList()) })
        assertNull(runBlocking { list.import(listOf(10)) })
        assertEquals("""{"contact_ids":[10]}""", writes.single { it.startsWith("POST /api/v1/birthdays/import") }.substringAfter("import "))
    }

    /** BDAY-A11. */
    @Test fun `the feed turns on with an address and off again`() {
        signIn()
        val list = BirthdaysViewModel(container, host)
        idleUntil { !list.state.value.loading }
        assertNull(runBlocking { list.feed() }.getOrThrow())
        assertEquals("http://localhost/feed/birthdays/abc.ics", runBlocking { list.regenerateFeed() }?.url)
        assertTrue(runBlocking { list.disableFeed() })
        assertTrue(writes.any { it.startsWith("DELETE /api/v1/birthdays/feed") })
    }
}
