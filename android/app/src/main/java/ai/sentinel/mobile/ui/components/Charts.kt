package ai.sentinel.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.sentinel.mobile.ui.adaptive.LocalAdaptive
import ai.sentinel.mobile.ui.theme.SentinelBlue
import ai.sentinel.mobile.ui.theme.SentinelCard
import ai.sentinel.mobile.ui.theme.SentinelGrid
import ai.sentinel.mobile.ui.theme.SentinelLine
import ai.sentinel.mobile.ui.theme.SentinelTextMuted
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.theme.SentinelWave
import kotlin.math.max
import kotlin.math.min

/**
 * Line + area chart. [height] defaults to the size-class chart height, so the same panel gets a
 * taller plot on a 13" tablet and a compact one on a phone.
 */
@Composable
fun LineChart(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = SentinelLine,
    areaColor: Color = lineColor.copy(alpha = 0.22f),
    gridColor: Color = SentinelGrid,
    labelColor: Color = SentinelTextMuted,
    width: Dp = 140.dp,
    height: Dp? = null,
) {
    val chartHeight = height ?: LocalAdaptive.current.chartHeight
    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            if (points.isEmpty()) return@Canvas
            val series = points.map { it.second }.toFloatArray()
            val lowest = series.minOrNull() ?: 0f
            val highest = series.maxOrNull() ?: 1f
            val range = max(highest - lowest, 1f)
            val cw = size.width
            val ch = size.height
            val gridStroke = 1.dp.toPx()
            val padTop = 6.dp.toPx()
            val padBottom = 6.dp.toPx()
            val usable = (ch - padTop - padBottom).coerceAtLeast(1f)

            for (i in 0..6) {
                val y = ch * (1 - i / 6f)
                drawLine(
                    color = gridColor.copy(alpha = 0.4f),
                    strokeWidth = gridStroke,
                    start = Offset(0f, y),
                    end = Offset(cw, y)
                )
            }
            drawLine(
                color = gridColor.copy(alpha = 0.2f),
                strokeWidth = gridStroke,
                start = Offset(0f, ch),
                end = Offset(cw, ch)
            )

            fun yFor(value: Float) = ch - padBottom - ((value - lowest) / range) * usable

            val path = Path()
            val area = Path()
            points.forEachIndexed { index, point ->
                val x = (point.first / 100f) * cw
                val y = yFor(point.second)
                if (index == 0) {
                    path.moveTo(x, y)
                    area.moveTo(x, ch)
                } else {
                    path.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo((points.last().first / 100f) * cw, ch)
            area.close()
            drawPath(area, color = areaColor)
            drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val dot = 3.dp.toPx()
            points.forEach { point ->
                drawCircle(
                    color = lineColor,
                    radius = dot,
                    center = Offset((point.first / 100f) * cw, yFor(point.second))
                )
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
    height: Dp? = null,
) {
    val chartHeight = height ?: LocalAdaptive.current.chartHeight
    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            val count = min(values.size, barCount)
            if (count == 0) return@Canvas
            val gap = barGap.toPx()
            val barWidth = (size.width - (gap * (count - 1))) / count
            if (barWidth <= 0f) return@Canvas
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(
                    color = SentinelGrid.copy(alpha = 0.3f),
                    strokeWidth = 1.dp.toPx(),
                    start = Offset(0f, y),
                    end = Offset(size.width, y)
                )
            }
            val corner = CornerRadius(3.dp.toPx())
            for (i in 0 until count) {
                val h = values[i].coerceIn(0f, maxBarHeight)
                val barH = (h / maxBarHeight) * (size.height - 8.dp.toPx())
                val x = (i + 0.5f) * (barWidth + gap) - barWidth / 2f
                val y = (size.height - barH) - 4.dp.toPx()
                val barColor = if (h > 5f) activeColor else idleColor.copy(alpha = 0.35f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barH),
                    cornerRadius = corner
                )
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
    height: Dp? = null,
) {
    val sparkHeight = height ?: LocalAdaptive.current.sparkHeight
    Box(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(sparkHeight)) {
            val count = min(values.size, barCount)
            if (count == 0) return@Canvas
            val gap = 1.5f
            val barWidth = (size.width - (gap * (count - 1))) / count
            if (barWidth <= 0f) return@Canvas
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(
                    color = gridColor.copy(alpha = 0.2f),
                    strokeWidth = 1.dp.toPx(),
                    start = Offset(0f, y),
                    end = Offset(size.width, y)
                )
            }
            val centerY = size.height / 2f
            val corner = CornerRadius(2.dp.toPx())
            for (i in 0 until count) {
                val h = values[i].coerceIn(0f, 100f)
                val barH = (h / 100f) * (size.height - 6.dp.toPx())
                val x = (i + 0.5f) * (barWidth + gap) - barWidth / 2f
                val y = centerY - barH / 2f
                val barColor = if (h > 5f) color else color.copy(alpha = 0.25f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barH),
                    cornerRadius = corner
                )
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SentinelCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SentinelTextSecondary,
                letterSpacing = 0.6.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = gaugeColor,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
        }
    }
}
