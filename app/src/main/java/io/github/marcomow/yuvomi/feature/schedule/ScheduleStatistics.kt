package io.github.marcomow.yuvomi.feature.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** docs/spec/schedule.md, Statistics (SCHED-R30 to R34): the filter, the tiles and two bar charts. */
@Composable
internal fun StatisticsTab(s: ScheduleViewModel.UiState, vm: ScheduleViewModel, viewer: ScheduleRules.Viewer, text: ScheduleText) {
    val strings = LocalStrings.current
    val st = s.stats
    val data = s.data
    val bounds = vm.statsBounds(st)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // S-13: someone who is not an admin sees only themselves here.
                val owners = ScheduleRules.ownerOptions(data, viewer).map { it.id to it.displayName.ifEmpty { it.username } }
                SelectField(t("schedule.owner"), owners.ifEmpty { listOf(viewer.me to ScheduleRules.userName(data.users, viewer.me)) }, st.userId, onSelect = { id ->
                    vm.updateStats { it.copy(userId = id) }
                })
                Text(t("schedule.statisticsRange"), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
                val ranges = ScheduleRules.StatsRange.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ranges.forEachIndexed { i, r ->
                        SegmentedButton(selected = st.range == r, onClick = { vm.updateStats { it.copy(range = r) } }, shape = SegmentedButtonDefaults.itemShape(i, ranges.size)) {
                            Text(t(r.labelKey), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                when (st.range) {
                    ScheduleRules.StatsRange.MONTHS -> {
                        MonthStepper(t("schedule.monthFrom"), st.monthFrom, strings) { m -> vm.updateStats { it.copy(monthFrom = m) } }
                        MonthStepper(t("schedule.monthTo"), st.monthTo, strings) { m -> vm.updateStats { it.copy(monthTo = m) } }
                    }
                    ScheduleRules.StatsRange.CUSTOM -> {
                        DateField(t("schedule.rangeFrom"), st.from, { d -> if (d != null) vm.updateStats { it.copy(from = d) } })
                        DateField(t("schedule.rangeTo"), st.to, { d -> if (d != null) vm.updateStats { it.copy(to = d) } })
                    }
                    ScheduleRules.StatsRange.CURRENT -> Unit
                }
            }
        }
        val period = bounds?.let {
            t("schedule.statisticsFor", "user" to ScheduleRules.userName(data.users, st.userId), "from" to text.day(it.from), "to" to text.day(it.to))
        }
        when {
            st.loading || !st.loaded -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            st.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(t("common.errorGeneric"))
                TextButton(onClick = vm::loadStatistics) { Text(t("common.retry")) }
            }
            bounds == null -> Card { Text(t("schedule.invalidRange"), Modifier.padding(16.dp)) }
            st.entries.isEmpty() -> {
                Text(period.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                EmptyState(t("schedule.noStatistics"))
            }
            else -> Results(st, data, period.orEmpty(), strings)
        }
    }
}

/** `<input type="month">` as a stepper: previous, the month, next. */
@Composable
private fun MonthStepper(label: String, month: YearMonth, strings: Strings, onChange: (YearMonth) -> Unit) {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag(strings.locale)) + " " + month.year
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "$label −") }
        Text(name, style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "$label +") }
    }
}

@Composable
private fun Results(st: ScheduleViewModel.StatsState, data: ScheduleData, period: String, strings: Strings) {
    val summary = ScheduleRules.summary(st.entries)
    val weekly = data.preferences.weeklyHours ?: ScheduleRules.DEFAULT_WEEKLY_HOURS
    // S-24: off means off - no tile and no computing.
    val overtime = if (data.preferences.overtimeEnabled) ScheduleRules.overtime(st.entries, weekly) else null
    Text(period, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val note = if (summary.freeDays > 0) t("schedule.shifts") + " · " + t("schedule.freeDaysCount", "count" to summary.freeDays) else t("schedule.shifts")
        Metric(t("schedule.shiftCounts"), summary.totalCount.toString(), note, Modifier.weight(1f))
        Metric(t("schedule.totalHours"), ScheduleRules.formatHours(summary.totalMinutes, strings), t("schedule.plannedNote"), Modifier.weight(1f))
    }
    if (overtime?.over == true) {
        Metric(t("schedule.overtime"), "+" + ScheduleRules.formatHours(overtime.excessMinutes, strings), t("schedule.overtimeNote", "hours" to weekly), Modifier.fillMaxWidth(), warning = true)
    }
    ChartCard(t("schedule.shiftCounts"), t("schedule.shiftCountsDescription"), summary.values, { it.count.toString() }, { it.count.toFloat() })
    ChartCard(t("schedule.workedHours"), t("schedule.workedHoursDescription"), summary.hourValues, { ScheduleRules.formatHours(it.minutes, strings) }, { it.minutes.toFloat() })
}

/** `.metric-card`: label, value, note. */
@Composable
private fun Metric(label: String, value: String, note: String, modifier: Modifier, warning: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (warning) Yuvomi.colors.warning.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
}

/**
 * `statisticsRows`: one bar per shift type in its colour, scaled to the largest of this list,
 * with a floor so the smallest stays visible. Drawn on a Canvas.
 */
@Composable
private fun ChartCard(
    title: String,
    description: String,
    items: List<ScheduleRules.TypeStat>,
    valueFor: (ScheduleRules.TypeStat) -> String,
    magnitude: (ScheduleRules.TypeStat) -> Float,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            if (items.isEmpty()) {
                Text(t("schedule.noStatistics"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                return@Column
            }
            val max = maxOf(items.maxOf(magnitude), 1f)
            val track = MaterialTheme.colorScheme.surfaceVariant
            items.forEach { item ->
                val label = ScheduleRules.label(item.type)
                val value = valueFor(item)
                val scale = maxOf(0.03f, magnitude(item) / max)
                val color = shiftColor(item.type.color)
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(item.type.color, 10.dp)
                        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(value, fontWeight = FontWeight.SemiBold)
                    }
                    Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
                        val radius = CornerRadius(size.height / 2, size.height / 2)
                        drawRoundRect(track, size = size, cornerRadius = radius)
                        drawRoundRect(color, size = Size(size.width * scale, size.height), cornerRadius = radius)
                    }
                }
            }
        }
    }
}
