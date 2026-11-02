package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch

/**
 * The transfer form (`openTransferDialog`, `transferFieldsHtml`): amount in the source account's
 * currency, from and to, title (with the default "Transfer A → B"), date, and for a series the
 * choice between this transfer and all coming ones. Category, visibility and repeat wait under
 * "more details".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferEditorScreen(vm: BudgetViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val entry = remember { vm.transferRequest }
    val scope = rememberCoroutineScope()
    val key = remember { YuvomiClient.newIdempotencyKey() }
    var transfer by remember { mutableStateOf<BudgetTransfer?>(null) }
    var loading by remember { mutableStateOf(entry != null) }
    var form by remember { mutableStateOf<TransferForm?>(null) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var more by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    // (series route, series prompt): the anchor of a series deletes it through its own id (`deleteEntry`).
    var confirmDelete by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    LaunchedEffect(entry) {
        if (entry?.id != null) {
            val t = vm.loadTransfer(entry.id)
            if (t == null) close() else {
                transfer = t
                form = TransferForm.of(t, s.money, s.accountCurrency(t.fromAccountId))
                more = t.isRecurring || t.recurrenceParentId != null
            }
            loading = false
        } else {
            val (from, to) = BudgetLedger.monthRange(s.month)
            val cats = s.meta.expenseCategories
            val cat = if (cats.any { it.key == "financial_other" }) "financial_other" else cats.firstOrNull()?.key ?: ""
            val subs = s.subcategories(cat)
            form = TransferForm(
                date = BudgetLedger.defaultDate(from, to, s.today),
                category = cat,
                subcategory = subs.firstOrNull { it.key == "saving" }?.key ?: if (subs.size == 1) subs[0].key else "",
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("budget.typeTransfer")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
            )
        },
    ) { padding ->
        val f = form
        if (loading || f == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val accounts = s.accounts.accounts
        val sourceCurrency = s.accountCurrency(f.from)
        val subs = s.subcategories(f.category)
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AmountField(t("budget.amountLabel"), f.amount, { form = f.copy(amount = it); errors = errors - "amount" }, s.money, sourceCurrency, error = errors["amount"], tag = "transfer-amount")
            // Archived accounts are not offered, except the one the transfer already uses.
            Picker(
                t("budget.transferFromAccount"),
                s.accountName(f.from),
                accounts.filter { !it.archived || it.id == f.from }.map { it.id to it.name },
                error = errors["from"],
            ) { form = f.copy(from = it); errors = errors - "to" - "from" }
            Picker(
                t("budget.transferToAccount"),
                s.accountName(f.to),
                accounts.filter { !it.archived || it.id == f.to }.map { it.id to it.name },
                error = errors["to"],
            ) { form = f.copy(to = it); errors = errors - "to" }
            TextInput(t("budget.titleLabel"), f.title, { form = f.copy(title = it) }, placeholder = f.defaultTitle(accounts, strings))
            DateField(t("budget.dateLabel"), f.date, { form = f.copy(date = it) }, error = errors["date"])
            val tr = transfer
            if (tr != null && (tr.isRecurring || tr.recurrenceParentId != null)) {
                Picker(
                    t("budget.recurringSeriesScope"),
                    t(if (f.scope == "series") "budget.recurringEditSeries" else "budget.recurringThisOnly"),
                    listOf("this" to t("budget.recurringThisOnly"), "series" to t("budget.recurringEditSeries")),
                ) { scopeChoice ->
                    // Fields still showing the previous target's values follow the new one (`wireTransferForm`).
                    form = if (tr.series != null) TransferForm.of(tr, s.money, sourceCurrency, scopeChoice) else f.copy(scope = scopeChoice)
                }
            }
            TextButton(onClick = { more = !more }) {
                Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(t("budget.transferMoreDetailsHint"))
            }
            if (more) {
                val cats = s.meta.expenseCategories
                Picker(
                    t("budget.categoryLabel"),
                    cats.firstOrNull { it.key == f.category }?.let { BudgetText.category(it, strings) } ?: "",
                    cats.map { it.key to BudgetText.category(it, strings) },
                    error = errors["category"],
                ) { c ->
                    val ss = s.subcategories(c)
                    form = f.copy(category = c, subcategory = if (ss.size == 1) ss[0].key else "")
                }
                if (subs.isNotEmpty()) {
                    Picker(
                        t("budget.subcategoryLabel"),
                        subs.firstOrNull { it.key == f.subcategory }?.let { BudgetText.subcategory(it, strings) } ?: "",
                        subs.map { it.key to BudgetText.subcategory(it, strings) },
                        error = errors["subcategory"],
                    ) { form = f.copy(subcategory = it) }
                }
                if (s.personal) {
                    Picker(
                        t("budget.visibilityLabel"),
                        strings.t("budget.visibility_${f.visibility}"),
                        listOf("private", "shared", "shared_amount").map { it to strings.t("budget.visibility_$it") },
                    ) { form = f.copy(visibility = it) }
                }
                // An existing transfer changes its rhythm only through the series (`syncRecurrenceEditing`).
                val rhythmEditable = transfer == null || f.scope == "series"
                SwitchRow(t("budget.recurringLabel"), f.recurring, { form = f.copy(recurring = it) }, enabled = transfer == null)
                if (f.recurring) {
                    RecurrenceFields(f.interval, f.intervalCount, { form = f.copy(interval = it) }, { form = f.copy(intervalCount = it) }, enabled = rhythmEditable)
                    SwitchRow(t("budget.confirmFirstLabel"), f.confirm, { form = f.copy(confirm = it) }, enabled = rhythmEditable)
                }
            }
            if (tr != null) {
                OutlinedButton(onClick = {
                    if (tr.recurrenceParentId != null) askDelete = true else confirmDelete = false to tr.isRecurring
                }) {
                    Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                    Text(" " + t("common.delete"), color = Yuvomi.colors.danger)
                }
            }
            Button(
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val current = if (f.scope == "series") tr?.series else tr
                    val original = current?.takeIf { it.fromAccountId == f.from }?.let { kotlin.math.abs(it.amount) }
                    errors = f.errors(s.money, sourceCurrency, subs, original, strings)
                    if (errors.isNotEmpty()) {
                        if (errors.keys.any { it == "category" || it == "subcategory" }) more = true
                        return@Button
                    }
                    scope.launch {
                        when (val r = vm.saveTransfer(tr, f, key)) {
                            BudgetViewModel.SaveResult.Saved -> close()
                            is BudgetViewModel.SaveResult.Field -> if (r.field != null) errors = mapOf(r.field to r.message) else vm.notify(r.message)
                        }
                    }
                },
            ) { Text(t(if (tr != null) "common.save" else "common.add")) }
        }
    }

    if (askDelete) {
        SeriesChoiceDialog(
            title = t("budget.recurringSeriesScope"),
            thisLabel = t("budget.recurringThisOnly"),
            seriesLabel = t("budget.recurringEntireSeries"),
            note = null,
            seriesDanger = true,
        ) { choice ->
            askDelete = false
            if (choice != null) confirmDelete = (choice == "series") to (choice == "series")
        }
    }
    confirmDelete?.let { (route, prompt) ->
        ConfirmDialog(
            title = t(if (prompt) "budget.transferDeleteSeriesConfirm" else "budget.transferDeleteConfirm"),
            detail = t("budget.transferDeleteConfirmDetail"),
            confirm = t("common.delete"),
            onDismiss = { confirmDelete = null },
        ) {
            confirmDelete = null
            val e = entry ?: return@ConfirmDialog
            scope.launch {
                vm.deleteTransfer(e, route)
                close()
            }
        }
    }
}
