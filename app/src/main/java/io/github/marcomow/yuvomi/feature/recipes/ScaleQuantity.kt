package io.github.marcomow.yuvomi.feature.recipes

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Port of `public/utils/scale-quantity.js` and the number helpers it uses from
 * `utils/money.js` (`toDecimalString`, `breaksOffAtSeparator`, `toStoredNumber`) and
 * `utils/digits.js`. Only the leading number of a free-text quantity is scaled; the rest
 * of the line is kept from the original, code point for code point. Reading and writing
 * follow the format locale: upstream takes the household region stored in the browser and
 * falls back to the UI language; the app has no region of its own and uses the UI language.
 */
object ScaleQuantity {

    /** `scaleQuantityText`. Returns the text unchanged when there is nothing it can read. */
    fun scale(quantity: String?, factor: Double, locale: String): String? {
        if (quantity.isNullOrEmpty() || factor == 1.0) return quantity
        val original = quantity.trim()
        val text = toDecimalString(original, locale, freeText = true)
        if (text.isEmpty()) return quantity

        val chars = original.codePointsList()
        fun restOf(match: MatchResult, tail: Int): String {
            val start = match.groupValues[0].codePointCount() - match.groupValues[tail].codePointCount()
            return chars.drop(start).joinToString("") { String(Character.toChars(it)) }
        }

        MIXED.matchEntire(text)?.let { m ->
            if (breaksOffAtSeparator(m.groupValues[4])) return quantity
            val whole = m.groupValues[1].toDouble()
            val num = m.groupValues[2].toDouble()
            val den = m.groupValues[3].toDouble()
            if (den > 0) return format(((whole + num / den) * factor), locale) + restOf(m, 4)
        }
        FRACTION.matchEntire(text)?.let { m ->
            if (breaksOffAtSeparator(m.groupValues[3])) return quantity
            val num = m.groupValues[1].toDouble()
            val den = m.groupValues[2].toDouble()
            if (den > 0) return format((num / den) * factor, locale) + restOf(m, 3)
        }
        DECIMAL.matchEntire(text)?.let { m ->
            val cut = breaksOffAtSeparator(m.groupValues[2])
            val base = m.groupValues[1].toDoubleOrNull()
            if (!cut && base != null && base.isFinite()) return format(base * factor, locale) + restOf(m, 2)
        }
        return quantity
    }

    /** `servingsFactor`: chosen over stored servings; without stored servings the choice is the factor. */
    fun servingsFactor(base: Int?, chosen: Double): Double {
        if (!chosen.isFinite() || chosen <= 0) return 1.0
        return if (base != null && base != 0) chosen / base else chosen
    }

    /** `scaledIngredients`: new ingredients with the scaled quantity, or null when it ends up empty. */
    fun scaledIngredients(ingredients: List<Ingredient>, factor: Double, locale: String): List<Ingredient> =
        ingredients.map { it.copy(quantity = scale(it.quantity ?: "", factor, locale)?.ifEmpty { null }) }

    // ── The servings stepper (utils/servings-stepper.js) ──

    data class Range(val min: Double, val max: Double, val step: Double)

    /** Servings 1 to 99 in steps of 1; without stored servings, multiples 0.5 to 10 in steps of 0.5. */
    fun range(base: Int?): Range = if (base != null && base != 0) Range(1.0, 99.0, 1.0) else Range(0.5, 10.0, 0.5)

    /** `setServingsValue`: clamped and snapped to the step (JavaScript `Math.round`). */
    fun clamp(base: Int?, value: Double): Double {
        val r = range(base)
        return min(r.max, max(r.min, floor(value / r.step + 0.5) * r.step))
    }

    fun step(base: Int?, value: Double, direction: Int): Double = clamp(base, value + direction * range(base).step)

    /** The number shown in the stepper: at most one fraction digit, grouped as the locale does. */
    fun valueNumber(value: Double, locale: String): String =
        NumberFormat.getNumberInstance(Locale.forLanguageTag(locale)).apply {
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    // ── utils/money.js ──

    private val MIXED = Regex("""(\d+)$SPACE+(\d+)/(\d+)(.*)""")
    private val FRACTION = Regex("""(\d+)/(\d+)(.*)""")
    private val DECIMAL = Regex("""(\d+(?:\.\d+)?)(.*)""")
    private const val SPACE = "[\\s\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]"

    /**
     * Characters any selectable region writes as a decimal or grouping separator, without the
     * whitespace ones (`numberSeparators`): `, . ' ٫ ٬`, plus the typographic apostrophe newer
     * CLDR data uses for Swiss grouping.
     */
    private val SEPARATORS = setOf(",", ".", "'", "’", "٫", "٬")

    /** `breaksOffAtSeparator`: does the rest start with a separator directly followed by a digit? */
    fun breaksOffAtSeparator(rest: String): Boolean {
        val cps = rest.codePointsList()
        if (cps.size < 2) return false
        return String(Character.toChars(cps[0])) in SEPARATORS && Character.getType(cps[1]) == Character.DECIMAL_DIGIT_NUMBER.toInt()
    }

    private fun symbols(locale: String) = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale))

    /**
     * `toDecimalString(value, { freeText })`: digits of any system to ASCII, the locale's
     * decimal separator to `.`, the Arabic separators to their ASCII twins; one character in,
     * one character out. A grouped leading number answers `""` (refused, never guessed).
     */
    fun toDecimalString(value: String, locale: String, freeText: Boolean = false): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        val sym = symbols(locale)
        val zero = sym.zeroDigit.code
        val normalized = buildString {
            raw.codePointsList().forEach { cp ->
                when {
                    cp in zero..zero + 9 -> append(('0'.code + cp - zero).toChar())
                    Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() && Character.digit(cp, 10) >= 0 ->
                        append(('0'.code + Character.digit(cp, 10)).toChar())
                    else -> appendCodePoint(cp)
                }
            }
        }
        val decimalSep = sym.decimalSeparator.toString()
        val groupSep = sym.groupingSeparator.toString()
        if (groupSep != decimalSep) {
            val group = Regex.escape(groupSep)
            val area = if (freeText) {
                Regex("^\\d+(?:[${escapeClass(groupSep)}${escapeClass(decimalSep)}]\\d+)*").find(normalized)?.value.orEmpty()
            } else {
                normalized
            }
            if (Regex("$group\\d{3}(?!\\d)").containsMatchIn(area)) return ""
        }
        return buildString {
            normalized.codePointsList().forEach { cp ->
                val c = String(Character.toChars(cp))
                append(
                    when (c) {
                        decimalSep -> "."
                        "٫" -> "."
                        "٬" -> ","
                        else -> c
                    },
                )
            }
        }
    }

    /** `toStoredNumber`: at most two fraction digits, no grouping, the locale's digits and separator. */
    fun format(value: Double, locale: String): String =
        (NumberFormat.getNumberInstance(Locale.forLanguageTag(locale)) as DecimalFormat).apply {
            isGroupingUsed = false
            maximumFractionDigits = 2
            minimumFractionDigits = 0
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    private fun escapeClass(s: String) = s.map { "\\" + it }.joinToString("")

    private fun String.codePointsList(): List<Int> = codePoints().toArray().toList()
    private fun String.codePointCount(): Int = codePointCount(0, length)
}
