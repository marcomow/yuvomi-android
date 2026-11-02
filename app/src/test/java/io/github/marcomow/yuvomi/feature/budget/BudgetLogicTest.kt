package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/** docs/spec/budget.md, the pure parts (BUDGET-A1 to A12). */
class BudgetLogicTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()) +
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n-android/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }
    private val en = Money(Locale.forLanguageTag("en"), "EUR")
    private val de = Money(Locale.forLanguageTag("de-DE"), "EUR")

    private fun sp(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun entry(
        id: Int,
        amount: Double,
        date: String = "2026-11-10",
        category: String = "food",
        sub: String? = null,
        pending: Boolean = false,
        people: List<BudgetPerson> = emptyList(),
    ) = BudgetEntry(id = id, title = "E$id", amount = amount, date = date, category = category, subcategory = sub, isPending = pending, responsibleUsers = people)

    // ── BUDGET-A1: money like money.js ──

    @Test fun `BUDGET-A1 amounts follow the number locale and the role decides the sign`() {
        assertEquals("€1,234.56", en.format(1234.56, "EUR"))
        assertEquals("1.234,56 €", sp(de.format(1234.56, "EUR")))
        assertEquals("+€12.00", en.signed(12.0, "EUR", Money.Role.FLOW))
        assertEquals("-€12.00", en.signed(-12.0, "EUR", Money.Role.FLOW))
        assertEquals("€0.00", en.signed(0.001, "EUR", Money.Role.FLOW))
        assertEquals("+12,00 €", sp(de.signed(12.0, "EUR", Money.Role.FLOW)))
        assertEquals("€12.00", en.signed(-12.0, "EUR", Money.Role.TOTAL))
        assertEquals("-€12.00", en.signed(-12.0, "EUR", Money.Role.BALANCE))
        assertEquals("¥1,235", en.format(1234.6, "JPY"))
        assertEquals(0, en.fractionDigits("JPY"))
        assertEquals("€1,235", en.axis(1234.6, "EUR"))
        // An unusable code falls back to the household's currency (`usableCurrency`).
        assertEquals("€5.00", en.format(5.0, "nope"))
    }

    @Test fun `BUDGET-A1 shares and tones`() {
        assertEquals("<1%", en.share(1.0, 1000.0))
        assertEquals("25%", en.share(-25.0, -100.0))
        assertEquals(Money.Tone.NEUTRAL, en.tone(-5.0, Money.Role.TOTAL))
        assertEquals(Money.Tone.NEGATIVE, en.tone(-5.0, Money.Role.BALANCE))
        assertEquals(Money.Tone.POSITIVE, en.tone(5.0, Money.Role.FLOW))
    }

    @Test fun `BUDGET-A2 amount input takes a decimal comma, refuses groups and extra decimals`() {
        assertEquals(12.5, de.parse("12,50")!!, 0.0)
        assertEquals(12.5, en.parse("12,50")!!, 0.0)
        assertEquals(-3.0, en.parse("−3")!!, 0.0)
        assertNull(en.parse("  "))
        assertTrue(en.parse("12a")!!.isNaN())
        assertEquals(Money.Problem.GROUPED, de.problem("1.200", "EUR"))
        assertEquals(Money.Problem.GROUPED, en.problem("1,200", "EUR"))
        assertEquals(Money.Problem.NOT_POSITIVE, en.problem("", "EUR"))
        assertEquals(Money.Problem.NOT_POSITIVE, en.problem("0", "EUR"))
        assertEquals(Money.Problem.PRECISION, en.problem("12.345", "EUR"))
        assertEquals(Money.Problem.PRECISION, en.problem("12.5", "JPY"))
        assertNull(en.problem("12.345", "EUR", original = 12.345))
        assertNull(en.problem("-5", "EUR", required = false, sign = Money.Sign.ANY))
        assertEquals(Money.Problem.INVALID, en.problem("-5", "EUR", sign = Money.Sign.NON_NEGATIVE))
        assertEquals("12,50", de.toInput(12.5, "EUR"))
        assertEquals("1200", en.toInput(1200.0, "JPY"))
        assertEquals("0,00", de.placeholder("EUR"))
        assertEquals("In EUR, amounts are only possible in steps of 0.01", en.problemText(Money.Problem.PRECISION, "EUR", strings))
    }

    @Test fun `BUDGET-A1 the region preset picks the number locale`() {
        assertEquals("de-CH", RegionPresets.numberLocaleFor(null, "CHF", "dmy", "24h"))
        assertEquals("de-DE", RegionPresets.numberLocaleFor(null, "EUR", "dmy", "24h"))
        // A stored region wins while its triple still fits (#486): es-ES and fr-FR share one.
        assertEquals("fr-FR", RegionPresets.numberLocaleFor("fr-FR", "EUR", "dmy_slash", "24h"))
        assertEquals("es-ES", RegionPresets.numberLocaleFor(null, "EUR", "dmy_slash", "24h"))
        assertEquals("nl-NL", RegionPresets.numberLocaleFor("nl-NL", "EUR", "dmy", "24h"))
        assertNull(RegionPresets.numberLocaleFor(null, "EUR", "mdy", "24h"))
        val ch = Money.of(RegionPresets.numberLocaleFor(null, "CHF", "dmy", "24h"), "en", "CHF")
        assertTrue(ch.format(1234.5, "CHF"), Regex("1[’']234\\.50").containsMatchIn(ch.format(1234.5, "CHF")))
    }

    // ── BUDGET-A3: the ledger ──

    @Test fun `BUDGET-A3 filters by category, subcategory and responsible person`() {
        val alex = BudgetPerson(1, "Alex")
        val rows = listOf(
            entry(1, -10.0, category = "food", sub = "groceries", people = listOf(alex)),
            entry(2, -20.0, category = "food", sub = "bakery"),
            entry(3, -30.0, category = "financial_other", sub = "saving"),
        )
        assertEquals(listOf(1, 2), BudgetLedger.visible(rows, BudgetLedger.Filters(category = "food")).map { it.id })
        assertEquals(listOf(3), BudgetLedger.visible(rows, BudgetLedger.Filters(category = BudgetLedger.SAVINGS_CHART_CATEGORY)).map { it.id })
        assertEquals(listOf(2), BudgetLedger.visible(rows, BudgetLedger.Filters(category = "food", subcategory = "food" to "bakery")).map { it.id })
        assertEquals(listOf(1), BudgetLedger.visible(rows, BudgetLedger.Filters(responsible = 1)).map { it.id })
        assertEquals(-30.0, BudgetLedger.bookedSum(rows.take(2) + entry(9, -100.0, pending = true)), 0.0)
    }

    @Test fun `BUDGET-A3 sorting by amount puts expenses first, largest first, stable`() {
        val rows = listOf(entry(1, 50.0), entry(2, -5.0), entry(3, -40.0), entry(4, -5.0))
        assertEquals(listOf(3, 2, 4, 1), BudgetLedger.sort(rows, "amount").map { it.id })
        assertEquals(listOf(1, 2, 3, 4), BudgetLedger.sort(rows, "date").map { it.id })
    }

    @Test fun `BUDGET-A3 planned holds later and expected rows, ascending, booked keeps the order`() {
        val today = LocalDate.parse("2026-11-10")
        val rows = listOf(entry(1, -1.0, "2026-11-25"), entry(2, -1.0, "2026-11-12"), entry(3, -1.0, "2026-11-10"), entry(4, -1.0, "2026-11-02", pending = true), entry(5, -1.0, "2026-11-01"))
        val split = BudgetLedger.split(rows, today)
        assertEquals(listOf(4, 2, 1), split.planned.map { it.id })
        assertEquals(listOf(3, 5), split.booked.map { it.id })
        assertTrue(BudgetLedger.forecast("2026-12", today))
        assertFalse(BudgetLedger.forecast("2026-11", today))
    }

    @Test fun `BUDGET-A3 groups by responsible in member order, nobody last, one entry in two groups`() {
        val alex = BudgetPerson(1, "Alex")
        val linda = BudgetPerson(2, "Linda")
        val rows = listOf(entry(1, -1.0, people = listOf(linda, alex)), entry(2, -1.0), entry(3, -1.0, people = listOf(alex)))
        val members = listOf(BudgetMember(2, "Linda", sortOrder = 0), BudgetMember(1, "Alex", sortOrder = 1))
        val groups = BudgetLedger.groupByResponsible(rows, members)
        assertEquals(listOf(2, 1, null), groups.map { it.person?.id })
        assertEquals(listOf(1, 3), groups[1].entries.map { it.id })
        assertEquals(listOf(2), groups[2].entries.map { it.id })
    }

    // ── BUDGET-A4: the chart ──

    @Test fun `BUDGET-A4 blocks by kind on their own scales, sorted by size`() {
        val rows = listOf(
            CategoryTotal("food", income = 0.0, expenses = -300.0, total = -300.0),
            CategoryTotal("Erwerbseinkommen", income = 3000.0, expenses = 0.0, total = 3000.0),
            CategoryTotal("housing", income = 50.0, expenses = -900.0, total = -850.0),
            CategoryTotal("financial_other", transfers = 200.0, income = 0.0, expenses = 0.0, total = 0.0),
        )
        val b = BudgetLedger.blocks(rows)
        assertEquals(listOf("housing", "food"), b.expenses.map { it.category })
        assertEquals(listOf("Erwerbseinkommen", "housing"), b.income.map { it.category })
        assertEquals(listOf("financial_other"), b.transfers.map { it.category })
        assertEquals("expenses", b.lead)
        assertTrue(b.hasMore)
        assertFalse(BudgetLedger.blocks(rows.take(1)).hasMore)
    }

    @Test fun `BUDGET-A4 subcategory rows need two groups and put the unassigned last`() {
        val rows = listOf(entry(1, -10.0, sub = "bakery"), entry(2, -5.0, sub = null), entry(3, -7.0, sub = "groceries"), entry(4, -1.0, sub = "groceries", pending = true))
        val sub = BudgetLedger.subcategoryRows("food", rows, listOf("groceries", "bakery"))
        assertEquals(listOf("groceries", "bakery", ""), sub.map { it.key })
        assertEquals(-7.0, sub[0].amount, 0.0)
        assertTrue(BudgetLedger.subcategoryRows("food", rows.take(1), emptyList()).isEmpty())
    }

    @Test fun `BUDGET-A4 category colours keep the catalogue position and avoid collisions`() {
        val catalog = listOf("housing", "food", "transport")
        val by = listOf(
            CategoryTotal("food", expenses = -10.0),
            CategoryTotal("housing", expenses = -50.0),
            CategoryTotal("zzz", expenses = -5.0),
            CategoryTotal("salary", income = 100.0, expenses = 0.0),
        )
        val idx = BudgetLedger.colorIndex(by, catalog)
        assertEquals(0, idx["housing"])
        assertEquals(1, idx["food"])
        assertEquals(3, idx["zzz"])
        assertNull(idx["salary"])
        assertEquals(2, BudgetLedger.paint(idx, "transport", catalog))
        assertTrue(BudgetLedger.textured(5))
    }

    // ── BUDGET-A5: deleting changes the summary locally ──

    @Test fun `BUDGET-A5 summaryWith takes an entry out of the totals and its category`() {
        val s = BudgetSummary(
            month = "2026-11", income = 100.0, expenses = -60.0, balance = 40.0,
            byCategory = listOf(CategoryTotal("food", 0.0, -60.0, -60.0)), pending = PendingTotals(1, 0.0, -9.0),
        )
        val out = BudgetLedger.summaryWith(s, listOf(entry(1, -60.0, "2026-11-03")), -1)
        assertEquals(0.0, out.expenses, 1e-9)
        assertEquals(100.0, out.balance, 1e-9)
        assertTrue(out.byCategory.isEmpty())
        val pend = BudgetLedger.summaryWith(s, listOf(entry(2, -9.0, "2026-11-20", pending = true)), -1)
        assertEquals(0, pend.pending!!.count)
        assertEquals(-60.0, pend.expenses, 1e-9)
        // A row of another month leaves the summary alone.
        assertEquals(s, BudgetLedger.summaryWith(s, listOf(entry(3, -5.0, "2026-10-03")), -1))
    }

    // ── BUDGET-A6: series edits send only what changed ──

    @Test fun `BUDGET-A6 an occurrence sends its changes, never date or rhythm`() {
        val e = entry(5, -20.0).copy(recurrenceParentId = 1, accountId = null, visibility = "shared")
        val form = EntryForm.of(e, en, "EUR", emptyList(), listOf(BudgetCategory("food"))).copy(title = "New")
        val body = form.body(en, withAccount = true, withResponsible = false, personal = false, attachmentIds = null)
        val series = BudgetLedger.occurrenceSeriesBody(body, e)
        assertEquals(setOf("title"), series.keys)
    }

    @Test fun `BUDGET-A6 the anchor sends its rhythm and a moved date as start_date`() {
        val e = entry(1, -20.0, "2026-11-01").copy(isRecurring = true, recurrenceInterval = "monthly", recurrenceIntervalCount = 1)
        val form = EntryForm.of(e, en, "EUR", emptyList(), listOf(BudgetCategory("food"))).copy(date = LocalDate.parse("2026-11-05"), amount = "25")
        val series = BudgetLedger.anchorSeriesBody(form.body(en, false, false, false, null), e)
        assertEquals("2026-11-05", (series["start_date"] as JsonPrimitive).content)
        assertEquals(-25.0, (series["amount"] as JsonPrimitive).doubleOrNull)
        assertTrue("is_recurring" in series && "recurrence_interval" in series)
        assertFalse("date" in series)
        assertFalse("title" in series)
    }

    // ── BUDGET-A7: the entry form ──

    @Test fun `BUDGET-A7 the form names every missing field and builds the web page's body`() {
        val subs = listOf(BudgetSubcategory("a"), BudgetSubcategory("b"))
        val empty = EntryForm(date = LocalDate.parse("2026-11-02"))
        val errors = empty.copy(category = "food").errors(en, "EUR", subs, null, strings)
        assertEquals(setOf("title", "amount", "subcategory"), errors.keys)
        assertEquals(setOf("title", "amount", "category"), empty.errors(en, "EUR", emptyList(), null, strings).keys)
        val ok = empty.copy(title = "Milk", amount = "3,20", category = "food", subcategory = "a", accountId = 4, responsible = setOf(2, 1))
        assertTrue(ok.errors(en, "EUR", subs, null, strings).isEmpty())
        val body = ok.body(en, withAccount = true, withResponsible = true, personal = true, attachmentIds = listOf(9))
        assertEquals(-3.2, (body["amount"] as JsonPrimitive).doubleOrNull)
        assertEquals(4, (body["account_id"] as JsonPrimitive).intOrNull)
        assertEquals(listOf(1, 2), (body["responsible_user_ids"] as JsonArray).map { (it as JsonPrimitive).intOrNull })
        assertEquals("shared", (body["visibility"] as JsonPrimitive).content)
        assertEquals(0, (body["is_recurring"] as JsonPrimitive).intOrNull)
        assertEquals(JsonNull, ok.copy(accountId = null).body(en, true, false, false, null)["account_id"])
        assertFalse("account_id" in ok.body(en, false, false, false, null))
        assertEquals(3.2, (ok.copy(type = "income").body(en, false, false, false, null)["amount"] as JsonPrimitive).doubleOrNull)
    }

    @Test fun `BUDGET-A7 one subcategory is preselected, several are a required choice`() {
        val one = listOf(BudgetSubcategory("x"))
        val two = listOf(BudgetSubcategory("x"), BudgetSubcategory("y"))
        assertEquals(BudgetLedger.SubChoice("x", false), BudgetLedger.subcategoryChoice(one, null))
        assertEquals(BudgetLedger.SubChoice("", true), BudgetLedger.subcategoryChoice(two, null))
        assertEquals(BudgetLedger.SubChoice("y", true), BudgetLedger.subcategoryChoice(two, "y"))
        assertEquals(BudgetLedger.SubChoice("", false), BudgetLedger.subcategoryChoice(emptyList(), "z"))
    }

    // ── BUDGET-A8: errors say the web page's sentences ──

    @Test fun `BUDGET-A8 refusals go to their field, the rest to a sentence of the app`() {
        val amount = BudgetText.budgetError(ApiProblem.Http(400, "entry_amount_invalid", "x"), strings, BudgetText.BUDGET_SAVE_FAILED)
        assertEquals("amount", amount.field)
        assertEquals(strings.t("budget.validAmountRequired"), amount.message)
        assertEquals(strings.t("category.errorExists"), BudgetText.budgetError(ApiProblem.Http(409, "category_exists", null), strings).message)
        assertEquals(strings.t("budget.saveFailed"), BudgetText.budgetError(ApiProblem.Http(400, "unknown", null), strings, BudgetText.BUDGET_SAVE_FAILED).message)
        assertEquals(strings.t("common.errorNoPermission"), BudgetText.budgetError(ApiProblem.Http(403, null, null), strings).message)
        assertEquals(strings.t("common.errorNotFound"), BudgetText.budgetError(ApiProblem.Http(404, null, null), strings).message)
        assertEquals(strings.t("common.errorOfflineMutation"), BudgetText.budgetError(ApiProblem.Network(IOException("x")), strings).message)
        assertEquals(strings.t("common.errorOccurred"), BudgetText.budgetError(ApiProblem.Http(429, null, null), strings).message)
        val loan = BudgetText.loanError(ApiProblem.Http(400, "loan_installments_invalid", null), strings)
        assertEquals("installments", loan.field)
        assertEquals(strings.t("budget.loanTermTooLong", "max" to 600), BudgetText.previewRefusal("loan_term_too_long", 600, strings))
        assertEquals(strings.t("category.errorInUse", "count" to 3), BudgetText.categoryError(ApiProblem.Http(409, "category_in_use", "Category is in use by 3 entries."), strings))
    }

    @Test fun `BUDGET-A8 labels translate seeded keys and keep the household's own names`() {
        val meta = BudgetMeta(
            expenseCategories = listOf(BudgetCategory("food", "Essen"), BudgetCategory("pets", "Pets")),
            subcategories = mapOf("food" to listOf(BudgetSubcategory("groceries", "Lebensmittel"), BudgetSubcategory("x1", "Own"))),
        )
        assertEquals(strings.t("budget.categoryFood"), BudgetText.category("food", meta, strings))
        assertEquals("Pets", BudgetText.category("pets", meta, strings))
        assertEquals(strings.t("budget.maskedCategory"), BudgetText.category(BudgetLedger.MASKED_CATEGORY, meta, strings))
        assertEquals(strings.t("budget.catSaving"), BudgetText.category(BudgetLedger.SAVINGS_CHART_CATEGORY, meta, strings))
        assertEquals(strings.t("budget.subcatGroceries"), BudgetText.subcategory("groceries", meta, strings))
        assertEquals("Own", BudgetText.subcategory("x1", meta, strings))
        val e = entry(1, -1.0).copy(isRecurring = true, recurrenceInterval = "weekly", recurrenceIntervalCount = 2, recurrenceConfirm = true)
        assertEquals("Every 2 weeks · ${strings.t("budget.confirmFirstLabel")}", BudgetText.recurrence(e, strings))
    }

    // ── BUDGET-A9: loans ──

    private fun loan(id: Int, direction: String = "borrowed", status: String = "active", rate: Double? = null, balance: Double = 100.0) =
        BudgetLoan(id = id, title = "L$id", borrower = "P$id", direction = direction, status = status, remainingAmount = balance, interest = rate?.let { LoanInterest(currentRate = it) })

    @Test fun `BUDGET-A9 loans sort by rate or balance within borrowed, lent, paid`() {
        val loans = listOf(loan(1, rate = 2.0), loan(2, "lent", rate = 5.0), loan(3, rate = 4.0), loan(4, status = "paid"))
        assertEquals(listOf(1, 2, 3, 4), BudgetLedger.sortLoans(loans, "start").map { it.id })
        assertEquals(listOf(3, 1, 2, 4), BudgetLedger.sortLoans(loans, "rate").map { it.id })
        assertEquals(listOf("borrowed", "lent", "paid"), BudgetLedger.groupLoans(loans, "rate")!!.map { it.id })
        assertNull(BudgetLedger.groupLoans(loans.filter { it.direction == "borrowed" }, "rate"))
        val small = listOf(loan(1, balance = 500.0), loan(2, balance = 50.0))
        assertEquals(listOf(2, 1), BudgetLedger.sortLoans(small, "balance").map { it.id })
    }

    @Test fun `BUDGET-A9 the next installment pays the rest at the end, payments come newest first`() {
        val l = loan(1).copy(installmentCount = 3, installmentAmount = 40.0, remainingAmount = 30.0, nextInstallmentNumber = 3)
        assertEquals(30.0, BudgetLedger.nextInstallmentAmount(l), 0.0)
        assertEquals(30.0, BudgetLedger.nextInstallmentAmount(l.copy(nextInstallmentNumber = 2)), 0.0)
        val withPayments = l.copy(payments = listOf(LoanPayment(1, 1, 10.0, "2026-01-01"), LoanPayment(2, 2, 10.0, "2026-02-01")))
        assertEquals(listOf(2, 1), BudgetLedger.loanPayments(listOf(withPayments), listOf(withPayments)).map { it.second.id })
        assertEquals("P1", BudgetLedger.loanRowTitle(l, listOf(l)))
        assertEquals("L1", BudgetLedger.loanRowTitle(l, listOf(l, loan(2).copy(borrower = "p1"))))
        assertEquals(10, BudgetLedger.suggestedPaidInstallments("2026-01", LocalDate.parse("2026-11-02"), null))
        assertEquals(3, BudgetLedger.suggestedPaidInstallments("2026-01", LocalDate.parse("2026-11-02"), 3))
    }

    @Test fun `BUDGET-A9 the loan form checks in the web page's order and builds its body`() {
        val f = LoanForm(borrower = "Bank", start = "2026-11", amount = "1200", installments = "12", currency = "EUR")
        val ok = f.check(en, "EUR", null, withAccountField = false, isEdit = false, strings) as LoanForm.Check.Ok
        assertEquals("Bank", (ok.body["title"] as JsonPrimitive).content)
        assertEquals(12, (ok.body["installment_count"] as JsonPrimitive).intOrNull)
        assertEquals(0, ok.paid)
        val missing = LoanForm(start = "2026-11").check(en, "EUR", null, false, false, strings) as LoanForm.Check.Field
        assertEquals("borrower", missing.field)
        val tooMany = f.copy(paid = "13").check(en, "EUR", null, false, false, strings) as LoanForm.Check.Field
        assertEquals(strings.t("budget.loanPaidInstallmentsMax", "max" to 12), tooMany.message)
        val foreign = f.copy(currency = "USD").check(en, "EUR", null, false, false, strings) as LoanForm.Check.Field
        assertEquals("exchangeRate", foreign.field)
        assertNull(f.previewBody(en))
        assertTrue(f.copy(mode = "fixed", principal = "10000", fixedRate = "3", repayment = "2").previewBody(en) != null)
    }

    // ── BUDGET-A10: statistics ──

    @Test fun `BUDGET-A10 calm axis, today's bucket and the donut's other segment`() {
        assertEquals(1200.0, BudgetLedger.calmMax(1000.0), 0.0)
        assertEquals(2.0, BudgetLedger.calmMax(0.0), 0.0)
        val days = (1..30).map { "2026-11-%02d".format(it) }
        assertEquals(1, BudgetLedger.futureStartIndex(days, "2026-11-02"))
        assertEquals(-1, BudgetLedger.futureStartIndex(days, "2026-12-01"))
        assertEquals(listOf(1.0, 3.0, 6.0), BudgetLedger.running(listOf(1.0, 2.0, 3.0)))
        val many = (1..9).map { CategoryTotal("c$it", expenses = -it.toDouble()) }
        val donut = BudgetLedger.donut(many, emptyList())
        assertEquals(7, donut.size)
        assertNull(donut.last().category)
        assertEquals(1.0 + 2 + 3, donut.last().value, 0.0)
        assertEquals("over", BudgetLedger.planTone(1.2, true))
        assertEquals("near", BudgetLedger.planTone(0.9, false))
    }

    @Test fun `BUDGET-A10 comparison rows join both periods`() {
        val now = listOf(CategoryTotal("food", expenses = -50.0), CategoryTotal("salary", income = 10.0))
        val before = listOf(CategoryTotal("food", expenses = -70.0), CategoryTotal("fun", expenses = -5.0))
        val rows = BudgetLedger.compareRows(now, before, "expenses")
        assertEquals(listOf("food", "fun"), rows.map { it.category })
        assertEquals(0.0, rows[1].amount, 0.0)
    }

    // ── BUDGET-A11: transfers and accounts ──

    @Test fun `BUDGET-A11 transfer and account forms`() {
        val accounts = listOf(BudgetAccount(1, "Giro"), BudgetAccount(2, "Savings"))
        val tf = TransferForm(amount = "50", from = 1, to = 2, date = LocalDate.parse("2026-11-02"), category = "financial_other")
        assertEquals("Transfer Giro → Savings", tf.defaultTitle(accounts, strings))
        assertTrue(tf.errors(en, "EUR", emptyList(), null, strings).isEmpty())
        assertEquals(setOf("to"), tf.copy(to = 1).errors(en, "EUR", emptyList(), null, strings).keys)
        val body = tf.body(en, accounts, personal = false, withRecurrence = false, strings = strings)
        assertEquals("Transfer Giro → Savings", (body["title"] as JsonPrimitive).content)
        assertFalse("is_recurring" in body)
        val af = AccountForm(name = "Card", type = "credit", balance = "-20", creditLimit = "500")
        assertTrue(af.errors(en, "EUR", null, strings).isEmpty())
        assertEquals(500.0, (af.body(en)["credit_limit"] as JsonPrimitive).doubleOrNull)
        assertEquals(JsonNull, af.copy(type = "checking").body(en)["credit_limit"])
        assertEquals(Money.Tone.NEUTRAL, BudgetLedger.accountTone(BudgetAccount(3, type = "credit", currentBalance = -10.0, availableLimit = 5.0)))
        assertEquals(Money.Tone.NEGATIVE, BudgetLedger.accountTone(BudgetAccount(3, type = "credit", availableLimit = -1.0)))
        assertEquals(Money.Tone.NEGATIVE, BudgetLedger.accountTone(BudgetAccount(3, currentBalance = -1.0)))
    }

    @Test fun `BUDGET-A12 the period helpers`() {
        assertEquals("2026-01", BudgetLedger.addMonths("2026-02", -1))
        assertEquals(LocalDate.parse("2026-11-02"), BudgetLedger.anchorForMonth("2026-11", LocalDate.parse("2026-11-02")))
        assertEquals(LocalDate.parse("2026-10-01"), BudgetLedger.anchorForMonth("2026-10", LocalDate.parse("2026-11-02")))
        assertEquals(LocalDate.parse("2026-11-09"), BudgetLedger.stepAnchor(LocalDate.parse("2026-11-02"), "week", 1))
        val (from, to) = BudgetLedger.monthRange("2026-02")
        assertEquals(LocalDate.parse("2026-02-28"), to)
        assertEquals(from, BudgetLedger.defaultDate(from, to, LocalDate.parse("2026-11-02")))
    }
}
