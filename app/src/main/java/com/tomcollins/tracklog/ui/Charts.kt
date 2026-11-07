package com.tomcollins.tracklog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomcollins.tracklog.data.Geo
import com.tomcollins.tracklog.data.PointEntity
import com.tomcollins.tracklog.data.Units
import java.util.Locale

data class Profile(val distanceM: DoubleArray, val altitude: DoubleArray?, val speedMps: DoubleArray)

/** Builds distance-indexed elevation and (smoothed) speed series from track points. */
fun buildProfile(points: List<PointEntity>): Profile {
    val n = points.size
    val dist = DoubleArray(n)
    val speed = DoubleArray(n)
    for (i in 1 until n) {
        val a = points[i - 1]
        val b = points[i]
        val d = if (a.segment == b.segment) Geo.distance(a.lat, a.lon, b.lat, b.lon) else 0.0
        dist[i] = dist[i - 1] + d
    }
    // Speed over a ±3 point window within a segment, preferring GPS-reported speed.
    for (i in 0 until n) {
        val p = points[i]
        val reported = p.speed?.toDouble()
        if (reported != null) { speed[i] = reported; continue }
        var lo = i
        var hi = i
        while (lo > 0 && i - lo < 3 && points[lo - 1].segment == p.segment) lo--
        while (hi < n - 1 && hi - i < 3 && points[hi + 1].segment == p.segment) hi++
        val dt = (points[hi].time - points[lo].time) / 1000.0
        speed[i] = if (dt > 0) (dist[hi] - dist[lo]) / dt else 0.0
    }
    val hasAlt = points.count { it.alt != null } > n / 2
    val alt = if (hasAlt) {
        var last = points.firstOrNull { it.alt != null }?.alt ?: 0.0
        DoubleArray(n) { i -> points[i].alt?.also { last = it } ?: last }
    } else null
    return Profile(dist, alt, speed)
}

@Composable
fun ProfileChart(
    title: String,
    xs: DoubleArray,
    ys: DoubleArray,
    color: Color,
    units: Units,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    yFromZero: Boolean = false,
) {
    if (xs.size < 2) return
    val measurer = rememberTextMeasurer()
    var selected by remember(xs, ys) { mutableStateOf<Int?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val minY = if (yFromZero) 0.0 else ys.min()
    val maxY = ys.max().let { if (it - minY < 1e-6) minY + 1 else it }
    val maxX = xs.last().coerceAtLeast(1.0)

    Column(modifier) {
        val sel = selected
        Text(
            if (sel == null) title
            else "$title · ${formatY(ys[sel])} at ${Format.distance(xs[sel], units)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(xs) {
                    fun pick(x: Float) {
                        val left = 44.dp.toPx()
                        val target = ((x - left) / (size.width - left)).coerceIn(0f, 1f) * maxX
                        var lo = 0
                        var hi = xs.lastIndex
                        while (lo < hi) {
                            val m = (lo + hi) / 2
                            if (xs[m] < target) lo = m + 1 else hi = m
                        }
                        selected = lo
                    }
                    detectDragGestures(
                        onDragStart = { pick(it.x) },
                        onDragEnd = { },
                        onDrag = { change, _ -> pick(change.position.x) },
                    )
                }
                .pointerInput(xs) {
                    detectTapGestures { o ->
                        val left = 44.dp.toPx()
                        val target = ((o.x - left) / (size.width - left)).coerceIn(0f, 1f) * maxX
                        selected = xs.indices.minBy { kotlin.math.abs(xs[it] - target) }
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
            val top = 6.dp.toPx()
            val w = size.width - left
            val h = bottom - top
            fun px(i: Int) = Offset(
                (left + xs[i] / maxX * w).toFloat(),
                (bottom - (ys[i] - minY) / (maxY - minY) * h).toFloat(),
            )
            for (k in 0..2) {
                val y = top + h * k / 2
                drawLine(gridColor, Offset(left, y), Offset(size.width, y), 1f)
            }
            // Downsample to at most ~2 points per pixel.
            val step = maxOf(1, xs.size / (w.toInt() * 2).coerceAtLeast(1))
            val line = Path()
            val area = Path()
            var first = true
            var i = 0
            while (i < xs.size) {
                val o = px(i)
                if (first) {
                    line.moveTo(o.x, o.y); area.moveTo(o.x, bottom); area.lineTo(o.x, o.y); first = false
                } else {
                    line.lineTo(o.x, o.y); area.lineTo(o.x, o.y)
                }
                i = if (i + step >= xs.size && i != xs.lastIndex) xs.lastIndex else i + step
            }
            area.lineTo(px(xs.lastIndex).x, bottom)
            area.close()
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.03f)), top, bottom))
            drawPath(line, color, style = Stroke(2.dp.toPx()))

            val style = TextStyle(color = textColor, fontSize = 10.sp)
            drawText(measurer, formatY(maxY), Offset(0f, top - 2.dp.toPx()), style = style)
            drawText(measurer, formatY(minY), Offset(0f, bottom - 12.dp.toPx()), style = style)
            drawText(measurer, "0", Offset(left, bottom + 2.dp.toPx()), style = style)
            val endLabel = String.format(Locale.getDefault(), "%.1f %s", Format.distanceValue(maxX, units), Format.distanceUnit(units))
            val endWidth = measurer.measure(endLabel, style).size.width
            drawText(measurer, endLabel, Offset(size.width - endWidth, bottom + 2.dp.toPx()), style = style)

            selected?.let {
                val o = px(it)
                drawLine(textColor, Offset(o.x, top), Offset(o.x, bottom), 1.dp.toPx())
                drawCircle(color, 4.dp.toPx(), o)
            }
        }
    }
}
