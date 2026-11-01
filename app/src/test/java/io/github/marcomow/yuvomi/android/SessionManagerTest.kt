package io.github.marcomow.yuvomi.android

import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.YuvomiApp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.model.LoginOutcome
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.core.session.ProbeResult
import io.github.marcomow.yuvomi.core.session.SessionManager
import io.github.marcomow.yuvomi.core.session.SessionState
import io.github.marcomow.yuvomi.core.session.SettingsStore
import io.github.marcomow.yuvomi.core.session.SignOutNotice
import io.github.marcomow.yuvomi.core.session.VersionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** docs/spec/signin.md against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YuvomiApp::class)
class SessionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsStore
    private lateinit var cache: ResponseCache
    private val routes = mutableMapOf<String, () -> MockResponse>()

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body).build()

    private val payload = """{"user":{"id":1,"username":"alex","display_name":"Alex Johnson"},"permissions":{"admin":true},"householdSize":5,"csrfToken":"t"}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes["${request.method} ${request.url.encodedPath}"]?.invoke() ?: json("""{"error":"Not found"}""", 404)
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<YuvomiApp>()
        settings = SettingsStore(context, PlainSecretBox())
        cache = ResponseCache(File(context.cacheDir, "test-${System.nanoTime()}"))
        routes["GET /api/v1/version"] = { json("""{"app_name":"Yuvomi","setup_required":false,"version":"2.83.0"}""") }
        routes["GET /api/v1/preferences"] = { json("""{"data":{"timezone_effective":"Europe/Berlin","disabled_modules":["waste"]}}""") }
        routes["GET /api/v1/auth/me"] = { json(payload) }
        runBlocking { settings.clearCookies() }
    }

    @After fun tearDown() = server.close()

    private fun manager() = SessionManager(settings, cache, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    private val address get() = "http://localhost:${server.port}"

    /** SIGNIN-A3 at the logic level. */
    @Test fun `a server that is not set up says so`() = runBlocking {
        routes["GET /api/v1/version"] = { json("""{"app_name":"Yuvomi","setup_required":true}""") }
        assertEquals(ProbeResult.SetupRequired(address), manager().probe(address))
    }

    @Test fun `a server that is not Yuvomi is recognised`() = runBlocking {
        routes["GET /api/v1/version"] = { json("<html></html>") }
        assertEquals(ProbeResult.NotYuvomi, manager().probe(address))
    }

    @Test fun `password sign-in reads member, household and version`() = runBlocking {
        routes["POST /api/v1/auth/login"] = { json(payload, cookie = "yuvomi.sid=abc; Path=/; HttpOnly; Max-Age=7776000") }
        val m = manager()
        assertTrue(m.probe(address) is ProbeResult.Ready)
        assertTrue(m.login("alex", "demo1234") is LoginOutcome.SignedIn)
        val s = (m.state.value as SessionState.SignedIn).session
        assertEquals("Europe/Berlin", s.zone.id)
        assertEquals("none", s.access("waste"))
        assertEquals("write", s.access("tasks"))
        assertEquals(VersionGate.Verdict.SUPPORTED, s.verdict)
        assertEquals("alex", settings.username())
    }

    /** SIGNIN-A6 at the logic level: the second factor uses the same cookie jar. */
    @Test fun `the second factor completes sign-in with the pending session`() = runBlocking {
        routes["POST /api/v1/auth/login"] = { json("""{"twoFactorRequired":true,"recoveryAvailable":false}""", cookie = "yuvomi.sid=pending; Path=/") }
        routes["POST /api/v1/auth/2fa/verify"] = { json(payload) }
        val m = manager()
        m.probe(address)
        assertEquals(LoginOutcome.TwoFactorRequired(false), m.login("alex", "demo1234"))
        m.verifyTwoFactor("123456")
        assertTrue(m.state.value is SessionState.SignedIn)
        server.takeRequest(); server.takeRequest()
        val verify = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .first { it.url.encodedPath == "/api/v1/auth/2fa/verify" }
        assertEquals("yuvomi.sid=pending", verify.headers["Cookie"])
    }

    /** SIGNIN-A8. */
    @Test fun `a session the server no longer knows signs out with a notice`() = runBlocking {
        routes["POST /api/v1/auth/login"] = { json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000") }
        manager().apply { probe(address); login("alex", "demo1234") }
        routes["GET /api/v1/auth/me"] = { json("""{"error":"Unauthorized"}""", 401) }
        val restarted = manager()
        restarted.start()
        val out = restarted.state.value as SessionState.SignedOut
        assertEquals(SignOutNotice.SIGNED_OUT_BY_SERVER, out.notice)
        assertEquals("alex", out.username)
        assertEquals(null, settings.cookies())
    }

    @Test fun `a rejected cookie without a cached session is still deleted`() = runBlocking {
        settings.setServerAndUser(address, null)
        settings.setCookies(io.github.marcomow.yuvomi.core.session.SessionCookieJar().serialize())
        routes["GET /api/v1/auth/me"] = { json("""{"error":"Unauthorized"}""", 401) }
        val m = manager()
        m.start()
        assertEquals(SignOutNotice.SIGNED_OUT_BY_SERVER, (m.state.value as SessionState.SignedOut).notice)
        assertEquals(null, settings.cookies())
    }

    /** SIGNIN-A10. */
    @Test fun `sign-out clears local state even when the server is gone`() = runBlocking {
        routes["POST /api/v1/auth/login"] = { json(payload, cookie = "yuvomi.sid=abc; Path=/; Max-Age=7776000") }
        val m = manager()
        m.probe(address); m.login("alex", "demo1234")
        server.close()
        m.signOut()
        assertTrue(m.state.value is SessionState.SignedOut)
        assertEquals(null, settings.cookies())
    }

    @Test fun `wrong password is a 401 Http problem`() = runBlocking {
        routes["POST /api/v1/auth/login"] = { json("""{"error":"Invalid credentials.","code":401}""", 401) }
        val m = manager()
        m.probe(address)
        try { m.login("alex", "nope"); fail() } catch (e: ApiProblem.Http) { assertEquals(401, e.status) }
        assertTrue(m.state.value !is SessionState.SignedIn)
    }
}
