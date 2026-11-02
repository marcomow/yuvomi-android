package io.github.marcomow.yuvomi.feature.housekeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingViewModel.Overlay
import io.github.marcomow.yuvomi.feature.housekeeping.HousekeepingViewModel.Tab as HkTab
import kotlinx.coroutines.launch
import java.time.Instant

/** docs/spec/housekeeping.md: the page with its four tabs (`renderShell`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HousekeepingScreen(vm: HousekeepingViewModel, link: HousekeepingLink) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    val text = vm.text
    val writable = !vm.readOnly && !s.offline

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.refresh()
    }
    // HOUSE-R30 and the Tags view's links, once the page has its data.
    var linked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.loading, link) {
        if (s.loading || linked) return@LaunchedEffect
        linked = true
        link.visit?.let(vm::openVisit)
        link.task?.let(vm::openTask)
        link.worker?.let(vm::openWorker)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(t("housekeeping.title")) }, actions = { AccountButton() })
                PrimaryScrollableTabRow(selectedTabIndex = s.tab.ordinal, edgePadding = 8.dp) {
                    HkTab.entries.forEach { tab ->
                        Tab(
                            selected = s.tab == tab,
                            onClick = { vm.setTab(tab) },
                            text = { Text(t(tabLabel(tab)), maxLines = 1) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // `updateHousekeepingFab()`: chores add a chore, staff a person; the others have nothing to add.
            when {
                s.tab == HkTab.CHORES && writable ->
                    FloatingActionButton(onClick = { vm.show(Overlay.TaskCreate) }) { Icon(Icons.Outlined.Add, contentDescription = t("housekeeping.addTask")) }
                s.tab == HkTab.STAFF && vm.mayManage && !s.offline ->
                    FloatingActionButton(onClick = { vm.show(Overlay.WorkerEdit(null)) }) { Icon(Icons.Outlined.Add, contentDescription = t("housekeeping.addWorker")) }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.loadFailed -> ErrorBox("${t("housekeeping.loadError")}\n${t("common.loadErrorDescription")}", onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        when (s.tab) {
                            HkTab.VISITS -> visitsTab(s, vm, text, writable)
                            HkTab.CHORES -> choresTab(s, vm, writable)
                            HkTab.REPORTS -> reportsTab(s, vm, text, writable)
                            HkTab.STAFF -> staffTab(s, vm, text, writable)
                        }
                    }
                }
            }
        }
    }

    HousekeepingOverlays(vm, s, text, writable)
}

/** Where a link into the module points: a visit (`?editVisit=`), a chore or a person. */
data class HousekeepingLink(val visit: Int? = null, val task: Int? = null, val worker: Int? = null)

private fun tabLabel(tab: HkTab) = when (tab) {
    HkTab.VISITS -> "housekeeping.tabVisits"
    HkTab.CHORES -> "housekeeping.tabChores"
    HkTab.REPORTS -> "housekeeping.reports"
    HkTab.STAFF -> "housekeeping.staffTitle"
}

// ── Visits (renderDashboard) ──

private fun LazyListScope.visitsTab(s: HousekeepingViewModel.UiState, vm: HousekeepingViewModel, text: HousekeepingText, writable: Boolean) {
    val workers = s.data.workers
    if (workers.isEmpty()) {
        item(key = "no-worker") {
            val manage = vm.mayManage && !s.offline
            EmptyState(
                title = t("housekeeping.noWorkerTitle"),
                body = t(if (manage) "housekeeping.noWorkerHint" else "housekeeping.noWorkerHintAdminOnly"),
                action = if (manage) t("housekeeping.setupProfileAction") else null,
                onAction = if (manage) ({ vm.show(Overlay.WorkerEdit(null)) }) else null,
            )
        }
        return
    }
    items(workers, key = { "w-${it.id}" }) { worker -> WorkerStrip(worker, vm, text, writable, busy = worker.id in s.busyWorkers) }
    val d = s.data.dashboard
    item(key = "glance") {
        Glance(
            label = t("housekeeping.visitsThisMonth"),
            value = d.visitsThisMonth.toString(),
            flows = listOfNotNull(
                if (d.pendingTasks > 0) t("housekeeping.pendingChores") to d.pendingTasks.toString() else null,
                if (d.finishedTasksThisMonth > 0) t("housekeeping.finishedChores") to d.finishedTasksThisMonth.toString() else null,
            ),
        )
    }
    item(key = "recent-head") { SectionHeading(t("housekeeping.recentVisits")) }
    val recent = s.recentVisits.take(5)
    if (recent.isEmpty()) item(key = "no-visits") { Muted(t("housekeeping.noVisits")) }
    items(recent, key = { "rv-${it.id}" }) { visit ->
        VisitRow(visit, vm, text, writable, title = text.date(visit.checkIn, weekday = true), visitDate = text.date(visit.checkIn, weekday = true))
    }
    if (s.recentVisits.size > recent.size) {
        item(key = "more-visits") {
            TextButton(onClick = { vm.setTab(HkTab.REPORTS) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(t("housekeeping.allVisitsInReport"))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
            }
        }
    }
    if (!HousekeepingRules.amountsHidden(vm.canSeeAmounts, d.monthlyPayments)) {
        item(key = "payments") { Payments(d, text) }
    }
}

@Composable
private fun WorkerStrip(worker: Worker, vm: HousekeepingViewModel, text: HousekeepingText, writable: Boolean, busy: Boolean) {
    val line = when (val l = HousekeepingRules.sessionLine(worker, vm.canSeeAmounts)) {
        is HousekeepingRules.SessionLine.CheckedIn -> "${t("housekeeping.checkedInAt")} ${text.time(l.checkIn)}"
        is HousekeepingRules.SessionLine.DoneToday -> t("housekeeping.visitToday", "from" to text.time(l.checkIn), "to" to text.time(l.checkOut))
        is HousekeepingRules.SessionLine.Recorded -> "${t("housekeeping.visitRecordedAt")} ${text.time(l.checkIn)}"
        HousekeepingRules.SessionLine.NotHere -> t("housekeeping.notHereToday")
        is HousekeepingRules.SessionLine.Rate ->
            if (l.hourly) "${text.money(l.rate)}/${t("housekeeping.rateHourly")}" else "${text.money(l.rate)} · ${t(HousekeepingRules.scheduleKey(l.schedule))}"
    }
    val action = HousekeepingRules.checkAction(worker)
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(worker.displayName, worker.avatarColor, worker.avatarData, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(worker.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(line, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            if (!vm.readOnly) {
                val label = t(
                    when (action) {
                        HousekeepingRules.CheckAction.CHECK_OUT -> "housekeeping.checkOut"
                        HousekeepingRules.CheckAction.CHECK_IN_AGAIN -> "housekeeping.checkInAgain"
                        HousekeepingRules.CheckAction.CHECK_IN -> "housekeeping.checkIn"
                    },
                )
                val icon = if (action == HousekeepingRules.CheckAction.CHECK_OUT) Icons.AutoMirrored.Outlined.Logout else Icons.AutoMirrored.Outlined.Login
                if (action == HousekeepingRules.CheckAction.CHECK_IN) {
                    Button(onClick = { vm.toggleSession(worker.id) }, enabled = writable && !busy) {
                        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { vm.toggleSession(worker.id) }, enabled = writable && !busy) {
                        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
                    }
                }
            } else if (worker.currentSession != null) {
                // `presenceSignHtml()`: a sign, not a disabled button.
                val present = t("dashboard.housekeepingPresent")
                Row(Modifier.semantics { contentDescription = present }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.size(18.dp), tint = Yuvomi.colors.module("housekeeping"))
                    Spacer(Modifier.width(4.dp))
                    Text(present, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun Payments(d: Dashboard, text: HousekeepingText) {
    val paidMonths = HousekeepingRules.paidMonths(d.monthlyPayments)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("housekeeping.payments"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${t("housekeeping.pendingPayments")}: ${text.money(d.pendingPayments ?: 0.0)}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Card {
            Box(Modifier.padding(12.dp)) {
                if (paidMonths.size >= 2) {
                    PaymentChart(d.monthlyPayments.orEmpty(), text)
                } else {
                    val sentence = paidMonths.singleOrNull()?.let { m ->
                        t(
                            if (m.pending > 0) "housekeeping.paymentsMonthWithOpen" else "housekeeping.paymentsMonthAllPaid",
                            "month" to text.month(m.month), "total" to text.money(m.total), "pending" to text.money(m.pending),
                        )
                    }
                    Text(sentence ?: t("housekeeping.noPaymentData"), color = if (sentence == null) Yuvomi.colors.textSecondary else Color.Unspecified)
                }
            }
        }
    }
}

/** Paid at the bottom, pending above, in one column per month (R19, R20). */
@Composable
private fun PaymentChart(months: List<MonthPayment>, text: HousekeepingText) {
    val tone = Yuvomi.colors.module("housekeeping")
    Column {
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            HousekeepingRules.bars(months).forEach { bar ->
                val name = if (bar.total > 0) {
                    "${text.month(bar.month)}: ${text.money(bar.total)}" + if (bar.pending > 0) " (${t("housekeeping.paymentPending")} ${text.money(bar.pending)})" else ""
                } else "${text.month(bar.month)}: ${text.money(0.0)}"
                Column(
                    Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = name },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (bar.total > 0) Text(text.chartMoney(bar.total), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip)
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                        if (bar.total > 0) {
                            Column(Modifier.fillMaxWidth(0.7f).fillMaxHeight(bar.scale.toFloat()).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))) {
                                if (bar.paidShare < 1.0) Box(Modifier.fillMaxWidth().weight((1 - bar.paidShare).toFloat().coerceAtLeast(0.001f)).background(tone.copy(alpha = 0.35f)))
                                if (bar.paidShare > 0.0) Box(Modifier.fillMaxWidth().weight(bar.paidShare.toFloat().coerceAtLeast(0.001f)).background(tone))
                            }
                        }
                    }
                    Text(text.month(bar.month, short = true), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(tone, t("housekeeping.paymentPaid"))
            Legend(tone.copy(alpha = 0.35f), t("housekeeping.paymentPending"))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Chores (renderTasks) ──

private fun LazyListScope.choresTab(s: HousekeepingViewModel.UiState, vm: HousekeepingViewModel, writable: Boolean) {
    val tasks = s.tasks
    if (tasks.isEmpty()) {
        item(key = "no-tasks") {
            EmptyState(
                title = t("housekeeping.noTasks"),
                action = if (writable) t("housekeeping.addTask") else null,
                onAction = if (writable) ({ vm.show(Overlay.TaskCreate) }) else null,
            )
        }
        return
    }
    items(tasks, key = { "t-${it.id}" }) { task -> ChoreRow(task, vm, writable, busy = task.id in s.busyTasks) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoreRow(task: DecayTask, vm: HousekeepingViewModel, writable: Boolean, busy: Boolean) {
    val scope = rememberCoroutineScope()
    val danger = Yuvomi.colors.danger
    val warning = Yuvomi.colors.warning
    val row = @Composable {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (writable) {
                    // The ring is no state: it reads "done now" on every row (`taskRowHtml`).
                    val completeLabel = t("housekeeping.completeTask", "name" to task.name)
                    IconButton(onClick = { vm.completeTask(task) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = completeLabel }) {
                        Surface(
                            shape = CircleShape,
                            color = if (busy) Yuvomi.colors.success else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(2.dp, if (busy) Yuvomi.colors.success else Yuvomi.colors.border),
                            modifier = Modifier.size(26.dp),
                        ) {
                            if (busy) Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        }
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Column(
                    Modifier.weight(1f)
                        .then(if (writable) Modifier.clickable { vm.show(Overlay.TaskEdit(task)) } else Modifier)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text(task.name, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        if (task.due) {
                            Text(
                                t(HousekeepingRules.urgencyKey(task.urgencyStatus)) + " · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.urgencyStatus == "overdue") danger else warning,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "${task.area} · ${t("housekeeping.everyDays", "days" to task.frequencyDays)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Yuvomi.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
    if (!writable) {
        row()
        return
    }
    val state = rememberSwipeToDismissBoxState()
    run {
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                Row(
                    Modifier.fillMaxSize().background(if (toEnd) Yuvomi.colors.success else Yuvomi.colors.danger).padding(horizontal = 20.dp),
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (toEnd) Icons.Outlined.Check else Icons.Outlined.Delete, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(t(if (toEnd) "tasks.swipeDone" else "common.delete"), color = Color.White)
                }
            },
            onDismiss = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> vm.completeTask(task)
                    SwipeToDismissBoxValue.EndToStart -> vm.deleteTask(task)
                    else -> Unit
                }
                scope.launch { state.reset() }
            },
        ) { row() }
    }
}

// ── Reports (renderReports) ──

private fun LazyListScope.reportsTab(s: HousekeepingViewModel.UiState, vm: HousekeepingViewModel, text: HousekeepingText, writable: Boolean) {
    val report = s.shownReport
    val shownMonth = s.reportMonth ?: report.month ?: s.currentMonth
    val isCurrent = s.reportMonth == null
    item(key = "period") {
        MonthStepper(
            label = text.month(shownMonth),
            onPrev = { vm.stepReportMonth(-1) },
            onNext = { vm.stepReportMonth(1) },
            onCurrent = if (isCurrent) null else ({ vm.showReportMonth(s.currentMonth) }),
        )
    }
    val visits = s.reportVisits
    val loaded = !s.reportLoading
    item(key = "glance") {
        val totals = report.totals
        if (HousekeepingRules.amountsHidden(vm.canSeeAmounts, totals)) {
            Glance(t("housekeeping.reportVisitsCount"), visits.size.toString())
        } else {
            Glance(
                t("housekeeping.pendingPayments"),
                text.money(totals?.pending ?: 0.0),
                flows = listOf(
                    t("housekeeping.reportVisitsCount") to visits.size.toString(),
                    t("housekeeping.paymentPaid") to text.money(totals?.paid ?: 0.0),
                ),
            )
        }
    }
    if (!loaded) {
        item(key = "loading") { Box(Modifier.fillMaxWidth().height(120.dp)) { LoadingBox() } }
        return
    }
    if (visits.isEmpty()) {
        item(key = "none") {
            Muted(if (isCurrent) t("housekeeping.noVisitReports") else t("housekeeping.noVisitReportsInMonth", "month" to text.month(shownMonth)))
        }
    }
    items(visits, key = { "rp-${it.id}" }) { visit ->
        VisitRow(
            visit, vm, text, writable,
            title = text.dayMonth(visit.checkIn),
            visitDate = text.date(visit.checkIn),
            paymentText = t(if (visit.paid) "housekeeping.paymentPaid" else "housekeeping.paymentPending"),
        )
    }
}

@Composable
internal fun MonthStepper(label: String, onPrev: () -> Unit, onNext: () -> Unit, onCurrent: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = t("housekeeping.prevMonth")) }
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = t("housekeeping.nextMonth")) }
        if (onCurrent != null) TextButton(onClick = onCurrent) { Text(t("housekeeping.currentMonth")) }
    }
}

// ── Staff (renderStaff) ──

private fun LazyListScope.staffTab(s: HousekeepingViewModel.UiState, vm: HousekeepingViewModel, text: HousekeepingText, writable: Boolean) {
    val workers = s.data.workers
    if (workers.isEmpty()) item(key = "no-workers") { Muted(t("housekeeping.noWorkers")) }
    items(workers, key = { "sw-${it.id}" }) { worker ->
        val selected = s.selectedStaffId == worker.id
        Surface(
            color = if (selected) Yuvomi.colors.module("housekeeping").copy(alpha = 0.10f) else Color.Transparent,
            modifier = Modifier.fillMaxWidth().clickable { vm.selectStaff(worker.id) },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(worker.displayName, worker.avatarColor, worker.avatarData, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(worker.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    val contact = worker.phone ?: worker.email
                    if (!contact.isNullOrBlank()) Text(contact, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                if (vm.mayManage && !s.offline) {
                    IconButton(onClick = { vm.show(Overlay.WorkerEdit(worker)) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = t("common.editNamed", "name" to worker.displayName))
                    }
                } else {
                    IconButton(onClick = { vm.show(Overlay.WorkerRead(worker)) }) {
                        Icon(Icons.Outlined.Badge, contentDescription = "${t("housekeeping.openWorkerProfile")}: ${worker.displayName}")
                    }
                }
            }
        }
    }
    val worker = workers.firstOrNull { it.id == s.selectedStaffId } ?: return
    item(key = "log-head") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text(t("housekeeping.staffLogTitle", "name" to worker.displayName), style = MaterialTheme.typography.titleMedium)
            Text(t("housekeeping.staffLogHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
    item(key = "log-month") {
        MonthStepper(label = text.month(s.staffMonth), onPrev = { vm.stepStaffMonth(-1) }, onNext = { vm.stepStaffMonth(1) }, onCurrent = null)
    }
    val visits = s.shownStaffVisits
    if (visits.isEmpty()) item(key = "log-none") { Muted(t("housekeeping.noVisitReports")) }
    items(visits, key = { "sv-${it.id}" }) { visit ->
        VisitRow(visit, vm, text, writable, title = text.date(visit.checkIn, weekday = true), visitDate = text.date(visit.checkIn), showWorker = false)
    }
}

// ── The one visit row (visitRowHtml) ──

@Composable
private fun VisitRow(
    visit: Visit,
    vm: HousekeepingViewModel,
    text: HousekeepingText,
    writable: Boolean,
    title: String,
    visitDate: String,
    showWorker: Boolean = true,
    paymentText: String? = null,
) {
    val hidden = HousekeepingRules.amountsHidden(vm.canSeeAmounts, visit.totalAmount)
    val pending = !hidden && !visit.paid
    val meta = listOfNotNull(
        if (showWorker) visit.workerName ?: t("housekeeping.unnamedWorker") else null,
        if (hidden) text.span(visit.checkIn, visit.checkOut) else text.money(visit.totalAmount),
        if (hidden || pending) null else paymentText ?: HousekeepingRules.paymentMetaKeys(visit, vm.readOnly).joinToString(" · ") { vm.text.strings.t(it) },
    ).filter { it.isNotBlank() }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { vm.show(Overlay.VisitReport(visit)) }.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (pending) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = Yuvomi.colors.warning.copy(alpha = 0.15f)) {
                        Text(t("housekeeping.paymentPending"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        if (pending && visit.canMarkPaid && writable) {
            TextButton(onClick = { vm.show(Overlay.ConfirmPay(visit, closesReport = false)) }, modifier = Modifier.semantics {
                contentDescription = vm.text.strings.t("housekeeping.markPaidNamed", "date" to visitDate)
            }) { Text(t("housekeeping.markPaidShort")) }
        }
        if (!vm.readOnly) VisitMenu(visit, vm, visitDate, hidden, writable)
    }
}

@Composable
private fun VisitMenu(visit: Visit, vm: HousekeepingViewModel, visitDate: String, hidden: Boolean, writable: Boolean) {
    val edit = visit.canEdit
    val pay = !visit.paid && visit.canMarkPaid && hidden
    val delete = visit.canDelete
    if (!edit && !pay && !delete) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = writable) {
            Icon(Icons.Outlined.MoreVert, contentDescription = t("common.moreActionsNamed", "name" to visitDate))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (edit) DropdownMenuItem(text = { Text(t("common.edit")) }, onClick = { open = false; vm.show(Overlay.VisitEdit(visit)) })
            if (pay) DropdownMenuItem(text = { Text(t("housekeeping.markPaid")) }, onClick = { open = false; vm.show(Overlay.ConfirmPay(visit, closesReport = false)) })
            if (delete) {
                DropdownMenuItem(text = { Text(t("common.delete"), color = Yuvomi.colors.danger) }, onClick = { open = false; vm.deleteVisit(visit) })
            }
        }
    }
}

// ── Small parts ──

/** `metricGlanceHtml({ everywhere: true })`: the leading figure and up to two side values. */
@Composable
private fun Glance(label: String, value: String, flows: List<Pair<String, String>> = emptyList()) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                flows.take(2).forEach { (l, v) -> Text("$l $v", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Muted(textValue: String) {
    Text(textValue, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}
