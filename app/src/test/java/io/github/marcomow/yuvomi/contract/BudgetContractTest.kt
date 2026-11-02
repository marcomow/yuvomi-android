package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.budget.AccountForm
import io.github.marcomow.yuvomi.feature.budget.BudgetApi
import io.github.marcomow.yuvomi.feature.budget.BudgetEntry
import io.github.marcomow.yuvomi.feature.budget.BudgetLedger
import io.github.marcomow.yuvomi.feature.budget.Money
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * docs/spec/budget.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). Every
 * test makes its own fixtures with unique names and removes them again. Signed in as alex.
 */
class BudgetContractTest : ContractTestBase() {
    private val money = Money(Locale.ENGLISH, "EUR")
    private val shared = BudgetApi.View(personal = false, scope = "mine")
    private val month: String = LocalDate.now().toString().take(7)
    private val today: String = LocalDate.now().toString()

    private fun alex(): BudgetApi = BudgetApi(signedIn("alex").first)

    private suspend fun expectHttp(status: Int, block: suspend () -> Unit): ApiProblem.Http {
        try {
            block()
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            return e
        }
        fail("expected HTTP $status")
        error("unreachable")
    }

    private suspend fun BudgetApi.expenseCategory(): String = meta().expenseCategories.first().key

    private suspend fun BudgetApi.newEntry(title: String, amount: Double = -12.5, extra: Map<String, Any> = emptyMap()): BudgetEntry {
        val category = expenseCategory()
        val sub = meta().subcategories[category].orEmpty().firstOrNull()?.key ?: ""
        val body = buildJsonObject {
            put("title", title)
            put("amount", amount)
            put("category", category)
            put("subcategory", sub)
            put("date", today)
            extra.forEach { (k, v) -> put(k, BudgetApi.nullable(v)) }
        }
        return create(body, YuvomiClient.newIdempotencyKey())
    }

    /** BUDGET-A1 (GET /preferences, GET /family/members, GET /budget/meta). */
    @Test fun `the household's currency, members and categories decode`() = runBlocking<Unit> {
        val api = alex()
        assertTrue(api.preferences().currency!!.length == 3)
        assertTrue(api.members().isNotEmpty())
        val meta = api.meta()
        assertTrue(meta.expenseCategories.isNotEmpty() && meta.incomeCategories.isNotEmpty())
    }

    /** BUDGET-A13 (GET /budget, GET /budget/summary, GET /budget/loans). */
    @Test fun `the month, its summary and the loans decode`() = runBlocking<Unit> {
        val api = alex()
        val entries = api.entries(month, shared)
        val summary = BudgetApi.data(api.summaryRaw(month, shared))!!
        assertNotNull(summary)
        assertTrue(entries.all { it.date.startsWith(month) || it.expected })
        api.loans()
    }

    /** BUDGET-A18, A20 (POST, PUT, DELETE /budget/{id}). */
    @Test fun `an entry is created, edited and deleted`() = runBlocking<Unit> {
        val api = alex()
        val title = unique("Contract entry")
        val e = api.newEntry(title)
        assertEquals(-12.5, e.amount, 0.0)
        val edited = api.update(e.id!!, buildJsonObject { put("title", "$title 2"); put("amount", -13.0); put("category", e.category); put("subcategory", e.subcategory ?: ""); put("date", e.date) })
        assertEquals("$title 2", edited.title)
        assertTrue(api.search(title, shared).entries.any { it.id == e.id })
        api.delete(e.id!!)
        expectHttp(404) { api.delete(e.id!!) }
    }

    /** BUDGET-A8: a refusal carries its reason. */
    @Test fun `an invalid amount is refused with its reason`() = runBlocking<Unit> {
        val api = alex()
        val e = expectHttp(400) { api.create(buildJsonObject { put("title", "x"); put("amount", "abc"); put("date", today) }, YuvomiClient.newIdempotencyKey()) }
        assertEquals("entry_amount_invalid", e.reason)
    }

    /** BUDGET-A19, A21 (PUT and DELETE /budget/{id}/series, PATCH /budget/{id}/confirm). */
    @Test fun `a series with confirmation is edited, confirmed and deleted`() = runBlocking<Unit> {
        val api = alex()
        val title = unique("Contract series")
        val anchor = api.newEntry(title, extra = mapOf("is_recurring" to 1, "recurrence_interval" to "monthly", "recurrence_confirm" to 1))
        api.updateSeries(anchor.id!!, buildJsonObject { put("title", "$title!") })
        val next = BudgetLedger.addMonths(month, 1)
        val occurrence = api.entries(next, shared).firstOrNull { it.recurrenceParentId == anchor.id }
        if (occurrence != null && occurrence.isPending) api.confirm(occurrence.id!!, 12.5, null)
        api.deleteSeries(anchor.id!!)
    }

    /** BUDGET-A14 (GET /budget/export). */
    @Test fun `the CSV export has its header`() = runBlocking<Unit> {
        val csv = String(alex().export(listOf("month" to month)))
        assertTrue(csv.contains("Date,Title,Amount,Category"))
    }

    /** Category manager (GET/POST/PUT/DELETE /budget/categories, subcategories, reorders). */
    @Test fun `categories and subcategories are managed`() = runBlocking<Unit> {
        val api = alex()
        val name = unique("Cat")
        val cat = api.createCategory(name, "expense", YuvomiClient.newIdempotencyKey())
        try {
            expectHttp(409) { api.createCategory(name, "expense", YuvomiClient.newIdempotencyKey()) }.also { assertEquals("category_exists", it.reason) }
            api.renameCategory(cat.key, "$name 2")
            val sub = api.createSubcategory(cat.key, "Sub", YuvomiClient.newIdempotencyKey())
            val sub2 = api.createSubcategory(cat.key, "Sub 2", YuvomiClient.newIdempotencyKey())
            api.renameSubcategory(cat.key, sub.key, "Sub one")
            api.reorderSubcategories(cat.key, listOf(sub2.key, sub.key))
            api.deleteSubcategory(cat.key, sub2.key)
            val listed = api.categories("en")
            assertTrue(listed.any { it.key == cat.key && it.subcategories.any { s -> s.key == sub.key } })
            api.reorderCategories("expense", listed.filter { it.type == "expense" }.map { it.key })
        } finally {
            api.deleteCategory(cat.key)
        }
    }

    /** BUDGET-A11 (accounts and transfers). */
    @Test fun `accounts and a transfer between them`() = runBlocking<Unit> {
        val api = alex()
        val a = api.createAccount(AccountForm(name = unique("Acc A")).body(money), YuvomiClient.newIdempotencyKey())
        val b = api.createAccount(AccountForm(name = unique("Acc B"), balance = "10").body(money), YuvomiClient.newIdempotencyKey())
        try {
            assertTrue(api.accounts().accounts.any { it.id == a.id })
            api.updateAccount(b.id, buildJsonObject { put("archived", true) })
            api.updateAccount(b.id, buildJsonObject { put("archived", false) })
            val cat = api.expenseCategory()
            val t = api.createTransfer(buildJsonObject {
                put("title", "T"); put("amount", 5.0); put("category", cat)
                put("subcategory", api.meta().subcategories[cat].orEmpty().firstOrNull()?.key ?: "")
                put("date", today); put("from_account_id", a.id); put("to_account_id", b.id)
            }, YuvomiClient.newIdempotencyKey())
            assertEquals(b.id, api.transfer(t.id).toAccountId)
            api.updateTransfer(t.id, buildJsonObject { put("amount", 6.0) }, series = false)
            expectHttp(400) { api.updateTransfer(t.id, buildJsonObject { put("title", "x") }, series = true) }
            api.delete(t.id)
        } finally {
            api.deleteAccount(a.id)
            api.deleteAccount(b.id)
        }
    }

    /** BUDGET-A9 (loans, preview, payments). */
    @Test fun `a loan is previewed, created, paid and deleted`() = runBlocking<Unit> {
        val api = alex()
        val preview = api.previewLoan(buildJsonObject { put("interest_mode", "fixed"); put("principal", 10000); put("fixed_rate", 3); put("initial_repayment_rate", 2) })
        assertTrue(preview.ok && preview.totalMonths > 0)
        val title = unique("Loan")
        api.createLoan(buildJsonObject {
            put("borrower", "Bank"); put("title", title); put("start_month", month); put("interest_mode", "none")
            put("total_amount", 300); put("installment_count", 3); put("direction", "borrowed")
        }, YuvomiClient.newIdempotencyKey())
        val loan = api.loans().loans.single { it.title == title }
        try {
            api.updateLoan(loan.id, buildJsonObject {
                put("borrower", "Bank"); put("title", title); put("start_month", month); put("interest_mode", "none")
                put("total_amount", 300); put("installment_count", 3); put("notes", "n")
            })
            val payment = api.payLoan(loan.id, 1, 100.0, today, YuvomiClient.newIdempotencyKey())
            assertNotNull(payment)
            assertTrue(api.loanEntries(loan.id).any { it.loanPaymentId == payment })
            api.deleteLoanPayment(loan.id, payment!!)
        } finally {
            api.deleteLoan(loan.id)
        }
    }

    /** BUDGET-A10 (plans, stats). */
    @Test fun `plan and statistics`() = runBlocking<Unit> {
        val api = alex()
        val cat = api.expenseCategory()
        val before = api.plans(month, shared).plans.firstOrNull { it.category == cat }?.planned
        api.setPlan(cat, 123.0)
        assertEquals(123.0, api.plans(month, shared).plans.single { it.category == cat }.planned, 0.0)
        if (before != null) api.setPlan(cat, before) else api.deletePlan(cat)
        val stats = api.stats("month", today, shared)
        assertEquals("month", stats.range)
        assertTrue(stats.series.isNotEmpty())
    }

    /** Receipts (GET/POST /documents, preview and download). */
    @Test fun `a receipt is uploaded, listed and fetched`() = runBlocking<Unit> {
        val (client, _) = signedIn("alex")
        val api = BudgetApi(client)
        val text = "data:text/plain;base64," + java.util.Base64.getEncoder().encodeToString("receipt".toByteArray())
        val doc = api.uploadReceipt(unique("Receipt"), "r.txt", text, "Receipts", YuvomiClient.newIdempotencyKey())
        try {
            assertTrue(api.documents().any { it.id == doc.id })
            assertTrue(api.receipt(doc.id, preview = false).isNotEmpty())
            runCatching { api.receipt(doc.id, preview = true) }
            val e = api.newEntry(unique("With receipt"), extra = mapOf("attachment_document_ids" to BudgetApi.ids(listOf(doc.id))))
            assertTrue(api.entries(month, shared).single { it.id == e.id }.attachments.orEmpty().any { it.documentId == doc.id })
            api.delete(e.id!!)
        } finally {
            raw(client, "DELETE", "/api/v1/documents/${doc.id}", "", tolerate = true)
        }
    }
}
