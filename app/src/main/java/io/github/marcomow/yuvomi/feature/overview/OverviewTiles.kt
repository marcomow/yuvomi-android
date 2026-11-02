package io.github.marcomow.yuvomi.feature.overview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.model.DashEvent
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.design.AvatarStack
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.ModuleBadge
import io.github.marcomow.yuvomi.core.design.ModuleCatalog
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.Features
import io.github.marcomow.yuvomi.feature.birthdays.BirthdaysRoutes
import io.github.marcomow.yuvomi.feature.calendar.CalendarRoutes
import io.github.marcomow.yuvomi.feature.meals.MealsRoutes
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shopping.ShoppingRoutes
import java.time.LocalDate

private const val GLANCE_ROWS = 5

/** OVERVIEW-R3 to R6, phone form: a list, no timeline. */
@Composable
fun TodayGlance(
    rows: List<GlanceRow>,
    expanded: Boolean,
    toggle: () -> Unit,
    readOnly: Boolean,
    openTask: (Int) -> Unit,
    tick: (Task, Pair<Int, String>?) -> Unit,
    today: LocalDate,
) {
    val shell = LocalShell.current
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            TileHeader(t("dashboard.todayTitle"), "dashboard", null)
            if (rows.isEmpty()) {
                Text(t("dashboard.todayFree"), Modifier.padding(16.dp), color = Yuvomi.colors.textSecondary)
            }
            val shown = if (expanded) rows else rows.take(GLANCE_ROWS)
            shown.forEach { row ->
                when (row) {
                    is GlanceRow.Event -> EventLine(row.event, row.start?.let { shell.formats.time(it) }, row.running) {
                        shell.navigate(row.event.id?.let(CalendarRoutes::event) ?: CalendarRoutes.START)
                    }
                    is GlanceRow.TaskRow -> TaskLine(row.task, readOnly, openTask, tick, today)
                    is GlanceRow.Meal -> SimpleLine(
                        Icons.Outlined.Restaurant, "meals",
                        t("meals.type" + row.meal.mealType.replaceFirstChar { it.uppercase() }), row.meal.title,
                    ) { shell.navigate(MealsRoutes.week()) }
                    is GlanceRow.Shopping -> SimpleLine(
                        Icons.Outlined.ShoppingCart, "shopping",
                        t("dashboard.todayShopping"), t("dashboard.todayShoppingCount", "count" to row.count),
                    ) { shell.navigate(ShoppingRoutes.list()) }
                }
            }
            if (rows.size > GLANCE_ROWS) {
                TextButton(onClick = toggle, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (expanded) t("dashboard.todayLess") else t("dashboard.todayMore", "count" to rows.size - GLANCE_ROWS))
                }
            }
        }
    }
}

@Composable
fun TileView(
    tile: Tile,
    readOnly: Boolean,
    openTask: (Int) -> Unit,
    openTasks: (Int?) -> Unit,
    tick: (Task, Pair<Int, String>?) -> Unit,
    today: LocalDate,
) {
    val shell = LocalShell.current
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            when (tile) {
                is Tile.Tasks -> {
                    TileHeader(
                        t("nav.tasks"), "tasks",
                        tile.openCount?.let { t("dashboard.metricOpen", "count" to it) },
                        onClick = { openTasks(null) },
                    )
                    if (tile.tasks.isEmpty()) EmptyLine(t("dashboard.todayNoTasks"))
                    tile.tasks.forEach { TaskLine(it, readOnly, openTask, tick, today) }
                    tile.overdueCount?.takeIf { it > 0 }?.let {
                        Text(
                            t("dashboard.todaySummaryOverdue", "count" to it),
                            color = Yuvomi.colors.danger,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                is Tile.Calendar -> {
                    TileHeader(t("nav.calendar"), "calendar", null, web = "/calendar")
                    if (tile.days.isEmpty()) EmptyLine(t("dashboard.noEvents"))
                    tile.days.forEach { (day, events) ->
                        Text(
                            dayLabel(day, today),
                            style = MaterialTheme.typography.labelMedium,
                            color = Yuvomi.colors.textSecondary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        )
                        events.forEach { (event, start) ->
                            EventLine(event, if (event.allDay) null else start?.let { shell.formats.time(it) }, false) {
                                shell.navigate(event.id?.let(CalendarRoutes::event) ?: CalendarRoutes.START)
                            }
                        }
                    }
                }
                is Tile.Shopping -> {
                    TileHeader(t("nav.shopping"), "shopping", t("dashboard.todayShoppingCount", "count" to tile.openCount)) {
                        shell.navigate(ShoppingRoutes.list())
                    }
                    if (tile.lists.isEmpty() && tile.openCount == 0) EmptyLine(t("dashboard.shoppingListNothingOpen"))
                    tile.lists.forEach { list ->
                        ListLine(list.name, if (list.openCount > 0) t("dashboard.todayShoppingCount", "count" to list.openCount) else t("dashboard.shoppingListNothingOpen")) {
                            shell.navigate(ShoppingRoutes.list(list.id))
                        }
                    }
                }
                is Tile.Meals -> {
                    TileHeader(t("dashboard.todayMeals"), "meals", null) { shell.navigate(MealsRoutes.week()) }
                    if (tile.meals.isEmpty()) EmptyLine(t("dashboard.todayNothingPlanned"))
                    tile.meals.forEach { meal ->
                        ListLine(t("meals.type" + meal.mealType.replaceFirstChar { it.uppercase() }), meal.title) { shell.navigate(MealsRoutes.week()) }
                    }
                }
                is Tile.Birthdays -> {
                    TileHeader(t("dashboard.upcomingBirthdays"), "birthdays", null, web = "/birthdays")
                    if (tile.birthdays.isEmpty()) EmptyLine(t("dashboard.noBirthdays"))
                    tile.birthdays.forEach { b ->
                        val days = b.daysUntil
                        val whenText = when (days) {
                            null -> ""
                            0 -> t("common.today")
                            1 -> t("common.tomorrow")
                            else -> t("dashboard.daysLeft", "count" to days)
                        }
                        val age = b.nextAge?.let { " · $it" } ?: ""
                        ListLine(b.name, whenText + age) { shell.navigate(BirthdaysRoutes.START) }
                    }
                    if (tile.total > tile.birthdays.size) {
                        EmptyLine(t("dashboard.birthdaysMore", "count" to tile.total - tile.birthdays.size))
                    }
                }
                is Tile.Family -> {
                    TileHeader(t("dashboard.familyTitle"), "dashboard", null)
                    tile.members.forEach { (member, open) ->
                        ListItem(
                            headlineContent = { Text(member.displayName) },
                            supportingContent = {
                                Text(if (open > 0) t("dashboard.memberOpenTasks", "count" to open) else t("dashboard.familyDayCalm"))
                            },
                            leadingContent = { MemberAvatar(member.displayName, member.avatarColor, member.avatarData) },
                            modifier = Modifier.clickable { openTasks(member.id) },
                        )
                    }
                }
                is Tile.Generic -> {
                    val module = ModuleCatalog.forWidget(tile.id)
                    // A module with native screens opens them (ADR 0015); the rest open the web app.
                    val native = Features.all.firstOrNull { it.id == module?.id }
                    val open = { if (native != null) shell.navigate(native.startRoute) else shell.openWeb(module?.route ?: "/") }
                    TileHeader(t(module?.labelKey ?: "nav.dashboard"), module?.tone ?: "dashboard", null, onClick = open)
                    ListLine(t(if (native != null) "android.overview.open" else "android.overview.openInWebApp"), null, open)
                }
            }
        }
    }
}

@Composable
private fun TileHeader(title: String, tone: String, trailing: String?, web: String? = null, onClick: (() -> Unit)? = null) {
    val shell = LocalShell.current
    val module = ModuleCatalog.get(tone) ?: ModuleCatalog.get("dashboard")!!
    val click = onClick ?: web?.let { { shell.openWeb(it) } }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModuleBadge(module.icon, Yuvomi.colors.module(module.tone))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        if (web != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, t("android.browser.opensInBrowser"), Modifier.size(16.dp), tint = Yuvomi.colors.textTertiary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskLine(task: Task, readOnly: Boolean, openTask: (Int) -> Unit, tick: (Task, Pair<Int, String>?) -> Unit, today: LocalDate) {
    val shell = LocalShell.current
    var pickDoer by remember { mutableStateOf(false) }
    val due = HouseholdTime.date(task.dueDate)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { openTask(task.id) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.combinedClickable(
                enabled = !readOnly,
                onClick = { tick(task, null) },
                onLongClick = { if (!shell.session.soloHousehold) pickDoer = true },
            ),
        ) {
            Checkbox(checked = task.isDone, onCheckedChange = null, enabled = !readOnly, modifier = Modifier.padding(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (due != null) {
                Text(
                    shell.formats.due(due, task.dueTime, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (due.isBefore(today)) Yuvomi.colors.danger else Yuvomi.colors.textSecondary,
                )
            }
        }
        AvatarStack(task.assignedUsers.map { Triple(it.displayName, it.color, it.avatarData) })
        Spacer(Modifier.width(12.dp))
    }
    if (pickDoer) {
        DoerDialog(task, onDismiss = { pickDoer = false }) { id, name ->
            pickDoer = false
            tick(task, id to name)
        }
    }
}

/** TASKS-R21 from the overview: the household members, from the dashboard's `users`. */
@Composable
private fun DoerDialog(task: Task, onDismiss: () -> Unit, pick: (Int, String) -> Unit) {
    val shell = LocalShell.current
    val members = remember { mutableStateOf<List<io.github.marcomow.yuvomi.core.api.model.Member>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        members.value = runCatching {
            io.github.marcomow.yuvomi.core.api.TasksApi.decodeMeta(shell.connection.tasks.meta()).users
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("tasks.doneByPick", "title" to task.title)) },
        text = {
            Column {
                members.value.forEach { m ->
                    ListItem(
                        headlineContent = { Text(m.displayName) },
                        leadingContent = { MemberAvatar(m.displayName, m.avatarColor, m.avatarData) },
                        modifier = Modifier.clickable { pick(m.id, m.displayName) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@Composable
private fun EventLine(event: DashEvent, time: String?, running: Boolean, open: () -> Unit) {
    val dot = Tokens.parseColor(event.color) ?: Yuvomi.colors.module("calendar")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                running -> t("dashboard.todayTimelineRunning")
                time == null -> t("dashboard.allDay")
                else -> time
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (running) Yuvomi.colors.module("calendar") else Yuvomi.colors.textSecondary,
            modifier = Modifier.width(76.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SimpleLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: String, label: String, value: String, open: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = Yuvomi.colors.module(tone), modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary, modifier = Modifier.width(76.dp))
        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ListLine(title: String, value: String?, open: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, color = Yuvomi.colors.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> t("common.today")
    today.plusDays(1) -> t("common.tomorrow")
    else -> LocalShell.current.formats.weekdayDate(day)
}
