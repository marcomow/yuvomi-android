package io.github.marcomow.yuvomi.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.marcomow.yuvomi.core.design.EmptyState
import io.github.marcomow.yuvomi.core.design.ErrorBox
import io.github.marcomow.yuvomi.core.design.LoadingBox
import io.github.marcomow.yuvomi.core.design.MemberAvatar
import io.github.marcomow.yuvomi.core.design.OfflineBanner
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.AccountButton
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.settings.SettingsRoutes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The frame of every health area: title, back to the overview, the person switcher, the
 * read-only or care banner, pull to refresh, offline banner, loading and error states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <D : Any> HealthScaffold(
    vm: HealthAreaViewModel<D>,
    title: String,
    back: (() -> Unit)?,
    fab: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    personLabel: String = t("health.vitals.personsLabel"),
    banner: Boolean = true,
    content: LazyListScope.(D) -> Unit,
) {
    val shell = LocalShell.current
    val s by vm.state.collectAsState()
    val hub by vm.hub.state.collectAsState()
    LaunchedEffect(hub.personId) { vm.adoptPerson() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("common.back")) }
                },
                actions = {
                    actions()
                    AccountButton()
                },
            )
        },
        floatingActionButton = { if (!s.offline && s.data != null) fab() },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val data = s.data
            when {
                s.loading && data == null -> LoadingBox()
                data == null -> ErrorBox(t(s.errorKey ?: "common.errorGeneric"), onRetry = { vm.refresh() })
                else -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.refresh(pulled = true) }) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        if (s.offline) {
                            item(key = "offline") { OfflineBanner(shell.formats.dateTime(Instant.ofEpochMilli(s.cachedAt!!).atZone(shell.time.zone))) }
                        }
                        item(key = "person") {
                            PersonSwitcher(vm.hub, hub, personLabel) { vm.choosePerson(it) }
                            if (banner) OwnerBanner(vm.hub, hub)
                        }
                        content(data)
                    }
                }
            }
        }
    }
}

/** `personSwitcherMarkup`: one pill for the module, the member first, "Who can see this?" in the own view. */
@Composable
internal fun PersonSwitcher(hub: HealthHub, state: HealthHub.State, label: String, choose: (Int) -> Unit) {
    val shell = LocalShell.current
    if (state.members.size <= 1) return
    var open by remember { mutableStateOf(false) }
    val you = t("health.vitals.you")
    fun nameOf(m: HealthMember) = if (m.id == hub.meId) "${m.displayName} · $you" else m.displayName
    val active = state.members.firstOrNull { it.id == (state.personId ?: hub.meId) } ?: state.members.first()
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .clickable { open = true }
                .semantics { contentDescription = "$label: ${nameOf(active)}" },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(active.displayName, active.avatarColor, null, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(nameOf(active), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val ordered = state.members.filter { it.id == hub.meId } + state.members.filter { it.id != hub.meId }
            ordered.forEach { m ->
                DropdownMenuItem(
                    text = { Text(nameOf(m), fontWeight = if (m.id == active.id) FontWeight.SemiBold else null) },
                    leadingIcon = { MemberAvatar(m.displayName, m.avatarColor, null, size = 24.dp) },
                    onClick = { open = false; choose(m.id) },
                )
            }
            if (active.id == hub.meId) {
                // `whoSeesItem`: the visibility defaults live in Settings.
                DropdownMenuItem(
                    text = { Text(t("health.whoSees")) },
                    leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                    onClick = { open = false; shell.navigate(SettingsRoutes.START) },
                )
            }
        }
    }
}

/** `readOnlyBannerMarkup`: whose data this is, read-only or recorded for someone in care. */
@Composable
internal fun OwnerBanner(hub: HealthHub, state: HealthHub.State, cycle: Boolean = false) {
    val person = state.personId ?: hub.meId
    if (person == hub.meId) return
    val name = hub.name(person)
    val care = if (cycle) false else hub.canEdit(person)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp), tint = Yuvomi.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text(
                if (care) t("health.careBanner", "name" to name) else t("health.readOnlyBanner", "name" to name),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
internal fun Disclaimer() {
    Text(
        t("health.disclaimer"),
        style = MaterialTheme.typography.bodySmall,
        color = Yuvomi.colors.textTertiary,
        modifier = Modifier.padding(16.dp),
    )
}

/** The area's empty state with its add action (`areaEmptyHtml`). */
@Composable
internal fun AreaEmpty(titleKey: String, descKey: String, actionKey: String?, onAction: (() -> Unit)?) =
    EmptyState(t(titleKey), t(descKey), actionKey?.let { t(it) }, onAction)

/** A full-screen editor: close, title, save; the body scrolls (the web app's modal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?,
    saveLabel: String = t("common.save"),
    saving: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, t("common.cancel")) } },
                        actions = { if (onSave != null) TextButton(onClick = onSave, enabled = !saving) { Text(saveLabel) } },
                    )
                },
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, detail: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) }) }
        }
    }
}

/** The visibility select of every form: `private`, or the area's open value. */
@Composable
internal fun VisibilityPicker(value: String, onPick: (String) -> Unit, open: String = "family", openLabel: String = t("health.vitals.visibility.family"), privateLabel: String = t("health.vitals.visibility.private")) {
    Picker(t("common.visibility.label"), if (value == open) openLabel else privateLabel, listOf("private" to privateLabel, open to openLabel), onPick = onPick)
}

@Composable
internal fun NumberField(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = true, supporting: String? = null, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun TextInput(label: String, value: String, modifier: Modifier = Modifier, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A date button with the Material picker; [clearable] adds "no date". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, value: LocalDate?, modifier: Modifier = Modifier, clearable: Boolean = false, onChange: (LocalDate?) -> Unit) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }) { Text(value?.let { shell.formats.date(it, shell.time.today()) } ?: "—") }
            if (clearable && value != null) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("datepicker.clear")) }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (value ?: shell.time.today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

/** A `HH:MM` button with the Material time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    val time = runCatching { LocalTime.parse(value.take(5)) }.getOrNull()
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        OutlinedButton(onClick = { open = true }) { Text(time?.let { shell.formats.time(it) } ?: "—") }
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 8, initialMinute = time?.minute ?: 0)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onChange("%02d:%02d".format(state.hour, state.minute))
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
            text = { TimePicker(state) },
        )
    }
}

/** Single choice among chips; tapping the chosen one again clears it when [allowNone]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, allowNone: Boolean = true, onPick: (T?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(if (value == selected && allowNone) null else value) },
                label = { Text(label) },
            )
        }
    }
}

/** A labelled value row of a details list. */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
    }
}

/** A delete button at the end of an editor (`btn--danger-outline`). */
@Composable
internal fun DeleteButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(t("common.delete"), color = Yuvomi.colors.danger)
    }
}
