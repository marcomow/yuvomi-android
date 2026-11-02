package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The pure parts of the cycle area (public/utils/health-cycle.js, `cycleStats`, `predictCycle`). */
object HealthCycle {
    val FLOW = listOf("spotting", "light", "medium", "heavy")
    val SYMPTOMS = listOf(
        "cramps" to "cramps", "headache" to "headache", "backache" to "backache", "bloating" to "bloating",
        "tender_breasts" to "tenderBreasts", "acne" to "acne", "fatigue" to "fatigue", "nausea" to "nausea",
        "cravings" to "cravings", "insomnia" to "insomnia", "constipation" to "constipation", "diarrhea" to "diarrhea",
        "joint_pain" to "jointPain", "dizziness" to "dizziness", "hot_flashes" to "hotFlashes", "swelling" to "swelling",
        "libido_change" to "libidoChange", "discharge_change" to "dischargeChange", "appetite_change" to "appetiteChange",
        "concentration_difficulty" to "concentrationDifficulty",
    )
    val MOODS = listOf("great", "good", "neutral", "sensitive", "sad", "irritable", "anxious")
    val MUCUS = listOf("dry", "sticky", "creamy", "watery", "eggwhite")
    val TESTS = listOf("negative", "positive")
    val INTIMACY = listOf("protected", "unprotected", "solo")
    val CONTRACEPTION = listOf("none", "pill", "hormonal_iud", "copper_iud", "implant", "injection", "patch", "ring", "condom", "other")
    private val HORMONAL = setOf("pill", "hormonal_iud", "implant", "injection", "patch", "ring")

    fun symptomKey(value: String): String = "health.cycle.symptom." + (SYMPTOMS.firstOrNull { it.first == value }?.second ?: value)

    private const val DEFAULT_CYCLE = 28
    private const val DEFAULT_PERIOD = 5
    private const val DEFAULT_LUTEAL = 14
    private const val FERTILE_WINDOW_DAYS = 6
    private const val MAX_HISTORY = 6
    const val MIN_HISTORY_GAPS = 3
    private const val GESTATION_DAYS = 280

    fun days(a: LocalDate, b: LocalDate): Int = ChronoUnit.DAYS.between(a, b).toInt()

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    fun sortAsc(periods: List<CyclePeriod>): List<CyclePeriod> =
        periods.filter { date(it.startDate) != null }.sortedWith(compareBy<CyclePeriod> { it.startDate.take(10) }.thenBy { it.id })

    private data class Gaps(val plausible: List<Int>, val long: List<Int>, val chronological: List<Int>, val raw: Int)

    private fun gaps(asc: List<CyclePeriod>, today: LocalDate): Gaps {
        val plausible = mutableListOf<Int>()
        val long = mutableListOf<Int>()
        val chrono = mutableListOf<Int>()
        var raw = 0
        for (i in 1 until asc.size) {
            val a = date(asc[i - 1].startDate)!!
            val b = date(asc[i].startDate)!!
            val gap = days(a, b)
            if (gap <= 0) continue
            raw++
            if (a > today || b > today || gap < 10 || gap > 365) continue
            if (gap > 90) long += gap else plausible += gap
            chrono += gap
        }
        return Gaps(plausible, long, chrono, raw)
    }

    private fun periodLengths(asc: List<CyclePeriod>): List<Int> = asc.mapNotNull { p ->
        val s = date(p.startDate) ?: return@mapNotNull null
        val e = date(p.endDate) ?: return@mapNotNull null
        (days(s, e) + 1).takeIf { it in 1..15 }
    }

    data class Stats(
        val count: Int,
        val avgCycle: Int,
        val avgPeriod: Int,
        val luteal: Int,
        val minCycle: Int?,
        val maxCycle: Int?,
        val regular: Boolean?,
        val trackFertility: Boolean,
        val gapCount: Int,
        val source: String,
    )

    fun stats(periods: List<CyclePeriod>, settings: CycleSettings, today: LocalDate): Stats {
        val asc = sortAsc(periods)
        val g = gaps(asc, today)
        val rescue = g.plausible.size < MIN_HISTORY_GAPS && g.plausible.size + g.long.size >= MIN_HISTORY_GAPS
        val all = if (rescue) g.chronological else g.plausible
        val recent = all.takeLast(MAX_HISTORY)
        val lengths = periodLengths(asc).takeLast(MAX_HISTORY)
        val derivedCycle = if (recent.size >= MIN_HISTORY_GAPS) recent.average().roundToInt().coerceIn(15, 60) else null
        val derivedPeriod = if (lengths.size >= MIN_HISTORY_GAPS) lengths.average().roundToInt().coerceIn(1, 15) else null
        val settingCycle = settings.cycleLengthAvg?.coerceIn(15, 60)
        val settingPeriod = settings.periodLengthAvg?.coerceIn(1, 15)
        val luteal = settings.lutealLength?.coerceIn(8, 18) ?: DEFAULT_LUTEAL
        val minC = recent.minOrNull()
        val maxC = recent.maxOrNull()
        val regular = if (settings.perimenopauseMode || recent.size < 2) null else (maxC!! - minC!!) <= 7
        return Stats(
            count = asc.size,
            avgCycle = settingCycle ?: derivedCycle ?: DEFAULT_CYCLE,
            avgPeriod = settingPeriod ?: derivedPeriod ?: DEFAULT_PERIOD,
            luteal = luteal,
            minCycle = minC,
            maxCycle = maxC,
            regular = regular,
            trackFertility = settings.trackFertility,
            gapCount = recent.size,
            source = when {
                settingCycle != null -> "settings"
                derivedCycle != null -> "history"
                g.raw > 0 -> "insufficient_history"
                else -> "default"
            },
        )
    }

    data class Pregnancy(val active: Boolean, val dueDate: LocalDate?, val weeks: Int = 0, val days: Int = 0)

    fun pregnancy(settings: CycleSettings, today: LocalDate): Pregnancy {
        val due = date(settings.pregnancyDueDate)
        if (!settings.pregnancyMode || due == null) return Pregnancy(settings.pregnancyMode, due)
        val gest = (GESTATION_DAYS - days(today, due)).coerceIn(0, GESTATION_DAYS)
        return Pregnancy(true, due, gest / 7, gest % 7)
    }

    /** `detectTemperatureShift`: three readings at least 0.2 °C over the mean of the six before. */
    fun temperatureShift(logs: List<CycleDayLog>, since: LocalDate): LocalDate? {
        val readings = logs.mapNotNull { l ->
            val d = date(l.logDate) ?: return@mapNotNull null
            val t = l.basalTemp ?: return@mapNotNull null
            if (d < since) return@mapNotNull null
            d to if (l.basalTempUnit == "f") (t - 32) * 5 / 9 else t
        }.sortedBy { it.first }
        if (readings.size < 9) return null
        for (i in 6..readings.size - 3) {
            val baseline = readings.subList(i - 6, i).map { it.second }.average()
            if (readings.subList(i, i + 3).all { it.second >= baseline + 0.2 }) return readings[i].first
        }
        return null
    }

    data class Prediction(
        val hasData: Boolean,
        val pregnant: Boolean,
        val pregnancy: Pregnancy,
        val stats: Stats,
        val trackFertility: Boolean,
        val fertilitySuppressed: Boolean,
        val lastStart: LocalDate? = null,
        val cycleDay: Int = 0,
        val nextStart: LocalDate? = null,
        val daysUntilNext: Int = 0,
        val ovulation: LocalDate? = null,
        val ovulationConfirmed: Boolean = false,
        val fertileStart: LocalDate? = null,
        val fertileEnd: LocalDate? = null,
        val phase: String = "follicular",
        val nextRange: Pair<LocalDate, LocalDate>? = null,
    )

    fun predict(periods: List<CyclePeriod>, settings: CycleSettings, today: LocalDate, logs: List<CycleDayLog> = emptyList()): Prediction {
        val asc = sortAsc(periods)
        val stats = stats(asc, settings, today)
        val pregnancy = pregnancy(settings, today)
        val suppressed = settings.contraception in HORMONAL
        if (pregnancy.active) return Prediction(asc.isNotEmpty(), true, pregnancy, stats, false, suppressed)
        val track = stats.trackFertility && !suppressed
        if (asc.isEmpty()) return Prediction(false, false, pregnancy, stats, track, suppressed)

        val past = asc.filter { date(it.startDate)!! <= today }
        val lastStart = date((past.lastOrNull() ?: asc.last()).startDate)!!
        val cycleDay = days(lastStart, today) + 1
        val nextStart = lastStart.plusDays(stats.avgCycle.toLong())
        val inPeriod = asc.any { p ->
            val s = date(p.startDate)!!
            val e = date(p.endDate) ?: s.plusDays((stats.avgPeriod - 1).toLong())
            today in s..e
        }
        var ovulation = nextStart.minusDays(stats.luteal.toLong())
        var confirmed = false
        if (track) temperatureShift(logs, lastStart)?.let { ovulation = it; confirmed = true }
        val fertileStart = ovulation.minusDays((FERTILE_WINDOW_DAYS - 1).toLong())
        val phase = when {
            inPeriod || cycleDay in 1..stats.avgPeriod -> "menstruation"
            track && today == ovulation -> "ovulation"
            track && today in fertileStart..ovulation -> "fertile"
            today > ovulation -> "luteal"
            else -> "follicular"
        }
        val range = if (settings.perimenopauseMode && stats.gapCount >= MIN_HISTORY_GAPS && stats.minCycle != null && stats.maxCycle != null) {
            lastStart.plusDays(stats.minCycle.toLong()) to lastStart.plusDays(stats.maxCycle.toLong())
        } else null
        return Prediction(
            hasData = true, pregnant = false, pregnancy = pregnancy, stats = stats, trackFertility = track,
            fertilitySuppressed = suppressed, lastStart = lastStart, cycleDay = cycleDay, nextStart = nextStart,
            daysUntilNext = days(today, nextStart),
            ovulation = if (track) ovulation else null, ovulationConfirmed = track && confirmed,
            fertileStart = if (track) fertileStart else null, fertileEnd = if (track) ovulation else null,
            phase = phase, nextRange = range,
        )
    }

    fun phaseKey(phase: String): String = "health.cycle.phase.$phase"

    /** `health.cycle.status.*`: in N days, expected today, or N days late. */
    fun nextText(p: Prediction, s: Strings): String = when {
        p.daysUntilNext > 0 -> s.t("health.cycle.status.inDays", "count" to p.daysUntilNext)
        p.daysUntilNext == 0 -> s.t("health.cycle.status.today")
        else -> s.t("health.cycle.status.overdue", "count" to -p.daysUntilNext)
    }

    /** Cycle day and phase, the status line of the areas list (`cycleStatusText`). */
    fun statusText(periods: List<CyclePeriod>, settings: CycleSettings, logs: List<CycleDayLog>, today: LocalDate, s: Strings): String {
        val p = predict(periods, settings, today, logs)
        if (p.pregnant) return s.t("health.cycle.pregnancy.title")
        if (!p.hasData) return ""
        return s.t("health.cycle.bubble.line1", "day" to p.cycleDay, "phase" to s.t(phaseKey(p.phase)))
    }

    /** The open period of today, if any (`endPeriodToday`). */
    fun openPeriod(periods: List<CyclePeriod>, today: LocalDate): CyclePeriod? =
        sortAsc(periods).lastOrNull { it.endDate == null && date(it.startDate)!! <= today }

    /** Days between each start and the next (`cycleLengthTrend`), for the history rows. */
    fun cycleLengths(periods: List<CyclePeriod>): Map<Int, Int> {
        val asc = sortAsc(periods)
        val out = mutableMapOf<Int, Int>()
        for (i in 0 until asc.size - 1) {
            val n = days(date(asc[i].startDate)!!, date(asc[i + 1].startDate)!!)
            if (n > 0) out[asc[i].id] = n
        }
        return out
    }

    /** The heaviest flow logged inside a period (`periodFlowSummary`). */
    fun heaviestFlow(period: CyclePeriod, logs: List<CycleDayLog>, avgPeriod: Int): String? {
        val s = date(period.startDate) ?: return null
        val e = date(period.endDate) ?: s.plusDays((avgPeriod - 1).toLong())
        return logs.filter { l -> date(l.logDate)?.let { it in s..e } == true }.mapNotNull { it.flow }
            .maxByOrNull { FLOW.indexOf(it) }
    }

    /** Whether a new or edited period overlaps another (`warningOverlap`). */
    fun overlaps(periods: List<CyclePeriod>, start: LocalDate, end: LocalDate?, exceptId: Int?, avgPeriod: Int): Boolean =
        periods.filter { it.id != exceptId }.any { p ->
            val s = date(p.startDate) ?: return@any false
            val e = date(p.endDate) ?: s.plusDays((avgPeriod - 1).toLong())
            val myEnd = end ?: start
            max(s.toEpochDay(), start.toEpochDay()) <= min(e.toEpochDay(), myEnd.toEpochDay())
        }
}
