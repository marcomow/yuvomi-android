package io.github.marcomow.yuvomi.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.YearMonth

/** What the schedule screens need from the shell; an interface so the view model is tested without one. */
interface ScheduleHost {
    val strings: Strings
    val userId: Int
    val readOnly: Boolean
    val isAdmin: Boolean

    fun today(): LocalDate

    /** Shows [message]; true when the member tapped [action]. */
    suspend fun notify(message: String, action: String? = null): Boolean
}

/** The four tabs, in the web's order (`scheduleHeadHtml`), with their route segment (utils/schedule-tabs.js). */
enum class ScheduleTab(val segment: String, val labelKey: String) {
    OVERVIEW("overview", "schedule.overview"),
    SHIFTS("shifts", "schedule.shiftTypes"),
    PATTERNS("patterns", "schedule.planning"),
    STATISTICS("statistics", "schedule.statistics"),
    ;

    companion object {
        /** `scheduleViewFromPath`: null for the bare root or an unknown segment. */
        fun of(segment: String?): ScheduleTab? = entries.firstOrNull { it.segment == segment }
    }
}

/** docs/spec/schedule.md: the page (SCHED-R1 to R40). */
class ScheduleViewModel(
    private val container: AppContainer,
    private val host: ScheduleHost,
    requestedTab: ScheduleTab? = null,
    /** A row to open once loaded (`ScheduleRoutes.open`), e.g. "pattern:3". */
    private val openKey: String? = null,
) : ViewModel() {

    data class OverviewState(
        val cursor: LocalDate,
        val dayMode: Boolean = false,
        val selected: List<Int> = emptyList(),
        val entries: List<Entry> = emptyList(),
        val holidays: List<Holiday> = emptyList(),
        val loading: Boolean = false,
        val failed: Boolean = false,
    )

    data class StatsState(
        val userId: Int,
        val range: ScheduleRules.StatsRange = ScheduleRules.StatsRange.CURRENT,
        val monthFrom: YearMonth,
        val monthTo: YearMonth,
        val from: LocalDate,
        val to: LocalDate,
        val entries: List<Entry> = emptyList(),
        val loading: Boolean = false,
        val failed: Boolean = false,
        val loaded: Boolean = false,
    )

    /** The member's own feed (`settings/pages/personal-feeds.js`, part `schedule`). */
    data class FeedState(val loading: Boolean = true, val feed: Feed? = null, val failed: Boolean = false, val busy: Boolean = false)

    data class UiState(
        val data: ScheduleData = ScheduleData(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val failed: Boolean = false,
        val tab: ScheduleTab? = null,
        val today: LocalDate,
        val overview: OverviewState,
        val stats: StatsState,
        val busy: Boolean = false,
        val feed: FeedState = FeedState(),
        /** A row the route asked to open; the screen clears it once shown. */
        val pendingOpen: String? = null,
    ) {
        val offline: Boolean get() = cachedAt != null
    }

    @Serializable
    private data class OverviewCache(val entries: List<Entry> = emptyList(), val holidays: List<Holiday> = emptyList())

    private val today0 = host.today()
    private val _state = MutableStateFlow(
        UiState(
            tab = requestedTab,
            today = today0,
            overview = OverviewState(cursor = today0, dayMode = readSavedDayMode()),
            stats = StatsState(userId = host.userId, monthFrom = YearMonth.from(today0), monthTo = YearMonth.from(today0), from = today0, to = today0),
            pendingOpen = openKey,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val api: ScheduleApi? get() = container.session.connection?.client?.let(::ScheduleApi)
    private var overviewSeq = 0
    private var statsSeq = 0
    var lastLoaded = 0L
        private set

    val viewer: ScheduleRules.Viewer get() = ScheduleRules.Viewer(host.userId, host.readOnly, host.isAdmin)

    init {
        readCache()
        refresh()
    }

    private fun readCache() {
        val cached = container.cache.read(CACHE_KEY) ?: return
        runCatching { ScheduleApi.decode(cached.json) }.onSuccess { data ->
            _state.update { it.copy(data = data, loading = false, tab = it.tab ?: startTab(data)) }
            applySelection(data)
        }
    }

    /** `render`: a deep link wins; the bare root starts on Shift types while nothing exists, else Overview. */
    private fun startTab(data: ScheduleData) = if (ScheduleRules.planningNeedsShiftTypes(data)) ScheduleTab.SHIFTS else ScheduleTab.OVERVIEW

    private fun applySelection(data: ScheduleData) {
        val eligible = data.people.map { it.id }
        _state.update { it.copy(overview = it.overview.copy(selected = ScheduleRules.initialSelection(readSavedSelection(), eligible, host.userId))) }
    }

    /** `load()` then the tab's own data (`activateView`). */
    fun refresh(pulled: Boolean = false) {
        val api = api ?: return
        _state.update { it.copy(refreshing = pulled, failed = false, loading = it.loading || it.failed) }
        viewModelScope.launch {
            try {
                val today = host.today()
                val data = api.load(today)
                container.cache.write(CACHE_KEY, ScheduleApi.encode(data))
                lastLoaded = System.currentTimeMillis()
                _state.update { it.copy(data = data, loading = false, refreshing = false, cachedAt = null, today = today, tab = it.tab ?: startTab(data)) }
                applySelection(data)
                if (state.value.tab == ScheduleTab.OVERVIEW) loadOverview()
                if (state.value.tab == ScheduleTab.STATISTICS) loadStatistics()
            } catch (e: ApiProblem) {
                val hasData = !state.value.loading
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        cachedAt = if (hasData && e is ApiProblem.Network) it.cachedAt ?: container.cache.read(CACHE_KEY)?.savedAt ?: System.currentTimeMillis() else it.cachedAt,
                        failed = !hasData,
                    )
                }
                if (hasData && e is ApiProblem.Network) {
                    if (state.value.tab == ScheduleTab.OVERVIEW) loadOverview()
                } else if (hasData) {
                    host.notify(host.strings.t(ScheduleErrors.key(e)))
                }
            }
        }
    }

    /** `navigateToView`: the tab's data loads fresh, like a tab switch on the web. */
    fun selectTab(tab: ScheduleTab) {
        _state.update { it.copy(tab = tab) }
        when (tab) {
            ScheduleTab.OVERVIEW -> loadOverview()
            ScheduleTab.STATISTICS -> loadStatistics()
            else -> Unit
        }
    }

    fun consumeOpen() = _state.update { it.copy(pendingOpen = null) }

    // ── Overview (SCHED-R6 to R13) ──

    private fun first() = ScheduleRules.weekStart(state.value.data.household.weekStart)

    /** `refreshOverview`: the week (one day before for a night shift) and its holidays; the newest request wins. */
    fun loadOverview() {
        val api = api
        val seq = ++overviewSeq
        val range = ScheduleRules.overviewFetchRange(state.value.overview.cursor, first())
        val cacheKey = "schedule-overview-${range.from}"
        _state.update { it.copy(overview = it.overview.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                if (api == null) throw ApiProblem.Malformed("signed out")
                val entries = api.entries(range.entriesFrom, range.to).entries
                val holidays = runCatching { api.holidays(range.from, range.to) }.getOrDefault(emptyList())
                if (seq != overviewSeq) return@launch
                container.cache.write(cacheKey, ApiJson.encodeToString(OverviewCache.serializer(), OverviewCache(entries, holidays)))
                _state.update { it.copy(overview = it.overview.copy(entries = entries, holidays = holidays, loading = false, failed = false)) }
            } catch (e: ApiProblem) {
                if (seq != overviewSeq) return@launch
                val cached = container.cache.read(cacheKey)?.let { runCatching { ApiJson.decodeFromString(OverviewCache.serializer(), it.json) }.getOrNull() }
                _state.update {
                    if (cached != null && e is ApiProblem.Network) {
                        it.copy(overview = it.overview.copy(entries = cached.entries, holidays = cached.holidays, loading = false), cachedAt = it.cachedAt ?: System.currentTimeMillis())
                    } else {
                        it.copy(overview = it.overview.copy(entries = emptyList(), holidays = emptyList(), loading = false, failed = true))
                    }
                }
            }
        }
    }

    /** `overview-week`: back, forward (a day or a week), or today. */
    fun stepOverview(direction: Int) {
        val o = state.value.overview
        val cursor = when {
            direction == 0 -> host.today()
            o.dayMode -> o.cursor.plusDays(direction.toLong())
            else -> o.cursor.plusWeeks(direction.toLong())
        }
        _state.update { it.copy(overview = it.overview.copy(cursor = cursor)) }
        loadOverview()
    }

    /** `overview-view-mode`; the choice is kept on this device (`OVERVIEW_VIEW_KEY`). */
    fun setDayMode(day: Boolean) {
        _state.update { it.copy(overview = it.overview.copy(dayMode = day)) }
        container.cache.write(MODE_KEY, if (day) "\"day\"" else "\"week\"")
    }

    /** `overview-open-day`: a day head opens that day. */
    fun openDay(date: LocalDate) {
        val sameWeek = ScheduleRules.startOfWeek(date, first()) == ScheduleRules.startOfWeek(state.value.overview.cursor, first())
        _state.update { it.copy(overview = it.overview.copy(cursor = date)) }
        setDayMode(true)
        if (!sameWeek) loadOverview()
    }

    /** `openOverviewPeople`: a chip toggles a person; the lanes keep the household order. */
    fun togglePerson(id: Int) {
        val people = state.value.data.people.map { it.id }
        val chosen = state.value.overview.selected.toMutableSet().apply { if (!add(id)) remove(id) }
        val next = people.filter { it in chosen }
        _state.update { it.copy(overview = it.overview.copy(selected = next)) }
        container.cache.write(SELECTION_KEY, ApiJson.encodeToString(ListSerializer(Int.serializer()), next))
    }

    private fun readSavedSelection(): List<Int>? =
        container.cache.read(SELECTION_KEY)?.let { runCatching { ApiJson.decodeFromString(ListSerializer(Int.serializer()), it.json) }.getOrNull() }

    private fun readSavedDayMode(): Boolean = container.cache.read(MODE_KEY)?.json?.contains("day") == true

    // ── Statistics (SCHED-R30 to R34) ──

    /** `refreshStatistics`: no request for an invalid period; the newest request wins. */
    fun loadStatistics() {
        val s = state.value.stats
        val bounds = statsBounds(s)
        val seq = ++statsSeq
        if (bounds == null) {
            _state.update { it.copy(stats = it.stats.copy(entries = emptyList(), loading = false, failed = false, loaded = true)) }
            return
        }
        val api = api
        val key = "schedule-stats-${s.userId}-${bounds.from}-${bounds.to}"
        _state.update { it.copy(stats = it.stats.copy(entries = emptyList(), loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                if (api == null) throw ApiProblem.Malformed("signed out")
                val answer = api.entries(bounds.from, bounds.to, s.userId)
                if (seq != statsSeq) return@launch
                container.cache.write(key, ScheduleApi.encodeEntries(answer))
                _state.update { it.copy(stats = it.stats.copy(entries = answer.entries, loading = false, loaded = true)) }
            } catch (e: ApiProblem) {
                if (seq != statsSeq) return@launch
                val cached = container.cache.read(key)?.let { runCatching { ScheduleApi.decodeEntries(it.json) }.getOrNull() }
                _state.update {
                    if (cached != null && e is ApiProblem.Network) {
                        it.copy(stats = it.stats.copy(entries = cached.entries, loading = false, loaded = true), cachedAt = it.cachedAt ?: System.currentTimeMillis())
                    } else {
                        it.copy(stats = it.stats.copy(loading = false, failed = true, loaded = true))
                    }
                }
            }
        }
    }

    fun statsBounds(s: StatsState = state.value.stats): ScheduleRules.Bounds? =
        ScheduleRules.statisticBounds(s.range, host.today(), s.monthFrom, s.monthTo, s.from, s.to)

    /** Every change of the filter computes again at once (R19: no apply button). */
    fun updateStats(change: (StatsState) -> StatsState) {
        _state.update { it.copy(stats = change(it.stats)) }
        loadStatistics()
    }

    // ── Writes ──

    private suspend fun refuseOffline(): Boolean {
        if (!state.value.offline) return false
        host.notify(host.strings.t("common.errorOfflineMutation"))
        return true
    }

    /** `reloadAfterWrite`: the data, and the overview's week when it is open. */
    private suspend fun reload() {
        val api = api ?: return
        val data = api.load(host.today())
        container.cache.write(CACHE_KEY, ScheduleApi.encode(data))
        _state.update { it.copy(data = data) }
        if (state.value.tab == ScheduleTab.OVERVIEW) loadOverview()
        if (state.value.tab == ScheduleTab.STATISTICS) loadStatistics()
    }

    /** `reportScheduleError`: the sentence, and a fresh page when the target is gone. */
    private suspend fun report(e: ApiProblem) {
        host.notify(host.strings.t(ScheduleErrors.key(e)))
        if (ScheduleErrors.isGone(e)) runCatching { reload() }
    }

    /**
     * Runs one dialog's write. On success: reload, [done], then "Saved." (or [message]). On
     * failure the dialog stays open with its input and the sentence is shown (`saveCreatedSchedule`).
     */
    private fun write(message: String = "schedule.saved", reloadOnFailure: Boolean = false, done: () -> Unit = {}, block: suspend (ScheduleApi) -> Unit) {
        val api = api ?: return
        if (state.value.busy) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(busy = true) }
            try {
                block(api)
                runCatching { reload() }
                done()
                host.notify(host.strings.t(message))
            } catch (e: ApiProblem) {
                host.notify(host.strings.t(ScheduleErrors.key(e)))
                if (reloadOnFailure || ScheduleErrors.isGone(e)) runCatching { reload() }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Deletes that someone else already did are no failure (`deleteUnlessGone`). */
    private suspend fun deleteUnlessGone(reason: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiProblem) {
            if (!ScheduleErrors.isAlreadyDeleted(e, reason)) throw e
        }
    }

    // Shift types (SCHED-R14 to R20)

    /** `saveCreatedSchedule` (`shift-create`) and `saveShiftTypeEdit`: details, then the attached fields. */
    fun saveShiftType(editing: ShiftType?, form: ShiftTypeForm, done: () -> Unit) {
        if (ScheduleChecks.shiftType(form).isNotEmpty()) return
        val body = ScheduleBodies.shiftType(form.name, form.shortCode, form.color, form.icon, form.start, form.end)
        write(reloadOnFailure = editing != null, done = done) { api ->
            if (editing == null) {
                api.createShiftType(body, YuvomiClient.newIdempotencyKey())
            } else {
                api.updateShiftType(editing.id, body)
                form.fields?.let { api.setShiftTypeFields(editing.id, it) }
            }
        }
    }

    fun deleteShiftType(type: ShiftType) = write(message = "schedule.deleted") { api ->
        deleteUnlessGone(ScheduleErrors.SHIFT_TYPE_GONE) { api.deleteShiftType(type.id) }
    }

    /**
     * `quick-start-shifts`: every preset of the template that is not there yet; the ones made
     * before a failure stay and show.
     */
    fun quickStart(template: String) {
        val api = api ?: return
        if (state.value.busy) return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(busy = true) }
            var created = 0
            try {
                for (preset in ScheduleRules.quickStartPresets(template, state.value.data.types, host.strings)) {
                    api.createShiftType(
                        ScheduleBodies.shiftType(
                            ScheduleRules.presetName(preset, host.strings), ScheduleRules.presetShortCode(preset, host.strings),
                            preset.color, preset.icon, preset.start, preset.end,
                        ),
                        YuvomiClient.newIdempotencyKey(),
                    )
                    created++
                }
                runCatching { reload() }
                host.notify(if (created > 0) host.strings.t("schedule.quickStartCreated", "count" to created) else host.strings.t("schedule.quickStartNothingNew"))
            } catch (e: ApiProblem) {
                runCatching { reload() }
                report(e)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun saveCustomField(editing: CustomField?, name: String, done: () -> Unit) {
        if (name.isBlank()) return
        write(done = done) { api ->
            if (editing == null) api.createCustomField(name, YuvomiClient.newIdempotencyKey()) else api.renameCustomField(editing.id, name)
        }
    }

    fun deleteCustomField(field: CustomField) = write(message = "schedule.deleted") { api ->
        deleteUnlessGone(ScheduleErrors.CUSTOM_FIELD_GONE) { api.deleteCustomField(field.id) }
    }

    // Patterns (SCHED-R21 to R25)

    /** `findOverlappingActivePattern`, for the question before a plan takes over. */
    fun overlapFor(userId: Int, form: PatternForm, excludeId: Int? = null): Pattern? =
        if (!form.active) null else ScheduleRules.overlappingActivePattern(state.value.data.patterns, userId, form.validFrom?.toString(), form.validUntil?.toString(), excludeId)

    fun createPattern(form: PatternForm, done: () -> Unit) {
        val length = form.length ?: return
        val userId = form.userId ?: return
        write(done = done) { api ->
            api.createPattern(
                ScheduleBodies.pattern(userId, form.name, form.anchor.toString(), length, form.active, form.validFrom?.toString(), form.validUntil?.toString()),
                YuvomiClient.newIdempotencyKey(),
            )
        }
    }

    /** `savePatternEdit`: details and days behind one Save, in `patternSaveOrder`. */
    fun savePattern(pattern: Pattern, form: PatternForm, rows: List<DayRowForm>, done: () -> Unit) {
        val length = form.length ?: return
        val days = rows.filter { it.position < length }.sortedBy { it.position }
            .map { ScheduleBodies.DayRow(it.position, it.shiftTypeId, it.fieldValues) }
        val body = ScheduleBodies.pattern(null, form.name, form.anchor.toString(), length, form.active, form.validFrom?.toString(), form.validUntil?.toString())
        write(reloadOnFailure = true, done = done) { api ->
            for (step in ScheduleRules.patternSaveOrder(pattern.cycleLength, length)) {
                if (step == ScheduleRules.SaveStep.PATTERN) api.updatePattern(pattern.id, body) else api.replacePatternDays(pattern.id, days)
            }
        }
    }

    fun deletePattern(pattern: Pattern) = write(message = "schedule.deleted") { api ->
        deleteUnlessGone(ScheduleErrors.PATTERN_GONE) { api.deletePattern(pattern.id) }
    }

    // Overrides and extras (SCHED-R26 to R29)

    /** "Replace": one day on its own endpoint, a range through `/overrides/fill` (asked first by the screen). */
    fun createOverride(form: OccurrenceForm, done: () -> Unit) {
        val userId = form.userId ?: return
        if (ScheduleChecks.occurrence(form, needsType = false).isNotEmpty()) return
        write(done = done) { api ->
            if (form.singleDay) {
                api.putOverride(form.from.toString(), ScheduleBodies.override(userId, form.shiftTypeId, form.note, form.fieldValues))
            } else {
                api.fillOverrides(ScheduleBodies.override(userId, form.shiftTypeId, form.note, form.fieldValues, form.from.toString(), form.to.toString()), YuvomiClient.newIdempotencyKey())
            }
        }
    }

    /** `override-edit`: fill the new span, then remove what lay outside it (`rangeDifference`). */
    fun saveOverrideGroup(group: ScheduleRules.OverrideGroup, form: OccurrenceForm, done: () -> Unit) {
        if (ScheduleChecks.occurrence(form, needsType = false).isNotEmpty()) return
        write(done = done) { api ->
            api.fillOverrides(
                ScheduleBodies.override(group.userId, form.shiftTypeId, form.note, form.fieldValues, form.from.toString(), form.to.toString()),
                YuvomiClient.newIdempotencyKey(),
            )
            for (span in ScheduleRules.rangeDifference(group.from, group.to, form.from.toString(), form.to.toString())) {
                api.deleteOverrides(group.userId, span.from, span.to)
            }
        }
    }

    fun deleteOverrideGroup(group: ScheduleRules.OverrideGroup) = write(message = "schedule.deleted") { api ->
        api.deleteOverrides(group.userId, group.from, group.to)
    }

    /** "Add": one extra, or one per day through `/extras/fill`. */
    fun createExtra(form: OccurrenceForm, done: () -> Unit) {
        val userId = form.userId ?: return
        val typeId = form.shiftTypeId ?: return
        if (ScheduleChecks.occurrence(form, needsType = true).isNotEmpty()) return
        write(done = done) { api ->
            if (form.singleDay) {
                api.createExtra(ScheduleBodies.extra(userId, typeId, form.note, form.reminder, form.fieldValues, dateKey = form.from.toString()), YuvomiClient.newIdempotencyKey())
            } else {
                api.fillExtras(ScheduleBodies.extra(userId, typeId, form.note, form.reminder, form.fieldValues, from = form.from.toString(), to = form.to.toString()), YuvomiClient.newIdempotencyKey())
            }
        }
    }

    /** `extra-edit-range`: new rows first, then the old ids, so a failure costs a duplicate, never data. */
    fun saveExtraGroup(group: ScheduleRules.ExtraGroup, form: OccurrenceForm, done: () -> Unit) {
        val typeId = form.shiftTypeId ?: return
        if (ScheduleChecks.occurrence(form, needsType = true).isNotEmpty()) return
        write(reloadOnFailure = true, done = done) { api ->
            if (form.singleDay) {
                api.createExtra(ScheduleBodies.extra(group.userId, typeId, form.note, form.reminder, form.fieldValues, dateKey = form.from.toString()), YuvomiClient.newIdempotencyKey())
            } else {
                api.fillExtras(ScheduleBodies.extra(group.userId, typeId, form.note, form.reminder, form.fieldValues, from = form.from.toString(), to = form.to.toString()), YuvomiClient.newIdempotencyKey())
            }
            group.ids.forEach { api.deleteExtra(it) }
        }
    }

    /** `delete-extra-range`: there is no range delete for extras; a failure halfway reloads. */
    fun deleteExtraGroup(group: ScheduleRules.ExtraGroup) = write(message = "schedule.deleted", reloadOnFailure = true) { api ->
        group.ids.forEach { id -> deleteUnlessGone(ScheduleErrors.EXTRA_GONE) { api.deleteExtra(id) } }
    }

    // ── Feed (SCHED-R35, R36) ──

    fun loadFeed() {
        val api = api ?: return
        _state.update { it.copy(feed = it.feed.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                val feed = api.feed()
                _state.update { it.copy(feed = FeedState(loading = false, feed = feed)) }
            } catch (e: ApiProblem) {
                _state.update { it.copy(feed = FeedState(loading = false, failed = true)) }
            }
        }
    }

    /** On creates the address (`regenerate`), off removes it; "new link" regenerates. */
    fun setFeed(on: Boolean, regenerate: Boolean = false) {
        val api = api ?: return
        viewModelScope.launch {
            if (refuseOffline()) return@launch
            _state.update { it.copy(feed = it.feed.copy(busy = true)) }
            try {
                val feed = if (on) api.regenerateFeed(YuvomiClient.newIdempotencyKey()) else api.disableFeed().let { null }
                _state.update { it.copy(feed = FeedState(loading = false, feed = feed)) }
                if (on && !regenerate) host.notify(host.strings.t("settings.scheduleFeedTitle"))
            } catch (e: ApiProblem) {
                _state.update { it.copy(feed = it.feed.copy(busy = false)) }
                host.notify(host.strings.t(ScheduleErrors.key(e)))
            }
        }
    }

    companion object {
        const val CACHE_KEY = "schedule-data"
        const val SELECTION_KEY = "schedule-overview-people"
        const val MODE_KEY = "schedule-overview-mode"
    }
}
