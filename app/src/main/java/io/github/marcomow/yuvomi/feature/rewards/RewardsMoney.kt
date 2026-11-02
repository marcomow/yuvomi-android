package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.i18n.Strings
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Pocket money amounts (`fmtMoney`, `moneyAmountProblem`, `minorToAmountInput` in
 * public/pages/rewards.js). Every money object names its currency and its decimals
 * (`minor_unit`) itself; the decimals come from the server, never from the formatter.
 */
class RewardsMoney(val locale: Locale) {

    /** The currency of [ctx] and its decimals, with the household's as the fallback (`moneyCtx`). */
    data class Ctx(val currency: String, val digits: Int)

    fun ctx(currency: String?, minorUnit: Int?, household: MoneyState?): Ctx {
        val code = currency ?: household?.currency ?: "EUR"
        val digits = (if (currency != null) minorUnit else household?.minorUnit ?: minorUnit) ?: defaultDigits(code)
        return Ctx(code, digits)
    }

    /** `currencyFractionDigits` (CLDR): writes COP, HUF, IDR, IRR and PYG without decimals although ISO gives them two. */
    private fun displayDigits(code: String): Int = if (code in CLDR_WHOLE) 0 else defaultDigits(code)

    private fun defaultDigits(code: String): Int =
        runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrDefault(2).takeIf { it >= 0 } ?: 2

    /** `fmtMoney`: minor units as "77,31 €". */
    fun format(minor: Long, ctx: Ctx): String {
        val format = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        runCatching { format.currency = Currency.getInstance(ctx.currency) }
        format.minimumFractionDigits = ctx.digits
        format.maximumFractionDigits = ctx.digits
        return format.format(BigDecimal.valueOf(minor, ctx.digits))
    }

    private val symbols: DecimalFormatSymbols get() = DecimalFormatSymbols.getInstance(locale)

    private fun plain(digits: Int, value: BigDecimal): String = (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
        isGroupingUsed = false
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }.format(value)

    /** `amountPlaceholder`: "0,00". */
    fun placeholder(ctx: Ctx): String = plain(ctx.digits, BigDecimal.ZERO)

    /** `minorToAmountInput`: minor units as the field shows them; whole amounts of a currency without decimals drop them. */
    fun toInput(minor: Long, ctx: Ctx): String {
        val value = BigDecimal.valueOf(kotlin.math.abs(minor), ctx.digits)
        val digits = if (displayDigits(ctx.currency) == 0 && value.stripTrailingZeros().scale() <= 0) 0 else ctx.digits
        return plain(digits, value)
    }

    enum class Problem { NOT_POSITIVE, GROUPED, INVALID, PRECISION }

    /** `toAmountDecimalString`: the field's text as "12.50"; "" when it carries a thousands group. */
    fun toDecimal(text: String): String {
        val raw = text.trim().replaceFirst(Regex("^[−‒–﹣－]"), "-")
        if (raw.isEmpty()) return ""
        val dec = symbols.decimalSeparator
        val grp = symbols.groupingSeparator
        val digits = raw.map { c -> Character.digit(c, 10).let { d -> if (d >= 0 && c !in '0'..'9') '0' + d else c } }.joinToString("")
        if (grp != dec && !grp.isWhitespace() && Regex("${Regex.escape(grp.toString())}\\d{3}(?!\\d)").containsMatchIn(digits)) return ""
        val decimal = digits.map { if (it == dec) '.' else it }.joinToString("")
        if (Regex(",\\d{3}(?!\\d)").containsMatchIn(decimal)) return ""
        return decimal.replaceFirst(',', '.').replaceFirst(Regex("^(-?)\\."), "$10.")
    }

    /** `moneyAmountProblem`: why the text cannot be sent, or null. */
    fun problem(text: String, ctx: Ctx): Problem? {
        val raw = text.trim()
        if (raw.isEmpty()) return Problem.NOT_POSITIVE
        val decimal = toDecimal(raw)
        if (decimal.isEmpty() || Regex("\\d\\s+\\d").containsMatchIn(decimal)) return Problem.GROUPED
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(decimal)) return Problem.INVALID
        if (!(decimal.toDouble() > 0)) return Problem.NOT_POSITIVE
        val fraction = decimal.substringAfter('.', "")
        return if (fraction.length > ctx.digits) Problem.PRECISION else null
    }

    /** `amountProblemText`, with the step from the server's decimals. */
    fun problemText(problem: Problem, ctx: Ctx, strings: Strings): String {
        val example = plain(ctx.digits, BigDecimal(1250))
        return when (problem) {
            Problem.GROUPED -> strings.t("common.amountGrouped", "example" to example)
            Problem.INVALID -> strings.t("common.amountInvalid", "example" to example)
            Problem.NOT_POSITIVE -> strings.t("common.amountNotPositive")
            Problem.PRECISION -> strings.t(
                "common.amountPrecisionRequired",
                "currency" to ctx.currency,
                "step" to plain(ctx.digits, BigDecimal.ONE.movePointLeft(ctx.digits)),
            )
        }
    }

    private companion object {
        val CLDR_WHOLE = setOf("COP", "HUF", "IDR", "IRR", "PYG")
    }

    /** `decimalToMinor`: only for the comparison with the balance. */
    fun toMinor(decimal: String, ctx: Ctx): Long = BigDecimal(decimal).movePointRight(ctx.digits).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}
