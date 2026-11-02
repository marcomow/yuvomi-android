package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /split-expenses/meta` (`data`). */
@Serializable
data class SplitMeta(
    @SerialName("group_types") val groupTypes: List<String> = listOf("household", "couple", "travel", "event", "shopping", "general"),
    @SerialName("split_methods") val splitMethods: List<String> = listOf("equal", "exact", "percentage", "shares"),
    val currencies: List<String> = emptyList(),
    val frequencies: List<String> = listOf("weekly", "monthly", "yearly"),
    @SerialName("default_currency") val defaultCurrency: String = "EUR",
)

/** A sum per currency (`total_owed`, `total_owing`); `amount` is a decimal string. */
@Serializable
data class MoneyRow(val currency: String = "EUR", val amount: String = "0")

/** `GET /split-expenses/dashboard` (`data`): the member's sums over every active group. */
@Serializable
data class SplitDashboard(
    @SerialName("total_owed") val totalOwed: List<MoneyRow> = emptyList(),
    @SerialName("total_owing") val totalOwing: List<MoneyRow> = emptyList(),
)

/** A group as `groupSelectWhere()` reads it. */
@Serializable
data class ExpenseGroup(
    val id: Int,
    val name: String = "",
    val description: String? = null,
    val type: String = "general",
    @SerialName("default_currency") val defaultCurrency: String = "EUR",
    @SerialName("default_split_method") val defaultSplitMethod: String = "equal",
    @SerialName("default_split_config") val defaultSplitConfig: String? = null,
    val status: String = "active",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("member_role") val memberRole: String? = null,
    @SerialName("created_by") val createdBy: Int? = null,
)

/** A person of a group (`GET /groups/:id/members`) or of the household (`GET /family/members`). */
@Serializable
data class SplitPerson(
    val id: Int? = null,
    @SerialName("user_id") val userId: Int? = null,
    val role: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
) {
    val uid: Int get() = userId ?: id ?: 0
}

/** `GET /groups/:id/member-candidates`: household members, guests and (with contacts write) contacts without an account. */
@Serializable
data class MemberCandidate(
    val source: String = "user",
    @SerialName("user_id") val userId: Int? = null,
    @SerialName("contact_id") val contactId: Int? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("in_group") val inGroup: FlexBool = false,
    @SerialName("group_role") val groupRole: String? = null,
) {
    val key: String get() = if (source == "contact") "contact:$contactId" else "user:$userId"
}

@Serializable
data class ExpenseSplit(
    @SerialName("user_id") val userId: Int,
    @SerialName("amount_minor") val amountMinor: Long = 0,
    val amount: String = "0",
    val currency: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class Attachment(
    @SerialName("document_id") val documentId: Int? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
data class Expense(
    val id: Int,
    @SerialName("group_id") val groupId: Int = 0,
    val title: String = "",
    val description: String? = null,
    val amount: String = "0",
    val currency: String = "EUR",
    @SerialName("amount_minor") val amountMinor: Long = 0,
    @SerialName("payer_id") val payerId: Int? = null,
    @SerialName("payer_name") val payerName: String? = null,
    @SerialName("budget_category") val budgetCategory: String? = null,
    @SerialName("split_method") val splitMethod: String = "equal",
    @SerialName("expense_date") val expenseDate: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("created_by") val createdBy: Int? = null,
    @SerialName("group_name") val groupName: String? = null,
    val splits: List<ExpenseSplit> = emptyList(),
    val attachments: List<Attachment>? = null,
)

@Serializable
data class ExpenseCursor(
    @SerialName("before_date") val beforeDate: String? = null,
    @SerialName("before_at") val beforeAt: String = "",
    @SerialName("before_id") val beforeId: Int = 0,
)

@Serializable
data class Pagination(@SerialName("has_more") val hasMore: Boolean = false, @SerialName("next_cursor") val nextCursor: ExpenseCursor? = null) {
    val cursor: ExpenseCursor? get() = if (hasMore) nextCursor else null
}

@Serializable
data class BalanceRow(
    val currency: String = "EUR",
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("net_minor") val netMinor: Long = 0,
    val net: String = "0",
)

@Serializable
data class Debt(
    @SerialName("from_user_id") val fromUserId: Int,
    @SerialName("from_name") val fromName: String? = null,
    @SerialName("to_user_id") val toUserId: Int,
    @SerialName("to_name") val toName: String? = null,
    val currency: String = "EUR",
    @SerialName("amount_minor") val amountMinor: Long = 0,
    val amount: String = "0",
)

@Serializable
data class MemberTotal(
    val currency: String = "EUR",
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("display_name") val displayName: String? = null,
    val paid: String = "0",
    val share: String = "0",
)

@Serializable
data class CategoryTotal(@SerialName("budget_category") val budgetCategory: String? = null, val currency: String = "EUR", val total: String = "0")

/** `GET /groups/:id/balances` (`data`). */
@Serializable
data class Balances(
    val balances: List<BalanceRow> = emptyList(),
    @SerialName("simplified_debts") val simplifiedDebts: List<Debt> = emptyList(),
    @SerialName("member_totals") val memberTotals: List<MemberTotal> = emptyList(),
    @SerialName("budget_category_totals") val budgetCategoryTotals: List<CategoryTotal> = emptyList(),
)

@Serializable
data class ActivitySettlement(
    val id: Int,
    @SerialName("payer_name") val payerName: String? = null,
    @SerialName("payee_name") val payeeName: String? = null,
    val amount: String = "0",
    val currency: String = "EUR",
    @SerialName("reversed_at") val reversedAt: String? = null,
    @SerialName("can_reverse") val canReverse: Boolean = false,
)

@Serializable
data class ActivityExpense(val id: Int, @SerialName("deleted_at") val deletedAt: String? = null)

@Serializable
data class Activity(
    val id: Int,
    val type: String = "",
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: Int? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val metadata: JsonObject? = null,
    val settlement: ActivitySettlement? = null,
    val expense: ActivityExpense? = null,
)

/** A stored split input of a series (`split_snapshot.splits`): what was typed, not the result. */
@Serializable
data class SplitInput(
    @SerialName("user_id") val userId: Int,
    val amount: String? = null,
    val percentage: String? = null,
    val shares: Int? = null,
)

/** A recurring expense as `recurringForViewer()` answers it. */
@Serializable
data class RecurringExpense(
    val id: Int,
    @SerialName("group_id") val groupId: Int = 0,
    val title: String = "",
    val description: String? = null,
    val amount: String = "0",
    val currency: String = "EUR",
    @SerialName("payer_id") val payerId: Int? = null,
    @SerialName("payer_name") val payerName: String? = null,
    @SerialName("budget_category") val budgetCategory: String? = null,
    @SerialName("split_method") val splitMethod: String = "equal",
    val frequency: String = "monthly",
    @SerialName("next_run_date") val nextRunDate: String = "",
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("failed_at") val failedAt: String? = null,
    val participants: List<Int> = emptyList(),
    val splits: List<SplitInput> = emptyList(),
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("missed_count") val missedCount: Int = 0,
    @SerialName("resume_date") val resumeDate: String? = null,
) {
    val paused: Boolean get() = pausedAt != null
    /** `recurringIsFailing()`: the run cannot book a running series. */
    val failing: Boolean get() = failedAt != null && pausedAt == null
}

/** An expense category of the budget (`GET /budget/meta`, `expenseCategories`). */
@Serializable
data class BudgetCategory(val key: String, val name: String = "")

@Serializable
data class BudgetMeta(val expenseCategories: List<BudgetCategory> = emptyList())
