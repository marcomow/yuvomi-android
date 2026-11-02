package io.github.marcomow.yuvomi.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalTime

/** docs/spec/health.md, HEALTH-R26 to R31 (pages/health-fasting.js). */
@Composable
fun HealthFastingScreen(vm: FastingViewModel, back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val s by vm.state.collectAsState()
    val canWrite = vm.canWrite && !s.offline
    var askSafety by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Fast?>(null) }
    val today = shell.time.today()
    val start: () -> Unit = { if (s.data?.state?.acknowledged == true) vm.start(false) else askSafety = true }

    if (!vm.hub.fastingEnabled) {
        // Fasting is a capability per account; without it the area does not exist (`HEALTH_AREAS`).
        HealthScaffold(vm, t("health.tabs.fasting"), back) { _ ->
            item(key = "off") { AreaEmpty("health.fasting.emptyTitle", "health.fasting.emptyDesc", null, null) }
        }
        return
    }

    HealthScaffold(
        vm = vm,
        title = t("health.tabs.fasting"),
        back = back,
        personLabel = t("health.fasting.person"),
        fab = {
            if (canWrite && s.data?.state?.active == null) {
                ExtendedFloatingActionButton(onClick = start, icon = { Icon(Icons.Outlined.PlayArrow, null) }, text = { Text(t("health.fasting.start")) })
            }
        },
    ) { data ->
        val st = data.state
        if (!vm.hub.isOwn) item(key = "ro") { Text(t("health.fasting.readOnly"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp)) }
        item(key = "clock") {
            Card(Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val active = st.active
                    val anchor = active?.startAt ?: vm.history().firstOrNull()?.endAt
                    val anchorAt = anchor?.let { shell.time.parse(it) }
                    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    if (active != null) LaunchedEffect(active.id) {
                        while (true) {
                            delay(1000)
                            nowMs = System.currentTimeMillis()
                        }
                    }
                    val seconds = anchorAt?.let { (nowMs - it.toInstant().toEpochMilli()) / 1000 } ?: 0
                    Text(t(if (active != null) "health.fasting.elapsed" else if (anchorAt != null) "health.fasting.sinceLast" else "health.fasting.ready"), style = MaterialTheme.typography.labelLarge)
                    if (anchorAt != null) Text(HealthFasting.clock(seconds), style = MaterialTheme.typography.displaySmall)
                    if (active != null) {
                        anchorAt?.let { Text(t("health.fasting.startedAt", "date" to shell.formats.dateTime(it)), style = MaterialTheme.typography.bodySmall) }
                        val goal = active.goalMinutes
                        if (goal != null && anchorAt != null) {
                            val remaining = goal * 60L - seconds
                            Text(
                                if (remaining <= 0) t("health.fasting.goalReached") else "${t("health.fasting.remaining")}: ${HealthFasting.duration(remaining / 60.0, strings)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (remaining <= 0) Yuvomi.colors.success else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(t("health.fasting.targetAt", "date" to shell.formats.dateTime(anchorAt.plusMinutes(goal.toLong()))), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                            ProgressBar((seconds / (goal * 60.0)).toFloat(), Yuvomi.colors.module("health"))
                        }
                        if (canWrite) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // "I started earlier" (`editStart`): the running fast's start in household time.
                            TextButton(onClick = { edit = active }) { Text(t("health.fasting.editStart")) }
                            Button(onClick = { vm.finish(active) }) { Text(t("health.fasting.finish")) }
                        }
                    }
                }
            }
        }
        // The goal and reminders, own view only (`fastingGoalHtml`, `fastingPreferencesHtml`).
        if (canWrite) {
            item(key = "goal") {
                SectionTitle(t("health.fasting.goalTitle"))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = st.active?.goalMinutes ?: st.settings?.defaultGoalMinutes
                    ChoiceChips(listOf<Pair<Int?, String>>(null to strings.t("health.fasting.noGoal")) + HealthFasting.PRESETS.map { it * 60 to "$it h" }, current, allowNone = false) {
                        vm.setGoal(it)
                    }
                    Text(t("health.fasting.goalNextHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                    SwitchRow(t("health.fasting.remindGoalToggle"), st.settings?.remindGoal == true) { vm.setReminder(goal = it) }
                    SwitchRow(t("health.fasting.remindNextToggle"), st.settings?.remindNextStart == true, t("health.fasting.reminderHint")) { vm.setReminder(next = it) }
                }
            }
        }
        data.stats?.let { stats ->
            item(key = "stats") {
                SectionTitle(t("health.fasting.statsTitle"))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    listOf("health.fasting.allTime" to stats.allTime, "health.fasting.thisYear" to stats.year, "health.fasting.last30Days" to stats.last30Days).forEach { (label, sum) ->
                        Text(t(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        Row {
                            StatTile(t("health.fasting.statsCount"), strings.num(sum.count.toDouble(), 0), Modifier.weight(1f))
                            StatTile(t("health.fasting.statsTotal"), HealthFasting.duration(sum.totalMinutes, strings), Modifier.weight(1f))
                            StatTile(t("health.fasting.statsAverage"), HealthFasting.duration(sum.averageMinutes ?: 0.0, strings), Modifier.weight(1f))
                        }
                    }
                    Row {
                        StatTile(t("health.fasting.statsCurrentStreak"), strings.num(stats.currentStreak.toDouble(), 0), Modifier.weight(1f))
                        StatTile(t("health.fasting.statsLongestStreak"), strings.num(stats.longestStreak.toDouble(), 0), Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "history") { SectionTitle(t("health.fasting.history")) }
        val history = vm.history()
        if (history.isEmpty()) item(key = "nohistory") {
            Text(t("health.fasting.noHistory"), style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        history.forEach { fast ->
            item(key = "fast-${fast.id}") {
                val a = shell.time.parse(fast.startAt)
                val b = fast.endAt?.let { shell.time.parse(it) }
                Row(
                    Modifier.fillMaxWidth().let { if (canWrite) it.clickable { edit = fast } else it }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a?.let { shell.formats.dateTime(it) }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        fast.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary) }
                    }
                    fast.rating?.let { Text("★ $it", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp)) }
                    if (a != null && b != null) Text(HealthFasting.duration(Duration.between(a, b).toMinutes().toDouble(), strings), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        if (data.cursor != null) item(key = "more") {
            TextButton(onClick = vm::loadMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text(t("health.fasting.loadMore")) }
        }
        item(key = "disclaimer") { Disclaimer() }
    }

    if (askSafety) {
        ConfirmDialog(t("health.fasting.safetyTitle"), t("health.fasting.safetyDialog"), t("common.confirm"), { vm.start(true) }, { askSafety = false })
    }
    edit?.let { fast -> FastEditor(vm, fast) { edit = null } }
}

/** `openFastingEditor`: start and end in household time, rating and note; delete with undo. */
@Composable
private fun FastEditor(vm: FastingViewModel, fast: Fast, close: () -> Unit) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    val start0 = shell.time.parse(fast.startAt) ?: shell.time.now()
    val end0 = fast.endAt?.let { shell.time.parse(it) }
    var startDate by remember { mutableStateOf(start0.toLocalDate()) }
    var startTime by remember { mutableStateOf(start0.toLocalTime().toString().take(5)) }
    var endDate by remember { mutableStateOf(end0?.toLocalDate()) }
    var endTime by remember { mutableStateOf(end0?.toLocalTime()?.toString()?.take(5).orEmpty()) }
    var rating by remember { mutableStateOf(fast.rating) }
    var note by remember { mutableStateOf(fast.note.orEmpty()) }
    fun at(d: java.time.LocalDate, tm: String) = d.atTime(runCatching { LocalTime.parse(tm) }.getOrDefault(LocalTime.MIDNIGHT)).atZone(shell.time.zone)
    EditorDialog(t("health.fasting.editTitle"), close, {
        vm.update(fast, at(startDate, startTime), endDate?.let { at(it, endTime) }, rating, note, close)
    }, saving = s.busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DateField(t("health.fasting.startDate"), startDate) { d -> d?.let { startDate = it } }
            TimeField(t("health.meds.log.timeLabel"), startTime) { startTime = it }
        }
        if (fast.endAt != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DateField(t("health.fasting.endDate"), endDate) { d -> d?.let { endDate = it } }
            TimeField(t("health.meds.log.timeLabel"), endTime) { endTime = it }
        }
        Text(t("health.fasting.rating"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { n ->
                IconButton(onClick = { rating = n }) {
                    Icon(if ((rating ?: 0) >= n) Icons.Outlined.Star else Icons.Outlined.StarOutline, t("health.fasting.ratingValue", "value" to n), tint = Yuvomi.colors.warning)
                }
            }
            if (rating != null) TextButton(onClick = { rating = null }) { Text(t("health.fasting.clearRating")) }
        }
        TextInput(t("health.fasting.note"), note, lines = 2) { note = it }
        if (fast.endAt != null) DeleteButton { close(); vm.delete(fast) }
    }
}
