package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Collator
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** docs/spec/subscriptions.md: the tab's pure rules. */
class SubscriptionLogicTest {
    companion object {
        private val assets = File(System.getProperty("yuvomi.assetsDir"))
        fun strings(locale: String = "en"): Strings {
            val en = Strings.flatten(File(assets, "i18n/en.json").readText()) +
                File(assets, "i18n-android").walkTopDown().filter { it.name == "en.json" }.flatMap { Strings.flatten(it.readText()).entries }.associate { it.key to it.value }
            return Strings(locale, en, en) { if (it == 1.0) "one" else "other" }
        }
    }

    private val en = strings()
    private val today = LocalDate.of(2026, 11, 2)

    private fun sub(id: Int, name: String = "S$id", amount: Double = 10.0, cycle: String = "monthly", interval: Int = 1, next: String = "2026-11-10", monthlyBase: Double? = amount, currency: String = "EUR", enabled: Boolean = true) =
        Subscription(id = id, name = name, amount = amount, billingCycle = cycle, cycleInterval = interval, nextPaymentDate = next, monthlyBase = monthlyBase, monthlyNative = amount, currency = currency, enabled = enabled)

    /** SUBS-A1: amounts in the region's notation, `toAmountDecimalString` and `amountInputProblem`. */
    @Test fun `money formats and reads amounts like the web`() {
        assertTrue(BudgetMoney.format(17.99, "EUR", "de").let { it.contains("17,99") && it.contains("€") })
        assertTrue(BudgetMoney.format(1200.0, "USD", "en").contains("1,200.00"))
        assertEquals("1200", BudgetMoney.format(1200.0, "JPY", "en").filter(Char::isDigit))
        assertEquals("12.50", BudgetMoney.toDecimal("12,50", "de"))
        assertEquals("12.50", BudgetMoney.toDecimal("12,50", "en"))
        assertEquals("0.5", BudgetMoney.toDecimal(".5", "en"))
        assertNull(BudgetMoney.toDecimal("1.200", "de"))
        assertNull(BudgetMoney.toDecimal("1,200", "en"))
        assertNull(BudgetMoney.toDecimal("1.200,50", "de"))
        assertEquals(BudgetMoney.GROUPED, BudgetMoney.problem("10 000", "EUR", "fr", required = true))
        assertEquals(BudgetMoney.INVALID, BudgetMoney.problem("12abc", "EUR", "en"))
        assertEquals(BudgetMoney.NOT_POSITIVE, BudgetMoney.problem("0", "EUR", "en"))
        assertNull(BudgetMoney.problem("0", "EUR", "en", nonNegative = true))
        assertEquals(BudgetMoney.PRECISION, BudgetMoney.problem("1.5", "JPY", "en"))
        assertNull(BudgetMoney.problem("1.234", "EUR", "en", original = 1.234, originalCurrency = "EUR"))
        assertEquals(BudgetMoney.PRECISION, BudgetMoney.problem("1.234", "EUR", "en"))
        assertEquals("17,90", BudgetMoney.toInput("17.9", "EUR", "de"))
        assertEquals("17.99", BudgetMoney.toInput(17.99, "EUR", "en"))
        assertTrue(BudgetMoney.problemText(en, BudgetMoney.GROUPED, "EUR").contains("1250.00"))
    }

    /** SUBS-A2: the cycle as the dialog's sentence, the due label with the coarse distance. */
    @Test fun `cycle and due labels`() {
        assertEquals("Monthly", SubscriptionRules.cycleLabel(en, "monthly", 1))
        assertEquals("Every 2 months", SubscriptionRules.cycleLabel(en, "monthly", 2))
        assertEquals("Every 3 weeks", SubscriptionRules.cycleLabel(en, "weekly", 3))
        assertEquals("months", SubscriptionRules.cycleUnitWord(en, "monthly", 2))
        val rel = { v: Long, u: String -> "$v $u" }
        assertEquals("2 days overdue", SubscriptionRules.dueLabel(en, -2, rel))
        assertEquals("1 day overdue", SubscriptionRules.dueLabel(en, -1, rel))
        assertEquals("Due today", SubscriptionRules.dueLabel(en, 0, rel))
        assertEquals("Due tomorrow", SubscriptionRules.dueLabel(en, 1, rel))
        assertEquals("Due in 30 days", SubscriptionRules.dueLabel(en, 30, rel))
        assertEquals("6 week", SubscriptionRules.dueLabel(en, 42, rel))
        assertEquals("5 month", SubscriptionRules.dueLabel(en, 140, rel))
        assertEquals("1 year", SubscriptionRules.dueLabel(en, 400, rel))
        assertEquals(8L, SubscriptionRules.daysUntil("2026-11-10", today))
    }

    /** SUBS-A3: the four sorts. */
    @Test fun `sorting by due date, cost and name`() {
        val rows = listOf(sub(1, "Beta", 5.0, next = "2026-11-20"), sub(2, "alpha", 20.0, next = "2026-11-05"), sub(3, "Gamma", 1.0, next = "2026-11-05", monthlyBase = null))
        val c = Collator.getInstance(Locale.ENGLISH)
        assertEquals(listOf(2, 3, 1), SubscriptionRules.sorted(rows, SubscriptionRules.SORT_DUE, c).map { it.id })
        assertEquals(listOf(2, 1, 3), SubscriptionRules.sorted(rows, SubscriptionRules.SORT_COST_DESC, c).map { it.id })
        assertEquals(listOf(1, 2, 3), SubscriptionRules.sorted(rows, SubscriptionRules.SORT_COST_ASC, c).map { it.id })
        assertEquals(listOf(2, 1, 3), SubscriptionRules.sorted(rows, SubscriptionRules.SORT_NAME, c).map { it.id })
    }

    /** SUBS-A4: six months of payments, limited by the end condition; paused ones do not count. */
    @Test fun `renewal forecast`() {
        val monthly = sub(1, amount = 10.0, next = "2026-10-15")
        val yearly = sub(2, amount = 120.0, cycle = "yearly", next = "2027-01-03", monthlyBase = 10.0)
        val ending = sub(3, amount = 5.0, next = "2026-11-20").copy(endType = Subscription.END_AFTER_COUNT, occurrenceCount = 2, occurrencesDone = 0)
        val paused = sub(4, amount = 99.0, enabled = false)
        val f = SubscriptionRules.forecast(listOf(monthly, yearly, ending, paused), today, "EUR")
        assertEquals(YearMonth.of(2026, 11), f.first().month)
        assertEquals(listOf(15.0, 15.0, 130.0, 10.0, 10.0, 10.0), f.map { it.amount })
        assertEquals(YearMonth.of(2027, 1), SubscriptionRules.peak(f)?.month)
        assertEquals(LocalDate.of(2026, 12, 20), SubscriptionRules.endBoundary(ending))
        // A foreign currency books in the base currency through the monthly ratio.
        val usd = sub(5, amount = 12.0, currency = "USD", monthlyBase = 11.0)
        assertEquals(11.0, SubscriptionRules.dueAmount(usd, "EUR"), 0.001)
    }

    /** SUBS-A5: over-budget note, foreign currencies, filters, gone filters. */
    @Test fun `summary rules and filters`() {
        assertEquals(121, SubscriptionRules.overBudgetPercent(Summary(monthlyTotal = 109.0, monthlyBudget = 90.0, remainingBudget = -19.0)))
        assertNull(SubscriptionRules.overBudgetPercent(Summary(monthlyTotal = 50.0, monthlyBudget = 90.0, remainingBudget = 40.0)))
        assertNull(SubscriptionRules.overBudgetPercent(Summary(monthlyTotal = 50.0, monthlyBudget = 0.0, remainingBudget = -50.0)))
        assertTrue(SubscriptionRules.hasForeignCurrency(listOf(sub(1, currency = "USD")), "EUR"))
        assertFalse(SubscriptionRules.hasForeignCurrency(listOf(sub(1)), "EUR"))
        val meta = SubscriptionMeta(categories = listOf(SubscriptionCategory(1, "Entertainment", labelKey = null)), paymentMethods = listOf(PaymentMethod(3, "PayPal", labelKey = "subscriptions.paymentMethodPaypal")))
        val f = SubscriptionRules.Filters(categoryId = 1, paymentMethodId = 3, status = "paused")
        assertEquals(3, f.count)
        assertEquals(listOf("Entertainment", "PayPal", "Disabled"), SubscriptionRules.activeFilters(en, f, meta).map { it.value })
        assertEquals(SubscriptionRules.Filters(categoryId = 1), SubscriptionRules.withoutGone(SubscriptionRules.Filters(categoryId = 1, paymentMethodId = 9), meta))
        assertEquals("Uncategorized", SubscriptionRules.rowCategoryLabel(en, sub(1)))
        assertEquals(listOf(2, 1, 3), SubscriptionRules.moved(listOf(1, 2, 3), 1, 1))
        assertNull(SubscriptionRules.moved(listOf(1, 2, 3), 1, -1))
        assertEquals("Education", SubscriptionRules.renamedName("Bildung", "Bildung", "Education"))
        assertEquals("Lernen", SubscriptionRules.renamedName(" Lernen ", "Bildung", "Education"))
    }

    /** SUBS-A6: the editor's checks, all at once, and the body `saveSubscription()` sends. */
    @Test fun `form validation and body`() {
        val blank = SubscriptionForm.blank(SubscriptionSettings(90.0, "EUR"), today)
        val errors = SubscriptionFormRules.errors(blank.copy(interval = "0", endType = Subscription.END_ON_DATE, endDate = "2026-10-01"), null, en)
        assertEquals(setOf(SubscriptionFormRules.NAME, SubscriptionFormRules.AMOUNT, SubscriptionFormRules.INTERVAL, SubscriptionFormRules.END_DATE), errors.keys)
        assertEquals(en.t("subscriptions.errorEndBeforeNextPayment"), errors[SubscriptionFormRules.END_DATE])
        val done = sub(1).copy(occurrencesDone = 3, endType = Subscription.END_AFTER_COUNT, occurrenceCount = 5)
        val low = SubscriptionForm.of(done, "en").copy(endCount = "2")
        assertEquals(en.t("subscriptions.errorOccurrenceCountTooLow"), SubscriptionFormRules.errors(low, done, en)[SubscriptionFormRules.END_COUNT])
        // A completed subscription that stays completed skips the comparison (#2009).
        val completed = done.copy(completedAt = "2026-10-01T10:00:00Z", enabled = false, status = "completed")
        assertTrue(SubscriptionFormRules.errors(SubscriptionForm.of(completed, "en").copy(endCount = "2"), completed, en).isEmpty())

        val form = blank.copy(name = " Netflix ", amount = "17,99", notes = "", categoryId = 2)
        val shared = SubscriptionFormRules.body(form, null, SubscriptionMeta(), "de")
        assertEquals("Netflix", shared["name"]!!.jsonPrimitive.content)
        assertEquals(17.99, shared["amount"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(JsonNull, shared["notes"])
        assertEquals(JsonPrimitive(2), shared["category_id"])
        assertEquals(JsonNull, shared["end_date"])
        assertFalse("account_id" in shared)
        assertFalse("visibility" in shared)
        val personal = SubscriptionFormRules.body(form.copy(visibility = "private", accountId = 4), null, SubscriptionMeta(budgetMode = "personal", accounts = listOf(BudgetAccountRef(4, "Main"))), "de")
        assertEquals("private", personal["visibility"]!!.jsonPrimitive.content)
        assertEquals(4, personal["account_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("subscriptions.logoTypeError", SubscriptionFormRules.logoProblem("image/gif", 10))
        assertEquals("subscriptions.logoTooLarge", SubscriptionFormRules.logoProblem("image/png", 600_000))
        assertNull(SubscriptionFormRules.logoProblem("image/svg+xml", 1000))
    }

    /** SUBS-A7: reasons to sentences; gone and already deleted. */
    @Test fun `errors map reasons to upstream sentences`() {
        fun http(status: Int, reason: String?) = ApiProblem.Http(status, reason, "English text")
        assertEquals("subscriptions.errorSubscriptionGone", SubscriptionErrors.key(http(404, SubscriptionErrors.SUBSCRIPTION_GONE)))
        assertEquals("subscriptions.errorCategoryExists", SubscriptionErrors.key(http(409, "subscriptions_category_exists")))
        assertEquals("subscriptions.errorSubscriptionCompleted", SubscriptionErrors.key(http(409, SubscriptionErrors.COMPLETED)))
        assertEquals("settings.permReadOnlyBanner", SubscriptionErrors.key(http(403, "module_read_only")))
        assertEquals("common.errorNoPermission", SubscriptionErrors.key(http(403, null)))
        assertEquals("common.errorOfflineMutation", SubscriptionErrors.key(ApiProblem.Network(java.io.IOException())))
        assertEquals("common.unknownError", SubscriptionErrors.key(http(409, SubscriptionErrors.UNDO_STALE)))
        assertTrue(SubscriptionErrors.isAlreadyDeleted(http(404, SubscriptionErrors.SUBSCRIPTION_GONE), SubscriptionErrors.SUBSCRIPTION_GONE))
        assertFalse(SubscriptionErrors.isGone(http(403, SubscriptionErrors.SUBSCRIPTION_GONE)))
    }

    /** SUBS-A8: the list answer decodes, flags as booleans or integers. */
    @Test fun `list answer decodes`() {
        val json = """{"data":{"subscriptions":[{"id":1,"name":"Netflix","amount":17.99,"currency":"EUR","billing_cycle":"monthly","cycle_interval":1,
            "next_payment_date":"2026-11-10","enabled":true,"reminder_enabled":1,"status":"active","monthly_base":null,"account_archived":false,"end_type":"never"}],
            "summary":{"active_count":1,"monthly_total":17.99,"monthly_budget":90,"remaining_budget":72.01,"base_currency":"EUR","by_category":[{"id":null,"name":null,"amount":17.99}]},
            "rates":{"source":"unavailable"}}}"""
        val list = SubscriptionsApi.decodeList(ApiJson.parseToJsonElement(json))
        assertEquals(1, list.subscriptions.size)
        assertNull(list.subscriptions[0].monthlyBase)
        assertTrue(list.subscriptions[0].reminderEnabled)
        assertEquals("unavailable", list.rates?.source)
        assertEquals("Uncategorized", SubscriptionRules.breakdownLabel(en, list.summary!!.byCategory[0], "subscriptions.uncategorized"))
    }

    /** SUBS-A9: hashtags in name, description or notes, one item per subscription with its route. */
    @Test fun `tag source finds hashtags`() {
        val items = SubscriptionsTagSource.items(listOf(
            sub(1, "Netflix").copy(notes = "Shared #family profile"),
            sub(2, "Gym").copy(description = "#Health and #family"),
            sub(3, "Plain"),
        ))
        assertEquals(listOf("subscription:1", "subscription:2"), items.map { it.key })
        assertEquals(setOf("Health", "family"), items[1].tags)
        assertEquals(SubscriptionsRoutes.subscription(1), items[0].route)
        assertTrue(items.all { it.moduleId == "budget" && it.route!!.startsWith("budget/subscriptions") })
    }
}
