package io.github.marcomow.yuvomi.feature.pantry

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Port of upstream's `public/utils/pantry-status.js` (PANTRY-R7, R8). */
object PantryStatus {
    /** `EXPIRY_SOON_DAYS`. */
    const val SOON_DAYS = 7L

    enum class Expiry { EXPIRED, SOON }

    /** The chips besides "All" and the places, in upstream's order (`PANTRY_FILTERS`). */
    val FILTERS = listOf("expired", "soon", "low")

    data class Status(val out: Boolean, val low: Boolean, val expiry: Expiry?)

    /** A phrase for `t()`: the key and its count, if any. */
    data class Phrase(val key: String, val count: Long? = null)

    /** `pantryItemStatus`. */
    fun of(quantity: Double, minQuantity: Double?, expiresOn: String?, today: LocalDate): Status {
        val out = quantity <= 0
        val low = !out && minQuantity != null && minQuantity.isFinite() && quantity <= minQuantity
        val expires = expiresOn?.takeIf { it.isNotEmpty() }
        val todayKey = today.toString()
        val expiry = when {
            expires == null -> null
            // Plain string comparison, as upstream: YYYY-MM-DD sorts chronologically.
            expires < todayKey -> Expiry.EXPIRED
            expires <= today.plusDays(SOON_DAYS).toString() -> Expiry.SOON
            else -> null
        }
        return Status(out, low, expiry)
    }

    fun of(item: PantryItem, today: LocalDate): Status = of(item.quantity, item.minQuantity, item.expiresOn, today)

    /** `daysUntil`: whole calendar days from [today] to [dateKey]; negative lies behind. */
    fun daysUntil(dateKey: String, today: LocalDate): Long? =
        runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(dateKey.take(10))) }.getOrNull()

    /** `pantryExpiryPhrase`. */
    fun phrase(days: Long): Phrase = when {
        days == -1L -> Phrase("pantry.badgeExpiredYesterday")
        days < 0 -> Phrase("pantry.badgeExpiredDays", -days)
        days == 0L -> Phrase("pantry.badgeExpiresToday")
        days == 1L -> Phrase("pantry.badgeExpiresTomorrow")
        else -> Phrase("pantry.badgeExpiresDays", days)
    }

    /** `matchesPantryFilter`; "low" includes empty items. */
    fun matches(item: PantryItem, filter: String?, today: LocalDate): Boolean {
        if (filter == null || filter == "all") return true
        val s = of(item, today)
        return when (filter) {
            "expired" -> s.expiry == Expiry.EXPIRED
            "soon" -> s.expiry == Expiry.SOON
            "low" -> s.low || s.out
            else -> true
        }
    }

    /** `pantryFilterCounts`. */
    fun counts(items: List<PantryItem>, today: LocalDate): Map<String, Int> {
        val counts = mutableMapOf("expired" to 0, "soon" to 0, "low" to 0)
        for (item in items) {
            val s = of(item, today)
            if (s.expiry == Expiry.EXPIRED) counts["expired"] = counts.getValue("expired") + 1
            if (s.expiry == Expiry.SOON) counts["soon"] = counts.getValue("soon") + 1
            if (s.low || s.out) counts["low"] = counts.getValue("low") + 1
        }
        return counts
    }

    /** `pantryFilterFromSearch`: only the three status chips come in through a link. */
    fun filterFromLink(value: String?): String? = value?.takeIf { it in FILTERS }
}
