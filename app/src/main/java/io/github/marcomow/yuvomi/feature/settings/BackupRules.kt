package io.github.marcomow.yuvomi.feature.settings

import java.time.Instant

/**
 * The backup card's reading of `scheduler` in `GET /backup/status` and of the answer of
 * `POST /backup/trigger` (upstream 2.89.0 and 2.90.0, #2525; SET-R80, SET-R81). Ports of
 * `backupReasonSentence`, `lastSuccessfulBackup`, `backupSuccessOverdue` and `backupStatusTone`
 * in `public/settings/pages/admin-backup.js` and of `cronScheduleIntervalMs` in
 * `public/settings/cron-label.js`. Pure, so it is unit tested.
 */
object BackupRules {

    /** What the card reads from `scheduler`. */
    data class Scheduler(
        val enabled: Boolean,
        val schedule: String?,
        val lastBackupAt: String?,
        /** null when the last run's outcome is not told. */
        val lastBackupSuccess: Boolean?,
        val lastSuccess: String?,
        val lastErrorReason: String?,
        val hasLastError: Boolean,
        val nextRun: String?,
    )

    /** [Scheduler] from the tolerant JSON of `scheduler`. */
    fun scheduler(json: kotlinx.serialization.json.JsonObject): Scheduler {
        val last = json.child("lastBackup")
        val error = json["lastError"] as? kotlinx.serialization.json.JsonObject
        return Scheduler(
            enabled = json.bool("enabled"),
            schedule = json.str("schedule"),
            lastBackupAt = last.str("timestamp"),
            lastBackupSuccess = if (last.containsKey("success")) last.bool("success") else null,
            lastSuccess = json.str("lastSuccess"),
            lastErrorReason = error?.str("reason"),
            hasLastError = error != null,
            nextRun = json.str("nextRun"),
        )
    }

    /** `BACKUP_ERROR_REASON_KEYS`; any other reason reads the general sentence. */
    private val REASONS = mapOf(
        "backup_dir_not_writable" to "settings.backupSchedulerReasonDirNotWritable",
        "disk_full" to "settings.backupSchedulerReasonDiskFull",
        "backup_verify_failed" to "settings.backupSchedulerReasonVerifyFailed",
        "unknown" to "settings.backupSchedulerReasonUnknown",
    )

    /** `backupReasonSentence`: the sentence key of a failed run, for the row and the toast. */
    fun reasonKey(reason: String?): String = reason?.let(REASONS::get) ?: "common.errorGeneric"

    /** `lastSuccessfulBackup`: `lastSuccess` (2.89.0), or the last run when it succeeded. */
    fun lastSuccessful(s: Scheduler): String? =
        s.lastSuccess ?: s.lastBackupAt.takeIf { s.lastBackupSuccess == true }

    /** "Last attempt": only a run that failed has a row of its own. */
    fun failedAttempt(s: Scheduler): String? = s.lastBackupAt.takeIf { s.lastBackupSuccess == false }

    /** The "Next backup" row: a value that is no instant gives no row. */
    fun nextRun(s: Scheduler): Instant? = s.nextRun?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private const val HOUR_MS = 60L * 60 * 1000
    private const val DAY_MS = 24 * HOUR_MS
    private val NUMBER = Regex("^\\d+$")
    private val STEP = Regex("^\\*/(\\d+)$")
    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    private fun weekday(field: String): Int? {
        if (NUMBER.matches(field)) return field.toInt().takeIf { it in 0..7 }?.let { it % 7 }
        val name = field.lowercase()
        return WEEKDAYS.indexOfFirst { name == it || name == it.take(3) }.takeIf { it >= 0 }
    }

    /**
     * `cronScheduleIntervalMs`: the longest gap between two runs of a recognised schedule
     * (every N hours, daily, weekly, monthly), null for lists, ranges and anything else. A
     * month counts 31 days, a run on the 29th to 31st 62, so "overdue" never warns too early.
     */
    fun intervalMs(expression: String?): Long? {
        val f = expression.orEmpty().trim().split(Regex("\\s+"))
        if (f.size != 5) return null
        val (minute, hour, dom, month, dow) = f
        if (month != "*") return null
        STEP.matchEntire(hour)?.let { m ->
            if (!NUMBER.matches(minute) || dom != "*" || dow != "*") return null
            val count = m.groupValues[1].toInt()
            return if (count in 1..23) count * HOUR_MS else null
        }
        if (!NUMBER.matches(minute) || !NUMBER.matches(hour)) return null
        return when {
            dom == "*" && dow == "*" -> DAY_MS
            dom == "*" -> weekday(dow)?.let { 7 * DAY_MS }
            dow == "*" && NUMBER.matches(dom) -> dom.toInt().takeIf { it in 1..31 }?.let { (if (it > 28) 62 else 31) * DAY_MS }
            else -> null
        }
    }

    /**
     * `backupSuccessOverdue`: the last success is older than two intervals of the schedule, or
     * a run was tried and none ever succeeded. A schedule without a known interval never warns.
     */
    fun overdue(s: Scheduler, now: Instant): Boolean {
        if (!s.enabled) return false
        val last = lastSuccessful(s) ?: return s.lastBackupAt != null
        val interval = intervalMs(s.schedule) ?: return false
        val at = runCatching { Instant.parse(last) }.getOrNull() ?: return false
        return now.toEpochMilli() - at.toEpochMilli() > 2 * interval
    }

    /** `backupStatusTone`: "Active" is green only while nothing on the card asks for attention. */
    fun statusOk(s: Scheduler, now: Instant): Boolean =
        s.enabled && !s.hasLastError && s.lastBackupSuccess != false && !overdue(s, now)

    /**
     * The answer of "Create backup now" (2.90.0): a failed run is a 200 with `success: false`
     * and a `reason`. Success is only an explicit `true`; anything else names the reason.
     */
    fun triggerKey(success: Boolean?, reason: String?): String =
        if (success == true) "settings.backupSchedulerTriggeredToast" else reasonKey(reason)
}
