package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.core.session.SessionCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarTest {
    private val url = "https://home.example.org/api/v1/auth/me".toHttpUrl()

    @Test fun `the session cookie survives a restart`() {
        var saved = ""
        val jar = SessionCookieJar { saved = it }
        val cookie = Cookie.parse(url, "yuvomi.sid=s%3Aabc; Path=/; HttpOnly; Max-Age=7776000")!!
        jar.saveFromResponse(url, listOf(cookie))
        val restored = SessionCookieJar(saved)
        assertEquals("s%3Aabc", restored.loadForRequest(url).single().value)
        assertTrue(restored.loadForRequest("https://other.example/".toHttpUrl()).isEmpty())
    }

    @Test fun `clear empties the jar`() {
        var saved = ""
        val jar = SessionCookieJar { saved = it }
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "yuvomi.sid=x; Path=/")!!))
        jar.clear()
        assertTrue(SessionCookieJar(saved).isEmpty())
    }
}
