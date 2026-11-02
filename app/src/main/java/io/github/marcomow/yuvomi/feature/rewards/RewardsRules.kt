package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.api.FriendlyError
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The web page's rules, as pure functions (docs/spec/rewards.md). */
object RewardsRules {

    // ── public/utils/reward-goal.js ──

    /** `isRedeemable`: active and not sold out. */
    fun isRedeemable(item: RewardItem): Boolean = item.isActive && item.remaining != 0

    sealed interface Goal {
        data object Reached : Goal
        data class Toward(val pct: Int, val missing: Int, val target: RewardItem) : Goal
    }

    /** `nextRewardGoal`: the cheapest redeemable reward the balance does not reach yet. */
    fun nextGoal(balance: Int, catalog: List<RewardItem>): Goal? {
        val active = catalog.filter(::isRedeemable)
        if (active.isEmpty()) return null
        val target = active.filter { it.cost > balance }.minByOrNull { it.cost } ?: return Goal.Reached
        val pct = max(0, min(100, (balance.toDouble() / target.cost * 100).roundToInt()))
        return Goal.Toward(pct, target.cost - balance, target)
    }

    /** `nextRewardHint`: the bar and its sentence, or null without any redeemable reward. */
    data class Hint(val pct: Int, val label: String)

    fun hint(balance: Int, catalog: List<RewardItem>, strings: Strings): Hint? {
        if (balance < 0) return Hint(0, strings.t("rewards.balanceBelowZero"))
        return when (val goal = nextGoal(balance, catalog) ?: return null) {
            Goal.Reached -> Hint(100, strings.t("rewards.canRedeemNow"))
            is Goal.Toward -> Hint(
                goal.pct,
                strings.t("rewards.remainingToReward", "points" to points(goal.missing, strings), "reward" to goal.target.name),
            )
        }
    }

    /** `canAffordAny`: does the balance cover any redeemable reward? */
    fun canAffordAny(balance: Int, catalog: List<RewardItem>): Boolean = catalog.any { isRedeemable(it) && it.cost <= balance }

    /** `fmtPoints`: a whole number in the UI language. */
    fun points(n: Number, strings: Strings): String =
        NumberFormat.getIntegerInstance(Locale.forLanguageTag(strings.locale)).format(n.toLong())

    /** `pointsLabel`: "40 points". */
    fun pointsLabel(n: Number, strings: Strings): String = "${points(n, strings)} ${strings.t("rewards.pointsUnit")}"

    // ── Ledger ──

    /** `isTaskReopened`: a negative reversal takes back points of a reopened task. */
    fun isTaskReopened(entry: LedgerEntry): Boolean = entry.type == "reversal" && entry.delta < 0

    /** `ledgerReason`. */
    fun ledgerReason(entry: LedgerEntry, strings: Strings): String {
        if (isTaskReopened(entry)) {
            return entry.reason?.let { strings.t("rewards.ledgerTaskReopenedNamed", "task" to it) } ?: strings.t("rewards.ledgerTaskReopened")
        }
        entry.reason?.takeIf { it.isNotEmpty() }?.let { return it }
        return strings.t("rewards.ledgerType.${entry.type}")
    }

    /** `+12` / `−5` with the true minus sign, as `ledgerRowHtml`. */
    fun delta(delta: Int, strings: Strings): String = (if (delta > 0) "+" else "−") + points(kotlin.math.abs(delta), strings)

    /** `moneyRowKind`: what a money entry was, from its fields. */
    fun moneyRowKind(entry: MoneyEntry): String = when {
        entry.allowanceDate != null -> "allowance"
        entry.requestKind == "deposit" -> "deposit"
        entry.requestKind == "withdrawal" || entry.type == "redeem" -> "withdrawal"
        entry.delta >= 0 -> "credit"
        else -> "debit"
    }

    /** `moneyRowLabel`. */
    fun moneyRowLabel(kind: String, strings: Strings): String = strings.t(
        when (kind) {
            "allowance" -> "rewards.money.title"
            "deposit" -> "rewards.money.ledgerDeposit"
            "withdrawal" -> "rewards.money.ledgerWithdrawal"
            "credit" -> "rewards.money.ledgerCredit"
            else -> "rewards.money.ledgerDebit"
        },
    )

    // ── Requests ──

    /** The title of a pending request (`renderPendingPanel`). */
    fun requestTitle(r: Redemption, strings: Strings): String = when {
        r.isMoney -> strings.t(if (r.kind == "deposit") "rewards.money.ledgerDeposit" else "rewards.money.ledgerWithdrawal")
        r.rewardIcon != null -> "${r.rewardIcon} ${r.rewardName}"
        else -> r.rewardName
    }

    /** `decisionSentence`: "<reward> approved for <name>". */
    fun decisionSentence(r: Redemption?, action: String, strings: Strings): String {
        val reward = when {
            r == null -> ""
            r.isMoney -> strings.t(if (r.kind == "deposit") "rewards.money.ledgerDeposit" else "rewards.money.ledgerWithdrawal")
            else -> r.rewardName
        }
        if (reward.isEmpty()) {
            return strings.t(
                when (action) {
                    "fulfill" -> "rewards.toastApproved"
                    "reject" -> "rewards.toastRejected"
                    else -> "rewards.toastCancelled"
                },
            )
        }
        if (action == "cancel") return strings.t("rewards.toastCancelledNamed", "reward" to reward)
        val name = r?.userName.orEmpty()
        if (name.isEmpty()) return strings.t(if (action == "fulfill") "rewards.toastApproved" else "rewards.toastRejected")
        return strings.t(if (action == "fulfill") "rewards.toastApprovedNamed" else "rewards.toastRejectedNamed", "reward" to reward, "name" to name)
    }

    /** Reasons after which the pending list is stale and reloads (`decideRedemption`). */
    fun staleAfterDecision(e: Throwable): Boolean {
        val reason = (e as? ApiProblem.Http)?.reason ?: return false
        return isGone(e) || reason in setOf("out_of_stock", "insufficient_funds", "rewards_redemption_decided", "account_deactivated", "balance_too_large")
    }

    // ── Setup and pocket money ──

    data class SetupStep(val done: Boolean, val labelKey: String, val action: String)

    /** `renderSetupHints`: three steps, or none once all are done. */
    fun setupSteps(setup: RewardSetup?): List<SetupStep> {
        val s = setup ?: return emptyList()
        val steps = listOf(
            SetupStep(s.participantCount > 0, "rewards.setupStep1", "participants"),
            SetupStep(s.pointedTaskCount > 0, "rewards.setupStep2", "tasks"),
            SetupStep(s.catalogCount > 0, "rewards.setupStep3", "catalog"),
        )
        return if (steps.all { it.done }) emptyList() else steps
    }

    /** `PARENT_ROLES`: pocket money is for the children; an unset role stays choosable. */
    private val PARENT_ROLES = setOf("dad", "mom", "parent", "grandparent")

    fun moneyCandidates(money: MoneyState?): List<MoneyCandidate> =
        money?.candidates.orEmpty().filter { it.familyRole !in PARENT_ROLES }

    // ── Errors (`rewardsErrorSentence`, `errorSentence`) ──

    const val REWARD_GONE = "rewards_reward_not_found"
    const val REQUEST_GONE = "rewards_redemption_not_found"
    const val ACCOUNT_GONE = "rewards_money_account_not_found"
    const val PLAN_GONE = "rewards_money_plan_not_found"

    /** `MONEY_REASON_KEYS`: older reasons only this page reads. */
    private val MONEY_REASONS = mapOf(
        "out_of_stock" to "rewards.soldOutHint",
        "insufficient_funds" to "rewards.money.insufficient",
        "money_account_not_empty" to "rewards.money.accountNotEmpty",
        "account_deactivated" to "rewards.money.errorAccountDeactivated",
        "balance_too_large" to "rewards.money.errorBalanceTooLarge",
        "money_account_exists" to "rewards.money.errorAccountExists",
        "no_money_account" to "rewards.money.errorNoAccount",
        "currency_mismatch" to "rewards.money.errorCurrencyChanged",
    )

    private val CASES = mapOf(
        REWARD_GONE to "rewards.errorRewardGone",
        REQUEST_GONE to "rewards.errorRequestGone",
        "rewards_redemption_decided" to "rewards.errorRequestDecided",
        "rewards_insufficient_points" to "rewards.errorNotEnoughPoints",
        "rewards_not_participating" to "rewards.errorNotParticipating",
        ACCOUNT_GONE to "rewards.errorMoneyAccountGone",
        PLAN_GONE to "rewards.errorMoneyPlanGone",
    )

    private val REFUSALS = mapOf(
        "module_access_denied" to "common.errorModuleNoAccess",
        "module_read_only" to "settings.permReadOnlyBanner",
        "csrf_invalid" to "common.errorFormExpired",
    )

    private val GONE = setOf(REWARD_GONE, REQUEST_GONE, ACCOUNT_GONE, PLAN_GONE)

    fun errorKey(e: Throwable, fallback: String = "common.errorGeneric"): String =
        FriendlyError.finish(e, errorKeyBase(e, fallback), fallback)

    private fun errorKeyBase(e: Throwable, fallback: String): String = when (e) {
        is ApiProblem.Network -> "common.errorOfflineMutation"
        is ApiProblem.Http -> when {
            e.reason == "restore_in_progress" -> "common.errorRestoreInProgress"
            e.status == 403 -> REFUSALS[e.reason] ?: "common.errorNoPermission"
            e.reason != null && e.reason in MONEY_REASONS -> MONEY_REASONS.getValue(e.reason)
            e.reason != null && e.reason in CASES -> CASES.getValue(e.reason)
            e.status == 429 -> "common.errorRateLimited"
            e.operationUnsupported -> "android.error.unsupported"
            else -> fallback
        }
        else -> fallback
    }

    /** `isGone`: what the action aimed at no longer exists (never on a 403). */
    fun isGone(e: Throwable): Boolean = e is ApiProblem.Http && e.status != 403 && e.reason in GONE

    /** `isAlreadyDeleted`: deleting what is gone already is no failure. */
    fun isAlreadyDeleted(e: Throwable, reason: String): Boolean = isGone(e) && (e as ApiProblem.Http).reason == reason
}
