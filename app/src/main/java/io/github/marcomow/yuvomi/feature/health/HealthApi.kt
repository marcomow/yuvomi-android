package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * docs/spec/health.md, "Upstream operations". Reads of another person pass `user_id`; writes
 * for a person in the member's care carry `user_id` in the body (`ownerField`), own writes never.
 */
class HealthApi(private val client: YuvomiClient) {

    private fun person(personId: Int?): List<Pair<String, String>> = personId?.let { listOf("user_id" to it.toString()) }.orEmpty()

    private fun <T> list(json: JsonElement, item: kotlinx.serialization.KSerializer<T>): List<T> = json.decodeData(ListSerializer(item))

    // ── Shared ──

    suspend fun members(): List<HealthMember> = list(client.call(Operation.FAMILY_MEMBERS), HealthMember.serializer())

    suspend fun careFor(): List<Int> = list(client.call(HealthOperation.CAREGIVERS_ME), Int.serializer())

    /** `{ defaults: { scope: visibility } }`; sparse, a missing scope is `private`. */
    suspend fun visibilityDefaults(): Map<String, String> {
        val data = (client.call(HealthOperation.VISIBILITY_DEFAULTS) as? JsonObject)?.get("data") as? JsonObject
        val defaults = data?.get("defaults") as? JsonObject ?: return emptyMap()
        return defaults.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    /** `health_cycle_effective` of `GET /preferences`: the household opt-in and the member's own choice, already combined. */
    suspend fun cycleEnabled(): Boolean {
        val data = (client.call(Operation.PREFERENCES) as? JsonObject)?.get("data") as? JsonObject
        return (data?.get("health_cycle_effective") as? JsonPrimitive)?.booleanOrNull != false
    }

    // ── Vitals ──

    suspend fun vitals(personId: Int?): List<Vital> = list(client.call(HealthOperation.VITALS_LIST, query = person(personId)), Vital.serializer())

    suspend fun createVital(body: JsonObject, key: String): Vital =
        client.call(HealthOperation.VITAL_CREATE, body = body, idempotencyKey = key).decodeData(Vital.serializer())

    suspend fun updateVital(id: Int, body: JsonObject): Vital =
        client.call(HealthOperation.VITAL_UPDATE, mapOf("id" to id), body = body).decodeData(Vital.serializer())

    suspend fun deleteVital(id: Int) {
        client.call(HealthOperation.VITAL_DELETE, mapOf("id" to id))
    }

    // ── Medications ──

    /** `medsWithPlansPath`: plans and the dose log of a window in one request (`YYYY-MM-DDTHH:MM` bounds). */
    suspend fun medications(personId: Int?, logsFrom: String, logsTo: String): List<Medication> {
        val query = person(personId) + listOf("include" to "schedules,logs", "logs_from" to logsFrom, "logs_to" to logsTo)
        return list(client.call(HealthOperation.MEDS_LIST, query = query), Medication.serializer())
    }

    suspend fun createMedication(body: JsonObject, key: String): Medication =
        client.call(HealthOperation.MED_CREATE, body = body, idempotencyKey = key).decodeData(Medication.serializer())

    suspend fun updateMedication(id: Int, body: JsonObject): Medication =
        client.call(HealthOperation.MED_UPDATE, mapOf("id" to id), body = body).decodeData(Medication.serializer())

    suspend fun deleteMedication(id: Int) {
        client.call(HealthOperation.MED_DELETE, mapOf("id" to id))
    }

    suspend fun createSchedule(medId: Int, body: JsonObject, key: String): MedSchedule =
        client.call(HealthOperation.SCHEDULE_CREATE, mapOf("id" to medId), body = body, idempotencyKey = key).decodeData(MedSchedule.serializer())

    suspend fun deleteSchedule(id: Int) {
        client.call(HealthOperation.SCHEDULE_DELETE, mapOf("id" to id))
    }

    /** A planned dose without a log row yet, or an as-needed dose (`sendScheduledDose`, `handlePrnDose`). */
    suspend fun logDose(medId: Int, body: JsonObject, key: String): DoseBooking =
        booking(client.call(HealthOperation.DOSE_LOG, mapOf("id" to medId), body = body, idempotencyKey = key))

    /** `/logs/{id}/take` or `/skip` for a row the scheduler already made. */
    suspend fun markLog(logId: Int, take: Boolean, stockDeduct: Double?, key: String): DoseBooking {
        val body = buildJsonObject { if (take && stockDeduct != null) put("stock_deduct", stockDeduct) }
        return booking(client.call(if (take) HealthOperation.LOG_TAKE else HealthOperation.LOG_SKIP, mapOf("id" to logId), body = body, idempotencyKey = key))
    }

    /** The toast's undo: the entry and the stock go back together (`POST /logs/{id}/undo`). */
    suspend fun undoDose(logId: Int, stockCredit: Double) {
        client.call(HealthOperation.LOG_UNDO, mapOf("id" to logId), body = buildJsonObject { if (stockCredit > 0) put("stock_credit", stockCredit) })
    }

    suspend fun updateLog(logId: Int, body: JsonObject): MedLog =
        client.call(HealthOperation.LOG_UPDATE, mapOf("id" to logId), body = body).decodeData(MedLog.serializer())

    suspend fun deleteLog(logId: Int) {
        client.call(HealthOperation.LOG_DELETE, mapOf("id" to logId))
    }

    private fun booking(answer: JsonElement): DoseBooking {
        val obj = answer as? JsonObject ?: throw ApiProblem.Malformed("dose answer")
        val log = (obj["data"] as? JsonObject)?.takeIf { it.containsKey("status") }?.let {
            io.github.marcomow.yuvomi.core.api.ApiJson.decodeFromJsonElement(MedLog.serializer(), it)
        }
        val deducted = (obj["stock_deducted"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return DoseBooking(log, deducted)
    }

    // ── Fasting ──

    suspend fun fastingState(personId: Int?): FastingState =
        client.call(HealthOperation.FASTING_STATE, query = person(personId)).decodeData(FastingState.serializer())

    /** The next page of completed fasts (`history_next_cursor`). */
    suspend fun fastingHistory(personId: Int?, cursor: FastingCursor): Pair<List<Fast>, FastingCursor?> {
        val answer = client.call(
            HealthOperation.FASTING_HISTORY,
            query = person(personId) + listOf("before_at" to cursor.beforeAt, "before_id" to cursor.beforeId.toString()),
        )
        val rows = list(answer, Fast.serializer())
        val next = (answer.jsonObject["next_cursor"] as? JsonObject)?.let {
            io.github.marcomow.yuvomi.core.api.ApiJson.decodeFromJsonElement(FastingCursor.serializer(), it)
        }
        return rows to next
    }

    suspend fun fastingStats(personId: Int?): FastingStats =
        client.call(HealthOperation.FASTING_STATS, query = person(personId)).decodeData(FastingStats.serializer())

    suspend fun startFast(body: JsonObject, key: String): Fast =
        client.call(HealthOperation.FASTING_CREATE, body = body, idempotencyKey = key).decodeData(Fast.serializer())

    suspend fun finishFast(fast: Fast): Fast =
        client.call(HealthOperation.FASTING_FINISH, mapOf("id" to fast.id), body = buildJsonObject { put("expected_revision", fast.revision) })
            .decodeData(Fast.serializer())

    suspend fun updateFast(id: Int, body: JsonObject): Fast =
        client.call(HealthOperation.FASTING_UPDATE, mapOf("id" to id), body = body).decodeData(Fast.serializer())

    suspend fun deleteFast(fast: Fast) {
        client.call(HealthOperation.FASTING_DELETE, mapOf("id" to fast.id), query = listOf("expected_revision" to fast.revision.toString()))
    }

    suspend fun saveFastingSettings(body: JsonObject): FastingSettings =
        client.call(HealthOperation.FASTING_SETTINGS, body = body).decodeData(FastingSettings.serializer())

    // ── Cycle ──

    suspend fun periods(personId: Int?): List<CyclePeriod> = list(client.call(HealthOperation.PERIODS_LIST, query = person(personId)), CyclePeriod.serializer())

    suspend fun createPeriod(body: JsonObject, key: String): CyclePeriod =
        client.call(HealthOperation.PERIOD_CREATE, body = body, idempotencyKey = key).decodeData(CyclePeriod.serializer())

    suspend fun updatePeriod(id: Int, body: JsonObject): CyclePeriod =
        client.call(HealthOperation.PERIOD_UPDATE, mapOf("id" to id), body = body).decodeData(CyclePeriod.serializer())

    suspend fun deletePeriod(id: Int) {
        client.call(HealthOperation.PERIOD_DELETE, mapOf("id" to id))
    }

    suspend fun cycleLogs(personId: Int?): List<CycleDayLog> = list(client.call(HealthOperation.CYCLE_LOGS_LIST, query = person(personId)), CycleDayLog.serializer())

    /** One row per day: a second save of the same `log_date` replaces it (upsert). */
    suspend fun saveCycleLog(body: JsonObject, key: String): CycleDayLog =
        client.call(HealthOperation.CYCLE_LOG_SAVE, body = body, idempotencyKey = key).decodeData(CycleDayLog.serializer())

    suspend fun deleteCycleLog(id: Int) {
        client.call(HealthOperation.CYCLE_LOG_DELETE, mapOf("id" to id))
    }

    suspend fun cycleSettings(): CycleSettings = client.call(HealthOperation.CYCLE_SETTINGS).decodeData(CycleSettings.serializer())

    suspend fun saveCycleSettings(body: JsonObject): CycleSettings =
        client.call(HealthOperation.CYCLE_SETTINGS_SAVE, body = body).decodeData(CycleSettings.serializer())

    /** Every own period and day entry to one visibility; answers how many rows changed. */
    suspend fun setCycleVisibility(visibility: String): Pair<Int, Int> {
        val data = (client.call(HealthOperation.CYCLE_VISIBILITY, body = buildJsonObject { put("visibility", visibility) }) as? JsonObject)?.get("data") as? JsonObject
        fun n(k: String) = (data?.get(k) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return n("periods") to n("logs")
    }

    // ── Prevention ──

    suspend fun preventionTypes(): List<PreventionType> = list(client.call(HealthOperation.PREVENTION_TYPES), PreventionType.serializer())

    suspend fun preventionRecords(personId: Int?): List<PreventionRecord> =
        list(client.call(HealthOperation.PREVENTION_RECORDS, query = person(personId)), PreventionRecord.serializer())

    suspend fun preventionDue(personId: Int?): List<PreventionDue> =
        list(client.call(HealthOperation.PREVENTION_DUE, query = person(personId)), PreventionDue.serializer())

    suspend fun createPreventionRecord(body: JsonObject, key: String): PreventionRecord =
        client.call(HealthOperation.PREVENTION_RECORD_CREATE, body = body, idempotencyKey = key).decodeData(PreventionRecord.serializer())

    suspend fun updatePreventionRecord(id: Int, body: JsonObject): PreventionRecord =
        client.call(HealthOperation.PREVENTION_RECORD_UPDATE, mapOf("id" to id), body = body).decodeData(PreventionRecord.serializer())

    suspend fun deletePreventionRecord(id: Int) {
        client.call(HealthOperation.PREVENTION_RECORD_DELETE, mapOf("id" to id))
    }

    // ── Labs ──

    suspend fun labs(personId: Int?): List<LabReport> = list(client.call(HealthOperation.LABS_LIST, query = person(personId)), LabReport.serializer())

    suspend fun createLab(body: JsonObject, key: String): LabReport =
        client.call(HealthOperation.LAB_CREATE, body = body, idempotencyKey = key).decodeData(LabReport.serializer())

    suspend fun updateLab(id: Int, body: JsonObject): LabReport =
        client.call(HealthOperation.LAB_UPDATE, mapOf("id" to id), body = body).decodeData(LabReport.serializer())

    suspend fun deleteLab(id: Int) {
        client.call(HealthOperation.LAB_DELETE, mapOf("id" to id))
    }

    suspend fun createResult(reportId: Int, body: JsonObject, key: String): LabResult =
        client.call(HealthOperation.RESULT_CREATE, mapOf("id" to reportId), body = body, idempotencyKey = key).decodeData(LabResult.serializer())

    suspend fun updateResult(id: Int, body: JsonObject): LabResult =
        client.call(HealthOperation.RESULT_UPDATE, mapOf("id" to id), body = body).decodeData(LabResult.serializer())

    suspend fun deleteResult(id: Int) {
        client.call(HealthOperation.RESULT_DELETE, mapOf("id" to id))
    }

    // ── Activity ──

    suspend fun activities(personId: Int?): List<Activity> = list(client.call(HealthOperation.ACTIVITIES_LIST, query = person(personId)), Activity.serializer())

    suspend fun createActivity(body: JsonObject, key: String): Activity =
        client.call(HealthOperation.ACTIVITY_CREATE, body = body, idempotencyKey = key).decodeData(Activity.serializer())

    suspend fun updateActivity(id: Int, body: JsonObject): Activity =
        client.call(HealthOperation.ACTIVITY_UPDATE, mapOf("id" to id), body = body).decodeData(Activity.serializer())

    suspend fun deleteActivity(id: Int) {
        client.call(HealthOperation.ACTIVITY_DELETE, mapOf("id" to id))
    }

    // ── Nutrition ──

    suspend fun nutritionEntries(personId: Int?, from: String?): List<NutritionEntry> =
        list(client.call(HealthOperation.NUTRITION_ENTRIES, query = person(personId) + listOfNotNull(from?.let { "from" to it })), NutritionEntry.serializer())

    suspend fun nutritionSummary(personId: Int?): NutritionSummary =
        client.call(HealthOperation.NUTRITION_SUMMARY, query = person(personId)).decodeData(NutritionSummary.serializer())

    suspend fun createNutritionEntry(body: JsonObject, key: String): NutritionEntry =
        client.call(HealthOperation.NUTRITION_ENTRY_CREATE, body = body, idempotencyKey = key).decodeData(NutritionEntry.serializer())

    suspend fun updateNutritionEntry(id: Int, body: JsonObject): NutritionEntry =
        client.call(HealthOperation.NUTRITION_ENTRY_UPDATE, mapOf("id" to id), body = body).decodeData(NutritionEntry.serializer())

    suspend fun deleteNutritionEntry(id: Int) {
        client.call(HealthOperation.NUTRITION_ENTRY_DELETE, mapOf("id" to id))
    }

    /** All eight values; all empty deletes the target (`target: null`). */
    suspend fun saveNutritionTarget(body: JsonObject) {
        client.call(HealthOperation.NUTRITION_TARGETS_SAVE, body = body)
    }

    companion object {
        fun newKey(): String = YuvomiClient.newIdempotencyKey()
    }
}
