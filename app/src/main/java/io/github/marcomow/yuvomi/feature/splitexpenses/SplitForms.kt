package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.subscriptions.BudgetMoney
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** What the budget hands over (`prefillSplitExpense()`, #1057): title, amount, date, currency, participants. */
data class SplitPrefill(val title: String = "", val amount: String = "", val date: String? = null, val currency: String? = null, val participantIds: List<Int> = emptyList())

/**
 * The fields an expense and a recurring expense share (`openExpenseModal()`, `openRecurringModal()`),
 * as text where the web reads text.
 */
data class ExpenseForm(
    val amount: String = "",
    val currency: String = "EUR",
    val title: String = "",
    val date: String = "",
    val payerId: Int? = null,
    val method: String = SplitRules.EQUAL,
    val budgetCategory: String? = null,
    val participants: Set<Int> = emptySet(),
    val values: Map<Int, String> = emptyMap(),
    val description: String = "",
    val frequency: String = "monthly",
) {
    companion object {
        /** A new expense: the group's currency and default split, everyone in, the reader pays. */
        fun blank(group: ExpenseGroup, me: Int?, today: LocalDate, people: List<SplitPerson>, prefill: SplitPrefill? = null, locale: String = "en"): ExpenseForm {
            val ids = people.map { it.uid }
            val pre = prefill?.participantIds?.filter { it in ids }.orEmpty()
            val currency = prefill?.currency ?: group.defaultCurrency
            return ExpenseForm(
                amount = prefill?.amount?.let { BudgetMoney.toInput(it, currency, locale) }.orEmpty(),
                currency = currency,
                title = prefill?.title.orEmpty(),
                date = prefill?.date ?: today.toString(),
                payerId = me?.takeIf { it in ids } ?: ids.firstOrNull(),
                method = group.defaultSplitMethod,
                participants = (pre.ifEmpty { ids }).toSet(),
                values = SplitRules.defaultSplitValues(group).mapKeys { it.key },
            )
        }

        fun of(e: Expense, locale: String) = ExpenseForm(
            amount = BudgetMoney.toInput(e.amount, e.currency, locale),
            currency = e.currency,
            title = e.title,
            date = e.expenseDate,
            payerId = e.payerId,
            method = e.splitMethod,
            budgetCategory = e.budgetCategory,
            participants = e.splits.map { it.userId }.toSet(),
            values = SplitRules.deriveSplitValues(e, locale),
            description = e.description.orEmpty(),
        )

        fun of(r: RecurringExpense, locale: String) = ExpenseForm(
            amount = BudgetMoney.toInput(r.amount, r.currency, locale),
            currency = r.currency,
            title = r.title,
            date = r.nextRunDate,
            payerId = r.payerId,
            method = r.splitMethod,
            budgetCategory = r.budgetCategory,
            participants = r.participants.toSet(),
            values = SplitRules.recurringSplitValues(r, locale),
            description = r.description.orEmpty(),
            frequency = r.frequency,
        )

        fun blankRecurring(group: ExpenseGroup, me: Int?, today: LocalDate, people: List<SplitPerson>) =
            blank(group, me, today, people).copy(frequency = "monthly")
    }
}

object SplitFormRules {
    const val TITLE = "title"
    const val AMOUNT = "amount"
    const val SPLIT = "split"

    /** The checks before sending: title, amount, participants and the split (`reportSplitProblem`, `rejectSplitAmounts`). */
    fun errors(f: ExpenseForm, strings: Strings, original: Pair<String, String>? = null): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (f.title.isBlank()) out[TITLE] = strings.t("common.required")
        val origAmount = original?.first?.toDoubleOrNull()
        BudgetMoney.problem(f.amount, f.currency, strings.locale, required = true, original = origAmount, originalCurrency = original?.second)
            ?.let { out[AMOUNT] = BudgetMoney.problemText(strings, it, f.currency) }
        val check = SplitRules.validate(strings, f.method, f.amount, f.currency, f.participants, f.values)
        when {
            f.participants.isEmpty() -> out[SPLIT] = strings.t("splitExpenses.participantsRequired")
            !check.valid && AMOUNT !in out -> out[SPLIT] = check.hint
        }
        if (SPLIT !in out && AMOUNT !in out) {
            SplitRules.exactShareProblem(f.method, f.participants, f.values, f.currency, strings.locale)?.let { out[SPLIT] = BudgetMoney.problemText(strings, it, f.currency) }
        }
        return out
    }

    private fun common(f: ExpenseForm, people: List<SplitPerson>, locale: String): Map<String, kotlinx.serialization.json.JsonElement> {
        val order = people.map { it.uid }.filter { it in f.participants } + f.participants.filter { p -> people.none { it.uid == p } }
        val (participants, splits) = SplitRules.splitPayload(f.method, order, f.values, locale)
        return linkedMapOf(
            "title" to JsonPrimitive(f.title.trim()),
            "amount" to JsonPrimitive(BudgetMoney.toDecimal(f.amount, locale).orEmpty()),
            "currency" to JsonPrimitive(f.currency),
            "payer_id" to (f.payerId?.let { JsonPrimitive(it) } ?: JsonNull),
            "split_method" to JsonPrimitive(f.method),
            "budget_category" to (f.budgetCategory?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull),
            "description" to JsonPrimitive(f.description),
            "participants" to participants,
            "splits" to splits,
        )
    }

    /** The body of `POST /groups/:id/expenses` and `PUT /expenses/:id`. Receipts stay as they are (no `attachment_document_ids`). */
    fun expenseBody(f: ExpenseForm, people: List<SplitPerson>, locale: String): JsonObject =
        JsonObject(common(f, people, locale) + ("expense_date" to JsonPrimitive(f.date)))

    /** `readRecurringForm()`. */
    fun recurringBody(f: ExpenseForm, people: List<SplitPerson>, locale: String): JsonObject =
        JsonObject(common(f, people, locale) + mapOf("frequency" to JsonPrimitive(f.frequency), "next_run_date" to JsonPrimitive(f.date)))

    /** `recurringPastDateChoice()`: the number of runs a past start would book, and the next run not before today. */
    fun pastDate(f: ExpenseForm, today: LocalDate): Pair<LocalDate, Int>? {
        val d = runCatching { LocalDate.parse(f.date) }.getOrNull() ?: return null
        if (d >= today) return null
        val next = SplitRules.nextRunNotBefore(d, f.frequency, today, d.dayOfMonth)
        return next.takeIf { it.second > 0 }
    }

    /** The group form's body (`openGroupModal()` + `collectGroupDefaults()`). */
    fun groupBody(name: String, description: String, type: String, currency: String, defaults: Pair<String, Map<Int, String>>?, locale: String): JsonObject = buildJsonObject {
        put("name", name.trim())
        put("description", description)
        put("type", type)
        put("default_currency", currency)
        if (defaults != null) {
            put("default_split_method", defaults.first)
            put("default_split_config", SplitRules.defaultsConfig(defaults.first, defaults.second, locale))
        }
    }

    /** The settlement form's body (`openSettlementModal()`). */
    fun settlementBody(payer: Int, payee: Int, amount: String, currency: String, notes: String, locale: String): JsonObject = buildJsonObject {
        put("payer_id", payer)
        put("payee_id", payee)
        put("amount", BudgetMoney.toDecimal(amount, locale).orEmpty())
        put("currency", currency)
        put("notes", notes)
    }

    /** The settlement the dialog proposes: the reader's own debt first, else the first one. */
    fun proposedDebt(debts: List<Debt>, me: Int?): Debt? = debts.firstOrNull { it.fromUserId == me } ?: debts.firstOrNull()

    /** `openGuestModal()`: display name and a temporary password of at least eight characters. */
    fun guestErrors(name: String, password: String, birthDate: String, strings: Strings): Map<String, String> = buildMap {
        if (name.isBlank()) put("name", strings.t("common.required"))
        if (password.length < 8) put("password", strings.t("android.splitexpenses.passwordShort"))
        if (birthDate.isNotBlank() && runCatching { LocalDate.parse(birthDate) }.isFailure) put("birth", strings.t("android.splitexpenses.dateFormat"))
    }

    fun guestBody(name: String, username: String, password: String, phone: String, email: String, birthDate: String): JsonObject = buildJsonObject {
        put("display_name", name.trim())
        if (username.isNotBlank()) put("username", username.trim())
        put("password", password)
        put("phone", phone.trim())
        put("email", email.trim())
        put("birth_date", birthDate.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
    }
}
