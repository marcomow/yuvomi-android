package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.AppContainer
import io.github.marcomow.yuvomi.core.api.ApiJson
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.data.ResponseCache
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.session.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import java.util.WeakHashMap

/**
 * The calendar's data and view state, shared by the calendar, the editor and the calendar
 * manager like the web page's module-level `state` (docs/spec/calendar.md). One store per
 * signed-in connection. Reads are cache-then-network (ADR 0007); writes go to the server and
 * reload the window from its answer.
 */
class CalendarStore(
    private val cache: ResponseCache,
    val api: CalendarApi,
    private val scope: CoroutineScope,
    val zone: ZoneId,
    private val todayFn: () -> LocalDate,
) {
    /** What the event form starts from (`openEventModal`). */
    data class EditTarget(
        val event: CalendarEvent? = null,
        val date: LocalDate? = null,
        val time: java.time.LocalTime? = null,
        val reminders: List<StoredReminder> = emptyList(),
    )

    data class Search(
        val active: Boolean = false,
        val query: String = "",
        val loading: Boolean = false,
        val results: List<CalendarEvent> = emptyList(),
        val total: Int = 0,
        val failed: Boolean = false,
    )

    data class State(
        val view: CalendarView = CalendarView.MONTH,
        val cursor: LocalDate = LocalDate.now(),
        val wide: Boolean = false,
        val from: LocalDate? = null,
        val to: LocalDate? = null,
        val events: List<CalendarEvent> = emptyList(),
        val tasks: List<CalendarTask> = emptyList(),
        val holidays: List<Holiday> = emptyList(),
        val schedule: List<ScheduleEntry> = emptyList(),
        val waste: List<WasteOccurrence> = emptyList(),
        val calendars: List<LocalCalendar> = emptyList(),
        val calendarsFailed: Boolean = false,
        val members: List<CalendarMember> = emptyList(),
        val prefs: CalendarPrefs = CalendarPrefs(),
        val filter: CalendarFilter = CalendarFilter(),
        val loading: Boolean = true,
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        val cachedAt: Long? = null,
        val errorKey: String? = null,
        /** Deletes on screen whose undo window is still open (`applyPendingCalendarDeleteOverlay`). */
        val pendingDeletes: List<PendingDelete> = emptyList(),
        /** The event whose read view is open. */
        val selected: CalendarEvent? = null,
        val selectedReminders: List<StoredReminder> = emptyList(),
        val search: Search = Search(),
    ) {
        val offline: Boolean get() = cachedAt != null

        /** Loaded events without those waiting for their delete. */
        val shown: List<CalendarEvent> get() = if (pendingDeletes.isEmpty()) events else events.filter { ev -> pendingDeletes.none { it.hides(ev) } }
    }

    /** One delete in its undo window: what it hides depends on the scope. */
    data class PendingDelete(val seriesId: Int, val scope: RecurrenceScope.Scope, val start: String) {
        fun hides(ev: CalendarEvent): Boolean {
            val series = ev.seriesId ?: ev.id
            if (series != seriesId && ev.id != seriesId) return false
            return when (scope) {
                RecurrenceScope.Scope.SERIES -> true
                RecurrenceScope.Scope.THIS -> ev.startDatetime == start
                RecurrenceScope.Scope.FOLLOWING -> ev.startDatetime >= start
            }
        }
    }

    private val _state = MutableStateFlow(State(cursor = todayFn()))
    val state: StateFlow<State> = _state.asStateFlow()

    val dates: CalendarDates get() = CalendarDates(zone, CalendarDates.weekStartOf(state.value.prefs.weekStart))

    var me: Int? = null
    var scheduleOn: Boolean = false
    var wasteOn: Boolean = false
    var tasksOn: Boolean = true
    var lastLoaded = 0L
        private set

    /** The target of the editor screen. */
    var editing: EditTarget? = null

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        readCache()
    }

    private fun readCache() {
        runCatching {
            cache.read(CACHE_FILTER)?.let { e ->
                val filter = ApiJson.decodeFromString(CalendarFilter.serializer(), e.json)
                _state.update { it.copy(filter = filter, view = CalendarView.of(filter.view)) }
            }
            cache.read(CACHE_PREFS)?.let { e -> _state.update { it.copy(prefs = ApiJson.decodeFromString(CalendarPrefs.serializer(), e.json)) } }
            cache.read(CACHE_CALENDARS)?.let { e -> _state.update { it.copy(calendars = CalendarApi.decodeCalendars(ApiJson.parseToJsonElement(e.json))) } }
            cache.read(CACHE_MEMBERS)?.let { e -> _state.update { it.copy(members = CalendarApi.decodeMembers(ApiJson.parseToJsonElement(e.json))) } }
        }
    }

    // ── View and period (CAL-R1 to R6) ──

    fun setWide(wide: Boolean) {
        if (state.value.wide == wide) return
        _state.update { it.copy(wide = wide) }
        if (state.value.view == CalendarView.WEEK) refresh()
    }

    fun setView(view: CalendarView) {
        _state.update { it.copy(view = view) }
        saveFilter(state.value.filter.copy(view = view.key))
        refresh()
    }

    fun step(dir: Int) {
        val s = state.value
        _state.update { it.copy(cursor = dates.step(s.view, s.cursor, dir, s.wide, todayFn())) }
        refresh()
    }

    fun goToday() {
        _state.update { it.copy(cursor = todayFn()) }
        refresh()
    }

    /** Selects a day; a day outside the loaded window loads it. */
    fun select(day: LocalDate) {
        val s = state.value
        _state.update { it.copy(cursor = day) }
        val (from, to) = dates.range(s.view, day, s.wide)
        if (s.from != from || s.to != to) refresh()
    }

    /** The month's tap on a day in the neighbouring month and the day view drill-in. */
    fun openDay(day: LocalDate) {
        _state.update { it.copy(view = CalendarView.DAY, cursor = day) }
        refresh()
    }

    // ── Loading (CAL-R7, R8) ──

    fun refresh(pulled: Boolean = false) {
        _state.update { it.copy(refreshing = pulled, errorKey = null) }
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    /** `loadRange()`: events, tasks, holidays, schedule and waste of the window in parallel. */
    suspend fun load(): Boolean {
        val s = state.value
        val (from, to) = dates.range(s.view, s.cursor, s.wide)
        // `fetchWindow()`: a day either side, for events that cross midnight.
        val winFrom = from.minusDays(1).toString()
        val winTo = to.plusDays(1).toString()
        val key = "$CACHE_EVENTS-$winFrom-$winTo"
        cache.read(key)?.let { e ->
            if (state.value.from != from || state.value.to != to) {
                runCatching { applyEvents(CalendarApi.decodeEvents(ApiJson.parseToJsonElement(e.json)), from, to) }
            }
        }
        return try {
            coroutineScope {
                val meta = if (!state.value.loaded) async { loadMeta() } else null
                val tasks = async { if (tasksOn) runCatching { CalendarRules.calendarTasks(CalendarApi.decodeTasks(api.tasks())) }.getOrDefault(emptyList()) else emptyList() }
                val holidays = async { runCatching { CalendarApi.decodeHolidays(api.holidays(from.toString(), to.toString())) }.getOrDefault(emptyList()) }
                val schedule = async { if (scheduleOn) runCatching { CalendarApi.decodeSchedule(api.schedule(from.toString(), to.toString())) }.getOrDefault(emptyList()) else emptyList() }
                val waste = async { if (wasteOn) runCatching { CalendarApi.decodeWaste(api.waste(winFrom, winTo)) }.getOrDefault(emptyList()) else emptyList() }
                val answer = api.events(winFrom, winTo)
                val events = CalendarApi.decodeEvents(answer)
                cache.write(key, answer.toString())
                meta?.await()
                lastLoaded = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        tasks = tasks.await(), holidays = holidays.await(), schedule = schedule.await(), waste = waste.await(),
                        loading = false, loaded = true, refreshing = false, cachedAt = null, errorKey = null,
                    )
                }
                applyEvents(events, from, to)
            }
            true
        } catch (e: ApiProblem) {
            _state.update {
                val hasData = it.events.isNotEmpty() || cache.read(key) != null
                it.copy(
                    loading = false,
                    refreshing = false,
                    cachedAt = if (hasData) cache.read(key)?.savedAt ?: System.currentTimeMillis() else null,
                    errorKey = if (hasData) null else CalendarErrors.loadKey(e),
                )
            }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        }
    }

    private fun applyEvents(events: List<CalendarEvent>, from: LocalDate, to: LocalDate) {
        _state.update { it.copy(events = events.sortedBy { e -> e.startDatetime }, from = from, to = to, loading = false) }
        // The open read view follows the fresh copy of its occurrence.
        state.value.selected?.let { sel ->
            val fresh = events.firstOrNull { it.occurrenceKey == sel.occurrenceKey }
            if (fresh != null) _state.update { it.copy(selected = fresh) }
        }
    }

    /** Calendars, members and preferences: what the views, filters and form read. */
    private suspend fun loadMeta() = coroutineScope {
        val prefs = async { runCatching { api.preferences() }.getOrNull() }
        val members = async { runCatching { api.members() }.getOrNull() }
        loadCalendars()
        prefs.await()?.let { p ->
            cache.write(CACHE_PREFS, ApiJson.encodeToString(CalendarPrefs.serializer(), p))
            _state.update { it.copy(prefs = p) }
        }
        members.await()?.let { m ->
            cache.write(CACHE_MEMBERS, m.toString())
            _state.update { it.copy(members = CalendarApi.decodeMembers(m)) }
        }
    }

    /** `loadLocalCalendars()`. */
    suspend fun loadCalendars(): Boolean = try {
        val answer = api.calendars()
        cache.write(CACHE_CALENDARS, answer.toString())
        _state.update { it.copy(calendars = CalendarApi.decodeCalendars(answer), calendarsFailed = false) }
        true
    } catch (e: ApiProblem) {
        _state.update { it.copy(calendarsFailed = it.calendars.isEmpty()) }
        false
    }

    /** `reloadCalendarEventsOnly()` after a write. */
    suspend fun reloadEvents() {
        load()
    }

    // ── What a day shows (CAL-R9 to R12) ──

    fun eventsOn(day: LocalDate, s: State = state.value): List<CalendarEvent> {
        val d = dates
        return s.shown.filter { d.on(it, day) && CalendarRules.shows(it, s.filter, me) }
    }

    fun tasksOn(day: LocalDate, s: State = state.value): List<CalendarTask> =
        s.tasks.filter { it.dueDate == day.toString() && CalendarRules.passesPeople(it.assignedUsers.map { u -> u.id }, s.filter, me) }

    fun holidaysOn(day: LocalDate, s: State = state.value): List<Holiday> = CalendarRules.holidaysOn(s.holidays, day, s.filter)

    fun scheduleOn(day: LocalDate, s: State = state.value): List<ScheduleEntry> =
        if (!s.filter.layerSchedule) emptyList()
        else s.schedule.filter { it.dateKey == day.toString() && it.shiftType != null && CalendarRules.passesPeople(listOfNotNull(it.userId), s.filter, me) }

    fun wasteOn(day: LocalDate, s: State = state.value): List<WasteOccurrence> =
        if (!s.filter.layerWaste) emptyList() else s.waste.filter { it.dateKey == day.toString() }

    fun isEmptyDay(day: LocalDate, s: State = state.value): Boolean =
        eventsOn(day, s).isEmpty() && tasksOn(day, s).isEmpty() && holidaysOn(day, s).isEmpty() && scheduleOn(day, s).isEmpty() && wasteOn(day, s).isEmpty()

    // ── Filters (CAL-R12) ──

    fun saveFilter(filter: CalendarFilter) {
        _state.update { it.copy(filter = filter) }
        cache.write(CACHE_FILTER, ApiJson.encodeToString(CalendarFilter.serializer(), filter))
    }

    fun sources(s: State = state.value): List<CalendarSource> = CalendarRules.sources(s.calendars, s.events, s.filter.hiddenSources)

    // ── Read view (CAL-R13) ──

    fun open(ev: CalendarEvent) {
        _state.update { it.copy(selected = ev, selectedReminders = emptyList()) }
        scope.launch {
            val reminders = runCatching { api.reminders(ev.reminderOwnerId ?: ev.id) }.getOrDefault(emptyList())
            _state.update { if (it.selected?.occurrenceKey == ev.occurrenceKey) it.copy(selectedReminders = reminders) else it }
        }
    }

    fun close() = _state.update { it.copy(selected = null, selectedReminders = emptyList()) }

    /**
     * `?open=<id>` (`openDeepLink`): fetch the event, move to its day, and open the occurrence
     * of that day once the window is loaded. Returns an error key, or null.
     */
    suspend fun openById(id: Int, date: LocalDate? = null): String? = try {
        val ev = api.event(id)
        val day = date ?: dates.localDate(ev.startDatetime) ?: todayFn()
        _state.update { it.copy(cursor = day) }
        load()
        val occurrence = state.value.events.firstOrNull { it.id == ev.id && dates.localDate(it.startDatetime) == day } ?: ev
        open(occurrence)
        null
    } catch (e: ApiProblem) {
        CalendarErrors.key(e, "calendar.loadError")
    }

    // ── Search (CAL-R14) ──

    fun setSearch(active: Boolean) {
        searchJob?.cancel()
        _state.update { it.copy(search = Search(active = active)) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        _state.update { it.copy(search = it.search.copy(query = query, loading = q.length >= 2, failed = false, results = if (q.length < 2) emptyList() else it.search.results)) }
        if (q.length < 2) return
        searchJob = scope.launch {
            kotlinx.coroutines.delay(220)
            try {
                val (results, total) = api.search(q)
                _state.update { if (it.search.query.trim() == q) it.copy(search = it.search.copy(loading = false, results = results, total = total)) else it }
            } catch (e: ApiProblem) {
                _state.update { if (it.search.query.trim() == q) it.copy(search = it.search.copy(loading = false, failed = true)) else it }
            }
        }
    }

    // ── Delete with undo (CAL-R20 to R22) ──

    /**
     * `scheduleCalendarDeleteWithUndo()`: the occurrences leave the view at once, a snackbar
     * offers undo, and only when it closes without undo does the request go out. "Already
     * deleted" counts as done; any other failure brings them back and says why.
     */
    fun scheduleDelete(ev: CalendarEvent, scopeChoice: RecurrenceScope.Scope, strings: Strings, notify: suspend (String, String?) -> Boolean) {
        val pending = PendingDelete(ev.seriesId ?: ev.id, scopeChoice, ev.startDatetime)
        _state.update { it.copy(pendingDeletes = it.pendingDeletes + pending, selected = null) }
        scope.launch {
            val undone = notify(strings.t("calendar.deletedToast"), strings.t("common.undo"))
            if (undone) {
                _state.update { it.copy(pendingDeletes = it.pendingDeletes - pending) }
                return@launch
            }
            try {
                requestDelete(ev, scopeChoice)
            } catch (e: ApiProblem) {
                if (!CalendarErrors.eventGone(e)) {
                    _state.update { it.copy(pendingDeletes = it.pendingDeletes - pending) }
                    notify(strings.t(CalendarErrors.key(e, "calendar.deleteError")), null)
                    return@launch
                }
            }
            load()
            _state.update { it.copy(pendingDeletes = it.pendingDeletes - pending) }
        }
    }

    /** `deleteEvent`, `deleteThisAndFollowing`, `deleteSingleOccurrence` (`requestCalendarOccurrenceDelete`). */
    private suspend fun requestDelete(ev: CalendarEvent, scopeChoice: RecurrenceScope.Scope) {
        val seriesId = ev.seriesId
        if (seriesId == null) {
            api.delete(ev.id)
            return
        }
        val recurrenceId = RecurrenceScope.recurrenceId(ev)
        if (scopeChoice == RecurrenceScope.Scope.SERIES || recurrenceId == null) {
            api.delete(seriesId)
            return
        }
        if (RecurrenceScope.usesLegacyPath(ev)) {
            when {
                scopeChoice == RecurrenceScope.Scope.THIS -> api.exception(seriesId, RecurrenceScope.exceptionBody(recurrenceId))
                !RecurrenceScope.followingMeansWholeSeries(ev) -> api.update(seriesId, buildJsonObject {
                    val rule = RecurrenceScope.truncateRuleBefore(ev.recurrenceRule, RecurrenceScope.seriesDay(ev, recurrenceId))
                    if (rule != null) put("recurrence_rule", rule) else put("recurrence_rule", kotlinx.serialization.json.JsonNull)
                })
                else -> api.delete(seriesId)
            }
            return
        }
        api.deleteOccurrence(seriesId, recurrenceId, following = scopeChoice == RecurrenceScope.Scope.FOLLOWING)
    }

    /** `POST /calendar/:id/reset` for a locally changed subscribed event. Returns an error key, or null. */
    suspend fun reset(ev: CalendarEvent): String? = try {
        api.reset(ev.id)
        close()
        load()
        null
    } catch (e: ApiProblem) {
        if (CalendarErrors.eventGone(e)) {
            close()
            load()
        }
        CalendarErrors.key(e, "calendar.saveError")
    }

    // ── Local calendars (CAL-R23 to R26) ──

    /** A manager write, then the fresh list and window. Returns an error key, or null. */
    suspend fun calendarWrite(block: suspend () -> Unit): String? = try {
        block()
        loadCalendars()
        load()
        null
    } catch (e: ApiProblem) {
        if (CalendarErrors.calendarsStale(e)) loadCalendars()
        CalendarErrors.key(e, "calendar.saveError")
    }

    suspend fun createCalendar(name: String, color: String, key: String = YuvomiClient.newIdempotencyKey()) =
        calendarWrite { api.createCalendar(name.trim(), color, key) }

    suspend fun editCalendar(calendar: LocalCalendar, name: String, color: String): String? {
        val changes = buildMap<String, JsonElement> {
            if (!color.equals(calendar.color, ignoreCase = true)) put("color", JsonPrimitive(color))
            if (name.trim() != calendar.name) put("name", JsonPrimitive(name.trim()))
        }
        if (changes.isEmpty()) return null
        return calendarWrite { api.updateCalendar(calendar.id, JsonObject(changes)) }
    }

    suspend fun moveCalendar(calendar: LocalCalendar, delta: Int): String? {
        val order = state.value.calendars.map { it.id }.toMutableList()
        val from = order.indexOf(calendar.id)
        val to = from + delta
        if (from < 0 || to !in order.indices) return null
        order[from] = order[to].also { order[to] = order[from] }
        return calendarWrite { api.reorderCalendars(order) }
    }

    suspend fun deleteCalendar(calendar: LocalCalendar): String? = calendarWrite {
        try {
            api.deleteCalendar(calendar.id)
        } catch (e: ApiProblem) {
            // Already deleted by someone else: what the member wanted (#1909).
            if (!CalendarErrors.isGone(e, CalendarErrors.CALENDAR_GONE)) throw e
        }
    }

    suspend fun setFeed(calendar: LocalCalendar, on: Boolean): String? = calendarWrite {
        if (on) api.enableFeed(calendar.id) else api.disableFeed(calendar.id)
    }

    companion object {
        const val CACHE_EVENTS = "calendar-events"
        const val CACHE_FILTER = "calendar-filter"
        const val CACHE_PREFS = "calendar-prefs"
        const val CACHE_CALENDARS = "calendar-calendars"
        const val CACHE_MEMBERS = "calendar-members"

        private val stores = WeakHashMap<Connection, CalendarStore>()

        /** The store of the signed-in connection, or null when signed out. */
        fun of(container: AppContainer, zone: ZoneId, today: () -> LocalDate): CalendarStore? {
            val connection = container.session.connection ?: return null
            return synchronized(stores) {
                stores.getOrPut(connection) { CalendarStore(container.cache, CalendarApi(connection.client), container.scope, zone, today) }
            }
        }
    }
}
