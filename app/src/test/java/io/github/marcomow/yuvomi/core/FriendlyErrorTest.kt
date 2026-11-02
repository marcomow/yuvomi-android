package io.github.marcomow.yuvomi.core

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.feature.calendar.CalendarErrors
import io.github.marcomow.yuvomi.feature.notes.NoteErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Upstream's public/utils/friendly-error.js, through the generated table. */
class FriendlyErrorTest {
    private fun http(status: Int, reason: String?, params: Map<String, Long> = emptyMap()) = ApiProblem.Http(status, reason, "English text", params)

    @Test fun `a named case gets its sentence`() =
        assertEquals("shopping.errorListGone", FriendlyError.key(http(404, "shopping_list_not_found")))

    @Test fun `a refusal reason counts only on a 403`() {
        assertEquals("tasks.errorLocked", FriendlyError.key(http(403, "task_locked")))
        assertEquals("common.errorNoPermission", FriendlyError.key(http(403, "shopping_list_not_found")))
        assertNull(FriendlyError.key(http(409, "task_locked")))
    }

    @Test fun `a 429 without its own sentence says wait`() =
        assertEquals("common.errorRateLimited", FriendlyError.key(http(429, null)))

    @Test fun `an unknown reason, a bare status and a network failure have no shared sentence`() {
        assertNull(FriendlyError.key(http(400, "something_new")))
        assertNull(FriendlyError.key(http(500, null)))
        assertNull(FriendlyError.key(ApiProblem.Network(IOException())))
    }

    @Test fun `a case with numbers needs them`() {
        assertEquals("calendar.errorCalendarNameInvalid?limit=100", FriendlyError.key(http(400, "calendar_name_invalid", mapOf("limit" to 100))))
        assertNull(FriendlyError.key(http(400, "calendar_name_invalid")))
    }

    @Test fun `numbers are added only to the case's own sentence`() {
        val e = http(400, "calendar_name_invalid", mapOf("limit" to 100))
        assertEquals("calendar.errorCalendarNameInvalid?limit=100", FriendlyError.withArgs(e, "calendar.errorCalendarNameInvalid"))
        assertEquals("common.errorGeneric", FriendlyError.withArgs(e, "common.errorGeneric"))
    }

    @Test fun `encoding round-trips`() {
        assertEquals("a.b" to mapOf("min" to 1L, "max" to 30L), FriendlyError.decode(FriendlyError.encode("a.b", mapOf("min" to 1, "max" to 30))))
        assertEquals("a.b" to emptyMap<String, Any?>(), FriendlyError.decode("a.b"))
    }

    @Test fun `gone reasons never count on a refusal`() {
        assertTrue(FriendlyError.isGone(http(404, "tasks_task_not_found")))
        assertFalse(FriendlyError.isGone(http(403, "tasks_task_not_found")))
    }

    @Test fun `modules fall back to the shared sentence instead of their general one`() {
        // Notes does not map shopping reasons itself; upstream's shared table does.
        assertEquals("shopping.errorListGone", NoteErrors.key(http(404, "shopping_list_not_found")))
        assertEquals("common.errorGeneric", NoteErrors.key(http(400, "something_new")))
    }

    @Test fun `a module's own sentence gets the server's numbers`() =
        assertEquals(
            "calendar.errorCalendarNameInvalid?limit=60",
            CalendarErrors.key(http(400, "calendar_name_invalid", mapOf("limit" to 60))),
        )
}
