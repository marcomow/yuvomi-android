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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

// ── Prevention ──

/** docs/spec/health.md, HEALTH-R39 to R41 (`renderPreventionShell`, `openPreventionModal`). */
@Composable
fun HealthPreventionScreen(vm: PreventionViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId) && !s.offline
    var editor by remember { mutableStateOf<PreventionRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    val today = shell.time.today()

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.prevention"),
        back = back,
        personLabel = t("health.prevention.personsLabel"),
        fab = { if (canEdit) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(t("newLabel.healthPrevention")) }) },
    ) { data ->
        val due = data.due.sortedBy { it.daysLeft }
        if (due.isNotEmpty()) {
            item(key = "due-title") { SectionTitle(t("health.prevention.dueTitle")) }
            due.forEach { item ->
                item(key = "due-${item.typeId}-${item.recordId}") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.typeName, style = MaterialTheme.typography.bodyMedium)
                            HealthTimes.date(item.dueOn)?.let { Text(shell.formats.date(it, today), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
                        }
                        val color = when {
                            item.daysLeft < 0 -> Yuvomi.colors.danger
                            item.daysLeft <= HealthPrevention.ALERT_DAYS -> Yuvomi.colors.warning
                            else -> Yuvomi.colors.textSecondary
                        }
                        Text(HealthPrevention.dueText(item.daysLeft, strings), style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
        }
        val groups = HealthPrevention.groups(vm.records())
        if (groups.isEmpty()) {
            item(key = "empty") { AreaEmpty("health.prevention.emptyTitle", "health.prevention.emptyDesc", if (canEdit) "health.prevention.add" else null) { creating = true } }
        }
        groups.forEach { rows ->
            item(key = "g-${rows.first().id}") { SectionTitle(HealthPrevention.label(rows.first(), data.types)) }
            rows.forEach { r ->
                item(key = "r-${r.id}") {
                    Column(Modifier.fillMaxWidth().let { if (canEdit) it.clickable { editor = r } else it }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(HealthTimes.date(r.givenOn)?.let { shell.formats.date(it, today) }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        val meta = listOfNotNull(r.doseNumber?.let { strings.t("health.prevention.field.doseShort", "value" to strings.num(it)) }, r.provider, r.batch)
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        r.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }
    if (creating) PreventionEditor(vm, null) { creating = false }
    editor?.let { r -> PreventionEditor(vm, r) { editor = null } }
}

@Composable
private fun PreventionEditor(vm: PreventionViewModel, record: PreventionRecord?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val types = s.data?.types.orEmpty()
    var f by remember { mutableStateOf(HealthForms.preventionForm(record, shell.time.today(), vm.hub.defaultVisibility("prevention"))) }
    EditorDialog(t(if (record == null) "health.prevention.add" else "health.prevention.edit"), close, { vm.save(f, record, close) }, saving = s.busy) {
        Picker(
            t("health.prevention.field.type"),
            types.firstOrNull { it.id == f.typeId }?.name ?: strings.t("health.prevention.field.noType"),
            listOf<Pair<Int?, String>>(null to strings.t("health.prevention.field.noType")) + types.map { it.id to it.name },
        ) { f = f.copy(typeId = it) }
        if (f.typeId == null) TextInput(t("health.prevention.field.name") + " *", f.name) { f = f.copy(name = it) }
        DateField(t("health.prevention.field.givenOn"), f.givenOn) { d -> d?.let { f = f.copy(givenOn = it) } }
        NumberField(t("health.prevention.field.doseNumber"), f.dose, decimal = false) { f = f.copy(dose = it) }
        TextInput(t("health.prevention.field.provider"), f.provider) { f = f.copy(provider = it) }
        TextInput(t("health.prevention.field.batch"), f.batch) { f = f.copy(batch = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(t("health.prevention.field.intervalMonths"), f.interval, Modifier.weight(1f), decimal = false) { f = f.copy(interval = it) }
            Picker(
                " ",
                strings.t(if (f.intervalUnit == "years") "rrule.unitYears" else "rrule.unitMonths"),
                listOf("months" to strings.t("rrule.unitMonths"), "years" to strings.t("rrule.unitYears")),
                Modifier.weight(1f),
            ) { f = f.copy(intervalUnit = it) }
        }
        DateField(t("health.prevention.field.nextDueOn"), f.nextDue, clearable = true) { f = f.copy(nextDue = it) }
        NumberField(t("health.prevention.field.reminderOffsetDays"), f.offset, decimal = false) { f = f.copy(offset = it) }
        TextInput(t("health.prevention.field.note"), f.note, lines = 2) { f = f.copy(note = it) }
        VisibilityPicker(f.visibility, { f = f.copy(visibility = it) }, openLabel = t("health.prevention.visibility.family"), privateLabel = t("health.prevention.visibility.private"))
        if (record != null) DeleteButton { close(); vm.delete(record) }
    }
}

// ── Labs ──

/** docs/spec/health.md, HEALTH-R42 to R45 (`renderLabsShell`, `labDetailMarkup`, `labTrendMarkup`). */
@Composable
fun HealthLabsScreen(vm: LabsViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId) && !s.offline
    var open by remember { mutableStateOf<Int?>(null) }
    var head by remember { mutableStateOf<LabReport?>(null) }
    var creating by remember { mutableStateOf(false) }
    var trend by remember { mutableStateOf<String?>(null) }
    val today = shell.time.today()

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.labs"),
        back = back,
        personLabel = t("health.labs.personsLabel"),
        fab = { if (canEdit) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(t("health.labs.addShort")) }) },
    ) { _ ->
        val reports = vm.reports()
        if (reports.isEmpty()) {
            item(key = "empty") { AreaEmpty("health.labs.emptyTitle", "health.labs.emptyDesc", if (canEdit) "health.labs.add" else null) { creating = true } }
            return@HealthScaffold
        }
        // The trend of one analyte across reports, with its reference range shaded (`labTrendChart`).
        val names = HealthLabs.names(reports)
        val chosen = trend?.takeIf { it in names } ?: HealthLabs.defaultAnalyte(reports)
        item(key = "trend") {
            SectionTitle(t("health.labs.trend.title"))
            Column(Modifier.padding(horizontal = 16.dp)) {
                ChoiceChips(names.map { it to it }, chosen, allowNone = false) { trend = it }
            }
            val points = chosen?.let { HealthLabs.trend(reports, it) }.orEmpty()
            if (points.size < 2) {
                Text(t("health.labs.trend.tooFew"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(16.dp))
            } else {
                val last = points.last()
                LineChart(
                    listOf(ChartLine(points.map { it.value }, Yuvomi.colors.module("health"), chosen.orEmpty())),
                    shell.formats.date(java.time.LocalDate.parse(points.first().date), today) to shell.formats.date(java.time.LocalDate.parse(last.date), today),
                    { strings.num(it, 2) },
                    t("health.labs.trend.title"),
                    band = if (last.refLow != null || last.refHigh != null) last.refLow to last.refHigh else null,
                    modifier = Modifier.padding(top = 8.dp),
                )
                last.unit?.let { Text(t("health.labs.trend.unit", "unit" to it), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
        item(key = "reports") { SectionTitle(t("health.labs.reportsTitle")) }
        reports.forEach { r ->
            item(key = "rep-${r.id}") {
                Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { open = r.id }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(HealthTimes.date(r.reportDate)?.let { shell.formats.date(it, today) }.orEmpty(), style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(r.labName, strings.t("health.labs.analyteCount", "count" to r.results.size)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        }
                        val abnormal = HealthLabs.abnormal(r)
                        if (abnormal > 0) AssistChip(onClick = { open = r.id }, label = { Text(t("health.labs.abnormalBadge", "count" to abnormal), color = Yuvomi.colors.danger) })
                    }
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }
    if (creating) LabHeadEditor(vm, null, close = { creating = false }) { id -> open = id }
    head?.let { r -> LabHeadEditor(vm, r, close = { head = null }) {} }
    open?.let { id ->
        val report = vm.report(id)
        if (report == null) LaunchedEffect(id) { if (!s.loading) open = null } else LabReportSheet(vm, report, canEdit, { open = null }, { head = report }) { trend = it; open = null }
    }
}

/** A report's analytes, with add, edit and delete (`labDetailMarkup`, `renderResultEditor`). */
@Composable
private fun LabReportSheet(vm: LabsViewModel, report: LabReport, canEdit: Boolean, close: () -> Unit, editHead: () -> Unit, showTrend: (String) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var result by remember { mutableStateOf<LabResult?>(null) }
    var form by remember { mutableStateOf<HealthForms.ResultForm?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    EditorDialog(HealthTimes.date(report.reportDate)?.let { shell.formats.date(it, shell.time.today()) }.orEmpty(), close, null) {
        report.labName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        report.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = editHead) { Icon(Icons.Outlined.Edit, null); Text(t("health.labs.edit"), Modifier.padding(start = 6.dp)) }
            OutlinedButton(onClick = { result = null; form = HealthForms.ResultForm() }) { Text(t("health.labs.results.add")) }
        }
        if (report.results.isEmpty()) Text(t("health.labs.noAnalytes"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        report.results.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { showTrend(r.analyte) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.analyte, style = MaterialTheme.typography.bodyMedium)
                    val ref = HealthLabs.reference(r, strings)
                    if (ref.isNotEmpty()) Text("${t("health.labs.col.reference")}: $ref", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                Text("${strings.num(r.value, 2)}${r.unit?.let { " $it" }.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                r.flag?.let { flag ->
                    Text(
                        " " + t("health.labs.flag.$flag"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (flag == "normal") Yuvomi.colors.success else Yuvomi.colors.danger,
                    )
                }
                if (canEdit) {
                    IconButton(onClick = { result = r; form = HealthForms.resultForm(r) }) { Icon(Icons.Outlined.Edit, t("health.labs.results.update")) }
                    IconButton(onClick = { vm.deleteResult(r) }) { Icon(Icons.Outlined.Delete, t("health.labs.results.delete")) }
                }
            }
        }
        form?.let { f ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(t("health.labs.results.analyte") + " *", f.analyte) { form = f.copy(analyte = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(t("health.labs.results.value") + " *", f.value, Modifier.weight(1f)) { form = f.copy(value = it) }
                        TextInput(t("health.labs.results.unit"), f.unit, Modifier.weight(1f)) { form = f.copy(unit = it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(t("health.labs.results.refLow"), f.low, Modifier.weight(1f)) { form = f.copy(low = it) }
                        NumberField(t("health.labs.results.refHigh"), f.high, Modifier.weight(1f)) { form = f.copy(high = it) }
                    }
                    val preview = HealthLabs.flag(HealthForms.number(f.value)?.takeIf { it.isFinite() }, HealthForms.number(f.low), HealthForms.number(f.high))
                    preview?.let { Text("${t("health.labs.results.flagPreview")} ${t("health.labs.flag.$it")}", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { form = null }) { Text(t("common.cancel")) }
                        TextButton(onClick = { vm.saveResult(report.id, result, f) { form = null } }, enabled = !s.busy) {
                            Text(t(if (result == null) "health.labs.results.add" else "health.labs.results.update"))
                        }
                    }
                }
            }
        }
        if (canEdit) DeleteButton { confirmDelete = true }
    }
    if (confirmDelete) ConfirmDialog(t("health.labs.deleteConfirm"), t("health.labs.deleteConfirmDetail"), t("common.delete"), { vm.delete(report, close) }, { confirmDelete = false })
}

@Composable
private fun LabHeadEditor(vm: LabsViewModel, report: LabReport?, close: () -> Unit, created: (Int) -> Unit) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    var date by remember { mutableStateOf(report?.let { HealthTimes.date(it.reportDate) } ?: shell.time.today()) }
    var lab by remember { mutableStateOf(report?.labName.orEmpty()) }
    var note by remember { mutableStateOf(report?.note.orEmpty()) }
    var visibility by remember { mutableStateOf(report?.visibility ?: vm.hub.defaultVisibility("labs")) }
    EditorDialog(t(if (report == null) "health.labs.add" else "health.labs.edit"), close, {
        vm.save(report, date, lab, note, visibility) { id -> close(); if (report == null) created(id) }
    }, saving = s.busy) {
        DateField(t("health.labs.field.reportDate"), date) { d -> d?.let { date = it } }
        TextInput(t("health.labs.field.labName"), lab) { lab = it }
        TextInput(t("health.labs.field.note"), note, lines = 2) { note = it }
        VisibilityPicker(visibility, { visibility = it }, openLabel = t("health.labs.visibility.family"), privateLabel = t("health.labs.visibility.private"))
        if (report == null) Text(t("health.labs.results.newHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

// ── Activity ──

/** docs/spec/health.md, HEALTH-R46 to R48 (`renderActivityShell`, `openActivityModal`). */
@Composable
fun HealthActivityScreen(vm: ActivityViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val anchor by vm.anchor.collectAsState()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId) && !s.offline
    var editor by remember { mutableStateOf<Activity?>(null) }
    var creating by remember { mutableStateOf(false) }
    val today = shell.time.today()
    LaunchedEffect(Unit) { if (vm.hub.takeAdd("activity")) creating = true }

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.activity"),
        back = back,
        personLabel = t("health.activity.personsLabel"),
        fab = { if (canEdit) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(t("health.activity.addShort")) }) },
    ) { _ ->
        val all = vm.rows()
        if (all.isEmpty()) {
            item(key = "empty") { AreaEmpty("health.activity.emptyTitle", "health.activity.emptyDesc", if (canEdit) "health.activity.add" else null) { creating = true } }
            return@HealthScaffold
        }
        val week = HealthActivity.week(all, anchor, today)
        val rows = HealthActivity.inWeek(all, week)
        item(key = "week") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.step(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, t("health.activity.prevWeek")) }
                Text("${shell.formats.date(week.from, today)} – ${shell.formats.date(week.to, today)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { vm.step(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, t("health.activity.nextWeek")) }
            }
            val totals = HealthActivity.totals(rows)
            Row(Modifier.padding(horizontal = 16.dp)) {
                StatTile(t("health.activity.totals.count"), strings.num(totals.count.toDouble(), 0), Modifier.weight(1f))
                StatTile(t("health.activity.totals.duration"), t("health.activity.unit.min", "value" to strings.num(totals.durationMin, 0)), Modifier.weight(1f))
                StatTile(t("health.activity.totals.distance"), t("health.activity.unit.km", "value" to strings.num(totals.distanceKm)), Modifier.weight(1f))
                StatTile(t("health.activity.totals.calories"), t("health.activity.unit.kcal", "value" to strings.num(totals.calories, 0)), Modifier.weight(1f))
            }
            SectionTitle(t("health.activity.chartTitle"))
            if (rows.isEmpty()) Text(t("health.activity.noData"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
            else BarChart(week.days.map { it.durationMin }, week.days.map { strings.t("health.activity.weekday." + HealthActivity.WEEKDAYS[it.weekday]) }, Yuvomi.colors.module("health"), t("health.activity.chartTitle"))
        }
        item(key = "log") { SectionTitle(t("health.activity.logTitle")) }
        if (rows.isEmpty()) item(key = "none") { Text(t("health.activity.noEntries"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp)) }
        rows.forEach { a ->
            item(key = "a-${a.id}") {
                Row(Modifier.fillMaxWidth().let { if (canEdit) it.clickable { editor = a } else it }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(HealthActivity.typeLabel(a.type, strings), style = MaterialTheme.typography.bodyMedium)
                        val at = shell.time.parse(a.performedAt)
                        val meta = listOfNotNull(
                            at?.let { shell.formats.dateTime(it) },
                            a.intensity?.takeIf { it in HealthActivity.INTENSITIES }?.let { strings.t("health.activity.intensity.$it") },
                        )
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    }
                    val figures = listOfNotNull(
                        a.durationMin?.let { strings.t("health.activity.unit.min", "value" to strings.num(it, 0)) },
                        a.distanceKm?.let { strings.t("health.activity.unit.km", "value" to strings.num(it)) },
                        a.calories?.let { strings.t("health.activity.unit.kcal", "value" to strings.num(it, 0)) },
                    )
                    Text(figures.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }
    if (creating) ActivityEditor(vm, null) { creating = false }
    editor?.let { a -> ActivityEditor(vm, a) { editor = null } }
}

@Composable
private fun ActivityEditor(vm: ActivityViewModel, row: Activity?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var f by remember { mutableStateOf(HealthForms.activityForm(row, shell.time.today(), shell.time.now().toLocalTime().toString().take(5), vm.hub.defaultVisibility("activities"))) }
    EditorDialog(t(if (row == null) "health.activity.add" else "health.activity.edit"), close, { vm.save(f, row, close) }, saving = s.busy) {
        Picker(
            t("health.activity.field.type"),
            when (f.type) { "" -> strings.t("health.activity.field.typePlaceholder"); HealthForms.CUSTOM -> strings.t("health.activity.type.custom"); else -> HealthActivity.typeLabel(f.type, strings) },
            HealthActivity.TYPES.map { it to HealthActivity.typeLabel(it, strings) } + (HealthForms.CUSTOM to strings.t("health.activity.type.custom")),
        ) { f = f.copy(type = it) }
        if (f.type == HealthForms.CUSTOM) TextInput(t("health.activity.field.customType"), f.custom) { f = f.copy(custom = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DateField(t("health.activity.field.performedAt"), f.date) { d -> d?.let { f = f.copy(date = it) } }
            TimeField(t("health.meds.log.timeLabel"), f.time) { f = f.copy(time = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(t("health.activity.field.duration"), f.duration, Modifier.weight(1f)) { f = f.copy(duration = it) }
            NumberField(t("health.activity.field.distance"), f.distance, Modifier.weight(1f)) { f = f.copy(distance = it) }
        }
        NumberField(t("health.activity.field.calories"), f.calories) { f = f.copy(calories = it) }
        Picker(
            t("health.activity.field.intensity"),
            if (f.intensity.isEmpty()) strings.t("health.activity.intensity.none") else strings.t("health.activity.intensity.${f.intensity}"),
            listOf("" to strings.t("health.activity.intensity.none")) + HealthActivity.INTENSITIES.map { it to strings.t("health.activity.intensity.$it") },
        ) { f = f.copy(intensity = it) }
        TextInput(t("health.activity.field.note"), f.note, lines = 2) { f = f.copy(note = it) }
        VisibilityPicker(f.visibility, { f = f.copy(visibility = it) }, openLabel = t("health.activity.visibility.family"), privateLabel = t("health.activity.visibility.private"))
        if (row != null) DeleteButton { close(); vm.delete(row) }
    }
}

// ── Nutrition ──

/** docs/spec/health.md, HEALTH-R49 to R51 (`renderNutritionShell`, `openNutritionModal`, `openNutritionTargetModal`). */
@Composable
fun HealthNutritionScreen(vm: NutritionViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId) && !s.offline
    var editor by remember { mutableStateOf<NutritionEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var targetOpen by remember { mutableStateOf(false) }
    val today = shell.time.today()

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.nutrition"),
        back = back,
        personLabel = t("health.nutrition.personsLabel"),
        fab = { if (canEdit) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(t("newLabel.healthNutrition")) }) },
    ) { data ->
        val summary = data.summary
        item(key = "today") {
            SectionTitle(t("health.nutrition.todayTitle"), action = {
                // The target is visible only to the owner and caregivers (`mayReadTarget`).
                if (canEdit) TextButton(onClick = { targetOpen = true }) { Text(t(if (summary?.target != null) "health.nutrition.targetEdit" else "health.nutrition.targetSet")) }
            })
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (summary?.target == null && vm.hub.canEdit(hub.personId ?: vm.hub.meId)) {
                    Text(t("health.nutrition.targetNone"), style = MaterialTheme.typography.bodySmall)
                    Text(t("health.nutrition.targetNoneHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }
                HealthNutrition.NUTRIENTS.forEach { n ->
                    val value = summary?.totals?.get(n.key) ?: 0.0
                    val target = summary?.target?.get(n.key)
                    val p = HealthNutrition.progress(value, target)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(n.labelKey), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (target != null) t("health.nutrition.ofTarget", "value" to strings.num(value), "target" to strings.num(target), "unit" to t(n.unitKey))
                            else t("health.nutrition.noTargetValue", "value" to strings.num(value), "unit" to t(n.unitKey)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.over) Yuvomi.colors.warning else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (p.hasTarget) ProgressBar(p.ratio, if (p.over) Yuvomi.colors.warning else Yuvomi.colors.module("health"))
                }
            }
        }
        val entries = vm.entries()
        if (entries.isEmpty()) {
            item(key = "empty") { AreaEmpty("health.nutrition.emptyTitle", "health.nutrition.emptyDesc", if (canEdit) "health.nutrition.add" else null) { creating = true } }
        } else {
            item(key = "entries") { SectionTitle(t("health.nutrition.entriesTitle")) }
            entries.forEach { e ->
                item(key = "n-${e.id}") {
                    Row(Modifier.fillMaxWidth().let { if (canEdit) it.clickable { editor = e } else it }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium)
                            val at = shell.time.parse(e.consumedAt)
                            val meta = listOfNotNull(at?.let { shell.formats.dateTime(it) }, e.mealType?.let { strings.t("health.nutrition.meal.$it") })
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        }
                        e.energyKcal?.let { Text("${strings.num(it, 0)} ${t("health.nutrition.unit.kcal")}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }
    if (creating) NutritionEditor(vm, null) { creating = false }
    editor?.let { e -> NutritionEditor(vm, e) { editor = null } }
    if (targetOpen) TargetEditor(vm) { targetOpen = false }
}

@Composable
private fun NutritionEditor(vm: NutritionViewModel, entry: NutritionEntry?, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var f by remember { mutableStateOf(HealthForms.nutritionForm(entry, shell.time.today(), shell.time.now().toLocalTime().toString().take(5), vm.defaultVisibility())) }
    EditorDialog(t(if (entry == null) "health.nutrition.add" else "health.nutrition.edit"), close, { vm.save(f, entry, close) }, saving = s.busy) {
        TextInput(t("health.nutrition.field.title") + " *", f.title) { f = f.copy(title = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DateField(t("health.nutrition.field.consumedAt"), f.date) { d -> d?.let { f = f.copy(date = it) } }
            TimeField(t("health.meds.log.timeLabel"), f.time) { f = f.copy(time = it) }
        }
        Picker(
            t("health.nutrition.field.mealType"),
            f.meal?.let { strings.t("health.nutrition.meal.$it") } ?: strings.t("health.nutrition.field.noMealType"),
            listOf<Pair<String?, String>>(null to strings.t("health.nutrition.field.noMealType")) + HealthNutrition.MEALS.map { it to strings.t("health.nutrition.meal.$it") },
        ) { f = f.copy(meal = it) }
        HealthNutrition.NUTRIENTS.forEach { n ->
            NumberField("${t(n.labelKey)} (${t(n.unitKey)})", f.nutrients[n.key].orEmpty()) { v -> f = f.copy(nutrients = f.nutrients + (n.key to v)) }
        }
        TextInput(t("health.nutrition.field.note"), f.note, lines = 2) { f = f.copy(note = it) }
        VisibilityPicker(f.visibility, { f = f.copy(visibility = it) }, open = "all", openLabel = t("common.visibility.all"), privateLabel = t("common.visibility.private"))
        if (entry != null) DeleteButton { close(); vm.delete(entry) }
    }
}

@Composable
private fun TargetEditor(vm: NutritionViewModel, close: () -> Unit) {
    val s by vm.state.collectAsState()
    val target = s.data?.summary?.target
    var values by remember { mutableStateOf(HealthNutrition.NUTRIENTS.associate { it.key to HealthForms.plain(target?.get(it.key)) }) }
    EditorDialog(t("health.nutrition.targetTitle"), close, { vm.saveTarget(values, close) }, saving = s.busy) {
        Text(t("health.nutrition.targetHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        HealthNutrition.NUTRIENTS.forEach { n ->
            NumberField("${t(n.labelKey)} (${t(n.unitKey)})", values[n.key].orEmpty()) { v -> values = values + (n.key to v) }
        }
    }
}
