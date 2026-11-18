package com.moblized.stormdeck.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moblized.stormdeck.data.Hour
import com.moblized.stormdeck.data.OniPoint
import com.moblized.stormdeck.data.WeeklyNino
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Temperature line over precipitation-chance bars for the next day or two. */
@Composable
fun HourlyChart(hours: List<Hour>, offsetSec: Int, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    if (hours.size < 2) return
    val line = MaterialTheme.colorScheme.tertiary
    val rain = LocalStorm.current.cool
    val dim = LocalStorm.current.dim
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val top = 22.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val w = size.width
        val temps = hours.map { it.temp }
        val lo = temps.min()
        val hi = temps.max()
        val span = max(1.0, hi - lo)
        val step = w / (hours.size - 1)
        fun x(i: Int) = i * step
        fun y(t: Double) = (bottom - (t - lo) / span * (bottom - top) * 0.8f - (bottom - top) * 0.1f).toFloat()

        // Rain chance bars from the bottom.
        val barW = max(2f, step * 0.6f)
        hours.forEachIndexed { i, h ->
            if (!h.precipChance.isNaN() && h.precipChance > 0) {
                val bh = (h.precipChance / 100.0 * (bottom - top) * 0.55).toFloat()
                drawRoundRect(
                    rain.copy(alpha = 0.35f + 0.4f * (h.precipChance / 100f).toFloat()),
                    topLeft = Offset(x(i) - barW / 2, bottom - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        drawLine(grid, Offset(0f, bottom), Offset(w, bottom), strokeWidth = 1f)

        val path = Path()
        hours.forEachIndexed { i, h -> if (i == 0) path.moveTo(x(i), y(h.temp)) else path.lineTo(x(i), y(h.temp)) }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.dp.toPx()
            color = dim.toArgb()
            textAlign = Paint.Align.CENTER
        }
        val bold = Paint(paint).apply {
            color = line.toArgb()
            typeface = Typeface.DEFAULT_BOLD
        }
        drawIntoCanvas { c ->
            val every = if (hours.size > 30) 6 else 3
            hours.forEachIndexed { i, h ->
                if (i % every == 0) {
                    c.nativeCanvas.drawText("${h.temp.roundToInt()}°", x(i).coerceIn(12f, w - 12f), y(h.temp) - 7.dp.toPx(), bold)
                    c.nativeCanvas.drawText(
                        com.moblized.stormdeck.data.Fmt.hour(h.time, offsetSec).replace(" ", "").lowercase(),
                        x(i).coerceIn(14f, w - 14f),
                        size.height - 3.dp.toPx(),
                        paint,
                    )
                }
            }
        }
    }
}

/** Oceanic Niño Index bars: red for El Niño seasons, blue for La Niña, grey in between. */
@Composable
fun OniChart(points: List<OniPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val warm = LocalStorm.current.warning
    val cool = LocalStorm.current.cool
    val dim = LocalStorm.current.dim
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        val left = 26.dp.toPx()
        val bottomPad = 16.dp.toPx()
        val h = size.height - bottomPad
        val maxAbs = max(2.0, ceil(points.maxOf { abs(it.anomaly) } * 2) / 2)
        val mid = h / 2
        fun y(v: Double) = (mid - v / maxAbs * (h / 2 - 4)).toFloat()
        val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.dp.toPx()
            color = dim.toArgb()
        }
        // Threshold lines at ±0.5 °C, the El Niño / La Niña cut-offs.
        listOf(0.5, -0.5, 1.5, -1.5).forEach { v ->
            drawLine(
                grid, Offset(left, y(v)), Offset(size.width, y(v)), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        drawLine(grid, Offset(left, mid), Offset(size.width, mid), strokeWidth = 1.5f)
        val bw = (size.width - left) / points.size
        points.forEachIndexed { i, p ->
            val color = when {
                p.anomaly >= 0.5 -> warm
                p.anomaly <= -0.5 -> cool
                else -> dim.copy(alpha = 0.6f)
            }
            val y0 = y(0.0)
            val y1 = y(p.anomaly)
            drawRect(color, topLeft = Offset(left + i * bw + bw * 0.15f, min(y0, y1)), size = Size(bw * 0.7f, abs(y1 - y0).coerceAtLeast(1f)))
        }
        drawIntoCanvas { c ->
            listOf(maxAbs, 0.5, 0.0, -0.5, -maxAbs).forEach { v ->
                c.nativeCanvas.drawText(if (v > 0) "+${"%.1f".format(v)}" else "%.1f".format(v), 0f, y(v) + 4.dp.toPx(), labels)
            }
            // Year labels at each JFM-ish start.
            points.forEachIndexed { i, p ->
                if (p.season == "DJF") c.nativeCanvas.drawText("${p.year}", left + i * bw, size.height - 2.dp.toPx(), labels)
            }
        }
    }
}

/** Weekly Niño 3.4 (and the other regions, faint) over the last year. */
@Composable
fun WeeklyChart(weeks: List<WeeklyNino>, modifier: Modifier = Modifier) {
    if (weeks.size < 2) return
    val main = MaterialTheme.colorScheme.tertiary
    val dim = LocalStorm.current.dim
    val warm = LocalStorm.current.warning
    val cool = LocalStorm.current.cool
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val left = 26.dp.toPx()
        val h = size.height - 14.dp.toPx()
        val all = weeks.flatMap { listOf(it.nino34, it.nino12, it.nino4) }
        val lo = floor(min(-1.0, all.min()) * 2) / 2
        val hi = ceil(max(1.0, all.max()) * 2) / 2
        fun y(v: Double) = (h - (v - lo) / (hi - lo) * (h - 6) - 3).toFloat()
        val step = (size.width - left) / (weeks.size - 1)
        fun x(i: Int) = left + i * step
        drawRect(warm.copy(alpha = 0.07f), topLeft = Offset(left, y(hi)), size = Size(size.width - left, y(0.5) - y(hi)))
        drawRect(cool.copy(alpha = 0.07f), topLeft = Offset(left, y(-0.5)), size = Size(size.width - left, y(lo) - y(-0.5)))
        drawLine(grid, Offset(left, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.5f)
        fun series(values: List<Double>, color: Color, width: Float) {
            val p = Path()
            values.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
            drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round))
        }
        series(weeks.map { it.nino12 }, dim.copy(alpha = 0.45f), 1.5.dp.toPx())
        series(weeks.map { it.nino4 }, cool.copy(alpha = 0.55f), 1.5.dp.toPx())
        series(weeks.map { it.nino34 }, main, 2.5.dp.toPx())
        val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.dp.toPx()
            color = dim.toArgb()
        }
        drawIntoCanvas { c ->
            var v = lo
            while (v <= hi + 1e-6) {
                c.nativeCanvas.drawText(if (v > 0) "+${"%.1f".format(v)}" else "%.1f".format(v), 0f, y(v) + 4.dp.toPx(), labels)
                v += if (hi - lo > 4) 1.0 else 0.5
            }
            weeks.forEachIndexed { i, w ->
                if (w.date.dayOfMonth <= 7 && w.date.monthValue % 3 == 1) {
                    c.nativeCanvas.drawText(w.date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + (w.date.year % 100), x(i), size.height - 1.dp.toPx(), labels)
                }
            }
        }
    }
}
