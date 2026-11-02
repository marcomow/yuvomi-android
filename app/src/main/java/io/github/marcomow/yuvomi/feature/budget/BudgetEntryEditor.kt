package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch

/**
 * The entry editor (`openBudgetModal`), full screen. Amount first, then title, category,
 * subcategory, date and account; what is rarely set waits under "more details". Choosing Loan or
 * Transfer in the type segment opens that editor instead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditorScreen(vm: BudgetViewModel, nav: BudgetNav) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val request = remember { vm.entryRequest }
    val entry = request.entry
    val isEdit = entry != null
    val scope = rememberCoroutineScope()
    val key = remember { YuvomiClient.newIdempotencyKey() }
    val isLoanPayment = entry != null && (entry.loanPaymentId != null || entry.loanId != null)

    var form by remember {
        val start = s.today.let { today ->
            val (from, to) = request.period ?: BudgetLedger.monthRange(s.month)
            BudgetLedger.defaultDate(from, to, today)
        }
        mutableStateOf(
            if (entry != null) {
                val known = if (entry.amount < 0) s.meta.expenseCategories else s.meta.incomeCategories
                EntryForm.of(entry, s.money, s.accountCurrency(entry.accountId), s.subcategories(entry.category), known)
            } else {
                EntryForm(type = if (request.type == "income") "income" else "expense", date = start)
            },
        )
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val original = entry?.let { kotlin.math.abs(BudgetLedger.shownAmount(it)) }
    val moreOpenInitially = entry != null && (
        entry.isRecurring || (entry.attachments?.size ?: 0) > 0 || entry.responsibleUsers.isNotEmpty() ||
            (s.personal && (entry.visibility ?: "shared") != "shared")
        )
    var more by remember { mutableStateOf(moreOpenInitially) }
    var askSeries by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }
    var newSubcategory by remember { mutableStateOf(false) }
    val currency = s.accountCurrency(form.accountId)
    val categories = if (form.type == "income") s.meta.incomeCategories else s.meta.expenseCategories
    val subs = s.subcategories(form.category)
    val people = BudgetPeople.pickable(s.members, entry?.responsibleUsers.orEmpty())

    fun save(series: Boolean) {
        scope.launch {
            when (val r = vm.saveEntry(entry, form, series, key)) {
                BudgetViewModel.SaveResult.Saved -> nav.back()
                is BudgetViewModel.SaveResult.Field -> if (r.field != null) errors = mapOf(r.field to r.message) else vm.notify(r.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (isEdit) "budget.editEntry" else "budget.newEntry")) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                actions = {
                    TextButton(
                        enabled = !s.busy,
                        onClick = {
                            errors = form.errors(s.money, currency, subs, original, strings)
                            if (errors.isNotEmpty()) return@TextButton
                            if (entry != null && (entry.recurrenceParentId != null || (entry.isRecurring && form.recurring))) askSeries = true else save(false)
                        },
                    ) { Text(t(if (isEdit) "common.save" else "common.add")) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val types = buildList {
                add("expense" to t("budget.typeExpense"))
                add("income" to t("budget.typeIncome"))
                if (!isEdit) {
                    add("loan" to t("budget.typeLoan"))
                    if (canTransfer(s)) add("transfer" to t("budget.typeTransfer"))
                }
            }
            Segments(types, form.type, enabled = !isLoanPayment) { type ->
                when (type) {
                    "loan" -> { vm.loanRequest = null; nav.switchTo(BudgetRoutes.LOAN) }
                    "transfer" -> { vm.transferRequest = null; nav.switchTo(BudgetRoutes.TRANSFER) }
                    else -> {
                        val cats = if (type == "income") s.meta.incomeCategories else s.meta.expenseCategories
                        val category = form.category.takeIf { c -> cats.any { it.key == c } } ?: ""
                        form = form.copy(type = type, category = category, subcategory = BudgetLedger.subcategoryChoice(s.subcategories(category), form.subcategory).value)
                    }
                }
            }
            if (isLoanPayment) Text(t("budget.loanPaymentTypeLocked"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)

            AmountField(t("budget.amountLabel"), form.amount, { form = form.copy(amount = it); errors = errors - "amount" }, s.money, currency, error = errors["amount"], tag = "budget-amount")
            TextInput(t("budget.titleLabel"), form.title, { form = form.copy(title = it); errors = errors - "title" }, error = errors["title"], placeholder = t("budget.titlePlaceholder"), tag = "budget-title")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Picker(
                    t("budget.categoryLabel"),
                    categories.firstOrNull { it.key == form.category }?.let { BudgetText.category(it, strings) } ?: "",
                    categories.map { it.key to BudgetText.category(it, strings) },
                    Modifier.weight(1f),
                    error = errors["category"],
                ) { key ->
                    form = form.copy(category = key, subcategory = BudgetLedger.subcategoryChoice(s.subcategories(key), form.subcategory).value)
                    errors = errors - "category" - "subcategory"
                }
                TextButton(onClick = { newCategory = true }) { Text(t("budget.addCategory")) }
            }
            if (form.category.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val required = BudgetLedger.subcategoryChoice(subs, null).required
                    Picker(
                        t("budget.subcategoryLabel") + if (required) " *" else "",
                        subs.firstOrNull { it.key == form.subcategory }?.let { BudgetText.subcategory(it, strings) } ?: "",
                        subs.map { it.key to BudgetText.subcategory(it, strings) },
                        Modifier.weight(1f),
                        error = errors["subcategory"],
                    ) { form = form.copy(subcategory = it); errors = errors - "subcategory" }
                    TextButton(onClick = { newSubcategory = true }) { Text(t("budget.addSubcategory")) }
                }
            }
            DateField(t("budget.dateLabel"), form.date, { form = form.copy(date = it) }, error = errors["date"])
            if (s.accounts.accounts.isNotEmpty()) {
                Picker(
                    t("budget.accountLabel"),
                    s.accountName(form.accountId).ifEmpty { t("budget.noAccount") },
                    listOf<Int?>(null).map { it to t("budget.noAccount") } + s.accounts.accounts.map { it.id to it.name },
                    error = errors["account"],
                ) { form = form.copy(accountId = it) }
            }

            TextButton(onClick = { more = !more }) {
                Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(t("budget.entryMoreDetailsHint"))
            }
            if (more) {
                if (s.personal) {
                    Picker(
                        t("budget.visibilityLabel"),
                        strings.t("budget.visibility_${form.visibility}"),
                        BudgetLedger.VISIBILITY_LEVELS.map { it to strings.t("budget.visibility_$it") },
                    ) { form = form.copy(visibility = it) }
                    Text(strings.t("budget.visibilityHint_${form.visibility}"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                // ZUSTAENDIG (#1057): a label that moves no money; absent in a household of one.
                if (people.size > 1) {
                    Text(t("budget.responsibleLabel"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        people.forEach { p ->
                            val on = p.id in form.responsible
                            FilterChip(
                                selected = on,
                                onClick = { form = form.copy(responsible = if (on) form.responsible - p.id else form.responsible + p.id) },
                                label = { Text(p.name) },
                                leadingIcon = { MemberAvatar(p.name, p.color, p.picture, size = 20.dp) },
                            )
                        }
                    }
                    Text(t("budget.responsibleHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                // An occurrence does not carry its series' rhythm (#1546): it is changed at the first entry.
                if (entry?.recurrenceParentId == null) {
                    SwitchRow(t("budget.recurringLabel"), form.recurring, { form = form.copy(recurring = it) })
                }
                if (form.recurring && entry?.recurrenceParentId == null) {
                    RecurrenceFields(form.interval, form.intervalCount, { form = form.copy(interval = it) }, { form = form.copy(intervalCount = it) }, errors["interval"] ?: errors["intervalCount"])
                    SwitchRow(t("budget.virtualBudgetLabel"), form.virtual, { form = form.copy(virtual = it) }, hint = t("budget.virtualBudgetHint"))
                    SwitchRow(t("budget.confirmFirstLabel"), form.confirmFirst, { form = form.copy(confirmFirst = it) }, hint = t("budget.confirmFirstHint"))
                }
                if (vm.documentsAccess != "none") {
                    ReceiptsField(vm, form.receipts, canUpload = vm.documentsAccess == "write") { form = form.copy(receipts = it) }
                }
            }
            if (isEdit) {
                OutlinedButton(onClick = { if (entry!!.isRecurring || entry.recurrenceParentId != null) askDelete = true else { vm.deleteEntry(entry); nav.back() } }) {
                    Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger)
                    Spacer(Modifier.width(6.dp))
                    Text(t("common.delete"), color = Yuvomi.colors.danger)
                }
            }
            Button(
                onClick = {
                    errors = form.errors(s.money, currency, subs, original, strings)
                    if (errors.isNotEmpty()) return@Button
                    if (entry != null && (entry.recurrenceParentId != null || (entry.isRecurring && form.recurring))) askSeries = true else save(false)
                },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t(if (isEdit) "common.save" else "common.add")) }
        }
    }

    if (askSeries) {
        SeriesChoiceDialog(
            title = t("budget.recurringSeriesScope"),
            thisLabel = t("budget.recurringThisOnly"),
            seriesLabel = t("budget.recurringEditSeries"),
            note = t("budget.recurringEditSeriesHint"),
            seriesDanger = false,
        ) { choice ->
            askSeries = false
            if (choice != null) save(choice == "series")
        }
    }
    if (askDelete && entry != null) {
        val start = entry.isRecurring && entry.recurrenceParentId == null
        SeriesChoiceDialog(
            title = t("budget.recurringSeriesScope"),
            thisLabel = t("budget.recurringThisOnly"),
            seriesLabel = t("budget.recurringEntireSeries"),
            note = if (start) t("budget.recurringDeleteFirstHint") else null,
            seriesDanger = true,
        ) { choice ->
            askDelete = false
            if (choice != null) {
                vm.deleteEntry(entry, series = choice == "series")
                nav.back()
            }
        }
    }
    if (newCategory) {
        NameDialog(t("budget.newCategoryTitle"), t("budget.newCategoryPrompt"), placeholder = t("budget.newCategoryPlaceholder"), onDismiss = { newCategory = false }) { name ->
            newCategory = false
            scope.launch {
                vm.addCategory(name, form.type)?.let { key ->
                    form = form.copy(category = key, subcategory = BudgetLedger.subcategoryChoice(vm.state.value.subcategories(key), null).value)
                }
            }
        }
    }
    if (newSubcategory) {
        NameDialog(t("budget.newSubcategoryTitle"), t("budget.newSubcategoryPrompt"), placeholder = t("budget.newSubcategoryPlaceholder"), onDismiss = { newSubcategory = false }) { name ->
            newSubcategory = false
            scope.launch { vm.addSubcategory(form.category, name)?.let { form = form.copy(subcategory = it) } }
        }
    }
}

/** `budgetIntervalFieldsHtml`: weekly, monthly or yearly, every N units. */
@Composable
fun RecurrenceFields(interval: String, count: String, onInterval: (String) -> Unit, onCount: (String) -> Unit, error: String? = null, enabled: Boolean = true) {
    val strings = LocalStrings.current
    Picker(
        t("budget.recurringIntervalLabel"),
        BudgetText.intervalLabel(interval, strings),
        listOf("weekly", "monthly", "yearly").map { it to BudgetText.intervalLabel(it, strings) },
        error = error,
        enabled = enabled,
    ) { onInterval(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("rrule.labelEvery"))
        Spacer(Modifier.width(8.dp))
        TextInput("", count, { v -> onCount(v.filter { it.isDigit() }.take(2)) }, Modifier.width(88.dp), number = true)
        Spacer(Modifier.width(8.dp))
        Text(BudgetText.intervalUnit(interval, count.toIntOrNull() ?: 1, strings))
    }
}
