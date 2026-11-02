package io.github.marcomow.yuvomi.feature.schedule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.github.marcomow.yuvomi.core.design.AvatarStack
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.LocalDate

private val HOUR_HEIGHT = 48.dp
private val AXIS_WIDTH = 40.dp
private val MIN_LANE = 44.dp

/** `overviewIsStart`: nothing planned at all, so the start state shows instead of an empty grid. */
internal fun overviewIsStart(s: ScheduleViewModel.UiState): Boolean =
    s.data.patterns.isEmpty() && s.data.overrides.isEmpty() && s.data.extras.isEmpty() &&
        !s.overview.loading && !s.overview.failed && s.overview.entries.isEmpty()

/** `scheduleOverviewEntryTitle`: shift, person, and the overlay line. */
internal fun entryTitle(entry: Entry, data: ScheduleData, strings: Strings): String {
    val base = entry.shiftType?.let(ScheduleRules::label) ?: strings.t("schedule.freeDay")
    return listOf(base, ScheduleRules.userName(data.users, entry.userId), ScheduleRules.overlayMeta(entry)).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** docs/spec/schedule.md, Overview (SCHED-R6 to R13): the week or day as a time grid, a lane per person. */
@Composable
internal fun OverviewTab(s: ScheduleViewModel.UiState, vm: ScheduleViewModel, text: ScheduleText, writable: Boolean, onOpen: (Overlay) -> Unit) {
    when {
        ScheduleRules.planningNeedsShiftTypes(s.data) -> NeedsTypesState { vm.selectTab(ScheduleTab.SHIFTS) }
        overviewIsStart(s) -> EmptyPatternState(s.data, writable) { onOpen(Overlay.CreateEntry(EntryMode.PATTERN)) }
        else -> Column(Modifier.fillMaxSize()) {
            OverviewToolbar(s, vm, text, onPeople = { onOpen(Overlay.People) })
            if (s.overview.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            when {
                s.overview.failed -> ErrorBox(t("common.errorGeneric"), onRetry = vm::loadOverview)
                s.overview.selected.isEmpty() -> EmptyState(t("schedule.overviewEmptyTitle"), t("schedule.overviewEmptyDescription"))
                else -> OverviewGrid(s, vm, onOpen)
            }
        }
    }
}

/** `emptyPlanningNeedsTypesState`: the one way forward is the Shift types tab. */
@Composable
internal fun NeedsTypesState(goToTypes: () -> Unit) {
    EmptyState(t("schedule.planningNeedsTypesTitle"), t("schedule.planningNeedsTypesDescription"), action = t("schedule.goToShiftTypes"), onAction = goToTypes)
}

/** `emptyPatternState`; without a shift type the hint says where to start. */
@Composable
internal fun EmptyPatternState(data: ScheduleData, writable: Boolean, add: () -> Unit) {
    val body = if (data.types.isNotEmpty()) t("schedule.emptyPatternsDescription") else "${t("schedule.emptyPatternsDescription")} ${t("schedule.noShiftTypesHint")}"
    EmptyState(t("schedule.emptyPatternsTitle"), body, action = if (writable) t("schedule.addPattern") else null, onAction = if (writable) add else null)
}

/** `overviewToolbarHtml`: period stepper with "Today", Week/Day, and the people button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewToolbar(s: ScheduleViewModel.UiState, vm: ScheduleViewModel, text: ScheduleText, onPeople: () -> Unit) {
    val o = s.overview
    val strings = LocalStrings.current
    val days = ScheduleRules.visibleDays(o.dayMode, o.cursor, ScheduleRules.weekStart(s.data.household.weekStart))
    val showsToday = s.today in days
    val label = if (o.dayMode) text.day(days.first()) else text.range(days.first().toString(), days.last().toString(), strings)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.stepOverview(-1) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, t(if (o.dayMode) "calendar.prevDay" else "calendar.prevWeek"))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { vm.stepOverview(1) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, t(if (o.dayMode) "calendar.nextDay" else "calendar.nextWeek"))
            }
            if (!showsToday) TextButton(onClick = { vm.stepOverview(0) }) { Text(t("calendar.today")) }
            Spacer(Modifier.weight(1f))
            val chosen = o.selected.mapNotNull { id -> s.data.people.firstOrNull { it.id == id } }
            val peopleLabel = if (chosen.isNotEmpty()) t("schedule.overviewPeopleCount", "count" to chosen.size) else t("schedule.overviewPeopleLabel")
            OutlinedButton(onClick = onPeople, modifier = Modifier.semantics { contentDescription = peopleLabel }) {
                if (chosen.isEmpty()) Icon(Icons.Outlined.Groups, null) else AvatarStack(chosen.map { Triple(it.displayName, it.avatarColor, it.avatarData) }, size = 22.dp)
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 8.dp)) {
            SegmentedButton(selected = !o.dayMode, onClick = { vm.setDayMode(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(t("calendar.viewWeek")) }
            SegmentedButton(selected = o.dayMode, onClick = { vm.setDayMode(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(t("calendar.viewDay")) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun OverviewGrid(s: ScheduleViewModel.UiState, vm: ScheduleViewModel, onOpen: (Overlay) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val o = s.overview
    val first = ScheduleRules.weekStart(s.data.household.weekStart)
    val days = ScheduleRules.visibleDays(o.dayMode, o.cursor, first)
    // DER LADEZUSTAND ZEIGT DAS RASTER: lanes stand while the week loads, without blocks.
    val lanesByDay = ScheduleRules.buildLanes(days, o.selected, if (o.loading) emptyList() else o.entries)
    val laneCount = o.selected.size
    val surname = ScheduleRules.sharedSurname(s.data.people.map { it.displayName })
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxWidth - AXIS_WIDTH
        val dayWidth: Dp = max(available / days.size, MIN_LANE * laneCount)
        val laneWidth = dayWidth / laneCount
        val visibleHours = ((maxHeight - 96.dp) / HOUR_HEIGHT).toInt().coerceAtLeast(1)

        // `alignOverviewToNow`: the grid opens on the current hour, from the morning or the first block.
        LaunchedEffect(o.dayMode, o.cursor, o.loading) {
            if (o.loading) return@LaunchedEffect
            val firstBlock = lanesByDay.flatMap { d -> d.lanes.flatMap { it.entries } }
                .filter { !it.continuation }.mapNotNull { it.span()?.first }.filter { it > 0 }.minOrNull()
            val top = ScheduleRules.topHour(shell.time.now().hour, visibleHours, firstBlock?.div(60))
            vScroll.scrollTo(with(density) { (HOUR_HEIGHT * top).roundToPx() })
        }

        Column(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            // Day heads: name and number (today in the accent circle), holidays, lane heads, all-day blocks.
            Row(Modifier.width(AXIS_WIDTH + dayWidth * days.size)) {
                Spacer(Modifier.width(AXIS_WIDTH))
                lanesByDay.forEach { day ->
                    Column(Modifier.width(dayWidth).padding(horizontal = 1.dp)) {
                        DayHead(day.date, s.today, t("calendar.monthOpenDay", "date" to shell.formats.date(day.date, s.today)), onOpen = if (o.dayMode) null else ({ vm.openDay(day.date) }))
                        ScheduleRules.holidaysOn(o.holidays, day.date).forEach { h ->
                            Text(
                                h.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                    .background((Tokens.parseColor(h.color) ?: Yuvomi.colors.border).copy(alpha = 0.2f)).padding(horizontal = 4.dp),
                            )
                        }
                        Row {
                            day.lanes.forEach { lane ->
                                Column(Modifier.width(laneWidth).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (laneCount > 1) LaneHead(lane.userId, s.data, surname)
                                    lane.entries.filterNot { ScheduleRules.isTimed(it.entry) }.forEach { le ->
                                        AllDayBlock(le.entry, s.data, strings) { onOpen(Overlay.EntryDetail(le.entry)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).verticalScroll(vScroll)) {
                Row(Modifier.height(HOUR_HEIGHT * 24)) {
                    Column(Modifier.width(AXIS_WIDTH).fillMaxHeight()) {
                        for (hour in 0 until 24) {
                            Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                                if (hour > 0) {
                                    Text(
                                        shell.formats.time(java.time.LocalTime.of(hour, 0)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Yuvomi.colors.textSecondary,
                                        maxLines = 1,
                                        modifier = Modifier.offset(y = (-7).dp).padding(start = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    lanesByDay.forEach { day -> DayColumn(day, dayWidth, laneWidth, s, onOpen) }
                }
            }
        }
    }
}

/** `timeGridDayHeadHtml`: weekday and number; the week's head opens the day. */
@Composable
private fun DayHead(date: LocalDate, today: LocalDate, openLabel: String, onOpen: (() -> Unit)?) {
    val isToday = date == today
    Column(
        Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen).semantics { contentDescription = openLabel } else Modifier)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(dayShort(date).uppercase(), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** `overviewLaneHeader`: avatar and first name; the shared surname stays for the accessible name. */
@Composable
private fun LaneHead(userId: Int, data: ScheduleData, surname: String) {
    val person = data.people.firstOrNull { it.id == userId }
    val name = person?.displayName ?: ScheduleRules.userName(data.users, userId)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = name }) {
        MemberAvatar(name, person?.avatarColor, person?.avatarData, size = 16.dp)
        Text(ScheduleRules.shortName(name, surname), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

/** An untimed entry (vacation, free day) in the lane's all-day cell. */
@Composable
private fun AllDayBlock(entry: Entry, data: ScheduleData, strings: Strings, open: () -> Unit) {
    val type = entry.shiftType
    val label = type?.let(ScheduleRules::label) ?: strings.t("schedule.freeDay")
    val overlay = ScheduleRules.overlayMeta(entry)
    val title = entryTitle(entry, data, strings)
    val color = type?.color?.let(::shiftColor)
    Text(
        if (overlay.isNotEmpty()) "$label · $overlay" else label,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .then(if (color != null) Modifier.background(color.copy(alpha = 0.2f)) else Modifier.border(1.dp, Yuvomi.colors.border, RoundedCornerShape(4.dp)))
            .clickable(onClick = open)
            .semantics { contentDescription = title }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** One day: hour lines, a lane per person with its timed blocks, the now line today. */
@Composable
private fun DayColumn(day: ScheduleRules.DayLanes, dayWidth: Dp, laneWidth: Dp, s: ScheduleViewModel.UiState, onOpen: (Overlay) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val line = Yuvomi.colors.border
    val now = Yuvomi.colors.danger
    val nowMinutes = if (day.date == s.today) shell.time.now().let { it.hour * 60 + it.minute } else null
    Box(
        Modifier.width(dayWidth).fillMaxHeight().drawBehind {
            val hour = size.height / 24
            for (h in 1 until 24) drawLine(line, Offset(0f, hour * h), Offset(size.width, hour * h), strokeWidth = 1f)
            drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
            if (nowMinutes != null) {
                val y = size.height * nowMinutes / (24 * 60)
                drawLine(now, Offset(0f, y), Offset(size.width, y), strokeWidth = 4f)
            }
        },
    ) {
        Row {
            day.lanes.forEach { lane ->
                Box(Modifier.width(laneWidth).fillMaxHeight()) {
                    lane.entries.filter { ScheduleRules.isTimed(it.entry) }.forEach { le ->
                        TimedBlock(le, s.data, strings, shell.formats::time) { onOpen(Overlay.EntryDetail(le.entry)) }
                    }
                }
            }
        }
    }
}

/** `overviewEntryBlock`: placed by its start, as tall as it lasts (at least half an hour). */
@Composable
private fun TimedBlock(le: ScheduleRules.LaneEntry, data: ScheduleData, strings: Strings, clock: (java.time.LocalTime) -> String, open: () -> Unit) {
    val (start, end) = le.span() ?: return
    val type = le.entry.shiftType ?: return
    val color = shiftColor(type.color)
    val overlay = ScheduleRules.overlayMeta(le.entry)
    val timeLine = if (le.continuation) {
        strings.t("schedule.continuesUntil", "time" to (ScheduleRules.time(type.endTime)?.let(clock) ?: ""))
    } else {
        val span = ScheduleRules.clockLabel(type, strings, clock)
        if (overlay.isNotEmpty()) "$span · $overlay" else span
    }
    val title = entryTitle(le.entry, data, strings)
    Column(
        Modifier
            .offset(y = HOUR_HEIGHT * (start / 60f))
            .height(HOUR_HEIGHT * ((end - start) / 60f) - 2.dp)
            .fillMaxWidth()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.22f))
            .drawBehind { drawLine(color, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 8f) }
            .clickable(onClick = open)
            .semantics { contentDescription = title }
            .padding(start = 6.dp, end = 2.dp, top = 2.dp),
    ) {
        Text(ScheduleRules.label(type), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(timeLine, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** `openOverviewPeople`: a chip per household member; the choice is kept on this device. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PeopleSheet(s: ScheduleViewModel.UiState, vm: ScheduleViewModel, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(t("schedule.overviewPeopleLabel"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.data.people.forEach { p ->
                    FilterChip(
                        selected = p.id in s.overview.selected,
                        onClick = { vm.togglePerson(p.id) },
                        label = { Text(p.displayName) },
                        leadingIcon = { MemberAvatar(p.displayName, p.avatarColor, p.avatarData, size = 20.dp) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
