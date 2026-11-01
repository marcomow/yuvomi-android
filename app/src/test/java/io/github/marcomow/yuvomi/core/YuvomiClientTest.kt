package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.AuthApi
import io.github.marcomow.yuvomi.core.api.CsrfStore
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.TasksApi
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.session.SessionCookieJar
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The client's upstream conventions: CSRF, retry, expiry, idempotency, error reasons. */
class YuvomiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var csrf: CsrfStore
    private lateinit var client: YuvomiClient
    private var expired = 0

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        csrf = CsrfStore()
        val http = OkHttpClient.Builder().cookieJar(SessionCookieJar()).addInterceptor(csrf.interceptor).build()
        client = YuvomiClient(server.url("/").toString(), http, csrf) { expired++ }
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.body(body).build()

    /** SIGNIN-A7. */
    @Test fun `writes carry the last csrf token the server sent`() = runTest {
        server.enqueue(json("""{"user":{"id":1},"csrfToken":"aaa"}"""))
        server.enqueue(json("""{"data":[]}""", 200, "X-CSRF-Token" to "bbb"))
        server.enqueue(json("""{"data":{"id":5,"status":"done"}}"""))
        AuthApi(client).me()
        assertEquals("aaa", csrf.token)
        client.call(Operation.TASKS_LIST)
        TasksApi(client).setStatus(5, "done")
        server.takeRequest(); server.takeRequest()
        val write = server.takeRequest()
        assertEquals("PATCH", write.method)
        assertEquals("bbb", write.headers["X-CSRF-Token"])
    }

    /** SIGNIN-A13. */
    @Test fun `csrf_invalid refreshes through me and retries once`() = runTest {
        csrf.update("old")
        server.enqueue(json("""{"error":"Invalid CSRF token.","code":403,"reason":"csrf_invalid"}""", 403))
        server.enqueue(json("""{"user":{"id":1},"csrfToken":"fresh"}"""))
        server.enqueue(json("""{"ok":true}"""))
        TasksApi(client).delete(3)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        assertEquals("/api/v1/auth/me", server.takeRequest().url.encodedPath)
        assertEquals("fresh", server.takeRequest().headers["X-CSRF-Token"])
    }

    /** SIGNIN-R12. */
    @Test fun `a 401 outside sign-in ends the session`() = runTest {
        server.enqueue(json("""{"error":"Unauthorized"}""", 401))
        try {
            client.call(Operation.DASHBOARD); fail()
        } catch (e: ApiProblem.SessionExpired) {
            assertEquals(1, expired)
        }
    }

    @Test fun `a 401 at login is wrong credentials, not expiry`() = runTest {
        server.enqueue(json("""{"error":"Invalid credentials.","code":401}""", 401))
        try {
            AuthApi(client).login("a", "b"); fail()
        } catch (e: ApiProblem.Http) {
            assertEquals(401, e.status)
            assertEquals(0, expired)
        }
    }

    @Test fun `login answers either a second factor or the payload`() = runTest {
        server.enqueue(json("""{"twoFactorRequired":true,"recoveryAvailable":true}"""))
        assertEquals(LoginOutcome.TwoFactorRequired(true), AuthApi(client).login("alex", "demo1234"))
        server.enqueue(json("""{"user":{"id":1,"username":"alex"},"permissions":{"admin":true},"householdSize":5,"csrfToken":"t"}"""))
        val signedIn = AuthApi(client).login("alex", "demo1234") as LoginOutcome.SignedIn
        assertTrue(signedIn.payload.permissions.admin)
        assertEquals("t", csrf.token)
    }

    /** TASKS-A2. */
    @Test fun `tags repeat as separate parameters`() = runTest {
        server.enqueue(json("""{"data":[]}"""))
        TasksApi(client).list(TasksApi.Filter(tags = listOf("Haus, Hof", "b"), includeFuture = true))
        val url = server.takeRequest().url
        assertEquals(listOf("Haus, Hof", "b"), url.queryParameterValues("tag"))
        assertEquals(listOf("open"), url.queryParameterValues("status"))
        assertEquals("1", url.queryParameter("include_future"))
    }

    /** TASKS-A17 at the client: the key is sent as given. */
    @Test fun `posts carry the idempotency key`() = runTest {
        server.enqueue(json("""{"data":{"id":9,"title":"x"}}""", 201))
        TasksApi(client).create(buildJsonObject { put("title", "x") }, "key-1")
        assertEquals("key-1", server.takeRequest().headers["Idempotency-Key"])
    }

    @Test fun `error reasons are kept and unknown operations are flagged`() = runTest {
        server.enqueue(json("""{"error":"Task not found.","code":404,"reason":"tasks_task_not_found"}""", 404))
        server.enqueue(json("""{"error":"Not found"}""", 404))
        try { TasksApi(client).get(1); fail() } catch (e: ApiProblem.Http) {
            assertEquals("tasks_task_not_found", e.reason); assertTrue(!e.operationUnsupported)
        }
        try { TasksApi(client).get(2); fail() } catch (e: ApiProblem.Http) {
            assertNull(e.reason); assertTrue(e.operationUnsupported)
        }
    }

    @Test fun `unknown fields are ignored and flags read as 0 or 1`() = runTest {
        server.enqueue(json("""{"data":{"id":1,"title":"x","locked":1,"is_recurring":0,"brand_new_field":{"a":1}}}"""))
        val task = TasksApi(client).get(1)
        assertTrue(task.locked)
        assertTrue(!task.isRecurring)
    }

    @Test fun `avatars never leave the household's host`() = runTest {
        try {
            client.bytes("https://evil.example/x.png"); fail()
        } catch (e: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }
}
