package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.AuthApi
import io.github.marcomow.yuvomi.core.api.CsrfStore
import io.github.marcomow.yuvomi.core.api.HouseholdApi
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.api.model.SignInPayload
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.session.SessionCookieJar
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.overview.OverviewBuilder
import io.github.marcomow.yuvomi.feature.overview.Tile
import io.github.marcomow.yuvomi.feature.tasks.TaskBodies
import io.github.marcomow.yuvomi.feature.tasks.TaskEditorViewModel.Form
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The real API client against upstream's Docker image at the pinned version,
 * seeded with upstream's `scripts/seed-demo.js` (docs/UPSTREAM-SYNC.md).
 * Skipped unless `YUVOMI_CONTRACT_URL` is set.
 */
class UpstreamContractTest {
    private val url: String? = System.getProperty("yuvomi.contractUrl")

    private class Member(val client: YuvomiClient, val me: SignInPayload) {
        val tasks = TasksApi(client)
        val household = HouseholdApi(client)
        val auth = AuthApi(client)
    }

    @Before fun requireServer() = assumeTrue("YUVOMI_CONTRACT_URL not set", url != null)

    private fun client(): YuvomiClient {
        val csrf = CsrfStore()
        val http = OkHttpClient.Builder().cookieJar(SessionCookieJar()).addInterceptor(csrf.interceptor).build()
        return YuvomiClient(url!!, http, csrf)
    }

    private fun signIn(username: String): Member = runBlocking {
        val c = client()
        val outcome = AuthApi(c).login(username, PASSWORD) as LoginOutcome.SignedIn
        Member(c, outcome.payload)
    }

    private fun Member.id(username: String): Int = runBlocking {
        TasksApi.decodeMeta(tasks.meta()).users.first { it.displayName.lowercase().startsWith(username) }.id
    }

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID().toString().take(8)}"

    // ── docs/spec/signin.md ──

    @Test fun `SIGNIN-A4 alex signs in and the cookie carries the session`() = runBlocking {
        val alex = signIn("alex")
        assertEquals("alex", alex.me.user.username)
        assertTrue(alex.me.permissions.admin)
        assertEquals("alex", alex.auth.me().user.username)
        assertNotNull(alex.auth.version().version)
    }

    @Test fun `SIGNIN-A5 a wrong password is a 401`() = runBlocking {
        try {
            AuthApi(client()).login("alex", "wrong-password"); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(401, e.status)
        }
    }

    /**
     * Enrols a fresh member in TOTP and signs in with the next 30-second step
     * (upstream refuses a step at or before the last one used). The member is
     * created by `alex` for this test alone, so no other test meets a second
     * factor. The seeded `maria` cannot be used: she is the housekeeper, and
     * upstream refuses sign-in for housekeeping workers (`canSignIn`).
     */
    @Test fun `SIGNIN-A6 a second factor completes sign-in in the same cookie jar`() = runBlocking {
        val alex = signIn("alex")
        val username = "twofa_" + UUID.randomUUID().toString().take(8)
        // /auth/users and /auth/2fa/* are not app operations; the fixture calls them raw.
        raw(alex.client, "POST", "/api/v1/auth/users",
            """{"username":"$username","display_name":"Two Factor","password":"$PASSWORD","family_role":"parent"}""")
        val member = signIn(username)
        val secret = raw(member.client, "POST", "/api/v1/auth/2fa/setup", "{}")
            .jsonObject["data"]!!.jsonObject["secret"]!!.jsonPrimitive.content
        raw(member.client, "POST", "/api/v1/auth/2fa/enable", """{"code":"${totp(secret, 0)}"}""")
        val c = client()
        assertTrue(AuthApi(c).login(username, PASSWORD) is LoginOutcome.TwoFactorRequired)
        assertEquals(username, AuthApi(c).verifyTwoFactor(totp(secret, 1)).user.username)
    }

    @Test fun `SIGNIN-A11 a child has no budget`() {
        assertEquals("none", signIn("leo").me.permissions.module("budget"))
    }

    // ── docs/spec/overview.md ──

    @Test fun `OVERVIEW-A4 the dashboard decodes into every native tile`() = runBlocking {
        val alex = signIn("alex")
        val dash = alex.household.dashboard()
        for (key in listOf("urgentTasks", "openTaskCount", "upcomingEvents", "todayMeals", "users", "birthdays")) {
            assertTrue("missing $key", key in dash)
        }
        val prefs = alex.household.preferences()
        val everything = prefs.copy(dashboardWidgets = ApiJson.parseToJsonElement(
            """[{"id":"tasks","visible":true},{"id":"calendar","visible":true},{"id":"shopping","visible":true},
               {"id":"meals","visible":true},{"id":"birthdays","visible":true},{"id":"family","visible":true}]""",
        ))
        val model = OverviewBuilder.build(dash, everything, alex.me.permissions,
            HouseholdTime(ZoneId.of(prefs.timezoneEffective ?: "UTC")), alex.me.user.displayName)
        val generic = model.tiles.filter { it is Tile.Generic && it.id in setOf("tasks", "calendar", "shopping", "meals", "birthdays", "family") }
        assertTrue("tiles that fell back to generic: $generic", generic.isEmpty())
    }

    @Test fun `OVERVIEW-A5 a child's dashboard decodes`() = runBlocking {
        val leo = signIn("leo")
        val model = OverviewBuilder.build(leo.household.dashboard(), leo.household.preferences(), leo.me.permissions,
            HouseholdTime(ZoneId.of("UTC")), leo.me.user.displayName)
        assertTrue(model.tiles.none { it.id == "budget" })
    }

    @Test fun `OVERVIEW-A6 countdown items are objects`() = runBlocking {
        val dash = signIn("alex").household.dashboard()
        val items = dash["countdowns"] as? JsonArray ?: JsonArray(emptyList())
        items.forEach { assertTrue(it is JsonObject) }
        println("countdown fields: ${items.firstOrNull()?.jsonObject?.keys}")
    }

    // ── docs/spec/tasks.md ──

    @Test fun `TASKS-A1 the default list is open tasks and decodes`() = runBlocking {
        val tasks = TasksApi.decodeList(signIn("alex").tasks.list(TasksApi.Filter()))
        assertTrue(tasks.isNotEmpty())
        assertTrue(tasks.all { it.status == Task.STATUS_OPEN })
    }

    @Test fun `TASKS-A4 tags fold by case and several assignees stick`() = runBlocking {
        val alex = signIn("alex")
        val form = Form(title = unique("Garden"), tags = listOf("Garten", "garten"), assignees = setOf(alex.id("emma"), alex.id("leo")))
        val created = alex.tasks.create(TaskBodies.create(form, null, solo = false), UUID.randomUUID().toString())
        assertEquals(1, created.tags.size)
        assertEquals(2, created.assignedUsers.size)
        assertTrue(TasksApi.decodeList(alex.tasks.list(TasksApi.Filter())).any { it.id == created.id })
    }

    @Test fun `TASKS-A5 a partial edit keeps countdown`() = runBlocking {
        val alex = signIn("alex")
        val body = JsonObject(TaskBodies.create(Form(title = unique("Licence"), dueDate = LocalDate.now().plusYears(1)), null, false) +
            ("countdown" to JsonPrimitive(true)))
        val created = alex.tasks.create(body, UUID.randomUUID().toString())
        val old = Form(title = created.title)
        alex.tasks.update(created.id, TaskBodies.diff(old, old.copy(title = created.title + "!"), created, solo = false))
        val after = alex.tasks.get(created.id)
        assertTrue(after.countdown)
        assertEquals(created.title + "!", after.title)
    }

    @Test fun `TASKS-A6 a locked task refuses other members`() = runBlocking {
        val alex = signIn("alex")
        val created = alex.tasks.create(TaskBodies.create(Form(title = unique("Locked"), locked = true), null, false), UUID.randomUUID().toString())
        try {
            signIn("emma").tasks.update(created.id, buildJsonObject { put("title", "hijack") }); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(403, e.status)
            assertEquals("task_locked", e.reason)
        }
    }

    @Test fun `TASKS-A7 naming who did it, then reopening`() = runBlocking {
        val alex = signIn("alex")
        val created = alex.tasks.create(
            TaskBodies.create(Form(title = unique("Dishes"), points = "10", assignees = setOf(alex.id("emma"))), null, false),
            UUID.randomUUID().toString(),
        )
        alex.tasks.setStatus(created.id, Task.STATUS_DONE, doneBy = alex.id("leo"))
        assertEquals(Task.STATUS_DONE, alex.tasks.get(created.id).status)
        alex.tasks.setStatus(created.id, Task.STATUS_OPEN)
        assertEquals(Task.STATUS_OPEN, alex.tasks.get(created.id).status)
    }

    @Test fun `TASKS-A8 a weekly series names its next due date and undo withdraws it`() = runBlocking {
        val alex = signIn("alex")
        val due = LocalDate.now().plusDays(1)
        val title = unique("Weekly")
        val created = alex.tasks.create(
            TaskBodies.create(Form(title = title, dueDate = due, rule = io.github.marcomow.yuvomi.feature.tasks.RRule.Parts(freq = "WEEKLY")), null, false),
            UUID.randomUUID().toString(),
        )
        val change = alex.tasks.setStatus(created.id, Task.STATUS_DONE)
        assertEquals(due.plusDays(7).toString(), change.nextDueDate)
        alex.tasks.setStatus(created.id, Task.STATUS_OPEN)
        val open = TasksApi.decodeList(alex.tasks.list(TasksApi.Filter(includeFuture = true))).filter { it.title == title }
        assertEquals(1, open.size)
    }

    @Test fun `TASKS-A9 a private task is invisible to others`() = runBlocking {
        val linda = signIn("linda")
        val created = linda.tasks.create(TaskBodies.create(Form(title = unique("Surprise"), visibility = "private"), null, false), UUID.randomUUID().toString())
        val alex = signIn("alex")
        assertFalse(TasksApi.decodeList(alex.tasks.list(TasksApi.Filter())).any { it.id == created.id })
        try {
            alex.tasks.get(created.id); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(404, e.status)
        }
    }

    @Test fun `TASKS-A13 ticking a checklist line rewrites exactly that line`() = runBlocking {
        val alex = signIn("alex")
        val created = alex.tasks.create(
            TaskBodies.create(Form(title = unique("List"), description = "- [ ] milk\n- [ ] eggs"), null, false),
            UUID.randomUUID().toString(),
        )
        val result = alex.tasks.checkLine(created.id, 1, true, "- [ ] eggs")
        assertEquals("- [ ] milk\n- [x] eggs", result.description)
        try {
            alex.tasks.checkLine(created.id, 1, false, "- [ ] eggs"); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(409, e.status)
        }
    }

    @Test fun `TASKS-A15 comments - authors edit, admins moderate`() = runBlocking {
        val alex = signIn("alex")
        val created = alex.tasks.create(TaskBodies.create(Form(title = unique("Talk")), null, false), UUID.randomUUID().toString())
        val emma = signIn("emma")
        val comment = emma.tasks.addComment(created.id, "@Alex Johnson can you help?", UUID.randomUUID().toString())
        assertEquals(comment.id, alex.tasks.comments(created.id).single().id)
        try {
            alex.tasks.editComment(created.id, comment.id, "changed"); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(403, e.status)
        }
        alex.tasks.deleteComment(created.id, comment.id)
        assertTrue(alex.tasks.comments(created.id).isEmpty())
    }

    @Test fun `TASKS-A16 documents are null exactly without Documents access`() = runBlocking {
        for (name in listOf("alex", "leo")) {
            val m = signIn(name)
            val task = TasksApi.decodeList(m.tasks.list(TasksApi.Filter())).first()
            val detail = m.tasks.get(task.id)
            if (m.me.permissions.module("documents") == "none") assertNull(detail.documents) else assertNotNull(detail.documents)
        }
    }

    @Test fun `TASKS-A17 a retried create with the same key makes one task`() = runBlocking {
        val alex = signIn("alex")
        val key = UUID.randomUUID().toString()
        val body = TaskBodies.create(Form(title = unique("Once")), null, false)
        val first = alex.tasks.create(body, key)
        val second = alex.tasks.create(body, key)
        assertEquals(first.id, second.id)
    }

    private fun raw(c: YuvomiClient, method: String, path: String, body: String, tolerate: Boolean = false): kotlinx.serialization.json.JsonElement = runBlocking {
        val http = c.javaClass.getDeclaredField("http").apply { isAccessible = true }.get(c) as OkHttpClient
        val request = okhttp3.Request.Builder()
            .url(c.baseUrl.resolve(path)!!)
            .header("X-CSRF-Token", c.csrf.token)
            .method(method, body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful && !tolerate) error("$method $path -> ${r.code} $text")
            runCatching { ApiJson.parseToJsonElement(text) }.getOrDefault(JsonObject(emptyMap()))
        }
    }

    /** RFC 6238, SHA-1, 6 digits, 30 s - upstream's server/utils/totp.js. */
    private fun totp(base32: String, stepOffset: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0; var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in base32.uppercase().filter { it != '=' }) {
            value = (value shl 5) or alphabet.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((value ushr (bits - 8)) and 0xFF); bits -= 8 }
        }
        val step = System.currentTimeMillis() / 30_000 + stepOffset
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(out.toByteArray(), "HmacSHA1")) }
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val o = hash.last().toInt() and 0x0F
        val bin = ((hash[o].toInt() and 0x7F) shl 24) or ((hash[o + 1].toInt() and 0xFF) shl 16) or
            ((hash[o + 2].toInt() and 0xFF) shl 8) or (hash[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    private companion object {
        /** Every account `scripts/seed-demo.js` creates uses this password. */
        const val PASSWORD = "demo1234"
    }
}
