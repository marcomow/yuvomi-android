package io.github.marcomow.yuvomi.feature.rewards

import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** docs/spec/rewards.md, "Upstream operations". */
class RewardsApi(private val client: YuvomiClient) {

    /** The fields of the reward form, as `openRewardModal` sends them (REW-R14). */
    data class RewardFields(
        val name: String,
        val cost: Int,
        /** null: unlimited. */
        val quantity: Int?,
        val icon: String?,
        val description: String?,
        /** Only on edit. */
        val isActive: Boolean? = null,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("cost", cost)
            put("quantity", quantity?.let(::JsonPrimitive) ?: JsonNull)
            put("icon", icon?.let(::JsonPrimitive) ?: JsonNull)
            put("description", description?.let(::JsonPrimitive) ?: JsonNull)
            if (isActive != null) put("is_active", isActive)
        }
    }

    data class PlanFields(val amount: String, val frequency: String, val anchorDay: Int, val paused: Boolean)

    suspend fun overview(): RewardOverview = client.call(RewardsOperation.OVERVIEW).decodeData(RewardOverview.serializer())

    suspend fun participants(): List<Participant> =
        client.call(RewardsOperation.PARTICIPANTS).decodeData(ListSerializer(Participant.serializer()))

    suspend fun setParticipant(userId: Int, enabled: Boolean) {
        client.call(RewardsOperation.PARTICIPANT_SET, mapOf("userId" to userId), body = buildJsonObject { put("enabled", enabled) })
    }

    suspend fun catalog(all: Boolean): List<RewardItem> =
        client.call(RewardsOperation.CATALOG, query = if (all) listOf("all" to "1") else emptyList())
            .decodeData(ListSerializer(RewardItem.serializer()))

    suspend fun createReward(fields: RewardFields, idempotencyKey: String): RewardItem =
        client.call(RewardsOperation.CATALOG_CREATE, body = fields.toJson(), idempotencyKey = idempotencyKey)
            .decodeData(RewardItem.serializer())

    suspend fun updateReward(id: Int, fields: RewardFields): RewardItem =
        client.call(RewardsOperation.CATALOG_UPDATE, mapOf("id" to id), body = fields.toJson()).decodeData(RewardItem.serializer())

    suspend fun deleteReward(id: Int) {
        client.call(RewardsOperation.CATALOG_DELETE, mapOf("id" to id))
    }

    suspend fun ledger(userId: Int? = null, limit: Int? = null): List<LedgerEntry> =
        client.call(
            RewardsOperation.LEDGER,
            query = listOfNotNull(userId?.let { "user_id" to it.toString() }, limit?.let { "limit" to it.toString() }),
        ).decodeData(ListSerializer(LedgerEntry.serializer()))

    /** `kind=all` (money too); [pendingOnly] for parents (`loadOverview`). */
    suspend fun redemptions(pendingOnly: Boolean): List<Redemption> =
        client.call(
            RewardsOperation.REDEMPTIONS,
            query = listOfNotNull(if (pendingOnly) "status" to "pending" else null, "kind" to "all"),
        ).decodeData(ListSerializer(Redemption.serializer()))

    suspend fun redeem(catalogId: Int, userId: Int?, note: String?, idempotencyKey: String): Redemption =
        client.call(RewardsOperation.REDEEM, body = buildJsonObject {
            put("catalog_id", catalogId)
            if (userId != null) put("user_id", userId)
            if (note != null) put("note", note)
        }, idempotencyKey = idempotencyKey).decodeData(Redemption.serializer())

    /** A pocket money request: `withdrawal` or `deposit`, the amount as a decimal string. */
    suspend fun requestMoney(kind: String, amount: String, note: String?, idempotencyKey: String): Redemption =
        client.call(RewardsOperation.REDEEM, body = buildJsonObject {
            put("kind", kind)
            put("amount", amount)
            if (note != null) put("note", note)
        }, idempotencyKey = idempotencyKey).decodeData(Redemption.serializer())

    /** `fulfill`, `reject`, `cancel` or `reopen`. */
    suspend fun decide(id: Int, action: String): Redemption =
        client.call(RewardsOperation.DECIDE, mapOf("id" to id), body = buildJsonObject { put("action", action) })
            .decodeData(Redemption.serializer())

    suspend fun bonus(userId: Int, delta: Int, reason: String?, idempotencyKey: String) {
        client.call(RewardsOperation.BONUS, body = buildJsonObject {
            put("user_id", userId)
            put("delta", delta)
            if (reason != null) put("reason", reason)
        }, idempotencyKey = idempotencyKey)
    }

    suspend fun money(): MoneyState = client.call(RewardsOperation.MONEY).decodeData(MoneyState.serializer())

    suspend fun moneyLedger(userId: Int, limit: Int): List<MoneyEntry> =
        client.call(RewardsOperation.MONEY_LEDGER, query = listOf("user_id" to userId.toString(), "limit" to limit.toString()))
            .decodeData(ListSerializer(MoneyEntry.serializer()))

    /** `credit` or `debit`. */
    suspend fun book(userId: Int, direction: String, amount: String, reason: String?, idempotencyKey: String) {
        client.call(RewardsOperation.MONEY_ENTRY, body = buildJsonObject {
            put("user_id", userId)
            put("amount", amount)
            put("direction", direction)
            if (reason != null) put("reason", reason)
        }, idempotencyKey = idempotencyKey)
    }

    suspend fun openAccount(userId: Int, idempotencyKey: String) {
        client.call(RewardsOperation.MONEY_ACCOUNT_OPEN, body = buildJsonObject { put("user_id", userId) }, idempotencyKey = idempotencyKey)
    }

    suspend fun closeAccount(userId: Int) {
        client.call(RewardsOperation.MONEY_ACCOUNT_CLOSE, mapOf("userId" to userId))
    }

    suspend fun savePlan(userId: Int, plan: PlanFields) {
        client.call(RewardsOperation.MONEY_PLAN_SAVE, mapOf("userId" to userId), body = buildJsonObject {
            put("amount", plan.amount)
            put("frequency", plan.frequency)
            put("anchor_day", plan.anchorDay)
            put("paused", plan.paused)
        })
    }

    suspend fun removePlan(userId: Int) {
        client.call(RewardsOperation.MONEY_PLAN_DELETE, mapOf("userId" to userId))
    }
}
