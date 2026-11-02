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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell

/** docs/spec/health.md, HEALTH-R17 to R25 (`renderMedsShell`). */
@Composable
fun HealthMedsScreen(vm: MedsViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId) && !s.offline
    var editing by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(false) }
    var logEdit by remember { mutableStateOf<Pair<Medication, MedLog>?>(null) }
    var logOpen by remember { mutableStateOf(false) }
    val today = shell.time.today()

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.meds"),
        back = back,
        personLabel = t("health.meds.personsLabel"),
        fab = {
            if (canEdit) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(t("health.meds.addShort")) })
        },
    ) { _ ->
        val meds = vm.meds()
        if (meds.isEmpty()) {
            item(key = "empty") { AreaEmpty("health.meds.emptyTitle", "health.meds.emptyDesc", if (canEdit) "health.meds.add" else null) { creating = true } }
            return@HealthScaffold
        }
        item(key = "due") {
            SectionTitle(t("health.meds.dueToday.title"))
            Column(Modifier.padding(horizontal = 16.dp)) { DueList(meds, today, canEdit, vm.dosesInFlight, vm::dose) }
        }
        val prn = HealthMeds.prnMeds(meds)
        if (prn.isNotEmpty()) {
            item(key = "prn") {
                SectionTitle(t("health.meds.prn.title"))
                Column(Modifier.padding(horizontal = 16.dp)) { PrnList(prn, canEdit, vm.dosesInFlight, vm::prn) }
            }
        }
        item(key = "adherence") {
            OverviewCard(areaIcon("meds"), t("health.meds.adherence.title")) { AdherenceBlock(meds, today, overview = false) }
        }
        // The intake log, newest first, ten entries (`medLogHistoryMarkup`).
        val history = HealthMeds.history(meds)
        if (history.isNotEmpty()) {
            item(key = "log-head") {
                Row(Modifier.fillMaxWidth().clickable { logOpen = !logOpen }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("health.meds.logTitle"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (logOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (logOpen) history.forEach { (med, log) ->
                item(key = "log-${log.id}") {
                    val at = log.takenAt ?: log.scheduledAt ?: log.createdAt.orEmpty()
                    val parsed = shell.time.parse(at)
                    val time = when {
                        parsed == null -> at
                        at.length >= 16 -> "${shell.formats.date(parsed.toLocalDate(), today)} · ${shell.formats.time(parsed)}"
                        else -> shell.formats.date(parsed.toLocalDate(), today)
                    }
                    val status = when (log.status) {
                        "skipped" -> t("health.meds.status.skipped")
                        "pending" -> t("health.meds.log.statusPending")
                        else -> t("health.meds.status.taken")
                    }
                    Row(
                        Modifier.fillMaxWidth().let { if (canEdit) it.clickable { logEdit = med to log } else it }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(med.name, style = MaterialTheme.typography.bodyMedium, color = if (log.status == "taken") MaterialTheme.colorScheme.onSurface else Yuvomi.colors.textSecondary)
                            Text(time, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        }
                        Text(status, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item(key = "all") { SectionTitle(t("health.meds.allTitle")) }
        meds.forEach { med ->
            item(key = "med-${med.id}") { MedCard(med) { if (canEdit) editing = med.id } }
        }
        item(key = "disclaimer") { Disclaimer() }
    }

    if (creating) MedEditor(vm, null) { creating = false }
    editing?.let { id -> vm.med(id)?.let { MedEditor(vm, it) { editing = null } } ?: run { editing = null } }
    logEdit?.let { (med, log) -> LogEditor(vm, med, log) { logEdit = null } }
}

/** `medCardMarkup`: name, badges, dosage and form, stock with its refill warning. */
@Composable
private fun MedCard(med: Medication, onClick: () -> Unit) {
    val strings = LocalStrings.current
    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(med.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = if (med.active) MaterialTheme.colorScheme.onSurface else Yuvomi.colors.textSecondary)
                if (!med.active) AssistChip(onClick = {}, label = { Text(t("health.meds.badge.inactive")) })
                if (med.prn) AssistChip(onClick = {}, label = { Text(t("health.meds.badge.prn")) }, modifier = Modifier.padding(start = 4.dp))
            }
            listOfNotNull(med.dosageText, med.form).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
            val refill = HealthMeds.refill(med)
            if (refill != HealthMeds.Refill.NONE) {
                val warn = refill == HealthMeds.Refill.LOW || refill == HealthMeds.Refill.OUT
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${t("health.meds.stock.label")}: ${strings.num(med.stockQty)}${med.stockUnit?.let { " $it" }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (warn) Yuvomi.colors.warning else Yuvomi.colors.textSecondary,
                    )
                    if (warn) {
                        Icon(Icons.Outlined.Warning, null, tint = Yuvomi.colors.warning, modifier = Modifier.padding(start = 8.dp))
                        Text(t(if (refill == HealthMeds.Refill.OUT) "health.meds.stock.out" else "health.meds.stock.low"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.warning)
                    }
                }
            }
        }
    }
}

/** `openMedModal` with its schedule editor: a new medication's slots are a draft sent with it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MedEditor(vm: MedsViewModel, med: Medication?, close: () -> Unit) {
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    var f by remember { mutableStateOf(HealthForms.medForm(med, vm.hub.defaultVisibility("meds"))) }
    var slot by remember { mutableStateOf(HealthForms.SlotForm()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(listOf(f.stock, f.stockUnit, f.refill, f.note).any { it.isNotBlank() }) }
    // The live slots of an existing medication come from the list, which reloads after each change.
    val schedules = med?.let { vm.med(it.id)?.schedules }.orEmpty()
    EditorDialog(t(if (med == null) "health.meds.add" else "health.meds.edit"), close, { vm.save(f, med, close) }, saving = s.busy) {
        TextInput(t("health.meds.field.name") + " *", f.name) { f = f.copy(name = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(t("health.meds.field.dosageText"), f.dosage, Modifier.weight(1f)) { f = f.copy(dosage = it) }
            TextInput(t("health.meds.field.form"), f.form, Modifier.weight(1f)) { f = f.copy(form = it) }
        }
        SwitchRow(t("health.meds.field.active"), f.active) { f = f.copy(active = it) }
        SwitchRow(t("health.meds.field.prn"), f.prn) { f = f.copy(prn = it) }
        if (f.prn) {
            NumberField(t("health.meds.field.minInterval"), f.interval, supporting = t("health.meds.field.minIntervalHint")) { f = f.copy(interval = it) }
            NumberField(t("health.meds.field.prnDose"), f.prnDose, supporting = t("health.meds.field.prnDoseHint")) { f = f.copy(prnDose = it) }
        }

        // The schedule (`renderSchedEditor`).
        Text(t("health.meds.schedule.title"), style = MaterialTheme.typography.titleSmall)
        val rows: List<Pair<String, () -> Unit>> = if (med != null) schedules.map { sc ->
            slotText(sc.timeOfDay, sc.doseQty, sc.daysMask, strings) to { vm.deleteSlot(sc) }
        } else f.slots.mapIndexed { i, sl ->
            slotText(sl.time, HealthForms.number(sl.dose), HealthMeds.indicesToMask(sl.days), strings) to { f = f.copy(slots = f.slots.filterIndexed { j, _ -> j != i }) }
        }
        if (rows.isEmpty()) Text(t("health.meds.schedule.none"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        rows.forEach { (text, remove) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = remove) { Icon(Icons.Outlined.Delete, t("health.meds.schedule.delete")) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            TimeField(t("health.meds.schedule.time"), slot.time) { slot = slot.copy(time = it) }
            NumberField(t("health.meds.schedule.dose"), slot.dose, Modifier.weight(1f)) { slot = slot.copy(dose = it) }
        }
        Text(t("health.meds.schedule.days") + " · " + if (slot.days.isEmpty()) t("health.meds.schedule.daily") else "", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HealthActivity.WEEKDAYS.forEachIndexed { i, d ->
                FilterChip(selected = i in slot.days, onClick = { slot = slot.copy(days = if (i in slot.days) slot.days - i else slot.days + i) }, label = { Text(t("health.meds.weekday.$d")) })
            }
        }
        OutlinedButton(onClick = {
            if (med != null) vm.addSlot(med, slot) else f = f.copy(slots = f.slots + slot)
            slot = HealthForms.SlotForm()
        }) { Text(t("health.meds.schedule.add")) }

        Row(Modifier.fillMaxWidth().clickable { more = !more }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("modal.moreDetails"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (more) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(t("health.meds.field.stockQty"), f.stock, Modifier.weight(1f)) { f = f.copy(stock = it) }
                TextInput(t("health.meds.field.stockUnit"), f.stockUnit, Modifier.weight(1f)) { f = f.copy(stockUnit = it) }
            }
            NumberField(t("health.meds.field.refillThreshold"), f.refill) { f = f.copy(refill = it) }
            TextInput(t("health.meds.field.note"), f.note, lines = 2) { f = f.copy(note = it) }
        }
        VisibilityPicker(f.visibility, { f = f.copy(visibility = it) }, openLabel = t("health.meds.visibility.family"), privateLabel = t("health.meds.visibility.private"))
        if (med != null) DeleteButton { confirmDelete = true }
    }
    if (confirmDelete && med != null) {
        ConfirmDialog(t("health.meds.deleteConfirm"), t("health.meds.deleteConfirmDetail"), t("common.delete"), { vm.delete(med, close) }, { confirmDelete = false })
    }
}

private fun slotText(time: String, dose: Double?, mask: Int?, strings: io.github.marcomow.yuvomi.core.i18n.Strings): String {
    val days = if (mask == null) strings.t("health.meds.schedule.daily")
    else HealthMeds.maskToIndices(mask).sorted().joinToString(", ") { strings.t("health.meds.weekday." + HealthActivity.WEEKDAYS[it]) }
    val doseText = dose?.let { " · " + strings.t("health.meds.doseQty", "count" to strings.num(it)) }.orEmpty()
    return "${time.take(5)}$doseText · $days"
}

/** `openMedLogModal`: status, the time when taken; only an entry without a schedule can be deleted. */
@Composable
private fun LogEditor(vm: MedsViewModel, med: Medication, log: MedLog, close: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val at = log.takenAt ?: log.scheduledAt ?: log.createdAt.orEmpty()
    var status by remember { mutableStateOf(log.status) }
    var date by remember { mutableStateOf(HealthTimes.date(at) ?: shell.time.today()) }
    var time by remember { mutableStateOf(at.drop(11).take(5).ifEmpty { "00:00" }) }
    EditorDialog(t("health.meds.log.editTitle"), close, { vm.saveLog(log, status, date, time, close) }, saving = s.busy) {
        Text(med.name, style = MaterialTheme.typography.bodyMedium)
        Picker(
            t("health.meds.log.statusLabel"),
            mapOf("taken" to strings.t("health.meds.status.taken"), "skipped" to strings.t("health.meds.status.skipped"), "pending" to strings.t("health.meds.log.statusPending"))[status].orEmpty(),
            listOf("taken" to strings.t("health.meds.status.taken"), "skipped" to strings.t("health.meds.status.skipped"), "pending" to strings.t("health.meds.log.statusPending")),
        ) { status = it }
        if (status == "taken") Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DateField(t("health.meds.log.dateLabel"), date) { d -> d?.let { date = it } }
            TimeField(t("health.meds.log.timeLabel"), time) { time = it }
        }
        Text(t(if (log.scheduleId != null) "health.meds.log.scheduledHint" else "health.meds.log.adhocHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        if (log.scheduleId == null) DeleteButton { close(); vm.deleteLog(log) }
    }
}
