package io.github.marcomow.yuvomi.feature.tasks

/**
 * Port of the parts of upstream's `public/rrule-ui.js` tasks use (TASKS-R26,
 * TASKS-R28, TASKS-R29): parse, build, and the wording rule that keeps a rule
 * verbatim when the form did not change what it shows (#756).
 */
object RRule {

    data class Parts(
        val freq: String = "",
        val interval: Int = 1,
        val byday: List<String> = emptyList(),
        val until: String = "",
        val count: Int? = null,
        val lastDay: Boolean = false,
    )

    val FREQS = listOf("", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    fun parse(rule: String?): Parts {
        if (rule.isNullOrBlank()) return Parts()
        var p = Parts()
        for (segment in rule.replace(Regex("^RRULE:", RegexOption.IGNORE_CASE), "").split(';')) {
            val eq = segment.indexOf('=')
            if (eq == -1) continue
            val key = segment.substring(0, eq).uppercase()
            val value = segment.substring(eq + 1)
            p = when (key) {
                "FREQ" -> p.copy(freq = value)
                "INTERVAL" -> p.copy(interval = value.toIntOrNull()?.takeIf { it != 0 } ?: 1)
                "BYDAY" -> p.copy(byday = value.split(',').map { it.trim() })
                "UNTIL" -> p.copy(until = untilDay(value))
                "COUNT" -> p.copy(count = value.toIntOrNull()?.takeIf { it > 0 })
                "BYMONTHDAY" -> if (value.trim() == "-1") p.copy(lastDay = true) else p
                else -> p
            }
        }
        return p
    }

    /**
     * Upstream's `untilDayOf` for tasks: a task series has no zone of its own,
     * so the date part of `UNTIL` is the day.
     */
    private fun untilDay(value: String): String {
        val c = value.trim().replace(Regex("[TZ].*$", RegexOption.IGNORE_CASE), "")
        return if (c.length >= 8) "${c.substring(0, 4)}-${c.substring(4, 6)}-${c.substring(6, 8)}" else ""
    }

    /** `UNTIL` and `COUNT` exclude each other; `COUNT` wins (#513). `UNTIL` is a plain date (#1974). */
    fun build(p: Parts): String? {
        if (p.freq.isEmpty()) return null
        val parts = mutableListOf("FREQ=${p.freq}")
        if (p.interval > 1) parts += "INTERVAL=${p.interval}"
        if (p.freq == "WEEKLY" && p.byday.isNotEmpty()) parts += "BYDAY=${p.byday.joinToString(",")}"
        if (p.freq == "MONTHLY" && p.lastDay) parts += "BYMONTHDAY=-1"
        if (p.count != null && p.count > 0) {
            parts += "COUNT=${p.count}"
        } else if (p.until.isNotEmpty()) {
            parts += "UNTIL=${p.until.replace("-", "")}"
        }
        return parts.joinToString(";")
    }

    /**
     * The value to save for the form's [shown] parts, given the stored [source].
     * When the form shows exactly what parsing the source gave, the source goes
     * back verbatim, so rule parts the form does not model survive (#756).
     */
    fun resolve(shown: Parts, source: String?): String? {
        val built = build(shown) ?: return null
        if (source != null && build(parse(source)) == built) return source
        return built
    }

    /**
     * True when the stored rule says something the form cannot show (BYSETPOS,
     * a BYMONTHDAY other than -1, BYDAY outside weekly, ordinal weekdays). Those
     * parts survive as long as the repetition fields are not changed.
     */
    fun hasForeignParts(source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        val segments = source.replace(Regex("^RRULE:", RegexOption.IGNORE_CASE), "").split(';').filter { it.isNotBlank() }
        val map = segments.associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
        if (map.keys.any { it !in setOf("FREQ", "INTERVAL", "BYDAY", "UNTIL", "COUNT", "BYMONTHDAY", "WKST") }) return true
        if (map["BYMONTHDAY"]?.let { it.trim() != "-1" || map["FREQ"] != "MONTHLY" } == true) return true
        val byday = map["BYDAY"] ?: return false
        return map["FREQ"] != "WEEKLY" || byday.split(',').any { it.trim() !in WEEKDAYS }
    }
}
