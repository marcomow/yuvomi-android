package io.github.marcomow.yuvomi.feature.splitexpenses

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.marcomow.yuvomi.feature.documents.DocumentsRoutes
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.subscriptions.BudgetMoney
import io.github.marcomow.yuvomi.feature.subscriptions.DetailRow
import io.github.marcomow.yuvomi.feature.subscriptions.SectionTitle
import io.github.marcomow.yuvomi.feature.subscriptions.budgetReadOnly
import io.github.marcomow.yuvomi.feature.subscriptions.dateText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** The navigation of the tab's own screens. */
class SplitNav(
    val back: () -> Unit,
    val expense: (Int?) -> Unit,
    val recurring: (Int?) -> Unit,
    val group: (Int?) -> Unit,
    val settle: () -> Unit,
    val guest: () -> Unit,
)

/** docs/spec/splitexpenses.md, the tab (SPLIT-R1 to R20). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SplitScreen(groupId: Int?, nav: SplitNav) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = remember(shell.connection) { SplitStore.of(shell.container) } ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope { kotlinx.coroutines.Dispatchers.Main.immediate }
    val ro = shell.budgetReadOnly()
    val me = shell.session.user.id
    var picker by remember { mutableStateOf(false) }
    var readExpense by remember { mutableStateOf<Int?>(null) }
    var readRecurring by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var resume by remember { mutableStateOf<RecurringExpense?>(null) }
    var addMember by remember { mutableStateOf(false) }

    LaunchedEffect(store) { store.refresh(preferGroup = groupId) }

    fun report(key: String?) {
        if (key != null) scope.launch { shell.notify(strings.t(key)) }
    }

    val group = s.group
    val canAct = !ro && !s.archived && !s.offline
    val canAdd = canAct && group != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("splitExpenses.tabLabel")) },
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    if (group != null && !ro && !s.offline && !store.guest) {
                        if (s.archived) {
                            IconButton(onClick = { scope.launch { report(store.restore(group.id)) } }) { Icon(Icons.Outlined.Unarchive, t("splitExpenses.restoreGroup")) }
                        } else {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(t("splitExpenses.addMember")) }, leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) }, onClick = { menu = false; addMember = true })
                                    DropdownMenuItem(text = { Text(t("splitExpenses.editGroup")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; nav.group(group.id) })
                                    DropdownMenuItem(text = { Text(t("splitExpenses.archiveGroup")) }, leadingIcon = { Icon(Icons.Outlined.Archive, null) }, onClick = { menu = false; confirm = Confirm.Archive(group.id) })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text(t("splitExpenses.deleteGroup"), color = Yuvomi.colors.danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) }, onClick = { menu = false; confirm = Confirm.Delete(group.id) })
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = { if (canAdd) FloatingActionButton(onClick = { nav.expense(null) }) { Icon(Icons.Outlined.Add, t("splitExpenses.addExpense")) } },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading && s.data.groups.isEmpty() -> LoadingBox()
                s.errorKey != null && !s.loaded && s.data.groups.isEmpty() -> ErrorBox(strings.t(s.errorKey!!), onRetry = { store.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { store.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) item { OfflineBanner(s.cachedAt?.let { shell.formats.dateTime(Instant.ofEpochMilli(it).atZone(shell.time.zone)) }) }
                        item { GroupSwitch(s) { picker = true } }
                        item { Summary(s, me) }
                        if (group == null) {
                            item {
                                if (s.archived) EmptyState(t("splitExpenses.emptyArchivedTitle"))
                                else if (!s.searchMiss) EmptyState(
                                    t("splitExpenses.emptyGroupsTitle"),
                                    if (ro) null else t("splitExpenses.emptyGroupsText"),
                                    if (!ro && !store.guest && !s.offline) t("splitExpenses.addGroup") else null,
                                ) { nav.group(null) }
                            }
                        } else {
                            item { GroupHeader(group, s, ro) }
                            groupBody(s, me, canAct, store, nav, openExpense = { readExpense = it }, openRecurring = { r ->
                                if (canAct && r.canEdit) nav.recurring(r.id) else readRecurring = r.id
                            }, toggle = { r ->
                                if (r.paused && r.missedCount > 0) resume = r
                                else scope.launch { report(store.togglePause(r, if (r.paused) "skip" else null)) }
                            }, reverse = { confirm = Confirm.Reverse(it) })
                        }
                    }
                }
            }
        }
    }

    if (picker) GroupPicker(s, store, ro, close = { picker = false }, newGroup = { picker = false; nav.group(null) })
    readExpense?.let { id ->
        s.expenses.firstOrNull { it.id == id }?.let { e ->
            ExpenseReadSheet(e, s, editable = canAct, close = { readExpense = null }, edit = { readExpense = null; nav.expense(e.id) })
        } ?: run { readExpense = null }
    }
    readRecurring?.let { id ->
        s.data.recurring.firstOrNull { it.id == id }?.let { r -> RecurringReadSheet(r, s) { readRecurring = null } } ?: run { readRecurring = null }
    }
    resume?.let { r ->
        AlertDialog(
            onDismissRequest = { resume = null },
            title = { Text(t("splitExpenses.recurring.missedQuestion")) },
            text = { Text(strings.t("splitExpenses.recurring.missedDetail", "count" to r.missedCount, "date" to shell.dateText(r.nextRunDate))) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { resume = null; scope.launch { report(store.togglePause(r, "skip")) } }) { Text(strings.t("splitExpenses.recurring.missedSkip", "date" to shell.dateText(r.resumeDate))) }
                    TextButton(onClick = { resume = null; scope.launch { report(store.togglePause(r, "book")) } }) { Text(strings.t("splitExpenses.recurring.missedBook", "date" to shell.dateText(r.nextRunDate))) }
                    TextButton(onClick = { resume = null }) { Text(t("common.cancel")) }
                }
            },
        )
    }
    confirm?.let { c ->
        val (title, detail, action) = when (c) {
            is Confirm.Archive -> Triple(strings.t("splitExpenses.archiveGroupConfirm"), null, strings.t("splitExpenses.archiveGroup"))
            is Confirm.Delete -> Triple(strings.t("splitExpenses.deleteGroupConfirm"), strings.t("splitExpenses.deleteGroupConfirmDetail"), strings.t("splitExpenses.deleteGroup"))
            is Confirm.Reverse -> Triple(strings.t("splitExpenses.reversePaymentConfirm"), strings.t("splitExpenses.reversePaymentConfirmDetail", *paymentParams(c.settlement, strings)), strings.t("splitExpenses.reversePayment"))
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = detail?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        report(
                            when (c) {
                                is Confirm.Archive -> store.archive(c.groupId)
                                is Confirm.Delete -> store.deleteGroup(c.groupId)
                                is Confirm.Reverse -> store.reverse(c.settlement.id)
                            },
                        )
                    }
                }) { Text(action, color = if (c is Confirm.Delete) Yuvomi.colors.danger else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("common.cancel")) } },
        )
    }
    if (addMember) AddMemberDialog(store, close = { addMember = false }, guest = { addMember = false; nav.guest() })
}

private sealed interface Confirm {
    data class Archive(val groupId: Int) : Confirm
    data class Delete(val groupId: Int) : Confirm
    data class Reverse(val settlement: ActivitySettlement) : Confirm
}

private fun paymentParams(st: ActivitySettlement, strings: Strings): Array<Pair<String, Any?>> = arrayOf(
    "payer" to st.payerName.orEmpty(),
    "payee" to st.payeeName.orEmpty(),
    "amount" to BudgetMoney.format(st.amount, st.currency, strings.locale),
)

@Composable
private fun GroupSwitch(s: SplitStore.State, open: () -> Unit) {
    val strings = LocalStrings.current
    val g = s.group
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).clickable(onClick = open),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Yuvomi.colors.module("budget").copy(alpha = 0.15f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, tint = Yuvomi.colors.module("budget"))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g?.name ?: strings.t("splitExpenses.groups"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (g != null) {
                    val type = SplitRules.groupTypeLabel(strings, g)
                    Text(
                        listOfNotNull(type.ifEmpty { null }, "${g.memberCount} ${strings.t("splitExpenses.members")}", if (s.archived) strings.t("splitExpenses.statusArchived") else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary,
                    )
                }
            }
            Icon(Icons.Outlined.ExpandMore, strings.t("splitExpenses.groups"))
        }
    }
}

/** `renderSummary()`: one balance statement; with a single debt of the reader's, that debt with the name. */
@Composable
private fun Summary(s: SplitStore.State, me: Int) {
    val strings = LocalStrings.current
    val locale = strings.locale
    val own = if (s.searchMiss && !s.archived) SplitRules.allGroupsBalance(s.data.dashboard) else SplitRules.ownGroupBalance(s.data.balances.balances, me)
    val currency = s.data.meta.defaultCurrency
    val both = own.owed.isNotEmpty() && own.owing.isNotEmpty()
    val folded = if (both || s.group == null) null else SplitRules.foldedDebt(s.data.balances.simplifiedDebts, me)
    val groupName = s.group?.name
    fun scoped(plain: String, scopedKey: String) = if (groupName != null) strings.t(scopedKey, "group" to groupName) else strings.t(plain)
    val lines: List<Triple<String, String, Boolean>> = when {
        folded != null -> listOf(Triple(SplitRules.debtSentence(strings, folded, me), BudgetMoney.format(folded.amount, folded.currency, locale), folded.toUserId == me))
        both -> listOf(
            Triple(scoped("splitExpenses.youAreOwed", "splitExpenses.youAreOwedInGroup"), SplitRules.totalsText(own.owed, currency, locale), true),
            Triple(scoped("splitExpenses.youOwe", "splitExpenses.youOweInGroup"), SplitRules.totalsText(own.owing, currency, locale), false),
        )
        own.owing.isNotEmpty() -> listOf(Triple(scoped("splitExpenses.youOwe", "splitExpenses.youOweInGroup"), SplitRules.totalsText(own.owing, currency, locale), false))
        own.owed.isNotEmpty() -> listOf(Triple(scoped("splitExpenses.youAreOwed", "splitExpenses.youAreOwedInGroup"), SplitRules.totalsText(own.owed, currency, locale), true))
        else -> listOf(Triple(scoped("splitExpenses.balanced", "splitExpenses.balancedInGroup"), SplitRules.totalsText(emptyList(), currency, locale), false))
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { (label, value, positive) ->
                Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = if (positive) Yuvomi.colors.success else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun GroupHeader(g: ExpenseGroup, s: SplitStore.State, ro: Boolean) {
    val strings = LocalStrings.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(g.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        SplitRules.groupTypeLabel(strings, g).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
        if (s.archived) Text(t("splitExpenses.statusArchived"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.warning)
        Text(g.description?.takeIf { it.isNotBlank() } ?: strings.t("splitExpenses.groupDefaultDescription"), style = MaterialTheme.typography.bodyMedium)
        if (ro) Text(groupMeta(g, s, strings), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** `groupMetaHtml()`: currency, default split with presets, and up to five members (read-only members only). */
private fun groupMeta(g: ExpenseGroup, s: SplitStore.State, strings: Strings): String {
    val members = s.data.groupMembers
    val values = SplitRules.defaultSplitValues(g)
    val presets = members.mapNotNull { m -> values[m.uid]?.let { v -> "${m.displayName} " + if (g.defaultSplitMethod == SplitRules.PERCENTAGE) BudgetMoney.percent((v.toDoubleOrNull() ?: 0.0) / 100, strings.locale, 2) else v } }
    val method = strings.t(SplitRules.methodKey(g.defaultSplitMethod))
    val names = members.take(5).map { if (it.role == "guest") "${it.displayName} (${strings.t("splitExpenses.roleGuest")})" else it.displayName }.toMutableList()
    if (members.size > 5) names += strings.t("splitExpenses.moreMembers", "count" to members.size - 5)
    return listOfNotNull(
        "${strings.t("splitExpenses.currency")}: ${g.defaultCurrency}",
        "${strings.t("splitExpenses.defaultSplit")}: " + if (presets.isNotEmpty()) "$method - ${presets.joinToString(", ")}" else method,
        names.takeIf { it.isNotEmpty() }?.let { "${strings.t("splitExpenses.members")}: ${it.joinToString(", ")}" },
    ).joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.groupBody(
    s: SplitStore.State,
    me: Int,
    canAct: Boolean,
    store: SplitStore,
    nav: SplitNav,
    openExpense: (Int) -> Unit,
    openRecurring: (RecurringExpense) -> Unit,
    toggle: (RecurringExpense) -> Unit,
    reverse: (ActivitySettlement) -> Unit,
) {
    val debts = s.data.balances.simplifiedDebts
    val folded = SplitRules.foldedDebt(debts, me)
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle(if (folded == null) t("splitExpenses.balances") else "") {
                if (canAct) OutlinedButton(onClick = nav.settle, enabled = s.expenses.isNotEmpty() || s.category.isNotEmpty() || debts.isNotEmpty()) { Text(t("splitExpenses.settle")) }
            }
            if (folded == null) {
                val strings = LocalStrings.current
                if (debts.isEmpty()) Text(t("splitExpenses.noBalances"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                debts.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(SplitRules.debtSentence(strings, d, me), modifier = Modifier.weight(1f))
                        Text(BudgetMoney.format(d.amount, d.currency, strings.locale), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    item {
        val strings = LocalStrings.current
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle(t("splitExpenses.recentExpenses"))
            val present = SplitRules.groupCategories(s.data.balances)
            if (present.size >= 2 || s.category.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.category.isEmpty(), { store.setCategory("") }, { Text(t("splitExpenses.allCategories")) })
                    (if (s.category.isNotEmpty() && s.category !in present) present + s.category else present).forEach { key ->
                        FilterChip(s.category == key, { store.setCategory(key) }, { Text(SplitRules.categoryLabel(strings, key, s.data.budgetCategories)) })
                    }
                }
            }
            if (s.expenses.isEmpty()) Text(t(if (s.category.isNotEmpty()) "splitExpenses.noExpensesInCategory" else "splitExpenses.noExpenses"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        }
    }
    items(s.expenses, key = { "expense-${it.id}" }) { e -> ExpenseRow(e, s) { openExpense(e.id) } }
    if (s.data.expensesCursor != null) item { TextButton(onClick = store::loadMoreExpenses, enabled = !s.loadingMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text(t("splitExpenses.loadMoreActivity")) } }
    if (s.data.balances.memberTotals.isNotEmpty()) item {
        val strings = LocalStrings.current
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle(t("splitExpenses.totalsByMember"))
            Text(t("splitExpenses.totalsByMemberHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            s.data.balances.memberTotals.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName.orEmpty(), fontWeight = FontWeight.Medium)
                        Text(strings.t("splitExpenses.ownShare", "amount" to BudgetMoney.format(m.share, m.currency, strings.locale)), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                    Text(BudgetMoney.format(m.paid, m.currency, strings.locale), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (s.data.balances.budgetCategoryTotals.isNotEmpty()) item {
        val strings = LocalStrings.current
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle(t("splitExpenses.totalsByCategory"))
            s.data.balances.budgetCategoryTotals.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(SplitRules.categoryLabel(strings, c.budgetCategory, s.data.budgetCategories), modifier = Modifier.weight(1f))
                    Text(BudgetMoney.format(c.total, c.currency, strings.locale), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle(t("budget.recurringLabel")) {
                if (canAct) TextButton(onClick = { nav.recurring(null) }) { Text(t("splitExpenses.recurring.add")) }
            }
            if (s.data.recurring.isEmpty()) Text(t("splitExpenses.recurring.empty"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        }
    }
    items(s.data.recurring, key = { "recurring-${it.id}" }) { r -> RecurringRow(r, canAct && r.canEdit, open = { openRecurring(r) }, toggle = { toggle(r) }) }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle(t("splitExpenses.activity"))
            if (s.data.activity.isEmpty()) Text(t("splitExpenses.noActivity"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        }
    }
    items(s.data.activity, key = { "activity-${it.id}" }) { a -> ActivityRow(a, s, canAct, reverse) }
    if (s.data.activityCursor != null) item { TextButton(onClick = store::loadMoreActivity, enabled = !s.loadingMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text(t("splitExpenses.loadMoreActivity")) } }
}

@Composable
private fun CategoryDot(key: String?) {
    Box(Modifier.size(10.dp).background(if (key == null) Yuvomi.colors.border else Yuvomi.colors.module("budget"), CircleShape))
}

@Composable
private fun ExpenseRow(e: Expense, s: SplitStore.State, open: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryDot(e.budgetCategory)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                append("${strings.t("splitExpenses.paidBy")}: ${e.payerName.orEmpty()} · ${shell.dateText(e.expenseDate)}")
                if (e.budgetCategory != null) append(" · ${SplitRules.categoryLabel(strings, e.budgetCategory, s.data.budgetCategories)}")
                val receipts = e.attachments?.size ?: 0
                if (receipts > 0) append(" · ${strings.t("splitExpenses.receiptsAttachedLabel", "count" to receipts)}")
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Text(BudgetMoney.format(e.amount, e.currency, strings.locale), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecurringRow(r: RecurringExpense, acts: Boolean, open: () -> Unit, toggle: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryDot(r.budgetCategory)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontWeight = FontWeight.Medium)
            Text(strings.t(SplitRules.FREQUENCY_KEYS[r.frequency] ?: "budget.intervalMonthly"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            Text(
                if (r.paused) strings.t("splitExpenses.recurring.paused") else "${strings.t("splitExpenses.recurring.nextDate")}: ${shell.dateText(r.nextRunDate)}",
                style = MaterialTheme.typography.bodySmall, color = if (r.paused) Yuvomi.colors.warning else Yuvomi.colors.textSecondary,
            )
            recurringReason(strings, r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.danger) }
        }
        Text(BudgetMoney.format(r.amount, r.currency, strings.locale), fontWeight = FontWeight.SemiBold)
        if (acts) {
            val label = strings.t(if (r.paused) "splitExpenses.recurring.resume" else "splitExpenses.recurring.pause")
            IconButton(onClick = toggle) { Icon(if (r.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, "${r.title} - $label") }
        }
    }
}

internal fun recurringReason(strings: Strings, r: RecurringExpense): String? = listOfNotNull(
    r.blockedReason?.let {
        when (it) {
            "split_invalid" -> strings.t("splitExpenses.recurring.reason.split_invalid")
            "not_a_member" -> strings.t("splitExpenses.recurring.reason.not_a_member")
            else -> null
        }
    },
    if (r.failing) strings.t("splitExpenses.recurring.failing") else null,
).joinToString(" · ").ifEmpty { null }

/** `activityItemHtml()`. */
@Composable
private fun ActivityRow(a: Activity, s: SplitStore.State, canAct: Boolean, reverse: (ActivitySettlement) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val st = a.settlement
    val gone = a.expense?.deletedAt != null && a.type != "expense_deleted"
    val detail = activityDetail(a, s, strings)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Yuvomi.colors.module("budget"), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(activityTitle(strings, a.type), fontWeight = FontWeight.Medium, color = if (st?.reversedAt != null || gone) Yuvomi.colors.textSecondary else MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val day = shell.time.parse(a.createdAt)?.toLocalDate()?.toString()
            Text("${a.actorName ?: strings.t("splitExpenses.system")} · ${shell.dateText(day ?: a.createdAt.take(10))}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            if (st?.reversedAt != null) Text(t("splitExpenses.paymentReversed"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.warning)
            else if (gone) Text(t("splitExpenses.expenseDeleted"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.warning)
        }
        if (st != null && st.canReverse && st.reversedAt == null && canAct) {
            OutlinedButton(onClick = { reverse(st) }) { Text(t("splitExpenses.reversePayment")) }
        }
    }
}

private val ACTIVITY_TYPES = setOf(
    "group_created", "group_updated", "group_archived", "group_unarchived", "member_added", "member_removed", "guest_created",
    "expense_created", "expense_edited", "expense_deleted", "comment_added", "payment_registered", "recurring_created",
    "recurring_paused", "recurring_auto_paused", "recurring_resumed", "recurring_generated", "recurring_edited",
    "recurring_deleted", "payment_reversed", "ledger_restored", "ledger_removed",
)

/** `t('splitExpenses.activityType.<type>')`; an unknown type stays readable instead of a raw key. */
internal fun activityTitle(strings: Strings, type: String): String =
    if (type in ACTIVITY_TYPES) strings.t("splitExpenses.activityType.$type") else type

private val EXPENSE_ACTIVITY = setOf("expense_created", "expense_edited", "expense_deleted", "comment_added", "recurring_created", "recurring_generated", "recurring_auto_paused", "recurring_edited", "recurring_deleted", "ledger_restored", "ledger_removed")

/** `paymentDetail` for a payment, else the snapshot's title and amount (`expenseDetail`, `restoredDetail`). */
internal fun activityDetail(a: Activity, s: SplitStore.State, strings: Strings): String? {
    a.settlement?.let { return strings.t("splitExpenses.paymentDetail", *paymentParams(it, strings)) }
    if (a.type !in EXPENSE_ACTIVITY) return null
    val md = a.metadata
    fun str(k: String) = (md?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    val title = str("title") ?: if (a.type == "comment_added") s.expenses.firstOrNull { it.id == a.entityId }?.title else null
    title ?: return null
    val amount = str("amount")
    val currency = str("currency")
    return if (amount != null && currency != null) "$title · ${BudgetMoney.format(amount, currency, strings.locale)}" else title
}

/** The group list (`renderGroups()`), with search, Active/Archived and "New group", as a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupPicker(s: SplitStore.State, store: SplitStore, ro: Boolean, close: () -> Unit, newGroup: () -> Unit) {
    val strings = LocalStrings.current
    var search by remember { mutableStateOf(s.query) }
    LaunchedEffect(search) {
        delay(250)
        store.setQuery(search)
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("splitExpenses.groups"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!ro && !store.guest && !s.offline) TextButton(onClick = newGroup) { Text(t("splitExpenses.addGroup")) }
            }
            OutlinedTextField(
                search, { search = it }, singleLine = true, placeholder = { Text(t("splitExpenses.searchGroups")) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, t("common.searchClear")) } },
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("active" to "splitExpenses.statusActive", "archived" to "splitExpenses.statusArchived").forEachIndexed { i, (id, key) ->
                    SegmentedButton(selected = s.status == id, onClick = { store.setStatus(id) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(t(key)) }
                }
            }
            if (!s.archived && s.data.groups.isNotEmpty()) {
                val d = s.data.dashboard
                val locale = strings.locale
                val parts = listOfNotNull(
                    d?.totalOwed?.takeIf { it.isNotEmpty() }?.let { "${strings.t("splitExpenses.youAreOwed")} ${SplitRules.totalsText(it, s.data.meta.defaultCurrency, locale)}" },
                    d?.totalOwing?.takeIf { it.isNotEmpty() }?.let { "${strings.t("splitExpenses.youOwe")} ${SplitRules.totalsText(it, s.data.meta.defaultCurrency, locale)}" },
                ).ifEmpty { listOf(strings.t("splitExpenses.balanced")) }
                Text("${strings.t("splitExpenses.allGroups")}: ${parts.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            when {
                s.searchMiss -> EmptyState(t("common.searchNoResultsTitle"), t("common.searchNoResultsDescription"), t("common.searchReset")) { search = "" }
                s.data.groups.isEmpty() -> EmptyState(t(if (s.archived) "splitExpenses.emptyArchivedTitle" else "splitExpenses.emptyGroupsTitle"))
            }
            s.data.groups.forEach { g ->
                val type = SplitRules.groupTypeLabel(strings, g)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (g.id == s.data.activeGroupId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable { store.selectGroup(g.id); close() },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(type.ifEmpty { null }, "${g.memberCount} ${strings.t("splitExpenses.members")}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** `openExpenseReadView()`: amount, payer, date, category, split and shares, notes and receipts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseReadSheet(e: Expense, s: SplitStore.State, editable: Boolean, close: () -> Unit, edit: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(e.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            DetailRow(t("splitExpenses.amount"), BudgetMoney.format(e.amount, e.currency, strings.locale))
            DetailRow(t("splitExpenses.paidBy"), e.payerName)
            DetailRow(t("splitExpenses.date"), shell.dateText(e.expenseDate))
            if (e.budgetCategory != null) DetailRow(t("splitExpenses.categoryLabel"), SplitRules.categoryLabel(strings, e.budgetCategory, s.data.budgetCategories))
            DetailRow(t("splitExpenses.splitMethod"), strings.t(SplitRules.methodKey(e.splitMethod)))
            DetailRow(t("splitExpenses.participants"), e.splits.joinToString("\n") { "${it.displayName.orEmpty()}: ${BudgetMoney.format(it.amount, it.currency ?: e.currency, strings.locale)}" })
            DetailRow(t("splitExpenses.notes"), e.description)
            val docs = e.attachments.orEmpty().filter { it.documentId != null }
            if (docs.isNotEmpty() && shell.shows("documents")) {
                Text(t("splitExpenses.receiptsLabel"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
                docs.forEach { d ->
                    TextButton(onClick = { close(); shell.navigate(DocumentsRoutes.document(d.documentId!!)) }) { Text(d.name ?: d.originalName.orEmpty()) }
                }
            }
            if (editable) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) { Button(onClick = edit) { Text(t("common.edit")) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringReadSheet(r: RecurringExpense, s: SplitStore.State, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val names = s.data.groupMembers.associate { it.uid to it.displayName }
    val values = SplitRules.recurringSplitValues(r, strings.locale)
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(r.title, style = MaterialTheme.typography.titleLarge)
            DetailRow(t("splitExpenses.amount"), BudgetMoney.format(r.amount, r.currency, strings.locale))
            DetailRow(t("splitExpenses.paidBy"), r.payerName)
            DetailRow(t("budget.recurringIntervalLabel"), strings.t(SplitRules.FREQUENCY_KEYS[r.frequency] ?: "budget.intervalMonthly"))
            if (!r.paused) DetailRow(t("splitExpenses.recurring.nextDate"), shell.dateText(r.nextRunDate))
            DetailRow(t("splitExpenses.statusLabel"), listOfNotNull(if (r.paused) strings.t("splitExpenses.recurring.paused") else null, recurringReason(strings, r)).joinToString(" · "))
            if (r.budgetCategory != null) DetailRow(t("splitExpenses.categoryLabel"), SplitRules.categoryLabel(strings, r.budgetCategory, s.data.budgetCategories))
            DetailRow(t("splitExpenses.splitMethod"), strings.t(SplitRules.methodKey(r.splitMethod)))
            DetailRow(t("splitExpenses.participants"), r.participants.joinToString("\n") { id -> names[id].orEmpty() + (values[id]?.let { ": $it" } ?: "") })
            DetailRow(t("splitExpenses.notes"), r.description)
        }
    }
}

/** `openMemberModal()`: a household member, guest or contact not yet in the group, with a role; or a new guest. */
@Composable
private fun AddMemberDialog(store: SplitStore, close: () -> Unit, guest: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope { kotlinx.coroutines.Dispatchers.Main.immediate }
    var candidates by remember { mutableStateOf<List<MemberCandidate>?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf("guest") }
    LaunchedEffect(Unit) {
        candidates = try { store.candidates().filter { !it.inGroup } } catch (e: io.github.marcomow.yuvomi.core.api.ApiProblem) {
            shell.notify(strings.t(SplitErrors.key(e)))
            emptyList()
        }
        chosen = candidates?.firstOrNull()?.key
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t("splitExpenses.addMember")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = candidates
                if (list == null) Text(t("common.loading"))
                else {
                    io.github.marcomow.yuvomi.feature.subscriptions.ChoiceField(
                        t("splitExpenses.member"), chosen,
                        list.map { it.key to it.displayName + if (it.source == "contact") " · ${strings.t("nav.contacts")}" else "" },
                    ) { chosen = it }
                    io.github.marcomow.yuvomi.feature.subscriptions.ChoiceField(
                        t("splitExpenses.role"), role, listOf("guest" to t("splitExpenses.roleGuest"), "admin" to t("splitExpenses.roleAdmin")),
                    ) { role = it }
                }
                TextButton(onClick = guest) { Text(t("splitExpenses.createGuest")) }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen != null, onClick = {
                val c = candidates?.firstOrNull { it.key == chosen } ?: return@TextButton
                close()
                scope.launch {
                    store.addMember(c.userId.takeIf { c.source == "user" }, c.contactId.takeIf { c.source == "contact" }, role)?.let { shell.notify(strings.t(it)) }
                }
            }) { Text(t("common.add")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}
