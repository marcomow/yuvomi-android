package io.github.marcomow.yuvomi.feature.subscriptions

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations the budget page's Subscriptions tab calls (docs/spec/subscriptions.md). Must
 * match `upstream/operations-used.d/subscriptions.txt` exactly (ContractFilesTest).
 */
enum class SubscriptionsOperation(override val method: String, override val template: String) : ApiOperation {
    LIST("GET", "/api/v1/budget/subscriptions"),
    CREATE("POST", "/api/v1/budget/subscriptions"),
    UPDATE("PUT", "/api/v1/budget/subscriptions/{id}"),
    DELETE("DELETE", "/api/v1/budget/subscriptions/{id}"),
    RENEW("POST", "/api/v1/budget/subscriptions/{id}/renew"),
    RENEW_UNDO("POST", "/api/v1/budget/subscriptions/{id}/renew/undo"),
    META("GET", "/api/v1/budget/subscriptions/meta"),
    META_ORDER("PUT", "/api/v1/budget/subscriptions/meta/order"),
    SETTINGS("GET", "/api/v1/budget/subscriptions/settings"),
    SETTINGS_UPDATE("PUT", "/api/v1/budget/subscriptions/settings"),
    CATEGORY_CREATE("POST", "/api/v1/budget/subscriptions/categories"),
    CATEGORY_UPDATE("PUT", "/api/v1/budget/subscriptions/categories/{id}"),
    CATEGORY_DELETE("DELETE", "/api/v1/budget/subscriptions/categories/{id}"),
    METHOD_CREATE("POST", "/api/v1/budget/subscriptions/payment-methods"),
    METHOD_UPDATE("PUT", "/api/v1/budget/subscriptions/payment-methods/{id}"),
    METHOD_DELETE("DELETE", "/api/v1/budget/subscriptions/payment-methods/{id}"),
    LOGO_SEARCH("POST", "/api/v1/budget/subscriptions/logo-search"),
}
