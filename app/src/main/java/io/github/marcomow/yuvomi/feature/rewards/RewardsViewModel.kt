package io.github.marcomow.yuvomi.feature.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** What the rewards screens need from the shell; an interface so the view model is tested without one. */
interface RewardsHost {
    val strings: Strings

    /** `isAdmin()`: parents decide requests, manage the catalogue and the money. */
    val isAdmin: Boolean

    /** `readOnly()`: `rewards: read` keeps everything visible but no action. */
    val readOnly: Boolean

    val me: Int

    suspend fun notify(message: String, action: String? = null): Boolean
}

/** docs/spec/rewards.md: the points and catalogue tabs and every form they open. */
class RewardsViewModel(private val container: AppContainer, private val host: RewardsHost) : ViewModel() {

    data class UiState(
        val bundle: RewardsBundle = RewardsBundle(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        /** `overview` or `catalog` (REW-R1). */
        val tab: String = TAB_POINTS,
        /** Requests a decision is on its way for. */
        val deciding: Set<Int> = emptySet(),
    ) {
        val offline: Boolean get() = cachedAt != null
        val balances: List<RewardBalance> get() = bundle.overview.balances
        fun balanceOf(id: Int): Int = balances.firstOrNull { it.id == id }?.balance ?: 0
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: RewardsApi? get() = container.session.connection?.client?.let(::RewardsApi)
    val money = RewardsMoney(Locale.forLanguageTag(host.strings.locale))
    private val strings get() = host.strings
    var lastLoaded = 0L
        private set
    private var loadSeq = 0

    init {
        container.cache.read(CACHE_KEY)?.let { cached ->
            runCatching { ApiJson.decodeFromString(RewardsBundle.serializer(), cached.json) }.onSuccess { bundle ->
                _state.update { it.copy(bundle = bundle, loading = false) }
            }
        }
        refresh()
    }

    fun setTab(tab: String) = _state.update { it.copy(tab = tab) }

    /** `renderCurrentTab`: overview, requests, recent entries, money and the catalogue (REW-R2). */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val seq = ++loadSeq
        _state.update { it.copy(refreshing = pulled, errorKey = null, loading = it.loading || it.errorKey != null) }
        viewModelScope.launch {
            try {
                val bundle = load(api)
                if (seq != loadSeq) return@launch
                container.cache.write(CACHE_KEY, ApiJson.encodeToString(RewardsBundle.serializer(), bundle))
                lastLoaded = System.currentTimeMillis()
                _state.update { it.copy(bundle = bundle, loading = false, refreshing = false, cachedAt = null) }
            } catch (e: ApiProblem) {
                if (seq != loadSeq) return@launch
                _state.update {
                    val hasData = !it.loading
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_KEY)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        errorKey = if (hasData) null else LOAD_ERROR,
                    )
                }
                if (!state.value.loading && state.value.errorKey == null && e !is ApiProblem.Network) host.notify(strings.t(RewardsRules.errorKey(e)))
            }
        }
    }

    private suspend fun load(api: RewardsApi): RewardsBundle = coroutineScope {
        val overview = async { api.overview() }
        val catalog = async { if (host.isAdmin) api.catalog(all = true) else null }
        // `loadOverview`: parents see every open request, everyone else only their own.
        val requests = async { api.redemptions(pendingOnly = host.isAdmin) }
        val recent = async { runCatching { api.ledger(limit = RECENT_ROWS) }.getOrNull()?.take(RECENT_ROWS) }
        val money = async { runCatching { api.money() }.getOrNull() }
        val o = overview.await()
        val me = o.me ?: host.me
        val pending = requests.await().let { list -> if (host.isAdmin) list else list.filter { it.userId == me && it.status == "pending" } }
        RewardsBundle(o, catalog.await() ?: o.catalog, pending, recent.await(), money.await())
    }

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(strings.t("common.errorOfflineMutation"))
        return true
    }

    /** Runs a write; returns the sentence of its failure, or null. */
    private suspend fun write(fallback: String = "common.errorGeneric", block: suspend (RewardsApi) -> Unit): String? {
        if (state.value.offline) return strings.t("common.errorOfflineMutation")
        val api = api ?: return strings.t("common.errorGeneric")
        return try {
            block(api)
            null
        } catch (e: ApiProblem) {
            strings.t(RewardsRules.errorKey(e, fallback))
        }
    }

    // ── REW-R8: redeem ──

    /** The rewards the redeem form offers (`openRedeemModal`): active and not sold out. */
    fun redeemable(): List<RewardItem> = state.value.bundle.overview.catalog.filter(RewardsRules::isRedeemable)

    /** The member the form starts with: the given one, else me when I collect, else the first. */
    fun defaultMember(memberId: Int?): Int? {
        val members = state.value.balances
        val me = state.value.bundle.overview.me ?: host.me
        return memberId ?: (if (members.any { it.id == me }) me else members.firstOrNull()?.id)
    }

    suspend fun redeem(memberId: Int, itemId: Int, note: String): String? {
        val item = redeemable().firstOrNull { it.id == itemId } ?: return strings.t("rewards.errorRewardGone")
        if (state.value.balanceOf(memberId) - item.cost < 0) return strings.t("rewards.notEnoughPoints")
        val error = write("rewards.redeemError") {
            it.redeem(itemId, memberId, note.trim().ifEmpty { null }, YuvomiClient.newIdempotencyKey())
        } ?: run {
            toast(if (host.isAdmin) "rewards.toastRedeemed" else "rewards.toastRequested")
            refresh()
            return null
        }
        return error
    }

    // ── REW-R6: decide with undo ──

    fun decide(r: Redemption, action: String) {
        if (state.value.deciding.contains(r.id)) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val api = api ?: return@launch
            _state.update { it.copy(deciding = it.deciding + r.id) }
            try {
                api.decide(r.id, action)
                // The decided request leaves the list at once; the reload confirms it.
                _state.update { s -> s.copy(bundle = s.bundle.copy(redemptions = s.bundle.redemptions.filter { it.id != r.id })) }
                refresh()
                container.scope.launch {
                    if (host.notify(RewardsRules.decisionSentence(r, action, strings), strings.t("common.undo"))) {
                        try {
                            api.decide(r.id, "reopen")
                        } catch (e: ApiProblem) {
                            host.notify(strings.t(RewardsRules.errorKey(e)))
                        }
                        refresh()
                    }
                }
            } catch (e: ApiProblem) {
                val reason = (e as? ApiProblem.Http)?.reason
                val message = when (reason) {
                    "out_of_stock" -> strings.t("rewards.outOfStock")
                    "insufficient_funds" -> strings.t("rewards.money.insufficientOnApprove")
                    else -> strings.t(RewardsRules.errorKey(e))
                }
                host.notify(message)
                if (RewardsRules.staleAfterDecision(e)) refresh()
            } finally {
                _state.update { it.copy(deciding = it.deciding - r.id) }
            }
        }
    }

    // ── REW-R10: bonus ──

    suspend fun bonus(memberId: Int, credit: Boolean, pointsText: String, reason: String): String? {
        val amount = pointsText.trim().toIntOrNull()
        if (amount == null || amount < 1) return strings.t("android.rewards.pointsWhole")
        return write {
            it.bonus(memberId, if (credit) amount else -amount, reason.trim().ifEmpty { null }, YuvomiClient.newIdempotencyKey())
        } ?: run {
            toast("rewards.toastBonus")
            refresh()
            null
        }
    }

    // ── REW-R14, R15: catalogue ──

    data class RewardForm(
        val icon: String = "",
        val name: String = "",
        val cost: String = "",
        val quantity: String = "",
        val description: String = "",
        val active: Boolean = true,
    ) {
        companion object {
            fun of(item: RewardItem) = RewardForm(
                item.icon.orEmpty(), item.name, item.cost.toString(), item.quantity?.toString().orEmpty(), item.description.orEmpty(), item.isActive,
            )
        }
    }

    suspend fun saveReward(item: RewardItem?, form: RewardForm): String? {
        val name = form.name.trim()
        if (name.isEmpty()) return strings.t("rewards.nameRequired")
        val cost = form.cost.trim().toDoubleOrNull()?.toInt()
        if (cost == null || cost < 1) return strings.t("rewards.costRequired")
        val quantityText = form.quantity.trim()
        val quantity = if (quantityText.isEmpty()) null else quantityText.toDoubleOrNull()?.toInt()
        if (quantityText.isNotEmpty() && (quantity == null || quantity < 1)) return strings.t("rewards.quantityInvalid")
        val fields = RewardsApi.RewardFields(
            name = name,
            cost = cost,
            quantity = quantity,
            icon = form.icon.trim().ifEmpty { null },
            description = form.description.trim().ifEmpty { null },
            isActive = if (item != null) form.active else null,
        )
        return write {
            if (item != null) it.updateReward(item.id, fields) else it.createReward(fields, YuvomiClient.newIdempotencyKey())
        } ?: run {
            toast("rewards.toastSaved")
            refresh()
            null
        }
    }

    suspend fun deleteReward(item: RewardItem): String? {
        val error = write {
            try {
                it.deleteReward(item.id)
            } catch (e: ApiProblem) {
                if (!RewardsRules.isAlreadyDeleted(e, RewardsRules.REWARD_GONE)) throw e
            }
        }
        if (error != null) return error
        toast("rewards.toastRewardDeleted")
        refresh()
        return null
    }

    // ── REW-R12: participants ──

    /** The sheet's list, or the sentence of its failure. */
    suspend fun participants(): Result<List<Participant>> {
        val api = api ?: return Result.failure(IllegalStateException())
        return try {
            Result.success(api.participants())
        } catch (e: ApiProblem) {
            Result.failure(IllegalStateException(strings.t(RewardsRules.errorKey(e, "rewards.loadError"))))
        }
    }

    suspend fun setParticipant(id: Int, enabled: Boolean): String? = write { it.setParticipant(id, enabled) }

    // ── REW-R11: a member's entries ──

    suspend fun memberLedger(id: Int): List<LedgerEntry> = runCatching { api?.ledger(userId = id, limit = 12) }.getOrNull().orEmpty()

    // ── REW-R16 to R22: pocket money ──

    fun ctx(account: MoneyAccount): RewardsMoney.Ctx = money.ctx(account.currency, account.minorUnit, state.value.bundle.money)

    fun planCtx(account: MoneyAccount): RewardsMoney.Ctx =
        account.plan?.let { money.ctx(it.currency, it.minorUnit, state.value.bundle.money) } ?: ctx(account)

    private fun readAmount(text: String, ctx: RewardsMoney.Ctx): Pair<String?, String?> {
        val problem = money.problem(text, ctx)
        if (problem != null) return null to money.problemText(problem, ctx, strings)
        return money.toDecimal(text) to null
    }

    suspend fun requestMoney(kind: String, account: MoneyAccount, amountText: String, note: String): String? {
        val ctx = ctx(account)
        val (amount, problem) = readAmount(amountText, ctx)
        if (amount == null) return problem
        if (kind == "withdrawal" && money.toMinor(amount, ctx) > account.balanceMinor) return strings.t("rewards.money.insufficient")
        return write { it.requestMoney(kind, amount, note.trim().ifEmpty { null }, YuvomiClient.newIdempotencyKey()) } ?: run {
            toast("rewards.toastRequested")
            refresh()
            null
        }
    }

    suspend fun book(account: MoneyAccount, credit: Boolean, amountText: String, reason: String): String? {
        val (amount, problem) = readAmount(amountText, ctx(account))
        if (amount == null) return problem
        val direction = if (credit && !account.former) "credit" else "debit"
        return write { it.book(account.id, direction, amount, reason.trim().ifEmpty { null }, YuvomiClient.newIdempotencyKey()) } ?: run {
            toast("rewards.toastSaved")
            refresh()
            null
        }
    }

    data class PlanForm(val amount: String, val weekly: Boolean, val weekday: Int, val monthDay: Int, val paused: Boolean)

    fun planForm(account: MoneyAccount): PlanForm {
        val plan = account.plan
        return PlanForm(
            amount = plan?.let { money.toInput(it.amountMinor, planCtx(account)) }.orEmpty(),
            weekly = plan?.frequency != "monthly",
            weekday = if (plan?.frequency == "weekly") plan.anchorDay else 1,
            monthDay = if (plan?.frequency == "monthly") plan.anchorDay else 1,
            paused = plan?.paused == true,
        )
    }

    suspend fun savePlan(account: MoneyAccount, form: PlanForm): String? {
        val (amount, problem) = readAmount(form.amount, planCtx(account))
        if (amount == null) return problem
        val plan = RewardsApi.PlanFields(amount, if (form.weekly) "weekly" else "monthly", if (form.weekly) form.weekday else form.monthDay, form.paused)
        return write { it.savePlan(account.id, plan) } ?: run {
            toast("rewards.toastSaved")
            refresh()
            null
        }
    }

    /** `removeMoneyPlan`: the failure is told, and the list reloads either way. */
    fun removePlan(account: MoneyAccount) = viewModelScope.launch {
        val error = write {
            try {
                it.removePlan(account.id)
            } catch (e: ApiProblem) {
                if (!RewardsRules.isAlreadyDeleted(e, RewardsRules.PLAN_GONE)) throw e
            }
        }
        host.notify(error ?: strings.t("rewards.toastSaved"))
        refresh()
    }

    suspend fun openAccount(memberId: Int): String? =
        write { it.openAccount(memberId, YuvomiClient.newIdempotencyKey()) } ?: run {
            toast("rewards.toastSaved")
            refresh()
            null
        }

    fun closeAccount(account: MoneyAccount) = viewModelScope.launch {
        val error = write {
            try {
                it.closeAccount(account.id)
            } catch (e: ApiProblem) {
                if (!RewardsRules.isAlreadyDeleted(e, RewardsRules.ACCOUNT_GONE)) throw e
            }
        }
        host.notify(error ?: strings.t("rewards.toastSaved"))
        refresh()
    }

    suspend fun moneyLedger(id: Int): List<MoneyEntry> = runCatching { api?.moneyLedger(id, 20) }.getOrNull().orEmpty()

    private fun toast(key: String) {
        container.scope.launch { host.notify(strings.t(key)) }
    }

    companion object {
        const val CACHE_KEY = "rewards-overview"
        const val LOAD_ERROR = "rewards.loadError"
        const val TAB_POINTS = "overview"
        const val TAB_CATALOG = "catalog"
        const val RECENT_ROWS = 6
    }
}
