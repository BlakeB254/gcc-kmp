package com.gcc.admin.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminOutlineVariant

/**
 * A single data series for the line chart.
 */
data class LineChartSeries(
    val label: String,
    val points: List<Float>,
    val color: Color,
)

/**
 * Canvas-based multi-series line chart with optional dots, grid lines,
 * and x-axis labels.
 */
@Composable
fun LineChart(
    series: List<LineChartSeries>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    showDots: Boolean = true,
    showGrid: Boolean = true,
) {
    if (series.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = AdminOnSurfaceVariant,
    )

    val allPoints = series.flatMap { it.points }
    val globalMax = allPoints.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val globalMin = allPoints.minOrNull() ?: 0f
    val range = (globalMax - globalMin).coerceAtLeast(1f)

    Canvas(
        modifier = modifier.heightIn(min = 200.dp),
    ) {
        val chartLeft = 8f
        val chartRight = size.width - 8f
        val chartTop = 8f
        val chartBottom = if (labels.isNotEmpty()) size.height - 28f else size.height - 8f
        val chartHeight = chartBottom - chartTop
        val chartWidth = chartRight - chartLeft

        // ── Grid Lines (3 horizontal dashed lines) ───────────────────
        if (showGrid) {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
                val y = chartBottom - (chartHeight * fraction)
                drawLine(
                    color = AdminOutlineVariant,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1f,
                    pathEffect = dashEffect,
                )
            }
        }

        // ── Series ───────────────────────────────────────────────────
        series.forEach { s ->
            if (s.points.size < 2) return@forEach

            val step = chartWidth / (s.points.size - 1).coerceAtLeast(1)

            fun pointOffset(index: Int): Offset {
                val x = chartLeft + index * step
                val normalised = (s.points[index] - globalMin) / range
                val y = chartBottom - (chartHeight * normalised)
                return Offset(x, y)
            }

            // Draw path
            val path = Path().apply {
                val first = pointOffset(0)
                moveTo(first.x, first.y)
                for (i in 1 until s.points.size) {
                    val pt = pointOffset(i)
                    lineTo(pt.x, pt.y)
                }
            }

            drawPath(
                path = path,
                color = s.color,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Dots
            if (showDots) {
                for (i in s.points.indices) {
                    val pt = pointOffset(i)
                    drawCircle(
                        color = s.color,
                        radius = 4.dp.toPx(),
                        center = pt,
                    )
                }
            }
        }

        // ── X-axis Labels ────────────────────────────────────────────
        if (labels.isNotEmpty()) {
            val maxPoints = series.maxOf { it.points.size }
            val step = chartWidth / (maxPoints - 1).coerceAtLeast(1)

            labels.forEachIndexed { index, label ->
                if (index < maxPoints) {
                    val measured = textMeasurer.measure(label, labelStyle)
                    val x = chartLeft + index * step - measured.size.width / 2f
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(x.coerceAtLeast(0f), chartBottom + 6f),
                    )
                }
            }
        }
    }
}
