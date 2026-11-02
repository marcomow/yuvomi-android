package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One person on an entry (`responsible_users`, #1057). */
@Serializable
data class BudgetPerson(
    val id: Int,
    @SerialName("display_name") val displayName: String? = null,
    val color: String? = null,
)

/** A receipt: a document of the Documents module linked to an entry (#583). */
@Serializable
data class BudgetAttachment(
    @SerialName("document_id") val documentId: Int? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: originalName.orEmpty()
}

/**
 * A row of `GET /budget` (and of `GET /budget?q=`, `GET /budget?loan_id=`). Masked rows of
 * someone else's `shared_amount` entry come without title and category (`details_hidden`); an
 * expected subscription payment of a later month has no id (`expected`, #1844).
 */
@Serializable
data class BudgetEntry(
    val id: Int? = null,
    val title: String = "",
    val amount: Double = 0.0,
    val category: String = "",
    val subcategory: String? = null,
    val date: String = "",
    @SerialName("is_recurring") val isRecurring: FlexBool = false,
    @SerialName("recurrence_parent_id") val recurrenceParentId: Int? = null,
    @SerialName("recurrence_interval") val recurrenceInterval: String? = null,
    @SerialName("recurrence_interval_count") val recurrenceIntervalCount: Int? = null,
    @SerialName("recurrence_virtual") val recurrenceVirtual: FlexBool = false,
    @SerialName("recurrence_confirm") val recurrenceConfirm: FlexBool = false,
    @SerialName("recurrence_full_amount") val recurrenceFullAmount: Double? = null,
    @SerialName("is_pending") val isPending: FlexBool = false,
    @SerialName("account_id") val accountId: Int? = null,
    @SerialName("transfer_entry_id") val transferEntryId: Int? = null,
    @SerialName("transfer_account_id") val transferAccountId: Int? = null,
    val visibility: String? = null,
    @SerialName("details_hidden") val detailsHidden: FlexBool = false,
    @SerialName("responsible_users") val responsibleUsers: List<BudgetPerson> = emptyList(),
    val attachments: List<BudgetAttachment>? = null,
    @SerialName("loan_payment_id") val loanPaymentId: Int? = null,
    @SerialName("loan_id") val loanId: Int? = null,
    val expected: FlexBool = false,
    @SerialName("subscription_id") val subscriptionId: Int? = null,
    @SerialName("owner_id") val ownerId: Int? = null,
    @SerialName("created_by") val createdBy: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isTransfer: Boolean get() = transferEntryId != null
    val fromSubscription: Boolean get() = expected && subscriptionId != null
}

@Serializable
data class CategoryTotal(
    val category: String = "",
    val income: Double? = null,
    val expenses: Double? = null,
    val total: Double = 0.0,
    val transfers: Double? = null,
)

@Serializable
data class PendingTotals(
    val count: Int = 0,
    val income: Double = 0.0,
    val expenses: Double = 0.0,
)

/** `GET /budget/summary`. */
@Serializable
data class BudgetSummary(
    val month: String = "",
    val income: Double = 0.0,
    val expenses: Double = 0.0,
    val balance: Double = 0.0,
    val byCategory: List<CategoryTotal> = emptyList(),
    val transfers: Double = 0.0,
    @SerialName("transfers_in") val transfersIn: Double? = null,
    val pending: PendingTotals? = null,
)

@Serializable
data class BudgetCategory(
    val key: String,
    val name: String = "",
    val type: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val label: String? = null,
    val subcategories: List<BudgetSubcategory> = emptyList(),
)

@Serializable
data class BudgetSubcategory(
    val key: String,
    val name: String = "",
    @SerialName("category_key") val categoryKey: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val label: String? = null,
)

/** `GET /budget/meta`. */
@Serializable
data class BudgetMeta(
    val expenseCategories: List<BudgetCategory> = emptyList(),
    val incomeCategories: List<BudgetCategory> = emptyList(),
    val subcategories: Map<String, List<BudgetSubcategory>> = emptyMap(),
)

@Serializable
data class BudgetAccount(
    val id: Int,
    val name: String = "",
    val type: String = "checking",
    @SerialName("starting_balance") val startingBalance: Double = 0.0,
    val currency: String? = null,
    val color: String? = null,
    val archived: FlexBool = false,
    @SerialName("credit_bank") val creditBank: String? = null,
    @SerialName("credit_limit") val creditLimit: Double? = null,
    @SerialName("current_balance") val currentBalance: Double = 0.0,
    @SerialName("available_limit") val availableLimit: Double? = null,
    @SerialName("entry_count") val entryCount: Int = 0,
)

@Serializable
data class CurrencyAmount(val currency: String = "", val amount: Double = 0.0)

/** `GET /budget/accounts`. */
@Serializable
data class AccountsAnswer(
    val accounts: List<BudgetAccount> = emptyList(),
    @SerialName("net_worth") val netWorth: Double? = null,
    @SerialName("net_worth_approx") val netWorthApprox: FlexBool = false,
    @SerialName("net_worth_rates_at") val netWorthRatesAt: String? = null,
    @SerialName("net_worth_by_currency") val netWorthByCurrency: List<CurrencyAmount> = emptyList(),
)

@Serializable
data class LoanInterest(
    val mode: String = "none",
    val principal: Double = 0.0,
    @SerialName("fixed_rate") val fixedRate: Double? = null,
    @SerialName("initial_repayment_rate") val initialRepaymentRate: Double? = null,
    @SerialName("fixed_period_months") val fixedPeriodMonths: Int? = null,
    @SerialName("followup_rate") val followupRate: Double? = null,
    @SerialName("monthly_payment") val monthlyPayment: Double = 0.0,
    @SerialName("total_interest") val totalInterest: Double = 0.0,
    @SerialName("remaining_principal") val remainingPrincipal: Double = 0.0,
    @SerialName("current_rate") val currentRate: Double? = null,
    @SerialName("remaining_after_binding") val remainingAfterBinding: Double? = null,
    @SerialName("binding_end_month") val bindingEndMonth: String? = null,
)

@Serializable
data class LoanPayment(
    val id: Int,
    @SerialName("installment_number") val installmentNumber: Int = 0,
    val amount: Double = 0.0,
    @SerialName("paid_date") val paidDate: String = "",
    @SerialName("budget_entry_id") val budgetEntryId: Int? = null,
    @SerialName("entry_title") val entryTitle: String? = null,
    @SerialName("entry_category") val entryCategory: String? = null,
)

@Serializable
data class BudgetLoan(
    val id: Int,
    val title: String = "",
    val borrower: String = "",
    @SerialName("total_amount") val totalAmount: Double = 0.0,
    @SerialName("installment_count") val installmentCount: Int = 0,
    @SerialName("start_month") val startMonth: String = "",
    val notes: String? = null,
    val status: String = "active",
    val direction: String = "lent",
    val currency: String? = null,
    @SerialName("exchange_rate") val exchangeRate: Double = 1.0,
    @SerialName("is_foreign_currency") val isForeignCurrency: FlexBool = false,
    @SerialName("installment_amount") val installmentAmount: Double = 0.0,
    @SerialName("paid_amount") val paidAmount: Double = 0.0,
    @SerialName("paid_installments") val paidInstallments: Int = 0,
    @SerialName("remaining_amount") val remainingAmount: Double = 0.0,
    @SerialName("remaining_principal") val remainingPrincipal: Double? = null,
    @SerialName("remaining_installments") val remainingInstallments: Int = 0,
    @SerialName("remaining_installments_forecast") val remainingInstallmentsForecast: Int? = null,
    @SerialName("projected_end_month") val projectedEndMonth: String? = null,
    @SerialName("is_settled") val isSettled: FlexBool = false,
    @SerialName("next_installment_number") val nextInstallmentNumber: Int? = null,
    @SerialName("next_due_month") val nextDueMonth: String? = null,
    @SerialName("next_due_date") val nextDueDate: String? = null,
    @SerialName("due_day") val dueDay: Int? = null,
    @SerialName("account_id") val accountId: Int? = null,
    val interest: LoanInterest? = null,
    val payments: List<LoanPayment> = emptyList(),
) {
    val borrowed: Boolean get() = direction == "borrowed"
}

@Serializable
data class LoanTotals(
    val count: Int = 0,
    @SerialName("has_interest") val hasInterest: FlexBool = false,
    @SerialName("paid_amount") val paidAmount: Double = 0.0,
    @SerialName("remaining_amount") val remainingAmount: Double = 0.0,
    @SerialName("remaining_principal") val remainingPrincipal: Double? = null,
    @SerialName("remaining_installments") val remainingInstallments: Int = 0,
)

@Serializable
data class LoanDirections(val borrowed: LoanTotals? = null, val lent: LoanTotals? = null)

@Serializable
data class LoansSummary(
    @SerialName("active_count") val activeCount: Int = 0,
    val currency: String? = null,
    @SerialName("has_foreign_currency") val hasForeignCurrency: FlexBool = false,
    @SerialName("has_interest") val hasInterest: FlexBool = false,
    @SerialName("paid_amount") val paidAmount: Double = 0.0,
    @SerialName("remaining_amount") val remainingAmount: Double = 0.0,
    @SerialName("remaining_principal") val remainingPrincipal: Double? = null,
    @SerialName("remaining_installments") val remainingInstallments: Int = 0,
    @SerialName("by_direction") val byDirection: LoanDirections? = null,
)

/** `GET /budget/loans`. */
@Serializable
data class LoansAnswer(val loans: List<BudgetLoan> = emptyList(), val summary: LoansSummary = LoansSummary())

/** `POST /budget/loans/preview`. */
@Serializable
data class LoanPreview(
    val ok: FlexBool = false,
    val reason: String? = null,
    val max: Int? = null,
    @SerialName("monthly_payment") val monthlyPayment: Double = 0.0,
    @SerialName("total_months") val totalMonths: Int = 0,
    @SerialName("total_interest") val totalInterest: Double = 0.0,
)

@Serializable
data class StatsTotals(val income: Double = 0.0, val expenses: Double = 0.0, val balance: Double = 0.0)

@Serializable
data class StatsPoint(val period: String = "", val income: Double = 0.0, val expenses: Double = 0.0, val balance: Double = 0.0)

/** `GET /budget/stats`. */
@Serializable
data class BudgetStats(
    val range: String = "month",
    val from: String = "",
    val to: String = "",
    val totals: StatsTotals = StatsTotals(),
    val series: List<StatsPoint> = emptyList(),
    val byCategory: List<CategoryTotal> = emptyList(),
    val comparison: StatsTotals? = null,
    val plans: Map<String, Double> = emptyMap(),
)

@Serializable
data class PlanRow(
    val category: String = "",
    val planned: Double = 0.0,
    val actual: Double = 0.0,
    val remaining: Double = 0.0,
    val ratio: Double = 0.0,
    val over: Boolean? = null,
)

@Serializable
data class SavingsGoal(
    val planned: Double = 0.0,
    val actual: Double = 0.0,
    val remaining: Double = 0.0,
    val ratio: Double = 0.0,
    val met: Boolean? = null,
)

/** `GET /budget/plans`. */
@Serializable
data class BudgetPlans(
    val month: String = "",
    val isCurrentMonth: FlexBool = false,
    val plans: List<PlanRow> = emptyList(),
    val savings: SavingsGoal? = null,
)

/** `GET /budget/transfers/{id}`: the outgoing half, both accounts, and the series when there is one. */
@Serializable
data class BudgetTransfer(
    val id: Int,
    val title: String = "",
    val amount: Double = 0.0,
    val category: String = "",
    val subcategory: String? = null,
    val date: String = "",
    val visibility: String? = null,
    @SerialName("is_recurring") val isRecurring: FlexBool = false,
    @SerialName("recurrence_parent_id") val recurrenceParentId: Int? = null,
    @SerialName("recurrence_interval") val recurrenceInterval: String? = null,
    @SerialName("recurrence_interval_count") val recurrenceIntervalCount: Int? = null,
    @SerialName("recurrence_confirm") val recurrenceConfirm: FlexBool = false,
    @SerialName("from_account_id") val fromAccountId: Int? = null,
    @SerialName("to_account_id") val toAccountId: Int? = null,
    val series: BudgetTransfer? = null,
)

/** The budget fields of `GET /preferences` (and the three that pick the number locale). */
@Serializable
data class BudgetPreferences(
    val currency: String? = null,
    @SerialName("budget_mode") val budgetMode: String? = null,
    val region: String? = null,
    @SerialName("date_format") val dateFormat: String? = null,
    @SerialName("time_format") val timeFormat: String? = null,
)

/** `GET /family/members`. */
@Serializable
data class BudgetMember(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    @SerialName("family_role") val familyRole: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

/** A document of `GET /documents`, for linking an existing receipt. */
@Serializable
data class BudgetDocument(
    val id: Int,
    val name: String = "",
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
