package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.api.FlexBool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A participating member with their points (`balancesOfEnrolled`, `GET /rewards/overview`). */
@Serializable
data class RewardBalance(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    @SerialName("family_role") val familyRole: String? = null,
    val balance: Int = 0,
    val rank: Int = 0,
)

/** A reward of the catalogue (`CATALOG_SELECT`, server/services/rewards.js). */
@Serializable
data class RewardItem(
    val id: Int,
    val name: String = "",
    val cost: Int = 0,
    val icon: String? = null,
    val description: String? = null,
    @SerialName("is_active") val isActive: FlexBool = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val quantity: Int? = null,
    /** `null` is unlimited, `0` sold out. */
    val remaining: Int? = null,
)

@Serializable
data class RewardSetup(
    val participantCount: Int = 0,
    val catalogCount: Int = 0,
    val pointedTaskCount: Int = 0,
)

/** `GET /rewards/overview`. */
@Serializable
data class RewardOverview(
    val balances: List<RewardBalance> = emptyList(),
    val catalog: List<RewardItem> = emptyList(),
    val pendingCount: Int = 0,
    val moneyPendingCount: Int = 0,
    val isAdmin: FlexBool = false,
    val me: Int? = null,
    val setup: RewardSetup? = null,
)

/** A points entry (`GET /rewards/ledger`). `reason` is null when the member may not see the task. */
@Serializable
data class LedgerEntry(
    val id: Int,
    @SerialName("user_id") val userId: Int = 0,
    val delta: Int = 0,
    val type: String = "",
    val reason: String? = null,
    @SerialName("task_id") val taskId: Int? = null,
    @SerialName("redemption_id") val redemptionId: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_color") val userColor: String? = null,
    @SerialName("user_avatar") val userAvatar: String? = null,
)

/** A request: a reward (`kind = reward`) or pocket money (`withdrawal`, `deposit`). */
@Serializable
data class Redemption(
    val id: Int,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("catalog_id") val catalogId: Int? = null,
    val kind: String? = "reward",
    val currency: String? = null,
    @SerialName("reward_name") val rewardName: String = "",
    @SerialName("reward_icon") val rewardIcon: String? = null,
    /** Points, or the amount in minor units for money. */
    val cost: Long = 0,
    val status: String = "pending",
    val note: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_color") val userColor: String? = null,
    @SerialName("user_avatar") val userAvatar: String? = null,
    @SerialName("user_balance") val userBalance: Long? = null,
    @SerialName("minor_unit") val minorUnit: Int? = null,
) {
    val isMoney: Boolean get() = kind != null && kind != "reward"
}

/** A pocket money plan (`planView`, server/services/reward-money.js). */
@Serializable
data class MoneyPlan(
    @SerialName("amount_minor") val amountMinor: Long = 0,
    val currency: String? = null,
    @SerialName("minor_unit") val minorUnit: Int? = null,
    val frequency: String = "weekly",
    @SerialName("anchor_day") val anchorDay: Int = 1,
    @SerialName("next_run_date") val nextRunDate: String? = null,
    val paused: FlexBool = false,
)

/** A pocket money account (`listMoneyAccounts`). */
@Serializable
data class MoneyAccount(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    @SerialName("family_role") val familyRole: String? = null,
    @SerialName("balance_minor") val balanceMinor: Long = 0,
    val former: FlexBool = false,
    val currency: String? = null,
    @SerialName("minor_unit") val minorUnit: Int? = null,
    val plan: MoneyPlan? = null,
    val closable: FlexBool = false,
)

@Serializable
data class MoneyCandidate(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("family_role") val familyRole: String? = null,
)

/** `GET /rewards/money`: the household's currency, the accounts the reader may see, candidates for parents. */
@Serializable
data class MoneyState(
    val currency: String? = null,
    @SerialName("minor_unit") val minorUnit: Int? = null,
    val accounts: List<MoneyAccount> = emptyList(),
    val candidates: List<MoneyCandidate> = emptyList(),
)

/** A pocket money entry (`listMoneyLedger`). */
@Serializable
data class MoneyEntry(
    val id: Int,
    @SerialName("user_id") val userId: Int = 0,
    val delta: Long = 0,
    val currency: String? = null,
    @SerialName("minor_unit") val minorUnit: Int? = null,
    val type: String = "",
    val reason: String? = null,
    @SerialName("allowance_date") val allowanceDate: String? = null,
    @SerialName("request_kind") val requestKind: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** A member in the participants sheet (`GET /rewards/participants`). */
@Serializable
data class Participant(
    val id: Int,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String? = null,
    @SerialName("avatar_data") val avatarData: String? = null,
    val enabled: FlexBool = false,
    val balance: Int = 0,
)

/** Everything the points and catalogue tabs show, cached as one (ADR 0007). */
@Serializable
data class RewardsBundle(
    val overview: RewardOverview = RewardOverview(),
    /** Admins see inactive rewards too (`?all=1`). */
    val catalog: List<RewardItem> = emptyList(),
    val redemptions: List<Redemption> = emptyList(),
    /** null: unknown (the side column is left out). */
    val recentLedger: List<LedgerEntry>? = null,
    /** null: unknown or not offered (the section is left out). */
    val money: MoneyState? = null,
)
