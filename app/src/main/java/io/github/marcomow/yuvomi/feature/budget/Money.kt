package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Money in the budget, ported from `public/utils/money.js` and `public/utils/amount-field.js`:
 * ONE source for format, sign and colour. The locale is the household's number locale
 * ([RegionPresets.numberLocaleFor], `getFormatLocale()`), falling back to the UI language; the
 * currency is the household's (`preferences.currency`) or the account's (#2302).
 *
 * Every amount has one of four roles (`MONEY_ROLES`), and the role decides sign and tone together:
 * `flow` always signed (one movement), `total` never (the label says the direction), `balance`
 * signed when negative, `plain` never and neutral (a price).
 */
class Money(val locale: Locale, fallbackCurrency: String = "EUR") {
    enum class Role { FLOW, TOTAL, BALANCE, PLAIN }
    enum class Tone { POSITIVE, NEGATIVE, NEUTRAL }

    /** `amountInputProblem`'s answers. */
    enum class Problem { NOT_POSITIVE, GROUPED, INVALID, PRECISION }

    /** Which signs an amount field takes (`sign` of `amountInputProblem`). */
    enum class Sign { POSITIVE, NON_NEGATIVE, ANY }

    private val fallback: String = fallbackCurrency.takeIf(::known) ?: "EUR"

    /** `usableCurrency`: a code the formatter knows, else the household's currency. */
    fun usable(currency: String?): String = currency?.takeIf(::known) ?: fallback

    private fun known(code: String): Boolean =
        code.length == 3 && runCatching { Currency.getInstance(code.uppercase(Locale.ROOT)) }.isSuccess

    private fun currencyFormat(currency: String?, digits: Int? = null): DecimalFormat {
        val format = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        val c = Currency.getInstance(usable(currency))
        format.currency = c
        val fraction = digits ?: fractionDigits(c.currencyCode)
        format.minimumFractionDigits = fraction
        format.maximumFractionDigits = fraction
        return format
    }

    /** `formatMoney`. */
    fun format(amount: Double, currency: String?): String = currencyFormat(currency).format(clean(amount))

    /** `formatMoneyAxis`: whole units for chart axes. */
    fun axis(amount: Double, currency: String?): String = currencyFormat(currency, digits = 0).format(clean(amount))

    /** `formatSignedAmount(...).text`. */
    fun signed(amount: Double, currency: String?, role: Role): String {
        val value = clean(amount)
        return when (role) {
            Role.TOTAL, Role.PLAIN -> format(abs(value), currency)
            Role.BALANCE -> format(value, currency)
            Role.FLOW -> {
                // `signDisplay: 'exceptZero'`: a plus where the locale writes its minus.
                val code = usable(currency)
                if (round(value, code) == 0.0) return format(0.0, code)
                if (value < 0) return format(value, code)
                val minus = (currencyFormat(code)).decimalFormatSymbols.minusSign
                format(-value, code).replaceFirst(minus, '+')
            }
        }
    }

    /** `resolveTone`. */
    fun tone(amount: Double, role: Role): Tone = when {
        role == Role.PLAIN || role == Role.TOTAL -> Tone.NEUTRAL
        amount > 0 -> Tone.POSITIVE
        amount < 0 -> Tone.NEGATIVE
        else -> Tone.NEUTRAL
    }

    /** `sharePercent`: the share of [part] in [total], `<1%` below half a percent. */
    fun share(part: Double, total: Double): String {
        val sum = abs(total)
        val share = if (sum > 0) abs(part) / sum else 0.0
        val format = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
        return if (share > 0 && share < 0.005) "<${format.format(0.01)}" else format.format(share)
    }

    /** `currencyFractionDigits`. */
    fun fractionDigits(currency: String?): Int =
        runCatching { Currency.getInstance(usable(currency)).defaultFractionDigits }.getOrDefault(2).takeIf { it >= 0 } ?: 2

    /** `roundToCurrency`. */
    fun round(amount: Double, currency: String?): Double {
        val factor = 10.0.pow(fractionDigits(currency))
        return clean((amount * factor).roundToLong() / factor)
    }

    /** `currencySymbol`. */
    fun symbol(currency: String?): String = currencyFormat(currency).decimalFormatSymbols.currencySymbol

    private fun plain(min: Int, max: Int, grouping: Boolean = false): DecimalFormat =
        (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            isGroupingUsed = grouping
            minimumFractionDigits = min
            maximumFractionDigits = max
        }

    /** `amountPlaceholder`: `0,00`, `0` for yen. */
    fun placeholder(currency: String?): String = fractionDigits(currency).let { plain(it, it).format(0) }

    /** `amountExample`: `1250,00`. */
    fun example(currency: String?): String = fractionDigits(currency).let { plain(it, it).format(1250) }

    /** `smallestUnitLabel`: `0,01`, `1` for yen. */
    fun smallestUnit(currency: String?): String = fractionDigits(currency).let { d ->
        plain(d, d).format(if (d == 0) 1.0 else 10.0.pow(-d))
    }

    /** A plain number in the locale (`getNumberFormat({ maximumFractionDigits })`). */
    fun number(value: Double, maxFraction: Int = 3): String = plain(0, maxFraction, grouping = true).format(clean(value))

    private val decimalSep: Char get() = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    private val groupSep: Char get() = DecimalFormatSymbols.getInstance(locale).groupingSeparator

    /** `amountToInput`: a stored amount as the field shows it, with the currency's decimals. */
    fun toInput(amount: Double?, currency: String?): String {
        if (amount == null || amount.isNaN()) return ""
        val text = BigDecimal(amount.toString()).stripTrailingZeros().toPlainString()
        val digits = fractionDigits(currency)
        val negative = text.startsWith("-")
        val body = text.removePrefix("-")
        val whole = body.substringBefore('.')
        val fraction = body.substringAfter('.', "").padEnd(digits, '0')
        return (if (negative) "-" else "") + whole + (if (fraction.isNotEmpty()) decimalSep + fraction else "")
    }

    /** `withAsciiMinus`. */
    private fun withAsciiMinus(text: String): String =
        text.trim().replaceFirst(Regex("^[−‒–﹣－]"), "-")

    /** `toDecimalString`: the locale's digits and decimal separator to `123.45`; `""` when grouped. */
    private fun toDecimalString(raw: String): String {
        if (raw.isEmpty()) return ""
        val normalized = buildString {
            for (c in raw) {
                val d = Character.digit(c, 10)
                append(
                    when {
                        d >= 0 && !c.isAsciiDigit() -> ('0' + d)
                        c == '٫' -> '.'
                        c == '٬' -> ','
                        else -> c
                    },
                )
            }
        }
        val dec = decimalSep
        val grp = groupSep
        if (grp != dec && !grp.isWhitespace() && Regex("${Regex.escape(grp.toString())}\\d{3}(?!\\d)").containsMatchIn(normalized)) return ""
        return normalized.map { if (it == dec) '.' else it }.joinToString("")
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** `toAmountDecimalString`: also takes a decimal comma, never a thousands group. */
    private fun toAmountDecimalString(text: String): String {
        val decimal = toDecimalString(withAsciiMinus(text))
        if (Regex(",\\d{3}(?!\\d)").containsMatchIn(decimal)) return ""
        return decimal.replaceFirst(',', '.').replaceFirst(Regex("^(-?)\\."), "$10.")
    }

    /** `parseAmountInput`: null when empty, NaN when not a number. */
    fun parse(text: String?): Double? {
        val raw = withAsciiMinus(text.orEmpty())
        if (raw.isEmpty()) return null
        val decimal = toAmountDecimalString(raw)
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(decimal)) return Double.NaN
        return decimal.toDoubleOrNull() ?: Double.NaN
    }

    /** `amountInputProblem` with `decimalComma: true`, as every amount field of the budget asks it. */
    fun problem(
        text: String?,
        currency: String?,
        required: Boolean = true,
        sign: Sign = Sign.POSITIVE,
        original: Double? = null,
        originalCurrency: String? = null,
    ): Problem? {
        val raw = withAsciiMinus(text.orEmpty())
        if (raw.isEmpty()) return if (required) Problem.NOT_POSITIVE else null
        val decimal = toAmountDecimalString(raw)
        if (decimal.isEmpty() || Regex("\\d\\s+\\d").containsMatchIn(decimal)) return Problem.GROUPED
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(decimal)) return Problem.INVALID
        val value = decimal.toDouble()
        when (sign) {
            Sign.ANY -> Unit
            Sign.NON_NEGATIVE -> if (value < 0 || decimal.startsWith("-")) return Problem.INVALID
            Sign.POSITIVE -> if (value <= 0) return Problem.NOT_POSITIVE
        }
        val places = decimal.substringAfter('.', "").length
        if (places <= fractionDigits(currency)) return null
        val untouched = original != null && original == value &&
            (originalCurrency == null || originalCurrency == currency) &&
            places <= BigDecimal(original.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
        return if (untouched) null else Problem.PRECISION
    }

    /** `amountProblemText`. */
    fun problemText(problem: Problem, currency: String?, strings: Strings): String = when (problem) {
        Problem.GROUPED -> strings.t("common.amountGrouped", "example" to example(currency))
        Problem.INVALID -> strings.t("common.amountInvalid", "example" to example(currency))
        Problem.NOT_POSITIVE -> strings.t("common.amountNotPositive")
        Problem.PRECISION -> strings.t("common.amountPrecisionRequired", "currency" to usable(currency), "step" to smallestUnit(currency))
    }

    /** The field's error text, or null (`amountFieldError`). */
    fun fieldError(
        text: String?,
        currency: String?,
        strings: Strings,
        required: Boolean = true,
        sign: Sign = Sign.POSITIVE,
        original: Double? = null,
        originalCurrency: String? = null,
    ): String? = problem(text, currency, required, sign, original, originalCurrency)?.let { problemText(it, currency, strings) }

    private fun clean(v: Double): Double = if (v == 0.0 || v.isNaN()) 0.0 else v

    companion object {
        /** The money of a household: its number locale, else the UI language. */
        fun of(numberLocale: String?, uiLocale: String, currency: String?): Money =
            Money(Locale.forLanguageTag(numberLocale ?: uiLocale), currency ?: "EUR")
    }
}
