package io.github.marcomow.yuvomi.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.marcomow.yuvomi.core.api.model.Comment
import io.github.marcomow.yuvomi.core.api.model.Task
import io.github.marcomow.yuvomi.core.design.AvatarStack
import io.github.marcomow.yuvomi.core.design.Card
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** docs/spec/tasks.md, TASKS-R25 and tier 2. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(taskId: Int, back: () -> Unit, edit: (Int) -> Unit, addSubtask: (Int) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val vm = viewModel(key = "task-$taskId") { TaskDetailViewModel(shell.container, taskId) }
    val s by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val completion = remember(shell.connection) { TaskCompletion(shell.connection.tasks) }
    val today = shell.time.today()
    val canWrite = shell.session.access("tasks") == "write"
    var menu by remember { mutableStateOf(false) }
    var pickDoer by remember { mutableStateOf(false) }
    var confirmSubtask by remember { mutableStateOf<Task?>(null) }
    var renameSubtask by remember { mutableStateOf<Task?>(null) }

    LaunchedEffect(s.gone) {
        if (s.gone) {
            shell.notify(strings.t("android.tasks.gone"))
            back()
        }
    }

    fun report(key: String?) {
        if (key != null) scope.launch { shell.notify(strings.t(key)) }
    }

    fun tick(task: Task, doneBy: Pair<Int, String>? = null) = scope.launch {
        try {
            val receipt = completion.setDone(task, !task.isDone, doneBy, strings, shell.formats, today)
            vm.load()
            if (receipt != null && shell.notify(receipt.message, strings.t("common.undo"))) {
                completion.undo(task)
                vm.load()
            }
        } catch (e: Exception) {
            shell.notify(strings.t(TaskCompletion.errorKey(e)))
        }
    }

    val task = s.task
    val mayEditDefinition = task != null && canWrite &&
        (!task.locked || task.createdBy == shell.session.user.id || shell.session.isAdmin)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                actions = {
                    if (task != null && canWrite) {
                        IconButton(onClick = { edit(task.id) }) { Icon(Icons.Outlined.Edit, t("tasks.editButton")) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (!task.isDone && !shell.session.soloHousehold && task.parentTaskId == null) {
                                DropdownMenuItem(text = { Text(t("tasks.doneByMenuItem")) }, onClick = { menu = false; pickDoer = true })
                            }
                            DropdownMenuItem(text = { Text(t("android.browser.openWebApp")) }, onClick = { menu = false; shell.openWeb("/tasks") })
                        }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        when {
            task == null && s.loading -> LoadingBox(Modifier.padding(padding))
            task == null -> ErrorBox(strings.t(s.errorKey ?: "android.error.generic"), onRetry = vm::load, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Title, status, actions ──
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = task.isDone,
                        onCheckedChange = { tick(task) },
                        enabled = canWrite,
                    )
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                        )
                        Text(t(statusKey(task.status)), style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.module("tasks"))
                    }
                }
                if (canWrite) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.status == Task.STATUS_OPEN) {
                            OutlinedButton(onClick = { scope.launch { report(vm.setStatus(Task.STATUS_IN_PROGRESS)) } }) { Text(t("tasks.detailStart")) }
                        }
                        if (task.status != Task.STATUS_OPEN) {
                            OutlinedButton(onClick = { scope.launch { report(vm.setStatus(Task.STATUS_OPEN)) } }) { Text(t("tasks.statusOpen")) }
                        }
                    }
                }
                if (task.locked) {
                    Text(t("tasks.lockedHint"), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                }

                // ── Facts ──
                Card {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        HouseholdTime.date(task.dueDate)?.let { Fact(t("tasks.dueDateLabel"), shell.formats.due(it, task.dueTime, today)) }
                        HouseholdTime.date(task.startDate)?.let { Fact(t("tasks.startDateLabel"), shell.formats.date(it, today)) }
                        Fact(t("tasks.priorityLabel"), t(priorityKey(task.priority)))
                        Fact(t("tasks.categoryLabel"), categoryLabel(task.category, s.meta.categories, strings))
                        if (task.isRecurring && !task.recurrenceRule.isNullOrBlank()) {
                            val text = RRuleText.describe(task.recurrenceRule, task.recurrenceFromCompletion, strings, shell.formats, today)
                            Fact(t("rrule.labelRepeat"), text.ifEmpty { t("android.tasks.ruleCustom") })
                        }
                        if (task.assignedUsers.isNotEmpty()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t("tasks.assignedLabel"), color = Yuvomi.colors.textSecondary, modifier = Modifier.width(120.dp))
                                AvatarStack(task.assignedUsers.map { Triple(it.displayName, it.color, it.avatarData) }, max = 5)
                                Spacer(Modifier.width(8.dp))
                                Text(task.assignedUsers.joinToString(", ") { it.displayName }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (task.points > 0) Fact(t("tasks.pointsLabel"), t("tasks.pointsSummary", "count" to task.points))
                        if (!shell.session.soloHousehold && task.visibility != "all") {
                            Fact(t("common.visibility.label"), t("common.visibility.${task.visibility}"))
                        }
                        if (task.locked) Fact(t("tasks.lockedLabel"), t("tasks.lockedDetail"))
                        task.createdBy?.let { id ->
                            s.meta.users.firstOrNull { it.id == id }?.let { Fact(t("android.tasks.createdBy"), it.displayName) }
                        }
                    }
                }
                if (task.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { task.tags.forEach { TagChip(it) } }
                }

                // ── Description with checklists (TASKS-R33) ──
                task.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            MarkdownText(
                                description,
                                linkColor = MaterialTheme.colorScheme.primary,
                                onCheck = if (canWrite) { line, checked, shown -> scope.launch { report(vm.checkLine(line, checked, shown)) } } else null,
                            )
                        }
                    }
                }

                // ── Subtasks (TASKS-R30 to R32) ──
                if (task.parentTaskId == null && (task.subtasks.isNotEmpty() || mayEditDefinition)) {
                    SectionTitle(t("tasks.subtasksLabel"), task.subtasks.takeIf { it.isNotEmpty() }?.let { subs -> "${subs.count { it.isDone }}/${subs.size}" })
                    Card {
                        Column {
                            task.subtasks.forEach { sub ->
                                // As in the web app: the row is the checkbox. Subtasks have no detail
                                // of their own (the server answers GET /tasks/{id} only for top-level
                                // tasks); renaming and deleting sit in the row's menu.
                                Row(
                                    Modifier.fillMaxWidth().toggleable(
                                        value = sub.isDone,
                                        enabled = canWrite,
                                        role = Role.Checkbox,
                                        onValueChange = { scope.launch { report(vm.toggleSubtask(sub)) } },
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = sub.isDone, onCheckedChange = null, enabled = canWrite, modifier = Modifier.padding(12.dp))
                                    Text(
                                        sub.title,
                                        textDecoration = if (sub.isDone) TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f),
                                    )
                                    AvatarStack(sub.assignedUsers.map { Triple(it.displayName, it.color, it.avatarData) })
                                    if (mayEditDefinition) {
                                        Box {
                                            var subMenu by remember { mutableStateOf(false) }
                                            IconButton(onClick = { subMenu = true }) {
                                                Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to sub.title), tint = Yuvomi.colors.textSecondary)
                                            }
                                            DropdownMenu(subMenu, onDismissRequest = { subMenu = false }) {
                                                DropdownMenuItem(
                                                    text = { Text(t("common.edit")) },
                                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                                    onClick = { subMenu = false; renameSubtask = sub },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(t("common.delete"), color = Yuvomi.colors.danger) },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                                                    onClick = { subMenu = false; confirmSubtask = sub },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (mayEditDefinition) {
                                TextButton(onClick = { addSubtask(task.id) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                                    Icon(Icons.Outlined.Add, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(t("tasks.subtaskAdd"))
                                }
                            }
                        }
                    }
                }

                // ── Documents (TASKS-R38 to R40) ──
                task.documents?.takeIf { it.isNotEmpty() }?.let { docs ->
                    SectionTitle(t("tasks.documentsLabel"), null)
                    Card {
                        Column {
                            docs.forEach { doc ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { shell.openWeb("/documents?open=${doc.id}") }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Description, null, tint = Yuvomi.colors.module("documents"))
                                    Spacer(Modifier.width(12.dp))
                                    Text(doc.label, modifier = Modifier.weight(1f))
                                }
                            }
                            TextButton(onClick = { shell.openWeb("/tasks") }, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(t("android.tasks.manageInWebApp"))
                            }
                        }
                    }
                }

                // ── Comments (TASKS-R35 to R37) ──
                if (task.parentTaskId == null) {
                    SectionTitle(t("tasks.commentsLabel"), null)
                    Comments(s, canWrite, vm, ::report)
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    if (pickDoer && task != null) {
        DoerPicker(task.title, s.meta.users, onDismiss = { pickDoer = false }) { m ->
            pickDoer = false
            tick(task, m.id to m.displayName)
        }
    }
    renameSubtask?.let { sub ->
        var title by remember(sub.id) { mutableStateOf(sub.title) }
        AlertDialog(
            onDismissRequest = { renameSubtask = null },
            title = { Text(t("tasks.subtaskRenamePrompt")) },
            text = {
                OutlinedTextField(
                    title, { title = it }, singleLine = true,
                    isError = title.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    renameSubtask = null
                    if (title.trim() != sub.title) scope.launch { report(vm.renameSubtask(sub, title.trim())) }
                }) { Text(t("common.save")) }
            },
            dismissButton = { TextButton(onClick = { renameSubtask = null }) { Text(t("common.cancel")) } },
        )
    }
    confirmSubtask?.let { sub ->
        ConfirmDialog(
            title = t("tasks.subtaskDeleteConfirm", "title" to sub.title),
            body = t("tasks.subtaskDeleteDetail"),
            confirm = t("common.delete"),
            onDismiss = { confirmSubtask = null },
        ) {
            confirmSubtask = null
            scope.launch { report(vm.deleteSubtask(sub)) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, color = Yuvomi.colors.textSecondary, modifier = Modifier.width(120.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String?) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = Yuvomi.colors.textSecondary)
    }
}

/** Comment timestamps come from SQLite in UTC; a value without an offset is UTC. */
private fun commentTime(value: String?): ZonedDateTime? {
    val v = value?.trim()?.replace(' ', 'T') ?: return null
    return runCatching { Instant.parse(if (v.endsWith("Z")) v else "${v}Z").atZone(ZoneOffset.UTC) }
        .recoverCatching { LocalDateTime.parse(v).atZone(ZoneOffset.UTC) }
        .getOrNull()
}

@Composable
private fun Comments(s: TaskDetailViewModel.UiState, canWrite: Boolean, vm: TaskDetailViewModel, report: (String?) -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val people = s.meta.users.map { Mentions.Person(it.id, it.displayName) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf<Comment?>(null) }
    var confirmDelete by remember { mutableStateOf<Comment?>(null) }

    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            when {
                s.commentsFailed && s.comments == null -> Text(t("tasks.commentsLoadError"), Modifier.padding(16.dp), color = Yuvomi.colors.danger)
                s.comments.isNullOrEmpty() -> Text(t("tasks.commentsEmpty"), Modifier.padding(16.dp), color = Yuvomi.colors.textSecondary)
            }
            s.comments.orEmpty().forEach { c ->
                val mine = c.userId == shell.session.user.id
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    MemberAvatar(c.authorName ?: strings.t("tasks.commentUnknownAuthor"), c.authorColor, null, size = 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val created = commentTime(c.createdAt)?.withZoneSameInstant(shell.time.zone)
                        val stamp = created?.let { z ->
                            if (c.updatedAt != null) {
                                strings.t("tasks.commentEditedAt", "date" to shell.formats.date(z.toLocalDate(), shell.time.today()), "time" to shell.formats.time(z))
                            } else {
                                shell.formats.dateTime(z)
                            }
                        }.orEmpty()
                        Text(
                            "${c.authorName ?: strings.t("tasks.commentUnknownAuthor")} · $stamp",
                            style = MaterialTheme.typography.labelMedium,
                            color = Yuvomi.colors.textSecondary,
                        )
                        Text(mentionText(c.comment, people, MaterialTheme.colorScheme.primary))
                    }
                    if (canWrite && (mine || shell.session.isAdmin)) {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            if (mine) {
                                DropdownMenuItem(text = { Text(t("tasks.commentEdit")) }, onClick = {
                                    open = false
                                    editing = c
                                    draft = TextFieldValue(c.comment, TextRange(c.comment.length))
                                })
                            }
                            DropdownMenuItem(text = { Text(t("tasks.commentDelete")) }, onClick = { open = false; confirmDelete = c })
                        }
                    }
                }
            }
            if (canWrite) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val query = Mentions.activeQuery(draft.text, draft.selection.start)
                if (query != null) {
                    val matches = people.filter { it.displayName.startsWith(query, ignoreCase = true) && it.id != shell.session.user.id }.take(5)
                    if (matches.isNotEmpty()) {
                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            matches.forEach { p ->
                                SuggestionChip(onClick = {
                                    Mentions.apply(draft.text, draft.selection.start, p.displayName)?.let { (text, caret) ->
                                        draft = TextFieldValue(text, TextRange(caret))
                                    }
                                }, label = { Text("@${p.displayName}") })
                            }
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.text.length <= 5000) draft = it },
                        placeholder = { Text(t("tasks.commentPlaceholder")) },
                        modifier = Modifier.weight(1f),
                        maxLines = 6,
                    )
                    if (editing != null) {
                        IconButton(onClick = { editing = null; draft = TextFieldValue("") }) { Icon(Icons.Outlined.Close, t("common.cancel")) }
                    }
                    IconButton(
                        enabled = draft.text.isNotBlank() && !s.sendingComment,
                        onClick = {
                            val text = draft.text
                            val target = editing
                            scope.launch {
                                val error = if (target != null) vm.editComment(target, text) else vm.addComment(text)
                                if (error == null) {
                                    draft = TextFieldValue("")
                                    editing = null
                                }
                                report(error)
                            }
                        },
                    ) { Icon(Icons.AutoMirrored.Outlined.Send, t("tasks.commentSubmit")) }
                }
            }
        }
    }

    confirmDelete?.let { c ->
        ConfirmDialog(t("tasks.commentDelete"), null, t("common.delete"), onDismiss = { confirmDelete = null }) {
            confirmDelete = null
            scope.launch {
                val error = vm.deleteComment(c)
                report(error ?: "tasks.commentDeletedToast")
            }
        }
    }
}

private fun mentionText(text: String, people: List<Mentions.Person>, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    Mentions.split(text, people).forEach { seg ->
        when (seg) {
            is Mentions.Segment.Mention -> withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(seg.text) }
            is Mentions.Segment.Text -> append(seg.text)
        }
    }
}
