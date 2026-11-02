package io.github.marcomow.yuvomi.feature.waste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** What the waste screens need from the shell; an interface so tests need no shell. */
interface WasteHost {
    val strings: Strings
    fun today(): LocalDate

    /** `waste` module access: `none`, `read` or `write`. */
    fun access(): String

    suspend fun notify(message: String, action: String? = null): Boolean
}

/** docs/spec/waste.md: the page, its sheets and its wizards (WASTE-R1 to R38). */
class WasteViewModel(
    private val container: AppContainer,
    private val host: WasteHost,
    private val link: Link = Link(),
) : ViewModel() {

    /** `?type=&date=` (`parseDeepLinkParams`): the pickup or type to bring into view. */
    data class Link(val typeId: Int? = null, val date: String? = null)

    sealed interface Overlay {
        data class TypeEdit(val type: WasteType?) : Overlay
        data class ScheduleEdit(val type: WasteType?, val schedule: WasteSchedule?) : Overlay
        data class PickupEdit(val pickup: WastePickup?) : Overlay
        data class Move(val occurrence: WasteOccurrence) : Overlay
        data class ConfirmSkip(val occurrence: WasteOccurrence) : Overlay
        data class ConfirmDeletePickup(val pickupId: Int, val typeName: String) : Overlay
        data class Source(val source: WasteSource) : Overlay
        data class Import(val source: WasteSource?) : Overlay
        data object UrlSource : Overlay
        data class Reminders(val settings: List<ReminderSetting>) : Overlay
        data class Feed(val feed: WasteFeed?) : Overlay
        data class ProfileImport(val source: WasteSource, val profile: JsonElement, val preview: ProfilePreview) : Overlay
    }

    data class UiState(
        val data: WasteData = WasteData(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val loadFailed: Boolean = false,
        val upcomingExpanded: Boolean = false,
        val overlay: Overlay? = null,
        /** The deep link's target, highlighted once the page is drawn. */
        val highlight: Link? = null,
        val busy: Boolean = false,
    ) {
        val offline: Boolean get() = cachedAt != null
        val types: List<WasteType> get() = data.types
        fun type(id: Int?): WasteType? = data.types.firstOrNull { it.id == id }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: WasteApi? get() = container.session.connection?.client?.let(::WasteApi)
    val strings: Strings get() = host.strings
    private var loadSeq = 0
    var lastLoaded = 0L
        private set

    val readOnly: Boolean get() = host.access() != "write"

    init {
        container.cache.read(CACHE_KEY)?.let { cached ->
            runCatching { WasteApi.decodeData(cached.json) }.onSuccess { data -> _state.update { it.copy(data = data, loading = false) } }
        }
        refresh()
    }

    private fun t(key: String, vararg args: Pair<String, Any?>) = strings.t(key, args.toMap())
    private fun say(m: WasteMessage) = strings.t(m.key, m.args)

    // ── WASTE-R2: loading ──

    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        val seq = ++loadSeq
        _state.update { it.copy(refreshing = pulled, loadFailed = false, loading = it.loading || it.loadFailed) }
        viewModelScope.launch {
            try {
                reload(api, seq)
                val first = link
                if (first.typeId != null && state.value.highlight == null) {
                    _state.update {
                        it.copy(
                            highlight = first,
                            upcomingExpanded = it.upcomingExpanded || WasteRules.deepLinkNeedsExpand(it.data.occurrences, first.typeId, first.date),
                        )
                    }
                }
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
                if (!state.value.loadFailed && e !is ApiProblem.Network) host.notify(say(WasteErrors.message(e)))
            }
        }
    }

    private suspend fun reload(api: WasteApi, seq: Int = ++loadSeq) {
        val from = host.today()
        val data = api.load(from.toString(), from.plusDays(WasteRules.UPCOMING_WINDOW_DAYS).toString())
        if (seq != loadSeq) return
        container.cache.write(CACHE_KEY, WasteApi.encodeData(data))
        lastLoaded = System.currentTimeMillis()
        _state.update { it.copy(data = data, loading = false, refreshing = false, loadFailed = false, cachedAt = null) }
    }

    /** `reloadAfterSave()`: the write stands; a failed reload says nothing more. */
    private suspend fun reloadQuietly() {
        val api = api ?: return
        runCatching { reload(api) }
    }

    fun show(overlay: Overlay?) = _state.update { it.copy(overlay = overlay) }

    fun toggleUpcoming() = _state.update { it.copy(upcomingExpanded = !it.upcomingExpanded) }

    fun clearHighlight() = _state.update { it.copy(highlight = null) }

    fun tell(message: String) {
        viewModelScope.launch { host.notify(message) }
    }

    private suspend fun refuseOffline(): Boolean {
        if (readOnly) return true
        if (!state.value.offline) return false
        host.notify(t("common.errorOfflineMutation"))
        return true
    }

    /** `reportError()`: the app's sentence; a gone target closes the sheet and reloads. */
    private suspend fun reportError(e: Throwable, fallback: String = "common.errorGeneric") {
        host.notify(say(WasteErrors.message(e, fallback)))
        if (WasteErrors.isGone(e)) {
            _state.update { it.copy(overlay = null) }
            reloadQuietly()
        }
    }

    private suspend fun done(message: String) {
        _state.update { it.copy(overlay = null) }
        reloadQuietly()
        container.scope.launch { host.notify(message) }
    }

    /** Runs a write; false keeps the sheet open. [onError] may claim a failure (true: handled). */
    private suspend fun write(onError: suspend (ApiProblem) -> Boolean = { false }, block: suspend (WasteApi) -> Unit): Boolean {
        val api = api ?: return false
        if (refuseOffline()) return false
        _state.update { it.copy(busy = true) }
        return try {
            block(api)
            true
        } catch (e: ApiProblem) {
            if (!onError(e)) reportError(e)
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun key() = YuvomiClient.newIdempotencyKey()

    /** `keepDialogForMissingType()`: the chosen type went; the sheet stays and the page reloads. */
    private suspend fun keepForMissingType(e: ApiProblem): Boolean {
        if (!WasteErrors.isSelectedTypeGone(e)) return false
        host.notify(say(WasteErrors.message(e)))
        reloadQuietly()
        return true
    }

    // ── WASTE-R10 to R14: types ──

    suspend fun createPreset(preset: WasteRules.Preset): Boolean = write { api ->
        val name = t(preset.labelKey)
        api.createType(name, preset.icon, preset.color, key())
        done(t("common.createdNamed", "name" to name))
    }

    suspend fun saveType(type: WasteType?, name: String, icon: String, color: String): Boolean = write { api ->
        if (type == null) api.createType(name, icon, color, key()) else api.updateType(type.id, WasteApi.typeBody(name, icon, color))
        done(t(if (type == null) "common.createdNamed" else "common.savedNamed", "name" to name))
    }

    suspend fun toggleArchive(type: WasteType): Boolean = write { api ->
        api.updateType(type.id, buildJsonObject { put("archived", !type.archived) })
        done(t(if (type.archived) "waste.typeRestoredNamed" else "waste.typeArchivedNamed", "name" to type.name))
    }

    suspend fun deleteType(type: WasteType): Boolean = write { api ->
        try {
            api.deleteType(type.id)
        } catch (e: ApiProblem) {
            if (!WasteErrors.alreadyDeleted(e, WasteErrors.TYPE_GONE)) throw e
        }
        done(t("common.deletedNamed", "name" to type.name))
    }

    /** `moveType()`: swap `sort_order` with the neighbour among the listed types. */
    fun moveType(type: WasteType, up: Boolean) {
        val s = state.value
        val listed = s.types.filter { !WasteRules.isUnscheduled(it, s.data.schedules, s.data.occurrences) }
        val swap = WasteRules.moveSwap(listed, type.id, up) ?: return
        viewModelScope.launch {
            write { api ->
                api.updateType(swap.first.first, buildJsonObject { put("sort_order", swap.first.second) })
                api.updateType(swap.second.first, buildJsonObject { put("sort_order", swap.second.second) })
                reloadQuietly()
            }
        }
    }

    // ── WASTE-R15 to R20: schedules ──

    sealed interface ScheduleResult {
        data object Saved : ScheduleResult
        data object Kept : ScheduleResult
        data object WeekdaysMissing : ScheduleResult
    }

    suspend fun saveSchedule(schedule: WasteSchedule?, form: WasteRules.ScheduleForm): ScheduleResult {
        val body = WasteRules.scheduleBody(form)
        if (body == null) return if (form.typeId == null) ScheduleResult.Kept else ScheduleResult.WeekdaysMissing
        var weekdays = false
        val ok = write(onError = { e ->
            when {
                keepForMissingType(e) -> true
                WasteErrors.reason(e) == WasteErrors.WEEKDAYS_REQUIRED && form.kind == "weekly" -> { weekdays = true; true }
                else -> false
            }
        }) { api ->
            if (schedule == null) api.createSchedule(body, key()) else api.updateSchedule(schedule.id, body)
            done(scheduleSaved(state.value.type(form.typeId)?.name))
        }
        return when {
            ok -> ScheduleResult.Saved
            weekdays -> ScheduleResult.WeekdaysMissing
            else -> ScheduleResult.Kept
        }
    }

    suspend fun deleteSchedule(schedule: WasteSchedule): Boolean = write { api ->
        try {
            api.deleteSchedule(schedule.id)
        } catch (e: ApiProblem) {
            if (!WasteErrors.alreadyDeleted(e, WasteErrors.SCHEDULE_GONE)) throw e
        }
        done(t("waste.scheduleDeletedNamed", "name" to (state.value.type(schedule.typeId)?.name ?: "")))
    }

    /** `scheduleSavedSentence()`. */
    private fun scheduleSaved(name: String?) = if (name != null) t("waste.scheduleSavedNamed", "name" to name) else t("waste.scheduleSavedToast")

    // ── WASTE-R6 to R9: one occurrence ──

    suspend fun moveOccurrence(occurrence: WasteOccurrence, date: LocalDate): Boolean {
        val origin = WasteRules.scheduleOrigin(occurrence) ?: return false
        return write { api ->
            api.setOverride(origin.scheduleId, origin.originalDate, date.toString())
            done(scheduleSaved(occurrence.typeName))
        }
    }

    fun skipOccurrence(occurrence: WasteOccurrence) {
        val origin = WasteRules.scheduleOrigin(occurrence) ?: return
        _state.update { it.copy(overlay = null) }
        viewModelScope.launch {
            write { api ->
                api.setOverride(origin.scheduleId, origin.originalDate, null)
                done(scheduleSaved(occurrence.typeName))
            }
        }
    }

    fun restoreOccurrence(occurrence: WasteOccurrence) {
        val origin = WasteRules.scheduleOrigin(occurrence) ?: return
        viewModelScope.launch {
            write { api ->
                try {
                    api.deleteOverride(origin.scheduleId, origin.originalDate)
                } catch (e: ApiProblem) {
                    if (!WasteErrors.alreadyDeleted(e, WasteErrors.OVERRIDE_GONE)) throw e
                }
                done(scheduleSaved(occurrence.typeName))
            }
        }
    }

    // ── WASTE-R21 to R23: one-off pickups ──

    /** `loadPickupAndEdit()`. */
    fun editPickup(id: Int) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(overlay = Overlay.PickupEdit(api.pickup(id))) }
            } catch (e: ApiProblem) {
                reportError(e)
            }
        }
    }

    suspend fun savePickup(pickup: WastePickup?, typeId: Int, date: LocalDate, note: String): Boolean = write(onError = ::keepForMissingType) { api ->
        val body = WasteRules.pickupBody(typeId, date, note)
        if (pickup == null) api.createPickup(body, key()) else api.updatePickup(pickup.id, body)
        val name = state.value.type(typeId)?.name
        done(if (name != null) t("waste.pickupSavedNamed", "name" to name) else t("waste.pickupSavedToast"))
    }

    suspend fun deletePickup(id: Int, typeName: String): Boolean = write { api ->
        try {
            api.deletePickup(id)
        } catch (e: ApiProblem) {
            if (!WasteErrors.alreadyDeleted(e, WasteErrors.PICKUP_GONE)) throw e
        }
        done(t("waste.pickupDeletedNamed", "name" to typeName))
    }

    // ── WASTE-R24 to R33: sources and import ──

    /** `loadSourceAndOpen()`. */
    fun openSource(id: Int) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(overlay = Overlay.Source(api.source(id))) }
            } catch (e: ApiProblem) {
                reportError(e)
            }
        }
    }

    /** The source sheet's save: the name, then each changed mapping (`openSourceModal`). */
    suspend fun saveSource(source: WasteSource, name: String, mappings: Map<Int, Set<Int>>): Boolean {
        var failedMapping: Int? = null
        return write(onError = { e ->
            if (failedMapping != null && WasteErrors.isSelectedTypeGone(e)) {
                // `keepSourceDialogForMissingType()`: fresh mappings in the open sheet.
                host.notify(say(WasteErrors.message(e)))
                reloadQuietly()
                runCatching { api?.source(source.id) }.getOrNull()?.let { fresh -> _state.update { it.copy(overlay = Overlay.Source(fresh)) } }
                true
            } else false
        }) { api ->
            if (name.isNotBlank() && name.trim() != source.name) api.renameSource(source.id, name.trim())
            for (mapping in source.mappings) {
                val chosen = mappings[mapping.id] ?: continue
                failedMapping = mapping.id
                if (WasteRules.mappingChanged(mapping, chosen)) api.updateMapping(source.id, mapping.id, chosen.sorted())
            }
            done(t("waste.sourceSavedToast"))
        }
    }

    suspend fun deleteSource(source: WasteSource): Boolean = write { api ->
        try {
            api.deleteSource(source.id)
        } catch (e: ApiProblem) {
            if (!WasteErrors.alreadyDeleted(e, WasteErrors.SOURCE_GONE)) throw e
        }
        done(t("waste.sourceDeletedToast"))
    }

    /** The row action: review, check now, or re-import (`sourceRowHtml`). */
    fun sourceAction(source: WasteSource) {
        when (WasteRules.sourceAction(source)) {
            WasteRules.SourceAction.REFRESH -> refreshSource(source.id)
            else -> show(Overlay.Import(source))
        }
    }

    /** `triggerManualRefresh()`. */
    fun refreshSource(id: Int) {
        _state.update { it.copy(overlay = null) }
        viewModelScope.launch {
            write { api ->
                val result = api.refreshSource(id, key())
                reloadQuietly()
                outcome(result)
            }
        }
    }

    private suspend fun outcome(result: SourceOutcome) {
        val message = when (result.outcome) {
            "committed" -> committed(result.diff)
            "needs_mapping" -> t("waste.sourceNeedsMappingToast")
            "error" -> say(WasteErrors.fetchFailure(result.reason, result.params))
            null -> null
            else -> t("waste.sourceUnchangedToast")
        }
        if (message != null) host.notify(message)
    }

    private fun committed(diff: ImportDiff?) =
        t("waste.importCommittedToast", "added" to (diff?.added ?: 0), "changed" to (diff?.changed ?: 0), "removed" to (diff?.removed ?: 0))

    /** The wizard's first step. Null keeps the step; an error sentence goes to the sheet. */
    sealed interface PreviewResult {
        data class Ready(val preview: ImportPreview) : PreviewResult
        data class Failed(val message: String) : PreviewResult
        data object Closed : PreviewResult
    }

    suspend fun preview(source: WasteSource?, ics: String?): PreviewResult {
        val api = api ?: return PreviewResult.Closed
        if (refuseOffline()) return PreviewResult.Closed
        return try {
            val preview = api.preview(source?.id, if (source?.isUrl == true) null else ics, key())
            if (preview.unchanged) {
                _state.update { it.copy(overlay = null) }
                host.notify(t("waste.sourceUnchangedToast"))
                PreviewResult.Closed
            } else PreviewResult.Ready(preview)
        } catch (e: ApiProblem) {
            if (WasteErrors.isGone(e)) {
                reportError(e)
                PreviewResult.Closed
            } else PreviewResult.Failed(say(WasteErrors.message(e, "common.unknownError")))
        }
    }

    /** The commit; [onTypesGone] refreshes the label chips (`refreshImportTypeChoices`). */
    suspend fun commit(source: WasteSource?, body: JsonObject): PreviewResult {
        val api = api ?: return PreviewResult.Closed
        if (refuseOffline()) return PreviewResult.Closed
        return try {
            val result = api.commit(source?.id, body, key())
            _state.update { it.copy(overlay = null) }
            reloadQuietly()
            host.notify(committed(result.diff))
            PreviewResult.Closed
        } catch (e: ApiProblem) {
            when {
                WasteErrors.isSelectedTypeGone(e) -> {
                    reloadQuietly()
                    PreviewResult.Failed(say(WasteErrors.message(e, "common.unknownError")))
                }
                WasteErrors.isGone(e) -> {
                    reportError(e)
                    PreviewResult.Closed
                }
                else -> PreviewResult.Failed(say(WasteErrors.message(e, "common.unknownError")))
            }
        }
    }

    /** `openUrlSourceWizard()`'s save; a failure stays in the sheet. */
    suspend fun createUrlSource(name: String, url: String, interval: Int): String? {
        val api = api ?: return null
        if (refuseOffline()) return null
        return try {
            val result = api.createUrlSource(name.ifBlank { t("waste.importDefaultSourceName") }, url, interval, key())
            _state.update { it.copy(overlay = null) }
            reloadQuietly()
            outcome(result)
            null
        } catch (e: ApiProblem) {
            say(WasteErrors.message(e, "common.unknownError"))
        }
    }

    /** `exportMappingProfileFile()`: the JSON for the file the member saves. */
    suspend fun exportProfile(source: WasteSource): String? {
        val api = api ?: return null
        return try {
            prettyJson.encodeToString(JsonElement.serializer(), api.exportProfile(source.id))
        } catch (e: ApiProblem) {
            reportError(e)
            null
        }
    }

    /** `openMappingProfileImportModal()`: parse the file, preview it, show the review. */
    fun previewProfile(source: WasteSource, text: String) {
        val api = api ?: return
        val profile = runCatching { io.github.marcomow.yuvomi.core.api.ApiJson.parseToJsonElement(text) }.getOrNull()
        if (profile == null) {
            tell(t("waste.mappingProfileInvalidFile"))
            return
        }
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            try {
                val preview = api.previewProfile(source.id, profile, key())
                _state.update { it.copy(overlay = Overlay.ProfileImport(source, profile, preview)) }
            } catch (e: ApiProblem) {
                reportError(e)
            }
        }
    }

    suspend fun applyProfile(o: Overlay.ProfileImport): Boolean = write { api ->
        api.commitProfile(o.source.id, o.profile, o.preview.profileDigest, key())
        reloadQuietly()
        _state.update { it.copy(overlay = Overlay.Source(api.source(o.source.id))) }
        container.scope.launch { host.notify(t("waste.mappingProfileAppliedToast")) }
    }

    // ── WASTE-R34 to R36: reminders and the feed ──

    fun openReminders() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(overlay = Overlay.Reminders(api.reminderSettings())) }
            } catch (e: ApiProblem) {
                reportError(e)
            }
        }
    }

    /** Saves only the rows that changed (`openReminderSettingsModal`). */
    suspend fun saveReminders(original: List<ReminderSetting>, edited: List<ReminderSetting>): Boolean = write { api ->
        for (row in edited) {
            val before = original.firstOrNull { it.typeId == row.typeId }
            if (before == row) continue
            api.updateReminder(row)
        }
        _state.update { it.copy(overlay = null) }
        container.scope.launch { host.notify(t("waste.reminderSettingsSavedToast")) }
    }

    fun openFeed() {
        val api = api ?: return
        viewModelScope.launch {
            try {
                _state.update { it.copy(overlay = Overlay.Feed(api.feed())) }
            } catch (e: ApiProblem) {
                reportError(e)
            }
        }
    }

    companion object {
        const val CACHE_KEY = "waste"
        private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
    }
}
