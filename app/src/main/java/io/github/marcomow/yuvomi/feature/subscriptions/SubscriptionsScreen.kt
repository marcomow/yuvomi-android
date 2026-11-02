package io.github.marcomow.yuvomi.feature.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Collator
import java.time.Instant
import java.time.format.TextStyle

/** docs/spec/subscriptions.md, the list (SUBS-R1 to R16). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubscriptionsScreen(openId: Int?, back: () -> Unit, add: () -> Unit, edit: (Int) -> Unit, manage: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SubscriptionsStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ro = shell.budgetReadOnly()
    var reading by remember { mutableStateOf(openId) }
    var filterSheet by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(s.query) }

    LaunchedEffect(store) {
        // `clearSubscriptionFilters()`: a link to one subscription must not land on a filtered list.
        if (openId != null && s.hasActiveFilters) {
            search = ""
            store.resetFilters()
        } else store.refresh()
    }
    LaunchedEffect(search) {
        delay(250)
        store.setQuery(search)
    }

    val canWrite = !ro && !s.offline
    fun renew(sub: Subscription) = scope.launch { renewWithUndo(shell, strings, store, sub) }
    fun delete(sub: Subscription) = store.scheduleDelete(sub, strings) { m, a -> shell.notify(m, a) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("subscriptions.tabLabel")) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    IconButton(onClick = { filterSheet = true }) {
                        BadgedBox(badge = { if (s.filters.count > 0) Badge { Text(s.filters.count.toString()) } }) {
                            Icon(Icons.Outlined.Tune, if (s.filters.count > 0) strings.t("subscriptions.filtersActive", "count" to s.filters.count) else t("subscriptions.filters"))
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            Text(t("subscriptions.filterLabelSort"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            SubscriptionRules.SORT_OPTIONS.forEach { (id, key) ->
                                DropdownMenuItem(
                                    text = { Text(t(key)) },
                                    leadingIcon = { if (s.sort == id) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
                                    onClick = { menu = false; store.setSort(id) },
                                )
                            }
                            if (!ro) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(t("subscriptions.manageMetadata")) }, leadingIcon = { Icon(Icons.Outlined.Sell, null) }, enabled = !s.offline, onClick = { menu = false; manage() })
                                DropdownMenuItem(text = { Text(t("subscriptions.settingsTitle")) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, enabled = !s.offline, onClick = { menu = false; settingsDialog = true })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = { if (canWrite) FloatingActionButton(onClick = add) { Icon(Icons.Outlined.Add, t("subscriptions.add")) } },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && s.subscriptions.isEmpty() -> LoadingBox()
                s.errorKey != null && s.subscriptions.isEmpty() && !s.loaded -> ErrorBox(strings.t(s.errorKey!!), onRetry = { store.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { store.refresh(pulled = true) }) {
                    ListContent(s, ro, canWrite, search, { search = it }, store, open = { reading = it.id }, renew = ::renew, delete = ::delete, add = add)
                }
            }
        }
    }

    reading?.let { id ->
        val sub = s.subscription(id)
        if (sub == null) {
            if (s.loaded) reading = null
        } else {
            ReadSheet(sub, s, editable = canWrite, close = { reading = null }, renew = { reading = null; renew(sub) }, edit = { reading = null; edit(sub.id) })
        }
    }
    if (filterSheet) FilterSheet(s, store) { filterSheet = false }
    if (settingsDialog) SettingsDialog(s, store) { settingsDialog = false }
}

/** `renewSubscription()`: no question first; the snackbar's Undo takes the payment back on the server. */
internal suspend fun renewWithUndo(shell: ShellContext, strings: Strings, store: SubscriptionsStore, sub: Subscription) {
    if (sub.isCompleted) return
    val r = store.renew(sub)
    if (r.errorKey != null) {
        shell.notify(strings.t(r.errorKey))
        return
    }
    val message = if (r.completed) strings.t("subscriptions.completedNamed", "name" to sub.name)
    else strings.t("subscriptions.renewedNamed", "name" to sub.name, "date" to shell.dateText(r.nextDate, weekday = true))
    if (r.token == null) {
        shell.notify(message)
        return
    }
    if (shell.notify(message, strings.t("common.undo"))) {
        store.undoRenew(sub.id, r.token)?.let { shell.notify(strings.t(it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ListContent(
    s: SubscriptionsStore.State,
    ro: Boolean,
    canWrite: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    store: SubscriptionsStore,
    open: (Subscription) -> Unit,
    renew: (Subscription) -> Unit,
    delete: (Subscription) -> Unit,
    add: () -> Unit,
) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val collator = remember(strings.locale) { Collator.getInstance(BudgetMoney.locale(strings.locale)) }
    val rows = SubscriptionRules.sorted(s.shown, s.sort, collator)
    val base = s.baseCurrency
    val filters = SubscriptionRules.activeFilters(strings, s.filters, s.meta)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (s.offline) item { OfflineBanner(s.cachedAt?.let { shell.formats.dateTime(Instant.ofEpochMilli(it).atZone(shell.time.zone)) }) }
        item { SummaryCard(s) }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                placeholder = { Text(t("subscriptions.searchPlaceholder")) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (filters.isNotEmpty()) item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    val label = strings.t("subscriptions.filterChip", "field" to f.field, "value" to f.value)
                    InputChip(
                        selected = true,
                        onClick = {
                            store.setFilters(
                                when (f.key) {
                                    "category" -> s.filters.copy(categoryId = null)
                                    "method" -> s.filters.copy(paymentMethodId = null)
                                    else -> s.filters.copy(status = "all")
                                },
                            )
                        },
                        label = { Text(label) },
                        trailingIcon = { Icon(Icons.Outlined.Close, strings.t("subscriptions.removeFilter", "label" to label), Modifier.size(16.dp)) },
                    )
                }
                if (filters.size > 1) TextButton(onClick = { onSearch(""); store.resetFilters() }) { Text(t("subscriptions.resetFilters")) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("subscriptions.listTitle"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("  ${rows.size}", style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                Spacer(Modifier.weight(1f))
                if (SubscriptionRules.hasForeignCurrency(rows, base)) {
                    if (s.rates?.source == "unavailable") Text(t("subscriptions.ratesUnavailable"), color = Yuvomi.colors.warning, style = MaterialTheme.typography.labelMedium)
                    else TextButton(onClick = { store.refresh(refreshRates = true) }) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("subscriptions.refreshRates"))
                    }
                }
            }
        }
        SubscriptionRules.overBudgetPercent(s.summary)?.let { pct ->
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${BudgetMoney.percentValue(pct, strings.locale)} ${strings.t("subscriptions.budgetUsed")}", color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { store.setSort(SubscriptionRules.SORT_COST_DESC) }) { Text(t("subscriptions.overBudgetAction")) }
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                if (s.hasActiveFilters) {
                    EmptyState(
                        t("common.searchNoResultsTitle"),
                        t(if (filters.isNotEmpty()) "common.searchNoResultsFilteredDescription" else "common.searchNoResultsDescription"),
                        t("common.searchReset"),
                    ) { onSearch(""); store.resetFilters() }
                } else {
                    EmptyState(
                        t("subscriptions.emptyTitle"),
                        if (ro) null else t("subscriptions.emptyDescription"),
                        if (canWrite) t("subscriptions.add") else null,
                        if (canWrite) add else null,
                    )
                }
            }
        }
        items(rows, key = { it.id }) { sub ->
            SubscriptionRow(sub, base, writable = canWrite, open = { open(sub) }, renew = { renew(sub) }, delete = { delete(sub) })
        }
        item { Analytics(s) }
    }
}

@Composable
private fun SummaryCard(s: SubscriptionsStore.State) {
    val strings = LocalStrings.current
    val summary = s.summary ?: Summary(baseCurrency = s.settings.baseCurrency)
    val base = s.baseCurrency
    fun money(v: Double) = BudgetMoney.format(v, base, strings.locale)
    val budget = summary.monthlyBudget
    val over = budget > 0 && summary.remainingBudget < 0
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t("subscriptions.monthlyCost"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            Text(money(summary.monthlyTotal), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (budget > 0) {
                LinearProgressIndicator(
                    progress = { (summary.monthlyTotal / budget).toFloat().coerceIn(0f, 1f) },
                    color = if (over) Yuvomi.colors.danger else if (summary.monthlyTotal / budget >= 0.85) Yuvomi.colors.warning else Yuvomi.colors.module("budget"),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row {
                    Text(t(if (over) "subscriptions.overBudget" else "subscriptions.remainingBudget"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(money(kotlin.math.abs(summary.remainingBudget)), style = MaterialTheme.typography.bodySmall, color = if (over) Yuvomi.colors.danger else Color.Unspecified)
                }
            }
            Row {
                Text(t("subscriptions.yearlyProjection"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(money(summary.monthlyTotal * 12), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionRow(sub: Subscription, base: String, writable: Boolean, open: () -> Unit, renew: () -> Unit, delete: () -> Unit) {
    val strings = LocalStrings.current
    val canRenew = writable && !sub.isCompleted
    val content: @Composable () -> Unit = { RowBody(sub, base, writable, canRenew, open, renew, delete) }
    if (!writable) {
        content()
        return
    }
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = canRenew,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) delete()
            if (value == SwipeToDismissBoxValue.StartToEnd) renew()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val dir = state.dismissDirection
            if (dir == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val renewing = dir == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (renewing) Yuvomi.colors.success else Yuvomi.colors.danger).padding(horizontal = 20.dp),
                contentAlignment = if (renewing) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (renewing) Icons.Outlined.EventAvailable else Icons.Outlined.Delete, null, tint = Color.White)
                    Text(strings.t(if (renewing) "subscriptions.markRenewed" else "common.delete"), color = Color.White, modifier = Modifier.padding(start = 6.dp))
                }
            }
        },
    ) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowBody(sub: Subscription, base: String, writable: Boolean, canRenew: Boolean, open: () -> Unit, renew: () -> Unit, delete: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val today = shell.time.today()
    val color = parseHex(sub.brandColor ?: sub.categoryColor ?: SubscriptionRules.DEFAULT_COLOR, Yuvomi.colors.module("budget"))
    val days = SubscriptionRules.daysUntil(sub.nextPaymentDate, today)
    val overdue = !sub.isCompleted && (days ?: 0) < 0
    val sameAsMonthly = sub.billingCycle == "monthly" && sub.cycleInterval == 1 && sub.currency == base
    val converted = when {
        sub.monthlyBase == null -> strings.t("subscriptions.conversionUnavailable")
        sameAsMonthly -> null
        else -> strings.t("subscriptions.monthlyEquivalent", "amount" to BudgetMoney.format(sub.monthlyBase, base, strings.locale))
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(sub.name, sub.logoData, color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (sub.effectiveStatus != Subscription.STATUS_ACTIVE) {
                    Spacer(Modifier.width(6.dp))
                    StatusBadge(strings.t(SubscriptionRules.statusLabelKey(sub)))
                }
            }
            Text(sub.description?.takeIf { it.isNotBlank() } ?: SubscriptionRules.rowCategoryLabel(strings, sub), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val meta = MaterialTheme.typography.labelSmall
                if (!sub.isCompleted && days != null) {
                    Text("${shell.dateText(sub.nextPaymentDate)} · ${SubscriptionRules.dueLabel(strings, days) { v, u -> relativeText(strings.locale, v, u) }}", style = meta, color = if (overdue) Yuvomi.colors.danger else Yuvomi.colors.textSecondary)
                }
                Text(SubscriptionRules.cycleLabel(strings, sub), style = meta, color = Yuvomi.colors.textSecondary)
                if (!sub.isCompleted && SubscriptionRules.reminderDiffers(sub)) {
                    Text(if (!sub.reminderEnabled) strings.t("subscriptions.noReminder") else strings.t("subscriptions.reminderMeta", "count" to sub.reminderDays), style = meta, color = Yuvomi.colors.textSecondary)
                }
                endText(shell, strings, sub)?.let { Text(it, style = meta, color = Yuvomi.colors.textSecondary) }
                if (sub.accountArchived && !sub.isCompleted) Text(strings.t("subscriptions.accountArchivedMeta", "name" to sub.accountName.orEmpty()), style = meta, color = Yuvomi.colors.textSecondary)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(BudgetMoney.format(sub.amount, sub.currency, strings.locale), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            converted?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary) }
        }
        if (writable) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to sub.name)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (canRenew) DropdownMenuItem(text = { Text(t("subscriptions.markRenewed")) }, leadingIcon = { Icon(Icons.Outlined.EventAvailable, null) }, onClick = { menu = false; renew() })
                    DropdownMenuItem(text = { Text(t("common.delete"), color = Yuvomi.colors.danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) }, onClick = { menu = false; delete() })
                }
            }
        }
    }
}

@Composable
internal fun StatusBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Yuvomi.colors.textSecondary,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun endText(shell: ShellContext, strings: Strings, sub: Subscription): String? = when (val e = SubscriptionRules.endInfo(sub)) {
    is SubscriptionRules.EndInfo.Completed -> e.at?.let { at ->
        val d = shell.time.parse(at)?.toLocalDate()?.toString()
        strings.t("subscriptions.completedOn", "date" to shell.dateText(d ?: at))
    } ?: strings.t("subscriptions.completed")
    is SubscriptionRules.EndInfo.OnDate -> strings.t("subscriptions.endsOn", "date" to shell.dateText(e.date))
    is SubscriptionRules.EndInfo.AfterCount -> strings.t("subscriptions.endsAfter", "count" to e.remaining)
    null -> null
}

/** The forecast and the two breakdowns (`renderAnalytics()`). */
@Composable
private fun Analytics(s: SubscriptionsStore.State) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val base = s.baseCurrency
    fun money(v: Double) = BudgetMoney.format(v, base, strings.locale)
    val forecast = SubscriptionRules.forecast(s.shown, shell.time.today(), base)
    val loc = BudgetMoney.locale(strings.locale)
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionTitle(t("subscriptions.renewalForecast"))
        SubscriptionRules.peak(forecast)?.let { peak ->
            Row {
                Text(strings.t("subscriptions.forecastPeak", "month" to peak.month.month.getDisplayName(TextStyle.SHORT, loc)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(money(peak.amount), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
        val max = (forecast.maxOfOrNull { it.amount } ?: 0.0).coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            forecast.forEach { m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height((64 * (m.amount / max)).dp.coerceAtLeast(2.dp)).background(Yuvomi.colors.module("budget"), RoundedCornerShape(4.dp)))
                    Text(m.month.month.getDisplayName(TextStyle.SHORT, loc), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                }
            }
        }
        Breakdowns(t("subscriptions.byCategory"), s.summary?.byCategory.orEmpty(), "subscriptions.uncategorized", ::money)
        Breakdowns(t("subscriptions.byPaymentMethod"), s.summary?.byPaymentMethod.orEmpty(), "subscriptions.unspecified", ::money)
    }
}

@Composable
private fun Breakdowns(title: String, raw: List<Breakdown>, emptyKey: String, money: (Double) -> String) {
    val strings = LocalStrings.current
    val rows = SubscriptionRules.breakdownRows(raw)
    SectionTitle(title)
    if (rows.isEmpty()) {
        Text(t("subscriptions.noAnalytics"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        return
    }
    val max = rows.maxOf { it.amount }.coerceAtLeast(1.0)
    val total = rows.sumOf { it.amount }.takeIf { it > 0 } ?: 1.0
    rows.forEach { row ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Row {
                Text(SubscriptionRules.breakdownLabel(strings, row, emptyKey), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(money(row.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("  " + BudgetMoney.percent(row.amount / total, strings.locale), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            LinearProgressIndicator(progress = { (row.amount / max).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Yuvomi.colors.module("budget"))
        }
    }
}

/** `openSubscriptionReadView()`: the subscription as text; Mark as renewed and Edit in the footer when writable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadSheet(sub: Subscription, s: SubscriptionsStore.State, editable: Boolean, close: () -> Unit, renew: () -> Unit, edit: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val base = s.baseCurrency
    val today = shell.time.today()
    val converted = if (sub.monthlyBase == null) strings.t("subscriptions.conversionUnavailable")
    else strings.t("subscriptions.monthlyEquivalent", "amount" to BudgetMoney.format(sub.monthlyBase, base, strings.locale))
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandTile(sub.name, sub.logoData, parseHex(sub.brandColor ?: sub.categoryColor, Yuvomi.colors.module("budget")))
                Spacer(Modifier.width(12.dp))
                Text(sub.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(8.dp))
            DetailRow(t("subscriptions.detailAmountLabel"), "${BudgetMoney.format(sub.amount, sub.currency, strings.locale)} · $converted")
            DetailRow(t("subscriptions.filterLabelStatus"), strings.t(SubscriptionRules.statusLabelKey(sub)))
            DetailRow(t("subscriptions.descriptionLabel"), sub.description)
            DetailRow(t("subscriptions.billingCycleLabel"), SubscriptionRules.cycleLabel(strings, sub))
            val days = SubscriptionRules.daysUntil(sub.nextPaymentDate, today)
            if (!sub.isCompleted && days != null) {
                DetailRow(t("subscriptions.detailNextPaymentLabel"), "${shell.dateText(sub.nextPaymentDate, weekday = true)} · ${SubscriptionRules.dueLabel(strings, days) { v, u -> relativeText(strings.locale, v, u) }}")
                DetailRow(t("subscriptions.reminderDaysLabel"), if (!sub.reminderEnabled) strings.t("subscriptions.noReminder") else strings.t("subscriptions.reminderMeta", "count" to sub.reminderDays))
            }
            DetailRow(t("subscriptions.endLabel"), endText(shell, strings, sub))
            if (sub.categoryId != null) DetailRow(t("subscriptions.categoryLabel"), SubscriptionRules.rowCategoryLabel(strings, sub))
            if (sub.paymentMethodId != null) DetailRow(t("subscriptions.paymentMethodLabel"), SubscriptionRules.rowMethodLabel(strings, sub))
            if (sub.accountId != null) DetailRow(t("budget.accountLabel"), if (sub.accountArchived) strings.t("subscriptions.accountArchivedMeta", "name" to sub.accountName.orEmpty()) else sub.accountName)
            DetailRow(t("subscriptions.accountUsernameLabel"), sub.accountUsername)
            DetailRow(t("subscriptions.notesLabel"), sub.notes)
            if (editable) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (!sub.isCompleted) OutlinedButton(onClick = renew) { Text(t("subscriptions.markRenewed")) }
                    Button(onClick = edit) { Text(t("common.edit")) }
                }
            }
        }
    }
}

/** `openFilterSheet()`: a view sheet, every choice applies at once; reading is allowed at `budget: read`. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(s: SubscriptionsStore.State, store: SubscriptionsStore, close: () -> Unit) {
    val strings = LocalStrings.current
    val f = s.filters
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(t("subscriptions.filters"), style = MaterialTheme.typography.titleLarge)
            Text(t("subscriptions.filterLabelCategory"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(f.categoryId == null, { store.setFilters(f.copy(categoryId = null)) }, { Text(t("common.all")) })
                s.meta.categories.forEach { c -> FilterChip(f.categoryId == c.id, { store.setFilters(f.copy(categoryId = c.id)) }, { Text(SubscriptionRules.categoryLabel(strings, c)) }) }
            }
            Text(t("subscriptions.filterLabelMethod"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(f.paymentMethodId == null, { store.setFilters(f.copy(paymentMethodId = null)) }, { Text(t("common.all")) })
                s.meta.paymentMethods.forEach { m -> FilterChip(f.paymentMethodId == m.id, { store.setFilters(f.copy(paymentMethodId = m.id)) }, { Text(SubscriptionRules.methodLabel(strings, m)) }) }
            }
            Text(t("subscriptions.filterLabelStatus"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionRules.STATUS_OPTIONS.forEach { (id, key) -> FilterChip(f.status == id, { store.setFilters(f.copy(status = id)) }, { Text(t(key)) }) }
            }
            OutlinedButton(onClick = { store.setFilters(SubscriptionRules.Filters()) }, modifier = Modifier.padding(top = 16.dp)) { Text(t("subscriptions.resetFilters")) }
        }
    }
}

/** `openSettingsModal()`: the monthly budget and the base currency. */
@Composable
private fun SettingsDialog(s: SubscriptionsStore.State, store: SubscriptionsStore, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var currency by remember { mutableStateOf(s.settings.baseCurrency) }
    var budget by remember { mutableStateOf(BudgetMoney.toInput(s.settings.monthlyBudget, s.settings.baseCurrency, strings.locale)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("subscriptions.settingsTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(budget, { budget = it; error = null }, label = { Text(t("subscriptions.monthlyBudgetLabel")) }, isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = true)
                ChoiceField(t("subscriptions.baseCurrencyLabel"), currency, BudgetMoney.CURRENCY_CODES.map { it to it }) { currency = it }
                Text(t("subscriptions.fixerHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val problem = BudgetMoney.problem(budget, currency, strings.locale, nonNegative = true, original = s.settings.monthlyBudget, originalCurrency = s.settings.baseCurrency)
                if (problem != null) {
                    error = BudgetMoney.problemText(strings, problem, currency)
                    return@TextButton
                }
                busy = true
                scope.launch {
                    val key = store.saveSettings(BudgetMoney.parse(budget, strings.locale) ?: 0.0, currency)
                    busy = false
                    if (key == null) {
                        close()
                        shell.notify(strings.t("subscriptions.settingsSaved"))
                    } else shell.notify(strings.t(key))
                }
            }) { Text(t("common.save")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}
