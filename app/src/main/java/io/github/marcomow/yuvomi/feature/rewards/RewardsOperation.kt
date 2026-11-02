package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.api.ApiOperation

/**
 * The upstream operations Rewards calls (docs/spec/rewards.md). Must match
 * `upstream/operations-used.d/rewards.txt` exactly (ContractFilesTest).
 */
enum class RewardsOperation(override val method: String, override val template: String) : ApiOperation {
    OVERVIEW("GET", "/api/v1/rewards/overview"),
    PARTICIPANTS("GET", "/api/v1/rewards/participants"),
    PARTICIPANT_SET("PUT", "/api/v1/rewards/participants/{userId}"),
    CATALOG("GET", "/api/v1/rewards/catalog"),
    CATALOG_CREATE("POST", "/api/v1/rewards/catalog"),
    CATALOG_UPDATE("PATCH", "/api/v1/rewards/catalog/{id}"),
    CATALOG_DELETE("DELETE", "/api/v1/rewards/catalog/{id}"),
    LEDGER("GET", "/api/v1/rewards/ledger"),
    REDEMPTIONS("GET", "/api/v1/rewards/redemptions"),
    REDEEM("POST", "/api/v1/rewards/redemptions"),
    DECIDE("PATCH", "/api/v1/rewards/redemptions/{id}"),
    BONUS("POST", "/api/v1/rewards/bonus"),
    MONEY("GET", "/api/v1/rewards/money"),
    MONEY_LEDGER("GET", "/api/v1/rewards/money/ledger"),
    MONEY_ENTRY("POST", "/api/v1/rewards/money/entries"),
    MONEY_ACCOUNT_OPEN("POST", "/api/v1/rewards/money/accounts"),
    MONEY_ACCOUNT_CLOSE("DELETE", "/api/v1/rewards/money/accounts/{userId}"),
    MONEY_PLAN_SAVE("PUT", "/api/v1/rewards/money/plans/{userId}"),
    MONEY_PLAN_DELETE("DELETE", "/api/v1/rewards/money/plans/{userId}"),
}
