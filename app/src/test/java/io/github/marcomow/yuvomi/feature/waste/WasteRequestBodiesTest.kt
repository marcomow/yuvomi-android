package io.github.marcomow.yuvomi.feature.waste

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bodies upstream 2.94.0 checks against its API description (`WasteScheduleOverrideInput`). */
class WasteRequestBodiesTest {

    /** `PUT /waste/schedules/{id}/overrides/{originalDate}` requires `replacement_date` and `note`. */
    @Test fun `a moved pickup sends the new day and an explicit note`() {
        val body = WasteApi.overrideBody("2026-11-27")
        assertEquals(setOf("replacement_date", "note"), body.keys)
        assertEquals("2026-11-27", body["replacement_date"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["note"])
    }

    @Test fun `a skipped pickup sends null as the replacement and still the note`() {
        val body = WasteApi.overrideBody(null)
        assertEquals(setOf("replacement_date", "note"), body.keys)
        assertEquals(JsonNull, body["replacement_date"])
        assertEquals(JsonNull, body["note"])
    }
}
