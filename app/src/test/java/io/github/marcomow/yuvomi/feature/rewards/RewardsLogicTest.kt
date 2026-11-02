package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale

/** Ports of the rewards page's rules (docs/spec/rewards.md), checked against what the web app computes. */
class RewardsLogicTest {
    private val english = Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n/en.json").readText()) +
        Strings.flatten(File(System.getProperty("yuvomi.assetsDir"), "i18n-android/rewards/en.json").readText())
    private val strings = Strings("en", english, english) { if (it == 1.0) "one" else "other" }

    private fun reward(id: Int, cost: Int, active: Boolean = true, remaining: Int? = null, name: String = "R$id") =
        RewardItem(id, name, cost, isActive = active, remaining = remaining)

    private val catalog = listOf(reward(1, 20, name = "Screen time"), reward(2, 40, name = "Gaming"), reward(3, 30, remaining = 0), reward(4, 25, active = false))

    /** REW-A1: `nextRewardGoal` picks the cheapest redeemable reward not reached yet. */
    @Test fun `the goal is the cheapest redeemable reward above the balance`() {
        val goal = RewardsRules.nextGoal(25, catalog) as RewardsRules.Goal.Toward
        assertEquals(2, goal.target.id)
        assertEquals(15, goal.missing)
        assertEquals(63, goal.pct)
        assertEquals(RewardsRules.Goal.Reached, RewardsRules.nextGoal(40, catalog))
        assertNull(RewardsRules.nextGoal(10, listOf(reward(3, 30, remaining = 0))))
        // Below zero the bar stays empty but the missing points count from there.
        assertEquals(25, (RewardsRules.nextGoal(-5, catalog) as RewardsRules.Goal.Toward).missing)
    }

    @Test fun `the hint names the target, says ready, or explains a negative balance`() {
        assertEquals("15 more to “Gaming”", RewardsRules.hint(25, catalog, strings)!!.label)
        assertEquals(strings.t("rewards.canRedeemNow"), RewardsRules.hint(40, catalog, strings)!!.label)
        assertEquals(strings.t("rewards.balanceBelowZero"), RewardsRules.hint(-3, emptyList(), strings)!!.label)
        assertNull(RewardsRules.hint(3, emptyList(), strings))
        assertTrue(RewardsRules.canAffordAny(20, catalog))
        // Sold out and inactive rewards do not count.
        assertFalse(RewardsRules.canAffordAny(19, catalog))
    }

    /** REW-A2: `ledgerReason`. */
    @Test fun `ledger rows name the task, a reopened task, or the entry type`() {
        val earn = LedgerEntry(1, delta = 10, type = "earn", reason = "Feed the cat")
        assertEquals("Feed the cat", RewardsRules.ledgerReason(earn, strings))
        assertEquals(strings.t("rewards.ledgerType.earn"), RewardsRules.ledgerReason(earn.copy(reason = null), strings))
        assertEquals("Reopened: Feed the cat", RewardsRules.ledgerReason(earn.copy(type = "reversal", delta = -10), strings))
        assertEquals(strings.t("rewards.ledgerTaskReopened"), RewardsRules.ledgerReason(LedgerEntry(2, delta = -5, type = "reversal"), strings))
        // A refund of a rejected request is a positive reversal and keeps its reward's name.
        assertEquals("Gaming", RewardsRules.ledgerReason(LedgerEntry(3, delta = 40, type = "reversal", reason = "Gaming"), strings))
        assertEquals("+12", RewardsRules.delta(12, strings))
        assertEquals("−1,500", RewardsRules.delta(-1500, strings))
    }

    @Test fun `money rows read their kind from their fields`() {
        assertEquals("allowance", RewardsRules.moneyRowKind(MoneyEntry(1, delta = 1000, allowanceDate = "2026-11-01")))
        assertEquals("deposit", RewardsRules.moneyRowKind(MoneyEntry(2, delta = 500, requestKind = "deposit")))
        assertEquals("withdrawal", RewardsRules.moneyRowKind(MoneyEntry(3, delta = -500, type = "redeem")))
        assertEquals("credit", RewardsRules.moneyRowKind(MoneyEntry(4, delta = 500, type = "bonus")))
        assertEquals("debit", RewardsRules.moneyRowKind(MoneyEntry(5, delta = -500, type = "adjust")))
    }

    /** REW-A3: `decisionSentence`. */
    @Test fun `a decision names the reward and the member`() {
        val r = Redemption(1, userId = 4, rewardName = "Gaming", userName = "Leo")
        assertEquals("“Gaming” approved for Leo.", RewardsRules.decisionSentence(r, "fulfill", strings))
        assertEquals("“Gaming” rejected for Leo.", RewardsRules.decisionSentence(r, "reject", strings))
        assertEquals("“Gaming” withdrawn.", RewardsRules.decisionSentence(r, "cancel", strings))
        assertEquals(strings.t("rewards.toastApproved"), RewardsRules.decisionSentence(null, "fulfill", strings))
        val money = Redemption(2, kind = "withdrawal", rewardName = "withdrawal", userName = "Emma")
        assertEquals("“Withdrawal” approved for Emma.", RewardsRules.decisionSentence(money, "fulfill", strings))
    }

    /** REW-A4: `rewardsErrorSentence`. */
    @Test fun `errors use the page's own sentences, never the server's`() {
        fun http(status: Int, reason: String?) = ApiProblem.Http(status, reason, "English text")
        assertEquals("rewards.money.insufficient", RewardsRules.errorKey(http(400, "insufficient_funds")))
        assertEquals("rewards.soldOutHint", RewardsRules.errorKey(http(409, "out_of_stock")))
        assertEquals("rewards.errorNotEnoughPoints", RewardsRules.errorKey(http(400, "rewards_insufficient_points")))
        assertEquals("rewards.errorRequestDecided", RewardsRules.errorKey(http(409, "rewards_redemption_decided")))
        // A refusal is a refusal, whatever reason it carries.
        assertEquals("common.errorNoPermission", RewardsRules.errorKey(http(403, "money_not_on_display")))
        assertEquals("rewards.redeemError", RewardsRules.errorKey(http(500, null), "rewards.redeemError"))
        assertEquals("common.errorOfflineMutation", RewardsRules.errorKey(ApiProblem.Network(IOException())))
        assertTrue(RewardsRules.isAlreadyDeleted(http(404, RewardsRules.REWARD_GONE), RewardsRules.REWARD_GONE))
        assertFalse(RewardsRules.isAlreadyDeleted(http(403, RewardsRules.REWARD_GONE), RewardsRules.REWARD_GONE))
        assertTrue(RewardsRules.staleAfterDecision(http(409, "rewards_redemption_decided")))
        assertFalse(RewardsRules.staleAfterDecision(http(500, null)))
    }

    @Test fun `setup steps disappear once all are done, and parents are no pocket money candidates`() {
        assertEquals(listOf(true, false, false), RewardsRules.setupSteps(RewardSetup(2, 0, 0)).map { it.done })
        assertTrue(RewardsRules.setupSteps(RewardSetup(1, 1, 1)).isEmpty())
        val money = MoneyState(candidates = listOf(MoneyCandidate(1, "Alex", "dad"), MoneyCandidate(4, "Leo", "son"), MoneyCandidate(9, "Sam", null)))
        assertEquals(listOf(4, 9), RewardsRules.moneyCandidates(money).map { it.id })
    }

    /** REW-A5: amounts in the decimals the server names (`minor_unit`), never the formatter's. */
    @Test fun `money is formatted and read in the server's decimals`() {
        val money = RewardsMoney(Locale.forLanguageTag("de-DE"))
        val eur = money.ctx("EUR", 2, null)
        assertEquals("25,00 €", money.format(2500, eur).replace('\u00A0', ' '))
        assertEquals("12.50", money.toDecimal("12,50"))
        assertNull(money.problem("12,5", eur))
        assertEquals(RewardsMoney.Problem.PRECISION, money.problem("1,234", eur))
        assertEquals(RewardsMoney.Problem.GROUPED, money.problem("1.250,00", eur))
        assertEquals(RewardsMoney.Problem.NOT_POSITIVE, money.problem("0", eur))
        assertEquals(RewardsMoney.Problem.INVALID, money.problem("12a", eur))
        assertEquals(1250L, money.toMinor("12.50", eur))
        // HUF: ISO says two decimals, the region writes none; a whole amount opens without them.
        val huf = money.ctx("HUF", 2, null)
        assertEquals("5000", money.toInput(500000, huf))
        assertEquals("12,50", money.toInput(1250, eur))
        // Without its own currency an object counts in the household's.
        assertEquals(RewardsMoney.Ctx("JPY", 0), money.ctx(null, null, MoneyState(currency = "JPY", minorUnit = 0)))
    }

    @Test fun `the overview answer decodes, flags as numbers or booleans`() {
        val o = ApiJson.decodeFromString(
            RewardOverview.serializer(),
            """{"balances":[{"id":4,"display_name":"Leo","balance":65,"rank":1}],
               "catalog":[{"id":2,"name":"Ice","cost":25,"is_active":1,"quantity":null,"remaining":null}],
               "pendingCount":1,"moneyPendingCount":0,"isAdmin":true,"me":1,
               "setup":{"participantCount":2,"catalogCount":6,"pointedTaskCount":3}}""",
        )
        assertEquals(65, o.balances.single().balance)
        assertTrue(o.catalog.single().isActive)
        assertEquals(1, o.me)
    }

    /** REW-A12: the Tags view's source. */
    @Test fun `rewards with hashtags in name or description become tagged items`() {
        val items = RewardsTagSource.items(
            listOf(
                RewardItem(1, "Movie night #family", 30, icon = "🎬"),
                RewardItem(2, "Gaming", 40, description = "One hour #screen"),
                RewardItem(3, "Ice cream", 25),
            ),
        )
        assertEquals(listOf("reward:1", "reward:2"), items.map { it.key })
        assertEquals(setOf("family"), items[0].tags)
        assertEquals("🎬 Movie night #family", items[0].title)
        assertEquals(RewardsRoutes.catalog(), items[1].route)
    }
}
