package io.github.marcomow.yuvomi.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import io.github.marcomow.yuvomi.core.design.Yuvomi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Native charts drawn on a Canvas (ADR 0012: no chart library), after public/utils/chart.js. */
object HealthChartMath {
    /** `niceDomain`: round bounds a little outside the values. */
    fun nice(lo: Double, hi: Double, minSpan: Double = 0.0): Pair<Double, Double> {
        var a = lo
        var b = hi
        if (b - a < minSpan) {
            val mid = (a + b) / 2
            a = mid - minSpan / 2
            b = mid + minSpan / 2
            if (a < 0 && lo >= 0) { b -= a; a = 0.0 }
        }
        if (a == b) { a -= 1; b += 1 }
        val span = b - a
        val step = 10.0.pow(floor(log10(span / 4)))
        return floor(a / step) * step to ceil(b / step) * step
    }
}

/** One line of a chart: values per x slot (null = gap), its colour and legend label. */
data class ChartLine(val values: List<Double?>, val color: Color, val label: String)

/**
 * A line chart over evenly spaced slots with the min/max of the axis on the left and the first
 * and last slot label below. [band] draws a shaded reference range (lab trends).
 */
@Composable
fun LineChart(
    lines: List<ChartLine>,
    xLabels: Pair<String, String>,
    format: (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    minSpan: Double = 0.0,
    band: Pair<Double?, Double?>? = null,
    height: Int = 180,
) {
    val values = lines.flatMap { it.values }.filterNotNull() + listOfNotNull(band?.first, band?.second)
    if (values.isEmpty()) return
    val (lo, hi) = HealthChartMath.nice(values.min(), values.max(), minSpan)
    val grid = Yuvomi.colors.border
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = description }) {
        Row {
            Column(Modifier.height(height.dp).padding(end = 6.dp)) {
                Text(format(hi), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(format(lo), style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
            }
            Canvas(Modifier.weight(1f).height(height.dp)) {
                val w = size.width
                val h = size.height
                fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
                for (i in 0..3) {
                    val gy = h * i / 3f
                    drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                band?.let { (bl, bh) ->
                    val top = y(bh ?: hi)
                    val bottom = y(bl ?: lo)
                    drawRect(bandColor, Offset(0f, top), Size(w, bottom - top))
                }
                for (line in lines) {
                    val n = line.values.size
                    fun x(i: Int) = if (n <= 1) w / 2 else w * i / (n - 1).toFloat()
                    val path = Path()
                    var open = false
                    line.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        if (!open) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                        open = true
                    }
                    drawPath(path, line.color, style = Stroke(width = 2.5.dp.toPx()))
                    line.values.forEachIndexed { i, v -> if (v != null) drawCircle(line.color, 3.5.dp.toPx(), Offset(x(i), y(v))) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Spacer(Modifier.width(36.dp))
            Text(xLabels.first, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(xLabels.second, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary)
        }
        if (lines.size > 1) {
            Row(Modifier.padding(top = 6.dp)) {
                lines.forEach { line ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(line.color))
                    Text(line.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
                }
            }
        }
    }
}

/** Bars per slot with their labels below (`activityChartMarkup`). */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, color: Color, description: String, modifier: Modifier = Modifier, height: Int = 140) {
    val top = max(values.maxOrNull() ?: 0.0, 1.0)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val slot = size.width / values.size
            val bw = slot * 0.56f
            values.forEachIndexed { i, v ->
                val bh = (v / top * size.height).toFloat()
                if (bh > 0) drawRoundRect(color, Offset(slot * i + (slot - bw) / 2, size.height - bh), Size(bw, bh), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Yuvomi.colors.textSecondary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

/** A thin progress bar (`metric-card__progress`). */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(color.copy(alpha = 0.18f), cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}
