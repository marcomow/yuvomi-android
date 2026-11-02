package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations the budget page's SplitExpenses tab calls (docs/spec/splitexpenses.md). Must
 * match `upstream/operations-used.d/splitexpenses.txt` exactly (ContractFilesTest).
 * The household members come from Meals' `FAMILY_MEMBERS` (`GET /family/members`); `BUDGET_META`
 * (the budget's expense categories) is under Budget's path.
 */
enum class SplitExpensesOperation(override val method: String, override val template: String) : ApiOperation {
    META("GET", "/api/v1/split-expenses/meta"),
    DASHBOARD("GET", "/api/v1/split-expenses/dashboard"),
    GROUPS("GET", "/api/v1/split-expenses/groups"),
    GROUP_CREATE("POST", "/api/v1/split-expenses/groups"),
    GROUP_UPDATE("PATCH", "/api/v1/split-expenses/groups/{id}"),
    GROUP_DELETE("DELETE", "/api/v1/split-expenses/groups/{id}"),
    GROUP_ARCHIVE("POST", "/api/v1/split-expenses/groups/{id}/archive"),
    GROUP_UNARCHIVE("POST", "/api/v1/split-expenses/groups/{id}/unarchive"),
    MEMBERS("GET", "/api/v1/split-expenses/groups/{id}/members"),
    MEMBER_ADD("POST", "/api/v1/split-expenses/groups/{id}/members"),
    MEMBER_REMOVE("DELETE", "/api/v1/split-expenses/groups/{id}/members/{userId}"),
    MEMBER_CANDIDATES("GET", "/api/v1/split-expenses/groups/{id}/member-candidates"),
    GUEST_CREATE("POST", "/api/v1/split-expenses/groups/{id}/guests"),
    EXPENSES("GET", "/api/v1/split-expenses/groups/{id}/expenses"),
    EXPENSE_CREATE("POST", "/api/v1/split-expenses/groups/{id}/expenses"),
    EXPENSE_UPDATE("PUT", "/api/v1/split-expenses/expenses/{id}"),
    EXPENSE_DELETE("DELETE", "/api/v1/split-expenses/expenses/{id}"),
    BALANCES("GET", "/api/v1/split-expenses/groups/{id}/balances"),
    SETTLEMENT_CREATE("POST", "/api/v1/split-expenses/groups/{id}/settlements"),
    SETTLEMENT_REVERSE("POST", "/api/v1/split-expenses/groups/{id}/settlements/{settlementId}/reverse"),
    ACTIVITY("GET", "/api/v1/split-expenses/groups/{id}/activity"),
    RECURRING("GET", "/api/v1/split-expenses/groups/{id}/recurring"),
    RECURRING_CREATE("POST", "/api/v1/split-expenses/groups/{id}/recurring"),
    RECURRING_UPDATE("PUT", "/api/v1/split-expenses/recurring/{id}"),
    RECURRING_DELETE("DELETE", "/api/v1/split-expenses/recurring/{id}"),
    RECURRING_PAUSE("POST", "/api/v1/split-expenses/recurring/{id}/pause"),
    BUDGET_META("GET", "/api/v1/budget/meta"),
}
