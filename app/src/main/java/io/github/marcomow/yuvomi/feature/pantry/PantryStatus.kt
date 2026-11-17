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

    /** Low and out of a product (`pantryStockStatus`, `pantryProductStock`; PANTRY-R23). */
    data class Stock(val key: String, val min: Double?, val total: Double, val out: Boolean, val low: Boolean)

    /** `pantryStockStatus`: a total against a minimum; null is no minimum, never low. */
    fun stockStatus(total: Double, min: Double?): Pair<Boolean, Boolean> {
        val out = total <= 0
        val low = !out && min != null && min.isFinite() && total <= min
        return out to low
    }

    /** `hasPantryMinimum`: not null and above 0; a minimum of 0 never warns. */
    fun hasMinimum(min: Double?): Boolean = min != null && min.isFinite() && min > 0

    /**
     * `pantryProductStock`: the stock of every product from the batches and `products` of
     * `GET /pantry`. A batch without a product stands for itself with its own minimum; a
     * product without batches is out only when it has a minimum, and never low.
     */
    /** The release that moved the minimum to the product (part 3 of #1448; PANTRY-R29). */
    const val PRODUCT_MINIMUM_SINCE = "2.85.0"

    /** An unreadable version counts as new, like the version gate (SIGNIN-R14). */
    fun productMinimums(serverVersion: String?): Boolean =
        (io.github.marcomow.yuvomi.core.session.VersionGate.compare(serverVersion, PRODUCT_MINIMUM_SINCE) ?: 0) >= 0

    /**
     * PANTRY-R29: a batch's minimum without the deprecated `min_quantity` on the batch where
     * the server knows better. From 2.85.0 on, the minimum of a batch with a product is the
     * product's, read from `products`; the batch field is only read for a batch without a
     * product, for a product the list does not carry, and on servers before 2.85.0.
     */
    fun minimumOf(item: PantryItem, products: List<PantryProduct>, productMinimums: Boolean): Double? {
        if (!productMinimums || item.productId == null) return item.minQuantity
        val product = products.firstOrNull { it.id == item.productId } ?: return item.minQuantity
        return product.minQuantity
    }

    fun productStock(items: List<PantryItem>, products: List<PantryProduct>, productMinimums: Boolean = true): Map<String, Stock> {
        val mins = LinkedHashMap<String, Double?>()
        val batches = LinkedHashMap<String, MutableList<PantryItem>>()
        for (p in products) {
            mins["p${p.id}"] = p.minQuantity
            batches["p${p.id}"] = mutableListOf()
        }
        for (item in items) {
            val key = PantryView.productKeyOf(item)
            if (key !in batches) {
                mins[key] = item.minQuantity
                batches[key] = mutableListOf()
            }
            batches.getValue(key) += item
        }
        return batches.mapValues { (key, list) ->
            // A server before 2.85.0 has no minimum on the product: the highest of its batches,
            // as upstream's migration keeps it. From 2.85.0 on the product's own is the answer,
            // also when it is none (PANTRY-R29).
            val min = if (productMinimums || key !in products.map { "p${it.id}" }) mins[key]
            else mins[key] ?: list.mapNotNull { it.minQuantity }.maxOrNull()
            val total = PantryView.total(list)
            if (list.isEmpty()) {
                Stock(key, min, total, out = hasMinimum(min), low = false)
            } else {
                val (out, low) = stockStatus(total, min)
                Stock(key, min, total, out, low)
            }
        }
    }

    /** `pantryItemStatus`: out and low read the batch alone (its only use is a lone batch). */
    fun of(quantity: Double, minQuantity: Double?, expiresOn: String?, today: LocalDate): Status {
        val (out, low) = stockStatus(quantity, minQuantity)
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

    /**
     * `matchesPantryFilter`; "low" includes empty items. With [stock] a batch matches "low"
     * when its product is low or out; without it the batch is read alone.
     */
    fun matches(item: PantryItem, filter: String?, today: LocalDate, stock: Map<String, Stock>? = null): Boolean {
        if (filter == null || filter == "all") return true
        val s = of(item, today)
        return when (filter) {
            "expired" -> s.expiry == Expiry.EXPIRED
            "soon" -> s.expiry == Expiry.SOON
            "low" -> stock?.get(PantryView.productKeyOf(item))?.let { it.low || it.out } ?: (s.low || s.out)
            else -> true
        }
    }

    /**
     * `pantryFilterCounts`: expired and soon count batches; low counts products that are low
     * or out over their total, a product without batches with a minimum included.
     */
    fun counts(items: List<PantryItem>, today: LocalDate, products: List<PantryProduct> = emptyList()): Map<String, Int> {
        val counts = mutableMapOf("expired" to 0, "soon" to 0, "low" to 0)
        for (item in items) {
            val s = of(item, today)
            if (s.expiry == Expiry.EXPIRED) counts["expired"] = counts.getValue("expired") + 1
            if (s.expiry == Expiry.SOON) counts["soon"] = counts.getValue("soon") + 1
        }
        counts["low"] = productStock(items, products).values.count { it.low || it.out }
        return counts
    }

    /** `pantryFilterFromSearch`: only the three status chips come in through a link. */
    fun filterFromLink(value: String?): String? = value?.takeIf { it in FILTERS }
}
