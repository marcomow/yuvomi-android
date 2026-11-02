package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `formatLoanAmount`: a loan's amounts stand in its own currency (#582). */
private fun loanMoney(s: BudgetViewModel.UiState, loan: BudgetLoan, amount: Double) = s.money.format(amount, loan.currency ?: s.currency)

/** `formatRate`: "3.5 %". */
private fun rate(s: BudgetViewModel.UiState, r: Double?) = "${s.money.number(r ?: 0.0, 2)} %"

/** `loanInterestMeta`: the monthly payment and the rate phase. */
fun loanInterestMeta(s: BudgetViewModel.UiState, loan: BudgetLoan, dates: BudgetDates, strings: Strings): String {
    val it = loan.interest ?: return ""
    val monthly = strings.t("budget.loanMonthlyRate", "amount" to loanMoney(s, loan, it.monthlyPayment))
    val phase = when {
        it.mode == "fixed_then_variable" && (it.remainingAfterBinding ?: 0.0) > 0 -> strings.t(
            "budget.loanRateFixedThenVariable",
            "fixed" to rate(s, it.fixedRate),
            "until" to (it.bindingEndMonth?.let(dates::monthYear) ?: ""),
            "variable" to rate(s, it.followupRate),
        )
        it.mode == "variable" -> strings.t("budget.loanRateVariable", "rate" to rate(s, it.fixedRate))
        else -> strings.t("budget.loanRateFixed", "rate" to rate(s, it.fixedRate))
    }
    return "$monthly · $phase"
}

/** `loanSummaryCards`: the lead figure, then the others, by direction. */
private fun summaryCards(s: BudgetViewModel.UiState, strings: Strings): List<Pair<String, String>> {
    val summary = s.loans.summary
    fun money(n: Double?) = s.money.signed(n ?: 0.0, s.currency, Money.Role.TOTAL)
    val dirs = summary.byDirection
    val borrowed = if (dirs != null) dirs.borrowed?.takeIf { it.count > 0 } else LoanTotals(1, summary.hasInterest, summary.paidAmount, summary.remainingAmount, summary.remainingPrincipal, summary.remainingInstallments)
    val lent = dirs?.lent?.takeIf { it.count > 0 }
    val cards = mutableListOf<Pair<String, String>>()
    if (borrowed != null) {
        cards += strings.t(if (borrowed.hasInterest) "budget.loanRemainingPrincipal" else "budget.loanRemainingAmount") to money(borrowed.remainingPrincipal ?: borrowed.remainingAmount)
    }
    if (lent != null) cards += strings.t("budget.loanOwedToYou") to money(lent.remainingPrincipal ?: lent.remainingAmount)
    if (borrowed != null && lent != null) {
        cards += strings.t("budget.loanPaidAmount") to money(borrowed.paidAmount)
        cards += strings.t("budget.loanReceivedAmount") to money(lent.paidAmount)
        return cards
    }
    val only = borrowed ?: lent ?: return cards
    cards += strings.t("budget.loanRemainingInstallments") to only.remainingInstallments.toString()
    cards += strings.t(if (borrowed != null) "budget.loanPaidAmount" else "budget.loanReceivedAmount") to money(only.paidAmount)
    return cards
}

/** The loans tab (`renderLoansPage`). */
@Composable
fun LoansTab(vm: BudgetViewModel, s: BudgetViewModel.UiState, dates: BudgetDates, nav: BudgetNav, editable: Boolean) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<BudgetLoan?>(null) }
    var menu by remember { mutableStateOf(false) }
    val loans = s.shownLoans
    if (loans.isEmpty()) {
        EmptyState(t("budget.loansEmpty"), action = if (editable) t("budget.newLoan") else null) {
            vm.loanRequest = null
            nav.loanEditor()
        }
        return
    }
    val visible = s.visibleLoans
    val cards = summaryCards(s, strings)
    val payments = BudgetLedger.loanPayments(visible, s.loans.loans)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (cards.isNotEmpty()) {
            item(key = "summary") {
                Card(Modifier.padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        val (leadLabel, leadValue) = cards.first()
                        Text(leadLabel, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                        Text(leadValue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        cards.drop(1).forEach { (label, value) ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        item(key = "tools") {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Segments(
                    listOf("active" to t("budget.loanStatusActive"), "paid" to t("budget.loanStatusPaid"), "all" to t("budget.loanStatusAll")),
                    s.loanStatus,
                    Modifier.weight(1f),
                ) { vm.setLoanStatus(it) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        Text(t("budget.loanSortLabel"), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        listOf("start" to "budget.loanSortStart", "rate" to "budget.loanSortRate", "balance" to "budget.loanSortBalance").forEach { (id, key) ->
                            MenuCheck(strings.t(key), s.loanSort == id) { menu = false; vm.setLoanSort(id) }
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                s.loanFilterId?.let { id ->
                    s.loans.loans.firstOrNull { it.id == id }?.let { l ->
                        Text(t("budget.loanFilterActive", "title" to l.title), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.toggleLoanFilter(null) }) { Text(t("budget.clearLoanFilter")) }
                    }
                }
                if (s.loanSort != "start") {
                    val key = if (s.loanSort == "rate") "budget.loanSortRate" else "budget.loanSortBalance"
                    TextButton(onClick = { menu = true }) { Text(t("budget.loanSortActive", "sort" to strings.t(key))) }
                }
            }
            if (s.loans.summary.hasForeignCurrency) {
                Text(
                    t("budget.loanSummaryConverted", "currency" to (s.loans.summary.currency ?: s.currency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Yuvomi.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (visible.isEmpty()) {
            item(key = "none") { EmptyState(t("budget.loansEmpty")) }
        } else {
            val groups = BudgetLedger.groupLoans(visible, s.loanSort)
            val shown = groups ?: listOf(BudgetLedger.LoanGroupRows("", visible))
            shown.forEach { g ->
                if (g.id.isNotEmpty()) {
                    item(key = "group-${g.id}") {
                        SectionTitle(t(when (g.id) { "borrowed" -> "budget.loanDirectionBorrowedBadge"; "lent" -> "budget.loanDirectionLentBadge"; else -> "budget.loanStatusPaid" }))
                    }
                }
                g.loans.forEach { loan ->
                    item(key = "loan-${loan.id}") {
                        LoanCard(loan, s, dates, strings, editable, onOpen = { report = loan }, onPay = { vm.payLoan(loan) }, onFilter = { vm.toggleLoanFilter(loan.id) })
                    }
                }
            }
        }
        if (payments.isNotEmpty()) {
            item(key = "payments-title") { SectionTitle(t("budget.loanTransactions")) }
            val expanded = s.loanPaymentsExpanded
            payments.take(if (expanded) payments.size else BudgetLedger.LOAN_PAYMENTS_PREVIEW_ROWS).forEach { (loan, payment) ->
                item(key = "pay-${payment.id}") {
                    LoanPaymentRow(loan, payment, s, dates, strings, editable, onEdit = {
                        scope.launch {
                            vm.loanPaymentEntry(loan.id, payment.id)?.let { e ->
                                vm.entryRequest = BudgetViewModel.EntryRequest(entry = e)
                                nav.entryEditor()
                            }
                        }
                    }, onDelete = { vm.deleteLoanPayment(loan, payment) })
                }
            }
            if (payments.size > BudgetLedger.LOAN_PAYMENTS_PREVIEW_ROWS) {
                item(key = "payments-toggle") {
                    TextButton(onClick = vm::toggleLoanPayments, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        Text(if (expanded) t("budget.showFewerCategories") else t("budget.showAllPlanned", "count" to payments.size))
                    }
                }
            }
        }
    }
    report?.let { loan ->
        LoanReportSheet(loan, s, dates, strings, editable,
            onEdit = { report = null; vm.loanRequest = loan; nav.loanEditor() },
            onDelete = { report = null; vm.deleteLoan(loan) },
            onDismiss = { report = null },
        )
    }
}

/** `renderLoanCard`. */
@Composable
private fun LoanCard(loan: BudgetLoan, s: BudgetViewModel.UiState, dates: BudgetDates, strings: Strings, editable: Boolean, onOpen: () -> Unit, onPay: () -> Unit, onFilter: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val interest = loan.interest
    val lead = if (interest != null) loan.remainingPrincipal ?: loan.remainingAmount else loan.remainingAmount
    val leadTotal = interest?.principal ?: loan.totalAmount
    val paidPct = if (loan.totalAmount > 0) minOf(100, Math.round(loan.paidAmount / loan.totalAmount * 100).toInt()) else 0
    val nextDue = if (loan.dueDay != null && loan.nextDueDate != null) dates.date(loan.nextDueDate)
    else loan.nextDueMonth?.let(dates::monthYear) ?: strings.t("budget.loanPaidStatus")
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(loan.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${strings.t(if (loan.borrowed) "budget.loanDirectionBorrowedBadge" else "budget.loanDirectionLentBadge")} · ${loan.borrower} · " +
                    strings.t("budget.loanInstallmentMeta", "paid" to loan.paidInstallments, "total" to loan.installmentCount),
                style = MaterialTheme.typography.bodySmall,
                color = Yuvomi.colors.textSecondary,
            )
            if (interest != null) Text(loanInterestMeta(s, loan, dates, strings), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            Text(loanMoney(s, loan, lead), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                (if (interest != null) strings.t(if (loan.borrowed) "budget.loanRemainingPrincipal" else "budget.loanOwedToYou") + " · " else "") +
                    strings.t("budget.loanRemainingOf", "total" to loanMoney(s, loan, leadTotal)),
                style = MaterialTheme.typography.bodySmall,
                color = Yuvomi.colors.textSecondary,
            )
            if (loan.isForeignCurrency) {
                Text(
                    strings.t("budget.loanConvertedAmount", "amount" to s.money.format(lead * loan.exchangeRate, s.currency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Yuvomi.colors.textSecondary,
                )
            }
            LinearProgressIndicator(progress = { paidPct / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Yuvomi.colors.module("budget"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.t("budget.loanNextDue", "month" to nextDue) +
                        (loan.projectedEndMonth?.let { " · " + strings.t("budget.loanProjectedEnd", "month" to dates.monthYear(it)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    OutlinedButton(onClick = onPay, enabled = !loan.isSettled) {
                        Text(strings.t(if (loan.borrowed) "budget.loanBookInstallment" else "budget.loanReceiveRepayment"))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to loan.title)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        MenuCheck(strings.t("budget.filterLoanTransactions"), s.loanFilterId == loan.id) { menu = false; onFilter() }
                    }
                }
            }
        }
    }
}

/** `renderLoanPaymentEntry`: one payment with its installment number. */
@Composable
private fun LoanPaymentRow(loan: BudgetLoan, payment: LoanPayment, s: BudgetViewModel.UiState, dates: BudgetDates, strings: Strings, editable: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val title = BudgetLedger.loanRowTitle(loan, s.loans.loans)
    val installment = strings.t("budget.loanInstallmentNumber", "number" to payment.installmentNumber, "total" to loan.installmentCount)
    val amount = (if (loan.borrowed) -1 else 1) * payment.amount
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("${dates.date(payment.paidDate)} · $installment", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Text(
            s.money.signed(amount, loan.currency ?: s.currency, Money.Role.FLOW),
            color = if (loan.borrowed) MaterialTheme.colorScheme.onSurface else Yuvomi.colors.success,
        )
        if (editable) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to "$title · $installment")) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (payment.budgetEntryId != null) DropdownMenuItem(text = { Text(strings.t("common.edit")) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(strings.t("common.delete"), color = Yuvomi.colors.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** `openLoanReport`: the figures, the details and every payment; Edit and Delete with write access. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoanReportSheet(loan: BudgetLoan, s: BudgetViewModel.UiState, dates: BudgetDates, strings: Strings, editable: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val interest = loan.interest
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(t("budget.loanReportTitle"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(loan.borrower, style = MaterialTheme.typography.titleLarge)
                    Text(loan.title, style = MaterialTheme.typography.bodyMedium)
                }
                Badge(t(if (loan.status == "paid") "budget.loanStatusPaid" else "budget.loanStatusActive"))
            }
            val cells = if (interest != null) {
                listOf(
                    t("budget.loanPrincipalAmount") to loanMoney(s, loan, interest.principal),
                    t("budget.loanRemainingPrincipal") to loanMoney(s, loan, loan.remainingPrincipal ?: 0.0),
                    t("budget.loanStillToPay") to loanMoney(s, loan, loan.remainingAmount),
                    t("budget.loanPaidAmount") to loanMoney(s, loan, loan.paidAmount),
                    t("budget.loanRemainingInstallments") to (
                        loan.remainingInstallmentsForecast?.takeIf { it != loan.remainingInstallments }
                            ?.let { strings.t("budget.loanRemainingInstallmentsForecast", "forecast" to it, "plan" to loan.remainingInstallments) }
                            ?: loan.remainingInstallments.toString()
                        ),
                )
            } else {
                listOf(
                    t("budget.loanAmountLabel") to loanMoney(s, loan, loan.totalAmount),
                    t("budget.loanRemainingAmount") to loanMoney(s, loan, loan.remainingAmount),
                    t("budget.loanPaidAmount") to loanMoney(s, loan, loan.paidAmount),
                    t("budget.loanRemainingInstallments") to loan.remainingInstallments.toString(),
                )
            }
            cells.forEach { (label, value) -> DetailRow(label, value) }
            DetailRow(t("budget.loanAccountLabel"), s.accountName(loan.accountId))
            DetailRow(t("budget.loanDetailStartMonthLabel"), if (loan.startMonth.isNotEmpty()) dates.monthYear(loan.startMonth) else "")
            DetailRow(t("budget.loanDueDayLabel"), loan.dueDay?.toString().orEmpty())
            DetailRow(t("budget.loanInitialRepaymentLabel"), interest?.initialRepaymentRate?.let { s.money.number(it, 2) }.orEmpty())
            DetailRow(t("budget.loanInterestModeLabel"), if (interest != null) loanInterestMeta(s, loan, dates, strings) else "")
            DetailRow(t("budget.loanNotesLabel"), loan.notes.orEmpty())
            if (loan.isForeignCurrency) {
                val remaining = if (interest != null) loan.remainingPrincipal ?: 0.0 else loan.remainingAmount
                Text(
                    strings.t(
                        "budget.loanRateInfo",
                        "currency" to (loan.currency ?: ""),
                        "rate" to s.money.number(loan.exchangeRate, 6),
                        "base" to s.currency,
                        "amount" to s.money.format(remaining * loan.exchangeRate, s.currency),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Yuvomi.colors.textSecondary,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(t("budget.loanTransactions"), style = MaterialTheme.typography.titleSmall)
            val payments = loan.payments.sortedWith(compareByDescending<LoanPayment> { it.paidDate }.thenByDescending { it.installmentNumber })
            if (payments.isEmpty()) {
                Text(t("budget.loanNoTransactions"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            payments.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.t("budget.loanInstallmentNumber", "number" to p.installmentNumber, "total" to loan.installmentCount), fontWeight = FontWeight.Medium)
                        Text(dates.date(p.paidDate), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                    Text(loanMoney(s, loan, p.amount), fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDelete) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onEdit) { Text(t("common.edit")) }
                }
            } else {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(t("common.close")) }
            }
        }
    }
}

/**
 * The loan form (`loanFormFieldsHtml`): direction and partner, amount and installments (or the
 * interest model with its live preview), first due month, due day, the installments already paid
 * of a running loan, and title, account, currency and notes under "more details".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanEditorScreen(vm: BudgetViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val loan = remember { vm.loanRequest }
    val scope = rememberCoroutineScope()
    val key = remember { YuvomiClient.newIdempotencyKey() }
    val dates = remember(strings) { BudgetDates(strings.locale) }
    var form by remember {
        mutableStateOf(loan?.let { LoanForm.of(it, s.money, s.currency) } ?: LoanForm(start = s.month.ifEmpty { s.today.toString().take(7) }, currency = s.currency))
    }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var preview by remember { mutableStateOf("") }
    var term by remember { mutableStateOf<Int?>(null) }
    val foreign = form.currency.isNotEmpty() && form.currency != s.currency
    var more by remember { mutableStateOf(loan != null && (loan.title.isNotEmpty() || !loan.notes.isNullOrEmpty() || loan.accountId != null) || foreign) }
    val currency = form.currency.ifEmpty { s.currency }

    // The interest preview, 300 ms after the last change (`wireLoanInterestFields`).
    LaunchedEffect(form.mode, form.principal, form.fixedRate, form.repayment, form.fixedPeriod, form.followupRate, form.currency) {
        term = null
        val body = form.previewBody(s.money)
        if (body == null) {
            preview = ""
            return@LaunchedEffect
        }
        delay(300)
        val answer = vm.previewLoan(body)
        preview = when {
            answer == null -> ""
            !answer.ok -> BudgetText.previewRefusal(answer.reason, answer.max, strings)
            else -> {
                term = answer.totalMonths.takeIf { it >= 1 }
                strings.t(
                    "budget.loanPreviewSummary",
                    "rate" to s.money.format(answer.monthlyPayment, currency),
                    "term" to strings.t("budget.loanTermYearsMonths", "years" to answer.totalMonths / 12, "months" to answer.totalMonths % 12),
                    "interest" to s.money.format(answer.totalInterest, currency),
                )
            }
        }
    }
    // `suggest`: the months since the start, until the member types a number of their own.
    LaunchedEffect(form.start, form.installments, form.mode, term) {
        if (loan == null && !form.paidTouched) {
            val cap = if (form.mode == "none") form.installments.trim().toIntOrNull()?.takeIf { it >= 1 } else term ?: 0
            BudgetLedger.suggestedPaidInstallments(form.start, s.today, cap)?.let { form = form.copy(paid = it.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(if (loan != null) "budget.editLoan" else "budget.newLoan")) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val borrowed = form.direction == "borrowed"
            Picker(
                t("budget.loanDirectionLabel"),
                t(if (borrowed) "budget.loanDirectionBorrowed" else "budget.loanDirectionLent"),
                listOf("lent" to t("budget.loanDirectionLent"), "borrowed" to t("budget.loanDirectionBorrowed")),
            ) { form = form.copy(direction = it) }
            TextInput(
                t(if (borrowed) "budget.loanLenderLabel" else "budget.loanBorrowerLabel"),
                form.borrower,
                { form = form.copy(borrower = it.take(100)); errors = errors - "borrower" },
                placeholder = t(if (borrowed) "budget.loanLenderPlaceholder" else "budget.loanBorrowerPlaceholder"),
                error = errors["borrower"],
                tag = "loan-borrower",
            )
            Picker(
                t("budget.loanInterestModeLabel"),
                t(interestKey(form.mode)),
                listOf("none", "fixed", "variable", "fixed_then_variable").map { it to t(interestKey(it)) },
            ) { form = form.copy(mode = it) }
            if (form.mode == "none") {
                AmountField(t("budget.loanAmountLabel"), form.amount, { form = form.copy(amount = it); errors = errors - "amount" }, s.money, currency, error = errors["amount"], tag = "loan-amount")
                TextInput(t("budget.loanInstallmentsLabel"), form.installments, { v -> form = form.copy(installments = v.filter { it.isDigit() }.take(3)); errors = errors - "installments" }, number = true, error = errors["installments"], tag = "loan-installments")
            } else {
                AmountField(t("budget.loanPrincipalLabel"), form.principal, { form = form.copy(principal = it); errors = errors - "principal" }, s.money, currency, error = errors["principal"])
                TextInput(t(if (form.mode == "variable") "budget.loanVariableRateLabel" else "budget.loanFixedRateLabel"), form.fixedRate, { form = form.copy(fixedRate = it); errors = errors - "fixedRate" }, number = true, error = errors["fixedRate"])
                TextInput(t("budget.loanInitialRepaymentLabel"), form.repayment, { form = form.copy(repayment = it); errors = errors - "repayment" }, number = true, error = errors["repayment"])
                if (form.mode == "fixed_then_variable") {
                    TextInput(t("budget.loanFixedPeriodLabel"), form.fixedPeriod, { v -> form = form.copy(fixedPeriod = v.filter { it.isDigit() }.take(3)); errors = errors - "fixedPeriod" }, number = true, error = errors["fixedPeriod"])
                    TextInput(t("budget.loanFollowupRateLabel"), form.followupRate, { form = form.copy(followupRate = it); errors = errors - "followupRate" }, number = true, error = errors["followupRate"])
                }
                if (form.mode == "variable") Text(t("budget.loanVariableHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            MonthField(t("budget.loanStartMonthLabel"), form.start, { form = form.copy(start = it); errors = errors - "start" }, dates::monthYear, error = errors["start"])
            TextInput(t("budget.loanDueDayLabel"), form.dueDay, { v -> form = form.copy(dueDay = v.filter { it.isDigit() }.take(2)); errors = errors - "dueDay" }, number = true, hint = t("budget.loanDueDayHint"), error = errors["dueDay"])
            if (loan == null) {
                TextInput(
                    t("budget.loanPaidInstallmentsLabel"),
                    form.paid,
                    { v -> form = form.copy(paid = v.filter { it.isDigit() }.take(3), paidTouched = true); errors = errors - "paid" },
                    number = true,
                    hint = t("budget.loanPaidInstallmentsHint"),
                    error = errors["paid"],
                )
            }
            TextButton(onClick = { more = !more }) {
                Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(t("budget.loanMoreDetailsHint"))
            }
            if (more) {
                TextInput(t("budget.loanTitleLabel"), form.title, { form = form.copy(title = it.take(200)); errors = errors - "title" }, placeholder = t("budget.loanTitlePlaceholder"), error = errors["title"])
                if (s.accounts.accounts.isNotEmpty()) {
                    Picker(
                        t("budget.loanAccountLabel"),
                        s.accountName(form.accountId).ifEmpty { t("budget.noAccount") },
                        listOf<Int?>(null).map { it to t("budget.noAccount") } + s.accounts.accounts.map { it.id to it.name },
                        error = errors["account"],
                    ) { form = form.copy(accountId = it) }
                }
                Picker(t("budget.loanCurrencyLabel"), currency, CURRENCIES.let { if (currency in it) it else listOf(currency) + it }.map { it to currencyLabel(it, strings.locale) }, error = errors["currency"]) {
                    form = form.copy(currency = it, exchangeRate = if (it != form.currency) "" else form.exchangeRate)
                }
                if (currency != s.currency) {
                    TextInput(t("budget.loanExchangeRateLabel"), form.exchangeRate, { form = form.copy(exchangeRate = it); errors = errors - "exchangeRate" }, number = true, error = errors["exchangeRate"])
                    Text(t("budget.loanExchangeRateHint", "currency" to currency, "base" to s.currency), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                TextInput(t("budget.loanNotesLabel"), form.notes, { form = form.copy(notes = it.take(1000)); errors = errors - "notes" }, singleLine = false, error = errors["notes"])
            }
            Button(
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        when (val r = vm.saveLoan(loan, form, key)) {
                            BudgetViewModel.SaveResult.Saved -> close()
                            is BudgetViewModel.SaveResult.Field -> if (r.field != null) {
                                if (r.field in setOf("title", "account", "currency", "exchangeRate", "notes")) more = true
                                errors = mapOf(r.field to r.message)
                            } else {
                                vm.notify(r.message)
                            }
                        }
                    }
                },
            ) { Text(t(if (loan != null) "common.save" else "common.add")) }
        }
    }
}

/** `appendCurrencyOptions`: the code with the currency's name in the UI language. */
private fun currencyLabel(code: String, locale: String): String =
    runCatching { code + " - " + java.util.Currency.getInstance(code).getDisplayName(java.util.Locale.forLanguageTag(locale)) }.getOrDefault(code)

private fun interestKey(mode: String) = when (mode) {
    "fixed" -> "budget.loanInterestFixed"
    "variable" -> "budget.loanInterestVariable"
    "fixed_then_variable" -> "budget.loanInterestFixedThenVariable"
    else -> "budget.loanInterestNone"
}

/** `CURRENCY_CODES` (`public/utils/currency-codes.js`), the list the server accepts. */
internal val CURRENCIES: List<String> = listOf(
    "AED", "ARS", "AUD", "BBD", "BOB", "BRL", "BSD", "BYN", "BZD", "CAD", "CHF", "CLP",
    "CNY", "COP", "CRC", "CUP", "CZK", "DKK", "DOP", "EUR", "GBP", "GTQ", "GYD",
    "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR", "IRR", "JMD", "JPY", "KRW", "KZT", "MOP",
    "MXN", "MYR", "NIO", "NOK", "NZD", "PAB", "PEN", "PHP", "PLN", "PYG", "RUB", "SAR",
    "SEK", "SGD", "SRD", "TRY", "TTD", "TWD", "UAH", "USD", "UYU", "VES", "VND", "XCD", "ZAR",
)
