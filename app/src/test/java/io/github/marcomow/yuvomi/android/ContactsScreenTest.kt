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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.StringsLoader
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.feature.contacts.ContactEditorViewModel
import io.github.marcomow.yuvomi.feature.contacts.ContactsHost
import io.github.marcomow.yuvomi.feature.shell.YuvomiRoot
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** docs/spec/contacts.md, CONT-A10 to A14, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class, qualifiers = "w411dp-h891dp")
class ContactsScreenTest {
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
        "permissions":{"admin":true,"modules":$modules},"householdSize":3,"csrfToken":"t"}"""

    private val contacts = """{"data":[
        {"id":1,"name":"Dr. Hans Weber","category":"doctor","phone":"+49 30 1234","email":"weber@praxis.de","address":"Main St 1, Berlin"},
        {"id":2,"name":"Plumber Max","category":"craftsman","phone":"0170 555"},
        {"id":3,"name":"Alex Johnson","category":"misc","family_user_id":1,"family_avatar_color":"#5b8def"}]}"""

    private val categories = """{"data":[
        {"key":"doctor","name":null,"label_key":"contacts.categoryDoctor","icon":"stethoscope","sort_order":0,"color":"var(--color-success)"},
        {"key":"craftsman","name":null,"label_key":"contacts.categoryCraftsman","icon":"wrench","sort_order":1,"color":null},
        {"key":"misc","name":null,"label_key":"contacts.categoryOther","icon":"tag","sort_order":2,"color":null}]}"""

    private val full = """{"data":{"id":1,"name":"Dr. Hans Weber","category":"doctor","phone":"+49 30 1234","email":"weber@praxis.de",
        "address":"Main St 1, Berlin","birthday":"1970-04-02","birthday_year_unknown":0,"notes":"Mon-Fri",
        "phones":[{"id":1,"label":"other","value":"+49 30 1234","isPrimary":true},{"id":2,"label":"Mobile","value":"0171 999","isPrimary":false}],
        "emails":[{"id":1,"label":"other","value":"weber@praxis.de","isPrimary":true}],"addresses":[]}}"""

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
                    line == "GET /api/v1/contacts" -> json(contacts)
                    line == "GET /api/v1/contacts/categories" -> json(categories)
                    line == "GET /api/v1/contacts/meta" -> json("""{"data":{"categories":[],"categoryColors":["var(--color-success)"]}}""")
                    line == "GET /api/v1/contacts/1" -> json(full)
                    line == "POST /api/v1/contacts" -> json("""{"data":{"id":9,"name":"Anna Miller","category":"doctor"}}""", code = 201)
                    line.startsWith("DELETE /api/v1/contacts/") -> MockResponse.Builder().code(204).build()
                    line == "POST /api/v1/birthdays/import" -> json("""{"data":{"imported":1}}""", code = 201)
                    else -> json("""{"error":"Not found"}""", code = 404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun openContacts() {
        val app = ApplicationProvider.getApplicationContext<YuvomiApp>()
        container = AppContainer(app, PlainSecretBox())
        strings = StringsLoader.load(app.assets, "en")
        runBlocking {
            container.settings.setLanguage("en")
            container.session.probe("http://localhost:${server.port}")
            container.session.login("alex", "demo1234")
        }
        compose.setContent { YuvomiRoot(container, null) {} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(strings.t("nav.more"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(strings.t("nav.more"))).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(strings.t("nav.contacts")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(strings.t("nav.contacts")).onFirst().performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Plumber Max").fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            println("SCREEN:\n" + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
            throw e
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    /** A view model outside Compose: its coroutines run on the main looper, which only idling moves on. */
    private fun pump(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out; writes=$writes")
    }

    private fun waitForWrite(prefix: String) = compose.waitUntil(10_000) { writes.any { it.startsWith(prefix) } }

    /** CONT-A10. */
    @Test fun `the list groups by category, searches, and opens the read view`() {
        openContacts()
        compose.onNodeWithText(strings.t("contacts.categoryDoctor").uppercase()).assertExists()
        compose.onNodeWithText(strings.t("contacts.categoryCraftsman").uppercase()).assertExists()
        compose.onNodeWithContentDescription(strings.t("contacts.callNamed", "name" to "Plumber Max")).assertExists()
        // Chips: "All" and the three categories in use.
        compose.onNodeWithText(strings.t("contacts.filterAll")).assertExists()
        compose.onAllNodesWithText(strings.t("contacts.categoryCraftsman")).onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dr. Hans Weber").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(strings.t("contacts.filterAll")).performClick()
        compose.onNodeWithText(strings.t("contacts.searchPlaceholder")).performTextInput("praxis")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Plumber Max").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Dr. Hans Weber").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("0171 999").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mobile").assertExists()
        compose.onNodeWithText("Mon-Fri").performScrollTo().assertExists()
        compose.onNodeWithText(strings.t("contacts.callLabel")).assertExists()
        compose.onNodeWithText(strings.t("contacts.mapsLabel")).assertExists()
        // A birthday without a link offers "Add to birthdays".
        compose.onNodeWithText(strings.t("contacts.addToBirthdays")).performScrollTo().performClick()
        waitForWrite("POST /api/v1/birthdays/import")
        assertTrue(writes.any { it == """POST /api/v1/birthdays/import {"contact_ids":[1]}""" })
    }

    /** CONT-A11. */
    @Test fun `a read-only member sees no FAB, no tools and no delete`() {
        modules = """{"contacts":"read"}"""
        openContacts()
        compose.onNodeWithContentDescription(strings.t("contacts.newContactLabel")).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.t("common.moreActions")).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Plumber Max")).performClick()
        compose.onNodeWithText(strings.t("contacts.exportLabel")).assertExists()
        compose.onNodeWithText(strings.t("common.delete")).assertDoesNotExist()
    }

    /** CONT-A12. */
    @Test fun `a deleted contact goes out after the snackbar, undo keeps it`() {
        openContacts()
        val menu = strings.t("common.moreActionsNamed", "name" to "Plumber Max")
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Plumber Max").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(strings.t("common.undo")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Plumber Max").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(writes.none { it.startsWith("DELETE") })

        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(strings.t("common.delete")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(strings.t("common.undo")).fetchSemanticsNodes().isNotEmpty() }
        advance(11_000)
        waitForWrite("DELETE /api/v1/contacts/2")
        assertEquals(1, writes.count { it.startsWith("DELETE") })
    }

    /** CONT-A13: a member's own contact has no delete, and select mode skips it. */
    @Test fun `a household member cannot be deleted or selected`() {
        openContacts()
        compose.onNodeWithContentDescription(strings.t("common.moreActions")).performClick()
        compose.onNodeWithText(strings.t("contacts.selectButton")).performClick()
        compose.onNodeWithText(strings.t("contacts.selectAll")).performClick()
        compose.onNodeWithText(strings.t("contacts.selectCount", "count" to 2)).assertExists()
        compose.onNodeWithText(strings.t("common.cancel")).performClick()
        compose.onNodeWithContentDescription(strings.t("common.moreActionsNamed", "name" to "Alex Johnson")).performClick()
        compose.onNodeWithText(strings.t("contacts.exportLabel")).assertExists()
        compose.onNodeWithText(strings.t("common.delete")).assertDoesNotExist()
    }

    /** CONT-A14. */
    @Test fun `create sends the web app's payload with an idempotency key`() {
        openContacts()
        val host = object : ContactsHost {
            override val strings = this@ContactsScreenTest.strings
            override val userId = 1
            override val isAdmin = true
            override val canWrite = true
            override val mayOpenBirthdays = true
            override val mayWriteBirthdays = true
            override suspend fun notify(message: String, action: String?) = false
        }
        val editor = ContactEditorViewModel(container, host, null, "doctor")
        pump { !editor.state.value.loading }
        editor.editName(first = "Anna", last = "Miller")
        editor.edit { it.copy(phones = listOf("" to "123"), emails = listOf("work" to "anna@example.org")) }
        editor.save()
        pump { writes.any { it.startsWith("POST /api/v1/contacts ") } }
        val post = writes.single { it.startsWith("POST /api/v1/contacts ") }.substringAfter("POST /api/v1/contacts ")
        assertEquals(
            """{"name":"Anna Miller","category":"doctor","phone":"123","email":"anna@example.org","address":null,"notes":null,""" +
                """"birthday":null,"birthday_year_unknown":false,"phones":[{"label":"other","value":"123","isPrimary":true}],""" +
                """"emails":[{"label":"work","value":"anna@example.org","isPrimary":true}],"firstName":"Anna","lastName":"Miller"}""",
            post,
        )
        assertNotNull(keys.firstOrNull { it.startsWith("POST /api/v1/contacts ") })
        pump { editor.state.value.done }
    }
}
