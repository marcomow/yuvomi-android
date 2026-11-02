package io.github.marcomow.yuvomi.feature.inventory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import io.github.marcomow.yuvomi.feature.shell.LocalShell
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.min

/** `dateText`: a date key as the UI shows dates. */
@Composable
fun dateText(key: String?): String {
    val shell = LocalShell.current
    val d = InventoryRules.parse(key) ?: return key.orEmpty()
    return shell.formats.date(d, LocalDate.of(1, 1, 1))
}

/** The same, for plain functions that format inside a composable. */
@Composable
fun dateFormatter(): (LocalDate) -> String {
    val shell = LocalShell.current
    return { d -> shell.formats.date(d, LocalDate.of(1, 1, 1)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
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
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) })
            }
        }
    }
}

/** A `yuvomi-datepicker type="date"`: the date as text, the picker on tap, a clear button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String?, modifier: Modifier = Modifier, clearable: Boolean = true, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { open = true }) { Text(value?.let { dateText(it) } ?: t("datepicker.openCalendar")) }
        if (clearable && value != null) IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, t("datepicker.clear")) }
    }
    if (open) {
        val shell = LocalShell.current
        val initial = InventoryRules.parse(value) ?: shell.time.today()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                }) { Text(t("common.confirm")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("common.cancel")) } },
        ) { DatePicker(state) }
    }
}

/** A small tinted label: status and deadline badges (`doc-badge`, `inventory-status-badge`). */
@Composable
fun Badge(text: String, tone: androidx.compose.ui.graphics.Color) {
    Surface(color = tone.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(text, color = tone, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1)
    }
}

/** `odometerChartMarkup`: the readings over time, from two points on (a plain line on time positions). */
@Composable
fun OdometerChart(points: List<InventoryRules.Point>, describe: String) {
    if (points.size < 2) return
    val tone = Yuvomi.colors.module("inventory")
    val grid = Yuvomi.colors.border
    Column {
        Text(t("inventory.odometerChartTitle"), style = MaterialTheme.typography.labelMedium, color = Yuvomi.colors.textSecondary)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(vertical = 8.dp)
                .semantics { contentDescription = describe },
        ) {
            val minV = points.minOf { it.value }.toFloat()
            val maxV = points.maxOf { it.value }.toFloat().let { if (it == minV) minV + 1f else it }
            val from = points.first().date.toEpochDay().toFloat()
            val to = points.last().date.toEpochDay().toFloat().let { if (it == from) from + 1f else it }
            fun x(p: InventoryRules.Point) = (p.date.toEpochDay() - from) / (to - from) * size.width
            fun y(p: InventoryRules.Point) = size.height - (p.value - minV) / (maxV - minV) * size.height
            for (i in 0..3) {
                val gy = size.height * i / 3f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
            drawPath(path, tone, style = Stroke(width = 4f))
            points.forEach { drawCircle(tone, radius = 7f, center = Offset(x(it), y(it))) }
        }
    }
}

/** `pickCroppedImage`: a centred square, 256 px, as a JPEG data URL; null when the file cannot be read. */
object ItemPhoto {
    private const val OUTPUT = 256
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    private val ACCEPTED = setOf("image/png", "image/jpeg", "image/webp")

    fun encode(context: Context, uri: Uri): String? {
        val type = context.contentResolver.getType(uri)
        if (type != null && type !in ACCEPTED) return null
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        if (bytes.size > MAX_FILE_BYTES) return null
        val source: Bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val out = ByteArrayOutputStream()
        square.scale(OUTPUT, OUTPUT).compress(Bitmap.CompressFormat.JPEG, 88, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** A `data:image/...;base64,` photo as a bitmap for the read view and the form. */
    fun decode(dataUrl: String?): androidx.compose.ui.graphics.ImageBitmap? {
        val data = dataUrl?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}
