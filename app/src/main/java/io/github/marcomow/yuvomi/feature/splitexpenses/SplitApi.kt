package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.Operation
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.feature.budget.BudgetOperation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A page of a cursor-paged list (`pagination.next_cursor`). */
data class Page<T>(val items: List<T>, val cursor: ExpenseCursor?)

/** docs/spec/splitexpenses.md, "Upstream operations". */
class SplitApi(private val client: YuvomiClient) {
    private fun gid(id: Int) = mapOf("id" to id)

    suspend fun meta(): SplitMeta = client.call(SplitExpensesOperation.META).decodeData(SplitMeta.serializer())
    suspend fun dashboard(): SplitDashboard = client.call(SplitExpensesOperation.DASHBOARD).decodeData(SplitDashboard.serializer())

    suspend fun groups(status: String, q: String): List<ExpenseGroup> =
        client.call(SplitExpensesOperation.GROUPS, query = buildList {
            if (status == "archived") add("status" to "archived")
            if (q.isNotBlank()) add("q" to q.trim())
        }).decodeData(ListSerializer(ExpenseGroup.serializer()))

    suspend fun createGroup(body: JsonObject, key: String): ExpenseGroup =
        client.call(SplitExpensesOperation.GROUP_CREATE, body = body, idempotencyKey = key).decodeData(ExpenseGroup.serializer())

    suspend fun updateGroup(id: Int, body: JsonObject): ExpenseGroup =
        client.call(SplitExpensesOperation.GROUP_UPDATE, gid(id), body = body).decodeData(ExpenseGroup.serializer())

    suspend fun deleteGroup(id: Int) { client.call(SplitExpensesOperation.GROUP_DELETE, gid(id)) }
    suspend fun archive(id: Int) { client.call(SplitExpensesOperation.GROUP_ARCHIVE, gid(id), idempotencyKey = YuvomiClient.newIdempotencyKey()) }
    suspend fun unarchive(id: Int) { client.call(SplitExpensesOperation.GROUP_UNARCHIVE, gid(id), idempotencyKey = YuvomiClient.newIdempotencyKey()) }

    suspend fun members(id: Int): List<SplitPerson> =
        client.call(SplitExpensesOperation.MEMBERS, gid(id)).decodeData(ListSerializer(SplitPerson.serializer()))

    suspend fun familyMembers(): List<SplitPerson> =
        client.call(Operation.FAMILY_MEMBERS).decodeData(ListSerializer(SplitPerson.serializer()))

    suspend fun candidates(id: Int): List<MemberCandidate> =
        client.call(SplitExpensesOperation.MEMBER_CANDIDATES, gid(id)).decodeData(ListSerializer(MemberCandidate.serializer()))

    /** `{ user_id }` or `{ contact_id }`, and a role (`guest`, `admin`). */
    suspend fun addMember(id: Int, userId: Int?, contactId: Int?, role: String) {
        client.call(SplitExpensesOperation.MEMBER_ADD, gid(id), body = buildJsonObject {
            if (userId != null) put("user_id", userId)
            if (contactId != null) put("contact_id", contactId)
            put("role", role)
        }, idempotencyKey = YuvomiClient.newIdempotencyKey())
    }

    suspend fun removeMember(id: Int, userId: Int) {
        client.call(SplitExpensesOperation.MEMBER_REMOVE, mapOf("id" to id, "userId" to userId))
    }

    suspend fun createGuest(id: Int, body: JsonObject, key: String): SplitPerson =
        client.call(SplitExpensesOperation.GUEST_CREATE, gid(id), body = body, idempotencyKey = key).decodeData(SplitPerson.serializer())

    suspend fun expenses(id: Int, category: String, limit: Int, cursor: ExpenseCursor?): Page<Expense> {
        val answer = client.call(SplitExpensesOperation.EXPENSES, gid(id), query = buildList {
            add("limit" to limit.toString())
            if (category.isNotEmpty()) add("budget_category" to category)
            if (cursor != null) {
                add("before_date" to cursor.beforeDate.orEmpty())
                add("before_at" to cursor.beforeAt)
                add("before_id" to cursor.beforeId.toString())
            }
        })
        return Page(answer.decodeData(ListSerializer(Expense.serializer())), pagination(answer).cursor)
    }

    suspend fun createExpense(groupId: Int, body: JsonObject, key: String): Expense =
        client.call(SplitExpensesOperation.EXPENSE_CREATE, gid(groupId), body = body, idempotencyKey = key).decodeData(Expense.serializer())

    suspend fun updateExpense(id: Int, body: JsonObject): Expense =
        client.call(SplitExpensesOperation.EXPENSE_UPDATE, gid(id), body = body).decodeData(Expense.serializer())

    suspend fun deleteExpense(id: Int) { client.call(SplitExpensesOperation.EXPENSE_DELETE, gid(id)) }

    suspend fun balances(id: Int): Balances = client.call(SplitExpensesOperation.BALANCES, gid(id)).decodeData(Balances.serializer())

    suspend fun settle(groupId: Int, body: JsonObject, key: String) {
        client.call(SplitExpensesOperation.SETTLEMENT_CREATE, gid(groupId), body = body, idempotencyKey = key)
    }

    suspend fun reverse(groupId: Int, settlementId: Int) {
        client.call(SplitExpensesOperation.SETTLEMENT_REVERSE, mapOf("id" to groupId, "settlementId" to settlementId), idempotencyKey = YuvomiClient.newIdempotencyKey())
    }

    suspend fun activity(id: Int, limit: Int, cursor: ExpenseCursor?): Page<Activity> {
        val answer = client.call(SplitExpensesOperation.ACTIVITY, gid(id), query = buildList {
            add("limit" to limit.toString())
            if (cursor != null) {
                add("before_at" to cursor.beforeAt)
                add("before_id" to cursor.beforeId.toString())
            }
        })
        return Page(answer.decodeData(ListSerializer(Activity.serializer())), pagination(answer).cursor)
    }

    suspend fun recurring(id: Int): List<RecurringExpense> =
        client.call(SplitExpensesOperation.RECURRING, gid(id)).decodeData(ListSerializer(RecurringExpense.serializer()))

    suspend fun createRecurring(groupId: Int, body: JsonObject, key: String): RecurringExpense =
        client.call(SplitExpensesOperation.RECURRING_CREATE, gid(groupId), body = body, idempotencyKey = key).decodeData(RecurringExpense.serializer())

    suspend fun updateRecurring(id: Int, body: JsonObject): RecurringExpense =
        client.call(SplitExpensesOperation.RECURRING_UPDATE, gid(id), body = body).decodeData(RecurringExpense.serializer())

    suspend fun deleteRecurring(id: Int) { client.call(SplitExpensesOperation.RECURRING_DELETE, gid(id)) }

    /** Pauses a running series; resumes a paused one with `missed: skip | book`. */
    suspend fun pause(id: Int, missed: String?): RecurringExpense =
        client.call(SplitExpensesOperation.RECURRING_PAUSE, gid(id), body = buildJsonObject { if (missed != null) put("missed", missed) }, idempotencyKey = YuvomiClient.newIdempotencyKey())
            .decodeData(RecurringExpense.serializer())

    /** The budget's expense categories; a member without Budget read gets none (`.catch(() => null)`). */
    suspend fun budgetCategories(): List<BudgetCategory> =
        client.call(BudgetOperation.META).decodeData(BudgetMeta.serializer()).expenseCategories

    private fun pagination(answer: JsonElement): Pagination =
        (answer as? JsonObject)?.get("pagination")?.let { runCatching { ApiJson.decodeFromJsonElement(Pagination.serializer(), it) }.getOrNull() } ?: Pagination()
}
