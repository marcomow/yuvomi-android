package io.github.marcomow.yuvomi.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LINKS-R1 to R3: links to the signed-in server, as native routes. */
class ServerLinksTest {
    private val server = "https://home.example.org/"

    @Test fun `items and modules of the web app open their screens`() {
        assertEquals("task/12", ServerLinks.route("https://home.example.org/tasks?open=12", server))
        assertEquals("tasks", ServerLinks.route("https://home.example.org/tasks", server))
        assertEquals("calendar?open=5", ServerLinks.route("https://home.example.org/calendar?open=5&date=2026-11-03", server))
        assertEquals("notes/3", ServerLinks.route("https://home.example.org/notes?open=3", server))
        assertEquals("recipes/9", ServerLinks.route("https://home.example.org/recipes?open=9", server))
        assertEquals("documents?open=4", ServerLinks.route("https://home.example.org/documents?open=4", server))
        assertEquals("shopping?list=2", ServerLinks.route("https://home.example.org/shopping?list=2", server))
        assertEquals("health/fasting", ServerLinks.route("https://home.example.org/health/fasting", server))
        assertEquals("budget", ServerLinks.route("https://home.example.org/budget/", server))
        assertEquals("overview", ServerLinks.route("https://home.example.org/", server))
    }

    @Test fun `only the signed-in server's links count`() {
        assertNull(ServerLinks.route("https://other.example.org/tasks?open=12", server))
        assertNull(ServerLinks.route("http://home.example.org/tasks?open=12", server))
        assertNull(ServerLinks.route("https://home.example.org:8443/tasks", server))
        assertNull(ServerLinks.route("https://home.example.org.evil.test/tasks", server))
        assertNull(ServerLinks.route("not a link", server))
    }

    @Test fun `a server under a path keeps its prefix`() {
        val sub = "https://nas.example.org/yuvomi/"
        assertEquals("task/7", ServerLinks.route("https://nas.example.org/yuvomi/tasks?open=7", sub))
        assertNull(ServerLinks.route("https://nas.example.org/tasks?open=7", sub))
        assertNull(ServerLinks.route("https://nas.example.org/yuvomiX/tasks", sub))
    }

    @Test fun `a page the app has no screen for stays in the browser`() {
        assertNull(ServerLinks.route("https://home.example.org/login", server))
        assertNull(ServerLinks.route("https://home.example.org/api/v1/tasks", server))
    }

    @Test fun `the first address in shared text`() {
        assertEquals("https://home.example.org/tasks?open=1", ServerLinks.firstUrl("Look: https://home.example.org/tasks?open=1."))
        assertNull(ServerLinks.firstUrl("no link here"))
    }
}
