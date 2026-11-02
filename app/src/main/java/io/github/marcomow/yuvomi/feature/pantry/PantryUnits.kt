package io.github.marcomow.yuvomi.feature.pantry

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Port of upstream's `public/utils/pantry-units.js` (PANTRY-R6, R12). */
object PantryUnits {
    val ALL = listOf("pcs", "g", "kg", "ml", "l", "pkg", "can", "bottle", "jar", "bag")
    const val DEFAULT = "pcs"
    const val MAX_QUANTITY = 1_000_000.0

    private val STEP = mapOf(
        "pcs" to 1.0, "g" to 100.0, "kg" to 0.5, "ml" to 100.0, "l" to 0.5,
        "pkg" to 1.0, "can" to 1.0, "bottle" to 1.0, "jar" to 1.0, "bag" to 1.0,
    )

    /** `normalizePantryUnit`: unknown or empty becomes `pcs`. */
    fun normalizeUnit(value: String?): String = value?.trim()?.takeIf { it in ALL } ?: DEFAULT

    /** `pantryUnitStep`: the stepper's step; unknown units step by 1. */
    fun step(unit: String?): Double = STEP[normalizeUnit(unit)] ?: 1.0

    /** `normalizePantryQuantity`: two decimals, within 0 and [MAX_QUANTITY]; not a number gives [fallback]. */
    fun normalizeQuantity(value: Double?, fallback: Double = 1.0): Double {
        if (value == null || !value.isFinite()) return fallback
        val rounded = BigDecimal(value * 100).setScale(0, RoundingMode.HALF_UP).toDouble() / 100
        return min(MAX_QUANTITY, max(0.0, rounded)) + 0.0
    }

    /** A form field's text as upstream's `Number(value)`: blank is 0, junk is not a number. */
    fun parse(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return 0.0
        return t.toDoubleOrNull()
    }

    /** Upstream's `getNumberFormat({ maximumFractionDigits: 2 })`: 2 stays "2", 2.5 is localized. */
    fun formatNumber(value: Double, locale: String): String =
        NumberFormat.getNumberInstance(Locale.forLanguageTag(locale)).apply {
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    /** The unit's name for a picker, the raw value when there is no string for it. */
    fun unitLabel(unit: String?, strings: Strings): String {
        val key = "pantry.units.$unit"
        val label = strings.t(key)
        return if (label == key) unit.orEmpty() else label
    }

    /** `pantryQuantityLabel`: "6 cans", "1 can", "1.5 kg"; the unit inflects with the amount. */
    fun quantityLabel(quantity: Double, unit: String?, strings: Strings): String {
        val key = "pantry.units.$unit"
        val label = strings.t(key, "count" to quantity)
        return "${formatNumber(quantity, strings.locale)} ${if (label == key) unit.orEmpty() else label}"
    }
}
