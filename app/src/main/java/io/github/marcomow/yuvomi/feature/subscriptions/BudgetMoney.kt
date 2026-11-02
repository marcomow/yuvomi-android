package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Money as the budget page's tabs write it (upstream `public/utils/money.js`, `amount-field.js`):
 * one formatter for every amount (`formatMoney`, role `plain`: no sign, no colour), and the
 * amount fields read as TEXT in the region's notation (`toAmountDecimalString`,
 * `amountInputProblem`), so "1.200" under German is refused and named, never silently 1.2.
 * Plain JDK formatting, so the rules run in unit tests too. Shared by Subscriptions and
 * Split expenses (docs/spec/subscriptions.md, docs/spec/splitexpenses.md).
 */
object BudgetMoney {
    /** `public/utils/currency-codes.js` (`CURRENCY_CODES`). */
    val CURRENCY_CODES = listOf(
        "AED", "ARS", "AUD", "BBD", "BOB", "BRL", "BSD", "BYN", "BZD", "CAD", "CHF", "CLP",
        "CNY", "COP", "CRC", "CUP", "CZK", "DKK", "DOP", "EUR", "GBP", "GTQ", "GYD",
        "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR", "IRR", "JMD", "JPY", "KRW", "KZT", "MOP",
        "MXN", "MYR", "NIO", "NOK", "NZD", "PAB", "PEN", "PHP", "PLN", "PYG", "RUB", "SAR",
        "SEK", "SGD", "SRD", "TRY", "TTD", "TWD", "UAH", "USD", "UYU", "VES", "VND", "XCD", "ZAR",
    )

    const val GROUPED = "grouped"
    const val INVALID = "invalid"
    const val NOT_POSITIVE = "notPositive"
    const val PRECISION = "precision"

    fun locale(tag: String): Locale = Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() } ?: Locale.ENGLISH

    private fun currencyOf(code: String?): Currency? =
        code?.takeIf { Regex("^[A-Z]{3}$").matches(it) }?.let { runCatching { Currency.getInstance(it) }.getOrNull() }

    /** `currencyFractionDigits`: EUR 2, JPY 0, KWD 3; two for anything unknown. */
    fun fractionDigits(currency: String?): Int = currencyOf(currency)?.defaultFractionDigits?.takeIf { it >= 0 } ?: 2

    /** `formatMoney(amount, currency)`; a code that cannot be formatted falls back to [fallback] (`usableCurrency`). */
    fun format(amount: Double, currency: String?, localeTag: String, fallback: String = "EUR"): String {
        val code = currencyOf(currency) ?: currencyOf(fallback) ?: Currency.getInstance("EUR")
        val nf = NumberFormat.getCurrencyInstance(locale(localeTag))
        nf.currency = code
        val digits = fractionDigits(code.currencyCode)
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        return nf.format(if (amount.isFinite()) amount else 0.0)
    }

    /** A decimal string from the server ("12.50"), formatted; unreadable text stays as it is, like the web. */
    fun format(decimal: String?, currency: String?, localeTag: String): String {
        val value = decimal?.trim()?.toDoubleOrNull() ?: return "${decimal.orEmpty()} ${currency.orEmpty()}".trim()
        return format(value, currency, localeTag)
    }

    /** A share in the region's notation: `getNumberFormat({ style: 'percent', maximumFractionDigits })`. */
    fun percent(ratio: Double, localeTag: String, maxFraction: Int = 0): String =
        NumberFormat.getPercentInstance(locale(localeTag)).apply { maximumFractionDigits = maxFraction }.format(ratio)

    /** `percentText(value)`: a whole percent value ("121") as text. */
    fun percentValue(value: Int, localeTag: String): String = percent(value / 100.0, localeTag)

    fun number(value: Double, localeTag: String, maxFraction: Int = 2): String =
        NumberFormat.getNumberInstance(locale(localeTag)).apply { maximumFractionDigits = maxFraction }.format(value)

    private fun symbols(localeTag: String) = DecimalFormatSymbols.getInstance(locale(localeTag))

    /**
     * `toAmountDecimalString`: the typed text in the server's notation (ASCII digits, a full stop,
     * no grouping). The region's separator and the comma of a different device keyboard are both
     * read as the decimal mark. Returns null for grouping ("1.200" under de, "10 000", ",500")
     * and the input unchanged-ish for anything else, which [problem] then calls invalid.
     */
    fun toDecimal(text: String?, localeTag: String): String? {
        val raw = text?.trim()?.replace('−', '-') ?: return ""
        if (raw.isEmpty()) return ""
        val sym = symbols(localeTag)
        val out = StringBuilder()
        var separators = 0
        var sawSpace = false
        for ((i, c) in raw.withIndex()) {
            when {
                Character.isDigit(c) -> {
                    if (sawSpace) return null
                    out.append(Character.digit(c, 10))
                }
                c == '-' && i == 0 -> out.append('-')
                c == '.' || c == ',' || c == sym.decimalSeparator || c == sym.monetaryDecimalSeparator -> {
                    separators++
                    out.append('.')
                    // `/,\d{3}(?!\d)/`, and the region's own grouping mark before three digits.
                    val rest = raw.substring(i + 1)
                    val three = Regex("^\\d{3}(?!\\d)").containsMatchIn(rest)
                    if (three && (c == ',' || (c == sym.groupingSeparator && c != sym.decimalSeparator))) return null
                }
                c.isWhitespace() || c == ' ' || c == ' ' || c == sym.groupingSeparator -> {
                    if (out.isNotEmpty()) sawSpace = true
                }
                else -> out.append(c)
            }
        }
        if (separators > 1) return null
        return out.toString().replace(Regex("^(-?)\\."), "$10.")
    }

    /** `parseAmountInput`: the field as a number, null when empty, NaN when unreadable. */
    fun parse(text: String?, localeTag: String): Double? {
        val decimal = toDecimal(text, localeTag) ?: return Double.NaN
        if (decimal.isEmpty()) return null
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(decimal)) return Double.NaN
        return decimal.toDoubleOrNull() ?: Double.NaN
    }

    /**
     * `amountInputProblem`: grouped, invalid, notPositive, precision, or null. A stored amount
     * outside the currency's grid stays savable untouched ([original], [originalCurrency]).
     */
    fun problem(
        text: String?,
        currency: String?,
        localeTag: String,
        required: Boolean = false,
        nonNegative: Boolean = false,
        original: Double? = null,
        originalCurrency: String? = null,
        checkPrecision: Boolean = true,
    ): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return if (required) NOT_POSITIVE else null
        val decimal = toDecimal(raw, localeTag) ?: return GROUPED
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(decimal)) return INVALID
        val value = decimal.toDouble()
        if (nonNegative) {
            if (value < 0 || decimal.startsWith("-")) return INVALID
        } else if (value <= 0) return NOT_POSITIVE
        if (!checkPrecision) return null
        val places = decimal.substringAfter('.', "").length
        if (places <= fractionDigits(currency)) return null
        val untouched = original != null && original == value &&
            (originalCurrency == null || originalCurrency == currency) &&
            places <= BigDecimal.valueOf(original).stripTrailingZeros().scale().coerceAtLeast(0)
        return if (untouched) null else PRECISION
    }

    /** `amountExample`: 1250 without grouping in the region's digits and the currency's places. */
    fun example(currency: String?, localeTag: String): String {
        val digits = fractionDigits(currency)
        return NumberFormat.getNumberInstance(locale(localeTag)).apply {
            isGroupingUsed = false
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(1250)
    }

    /** `amountPlaceholder`: zero with the currency's places ("0,00", "0"). */
    fun placeholder(currency: String?, localeTag: String): String {
        val digits = fractionDigits(currency)
        return NumberFormat.getNumberInstance(locale(localeTag)).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(0)
    }

    /** `amountProblemText`. */
    fun problemText(strings: Strings, problem: String, currency: String?): String {
        val tag = strings.locale
        return when (problem) {
            GROUPED -> strings.t("common.amountGrouped", "example" to example(currency, tag))
            INVALID -> strings.t("common.amountInvalid", "example" to example(currency, tag))
            NOT_POSITIVE -> strings.t("common.amountNotPositive")
            else -> {
                val digits = fractionDigits(currency)
                val step = NumberFormat.getNumberInstance(locale(tag)).apply {
                    isGroupingUsed = false
                    minimumFractionDigits = digits
                    maximumFractionDigits = digits
                }.format(if (digits == 0) 1.0 else Math.pow(10.0, -digits.toDouble()))
                strings.t("common.amountPrecisionRequired", "currency" to currency.orEmpty(), "step" to step)
            }
        }
    }

    /** `amountToInput`: a stored amount as field text in the region's notation, padded to the currency's places. */
    fun toInput(amount: String?, currency: String?, localeTag: String): String {
        val text = amount?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val match = Regex("^(-?)(\\d+)(?:\\.(\\d+))?$").matchEntire(text) ?: return text
        val (sign, whole, fraction) = match.destructured
        val digits = fractionDigits(currency)
        val padded = fraction.padEnd(digits, '0')
        val sep = symbols(localeTag).decimalSeparator
        return sign + whole + (if (padded.isNotEmpty()) "$sep$padded" else "")
    }

    fun toInput(amount: Double?, currency: String?, localeTag: String): String =
        if (amount == null || !amount.isFinite()) "" else toInput(BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString(), currency, localeTag)

    /** `toStoredNumber`: a plain number (a percentage, shares) in the region's notation, without padding. */
    fun plainNumber(value: Double, localeTag: String): String {
        val text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return text.replace('.', symbols(localeTag).decimalSeparator)
    }
}
