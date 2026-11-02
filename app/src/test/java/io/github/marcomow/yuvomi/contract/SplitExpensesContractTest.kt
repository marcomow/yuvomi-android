package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.splitexpenses.ExpenseForm
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitApi
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitFormRules
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitPerson
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * docs/spec/splitexpenses.md against upstream's image at the pinned version, seeded by `seed-demo.js`.
 * Tests work in groups of their own with unique names. A group with history can only be archived,
 * so those stay behind archived; empty ones are deleted.
 */
class SplitExpensesContractTest : ContractTestBase() {
    private fun expectHttp(status: Int, reason: String? = null, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            if (reason != null) assertEquals(reason, e.reason)
        }
    }

    private class Ctx(val api: SplitApi, val me: Int)

    private fun alex(): Ctx = signedIn("alex").let { (c, p) -> Ctx(SplitApi(c), p.user.id) }

    private fun lindaId(ctx: Ctx): Int = runBlocking { ctx.api.familyMembers().first { it.displayName.startsWith("Linda") }.uid }

    private fun groupBody(name: String) = SplitFormRules.groupBody(name, "contract", "travel", "EUR", null, "en")

    private fun expenseBody(people: List<SplitPerson>, payer: Int, method: String = SplitRules.EQUAL, values: Map<Int, String> = emptyMap(), amount: String = "30.00") =
        SplitFormRules.expenseBody(
            ExpenseForm(amount = amount, currency = "EUR", title = unique("Exp"), date = LocalDate.now().toString(), payerId = payer, method = method,
                participants = people.map { it.uid }.toSet(), values = values),
            people, "en",
        )

    /** SPLIT-A12: meta, dashboard, the seeded groups and the budget's categories decode. */
    @Test fun `SPLIT-A12 the seeded tab decodes`() = runBlocking {
        val ctx = alex()
        val meta = ctx.api.meta()
        assertTrue("EUR" in meta.currencies)
        assertTrue(SplitRules.EQUAL in meta.splitMethods)
        assertNotNull(ctx.api.dashboard())
        val groups = ctx.api.groups("active", "")
        assertTrue(groups.size >= 2)
        val household = groups.first { it.type == "household" }
        val expenses = ctx.api.expenses(household.id, "", 30, null)
        assertTrue(expenses.items.isNotEmpty())
        assertTrue(expenses.items.all { it.splits.isNotEmpty() })
        val balances = ctx.api.balances(household.id)
        assertTrue(balances.budgetCategoryTotals.isNotEmpty())
        val activity = ctx.api.activity(household.id, 12, null)
        assertTrue(activity.items.isNotEmpty())
        assertTrue(ctx.api.recurring(household.id).isNotEmpty())
        assertTrue(ctx.api.members(household.id).size >= 2)
        assertTrue(ctx.api.budgetCategories().any { it.key == "food" })
        val filtered = ctx.api.expenses(household.id, "food", 30, null).items
        assertTrue(filtered.all { it.budgetCategory == "food" })
        val firstPage = ctx.api.expenses(household.id, "", 1, null)
        if (firstPage.cursor != null) assertTrue(ctx.api.expenses(household.id, "", 1, firstPage.cursor).items.none { it.id == firstPage.items[0].id })
    }

    /** SPLIT-A13: a group: create, edit, members and candidates, then delete while empty. */
    @Test fun `SPLIT-A13 group and members`() = runBlocking {
        val ctx = alex()
        val linda = lindaId(ctx)
        val group = ctx.api.createGroup(groupBody(unique("Group")), YuvomiClient.newIdempotencyKey())
        try {
            assertEquals("owner", group.memberRole)
            assertEquals("travel", group.type)
            assertTrue(ctx.api.candidates(group.id).any { it.userId == linda && !it.inGroup })
            ctx.api.addMember(group.id, linda, null, "guest")
            assertTrue(ctx.api.members(group.id).any { it.uid == linda && it.role == "guest" })
            val patched = ctx.api.updateGroup(group.id, SplitFormRules.groupBody(group.name, "x", "event", "USD",
                SplitRules.PERCENTAGE to mapOf(ctx.me to "60", linda to "40"), "en"))
            assertEquals("USD", patched.defaultCurrency)
            assertEquals(mapOf(ctx.me to "60", linda to "40"), SplitRules.defaultSplitValues(patched))
            ctx.api.removeMember(group.id, linda)
            expectHttp(400) { ctx.api.removeMember(group.id, ctx.me) }
            assertTrue(ctx.api.groups("active", group.name).any { it.id == group.id })
        } finally {
            ctx.api.deleteGroup(group.id)
        }
        expectHttp(404) { ctx.api.members(group.id) }
    }

    /** SPLIT-A14: expenses with exact splits, balances, a settlement and its reversal, archive and restore. */
    @Test fun `SPLIT-A14 expenses, balances and settlements`() = runBlocking {
        val ctx = alex()
        val linda = lindaId(ctx)
        val group = ctx.api.createGroup(groupBody(unique("Trip")), YuvomiClient.newIdempotencyKey())
        ctx.api.addMember(group.id, linda, null, "admin")
        val people = ctx.api.members(group.id)
        val created = ctx.api.createExpense(group.id, expenseBody(people, ctx.me, SplitRules.EXACT, mapOf(ctx.me to "10.00", linda to "20.00")), YuvomiClient.newIdempotencyKey())
        assertEquals("30.00", created.amount)
        assertEquals(setOf(1000L, 2000L), created.splits.map { it.amountMinor }.toSet())
        expectHttp(400) { ctx.api.createExpense(group.id, expenseBody(people, ctx.me, SplitRules.EXACT, mapOf(ctx.me to "1.00", linda to "2.00")), YuvomiClient.newIdempotencyKey()) }
        val updated = ctx.api.updateExpense(created.id, expenseBody(people, ctx.me, SplitRules.SHARES, mapOf(ctx.me to "1", linda to "2")))
        assertEquals(SplitRules.SHARES, updated.splitMethod)
        val debts = ctx.api.balances(group.id).simplifiedDebts
        assertEquals(1, debts.size)
        assertEquals(linda, debts[0].fromUserId)
        assertEquals("20.00", debts[0].amount)
        ctx.api.settle(group.id, SplitFormRules.settlementBody(linda, ctx.me, "20.00", "EUR", "contract", "en"), YuvomiClient.newIdempotencyKey())
        assertTrue(ctx.api.balances(group.id).simplifiedDebts.isEmpty())
        val payment = ctx.api.activity(group.id, 12, null).items.first { it.type == "payment_registered" }.settlement!!
        assertTrue(payment.canReverse)
        ctx.api.reverse(group.id, payment.id)
        expectHttp(409) { ctx.api.reverse(group.id, payment.id) }
        assertEquals(1, ctx.api.balances(group.id).simplifiedDebts.size)
        ctx.api.deleteExpense(created.id)
        expectHttp(404) { ctx.api.deleteExpense(created.id) }
        expectHttp(409) { ctx.api.deleteGroup(group.id) }
        ctx.api.archive(group.id)
        assertTrue(ctx.api.groups("archived", "").any { it.id == group.id })
        expectHttp(409, "group_archived") { ctx.api.createExpense(group.id, expenseBody(people, ctx.me), YuvomiClient.newIdempotencyKey()) }
        ctx.api.unarchive(group.id)
        assertTrue(ctx.api.groups("active", "").any { it.id == group.id })
        ctx.api.archive(group.id)
    }

    /** SPLIT-A15: a recurring expense: create, edit only what changed, pause, resume, delete. */
    @Test fun `SPLIT-A15 recurring expenses`() = runBlocking {
        val ctx = alex()
        val group = ctx.api.createGroup(groupBody(unique("Series")), YuvomiClient.newIdempotencyKey())
        try {
            val people = ctx.api.members(group.id)
            val form = ExpenseForm(amount = "50.00", currency = "EUR", title = unique("Rent"), date = LocalDate.now().plusDays(3).toString(), payerId = ctx.me,
                participants = people.map { it.uid }.toSet(), frequency = "monthly")
            val created = ctx.api.createRecurring(group.id, SplitFormRules.recurringBody(form, people, "en"), YuvomiClient.newIdempotencyKey())
            assertTrue(created.canEdit)
            assertEquals(form.date, created.nextRunDate)
            val shown = SplitFormRules.recurringBody(form, people, "en")
            val changed = SplitRules.changedRecurringFields(shown, SplitFormRules.recurringBody(form.copy(title = "Renamed"), people, "en"), created.participants)
            assertEquals("Renamed", ctx.api.updateRecurring(created.id, changed).title)
            assertNotNull(ctx.api.pause(created.id, null).pausedAt)
            assertEquals(null, ctx.api.pause(created.id, "skip").pausedAt)
            expectHttp(400, "invalid_missed") { ctx.api.pause(created.id, "later") }
            ctx.api.deleteRecurring(created.id)
            expectHttp(404) { ctx.api.deleteRecurring(created.id) }
        } finally {
            ctx.api.deleteGroup(group.id)
        }
    }

    /** SPLIT-A16: a guest with their own sign-in joins the group; a short password is refused. */
    @Test fun `SPLIT-A16 guests`() = runBlocking {
        val ctx = alex()
        val group = ctx.api.createGroup(groupBody(unique("Guests")), YuvomiClient.newIdempotencyKey())
        try {
            expectHttp(400) { ctx.api.createGuest(group.id, SplitFormRules.guestBody("Guest", "", "short", "", "", ""), YuvomiClient.newIdempotencyKey()) }
            val name = unique("Guest")
            val guest = ctx.api.createGuest(group.id, SplitFormRules.guestBody(name, "", "longenough1", "", "", ""), YuvomiClient.newIdempotencyKey())
            assertEquals(name, guest.displayName)
            assertTrue(ctx.api.members(group.id).any { it.uid == guest.uid && it.role == "guest" })
            ctx.api.removeMember(group.id, guest.uid)
        } finally {
            ctx.api.deleteGroup(group.id)
        }
    }
}
