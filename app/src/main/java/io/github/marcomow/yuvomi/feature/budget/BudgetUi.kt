package io.github.marcomow.yuvomi.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** The seven chart series of `tokens.css` (`--chart-series-1..7`), by zero-based index; -1 is "other". */
@Composable
fun seriesColor(index: Int?): Color = when {
    index == null || index < 0 -> Yuvomi.colors.textTertiary
    else -> Yuvomi.colors.tokens.color("chart-series-${index % BudgetLedger.SERIES_COLORS + 1}", Yuvomi.colors.isDark)
}

/** An account's accent: a `var(--chart-series-N)` token, a hex colour, or the module accent. */
@Composable
fun accountColor(color: String?): Color {
    val token = color?.let { Regex("var\\(--([a-z0-9-]+)\\)").find(it)?.groupValues?.get(1) }
    return when {
        token != null -> Yuvomi.colors.tokens.color(token, Yuvomi.colors.isDark)
        color != null -> io.github.marcomow.yuvomi.core.design.Tokens.parseColor(color) ?: Yuvomi.colors.module("budget")
        else -> Yuvomi.colors.module("budget")
    }
}

/** The colour of an amount by its tone (`.budget-entry__amount--*`, `.metric-card--balance-*`). */
@Composable
fun toneColor(tone: Money.Tone): Color = when (tone) {
    Money.Tone.POSITIVE -> Yuvomi.colors.success
    Money.Tone.NEGATIVE -> Yuvomi.colors.danger
    Money.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) })
            }
        }
    }
}

/** An amount field: the currency stands as its unit (R22, D9), the keyboard is decimal. */
@Composable
fun AmountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    money: Money,
    currency: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    tag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(money.placeholder(currency)) },
        suffix = { Text(money.symbol(currency), color = Yuvomi.colors.textSecondary) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    placeholder: String? = null,
    number: Boolean = false,
    singleLine: Boolean = true,
    tag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Yuvomi.colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A single choice as Material segments (`.segmented` of the page). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segments(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = key == selected,
                onClick = { onSelect(key) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** A date field that opens Material's date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, error: String? = null) {
    val shell = LocalShell.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
            Text(shell.formats.weekdayDate(value) + if (value.year != shell.time.today().year) " ${value.year}" else "")
        }
    }
    if (error != null) Text(error, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
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

/** A month field (`<input type="month">`): a year stepper over twelve months. */
@Composable
fun MonthField(label: String, value: String, onChange: (String) -> Unit, monthLabel: (String) -> String, error: String? = null) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
            Text(if (value.isNotEmpty()) monthLabel(value) else "—")
        }
    }
    if (error != null) Text(error, color = Yuvomi.colors.danger, style = MaterialTheme.typography.bodySmall)
    if (open) {
        val initial = runCatching { YearMonth.parse(value) }.getOrElse { YearMonth.now() }
        var year by remember { mutableIntStateOf(initial.year) }
        val locale = Locale.forLanguageTag(io.github.marcomow.yuvomi.core.i18n.LocalStrings.current.locale)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, t("budget.prevMonth")) }
                    Text(year.toString(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, t("budget.nextMonth")) }
                }
            },
            text = {
                LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items((1..12).toList()) { m ->
                        val ym = YearMonth.of(year, m)
                        FilterChip(
                            selected = ym == initial && value.isNotEmpty(),
                            onClick = { open = false; onChange(ym.toString()) },
                            label = { Text(ym.month.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
            },
        )
    }
}

/** A question with one text field (`promptModal`, `requestNameInPanel`). */
@Composable
fun NameDialog(title: String, label: String, initial: String = "", placeholder: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TextInput(label, text, { text = it }, placeholder = placeholder) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(t("common.save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** `confirmOverModal` with a danger button and a detail line. */
@Composable
fun ConfirmDialog(title: String, detail: String?, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = detail?.let { { Text(it) } },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Yuvomi.colors.danger)) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/** `recurringChoiceModal`: this occurrence, the series, or nothing. */
@Composable
fun SeriesChoiceDialog(title: String, thisLabel: String, seriesLabel: String, note: String?, seriesDanger: Boolean, onChoice: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!note.isNullOrEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium, color = Yuvomi.colors.textSecondary)
                OutlinedButton(onClick = { onChoice("this") }, modifier = Modifier.fillMaxWidth()) { Text(thisLabel) }
                Button(
                    onClick = { onChoice("series") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (seriesDanger) ButtonDefaults.buttonColors(containerColor = Yuvomi.colors.danger) else ButtonDefaults.buttonColors(),
                ) { Text(seriesLabel) }
            }
        },
        confirmButton = { TextButton(onClick = { onChoice(null) }) { Text(t("common.cancel")) } },
    )
}
