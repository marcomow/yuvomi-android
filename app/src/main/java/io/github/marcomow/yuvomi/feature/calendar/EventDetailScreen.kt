package io.github.marcomow.yuvomi.feature.calendar

import io.github.marcomow.yuvomi.feature.tags.HashtagText
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.documents.DocumentsRoutes
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.tasks.RRuleText
import kotlinx.coroutines.launch

/**
 * docs/spec/calendar.md, the read view of an event (CAL-R13, `openEventDetail` and
 * `renderEventDetail`), with Edit, Delete (and its scope question, CAL-R20) and the reset of
 * a changed subscribed event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(back: () -> Unit, edit: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = rememberCalendarStore() ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val rights = shell.calendarRights()
    val today = shell.time.today()
    val text = CalendarText(strings, store.dates, today)
    val ev = s.selected
    var question by remember { mutableStateOf<DeleteQuestion?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(ev == null) { if (ev == null) back() }
    if (ev == null) return
    val mayChange = rights.mayChange(ev) && !s.offline

    fun delete(choice: RecurrenceScope.Scope) {
        store.scheduleDelete(ev, choice, strings) { message, action -> shell.notify(message, action) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { store.close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                title = { Text(ev.title, maxLines = 1) },
                actions = {
                    if (mayChange) {
                        IconButton(onClick = {
                            store.editing = CalendarStore.EditTarget(event = ev, reminders = s.selectedReminders)
                            edit()
                        }) { Icon(Icons.Outlined.Edit, t("common.edit")) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActions")) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("common.delete")) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menu = false; question = deleteQuestion(ev) ?: run { delete(RecurrenceScope.Scope.SERIES); null } },
                                )
                                // A changed event of a subscription goes back to its original (`detail-ics-reset`).
                                if (ev.externalSource == "ics" && ev.userModified) {
                                    DropdownMenuItem(
                                        text = { Text(t("calendar.ics.reset")) },
                                        leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                                        onClick = {
                                            menu = false
                                            scope.launch {
                                                val error = store.reset(ev)
                                                shell.notify(strings.t(error ?: "calendar.ics.resetToast"))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    AccountButton()
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The head (`eventDetailHead`): colour dot, the time in words, the people.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color(CalendarRules.color(ev), Yuvomi.colors.textSecondary)))
                Spacer(Modifier.width(10.dp))
                Text(text.whenText(ev), style = MaterialTheme.typography.titleMedium)
            }
            if (ev.assignedUsers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ev.assignedUsers.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MemberAvatar(p.displayName, p.color, p.avatarData, size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(p.displayName)
                        }
                    }
                }
            }
            val calendar = calendarName(ev, s.calendars)
            if (calendar.isNotEmpty()) DetailRow(Icons.Outlined.CalendarMonth, t("calendar.detailCalendar"), calendar)
            ev.recurrenceRule?.takeIf { it.isNotBlank() }?.let { rule ->
                DetailRow(Icons.Outlined.Repeat, t("rrule.labelRepeat"), RRuleText.describe(rule, false, strings, shell.formats, today))
            }
            ev.location?.takeIf { it.isNotBlank() }?.let { loc ->
                val context = LocalContext.current
                DetailRow(Icons.Outlined.Place, t("calendar.locationLabel"), flatLocation(loc))
                // `mapRowAction`: an OpenStreetMap search, only when tapped (#1110).
                TextButton(onClick = {
                    val url = "https://www.openstreetmap.org/search?query=" + Uri.encode(flatLocation(loc))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                }) {
                    Icon(Icons.Outlined.Map, null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("calendar.openInMap"))
                }
            }
            if (ev.assignedUsers.isEmpty() && !ev.assignedName.isNullOrBlank()) {
                DetailRow(Icons.Outlined.Person, t("calendar.assignedLabel"), ev.assignedName)
            }
            val reminders = EventReminders.summary(ev, s.selectedReminders, store.dates, strings) { text.moment(it) }
            if (reminders.isNotEmpty()) {
                DetailRow(Icons.Outlined.Notifications, t(if (s.selectedReminders.size > 1) "reminders.sectionTitlePlural" else "reminders.sectionTitle"), reminders)
            }
            if (ev.visibility == "private" || ev.visibility == "assignees") {
                DetailRow(Icons.Outlined.Lock, t("common.visibility.label"), t(if (ev.visibility == "private") "common.visibility.private" else "common.visibility.assignees"))
            }
            if (ev.countdown) DetailRow(Icons.Outlined.HourglassEmpty, t("dashboard.countdownTitle"), t("calendar.countdownDetail"))
            ev.description?.takeIf { it.isNotBlank() }?.let { d ->
                DetailRow(Icons.AutoMirrored.Outlined.Notes, t("calendar.descriptionLabel"), if (d.length > 500) d.take(500) + "…" else d)
            }
            Attachment(store, ev, openDocument = { shell.navigate(DocumentsRoutes.document(it)) })
            // Someone else's event without the right to change it (R23): why Edit is missing.
            if (!rights.mayChange(ev) && rights.write && !ev.creatorName.isNullOrBlank()) {
                DetailRow(Icons.Outlined.Visibility, "", strings.t("common.foreignReadOnlyHint", "name" to ev.creatorName))
            }
            if (mayChange) {
                OutlinedButton(onClick = { question = deleteQuestion(ev) ?: run { delete(RecurrenceScope.Scope.SERIES); null } }) {
                    Icon(Icons.Outlined.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("common.delete"))
                }
            }
        }
    }

    question?.let { q ->
        DeleteDialog(q, ev, text, onChoice = { choice ->
            question = null
            if (choice != null) delete(choice)
        })
    }
}

/** `requestDeleteEvent()`: which question deleting this event asks first, or none. */
sealed interface DeleteQuestion {
    /** `confirmExternalSeriesDelete`: a birthday, a name day, a subscription or a synced series. */
    data class External(val prefix: String) : DeleteQuestion
    data object WholeSeriesOnly : DeleteQuestion
    data object Scope : DeleteQuestion
}

fun deleteQuestion(ev: CalendarEvent): DeleteQuestion? = when {
    RecurrenceScope.isExternalSeries(ev) -> DeleteQuestion.External(
        when {
            ev.birthdayName != null && ev.birthdayEventKind == "name_day" -> "calendar.deleteNameDayEvent"
            ev.birthdayName != null -> "calendar.deleteBirthdayEvent"
            ev.subscriptionId != null -> "calendar.deleteSubscribedSeries"
            else -> "calendar.deleteExternalSeries"
        },
    )
    !RecurrenceScope.isLocalSeries(ev) -> null
    !RecurrenceScope.canEditOccurrence(ev) -> DeleteQuestion.WholeSeriesOnly
    else -> DeleteQuestion.Scope
}

@Composable
private fun DeleteDialog(q: DeleteQuestion, ev: CalendarEvent, text: CalendarText, onChoice: (RecurrenceScope.Scope?) -> Unit) {
    val strings = LocalStrings.current
    when (q) {
        is DeleteQuestion.External -> ConfirmDialog(
            title = strings.t("${q.prefix}Title"),
            detail = strings.t("${q.prefix}Detail", "title" to ev.title),
            confirm = strings.t("${q.prefix}Confirm"),
            onResult = { ok -> onChoice(if (ok) RecurrenceScope.Scope.SERIES else null) },
        )
        DeleteQuestion.WholeSeriesOnly -> ConfirmDialog(
            title = t("calendar.deleteWholeSeriesOnlyTitle"),
            detail = strings.t("calendar.deleteWholeSeriesOnlyDetail", "title" to ev.title),
            confirm = t("calendar.deleteWholeSeriesOnlyConfirm"),
            onResult = { ok -> onChoice(if (ok) RecurrenceScope.Scope.SERIES else null) },
        )
        DeleteQuestion.Scope -> ScopeDialog(t("calendar.deleteRecurringTitle"), ev, text, allowThis = true, onChoice = onChoice)
    }
}

@Composable
fun ConfirmDialog(title: String, detail: String?, confirm: String, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text(confirm, color = Yuvomi.colors.danger) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text(t("common.cancel")) } },
    )
}

/**
 * `recurringScopeChoice()`: three answers without a preselection, the occurrence named above
 * them (#1284); "only this" drops out when the save moved the event to another calendar.
 */
@Composable
fun ScopeDialog(title: String, ev: CalendarEvent, text: CalendarText, allowThis: Boolean, onChoice: (RecurrenceScope.Scope?) -> Unit) {
    val strings = LocalStrings.current
    val shell = LocalShell.current
    val day = CalendarDates(shell.time.zone).startDate(ev)
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.t("calendar.recurringScopeOccurrence", "title" to ev.title, "date" to text.date(day)))
                Text(t("calendar.recurringScopeLabel"), style = MaterialTheme.typography.labelLarge)
                RecurrenceScope.Scope.entries.filter { allowThis || it != RecurrenceScope.Scope.THIS }.forEach { scope ->
                    OutlinedButton(onClick = { onChoice(scope) }, modifier = Modifier.fillMaxWidth()) { Text(t(scope.labelKey)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoice(null) }) { Text(t("common.cancel")) } },
    )
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp).padding(top = 2.dp), tint = Yuvomi.colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Column {
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
            HashtagText(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** `attachmentNode()`: an image as preview, a file as a link into Documents, a private one as a quiet line. */
@Composable
private fun Attachment(store: CalendarStore, ev: CalendarEvent, openDocument: (Int) -> Unit) {
    if (ev.attachmentLocked == true) {
        DetailRow(Icons.Outlined.AttachFile, t("calendar.attachmentLabel"), t("documentAttach.lockedPrivate"))
        return
    }
    val documentId = ev.attachmentDocumentId ?: return
    val name = ev.attachmentName ?: t("calendar.attachmentFallback")
    val image = ev.attachmentMime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow(Icons.Outlined.AttachFile, t("calendar.attachmentLabel"), name)
        if (image) {
            val bitmap by produceState<ImageBitmap?>(null, documentId) {
                value = runCatching {
                    val bytes = store.api.preview(documentId)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            bitmap?.let {
                Image(it, name, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            }
        }
        TextButton(onClick = { openDocument(documentId) }) { Text(name, fontWeight = FontWeight.Medium) }
    }
}
