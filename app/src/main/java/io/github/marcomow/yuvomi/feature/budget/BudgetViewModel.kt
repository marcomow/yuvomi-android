package io.github.marcomow.yuvomi.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.api.decodeData
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.Base64
import java.util.Locale

/**
 * What the budget screens need from the shell. An interface so the view model is tested
 * without one.
 */
interface BudgetHost {
    val strings: Strings

    fun today(): LocalDate

    /** Shows [message]; true when the member tapped [action]. */
    suspend fun notify(message: String, action: String? = null): Boolean

    /** `budget: write` (`readOnly()` of the page is its negation). */
    val writable: Boolean

    /** The Documents right, for the receipts field (`document-attach.js`). */
    val documentsAccess: String
}

enum class BudgetTab(val id: String) { OVERVIEW("budget"), ACCOUNTS("accounts"), PLAN("plan"), LOANS("loans"), REPORTS("reports") }

/** docs/spec/budget.md: the whole page's state, shared by its tabs and editors. */
class BudgetViewModel(
    private val container: AppContainer,
    private val host: BudgetHost,
    initialTab: BudgetTab = BudgetTab.OVERVIEW,
) : ViewModel() {

    data class UiState(
        val tab: BudgetTab = BudgetTab.OVERVIEW,
        val month: String = "",
        val today: LocalDate = LocalDate.now(),
        val prefs: BudgetPreferences = BudgetPreferences(),
        val money: Money = Money(Locale.ENGLISH),
        val meta: BudgetMeta = BudgetMeta(),
        val members: List<BudgetMember> = emptyList(),
        val entries: List<BudgetEntry> = emptyList(),
        val summary: BudgetSummary = BudgetSummary(),
        val previous: BudgetSummary? = null,
        val loans: LoansAnswer = LoansAnswer(),
        val accounts: AccountsAnswer = AccountsAnswer(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadError: Boolean = false,
        val cachedAt: Long? = null,
        val scope: String = "mine",
        val filters: BudgetLedger.Filters = BudgetLedger.Filters(),
        /** The statement came from an account's read view: its chip leads back to Accounts (S10-F3). */
        val accountFilterFromAccounts: Boolean = false,
        val responsibleName: String = "",
        val ledgerSort: String = "date",
        val groupByResponsible: Boolean = false,
        val expensesOnly: Boolean = false,
        val categoriesExpanded: Boolean = false,
        val plannedExpandedMonth: String? = null,
        val query: String = "",
        val results: List<BudgetEntry>? = null,
        val truncated: Boolean = false,
        val searchError: Boolean = false,
        val pendingDeletes: Set<Int> = emptySet(),
        val showArchived: Boolean = false,
        val pendingAccountDeletes: Set<Int> = emptySet(),
        val loanStatus: String = "active",
        val loanFilterId: Int? = null,
        val loanSort: String = "start",
        val loanPaymentsExpanded: Boolean = false,
        val pendingLoanDeletes: Set<Int> = emptySet(),
        val pendingPaymentDeletes: Set<Int> = emptySet(),
        val range: String = "month",
        val anchor: LocalDate = LocalDate.now(),
        val stats: BudgetStats? = null,
        val statsPrevious: BudgetStats? = null,
        val statsLoading: Boolean = false,
        val statsError: Boolean = false,
        val plans: BudgetPlans? = null,
        val plansLoading: Boolean = false,
        val plansError: Boolean = false,
        val busy: Boolean = false,
    ) {
        val offline: Boolean get() = cachedAt != null
        val personal: Boolean get() = prefs.budgetMode == "personal"
        val currency: String get() = money.usable(prefs.currency)

        /** `viewCurrency`: an account's statement stands in that account's currency (#2302). */
        val viewCurrency: String get() = filters.account?.let(::accountCurrency) ?: currency

        /** `accountCurrency`. */
        fun accountCurrency(id: Int?): String =
            id?.let { i -> accounts.accounts.firstOrNull { it.id == i }?.currency }?.takeIf { it.isNotEmpty() } ?: currency

        fun accountName(id: Int?): String = id?.let { i -> accounts.accounts.firstOrNull { it.id == i }?.name }.orEmpty()

        val shownEntries: List<BudgetEntry> get() = entries.filter { it.id == null || it.id !in pendingDeletes }
        val shownResults: List<BudgetEntry>? get() = results?.filter { it.id == null || it.id !in pendingDeletes }
        val shownAccounts: List<BudgetAccount> get() = accounts.accounts.filter { it.id !in pendingAccountDeletes }
        val shownLoans: List<BudgetLoan>
            get() = loans.loans.filter { it.id !in pendingLoanDeletes }
                .map { l -> if (l.payments.any { it.id in pendingPaymentDeletes }) l.copy(payments = l.payments.filter { it.id !in pendingPaymentDeletes }) else l }

        val expenseCategoryKeys: List<String> get() = meta.expenseCategories.map { it.key }

        fun subcategories(category: String): List<BudgetSubcategory> = meta.subcategories[category].orEmpty()

        /** `filteredLoans`. */
        val visibleLoans: List<BudgetLoan>
            get() = BudgetLedger.sortLoans(
                shownLoans.filter { (loanStatus == "all" || it.status == loanStatus) && (loanFilterId == null || it.id == loanFilterId) },
                loanSort,
            )
    }

    private val strings get() = host.strings
    private val api: BudgetApi? get() = container.session.connection?.client?.let(::BudgetApi)

    private val _state = MutableStateFlow(UiState(tab = initialTab, today = host.today()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val writable: Boolean get() = host.writable
    val documentsAccess: String get() = host.documentsAccess

    /** What the entry editor opens with: an entry to edit, or a new one of [type] in [period]. */
    data class EntryRequest(val entry: BudgetEntry? = null, val type: String = "expense", val period: Pair<LocalDate, LocalDate>? = null)

    /** The editors' input, set right before navigating to them. */
    var entryRequest: EntryRequest = EntryRequest()
    var transferRequest: BudgetEntry? = null
    var accountRequest: BudgetAccount? = null
    var loanRequest: BudgetLoan? = null

    private var monthSeq = 0
    private var monthInFlight: String? = null
    private var searchSeq = 0
    private var searchJob: Job? = null
    private var statsSeq = 0
    private var plansSeq = 0
    var lastLoaded = 0L
        private set

    @Serializable
    private data class ViewPrefs(
        val expensesOnly: Boolean = false,
        val groupByResponsible: Boolean = false,
        val ledgerSort: String = "date",
        val loanSort: String = "start",
    )

    @Serializable
    private data class MonthCache(val entries: JsonElement, val summary: JsonElement, val previous: JsonElement? = null, val loans: JsonElement? = null)

    init {
        val today = host.today()
        val month = today.toString().take(7)
        val view = runCatching { container.cache.readGlobal(VIEW_KEY)?.let { ApiJson.decodeFromString(ViewPrefs.serializer(), it) } }.getOrNull() ?: ViewPrefs()
        _state.update {
            it.copy(
                month = month,
                anchor = today,
                expensesOnly = view.expensesOnly,
                groupByResponsible = view.groupByResponsible,
                ledgerSort = if (view.ledgerSort in listOf("date", "amount")) view.ledgerSort else "date",
                loanSort = if (view.loanSort in BudgetLedger.LOAN_SORTS) view.loanSort else "start",
            )
        }
        readCache(month)
        start()
    }

    private fun saveView() {
        val s = state.value
        runCatching {
            container.cache.writeGlobal(VIEW_KEY, ApiJson.encodeToString(ViewPrefs.serializer(), ViewPrefs(s.expensesOnly, s.groupByResponsible, s.ledgerSort, s.loanSort)))
        }
    }

    private fun moneyFor(prefs: BudgetPreferences): Money =
        Money.of(RegionPresets.numberLocaleFor(prefs.region, prefs.currency, prefs.dateFormat, prefs.timeFormat), strings.locale, prefs.currency)

    private fun readCache(month: String) {
        val cache = container.cache
        runCatching { cache.read(PREFS_KEY)?.let { ApiJson.decodeFromString(BudgetPreferences.serializer(), it.json) } }.getOrNull()?.let { p ->
            _state.update { it.copy(prefs = p, money = moneyFor(p)) }
        }
        runCatching { cache.read(META_KEY)?.let { ApiJson.parseToJsonElement(it.json).decodeData(BudgetMeta.serializer()) } }.getOrNull()?.let { m ->
            _state.update { it.copy(meta = m) }
        }
        runCatching { cache.read(ACCOUNTS_KEY)?.let { ApiJson.parseToJsonElement(it.json).decodeData(AccountsAnswer.serializer()) } }.getOrNull()?.let { a ->
            _state.update { it.copy(accounts = a) }
        }
        runCatching { cache.read(monthKey(month, state.value.scope, null))?.let { ApiJson.decodeFromString(MonthCache.serializer(), it.json) } }.getOrNull()?.let { c ->
            runCatching { applyMonth(month, decodeMonth(c)) }
        }
    }

    private data class MonthData(val entries: List<BudgetEntry>, val summary: BudgetSummary, val previous: BudgetSummary?, val loans: LoansAnswer?)

    private fun decodeMonth(c: MonthCache) = MonthData(
        c.entries.decodeData(ListSerializer(BudgetEntry.serializer())),
        c.summary.decodeData(BudgetSummary.serializer()),
        c.previous?.let { runCatching { it.decodeData(BudgetSummary.serializer()) }.getOrNull() },
        c.loans?.let { runCatching { it.decodeData(LoansAnswer.serializer()) }.getOrNull() },
    )

    private fun applyMonth(month: String, d: MonthData) {
        _state.update {
            it.copy(
                month = month,
                entries = d.entries,
                summary = d.summary,
                previous = d.previous,
                loans = d.loans ?: it.loans,
                loading = false,
                loadError = false,
            )
        }
    }

    private fun monthKey(month: String, scope: String, account: Int?) = "budget-month-$month-$scope-${account ?: 0}"

    private fun view(s: UiState = state.value) = BudgetApi.View(s.personal, s.scope, s.filters.account)

    /** `render`: preferences, members and categories first, then the month and the accounts. */
    private fun start() {
        val api = api ?: return
        viewModelScope.launch {
            coroutineScope {
                val prefs = async { runCatching { api.preferences() } }
                val members = async { runCatching { api.members() } }
                val meta = async { loadMeta(api) }
                prefs.await().onSuccess { p ->
                    container.cache.write(PREFS_KEY, ApiJson.encodeToString(BudgetPreferences.serializer(), p))
                    _state.update { it.copy(prefs = p, money = moneyFor(p)) }
                }
                members.await().onSuccess { m -> _state.update { it.copy(members = m) } }
                meta.await()
            }
            coroutineScope {
                launch { loadMonth(state.value.month) }
                launch { loadAccounts() }
            }
            when (state.value.tab) {
                BudgetTab.REPORTS -> loadStats()
                BudgetTab.PLAN -> loadPlans()
                else -> Unit
            }
            if (metaFailed && !state.value.loadError) host.notify(strings.t("budget.metaLoadError"))
        }
    }

    private var metaFailed = false

    private suspend fun loadMeta(api: BudgetApi) {
        try {
            val raw = api.metaRaw()
            val meta = raw.decodeData(BudgetMeta.serializer())
            container.cache.write(META_KEY, raw.toString())
            metaFailed = false
            _state.update { it.copy(meta = meta) }
        } catch (e: ApiProblem) {
            metaFailed = true
        }
    }

    /** `loadBudgetMeta` after a category change (the manager's `onChanged`). */
    fun reloadMeta() {
        val api = api ?: return
        viewModelScope.launch { loadMeta(api) }
    }

    /** `requestedMonth`: the month the page is on its way to, else the shown one. */
    val requestedMonth: String get() = monthInFlight ?: state.value.month

    /**
     * `loadMonth`: entries, the month's and the previous month's summary, and the loans, as one
     * answer. The last request wins, not the last answer (#1781). Returns false when overtaken.
     */
    suspend fun loadMonth(month: String, pulled: Boolean = false): Boolean {
        val api = api ?: return false
        val seq = ++monthSeq
        monthInFlight = month
        _state.update { it.copy(refreshing = pulled) }
        val s = state.value
        val v = view(s)
        try {
            val data = coroutineScope {
                val entries = async { api.entriesRaw(month, v) }
                val summary = async { api.summaryRaw(month, v) }
                val previous = async { api.summaryRaw(BudgetLedger.addMonths(month, -1), v) }
                val loans = async { api.loansRaw() }
                MonthCache(entries.await(), summary.await(), previous.await(), loans.await())
            }
            if (seq != monthSeq) return false
            val decoded = decodeMonth(data)
            container.cache.write(monthKey(month, s.scope, s.filters.account), ApiJson.encodeToString(MonthCache.serializer(), data))
            lastLoaded = System.currentTimeMillis()
            applyMonth(month, decoded)
            _state.update { it.copy(refreshing = false, cachedAt = null, today = host.today()) }
            if (state.value.query.isNotEmpty()) runSearch(state.value.query)
            return true
        } catch (e: ApiProblem) {
            if (seq != monthSeq) return false
            val cached = if (e is ApiProblem.Network) container.cache.read(monthKey(month, s.scope, s.filters.account)) else null
            val decoded = cached?.let { c -> runCatching { decodeMonth(ApiJson.decodeFromString(MonthCache.serializer(), c.json)) }.getOrNull() }
            if (decoded != null) {
                applyMonth(month, decoded)
                _state.update { it.copy(refreshing = false, cachedAt = cached.savedAt) }
            } else if (e is ApiProblem.Network && state.value.month == month && state.value.entries.isNotEmpty()) {
                _state.update { it.copy(refreshing = false, loading = false, cachedAt = it.cachedAt ?: System.currentTimeMillis()) }
            } else {
                // A failed load is a result too: it shows the error state, never an empty month (P0).
                _state.update {
                    it.copy(
                        month = month, entries = emptyList(), summary = BudgetSummary(month = month), previous = null,
                        loading = false, refreshing = false, loadError = true,
                    )
                }
            }
            return true
        } finally {
            if (seq == monthSeq) monthInFlight = null
        }
    }

    /** `reloadMonth`. */
    fun reload(pulled: Boolean = false) {
        viewModelScope.launch {
            loadMonth(requestedMonth, pulled)
            if (pulled) {
                loadAccounts()
                when (state.value.tab) {
                    BudgetTab.REPORTS -> loadStats()
                    BudgetTab.PLAN -> loadPlans()
                    else -> Unit
                }
            }
        }
    }

    /** The load error's retry: the month, and the categories when they failed too. */
    fun retry() {
        val api = api ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            if (metaFailed) loadMeta(api)
            loadMonth(requestedMonth)
            if (metaFailed && !state.value.loadError) host.notify(strings.t("budget.metaLoadError"))
        }
    }

    suspend fun loadAccounts() {
        val api = api ?: return
        try {
            val raw = api.accountsRaw()
            val answer = raw.decodeData(AccountsAnswer.serializer())
            container.cache.write(ACCOUNTS_KEY, raw.toString())
            _state.update { s -> s.copy(accounts = answer.copy(accounts = answer.accounts.filter { it.id !in s.pendingAccountDeletes })) }
        } catch (_: ApiProblem) {
            // `loadAccounts` logs only: the names and balances of the last answer stay.
        }
    }

    // ── Period and tabs ──

    /** `stepPeriod`. */
    fun step(dir: Int) {
        val s = state.value
        if (s.tab == BudgetTab.REPORTS) {
            _state.update { it.copy(anchor = BudgetLedger.stepAnchor(it.anchor, it.range, dir)) }
            viewModelScope.launch { loadStats() }
            return
        }
        viewModelScope.launch {
            if (loadMonth(BudgetLedger.addMonths(requestedMonth, dir)) && state.value.tab == BudgetTab.PLAN) loadPlans()
        }
    }

    /** "Current": back to the period that contains today. */
    fun jumpToCurrent() {
        val today = host.today()
        if (state.value.tab == BudgetTab.REPORTS) {
            _state.update { it.copy(anchor = today) }
            viewModelScope.launch { loadStats() }
            return
        }
        val m = today.toString().take(7)
        if (m == requestedMonth) return
        viewModelScope.launch {
            if (loadMonth(m) && state.value.tab == BudgetTab.PLAN) loadPlans()
        }
    }

    /** `syncCurrentButton`: is the current period already on screen? */
    fun showsCurrent(s: UiState = state.value): Boolean {
        val today = host.today()
        return when (s.tab) {
            BudgetTab.REPORTS -> when (s.range) {
                "year" -> s.anchor.year == today.year
                "month" -> s.anchor.toString().take(7) == today.toString().take(7)
                else -> s.stats?.takeIf { it.range == "week" && it.from.isNotEmpty() }?.let { today.toString() in it.from..it.to } ?: (s.anchor == today)
            }
            BudgetTab.OVERVIEW, BudgetTab.PLAN -> s.month == today.toString().take(7)
            else -> true
        }
    }

    /** `changeTab`: one time line across tabs; the month becomes the report anchor and back. */
    fun selectTab(tab: BudgetTab) {
        val prev = state.value.tab
        if (prev == tab) return
        _state.update { it.copy(tab = tab) }
        if (tab == BudgetTab.REPORTS) {
            _state.update { it.copy(anchor = BudgetLedger.anchorForMonth(requestedMonth, host.today())) }
            viewModelScope.launch { loadStats() }
            return
        }
        viewModelScope.launch {
            if (prev == BudgetTab.REPORTS) {
                val ym = state.value.anchor.toString().take(7)
                if (ym != requestedMonth) loadMonth(ym)
            }
            when (tab) {
                BudgetTab.PLAN -> loadPlans()
                BudgetTab.ACCOUNTS -> loadAccounts()
                else -> Unit
            }
        }
    }

    fun setRange(range: String) {
        if (range !in listOf("week", "month", "year") || range == state.value.range) return
        _state.update { it.copy(range = range) }
        if (state.value.tab == BudgetTab.REPORTS) viewModelScope.launch { loadStats() }
    }

    /** Mine / household (#476/#505), personal mode only. */
    fun setScope(scope: String) {
        if (scope == state.value.scope) return
        _state.update { it.copy(scope = scope) }
        viewModelScope.launch {
            loadMonth(requestedMonth)
            when (state.value.tab) {
                BudgetTab.REPORTS -> loadStats()
                BudgetTab.PLAN -> loadPlans()
                else -> Unit
            }
        }
    }

    // ── Overview: view options and filters ──

    fun toggleExpensesOnly() {
        _state.update { it.copy(expensesOnly = !it.expensesOnly) }
        saveView()
    }

    fun toggleGroupByResponsible() {
        _state.update { it.copy(groupByResponsible = !it.groupByResponsible) }
        saveView()
    }

    fun setLedgerSort(sort: String) {
        _state.update { it.copy(ledgerSort = if (sort == "amount") "amount" else "date") }
        saveView()
    }

    fun toggleCategoriesExpanded() = _state.update { it.copy(categoriesExpanded = !it.categoriesExpanded) }

    fun togglePlanned() = _state.update { it.copy(plannedExpandedMonth = if (it.plannedExpandedMonth == it.month) null else it.month) }

    /** `toggleCategoryFilter`: the subcategory filter goes with it. */
    fun toggleCategory(category: String) = _state.update {
        it.copy(filters = it.filters.copy(category = if (it.filters.category == category) null else category, subcategory = null))
    }

    fun toggleSubcategory(category: String, key: String) = _state.update {
        val same = it.filters.subcategory == (category to key)
        it.copy(filters = it.filters.copy(subcategory = if (same) null else category to key))
    }

    /** `toggleResponsibleFilter`: the name is remembered for a later month without that person (#1207). */
    fun toggleResponsible(person: BudgetPerson) = _state.update { s ->
        val id = if (s.filters.responsible == person.id) null else person.id
        val name = if (id == null) "" else s.members.firstOrNull { it.id == id }?.displayName ?: person.displayName.orEmpty()
        s.copy(filters = s.filters.copy(responsible = id), responsibleName = name)
    }

    /** The account statement (`showAccountStatement`, the tools menu). The server filters. */
    fun setAccountFilter(id: Int?, fromAccounts: Boolean = false) {
        if (id == state.value.filters.account && fromAccounts == state.value.accountFilterFromAccounts) {
            if (fromAccounts) _state.update { it.copy(tab = BudgetTab.OVERVIEW) }
            return
        }
        _state.update { it.copy(filters = it.filters.copy(account = id), accountFilterFromAccounts = fromAccounts && id != null, tab = BudgetTab.OVERVIEW) }
        viewModelScope.launch { loadMonth(requestedMonth) }
    }

    /** The account chip's x: back to Accounts when the statement came from there. */
    fun clearAccountFilter() {
        val back = state.value.accountFilterFromAccounts
        _state.update { it.copy(filters = it.filters.copy(account = null), accountFilterFromAccounts = false, tab = if (back) BudgetTab.ACCOUNTS else it.tab) }
        viewModelScope.launch { loadMonth(requestedMonth) }
    }

    /** `clearBudgetFilters`. */
    fun clearFilters() {
        val reload = state.value.filters.account != null
        _state.update { it.copy(filters = BudgetLedger.Filters(), accountFilterFromAccounts = false, responsibleName = "") }
        if (reload) viewModelScope.launch { loadMonth(requestedMonth) }
    }

    // ── Ledger search (C6) ──

    fun setQuery(text: String) {
        val query = text.trim().take(100)
        _state.update { it.copy(query = text.take(100)) }
        searchJob?.cancel()
        if (query.isEmpty()) {
            searchSeq++
            _state.update { it.copy(results = null, searchError = false, truncated = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        val api = api ?: return
        val seq = ++searchSeq
        try {
            val result = api.search(query.trim(), view())
            if (seq != searchSeq) return
            _state.update { it.copy(results = result.entries, truncated = result.truncated, searchError = false) }
        } catch (e: ApiProblem) {
            if (seq != searchSeq) return
            _state.update { it.copy(results = emptyList(), truncated = false, searchError = true) }
        }
    }

    // ── Writes ──

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(strings.t("common.errorOfflineMutation"))
        return true
    }

    private suspend fun toast(e: Throwable, fallback: String = "common.errorOccurred") {
        host.notify(BudgetText.budgetError(e, strings, fallback).message)
    }

    sealed interface SaveResult {
        data object Saved : SaveResult
        data class Field(val field: String?, val message: String) : SaveResult
    }

    private suspend fun commitReceipts(api: BudgetApi, form: EntryForm): List<Int> {
        val ids = mutableListOf<Int>()
        for (r in form.receipts) {
            when (r) {
                is Receipt.Linked -> ids += r.documentId
                is Receipt.Pending -> {
                    val name = strings.t(
                        "budget.receiptDocumentName",
                        "title" to form.title.trim().ifEmpty { r.fileName },
                        "date" to form.date.toString(),
                    )
                    val dataUrl = "data:${r.mimeType};base64," + Base64.getEncoder().encodeToString(r.bytes)
                    ids += api.uploadReceipt(name, r.fileName, dataUrl, strings.t("documents.budgetFolder"), YuvomiClient.newIdempotencyKey()).id
                }
            }
        }
        return ids
    }

    /**
     * `openBudgetModal`'s save: create, or edit this entry, or (with [series]) the series from
     * here on. The caller asked the series question before (`recurringChoiceModal`).
     */
    suspend fun saveEntry(original: BudgetEntry?, form: EntryForm, series: Boolean, idempotencyKey: String): SaveResult {
        val api = api ?: return SaveResult.Field(null, strings.t("common.errorOccurred"))
        if (refuseOffline()) return SaveResult.Field(null, strings.t("common.errorOfflineMutation"))
        val s = state.value
        val withAccount = s.accounts.accounts.isNotEmpty()
        val withResponsible = BudgetPeople.pickable(s.members, original?.responsibleUsers.orEmpty()).size > 1
        val receipts = documentsAccess != "none"
        _state.update { it.copy(busy = true) }
        try {
            if (original == null) {
                val body = form.body(s.money, withAccount, withResponsible, s.personal, if (receipts) commitReceipts(api, form) else null)
                api.create(body, idempotencyKey)
                loadMonth(requestedMonth)
                host.notify(strings.t("budget.addedToast"))
            } else if (series) {
                val body = form.body(s.money, withAccount, withResponsible, s.personal, null).toMutableMap()
                if (body["account_id"] is kotlinx.serialization.json.JsonNull && original.accountId == null) body.remove("account_id")
                val seriesBody = if (original.recurrenceParentId != null) {
                    BudgetLedger.occurrenceSeriesBody(JsonObject(body), original)
                } else {
                    BudgetLedger.anchorSeriesBody(JsonObject(body), original)
                }
                api.updateSeries(original.id!!, seriesBody)
                loadMonth(requestedMonth)
                host.notify(strings.t("budget.recurringSeriesSaved"))
            } else {
                val body = form.body(s.money, withAccount, withResponsible, s.personal, if (receipts) commitReceipts(api, form) else null)
                api.update(original.id!!, body)
                loadMonth(requestedMonth)
                host.notify(strings.t("budget.savedToast"))
            }
            return SaveResult.Saved
        } catch (e: ApiProblem) {
            val r = BudgetText.budgetError(e, strings, BudgetText.BUDGET_SAVE_FAILED)
            return SaveResult.Field(r.field, r.message)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** `addCategory` / `addSubcategory` of the entry dialog; returns the new key. */
    suspend fun addCategory(name: String, type: String): String? {
        val api = api ?: return null
        if (refuseOffline() || name.isBlank()) return null
        return try {
            val created = api.createCategory(name.trim(), type, YuvomiClient.newIdempotencyKey())
            loadMeta(api)
            host.notify(strings.t("budget.categoryAddedToast"))
            created.key
        } catch (e: ApiProblem) {
            toast(e, BudgetText.BUDGET_SAVE_FAILED)
            null
        }
    }

    suspend fun addSubcategory(category: String, name: String): String? {
        val api = api ?: return null
        if (refuseOffline() || name.isBlank() || category.isEmpty()) return null
        return try {
            val created = api.createSubcategory(category, name.trim(), YuvomiClient.newIdempotencyKey())
            loadMeta(api)
            host.notify(strings.t("budget.subcategoryAddedToast"))
            created.key
        } catch (e: ApiProblem) {
            toast(e, BudgetText.BUDGET_SAVE_FAILED)
            null
        }
    }

    /** `openConfirmBookingModal`'s save; returns the amount field's error, or null when booked. */
    suspend fun confirm(entry: BudgetEntry, amountText: String, date: LocalDate?): String? {
        val api = api ?: return null
        val id = entry.id ?: return null
        if (refuseOffline()) return null
        val s = state.value
        val currency = if (entry.isTransfer) s.accountCurrency(entry.accountId) else s.currency
        val original = kotlin.math.abs(entry.amount)
        s.money.fieldError(amountText, currency, strings, required = true, original = original)?.let { return it }
        return try {
            api.confirm(id, s.money.parse(amountText) ?: original, date?.toString())
            loadMonth(requestedMonth)
            host.notify(strings.t("budget.confirmSaved"))
            null
        } catch (e: ApiProblem) {
            val r = BudgetText.budgetError(e, strings, BudgetText.BUDGET_SAVE_FAILED)
            if (r.field == "amount") r.message else {
                host.notify(r.message)
                null
            }
        }
    }

    /**
     * `deleteEntry`: the row goes at once, the summary without it (`summaryWith`), and only when
     * the undo window ends does the DELETE go out. With [series] the whole series goes
     * (`deleteEntrySeries`).
     */
    fun deleteEntry(entry: BudgetEntry, series: Boolean = false) {
        val api = api ?: return
        val id = entry.id ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val parentId = entry.recurrenceParentId ?: id
            val removed = if (series) {
                state.value.entries.filter { it.id == parentId || it.recurrenceParentId == parentId }
            } else {
                listOf(entry)
            }
            val ids = removed.mapNotNull { it.id }.toSet() + id
            val inMonth = state.value.entries.any { it.id == id }
            val before = state.value.summary
            _state.update {
                it.copy(
                    pendingDeletes = it.pendingDeletes + ids,
                    summary = if (inMonth || series) BudgetLedger.summaryWith(it.summary, removed.filter { e -> it.entries.any { r -> r.id == e.id } }, -1) else it.summary,
                )
            }
            container.scope.launch {
                val message = strings.t(if (series) "budget.recurringSeriesDeleted" else "budget.deletedToast")
                if (host.notify(message, strings.t("common.undo"))) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - ids, summary = if (it.month == before.month) before else it.summary) }
                    return@launch
                }
                try {
                    if (series) api.deleteSeries(id) else api.delete(id)
                    loadMonth(requestedMonth)
                    _state.update { s ->
                        s.copy(pendingDeletes = s.pendingDeletes - ids, results = s.results?.filter { r -> r.id !in ids })
                    }
                } catch (e: ApiProblem) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - ids) }
                    loadMonth(requestedMonth)
                    toast(e)
                }
            }
        }
    }

    /** A transfer's delete: both halves, after the confirmation (`deleteEntry`, transfer branch). */
    suspend fun deleteTransfer(entry: BudgetEntry, series: Boolean) {
        val api = api ?: return
        val id = entry.id ?: return
        if (refuseOffline()) return
        try {
            if (series) api.deleteSeries(id) else api.delete(id)
            loadMonth(requestedMonth)
            loadAccounts()
        } catch (e: ApiProblem) {
            toast(e, BudgetText.BUDGET_SAVE_FAILED)
        }
    }

    // ── Transfers ──

    suspend fun loadTransfer(entryId: Int): BudgetTransfer? {
        val api = api ?: return null
        return try {
            api.transfer(entryId)
        } catch (e: ApiProblem) {
            toast(e)
            null
        }
    }

    suspend fun saveTransfer(transfer: BudgetTransfer?, form: TransferForm, idempotencyKey: String): SaveResult {
        val api = api ?: return SaveResult.Field(null, strings.t("common.errorOccurred"))
        if (refuseOffline()) return SaveResult.Field(null, strings.t("common.errorOfflineMutation"))
        val s = state.value
        val series = form.scope == "series"
        val body = form.body(s.money, s.accounts.accounts, s.personal, withRecurrence = transfer == null || series, strings)
        _state.update { it.copy(busy = true) }
        return try {
            if (transfer == null) api.createTransfer(body, idempotencyKey) else api.updateTransfer(transfer.id, body, series)
            loadMonth(requestedMonth)
            loadAccounts()
            host.notify(strings.t("budget.savedToast"))
            SaveResult.Saved
        } catch (e: ApiProblem) {
            val r = BudgetText.budgetError(e, strings, BudgetText.BUDGET_SAVE_FAILED)
            SaveResult.Field(r.field, r.message)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // ── Accounts ──

    fun toggleArchived() = _state.update { it.copy(showArchived = !it.showArchived) }

    suspend fun saveAccount(original: BudgetAccount?, form: AccountForm, idempotencyKey: String): SaveResult {
        val api = api ?: return SaveResult.Field(null, strings.t("common.errorOccurred"))
        if (refuseOffline()) return SaveResult.Field(null, strings.t("common.errorOfflineMutation"))
        val s = state.value
        _state.update { it.copy(busy = true) }
        return try {
            if (original == null) api.createAccount(form.body(s.money), idempotencyKey) else api.updateAccount(original.id, form.body(s.money))
            loadAccounts()
            host.notify(strings.t(if (original == null) "budget.accountAddedToast" else "budget.accountSavedToast"))
            SaveResult.Saved
        } catch (e: ApiProblem) {
            val r = BudgetText.budgetError(e, strings, BudgetText.BUDGET_SAVE_FAILED)
            SaveResult.Field(r.field, r.message)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    suspend fun setArchived(account: BudgetAccount, archived: Boolean): Boolean {
        val api = api ?: return false
        if (refuseOffline()) return false
        return try {
            api.updateAccount(account.id, buildJsonObject { put("archived", archived) })
            loadAccounts()
            host.notify(strings.t(if (archived) "budget.accountArchivedToast" else "budget.accountRestoredToast"))
            true
        } catch (e: ApiProblem) {
            toast(e)
            false
        }
    }

    /** An account without entries goes with an undo (`deleteEmptyAccountWithUndo`). */
    fun deleteEmptyAccount(account: BudgetAccount) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(pendingAccountDeletes = it.pendingAccountDeletes + account.id) }
            container.scope.launch {
                if (host.notify(strings.t("common.deletedNamed", "name" to account.name), strings.t("common.undo"))) {
                    _state.update { it.copy(pendingAccountDeletes = it.pendingAccountDeletes - account.id) }
                    return@launch
                }
                try {
                    api.deleteAccount(account.id)
                } catch (e: ApiProblem) {
                    toast(e)
                }
                _state.update { it.copy(pendingAccountDeletes = it.pendingAccountDeletes - account.id) }
                loadAccounts()
            }
        }
    }

    /** An account with entries, after the confirmation: the entries keep, without account. */
    suspend fun deleteAccount(account: BudgetAccount): Boolean {
        val api = api ?: return false
        if (refuseOffline()) return false
        return try {
            api.deleteAccount(account.id)
            if (state.value.filters.account == account.id) _state.update { it.copy(filters = it.filters.copy(account = null)) }
            loadMonth(requestedMonth)
            loadAccounts()
            host.notify(strings.t("common.deletedNamed", "name" to account.name))
            true
        } catch (e: ApiProblem) {
            toast(e)
            false
        }
    }

    // ── Loans ──

    fun setLoanStatus(status: String) = _state.update { it.copy(loanStatus = status) }

    fun toggleLoanFilter(id: Int?) = _state.update { it.copy(loanFilterId = if (id == null || it.loanFilterId == id) null else id) }

    fun setLoanSort(sort: String) {
        _state.update { it.copy(loanSort = if (sort in BudgetLedger.LOAN_SORTS) sort else "start") }
        saveView()
    }

    fun toggleLoanPayments() = _state.update { it.copy(loanPaymentsExpanded = !it.loanPaymentsExpanded) }

    suspend fun previewLoan(body: JsonObject): LoanPreview? = runCatching { api?.previewLoan(body) }.getOrNull()

    suspend fun saveLoan(original: BudgetLoan?, form: LoanForm, idempotencyKey: String): SaveResult {
        val api = api ?: return SaveResult.Field(null, strings.t("common.errorOccurred"))
        if (refuseOffline()) return SaveResult.Field(null, strings.t("common.errorOfflineMutation"))
        val s = state.value
        val check = form.check(s.money, s.currency, original, s.accounts.accounts.isNotEmpty(), original != null, strings)
        val ok = when (check) {
            is LoanForm.Check.Field -> return SaveResult.Field(check.field, check.message)
            is LoanForm.Check.Ok -> check
        }
        _state.update { it.copy(busy = true) }
        try {
            // An interest loan's term comes from the preview: no more paid installments than it has.
            if (form.mode != "none" && (ok.paid ?: 0) > 0) {
                val term = api.runCatching { previewLoan(ok.body) }.getOrNull()?.takeIf { it.ok }?.totalMonths
                if (term != null && ok.paid!! > term) return SaveResult.Field("paid", strings.t("budget.loanPaidInstallmentsMax", "max" to term))
            }
            if (original == null) api.createLoan(ok.body, idempotencyKey) else api.updateLoan(original.id, ok.body)
            loadMonth(requestedMonth)
            host.notify(strings.t(if (original == null) "budget.loanAddedToast" else "budget.loanSavedToast"))
            return SaveResult.Saved
        } catch (e: ApiProblem) {
            val r = BudgetText.loanError(e, strings)
            return SaveResult.Field(r.field, r.message)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** `markLoanPayment`: the next installment on its due date, with an undo. */
    fun payLoan(loan: BudgetLoan) {
        val api = api ?: return
        val number = loan.nextInstallmentNumber ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val paidDate = loan.nextDueDate ?: host.today().toString()
            val booked = strings.t("budget.loanPaymentAddedOnToast", "date" to paidDate)
            val paymentId = try {
                api.payLoan(loan.id, number, BudgetLedger.nextInstallmentAmount(loan), paidDate, YuvomiClient.newIdempotencyKey())
            } catch (e: ApiProblem) {
                toast(e)
                return@launch
            }
            loadMonth(requestedMonth)
            container.scope.launch {
                if (paymentId != null && host.notify(booked, strings.t("common.undo"))) {
                    try {
                        api.deleteLoanPayment(loan.id, paymentId)
                        loadMonth(requestedMonth)
                    } catch (e: ApiProblem) {
                        toast(e)
                    }
                } else if (paymentId == null) {
                    host.notify(booked)
                }
            }
        }
    }

    fun deleteLoan(loan: BudgetLoan) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(pendingLoanDeletes = it.pendingLoanDeletes + loan.id) }
            container.scope.launch {
                if (host.notify(strings.t("budget.loanDeletedToast"), strings.t("common.undo"))) {
                    _state.update { it.copy(pendingLoanDeletes = it.pendingLoanDeletes - loan.id) }
                    return@launch
                }
                try {
                    api.deleteLoan(loan.id)
                    loadMonth(requestedMonth)
                } catch (e: ApiProblem) {
                    toast(e)
                }
                _state.update { it.copy(pendingLoanDeletes = it.pendingLoanDeletes - loan.id) }
            }
        }
    }

    fun deleteLoanPayment(loan: BudgetLoan, payment: LoanPayment) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(pendingPaymentDeletes = it.pendingPaymentDeletes + payment.id) }
            container.scope.launch {
                if (host.notify(strings.t("budget.deletedToast"), strings.t("common.undo"))) {
                    _state.update { it.copy(pendingPaymentDeletes = it.pendingPaymentDeletes - payment.id) }
                    return@launch
                }
                try {
                    api.deleteLoanPayment(loan.id, payment.id)
                    loadMonth(requestedMonth)
                } catch (e: ApiProblem) {
                    toast(e)
                }
                _state.update { it.copy(pendingPaymentDeletes = it.pendingPaymentDeletes - payment.id) }
            }
        }
    }

    /** `openLoanPaymentEntry`: the booked entry of a payment, for its editor. */
    suspend fun loanPaymentEntry(loanId: Int, paymentId: Int): BudgetEntry? {
        val api = api ?: return null
        return try {
            api.loanEntries(loanId).firstOrNull { it.loanPaymentId == paymentId }.also { if (it == null) host.notify(strings.t("common.unknownError")) }
        } catch (e: ApiProblem) {
            toast(e)
            null
        }
    }

    // ── Statistics ──

    /** `fetchStats`: the period, and the one before it for the comparison. */
    suspend fun loadStats() {
        val api = api ?: return
        val seq = ++statsSeq
        val s = state.value
        _state.update { it.copy(statsLoading = true, statsError = false) }
        try {
            val data = api.stats(s.range, s.anchor.toString(), view(s))
            val previous = runCatching {
                api.stats(s.range, LocalDate.parse(data.from).minusDays(1).toString(), view(s))
            }.getOrNull()
            if (seq != statsSeq) return
            _state.update { it.copy(stats = data, statsPrevious = previous, statsLoading = false) }
        } catch (e: ApiProblem) {
            if (seq != statsSeq) return
            _state.update { it.copy(stats = null, statsPrevious = null, statsLoading = false, statsError = true) }
        }
    }

    fun retryStats() {
        viewModelScope.launch { loadStats() }
    }

    /** CSV export of the month (`listToolsMenuHtml`) or of the stats period (`renderExport`). */
    suspend fun export(query: List<Pair<String, String>>): ByteArray? {
        val api = api ?: return null
        return try {
            api.export(query)
        } catch (e: ApiProblem) {
            toast(e)
            null
        }
    }

    fun monthExportQuery(s: UiState = state.value): List<Pair<String, String>> =
        listOf("month" to s.month) + if (s.personal) listOf("scope" to s.scope) else emptyList()

    fun statsExportQuery(stats: BudgetStats, s: UiState = state.value): List<Pair<String, String>> =
        listOf("from" to stats.from, "to" to stats.to) + if (s.personal) listOf("scope" to s.scope) else emptyList()

    // ── Plan ──

    suspend fun loadPlans() {
        val api = api ?: return
        val seq = ++plansSeq
        val s = state.value
        _state.update { it.copy(plansLoading = true, plansError = false) }
        try {
            val plans = api.plans(requestedMonth, view(s))
            if (seq != plansSeq) return
            _state.update { it.copy(plans = plans, plansLoading = false) }
        } catch (e: ApiProblem) {
            if (seq != plansSeq) return
            _state.update { it.copy(plans = null, plansLoading = false, plansError = true) }
        }
    }

    fun retryPlans() {
        viewModelScope.launch { loadPlans() }
    }

    /** `savePlan`; returns the amount field's error, or null when saved. */
    suspend fun savePlan(category: String, amountText: String, original: Double?): String? {
        val api = api ?: return null
        if (refuseOffline()) return strings.t("common.errorOfflineMutation")
        val s = state.value
        s.money.fieldError(amountText, s.currency, strings, required = true, original = original)?.let { return it }
        return try {
            api.setPlan(category, s.money.parse(amountText) ?: 0.0)
            loadPlans()
            host.notify(strings.t("budget.planSavedToast"))
            null
        } catch (e: ApiProblem) {
            host.notify(strings.t("budget.loadError"))
            ""
        }
    }

    /** `deletePlan`: gone at once, the undo puts the previous amount back. */
    fun deletePlan(category: String) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val s = state.value
            val previous = if (category == BudgetLedger.SAVINGS_PLAN_KEY) s.plans?.savings?.planned else s.plans?.plans?.firstOrNull { it.category == category }?.planned
            try {
                api.deletePlan(category)
                loadPlans()
            } catch (e: ApiProblem) {
                host.notify(strings.t("budget.loadError"))
                return@launch
            }
            container.scope.launch {
                if (host.notify(strings.t("budget.planRemovedToast"), strings.t("common.undo")) && previous != null) {
                    try {
                        api.setPlan(category, previous)
                        loadPlans()
                    } catch (e: ApiProblem) {
                        host.notify(strings.t("common.unknownError"))
                    }
                }
            }
        }
    }

    // ── Receipts ──

    suspend fun documents(): List<BudgetDocument>? {
        val api = api ?: return null
        return try {
            api.documents().filter { it.status != "archived" }
        } catch (e: ApiProblem) {
            host.notify(strings.t("documentAttach.loadFailed"))
            null
        }
    }

    suspend fun receipt(id: Int, preview: Boolean): ByteArray? {
        val api = api ?: return null
        return try {
            api.receipt(id, preview)
        } catch (e: ApiProblem) {
            toast(e)
            null
        }
    }

    suspend fun notify(message: String) = host.notify(message)

    companion object {
        const val PREFS_KEY = "budget-preferences"
        const val META_KEY = "budget-meta"
        const val ACCOUNTS_KEY = "budget-accounts"
        const val VIEW_KEY = "budget-view"
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
