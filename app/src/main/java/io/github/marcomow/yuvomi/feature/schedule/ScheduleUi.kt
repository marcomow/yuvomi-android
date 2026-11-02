package io.github.marcomow.yuvomi.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CoPresent
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PedalBike
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Upstream stores Lucide icon names (`icon` of a shift type). The app shows the common ones in
 * Material's set; any other name is kept and shown as the generic clock.
 */
object ScheduleIcons {
    val catalog: List<Pair<String, ImageVector>> = listOf(
        "sunrise" to Icons.Outlined.WbTwilight,
        "sunset" to Icons.Outlined.WbTwilight,
        "moon" to Icons.Outlined.Bedtime,
        "sun" to Icons.Outlined.WbSunny,
        "clock" to Icons.Outlined.Schedule,
        "tree-palm" to Icons.Outlined.BeachAccess,
        "thermometer" to Icons.Outlined.Thermostat,
        "book-open" to Icons.AutoMirrored.Outlined.MenuBook,
        "file-text" to Icons.Outlined.Description,
        "presentation" to Icons.Outlined.CoPresent,
        "users" to Icons.Outlined.Groups,
        "flask-conical" to Icons.Outlined.Science,
        "phone" to Icons.Outlined.Phone,
        "briefcase" to Icons.Outlined.Work,
        "house" to Icons.Outlined.Home,
        "car" to Icons.Outlined.DirectionsCar,
        "bus" to Icons.Outlined.DirectionsBus,
        "bike" to Icons.Outlined.PedalBike,
        "plane" to Icons.Outlined.Flight,
        "truck" to Icons.Outlined.LocalShipping,
        "hospital" to Icons.Outlined.LocalHospital,
        "stethoscope" to Icons.Outlined.MedicalServices,
        "graduation-cap" to Icons.Outlined.School,
        "baby" to Icons.Outlined.ChildCare,
        "dumbbell" to Icons.Outlined.FitnessCenter,
        "music" to Icons.Outlined.MusicNote,
        "laptop" to Icons.Outlined.Laptop,
        "building-2" to Icons.Outlined.Business,
        "shield" to Icons.Outlined.Shield,
        "wrench" to Icons.Outlined.Build,
        "shopping-cart" to Icons.Outlined.ShoppingCart,
        "coffee" to Icons.Outlined.LocalCafe,
        "utensils" to Icons.Outlined.Restaurant,
        "calendar" to Icons.Outlined.CalendarToday,
        "bell" to Icons.Outlined.Notifications,
        "map-pin" to Icons.Outlined.Place,
        "zap" to Icons.Outlined.Bolt,
        "cloud" to Icons.Outlined.Cloud,
        "snowflake" to Icons.Outlined.AcUnit,
        "flame" to Icons.Outlined.LocalFireDepartment,
        "bed" to Icons.Outlined.Bed,
        "headphones" to Icons.Outlined.Headphones,
        "camera" to Icons.Outlined.PhotoCamera,
        "gamepad-2" to Icons.Outlined.SportsEsports,
        "palette" to Icons.Outlined.Palette,
        "trophy" to Icons.Outlined.EmojiEvents,
        "heart" to Icons.Outlined.FavoriteBorder,
        "star" to Icons.Outlined.StarOutline,
        "dog" to Icons.Outlined.Pets,
    )
    private val byName = catalog.toMap()

    fun of(name: String?): ImageVector? = name?.takeIf { it.isNotEmpty() }?.let { byName[it] ?: Icons.Outlined.Schedule }
}

/** The colour names of utils/color-picker.js, in the order of [ScheduleRules.USER_COLORS]. */
internal val COLOR_NAMES = listOf("gray", "blue", "green", "ocher", "emerald", "fuchsia", "red", "cyan", "orange", "pink")

internal fun shiftColor(value: String?): Color = Tokens.parseColor(value) ?: Color(0xFF0891B2)

/** `.schedule-swatch`: the shift type's colour dot; an outline for a free day. */
@Composable
internal fun Swatch(color: String?, size: Dp = 12.dp) {
    if (color == null) {
        Box(Modifier.size(size).clip(CircleShape).border(1.5.dp, Yuvomi.colors.border, CircleShape))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(shiftColor(color)))
    }
}

/** A shift type's dot, icon and label in one row's leading slot. */
@Composable
internal fun TypeLeading(type: ShiftType?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(type?.color)
        ScheduleIcons.of(type?.icon)?.let { Icon(it, null, Modifier.padding(start = 6.dp).size(20.dp), tint = Yuvomi.colors.textSecondary) }
    }
}

/** Dates and times in the household's words. */
internal class ScheduleText(private val today: LocalDate, private val date: (LocalDate) -> String, val clock: (LocalTime) -> String) {
    fun day(d: LocalDate): String = date(d)

    fun day(key: String): String = runCatching { date(LocalDate.parse(key)) }.getOrDefault(key)

    /** `dateRangeText`. */
    fun range(from: String, to: String, strings: io.github.marcomow.yuvomi.core.i18n.Strings): String =
        if (from == to) day(from) else strings.t("calendar.dayRangeLabel", "from" to day(from), "to" to day(to))

    val todayDate: LocalDate get() = today
}

@Composable
internal fun rememberScheduleText(today: LocalDate): ScheduleText {
    val shell = LocalShell.current
    return remember(shell, today) { ScheduleText(today, { shell.formats.date(it, today) }, { shell.formats.time(it) }) }
}

/** "Mon", "Tue" … from upstream's calendar strings. */
@Composable
internal fun dayShort(date: LocalDate): String {
    val name = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    return t("calendar.dayShort$name")
}

/** `editDeleteMenu`: the row's one "more" button with Edit and Delete. */
@Composable
internal fun RowMenu(name: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, t("common.moreActionsNamed", "name" to name)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("common.edit")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { open = false; onEdit() })
            DropdownMenuItem(
                text = { Text(t("schedule.delete"), color = Yuvomi.colors.danger) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Yuvomi.colors.danger) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** A labelled choice (`<select class="form-input">`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SelectField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

/** A date with a picker; [clearable] adds the clear button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, value: LocalDate?, onChange: (LocalDate?) -> Unit, enabled: Boolean = true, clearable: Boolean = false, error: String? = null) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text(value?.let { shell.formats.date(it, shell.time.today()) } ?: "—")
            }
            if (clearable && value != null && enabled) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
        }
        if (error != null) Text(error, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
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

/** A time of day with a picker and a clear button (`yuvomi-datepicker type="time"`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeField(label: String, value: String?, onChange: (String?) -> Unit, error: String? = null) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    val time = ScheduleRules.time(value)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { open = true }) { Text(time?.let { shell.formats.time(it) } ?: "—") }
            if (time != null) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("common.searchClear")) }
        }
        if (error != null) Text(error, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        val initial = time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute)
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

/** A yes/no question before a destructive or far-reaching write (`confirmModal`). */
@Composable
internal fun ConfirmDialog(title: String, detail: String?, confirm: String, danger: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (danger) Yuvomi.colors.danger else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** The sentence for an i18n key held in form errors. */
@Composable
internal fun errorText(key: String?, args: Map<String, Any?> = emptyMap()): String? = key?.let { LocalStrings.current.t(it, args) }
