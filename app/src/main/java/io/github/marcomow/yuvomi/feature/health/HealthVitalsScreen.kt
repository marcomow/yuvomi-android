package io.github.marcomow.yuvomi.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.LocalDate

/** docs/spec/health.md, HEALTH-R10 to R16 (`renderVitalsShell`, `renderCards`, `renderDetail`, `openVitalModal`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthVitalsScreen(vm: VitalsViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val view by vm.view.collectAsState()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId) && !s.offline
    var editor by remember { mutableStateOf<Pair<Vital?, String>?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    val today = shell.time.today()
    LaunchedEffect(Unit) { if (vm.hub.takeAdd("vitals")) editor = null to view.selected }

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.vitals"),
        back = back,
        fab = {
            if (canEdit) ExtendedFloatingActionButton(
                onClick = { editor = null to view.selected },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(t("health.vitals.addShort")) },
            )
        },
    ) { _ ->
        val rows = vm.rows()
        if (rows.isEmpty()) {
            item(key = "empty") {
                AreaEmpty("health.vitals.emptyTitle", "health.vitals.emptyDesc", if (canEdit) "health.vitals.add" else null) { editor = null to view.selected }
            }
            item(key = "disclaimer") { Disclaimer() }
            return@HealthScaffold
        }
        // One period row over cards and chart (`vitalStepperMarkup`).
        item(key = "range") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                HealthVitals.RANGES.forEachIndexed { i, r ->
                    SegmentedButton(selected = view.range == r, onClick = { vm.setRange(r) }, shape = SegmentedButtonDefaults.itemShape(i, HealthVitals.RANGES.size)) {
                        Text(t("health.vitals.range.$r"))
                    }
                }
            }
            val series = HealthVitals.series(rows, view.selected, view.range, view.anchor, today)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.step(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, t("health.vitals.prevPeriod")) }
                Text(
                    if (series.gran == "hour") shell.formats.weekdayDate(series.from) else "${shell.formats.date(series.from, today)} – ${shell.formats.date(series.to, today)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { vm.step(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, t("health.vitals.nextPeriod")) }
            }
        }
        val measured = HealthVitals.METRICS.filter { m -> rows.any { it.type == m.type } }
        val unmeasured = HealthVitals.METRICS - measured.toSet()
        measured.forEach { metric ->
            item(key = "card-${metric.type}") { MetricCard(metric, rows, view, today, selected = metric.type == view.selected) { vm.select(metric.type) } }
        }
        if (unmeasured.isNotEmpty()) {
            item(key = "more") {
                Row(Modifier.fillMaxWidth().clickable { moreOpen = !moreOpen }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("health.vitals.moreMetrics"), style = MaterialTheme.typography.titleSmall)
                        Text(unmeasured.joinToString(", ") { strings.t(it.labelKey) }, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                    Icon(if (moreOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (moreOpen) unmeasured.forEach { metric ->
                item(key = "more-${metric.type}") {
                    Row(Modifier.fillMaxWidth().clickable { if (canEdit) editor = null to metric.type }.padding(horizontal = 24.dp, vertical = 10.dp)) {
                        Text(t(metric.labelKey), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(t("health.vitals.noValue"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                }
            }
        }

        // The detail of the selected kind: chart and recent measurements (`renderDetail`).
        val metric = HealthVitals.metric(view.selected) ?: HealthVitals.METRICS.first()
        item(key = "chart") {
            SectionTitle(t(metric.labelKey))
            VitalChart(metric, HealthVitals.series(rows, metric.type, view.range, view.anchor, today))
        }
        val recent = rows.filter { it.type == metric.type }.sortedByDescending { it.measuredAt }.take(8)
        if (recent.isNotEmpty()) {
            item(key = "recent-title") { SectionTitle(t("health.vitals.recentMeasurements")) }
            recent.forEach { row ->
                item(key = "row-${row.id}") {
                    Row(
                        Modifier.fillMaxWidth().let { if (canEdit) it.clickable { editor = row to row.type } else it }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val at = shell.time.parse(row.measuredAt)
                            Text(
                                listOfNotNull(HealthTimes.date(row.measuredAt)?.let { shell.formats.date(it, today) }, at?.takeIf { row.measuredAt.length >= 16 }?.let { shell.formats.time(it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Yuvomi.colors.textSecondary,
                            )
                            row.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(HealthVitals.readingText(metric, row, strings), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }

    editor?.let { (row, type) ->
        VitalEditor(vm, row, type) { editor = null }
    }
}

@Composable
private fun MetricCard(metric: VitalMetric, rows: List<Vital>, view: VitalsViewModel.View, today: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val series = HealthVitals.series(rows, metric.type, view.range, view.anchor, today)
    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t(metric.labelKey), style = MaterialTheme.typography.titleSmall, color = if (selected) Yuvomi.colors.module("health") else MaterialTheme.colorScheme.onSurface)
                val delta = if (metric.format == "scale") "" else HealthVitals.deltaText(metric, series.deltas[0], strings)
                if (delta.isNotEmpty()) Text(delta, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            val (value, unit) = HealthVitals.cardValue(metric, series.latest, strings)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
}

/** One chart per axis: blood pressure in mmHg, the pulse on its own (`chartMarkup`, `axes`). */
@Composable
private fun VitalChart(metric: VitalMetric, series: HealthVitals.Series) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    if (!series.hasData) {
        Text(t("health.vitals.noData"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(16.dp))
        return
    }
    val colors = listOf(Yuvomi.colors.module("health"), MaterialTheme.colorScheme.tertiary, Yuvomi.colors.warning)
    val today = shell.time.today()
    // A day is drawn reading by reading; longer periods by bucket average.
    val slots: List<List<Double?>> = if (series.gran == "hour") (0..2).map { c -> series.readings.map { it.values[c] } }
    else (0..2).map { c -> series.points.map { it.avg[c] } }
    val label: (String) -> String = { key ->
        when (series.gran) {
            "month" -> shell.formats.date(LocalDate.parse("$key-01"), today)
            "hour" -> key
            else -> shell.formats.date(LocalDate.parse(key), today)
        }
    }
    val xLabels = if (series.gran == "hour") {
        val first = series.readings.first()
        val last = series.readings.last()
        "%02d:%02d".format(first.minutes / 60, first.minutes % 60) to "%02d:%02d".format(last.minutes / 60, last.minutes % 60)
    } else label(series.points.first().key) to label(series.points.last().key)
    val minSpan = when (metric.type) {
        "bp" -> 40.0; "glucose" -> if (series.unit == "mmol/L") 2.0 else 40.0; "weight" -> if (series.unit == "lb") 10.0 else 5.0
        "height" -> 10.0; "head_circumference" -> 4.0; "spo2" -> 10.0; "temp" -> if (series.unit == "°F") 4.0 else 2.0; "sleep" -> 4.0
        else -> 0.0
    }
    val format: (Double) -> String = { HealthVitals.channelText(metric, it, strings) }
    if (metric.format == "pair") {
        LineChart(
            listOf(ChartLine(slots[0], colors[0], t(metric.channelLabelKeys[0])), ChartLine(slots[1], colors[1], t(metric.channelLabelKeys[1]))),
            xLabels, { strings.num(it, 0) }, t("health.vitals.chartTitle"), minSpan = minSpan,
        )
        if (slots[2].any { it != null }) {
            Text(t(metric.channelLabelKeys[2]), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            LineChart(listOf(ChartLine(slots[2], colors[2], t(metric.channelLabelKeys[2]))), xLabels, { strings.num(it, 0) }, t("health.vitals.chartTitle"), minSpan = 40.0, height = 120)
        }
    } else {
        LineChart(listOf(ChartLine(slots[0], colors[0], t(metric.labelKey))), xLabels, format, t("health.vitals.chartTitle"), minSpan = minSpan)
    }
    val counted = if (series.gran == "hour") series.readings.size else series.points.count { it.count > 0 }
    if (counted < 2) Text(t("health.vitals.sparse"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** `openVitalModal`: one form per kind, the plausibility line under the values, delete when editing. */
@Composable
private fun VitalEditor(vm: VitalsViewModel, row: Vital?, type: String, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var f by remember {
        mutableStateOf(
            HealthForms.vitalForm(row, type, shell.time.today(), shell.time.now().toLocalTime().toString().take(5), vm.hub.defaultVisibility("vital:$type")),
        )
    }
    val metric = HealthVitals.metric(f.type)
    EditorDialog(
        title = t(if (row == null) "health.vitals.add" else "health.vitals.edit"),
        onDismiss = close,
        onSave = { vm.save(f, row, close) },
        saving = s.busy,
    ) {
        if (row == null) {
            Picker(t("health.vitals.field.type"), t(metric?.labelKey ?: "health.vitals.metric.bp"), HealthVitals.METRICS.map { it.type to strings.t(it.labelKey) }) { tp ->
                val m = HealthVitals.metric(tp)
                f = f.copy(type = tp, unit = m?.units?.firstOrNull().orEmpty(), visibility = vm.hub.defaultVisibility("vital:$tp"))
            }
        }
        when (metric?.format) {
            "pair" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(t("health.vitals.field.systolic"), f.systolic, Modifier.weight(1f)) { f = f.copy(systolic = it) }
                    NumberField(t("health.vitals.field.diastolic"), f.diastolic, Modifier.weight(1f)) { f = f.copy(diastolic = it) }
                }
                NumberField(t("health.vitals.field.pulse"), f.pulse) { f = f.copy(pulse = it) }
            }
            "duration" -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(t("health.vitals.field.hours"), f.hours, Modifier.weight(1f), decimal = false) { f = f.copy(hours = it) }
                NumberField(t("health.vitals.field.minutes"), f.minutes, Modifier.weight(1f), decimal = false) { f = f.copy(minutes = it) }
            }
            "scale" -> {
                Text(t("health.vitals.field.mood"), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(HealthVitals.MOOD_KEYS.mapIndexed { i, k -> (i + 1) to strings.t(k) }, f.mood, allowNone = false) { f = f.copy(mood = it) }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(t("health.vitals.field.value"), f.value, Modifier.weight(1f)) { f = f.copy(value = it) }
                if ((metric?.units?.size ?: 0) > 1) {
                    Picker(t("health.vitals.field.unit"), f.unit, metric!!.units.map { it to it }, Modifier.weight(1f)) { f = f.copy(unit = it) }
                } else if (metric?.units?.isNotEmpty() == true) {
                    Text(metric.units.first(), modifier = Modifier.padding(top = 20.dp))
                }
            }
        }
        // `vitalPlausibilityText`: the impossible stops the save, the unusual only asks to check.
        val issues = HealthVitals.plausibility(f.type, f.unitSent(), f.values())
        val line = when {
            "bpOrder" in issues.values -> "health.errorVitalBpOrder"
            "impossible" in issues.values -> "health.errorVitalImpossible"
            "high" in issues.values -> "health.vitals.unusualHigh"
            "low" in issues.values -> "health.vitals.unusualLow"
            else -> null
        }
        line?.let { Text(t(it), style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("health.error")) Yuvomi.colors.danger else Yuvomi.colors.warning) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DateField(t("health.vitals.field.measuredAt"), f.date) { d -> d?.let { f = f.copy(date = it) } }
            TimeField(t("health.meds.log.timeLabel"), f.time) { f = f.copy(time = it) }
        }
        TextInput(t("health.vitals.field.note"), f.note, lines = 2) { f = f.copy(note = it) }
        VisibilityPicker(f.visibility, { f = f.copy(visibility = it) })
        if (row != null) DeleteButton { close(); vm.delete(row) }
    }
}
