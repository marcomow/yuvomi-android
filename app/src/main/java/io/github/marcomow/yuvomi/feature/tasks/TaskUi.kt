package io.github.marcomow.yuvomi.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.model.Member
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.api.model.TaskCategory
import io.github.marcomow.yuvomi.core.design.AvatarStack
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.Strings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.tags.TagChip
import java.time.LocalDate

/** TASKS-R7: `label_key` through upstream's strings when present, else `name`. */
fun categoryLabel(key: String, categories: List<TaskCategory>, strings: Strings): String {
    val c = categories.firstOrNull { it.key == key } ?: return key
    return c.labelKey?.takeIf { strings.has(it) }?.let { strings.t(it) } ?: c.name ?: key
}

fun priorityKey(priority: String) = "tasks.priority" + priority.replaceFirstChar { it.uppercase() }

fun statusKey(status: String) = when (status) {
    Task.STATUS_IN_PROGRESS -> "tasks.statusInProgress"
    Task.STATUS_DONE -> "tasks.statusDone"
    else -> "tasks.statusOpen"
}

/** TASKS-R6: one row of the task list. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: Task,
    readOnly: Boolean,
    today: LocalDate,
    onOpen: () -> Unit,
    onTick: () -> Unit,
    onPickDoer: (() -> Unit)?,
    showCategory: String? = null,
) {
    val shell = LocalShell.current
    val due = HouseholdTime.date(task.dueDate)
    val doneStyle = if (task.isDone) TextDecoration.LineThrough else null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .combinedClickable(enabled = !readOnly, onClick = onTick, onLongClick = onPickDoer)
                .semantics { contentDescription = shell.container.strings.value?.t("tasks.markDone", "title" to task.title) ?: task.title },
        ) {
            Checkbox(checked = task.isDone, onCheckedChange = null, enabled = !readOnly, modifier = Modifier.padding(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.priority == "urgent" || task.priority == "high") {
                    Icon(
                        Icons.Outlined.PriorityHigh, t(priorityKey(task.priority)), Modifier.size(16.dp),
                        tint = if (task.priority == "urgent") Yuvomi.colors.danger else Yuvomi.colors.warning,
                    )
                }
                Text(task.title, textDecoration = doneStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (due != null) {
                    Text(
                        shell.formats.due(due, task.dueTime, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (due.isBefore(today) && !task.isDone) Yuvomi.colors.danger else Yuvomi.colors.textSecondary,
                    )
                }
                if (showCategory != null) Meta(null, showCategory)
                if (task.subtaskTotal > 0) Meta(Icons.Outlined.ChecklistRtl, "${task.subtaskDone}/${task.subtaskTotal}")
                task.documentCount?.takeIf { it > 0 }?.let { Meta(Icons.Outlined.AttachFile, it.toString()) }
                if (task.isRecurring) Meta(Icons.Outlined.Repeat, null, t("tasks.recurring"))
                if (task.locked) Meta(Icons.Outlined.Lock, null, t("tasks.lockedBadge"))
                when (task.visibility) {
                    "private" -> Meta(Icons.Outlined.VisibilityOff, null, t("common.visibility.private"))
                    "assignees" -> Meta(Icons.Outlined.Group, null, t("common.visibility.assignees"))
                }
                if (task.points > 0) Meta(null, t("tasks.pointsSummary", "count" to task.points))
                task.tags.take(3).forEach { TagChip(it) }
                if (task.tags.size > 3) Meta(null, "+${task.tags.size - 3}")
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.padding(top = 12.dp)) {
            AvatarStack(task.assignedUsers.map { Triple(it.displayName, it.color, it.avatarData) })
        }
    }
}

@Composable
private fun Meta(icon: ImageVector?, text: String?, description: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, description, Modifier.size(14.dp), tint = Yuvomi.colors.textSecondary)
        if (text != null) {
            if (icon != null) Spacer(Modifier.width(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
    }
}

/** TASKS-R21: "Who did it?" */
@Composable
fun DoerPicker(title: String, members: List<Member>, onDismiss: () -> Unit, pick: (Member) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("tasks.doneByPick", "title" to title)) },
        text = {
            Column {
                members.forEach { m ->
                    ListItem(
                        headlineContent = { Text(m.displayName) },
                        leadingContent = { MemberAvatar(m.displayName, m.avatarColor, m.avatarData) },
                        modifier = Modifier.clickable { pick(m) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** A yes/no question before something that cannot be undone. */
@Composable
fun ConfirmDialog(title: String, body: String?, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Yuvomi.colors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** State holder for "long press asks who did it". */
class DoerState {
    var task by mutableStateOf<Task?>(null)
}

@Composable
fun rememberDoerState() = remember { DoerState() }
