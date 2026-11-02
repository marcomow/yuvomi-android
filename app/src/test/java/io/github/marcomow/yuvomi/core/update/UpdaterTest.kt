package io.github.marcomow.yuvomi.core.update

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.marcomow.yuvomi.core.session.PlainSecretBox
import io.github.marcomow.yuvomi.core.session.SettingsStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** ADR 0018: check, download, verify, hand to the installer. GitHub is a mock server here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdaterTest {
    private lateinit var server: MockWebServer
    private lateinit var app: Application
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private var digest = sha256(apk)
    private var now = 1_000_000_000L

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/repos/me/app/releases" -> MockResponse.Builder().body(
                    """[{"tag_name":"v0.2.0","draft":false,"assets":[{"name":"yuvomi-android-0.2.0.apk",
                    "browser_download_url":"${server.url("/download/app.apk")}","size":${apk.size},"digest":"sha256:$digest"}]},
                    {"tag_name":"v0.1.4","draft":false,"assets":[]}]""",
                ).build()
                "/download/app.apk" -> MockResponse.Builder().body(Buffer().write(apk)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        File(app.cacheDir, Updater.DIR).deleteRecursively()
    }

    @After fun tearDown() = server.close()

    private fun updater(installed: String = "0.1.4", enabled: Boolean = true) = Updater(
        app, SettingsStore(app, PlainSecretBox()), installed = installed, repo = "me/app", enabled = enabled,
        now = { now }, apiBase = server.url("/").toString().trimEnd('/'),
    )

    @Test fun `a newer release is offered`() = runBlocking {
        val u = updater()
        assertEquals("0.2.0", u.check()?.version)
        assertTrue(u.state.value is Updater.State.Available)
    }

    @Test fun `the newest installed version is up to date`() = runBlocking {
        val u = updater(installed = "0.2.0")
        assertEquals(null, u.check())
        assertEquals(Updater.State.UpToDate("0.2.0"), u.state.value)
    }

    @Test fun `the automatic check waits twelve hours and respects the switch`() = runBlocking {
        // The settings store outlives a test in this process: start after any earlier check, switch on.
        now = 10_000_000_000_000L
        val u = updater()
        u.setAutomatic(true)
        assertEquals("0.2.0", u.checkIfDue()?.version)
        val before = server.requestCount
        now += 60_000
        u.checkIfDue()
        assertEquals(before, server.requestCount)
        now += Updater.INTERVAL_MS
        u.setAutomatic(false)
        assertEquals(null, u.checkIfDue())
        assertEquals(before, server.requestCount)
    }

    @Test fun `a debug build never asks`() = runBlocking {
        assertEquals(null, updater(enabled = false).check())
        assertEquals(0, server.requestCount)
    }

    @Test fun `without the install permission it asks for it first`() = runBlocking {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val u = updater()
        val update = u.check()!!
        u.install(update)
        assertTrue(u.state.value is Updater.State.NeedsPermission)
    }

    @Test fun `a verified download opens the package installer`() = runBlocking {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val u = updater()
        u.install(u.check()!!)
        assertTrue(u.state.value is Updater.State.Installing)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Updater.APK_TYPE, started.type)
        assertTrue(File(app.cacheDir, "${Updater.DIR}/yuvomi-android-0.2.0.apk").readBytes().contentEquals(apk))
    }

    @Test fun `a download that does not match the digest is refused and deleted`() = runBlocking {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        digest = "0".repeat(64)
        val u = updater()
        u.install(u.check()!!)
        assertEquals("android.update.corrupt", (u.state.value as Updater.State.Failed).messageKey)
        assertFalse(File(app.cacheDir, "${Updater.DIR}/yuvomi-android-0.2.0.apk").exists())
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
