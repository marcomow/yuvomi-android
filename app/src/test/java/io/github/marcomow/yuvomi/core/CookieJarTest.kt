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
        val jar = SessionCookieJar { serialized, _ -> saved = serialized }
        val cookie = Cookie.parse(url, "yuvomi.sid=s%3Aabc; Path=/; HttpOnly; Max-Age=7776000")!!
        jar.saveFromResponse(url, listOf(cookie))
        val restored = SessionCookieJar(saved)
        assertEquals("s%3Aabc", restored.loadForRequest(url).single().value)
        assertTrue(restored.loadForRequest("https://other.example/".toHttpUrl()).isEmpty())
    }

    @Test fun `clear empties the jar and marks pending writes stale`() {
        val writes = mutableListOf<Pair<String, Int>>()
        val jar = SessionCookieJar { serialized, generation -> writes += serialized to generation }
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "yuvomi.sid=x; Path=/")!!))
        val before = writes.single().second
        jar.clear()
        assertTrue(jar.isEmpty())
        // Clearing does not write: the caller deletes the stored copy, and a write still
        // pending with the old generation is dropped (SessionManager.clearLocal).
        assertEquals(1, writes.size)
        assertTrue(jar.generation != before)
        // Cookies set after a clear persist under the new generation.
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "yuvomi.sid=y; Path=/")!!))
        assertEquals(jar.generation, writes.last().second)
        assertEquals("y", SessionCookieJar(writes.last().first).loadForRequest(url).single().value)
    }
}
