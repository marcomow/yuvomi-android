package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.feature.subscriptions.BudgetMoney
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pure rules of the Split tab (`public/pages/split-expenses.js`, `server/services/split-expenses.js`),
 * unit tested without Android (docs/spec/splitexpenses.md).
 */
object SplitRules {
    const val EQUAL = "equal"
    const val EXACT = "exact"
    const val PERCENTAGE = "percentage"
    const val SHARES = "shares"
    val METHODS = listOf(EQUAL, PERCENTAGE, EXACT, SHARES)

    const val EXPENSE_PAGE = 30
    const val ACTIVITY_PAGE = 12

    fun methodKey(method: String): String = when (method) {
        PERCENTAGE -> "splitExpenses.splitPercentage"
        EXACT -> "splitExpenses.splitExact"
        SHARES -> "splitExpenses.splitShares"
        else -> "splitExpenses.splitEqual"
    }

    fun hintKey(method: String): String = when (method) {
        PERCENTAGE -> "splitExpenses.splitHint.percentage"
        EXACT -> "splitExpenses.splitHint.exact"
        SHARES -> "splitExpenses.splitHint.shares"
        else -> "splitExpenses.splitHint.equal"
    }

    val GROUP_TYPE_KEYS = mapOf(
        "household" to "splitExpenses.groupType.household",
        "couple" to "splitExpenses.groupType.couple",
        "travel" to "splitExpenses.groupType.travel",
        "event" to "splitExpenses.groupType.event",
        "shopping" to "splitExpenses.groupType.shopping",
        "general" to "splitExpenses.groupType.general",
    )

    val FREQUENCY_KEYS = mapOf("weekly" to "budget.intervalWeekly", "monthly" to "budget.intervalMonthly", "yearly" to "budget.intervalYearly")

    /** `BUDGET_CATEGORY_LABEL_KEYS` (utils/category-labels.js), the expense side. */
    private val CATEGORY_KEYS = mapOf(
        "housing" to "budget.categoryHousing", "food" to "budget.categoryFood", "insurance" to "budget.categoryInsurance",
        "utilities" to "budget.categoryUtilities", "health" to "budget.categoryHealth", "family" to "budget.categoryFamily",
        "home" to "budget.categoryHome", "clothing" to "budget.categoryClothing", "transport" to "budget.catTransport",
        "personal_health" to "budget.catPersonalHealth", "leisure" to "budget.catLeisure",
        "shopping_clothing" to "budget.catShoppingClothing", "education" to "budget.catEducation",
        "financial_other" to "budget.catFinancialOther", "subscriptions" to "budget.catSubscriptions",
    )

    /** Minor units the server books in (`CURRENCY_MINOR_UNITS`): three for BHD and the like, none for JPY, two otherwise. */
    fun minorUnit(currency: String): Int = when (currency.uppercase()) {
        "BHD", "IQD", "JOD", "KWD", "OMR", "TND" -> 3
        "CLP", "JPY", "KRW", "VND" -> 0
        else -> 2
    }

    // ── Labels ──

    /** `groupTypeLabel()`: the type, unless it only repeats the group's name. */
    fun groupTypeLabel(strings: Strings, g: ExpenseGroup): String {
        val label = strings.t(GROUP_TYPE_KEYS[g.type] ?: "splitExpenses.groupType.general")
        return if (g.name.trim().equals(label.trim(), ignoreCase = true)) "" else label
    }

    /** `categoryLabel(key)`: "No category", the translated default, else the stored name. */
    fun categoryLabel(strings: Strings, key: String?, categories: List<BudgetCategory>): String {
        if (key.isNullOrEmpty() || key == "none") return strings.t("splitExpenses.noCategory")
        CATEGORY_KEYS[key]?.let { return strings.t(it) }
        return categories.firstOrNull { it.key == key }?.name?.takeIf { it.isNotEmpty() } ?: key
    }

    /** `groupCategories()`: the categories the group's expenses use, largest sum first; `none` for those without. */
    fun groupCategories(b: Balances): List<String> = b.budgetCategoryTotals.map { it.budgetCategory ?: "none" }.distinct()

    /** `debtSentence()`: from the reader's side when they are in it. */
    fun debtSentence(strings: Strings, d: Debt, me: Int?): String = when (me) {
        d.fromUserId -> strings.t("splitExpenses.debtYouOwe", "name" to d.toName.orEmpty())
        d.toUserId -> strings.t("splitExpenses.debtOwesYou", "name" to d.fromName.orEmpty())
        else -> strings.t("splitExpenses.debtOwes", "from" to d.fromName.orEmpty(), "to" to d.toName.orEmpty())
    }

    data class OwnBalance(val owed: List<MoneyRow>, val owing: List<MoneyRow>)

    /** `ownGroupBalance()`: the member's net in the chosen group, per currency, positive owed, negative owing. */
    fun ownGroupBalance(rows: List<BalanceRow>, me: Int?): OwnBalance {
        val own = rows.filter { me != null && it.userId == me }
        fun part(positive: Boolean) = own.filter { if (positive) it.netMinor > 0 else it.netMinor < 0 }
            .map { MoneyRow(it.currency, it.net.removePrefix("-")) }
        return OwnBalance(part(true), part(false))
    }

    fun allGroupsBalance(d: SplitDashboard?): OwnBalance =
        OwnBalance(d?.totalOwed.orEmpty().map { it.copy(amount = it.amount.removePrefix("-")) }, d?.totalOwing.orEmpty().map { it.copy(amount = it.amount.removePrefix("-")) })

    /** `foldedDebt()`: the group's only debt, when it is the reader's, stands as the lead figure. */
    fun foldedDebt(debts: List<Debt>, me: Int?): Debt? {
        if (debts.size != 1) return null
        val d = debts[0]
        return if (d.fromUserId == me || d.toUserId == me) d else null
    }

    fun totalsText(rows: List<MoneyRow>, defaultCurrency: String, locale: String): String =
        if (rows.isEmpty()) BudgetMoney.format(0.0, defaultCurrency, locale)
        else rows.joinToString(" · ") { BudgetMoney.format(it.amount, it.currency, locale) }

    // ── Split values ──

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** `deriveSplitValues()`: the editor's per-person values back from a booked expense. */
    fun deriveSplitValues(e: Expense, locale: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        when (e.splitMethod) {
            EXACT -> e.splits.forEach { out[it.userId] = BudgetMoney.toInput(it.amount, it.currency ?: e.currency, locale) }
            PERCENTAGE -> {
                val total = e.splits.sumOf { abs(it.amountMinor) }.takeIf { it > 0 } ?: 1L
                var acc = 0.0
                e.splits.forEachIndexed { i, s ->
                    if (i == e.splits.lastIndex) out[s.userId] = BudgetMoney.plainNumber(round2(100 - acc), locale)
                    else {
                        val pct = round2(abs(s.amountMinor).toDouble() / total * 100)
                        acc += pct
                        out[s.userId] = BudgetMoney.plainNumber(pct, locale)
                    }
                }
            }
            SHARES -> {
                val amounts = e.splits.map { abs(it.amountMinor) }
                val divisor = amounts.fold(0L) { a, b -> gcd(a, b) }.takeIf { it > 0 } ?: 1L
                e.splits.forEachIndexed { i, s -> out[s.userId] = maxOf(1L, (amounts[i].toDouble() / divisor).roundToInt().toLong()).toString() }
            }
        }
        return out
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    /** `parseSplitConfig()` + `defaultSplitValues()`: the group's preset per member (percentage or shares). */
    fun defaultSplitValues(g: ExpenseGroup?): Map<Int, String> {
        val method = g?.defaultSplitMethod
        if (method != PERCENTAGE && method != SHARES) return emptyMap()
        val raw = g.defaultSplitConfig ?: return emptyMap()
        val array = runCatching { io.github.marcomow.yuvomi.core.api.ApiJson.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyMap()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val uid = (o["user_id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val v = (o[if (method == SHARES) "shares" else "percentage"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            uid to v
        }.toMap()
    }

    /** `recurringSplitValues()`: a series keeps its typed input, set in the region's notation. */
    fun recurringSplitValues(r: RecurringExpense, locale: String): Map<Int, String> = r.splits.mapNotNull { s ->
        when (r.splitMethod) {
            EXACT -> s.amount?.let { s.userId to BudgetMoney.toInput(it, r.currency, locale) }
            PERCENTAGE -> s.percentage?.let { p -> s.userId to (p.toDoubleOrNull()?.let { BudgetMoney.plainNumber(it, locale) } ?: p) }
            SHARES -> s.shares?.let { s.userId to it.toString() }
            else -> null
        }
    }.toMap()

    data class SplitCheck(val valid: Boolean, val hint: String, val amountProblem: String?)

    private fun numberValue(text: String?, locale: String): Double? =
        BudgetMoney.toDecimal(text, locale)?.takeIf { Regex("^-?\\d+(\\.\\d+)?$").matches(it) }?.toDouble()

    /** `validateSplitForm()`: who takes part, and whether the method's values add up. */
    fun validate(strings: Strings, method: String, amount: String, currency: String, participants: Set<Int>, values: Map<Int, String>): SplitCheck {
        val locale = strings.locale
        val problem = BudgetMoney.problem(amount, currency, locale, required = true).takeIf { it != BudgetMoney.PRECISION }
        val total = numberValue(amount, locale)
        var valid = participants.isNotEmpty() && total != null && total > 0 && problem == null
        var hint = strings.t(hintKey(method))
        if (valid && method == PERCENTAGE) {
            val sum = participants.sumOf { numberValue(values[it], locale) ?: 0.0 }
            valid = abs(sum - 100) < 0.01
            hint += " " + strings.t("splitExpenses.splitCurrentTotal", "total" to BudgetMoney.percent(sum / 100, locale, 2))
        } else if (valid && method == EXACT) {
            val sum = participants.sumOf { numberValue(values[it], locale) ?: 0.0 }
            valid = abs(sum - total!!) < 0.01
            hint += " " + strings.t("splitExpenses.splitCurrentTotal", "total" to BudgetMoney.format(sum, currency, locale))
        } else if (valid && method == SHARES) {
            valid = participants.all { p -> numberValue(values[p], locale)?.let { it > 0 && it == Math.floor(it) } == true }
        }
        return SplitCheck(valid, hint, problem)
    }

    /** `collectSplitPayload()`: participants, and per participant the method's value in the server's notation. */
    fun splitPayload(method: String, participants: List<Int>, values: Map<Int, String>, locale: String): Pair<JsonArray, JsonArray> {
        val ids = JsonArray(participants.map { JsonPrimitive(it) })
        if (method == EQUAL) return ids to JsonArray(emptyList())
        val splits = participants.map { uid ->
            val v = BudgetMoney.toDecimal(values[uid], locale).orEmpty()
            buildJsonObject {
                put("user_id", uid)
                when (method) {
                    PERCENTAGE -> put("percentage", v)
                    EXACT -> put("amount", v)
                    else -> put("shares", v.toDoubleOrNull()?.toInt() ?: 0)
                }
            }
        }
        return ids to JsonArray(splits)
    }

    /** `rejectSplitAmounts()` for "exact": every share of a ticked member must be a positive amount in the grid. */
    fun exactShareProblem(method: String, participants: Set<Int>, values: Map<Int, String>, currency: String, locale: String): String? {
        if (method != EXACT) return null
        return participants.firstNotNullOfOrNull { BudgetMoney.problem(values[it], currency, locale, required = true) }
    }

    // ── Group defaults (`renderGroupDefaults`, `updateGroupDefaults`, `collectGroupDefaults`) ──

    /** Empty values mean no preset; a filled percentage set must make 100, shares must be whole and positive. */
    fun defaultsValid(method: String, values: Map<Int, String>, locale: String): Boolean {
        val filled = values.filterValues { it.isNotBlank() }
        if (filled.isEmpty()) return true
        return when (method) {
            PERCENTAGE -> abs(values.values.sumOf { numberValue(it, locale) ?: 0.0 } - 100) < 0.01
            SHARES -> filled.values.all { v -> numberValue(v, locale)?.let { it > 0 && it == Math.floor(it) } == true }
            else -> true
        }
    }

    fun defaultsConfig(method: String, values: Map<Int, String>, locale: String): JsonArray {
        if (method != PERCENTAGE && method != SHARES) return JsonArray(emptyList())
        return JsonArray(values.mapNotNull { (uid, raw) ->
            val v = BudgetMoney.toDecimal(raw, locale)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            buildJsonObject {
                put("user_id", uid)
                if (method == SHARES) put("shares", v.toDoubleOrNull()?.toInt() ?: 0) else put("percentage", v)
            }
        })
    }

    // ── Recurring ──

    /** `addInterval()` (utils/interval-date.js): monthly and yearly clamp to the month and lift back to the anchor day. */
    fun addInterval(date: LocalDate, frequency: String, anchorDay: Int?): LocalDate {
        fun lift(d: LocalDate): LocalDate = if (anchorDay == null || anchorDay !in 1..31) d else d.withDayOfMonth(minOf(anchorDay, d.lengthOfMonth()))
        return when (frequency) {
            "monthly" -> lift(date.plusMonths(1))
            "yearly" -> lift(date.plusYears(1))
            else -> date.plusWeeks(1)
        }
    }

    /** `nextRunNotBefore()`: the first run not before [today], and how many were passed over. */
    fun nextRunNotBefore(date: LocalDate, frequency: String, today: LocalDate, anchorDay: Int?): Pair<LocalDate, Int> {
        var d = date
        var skipped = 0
        while (d < today && skipped < 100_000) {
            val next = addInterval(d, frequency, anchorDay)
            if (next <= d) break
            d = next
            skipped++
        }
        return d to skipped
    }

    /** `changedRecurringFields()`: an edit sends only what changed against what the dialog showed. */
    fun changedRecurringFields(shown: JsonObject, now: JsonObject, storedParticipants: List<Int>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (key in listOf("title", "payer_id", "frequency", "next_run_date", "budget_category", "description")) {
            if (shown[key] != now[key]) out[key] = now[key] ?: JsonNull
        }
        if (shown["amount"] != now["amount"] || shown["currency"] != now["currency"]) {
            out["amount"] = now["amount"] ?: JsonNull
            out["currency"] = now["currency"] ?: JsonNull
        }
        val nowIds = (now["participants"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty().sorted()
        if (shown["split_method"] != now["split_method"] || shown["splits"] != now["splits"] || storedParticipants.sorted() != nowIds) {
            out["split_method"] = now["split_method"] ?: JsonNull
            out["participants"] = now["participants"] ?: JsonNull
            out["splits"] = now["splits"] ?: JsonNull
        }
        return JsonObject(out)
    }
}

/**
 * Error sentences of the tab. The web page leaves most failures to the global handler
 * (`unhandledErrorSentence`): a known reason's sentence, offline, permission, else a general
 * one; a few cases the server answers without a reason get the app's own sentence here.
 */
object SplitErrors {
    fun key(e: Throwable, fallback: String = "common.unexpectedError"): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 && e.reason == "module_read_only" -> "settings.permReadOnlyBanner"
            e.status == 403 && e.reason == "module_access_denied" -> "common.errorModuleNoAccess"
            e.status == 403 && e.reason == "csrf_invalid" -> "common.errorFormExpired"
            e.status == 403 -> "common.errorNoPermission"
            e.status == 429 -> "common.errorRateLimited"
            e.reason == "group_archived" -> "android.splitexpenses.groupArchived"
            e.reason == "email_in_use" -> "android.splitexpenses.emailInUse"
            e.reason == "next_run_not_after_last_booking" -> "splitExpenses.recurring.dateBooked"
            e.status == 409 -> "android.splitexpenses.conflict"
            e.status == 404 && e.reason == null -> "common.errorNotFound"
            e.status >= 500 -> "common.errorServer"
            else -> fallback
        }
        else -> fallback
    }
}
