package io.github.marcomow.yuvomi.feature.calendar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.YuvomiClient
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.settings.SettingsRoutes
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import kotlinx.coroutines.launch

/** docs/spec/calendar.md, the calendar manager (CAL-R23 to R26, `openLocalCalendarsModal`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(back: () -> Unit) {
    val shell = LocalShell.current
    val strings = LocalStrings.current
    val store = rememberCalendarStore() ?: return
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val rights = shell.calendarRights()
    var editing by remember { mutableStateOf<LocalCalendar?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LocalCalendar?>(null) }
    var confirmFeedOff by remember { mutableStateOf<LocalCalendar?>(null) }
    val context = LocalContext.current

    LaunchedEffect(store) { store.loadCalendars() }

    fun run(success: String?, block: suspend () -> String?) {
        scope.launch {
            val error = block()
            when {
                error != null -> shell.notify(strings.t(error))
                success != null -> shell.notify(strings.t(success))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) } },
                title = { Text(t("calendar.manageCalendars")) },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.calendarsFailed) {
                ErrorBox("${strings.t("common.errorGeneric")} ${strings.t("common.loadErrorDescription")}", onRetry = { scope.launch { store.loadCalendars() } })
                return@Column
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (rights.write) {
                    item {
                        OutlinedButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("calendar.localCalendarLabel"))
                        }
                    }
                }
                if (s.calendars.isEmpty()) item { EmptyState(title = t("calendar.localCalendarEmpty")) }
                items(s.calendars, key = { it.id }) { cal ->
                    val index = s.calendars.indexOf(cal)
                    Row(
                        Modifier.fillMaxWidth().then(if (rights.write) Modifier.clickable { editing = cal } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(16.dp).background(color(cal.color, Yuvomi.colors.textSecondary), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                            Text(strings.t("calendar.localCalendarEventCount", "count" to cal.eventCount), style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
                        }
                        var menu by remember { mutableStateOf(false) }
                        val items = buildList<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> {
                            if (rights.write && s.calendars.size > 1 && index > 0) add(Triple(strings.t("category.moveUpNamed", "name" to cal.name), Icons.Outlined.ArrowUpward) { run(null) { store.moveCalendar(cal, -1) } })
                            if (rights.write && s.calendars.size > 1 && index < s.calendars.size - 1) add(Triple(strings.t("category.moveDownNamed", "name" to cal.name), Icons.Outlined.ArrowDownward) { run(null) { store.moveCalendar(cal, 1) } })
                            if (rights.admin && cal.feedUrl != null) add(Triple(strings.t("calendar.localCalendarCopyExport"), Icons.Outlined.ContentCopy) {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(cal.name, cal.feedUrl))
                                scope.launch { shell.notify(strings.t("calendar.localCalendarExportCopied")) }
                            })
                            if (rights.admin) {
                                if (cal.feedUrl != null) add(Triple(strings.t("calendar.localCalendarDisableExport"), Icons.Outlined.LinkOff) { confirmFeedOff = cal })
                                else add(Triple(strings.t("calendar.localCalendarEnableExport"), Icons.Outlined.FileUpload) { run(null) { store.setFeed(cal, true) } })
                            }
                            if (rights.write && !cal.isDefault) add(Triple(strings.t("common.delete"), Icons.Outlined.Delete) { confirmDelete = cal })
                        }
                        if (items.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.t("common.moreActionsNamed", "name" to cal.name)) }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    items.forEach { (label, icon, action) ->
                                        DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; action() })
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // Subscriptions and sync live in Settings, like the web page's link (`/settings/modules/calendar`).
                    Row(
                        Modifier.fillMaxWidth().clickable { shell.navigate(SettingsRoutes.START) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("calendar.manageSubscriptionsLink"), Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }

    if (creating) {
        CalendarSheet(null, close = { creating = false }) { name, color ->
            creating = false
            val key = YuvomiClient.newIdempotencyKey()
            run("calendar.localCalendarCreated") { store.createCalendar(name, color, key) }
        }
    }
    editing?.let { cal ->
        CalendarSheet(cal, close = { editing = null }) { name, color ->
            editing = null
            run("calendar.localCalendarSaved") { store.editCalendar(cal, name, color) }
        }
    }
    confirmDelete?.let { cal ->
        val icloud = s.calendars.any { it.isDefault && it.uploadsToIcloud }
        ConfirmDialog(
            strings.t("calendar.localCalendarDeleteConfirm", "name" to cal.name),
            t(if (icloud) "calendar.localCalendarDeleteDetailIcloud" else "calendar.localCalendarDeleteDetail"),
            t("common.delete"),
        ) { ok ->
            confirmDelete = null
            if (ok) run("calendar.localCalendarDeleted") { store.deleteCalendar(cal) }
        }
    }
    confirmFeedOff?.let { cal ->
        ConfirmDialog(t("settings.feedExportDisableConfirm"), t("settings.feedExportDisableConfirmDetail"), t("settings.feedExportDisable")) { ok ->
            confirmFeedOff = null
            if (ok) run(null) { store.setFeed(cal, false) }
        }
    }
}

/** `localCalendarSheet()`: name and colour, from the family tones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarSheet(calendar: LocalCalendar?, close: () -> Unit, save: (String, String) -> Unit) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(calendar?.name.orEmpty()) }
    var color by remember { mutableStateOf(calendar?.color ?: CalendarRules.USER_COLOR_DEFAULT) }
    var missing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(t(if (calendar != null) "calendar.localCalendarEdit" else "calendar.localCalendarNew")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80); missing = false },
                    label = { Text(t("calendar.localCalendarName")) },
                    isError = missing,
                    supportingText = if (missing) { { Text(t("common.required")) } } else null,
                    singleLine = true,
                )
                Text(t("calendar.localCalendarColor"), style = MaterialTheme.typography.labelLarge)
                val palette = CalendarRules.FAMILY_COLORS.map { it.first }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalendarRules.FAMILY_COLORS.forEach { (hex, key) -> Swatch(hex, color.equals(hex, ignoreCase = true), strings.t(key)) { color = hex } }
                    if (palette.none { it.equals(color, ignoreCase = true) }) Swatch(color, true, t("calendar.colorCurrent")) {}
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) missing = true else save(name.trim(), color)
            }) { Text(t(if (calendar != null) "common.save" else "common.add")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(t("common.cancel")) } },
    )
}
