package io.github.marcomow.yuvomi.feature.splitexpenses

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.WeakHashMap

/** What the tab shows, as one serializable snapshot (also the offline copy, ADR 0007). */
@Serializable
data class SplitData(
    val meta: SplitMeta = SplitMeta(),
    val dashboard: SplitDashboard? = null,
    val groups: List<ExpenseGroup> = emptyList(),
    val members: List<SplitPerson> = emptyList(),
    val budgetCategories: List<BudgetCategory> = emptyList(),
    val activeGroupId: Int? = null,
    val expenses: List<Expense> = emptyList(),
    val expensesCursor: ExpenseCursor? = null,
    val balances: Balances = Balances(),
    val activity: List<Activity> = emptyList(),
    val activityCursor: ExpenseCursor? = null,
    val groupMembers: List<SplitPerson> = emptyList(),
    val recurring: List<RecurringExpense> = emptyList(),
)

/**
 * The tab's state, shared by its screens like the web page's module-level `state`
 * (docs/spec/splitexpenses.md). One store per signed-in connection. Writes go to the server,
 * then the dashboard and the chosen group are read again, as the page does after every write.
 */
class SplitStore(private val cache: ResponseCache, val api: SplitApi, private val scope: CoroutineScope, val guest: Boolean) {

    data class State(
        val data: SplitData = SplitData(),
        val status: String = "active",
        val query: String = "",
        val category: String = "",
        val loading: Boolean = true,
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        val pendingDeletes: Set<Int> = emptySet(),
    ) {
        val offline: Boolean get() = cachedAt != null
        val archived: Boolean get() = status == "archived"
        val group: ExpenseGroup? get() = data.groups.firstOrNull { it.id == data.activeGroupId }
        val expenses: List<Expense> get() = data.expenses.filter { it.id !in pendingDeletes }
        /** The people the pickers offer: the group's members, else the household. */
        val people: List<SplitPerson> get() = data.groupMembers.ifEmpty { data.members }
        val searchMiss: Boolean get() = query.isNotBlank() && data.groups.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var generation = 0

    init {
        runCatching {
            cache.read(CACHE)?.let { e ->
                val data = ApiJson.decodeFromString(SplitData.serializer(), e.json)
                _state.update { it.copy(data = data, loading = false, cachedAt = e.savedAt) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (s.status == "active" && s.query.isBlank() && s.category.isEmpty()) runCatching { cache.write(CACHE, ApiJson.encodeToString(SplitData.serializer(), s.data)) }
    }

    fun refresh(pulled: Boolean = false, preferGroup: Int? = null) {
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        scope.launch { load(preferGroup) }
    }

    /** `loadInitial()`: meta, dashboard, groups, the household and the budget's categories, then the chosen group. */
    suspend fun load(preferGroup: Int? = null): Boolean = guard {
        val s = _state.value
        coroutineScope {
            val meta = async { api.meta() }
            val dash = async { api.dashboard() }
            val groups = async { api.groups(s.status, s.query) }
            val members = async { if (guest) emptyList() else api.familyMembers() }
            val cats = async { runCatching { api.budgetCategories() }.getOrDefault(emptyList()) }
            val list = groups.await()
            val wanted = preferGroup ?: s.data.activeGroupId
            val active = list.firstOrNull { it.id == wanted }?.id ?: list.firstOrNull()?.id
            _state.update {
                it.copy(data = it.data.copy(meta = meta.await(), dashboard = dash.await(), groups = list, members = members.await(), budgetCategories = cats.await(), activeGroupId = active))
            }
        }
        loadGroupData()
        _state.update { it.copy(loading = false, loaded = true, refreshing = false, cachedAt = null, errorKey = null) }
        save()
    }

    private suspend fun guard(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ApiProblem) {
        _state.update {
            val hasData = it.data.groups.isNotEmpty() || it.cachedAt != null
            it.copy(
                loading = false, refreshing = false, loadingMore = false,
                cachedAt = it.cachedAt ?: if (hasData && e is ApiProblem.Network) System.currentTimeMillis() else null,
                errorKey = if (hasData && e is ApiProblem.Network) null else loadErrorKey(e),
            )
        }
        false
    }

    private fun loadErrorKey(e: ApiProblem): String = when {
        e is ApiProblem.Network -> "android.error.offline"
        e is ApiProblem.Http && e.operationUnsupported -> "android.error.unsupported"
        e is ApiProblem.Http && e.status == 403 -> SplitErrors.key(e)
        else -> "splitExpenses.loadError"
    }

    /** `loadGroupData()`: the chosen group's expenses, balances, activity, members and series. */
    private suspend fun loadGroupData() {
        val mine = ++generation
        val s = _state.value
        val id = s.data.activeGroupId
        if (id == null) {
            _state.update { it.copy(data = it.data.copy(expenses = emptyList(), expensesCursor = null, balances = Balances(), activity = emptyList(), activityCursor = null, groupMembers = emptyList(), recurring = emptyList())) }
            return
        }
        coroutineScope {
            val sameGroup = s.data.expenses.firstOrNull()?.groupId == id
            val depthExpenses = if (sameGroup) maxOf(SplitRules.EXPENSE_PAGE, s.data.expenses.size + 1).coerceAtMost(100) else SplitRules.EXPENSE_PAGE
            val depthActivity = if (sameGroup) maxOf(SplitRules.ACTIVITY_PAGE, s.data.activity.size + 1).coerceAtMost(100) else SplitRules.ACTIVITY_PAGE
            val expenses = async { api.expenses(id, s.category, depthExpenses, null) }
            val balances = async { api.balances(id) }
            val activity = async { api.activity(id, depthActivity, null) }
            val members = async { api.members(id) }
            val recurring = async { api.recurring(id) }
            val e = expenses.await()
            val a = activity.await()
            val b = balances.await()
            val m = members.await()
            val r = recurring.await()
            if (mine != generation) return@coroutineScope
            _state.update {
                it.copy(data = it.data.copy(expenses = e.items, expensesCursor = e.cursor, balances = b, activity = a.items, activityCursor = a.cursor, groupMembers = m, recurring = r))
            }
        }
    }

    /** After a write: the dashboard and the chosen group again (`refreshDashboard()` + `loadGroupData()`). */
    private suspend fun reloadGroup() = guard {
        val dash = api.dashboard()
        _state.update { it.copy(data = it.data.copy(dashboard = dash)) }
        loadGroupData()
        save()
    }

    private suspend fun reloadGroups(prefer: Int? = null) = guard {
        val s = _state.value
        val list = api.groups(s.status, s.query)
        val wanted = prefer ?: s.data.activeGroupId
        _state.update { it.copy(data = it.data.copy(groups = list, activeGroupId = list.firstOrNull { g -> g.id == wanted }?.id ?: list.firstOrNull()?.id)) }
        val dash = api.dashboard()
        _state.update { it.copy(data = it.data.copy(dashboard = dash)) }
        loadGroupData()
        save()
    }

    fun selectGroup(id: Int) {
        if (id == _state.value.data.activeGroupId) return
        _state.update { it.copy(category = "", data = it.data.copy(activeGroupId = id, expenses = emptyList(), activity = emptyList())) }
        scope.launch { guard { loadGroupData() } }
    }

    fun setStatus(status: String) {
        if (status == _state.value.status) return
        _state.update { it.copy(status = status, category = "", data = it.data.copy(activeGroupId = null)) }
        scope.launch { reloadGroups() }
    }

    fun setQuery(q: String) {
        if (q.trim() == _state.value.query.trim()) return
        _state.update { it.copy(query = q) }
        scope.launch { reloadGroups() }
    }

    fun setCategory(category: String) {
        _state.update { it.copy(category = category, data = it.data.copy(expenses = emptyList())) }
        scope.launch { guard { loadGroupData() } }
    }

    fun loadMoreExpenses() {
        val s = _state.value
        val id = s.data.activeGroupId ?: return
        val cursor = s.data.expensesCursor ?: return
        if (s.loadingMore) return
        val mine = generation
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            guard {
                val page = api.expenses(id, s.category, SplitRules.EXPENSE_PAGE, cursor)
                if (mine != generation) return@guard
                _state.update { st ->
                    val seen = st.data.expenses.map { it.id }.toSet()
                    st.copy(data = st.data.copy(expenses = st.data.expenses + page.items.filter { it.id !in seen }, expensesCursor = page.cursor))
                }
            }
            _state.update { it.copy(loadingMore = false) }
        }
    }

    fun loadMoreActivity() {
        val s = _state.value
        val id = s.data.activeGroupId ?: return
        val cursor = s.data.activityCursor ?: return
        if (s.loadingMore) return
        val mine = generation
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            guard {
                val page = api.activity(id, SplitRules.ACTIVITY_PAGE, cursor)
                if (mine != generation) return@guard
                _state.update { st ->
                    val seen = st.data.activity.map { it.id }.toSet()
                    st.copy(data = st.data.copy(activity = st.data.activity + page.items.filter { it.id !in seen }, activityCursor = page.cursor))
                }
            }
            _state.update { it.copy(loadingMore = false) }
        }
    }

    // ── Writes: each returns an error key, or null ──

    private suspend fun write(after: suspend () -> Boolean = { reloadGroup() }, block: suspend () -> Unit): String? = try {
        block()
        after()
        null
    } catch (e: ApiProblem) {
        after()
        SplitErrors.key(e)
    }

    suspend fun archive(id: Int) = write({ reloadGroups() }) { api.archive(id) }

    /** `restoreGroup()`: back to the active list, on that group. */
    suspend fun restore(id: Int): String? = try {
        api.unarchive(id)
        _state.update { it.copy(status = "active") }
        reloadGroups(prefer = id)
        null
    } catch (e: ApiProblem) {
        SplitErrors.key(e)
    }

    suspend fun deleteGroup(id: Int) = write({ reloadGroups() }) { api.deleteGroup(id) }

    suspend fun reverse(settlementId: Int): String? {
        val gid = _state.value.data.activeGroupId ?: return null
        return write { api.reverse(gid, settlementId) }
    }

    suspend fun togglePause(r: RecurringExpense, missed: String?) = write { api.pause(r.id, missed) }

    suspend fun settle(body: kotlinx.serialization.json.JsonObject, key: String): String? {
        val gid = _state.value.data.activeGroupId ?: return null
        return write { api.settle(gid, body, key) }
    }

    /** Saves a group, then the member editor's changes one by one (`syncEditedGroupMembers()`). */
    suspend fun saveGroup(existing: ExpenseGroup?, body: kotlinx.serialization.json.JsonObject, key: String, add: List<MemberCandidate>, remove: List<Int>): String? = try {
        val saved = if (existing == null) api.createGroup(body, key) else api.updateGroup(existing.id, body)
        for (c in add) api.addMember(saved.id, c.userId.takeIf { c.source == "user" }, c.contactId.takeIf { c.source == "contact" }, "guest")
        for (uid in remove) api.removeMember(saved.id, uid)
        if (existing == null) _state.update { it.copy(status = "active") }
        reloadGroups(prefer = saved.id)
        null
    } catch (e: ApiProblem) {
        reloadGroups()
        SplitErrors.key(e)
    }

    suspend fun addMember(userId: Int?, contactId: Int?, role: String): String? {
        val gid = _state.value.data.activeGroupId ?: return null
        return write({ reloadGroups() }) { api.addMember(gid, userId, contactId, role) }
    }

    suspend fun createGuest(body: kotlinx.serialization.json.JsonObject, key: String): String? {
        val gid = _state.value.data.activeGroupId ?: return null
        val after: suspend () -> Boolean = {
            val ok = reloadGroups()
            if (!guest) {
                val m = try { api.familyMembers() } catch (e: ApiProblem) { null }
                if (m != null) _state.update { it.copy(data = it.data.copy(members = m)) }
            }
            ok
        }
        return write(after) {
            api.createGuest(gid, body, key)
        }
    }

    /** Throws [ApiProblem] for the editor to explain. */
    suspend fun saveExpense(existing: Expense?, body: kotlinx.serialization.json.JsonObject, key: String) {
        val gid = _state.value.data.activeGroupId ?: return
        if (existing == null) api.createExpense(gid, body, key) else api.updateExpense(existing.id, body)
        reloadGroup()
    }

    /** Throws [ApiProblem] for the editor to explain. */
    suspend fun saveRecurring(existing: RecurringExpense?, body: kotlinx.serialization.json.JsonObject, key: String) {
        val gid = _state.value.data.activeGroupId ?: return
        if (existing == null) api.createRecurring(gid, body, key) else if (body.isNotEmpty()) api.updateRecurring(existing.id, body)
        reloadGroup()
    }

    suspend fun deleteRecurring(id: Int) = write { api.deleteRecurring(id) }

    /** `scheduleUndoableDelete()`: the expense leaves at once; only without Undo is it deleted. */
    fun scheduleDeleteExpense(e: Expense, strings: Strings, notify: suspend (String, String?) -> Boolean) {
        _state.update { it.copy(pendingDeletes = it.pendingDeletes + e.id) }
        scope.launch {
            val message = if (e.title.isNotBlank()) strings.t("common.deletedNamed", "name" to e.title) else strings.t("splitExpenses.expenseDeleted")
            if (notify(message, strings.t("common.undo"))) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - e.id) }
                return@launch
            }
            val error = try {
                api.deleteExpense(e.id)
                null
            } catch (x: ApiProblem) {
                SplitErrors.key(x)
            }
            reloadGroup()
            _state.update { it.copy(pendingDeletes = it.pendingDeletes - e.id) }
            if (error != null) notify(strings.t(error), null)
        }
    }

    suspend fun candidates(): List<MemberCandidate> {
        val gid = _state.value.data.activeGroupId ?: return emptyList()
        if (guest) return emptyList()
        return api.candidates(gid)
    }

    companion object {
        const val CACHE = "split-expenses"
        private val stores = WeakHashMap<Connection, SplitStore>()

        fun of(container: AppContainer): SplitStore? {
            val connection = container.session.connection ?: return null
            val guest = container.session.state.value.let { (it as? io.github.marcomow.yuvomi.core.session.SessionState.SignedIn)?.session?.user?.accessScope == "split_guest" }
            return synchronized(stores) {
                stores.getOrPut(connection) { SplitStore(container.cache, SplitApi(connection.client), container.scope, guest) }
            }
        }
    }
}
