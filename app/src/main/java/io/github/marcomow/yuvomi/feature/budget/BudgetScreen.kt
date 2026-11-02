package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.splitexpenses.SplitExpensesRoutes
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsRoutes

/** The web page's seven tabs; Subscriptions and Split expenses are their own screens (`TAB_CAPS`). */
private enum class TabItem(val labelKey: String, val tab: BudgetTab?) {
    BUDGET("budget.budgetTab", BudgetTab.OVERVIEW),
    ACCOUNTS("budget.accountsTab", BudgetTab.ACCOUNTS),
    PLAN("budget.planTab", BudgetTab.PLAN),
    SUBSCRIPTIONS("subscriptions.tabLabel", null),
    LOANS("budget.loansTab", BudgetTab.LOANS),
    REPORTS("budget.reportsTab", BudgetTab.REPORTS),
    SPLIT("splitExpenses.tabLabel", null),
}

/** docs/spec/budget.md: the page frame, its period header, the tabs and the add action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(vm: BudgetViewModel, nav: BudgetNav) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val dates = remember(strings) { BudgetDates(strings.locale) }
    val editable = vm.writable && !s.offline

    // A split guest only ever sees the Split expenses tab (`access_scope === 'split_guest'`).
    if (shell.session.user.accessScope == "split_guest") {
        LaunchedEffect(Unit) { shell.navigate(SplitExpensesRoutes.START) }
        return
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (vm.lastLoaded != 0L && System.currentTimeMillis() - vm.lastLoaded > 60_000) vm.reload()
    }

    // The plan's add travels from the FAB to the plan tab (`openAddPlan`).
    val planAdd = remember { mutableStateOf(false) }
    val add: (() -> Unit)? = if (!editable) null else when (s.tab) {
        BudgetTab.OVERVIEW -> ({ vm.entryRequest = BudgetViewModel.EntryRequest(); nav.entryEditor() })
        BudgetTab.ACCOUNTS -> ({ vm.accountRequest = null; nav.accountEditor() })
        BudgetTab.LOANS -> ({ vm.loanRequest = null; nav.loanEditor() })
        BudgetTab.PLAN -> ({ planAdd.value = true })
        BudgetTab.REPORTS -> null
    }
    val addLabel = when (s.tab) {
        BudgetTab.OVERVIEW -> "budget.newEntryFabLabel"
        BudgetTab.ACCOUNTS -> "budget.addAccount"
        BudgetTab.LOANS -> "budget.newLoan"
        BudgetTab.PLAN -> "budget.planAddBudget"
        BudgetTab.REPORTS -> "budget.newEntryFabLabel"
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(t("budget.title")) }, actions = { AccountButton() })
                PeriodHeader(vm, s, dates)
                if (s.personal) {
                    Segments(
                        listOf("mine" to t("budget.scopeMine"), "household" to t("budget.scopeHousehold")),
                        s.scope,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { vm.setScope(it) }
                }
                val selected = TabItem.entries.indexOfFirst { it.tab == s.tab }.coerceAtLeast(0)
                PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                    TabItem.entries.forEachIndexed { i, item ->
                        Tab(
                            selected = i == selected,
                            onClick = {
                                when (item) {
                                    TabItem.SUBSCRIPTIONS -> shell.navigate(SubscriptionsRoutes.START)
                                    TabItem.SPLIT -> shell.navigate(SplitExpensesRoutes.START)
                                    else -> vm.selectTab(item.tab!!)
                                }
                            },
                            text = { Text(t(item.labelKey), maxLines = 1) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (add != null) FloatingActionButton(onClick = add) { Icon(Icons.Outlined.Add, contentDescription = t(addLabel)) }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> LoadingBox()
                s.loadError -> ErrorBox("${t("budget.loadError")}\n${t("common.loadErrorDescription")}", onRetry = { vm.retry() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.reload(pulled = true) }) {
                    when (s.tab) {
                        BudgetTab.OVERVIEW -> OverviewTab(vm, s, dates, nav, editable)
                        BudgetTab.ACCOUNTS -> AccountsTab(vm, s, dates, nav, editable)
                        BudgetTab.PLAN -> PlanTab(vm, s, editable, planAdd)
                        BudgetTab.LOANS -> LoansTab(vm, s, dates, nav, editable)
                        BudgetTab.REPORTS -> ReportsTab(vm, s, dates, nav, editable)
                    }
                }
            }
        }
    }
}

/**
 * The period head (`monthNavHtml`): back, the period, forward, and "Current" behind it when the
 * current period is not on screen; on tabs without a time axis a quiet note (`TAB_CAPS.note`).
 * The statistics choose week, month or year at the period (`statsRangeHtml`).
 */
@Composable
private fun PeriodHeader(vm: BudgetViewModel, s: BudgetViewModel.UiState, dates: BudgetDates) {
    val strings = LocalStrings.current
    val note = when (s.tab) {
        BudgetTab.ACCOUNTS -> "budget.periodNoteAccounts"
        BudgetTab.LOANS -> "budget.periodNoteLoans"
        else -> null
    }
    if (note != null) {
        Text(
            t(note),
            style = MaterialTheme.typography.bodyMedium,
            color = Yuvomi.colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    val label = if (s.tab == BudgetTab.REPORTS) {
        when (s.range) {
            "year" -> s.anchor.year.toString()
            "month" -> dates.monthYear(s.anchor.toString().take(7))
            else -> s.stats?.takeIf { it.range == "week" && it.from.isNotEmpty() }?.let { dates.range(it.from, it.to) } ?: dates.date(s.anchor.toString())
        }
    } else {
        dates.monthYear(s.month)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.step(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, t("budget.prevMonth")) }
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { vm.step(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, t("budget.nextMonth")) }
        if (!vm.showsCurrent(s)) TextButton(onClick = { vm.jumpToCurrent() }) { Text(t("budget.currentMonth")) }
        if (s.tab == BudgetTab.REPORTS) {
            var open by remember { mutableStateOf(false) }
            val labels = mapOf("week" to "budget.statsRangeWeek", "month" to "budget.statsRangeMonth", "year" to "budget.statsRangeYear")
            TextButton(onClick = { open = true }) {
                Text(strings.t(labels.getValue(s.range)))
                Icon(Icons.Outlined.ArrowDropDown, t("budget.statsRangeLabel"))
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                labels.forEach { (id, key) ->
                    DropdownMenuItem(
                        text = { Text(strings.t(key)) },
                        leadingIcon = { if (id == s.range) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.width(24.dp)) },
                        onClick = { open = false; vm.setRange(id) },
                    )
                }
            }
        }
    }
}
