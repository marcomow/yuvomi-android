package io.github.marcomow.yuvomi.feature.health

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Upstream 2.94.0 validates `/api/v1/health` against its OpenAPI description (#2810): these
 * bodies must carry every field the description marks required, with the right JSON types.
 */
class HealthRequiredFieldsTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun ok(b: HealthForms.Built): JsonObject = (b as HealthForms.Built.Ok).body

    @Test fun `POST cycle logs sends the whole day even when the form is empty`() {
        val body = ok(HealthForms.dayBody(HealthForms.DayForm(today)))
        val required = setOf(
            "log_date", "flow", "note", "visibility", "symptoms", "basal_temp", "basal_temp_unit",
            "cervix_mucus", "lh_test", "pregnancy_test", "intimacy",
        )
        assertTrue(body.keys.containsAll(required))
    }

    @Test fun `PUT cycle settings sends all 14 fields`() {
        val body = HealthForms.cycleSettingsBody(CycleSettings())
        assertEquals(
            setOf(
                "cycle_length_avg", "period_length_avg", "luteal_length", "track_fertility", "pregnancy_mode",
                "pregnancy_due_date", "default_visibility", "remind_period_days_before", "remind_log_daily",
                "contraception", "perimenopause_mode", "show_pms", "notify_partner_user_id", "notify_partner_days_before",
            ),
            body.keys,
        )
        val partner = HealthForms.cycleSettingsBody(CycleSettings(notifyPartnerUserId = 7))["notify_partner_user_id"] as JsonPrimitive
        assertFalse(partner.isString)
        assertEquals(7, partner.intOrNull)
    }

    @Test fun `PUT nutrition targets sends all eight nutrients, as numbers`() {
        val empty = ok(HealthForms.targetBody(emptyMap(), 1, 1))
        assertEquals(HealthNutrition.NUTRIENTS.map { it.key }.toSet(), empty.keys)
        val one = ok(HealthForms.targetBody(mapOf("fat_g" to "70,5"), 3, 1))
        assertFalse((one["fat_g"] as JsonPrimitive).isString)
        assertEquals(3, (one["user_id"] as JsonPrimitive).intOrNull)
    }

    @Test fun `POST medication logs always names the status`() {
        val dose = HealthMeds.Dose(11, 5, today, "08:00", null)
        assertTrue("status" in HealthForms.doseBody(dose, take = false, nowStamp = "2026-11-04T08:05"))
        assertTrue("status" in HealthForms.doseBody(dose, take = true, nowStamp = "2026-11-04T08:05"))
        assertTrue("status" in HealthForms.prnBody(Medication(5), "2026-11-04T08:05"))
        val scheduleId = HealthForms.doseBody(dose, take = true, nowStamp = "x")["schedule_id"] as JsonPrimitive
        assertFalse(scheduleId.isString)
    }

    @Test fun `preventive-care type id and whole-number fields go as numbers`() {
        val body = ok(HealthForms.preventionBody(
            HealthForms.PreventionForm(typeId = 3, givenOn = today, dose = "2", interval = "1", intervalUnit = "years", offset = "14"),
            creating = true, personId = 4, meId = 1,
        ))
        for (k in listOf("type_id", "dose_number", "interval_months", "reminder_offset_days", "user_id")) {
            assertFalse(k, (body[k] as JsonPrimitive).isString)
        }
        assertEquals(12, (body["interval_months"] as JsonPrimitive).intOrNull)
        assertEquals(14, (body["reminder_offset_days"] as JsonPrimitive).intOrNull)
    }
}
