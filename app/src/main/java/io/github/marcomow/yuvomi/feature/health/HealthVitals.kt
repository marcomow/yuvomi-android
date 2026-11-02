package io.github.marcomow.yuvomi.feature.health

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One measured kind (`VITAL_METRICS` in public/utils/health-vitals.js). */
data class VitalMetric(
    val type: String,
    val labelKey: String,
    val channels: Int,
    val channelLabelKeys: List<String>,
    val units: List<String>,
    /** `pair` (120/80), `duration` (decimal hours), `scale` (mood 1..5), or null for a plain number. */
    val format: String? = null,
)

/** A number in the UI language, at most [digits] fraction digits (`fmtNum`). */
fun Strings.num(value: Double?, digits: Int = 1, sign: Boolean = false): String {
    if (value == null || !value.isFinite()) return "-"
    val f = NumberFormat.getNumberInstance(Locale.forLanguageTag(locale)).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }
    val text = f.format(value)
    return if (sign && value > 0 && f.format(value) != f.format(0.0)) "+$text" else text
}

/** The pure parts of the vitals area (docs/spec/health.md, HEALTH-R10 to R16). */
object HealthVitals {
    val MOOD_KEYS = listOf(
        "health.vitals.mood.veryBad", "health.vitals.mood.bad", "health.vitals.mood.okay",
        "health.vitals.mood.good", "health.vitals.mood.veryGood",
    )

    val METRICS = listOf(
        VitalMetric(
            "bp", "health.vitals.metric.bp", 3,
            listOf("health.vitals.channel.systolic", "health.vitals.channel.diastolic", "health.vitals.channel.pulse"),
            listOf("mmHg"), "pair",
        ),
        VitalMetric("glucose", "health.vitals.metric.glucose", 1, listOf("health.vitals.metric.glucose"), listOf("mg/dL", "mmol/L")),
        VitalMetric("weight", "health.vitals.metric.weight", 1, listOf("health.vitals.metric.weight"), listOf("kg", "lb")),
        VitalMetric("height", "health.vitals.metric.height", 1, listOf("health.vitals.metric.height"), listOf("cm", "in")),
        VitalMetric("head_circumference", "health.vitals.metric.headCircumference", 1, listOf("health.vitals.metric.headCircumference"), listOf("cm", "in")),
        VitalMetric("spo2", "health.vitals.metric.spo2", 1, listOf("health.vitals.metric.spo2"), listOf("%")),
        VitalMetric("temp", "health.vitals.metric.temp", 1, listOf("health.vitals.metric.temp"), listOf("°C", "°F")),
        VitalMetric("sleep", "health.vitals.metric.sleep", 1, listOf("health.vitals.metric.sleep"), listOf("h"), "duration"),
        VitalMetric("mood", "health.vitals.metric.mood", 1, listOf("health.vitals.metric.mood"), emptyList(), "scale"),
    )

    fun metric(type: String): VitalMetric? = METRICS.firstOrNull { it.type == type }

    val RANGES = listOf("day", "week", "month", "year")

    // ── Units (`UNIT_CONVERSIONS`, `vitalDisplayUnit`, `convertVitalValue`) ──

    private data class Conv(val toBase: (Double) -> Double, val fromBase: (Double) -> Double)

    private fun linear(f: Double) = Conv({ it * f }, { it / f })
    private val fahrenheit = Conv({ (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 })
    private val length = mapOf("cm" to linear(1.0), "mm" to linear(0.1), "m" to linear(100.0), "in" to linear(2.54))
    // Two spellings of one unit share ONE object, so `limitReading` knows nothing is converted
    // (utils/vital-units.js, 2.84.0). 1 stone = 14 lb.
    private val pound = linear(0.45359237)
    private val celsius = linear(1.0)
    private val conversions: Map<String, Map<String, Conv>> = mapOf(
        "weight" to mapOf("kg" to linear(1.0), "g" to linear(0.001), "lb" to pound, "lbs" to pound, "stone" to linear(0.45359237 * 14)),
        "height" to length,
        "head_circumference" to length,
        "glucose" to mapOf("mg/dl" to linear(1.0), "mmol/l" to linear(18.0182)),
        "temp" to mapOf("°c" to celsius, "c" to celsius, "°f" to fahrenheit, "f" to fahrenheit),
    )

    private fun conv(type: String, unit: String?): Conv? = unit?.let { conversions[type]?.get(it.trim().lowercase()) }

    fun convert(type: String, value: Double, from: String?, to: String?): Double {
        val a = conv(type, from) ?: return value
        val b = conv(type, to) ?: return value
        if (a === b) return value
        // Long, not Int: 9,000 kg in micro-units would clamp at Int.MAX_VALUE.
        return Math.round(b.fromBase(a.toBase(value)) * 1e6) / 1e6
    }

    /** The most frequent of the dialog's units, the first on a tie or when none occurs. */
    fun displayUnit(metric: VitalMetric, rows: List<Vital>): String? {
        if (metric.units.size < 2) return metric.units.firstOrNull()
        val counts = metric.units.associateWith { u -> rows.count { it.unit?.trim()?.lowercase() == u.lowercase() } }
        return metric.units.fold(metric.units.first()) { best, u -> if (counts.getValue(u) > counts.getValue(best)) u else best }
    }

    private fun toDisplay(row: Vital, type: String, unit: String?): Vital {
        if (unit == null || conv(type, row.unit) == null || conv(type, unit) == null) return row
        return row.copy(
            unit = unit,
            value1 = row.value1?.let { convert(type, it, row.unit, unit) },
            value2 = row.value2?.let { convert(type, it, row.unit, unit) },
            value3 = row.value3?.let { convert(type, it, row.unit, unit) },
        )
    }

    // ── Periods (`buildVitalBuckets`, `isRollingAnchor`, `stepVitalAnchor`) ──

    private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun periodId(range: String, d: LocalDate): String = if (range == "week") weekStart(d).toString() else d.toString().take(7)

    private fun periodEnd(range: String, d: LocalDate): LocalDate =
        if (range == "week") weekStart(d).plusDays(6) else YearMonth.from(d).atEndOfMonth()

    fun isRolling(range: String, anchor: LocalDate, today: LocalDate): Boolean =
        (range == "week" || range == "month") && anchor <= today && periodId(range, anchor) == periodId(range, today)

    fun step(range: String, anchor: LocalDate, dir: Int, today: LocalDate): LocalDate {
        if (range == "day") return anchor.plusDays(dir.toLong())
        if (range == "year") return anchor.plusYears(dir.toLong())
        val kind = if (range == "week") "week" else "month"
        val current = periodId(kind, today)
        val end = periodEnd(kind, today)
        val hasRest = end > today
        if (periodId(kind, anchor) == current) {
            if (anchor <= today && dir > 0 && hasRest) return end
            if (anchor > today && dir < 0) return today
        }
        val next = if (kind == "week") anchor.plusWeeks(dir.toLong()) else YearMonth.from(anchor).plusMonths(dir.toLong()).atDay(1)
        if (periodId(kind, next) != current) return next
        return if (dir < 0 && hasRest) end else today
    }

    data class Buckets(val keys: List<String>, val from: LocalDate, val to: LocalDate, val gran: String)

    fun buckets(range: String, anchor: LocalDate, today: LocalDate): Buckets {
        fun rolling(days: Int): Buckets {
            val keys = (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
            return Buckets(keys, LocalDate.parse(keys.first()), today, "day")
        }
        return when (range) {
            "day" -> Buckets(listOf(anchor.toString()), anchor, anchor, "hour")
            "year" -> {
                val keys = (1..12).map { "%04d-%02d".format(anchor.year, it) }
                Buckets(keys, LocalDate.of(anchor.year, 1, 1), LocalDate.of(anchor.year, 12, 31), "month")
            }
            "week" -> if (isRolling("week", anchor, today)) rolling(7) else {
                val start = weekStart(anchor)
                Buckets((0L..6L).map { start.plusDays(it).toString() }, start, start.plusDays(6), "day")
            }
            else -> if (isRolling("month", anchor, today)) rolling(maxOf(30, today.dayOfMonth)) else {
                val ym = YearMonth.from(anchor)
                Buckets((1..ym.lengthOfMonth()).map { ym.atDay(it).toString() }, ym.atDay(1), ym.atEndOfMonth(), "day")
            }
        }
    }

    // ── Series (`computeVitalSeries`) ──

    data class Reading(val id: Int, val index: Int, val date: String, val minutes: Int, val values: List<Double?>)

    data class Point(val key: String, val count: Int, val avg: List<Double?>, val min: List<Double?>, val max: List<Double?>)

    data class Series(
        val type: String,
        val unit: String?,
        val from: LocalDate,
        val to: LocalDate,
        val gran: String,
        val points: List<Point>,
        val readings: List<Reading>,
        val latest: Vital?,
        val previous: Vital?,
        val deltas: List<Double?>,
    ) {
        val hasData: Boolean get() = points.any { it.count > 0 }
    }

    private fun minutesOf(at: String): Int = Regex("T(\\d{2}):(\\d{2})").find(at)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() } ?: 0

    private val byTime = compareByDescending<Vital> { it.measuredAt }.thenByDescending { it.id }

    fun series(rows: List<Vital>, type: String, range: String, anchor: LocalDate, today: LocalDate): Series {
        val metric = metric(type)
        val own = rows.filter { it.type == type }
        val unit = metric?.let { displayUnit(it, own) }
        val typed = own.map { toDisplay(it, type, unit) }
        val sorted = typed.sortedWith(byTime)
        val latest = sorted.getOrNull(0)
        val previous = sorted.getOrNull(1)
        val deltas = (0..2).map { i ->
            val a = latest?.channel(i)
            val b = previous?.channel(i)
            if (a != null && b != null) a - b else null
        }
        val b = buckets(range, anchor, today)
        val index = b.keys.withIndex().associate { (i, k) -> k to i }
        val sums = Array(b.keys.size) { DoubleArray(3) }
        val counts = Array(b.keys.size) { IntArray(3) }
        val mins = Array(b.keys.size) { arrayOfNulls<Double>(3) }
        val maxs = Array(b.keys.size) { arrayOfNulls<Double>(3) }
        val rowCounts = IntArray(b.keys.size)
        val readings = mutableListOf<Reading>()
        for (row in typed) {
            val dk = row.measuredAt.take(10)
            if (dk < b.from.toString() || dk > b.to.toString()) continue
            val i = index[if (b.gran == "month") dk.take(7) else dk] ?: continue
            rowCounts[i]++
            val values = (0..2).map { row.channel(it) }
            values.forEachIndexed { c, v ->
                if (v == null) return@forEachIndexed
                sums[i][c] += v
                counts[i][c]++
                if (mins[i][c] == null || v < mins[i][c]!!) mins[i][c] = v
                if (maxs[i][c] == null || v > maxs[i][c]!!) maxs[i][c] = v
            }
            readings += Reading(row.id, i, dk, minutesOf(row.measuredAt), values)
        }
        readings.sortWith(compareBy<Reading> { it.date }.thenBy { it.minutes }.thenBy { it.id })
        val points = b.keys.mapIndexed { i, k ->
            Point(k, rowCounts[i], (0..2).map { c -> if (counts[i][c] > 0) sums[i][c] / counts[i][c] else null }, mins[i].toList(), maxs[i].toList())
        }
        return Series(type, unit, b.from, b.to, b.gran, points, readings, latest, previous, deltas)
    }

    // ── Plausibility (`vitalPlausibility`), the server's hard limits and the soft ranges ──

    private val hard = mapOf(
        "glucose" to mapOf("mg/dL" to 10.0..2000.0, "mmol/L" to 0.5..111.0),
        "weight" to mapOf("kg" to 0.2..700.0, "lb" to 0.4..1550.0),
        "spo2" to mapOf("%" to 1.0..100.0),
        "temp" to mapOf("°C" to 10.0..46.0, "°F" to 50.0..115.0),
    )
    private val soft = mapOf(
        "glucose" to mapOf("mg/dL" to 50.0..350.0, "mmol/L" to 2.8..19.4),
        "weight" to mapOf("kg" to 2.0..250.0, "lb" to 4.5..550.0),
        "spo2" to mapOf("%" to 88.0..100.0),
        "temp" to mapOf("°C" to 35.0..41.0, "°F" to 95.0..105.8),
    )

    /**
     * `vitalLimitReading` (utils/vital-units.js, 2.84.0): the value in the listed unit the limits
     * stand against. "LB" and "lbs" are `lb` without converting; "g" and "stone" have no limits of
     * their own and are converted to the type's first unit. An unknown unit comes back unchanged
     * and finds no limit.
     */
    fun limitReading(type: String, unit: String?, value: Double?): Pair<String?, Double?> {
        val listed = metric(type)?.units ?: return unit to value
        if (unit == null) return unit to value
        listed.firstOrNull { it.trim().lowercase() == unit.trim().lowercase() }?.let { return it to value }
        val c = conv(type, unit) ?: return unit to value
        val twin = listed.firstOrNull { conv(type, it) === c }
        return if (twin != null) twin to value else listed.first() to value?.let { convert(type, it, unit, listed.first()) }
    }

    /** Per channel: `impossible`, `bpOrder`, `low`, `high`, or nothing. */
    fun plausibility(type: String, unit: String?, values: List<Double?>): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        fun check(c: Int, v: Double?, h: ClosedFloatingPointRange<Double>?, s: ClosedFloatingPointRange<Double>?) {
            if (v == null) return
            when {
                h != null && v !in h -> out[c] = "impossible"
                s != null && v < s.start -> out[c] = "low"
                s != null && v > s.endInclusive -> out[c] = "high"
            }
        }
        if (type == "bp") {
            if (limitReading("bp", unit ?: "mmHg", null).first == "mmHg") {
                check(0, values[0], 40.0..300.0, 70.0..220.0)
                check(1, values[1], 20.0..200.0, 40.0..130.0)
            }
            val a = values[0]
            val b = values[1]
            if (a != null && b != null && a <= b && out.values.none { it == "impossible" }) out[1] = "bpOrder"
            check(2, values.getOrNull(2), 20.0..300.0, 40.0..180.0)
            return out
        }
        // As on the server, the converted reading counts (#2375): a legacy row in grams stops at the same limits.
        val (limitUnit, value) = limitReading(type, unit, values[0])
        val h = if (type == "spo2") 1.0..100.0 else hard[type]?.get(limitUnit)
        check(0, value, h, soft[type]?.get(limitUnit))
        return out
    }

    // ── Text (`fmtDuration`, `vitalValueText`, `vitalCardValue`, `fmtVitalDelta`) ──

    fun split(hours: Double?): Pair<Int, Int>? {
        if (hours == null || !hours.isFinite() || hours < 0) return null
        val total = (hours * 60).roundToInt()
        return total / 60 to total % 60
    }

    fun toHours(h: Int, m: Int): Double = (h * 60 + m) / 60.0

    fun duration(hours: Double?, s: Strings): String {
        val (h, m) = split(hours) ?: return "-"
        return when {
            h > 0 && m > 0 -> s.t("health.duration.hm", "hours" to h, "minutes" to m)
            h > 0 -> s.t("health.duration.h", "hours" to h)
            else -> s.t("health.duration.m", "minutes" to m)
        }
    }

    fun moodKey(value: Double?): String? {
        if (value == null || !value.isFinite()) return null
        return MOOD_KEYS[(floor(value + 0.5).toInt()).coerceIn(1, 5) - 1]
    }

    fun channelText(metric: VitalMetric?, value: Double?, s: Strings): String = when {
        value == null -> "-"
        metric?.format == "duration" -> duration(value, s)
        metric?.format == "scale" -> s.t(moodKey(value) ?: "health.vitals.noValue")
        else -> s.num(value)
    }

    fun valueText(metric: VitalMetric?, row: Vital?, s: Strings): String = when {
        row == null -> "-"
        metric?.format == "pair" -> "${s.num(row.value1)}/${s.num(row.value2)}"
        else -> channelText(metric, row.value1, s)
    }

    fun unitText(metric: VitalMetric?, row: Vital?): String =
        if (metric?.format == "duration" || metric?.format == "scale") "" else row?.unit.orEmpty()

    /** Value and unit of a card: a duration as `7:30 h`. */
    fun cardValue(metric: VitalMetric, row: Vital?, s: Strings): Pair<String, String> {
        if (metric.format == "duration") {
            val (h, m) = split(row?.value1) ?: return "-" to ""
            return "$h:${"%02d".format(m)}" to s.t("health.duration.unitH")
        }
        return valueText(metric, row, s) to unitText(metric, row)
    }

    /** The full line of a reading: value plus unit, and the pulse after a blood pressure. */
    fun readingText(metric: VitalMetric?, row: Vital, s: Strings): String {
        val unit = unitText(metric, row)
        val base = valueText(metric, row, s) + if (unit.isNotEmpty()) " $unit" else ""
        return if (metric?.format == "pair" && row.value3 != null) "$base · ${s.num(row.value3)} /min" else base
    }

    fun deltaText(metric: VitalMetric, delta: Double?, s: Strings): String {
        if (delta == null) return ""
        if (metric.format == "duration") {
            val minutes = (delta * 60).roundToInt()
            return s.t("health.duration.m", "minutes" to (if (minutes > 0) "+$minutes" else "$minutes"))
        }
        if (abs(delta) < 0.05) return s.num(0.0)
        return s.num(delta, sign = true)
    }

    /** The status line of the areas list: the latest measurement of any kind (`vitalsStatusText`). */
    fun statusText(rows: List<Vital>, s: Strings): String {
        val latest = rows.maxWithOrNull(compareBy<Vital> { it.measuredAt }.thenBy { it.id }) ?: return ""
        val metric = metric(latest.type) ?: return ""
        val unit = unitText(metric, latest)
        return "${s.t(metric.labelKey)} ${valueText(metric, latest, s)}" + if (unit.isNotEmpty()) " $unit" else ""
    }

    /** The brief at the top of the overview (`overviewBriefMarkup`): what changed since the last measurement. */
    fun brief(rows: List<Vital>, today: LocalDate, s: Strings): String? {
        val measured = METRICS.filter { it.format != "scale" }
            .map { it to series(rows, it.type, "month", today, today) }
            .filter { it.second.latest != null }
            .sortedByDescending { it.second.latest!!.measuredAt }
        if (measured.isEmpty()) return null
        val changed = measured.filter { it.second.deltas[0] != null }.take(3)
        fun join(parts: List<String>) = parts.joinToString(", ")
        return if (changed.isNotEmpty()) {
            s.t("health.overview.brief.changes", "list" to join(changed.map { (m, sr) -> briefChange(m, sr, s) }))
        } else {
            s.t("health.overview.brief.latest", "list" to join(measured.take(3).map { (m, sr) ->
                val (v, u) = cardValue(m, sr.latest, s)
                s.t("health.overview.brief.item", "metric" to s.t(m.labelKey), "value" to v + if (u.isNotEmpty()) " $u" else "")
            }))
        }
    }

    private fun briefChange(m: VitalMetric, sr: Series, s: Strings): String {
        val label = s.t(m.labelKey)
        val d = sr.deltas
        val same = (0 until (if (m.format == "pair") 2 else 1)).all { d[it] == null || abs(d[it]!!) < 0.05 }
        if (same) return s.t("health.overview.brief.same", "metric" to label)
        val value = when (m.format) {
            "pair" -> "${deltaText(m, d[0], s)}/${deltaText(m, d[1], s)} ${sr.unit.orEmpty()}".trim()
            "duration" -> deltaText(m, d[0], s)
            else -> "${deltaText(m, d[0], s)} ${sr.unit.orEmpty()}".trim()
        }
        return s.t("health.overview.brief.item", "metric" to label, "value" to value)
    }
}
