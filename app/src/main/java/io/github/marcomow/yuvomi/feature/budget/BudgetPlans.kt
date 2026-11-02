package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch

/** The plan tab (`public/pages/budget-plans.js`): category budgets and the savings goal of the month. */
@Composable
fun PlanTab(vm: BudgetViewModel, s: BudgetViewModel.UiState, editable: Boolean, add: MutableState<Boolean>) {
    val strings = LocalStrings.current
    var editing by remember { mutableStateOf<String?>(null) }
    val plans = s.plans
    when {
        s.plansLoading && plans == null -> LoadingBox()
        s.plansError -> ErrorBox("${t("budget.statsError")}\n${t("budget.statsErrorDescription")}", onRetry = vm::retryPlans)
        plans != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (!plans.isCurrentMonth) {
                item(key = "historic") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(t("budget.planHistoricNote"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
            }
            // A goal that is set is state and comes first; the prompt to set one comes last (R21 S7).
            plans.savings?.let { goal -> item(key = "savings") { SavingsCard(goal, s, editable) { editing = BudgetLedger.SAVINGS_PLAN_KEY } } }
            item(key = "title") { SectionTitle(t("budget.planCategoryBudgets")) }
            if (plans.plans.isEmpty()) {
                item(key = "empty") { EmptyState(t("budget.planEmptyTitle"), if (editable) t("budget.planEmptyDesc") else null) }
            }
            plans.plans.forEach { p ->
                item(key = "plan-${p.category}") { PlanRowItem(p, s, strings, editable) { editing = p.category } }
            }
            if (plans.savings == null && editable) {
                item(key = "savings-prompt") {
                    Card(Modifier.padding(16.dp).clickable { editing = BudgetLedger.SAVINGS_PLAN_KEY }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Savings, null, tint = Yuvomi.colors.module("budget"))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t("budget.planSavingsSetTitle"), style = MaterialTheme.typography.titleSmall)
                                Text(t("budget.planSavingsSetDesc"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                            }
                            Text(t("budget.planSetGoal"), color = Yuvomi.colors.module("budget"))
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Yuvomi.colors.module("budget"))
                        }
                    }
                }
            }
        }
    }
    editing?.let { category ->
        val savings = category == BudgetLedger.SAVINGS_PLAN_KEY
        val current = if (savings) plans?.savings?.planned else plans?.plans?.firstOrNull { it.category == category }?.planned
        PlanDialog(
            vm, s,
            title = if (savings) t("budget.planSavingsGoal") else BudgetText.category(category, s.meta, strings),
            hint = if (savings) t("budget.planSavingsHint") else null,
            categories = null,
            initialCategory = category,
            current = current,
        ) { editing = null }
    }
    if (add.value) {
        val planned = plans?.plans?.map { it.category }?.toSet().orEmpty()
        val options = s.meta.expenseCategories.filter { it.key !in planned }
        if (options.isEmpty()) {
            LaunchedEffect(Unit) {
                add.value = false
                vm.notify(strings.t("budget.planAllCategoriesBudgeted"))
            }
        } else {
            PlanDialog(vm, s, t("budget.planAddBudget"), null, options, options.first().key, null) { add.value = false }
        }
    }
}

/** The plan row: spent of planned, the bar, and what is left or over (`renderRows`). */
@Composable
private fun PlanRowItem(p: PlanRow, s: BudgetViewModel.UiState, strings: io.github.marcomow.yuvomi.core.i18n.Strings, editable: Boolean, onEdit: () -> Unit) {
    val tone = BudgetLedger.planTone(p.ratio, p.over)
    val colors = BudgetLedger.colorIndex(s.summary.byCategory, s.expenseCategoryKeys)
    val fill = when (tone) {
        "over" -> Yuvomi.colors.danger
        "near" -> Yuvomi.colors.warning
        else -> colors[p.category]?.let { seriesColor(it) } ?: Yuvomi.colors.module("budget")
    }
    val foot = when (p.over) {
        null -> null
        true -> strings.t("budget.planOverBy", "amount" to s.money.format(kotlin.math.abs(p.remaining), s.currency))
        false -> strings.t("budget.planLeft", "amount" to s.money.format(maxOf(0.0, p.remaining), s.currency))
    }
    BarRow(
        label = BudgetText.category(p.category, s.meta, strings),
        fraction = p.ratio.toFloat().coerceIn(0f, 1f),
        fill = fill,
        amount = "${s.money.format(p.actual, s.currency)} / ${s.money.format(p.planned, s.currency)}",
        share = null,
        onClick = if (editable) onEdit else null,
    ) {
        if (foot != null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.over == true) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = Yuvomi.colors.danger)
                else if (tone == "near") Icon(Icons.Outlined.Warning, null, Modifier.size(14.dp), tint = Yuvomi.colors.warning)
                Spacer(Modifier.width(4.dp))
                Text(foot, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        }
    }
}

/** The savings goal as a ring (`renderSavingsCard`). */
@Composable
private fun SavingsCard(goal: SavingsGoal, s: BudgetViewModel.UiState, editable: Boolean, onEdit: () -> Unit) {
    val tone = when (goal.met) {
        null -> "near"
        true -> "under"
        false -> if (goal.actual < 0) "over" else "near"
    }
    val color = when (tone) {
        "under" -> Yuvomi.colors.success
        "over" -> Yuvomi.colors.danger
        else -> Yuvomi.colors.warning
    }
    val status = when (goal.met) {
        null -> null
        true -> t("budget.planSavingsMet")
        false -> if (goal.actual < 0) t("budget.planSavingsNegative") else t("budget.planSavingsShort", "amount" to s.money.format(maxOf(0.0, goal.remaining), s.currency))
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Card(Modifier.padding(16.dp).let { if (editable) it.clickable(onClick = onEdit) else it }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                val ratio = goal.ratio.coerceIn(0.0, 1.0).toFloat()
                Canvas(Modifier.size(72.dp)) {
                    val stroke = 8.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(color, -90f, 360f * ratio, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("${BudgetLedger.percent(goal.ratio)}%", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t("budget.planSavingsGoal"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.money.format(goal.actual, s.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(" / ${s.money.format(goal.planned, s.currency)}", style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                }
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

/** `openAddPlan` and `openPlanEditor`: the monthly amount, with Delete for an existing one. */
@Composable
private fun PlanDialog(
    vm: BudgetViewModel,
    s: BudgetViewModel.UiState,
    title: String,
    hint: String?,
    categories: List<BudgetCategory>?,
    initialCategory: String,
    current: Double?,
    close: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(initialCategory) }
    var amount by remember { mutableStateOf(current?.let { s.money.toInput(it, s.currency) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                if (categories != null) {
                    Picker(
                        t("budget.categoryLabel"),
                        categories.firstOrNull { it.key == category }?.let { BudgetText.category(it, strings) } ?: "",
                        categories.map { it.key to BudgetText.category(it, strings) },
                    ) { category = it }
                }
                AmountField(t("budget.planMonthlyAmount"), amount, { amount = it; error = null }, s.money, s.currency, error = error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val problem = vm.savePlan(category, amount, current)
                    if (problem == null || problem.isEmpty()) {
                        if (problem == null) close()
                    } else {
                        error = problem
                    }
                }
            }) { Text(t(if (categories != null) "common.add" else "common.save")) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { close(); vm.deletePlan(category) }) { Text(t("common.delete"), color = Yuvomi.colors.danger) }
                TextButton(onClick = close) { Text(t("common.cancel")) }
            }
        },
    )
}

