package io.github.marcomow.yuvomi.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.ModuleBadge
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell

internal fun areaIcon(id: String): ImageVector = when (id) {
    "vitals" -> Icons.Outlined.MonitorHeart
    "cycle" -> Icons.Outlined.WaterDrop
    "fasting" -> Icons.Outlined.Timer
    "meds" -> Icons.Outlined.Medication
    "prevention" -> Icons.Outlined.Vaccines
    "labs" -> Icons.Outlined.Biotech
    "activity" -> Icons.AutoMirrored.Outlined.DirectionsRun
    "nutrition" -> Icons.Outlined.Restaurant
    else -> Icons.Outlined.Favorite
}

/** docs/spec/health.md, HEALTH-R9: the overview (`renderOverviewShell`, `overviewGridMarkup`). */
@Composable
fun HealthOverviewScreen(vm: OverviewViewModel, open: (String) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val hub by vm.hub.state.collectAsState()
    val s by vm.state.collectAsState()
    val today = shell.time.today()
    val canEdit = vm.hub.canEdit(hub.personId ?: vm.hub.meId)
    var capture by remember { mutableStateOf(false) }
    val tone = Yuvomi.colors.module("health")

    HealthScaffold(
        vm = vm,
        title = t("nav.health"),
        back = null,
        personLabel = t("health.overview.personsLabel"),
        actions = {
            // The CSV export (`healthToolsHtml`) is a browser download: it stays in the web app.
            IconButton(onClick = { shell.openWeb("/health") }) { Icon(Icons.Outlined.Download, t("health.export.title")) }
        },
        fab = {
            // `overviewCaptureItems`: the FAB opens a menu of what can be recorded here.
            if (canEdit) {
                Box {
                    FloatingActionButton(onClick = { capture = true }) { Icon(Icons.Outlined.Add, t("health.overview.quick.title")) }
                    DropdownMenu(expanded = capture, onDismissRequest = { capture = false }) {
                        DropdownMenuItem(text = { Text(t("newLabel.healthVitals")) }, leadingIcon = { Icon(areaIcon("vitals"), null) }, onClick = {
                            capture = false; vm.hub.pendingAdd = "vitals"; open(HealthRoutes.VITALS)
                        })
                        DropdownMenuItem(text = { Text(t("newLabel.healthActivity")) }, leadingIcon = { Icon(areaIcon("activity"), null) }, onClick = {
                            capture = false; vm.hub.pendingAdd = "activity"; open(HealthRoutes.ACTIVITY)
                        })
                        DropdownMenuItem(text = { Text(t("health.overview.quick.meds")) }, leadingIcon = { Icon(areaIcon("meds"), null) }, onClick = {
                            capture = false; open(HealthRoutes.MEDS)
                        })
                        if (hub.cycleEnabled && vm.hub.isOwn) {
                            DropdownMenuItem(text = { Text(t("health.tabs.cycle")) }, leadingIcon = { Icon(areaIcon("cycle"), null) }, onClick = {
                                capture = false; open(HealthRoutes.CYCLE)
                            })
                        }
                    }
                }
            }
        },
    ) { data ->
        HealthVitals.brief(data.vitals, today, strings)?.let { brief ->
            item(key = "brief") { Text(brief, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }

        // What is due today comes first (`overviewDueMarkup`).
        item(key = "due") {
            OverviewCard(Icons.Outlined.CheckCircle, t("health.overview.dueToday.title")) {
                DueList(data.meds, today, canEdit && !s.offline, vm.dosesInFlight, vm::dose)
            }
        }
        val prn = HealthMeds.prnMeds(data.meds)
        if (prn.isNotEmpty()) {
            item(key = "prn") {
                OverviewCard(Icons.Outlined.Medication, t("health.meds.prn.title")) {
                    PrnList(prn, canEdit && !s.offline, vm.dosesInFlight, vm::prn)
                }
            }
        }

        // "All categories": every area with its status line, own view only (`areasNavMarkup`).
        item(key = "areas") {
            SectionTitle(t("health.overview.areasTitle"))
            Card(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    val own = vm.hub.isOwn
                    HealthRoutes.areas(hub.cycleEnabled, vm.hub.fastingEnabled).forEach { id ->
                        val status = if (!own) "" else if (id == "fasting") data.fastingStart?.let { start ->
                            shell.time.parse(start)?.let { st ->
                                val minutes = java.time.Duration.between(st, shell.time.now()).toMinutes().toDouble()
                                "${t("health.fasting.elapsed")}: ${HealthFasting.duration(minutes, strings)}"
                            }
                        }.orEmpty() else data.statuses[id].orEmpty()
                        AreaRow(id, status, tone) { open(HealthRoutes.area(id)) }
                    }
                }
            }
        }

        item(key = "vitals") {
            OverviewCard(Icons.Outlined.MonitorHeart, t("health.overview.vitals.title"), onTitle = { open(HealthRoutes.VITALS) }) {
                val entries = HealthVitals.METRICS.map { it to HealthVitals.series(data.vitals, it.type, "month", today, today) }
                    .filter { it.second.latest != null }
                if (entries.isEmpty()) {
                    Text(t("health.vitals.emptyTitle"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                } else {
                    entries.forEach { (metric, series) -> VitalLine(metric, series) }
                }
            }
        }

        item(key = "adherence") {
            OverviewCard(Icons.Outlined.LocalFireDepartment, t("health.overview.adherence.title")) {
                AdherenceBlock(data.meds, today, overview = true)
            }
        }

        if (hub.cycleEnabled) {
            val prediction = HealthCycle.predict(data.periods, data.cycleSettings ?: CycleSettings(), today, data.cycleLogs)
            if (prediction.hasData) {
                item(key = "cycle") {
                    OverviewCard(areaIcon("cycle"), t(if (prediction.pregnant) "health.cycle.pregnancy.title" else "health.cycle.status.nextPeriod"), onTitle = { open(HealthRoutes.CYCLE) }) {
                        if (prediction.pregnant) {
                            val p = prediction.pregnancy
                            Text(if (p.dueDate != null) t("health.cycle.pregnancy.week", "weeks" to p.weeks, "days" to p.days) else t("health.cycle.pregnancy.title"), style = MaterialTheme.typography.titleMedium)
                        } else {
                            val range = prediction.nextRange
                            Text(
                                if (range != null) "${shell.formats.date(range.first, today)} – ${shell.formats.date(range.second, today)}" else shell.formats.date(prediction.nextStart!!, today),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (range != null) t("health.cycle.status.nextPeriodRangeSub", "date" to shell.formats.date(prediction.nextStart!!, today)) else HealthCycle.nextText(prediction, strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = Yuvomi.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        item(key = "upcoming") {
            OverviewCard(Icons.Outlined.Timer, t("health.overview.reminders.title")) {
                val now = shell.time.now().toLocalTime().toString().take(5)
                val upcoming = HealthMeds.upcoming(data.meds, today, now).take(5)
                if (upcoming.isEmpty()) {
                    Text(t("health.overview.reminders.empty"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                } else {
                    upcoming.forEach { d ->
                        val name = data.meds.firstOrNull { it.id == d.medicationId }?.name.orEmpty()
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(d.time, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item(key = "disclaimer") { Disclaimer() }
    }
}

@Composable
internal fun OverviewCard(icon: ImageVector, title: String, onTitle: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().let { if (onTitle != null) it.clickable(onClick = onTitle) else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = Yuvomi.colors.module("health"))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (onTitle != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
            }
            content()
        }
    }
}

@Composable
private fun AreaRow(id: String, status: String, tone: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ModuleBadge(areaIcon(id), tone)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("health.tabs.$id"), style = MaterialTheme.typography.bodyLarge)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Yuvomi.colors.textTertiary)
    }
}

/** The latest value of one kind, with the change to the one before (`overviewVitalCardMarkup`). */
@Composable
internal fun VitalLine(metric: VitalMetric, series: HealthVitals.Series) {
    val strings = LocalStrings.current
    val (value, unit) = HealthVitals.cardValue(metric, series.latest, strings)
    val delta = if (metric.format == "scale") "" else HealthVitals.deltaText(metric, series.deltas[0], strings)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t(metric.labelKey), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$value${if (unit.isNotEmpty()) " $unit" else ""}", style = MaterialTheme.typography.titleSmall)
        if (delta.isNotEmpty()) Text("  $delta", style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** Today's planned doses with the check ring and the skip menu (`dueTodayMarkup`, `doseCheckMarkup`). */
@Composable
internal fun DueList(meds: List<Medication>, today: java.time.LocalDate, canEdit: Boolean, inFlight: Set<Int>, dose: (HealthMeds.Dose, MedLog?, Boolean) -> Unit) {
    val strings = LocalStrings.current
    val due = HealthMeds.dueDoses(HealthMeds.currentSchedules(meds), today, today)
    if (due.isEmpty()) {
        Text(t("health.meds.dueToday.empty"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        return
    }
    val logs = meds.flatMap { it.logs }
    due.forEach { d ->
        val med = meds.firstOrNull { it.id == d.medicationId }
        val name = med?.name.orEmpty()
        val log = HealthMeds.logFor(d, logs)
        val status = log?.status
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val open = status == null || status == "pending"
            if (canEdit && open) {
                IconButton(onClick = { dose(d, log, true) }, enabled = d.medicationId !in inFlight, modifier = Modifier.semantics { contentDescription = strings.t("health.meds.takeNamed", "name" to name) }) {
                    Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Yuvomi.colors.module("health"))
                }
            } else {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (status == "taken") Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        if (status == "taken") t("health.meds.status.taken") else null,
                        tint = if (status == "taken") Yuvomi.colors.success else Yuvomi.colors.textTertiary,
                    )
                }
            }
            Text(d.time, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
            val doseText = d.doseQty?.let { " · " + strings.t("health.meds.doseQty", "count" to strings.num(it)) }.orEmpty()
            Text(name + doseText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when {
                status == "skipped" -> Text(t("health.meds.status.skipped"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                status == "taken" -> Unit
                !canEdit -> Text(t("health.meds.status.pending"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
                else -> {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to name)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(t("health.meds.skip")) }, onClick = { menu = false; dose(d, log, false) })
                        }
                    }
                }
            }
        }
    }
}

/** As-needed medications with their countdown (`prnRowMarkup`). The early-dose question is asked by the caller. */
@Composable
internal fun PrnList(meds: List<Medication>, canEdit: Boolean, inFlight: Set<Int>, take: (Medication) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    var early by remember { mutableStateOf<Pair<Medication, HealthMeds.PrnState>?>(null) }
    val now = shell.time.now().toLocalDateTime()
    val today = shell.time.today()
    fun whenLabel(at: java.time.LocalDateTime): String =
        if (at.toLocalDate() == today) shell.formats.time(at.toLocalTime()) else "${shell.formats.date(at.toLocalDate(), today)} ${shell.formats.time(at.toLocalTime())}"
    fun remaining(minutes: Long): String =
        if (minutes >= 60) strings.t("health.meds.prn.remainingHm", "hours" to minutes / 60, "minutes" to minutes % 60) else strings.t("health.meds.prn.remainingM", "minutes" to minutes)
    meds.forEach { med ->
        val state = HealthMeds.prnState(med, now) { shell.time.parse(it)?.toLocalDateTime() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (canEdit) {
                IconButton(
                    onClick = { if (state.allowed) take(med) else early = med to state },
                    enabled = med.id !in inFlight,
                    modifier = Modifier.semantics { contentDescription = strings.t("health.meds.takeNamed", "name" to med.name) },
                ) { Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Yuvomi.colors.module("health")) }
            }
            Column(Modifier.weight(1f).padding(start = if (canEdit) 0.dp else 4.dp)) {
                val doseText = med.prnDoseQty?.let { strings.t("health.meds.doseQty", "count" to strings.num(it)) } ?: med.dosageText.orEmpty()
                Text(med.name + if (doseText.isNotEmpty()) " · $doseText" else "", style = MaterialTheme.typography.bodyMedium)
                val line = when {
                    !state.allowed && state.nextAllowedAt != null ->
                        "${strings.t("health.meds.prn.nextAt", "time" to whenLabel(state.nextAllowedAt))} · ${remaining(state.remainingMinutes)}"
                    state.lastTakenAt == null -> strings.t("health.meds.prn.never")
                    else -> "${strings.t("health.meds.prn.ready")} · ${strings.t("health.meds.prn.lastTaken", "time" to whenLabel(state.lastTakenAt))}"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
            }
        }
    }
    early?.let { (med, state) ->
        ConfirmDialog(
            title = t("health.meds.prn.earlyConfirm"),
            detail = t("health.meds.prn.earlyDetail", "time" to whenLabel(state.nextAllowedAt!!), "remaining" to remaining(state.remainingMinutes)),
            confirm = t("health.meds.prn.earlyConfirmAction"),
            onConfirm = { take(med) },
            onDismiss = { early = null },
        )
    }
}

/** Adherence over the last seven days, plus the streak on the overview (`overviewAdherenceMarkup`, `adherenceMarkup`). */
@Composable
internal fun AdherenceBlock(meds: List<Medication>, today: java.time.LocalDate, overview: Boolean) {
    val strings = LocalStrings.current
    val a = HealthMeds.adherenceFor(meds, today)
    if (!overview) Text(t("health.meds.adherence.period", "days" to HealthMeds.ADHERENCE_DAYS), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    when {
        a.rate == null -> Text(t(if (overview) "health.overview.adherence.noData" else "health.meds.adherence.noData"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        a.taken == 0 -> Text(t(if (overview) "health.overview.adherence.notStarted" else "health.meds.adherence.notStarted"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
        else -> {
            val pct = Math.round(a.rate * 100).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatTile(
                    if (overview) strings.t("health.overview.adherence.period", "days" to HealthMeds.ADHERENCE_DAYS) else strings.t("health.meds.adherence.summary", "taken" to a.taken, "planned" to a.planned),
                    "$pct%",
                    Modifier.weight(1f),
                )
                if (overview) {
                    val streak = HealthMeds.streak(meds, today)
                    if (streak > 0) StatTile(t("health.overview.adherence.streakLabel"), strings.num(streak.toDouble(), 0))
                }
            }
            ProgressBar(pct / 100f, Yuvomi.colors.module("health"))
        }
    }
}
