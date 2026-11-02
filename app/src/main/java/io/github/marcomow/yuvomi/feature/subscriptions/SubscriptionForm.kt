package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The subscription dialog's fields (`openSubscriptionModal()`), as text where the web reads
 * text, so validation and the request body are pure (`saveSubscription()`).
 */
data class SubscriptionForm(
    val name: String = "",
    val description: String = "",
    val amount: String = "",
    val currency: String = "EUR",
    val cycle: String = "monthly",
    val interval: String = "1",
    val nextDate: String = "",
    val accountId: Int? = null,
    val reminderEnabled: Boolean = true,
    val reminderDays: String = SubscriptionRules.DEFAULT_REMINDER_DAYS.toString(),
    val endType: String = Subscription.END_NEVER,
    val endDate: String = "",
    val endCount: String = "",
    val visibility: String = "shared",
    val categoryId: Int? = null,
    val paymentMethodId: Int? = null,
    val brandColor: String = SubscriptionRules.DEFAULT_COLOR,
    val accountUsername: String = "",
    val notes: String = "",
    val enabled: Boolean = true,
    val logoData: String? = null,
) {
    companion object {
        /** A new subscription: base currency, monthly, due today, reminder three days before. */
        fun blank(settings: SubscriptionSettings, today: LocalDate) =
            SubscriptionForm(currency = settings.baseCurrency, nextDate = today.toString())

        fun of(s: Subscription, localeTag: String) = SubscriptionForm(
            name = s.name,
            description = s.description.orEmpty(),
            amount = BudgetMoney.toInput(s.amount, s.currency, localeTag),
            currency = s.currency,
            cycle = s.billingCycle,
            interval = s.cycleInterval.toString(),
            nextDate = s.nextPaymentDate,
            accountId = s.accountId,
            reminderEnabled = s.reminderEnabled,
            reminderDays = s.reminderDays.toString(),
            endType = s.endType,
            endDate = s.endDate.orEmpty(),
            endCount = s.occurrenceCount?.toString().orEmpty(),
            visibility = if (s.visibility == "private") "private" else "shared",
            categoryId = s.categoryId,
            paymentMethodId = s.paymentMethodId,
            brandColor = s.brandColor ?: SubscriptionRules.DEFAULT_COLOR,
            accountUsername = s.accountUsername.orEmpty(),
            notes = s.notes.orEmpty(),
            enabled = s.enabled,
            logoData = s.logoData,
        )

        /** `advancedOpen`: "More details" opens itself when an edited subscription uses one of its fields. */
        fun advancedOpen(s: Subscription?, meta: SubscriptionMeta): Boolean = s != null && (
            s.categoryId != null || s.paymentMethodId != null ||
                (s.brandColor != null && !s.brandColor.equals(SubscriptionRules.DEFAULT_COLOR, true)) ||
                !s.notes.isNullOrEmpty() || !s.enabled || (meta.personal && s.visibility == "private")
            )
    }
}

object SubscriptionFormRules {
    const val NAME = "name"
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val INTERVAL = "interval"
    const val NEXT_DATE = "nextDate"
    const val REMINDER = "reminder"
    const val END_DATE = "endDate"
    const val END_COUNT = "endCount"

    private fun date(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    /**
     * `saveSubscription()`'s checks, every one at once, as field to sentence. A completed
     * subscription that stays completed skips the comparisons with its payments (#2009).
     */
    fun errors(f: SubscriptionForm, existing: Subscription?, strings: Strings): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (f.name.isBlank()) out[NAME] = strings.t("common.required")
        val next = date(f.nextDate)
        if (next == null) out[NEXT_DATE] = strings.t("subscriptions.invalidDate")
        if (f.currency.isBlank()) out[CURRENCY] = strings.t("subscriptions.currencyRequired")
        val interval = f.interval.trim().toIntOrNull()
        if (interval == null || interval !in 1..365) out[INTERVAL] = strings.t("android.subscriptions.intervalRange")
        if (f.reminderEnabled) {
            val days = f.reminderDays.trim().toIntOrNull()
            if (days == null || days !in 0..365) out[REMINDER] = strings.t("android.subscriptions.reminderRange")
        }
        val staysCompleted = existing?.isCompleted == true && !f.enabled
        when (f.endType) {
            Subscription.END_ON_DATE -> {
                val end = date(f.endDate)
                if (end == null) out[END_DATE] = strings.t("subscriptions.invalidDate")
                else if (next != null && !staysCompleted && end < next) out[END_DATE] = strings.t("subscriptions.errorEndBeforeNextPayment")
            }
            Subscription.END_AFTER_COUNT -> {
                val count = f.endCount.trim().toIntOrNull()
                if (count == null || count < 1 || count > 1200) out[END_COUNT] = strings.t("subscriptions.endCountInvalid")
                else if (!staysCompleted && count <= (existing?.occurrencesDone ?: 0)) out[END_COUNT] = strings.t("subscriptions.errorOccurrenceCountTooLow")
            }
        }
        val currency = f.currency.trim().uppercase()
        val problem = BudgetMoney.problem(
            f.amount, currency.ifEmpty { null }, strings.locale, required = true, nonNegative = true,
            original = existing?.amount, originalCurrency = existing?.currency, checkPrecision = currency.isNotEmpty(),
        )
        if (problem != null) out[AMOUNT] = BudgetMoney.problemText(strings, problem, currency)
        return out
    }

    /**
     * The request body `saveSubscription()` sends. Account and visibility only when the dialog
     * shows them (#1277, #1701): without accounts the stored one stays, in shared mode the server
     * decides the visibility.
     */
    fun body(f: SubscriptionForm, existing: Subscription?, meta: SubscriptionMeta, localeTag: String): JsonObject = buildJsonObject {
        put("name", f.name.trim())
        put("description", f.description.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("amount", BudgetMoney.parse(f.amount, localeTag) ?: 0.0)
        put("currency", f.currency.trim().uppercase())
        put("billing_cycle", f.cycle)
        put("cycle_interval", f.interval.trim().toIntOrNull() ?: 1)
        put("next_payment_date", f.nextDate.trim())
        put("reminder_days", f.reminderDays.trim().toIntOrNull() ?: existing?.reminderDays ?: SubscriptionRules.DEFAULT_REMINDER_DAYS)
        put("reminder_enabled", f.reminderEnabled)
        put("category_id", f.categoryId?.let(::JsonPrimitive) ?: JsonNull)
        put("payment_method_id", f.paymentMethodId?.let(::JsonPrimitive) ?: JsonNull)
        put("website_url", existing?.websiteUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("brand_color", f.brandColor)
        put("logo_data", f.logoData?.let(::JsonPrimitive) ?: JsonNull)
        put("notes", f.notes.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("account_username", f.accountUsername.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        put("enabled", f.enabled)
        put("end_type", f.endType)
        put("end_date", if (f.endType == Subscription.END_ON_DATE) JsonPrimitive(f.endDate.trim()) else JsonNull)
        put("occurrence_count", if (f.endType == Subscription.END_AFTER_COUNT) f.endCount.trim().toIntOrNull()?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
        if (meta.accounts.isNotEmpty()) put("account_id", f.accountId?.let(::JsonPrimitive) ?: JsonNull)
        if (meta.personal) put("visibility", if (f.visibility == "private") "private" else "shared")
    }

    /** `accountFieldHtml()`: active accounts, and an archived one only while it is the chosen one. */
    fun accountOptions(meta: SubscriptionMeta, current: Int?): List<BudgetAccountRef> =
        meta.accounts.filter { !it.archived || it.id == current }

    /** `fileToDataUrl()`'s checks: PNG, JPEG, WebP or SVG, under 500 KB. Returns an error key or null. */
    val LOGO_TYPES = listOf("image/png", "image/jpeg", "image/webp", "image/svg+xml")

    fun logoProblem(mime: String?, size: Long): String? = when {
        mime !in LOGO_TYPES -> "subscriptions.logoTypeError"
        size > 500_000 -> "subscriptions.logoTooLarge"
        else -> null
    }
}
