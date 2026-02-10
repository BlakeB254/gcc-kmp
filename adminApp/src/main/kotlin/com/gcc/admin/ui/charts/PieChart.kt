package com.gcc.admin.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurface
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.ChartPalette

/**
 * Canvas-based pie (or donut) chart with a side legend.
 *
 * @param data Data points to visualize as slices.
 * @param modifier Modifier applied to the layout container.
 * @param donut If true, renders a donut chart (hollow center). Defaults to false.
 */
@Composable
fun PieChart(
    data: List<ChartDataPoint>,
    modifier: Modifier = Modifier,
    donut: Boolean = false,
) {
    if (data.isEmpty()) return

    val total = data.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chart canvas
        Canvas(
            modifier = Modifier.size(200.dp),
        ) {
            val diameter = size.minDimension
            val radius = diameter / 2
            val strokeWidth = if (donut) radius * 0.4f else 0f
            val drawRadius = if (donut) radius - strokeWidth / 2 else radius
            val center = Offset(size.width / 2, size.height / 2)

            var startAngle = -90f
            data.forEachIndexed { index, point ->
                val sweepAngle = (point.value / total) * 360f
                val sliceColor = point.color ?: ChartPalette[index % ChartPalette.size]

                if (donut) {
                    drawArc(
                        color = sliceColor,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - drawRadius, center.y - drawRadius),
                        size = Size(drawRadius * 2, drawRadius * 2),
                        style = Stroke(width = strokeWidth),
                    )
                } else {
                    drawArc(
                        color = sliceColor,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(diameter, diameter),
                    )
                }

                startAngle += sweepAngle
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Legend
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            data.forEachIndexed { index, point ->
                val dotColor = point.color ?: ChartPalette[index % ChartPalette.size]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = dotColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = point.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = AdminOnSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = point.value.toInt().toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AdminOnSurfaceVariant,
                    )
                }
            }
        }
    }
}
