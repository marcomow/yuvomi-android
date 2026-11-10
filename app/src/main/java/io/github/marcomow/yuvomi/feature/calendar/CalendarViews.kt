package io.github.marcomow.yuvomi.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marcomow.yuvomi.core.design.AvatarStack
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import java.time.LocalDate
import java.time.LocalTime

/** What a row or chip tap does; the screen wires these. */
class CalendarActions(
    val openEvent: (CalendarEvent) -> Unit,
    val openTask: (CalendarTask) -> Unit,
    val openWaste: (WasteOccurrence) -> Unit,
    val openSchedule: (ScheduleEntry) -> Unit,
    val openDay: (LocalDate) -> Unit,
    val select: (LocalDate) -> Unit,
    /** Null when the member may not add (`readOnly()`). */
    val create: ((LocalDate, LocalTime?) -> Unit)?,
)

fun color(hex: String?, fallback: Color): Color = Tokens.parseColor(hex) ?: fallback

// ── Month (CAL-R9): the grid, and the chosen day's list below it ──

@Composable
fun MonthView(store: CalendarStore, s: CalendarStore.State, text: CalendarText, today: LocalDate, actions: CalendarActions) {
    val dates = store.dates
    val grid = dates.monthGrid(s.cursor)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "weekdays") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                grid.take(7).forEach { d ->
                    Text(text.weekdayShort(d), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                }
            }
        }
        grid.chunked(7).forEachIndexed { w, week ->
            item(key = "week-$w") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    week.forEach { day -> MonthCell(store, s, text, day, today, actions, Modifier.weight(1f)) }
                }
            }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        item(key = "day-head") { DayHeader(s.cursor, text, today, onClick = { actions.openDay(s.cursor) }) }
        val empty = store.isEmptyDay(s.cursor, s)
        if (empty) {
            item(key = "day-empty") {
                Text(t("calendar.agendaDayEmpty"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Yuvomi.colors.textSecondary)
            }
        } else {
            item(key = "day-rows") { DayRows(store, s, s.cursor, text, actions) }
        }
    }
}

@Composable
private fun MonthCell(store: CalendarStore, s: CalendarStore.State, text: CalendarText, day: LocalDate, today: LocalDate, actions: CalendarActions, modifier: Modifier) {
    val inMonth = day.month == s.cursor.month
    val selected = day == s.cursor
    val events = store.eventsOn(day, s)
    val tasks = store.tasksOn(day, s)
    val holidays = store.holidaysOn(day, s)
    val shifts = store.scheduleOn(day, s)
    val waste = store.wasteOn(day, s)
    val total = events.size + tasks.size + holidays.size + shifts.size + waste.size
    val label = buildString {
        append(text.dayHeading(day))
        if (total > 0) append(", ").append(text.s.t("calendar.monthDayEntries", "count" to total))
    }
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .clickable { actions.select(day) }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).then(if (day == today) Modifier.background(accent) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (day == today) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    day == today -> MaterialTheme.colorScheme.onPrimary
                    !inMonth -> Yuvomi.colors.textTertiary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        // The phone's month shows dots, not titles (`monthTitles: false`).
        val dots = (holidays.map { color(it.color, Yuvomi.colors.danger) } +
            shifts.map { color(it.shiftType?.color, Yuvomi.colors.textSecondary) } +
            waste.map { color(it.typeColor, Yuvomi.colors.textSecondary) } +
            events.map { color(CalendarRules.color(it), Yuvomi.colors.textSecondary) } +
            tasks.map { Yuvomi.colors.module("tasks") }).take(4)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            dots.forEach { c -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (inMonth) c else c.copy(alpha = 0.4f))) }
        }
    }
}

@Composable
fun DayHeader(day: LocalDate, text: CalendarText, today: LocalDate, onClick: (() -> Unit)?) {
    val relative = text.relative(day)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            relative ?: text.dayHeading(day),
            style = MaterialTheme.typography.titleSmall,
            color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (relative != null) {
            Spacer(Modifier.width(8.dp))
            Text(text.dayHeading(day), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
}

/** `dayGroupHtml()`: holidays, shifts, waste, events, tasks of one day. */
@Composable
fun DayRows(store: CalendarStore, s: CalendarStore.State, day: LocalDate, text: CalendarText, actions: CalendarActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        store.holidaysOn(day, s).forEach { h -> LayerChip(h.name, color(h.color, Yuvomi.colors.danger), null) }
        store.scheduleOn(day, s).forEach { e ->
            val type = e.shiftType ?: return@forEach
            val owner = s.members.firstOrNull { it.id == e.userId }?.displayName
            val label = listOfNotNull(type.name, owner, text.shift(type).ifEmpty { null }).joinToString(" · ")
            LayerChip(label, color(type.color, Yuvomi.colors.textSecondary), Icons.Outlined.Work) { actions.openSchedule(e) }
        }
        store.wasteOn(day, s).forEach { w -> LayerChip(w.typeName, color(w.typeColor, Yuvomi.colors.textSecondary), Icons.Outlined.Delete) { actions.openWaste(w) } }
        store.eventsOn(day, s).forEach { ev -> EventRow(ev, day, text, s, onClick = { actions.openEvent(ev) }) }
        store.tasksOn(day, s).forEach { task -> TaskRow(task, text) { actions.openTask(task) } }
    }
}

@Composable
fun LayerChip(label: String, tone: Color, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tone.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = tone)
            Spacer(Modifier.width(6.dp))
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** `renderAgendaEvent()`: colour edge, glyphs, title, time, place, calendar, people. */
@Composable
fun EventRow(ev: CalendarEvent, day: LocalDate, text: CalendarText, s: CalendarStore.State, onClick: () -> Unit) {
    val tone = color(CalendarRules.color(ev), Yuvomi.colors.textSecondary)
    val time = text.rowTime(ev, day)
    val position = text.position(ev, day)
    val calendarName = calendarName(ev, s.calendars)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tone.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 48.dp)) {
            Box(Modifier.width(4.dp).fillMaxHeight().heightIn(min = 48.dp).background(tone))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (CalendarIcons.shown(ev.icon)) {
                        Icon(CalendarIcons.glyph(ev.icon), null, Modifier.size(16.dp), tint = tone)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (!ev.recurrenceRule.isNullOrEmpty() || ev.seriesId != null) {
                        Icon(Icons.Outlined.Repeat, t("calendar.recurringEvent"), Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        ev.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (CalendarRules.isProviderCancelled(ev)) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (CalendarRules.isProviderCancelled(ev)) {
                        Spacer(Modifier.width(6.dp))
                        Text(t("calendar.cancelled"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.danger)
                    }
                }
                Meta(Icons.Outlined.Schedule, listOfNotNull(time, position).joinToString(" · "))
                ev.location?.takeIf { it.isNotBlank() }?.let { Meta(Icons.Outlined.Place, flatLocation(it)) }
                if (calendarName.isNotEmpty()) Meta(Icons.Outlined.CalendarMonth, calendarName)
                if (ev.visibility == "private" || ev.visibility == "assignees") {
                    Meta(Icons.Outlined.Lock, t(if (ev.visibility == "private") "common.visibility.private" else "common.visibility.assignees"))
                }
            }
            if (ev.assignedUsers.isNotEmpty()) {
                Box(Modifier.padding(end = 10.dp).align(Alignment.CenterVertically)) {
                    AvatarStack(ev.assignedUsers.map { Triple(it.displayName, it.color, it.avatarData) }, max = 3, size = 22.dp)
                }
            }
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
        Spacer(Modifier.width(4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `renderTaskChip()`: the open ring, the title, its time. */
@Composable
fun TaskRow(task: CalendarTask, text: CalendarText, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = text.s.t("calendar.taskChipAriaLabel", "title" to task.title) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(18.dp), tint = Yuvomi.colors.module("tasks"))
        Spacer(Modifier.width(8.dp))
        Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        task.dueTime?.let { tm -> runCatching { LocalTime.parse(tm.take(5)) }.getOrNull() }?.let {
            Text(text.time(it), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
}

/** `fmtLocation()`: an ICS-escaped, multi-line address as one line. */
fun flatLocation(value: String): String =
    value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

/** `eventLocalCalendarDisplayName()`: no name while there is only one local calendar. */
fun calendarName(ev: CalendarEvent, calendars: List<LocalCalendar>): String {
    if (ev.localCalendarId != null && calendars.size <= 1) return ""
    val local = calendars.firstOrNull { it.id == ev.localCalendarId }
    return local?.name ?: ev.calName ?: ev.localCalendarName ?: ""
}

// ── Agenda (CAL-R11): 31 days from the cursor, the days with something, and today ──

@Composable
fun AgendaView(store: CalendarStore, s: CalendarStore.State, text: CalendarText, today: LocalDate, actions: CalendarActions) {
    val days = (0..CalendarDates.AGENDA_DAYS).map { s.cursor.plusDays(it.toLong()) }
    val todayInRange = !today.isBefore(days.first()) && !today.isAfter(days.last())
    val shown = days.filter { !store.isEmptyDay(it, s) || (todayInRange && it == today) }
    if (shown.isEmpty()) {
        io.github.marcomow.yuvomi.core.design.EmptyState(
            title = t("calendar.agendaEmpty"),
            action = if (actions.create != null) t("calendar.newEvent") else null,
            onAction = actions.create?.let { create -> { create(store.dates.newEventDate(s.view, s.cursor, today, s.wide), null) } },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(shown, key = { it.toString() }) { day ->
            Column {
                DayHeader(day, text, today, onClick = { actions.openDay(day) })
                if (store.isEmptyDay(day, s)) {
                    Text(t("calendar.agendaDayEmpty"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Yuvomi.colors.textSecondary)
                } else {
                    DayRows(store, s, day, text, actions)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ── Week and day (CAL-R10): the time grid ──

private val HOUR = 48.dp
private val GUTTER = 44.dp

@Composable
fun TimeGridView(store: CalendarStore, s: CalendarStore.State, days: List<LocalDate>, text: CalendarText, today: LocalDate, now: LocalTime, actions: CalendarActions) {
    val dates = store.dates
    Column(Modifier.fillMaxSize()) {
        // Day heads; a tap opens that day.
        if (days.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = GUTTER)) {
                days.forEach { d ->
                    Column(
                        Modifier.weight(1f).clickable { actions.openDay(d) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text.weekdayShort(d), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                        Text(
                            d.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        // The all-day band: holidays, all-day and multi-day events, shifts, waste, tasks.
        val bandItems = days.map { d ->
            val allDay = store.eventsOn(d, s).filter { dates.isAllDayLike(it) }
            d to allDay
        }
        val anyBand = days.any { d ->
            store.holidaysOn(d, s).isNotEmpty() || store.scheduleOn(d, s).isNotEmpty() || store.wasteOn(d, s).isNotEmpty() || store.tasksOn(d, s).isNotEmpty()
        } || bandItems.any { it.second.isNotEmpty() }
        if (anyBand) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(t("calendar.allDayShort"), Modifier.width(GUTTER).padding(start = 4.dp, top = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                bandItems.forEach { (d, allDay) ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        store.holidaysOn(d, s).forEach { h -> MiniChip(h.name, color(h.color, Yuvomi.colors.danger), null) }
                        store.scheduleOn(d, s).forEach { e ->
                            val type = e.shiftType ?: return@forEach
                            MiniChip(type.shortCode ?: type.name, color(type.color, Yuvomi.colors.textSecondary)) { actions.openSchedule(e) }
                        }
                        store.wasteOn(d, s).forEach { w -> MiniChip(w.typeName, color(w.typeColor, Yuvomi.colors.textSecondary)) { actions.openWaste(w) } }
                        allDay.forEach { ev ->
                            val label = if (CalendarRules.isProviderCancelled(ev)) "${t("calendar.cancelled")}: ${ev.title}" else ev.title
                            MiniChip(label, color(CalendarRules.color(ev), Yuvomi.colors.textSecondary)) { actions.openEvent(ev) }
                        }
                        store.tasksOn(d, s).forEach { task -> MiniChip("○ " + task.title, Yuvomi.colors.module("tasks")) { actions.openTask(task) } }
                    }
                }
            }
            HorizontalDivider()
        }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(days.first()) {
            // `scrollGridToNow`: today's hour, else the morning.
            val hour = if (days.contains(today)) (now.hour - 1).coerceAtLeast(0) else 7
            scroll.scrollTo(with(density) { (HOUR * hour).roundToPx() })
        }
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.width(GUTTER)) {
                (0 until 24).forEach { h ->
                    Box(Modifier.height(HOUR).fillMaxWidth()) {
                        if (h > 0) Text(text.time(LocalTime.of(h, 0)), Modifier.offset(y = (-7).dp).padding(start = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Yuvomi.colors.textSecondary)
                    }
                }
            }
            days.forEach { d ->
                DayColumn(store, s, d, text, d == today, now, actions, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(store: CalendarStore, s: CalendarStore.State, day: LocalDate, text: CalendarText, isToday: Boolean, now: LocalTime, actions: CalendarActions, modifier: Modifier) {
    val dates = store.dates
    val timed = store.eventsOn(day, s).filter { !dates.isAllDayLike(it) }
    val lanes = remember(timed, day) { CalendarDates.lanes(timed) { dates.minutes(it, day) } }
    val line = Yuvomi.colors.border.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier
            .height(HOUR * 24)
            .border(0.5.dp, line)
            .pointerInput(day, actions.create) {
                val create = actions.create ?: return@pointerInput
                detectTapGestures(onLongPress = { offset ->
                    // `clickedTime()`: to the half hour, at most 23:30.
                    val minutes = ((offset.y / HOUR.toPx()) * 60 / 30).toInt() * 30
                    val clamped = minutes.coerceIn(0, 23 * 60 + 30)
                    create(day, LocalTime.of(clamped / 60, clamped % 60))
                })
            },
    ) {
        val width = maxWidth
        Column {
            (0 until 24).forEach { _ ->
                Box(Modifier.height(HOUR).fillMaxWidth()) { HorizontalDivider(color = line, thickness = 0.5.dp) }
            }
        }
        timed.forEach { ev ->
            val range = dates.minutes(ev, day)
            val (col, total) = lanes[ev] ?: (0 to 1)
            val tone = color(CalendarRules.color(ev), Yuvomi.colors.textSecondary)
            val laneWidth = width / total
            Column(
                Modifier
                    .offset(x = laneWidth * col, y = HOUR * (range.first / 60f))
                    .width(laneWidth)
                    .height(HOUR * ((range.last + 1 - range.first) / 60f))
                    .padding(1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tone.copy(alpha = 0.22f))
                    .border(width = 0.dp, color = Color.Transparent)
                    .clickable { actions.openEvent(ev) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    if (CalendarRules.isProviderCancelled(ev)) "${t("calendar.cancelled")}: ${ev.title}" else ev.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (CalendarRules.isProviderCancelled(ev)) TextDecoration.LineThrough else null,
                )
                Text(text.rowTime(ev, day), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Yuvomi.colors.textSecondary, maxLines = 1)
            }
        }
        if (isToday) {
            // The now line (`timeGridNowLineHtml`).
            Box(Modifier.offset(y = HOUR * ((now.hour * 60 + now.minute) / 60f)).fillMaxWidth().height(2.dp).background(Yuvomi.colors.danger))
        }
    }
}

@Composable
private fun MiniChip(label: String, tone: Color, onClick: (() -> Unit)?) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(tone.copy(alpha = 0.18f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
