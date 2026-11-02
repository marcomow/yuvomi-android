package io.github.marcomow.yuvomi.feature.subscriptions

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.design.Tokens
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.LocalStrings
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.core.time.HouseholdTime
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import io.github.marcomow.yuvomi.feature.shell.ShellContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `isNavModuleReadOnly('budget')`: the budget page's tabs ask the budget module (admins write
 * everywhere). Read-only members see everything without the writing affordances.
 */
fun ShellContext.budgetReadOnly(): Boolean = !session.isAdmin && session.access("budget") == "read"

/** `dateText(key)`: "Nov 4", with the year outside the current one; the key itself when unreadable. */
fun ShellContext.dateText(key: String?, weekday: Boolean = false): String {
    val d = HouseholdTime.date(key) ?: return key.orEmpty()
    return if (weekday) formats.weekdayDate(d) else formats.date(d, time.today())
}

/** `relativeUnit(value, unit)` through ICU in the UI language ("in 3 months", "3 months ago"). */
fun relativeText(localeTag: String, value: Long, unit: String): String = runCatching {
    val f = android.icu.text.RelativeDateTimeFormatter.getInstance(android.icu.util.ULocale.forLanguageTag(localeTag))
    val u = when (unit) {
        "week" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
        "month" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        "year" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
        else -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
    }
    val direction = if (value < 0) android.icu.text.RelativeDateTimeFormatter.Direction.LAST else android.icu.text.RelativeDateTimeFormatter.Direction.NEXT
    f.format(kotlin.math.abs(value).toDouble(), direction, u)
}.getOrDefault("$value $unit")

fun parseHex(hex: String?, fallback: Color): Color = Tokens.parseColor(hex) ?: fallback

/** A `data:image/...;base64,` logo as a bitmap; SVG and unreadable data give null (initials then). */
object LogoImages {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(48) {}

    fun decode(dataUrl: String?): ImageBitmap? {
        if (dataUrl.isNullOrEmpty() || !dataUrl.startsWith("data:image/") || "svg" in dataUrl.substringBefore(',')) return null
        val key = dataUrl.length.toString() + dataUrl.hashCode()
        cache.get(key)?.let { return it }
        val bytes = runCatching { Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT) }.getOrNull() ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }
}

/** `.subscription-card__brand`: the logo, else the first two letters on the brand colour. */
@Composable
fun BrandTile(name: String, logo: String?, color: Color, size: Dp = 40.dp) {
    val image = remember(logo) { LogoImages.decode(logo) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) Image(image, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        else Text(name.take(2).uppercase().ifEmpty { "+" }, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/** A labelled choice as a read-only field that opens a menu. */
@Composable
fun <T> ChoiceField(label: String, value: T, options: List<Pair<T, String>>, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { open = true }.semantics { contentDescription = label })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onChange(v) })
            }
        }
    }
}

/** A date as a field that opens the Material date picker; `YYYY-MM-DD` in and out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, modifier: Modifier = Modifier, error: String? = null, onChange: (String) -> Unit) {
    val shell = LocalShell.current
    var picking by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = HouseholdTime.date(value)?.let { shell.formats.date(it, shell.time.today()) } ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { picking = true }.semantics { contentDescription = label })
    }
    if (picking) {
        val initial = HouseholdTime.date(value) ?: shell.time.today()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    picking = false
                }) { Text(t("common.save")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

/** The colour row (`colorPickerHtml` with `subscriptionColorOptions()`): the default, the family tones, and a stored custom one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRow(value: String, onChange: (String) -> Unit) {
    val strings = LocalStrings.current
    val options = SubscriptionRules.COLOR_OPTIONS.let { list ->
        if (list.none { it.first.equals(value, true) } && value.isNotEmpty()) list + (value to "colorPicker.custom") else list
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (hex, key) ->
            val selected = hex.equals(value, true)
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(parseHex(hex, Yuvomi.colors.textSecondary), CircleShape)
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onChange(hex) }
                    .semantics { contentDescription = strings.t(key) },
            )
        }
    }
}

/** A label above a value, like the detail view's rows (`openDetailView` sections); nothing when empty. */
@Composable
fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A section title on the stage (`.u-section-title`). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

fun today(shell: ShellContext): LocalDate = shell.time.today()
