package io.github.marcomow.yuvomi.feature.budget

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Budget calls (docs/spec/budget.md). Must match
 * `upstream/operations-used.d/budget.txt` exactly (ContractFilesTest).
 * `GET /preferences` is core ([io.github.marcomow.yuvomi.core.api.Operation.PREFERENCES]); the
 * member list (`GET /family/members`) is Meals' `FAMILY_MEMBERS`. The Subscriptions and Split
 * expenses tabs keep their own operations in their packages.
 */
enum class BudgetOperation(override val method: String, override val template: String) : ApiOperation {
    ENTRIES_LIST("GET", "/api/v1/budget"),
    ENTRY_CREATE("POST", "/api/v1/budget"),
    ENTRY_UPDATE("PUT", "/api/v1/budget/{id}"),
    ENTRY_DELETE("DELETE", "/api/v1/budget/{id}"),
    ENTRY_CONFIRM("PATCH", "/api/v1/budget/{id}/confirm"),
    SERIES_UPDATE("PUT", "/api/v1/budget/{id}/series"),
    SERIES_DELETE("DELETE", "/api/v1/budget/{id}/series"),
    SUMMARY("GET", "/api/v1/budget/summary"),
    EXPORT("GET", "/api/v1/budget/export"),
    META("GET", "/api/v1/budget/meta"),

    CATEGORIES_LIST("GET", "/api/v1/budget/categories"),
    CATEGORY_CREATE("POST", "/api/v1/budget/categories"),
    CATEGORIES_REORDER("PATCH", "/api/v1/budget/categories/reorder"),
    CATEGORY_UPDATE("PUT", "/api/v1/budget/categories/{key}"),
    CATEGORY_DELETE("DELETE", "/api/v1/budget/categories/{key}"),
    SUBCATEGORY_CREATE("POST", "/api/v1/budget/categories/{categoryKey}/subcategories"),
    SUBCATEGORIES_REORDER("PATCH", "/api/v1/budget/categories/{key}/subcategories/reorder"),
    SUBCATEGORY_UPDATE("PUT", "/api/v1/budget/categories/{key}/subcategories/{subKey}"),
    SUBCATEGORY_DELETE("DELETE", "/api/v1/budget/categories/{key}/subcategories/{subKey}"),

    ACCOUNTS_LIST("GET", "/api/v1/budget/accounts"),
    ACCOUNT_CREATE("POST", "/api/v1/budget/accounts"),
    ACCOUNT_UPDATE("PUT", "/api/v1/budget/accounts/{id}"),
    ACCOUNT_DELETE("DELETE", "/api/v1/budget/accounts/{id}"),

    TRANSFER_CREATE("POST", "/api/v1/budget/transfers"),
    TRANSFER_GET("GET", "/api/v1/budget/transfers/{id}"),
    TRANSFER_UPDATE("PUT", "/api/v1/budget/transfers/{id}"),
    TRANSFER_SERIES_UPDATE("PUT", "/api/v1/budget/transfers/{id}/series"),

    LOANS_LIST("GET", "/api/v1/budget/loans"),
    LOAN_CREATE("POST", "/api/v1/budget/loans"),
    LOAN_PREVIEW("POST", "/api/v1/budget/loans/preview"),
    LOAN_UPDATE("PUT", "/api/v1/budget/loans/{id}"),
    LOAN_DELETE("DELETE", "/api/v1/budget/loans/{id}"),
    LOAN_PAYMENT_CREATE("POST", "/api/v1/budget/loans/{id}/payments"),
    LOAN_PAYMENT_DELETE("DELETE", "/api/v1/budget/loans/{id}/payments/{paymentId}"),

    PLANS_LIST("GET", "/api/v1/budget/plans"),
    PLAN_SET("PUT", "/api/v1/budget/plans/{category}"),
    PLAN_DELETE("DELETE", "/api/v1/budget/plans/{category}"),

    STATS("GET", "/api/v1/budget/stats"),

    // Receipts are documents of the Documents module (#583, document-attach.js).
}
