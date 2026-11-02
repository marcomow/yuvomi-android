package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.text.Collator
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pure rules of the Subscriptions tab (`public/pages/subscriptions.js`), unit tested
 * without Android (docs/spec/subscriptions.md).
 */
object SubscriptionRules {
    /** `SUBSCRIPTION_COLOR_DEFAULT`. */
    const val DEFAULT_COLOR = "#0F766E"

    /** `DEFAULT_REMINDER_DAYS`. */
    const val DEFAULT_REMINDER_DAYS = 3

    /** `COUNTDOWN_EXACT_DAYS`, `COUNTDOWN_WEEKS_UNTIL`, `COUNTDOWN_MONTHS_UNTIL` (utils/countdown.js). */
    const val EXACT_DAYS = 30
    const val WEEKS_UNTIL = 60
    const val MONTHS_UNTIL = 364

    /** `subscriptionColorOptions()`: the module default, then the family tones (`USER_COLORS`). */
    val COLOR_OPTIONS = listOf(
        DEFAULT_COLOR to "colorPicker.standard",
        "#78808C" to "colorPicker.gray", "#3B82F6" to "colorPicker.blue", "#16A34A" to "colorPicker.green",
        "#D97706" to "colorPicker.ocher", "#059669" to "colorPicker.emerald", "#D946EF" to "colorPicker.fuchsia",
        "#EF4444" to "colorPicker.red", "#0891B2" to "colorPicker.cyan", "#EA580C" to "colorPicker.orange",
        "#EC4899" to "colorPicker.pink",
    )

    /** `UNIT_SENTENCE_KEYS` (utils/interval-sentence.js). */
    val UNIT_SENTENCE_KEYS = mapOf(
        "daily" to "common.everyNDays",
        "weekly" to "common.everyNWeeks",
        "monthly" to "common.everyNMonths",
        "yearly" to "common.everyNYears",
    )

    val CYCLE_KEYS = mapOf(
        "daily" to "subscriptions.cycle.daily",
        "weekly" to "subscriptions.cycle.weekly",
        "monthly" to "subscriptions.cycle.monthly",
        "yearly" to "subscriptions.cycle.yearly",
    )

    /** `STATUS_OPTIONS`. */
    val STATUS_OPTIONS = listOf(
        "all" to "common.all",
        Subscription.STATUS_ACTIVE to "subscriptions.statusActive",
        Subscription.STATUS_PAUSED to "subscriptions.statusDisabled",
        Subscription.STATUS_COMPLETED to "subscriptions.completed",
    )

    /** `SORT_OPTIONS`. */
    val SORT_OPTIONS = listOf(
        SORT_DUE to "subscriptions.sortDue",
        SORT_COST_DESC to "subscriptions.sortCostDesc",
        SORT_COST_ASC to "subscriptions.sortCostAsc",
        SORT_NAME to "subscriptions.sortName",
    )
    const val SORT_DUE = "due"
    const val SORT_COST_DESC = "cost-desc"
    const val SORT_COST_ASC = "cost-asc"
    const val SORT_NAME = "name"

    // ── Labels ──

    /** `metaLabel()`: the row's own translation key, else its name, else the empty slot. */
    fun metaLabel(strings: Strings, labelKey: String?, name: String?, emptyKey: String): String = when {
        !labelKey.isNullOrEmpty() -> strings.t(labelKey)
        !name.isNullOrEmpty() -> name
        else -> strings.t(emptyKey)
    }

    fun categoryLabel(strings: Strings, c: SubscriptionCategory?): String =
        metaLabel(strings, c?.labelKey, c?.name, "subscriptions.uncategorized")

    fun methodLabel(strings: Strings, m: PaymentMethod?): String =
        metaLabel(strings, m?.labelKey, m?.name, "subscriptions.unspecified")

    fun rowCategoryLabel(strings: Strings, s: Subscription): String =
        metaLabel(strings, s.categoryLabelKey, s.categoryName, "subscriptions.uncategorized")

    fun rowMethodLabel(strings: Strings, s: Subscription): String =
        metaLabel(strings, s.paymentMethodLabelKey, s.paymentMethodName, "subscriptions.unspecified")

    fun breakdownLabel(strings: Strings, b: Breakdown, emptyKey: String): String = metaLabel(strings, b.labelKey, b.name, emptyKey)

    /** `cycleLabel()`: the adverb at 1 ("Monthly"), the unit's sentence with its plural otherwise ("Every 2 months"). */
    fun cycleLabel(strings: Strings, cycle: String, interval: Int): String {
        val sentence = UNIT_SENTENCE_KEYS[cycle]
        return if (interval == 1 || sentence == null) strings.t(CYCLE_KEYS[cycle] ?: "subscriptions.cycle.monthly")
        else strings.t(sentence, "count" to interval)
    }

    fun cycleLabel(strings: Strings, s: Subscription): String = cycleLabel(strings, s.billingCycle, s.cycleInterval)

    /** The unit word of the sentence for the cycle picker ("months" for 2), without the number. */
    fun cycleUnitWord(strings: Strings, cycle: String, interval: Int): String = cycleSentenceParts(strings, cycle, interval).second

    /** `sentenceParts()`: the words before and after the number of the unit's sentence ("Every", "months"). */
    fun cycleSentenceParts(strings: Strings, cycle: String, interval: Int): Pair<String, String> {
        val sentence = UNIT_SENTENCE_KEYS[cycle] ?: return "" to cycle
        val full = strings.t(sentence, "count" to interval)
        val at = full.indexOf(interval.toString())
        if (at < 0) return "" to full
        return full.substring(0, at).trim() to full.substring(at + interval.toString().length).trim()
    }

    fun statusLabelKey(s: Subscription): String = when (s.effectiveStatus) {
        Subscription.STATUS_COMPLETED -> "subscriptions.completed"
        Subscription.STATUS_ACTIVE -> "subscriptions.active"
        else -> "subscriptions.disabled"
    }

    // ── Due dates ──

    fun daysUntil(date: String, today: LocalDate): Long? =
        runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(date.take(10))) }.getOrNull()

    /** A distance beyond [EXACT_DAYS]: `daysFromTodayText` in weeks, months or years (value with sign, unit). */
    fun coarseDistance(days: Long): Pair<Long, String> {
        val sign = if (days < 0) -1 else 1
        val a = abs(days)
        return when {
            a <= EXACT_DAYS -> days to "day"
            a <= WEEKS_UNTIL -> sign * (a / 7.0).roundToInt().toLong() to "week"
            a <= MONTHS_UNTIL -> sign * (a / 30.44).roundToInt().toLong() to "month"
            else -> sign * maxOf(1, (a / 365.25).roundToInt()).toLong() to "year"
        }
    }

    /**
     * `dueLabel()`: overdue, today, tomorrow, "Due in N days" up to a month, then the coarse
     * distance, which [relative] words in the UI language (`Intl.RelativeTimeFormat`).
     */
    fun dueLabel(strings: Strings, days: Long, relative: (Long, String) -> String): String = when {
        days < 0 -> strings.t("subscriptions.overdueDays", "count" to abs(days))
        days == 0L -> strings.t("subscriptions.dueToday")
        days == 1L -> strings.t("subscriptions.dueTomorrow")
        days > EXACT_DAYS -> coarseDistance(days).let { (v, unit) -> relative(v, unit) }
        else -> strings.t("subscriptions.dueInDays", "count" to days)
    }

    /** `reminderDiffers()`: off, or another lead time than the default three days. */
    fun reminderDiffers(s: Subscription): Boolean = !s.reminderEnabled || s.reminderDays != DEFAULT_REMINDER_DAYS

    sealed interface EndInfo {
        data class Completed(val at: String?) : EndInfo
        data class OnDate(val date: String) : EndInfo
        data class AfterCount(val remaining: Int) : EndInfo
    }

    /** `endInfoLabel()`. */
    fun endInfo(s: Subscription): EndInfo? = when {
        s.isCompleted -> EndInfo.Completed(s.completedAt)
        s.endType == Subscription.END_ON_DATE && !s.endDate.isNullOrEmpty() -> EndInfo.OnDate(s.endDate)
        s.endType == Subscription.END_AFTER_COUNT -> EndInfo.AfterCount(s.occurrencesRemaining ?: 0)
        else -> null
    }

    // ── List ──

    /** `sortedSubscriptions()`. */
    fun sorted(rows: List<Subscription>, sort: String, collator: Collator): List<Subscription> = when (sort) {
        SORT_COST_DESC -> rows.sortedByDescending { it.monthlyBase ?: -1.0 }
        SORT_COST_ASC -> rows.sortedBy { it.monthlyBase ?: Double.POSITIVE_INFINITY }
        SORT_NAME -> rows.sortedWith { a, b -> collator.compare(a.name, b.name) }
        else -> rows.sortedWith { a, b -> a.nextPaymentDate.compareTo(b.nextPaymentDate).takeIf { it != 0 } ?: collator.compare(a.name, b.name) }
    }

    /** The rate status shows only when some subscription is in another currency than the base one. */
    fun hasForeignCurrency(rows: List<Subscription>, base: String): Boolean = rows.any { it.currency.isNotEmpty() && it.currency != base }

    /** `renderOverBudgetNote()`: the real share of the budget when it is exceeded, else null. */
    fun overBudgetPercent(summary: Summary?): Int? {
        val budget = summary?.monthlyBudget ?: return null
        if (budget <= 0 || summary.remainingBudget >= 0) return null
        return (summary.monthlyTotal / budget * 100).roundToInt()
    }

    /** `amountRows()`: positive rows, largest first. */
    fun breakdownRows(rows: List<Breakdown>): List<Breakdown> = rows.filter { it.amount > 0 }.sortedByDescending { it.amount }

    // ── Forecast (`renewalForecast()`) ──

    /** The web's `addMonths`: the same day, or the month's last day when it has fewer. */
    fun addMonths(date: LocalDate, count: Int): LocalDate = date.plusMonths(count.toLong())

    /** `addCycleDate()`. */
    fun addCycle(date: LocalDate, cycle: String, interval: Int): LocalDate = when (cycle) {
        "daily" -> date.plusDays(interval.toLong())
        "weekly" -> date.plusWeeks(interval.toLong())
        "yearly" -> date.plusYears(interval.toLong())
        else -> addMonths(date, interval)
    }

    /** `subscriptionEndBoundary()`: the last payment date to plan for a limited subscription, else null. */
    fun endBoundary(s: Subscription): LocalDate? {
        if (s.endType == Subscription.END_ON_DATE && !s.endDate.isNullOrEmpty()) return runCatching { LocalDate.parse(s.endDate.take(10)) }.getOrNull()
        if (s.endType == Subscription.END_AFTER_COUNT) {
            val remaining = (s.occurrenceCount ?: 0) - s.occurrencesDone
            if (remaining <= 0) return LocalDate.of(1970, 1, 1)
            var last = runCatching { LocalDate.parse(s.nextPaymentDate.take(10)) }.getOrNull() ?: return null
            repeat(remaining - 1) { last = addCycle(last, s.billingCycle, s.cycleInterval.coerceAtLeast(1)) }
            return last
        }
        return null
    }

    /** `dueAmount()`: one payment in the base currency. */
    fun dueAmount(s: Subscription, base: String): Double {
        val monthlyBase = s.monthlyBase ?: return 0.0
        if (s.currency == base) return s.amount
        val nativeMonthly = s.monthlyNative ?: 0.0
        if (nativeMonthly == 0.0) return monthlyBase
        return s.amount * (monthlyBase / nativeMonthly)
    }

    data class ForecastMonth(val month: YearMonth, val amount: Double)

    /** `renewalForecast()`: the payments of the running subscriptions in this and the next five months. */
    fun forecast(rows: List<Subscription>, today: LocalDate, base: String): List<ForecastMonth> {
        val start = today.withDayOfMonth(1)
        val months = (0 until 6).map { YearMonth.from(addMonths(start, it)) }
        val sums = DoubleArray(6)
        val end = addMonths(start, 6)
        for (s in rows.filter { it.enabled }) {
            val boundary = endBoundary(s)
            var due = runCatching { LocalDate.parse(s.nextPaymentDate.take(10)) }.getOrNull() ?: continue
            val step = s.cycleInterval.coerceAtLeast(1)
            var guard = 0
            while (due < start && guard++ < 100_000) due = addCycle(due, s.billingCycle, step)
            while (due < end && (boundary == null || due <= boundary) && guard++ < 100_000) {
                val i = months.indexOf(YearMonth.from(due))
                if (i >= 0) sums[i] += dueAmount(s, base)
                due = addCycle(due, s.billingCycle, step)
            }
        }
        return months.mapIndexed { i, m -> ForecastMonth(m, Math.round(sums[i] * 100) / 100.0) }
    }

    /** The peak month of the forecast (`renderAreaChart`), or null when nothing is due. */
    fun peak(rows: List<ForecastMonth>): ForecastMonth? = rows.fold(null as ForecastMonth?) { best, row -> if (row.amount > (best?.amount ?: 0.0)) row else best }

    // ── Filters ──

    data class Filters(val categoryId: Int? = null, val paymentMethodId: Int? = null, val status: String = "all") {
        val count: Int get() = listOfNotNull(categoryId, paymentMethodId, status.takeIf { it != "all" }).size
    }

    data class ActiveFilter(val key: String, val field: String, val value: String)

    /** `activeFilters()`. */
    fun activeFilters(strings: Strings, f: Filters, meta: SubscriptionMeta): List<ActiveFilter> = buildList {
        f.categoryId?.let { id ->
            add(ActiveFilter("category", strings.t("subscriptions.filterLabelCategory"), categoryLabel(strings, meta.categories.firstOrNull { it.id == id })))
        }
        f.paymentMethodId?.let { id ->
            add(ActiveFilter("method", strings.t("subscriptions.filterLabelMethod"), methodLabel(strings, meta.paymentMethods.firstOrNull { it.id == id })))
        }
        if (f.status != "all") {
            add(ActiveFilter("status", strings.t("subscriptions.filterLabelStatus"), strings.t(STATUS_OPTIONS.firstOrNull { it.first == f.status }?.second ?: "common.all")))
        }
    }

    /** `resetGoneFilter()`: a filter on a category or payment method that no longer exists goes back to "all". */
    fun withoutGone(f: Filters, meta: SubscriptionMeta): Filters = f.copy(
        categoryId = f.categoryId?.takeIf { id -> meta.categories.any { it.id == id } },
        paymentMethodId = f.paymentMethodId?.takeIf { id -> meta.paymentMethods.any { it.id == id } },
    )

    // ── Manager ──

    /** `moveMetaRow()`: the ids after moving [id] by [delta], or null at the edge. */
    fun moved(ids: List<Int>, id: Int, delta: Int): List<Int>? {
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return null
        val out = ids.toMutableList()
        out[from] = ids[to]
        out[to] = ids[from]
        return out
    }

    /**
     * The name a rename sends: the field shows a default category's translated name
     * ("Bildung"); left unchanged (only the colour changed), the stored canonical name
     * ("Education") is kept so the translation is not lost.
     */
    fun renamedName(typed: String, shownLabel: String, storedName: String): String =
        if (typed.trim() == shownLabel.trim()) storedName else typed.trim()
}

/**
 * The sentence for a failed action in the tab, as upstream's `errorSentence`
 * (`public/utils/friendly-error.js`): a known reason has its own sentence, everything else
 * the general one of the place. The server's English text is never shown (ARCHITECTURE.md, rule 4).
 */
object SubscriptionErrors {
    const val SUBSCRIPTION_GONE = "subscriptions_subscription_not_found"
    const val COMPLETED = "subscriptions_subscription_completed"
    const val CATEGORY_GONE = "subscriptions_category_not_found"
    const val METHOD_GONE = "subscriptions_payment_method_not_found"
    const val SELECTED_CATEGORY_GONE = "subscriptions_selected_category_not_found"
    const val SELECTED_METHOD_GONE = "subscriptions_selected_payment_method_not_found"
    const val ACCOUNT_GONE = "subscriptions_account_not_found"
    const val UNDO_STALE = "subscriptions_renew_undo_stale"
    const val NO_LOGO = "subscriptions_no_logo_found"

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )

    val CASES = mapOf(
        SUBSCRIPTION_GONE to "subscriptions.errorSubscriptionGone",
        COMPLETED to "subscriptions.errorSubscriptionCompleted",
        CATEGORY_GONE to "subscriptions.errorCategoryGone",
        METHOD_GONE to "subscriptions.errorPaymentMethodGone",
        SELECTED_CATEGORY_GONE to "subscriptions.errorSelectedCategoryGone",
        SELECTED_METHOD_GONE to "subscriptions.errorSelectedPaymentMethodGone",
        ACCOUNT_GONE to "subscriptions.errorAccountGone",
        "subscriptions_category_exists" to "subscriptions.errorCategoryExists",
        "subscriptions_payment_method_exists" to "subscriptions.errorPaymentMethodExists",
        "subscriptions_end_before_next_payment" to "subscriptions.errorEndBeforeNextPayment",
        "subscriptions_occurrence_count_too_low" to "subscriptions.errorOccurrenceCountTooLow",
        "subscriptions_logo_content_mismatch" to "subscriptions.errorLogoContentMismatch",
        NO_LOGO to "subscriptions.logoSearchEmpty",
    )

    /** `GONE_REASONS` of friendly-error.js that this tab meets. */
    private val GONE = setOf(SUBSCRIPTION_GONE, CATEGORY_GONE, METHOD_GONE, SELECTED_CATEGORY_GONE, SELECTED_METHOD_GONE, ACCOUNT_GONE)

    /** An i18n key for [e]; [fallback] is the general sentence of the place. */
    fun key(e: Throwable, fallback: String = "common.unknownError"): String =
        FriendlyError.finish(e, keyBase(e, fallback), fallback)

    private fun keyBase(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASES[e.reason] ?: "common.errorRateLimited"
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `isGone`: the thing the action aimed at no longer exists (never on a 403). */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    fun reason(e: Throwable): String? = (e as? ApiProblem.Http)?.reason

    /** `isAlreadyDeleted(err, reason)`: deleting what someone else deleted first is a success. */
    fun isAlreadyDeleted(e: Throwable, reason: String): Boolean = isGone(e) && reason(e) == reason
}
