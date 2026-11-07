package ai.sentinel.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.sentinel.mobile.ui.theme.SentinelBlue
import ai.sentinel.mobile.ui.theme.SentinelGreen
import ai.sentinel.mobile.ui.theme.SentinelNavy
import ai.sentinel.mobile.ui.theme.SentinelRed
import ai.sentinel.mobile.ui.theme.SentinelCard
import ai.sentinel.mobile.ui.theme.SentinelGrid
import ai.sentinel.mobile.ui.theme.SentinelLine
import ai.sentinel.mobile.ui.theme.SentinelTextMuted
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.theme.SentinelWave
import kotlin.math.max
import kotlin.math.min

@Composable
fun LineChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = SentinelLine,
    areaColor: Color = lineColor.copy(alpha = 0.22f),
    gridColor: Color = SentinelGrid,
    labelColor: Color = SentinelTextMuted,
    width: Dp = 140.dp,
    height: Dp = 72.dp,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (points.isEmpty()) return@Canvas
            val count = points.size
            val series = points.map { it.second }.toFloatArray()
            val min = series.minOrNull() ?: 0f
            val max = series.maxOrNull() ?: 1f
            val range = max(max - min, 1f)
            val cw = size.width
            val ch = size.height
            for (i in 0..6) {
                val t = i / 6f
                val y = ch * (1 - t)
                drawLine(color = gridColor.copy(alpha = 0.4f), strokeWidth = 1f,
                    start = Offset(0f, y),
                    end = Offset(cw, y))
            }
            drawLine(color = gridColor.copy(alpha = 0.2f), strokeWidth = 1f,
                start = Offset(0f, ch),
                end = Offset(cw, ch))
            val path = Path()
            val area = Path()
            points.forEachIndexed { idx, (px, py) ->
                val x = (px / 100f) * cw
                val y = ch - ((py - min) / range) * (ch - 8f) - 4f
                val pt = Offset(x, y)
                if (idx == 0) {
                    path.moveTo(pt.x, pt.y)
                    area.moveTo(pt.x, ch)
                } else {
                    path.lineTo(pt.x, pt.y)
                    area.lineTo(pt.x, pt.y)
                }
                if (idx == count - 1) area.lineTo(pt.x, ch)
            }
            area.lineTo((points.last().first / 100f) * cw, ch)
            area.close()
            drawPath(area, color = areaColor)
            drawPath(path, color = lineColor, style = Stroke(width = 2f, cap = StrokeCap.Round))
            points.forEachIndexed { idx, (_, py) ->
                val x = (points[idx].first / 100f) * cw
                val y = ch - ((py - min) / range) * (ch - 8f) - 4f
                drawCircle(color = lineColor, radius = 4f, center = Offset(x, y))
            }
        }
    }
}

@Composable
fun WaveLine(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    maxBarHeight: Float = 100f,
    activeColor: Color = SentinelWave,
    idleColor: Color = SentinelTextMuted,
    barGap: Dp = 1.5.dp,
    showLabels: Boolean = false,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val count = min(values.size, barCount)
            if (count == 0) return@Canvas
            val barWidth = (size.width - (barGap.toPx() * (count - 1))) / count
            val gap = barGap.toPx()
            val cy = size.height / 2f
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(color = SentinelGrid.copy(alpha = 0.3f), strokeWidth = 1f,
                    start = Offset(0f, y),
                    end = Offset(size.width, y))
            }
            for (i in 0 until count) {
                val h = if (i < values.size) values[i] else 0f
                val barH = (h / 100f) * (size.height - 8f)
                val x = (i + 0.5f) * (barWidth + gap) - barWidth / 2f
                val y = (size.height - barH) - 4f
                val alpha = if (h > 5f) 1f else 0.35f
                val barColor = if (h > 5f) activeColor else idleColor.copy(alpha = alpha)
                drawRoundRect(color = barColor, topLeft = Offset(x, y), size = Size(barWidth, barH), cornerRadius = CornerRadius(4f))
            }
        }
    }
}

@Composable
fun SparkLine(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = SentinelLine,
    gridColor: Color = SentinelGrid,
    barCount: Int = 24,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val count = min(values.size, barCount)
            if (count == 0) return@Canvas
            val barWidth = (size.width - (1.5f * (count - 1))) / count
            val gap = 1.5f
            val cy = size.height / 2f
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(color = gridColor.copy(alpha = 0.2f), strokeWidth = 1f,
                    start = Offset(0f, y),
                    end = Offset(size.width, y))
            }
            for (i in 0 until count) {
                val h = if (i < values.size) values[i] else 0f
                val barH = (h / 100f) * (size.height - 6f)
                val x = (i + 0.5f) * (barWidth + gap) - barWidth / 2f
                val y = cy - barH / 2f
                val alpha = if (h > 5f) 1f else 0.25f
                val barColor = if (h > 5f) color else color.copy(alpha = alpha)
                drawRoundRect(color = barColor, topLeft = Offset(x, y), size = Size(barWidth, barH), cornerRadius = CornerRadius(3f))
            }
        }
    }
}

/**
 * Accepts a "#RRGGBB" value and never throws. A malformed value falls back to a neutral
 * accent instead of taking the screen down.
 */
internal fun safeColor(value: String): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: Throwable) {
    SentinelBlue
}

@Composable
fun LiveGauge(
    title: String,
    value: String,
    color: String = "#E5534B",
    modifier: Modifier = Modifier
) {
    val gaugeColor = safeColor(color)
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SentinelCard), shape = RoundedCornerShape(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = SentinelTextSecondary, letterSpacing = 0.6.sp)
                Text(value, style = MaterialTheme.typography.headlineSmall, color = gaugeColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}
