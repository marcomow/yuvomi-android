package io.github.marcomow.yuvomi.feature.housekeeping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the housekeeping screens need from the shell; an interface so tests need no shell. */
interface HousekeepingHost {
    val strings: Strings
    val time: HouseholdTime

    /** `housekeeping` module access: `none`, `read` or `write`. */
    fun access(): String

    /** `pathAccess('/documents')`: the receipt field's three levels. */
    fun documentsAccess(): String

    fun canSeeAmounts(): Boolean

    suspend fun notify(message: String, action: String? = null): Boolean
}

/** docs/spec/housekeeping.md: the page with its four tabs (HOUSE-R1 to R40). */
class HousekeepingViewModel(
    private val container: AppContainer,
    private val host: HousekeepingHost,
    linkedTab: Tab? = null,
) : ViewModel() {

    enum class Tab(val id: String) { VISITS("dashboard"), CHORES("tasks"), REPORTS("reports"), STAFF("staff");
        companion object { fun of(id: String?): Tab? = entries.firstOrNull { it.id == id } }
    }

    /** The sheets and dialogs over the page. */
    sealed interface Overlay {
        data class VisitReport(val visit: Visit) : Overlay
        data class VisitEdit(val visit: Visit) : Overlay
        data class ConfirmPay(val visit: Visit, val closesReport: Boolean) : Overlay
        data class ConfirmUnpay(val visit: Visit, val closesReport: Boolean) : Overlay
        data object TaskCreate : Overlay
        data class TaskEdit(val task: DecayTask) : Overlay
        data class WorkerEdit(val worker: Worker?) : Overlay
        data class WorkerRead(val worker: Worker) : Overlay
    }

    data class UiState(
        val data: HousekeepingData = HousekeepingData(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val loadFailed: Boolean = false,
        val tab: Tab = Tab.VISITS,
        /** The report tab's month; null follows the current one (#1137). */
        val reportMonth: String? = null,
        val report: VisitReport? = null,
        val reportLoading: Boolean = false,
        val selectedStaffId: Int? = null,
        val staffMonth: String = "",
        val staffVisits: List<Visit> = emptyList(),
        val pendingTaskDeletes: Set<Int> = emptySet(),
        val pendingVisitDeletes: Set<Int> = emptySet(),
        val busyWorkers: Set<Int> = emptySet(),
        val busyTasks: Set<Int> = emptySet(),
        val overlay: Overlay? = null,
    ) {
        val offline: Boolean get() = cachedAt != null
        val tasks: List<DecayTask> get() = data.tasks.filter { it.id !in pendingTaskDeletes }
        val recentVisits: List<Visit> get() = data.current.visits.filter { it.id !in pendingVisitDeletes }
        val currentMonth: String get() = data.current.month ?: ""

        /** The report shown: the stepped month's, else the current month's. */
        val shownReport: VisitReport get() = if (reportMonth != null) report ?: VisitReport(month = reportMonth) else data.current
        val reportVisits: List<Visit> get() = shownReport.visits.filter { it.id !in pendingVisitDeletes }
        val shownStaffVisits: List<Visit> get() = staffVisits.filter { it.id !in pendingVisitDeletes }
    }

    private val _state = MutableStateFlow(UiState(tab = linkedTab ?: Tab.VISITS, staffMonth = host.time.today().toString().take(7)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val zone: String get() = host.time.zone.id
    private val api: HousekeepingApi? get() = container.session.connection?.client?.let { HousekeepingApi(it, zone) }
    private val strings get() = host.strings
    val text: HousekeepingText get() = HousekeepingText(strings, host.time, state.value.data.currency)

    private var loadSeq = 0
    private var reportSeq = 0
    var lastLoaded = 0L
        private set

    val readOnly: Boolean get() = host.access() != "write"

    /** `mayManageWorkers()`: write access and the server's `can_manage` (admins). */
    val mayManage: Boolean get() = !readOnly && state.value.data.canManage

    val canSeeAmounts: Boolean get() = host.canSeeAmounts()

    val documentsAccess: String get() = host.documentsAccess()

    fun tell(message: String) {
        viewModelScope.launch { host.notify(message) }
    }

    init {
        container.cache.read(CACHE_KEY)?.let { cached ->
            runCatching { HousekeepingApi.decodeData(cached.json) }.onSuccess { data -> _state.update { it.copy(data = data, loading = false) } }
        }
        refresh()
    }

    // ── Loading (loadData) ──

    /** HOUSE-R2: only the latest load writes the page (`loadDataSeq`). */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val seq = ++loadSeq
        _state.update { it.copy(refreshing = pulled, loadFailed = false, loading = it.loading || it.loadFailed) }
        viewModelScope.launch {
            try {
                reload(api, seq)
            } catch (e: ApiProblem) {
                if (seq != loadSeq) return@launch
                _state.update {
                    val hasData = !it.loading
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_KEY)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        loadFailed = !hasData,
                    )
                }
                if (!state.value.loadFailed && e !is ApiProblem.Network) host.notify(strings.t(HousekeepingErrors.key(e, LOAD_ERROR)))
            }
        }
    }

    private suspend fun reload(api: HousekeepingApi, seq: Int = ++loadSeq) {
        val reportMonth = state.value.reportMonth
        val rSeq = ++reportSeq
        val data = api.load()
        val report = reportMonth?.let { runCatching { api.visits(it, null) }.getOrNull() }
        val staffId = state.value.selectedStaffId
        val staff = staffId?.let { runCatching { api.visits(state.value.staffMonth, it).visits }.getOrNull() }
        if (seq != loadSeq) return
        container.cache.write(CACHE_KEY, HousekeepingApi.encodeData(data))
        lastLoaded = System.currentTimeMillis()
        _state.update { s ->
            s.copy(
                data = data,
                loading = false,
                refreshing = false,
                loadFailed = false,
                cachedAt = null,
                report = if (rSeq == reportSeq && report != null && s.reportMonth == reportMonth) report else s.report,
                staffVisits = staff ?: s.staffVisits,
                selectedStaffId = s.selectedStaffId?.takeIf { id -> data.workers.any { it.id == id } },
            )
        }
    }

    /** `reloadAfterSave()`: the write stands; a failed reload says nothing more. */
    private suspend fun reloadQuietly() {
        val api = api ?: return
        runCatching { reload(api) }
    }

    fun setTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun show(overlay: Overlay?) = _state.update { it.copy(overlay = overlay) }

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(strings.t("common.errorOfflineMutation"))
        return true
    }

    /** `reportWriteError()`: the app's sentence; a gone target or "already checked in" reloads. */
    private suspend fun reportWriteError(e: ApiProblem) {
        host.notify(strings.t(HousekeepingErrors.key(e)))
        if (HousekeepingErrors.reloads(e)) reloadQuietly()
    }

    // ── HOUSE-R5 to R8: check in and out ──

    fun toggleSession(workerId: Int) {
        if (readOnly) return
        val api = api ?: return
        val worker = state.value.data.workers.firstOrNull { it.id == workerId } ?: return
        if (workerId in state.value.busyWorkers) return
        _state.update { it.copy(busyWorkers = it.busyWorkers + workerId) }
        viewModelScope.launch {
            try {
                if (refuseOffline()) return@launch
                val current = worker.currentSession
                if (current != null) {
                    val out = api.checkOut(worker.id, YuvomiClient.newIdempotencyKey())
                    reloadQuietly()
                    container.scope.launch {
                        // "<Name> checked out." with the way back (`offerUndo`): reopen the same session.
                        if (host.notify(strings.t("housekeeping.checkedOutNamed", "name" to worker.displayName), strings.t("common.undo"))) {
                            try {
                                api.reopen(out.id, YuvomiClient.newIdempotencyKey())
                                reloadQuietly()
                            } catch (e: ApiProblem) {
                                reportWriteError(e)
                            }
                        }
                    }
                } else {
                    val rate = if (worker.hourly) 0.0 else worker.dailyRate ?: 0.0
                    val texts = HousekeepingRules.visitTexts(worker.displayName, text.date(host.time.today()), text.money(rate), strings)
                    api.checkIn(HousekeepingRules.checkInBody(worker, zone, texts), YuvomiClient.newIdempotencyKey())
                    container.scope.launch { host.notify(strings.t("housekeeping.checkedInNamed", "name" to worker.displayName)) }
                    reloadQuietly()
                }
            } catch (e: ApiProblem) {
                reportWriteError(e)
            } finally {
                _state.update { it.copy(busyWorkers = it.busyWorkers - workerId) }
            }
        }
    }

    // ── HOUSE-R10 to R16: chores ──

    /** `createTask()`: true when the sheet may close. */
    suspend fun createTask(name: String, area: String, frequencyDays: Int): Boolean {
        if (readOnly) return false
        val api = api ?: return false
        if (refuseOffline()) return false
        return try {
            api.createTask(name.trim(), area.trim(), frequencyDays, YuvomiClient.newIdempotencyKey())
            container.scope.launch { host.notify(strings.t("housekeeping.taskCreatedToast")) }
            reloadQuietly()
            true
        } catch (e: ApiProblem) {
            reportWriteError(e)
            false
        }
    }

    suspend fun createFromTemplate(template: TaskTemplate): Boolean = createTask(
        HousekeepingRules.templateLabel(template, area = false, strings),
        HousekeepingRules.templateLabel(template, area = true, strings),
        template.frequencyDays,
    )

    suspend fun saveTask(task: DecayTask, name: String, area: String, frequencyDays: Int): Boolean {
        if (readOnly) return false
        val api = api ?: return false
        if (refuseOffline()) return false
        return try {
            api.updateTask(task.id, name.trim(), area.trim(), frequencyDays)
            container.scope.launch { host.notify(strings.t("housekeeping.taskUpdatedToast")) }
            reloadQuietly()
            true
        } catch (e: ApiProblem) {
            reportWriteError(e)
            false
        }
    }

    /** `completeTask()`: the receipt names the chore; "Undo" restores the previous completion. */
    fun completeTask(task: DecayTask) {
        if (readOnly) return
        val api = api ?: return
        if (task.id in state.value.busyTasks) return
        _state.update { it.copy(busyTasks = it.busyTasks + task.id) }
        viewModelScope.launch {
            try {
                if (refuseOffline()) return@launch
                val previous = task.lastCompleted
                api.completeTask(task.id, YuvomiClient.newIdempotencyKey())
                reloadQuietly()
                container.scope.launch {
                    if (host.notify(strings.t("housekeeping.taskDoneNamed", "name" to task.name), strings.t("common.undo"))) {
                        try {
                            api.restoreCompletion(task.id, previous)
                            host.notify(strings.t("housekeeping.taskUndoneToast"))
                            reloadQuietly()
                        } catch (e: ApiProblem) {
                            reportWriteError(e)
                        }
                    }
                }
            } catch (e: ApiProblem) {
                reportWriteError(e)
            } finally {
                _state.update { it.copy(busyTasks = it.busyTasks - task.id) }
            }
        }
    }

    /** `deleteTask()`: the row goes at once; the server deletes after the undo window. */
    fun deleteTask(task: DecayTask) {
        if (readOnly) return
        val api = api ?: return
        if (state.value.offline) {
            viewModelScope.launch { refuseOffline() }
            return
        }
        _state.update { it.copy(pendingTaskDeletes = it.pendingTaskDeletes + task.id, overlay = null) }
        container.scope.launch {
            if (host.notify(strings.t("housekeeping.taskDeletedToast"), strings.t("common.undo"))) {
                _state.update { it.copy(pendingTaskDeletes = it.pendingTaskDeletes - task.id) }
                return@launch
            }
            try {
                api.deleteTask(task.id)
            } catch (e: ApiProblem) {
                if (!HousekeepingErrors.alreadyDeleted(e, HousekeepingErrors.TASK_GONE)) host.notify(strings.t(HousekeepingErrors.key(e)))
            }
            reloadQuietly()
            _state.update { it.copy(pendingTaskDeletes = it.pendingTaskDeletes - task.id) }
        }
    }

    // ── HOUSE-R17 to R22: visits ──

    /** Pay after the confirmation (`payVisit`). */
    fun pay(visit: Visit) {
        if (readOnly) return
        val api = api ?: return
        _state.update { it.copy(overlay = null) }
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            try {
                api.pay(visit.id, YuvomiClient.newIdempotencyKey())
                container.scope.launch { host.notify(strings.t("housekeeping.visitPaidNamed", "date" to text.date(visit.checkIn))) }
                reloadQuietly()
            } catch (e: ApiProblem) {
                reportWriteError(e)
            }
        }
    }

    fun unpay(visit: Visit) {
        if (readOnly) return
        val api = api ?: return
        _state.update { it.copy(overlay = null) }
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            try {
                api.unpay(visit.id, YuvomiClient.newIdempotencyKey())
                container.scope.launch { host.notify(strings.t("housekeeping.visitUnpaidNamed", "date" to text.date(visit.checkIn))) }
                reloadQuietly()
            } catch (e: ApiProblem) {
                reportWriteError(e)
            }
        }
    }

    /** `deleteVisitWithUndo()`: the toast names the day; nothing happens on the server until it ends. */
    fun deleteVisit(visit: Visit) {
        if (readOnly) return
        val api = api ?: return
        if (state.value.offline) {
            viewModelScope.launch { refuseOffline() }
            return
        }
        _state.update { it.copy(pendingVisitDeletes = it.pendingVisitDeletes + visit.id, overlay = null) }
        container.scope.launch {
            if (host.notify(strings.t("housekeeping.visitDeletedNamed", "date" to text.date(visit.checkIn)), strings.t("common.undo"))) {
                _state.update { it.copy(pendingVisitDeletes = it.pendingVisitDeletes - visit.id) }
                return@launch
            }
            try {
                api.deleteVisit(visit.id)
            } catch (e: ApiProblem) {
                if (!HousekeepingErrors.alreadyDeleted(e, HousekeepingErrors.VISIT_GONE)) host.notify(strings.t(HousekeepingErrors.key(e)))
            }
            reloadQuietly()
            _state.update { it.copy(pendingVisitDeletes = it.pendingVisitDeletes - visit.id) }
        }
    }

    /** A receipt the member picked for the visit editor. */
    class Receipt(val fileName: String, val mime: String, val bytes: ByteArray)

    /** The visit editor's values; amounts are already parsed. */
    data class VisitEdit(val date: LocalDate, val minutes: Double?, val dailyRate: Double?, val extras: Double)

    /** `openVisitEditModal`'s submit: the receipt first (into Documents), then the visit. */
    suspend fun saveVisit(visit: Visit, edit: VisitEdit, receipt: Receipt?): Boolean {
        if (readOnly) return false
        val api = api ?: return false
        if (refuseOffline()) return false
        val worker = state.value.data.workers.firstOrNull { it.id == visit.workerId }
        val upload = receipt?.takeIf { host.documentsAccess() == "write" && !HousekeepingRules.receiptHidden(visit) }
        if (upload != null && upload.bytes.size > MAX_UPLOAD_BYTES) {
            host.notify(strings.t("documents.fileTooLarge", "size" to MAX_UPLOAD_BYTES / (1024 * 1024)))
            return false
        }
        val visitDay = text.day(visit.checkIn) ?: edit.date
        val dateLabel = text.date(edit.date)
        val name = worker?.displayName ?: visit.workerName
        return try {
            var receiptId = visit.receiptDocumentId
            if (upload != null) {
                val docName = name?.takeIf { it.isNotBlank() } ?: strings.t("housekeeping.unnamedWorker")
                val dataUrl = "data:${upload.mime};base64," + java.util.Base64.getEncoder().encodeToString(upload.bytes)
                receiptId = api.uploadReceipt(
                    name = strings.t("housekeeping.receiptDocumentName", "name" to docName, "date" to dateLabel),
                    description = strings.t("housekeeping.receiptDocumentDescription", "name" to docName, "date" to dateLabel),
                    folderName = strings.t("documents.housekeepingFolder"),
                    fileName = upload.fileName,
                    dataUrl = dataUrl,
                    idempotencyKey = YuvomiClient.newIdempotencyKey(),
                ) ?: receiptId
            }
            val total = (edit.dailyRate ?: visit.dailyRate ?: 0.0) + edit.extras
            val texts = HousekeepingRules.visitTexts(name, dateLabel, text.money(total), strings)
            api.updateVisit(
                visit.id,
                HousekeepingRules.visitBody(visit, edit.date.toString(), visitDay.toString(), edit.minutes, edit.dailyRate, edit.extras, receiptId, texts),
            )
            container.scope.launch { host.notify(strings.t("housekeeping.visitSavedToast")) }
            _state.update { it.copy(staffMonth = edit.date.toString().take(7)) }
            reloadQuietly()
            true
        } catch (e: ApiProblem) {
            // The editor stays open with its input; only the sentence (#1909).
            host.notify(strings.t(HousekeepingErrors.key(e)))
            false
        }
    }

    // ── HOUSE-R23 to R26: the report month ──

    fun stepReportMonth(dir: Int) {
        val base = state.value.reportMonth ?: state.value.currentMonth.ifEmpty { host.time.today().toString().take(7) }
        showReportMonth(HousekeepingRules.shiftMonth(base, dir))
    }

    fun showReportMonth(month: String) {
        val api = api ?: return
        val current = state.value.currentMonth
        val target = month.takeIf { it != current }
        val seq = ++reportSeq
        val shownBefore = state.value.reportMonth
        _state.update { it.copy(reportMonth = target, reportLoading = target != null, report = if (target == null) null else it.report?.takeIf { r -> r.month == target }) }
        if (target == null) return
        viewModelScope.launch {
            try {
                val report = api.visits(target, null)
                if (seq != reportSeq) return@launch
                _state.update { it.copy(report = report, reportLoading = false) }
            } catch (e: ApiProblem) {
                if (seq != reportSeq) return@launch
                // Back to the month the report shows (#1174).
                _state.update { it.copy(reportMonth = shownBefore, reportLoading = false) }
                host.notify(strings.t(HousekeepingErrors.key(e, LOAD_ERROR)))
            }
        }
    }

    // ── HOUSE-R27 to R29: staff and their visit log ──

    fun selectStaff(workerId: Int) {
        _state.update { it.copy(selectedStaffId = workerId, staffVisits = if (it.selectedStaffId == workerId) it.staffVisits else emptyList()) }
        loadStaffVisits()
    }

    fun stepStaffMonth(dir: Int) {
        _state.update { it.copy(staffMonth = HousekeepingRules.shiftMonth(it.staffMonth, dir)) }
        loadStaffVisits()
    }

    private fun loadStaffVisits() {
        val api = api ?: return
        val id = state.value.selectedStaffId ?: return
        val month = state.value.staffMonth
        viewModelScope.launch {
            try {
                val visits = api.visits(month, id).visits
                if (state.value.selectedStaffId == id && state.value.staffMonth == month) _state.update { it.copy(staffVisits = visits) }
            } catch (e: ApiProblem) {
                host.notify(strings.t(HousekeepingErrors.key(e, LOAD_ERROR)))
            }
        }
    }

    /** `openStaffModal`'s submit; admins only (`mayManageWorkers`). */
    suspend fun saveWorker(form: HousekeepingRules.WorkerForm, stored: Worker?): Boolean {
        if (!mayManage) return false
        val api = api ?: return false
        if (refuseOffline()) return false
        return try {
            api.saveWorker(HousekeepingRules.workerBody(form, stored, strings.locale), YuvomiClient.newIdempotencyKey())
            container.scope.launch { host.notify(strings.t("housekeeping.workerSavedToast")) }
            reloadQuietly()
            true
        } catch (e: ApiProblem) {
            reportWriteError(e)
            false
        }
    }

    // ── HOUSE-R30: `?editVisit=<id>` ──

    /** `openVisitFromDeepLink()`: the editor where allowed, the report otherwise. */
    fun openVisit(id: Int) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                val visit = api.visit(id)
                _state.update { it.copy(overlay = if (visit.canEdit && !readOnly) Overlay.VisitEdit(visit) else Overlay.VisitReport(visit)) }
            } catch (e: ApiProblem) {
                val transient = e is ApiProblem.Network || (e is ApiProblem.Http && (e.status >= 500 || e.status == 429))
                if (host.notify(strings.t(HousekeepingErrors.key(e)), if (transient) strings.t("common.retry") else null)) openVisit(id)
            }
        }
    }

    /** The chore and person links of the Tags view. */
    fun openTask(id: Int) {
        val task = state.value.data.tasks.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(tab = Tab.CHORES, overlay = if (!readOnly) Overlay.TaskEdit(task) else null) }
    }

    fun openWorker(id: Int) {
        if (state.value.data.workers.none { it.id == id }) return
        _state.update { it.copy(tab = Tab.STAFF) }
        selectStaff(id)
    }

    companion object {
        const val CACHE_KEY = "housekeeping"
        const val LOAD_ERROR = "housekeeping.loadError"

        /** `maxUploadBytes()` before `/version` says otherwise: the server default. */
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    }
}
