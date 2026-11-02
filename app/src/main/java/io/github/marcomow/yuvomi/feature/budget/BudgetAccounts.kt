package io.github.marcomow.yuvomi.feature.budget

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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ModuleBadge
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch

/** `ACCOUNT_TYPE_ICONS`, in Material symbols. */
fun accountIcon(type: String): ImageVector = when (type) {
    "checking" -> Icons.Outlined.AccountBalance
    "savings" -> Icons.Outlined.Savings
    "cash" -> Icons.Outlined.AccountBalanceWallet
    "credit" -> Icons.Outlined.CreditCard
    "investment" -> Icons.Outlined.TrendingUp
    else -> Icons.Outlined.Payments
}

/** The accounts tab (`renderAccountsPage`): net worth, then one row per account. */
@Composable
fun AccountsTab(vm: BudgetViewModel, s: BudgetViewModel.UiState, dates: BudgetDates, nav: BudgetNav, editable: Boolean) {
    val strings = LocalStrings.current
    var reading by remember { mutableStateOf<BudgetAccount?>(null) }
    val all = s.shownAccounts
    val visible = all.filter { s.showArchived || !it.archived }
    val info = s.accounts
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "net-worth") {
            Card(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("budget.netWorth"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                    // Several currencies without a rate: one total per currency (#2302).
                    val parts = if (info.netWorth == null) info.netWorthByCurrency else emptyList()
                    val text = if (parts.isNotEmpty()) {
                        parts.joinToString(" · ") { s.money.format(it.amount, it.currency) }
                    } else {
                        (if (info.netWorthApprox) "≈ " else "") + s.money.signed(info.netWorth ?: 0.0, s.currency, Money.Role.BALANCE)
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if ((info.netWorth ?: 0.0) < 0) Yuvomi.colors.danger else MaterialTheme.colorScheme.onSurface,
                    )
                    val note = when {
                        parts.isNotEmpty() -> t("budget.netWorthPerCurrencyNote")
                        info.netWorthApprox && info.netWorthRatesAt != null -> t("budget.netWorthApproxNote", "date" to dates.date(info.netWorthRatesAt))
                        else -> null
                    }
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
            }
        }
        if (all.any { it.archived }) {
            item(key = "archived") {
                TextButton(onClick = vm::toggleArchived, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(if (s.showArchived) Icons.Outlined.Visibility else Icons.Outlined.Archive, null, Modifier.size(18.dp))
                    Text(" " + t(if (s.showArchived) "budget.hideArchivedAccounts" else "budget.showArchivedAccounts"))
                }
            }
        }
        if (all.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    t("budget.accountsEmptyTitle"),
                    if (editable) t("budget.accountsEmptyDescription") else null,
                    if (editable) t("budget.addAccount") else null,
                ) { vm.accountRequest = null; nav.accountEditor() }
            }
        }
        items(visible, key = { it.id }) { a ->
            AccountRow(a, s, strings, editable, onOpen = { reading = a }, onEdit = { vm.accountRequest = a; nav.accountEditor() })
        }
    }
    reading?.let { a ->
        AccountReadSheet(
            a, s, strings, editable,
            onStatement = { reading = null; vm.setAccountFilter(a.id, fromAccounts = true) },
            onEdit = { reading = null; vm.accountRequest = a; nav.accountEditor() },
            onDismiss = { reading = null },
        )
    }
}

@Composable
private fun AccountRow(a: BudgetAccount, s: BudgetViewModel.UiState, strings: io.github.marcomow.yuvomi.core.i18n.Strings, editable: Boolean, onOpen: () -> Unit, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cur = s.accountCurrency(a.id)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleBadge(accountIcon(a.type), accountColor(a.color), size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, style = MaterialTheme.typography.bodyLarge)
                if (a.archived) Badge(t("budget.archivedBadge"))
            }
            Text(BudgetText.accountType(a.type, strings), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            if (a.type == "credit" && (a.creditBank != null || a.availableLimit != null)) {
                Text(
                    listOfNotNull(a.creditBank?.takeIf { it.isNotEmpty() }, a.availableLimit?.let { "${t("budget.availableLimitShort")} ${s.money.format(it, cur)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Yuvomi.colors.textSecondary,
                )
            }
        }
        Text(s.money.format(a.currentBalance, cur), color = toneColor(BudgetLedger.accountTone(a)), fontWeight = FontWeight.Medium)
        if (editable) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to a.name)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(t("common.edit")) }, onClick = { menu = false; onEdit() })
                }
            }
        }
    }
}

/** The account as a read sheet (`openAccountReadView`): "Show transactions" and, with write access, Edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountReadSheet(
    a: BudgetAccount,
    s: BudgetViewModel.UiState,
    strings: io.github.marcomow.yuvomi.core.i18n.Strings,
    editable: Boolean,
    onStatement: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cur = a.currency ?: s.currency
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(accountColor(a.color)))
                Text("  " + a.name, style = MaterialTheme.typography.titleLarge)
            }
            DetailRow(t("budget.accountTypeLabel"), BudgetText.accountType(a.type, strings))
            DetailRow(t("budget.balance"), s.money.format(a.currentBalance, cur))
            DetailRow(t("budget.startingBalanceLabel"), s.money.format(a.startingBalance, cur))
            if (a.type == "credit") {
                DetailRow(t("budget.creditBankLabel"), a.creditBank.orEmpty())
                DetailRow(t("budget.creditLimitLabel"), a.creditLimit?.let { s.money.format(it, cur) }.orEmpty())
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onStatement, modifier = Modifier.fillMaxWidth()) { Text(t("budget.showAccountTransactions")) }
            if (editable) Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(t("common.edit")) }
        }
    }
}

/** The account form (`openAccountModal`): name, type, starting balance, the credit fields, the colour. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountEditorScreen(vm: BudgetViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val account = remember { vm.accountRequest }
    val scope = rememberCoroutineScope()
    val key = remember { YuvomiClient.newIdempotencyKey() }
    val currency = account?.currency ?: s.currency
    var form by remember { mutableStateOf(account?.let { AccountForm.of(it, s.money, currency) } ?: AccountForm()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (account != null) "budget.editAccount" else "budget.newAccount")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextInput(t("budget.accountNameLabel"), form.name, { form = form.copy(name = it.take(100)); errors = errors - "name" }, error = errors["name"], placeholder = t("budget.accountNamePlaceholder"), tag = "account-name")
            Picker(
                t("budget.accountTypeLabel"),
                BudgetText.accountType(form.type, strings),
                BudgetLedger.ACCOUNT_TYPES.map { it to BudgetText.accountType(it, strings) },
                error = errors["type"],
            ) { form = form.copy(type = it) }
            AmountField(t("budget.startingBalanceLabel"), form.balance, { form = form.copy(balance = it); errors = errors - "balance" }, s.money, currency, error = errors["balance"], hint = t("budget.startingBalanceHint"))
            if (form.type == "credit") {
                TextInput(t("budget.creditBankLabel"), form.creditBank, { form = form.copy(creditBank = it.take(100)) }, placeholder = t("budget.creditBankPlaceholder"), error = errors["creditBank"])
                AmountField(t("budget.creditLimitLabel"), form.creditLimit, { form = form.copy(creditLimit = it); errors = errors - "creditLimit" }, s.money, currency, error = errors["creditLimit"], hint = t("budget.creditLimitHint"))
            }
            Text(t("budget.accountColorLabel"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Pair<String?, String>>(null to "budget.accountColorDefault") + AccountForm.COLORS).forEach { (value, nameKey) ->
                    val on = (form.color ?: "") == (value ?: "")
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(accountColor(value))
                            .clickable { form = form.copy(color = value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Icon(Icons.Outlined.Check, strings.t(nameKey), tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            if (account != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        // An account without entries goes with an undo instead of a question (R23, D6).
                        if (account.entryCount > 0) confirmDelete = true else { vm.deleteEmptyAccount(account); close() }
                    }) {
                        Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                        Text(" " + t("common.delete"), color = Yuvomi.colors.danger)
                    }
                    OutlinedButton(onClick = { scope.launch { if (vm.setArchived(account, !account.archived)) close() } }) {
                        Icon(if (account.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null)
                        Text(" " + t(if (account.archived) "budget.unarchiveAccount" else "budget.archiveAccount"))
                    }
                }
            }
            Button(
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    errors = form.errors(s.money, currency, account, strings)
                    if (errors.isNotEmpty()) return@Button
                    scope.launch {
                        when (val r = vm.saveAccount(account, form, key)) {
                            BudgetViewModel.SaveResult.Saved -> close()
                            is BudgetViewModel.SaveResult.Field -> if (r.field != null) errors = mapOf(r.field to r.message) else vm.notify(r.message)
                        }
                    }
                },
            ) { Text(t(if (account != null) "common.save" else "common.add")) }
        }
    }
    if (confirmDelete && account != null) {
        ConfirmDialog(
            title = t("budget.deleteAccountConfirm", "name" to account.name),
            detail = t("budget.deleteAccountConfirmDetail"),
            confirm = t("common.delete"),
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            scope.launch { if (vm.deleteAccount(account)) close() }
        }
    }
}

