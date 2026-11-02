package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** A receipt in an open form: a linked document, or a file waiting for its upload (`commit()`). */
sealed interface Receipt {
    val label: String

    data class Linked(val documentId: Int, override val label: String, val mimeType: String?) : Receipt
    data class Pending(val fileName: String, val mimeType: String, val bytes: ByteArray) : Receipt {
        override val label: String get() = fileName
        override fun equals(other: Any?) = other is Pending && other.fileName == fileName && other.bytes.contentEquals(bytes)
        override fun hashCode() = fileName.hashCode()
    }
}

/** The entry editor's fields (`openBudgetModal`), and what its save sends. */
data class EntryForm(
    val type: String = "expense",
    val amount: String = "",
    val title: String = "",
    val category: String = "",
    val subcategory: String = "",
    val date: LocalDate,
    val accountId: Int? = null,
    val visibility: String = "shared",
    val responsible: Set<Int> = emptySet(),
    val recurring: Boolean = false,
    val interval: String = "monthly",
    val intervalCount: String = "1",
    val virtual: Boolean = false,
    val confirmFirst: Boolean = false,
    val receipts: List<Receipt> = emptyList(),
) {
    val count: Int get() = (intervalCount.trim().toIntOrNull() ?: 1).coerceIn(1, BudgetLedger.MAX_INTERVAL_COUNT)

    /**
     * `entryFormErrors`: every field error at once, as `field to message`; the first one of a
     * field wins. [currency] is the chosen account's (`entryDialogCurrency`).
     */
    fun errors(money: Money, currency: String, subs: List<BudgetSubcategory>, original: Double?, strings: Strings): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (title.isBlank()) out["title"] = strings.t("common.titleRequired")
        money.fieldError(amount, currency, strings, required = true, original = original)?.let { out["amount"] = it }
        if (category.isEmpty()) out["category"] = strings.t("budget.categoryRequired")
        if (category.isNotEmpty() && subcategory.isEmpty() && BudgetLedger.subcategoryChoice(subs, null).required) {
            out["subcategory"] = strings.t("budget.subcategoryRequired")
        }
        return out
    }

    /**
     * The body of `POST /budget` and `PUT /budget/{id}` as the page builds it. [withAccount] is
     * false when the household has no accounts (the field is absent, `account_id` not sent);
     * [withResponsible] when the picker shows; [personal] adds `visibility`.
     */
    fun body(
        money: Money,
        withAccount: Boolean,
        withResponsible: Boolean,
        personal: Boolean,
        attachmentIds: List<Int>?,
    ): JsonObject = buildJsonObject {
        val abs = money.parse(amount) ?: 0.0
        put("title", title.trim())
        put("amount", if (type == "expense") -abs else abs)
        put("category", category)
        put("subcategory", subcategory)
        put("date", date.toString())
        put("is_recurring", if (recurring) 1 else 0)
        put("recurrence_interval", interval)
        put("recurrence_interval_count", count)
        put("recurrence_virtual", if (recurring && virtual) 1 else 0)
        put("recurrence_confirm", if (recurring && confirmFirst) 1 else 0)
        if (withAccount) put("account_id", accountId?.let(::JsonPrimitive) ?: JsonNull)
        if (withResponsible) put("responsible_user_ids", BudgetApi.ids(responsible.sorted()))
        if (personal && visibility in BudgetLedger.VISIBILITY_LEVELS) put("visibility", visibility)
        if (attachmentIds != null) put("attachment_document_ids", BudgetApi.ids(attachmentIds))
    }

    companion object {
        /** The form as `openBudgetModal({ mode: 'edit', entry })` fills it. */
        fun of(entry: BudgetEntry, money: Money, currency: String, subs: List<BudgetSubcategory>, known: List<BudgetCategory>): EntryForm {
            val shown = if (entry.recurrenceVirtual && entry.recurrenceFullAmount != null) entry.recurrenceFullAmount else entry.amount
            val category = entry.category.takeIf { c -> known.any { it.key == c } } ?: ""
            return EntryForm(
                type = if (entry.amount < 0) "expense" else "income",
                amount = money.toInput(kotlin.math.abs(shown), currency),
                title = entry.title,
                category = category,
                subcategory = BudgetLedger.subcategoryChoice(subs, entry.subcategory).value,
                date = LocalDate.parse(entry.date.take(10)),
                accountId = entry.accountId,
                visibility = entry.visibility ?: "shared",
                responsible = entry.responsibleUsers.map { it.id }.toSet(),
                recurring = entry.isRecurring,
                interval = entry.recurrenceInterval ?: "monthly",
                intervalCount = (entry.recurrenceIntervalCount ?: 1).coerceIn(1, BudgetLedger.MAX_INTERVAL_COUNT).toString(),
                virtual = entry.recurrenceVirtual,
                confirmFirst = entry.recurrenceConfirm,
                receipts = entry.attachments.orEmpty().mapNotNull { a -> a.documentId?.let { Receipt.Linked(it, a.label, a.mimeType) } },
            )
        }
    }
}

/** The transfer form (`transferFieldsHtml`, `wireTransferForm`). */
data class TransferForm(
    val amount: String = "",
    val from: Int? = null,
    val to: Int? = null,
    val title: String = "",
    val date: LocalDate,
    val scope: String = "this",
    val category: String = "",
    val subcategory: String = "",
    val visibility: String = "private",
    val recurring: Boolean = false,
    val interval: String = "monthly",
    val intervalCount: String = "1",
    val confirm: Boolean = false,
) {
    /** `transferTitle`: "Transfer A → B", or just "Transfer" without both accounts. */
    fun defaultTitle(accounts: List<BudgetAccount>, strings: Strings): String {
        val f = accounts.firstOrNull { it.id == from }?.name
        val t = accounts.firstOrNull { it.id == to }?.name
        return if (f != null && t != null) strings.t("budget.transferDefaultTitle", "from" to f, "to" to t) else strings.t("budget.typeTransfer")
    }

    fun errors(money: Money, currency: String, subs: List<BudgetSubcategory>, original: Double?, strings: Strings): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (category.isEmpty()) out["category"] = strings.t("budget.categoryRequired")
        if (subs.size > 1 && subcategory.isEmpty()) out["subcategory"] = strings.t("budget.subcategoryRequired")
        money.fieldError(amount, currency, strings, required = true, original = original)?.let { out["amount"] = it }
        if (from == null || to == null || from == to) out["to"] = strings.t("budget.transferChooseAccounts")
        return out
    }

    /** The body of `POST /budget/transfers` and `PUT /budget/transfers/{id}[/series]`. */
    fun body(money: Money, accounts: List<BudgetAccount>, personal: Boolean, withRecurrence: Boolean, strings: Strings): JsonObject = buildJsonObject {
        put("title", title.trim().ifEmpty { defaultTitle(accounts, strings) })
        put("amount", money.parse(amount) ?: 0.0)
        put("category", category)
        put("subcategory", subcategory)
        put("date", date.toString())
        put("from_account_id", from ?: 0)
        put("to_account_id", to ?: 0)
        if (personal) put("visibility", visibility)
        if (withRecurrence) {
            put("is_recurring", recurring)
            put("recurrence_interval", interval)
            put("recurrence_interval_count", intervalCount.trim().toIntOrNull() ?: 1)
            put("recurrence_confirm", confirm)
        }
    }

    companion object {
        /** The form of an existing transfer, or of its series when [series] (`scope` change). */
        fun of(transfer: BudgetTransfer, money: Money, currency: String, scope: String = "this"): TransferForm {
            val source = if (scope == "series") transfer.series ?: transfer else transfer
            val schedule = transfer.series ?: transfer
            return TransferForm(
                amount = money.toInput(kotlin.math.abs(source.amount), currency),
                from = source.fromAccountId,
                to = source.toAccountId,
                title = source.title,
                date = LocalDate.parse(source.date.take(10)),
                scope = scope,
                category = source.category,
                subcategory = source.subcategory ?: "",
                visibility = source.visibility ?: "private",
                recurring = transfer.isRecurring || transfer.recurrenceParentId != null,
                interval = schedule.recurrenceInterval ?: "monthly",
                intervalCount = (schedule.recurrenceIntervalCount ?: 1).toString(),
                confirm = schedule.recurrenceConfirm,
            )
        }
    }
}

/** The account form (`openAccountModal`). */
data class AccountForm(
    val name: String = "",
    val type: String = "checking",
    val balance: String = "",
    val creditBank: String = "",
    val creditLimit: String = "",
    val color: String? = null,
) {
    fun errors(money: Money, currency: String, original: BudgetAccount?, strings: Strings): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (name.isBlank()) out["name"] = strings.t("common.titleRequired")
        money.fieldError(balance, currency, strings, required = false, sign = Money.Sign.ANY, original = original?.startingBalance)?.let { out["balance"] = it }
        if (type == "credit") {
            money.fieldError(creditLimit, currency, strings, required = false, sign = Money.Sign.NON_NEGATIVE, original = original?.creditLimit)
                ?.let { out["creditLimit"] = it }
        }
        return out
    }

    fun body(money: Money): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("type", type)
        put("starting_balance", money.parse(balance)?.takeIf { !it.isNaN() } ?: 0.0)
        put("color", color?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        put("credit_bank", if (type == "credit") creditBank.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        put("credit_limit", if (type == "credit") money.parse(creditLimit)?.takeIf { !it.isNaN() }?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
    }

    companion object {
        fun of(a: BudgetAccount, money: Money, currency: String) = AccountForm(
            name = a.name,
            type = a.type,
            balance = money.toInput(a.startingBalance, currency),
            creditBank = a.creditBank.orEmpty(),
            creditLimit = a.creditLimit?.let { money.toInput(it, currency) }.orEmpty(),
            color = a.color,
        )

        /** `ACCOUNT_COLORS`: tokens, so the palette lightens in the dark theme. */
        val COLORS = listOf(
            "var(--chart-series-2)" to "budget.colorTeal",
            "var(--chart-series-4)" to "budget.colorBlue",
            "var(--chart-series-1)" to "budget.colorViolet",
            "var(--chart-series-6)" to "budget.colorMagenta",
            "var(--chart-series-3)" to "budget.colorOrange",
            "var(--chart-series-7)" to "budget.colorGreen",
            "var(--chart-series-5)" to "budget.colorOcher",
        )
    }
}

/** The loan form (`loanFormFieldsHtml`, `saveLoanFromPanel`). */
data class LoanForm(
    val direction: String = "lent",
    val borrower: String = "",
    val amount: String = "",
    val installments: String = "",
    val mode: String = "none",
    val principal: String = "",
    val fixedRate: String = "",
    val repayment: String = "",
    val fixedPeriod: String = "",
    val followupRate: String = "",
    val start: String = "",
    val dueDay: String = "",
    val paid: String = "0",
    val paidTouched: Boolean = false,
    val title: String = "",
    val accountId: Int? = null,
    val currency: String = "",
    val exchangeRate: String = "",
    val notes: String = "",
) {
    private fun dec(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    /** The body of `POST /budget/loans/preview`, or null while it is incomplete. */
    fun previewBody(money: Money): JsonObject? {
        if (mode == "none") return null
        val principalValue = money.parse(principal)
        val rate = dec(fixedRate)
        val rep = dec(repayment)
        val period = fixedPeriod.trim().toIntOrNull()
        val follow = dec(followupRate)
        val incomplete = !(principalValue != null && principalValue > 0) || !(rate != null && rate >= 0) || !(rep != null && rep > 0) ||
            (mode == "fixed_then_variable" && !(period != null && period > 0 && follow != null && follow >= 0))
        if (incomplete) return null
        return buildJsonObject {
            put("interest_mode", mode)
            put("principal", principalValue!!)
            put("fixed_rate", rate!!)
            put("initial_repayment_rate", rep!!)
            if (mode == "fixed_then_variable") {
                put("fixed_period_months", period!!)
                put("followup_rate", follow!!)
            }
        }
    }

    sealed interface Check {
        data class Field(val field: String, val message: String) : Check
        data class Ok(val body: JsonObject, val paid: Int?) : Check
    }

    /** `saveLoanFromPanel`'s checks in its order, then the body. */
    fun check(money: Money, baseCurrency: String, original: BudgetLoan?, withAccountField: Boolean, isEdit: Boolean, strings: Strings): Check {
        val borrowerText = borrower.trim()
        if (borrowerText.isEmpty()) return Check.Field("borrower", strings.t("budget.loanBorrowerRequired"))
        if (!Regex("^\\d{4}-\\d{2}$").matches(start)) return Check.Field("start", strings.t("budget.loanStartMonthRequired"))
        val cur = currency.ifEmpty { baseCurrency }
        var rate = 1.0
        if (cur != baseCurrency) {
            val r = dec(exchangeRate)
            if (r == null || !r.isFinite() || r <= 0) return Check.Field("exchangeRate", strings.t("budget.loanExchangeRateRequired"))
            rate = r
        }
        val originalCurrency = original?.currency ?: if (original != null) baseCurrency else null
        fun readPaid(): Pair<Boolean, Int?> {
            if (isEdit) return true to null
            val text = paid.trim()
            if (text.isEmpty()) return true to null
            val v = text.toIntOrNull()
            return if (v == null || v < 0) false to null else true to v
        }
        val body = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "borrower" to JsonPrimitive(borrowerText),
            "title" to JsonPrimitive(title.trim().ifEmpty { borrowerText }),
            "start_month" to JsonPrimitive(start),
            "notes" to JsonPrimitive(notes.trim()),
            "interest_mode" to JsonPrimitive(mode),
        )
        var paidValue: Int? = null
        if (mode == "none") {
            val total = money.parse(amount)
            val count = installments.trim().toIntOrNull()
            money.fieldError(amount, cur, strings, required = true, original = original?.totalAmount, originalCurrency = originalCurrency)
                ?.let { return Check.Field("amount", it) }
            if (count == null || count < 1) return Check.Field("installments", strings.t("budget.loanInstallmentsRequired"))
            val (ok, p) = readPaid()
            if (!ok) return Check.Field("paid", strings.t("budget.loanPaidInstallmentsInvalid"))
            if (p != null && p > count) return Check.Field("paid", strings.t("budget.loanPaidInstallmentsMax", "max" to count))
            body["total_amount"] = JsonPrimitive(total ?: 0.0)
            body["installment_count"] = JsonPrimitive(count)
            paidValue = p
        } else {
            if (principal.isBlank()) return Check.Field("principal", strings.t("budget.loanPrincipalRequired"))
            money.fieldError(principal, cur, strings, required = true, original = original?.interest?.principal, originalCurrency = originalCurrency)
                ?.let { return Check.Field("principal", it) }
            val r = dec(fixedRate)
            if (r == null || r < 0 || r > 100) return Check.Field("fixedRate", strings.t("budget.loanRateRequired"))
            val rep = dec(repayment)
            if (rep == null || rep <= 0 || rep > 100) return Check.Field("repayment", strings.t("budget.loanRepaymentRequired"))
            body["principal"] = JsonPrimitive(money.parse(principal) ?: 0.0)
            body["fixed_rate"] = JsonPrimitive(r)
            body["initial_repayment_rate"] = JsonPrimitive(rep)
            if (mode == "fixed_then_variable") {
                val period = fixedPeriod.trim().toIntOrNull()
                if (period == null || period < 1 || period > 600) return Check.Field("fixedPeriod", strings.t("budget.loanFixedPeriodRequired"))
                val follow = dec(followupRate)
                if (follow == null || follow < 0 || follow > 100) return Check.Field("followupRate", strings.t("budget.loanRateRequired"))
                body["fixed_period_months"] = JsonPrimitive(period)
                body["followup_rate"] = JsonPrimitive(follow)
            }
            val (ok, p) = readPaid()
            if (!ok) return Check.Field("paid", strings.t("budget.loanPaidInstallmentsInvalid"))
            paidValue = p
        }
        if (paidValue != null) body["paid_installments"] = JsonPrimitive(paidValue)
        val due = dueDay.trim()
        if (due.isNotEmpty()) {
            val d = due.toIntOrNull()
            if (d == null || d < 1 || d > 31) return Check.Field("dueDay", strings.t("budget.loanDueDayInvalid"))
            body["due_day"] = JsonPrimitive(d)
        } else {
            body["due_day"] = JsonNull
        }
        body["currency"] = JsonPrimitive(cur)
        body["exchange_rate"] = JsonPrimitive(rate)
        body["direction"] = JsonPrimitive(if (direction == "borrowed") "borrowed" else "lent")
        if (withAccountField) body["account_id"] = accountId?.let(::JsonPrimitive) ?: JsonNull
        return Check.Ok(JsonObject(body), paidValue)
    }

    companion object {
        fun of(l: BudgetLoan, money: Money, baseCurrency: String): LoanForm {
            val cur = l.currency ?: baseCurrency
            val it = l.interest
            return LoanForm(
                direction = if (l.borrowed) "borrowed" else "lent",
                borrower = l.borrower,
                amount = money.toInput(l.totalAmount, cur),
                installments = l.installmentCount.toString(),
                mode = it?.mode ?: "none",
                principal = it?.principal?.let { p -> money.toInput(p, cur) }.orEmpty(),
                fixedRate = it?.fixedRate?.let { v -> v.toString().removeSuffix(".0") }.orEmpty(),
                repayment = it?.initialRepaymentRate?.let { v -> v.toString().removeSuffix(".0") }.orEmpty(),
                fixedPeriod = it?.fixedPeriodMonths?.toString().orEmpty(),
                followupRate = it?.followupRate?.let { v -> v.toString().removeSuffix(".0") }.orEmpty(),
                start = l.startMonth,
                dueDay = l.dueDay?.toString().orEmpty(),
                paid = "",
                title = l.title,
                accountId = l.accountId,
                currency = cur,
                exchangeRate = if (cur != baseCurrency) l.exchangeRate.toString() else "",
                notes = l.notes.orEmpty(),
            )
        }
    }
}
