package io.github.marcomow.yuvomi.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.LocalDate

/** docs/spec/health.md, HEALTH-R32 to R38 (`renderCycleShell`). Caregiving does not apply here. */
@Composable
fun HealthCycleScreen(vm: CycleViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val canEdit = vm.canEdit && !s.offline
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var period by remember { mutableStateOf<CyclePeriod?>(null) }
    var newPeriod by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val today = shell.time.today()

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.cycle"),
        back = back,
        banner = false,
        actions = { if (canEdit) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Settings, t("health.cycle.settings.open")) } },
        fab = {
            if (canEdit) ExtendedFloatingActionButton(onClick = { day = today }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(t("health.cycle.today.logDay")) })
        },
    ) { _ ->
        item(key = "banner") { OwnerBanner(vm.hub, hub, cycle = true) }
        if (!hub.cycleEnabled) {
            item(key = "off") { AreaEmpty("health.cycle.emptyTitle", "health.cycle.emptyDesc", null, null) }
            return@HealthScaffold
        }
        val periods = vm.periods()
        val logs = vm.logs()
        val settings = vm.settings()
        val p = HealthCycle.predict(periods, settings, today, logs)

        if (canEdit) item(key = "today") {
            SectionTitle(t("health.cycle.today.title"))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val open = HealthCycle.openPeriod(periods, today)
                if (open == null) OutlinedButton(onClick = vm::startToday) { Text(t("health.cycle.today.startPeriod")) }
                else OutlinedButton(onClick = vm::endToday) { Text(t("health.cycle.today.endPeriod")) }
                OutlinedButton(onClick = { day = today }) { Text(t("health.cycle.today.logDay")) }
            }
        }

        if (periods.isEmpty() && !p.pregnant) {
            item(key = "empty") { AreaEmpty("health.cycle.emptyTitle", "health.cycle.emptyDesc", if (canEdit) "health.cycle.emptyCta" else null) { newPeriod = true } }
        } else item(key = "status") {
            Card(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { CycleStatus(p, vm.hub.isOwn, today, strings) }
            }
        }

        if (periods.isNotEmpty()) {
            item(key = "history") {
                SectionTitle(t("health.cycle.history.title"), action = { if (canEdit) TextButton(onClick = { newPeriod = true }) { Text(t("health.cycle.period.add")) } })
            }
            val lengths = HealthCycle.cycleLengths(periods)
            HealthCycle.sortAsc(periods).reversed().forEach { per ->
                item(key = "p-${per.id}") {
                    val start = HealthTimes.date(per.startDate)!!
                    val end = HealthTimes.date(per.endDate)
                    Row(
                        Modifier.fillMaxWidth().let { if (canEdit) it.clickable { period = per } else it }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                shell.formats.date(start, today) + " – " + (end?.let { shell.formats.date(it, today) } ?: t("health.cycle.history.ongoing")),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val extras = listOfNotNull(
                                lengths[per.id]?.let { strings.t("health.cycle.history.cycleLength", "count" to it, "value" to it) },
                                HealthCycle.heaviestFlow(per, logs, p.stats.avgPeriod)?.let { strings.t("health.cycle.history.flowHeaviest", "value" to strings.t("health.cycle.flow.$it")) },
                            )
                            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        }
                    }
                }
            }
        }

        val recent = logs.sortedByDescending { it.logDate }.take(14)
        if (recent.isNotEmpty()) {
            item(key = "days") { SectionTitle(t("health.cycle.dayLog.title")) }
            recent.forEach { log ->
                item(key = "d-${log.id}") {
                    Row(
                        Modifier.fillMaxWidth().let { if (canEdit) it.clickable { day = HealthTimes.date(log.logDate) } else it }.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(HealthTimes.date(log.logDate)?.let { shell.formats.date(it, today) }.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                        val parts = listOfNotNull(log.flow?.let { strings.t("health.cycle.flow.$it") }) +
                            log.symptoms.map { strings.t(HealthCycle.symptomKey(it.key)) } + log.feelings.map { strings.t("health.cycle.mood.$it") } +
                            listOfNotNull(log.basalTemp?.let { "${strings.num(it, 2)} °${(log.basalTempUnit ?: "c").uppercase()}" })
                        Text(parts.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }

    day?.let { d -> DayEditor(vm, d) { day = null } }
    if (newPeriod) PeriodEditor(vm, null) { newPeriod = false }
    period?.let { per -> PeriodEditor(vm, per) { period = null } }
    if (settingsOpen) CycleSettingsEditor(vm) { settingsOpen = false }
}

@Composable
private fun CycleStatus(p: HealthCycle.Prediction, own: Boolean, today: LocalDate, strings: Strings) {
    val shell = LocalShell.current
    fun d(x: LocalDate) = shell.formats.date(x, today)
    if (p.pregnant) {
        Text(t("health.cycle.pregnancy.title"), style = MaterialTheme.typography.titleMedium)
        p.pregnancy.dueDate?.let {
            Text(t("health.cycle.pregnancy.week", "weeks" to p.pregnancy.weeks, "days" to p.pregnancy.days), style = MaterialTheme.typography.headlineSmall)
            Text(t("health.cycle.pregnancy.dueDate", "date" to d(it)), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (!p.hasData) {
        Text(t("health.cycle.status.noPrediction"), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(t("health.cycle.ring.cycleDay", "day" to p.cycleDay), style = MaterialTheme.typography.headlineSmall)
    Text(t(HealthCycle.phaseKey(p.phase)), style = MaterialTheme.typography.titleSmall, color = Yuvomi.colors.module("health"))
    Text(t("health.cycle.status.nextPeriod"), style = MaterialTheme.typography.labelLarge)
    val range = p.nextRange
    Text(
        (if (range != null) "${d(range.first)} – ${d(range.second)}" else d(p.nextStart!!)) + " · " + HealthCycle.nextText(p, strings),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (p.trackFertility && p.fertileStart != null && p.fertileEnd != null) {
        Text("${t("health.cycle.status.fertileWindow")}: ${d(p.fertileStart)} – ${d(p.fertileEnd)}", style = MaterialTheme.typography.bodyMedium)
        p.ovulation?.let { Text("${t(if (p.ovulationConfirmed) "health.cycle.status.ovulationConfirmed" else "health.cycle.status.ovulation")}: ${d(it)}", style = MaterialTheme.typography.bodyMedium) }
    }
    if (p.fertilitySuppressed) Text(t("health.cycle.status.fertilitySuppressedNote"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    Row {
        StatTile(t("health.cycle.status.avgCycle"), "${p.stats.avgCycle} ${t("health.cycle.settings.daysUnit")}", Modifier.weight(1f))
        StatTile(t("health.cycle.status.avgPeriod"), "${p.stats.avgPeriod} ${t("health.cycle.settings.daysUnit")}", Modifier.weight(1f))
    }
    StatTile(
        t("health.cycle.status.cycleVariation"),
        when (p.stats.regular) { true -> t("health.cycle.status.regular"); false -> t("health.cycle.status.irregular"); null -> t("health.cycle.status.notEnoughData") },
    )
    val source = when (p.stats.source) {
        "history" -> strings.t(if (own) "health.cycle.stats.source.history" else "health.cycle.stats.source.historyOther", "count" to p.stats.gapCount)
        "insufficient_history" -> strings.t("health.cycle.stats.source.insufficientHistory", "count" to (HealthCycle.MIN_HISTORY_GAPS - p.stats.gapCount).coerceAtLeast(1))
        "default" -> strings.t("health.cycle.stats.source.default")
        else -> null
    }
    source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
}

/** `openPeriodModal`: start, optional end, note, visibility; warnings for the future and overlaps. */
@Composable
private fun PeriodEditor(vm: CycleViewModel, period: CyclePeriod?, close: () -> Unit) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    val today = shell.time.today()
    var start by remember { mutableStateOf(period?.let { HealthTimes.date(it.startDate) } ?: today) }
    var end by remember { mutableStateOf(period?.let { HealthTimes.date(it.endDate) }) }
    var note by remember { mutableStateOf(period?.note.orEmpty()) }
    var visibility by remember { mutableStateOf(period?.visibility ?: vm.settings().defaultVisibility) }
    EditorDialog(t(if (period == null) "health.cycle.period.add" else "health.cycle.period.edit"), close, { vm.savePeriod(period, start, end, note, visibility, close) }, saving = s.busy) {
        DateField(t("health.cycle.field.startDate"), start) { d -> d?.let { start = it } }
        DateField(t("health.cycle.field.endDate"), end, clearable = true) { end = it }
        if (start > today) Text(t("health.cycle.period.warningFuture"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.warning)
        if (HealthCycle.overlaps(vm.periods(), start, end, period?.id, HealthCycle.stats(vm.periods(), vm.settings(), today).avgPeriod)) {
            Text(t("health.cycle.period.warningOverlap"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.warning)
        }
        if (end != null && end!! < start) Text(t("health.cycle.endBeforeStart"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.danger)
        TextInput(t("health.cycle.field.note"), note, lines = 2) { note = it }
        VisibilityPicker(visibility, { visibility = it }, openLabel = t("health.cycle.visibility.family"), privateLabel = t("health.cycle.visibility.private"))
        if (period != null) DeleteButton { close(); vm.deletePeriod(period) }
    }
}

/** `openDayLogModal`: one entry per day; a second save of the same date replaces it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DayEditor(vm: CycleViewModel, date: LocalDate, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val existing = vm.logs().firstOrNull { it.logDate.take(10) == date.toString() }
    var f by remember { mutableStateOf(HealthForms.dayForm(existing, date, vm.settings().defaultVisibility)) }
    EditorDialog(t("health.cycle.dayLog.title"), close, { vm.saveDay(f, close) }, saving = s.busy) {
        DateField(t("health.cycle.dayLog.date"), f.date) { d ->
            d?.let { nd -> f = HealthForms.dayForm(vm.logs().firstOrNull { it.logDate.take(10) == nd.toString() }, nd, f.visibility) }
        }
        Text(t("health.cycle.flow.label"), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(HealthCycle.FLOW.map { it to strings.t("health.cycle.flow.$it") }, f.flow) { f = f.copy(flow = it) }
        Text(t("health.cycle.symptom.label"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HealthCycle.SYMPTOMS.forEach { (value, _) ->
                val on = value in f.symptoms
                val intensity = f.symptoms[value]
                // A tap adds the symptom, further taps step through mild, moderate, severe, then off.
                FilterChip(
                    selected = on,
                    onClick = {
                        f = f.copy(symptoms = when {
                            !on -> f.symptoms + (value to null)
                            intensity == null -> f.symptoms + (value to 1)
                            intensity < 3 -> f.symptoms + (value to intensity + 1)
                            else -> f.symptoms - value
                        })
                    },
                    label = {
                        val level = when (intensity) { 1 -> "mild"; 2 -> "moderate"; 3 -> "severe"; else -> null }
                        Text(strings.t(HealthCycle.symptomKey(value)) + (level?.let { " · " + strings.t("health.cycle.intensity.$it") }.orEmpty()))
                    },
                )
            }
        }
        Text(t("health.cycle.feelings.label"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HealthCycle.MOODS.forEach { m ->
                FilterChip(selected = m in f.feelings, onClick = { f = f.copy(feelings = if (m in f.feelings) f.feelings - m else f.feelings + m) }, label = { Text(strings.t("health.cycle.mood.$m")) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(t("health.cycle.bbt.label"), f.temp, Modifier.weight(1f), supporting = t("health.cycle.bbt.hint")) { f = f.copy(temp = it) }
            Picker(t("health.cycle.bbt.unitLabel"), strings.t(if (f.tempUnit == "f") "health.cycle.bbt.fahrenheit" else "health.cycle.bbt.celsius"),
                listOf("c" to strings.t("health.cycle.bbt.celsius"), "f" to strings.t("health.cycle.bbt.fahrenheit")), Modifier.weight(0.6f)) { f = f.copy(tempUnit = it) }
        }
        Text(t("health.cycle.mucus.label"), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(HealthCycle.MUCUS.map { it to strings.t("health.cycle.mucus.$it") }, f.mucus) { f = f.copy(mucus = it) }
        Text(t("health.cycle.test.lhLabel"), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(HealthCycle.TESTS.map { it to strings.t("health.cycle.test.$it") }, f.lh) { f = f.copy(lh = it) }
        Text(t("health.cycle.test.pregnancyLabel"), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(HealthCycle.TESTS.map { it to strings.t("health.cycle.test.$it") }, f.pregnancy) { f = f.copy(pregnancy = it) }
        Text(t("health.cycle.intimacy.label"), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(HealthCycle.INTIMACY.map { it to strings.t("health.cycle.intimacy.$it") }, f.intimacy) { f = f.copy(intimacy = it) }
        Text(t("health.cycle.intimacy.hint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        TextInput(t("health.cycle.field.note"), f.note, lines = 2) { f = f.copy(note = it) }
        VisibilityPicker(f.visibility, { f = f.copy(visibility = it) }, openLabel = t("health.cycle.visibility.family"), privateLabel = t("health.cycle.visibility.private"))
        if (existing != null && existing.logDate.take(10) == f.date.toString()) DeleteButton { close(); vm.deleteDay(existing) }
    }
}

/** `openCycleSettings`: every field goes back on save; "apply to all" is its own write. */
@Composable
private fun CycleSettingsEditor(vm: CycleViewModel, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var c by remember { mutableStateOf(vm.settings()) }
    var cycleLen by remember { mutableStateOf(c.cycleLengthAvg?.toString().orEmpty()) }
    var periodLen by remember { mutableStateOf(c.periodLengthAvg?.toString().orEmpty()) }
    var luteal by remember { mutableStateOf(c.lutealLength?.toString().orEmpty()) }
    var confirmAll by remember { mutableStateOf(false) }
    EditorDialog(t("health.cycle.settings.title"), close, {
        vm.saveSettings(c.copy(cycleLengthAvg = cycleLen.trim().toIntOrNull(), periodLengthAvg = periodLen.trim().toIntOrNull(), lutealLength = luteal.trim().toIntOrNull() ?: 14), close)
    }, saving = s.busy) {
        NumberField(t("health.cycle.settings.cycleLength"), cycleLen, decimal = false, supporting = t("health.cycle.settings.daysUnit")) { cycleLen = it }
        NumberField(t("health.cycle.settings.periodLength"), periodLen, decimal = false, supporting = t("health.cycle.settings.autoHint")) { periodLen = it }
        NumberField(t("health.cycle.settings.lutealLength"), luteal, decimal = false) { luteal = it }
        SwitchRow(t("health.cycle.settings.trackFertility"), c.trackFertility) { c = c.copy(trackFertility = it) }
        SwitchRow(t("health.cycle.settings.showPms"), c.showPms) { c = c.copy(showPms = it) }
        Picker(
            t("health.cycle.settings.contraception"),
            c.contraception?.let { strings.t("health.cycle.settings.contraceptionOptions.$it") } ?: strings.t("health.cycle.settings.contraceptionUnset"),
            listOf<Pair<String?, String>>(null to strings.t("health.cycle.settings.contraceptionUnset")) +
                HealthCycle.CONTRACEPTION.map { it to strings.t("health.cycle.settings.contraceptionOptions.$it") },
        ) { c = c.copy(contraception = it) }
        Text(t("health.cycle.settings.contraceptionHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        SwitchRow(t("health.cycle.settings.perimenopauseMode"), c.perimenopauseMode, t("health.cycle.settings.perimenopauseModeHint")) { c = c.copy(perimenopauseMode = it) }
        SwitchRow(t("health.cycle.settings.pregnancyMode"), c.pregnancyMode, t("health.cycle.settings.pregnancyHint")) { c = c.copy(pregnancyMode = it) }
        if (c.pregnancyMode) DateField(t("health.cycle.settings.dueDate"), HealthTimes.date(c.pregnancyDueDate), clearable = true) { c = c.copy(pregnancyDueDate = it?.toString()) }
        Picker(
            t("health.cycle.settings.remindPeriodDaysBefore"),
            remindLabel(c.remindPeriodDaysBefore, strings),
            listOf<Int?>(null, 0, 1, 2, 3, 5, 7).map { it to remindLabel(it, strings) },
        ) { c = c.copy(remindPeriodDaysBefore = it) }
        SwitchRow(t("health.cycle.settings.remindLogDaily"), c.remindLogDaily) { c = c.copy(remindLogDaily = it) }
        VisibilityPicker(c.defaultVisibility, { c = c.copy(defaultVisibility = it) }, openLabel = t("health.cycle.visibility.family"), privateLabel = t("health.cycle.visibility.private"))
        Text(t("health.cycle.settings.defaultVisibilityHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        val label = strings.t(if (c.defaultVisibility == "family") "health.cycle.visibility.family" else "health.cycle.visibility.private")
        OutlinedButton(onClick = { confirmAll = true }) { Text(t("health.cycle.settings.applyToAllValue", "visibility" to label)) }
        Text(t("health.cycle.settings.applyToAllHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        if (confirmAll) ConfirmDialog(t("health.cycle.settings.applyToAllConfirm", "visibility" to label), null, t("common.confirm"), { vm.applyVisibility(c.defaultVisibility) }, { confirmAll = false })
    }
}

private fun remindLabel(days: Int?, s: Strings): String = when (days) {
    null -> s.t("health.cycle.settings.remindOff")
    0 -> s.t("health.cycle.settings.remindSameDay")
    else -> "$days ${s.t("health.cycle.settings.daysUnit")}"
}
