package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings

/** Labels the web page derives from data, and its error sentences (`budgetError`, `loanSaveError`). */
object BudgetText {
    /** `BUDGET_CATEGORY_LABEL_KEYS` (`public/utils/category-labels.js`). */
    private val CATEGORY_KEYS = mapOf(
        "income" to "budget.categoryIncome",
        "housing" to "budget.categoryHousing",
        "food" to "budget.categoryFood",
        "insurance" to "budget.categoryInsurance",
        "utilities" to "budget.categoryUtilities",
        "health" to "budget.categoryHealth",
        "family" to "budget.categoryFamily",
        "home" to "budget.categoryHome",
        "clothing" to "budget.categoryClothing",
        "transport" to "budget.catTransport",
        "personal_health" to "budget.catPersonalHealth",
        "leisure" to "budget.catLeisure",
        "shopping_clothing" to "budget.catShoppingClothing",
        "education" to "budget.catEducation",
        "financial_other" to "budget.catFinancialOther",
        "subscriptions" to "budget.catSubscriptions",
        "Erwerbseinkommen" to "budget.catEarnedIncome",
        "Kapitalerträge" to "budget.catInvestmentIncome",
        "Geschenke & Transfers" to "budget.catTransferGiftIncome",
        "Sozialleistungen" to "budget.catGovernmentBenefits",
        "Sonstiges Einkommen" to "budget.catOtherIncome",
    )

    /** `SUBCATEGORY_I18N` of `budget.js`. */
    private val SUBCATEGORY_KEYS = mapOf(
        "saving" to "budget.catSaving",
        "rent_mortgage" to "budget.subcatRentMortgage",
        "condominium" to "budget.subcatCondominium",
        "utilities" to "budget.subcatUtilities",
        "internet_tv_phone" to "budget.subcatInternetTvPhone",
        "renovation_maintenance" to "budget.subcatRenovationMaintenance",
        "cleaning" to "budget.subcatCleaning",
        "groceries" to "budget.subcatGroceries",
        "restaurants_bars" to "budget.subcatRestaurantsBars",
        "snacks_fast_food" to "budget.subcatSnacksFastFood",
        "bakery" to "budget.subcatBakery",
        "fuel" to "budget.subcatFuel",
        "parking_tolls" to "budget.subcatParkingTolls",
        "public_transport" to "budget.subcatPublicTransport",
        "apps_taxi" to "budget.subcatAppsTaxi",
        "maintenance_insurance" to "budget.subcatMaintenanceInsurance",
        "pharmacy" to "budget.subcatPharmacy",
        "health_insurance" to "budget.subcatHealthInsurance",
        "gym_sports" to "budget.subcatGymSports",
        "beauty_cosmetics" to "budget.subcatBeautyCosmetics",
        "travel" to "budget.subcatTravel",
        "streaming" to "budget.subcatStreaming",
        "events" to "budget.subcatEvents",
        "hobbies" to "budget.subcatHobbies",
        "clothes_shoes" to "budget.subcatClothesShoes",
        "electronics" to "budget.subcatElectronics",
        "gifts" to "budget.subcatGifts",
        "courses_college" to "budget.subcatCoursesCollege",
        "school_supplies" to "budget.subcatSchoolSupplies",
        "languages" to "budget.subcatLanguages",
        "loans_interest" to "budget.subcatLoansInterest",
        "bank_fees" to "budget.subcatBankFees",
        "insurance_other" to "budget.subcatInsuranceOther",
        "investments" to "budget.subcatInvestments",
        "taxes" to "budget.subcatTaxes",
        "subscription_entertainment" to "budget.subcatSubscriptionEntertainment",
        "subscription_productivity" to "budget.subcatSubscriptionProductivity",
        "subscription_utilities" to "budget.subcatSubscriptionUtilities",
        "subscription_health" to "budget.subcatSubscriptionHealth",
        "subscription_education" to "budget.subcatSubscriptionEducation",
        "subscription_other" to "budget.subcatSubscriptionOther",
    )

    /** `budgetCategoryLabel(key, name, t)`. */
    fun catalogLabel(key: String, name: String?, strings: Strings): String =
        CATEGORY_KEYS[key.trim()]?.let { strings.t(it) } ?: name?.takeIf { it.isNotEmpty() } ?: key.trim()

    /** `categoryLabel` of `budget.js`: the masked bucket and savings first, then the catalogue. */
    fun category(key: String, meta: BudgetMeta, strings: Strings): String {
        if (key == BudgetLedger.MASKED_CATEGORY) return strings.t("budget.maskedCategory")
        if (key == BudgetLedger.SAVINGS_CHART_CATEGORY) return strings.t("budget.catSaving")
        val item = (meta.expenseCategories + meta.incomeCategories).firstOrNull { it.key == key }
        return catalogLabel(item?.key ?: key, item?.name ?: key, strings)
    }

    fun category(c: BudgetCategory, strings: Strings): String = c.label ?: catalogLabel(c.key, c.name, strings)

    /** `subcategoryLabel`. */
    fun subcategory(key: String, meta: BudgetMeta, strings: Strings): String {
        val item = meta.subcategories.values.flatten().firstOrNull { it.key == key }
        return SUBCATEGORY_KEYS[item?.key ?: key]?.let { strings.t(it) } ?: item?.name ?: key
    }

    fun subcategory(s: BudgetSubcategory, strings: Strings): String = s.label ?: SUBCATEGORY_KEYS[s.key]?.let { strings.t(it) } ?: s.name

    /** `accountTypeLabel`. */
    fun accountType(type: String, strings: Strings): String =
        strings.t("budget.accountType_" + (if (type in BudgetLedger.ACCOUNT_TYPES) type else "other"))

    /** `intervalUnitLabel` (`public/rrule-ui.js`) for the budget's three intervals. */
    fun intervalUnit(interval: String, count: Int, strings: Strings): String = strings.t(
        when (interval) {
            "weekly" -> if (count > 1) "rrule.unitWeeks" else "rrule.unitWeek"
            "yearly" -> if (count > 1) "rrule.unitYears" else "rrule.unitYear"
            else -> if (count > 1) "rrule.unitMonths" else "rrule.unitMonth"
        },
    )

    fun intervalLabel(interval: String, strings: Strings): String = strings.t(
        when (interval) {
            "weekly" -> "budget.intervalWeekly"
            "yearly" -> "budget.intervalYearly"
            else -> "budget.intervalMonthly"
        },
    )

    /** The recurrence row of the read view (`entryReadSections`). */
    fun recurrence(entry: BudgetEntry, strings: Strings): String {
        if (!entry.isRecurring) return ""
        val interval = entry.recurrenceInterval ?: "monthly"
        val count = (entry.recurrenceIntervalCount ?: 1).coerceIn(1, 99)
        val every = if (count == 1) intervalLabel(interval, strings) else "${strings.t("rrule.labelEvery")} $count ${intervalUnit(interval, count, strings)}"
        return listOfNotNull(
            every,
            strings.t("budget.virtualBudgetLabel").takeIf { entry.recurrenceVirtual },
            strings.t("budget.confirmFirstLabel").takeIf { entry.recurrenceConfirm },
        ).joinToString(" · ")
    }

    /** A refusal for one field of the open form, or a toast when [field] is null. */
    data class Refusal(val field: String?, val message: String)

    private data class Rule(val fields: List<String>, val key: String, val keyWithMax: String? = null)

    private const val BUDGET_FAILED = "common.errorOccurred"
    const val BUDGET_SAVE_FAILED = "budget.saveFailed"
    const val LOAN_SAVE_FAILED = "budget.loanSaveFailed"

    /** `BUDGET_REFUSALS`: the fields are the form fields of the native editors. */
    private val BUDGET_REFUSALS = mapOf(
        "entry_title_invalid" to Rule(listOf("title"), "common.titleRequired"),
        "entry_amount_invalid" to Rule(listOf("amount"), "budget.validAmountRequired"),
        "entry_amount_exceeds_loan" to Rule(listOf("amount"), "budget.amountExceedsLoanRemaining"),
        "entry_category_invalid" to Rule(listOf("category"), "budget.categoryRequired"),
        "entry_subcategory_invalid" to Rule(listOf("subcategory"), "budget.subcategoryRequired"),
        "entry_date_invalid" to Rule(listOf("date"), "calendar.invalidDate"),
        "entry_start_date_invalid" to Rule(listOf("date"), "calendar.invalidDate"),
        "entry_account_invalid" to Rule(listOf("account"), "budget.accountNotFound"),
        "entry_responsible_invalid" to Rule(emptyList(), "budget.responsibleNotMember"),
        "series_start_too_early" to Rule(listOf("date"), "budget.seriesStartTooEarly"),
        "entry_recurrence_invalid" to Rule(listOf("interval"), BUDGET_SAVE_FAILED),
        "entry_interval_count_invalid" to Rule(listOf("intervalCount"), BUDGET_SAVE_FAILED),
        "series_end_refused" to Rule(emptyList(), BUDGET_SAVE_FAILED),
        "entry_not_recurring" to Rule(emptyList(), BUDGET_FAILED),
        "entry_already_booked" to Rule(emptyList(), BUDGET_FAILED),
        "transfer_invalid" to Rule(emptyList(), BUDGET_SAVE_FAILED),
        "transfer_currency_mismatch" to Rule(listOf("to", "account"), "budget.transferCurrencyMismatch"),
        "transfer_account_archived" to Rule(listOf("from", "to", "account"), "budget.transferAccountArchived"),
        "transfer_series_required" to Rule(emptyList(), BUDGET_SAVE_FAILED),
        "account_name_invalid" to Rule(listOf("name"), "common.titleRequired"),
        "account_balance_invalid" to Rule(listOf("balance"), "budget.validAmountRequired"),
        "account_credit_limit_invalid" to Rule(listOf("creditLimit"), "budget.validAmountRequired"),
        "account_credit_bank_invalid" to Rule(listOf("creditBank"), BUDGET_SAVE_FAILED),
        "account_type_invalid" to Rule(listOf("type"), BUDGET_SAVE_FAILED),
        "account_has_transfers" to Rule(emptyList(), "budget.accountHasTransfers"),
        "account_color_invalid" to Rule(emptyList(), BUDGET_SAVE_FAILED),
        "account_currency_invalid" to Rule(emptyList(), "budget.loanCurrencyInvalid"),
        "category_name_invalid" to Rule(emptyList(), "common.nameRequired"),
        "subcategory_name_invalid" to Rule(emptyList(), "common.nameRequired"),
        "category_type_invalid" to Rule(emptyList(), BUDGET_SAVE_FAILED),
        "category_exists" to Rule(emptyList(), "category.errorExists"),
        "subcategory_exists" to Rule(emptyList(), "category.errorSubExists"),
        "loan_settled" to Rule(emptyList(), "budget.loanAlreadyPaid"),
        "loan_installment_paid" to Rule(emptyList(), "budget.loanInstallmentAlreadyPaid"),
        "loan_payment_amount_invalid" to Rule(emptyList(), "budget.validAmountRequired"),
        "loan_payment_amount_exceeds" to Rule(emptyList(), "budget.amountExceedsLoanRemaining"),
        "loan_payment_date_invalid" to Rule(emptyList(), "calendar.invalidDate"),
        "loan_payment_installment_invalid" to Rule(emptyList(), BUDGET_FAILED),
        "loan_payment_rate_unavailable" to Rule(listOf("amount"), "budget.loanPaymentRateUnavailable"),
    )

    /** `LOAN_REFUSALS`. */
    private val LOAN_REFUSALS = mapOf(
        "loan_title_invalid" to Rule(listOf("title"), "budget.loanTitleInvalid"),
        "loan_borrower_invalid" to Rule(listOf("borrower"), "budget.loanBorrowerRequired"),
        "loan_start_month_invalid" to Rule(listOf("start"), "budget.loanStartMonthRequired"),
        "loan_notes_invalid" to Rule(listOf("notes"), "budget.loanNotesInvalid"),
        "loan_amount_invalid" to Rule(listOf("amount"), "budget.validAmountRequired"),
        "loan_installments_invalid" to Rule(listOf("installments"), "budget.loanInstallmentsRequired", "budget.loanInstallmentsRange"),
        "loan_principal_invalid" to Rule(listOf("principal"), "budget.loanPrincipalRequired"),
        "loan_rate_invalid" to Rule(listOf("fixedRate"), "budget.loanRateRequired"),
        "loan_repayment_invalid" to Rule(listOf("repayment"), "budget.loanRepaymentRequired"),
        "loan_fixed_period_invalid" to Rule(listOf("fixedPeriod"), "budget.loanFixedPeriodRequired"),
        "loan_followup_rate_invalid" to Rule(listOf("followupRate"), "budget.loanRateRequired"),
        "loan_not_amortizing" to Rule(listOf("repayment"), "budget.loanPreviewInvalid"),
        "loan_term_too_long" to Rule(listOf("repayment"), "budget.loanPreviewInvalid", "budget.loanTermTooLong"),
        "loan_currency_invalid" to Rule(listOf("currency"), "budget.loanCurrencyInvalid"),
        "loan_exchange_rate_invalid" to Rule(listOf("exchangeRate"), "budget.loanExchangeRateRequired"),
        "loan_account_invalid" to Rule(listOf("account"), "budget.accountNotFound"),
        "loan_due_day_invalid" to Rule(listOf("dueDay"), "budget.loanDueDayInvalid"),
        "loan_paid_installments_invalid" to Rule(listOf("paid"), "budget.loanPaidInstallmentsInvalid"),
        "loan_paid_installments_exceed" to Rule(listOf("paid"), "budget.loanPaidInstallmentsTooMany", "budget.loanPaidInstallmentsMax"),
        "loan_term_below_paid" to Rule(listOf("installments", "repayment"), "budget.loanTermBelowPaid"),
        "loan_direction_invalid" to Rule(emptyList(), LOAN_SAVE_FAILED),
        "loan_interest_mode_invalid" to Rule(emptyList(), LOAN_SAVE_FAILED),
        "loan_interest_fields_required" to Rule(emptyList(), LOAN_SAVE_FAILED),
    )

    /** The sentence of a preview refusal (`refusalText(LOAN_REFUSALS.get(reason) ?? ...)`). */
    fun previewRefusal(reason: String?, max: Int?, strings: Strings): String {
        val rule = LOAN_REFUSALS[reason] ?: Rule(emptyList(), "budget.loanPreviewInvalid")
        return text(rule, max, strings)
    }

    private fun text(rule: Rule, max: Int?, strings: Strings): String =
        if (rule.keyWithMax != null && max != null && max >= 0) strings.t(rule.keyWithMax, "max" to max) else strings.t(rule.key)

    /** `REFUSAL_MESSAGES` and the budget-relevant part of `CASE_MESSAGES` (`friendly-error.js`). */
    private val REFUSAL_MESSAGES = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "csrf_invalid" to "common.errorFormExpired",
        "not_owner" to "common.errorNotOwner",
    )
    private val CASE_MESSAGES = mapOf(
        "category_exists" to "category.errorExists",
        "category_last" to "category.errorLast",
        "documents_document_not_found" to "documents.errorDocumentGone",
        "documents_file_type_not_allowed" to "documents.errorFileTypeNotAllowed",
        "documents_file_content_mismatch" to "documents.errorFileContentMismatch",
        "documents_file_empty" to "documents.errorFileEmpty",
        "documents_storage_failed" to "documents.errorStorageFailed",
    )

    /** `knownKey` of `friendly-error.js`. */
    private fun knownKey(e: Throwable): String? = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSAL_MESSAGES[e.reason] ?: "common.errorNoPermission"
            e.status == 429 -> CASE_MESSAGES[e.reason] ?: "common.errorRateLimited"
            else -> CASE_MESSAGES[e.reason]
        }
        else -> null
    }

    /** `errorSentence`. */
    fun errorKey(e: Throwable, fallback: String = "common.errorGeneric"): String = knownKey(e) ?: fallback

    /** `loadErrorSentence`, or null when it knows nothing better than the caller. */
    private fun loadErrorKey(e: Throwable): String? = when {
        e is ApiProblem.Network -> "common.errorOffline"
        knownKey(e) != null -> knownKey(e)
        e is ApiProblem.Http && e.status == 404 -> "common.errorNotFound"
        e is ApiProblem.Http && e.status >= 500 -> "common.errorServer"
        else -> null
    }

    /** `refusalSentence`: a known reason names its field; never the server's own sentence. */
    private fun refusal(e: Throwable, rules: Map<String, Rule>, statuses: Set<Int>, fallback: String, strings: Strings): Refusal {
        val http = e as? ApiProblem.Http
        if (http != null && http.status in statuses) {
            val rule = rules[http.reason]
            return if (rule != null) Refusal(rule.fields.firstOrNull(), text(rule, null, strings)) else Refusal(null, strings.t(fallback))
        }
        if (e is ApiProblem.Network || http?.status == 403) return Refusal(null, strings.t(errorKey(e, fallback)))
        if (e is ApiProblem.SessionExpired) return Refusal(null, strings.t(fallback))
        if (http != null && http.status == 405) return Refusal(null, strings.t("android.error.unsupported"))
        val known = http != null && (http.status == 404 || http.status >= 500)
        return Refusal(null, strings.t((if (known) loadErrorKey(e) else null) ?: fallback))
    }

    /** `budgetError`: entries, series, accounts, categories, payments (400 and 409 carry a reason). */
    fun budgetError(e: Throwable, strings: Strings, fallback: String = BUDGET_FAILED): Refusal =
        refusal(e, BUDGET_REFUSALS, setOf(400, 409), fallback, strings)

    /** `loanSaveError`: the loan form (400 carries a reason). */
    fun loanError(e: Throwable, strings: Strings): Refusal = refusal(e, LOAN_REFUSALS, setOf(400), LOAN_SAVE_FAILED, strings)

    /** The category manager's own sentences (`_errMsg` of `category-manager.js`). */
    fun categoryError(e: Throwable, strings: Strings): String {
        val http = e as? ApiProblem.Http
        return when (http?.reason) {
            "category_in_use" -> strings.t("category.errorInUse", "count" to (http.count ?: 0))
            "category_last" -> strings.t("category.errorLast")
            "category_exists" -> strings.t("category.errorExists")
            "subcategory_in_use" -> strings.t("category.errorSubInUse", "count" to (http.count ?: 0))
            "subcategory_last" -> strings.t("category.errorSubLast")
            "subcategory_exists" -> strings.t("category.errorSubExists")
            else -> budgetError(e, strings).message
        }
    }

    /** The `count` the in-use refusals carry; the client keeps only `reason`, so it is read from the message. */
    private val ApiProblem.Http.count: Int?
        get() = serverMessage?.let { Regex("by (\\d+) entr").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
