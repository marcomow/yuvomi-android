package io.github.marcomow.yuvomi.feature.health

import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Idempotency keys of dose bookings (`createDoseAttempts`, #2069): a second tap on the SAME
 * action after a network or server failure sends the same key, so the server answers the
 * first booking instead of booking twice and deducting the stock twice.
 */
class DoseAttempts(private val now: () -> Long = System::currentTimeMillis, private val windowMs: Long = 2 * 60 * 1000) {
    private class Entry(val key: String, var failedAt: Long?)

    private val pending = mutableMapOf<String, Entry>()

    fun key(action: String): String {
        val entry = pending[action]?.takeIf { e -> e.failedAt.let { it == null || now() - it <= windowMs } }
            ?: Entry(HealthApi.newKey(), null).also { pending[action] = it }
        entry.failedAt = null
        return entry.key
    }

    /** `doseOutcomeUnknown`: keep the key when the first attempt may have arrived. */
    fun settle(action: String, error: Throwable? = null) {
        val unknown = when (error) {
            null -> false
            is ApiProblem.Network -> true
            is ApiProblem.Http -> error.status == 408 || error.status == 429 || error.status >= 500 ||
                (error.status == 409 && error.reason == "idempotency_in_progress")
            else -> false
        }
        if (unknown) pending[action]?.failedAt = now() else pending.remove(action)
    }
}

/** Dose booking shared by the overview and the medications area (`sendScheduledDose`, `handlePrnDose`, `acknowledgeDose`). */
internal class DoseBooker(private val host: HealthHost, private val attempts: DoseAttempts = DoseAttempts()) {
    var inFlight = emptySet<Int>()
        private set

    private fun stamp(): String = host.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

    suspend fun planned(api: HealthApi, dose: HealthMeds.Dose, log: MedLog?, take: Boolean, name: String, reload: () -> Unit) {
        val action = if (log != null) "log:${log.id}:$take" else "dose:${dose.medicationId}:${dose.scheduleId}:${dose.scheduledAt}:$take"
        book(api, dose.medicationId, name, take, action, reload) { key ->
            if (log != null) api.markLog(log.id, take, if (take) dose.doseQty else null, key)
            else api.logDose(dose.medicationId, HealthForms.doseBody(dose, take, stamp()), key)
        }
    }

    suspend fun prn(api: HealthApi, med: Medication, reload: () -> Unit) {
        book(api, med.id, med.name, true, "prn:${med.id}", reload) { key -> api.logDose(med.id, HealthForms.prnBody(med, stamp()), key) }
    }

    private suspend fun book(api: HealthApi, medId: Int, name: String, take: Boolean, action: String, reload: () -> Unit, call: suspend (String) -> DoseBooking) {
        if (medId in inFlight) return
        inFlight = inFlight + medId
        val booking = try {
            call(attempts.key(action)).also { attempts.settle(action) }
        } catch (e: ApiProblem) {
            attempts.settle(action, e)
            inFlight = inFlight - medId
            host.notify(host.strings.t(HealthErrors.key(e, "health.meds.doseError")))
            if (HealthErrors.isGone(e)) reload()
            return
        }
        inFlight = inFlight - medId
        reload()
        val s = host.strings
        val sentence = if (name.isNotEmpty()) s.t(if (take) "health.meds.doseTakenNamed" else "health.meds.doseSkippedNamed", "name" to name)
        else s.t("health.meds.doseSaved")
        val log = booking.log
        if (log == null) {
            host.notify(sentence)
            return
        }
        // The undo takes back the entry AND what the booking really deducted (`stock_deducted`).
        if (host.notify(sentence, s.t("common.undo"))) {
            try {
                api.undoDose(log.id, booking.stockDeducted)
            } catch (e: ApiProblem) {
                host.notify(s.t(HealthErrors.key(e, "health.meds.doseError")))
            }
            reload()
        }
    }
}

private fun logWindow(today: LocalDate): Pair<String, String> =
    "${today.minusDays((HealthMeds.LOG_WINDOW_DAYS - 1).toLong())}T00:00" to "${today}T23:59"

// ── Overview ──

@Serializable
data class OverviewData(
    val vitals: List<Vital> = emptyList(),
    val meds: List<Medication> = emptyList(),
    val periods: List<CyclePeriod> = emptyList(),
    val cycleLogs: List<CycleDayLog> = emptyList(),
    val cycleSettings: CycleSettings? = null,
    /** The status line per area of the "All categories" list; own view only (`refreshAreaStatus`). */
    val statuses: Map<String, String> = emptyMap(),
    val fastingStart: String? = null,
)

/** docs/spec/health.md, HEALTH-R9 (`mountOverview`, `loadOverview`, `refreshAllAreaStatuses`). */
class OverviewViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<OverviewData>(container, hub, host, OverviewData.serializer(), "overview") {

    private val booker = DoseBooker(host)

    override suspend fun fetch(api: HealthApi, personId: Int): OverviewData = coroutineScope {
        val today = host.today()
        val (from, to) = logWindow(today)
        val cycle = hub.state.value.cycleEnabled
        val own = personId == hub.meId
        val vitals = async { api.vitals(personId) }
        val meds = async { api.medications(personId, from, to) }
        val periods = async { if (cycle) api.periods(personId) else emptyList() }
        val logs = async { if (cycle) api.cycleLogs(personId) else emptyList() }
        // The cycle settings are private: only in the own view, a failure leaves the defaults.
        val settings = async { if (cycle && own) runCatching { api.cycleSettings() }.getOrNull() else null }
        val s = host.strings
        val date = { d: String -> host.date(d) }
        val labs = async { if (own) runCatching { HealthLabs.statusText(api.labs(null), date, s) }.getOrDefault("") else "" }
        val activity = async { if (own) runCatching { HealthActivity.statusText(api.activities(null), date, s) }.getOrDefault("") else "" }
        val prevention = async { if (own) runCatching { HealthPrevention.statusText(api.preventionDue(null), s) }.getOrDefault("") else "" }
        val nutrition = async { if (own) runCatching { HealthNutrition.statusText(api.nutritionSummary(null), s) }.getOrDefault("") else "" }
        val fasting = async { if (own && hub.fastingEnabled) runCatching { api.fastingState(null).active?.startAt }.getOrNull() else null }
        val medList = HealthMeds.trimLogs(meds.await(), today, 30)
        val cycleSettings = settings.await()
        val data = OverviewData(
            vitals = vitals.await(),
            meds = medList,
            periods = periods.await(),
            cycleLogs = logs.await(),
            cycleSettings = cycleSettings,
            fastingStart = fasting.await(),
        )
        data.copy(
            statuses = if (!own) emptyMap() else mapOf(
                "vitals" to HealthVitals.statusText(data.vitals, s),
                "meds" to HealthMeds.statusText(data.meds),
                "cycle" to if (cycle) HealthCycle.statusText(data.periods, cycleSettings ?: CycleSettings(), data.cycleLogs, today, s) else "",
                "labs" to labs.await(),
                "activity" to activity.await(),
                "prevention" to prevention.await(),
                "nutrition" to nutrition.await(),
            ),
        )
    }

    val dosesInFlight: Set<Int> get() = booker.inFlight

    fun dose(dose: HealthMeds.Dose, log: MedLog?, take: Boolean) {
        val api = api ?: return
        if (!hub.canEdit()) return
        val name = state.value.data?.meds?.firstOrNull { it.id == dose.medicationId }?.name.orEmpty()
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            booker.planned(api, dose, log, take, name) { refresh() }
        }
    }

    fun prn(med: Medication) {
        val api = api ?: return
        if (!hub.canEdit()) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            booker.prn(api, med) { refresh() }
        }
    }
}

// ── Vitals ──

@Serializable
data class VitalsData(val rows: List<Vital> = emptyList())

/** docs/spec/health.md, HEALTH-R10 to R16 (`mountVitals`, `openVitalModal`, `deleteVitalWithUndo`). */
class VitalsViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<VitalsData>(container, hub, host, VitalsData.serializer(), "vitals") {

    data class View(val range: String = "month", val anchor: LocalDate, val selected: String = "bp")

    val view = kotlinx.coroutines.flow.MutableStateFlow(View(anchor = host.today()))

    override suspend fun fetch(api: HealthApi, personId: Int) = VitalsData(api.vitals(personId))

    fun setRange(range: String) = view.update { it.copy(range = range) }
    fun step(dir: Int) = view.update { it.copy(anchor = HealthVitals.step(it.range, it.anchor, dir, host.today())) }
    fun select(type: String) = view.update { it.copy(selected = type) }

    fun rows(): List<Vital> = state.value.data?.rows.orEmpty().filterNot { hidden("v${it.id}") }

    fun save(form: HealthForms.VitalForm, editing: Vital?, onDone: () -> Unit) {
        when (val built = HealthForms.vitalBody(form, editing, hub.personId, hub.meId)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.vitals.saveError", "health.vitals.saved", onSuccess = {
                view.update { it.copy(selected = form.type) }
                onDone()
            }) { api ->
                if (editing != null) api.updateVital(editing.id, built.body) else api.createVital(built.body, HealthApi.newKey())
            }
        }
    }

    fun delete(row: Vital) = deleteWithUndo("v${row.id}", "health.vitals.measurementDeleted", "health.vitals.saveError") { it.deleteVital(row.id) }
}

// ── Medications ──

@Serializable
data class MedsData(val meds: List<Medication> = emptyList())

/** docs/spec/health.md, HEALTH-R17 to R25. */
class MedsViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<MedsData>(container, hub, host, MedsData.serializer(), "meds") {

    private val booker = DoseBooker(host)
    val dosesInFlight: Set<Int> get() = booker.inFlight

    override suspend fun fetch(api: HealthApi, personId: Int): MedsData {
        val today = host.today()
        val (from, to) = logWindow(today)
        return MedsData(HealthMeds.trimLogs(api.medications(personId, from, to), today, HealthMeds.ADHERENCE_DAYS))
    }

    fun meds(): List<Medication> = state.value.data?.meds.orEmpty().map { m -> m.copy(logs = m.logs.filterNot { hidden("l${it.id}") }) }

    fun dose(dose: HealthMeds.Dose, log: MedLog?, take: Boolean) {
        val api = api ?: return
        if (!hub.canEdit()) return
        val name = meds().firstOrNull { it.id == dose.medicationId }?.name.orEmpty()
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            booker.planned(api, dose, log, take, name) { refresh() }
        }
    }

    fun prn(med: Medication) {
        val api = api ?: return
        if (!hub.canEdit()) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            booker.prn(api, med) { refresh() }
        }
    }

    fun save(form: HealthForms.MedForm, med: Medication?, onDone: () -> Unit) {
        when (val built = HealthForms.medBody(form, med == null, hub.personId, hub.meId)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.meds.saveError", "health.meds.saved", onSuccess = onDone) { api ->
                if (med != null) api.updateMedication(med.id, built.body) else api.createMedication(built.body, HealthApi.newKey())
            }
        }
    }

    /** A time slot of an existing medication saves at once (`wireSchedEditor`). */
    fun addSlot(med: Medication, slot: HealthForms.SlotForm) {
        val body = HealthForms.slotBody(slot) ?: return run { viewModelScope.launch { host.notify(host.strings.t("health.meds.schedule.timeRequired")) } }
        write("health.meds.saveError") { api -> api.createSchedule(med.id, body, HealthApi.newKey()) }
    }

    fun deleteSlot(schedule: MedSchedule) = write("health.meds.saveError") { api -> api.deleteSchedule(schedule.id) }

    fun delete(med: Medication, onDone: () -> Unit) =
        write("health.meds.deleteError", "health.meds.deleted", onSuccess = onDone) { api -> api.deleteMedication(med.id) }

    fun saveLog(log: MedLog, status: String, date: LocalDate, time: String, onDone: () -> Unit) =
        write("health.meds.log.saveError", "health.meds.log.saved", onSuccess = onDone) { api -> api.updateLog(log.id, HealthForms.logBody(status, date, time)) }

    /** Only entries without a schedule can go (`DELETE /logs/{id}` answers 409 otherwise). */
    fun deleteLog(log: MedLog) = deleteWithUndo("l${log.id}", "health.meds.log.deleted", "health.meds.log.saveError") { it.deleteLog(log.id) }

    /** A medication from the list, with its current schedules, for the editor. */
    fun med(id: Int): Medication? = state.value.data?.meds?.firstOrNull { it.id == id }
}

// ── Fasting ──

@Serializable
data class FastingData(val state: FastingState = FastingState(), val stats: FastingStats? = null, val more: List<Fast> = emptyList(), val cursor: FastingCursor? = null)

/** docs/spec/health.md, HEALTH-R26 to R31 (pages/health-fasting.js, components/fasting-controls.js). */
class FastingViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<FastingData>(container, hub, host, FastingData.serializer(), "fasting") {

    override val loadErrorKey: String get() = "health.fasting.loadError"

    /** Fasting reads `user_id` only for someone else (`fastingSubjectParams`). */
    private fun subject(personId: Int): Int? = personId.takeIf { it != hub.meId }

    override suspend fun fetch(api: HealthApi, personId: Int): FastingData = coroutineScope {
        val state = async { api.fastingState(subject(personId)) }
        val stats = async { runCatching { api.fastingStats(subject(personId)) }.getOrNull() }
        val st = state.await()
        FastingData(st, stats.await(), cursor = st.historyNextCursor.takeIf { st.historyHasMore })
    }

    /** `requireFastingWrite`: own view, module write, and the capability. */
    val canWrite: Boolean get() = hub.isOwn && !hub.readOnly && hub.fastingEnabled

    fun history(): List<Fast> = state.value.data?.let { it.state.history + it.more }.orEmpty().filterNot { hidden("f${it.id}") }

    fun loadMore() {
        val api = api ?: return
        val cursor = state.value.data?.cursor ?: return
        val person = hub.personId
        viewModelScope.launch {
            try {
                val (rows, next) = api.fastingHistory(subject(person), cursor)
                _state.update { s -> s.copy(data = s.data?.copy(more = s.data.more + rows, cursor = next)) }
            } catch (e: ApiProblem) {
                host.notify(host.strings.t(HealthErrors.key(e, "health.fasting.loadError")))
            }
        }
    }

    /** `startFasting`: the safety note must be acknowledged first; the screen asks, this sends. */
    fun start(acknowledge: Boolean) {
        if (!canWrite) return
        val settings = state.value.data?.state?.settings
        write("health.fasting.saveError") { api ->
            api.startFast(buildJsonObject {
                put("start_tzid", ZoneId.systemDefault().id)
                put("goal_minutes", settings?.defaultGoalMinutes?.let(::JsonPrimitive) ?: JsonNull)
                put("acknowledge_safety", acknowledge)
            }, HealthApi.newKey())
        }
    }

    /** `finishFasting`: the end is saved first; the snackbar offers to undo it. */
    fun finish(active: Fast) {
        if (!canWrite) return
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            val done = try {
                api.finishFast(active)
            } catch (e: ApiProblem) {
                host.notify(host.strings.t(HealthErrors.key(e, "health.fasting.saveError")))
                refresh()
                return@launch
            }
            refresh()
            val s = host.strings
            if (host.notify(s.t("health.fasting.finishedTitle"), s.t("health.fasting.undoEnd"))) {
                try {
                    api.updateFast(done.id, buildJsonObject {
                        put("end_at", JsonNull)
                        put("expected_revision", done.revision)
                    })
                } catch (e: ApiProblem) {
                    host.notify(s.t(HealthErrors.key(e, "health.fasting.saveError")))
                }
                refresh()
            }
        }
    }

    /** Edit a fast: start and end in household wall time, rating, note (`openFastingEditor`). */
    fun update(fast: Fast, start: ZonedDateTime, end: ZonedDateTime?, rating: Int?, note: String, onDone: () -> Unit) {
        if (end != null && !end.isAfter(start)) {
            viewModelScope.launch { host.notify(host.strings.t("health.fasting.rangeError")) }
            return
        }
        write("health.fasting.saveError", onSuccess = onDone) { api ->
            api.updateFast(fast.id, buildJsonObject {
                put("start_at", start.toInstant().toString())
                if (fast.endAt != null) put("end_at", end?.toInstant()?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("rating", rating?.let(::JsonPrimitive) ?: JsonNull)
                put("note", note.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
                put("expected_revision", fast.revision)
            })
        }
    }

    fun delete(fast: Fast) = deleteWithUndo("f${fast.id}", "health.fasting.deleted", "health.fasting.saveError") { it.deleteFast(fast) }

    /** The goal of the running fast and the default for the next ones in one write (`fastingGoalHtml`). */
    fun setGoal(minutes: Int?) {
        if (!canWrite) return
        val active = state.value.data?.state?.active
        write("health.fasting.saveError") { api ->
            api.saveFastingSettings(buildJsonObject {
                put("default_goal_minutes", minutes?.let(::JsonPrimitive) ?: JsonNull)
                put("active_id", active?.id?.let(::JsonPrimitive) ?: JsonNull)
                if (active != null) put("expected_revision", active.revision)
            })
        }
    }

    fun setReminder(goal: Boolean? = null, next: Boolean? = null) {
        if (!canWrite) return
        write("health.fasting.saveError") { api ->
            api.saveFastingSettings(buildJsonObject {
                goal?.let { put("remind_goal", it) }
                next?.let { put("remind_next_start", it) }
            })
        }
    }
}

// ── Cycle ──

@Serializable
data class CycleData(val periods: List<CyclePeriod> = emptyList(), val logs: List<CycleDayLog> = emptyList(), val settings: CycleSettings? = null)

/** docs/spec/health.md, HEALTH-R32 to R38. */
class CycleViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<CycleData>(container, hub, host, CycleData.serializer(), "cycle") {

    override suspend fun fetch(api: HealthApi, personId: Int): CycleData = coroutineScope {
        val periods = async { api.periods(personId) }
        val logs = async { api.cycleLogs(personId) }
        val settings = async { if (personId == hub.meId) runCatching { api.cycleSettings() }.getOrNull() else null }
        CycleData(periods.await(), logs.await(), settings.await())
    }

    val canEdit: Boolean get() = hub.cycleCanEdit()

    fun settings(): CycleSettings = state.value.data?.settings ?: CycleSettings()

    fun periods(): List<CyclePeriod> = state.value.data?.periods.orEmpty().filterNot { hidden("p${it.id}") }

    fun logs(): List<CycleDayLog> = state.value.data?.logs.orEmpty().filterNot { hidden("d${it.id}") }

    /** "Period started" today (`startPeriodToday`). */
    fun startToday() {
        if (!canEdit) return
        write("common.errorGeneric", "health.cycle.today.startedToast") { api ->
            api.createPeriod(buildJsonObject {
                put("start_date", host.today().toString())
                put("visibility", settings().defaultVisibility)
            }, HealthApi.newKey())
        }
    }

    /** "Period ended" today (`endPeriodToday`). */
    fun endToday() {
        if (!canEdit) return
        val open = HealthCycle.openPeriod(periods(), host.today())
        if (open == null) {
            viewModelScope.launch { host.notify(host.strings.t("health.cycle.today.noOpenPeriod")) }
            return
        }
        write("common.errorGeneric", "health.cycle.today.endedToast") { api ->
            api.updatePeriod(open.id, buildJsonObject { put("end_date", host.today().toString()) })
        }
    }

    fun savePeriod(period: CyclePeriod?, start: LocalDate, end: LocalDate?, note: String, visibility: String, onDone: () -> Unit) {
        when (val built = HealthForms.periodBody(start, end, note, visibility)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.cycle.saveError", "health.cycle.saved", onSuccess = onDone) { api ->
                if (period != null) api.updatePeriod(period.id, built.body) else api.createPeriod(built.body, HealthApi.newKey())
            }
        }
    }

    fun deletePeriod(period: CyclePeriod) = deleteWithUndo("p${period.id}", "health.cycle.periodDeleted", "health.cycle.deleteError") { it.deletePeriod(period.id) }

    fun saveDay(form: HealthForms.DayForm, onDone: () -> Unit) {
        when (val built = HealthForms.dayBody(form)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.cycle.saveError", "health.cycle.saved", onSuccess = onDone) { api -> api.saveCycleLog(built.body, HealthApi.newKey()) }
        }
    }

    fun deleteDay(log: CycleDayLog) = deleteWithUndo("d${log.id}", "health.cycle.logDeleted", "health.cycle.deleteError") { it.deleteCycleLog(log.id) }

    fun saveSettings(settings: CycleSettings, onDone: () -> Unit) =
        write("common.errorGeneric", "health.cycle.settings.saved", onSuccess = onDone) { api -> api.saveCycleSettings(HealthForms.cycleSettingsBody(settings)) }

    /** "Apply to all entries" (`PATCH /cycle/visibility`). */
    fun applyVisibility(visibility: String) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            try {
                val (p, l) = api.setCycleVisibility(visibility)
                host.notify(host.strings.t("health.cycle.settings.applyToAllDone", "count" to p + l))
                refresh()
            } catch (e: ApiProblem) {
                host.notify(host.strings.t(HealthErrors.key(e, "common.errorGeneric")))
            }
        }
    }
}

// ── Prevention ──

@Serializable
data class PreventionData(val types: List<PreventionType> = emptyList(), val records: List<PreventionRecord> = emptyList(), val due: List<PreventionDue> = emptyList())

/** docs/spec/health.md, HEALTH-R39 to R41. */
class PreventionViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<PreventionData>(container, hub, host, PreventionData.serializer(), "prevention") {

    override suspend fun fetch(api: HealthApi, personId: Int): PreventionData = coroutineScope {
        val types = async { api.preventionTypes() }
        val records = async { api.preventionRecords(personId) }
        val due = async { api.preventionDue(personId) }
        PreventionData(types.await(), records.await(), due.await())
    }

    fun records(): List<PreventionRecord> = state.value.data?.records.orEmpty().filterNot { hidden("r${it.id}") }

    fun save(form: HealthForms.PreventionForm, record: PreventionRecord?, onDone: () -> Unit) {
        when (val built = HealthForms.preventionBody(form, record == null, hub.personId, hub.meId)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.prevention.saveError", "health.prevention.saved", onSuccess = onDone) { api ->
                if (record != null) api.updatePreventionRecord(record.id, built.body) else api.createPreventionRecord(built.body, HealthApi.newKey())
            }
        }
    }

    fun delete(record: PreventionRecord) =
        deleteWithUndo("r${record.id}", "health.prevention.deleted", "health.prevention.deleteError") { it.deletePreventionRecord(record.id) }
}

// ── Labs ──

@Serializable
data class LabsData(val reports: List<LabReport> = emptyList())

/** docs/spec/health.md, HEALTH-R42 to R45. */
class LabsViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<LabsData>(container, hub, host, LabsData.serializer(), "labs") {

    override suspend fun fetch(api: HealthApi, personId: Int) = LabsData(api.labs(personId))

    fun reports(): List<LabReport> = state.value.data?.reports.orEmpty()

    fun report(id: Int): LabReport? = reports().firstOrNull { it.id == id }

    /** Saves the report head; a new report answers its id so the analytes can follow (`openLabModal`). */
    fun save(report: LabReport?, date: LocalDate, labName: String, note: String, visibility: String, onDone: (Int) -> Unit) {
        val body = HealthForms.labBody(date, labName, note, visibility, report == null, hub.personId, hub.meId)
        var id = report?.id ?: 0
        write("health.labs.saveError", "health.labs.saved", onSuccess = { onDone(id) }) { api ->
            id = if (report != null) api.updateLab(report.id, body).id else api.createLab(body, HealthApi.newKey()).id
        }
    }

    fun delete(report: LabReport, onDone: () -> Unit) =
        write("health.labs.deleteError", "health.labs.deleted", onSuccess = onDone) { api -> api.deleteLab(report.id) }

    fun saveResult(reportId: Int, result: LabResult?, form: HealthForms.ResultForm, onDone: () -> Unit) {
        when (val built = HealthForms.resultBody(form)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.labs.saveError", onSuccess = onDone) { api ->
                if (result != null) api.updateResult(result.id, built.body) else api.createResult(reportId, built.body, HealthApi.newKey())
            }
        }
    }

    fun deleteResult(result: LabResult) = write("health.labs.saveError") { api -> api.deleteResult(result.id) }
}

// ── Activity ──

@Serializable
data class ActivityData(val rows: List<Activity> = emptyList())

/** docs/spec/health.md, HEALTH-R46 to R48. */
class ActivityViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<ActivityData>(container, hub, host, ActivityData.serializer(), "activity") {

    val anchor = kotlinx.coroutines.flow.MutableStateFlow(host.today())

    override suspend fun fetch(api: HealthApi, personId: Int) = ActivityData(api.activities(personId))

    fun rows(): List<Activity> = state.value.data?.rows.orEmpty().filterNot { hidden("a${it.id}") }

    fun step(dir: Int) = anchor.update { HealthVitals.step("week", it, dir, host.today()) }

    fun save(form: HealthForms.ActivityForm, row: Activity?, onDone: () -> Unit) {
        when (val built = HealthForms.activityBody(form, row != null, hub.personId, hub.meId)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.activity.saveError", "health.activity.saved", onSuccess = onDone) { api ->
                if (row != null) api.updateActivity(row.id, built.body) else api.createActivity(built.body, HealthApi.newKey())
            }
        }
    }

    fun delete(row: Activity) = deleteWithUndo("a${row.id}", "health.activity.deleted", "health.activity.deleteError") { it.deleteActivity(row.id) }
}

// ── Nutrition ──

@Serializable
data class NutritionData(val entries: List<NutritionEntry> = emptyList(), val summary: NutritionSummary? = null)

/** docs/spec/health.md, HEALTH-R49 to R51. */
class NutritionViewModel(container: AppContainer, hub: HealthHub, host: HealthHost) :
    HealthAreaViewModel<NutritionData>(container, hub, host, NutritionData.serializer(), "nutrition") {

    override suspend fun fetch(api: HealthApi, personId: Int): NutritionData = coroutineScope {
        // The entries of the last 30 days; the summary is today's balance and target.
        val entries = async { api.nutritionEntries(personId, host.today().minusDays(29).toString()) }
        val summary = async { api.nutritionSummary(personId) }
        NutritionData(entries.await(), summary.await())
    }

    fun entries(): List<NutritionEntry> = state.value.data?.entries.orEmpty().filterNot { hidden("n${it.id}") }

    /** The form's visibility default: this table writes `all` for the open value (`defaultNutritionVisibility`). */
    fun defaultVisibility(): String = if (hub.defaultVisibility("nutrition") == "family") "all" else "private"

    fun save(form: HealthForms.NutritionForm, entry: NutritionEntry?, onDone: () -> Unit) {
        when (val built = HealthForms.nutritionBody(form, entry == null, hub.personId, hub.meId)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.nutrition.saveError", "health.nutrition.saved", onSuccess = onDone) { api ->
                if (entry != null) api.updateNutritionEntry(entry.id, built.body) else api.createNutritionEntry(built.body, HealthApi.newKey())
            }
        }
    }

    fun delete(entry: NutritionEntry) =
        deleteWithUndo("n${entry.id}", "health.nutrition.deleted", "health.nutrition.saveError") { it.deleteNutritionEntry(entry.id) }

    fun saveTarget(values: Map<String, String>, onDone: () -> Unit) {
        when (val built = HealthForms.targetBody(values, hub.personId, hub.meId)) {
            is HealthForms.Built.Invalid -> viewModelScope.launch { host.notify(host.strings.t(built.key)) }
            is HealthForms.Built.Ok -> write("health.nutrition.targetSaveError", "health.nutrition.targetSaved", onSuccess = onDone) { api ->
                api.saveNutritionTarget(built.body)
            }
        }
    }
}

internal fun JsonObject.has(key: String) = containsKey(key)
