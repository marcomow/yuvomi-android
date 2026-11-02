package io.github.marcomow.yuvomi.contract

import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.feature.rewards.RewardsApi
import io.github.marcomow.yuvomi.feature.rewards.RewardsApi.RewardFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/spec/rewards.md against the real server (skipped without `YUVOMI_CONTRACT_URL`). Every
 * test makes its own fixtures with unique names and leaves balances as it found them. The seed
 * enrols emma and leo; emma has a pocket money account, leo has none.
 */
class RewardsContractTest : ContractTestBase() {

    private fun api(username: String): Pair<RewardsApi, Int> = signedIn(username).let { (c, p) -> RewardsApi(c) to p.user.id }

    private suspend fun expectHttp(status: Int, block: suspend () -> Unit): ApiProblem.Http {
        try {
            block()
        } catch (e: ApiProblem.Http) {
            assertEquals(status, e.status)
            return e
        }
        fail("expected HTTP $status")
        error("unreachable")
    }

    /** REW-C1 (GET /rewards/overview). */
    @Test fun `REW-C1 the overview has balances, catalogue, setup and who asks`() = runBlocking<Unit> {
        val (alex, alexId) = api("alex")
        val (_, emmaId) = api("emma")
        val o = alex.overview()
        assertTrue(o.isAdmin)
        assertEquals(alexId, o.me)
        assertTrue(o.balances.any { it.id == emmaId })
        // A ranking: highest balance first.
        assertEquals(o.balances.sortedByDescending { it.balance }.map { it.balance }, o.balances.map { it.balance })
        assertTrue(o.catalog.isNotEmpty())
        assertTrue(o.catalog.all { it.isActive })
        assertNotNull(o.setup)
        assertTrue(o.setup!!.participantCount >= 2)
    }

    /** REW-C2 (GET /rewards/participants, PUT /rewards/participants/{userId}). */
    @Test fun `REW-C2 participants are listed and switched by parents only`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, emmaId) = api("emma")
        val list = alex.participants()
        assertTrue(list.first { it.id == emmaId }.enabled)
        // Switching on who is on already is a no-op that answers.
        alex.setParticipant(emmaId, true)
        assertTrue(alex.participants().first { it.id == emmaId }.enabled)
        expectHttp(403) { emma.participants() }
        expectHttp(403) { emma.setParticipant(emmaId, false) }
        expectHttp(404) { alex.setParticipant(999_999, true) }
    }

    /** REW-C3 (GET, POST /rewards/catalog, PATCH, DELETE /rewards/catalog/{id}). */
    @Test fun `REW-C3 a reward is created, edited, deactivated and deleted`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, _) = api("emma")
        val name = unique("Kite")
        val key = YuvomiClient.newIdempotencyKey()
        val created = alex.createReward(RewardFields(name, 35, null, "🪁", "A kite #outdoor"), key)
        try {
            assertEquals(name, created.name)
            assertEquals(35, created.cost)
            assertNull(created.quantity)
            assertNull(created.remaining)
            assertTrue(created.isActive)
            assertEquals(created.id, alex.createReward(RewardFields(name, 35, null, "🪁", "A kite #outdoor"), key).id)

            val edited = alex.updateReward(created.id, RewardFields(name, 40, 2, null, null, isActive = false))
            assertEquals(40, edited.cost)
            assertEquals(2, edited.quantity)
            assertEquals(2, edited.remaining)
            assertNull(edited.icon)
            assertNull(edited.description)
            assertFalse(edited.isActive)
            assertTrue(alex.catalog(all = true).any { it.id == created.id })
            assertTrue(alex.catalog(all = false).none { it.id == created.id })
            // A child neither sees inactive rewards nor writes the catalogue.
            assertTrue(emma.catalog(all = true).none { it.id == created.id })
            expectHttp(403) { emma.createReward(RewardFields(unique("Nope"), 1, null, null, null), YuvomiClient.newIdempotencyKey()) }

            val bad = expectHttp(400) { alex.updateReward(created.id, RewardFields(name, 40, 0, null, null)) }
            assertNull(bad.reason)
        } finally {
            alex.deleteReward(created.id)
        }
        assertEquals("rewards_reward_not_found", expectHttp(404) { alex.deleteReward(created.id) }.reason)
        assertEquals("rewards_reward_not_found", expectHttp(404) { alex.updateReward(created.id, RewardFields(name, 1, null, null, null)) }.reason)
    }

    /** REW-C4 (GET /rewards/ledger). */
    @Test fun `REW-C4 the points history filters by person and limits`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (_, emmaId) = api("emma")
        val rows = alex.ledger(userId = emmaId, limit = 3)
        assertTrue(rows.size in 1..3)
        assertTrue(rows.all { it.userId == emmaId && it.userName != null && it.createdAt != null })
        assertTrue(alex.ledger().isNotEmpty())
    }

    /** REW-C5 (GET, POST /rewards/redemptions, PATCH /rewards/redemptions/{id}). */
    @Test fun `REW-C5 a request is filed, rejected, reopened and withdrawn`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, emmaId) = api("emma")
        val reward = alex.createReward(RewardFields(unique("Sticker"), 1, null, "⭐", null), YuvomiClient.newIdempotencyKey())
        try {
            val before = alex.overview().balances.first { it.id == emmaId }.balance
            val filed = emma.redeem(reward.id, null, "please", YuvomiClient.newIdempotencyKey())
            assertEquals(emmaId, filed.userId)
            assertEquals("pending", filed.status)
            assertEquals(reward.name, filed.rewardName)
            // The points are reserved at once.
            assertEquals(before - 1, alex.overview().balances.first { it.id == emmaId }.balance)

            val open = alex.redemptions(pendingOnly = true).first { it.id == filed.id }
            assertEquals(before - 1L, open.userBalance)
            assertEquals("please", open.note)
            // A child sees only her own requests.
            assertTrue(emma.redemptions(pendingOnly = false).all { it.userId == emmaId })

            assertEquals("rejected", alex.decide(filed.id, "reject").status)
            assertEquals(before, alex.overview().balances.first { it.id == emmaId }.balance)
            assertEquals("rewards_redemption_decided", expectHttp(409) { alex.decide(filed.id, "fulfill") }.reason)
            assertEquals("pending", alex.decide(filed.id, "reopen").status)
            assertEquals("rewards_redemption_open", expectHttp(409) { alex.decide(filed.id, "reopen") }.reason)
            expectHttp(403) { emma.decide(filed.id, "fulfill") }
            assertEquals("cancelled", emma.decide(filed.id, "cancel").status)
            assertEquals(before, alex.overview().balances.first { it.id == emmaId }.balance)
            assertEquals("rewards_redemption_not_found", expectHttp(404) { alex.decide(999_999, "cancel") }.reason)
        } finally {
            alex.deleteReward(reward.id)
        }
    }

    /** REW-C5b: a request beyond the balance is refused with its reason. */
    @Test fun `REW-C5b too few points are refused`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, _) = api("emma")
        val castle = alex.createReward(RewardFields(unique("Castle"), 1_000_000, null, null, null), YuvomiClient.newIdempotencyKey())
        try {
            val refused = expectHttp(400) { emma.redeem(castle.id, null, null, YuvomiClient.newIdempotencyKey()) }
            assertEquals("rewards_insufficient_points", refused.reason)
        } finally {
            alex.deleteReward(castle.id)
        }
        assertEquals("rewards_reward_not_found", expectHttp(404) { emma.redeem(castle.id, null, null, YuvomiClient.newIdempotencyKey()) }.reason)
    }

    /** REW-C6 (POST /rewards/bonus). */
    @Test fun `REW-C6 a bonus and a correction book points`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, emmaId) = api("emma")
        val before = alex.overview().balances.first { it.id == emmaId }.balance
        alex.bonus(emmaId, 3, unique("Helped"), YuvomiClient.newIdempotencyKey())
        assertEquals(before + 3, alex.overview().balances.first { it.id == emmaId }.balance)
        alex.bonus(emmaId, -3, null, YuvomiClient.newIdempotencyKey())
        assertEquals(before, alex.overview().balances.first { it.id == emmaId }.balance)
        assertEquals("adjust", alex.ledger(userId = emmaId, limit = 1).first().type)
        expectHttp(403) { emma.bonus(emmaId, 3, null, YuvomiClient.newIdempotencyKey()) }
        expectHttp(400) { alex.bonus(emmaId, 0, null, YuvomiClient.newIdempotencyKey()) }
    }

    /** REW-C7 (GET /rewards/money, GET /rewards/money/ledger, POST /rewards/money/entries). */
    @Test fun `REW-C7 pocket money is read by the child and the parents and booked by parents`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, emmaId) = api("emma")
        val (leo, _) = api("leo")
        val money = alex.money()
        assertNotNull(money.currency)
        val account = money.accounts.first { it.id == emmaId }
        assertEquals("EUR", account.currency)
        assertEquals(2, account.minorUnit)
        assertNotNull(account.plan)
        // A child sees her own account only; a sibling none of hers.
        assertEquals(listOf(emmaId), emma.money().accounts.map { it.id })
        assertTrue(leo.money().accounts.none { it.id == emmaId })
        assertTrue(emma.money().candidates.isEmpty())

        alex.book(emmaId, "credit", "0.05", unique("Found coins"), YuvomiClient.newIdempotencyKey())
        assertEquals(account.balanceMinor + 5, alex.money().accounts.first { it.id == emmaId }.balanceMinor)
        alex.book(emmaId, "debit", "0.05", null, YuvomiClient.newIdempotencyKey())
        assertEquals(account.balanceMinor, alex.money().accounts.first { it.id == emmaId }.balanceMinor)
        val entries = alex.moneyLedger(emmaId, 2)
        assertEquals(listOf(-5L, 5L), entries.map { it.delta })
        assertTrue(entries.all { it.minorUnit == 2 && it.currency == "EUR" })
        assertTrue(leo.moneyLedger(emmaId, 5).isEmpty())
        assertEquals("insufficient_funds", expectHttp(400) { alex.book(emmaId, "debit", "900.00", null, YuvomiClient.newIdempotencyKey()) }.reason)
        expectHttp(400) { alex.book(emmaId, "credit", "0.001", null, YuvomiClient.newIdempotencyKey()) }
        expectHttp(403) { emma.book(emmaId, "credit", "1.00", null, YuvomiClient.newIdempotencyKey()) }
    }

    /** REW-C8 (money requests through POST /rewards/redemptions and PATCH /rewards/redemptions/{id}). */
    @Test fun `REW-C8 a withdrawal request books nothing until decided`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (emma, emmaId) = api("emma")
        val (leo, _) = api("leo")
        val before = alex.money().accounts.first { it.id == emmaId }.balanceMinor
        val request = emma.requestMoney("withdrawal", "0.01", "sweets", YuvomiClient.newIdempotencyKey())
        assertEquals("withdrawal", request.kind)
        assertEquals(1L, request.cost)
        assertEquals(before, alex.money().accounts.first { it.id == emmaId }.balanceMinor)
        val listed = alex.redemptions(pendingOnly = true).first { it.id == request.id }
        assertEquals(2, listed.minorUnit)
        assertEquals(before, listed.userBalance)
        assertEquals("cancelled", emma.decide(request.id, "cancel").status)
        assertEquals(before, alex.money().accounts.first { it.id == emmaId }.balanceMinor)
        assertEquals("no_money_account", expectHttp(400) { leo.requestMoney("deposit", "1.00", null, YuvomiClient.newIdempotencyKey()) }.reason)
        assertEquals("insufficient_funds", expectHttp(400) { emma.requestMoney("withdrawal", "900.00", null, YuvomiClient.newIdempotencyKey()) }.reason)
    }

    /** REW-C9 (POST, DELETE /rewards/money/accounts, PUT, DELETE /rewards/money/plans/{userId}). */
    @Test fun `REW-C9 an account is opened, gets a plan, loses it and is closed`() = runBlocking<Unit> {
        val (alex, _) = api("alex")
        val (leo, leoId) = api("leo")
        assertTrue(alex.money().candidates.any { it.id == leoId })
        alex.openAccount(leoId, YuvomiClient.newIdempotencyKey())
        try {
            assertEquals("money_account_exists", expectHttp(409) { alex.openAccount(leoId, YuvomiClient.newIdempotencyKey()) }.reason)
            val opened = alex.money().accounts.first { it.id == leoId }
            assertEquals(0L, opened.balanceMinor)
            assertNull(opened.plan)
            assertTrue(opened.closable)
            assertEquals(listOf(leoId), leo.money().accounts.map { it.id })

            // Paused, so no first credit is booked today.
            alex.savePlan(leoId, RewardsApi.PlanFields("2.50", "weekly", 6, paused = true))
            val plan = alex.money().accounts.first { it.id == leoId }.plan!!
            assertEquals(250L, plan.amountMinor)
            assertEquals("weekly", plan.frequency)
            assertEquals(6, plan.anchorDay)
            assertTrue(plan.paused)
            assertFalse(alex.money().accounts.first { it.id == leoId }.closable)
            assertEquals("money_account_not_empty", expectHttp(409) { alex.closeAccount(leoId) }.reason)
            expectHttp(400) { alex.savePlan(leoId, RewardsApi.PlanFields("2.50", "weekly", 8, paused = true)) }
            expectHttp(403) { leo.savePlan(leoId, RewardsApi.PlanFields("9.00", "monthly", 1, paused = false)) }

            alex.removePlan(leoId)
            assertEquals("rewards_money_plan_not_found", expectHttp(404) { alex.removePlan(leoId) }.reason)
        } finally {
            runCatching { alex.removePlan(leoId) }
            alex.closeAccount(leoId)
        }
        assertEquals("rewards_money_account_not_found", expectHttp(404) { alex.closeAccount(leoId) }.reason)
        assertTrue(alex.money().accounts.none { it.id == leoId })
    }
}
