package io.github.marcomow.yuvomi.feature.settings

import io.github.marcomow.yuvomi.core.api.ApiJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Ports of `admin-backup.js` and `cronScheduleIntervalMs` (upstream 2.89.0, 2.90.0; SET-R80, R81). */
class BackupRulesTest {
    private val now = Instant.parse("2026-11-17T12:00:00Z")
    private fun json(s: String) = ApiJson.parseToJsonElement(s) as JsonObject

    /** SET-A41: the schedules whose interval is known, and the ones without. */
    @Test fun `cron intervals like upstream`() {
        val day = 24L * 3600 * 1000
        assertEquals(day, BackupRules.intervalMs("0 2 * * *"))
        assertEquals(6L * 3600 * 1000, BackupRules.intervalMs("0 */6 * * *"))
        assertEquals(7 * day, BackupRules.intervalMs("0 2 * * mon"))
        assertEquals(7 * day, BackupRules.intervalMs("0 2 * * 7"))
        assertEquals(31 * day, BackupRules.intervalMs("0 2 15 * *"))
        assertEquals(62 * day, BackupRules.intervalMs("0 2 31 * *"))
        assertNull(BackupRules.intervalMs("0 2 * * mon-fri"))
        assertNull(BackupRules.intervalMs("0 2 * * mon,wed"))
        assertNull(BackupRules.intervalMs("0 2 * 1 *"))
        assertNull(BackupRules.intervalMs("0 */24 * * *"))
        assertNull(BackupRules.intervalMs(null))
    }

    /** SET-A42: "Last backup" is the last success; overdue after two intervals, or when none ever succeeded. */
    @Test fun `last success, failed attempt and overdue`() {
        val failedAfterSuccess = BackupRules.scheduler(json("""{"enabled":true,"schedule":"0 2 * * *",
            "lastBackup":{"timestamp":"2026-11-17T02:00:00Z","success":false,"reason":"disk_full"},
            "lastSuccess":"2026-11-16T02:00:00Z","lastError":{"reason":"disk_full"},"nextRun":"2026-11-18T02:00:00Z"}"""))
        assertEquals("2026-11-16T02:00:00Z", BackupRules.lastSuccessful(failedAfterSuccess))
        assertEquals("2026-11-17T02:00:00Z", BackupRules.failedAttempt(failedAfterSuccess))
        assertEquals("settings.backupSchedulerReasonDiskFull", BackupRules.reasonKey(failedAfterSuccess.lastErrorReason))
        assertEquals(Instant.parse("2026-11-18T02:00:00Z"), BackupRules.nextRun(failedAfterSuccess))
        assertFalse(BackupRules.overdue(failedAfterSuccess, now))
        assertFalse(BackupRules.statusOk(failedAfterSuccess, now))

        val stale = BackupRules.scheduler(json("""{"enabled":true,"schedule":"0 2 * * *",
            "lastBackup":{"timestamp":"2026-11-14T02:00:00Z","success":true},"lastError":null}"""))
        assertEquals("2026-11-14T02:00:00Z", BackupRules.lastSuccessful(stale))
        assertTrue(BackupRules.overdue(stale, now))
        assertFalse(BackupRules.statusOk(stale, now))

        val neverSucceeded = BackupRules.scheduler(json("""{"enabled":true,"schedule":"0 2 * * mon-fri",
            "lastBackup":{"timestamp":"2026-11-17T02:00:00Z","success":false}}"""))
        assertTrue(BackupRules.overdue(neverSucceeded, now))
        val fresh = BackupRules.scheduler(json("""{"enabled":true,"schedule":"0 2 * * *",
            "lastBackup":{"timestamp":"2026-11-17T02:00:00Z","success":true},"lastError":null,"nextRun":"not a time"}"""))
        assertTrue(BackupRules.statusOk(fresh, now))
        assertNull(BackupRules.nextRun(fresh))
        assertNull(BackupRules.failedAttempt(fresh))
        assertFalse(BackupRules.overdue(BackupRules.scheduler(json("""{"enabled":false}""")), now))
    }

    /** SET-A43: "Create backup now" reads the answer; only an explicit success is one. */
    @Test fun `a failed manual run names its reason`() {
        assertEquals("settings.backupSchedulerTriggeredToast", BackupRules.triggerKey(true, null))
        assertEquals("settings.backupSchedulerReasonVerifyFailed", BackupRules.triggerKey(false, "backup_verify_failed"))
        assertEquals("common.errorGeneric", BackupRules.triggerKey(false, "something_new"))
        assertEquals("common.errorGeneric", BackupRules.triggerKey(null, null))
    }
}
