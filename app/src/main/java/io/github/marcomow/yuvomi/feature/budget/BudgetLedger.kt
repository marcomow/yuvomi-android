package io.github.marcomow.yuvomi.feature.budget

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The pure parts of `public/pages/budget.js` the overview needs: which rows the list shows, in
 * which order and sections, the category blocks of the chart, and the local summary arithmetic
 * of a delete (`summaryWith`). Everything here is plain Kotlin and unit tested.
 */
object BudgetLedger {
    /** `SAVINGS_CHART_CATEGORY` (`public/utils/budget-chart-category.js`). */
    const val SAVINGS_CHART_CATEGORY = "__saving__"

    /** `BUDGET_MASKED_CATEGORY`: the bucket of other people's `shared_amount` rows (#659). */
    const val MASKED_CATEGORY = "__private__"

    /** `BUDGET_SAVINGS_KEY` of the plan (`server/routes/budget/plans.js`). */
    const val SAVINGS_PLAN_KEY = "__savings__"

    const val PLANNED_PREVIEW_ROWS = 3
    const val CHART_LEAD = 3
    const val LOAN_PAYMENTS_PREVIEW_ROWS = 6
    const val MAX_INTERVAL_COUNT = 99
    val VISIBILITY_LEVELS = listOf("private", "shared_amount", "shared")
    val ACCOUNT_TYPES = listOf("checking", "savings", "cash", "credit", "investment", "other")

    /** `budgetChartCategory`: savings show as their own bar. */
    fun chartCategory(entry: BudgetEntry): String =
        if (entry.category == "financial_other" && entry.subcategory == "saving") SAVINGS_CHART_CATEGORY else entry.category

    data class Filters(
        val category: String? = null,
        val subcategory: Pair<String, String>? = null,
        val responsible: Int? = null,
        val account: Int? = null,
    ) {
        val any: Boolean get() = category != null || subcategory != null || responsible != null || account != null
    }

    /** `visibleEntries`: the account filter already came from the server (`account_id`). */
    fun visible(entries: List<BudgetEntry>, filters: Filters, ignoreSubcategory: Boolean = false): List<BudgetEntry> =
        entries.filter { e ->
            val responsible = filters.responsible == null || e.responsibleUsers.any { it.id == filters.responsible }
            val category = filters.category == null || chartCategory(e) == filters.category
            val sub = filters.subcategory
            val subOk = ignoreSubcategory || sub == null ||
                (e.category == sub.first && !e.detailsHidden && (e.subcategory ?: "") == sub.second)
            responsible && category && subOk
        }

    /** `sortLedger`: by date keeps the server's order; by amount puts expenses first, largest first. */
    fun sort(entries: List<BudgetEntry>, sort: String): List<BudgetEntry> {
        if (sort != "amount") return entries
        return entries.withIndex().sortedWith(
            compareBy<IndexedValue<BudgetEntry>> { if (it.value.amount < 0) 0 else 1 }
                .thenByDescending { abs(it.value.amount) }
                .thenBy { it.index },
        ).map { it.value }
    }

    data class Split(val planned: List<BudgetEntry>, val booked: List<BudgetEntry>)

    /** `splitLedger`: expected or later than today is planned (ascending), the rest booked. */
    fun split(rows: List<BudgetEntry>, today: LocalDate): Split {
        val key = today.toString()
        val (planned, booked) = rows.partition { it.isPending || it.date > key }
        return Split(planned.sortedBy { it.date }, booked)
    }

    /** `isUpcomingEntry`: booked, but dated after today. */
    fun upcoming(entry: BudgetEntry, today: LocalDate): Boolean = !entry.isPending && entry.date > today.toString()

    /** `isForecastMonth`: the whole month lies after today. */
    fun forecast(month: String, today: LocalDate): Boolean = month > today.toString().take(7)

    data class Group(val person: BudgetPerson?, val entries: List<BudgetEntry>)

    /** `groupEntriesByResponsible`: one entry can stand in two groups; "nobody" goes last. */
    fun groupByResponsible(entries: List<BudgetEntry>, members: List<BudgetMember>): List<Group> {
        val groups = LinkedHashMap<Int, Pair<BudgetPerson, MutableList<BudgetEntry>>>()
        val unassigned = mutableListOf<BudgetEntry>()
        for (e in entries) {
            if (e.responsibleUsers.isEmpty()) {
                unassigned += e
                continue
            }
            for (p in e.responsibleUsers) groups.getOrPut(p.id) { p to mutableListOf() }.second += e
        }
        val ranks = sortMembers(members).withIndex().associate { it.value.id to it.index }
        val ordered = groups.values.sortedWith(
            compareBy<Pair<BudgetPerson, MutableList<BudgetEntry>>> { ranks[it.first.id] ?: Int.MAX_VALUE }
                .thenComparator { a, b -> compareNames(a.first.displayName, b.first.displayName) }
                .thenBy { it.first.id },
        ).map { Group(it.first, it.second) }
        return if (unassigned.isEmpty()) ordered else ordered + Group(null, unassigned)
    }

    /** `compareMembers` (`public/utils/member-order.js`). */
    fun sortMembers(members: List<BudgetMember>): List<BudgetMember> = members.sortedWith { a, b ->
        val pa = a.sortOrder
        val pb = b.sortOrder
        if (pa != null || pb != null) {
            if (pa == null) return@sortedWith 1
            if (pb == null) return@sortedWith -1
            if (pa != pb) return@sortedWith pa - pb
        }
        compareNames(a.displayName, b.displayName).takeIf { it != 0 } ?: (a.id - b.id)
    }

    /** `compareNamesNoCase`: ASCII letters folded, everything else by code point. */
    fun compareNames(a: String?, b: String?): Int {
        fun fold(c: Int) = if (c in 65..90) c + 32 else c
        val l = (a ?: "").codePoints().map(::fold).toArray()
        val r = (b ?: "").codePoints().map(::fold).toArray()
        for (i in 0 until minOf(l.size, r.size)) if (l[i] != r[i]) return l[i] - r[i]
        return l.size - r.size
    }

    data class BarRow(val category: String, val amount: Double)

    data class Blocks(val expenses: List<BarRow>, val income: List<BarRow>, val transfers: List<BarRow>) {
        fun of(kind: String) = when (kind) {
            "expenses" -> expenses
            "income" -> income
            else -> transfers
        }

        /** `chartLeadKind`. */
        val lead: String get() = if (expenses.isNotEmpty()) "expenses" else if (income.isNotEmpty()) "income" else "transfers"

        /** `chartHasMore`: does the short version hide anything? */
        val hasMore: Boolean
            get() = of(lead).size > CHART_LEAD || KINDS.any { it != lead && of(it).isNotEmpty() }
    }

    val KINDS = listOf("expenses", "income", "transfers")

    /** `categoryBlocks`: two scales instead of one, each block by its own maximum. */
    fun blocks(byCategory: List<CategoryTotal>): Blocks {
        fun part(c: CategoryTotal, kind: String): Double = when (kind) {
            "transfers" -> c.transfers ?: 0.0
            "expenses" -> c.expenses ?: minOf(c.total, 0.0)
            else -> c.income ?: maxOf(c.total, 0.0)
        }
        fun block(kind: String) = byCategory.map { BarRow(it.category, part(it, kind)) }
            .filter { it.amount != 0.0 }
            .sortedByDescending { abs(it.amount) }
        return Blocks(block("expenses"), block("income"), block("transfers"))
    }

    fun total(rows: List<BarRow>): Double = rows.sumOf { it.amount }

    data class SubRow(val key: String, val amount: Double)

    /**
     * `subcategoryRows`: the filtered category split by subcategory, only when it has at least
     * two; without subcategory last, the rest in the catalogue's order.
     */
    fun subcategoryRows(category: String, entries: List<BudgetEntry>, known: List<String>): List<SubRow> {
        val groups = LinkedHashMap<String, Double>()
        for (e in entries) {
            if (e.category != category) continue
            val key = if (e.detailsHidden) "" else e.subcategory ?: ""
            groups[key] = (groups[key] ?: 0.0) + if (e.isPending) 0.0 else e.amount
        }
        if (groups.size < 2) return emptyList()
        return groups.map { SubRow(it.key, it.value) }.sortedWith { a, b ->
            when {
                a.key.isEmpty() -> 1
                b.key.isEmpty() -> -1
                else -> known.indexOf(a.key) - known.indexOf(b.key)
            }
        }
    }

    /** The sum the status line of a filtered list names (`ledgerStatusText`): expected rows count 0. */
    fun bookedSum(entries: List<BudgetEntry>): Double = entries.sumOf { if (it.isPending) 0.0 else it.amount }

    /** `summaryWith`: the summary with [entries] added (`sign` 1) or taken out (`sign` -1). */
    fun summaryWith(summary: BudgetSummary, entries: List<BudgetEntry>, sign: Int): BudgetSummary {
        fun round(n: Double) = Math.round(n * 1e6) / 1e6
        var s = summary
        val rows = summary.byCategory.toMutableList()
        for (e in entries) {
            if (e.isTransfer) continue
            if (summary.month.isNotEmpty() && e.date.take(7) != summary.month) continue
            val income = if (e.amount > 0) e.amount else 0.0
            val expenses = if (e.amount < 0) e.amount else 0.0
            if (e.isPending) {
                val p = s.pending ?: continue
                s = s.copy(
                    pending = p.copy(
                        count = maxOf(0, p.count + sign),
                        income = round(p.income + sign * income),
                        expenses = round(p.expenses + sign * expenses),
                    ),
                )
                continue
            }
            s = s.copy(
                income = round(s.income + sign * income),
                expenses = round(s.expenses + sign * expenses),
                balance = round(s.balance + sign * e.amount),
            )
            var i = rows.indexOfFirst { it.category == e.category }
            if (i < 0 && sign > 0) {
                rows += CategoryTotal(e.category, 0.0, 0.0, 0.0)
                i = rows.lastIndex
            }
            if (i < 0) continue
            val row = rows[i]
            rows[i] = row.copy(
                income = round((row.income ?: 0.0) + sign * income),
                expenses = round((row.expenses ?: 0.0) + sign * expenses),
                total = round(row.total + sign * e.amount),
            )
        }
        return s.copy(byCategory = rows.filter { (it.income ?: 0.0) != 0.0 || (it.expenses ?: 0.0) != 0.0 || (it.transfers ?: 0.0) > 0 })
    }

    data class SubChoice(val value: String, val required: Boolean)

    /**
     * `subcategoryChoice`: with several subcategories the choice is required and starts empty,
     * with exactly one it is preselected, without any there is nothing to choose.
     */
    fun subcategoryChoice(subs: List<BudgetSubcategory>, selected: String?): SubChoice {
        val value = when {
            subs.any { it.key == selected } -> selected!!
            subs.size == 1 -> subs[0].key
            else -> ""
        }
        return SubChoice(value, subs.size > 1)
    }

    private val SERIES_RHYTHM_FIELDS = setOf(
        "is_recurring", "recurrence_interval", "recurrence_interval_count", "recurrence_virtual", "recurrence_confirm", "recurrence_rule",
    )

    /**
     * `changedSeriesBody`: only what the member changed goes to `PUT /budget/{id}/series`, so an
     * untouched field of an occurrence does not overwrite the series. The date never goes; the
     * rhythm only from the anchor ([keepRhythm]).
     */
    fun changedSeriesBody(body: JsonObject, entry: BudgetEntry, amount: Double, keepRhythm: Boolean): JsonObject {
        val before: Map<String, Any?> = mapOf(
            "title" to entry.title,
            "amount" to amount,
            "category" to entry.category,
            "subcategory" to (entry.subcategory ?: ""),
            "account_id" to entry.accountId,
            "visibility" to entry.visibility,
            "responsible_user_ids" to entry.responsibleUsers.map { it.id }.sorted(),
        )
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in body) {
            if (key == "date" || key == "attachment_document_ids") continue
            if (key in SERIES_RHYTHM_FIELDS) {
                if (keepRhythm) out[key] = value
                continue
            }
            if (key in before && same(value, before[key])) continue
            out[key] = value
        }
        return JsonObject(out)
    }

    private fun same(value: JsonElement, before: Any?): Boolean = when {
        value is JsonNull -> before == null
        value is JsonArray -> before is List<*> && value.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.sorted() == before
        before is Double -> (value as? JsonPrimitive)?.doubleOrNull == before
        before is Int -> (value as? JsonPrimitive)?.intOrNull == before
        before is String -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content == before
        else -> false
    }

    /** `occurrenceSeriesBody`: from an occurrence, never the rhythm. */
    fun occurrenceSeriesBody(body: JsonObject, entry: BudgetEntry): JsonObject = changedSeriesBody(body, entry, entry.amount, keepRhythm = false)

    /** `anchorSeriesBody`: from the first entry, with its rhythm, and a moved date as `start_date`. */
    fun anchorSeriesBody(body: JsonObject, entry: BudgetEntry): JsonObject {
        val shown = if (entry.recurrenceVirtual && entry.recurrenceFullAmount != null) entry.recurrenceFullAmount else entry.amount
        val out = changedSeriesBody(body, entry, shown, keepRhythm = true).toMutableMap()
        val date = (body["date"] as? JsonPrimitive)?.content
        if (date != null && date != entry.date) out["start_date"] = JsonPrimitive(date)
        return JsonObject(out)
    }

    /** The amount the editor and the read view show: the period amount of a virtual series. */
    fun shownAmount(entry: BudgetEntry): Double =
        if (entry.recurrenceVirtual && entry.recurrenceFullAmount != null) {
            (if (entry.amount < 0 || entry.amount == 0.0) -1.0 else 1.0) * abs(entry.recurrenceFullAmount)
        } else {
            entry.amount
        }

    /** `newEntryDefaultDate` / `defaultDateInPeriod`: today when it lies in the period, else its first day. */
    fun defaultDate(from: LocalDate, to: LocalDate, today: LocalDate): LocalDate = if (today in from..to) today else from

    fun monthRange(month: String): Pair<LocalDate, LocalDate> {
        val ym = YearMonth.parse(month)
        return ym.atDay(1) to ym.atEndOfMonth()
    }

    fun addMonths(month: String, n: Int): String = YearMonth.parse(month).plusMonths(n.toLong()).toString()

    /** `stepAnchor` of the statistics. */
    fun stepAnchor(anchor: LocalDate, range: String, dir: Int): LocalDate = when (range) {
        "week" -> anchor.plusDays(7L * dir)
        "month" -> anchor.plusMonths(dir.toLong())
        else -> anchor.plusYears(dir.toLong())
    }

    /** `anchorForMonth`: today in the current month, else the first. */
    fun anchorForMonth(month: String, today: LocalDate): LocalDate =
        if (month == today.toString().take(7)) today else YearMonth.parse(month).atDay(1)

    // ── Accounts ──

    /** `accountBalanceClass`: credit cards are neutral unless over the limit. */
    fun accountTone(a: BudgetAccount): Money.Tone = if (a.type == "credit") {
        if (a.availableLimit != null && a.availableLimit < 0) Money.Tone.NEGATIVE else Money.Tone.NEUTRAL
    } else if (a.currentBalance >= 0) Money.Tone.POSITIVE else Money.Tone.NEGATIVE

    // ── Category colours (`public/utils/budget-category-colors.js`) ──

    const val SERIES_COLORS = 7

    /**
     * `categoryColorIndex`: the expense categories of the period get a series colour from their
     * catalogue position, colliding ones the next free; -1 when all seven are taken (`other`).
     */
    fun colorIndex(byCategory: List<CategoryTotal>, catalog: List<String>): Map<String, Int> {
        val position = catalog.withIndex().associate { it.value to it.index }.toMutableMap()
        val shown = byCategory.filter { (it.expenses ?: 0.0) < 0 }.sortedByDescending { abs(it.expenses ?: 0.0) }
        shown.map { it.category }.filter { it !in position }.distinct().sorted()
            .forEachIndexed { i, key -> position[key] = catalog.size + i }
        val taken = mutableSetOf<Int>()
        val out = LinkedHashMap<String, Int>()
        for (c in shown) {
            val preferred = (position[c.category] ?: 0) % SERIES_COLORS
            var index = -1
            for (step in 0 until SERIES_COLORS) {
                val candidate = (preferred + step) % SERIES_COLORS
                if (candidate !in taken) {
                    index = candidate
                    break
                }
            }
            if (index >= 0) taken += index
            out[c.category] = index
        }
        return out
    }

    /** `categoryPaint`: the period's colour, else the catalogue position's; null when unknown. */
    fun paint(index: Map<String, Int>, category: String, catalog: List<String>): Int? {
        index[category]?.let { return it }
        val p = catalog.indexOf(category)
        return if (p < 0) null else p % SERIES_COLORS
    }

    /** `TEXTURED_SERIES`: series 6 and 7 are hatched as a second channel. */
    fun textured(i: Int?): Boolean = i == 5 || i == 6

    // ── Loans (`public/utils/loan-order.js`) ──

    val LOAN_SORTS = listOf("start", "rate", "balance")
    val LOAN_GROUPS = listOf("borrowed", "lent", "paid")

    private fun open(l: BudgetLoan) = l.status != "paid" && !l.isSettled

    fun loanGroup(l: BudgetLoan): String = if (!open(l)) "paid" else if (l.borrowed) "borrowed" else "lent"

    private fun rate(l: BudgetLoan) = l.interest?.currentRate ?: 0.0

    private fun balance(l: BudgetLoan): Double {
        val b = l.remainingPrincipal ?: l.remainingAmount
        return b * (if (l.exchangeRate > 0) l.exchangeRate else 1.0)
    }

    /** `sortLoans`: by start keeps the server's order; otherwise borrowed, lent, paid, then the key. */
    fun sortLoans(loans: List<BudgetLoan>, sort: String): List<BudgetLoan> {
        if (sort !in listOf("rate", "balance")) return loans
        return loans.withIndex().sortedWith { a, b ->
            val ga = LOAN_GROUPS.indexOf(loanGroup(a.value))
            val gb = LOAN_GROUPS.indexOf(loanGroup(b.value))
            if (ga != gb) return@sortedWith ga - gb
            if (!open(a.value)) return@sortedWith a.index - b.index
            val c = if (sort == "rate") rate(b.value).compareTo(rate(a.value)) else balance(a.value).compareTo(balance(b.value))
            if (c != 0) c else a.index - b.index
        }.map { it.value }
    }

    data class LoanGroupRows(val id: String, val loans: List<BudgetLoan>)

    /** `groupLoans`: headings only when sorted by a key and both directions are present. */
    fun groupLoans(loans: List<BudgetLoan>, sort: String): List<LoanGroupRows>? {
        if (sort !in listOf("rate", "balance")) return null
        val sorted = sortLoans(loans, sort)
        val groups = LOAN_GROUPS.map { id -> LoanGroupRows(id, sorted.filter { loanGroup(it) == id }) }.filter { it.loans.isNotEmpty() }
        return if (groups.count { it.id != "paid" } > 1) groups else null
    }

    /** `loanPaymentsFor`: every payment of the shown loans, newest first. */
    fun loanPayments(loans: List<BudgetLoan>, serverOrder: List<BudgetLoan>): List<Pair<BudgetLoan, LoanPayment>> {
        val rank = serverOrder.withIndex().associate { it.value.id to it.index }
        return loans.flatMap { l -> l.payments.map { l to it } }.sortedWith(
            compareByDescending<Pair<BudgetLoan, LoanPayment>> { it.second.paidDate }
                .thenByDescending { it.second.installmentNumber }
                .thenBy { rank[it.first.id] ?: Int.MAX_VALUE },
        )
    }

    /** `loanRowTitle`: the partner, or the title when two loans share the partner. */
    fun loanRowTitle(loan: BudgetLoan, loans: List<BudgetLoan>): String {
        val partner = loan.borrower.trim()
        if (partner.isEmpty()) return loan.title
        val shared = loans.any { it.id != loan.id && it.borrower.trim().equals(partner, ignoreCase = true) }
        return if (shared) loan.title.ifEmpty { partner } else partner
    }

    /** The amount of the next installment (`markLoanPayment`): the rest with the last one. */
    fun nextInstallmentAmount(loan: BudgetLoan): Double =
        if (loan.nextInstallmentNumber == loan.installmentCount) loan.remainingAmount else minOf(loan.installmentAmount, loan.remainingAmount)

    /** How many months since the start (`wireLoanPaidInstallmentsField.suggest`). */
    fun suggestedPaidInstallments(startMonth: String, today: LocalDate, cap: Int?): Int? {
        val m = Regex("^(\\d{4})-(\\d{2})$").find(startMonth) ?: return null
        val months = maxOf(0, (today.year - m.groupValues[1].toInt()) * 12 + (today.monthValue - m.groupValues[2].toInt()))
        return if (cap == null) months else minOf(months, cap)
    }

    // ── Statistics (`public/pages/budget-stats.js`, `public/utils/chart.js`) ──

    private val CALM_FACTORS = listOf(1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

    /** `calmDomain(max, { integer: true })`: two calm steps above the largest value. */
    fun calmMax(max: Double): Double {
        val need = (maxOf(1.0, max) * 1.15) / 2
        val mag = 10.0.pow(floor(log10(need)))
        fun fix(v: Double): Double {
            val digits = maxOf(0, 2 - floor(log10(mag)).toInt())
            return Math.round(v * 10.0.pow(digits)) / 10.0.pow(digits)
        }
        fun whole(v: Double) = abs(v - Math.round(v)) < 1e-9
        val step = CALM_FACTORS.map { fix(it * mag) }.firstOrNull { it >= need - 1e-9 && whole(it) } ?: kotlin.math.ceil(need)
        return fix(step * 2)
    }

    /** `futureStartIndex`: the last bucket on or before today, or -1 when today is outside. */
    fun futureStartIndex(periods: List<String>, today: String): Int {
        if (periods.isEmpty() || today < periods.first() || today >= periods.last()) return -1
        var index = -1
        for ((i, p) in periods.withIndex()) if (p <= today) index = i else break
        return index
    }

    fun running(values: List<Double>): List<Double> {
        var acc = 0.0
        return values.map { acc += it; acc }
    }

    data class DonutSlice(val category: String?, val value: Double, val colorIndex: Int)

    /** `donutSlices`: seven segments at most, the rest as "other" (index -1). */
    fun donut(byCategory: List<CategoryTotal>, catalog: List<String>): List<DonutSlice> {
        val colors = colorIndex(byCategory, catalog)
        val exp = byCategory.filter { (it.expenses ?: 0.0) < 0 }
            .map { DonutSlice(it.category, abs(it.expenses ?: 0.0), colors[it.category] ?: -1) }
            .sortedByDescending { it.value }
        if (exp.size <= SERIES_COLORS) return exp
        val head = exp.take(SERIES_COLORS - 1)
        return head + DonutSlice(null, exp.drop(SERIES_COLORS - 1).sumOf { it.value }, -1)
    }

    data class CompareRow(val category: String, val amount: Double, val prev: Double)

    /** `renderCatBars`: this period against the previous one, by category, for one kind. */
    fun compareRows(now: List<CategoryTotal>, before: List<CategoryTotal>?, kind: String): List<CompareRow> {
        fun amounts(rows: List<CategoryTotal>?) = rows.orEmpty().mapNotNull { c ->
            val v = (if (kind == "expenses") c.expenses else c.income) ?: 0.0
            if (v != 0.0) c.category to v else null
        }.toMap()
        val a = amounts(now)
        val b = amounts(before)
        return (a.keys + b.keys).distinct().map { CompareRow(it, a[it] ?: 0.0, b[it] ?: 0.0) }
            .sortedWith(compareByDescending<CompareRow> { abs(it.amount) }.thenByDescending { abs(it.prev) })
    }

    /** `toneForRatio` of the plan. */
    fun planTone(ratio: Double, over: Boolean?): String = when {
        over == true -> "over"
        ratio > 0.85 && ratio < 1 -> "near"
        else -> "under"
    }

    fun percent(ratio: Double): Int = (ratio * 100).roundToInt()
}

/** The responsible picker's people (`withChosenPeople`, `responsiblePickerHtml`). */
object BudgetPeople {
    data class Person(val id: Int, val name: String, val color: String?, val picture: String?)

    /** The members, then anyone already chosen whom the list does not know (#1207). */
    fun pickable(members: List<BudgetMember>, chosen: List<BudgetPerson>): List<Person> {
        val list = members.map { Person(it.id, it.displayName, it.avatarColor, it.avatarData) }.toMutableList()
        val seen = list.map { it.id }.toMutableSet()
        for (p in chosen) {
            if (p.id < 1 || p.id in seen) continue
            seen += p.id
            list += Person(p.id, p.displayName.orEmpty(), p.color, null)
        }
        return list
    }
}
