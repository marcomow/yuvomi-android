package io.github.marcomow.yuvomi.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingRoutes
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.Routes
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import io.github.marcomow.yuvomi.feature.waste.WasteRoutes
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The member's rights on the calendar (`readOnly()`, `mayChangeEvent()`). */
data class CalendarRights(val write: Boolean, val editOthers: Boolean, val admin: Boolean, val me: Int?) {
    fun mayChange(ev: CalendarEvent) = CalendarRules.mayChange(ev, me, write, editOthers)
}

fun ShellContext.calendarRights(): CalendarRights = CalendarRights(
    write = session.access("calendar") == "write",
    editOthers = session.isAdmin || session.permissions.capability("calendar_edit_others"),
    admin = session.isAdmin,
    me = session.user.id,
)

/** The store, configured for this member (layers they may read). */
@Composable
fun rememberCalendarStore(): CalendarStore? {
    val shell = LocalShell.current
    val store = remember(shell.connection) { CalendarStore.of(shell.container, shell.time.zone) { shell.time.today() } } ?: return null
    store.me = shell.session.user.id
    store.scheduleOn = shell.shows("schedule")
    store.wasteOn = shell.shows("waste")
    store.tasksOn = shell.shows("tasks")
    return store
}

/** docs/spec/calendar.md, the calendar page (CAL-R1 to R14). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(openId: Int?, openEvent: () -> Unit, openEditor: () -> Unit, manageCalendars: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = rememberCalendarStore() ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val rights = shell.calendarRights()
    val today = shell.time.today()
    val text = CalendarText(strings, store.dates, today)
    var filtersOpen by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var scheduleShown by remember { mutableStateOf<ScheduleEntry?>(null) }
    var deepLinkHandled by rememberSaveable { mutableStateOf(false) }
    val newEvent = { date: LocalDate, time: LocalTime? ->
        store.editing = CalendarStore.EditTarget(date = date, time = time)
        openEditor()
    }

    LaunchedEffect(store) { if (openId == null || deepLinkHandled) store.refresh() }
    LaunchedEffect(openId) {
        if (openId != null && !deepLinkHandled) {
            deepLinkHandled = true
            val error = store.openById(openId)
            if (error != null) shell.notify(strings.t(error)) else openEvent()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (store.lastLoaded != 0L && System.currentTimeMillis() - store.lastLoaded > 60_000) store.refresh()
    }

    val canCreate = rights.write && !s.offline
    val actions = CalendarActions(
        openEvent = { ev ->
            // Housekeeping visits are edited in their module (`openEventDetail`).
            if (ev.housekeepingVisitId != null) shell.navigate(HousekeepingRoutes.START)
            else {
                store.open(ev)
                openEvent()
            }
        },
        openTask = { shell.navigate(Routes.task(it.id)) },
        openWaste = { shell.navigate(WasteRoutes.START) },
        openSchedule = { scheduleShown = it },
        openDay = { store.openDay(it) },
        select = { store.select(it) },
        create = if (canCreate) newEvent else null,
    )

    Scaffold(
        topBar = {
            if (s.search.active) {
                SearchBar(s.search.query, onQuery = { store.search(it) }, onClose = { store.setSearch(false) })
            } else {
                TopAppBar(
                    title = { Text(t("calendar.title")) },
                    actions = {
                        IconButton(onClick = { store.setSearch(true) }) { Icon(Icons.Outlined.Search, t("calendar.searchOpen")) }
                        val count = CalendarRules.activeCount(s.filter, s.prefs, store.scheduleOn)
                        IconButton(onClick = { filtersOpen = true }) {
                            BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                Icon(Icons.Outlined.FilterList, if (count > 0) strings.t("calendar.filtersActive", "count" to count) else t("calendar.filtersOpen"))
                            }
                        }
                        if (rights.write) {
                            Box {
                                IconButton(onClick = { viewMenu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                                DropdownMenu(viewMenu, onDismissRequest = { viewMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(t("calendar.manageCalendars")) },
                                        leadingIcon = { Icon(Icons.Outlined.EditCalendar, null) },
                                        enabled = !s.offline,
                                        onClick = { viewMenu = false; manageCalendars() },
                                    )
                                }
                            }
                        }
                        AccountButton()
                    },
                )
            }
        },
        floatingActionButton = {
            if (canCreate && !s.search.active) {
                FloatingActionButton(onClick = { newEvent(store.dates.newEventDate(s.view, s.cursor, today, s.wide), null) }) {
                    Icon(Icons.Outlined.Add, contentDescription = t("calendar.newEvent"))
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            LaunchedEffect(wide) { store.setWide(wide) }
            Column(Modifier.fillMaxSize()) {
                if (s.offline) OfflineBanner(s.cachedAt?.let { shell.formats.dateTime(Instant.ofEpochMilli(it).atZone(shell.time.zone)) })
                if (s.search.active) {
                    SearchResults(s.search, text, actions)
                    return@Column
                }
                PeriodBar(s, text, today, onStep = { store.step(it) }, onToday = { store.goToday() }, onView = { store.setView(it) })
                when {
                    s.loading && s.events.isEmpty() && !s.loaded -> LoadingBox()
                    s.errorKey != null && !s.loaded -> ErrorBox(strings.t(s.errorKey!!), onRetry = { store.refresh() })
                    else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { store.refresh(pulled = true) }, modifier = Modifier.fillMaxSize()) {
                        when (s.view) {
                            CalendarView.MONTH -> MonthView(store, s, text, today, actions)
                            CalendarView.WEEK -> TimeGridView(store, s, store.dates.weekDays(s.cursor, s.wide), text, today, shell.time.now().toLocalTime(), actions)
                            CalendarView.DAY -> TimeGridView(store, s, listOf(s.cursor), text, today, shell.time.now().toLocalTime(), actions)
                            CalendarView.AGENDA -> AgendaView(store, s, text, today, actions)
                        }
                    }
                }
            }
        }
    }

    if (filtersOpen) FilterSheet(store, s, rights, manageCalendars = { filtersOpen = false; manageCalendars() }, close = { filtersOpen = false })
    scheduleShown?.let { e -> ScheduleDialog(e, s, text) { scheduleShown = null } }
}

/** Arrows, the period title, Today and the view switch (`toolbarHtml`, `periodNavHtml`). */
@Composable
private fun PeriodBar(s: CalendarStore.State, text: CalendarText, today: LocalDate, onStep: (Int) -> Unit, onToday: () -> Unit, onView: (CalendarView) -> Unit) {
    val strings = LocalStrings.current
    val (prevKey, nextKey) = CalendarDates(java.time.ZoneOffset.UTC).arrowKeys(s.view, s.wide)
    val days = CalendarDates(java.time.ZoneOffset.UTC).stepDays(s.view, s.wide)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, strings.t(prevKey, "count" to days)) }
            Text(
                text.period(s.view, s.cursor, s.wide),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onStep(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, strings.t(nextKey, "count" to days)) }
            // `syncTodayButton`: Today only does something away from today.
            TextButton(onClick = onToday, enabled = s.cursor != today) { Text(t("calendar.today")) }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            CalendarView.entries.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = s.view == v,
                    onClick = { onView(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, CalendarView.entries.size),
                    icon = {},
                ) { Text(t(v.labelKey), maxLines = 1) }
            }
        }
    }
}

// ── Search (CAL-R14) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("calendar.searchClose")) } },
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(t("calendar.searchOpen")) },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
        },
    )
}

@Composable
private fun SearchResults(search: CalendarStore.Search, text: CalendarText, actions: CalendarActions) {
    val strings = LocalStrings.current
    val q = search.query.trim()
    when {
        q.length < 2 -> EmptyState(title = t("calendar.searchHint"))
        search.loading && search.results.isEmpty() -> LoadingBox()
        search.failed -> EmptyState(title = t("calendar.searchError"))
        search.results.isEmpty() -> EmptyState(title = strings.t("calendar.searchEmpty", "query" to q))
        else -> {
            val dates = text
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(
                        if (search.total > search.results.size) strings.t("calendar.searchCountCapped", "shown" to search.results.size, "total" to search.total)
                        else strings.t("calendar.searchCount", "count" to search.results.size),
                        style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary,
                    )
                }
                items(search.results, key = { it.occurrenceKey }) { ev ->
                    val shell = LocalShell.current
                    val day = CalendarDates(shell.time.zone).startDate(ev)
                    Column {
                        Text(dates.dayHeading(day, year = true), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                        EventRow(ev, day, text, CalendarStore.State(), onClick = { actions.openEvent(ev) })
                    }
                }
            }
        }
    }
}

// ── Filters (CAL-R12) ──

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(store: CalendarStore, s: CalendarStore.State, rights: CalendarRights, manageCalendars: () -> Unit, close: () -> Unit) {
    val f = s.filter
    fun set(change: (CalendarFilter) -> CalendarFilter) = store.saveFilter(change(store.state.value.filter))
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(t("calendar.filters"), style = MaterialTheme.typography.titleLarge)
            if (s.members.size > 1) {
                SectionTitle(t("calendar.filtersPeople"))
                SwitchRow(t("calendar.assignedToMe"), null, f.assignedToMe) { on -> set { it.copy(assignedToMe = on) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.members.forEach { m ->
                        FilterChip(
                            selected = m.id.toString() in f.people,
                            onClick = { set { CalendarRules.togglePerson(it, m.id.toString(), s.members.size) } },
                            label = { Text(m.displayName.substringBefore(' ')) },
                            leadingIcon = { MemberAvatar(m.displayName, m.avatarColor, m.avatarData, size = 20.dp) },
                        )
                    }
                    FilterChip(
                        selected = CalendarFilter.UNASSIGNED in f.people,
                        onClick = { set { CalendarRules.togglePerson(it, CalendarFilter.UNASSIGNED, s.members.size) } },
                        label = { Text(t("calendar.filterUnassigned")) },
                    )
                }
            }
            val sources = store.sources(s)
            SectionTitle(t("calendar.filtersSources"))
            sources.forEach { src ->
                SwitchRow(src.name.ifEmpty { t("calendar.detailCalendar") }, src.color, src.key !in f.hiddenSources) { on ->
                    set { it.copy(hiddenSources = if (on) it.hiddenSources - src.key else it.hiddenSources + (src.key to HiddenSource(src.name, src.color))) }
                }
            }
            if (rights.write) {
                TextButton(onClick = manageCalendars) {
                    Icon(Icons.Outlined.EditCalendar, null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("calendar.manageCalendars"))
                }
            }
            SectionTitle(t("calendar.filtersLayers"))
            if (s.prefs.holidayShowPublic) SwitchRow(t("calendar.toggleHolidays"), s.prefs.holidayPublicColor ?: "#FF3B30", f.layerHolidays) { on -> set { it.copy(layerHolidays = on) } }
            if (s.prefs.holidayShowSchool) SwitchRow(t("calendar.toggleSchool"), s.prefs.holidaySchoolColor ?: "#34C759", f.layerSchool) { on -> set { it.copy(layerSchool = on) } }
            if (store.scheduleOn) SwitchRow(t("schedule.overlay"), null, f.layerSchedule) { on -> set { it.copy(layerSchedule = on) } }
            if (store.wasteOn) {
                SwitchRow(t("waste.overlay"), null, f.layerWaste) { on ->
                    set { it.copy(layerWaste = on) }
                    store.refresh()
                }
            }
            SwitchRow(t("calendar.toggleBirthdays"), null, f.layerBirthdays) { on -> set { it.copy(layerBirthdays = on) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = {
                // "Reset filters": everything back to the defaults, the view stays.
                set { CalendarFilter(view = it.view) }
            }) { Text(t("common.filtersResetDefault")) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
}

@Composable
fun SwitchRow(label: String, swatch: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(Modifier.size(12.dp).background(color(swatch, Yuvomi.colors.textSecondary), CircleShape))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** `openScheduleEntryDetailModal()`: the shift, who, when, note. */
@Composable
private fun ScheduleDialog(entry: ScheduleEntry, s: CalendarStore.State, text: CalendarText, close: () -> Unit) {
    val type = entry.shiftType ?: return
    val owner = s.members.firstOrNull { it.id == entry.userId }?.displayName
    AlertDialog(
        onDismissRequest = close,
        title = { Text(type.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                owner?.let { Text(it) }
                Text(text.dayHeading(LocalDate.parse(entry.dateKey)))
                text.shift(type).takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (entry.source == "extra") Text(t("schedule.extraBadgeLabel"))
                entry.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(t("common.close")) } },
    )
}
