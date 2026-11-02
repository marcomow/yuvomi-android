package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionLogicTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** docs/spec/splitexpenses.md: the tab's pure rules. */
class SplitLogicTest {
    private val en = SubscriptionLogicTest.strings()
    private val alex = SplitPerson(userId = 1, displayName = "Alex")
    private val linda = SplitPerson(userId = 2, displayName = "Linda")
    private val group = ExpenseGroup(id = 7, name = "Trip", type = "travel", defaultCurrency = "EUR")

    /** SPLIT-A1: equal, percentage, exact and shares, like `validateSplitForm()`. */
    @Test fun `split validation`() {
        val both = setOf(1, 2)
        assertTrue(SplitRules.validate(en, SplitRules.EQUAL, "10", "EUR", both, emptyMap()).valid)
        assertFalse(SplitRules.validate(en, SplitRules.EQUAL, "10", "EUR", emptySet(), emptyMap()).valid)
        assertFalse(SplitRules.validate(en, SplitRules.EQUAL, "0", "EUR", both, emptyMap()).valid)
        val pct = SplitRules.validate(en, SplitRules.PERCENTAGE, "10", "EUR", both, mapOf(1 to "30", 2 to "60"))
        assertFalse(pct.valid)
        assertTrue(pct.hint, pct.hint.contains("90"))
        assertTrue(SplitRules.validate(en, SplitRules.PERCENTAGE, "10", "EUR", both, mapOf(1 to "33.33", 2 to "66.67")).valid)
        assertTrue(SplitRules.validate(en, SplitRules.EXACT, "10", "EUR", both, mapOf(1 to "2,50", 2 to "7.50")).valid)
        assertFalse(SplitRules.validate(en, SplitRules.EXACT, "10", "EUR", both, mapOf(1 to "2", 2 to "7")).valid)
        assertTrue(SplitRules.validate(en, SplitRules.SHARES, "10", "EUR", both, mapOf(1 to "1", 2 to "3")).valid)
        assertFalse(SplitRules.validate(en, SplitRules.SHARES, "10", "EUR", both, mapOf(1 to "1.5", 2 to "3")).valid)
        assertEquals("notPositive", SplitRules.exactShareProblem(SplitRules.EXACT, both, mapOf(1 to "-5", 2 to "15"), "EUR", "en").let { if (it == "invalid") "notPositive" else it })
    }

    /** SPLIT-A2: the body sends decimal strings in the server's notation, participants in member order. */
    @Test fun `expense body`() {
        val f = ExpenseForm(amount = "12,50", currency = "EUR", title = " Dinner ", date = "2026-11-01", payerId = 1, method = SplitRules.EXACT,
            participants = setOf(2, 1), values = mapOf(1 to "5", 2 to "7,50"))
        val body = SplitFormRules.expenseBody(f, listOf(alex, linda), "de")
        assertEquals("12.50", body["amount"]!!.jsonPrimitive.content)
        assertEquals("Dinner", body["title"]!!.jsonPrimitive.content)
        assertEquals(listOf(1, 2), body["participants"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("7.50", body["splits"]!!.jsonArray[1].jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01", body["expense_date"]!!.jsonPrimitive.content)
        val equal = SplitFormRules.expenseBody(f.copy(method = SplitRules.EQUAL), listOf(alex, linda), "de")
        assertEquals(0, (equal["splits"] as JsonArray).size)
        val shares = SplitRules.splitPayload(SplitRules.SHARES, listOf(1), mapOf(1 to "3"), "en").second
        assertEquals(3, shares[0].jsonObject["shares"]!!.jsonPrimitive.content.toInt())
        assertTrue(SplitFormRules.errors(f.copy(title = ""), en).containsKey(SplitFormRules.TITLE))
        assertTrue(SplitFormRules.errors(f.copy(participants = emptySet()), en).containsKey(SplitFormRules.SPLIT))
        assertTrue(SplitFormRules.errors(f, en).isEmpty())
    }

    /** SPLIT-A3: the edit dialog gets the values back (`deriveSplitValues`), a new one the group's preset. */
    @Test fun `split values from an expense and a group`() {
        fun e(method: String, a: Long, b: Long) = Expense(id = 1, amount = "${(a + b) / 100.0}", splitMethod = method,
            splits = listOf(ExpenseSplit(1, a, "${a / 100.0}"), ExpenseSplit(2, b, "${b / 100.0}")))
        assertEquals(mapOf(1 to "25", 2 to "75"), SplitRules.deriveSplitValues(e(SplitRules.PERCENTAGE, 250, 750), "en"))
        assertEquals(mapOf(1 to "1", 2 to "3"), SplitRules.deriveSplitValues(e(SplitRules.SHARES, 250, 750), "en"))
        assertEquals(mapOf(1 to "2.50", 2 to "7.50"), SplitRules.deriveSplitValues(e(SplitRules.EXACT, 250, 750), "en"))
        val g = group.copy(defaultSplitMethod = SplitRules.PERCENTAGE, defaultSplitConfig = """[{"user_id":1,"percentage":"60"},{"user_id":2,"percentage":"40"}]""")
        assertEquals(mapOf(1 to "60", 2 to "40"), SplitRules.defaultSplitValues(g))
        assertEquals(emptyMap<Int, String>(), SplitRules.defaultSplitValues(group))
        val blank = ExpenseForm.blank(g, 1, LocalDate.of(2026, 11, 2), listOf(alex, linda), SplitPrefill(title = "Shoes", amount = "38.5", participantIds = listOf(2, 99)), "de")
        assertEquals(setOf(2), blank.participants)
        assertEquals("38,50", blank.amount)
        assertEquals(SplitRules.PERCENTAGE, blank.method)
        assertTrue(SplitRules.defaultsValid(SplitRules.PERCENTAGE, mapOf(1 to "60", 2 to "40"), "en"))
        assertFalse(SplitRules.defaultsValid(SplitRules.PERCENTAGE, mapOf(1 to "60", 2 to "30"), "en"))
        assertTrue(SplitRules.defaultsValid(SplitRules.SHARES, mapOf(1 to "", 2 to ""), "en"))
        assertEquals(2, SplitRules.defaultsConfig(SplitRules.SHARES, mapOf(1 to "1", 2 to "2"), "en").size)
    }

    /** SPLIT-A4: balances from the reader's side, the folded single debt. */
    @Test fun `balance statement`() {
        val rows = listOf(BalanceRow("EUR", 1, "Alex", 2414, "24.14"), BalanceRow("EUR", 2, "Linda", -2414, "-24.14"), BalanceRow("USD", 1, "Alex", -500, "-5.00"))
        val own = SplitRules.ownGroupBalance(rows, 1)
        assertEquals(listOf(MoneyRow("EUR", "24.14")), own.owed)
        assertEquals(listOf(MoneyRow("USD", "5.00")), own.owing)
        val debt = Debt(2, "Linda", 1, "Alex", "EUR", 2414, "24.14")
        assertEquals(debt, SplitRules.foldedDebt(listOf(debt), 1))
        assertNull(SplitRules.foldedDebt(listOf(debt), 3))
        assertNull(SplitRules.foldedDebt(listOf(debt, debt), 1))
        assertEquals("Linda owes you", SplitRules.debtSentence(en, debt, 1))
        assertEquals("You owe Alex", SplitRules.debtSentence(en, debt, 2))
        assertEquals("Linda owes Alex", SplitRules.debtSentence(en, debt, 3))
        assertEquals("", SplitRules.groupTypeLabel(en, group.copy(name = "travel")))
        assertEquals("Travel", SplitRules.groupTypeLabel(en, group))
        assertEquals("No category", SplitRules.categoryLabel(en, "none", emptyList()))
        assertEquals(en.t("budget.categoryFood"), SplitRules.categoryLabel(en, "food", emptyList()))
        assertEquals("Pets", SplitRules.categoryLabel(en, "pets_custom", listOf(BudgetCategory("pets_custom", "Pets"))))
        assertEquals(listOf("food", "none"), SplitRules.groupCategories(Balances(budgetCategoryTotals = listOf(CategoryTotal("food"), CategoryTotal(null), CategoryTotal("food", "USD")))))
    }

    /** SPLIT-A5: `nextRunNotBefore` with the anchor day, and what an edit sends. */
    @Test fun `recurring rules`() {
        val (next, skipped) = SplitRules.nextRunNotBefore(LocalDate.of(2026, 1, 31), "monthly", LocalDate.of(2026, 4, 1), 31)
        assertEquals(LocalDate.of(2026, 4, 30), next)
        assertEquals(3, skipped)
        assertEquals(LocalDate.of(2026, 3, 31), SplitRules.addInterval(LocalDate.of(2026, 2, 28), "monthly", 31))
        assertEquals(LocalDate.of(2026, 2, 7), SplitRules.addInterval(LocalDate.of(2026, 1, 31), "weekly", 31))
        assertEquals(0, SplitRules.nextRunNotBefore(LocalDate.of(2026, 5, 1), "monthly", LocalDate.of(2026, 4, 1), 1).second)
        val form = ExpenseForm(amount = "1450", currency = "EUR", title = "Rent", date = "2026-11-01", payerId = 1, participants = setOf(1, 2), frequency = "monthly")
        val people = listOf(alex, linda)
        val shown = SplitFormRules.recurringBody(form, people, "en")
        val now = SplitFormRules.recurringBody(form.copy(title = "Rent share"), people, "en")
        assertEquals(setOf("title"), SplitRules.changedRecurringFields(shown, now, listOf(1, 2)).keys)
        val amount = SplitFormRules.recurringBody(form.copy(amount = "1500"), people, "en")
        assertEquals(setOf("amount", "currency"), SplitRules.changedRecurringFields(shown, amount, listOf(1, 2)).keys)
        assertEquals(setOf("split_method", "participants", "splits"), SplitRules.changedRecurringFields(shown, shown, listOf(1, 2, 3)).keys)
        assertEquals(LocalDate.of(2026, 11, 1) to 1, SplitFormRules.pastDate(form.copy(date = "2026-10-01"), LocalDate.of(2026, 11, 1)))
        assertNull(SplitFormRules.pastDate(form, LocalDate.of(2026, 11, 1)))
    }

    /** SPLIT-A6: the answers decode; activity metadata and a series' stored input. */
    @Test fun `answers decode`() {
        val activity = ApiJson.decodeFromString(ListSerializer(Activity.serializer()), """[{"id":3,"type":"payment_registered","entity_type":"settlement","entity_id":5,
            "actor_name":"Linda","created_at":"2026-11-01T10:00:00Z","metadata":{"amount":"50.00","currency":"EUR"},
            "settlement":{"id":5,"payer_name":"Linda","payee_name":"Alex","amount":"50.00","currency":"EUR","reversed_at":null,"can_reverse":true}}]""")
        assertTrue(activity[0].settlement!!.canReverse)
        val r = ApiJson.decodeFromString(RecurringExpense.serializer(), """{"id":1,"title":"Rent","amount":"1450.00","currency":"EUR","split_method":"percentage",
            "participants":[1,2],"splits":[{"user_id":1,"percentage":"60"},{"user_id":2,"percentage":40}],"can_edit":true,"missed_count":0}""")
        assertEquals(mapOf(1 to "60", 2 to "40"), SplitRules.recurringSplitValues(r, "en"))
        val s = SplitStore.State(data = SplitData(expenses = listOf(Expense(id = 9, title = "Dinner"))))
        val comment = Activity(id = 1, type = "comment_added", entityType = "expense", entityId = 9, metadata = JsonObject(emptyMap()))
        assertEquals("Dinner", activityDetail(comment, s, en))
        val created = Activity(id = 2, type = "expense_created", metadata = JsonObject(mapOf("title" to JsonPrimitive("Shoes"), "amount" to JsonPrimitive("38.50"), "currency" to JsonPrimitive("EUR"))))
        assertTrue(activityDetail(created, s, en)!!.startsWith("Shoes · "))
        assertEquals("Payment registered", activityTitle(en, "payment_registered"))
        assertEquals("new_type", activityTitle(en, "new_type"))
    }

    /** SPLIT-A7: errors without a reason get the app's sentence, never the server's text. */
    @Test fun `errors`() {
        assertEquals("android.splitexpenses.groupArchived", SplitErrors.key(ApiProblem.Http(409, "group_archived", "x")))
        assertEquals("android.splitexpenses.conflict", SplitErrors.key(ApiProblem.Http(409, null, "Groups with financial history must be archived instead of deleted.")))
        assertEquals("common.errorNoPermission", SplitErrors.key(ApiProblem.Http(403, null, "Not authorized.")))
        assertEquals("common.unexpectedError", SplitErrors.key(ApiProblem.Http(400, null, "Exact splits must add up.")))
        assertEquals("common.errorOfflineMutation", SplitErrors.key(ApiProblem.Network(java.io.IOException())))
        assertTrue(SplitFormRules.guestErrors("", "short", "x", en).keys.containsAll(listOf("name", "password", "birth")))
        assertEquals(3, SplitRules.minorUnit("KWD"))
        assertEquals(0, SplitRules.minorUnit("JPY"))
    }

    /** SPLIT-A8: hashtags in title or notes; the item opens its group. */
    @Test fun `tag source finds hashtags`() {
        val items = SplitExpensesTagSource.items(group, listOf(
            Expense(id = 1, title = "Pizza #italy", description = "with #friends"),
            Expense(id = 2, title = "Fuel"),
        ))
        assertEquals(1, items.size)
        assertEquals(setOf("italy", "friends"), items[0].tags)
        assertEquals("Trip", items[0].subtitle)
        assertEquals(SplitExpensesRoutes.group(7), items[0].route)
        assertTrue(items[0].route!!.startsWith("budget/split"))
    }
}
