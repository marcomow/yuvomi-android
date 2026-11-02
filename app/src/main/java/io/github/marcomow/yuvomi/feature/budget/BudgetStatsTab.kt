package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import java.time.LocalDate
import kotlin.math.abs

/**
 * The statistics tab (`public/pages/budget-stats.js`): the trend (a running total within a month
 * or week), the expense shares as a ring, the categories against the previous period and the
 * plan, and the CSV export of the period. Drawn natively on a Compose canvas.
 */
@Composable
fun ReportsTab(vm: BudgetViewModel, s: BudgetViewModel.UiState, dates: BudgetDates, nav: BudgetNav, editable: Boolean) {
    val strings = LocalStrings.current
    val exporter = rememberCsvExport(vm)
    val d = s.stats
    when {
        s.statsLoading && d == null -> LoadingBox()
        s.statsError -> ErrorBox("${t("budget.statsError")}\n${t("budget.statsErrorDescription")}", onRetry = vm::retryStats)
        d == null -> LoadingBox()
        d.totals.income == 0.0 && d.totals.expenses == 0.0 && d.series.none { it.income != 0.0 || it.expenses != 0.0 } ->
            EmptyState(t("budget.statsEmptyTitle"), t("budget.statsEmptyDescription"), if (editable) t("budget.emptyAction") else null) {
                // The empty state adds an entry dated into the period it shows (#1775).
                vm.entryRequest = BudgetViewModel.EntryRequest(period = LocalDate.parse(d.from) to LocalDate.parse(d.to))
                nav.entryEditor()
            }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "trend") { TrendCard(d, s, dates, strings) }
            item(key = "donut") { DonutCard(d, s, strings) }
            item(key = "categories") { CompareCard(d, s.statsPrevious, s, dates, strings) }
            item(key = "export") {
                OutlinedButton(
                    onClick = { exporter("budget-${d.from}_${d.to}.csv", vm.statsExportQuery(d, s)) },
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(Icons.Outlined.Download, null)
                    Text(" " + t("budget.statsExport"))
                }
            }
        }
    }
}

@Composable
private fun TrendDelta(delta: Double, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (delta != 0.0) Icon(if (delta > 0) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, null, Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
        Text(" $text", style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
    }
}

/** `renderTrendChart`. */
@Composable
private fun TrendCard(d: BudgetStats, s: BudgetViewModel.UiState, dates: BudgetDates, strings: Strings) {
    val series = d.series
    val rawIncomes = series.map { it.income }
    val rawExpenses = series.map { abs(it.expenses) }
    val cumulative = series.isNotEmpty() && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(series[0].period)
    val incomes = if (cumulative) BudgetLedger.running(rawIncomes) else rawIncomes
    val expenses = if (cumulative) BudgetLedger.running(rawExpenses) else rawExpenses
    val max = maxOf(1.0, incomes.maxOrNull() ?: 0.0, expenses.maxOrNull() ?: 0.0)
    val axisMax = BudgetLedger.calmMax(max)
    val todayIndex = if (cumulative) BudgetLedger.futureStartIndex(series.map { it.period }, s.today.toString()) else -1
    val lastPast = if (todayIndex >= 0) todayIndex else series.lastIndex
    var markIndex = lastPast
    if (todayIndex < 0 && !cumulative) while (markIndex > 0 && rawIncomes[markIndex] == 0.0 && rawExpenses[markIndex] == 0.0) markIndex--
    var selected by remember(d) {
        var initial = series.lastIndex
        while (initial > 0 && series[initial].income == 0.0 && series[initial].expenses == 0.0) initial--
        mutableIntStateOf(if (todayIndex >= 0) markIndex else initial.coerceAtLeast(0))
    }
    val pointKey = if (cumulative) "budget.statsPointLabelCumulative" else "budget.statsPointLabel"
    val fmt = { v: Double -> s.money.format(v, s.currency) }
    val incomeColor = Yuvomi.colors.success
    val expenseColor = Yuvomi.colors.textSecondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = Yuvomi.colors.textSecondary
    val measurer = rememberTextMeasurer()
    val daily = cumulative
    val sameMonth = series.isNotEmpty() && series.first().period.take(7) == series.last().period.take(7)
    val summary = strings.t(
        "budget.statsTrendSummary",
        "periods" to series.size,
        "income" to fmt(rawIncomes.sum()),
        "expenses" to fmt(rawExpenses.sum()),
        "peak" to fmt(max),
    )
    Card(Modifier.padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(t(if (cumulative) "budget.statsTrendTitleCumulative" else "budget.statsTrendTitle"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (series.isNotEmpty()) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .semantics { contentDescription = summary }
                        .pointerInput(series) {
                            fun pick(x: Float) {
                                val left = 56.dp.toPx()
                                val right = size.width - 12.dp.toPx()
                                val step = if (series.size > 1) (right - left) / (series.size - 1) else 0f
                                selected = if (step == 0f) 0 else ((x - left) / step).toInt().coerceIn(0, series.lastIndex).let { i ->
                                    if (i < series.lastIndex && (x - left) - i * step > step / 2) i + 1 else i
                                }
                            }
                            detectTapGestures { pick(it.x) }
                        }
                        .pointerInput(series) {
                            detectDragGestures { change, _ ->
                                val left = 56.dp.toPx()
                                val right = size.width - 12.dp.toPx()
                                val step = if (series.size > 1) (right - left) / (series.size - 1) else 0f
                                if (step > 0f) selected = ((change.position.x - left + step / 2) / step).toInt().coerceIn(0, series.lastIndex)
                            }
                        },
                ) {
                    val left = 56.dp.toPx()
                    val right = size.width - 12.dp.toPx()
                    val top = 14.dp.toPx()
                    val bottom = size.height - 26.dp.toPx()
                    fun x(i: Int) = if (series.size > 1) left + (right - left) * i / (series.size - 1) else (left + right) / 2
                    fun y(v: Double) = (bottom - (bottom - top) * (v / axisMax)).toFloat()
                    // Grid and y labels: 0, the step, the top (`chartGridMarkup` with two steps).
                    listOf(0.0, axisMax / 2, axisMax).forEach { v ->
                        drawLine(grid, Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1f)
                        drawLabel(measurer, s.money.axis(v, s.currency), Offset(left - 6.dp.toPx(), y(v)), labelColor, alignEnd = true)
                    }
                    // A few x labels, evenly spread.
                    val every = maxOf(1, series.size / 6)
                    series.forEachIndexed { i, p ->
                        if (i % every == 0 || i == series.lastIndex) {
                            drawLabel(measurer, dates.axisLabel(p.period, daily, sameMonth), Offset(x(i), bottom + 12.dp.toPx()), labelColor, center = true)
                        }
                    }
                    if (todayIndex >= 0) {
                        drawLine(labelColor, Offset(x(todayIndex), top), Offset(x(todayIndex), bottom), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    fun line(values: List<Double>, from: Int, to: Int): Path = Path().apply {
                        for (i in from..to) if (i == from) moveTo(x(i), y(values[i])) else lineTo(x(i), y(values[i]))
                    }
                    val area = line(incomes, 0, lastPast).apply {
                        lineTo(x(lastPast), bottom)
                        lineTo(x(0), bottom)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(incomeColor.copy(alpha = 0.18f), incomeColor.copy(alpha = 0f)), top, bottom))
                    drawPath(line(incomes, 0, lastPast), incomeColor, style = Stroke(2.dp.toPx()))
                    drawPath(line(expenses, 0, lastPast), expenseColor, style = Stroke(2.dp.toPx()))
                    if (todayIndex >= 0) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        drawPath(line(incomes, todayIndex, series.lastIndex), incomeColor.copy(alpha = 0.5f), style = Stroke(2.dp.toPx(), pathEffect = dash))
                        drawPath(line(expenses, todayIndex, series.lastIndex), expenseColor.copy(alpha = 0.5f), style = Stroke(2.dp.toPx(), pathEffect = dash))
                    }
                    val sel = selected.coerceIn(0, series.lastIndex)
                    drawLine(grid, Offset(x(sel), top), Offset(x(sel), bottom), strokeWidth = 1.dp.toPx())
                    drawCircle(incomeColor, 4.dp.toPx(), Offset(x(sel), y(incomes[sel])))
                    drawCircle(expenseColor, 4.dp.toPx(), Offset(x(sel), y(expenses[sel])))
                }
                // The readout of the chosen point (`wireTrendPoints`).
                val p = series[selected.coerceIn(0, series.lastIndex)]
                Text(
                    strings.t(pointKey, "period" to dates.periodLabel(p.period), "income" to fmt(incomes[selected.coerceIn(0, series.lastIndex)]), "expenses" to fmt(expenses[selected.coerceIn(0, series.lastIndex)])),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // The legend with the totals, what is still planned, and the change (`legendItemHtml`).
            val comparison = d.comparison
            Legend(incomeColor, strings.t("budget.statsIncome"), incomes, rawIncomes, markIndex, todayIndex >= 0, comparison?.income, s, fmt)
            Legend(expenseColor, strings.t("budget.statsExpenses"), expenses, rawExpenses, markIndex, todayIndex >= 0, comparison?.expenses?.let { abs(it) }, s, fmt)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, shown: List<Double>, raw: List<Double>, markIndex: Int, running: Boolean, previous: Double?, s: BudgetViewModel.UiState, fmt: (Double) -> String) {
    val total = raw.sum()
    val value = if (running) shown.getOrNull(markIndex) ?: total else total
    val planned = if (running) s.money.round(total - value, s.currency).takeIf { it > 0 } ?: 0.0 else 0.0
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Text(" $label · ${fmt(value)}", style = MaterialTheme.typography.bodySmall)
        if (planned > 0) Text(" " + t("budget.statsPlannedRest", "amount" to fmt(planned)), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        if (previous != null) {
            val delta = s.money.round(total - previous, s.currency)
            TrendDelta(delta, s.money.signed(delta, s.currency, Money.Role.FLOW))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    alignEnd: Boolean = false,
    center: Boolean = false,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, color = color))
    val x = when {
        alignEnd -> at.x - layout.size.width
        center -> at.x - layout.size.width / 2f
        else -> at.x
    }.coerceIn(0f, maxOf(0f, size.width - layout.size.width))
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2f))
}

/** `renderDonut`: the expense shares, seven segments at most. */
@Composable
private fun DonutCard(d: BudgetStats, s: BudgetViewModel.UiState, strings: Strings) {
    val slices = BudgetLedger.donut(d.byCategory, s.expenseCategoryKeys)
    val total = slices.sumOf { it.value }
    if (total == 0.0) return
    val colors = slices.map { seriesColor(it.colorIndex) }
    val label = { slice: BudgetLedger.DonutSlice -> slice.category?.let { BudgetText.category(it, s.meta, strings) } ?: strings.t("budget.statsOtherCategories") }
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val sub = Yuvomi.colors.textSecondary
    val summary = strings.t(
        "budget.statsDonutSummary",
        "count" to slices.size,
        "top" to label(slices[0]),
        "pct" to Math.round(slices[0].value / total * 100),
        "total" to s.money.format(total, s.currency),
    )
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(t("budget.statsDonutTitle"), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(160.dp).padding(8.dp).semantics { contentDescription = summary }) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    var start = -90f
                    val gap = if (slices.size > 1) 1.5f else 0f
                    slices.forEachIndexed { i, slice ->
                        val sweep = (slice.value / total * 360).toFloat()
                        drawArc(colors[i], start, maxOf(sweep - gap, 0.5f), false, Offset(inset, inset), arc, style = Stroke(stroke))
                        start += sweep
                    }
                    val amount = measurer.measure(s.money.axis(total, s.currency), TextStyle(fontSize = 14.sp, color = textColor, fontWeight = FontWeight.SemiBold))
                    drawText(amount, topLeft = Offset((size.width - amount.size.width) / 2, size.height / 2 - amount.size.height))
                    val caption = measurer.measure(strings.t("budget.statsExpenses"), TextStyle(fontSize = 11.sp, color = sub))
                    drawText(caption, topLeft = Offset((size.width - caption.size.width) / 2, size.height / 2 + 2.dp.toPx()))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    slices.forEachIndexed { i, slice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                            Text(" ${label(slice)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${Math.round(slice.value / total * 100)}%", style = MaterialTheme.typography.bodySmall, color = sub)
                        }
                    }
                }
            }
        }
    }
}

/** `renderCatBars`: the categories against the previous period, and the plan of a month. */
@Composable
private fun CompareCard(d: BudgetStats, prev: BudgetStats?, s: BudgetViewModel.UiState, dates: BudgetDates, strings: Strings) {
    val plans = if (d.range == "month") d.plans else emptyMap()
    val colors = BudgetLedger.colorIndex(d.byCategory, s.expenseCategoryKeys)
    val blocks = listOf("expenses" to "budget.statsExpenses", "income" to "budget.statsIncome")
        .map { (kind, key) -> Triple(kind, key, BudgetLedger.compareRows(d.byCategory, prev?.byCategory, kind)) }
        .filter { (_, _, rows) -> rows.any { it.amount != 0.0 } }
    if (blocks.isEmpty()) return
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(t("budget.statsCategoryTitle"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            if (prev != null) {
                val period = when (d.range) {
                    "year" -> prev.from.take(4)
                    "month" -> dates.monthYear(prev.from.take(7))
                    else -> dates.range(prev.from, prev.to)
                }
                Text(t("budget.statsCompareNote", "period" to period), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
            }
            blocks.forEach { (kind, key, rows) ->
                fun targetOf(r: BudgetLedger.CompareRow): Double? = if (kind == "expenses") plans[r.category] else null
                val max = maxOf(rows.maxOf { abs(it.amount) }, rows.maxOf { targetOf(it) ?: 0.0 }, 1.0)
                val total = rows.sumOf { it.amount }
                val absTotal = rows.sumOf { abs(it.amount) }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(t(key), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(s.money.format(abs(total), s.currency), style = MaterialTheme.typography.labelLarge)
                }
                rows.forEach { r ->
                    val target = targetOf(r)
                    val over = target != null && target > 0 && abs(r.amount) > target
                    BarRow(
                        label = BudgetText.category(r.category, s.meta, strings),
                        fraction = (abs(r.amount) / max).toFloat(),
                        fill = if (kind == "expenses") seriesColor(colors[r.category]) else Yuvomi.colors.success,
                        amount = s.money.format(abs(r.amount), s.currency),
                        share = if (kind == "expenses" && absTotal > 0 && r.amount != 0.0) s.money.share(r.amount, absTotal) else null,
                        target = target?.let { (it / max).toFloat() },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (target != null) {
                                Text(
                                    strings.t("budget.planTargetOf", "amount" to s.money.format(target, s.currency)) + "  ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (over) Yuvomi.colors.danger else Yuvomi.colors.textSecondary,
                                )
                            }
                            if (prev != null) {
                                val delta = s.money.round(abs(r.amount) - abs(r.prev), s.currency)
                                TrendDelta(delta, s.money.signed(delta, s.currency, Money.Role.FLOW))
                            }
                        }
                    }
                }
            }
        }
    }
}

