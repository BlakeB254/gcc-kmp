package com.gcc.admin.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.ChartPalette

/**
 * A single data point for chart components.
 *
 * @param label Display label (x-axis label for bar charts, legend label for pie charts).
 * @param value Numeric value.
 * @param color Optional override color. If null, a color from [ChartPalette] is assigned.
 */
data class ChartDataPoint(
    val label: String,
    val value: Float,
    val color: Color? = null,
)

/**
 * Canvas-based vertical bar chart.
 *
 * Draws bars proportional to [ChartDataPoint.value] with labels on the x-axis
 * and value labels above each bar. Automatically scales to the maximum value
 * in the dataset.
 *
 * @param data List of data points to plot.
 * @param modifier Modifier applied to the Canvas.
 */
@Composable
fun BarChart(
    data: List<ChartDataPoint>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelColor = AdminOnSurfaceVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)
    val valueStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
    ) {
        if (data.isEmpty()) return@Canvas

        val maxValue = data.maxOf { it.value }.coerceAtLeast(1f)
        val labelAreaHeight = 28.dp.toPx()
        val valueAreaHeight = 18.dp.toPx()
        val chartTop = valueAreaHeight
        val chartHeight = size.height - labelAreaHeight - valueAreaHeight
        val barSpacing = 8.dp.toPx()
        val barCount = data.size
        val totalSpacing = barSpacing * (barCount + 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(12.dp.toPx())

        data.forEachIndexed { index, point ->
            val barColor = point.color ?: ChartPalette[index % ChartPalette.size]
            val barHeight = (point.value / maxValue) * chartHeight
            val x = barSpacing + index * (barWidth + barSpacing)
            val y = chartTop + chartHeight - barHeight

            // Bar
            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
            )

            // Value label above bar
            drawTextLabel(
                textMeasurer = textMeasurer,
                text = point.value.toInt().toString(),
                style = valueStyle,
                x = x + barWidth / 2,
                y = y - 4.dp.toPx(),
                anchorBottom = true,
            )

            // X-axis label below bar
            drawTextLabel(
                textMeasurer = textMeasurer,
                text = point.label,
                style = labelStyle,
                x = x + barWidth / 2,
                y = chartTop + chartHeight + 6.dp.toPx(),
                anchorBottom = false,
                maxWidth = barWidth + barSpacing,
            )
        }
    }
}

/**
 * Utility to draw centered text at a given position on the canvas.
 */
internal fun DrawScope.drawTextLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    anchorBottom: Boolean,
    maxWidth: Float = Float.MAX_VALUE,
) {
    val layoutResult = textMeasurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = if (maxWidth < Float.MAX_VALUE) {
            Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1))
        } else {
            Constraints()
        },
    )
    val textX = x - layoutResult.size.width / 2f
    val textY = if (anchorBottom) y - layoutResult.size.height else y

    drawText(
        textLayoutResult = layoutResult,
        topLeft = Offset(textX.coerceAtLeast(0f), textY),
    )
}
