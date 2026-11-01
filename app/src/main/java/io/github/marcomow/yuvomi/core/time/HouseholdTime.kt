package io.github.marcomow.yuvomi.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * "Today" is the household's day (ARCHITECTURE.md, rule 3): every date key is
 * computed in `timezone_effective`, never in the device zone.
 */
class HouseholdTime(val zone: ZoneId, private val clock: () -> Instant = Instant::now) {

    fun now(): ZonedDateTime = clock().atZone(zone)
    fun today(): LocalDate = now().toLocalDate()

    /**
     * Upstream datetimes come as a date (`2026-11-01`, all-day), a wall time
     * without offset (`2026-11-01T09:30`, household zone), or an instant with
     * `Z` or an offset.
     */
    fun parse(value: String?): ZonedDateTime? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            when {
                v.length == 10 -> LocalDate.parse(v).atStartOfDay(zone)
                v.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(v.substring(10)) ->
                    OffsetDateTime.parse(v.replace(" ", "T")).atZoneSameInstant(zone)
                else -> LocalDateTime.parse(v.replace(" ", "T")).atZone(zone)
            }
        } catch (e: DateTimeParseException) {
            null
        }
    }

    companion object {
        fun date(value: String?): LocalDate? =
            value?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }
    }
}
