package io.github.marcomow.yuvomi.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime

/**
 * docs/spec/calendar.md, the event form (CAL-R15 to R19): `openEventModal` and `saveEvent`
 * of public/pages/calendar.js, including the question which occurrences a change applies to.
 */
class EventEditorViewModel(
    private val store: CalendarStore,
    private val target: CalendarStore.EditTarget,
    private val me: Int?,
    private val now: () -> LocalDateTime,
) : ViewModel() {

    enum class Outcome { CREATED, SAVED, GONE, CLOSED }

    /** A question the save needs answered first. */
    sealed interface Question {
        /** `confirmLocalWholeSeriesEdit`. */
        data object WholeSeriesOnly : Question

        /** `recurringScopeChoice({ action: 'save' })`; "only this" drops out when the calendar changed. */
        data class Scope(val allowThis: Boolean) : Question
    }

    data class UiState(
        val event: CalendarEvent? = null,
        val form: EventForm = EventForm(),
        val original: EventForm = EventForm(),
        val loadedReminders: List<EventReminders.Row> = emptyList(),
        val syncTargets: SyncTargets = SyncTargets(),
        val errors: Map<EventForm.Field, String> = emptyMap(),
        val saving: Boolean = false,
        val question: Question? = null,
        val messageKey: String? = null,
        val outcome: Outcome? = null,
    ) {
        val isEdit: Boolean get() = event != null
        val dirty: Boolean get() = form != original
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One key per form: a retried create is the same create (ADR 0007). */
    private val idempotencyKey = YuvomiClient.newIdempotencyKey()

    init {
        val s = store.state.value
        val dates = store.dates
        val duration = s.prefs.defaultDuration ?: 60
        val ev = target.event
        val form = if (ev != null) {
            EventForm.edit(ev, target.reminders, dates, duration)
        } else {
            val date = target.date ?: store.state.value.cursor
            val time = target.time ?: dates.defaultNewEventTime(date, now())
            val default = s.calendars.firstOrNull { it.isDefault } ?: s.calendars.firstOrNull()
            EventForm.create(date, time, duration, me, s.prefs, default?.id)
        }
        _state.update { it.copy(event = ev, form = form, original = form, loadedReminders = form.reminders) }
        viewModelScope.launch {
            val targets = runCatching { store.api.syncTargets() }.getOrNull() ?: return@launch
            _state.update { st ->
                // `applyDefaultSyncTarget`: a new event takes the member's default when it is offered.
                val pref = s.prefs.defaultTarget.orEmpty()
                val offered = targetValues(targets)
                if (ev == null && pref.isNotEmpty() && pref in offered && st.form == st.original) {
                    val f = st.form.copy(syncTarget = pref)
                    st.copy(syncTargets = targets, form = f, original = f)
                } else {
                    st.copy(syncTargets = targets)
                }
            }
        }
    }

    fun edit(change: (EventForm) -> EventForm) = _state.update { it.copy(form = change(it.form), errors = emptyMap()) }

    fun consumeMessage() = _state.update { it.copy(messageKey = null) }

    fun dismissQuestion() = _state.update { it.copy(question = null) }

    /** Whether "only this" applies: a series this member may split (`isLocalRecurringSeries && canEditCalendarOccurrence`). */
    private fun asksScope(ev: CalendarEvent?): Boolean =
        ev != null && RecurrenceScope.isLocalSeries(ev) && RecurrenceScope.canEditOccurrence(ev)

    /** The first step of Save: checks, then the confirmation or scope question, then the write. */
    fun save(confirmedWholeSeries: Boolean = false) {
        val s = state.value
        if (s.saving) return
        val errors = s.form.errors(s.event, store.dates)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        val ev = s.event
        if (ev != null && RecurrenceScope.requiresWholeSeriesConfirmation(ev) && !confirmedWholeSeries) {
            _state.update { it.copy(question = Question.WholeSeriesOnly) }
            return
        }
        if (asksScope(ev)) {
            if (!s.dirty) {
                _state.update { it.copy(outcome = Outcome.CLOSED) }
                return
            }
            val calendarChanged = calendarsOffered() && s.form.localCalendarId != ev!!.localCalendarId && !s.form.leavesLocalCalendars
            _state.update { it.copy(question = Question.Scope(allowThis = !calendarChanged)) }
            return
        }
        write(RecurrenceScope.Scope.SERIES)
    }

    /** The answer to [Question.Scope]. */
    fun saveWithScope(scope: RecurrenceScope.Scope) {
        _state.update { it.copy(question = null) }
        write(scope)
    }

    private fun calendarsOffered(): Boolean {
        val calendars = store.state.value.calendars
        val ev = state.value.event
        return calendars.size > 1 && (ev == null || ev.localCalendarId != null)
    }

    private fun write(chosen: RecurrenceScope.Scope) {
        _state.update { it.copy(saving = true, question = null) }
        viewModelScope.launch {
            val s = state.value
            val dates = store.dates
            val form = s.form
            val ev = s.event
            try {
                val body = form.body(ev, calendarsOffered()).toMutableMap()
                val rows = form.activeReminders
                var savedId: Int? = ev?.id
                var remindersAtomic = false
                var reminderBase = form.start
                var untouched: EventReminders.Untouched? = null
                if (ev == null) {
                    savedId = store.api.create(JsonObject(body), idempotencyKey).id
                } else {
                    var scope = chosen
                    if (scope == RecurrenceScope.Scope.THIS) body.remove("local_calendar_id")
                    val canOverride = RecurrenceScope.canOverride(ev)
                    if (!canOverride && scope == RecurrenceScope.Scope.FOLLOWING && RecurrenceScope.followingMeansWholeSeries(ev)) scope = RecurrenceScope.Scope.SERIES
                    val local = RecurrenceScope.isLocalSeries(ev)
                    val seriesId = ev.seriesId ?: ev.id
                    val recurrenceId = RecurrenceScope.recurrenceId(ev)
                    if (local && RecurrenceScope.canEditOccurrence(ev) && scope != RecurrenceScope.Scope.SERIES && recurrenceId != null) {
                        // These endpoints take lead times only (#1260).
                        val offsets = if (form.remindersOn) EventReminders.occurrenceOffsets(rows) else emptyList()
                        if (offsets == null && canOverride && !EventReminders.unchanged(rows, s.loadedReminders)) {
                            _state.update { it.copy(saving = false, errors = mapOf(EventForm.Field.REMINDERS to "reminders.afterStartNeedsLeadTime")) }
                            return@launch
                        }
                        savedId = if (RecurrenceScope.usesLegacyPath(ev)) {
                            legacyOccurrence(ev, seriesId, recurrenceId, scope, body)
                        } else {
                            val payload = body.toMutableMap()
                            if (scope == RecurrenceScope.Scope.THIS) {
                                listOf("target_google_calendar_id", "target_caldav_account_id", "target_caldav_calendar_url",
                                    "target_outlook_account_id", "target_outlook_calendar_id", "recurrence_rule").forEach { payload.remove(it) }
                            }
                            if (offsets != null) payload["reminder_offsets"] = JsonArray(offsets.map { JsonPrimitive(it) })
                            remindersAtomic = true
                            store.api.updateOccurrence(seriesId, recurrenceId, scope == RecurrenceScope.Scope.FOLLOWING, JsonObject(payload)).id
                        }
                    } else if (local) {
                        // A whole-series edit moves the series start by the shift of the opened occurrence (#532).
                        val master = store.api.event(seriesId)
                        val newStart = RecurrenceScope.shiftSeriesStart(master.startDatetime, ev.startDatetime, form.start, form.allDay, dates)
                        val newEnd = RecurrenceScope.shiftEndForStart(newStart, form.start, form.end, form.allDay, dates)
                        body["start_datetime"] = JsonPrimitive(newStart)
                        body["end_datetime"] = newEnd?.let { JsonPrimitive(it) } ?: JsonNull
                        reminderBase = newStart
                        savedId = store.api.update(seriesId, JsonObject(body)).id
                    } else {
                        // A time nobody touched does not go (#1958): the route keeps the stored one.
                        if (form.allDay == s.original.allDay && form.start == s.original.start && form.end == s.original.end) {
                            body.remove("start_datetime")
                            body.remove("end_datetime")
                            // CAL-R29 (2.84.0): the series start stays, so an unchanged reminder must not
                            // slide onto the opened occurrence.
                            ev.reminderAnchorStart?.let { anchor ->
                                untouched = EventReminders.Untouched(anchor, target.reminders.mapNotNull { r -> EventReminders.naiveUtc(r.remindAt) })
                            }
                        }
                        savedId = store.api.update(ev.id, JsonObject(body)).id
                    }
                }
                if (savedId != null && !remindersAtomic) {
                    val times = EventReminders.times(rows, reminderBase, dates, untouched)
                    if (times.isNotEmpty()) store.api.setReminders(savedId, times)
                    else runCatching { store.api.clearReminders(savedId) }
                }
                store.reloadEvents()
                store.close()
                _state.update { it.copy(saving = false, outcome = if (ev == null) Outcome.CREATED else Outcome.SAVED) }
            } catch (e: ApiProblem) {
                val key = if (CalendarErrors.orphanConflict(e)) "calendar.overrideOrphanConfirmDetail" else CalendarErrors.key(e, "calendar.saveError")
                when {
                    CalendarErrors.eventGone(e) -> {
                        store.close()
                        store.reloadEvents()
                        _state.update { it.copy(saving = false, messageKey = key, outcome = Outcome.GONE) }
                    }
                    CalendarErrors.isGone(e, CalendarErrors.SELECTED_CALENDAR_GONE) -> {
                        store.loadCalendars()
                        val calendars = store.state.value.calendars
                        _state.update {
                            val fallback = (calendars.firstOrNull { c -> c.isDefault } ?: calendars.firstOrNull())?.id
                            it.copy(saving = false, messageKey = key, form = it.form.copy(localCalendarId = fallback))
                        }
                    }
                    else -> _state.update { it.copy(saving = false, messageKey = key) }
                }
            }
        }
    }

    /** `requestCalendarOccurrenceMutation()` for a locally owned series without linked overrides. */
    private suspend fun legacyOccurrence(ev: CalendarEvent, seriesId: Int, recurrenceId: String, scope: RecurrenceScope.Scope, body: Map<String, kotlinx.serialization.json.JsonElement>): Int {
        if (scope == RecurrenceScope.Scope.THIS) {
            val created = store.api.create(JsonObject(body + ("recurrence_rule" to JsonNull)), idempotencyKey)
            store.api.exception(seriesId, RecurrenceScope.exceptionBody(recurrenceId))
            return created.id
        }
        val rule = RecurrenceScope.truncateRuleBefore(ev.recurrenceRule, RecurrenceScope.seriesDay(ev, recurrenceId))
        store.api.update(seriesId, JsonObject(mapOf("recurrence_rule" to (rule?.let { JsonPrimitive(it) } ?: JsonNull))))
        return store.api.create(JsonObject(body), idempotencyKey).id
    }

    companion object {
        /** The values the sync-target picker offers (`googleTargetValue` and friends). */
        fun targetValues(t: SyncTargets): List<String> =
            t.google.map { "google:${it.id}" } + t.caldav.map { "caldav:${it.accountId}|${it.calendarUrl}" } +
                t.outlook.map { "outlook:${it.accountId}|${it.calendarId}" }
    }
}
