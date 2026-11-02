package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One subscription as `GET /budget/subscriptions` lists it and every write answers it
 * (`server/routes/subscriptions.js`, `decorate()` and `subscriptionsWithConversions()`).
 * `monthly_native`, `monthly_base` and `base_currency` come only with the list; a null
 * `monthly_base` there means the conversion is unavailable.
 */
@Serializable
data class Subscription(
    val id: Int,
    val name: String = "",
    val description: String? = null,
    val amount: Double = 0.0,
    val currency: String = "EUR",
    @SerialName("billing_cycle") val billingCycle: String = "monthly",
    @SerialName("cycle_interval") val cycleInterval: Int = 1,
    @SerialName("next_payment_date") val nextPaymentDate: String = "",
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("payment_method_id") val paymentMethodId: Int? = null,
    @SerialName("reminder_days") val reminderDays: Int = 3,
    @SerialName("reminder_enabled") val reminderEnabled: FlexBool = true,
    val enabled: FlexBool = true,
    @SerialName("website_url") val websiteUrl: String? = null,
    @SerialName("logo_data") val logoData: String? = null,
    @SerialName("brand_color") val brandColor: String? = null,
    val notes: String? = null,
    @SerialName("account_username") val accountUsername: String? = null,
    @SerialName("created_by") val createdBy: Int? = null,
    @SerialName("owner_id") val ownerId: Int? = null,
    val visibility: String? = null,
    @SerialName("end_type") val endType: String = END_NEVER,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("occurrence_count") val occurrenceCount: Int? = null,
    @SerialName("occurrences_done") val occurrencesDone: Int = 0,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("account_id") val accountId: Int? = null,
    @SerialName("budget_entry_id") val budgetEntryId: Int? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("category_color") val categoryColor: String? = null,
    @SerialName("category_label_key") val categoryLabelKey: String? = null,
    @SerialName("payment_method_name") val paymentMethodName: String? = null,
    @SerialName("payment_method_label_key") val paymentMethodLabelKey: String? = null,
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_archived") val accountArchived: FlexBool = false,
    val status: String? = null,
    @SerialName("occurrences_remaining") val occurrencesRemaining: Int? = null,
    @SerialName("monthly_native") val monthlyNative: Double? = null,
    @SerialName("monthly_base") val monthlyBase: Double? = null,
    @SerialName("base_currency") val baseCurrency: String? = null,
) {
    /** `subscriptionStatus()`: completed, active or paused. */
    val effectiveStatus: String get() = status ?: when {
        completedAt != null -> STATUS_COMPLETED
        enabled -> STATUS_ACTIVE
        else -> STATUS_PAUSED
    }
    val isCompleted: Boolean get() = effectiveStatus == STATUS_COMPLETED

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val END_NEVER = "never"
        const val END_ON_DATE = "on_date"
        const val END_AFTER_COUNT = "after_count"
    }
}

/** A row of `summary.by_category` / `summary.by_payment_method`: monthly amount in the base currency. */
@Serializable
data class Breakdown(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("label_key") val labelKey: String? = null,
    val amount: Double = 0.0,
)

@Serializable
data class Summary(
    @SerialName("active_count") val activeCount: Int = 0,
    @SerialName("disabled_count") val disabledCount: Int = 0,
    @SerialName("completed_count") val completedCount: Int = 0,
    @SerialName("monthly_total") val monthlyTotal: Double = 0.0,
    @SerialName("monthly_budget") val monthlyBudget: Double = 0.0,
    @SerialName("remaining_budget") val remainingBudget: Double = 0.0,
    @SerialName("base_currency") val baseCurrency: String = "EUR",
    @SerialName("by_category") val byCategory: List<Breakdown> = emptyList(),
    @SerialName("by_payment_method") val byPaymentMethod: List<Breakdown> = emptyList(),
)

@Serializable
data class Rates(val source: String? = null, @SerialName("fetched_at") val fetchedAt: String? = null)

/** `GET /budget/subscriptions` answer (`data`). */
@Serializable
data class SubscriptionList(
    val subscriptions: List<Subscription> = emptyList(),
    val summary: Summary? = null,
    val rates: Rates? = null,
)

@Serializable
data class SubscriptionCategory(
    val id: Int,
    val name: String = "",
    val color: String? = null,
    @SerialName("label_key") val labelKey: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("usage_count") val usageCount: Int = 0,
)

@Serializable
data class PaymentMethod(
    val id: Int,
    val name: String = "",
    @SerialName("label_key") val labelKey: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("usage_count") val usageCount: Int = 0,
)

@Serializable
data class BudgetAccountRef(val id: Int, val name: String = "", val archived: FlexBool = false)

/** `GET /budget/subscriptions/meta` (`data`). */
@Serializable
data class SubscriptionMeta(
    val categories: List<SubscriptionCategory> = emptyList(),
    @SerialName("payment_methods") val paymentMethods: List<PaymentMethod> = emptyList(),
    @SerialName("billing_cycles") val billingCycles: List<String> = listOf("daily", "weekly", "monthly", "yearly"),
    @SerialName("budget_mode") val budgetMode: String? = null,
    val accounts: List<BudgetAccountRef> = emptyList(),
) {
    val personal: Boolean get() = budgetMode == "personal"
}

/** `GET /budget/subscriptions/settings` (`data`). */
@Serializable
data class SubscriptionSettings(
    @SerialName("monthly_budget") val monthlyBudget: Double = 0.0,
    @SerialName("base_currency") val baseCurrency: String = "EUR",
)

/** One logo of `POST /logo-search` (`data.options`). */
@Serializable
data class LogoOption(@SerialName("logo_data") val logoData: String = "")
