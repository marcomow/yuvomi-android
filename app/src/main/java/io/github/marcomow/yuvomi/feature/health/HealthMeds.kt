package io.github.marcomow.yuvomi.feature.health

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The pure parts of the medications area (public/utils/health-meds.js, health-overview.js). */
object HealthMeds {
    /** The adherence window of both the overview and the medications area (`ADHERENCE_WINDOW_DAYS`). */
    const val ADHERENCE_DAYS = 7

    /** How far back the dose log is read: the longest as-needed interval plus a day (`MED_LOG_WINDOW_MAX_DAYS`). */
    const val LOG_WINDOW_DAYS = 29

    /** One planned dose on one day (`computeDueDoses`). */
    data class Dose(val scheduleId: Int, val medicationId: Int, val date: LocalDate, val time: String, val doseQty: Double?) {
        val scheduledAt: String get() = "${date}T$time"
    }

    /** Monday is 0 (`weekdayIndex`). */
    fun weekdayIndex(d: LocalDate): Int = d.dayOfWeek.value - 1

    fun maskMatches(mask: Int?, weekday: Int): Boolean = mask == null || (mask and (1 shl weekday)) != 0

    fun maskToIndices(mask: Int?): Set<Int> = (0..6).filter { maskMatches(mask, it) }.toSet()

    /** Nothing or everything chosen is "daily" (`null`). */
    fun indicesToMask(indices: Set<Int>): Int? {
        if (indices.isEmpty() || indices.size >= 7) return null
        return indices.filter { it in 0..6 }.fold(0) { acc, i -> acc or (1 shl i) }.takeIf { it != 0 }
    }

    /** The schedules of the medications that count today: active ones only (#1950, `currentSchedules`). */
    fun currentSchedules(meds: List<Medication>): List<MedSchedule> =
        meds.filter { it.active }.flatMap { m -> m.schedules.map { if (it.medicationId == null) it.copy(medicationId = m.id) else it } }

    fun dueDoses(schedules: List<MedSchedule>, from: LocalDate, to: LocalDate): List<Dose> {
        if (from > to) return emptyList()
        val out = mutableListOf<Dose>()
        var d = from
        var guard = 0
        while (d <= to && guard < 1000) {
            val wd = weekdayIndex(d)
            for (s in schedules) {
                if (!s.active) continue
                if (s.startDate != null && d.toString() < s.startDate) continue
                if (s.endDate != null && d.toString() > s.endDate) continue
                if (!maskMatches(s.daysMask, wd)) continue
                out += Dose(s.id, s.medicationId ?: 0, d, s.timeOfDay.take(5), s.doseQty)
            }
            d = d.plusDays(1)
            guard++
        }
        return out.sortedWith(compareBy<Dose> { it.scheduledAt }.thenBy { it.scheduleId })
    }

    fun logFor(dose: Dose, logs: List<MedLog>): MedLog? =
        logs.firstOrNull { it.scheduleId == dose.scheduleId && it.scheduledAt?.take(16) == dose.scheduledAt }

    data class Adherence(val taken: Int, val skipped: Int, val planned: Int, val rate: Double?)

    fun adherence(logs: List<MedLog>, planned: Int): Adherence {
        val taken = logs.count { it.status == "taken" }
        val skipped = logs.count { it.status == "skipped" }
        val denom = max(planned, taken)
        return Adherence(taken, skipped, planned, if (denom > 0) taken.toDouble() / denom else null)
    }

    /** Planned doses of active medications only, in [from]..[to] (`allLogsInRange`). */
    fun scheduledLogsInRange(meds: List<Medication>, from: LocalDate, to: LocalDate): List<MedLog> =
        meds.filter { it.active }.flatMap { it.logs }.filter { it.scheduledAt != null }.filter {
            val k = it.whenKey.take(10)
            k >= from.toString() && k <= to.toString()
        }

    fun adherenceFor(meds: List<Medication>, today: LocalDate, days: Int = ADHERENCE_DAYS): Adherence {
        val from = today.minusDays((days - 1).toLong())
        val planned = dueDoses(currentSchedules(meds), from, today).size
        return adherence(scheduledLogsInRange(meds, from, today), planned)
    }

    /** Days in a row with every planned dose taken; today counts only once complete (`computeAdherenceStreak`). */
    fun streak(meds: List<Medication>, today: LocalDate, maxDays: Int = 60): Int {
        val schedules = currentSchedules(meds)
        val takenByDay = meds.filter { it.active }.flatMap { it.logs }.filter { it.scheduledAt != null && it.status == "taken" }
            .groupingBy { it.whenKey.take(10) }.eachCount()
        var streak = 0
        var day = today
        repeat(maxDays) {
            val planned = dueDoses(schedules, day, day).size
            if (planned > 0) {
                if ((takenByDay[day.toString()] ?: 0) >= planned) streak++ else if (day != today) return streak
            }
            day = day.minusDays(1)
        }
        return streak
    }

    /** Today's doses that are still open after [nowTime] (`upcomingDoses`). */
    fun upcoming(meds: List<Medication>, today: LocalDate, nowTime: String): List<Dose> {
        val logs = meds.flatMap { it.logs }
        return dueDoses(currentSchedules(meds), today, today).filter { d ->
            d.time >= nowTime && (logFor(d, logs)?.status ?: "pending") == "pending"
        }
    }

    // ── As needed (`prnDoseState`) ──

    data class PrnState(val lastTakenAt: LocalDateTime?, val nextAllowedAt: LocalDateTime?, val remainingMinutes: Long) {
        val allowed: Boolean get() = remainingMinutes <= 0
    }

    /** A log stamp as household wall time; `Z` stamps (`created_at`) are left to the caller's [utc] converter. */
    fun parseStamp(value: String?, utc: (String) -> LocalDateTime? = { null }): LocalDateTime? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(raw.drop(10))) return utc(raw)
        return try {
            LocalDateTime.parse(raw.replace(' ', 'T').let { if (it.length == 16) "$it:00" else it }.take(19))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun prnState(med: Medication, now: LocalDateTime, utc: (String) -> LocalDateTime? = { null }): PrnState {
        val hours = med.minIntervalHours?.takeIf { it > 0 }
        val last = med.logs.filter { it.status == "taken" }
            .mapNotNull { parseStamp(it.takenAt ?: it.scheduledAt ?: it.createdAt, utc) }
            .maxOrNull()
        if (last == null || hours == null) return PrnState(last, null, 0)
        val next = last.plusMinutes((hours * 60).roundToInt().toLong())
        val remainingSeconds = Duration.between(now, next).seconds
        return PrnState(last, next, if (remainingSeconds <= 0) 0 else ceil(remainingSeconds / 60.0).toLong())
    }

    fun prnMeds(meds: List<Medication>): List<Medication> = meds.filter { it.prn && it.active }

    // ── Stock (`refillState`) ──

    enum class Refill { NONE, OK, LOW, OUT }

    fun refill(med: Medication): Refill {
        val stock = med.stockQty ?: return Refill.NONE
        return when {
            stock <= 0 -> Refill.OUT
            med.refillThreshold != null && stock <= med.refillThreshold -> Refill.LOW
            else -> Refill.OK
        }
    }

    /** Each medication's log cut to its own window: [baseDays], or longer for a long as-needed interval (`splitMedPlans`). */
    fun trimLogs(meds: List<Medication>, today: LocalDate, baseDays: Int): List<Medication> = meds.map { m ->
        val hours = m.minIntervalHours
        val days = if (m.prn && hours != null && hours > 0) max(baseDays, ceil(hours / 24).toInt() + 1) else baseDays
        val from = "${today.minusDays((days - 1).toLong())}T00:00"
        m.copy(logs = m.logs.filter { it.whenKey.take(16) >= from })
    }

    /** Active medications by name, the status line of the areas list (`medsStatusText`). */
    fun statusText(meds: List<Medication>): String = meds.filter { it.active }.map { it.name }.filter { it.isNotEmpty() }.joinToString(", ")

    /** The 10 newest entries of the intake log, all medications together (`medLogHistoryMarkup`). */
    fun history(meds: List<Medication>, limit: Int = 10): List<Pair<Medication, MedLog>> =
        meds.flatMap { m -> m.logs.map { m to it } }
            .sortedByDescending { (_, l) -> l.takenAt ?: l.scheduledAt ?: l.createdAt.orEmpty() }
            .take(limit)
}
