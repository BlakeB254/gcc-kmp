package com.gcc.admin.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminOutlineVariant
import com.gcc.admin.ui.theme.ChartGreen

/**
 * Canvas-based line chart.
 *
 * Plots data points as a connected line with dots at each vertex.
 * Shows x-axis labels below and grid lines in the background.
 *
 * @param data List of data points to plot.
 * @param modifier Modifier applied to the Canvas.
 * @param lineColor Color of the line and data points.
 */
@Composable
fun LineChart(
    data: List<ChartDataPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = ChartGreen,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = AdminOnSurfaceVariant)
    val gridColor = AdminOutlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
    ) {
        if (data.isEmpty()) return@Canvas

        val maxValue = data.maxOf { it.value }.coerceAtLeast(1f)
        val labelAreaHeight = 28.dp.toPx()
        val topPadding = 16.dp.toPx()
        val chartHeight = size.height - labelAreaHeight - topPadding
        val pointCount = data.size
        val stepX = if (pointCount > 1) size.width / (pointCount - 1) else size.width / 2

        // Grid lines
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = topPadding + (chartHeight * i / gridLines)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        // Build path
        val path = Path()
        val points = data.mapIndexed { index, point ->
            val x = if (pointCount > 1) index * stepX else size.width / 2
            val y = topPadding + chartHeight - (point.value / maxValue) * chartHeight
            Offset(x, y)
        }

        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }

            // Draw line
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Draw dots
            points.forEach { pt ->
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = pt,
                )
            }

            // X-axis labels
            data.forEachIndexed { index, point ->
                val x = if (pointCount > 1) index * stepX else size.width / 2
                drawTextLabel(
                    textMeasurer = textMeasurer,
                    text = point.label,
                    style = labelStyle,
                    x = x,
                    y = topPadding + chartHeight + 6.dp.toPx(),
                    anchorBottom = false,
                    maxWidth = stepX.coerceAtLeast(40.dp.toPx()),
                )
            }
        }
    }
}
