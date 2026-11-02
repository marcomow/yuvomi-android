package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SubdirectoryArrowLeft
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.marcomow.yuvomi.core.design.AvatarStack
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.subscriptions.SubscriptionsRoutes
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs

/** The overview tab (`renderBody`, tab `budget`): the month's balance, its categories and its ledger. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewTab(vm: BudgetViewModel, s: BudgetViewModel.UiState, dates: BudgetDates, nav: BudgetNav, editable: Boolean) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    var reading by remember { mutableStateOf<BudgetEntry?>(null) }
    var confirming by remember { mutableStateOf<BudgetEntry?>(null) }
    val exporter = rememberCsvExport(vm)
    val sm = s.summary
    val monthEmpty = s.shownEntries.isEmpty() && sm.income == 0.0 && sm.expenses == 0.0 && (sm.pending?.count ?: 0) == 0

    val open: (BudgetEntry) -> Unit = { e ->
        when {
            e.fromSubscription -> shell.navigate(SubscriptionsRoutes.START)
            e.detailsHidden -> Unit
            else -> reading = e
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (s.offline) item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
        if (!monthEmpty) {
            item(key = "summary") { SummaryCard(vm, s, dates) }
            val pending = sm.pending
            if (pending != null && pending.count > 0) {
                item(key = "pending") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = Yuvomi.colors.textSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t("budget.pendingSummary", "count" to pending.count, "amount" to s.money.signed(pending.income + pending.expenses, s.viewCurrency, Money.Role.FLOW)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Yuvomi.colors.textSecondary,
                        )
                    }
                }
            }
            if (sm.byCategory.isNotEmpty()) item(key = "chart") { CategoryChart(vm, s, strings) }
        }
        item(key = "ledger-head") { LedgerHeader(vm, s, nav, editable, exporter) }
        subcategoryBreakdown(vm, s, strings)
        item(key = "status") {
            val status = ledgerStatus(s, strings)
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        ledger(vm, s, dates, strings, editable, open, onConfirm = { confirming = it }, onAdd = {
            vm.entryRequest = BudgetViewModel.EntryRequest()
            nav.entryEditor()
        })
    }

    reading?.let { e ->
        EntryReadSheet(
            entry = e,
            s = s,
            dates = dates,
            vm = vm,
            editable = editable && !e.detailsHidden,
            onEdit = {
                reading = null
                if (e.isTransfer) {
                    vm.transferRequest = e
                    nav.transferEditor()
                } else {
                    vm.entryRequest = BudgetViewModel.EntryRequest(entry = e)
                    nav.entryEditor()
                }
            },
            onDismiss = { reading = null },
        )
    }
    confirming?.let { e -> ConfirmBookingDialog(vm, s, e) { confirming = null } }
}

/** `renderTrend`: the change against the previous month, neutral in colour, with its direction. */
@Composable
private fun Trend(current: Double, prev: Double, prevLabel: String, s: BudgetViewModel.UiState) {
    val delta = s.money.round(current - prev, s.viewCurrency)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (delta != 0.0) {
            Icon(if (delta > 0) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, null, Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (delta == 0.0) t("budget.trendNeutral", "month" to prevLabel)
            else t("budget.trendDelta", "amount" to s.money.signed(delta, s.viewCurrency, Money.Role.FLOW), "month" to prevLabel),
            style = MaterialTheme.typography.labelSmall,
            color = Yuvomi.colors.textSecondary,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier = Modifier, lead: Boolean = false, trend: @Composable (() -> Unit)? = null) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Text(value, style = if (lead) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
        trend?.invoke()
    }
}

/** The balance: income, expenses and the leading balance, or expenses alone (#504). */
@Composable
private fun SummaryCard(vm: BudgetViewModel, s: BudgetViewModel.UiState, dates: BudgetDates) {
    val sm = s.summary
    val p = s.previous
    val forecast = BudgetLedger.forecast(s.month, s.today)
    // Pure expense tracking has no real balance: then it stays neutral and loses its trend (#504).
    val balanceNeutral = sm.income == 0.0 && sm.balance < 0
    val balanceColor = when {
        forecast || balanceNeutral -> MaterialTheme.colorScheme.onSurface
        sm.balance >= 0 -> Yuvomi.colors.success
        else -> Yuvomi.colors.danger
    }
    val prevLabel = p?.month?.takeIf { it.length == 7 }?.let { dates.monthShortName(it) }.orEmpty()
    val c = s.viewCurrency
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(if (forecast) "budget.summaryTitleForecast" else "budget.summaryTitle"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilterChip(selected = s.expensesOnly, onClick = { vm.toggleExpensesOnly() }, label = { Text(t("budget.expensesOnly")) })
            }
            if (s.expensesOnly) {
                Metric(t("budget.expenses"), s.money.signed(sm.expenses, c, Money.Role.TOTAL), MaterialTheme.colorScheme.onSurface, lead = true) {
                    if (p != null) Trend(abs(sm.expenses), abs(p.expenses), prevLabel, s)
                }
            } else {
                Metric(t("budget.balance"), s.money.signed(sm.balance, c, Money.Role.BALANCE), balanceColor, lead = true) {
                    if (p != null && !balanceNeutral) Trend(sm.balance, p.balance, prevLabel, s)
                }
                Row {
                    Metric(t("budget.income"), s.money.signed(sm.income, c, Money.Role.TOTAL), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) {
                        if (p != null) Trend(sm.income, p.income, prevLabel, s)
                    }
                    Metric(t("budget.expenses"), s.money.signed(sm.expenses, c, Money.Role.TOTAL), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) {
                        if (p != null) Trend(abs(sm.expenses), abs(p.expenses), prevLabel, s)
                    }
                }
            }
            TransferSummary(s)
        }
    }
}

/** `transferSummaryHtml`: one number unfiltered, incoming and outgoing on one account (#2207). */
@Composable
private fun TransferSummary(s: BudgetViewModel.UiState) {
    val sm = s.summary
    val out = sm.transfers
    val c = s.viewCurrency
    if (s.filters.account != null && sm.transfersIn != null) {
        if (sm.transfersIn <= 0 && out <= 0) return
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(t("budget.summaryTransfers"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Row {
            Metric(t("budget.summaryTransfersIn"), s.money.signed(sm.transfersIn, c, Money.Role.FLOW), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Metric(t("budget.summaryTransfersOut"), s.money.signed(-out, c, Money.Role.FLOW), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
    } else if (out > 0) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Metric(t("budget.summaryTransfers"), s.money.signed(out, c, Money.Role.TOTAL), MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The category chart (`renderCategoryBars`): expenses, income and transfers as blocks, each on
 * its own scale; collapsed, the three largest of the leading block. A tap filters the ledger.
 */
@Composable
private fun CategoryChart(vm: BudgetViewModel, s: BudgetViewModel.UiState, strings: Strings) {
    val blocks = BudgetLedger.blocks(s.summary.byCategory)
    val colors = BudgetLedger.colorIndex(s.summary.byCategory, s.expenseCategoryKeys)
    val c = s.viewCurrency
    val expanded = s.categoriesExpanded
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("budget.byCategory"), style = MaterialTheme.typography.titleSmall)
                    // `chartIncomeLine`: the collapsed income block as one line under the title.
                    if (!expanded && blocks.lead == "expenses" && blocks.income.isNotEmpty()) {
                        Text(
                            "${t("budget.income")}  ${s.money.signed(BudgetLedger.total(blocks.income), c, Money.Role.FLOW)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Yuvomi.colors.textSecondary,
                        )
                    }
                }
                if (blocks.hasMore) {
                    TextButton(onClick = { vm.toggleCategoriesExpanded() }) {
                        Text(if (expanded) t("budget.showFewerCategories") else t("budget.showAllCategories", "count" to s.summary.byCategory.size))
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                }
            }
            val kinds = BudgetLedger.KINDS.filter { blocks.of(it).isNotEmpty() && (expanded || it == blocks.lead) }
            kinds.forEach { kind ->
                val rows = blocks.of(kind).let { if (!expanded && kind == blocks.lead) it.take(BudgetLedger.CHART_LEAD) else it }
                val all = blocks.of(kind)
                val max = maxOf(all.maxOf { abs(it.amount) }, 1.0)
                val total = BudgetLedger.total(all)
                val labelKey = when (kind) {
                    "expenses" -> "budget.expenses"
                    "income" -> "budget.income"
                    else -> "budget.summaryTransfers"
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(t(labelKey), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(blockAmount(total, kind, s), style = MaterialTheme.typography.labelLarge)
                }
                rows.forEach { r ->
                    val fill = when (kind) {
                        "income" -> Yuvomi.colors.success
                        "transfers" -> Yuvomi.colors.module("budget")
                        else -> colors[r.category]?.let { seriesColor(it) } ?: Yuvomi.colors.module("budget")
                    }
                    BarRow(
                        label = BudgetText.category(r.category, s.meta, strings),
                        fraction = (abs(r.amount) / max).toFloat(),
                        fill = fill,
                        amount = blockAmount(r.amount, kind, s),
                        share = if (kind == "expenses") s.money.share(r.amount, total) else null,
                        selected = s.filters.category == r.category,
                        onClick = { vm.toggleCategory(r.category) },
                    )
                }
            }
        }
    }
}

/** `blockAmountText`: no minus under "Expenses"; transfers keep theirs. */
private fun blockAmount(amount: Double, kind: String, s: BudgetViewModel.UiState): String =
    s.money.signed(amount, s.viewCurrency, if (kind == "transfers") Money.Role.FLOW else Money.Role.TOTAL)

@Composable
fun BarRow(label: String, fraction: Float, fill: Color, amount: String, share: String?, selected: Boolean = false, target: Float? = null, onClick: (() -> Unit)? = null, below: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .background(if (selected) Yuvomi.colors.module("budget").copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { if (selected) contentDescription = "$label ✓" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(amount, style = MaterialTheme.typography.bodyMedium)
            if (share != null) Text("  $share", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(fill))
            if (target != null) {
                Box(Modifier.fillMaxWidth(target.coerceIn(0f, 1f)).height(8.dp)) {
                    Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(8.dp).background(MaterialTheme.colorScheme.onSurface))
                }
            }
        }
        below?.invoke()
    }
}

/** The ledger's head: title, the active filter chips, the search over all months and the tools menu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerHeader(vm: BudgetViewModel, s: BudgetViewModel.UiState, nav: BudgetNav, editable: Boolean, exporter: (String, List<Pair<String, String>>) -> Unit) {
    val strings = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("budget.transactions"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    Text(t("budget.ledgerSortLabel"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    listOf("date" to "budget.ledgerSortDate", "amount" to "budget.ledgerSortAmount").forEach { (id, key) ->
                        MenuCheck(strings.t(key), s.ledgerSort == id) { menu = false; vm.setLedgerSort(id) }
                    }
                    val accounts = s.accounts.accounts.filter { !it.archived || it.id == s.filters.account }
                    if (accounts.isNotEmpty()) {
                        HorizontalDivider()
                        Text(t("budget.accountLabel"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        MenuCheck(t("budget.ledgerAccountsAll"), s.filters.account == null) { menu = false; vm.setAccountFilter(null) }
                        accounts.forEach { a -> MenuCheck(a.name, s.filters.account == a.id) { menu = false; vm.setAccountFilter(a.id) } }
                    }
                    if (s.entries.any { it.responsibleUsers.isNotEmpty() }) {
                        HorizontalDivider()
                        MenuCheck(t("budget.groupByResponsible"), s.groupByResponsible) { menu = false; vm.toggleGroupByResponsible() }
                    }
                    if (editable) {
                        DropdownMenuItem(text = { Text(t("budget.manageCategories")) }, leadingIcon = { Icon(Icons.Outlined.Sell, null) }, onClick = { menu = false; nav.categories() })
                    }
                    if (s.entries.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(t("budget.csvExport")) },
                            leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            onClick = { menu = false; exporter("budget-${s.month}.csv", vm.monthExportQuery(s)) },
                        )
                    }
                }
            }
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.filters.account?.let { id ->
                InputChip(
                    selected = true,
                    onClick = { vm.clearAccountFilter() },
                    label = { Text(s.accountName(id)) },
                    leadingIcon = { Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, t("budget.clearAccountFilter"), Modifier.size(16.dp)) },
                )
                // `statementCreditLimitHtml`: the starting balance, and the credit limit of a card.
                s.accounts.accounts.firstOrNull { it.id == id }?.let { a ->
                    val cur = a.currency ?: s.currency
                    Text("${t("budget.startingBalanceShort")} ${s.money.format(a.startingBalance, cur)}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                    if (a.type == "credit" && a.creditLimit != null) {
                        Text("${t("budget.creditLimitLabel")} ${s.money.format(a.creditLimit, cur)}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
            s.filters.responsible?.let {
                InputChip(
                    selected = true,
                    onClick = { vm.toggleResponsible(BudgetPerson(it)) },
                    label = { Text(s.members.firstOrNull { m -> m.id == it }?.displayName ?: s.responsibleName) },
                    leadingIcon = { Icon(Icons.Outlined.Person, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, t("budget.clearResponsibleFilter"), Modifier.size(16.dp)) },
                )
            }
            s.filters.category?.let { cat ->
                val label = categoryFilterLabel(s, strings)
                InputChip(
                    selected = true,
                    onClick = { s.filters.subcategory?.let { (c, k) -> vm.toggleSubcategory(c, k) } ?: vm.toggleCategory(cat) },
                    label = { Text(label) },
                    leadingIcon = { Icon(Icons.Outlined.Label, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, t("budget.clearCategoryFilter", "name" to label), Modifier.size(16.dp)) },
                )
            }
            if (s.ledgerSort != "date") {
                AssistChip(onClick = { menu = true }, label = { Text(t("budget.ledgerSortActive", "sort" to strings.t("budget.ledgerSortAmount"))) })
            }
        }
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            placeholder = { Text(t("budget.ledgerSearchPlaceholder")) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (s.query.isNotEmpty()) ({ IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, t("common.searchClear")) } }) else null,
            singleLine = true,
            label = { Text(t("budget.ledgerSearchLabel")) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun MenuCheck(label: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (on) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.width(24.dp)) },
        onClick = onClick,
    )
}

/** `categoryFilterLabel`: "Food › Groceries", or the category alone. */
private fun categoryFilterLabel(s: BudgetViewModel.UiState, strings: Strings): String {
    val cat = s.filters.category ?: return ""
    val sub = s.filters.subcategory ?: return BudgetText.category(cat, s.meta, strings)
    val subLabel = if (sub.second.isEmpty()) strings.t("budget.withoutSubcategory") else BudgetText.subcategory(sub.second, s.meta, strings)
    return "${BudgetText.category(cat, s.meta, strings)} › $subLabel"
}

/** `ledgerStatusText`: the hit count of a search, or the count and sum of a filtered list. */
fun ledgerStatus(s: BudgetViewModel.UiState, strings: Strings): String {
    val results = s.shownResults
    if (s.query.isNotBlank() && results != null && !s.searchError) {
        if (results.isEmpty()) return ""
        return if (s.truncated) strings.t("budget.ledgerSearchTruncated", "limit" to results.size)
        else strings.t("budget.ledgerSearchCount", "count" to results.size)
    }
    if (!s.filters.any) return ""
    val entries = BudgetLedger.visible(s.shownEntries, s.filters)
    return strings.t("budget.ledgerFilterSummary", "count" to entries.size, "sum" to s.money.format(BudgetLedger.bookedSum(entries), s.viewCurrency))
}

/** `renderSubcategoryBreakdowns`: the filtered category by subcategory. */
private fun LazyListScope.subcategoryBreakdown(vm: BudgetViewModel, s: BudgetViewModel.UiState, strings: Strings) {
    val category = s.filters.category ?: return
    val entries = BudgetLedger.visible(s.shownEntries, s.filters, ignoreSubcategory = true)
    val known = s.subcategories(category).map { it.key }
    val rows = BudgetLedger.subcategoryRows(category, entries, known)
    if (rows.isEmpty()) return
    val kind = if (s.meta.expenseCategories.any { it.key == category }) "expenses" else "income"
    val total = entries.filter { it.category == category }.sumOf { if (it.isPending) 0.0 else it.amount }
    val categoryTotal = rows.sumOf { abs(it.amount) }
    item(key = "sub-head") {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(BudgetText.category(category, s.meta, strings), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(s.money.signed(total, s.viewCurrency, Money.Role.TOTAL), style = MaterialTheme.typography.labelLarge)
        }
    }
    rows.forEach { row ->
        item(key = "sub-${row.key}") {
            val colors = BudgetLedger.colorIndex(s.summary.byCategory, s.expenseCategoryKeys)
            BarRow(
                label = if (row.key.isEmpty()) strings.t("budget.withoutSubcategory") else BudgetText.subcategory(row.key, s.meta, strings),
                fraction = (abs(row.amount) / (if (categoryTotal == 0.0) 1.0 else categoryTotal)).toFloat(),
                fill = if (kind == "expenses") colors[category]?.let { seriesColor(it) } ?: Yuvomi.colors.module("budget") else Yuvomi.colors.success,
                amount = s.money.signed(row.amount, s.viewCurrency, Money.Role.FLOW),
                share = if (kind == "expenses" && row.amount != 0.0) s.money.share(row.amount, categoryTotal) else null,
                selected = s.filters.subcategory == (category to row.key),
                onClick = { vm.toggleSubcategory(category, row.key) },
            )
        }
    }
}

/** `renderEntries`: search hits, the empty month, the empty filter, the groups, or planned and booked. */
private fun LazyListScope.ledger(
    vm: BudgetViewModel,
    s: BudgetViewModel.UiState,
    dates: BudgetDates,
    strings: Strings,
    editable: Boolean,
    open: (BudgetEntry) -> Unit,
    onConfirm: (BudgetEntry) -> Unit,
    onAdd: () -> Unit,
) {
    val rowFor: @Composable (BudgetEntry, Boolean) -> Unit = { e, fullDate ->
        EntryRow(e, s, dates, strings, editable, fullDate, onClick = { open(e) }, onConfirm = { onConfirm(e) }, onResponsible = { vm.toggleResponsible(it) })
    }
    val results = s.shownResults
    if (s.query.isNotBlank() && results != null) {
        when {
            s.searchError -> item(key = "search-error") { EmptyState(strings.t("budget.ledgerSearchError")) }
            results.isEmpty() -> item(key = "search-empty") {
                EmptyState(strings.t("common.searchNoResultsTitle"), strings.t("common.searchNoResultsDescription"), strings.t("common.searchReset")) { vm.setQuery("") }
            }
            else -> BudgetLedger.sort(results, s.ledgerSort).forEach { e -> item(key = "q-${e.id}-${e.date}") { rowFor(e, true) } }
        }
        return
    }
    val all = s.shownEntries
    if (all.isEmpty()) {
        item(key = "empty") { EmptyState(strings.t("budget.emptyTitle"), action = if (editable) strings.t("budget.emptyAction") else null, onAction = onAdd) }
        return
    }
    val rows = BudgetLedger.sort(BudgetLedger.visible(all, s.filters), s.ledgerSort)
    if (rows.isEmpty()) {
        val active = listOfNotNull(
            s.filters.category?.let { categoryFilterLabel(s, strings) },
            s.filters.responsible?.let { s.members.firstOrNull { m -> m.id == it }?.displayName ?: s.responsibleName.ifEmpty { strings.t("budget.responsibleLabel") } },
            s.filters.account?.let { s.accountName(it).ifEmpty { strings.t("budget.accountLabel") } },
        ).joinToString(", ")
        item(key = "filter-empty") { EmptyState(strings.t("budget.filterEmptyTitle", "filter" to active), action = strings.t("budget.clearFilters"), onAction = vm::clearFilters) }
        return
    }
    if (s.groupByResponsible) {
        BudgetLedger.groupByResponsible(rows, s.members).forEach { g ->
            item(key = "g-${g.person?.id ?: 0}") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (g.person != null) AvatarStack(listOf(Triple(g.person.displayName.orEmpty(), g.person.color, null)), max = 1, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(g.person?.displayName ?: strings.t("budget.responsibleNobody"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(g.entries.size.toString(), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                }
            }
            g.entries.forEach { e -> item(key = "g-${g.person?.id ?: 0}-${e.id}-${e.date}-${e.subscriptionId}") { rowFor(e, false) } }
        }
        return
    }
    // `ledgerSectionsHtml`: a forecast month is all planned and stays one list.
    val split = BudgetLedger.split(rows, s.today)
    if (BudgetLedger.forecast(s.month, s.today) || split.planned.isEmpty()) {
        rows.forEach { e -> item(key = "e-${e.id}-${e.date}-${e.subscriptionId}") { rowFor(e, false) } }
        return
    }
    val planned = if (s.ledgerSort == "amount") BudgetLedger.sort(split.planned, "amount") else split.planned
    val expanded = s.plannedExpandedMonth == s.month
    item(key = "planned-title") {
        SectionTitle("${strings.t("budget.plannedTitle")} · ${planned.size}")
    }
    planned.take(if (expanded) planned.size else BudgetLedger.PLANNED_PREVIEW_ROWS).forEach { e ->
        item(key = "p-${e.id}-${e.date}-${e.subscriptionId}") { rowFor(e, false) }
    }
    if (planned.size > BudgetLedger.PLANNED_PREVIEW_ROWS) {
        item(key = "planned-toggle") {
            TextButton(onClick = vm::togglePlanned, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text(if (expanded) strings.t("budget.showFewerCategories") else strings.t("budget.showAllPlanned", "count" to planned.size))
            }
        }
    }
    if (split.booked.isNotEmpty()) {
        item(key = "booked-title") { SectionTitle(strings.t("budget.bookedTitle")) }
        split.booked.forEach { e -> item(key = "b-${e.id}-${e.date}") { rowFor(e, false) } }
    }
}

/** One ledger row (`entryRows`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(
    e: BudgetEntry,
    s: BudgetViewModel.UiState,
    dates: BudgetDates,
    strings: Strings,
    editable: Boolean,
    fullDate: Boolean,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
    onResponsible: (BudgetPerson) -> Unit,
) {
    val income = e.amount > 0
    val masked = e.detailsHidden
    val amountText = s.money.signed(e.amount, s.accountCurrency(e.accountId), Money.Role.FLOW)
    val upcoming = BudgetLedger.upcoming(e, s.today)
    val markUpcoming = !BudgetLedger.forecast(s.month, s.today)
    val colors = BudgetLedger.colorIndex(s.summary.byCategory, s.expenseCategoryKeys)
    val dot = when {
        e.isTransfer -> Yuvomi.colors.module("budget")
        income -> Yuvomi.colors.success
        else -> BudgetLedger.paint(colors, e.category, s.expenseCategoryKeys)?.let { seriesColor(it) } ?: Yuvomi.colors.module("budget")
    }
    val ring = upcoming || e.isPending
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !masked, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .let { if (ring) it.border(2.dp, dot, CircleShape) else it.background(dot) },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (masked) strings.t("budget.maskedEntryTitle") else e.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (s.personal && e.visibility == "shared") Badge(strings.t("budget.householdBadge"))
                if (masked) Badge(strings.t("budget.amountOnlyBadge"))
                if (e.isPending) Badge(strings.t("budget.pendingBadge"))
                if (e.isTransfer) Badge(strings.t("budget.typeTransfer"))
            }
            FlowRow(verticalArrangement = Arrangement.Center) {
                val meta = MaterialTheme.typography.bodySmall
                val tint = Yuvomi.colors.textSecondary
                Text(if (fullDate) dates.date(e.date) else dates.dayMonth(e.date), style = meta, color = tint)
                if (upcoming && markUpcoming) MetaIcon(Icons.Outlined.EventRepeat, strings.t("budget.upcomingLabel"))
                if (e.isRecurring) {
                    MetaIcon(Icons.Outlined.Repeat, strings.t("budget.recurringLabel"))
                    if (e.recurrenceVirtual) Text(" ${strings.t("budget.virtualBudgetBadge")}", style = meta, color = tint)
                } else if (e.recurrenceParentId != null) {
                    MetaIcon(Icons.Outlined.SubdirectoryArrowLeft, strings.t("budget.recurringInstanceLabel"))
                }
                val receipts = if (masked) 0 else e.attachments?.size ?: 0
                if (receipts > 0) MetaIcon(Icons.Outlined.AttachFile, strings.t("budget.receiptsAttachedLabel", "count" to receipts))
                val categoryMeta = if (s.filters.category != null) {
                    if (masked || e.subcategory.isNullOrEmpty()) "" else BudgetText.subcategory(e.subcategory, s.meta, strings)
                } else {
                    BudgetText.category(e.category, s.meta, strings)
                }
                if (categoryMeta.isNotEmpty()) Text(" · $categoryMeta", style = meta, color = tint)
                if (e.isTransfer) {
                    val partner = e.transferAccountId ?: s.entries.firstOrNull { it.id == e.transferEntryId }?.accountId
                    val source = s.accountName(if (income) partner else e.accountId)
                    val target = s.accountName(if (income) e.accountId else partner)
                    if (!masked && source.isNotEmpty() && target.isNotEmpty()) Text(" · $source → $target", style = meta, color = tint)
                } else {
                    val account = s.accountName(e.accountId)
                    if (account.isNotEmpty()) Text(" · $account", style = meta, color = tint)
                }
                if (!masked && e.responsibleUsers.isNotEmpty()) {
                    Text(" · ", style = meta, color = tint)
                    Box(
                        Modifier.clickable { onResponsible(e.responsibleUsers.first()) }
                            .semantics { contentDescription = strings.t("budget.responsibleFilterTo", "name" to (e.responsibleUsers.first().displayName ?: "")) },
                    ) {
                        AvatarStack(e.responsibleUsers.map { Triple(it.displayName.orEmpty(), it.color, null) }, max = 3, size = 16.dp)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            amountText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (!e.isTransfer && income) Yuvomi.colors.success else MaterialTheme.colorScheme.onSurface,
        )
        // The one row action: book an expected entry (`confirmBtn`).
        if (e.isPending && editable && !masked && !e.fromSubscription) {
            IconButton(onClick = onConfirm) { Icon(Icons.Outlined.Check, "${strings.t("budget.confirmAction")}: ${e.title}") }
        }
    }
}

@Composable
private fun MetaIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Icon(icon, label, Modifier.padding(start = 4.dp).size(14.dp), tint = Yuvomi.colors.textSecondary)
}

@Composable
fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** A labelled value of a read view (`detailRowEl`); empty values are left out. */
@Composable
fun DetailRow(label: String, value: String) {
    if (value.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The entry as a read sheet (`openEntryReadView`): Edit is its one primary action with write access. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryReadSheet(entry: BudgetEntry, s: BudgetViewModel.UiState, dates: BudgetDates, vm: BudgetViewModel, editable: Boolean, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(if (entry.detailsHidden) t("budget.maskedEntryTitle") else entry.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            DetailRow(t("budget.amountLabel"), s.money.signed(BudgetLedger.shownAmount(entry), s.currency, Money.Role.FLOW))
            DetailRow(t("budget.detailDateLabel"), if (entry.date.isNotEmpty()) dates.weekdayDate(entry.date) else "")
            DetailRow(t("budget.categoryLabel"), if (entry.category.isNotEmpty()) BudgetText.category(entry.category, s.meta, strings) else "")
            DetailRow(t("budget.subcategoryLabel"), entry.subcategory?.takeIf { it.isNotEmpty() }?.let { BudgetText.subcategory(it, s.meta, strings) }.orEmpty())
            DetailRow(t("budget.accountLabel"), s.accountName(entry.accountId))
            DetailRow(t("budget.visibilityLabel"), if (s.personal && entry.visibility != null) strings.t("budget.visibility_${entry.visibility}") else "")
            DetailRow(t("budget.responsibleLabel"), entry.responsibleUsers.mapNotNull { it.displayName }.joinToString(", "))
            DetailRow(t("budget.recurringLabel"), BudgetText.recurrence(entry, strings))
            if (vm.documentsAccess != "none") {
                val docs = entry.attachments.orEmpty().filter { it.documentId != null }
                if (docs.isNotEmpty()) {
                    Text(t("budget.receiptsLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
                    docs.forEach { d -> ReceiptLink(vm, Receipt.Linked(d.documentId!!, d.label, d.mimeType)) }
                }
            }
            if (editable) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(t("common.edit")) }
            }
        }
    }
}

/** `openConfirmBookingModal`: book an expected entry with the amount and date it really had. */
@Composable
private fun ConfirmBookingDialog(vm: BudgetViewModel, s: BudgetViewModel.UiState, entry: BudgetEntry, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currency = if (entry.isTransfer) s.accountCurrency(entry.accountId) else s.currency
    var amount by remember { mutableStateOf(s.money.toInput(abs(entry.amount), currency)) }
    var date by remember { mutableStateOf(runCatching { java.time.LocalDate.parse(entry.date) }.getOrElse { s.today }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("budget.confirmTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("budget.confirmHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                AmountField(t("budget.amountLabel"), amount, { amount = it; error = null }, s.money, currency, error = error)
                DateField(t("budget.dateLabel"), date, { date = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val problem = vm.confirm(entry, amount, date)
                    if (problem == null) close() else error = problem
                }
            }) { Text(t("budget.confirmAction")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}

/** Whether a transfer card shows (`canTransfer`): two active accounts. */
fun canTransfer(s: BudgetViewModel.UiState) = s.accounts.accounts.count { !it.archived } >= 2

