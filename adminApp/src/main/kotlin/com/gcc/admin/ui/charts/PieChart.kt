package com.gcc.admin.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant

/**
 * A single slice entry for the pie chart.
 */
data class PieChartData(
    val label: String,
    val value: Float,
    val color: Color,
)

/**
 * Canvas-based pie (or donut) chart with an optional legend.
 *
 * Sweep angles are animated from 0 to their final values.
 */
@Composable
fun PieChart(
    data: List<PieChartData>,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
    donut: Boolean = false,
) {
    if (data.isEmpty()) return

    val total = data.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)

    // Animation fraction
    var targetFraction by remember { mutableStateOf(0f) }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 700),
    )
    LaunchedEffect(data) { targetFraction = 1f }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Chart Canvas ─────────────────────────────────────────────
        Canvas(modifier = Modifier.size(200.dp)) {
            val diameter = minOf(size.width, size.height)
            val radius = diameter / 2f
            val strokeWidth = if (donut) 30.dp.toPx() else 0f
            val arcSize = if (donut) {
                Size(diameter - strokeWidth, diameter - strokeWidth)
            } else {
                Size(diameter, diameter)
            }
            val arcOffset = if (donut) {
                Offset(strokeWidth / 2f, strokeWidth / 2f)
            } else {
                Offset.Zero
            }

            var startAngle = -90f // Start from top

            data.forEach { entry ->
                val sweepAngle = (entry.value / total) * 360f * animatedFraction

                if (donut) {
                    drawArc(
                        color = entry.color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcOffset,
                        size = arcSize,
                        style = Stroke(width = strokeWidth),
                    )
                } else {
                    drawArc(
                        color = entry.color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true,
                        topLeft = arcOffset,
                        size = arcSize,
                    )
                }

                startAngle += sweepAngle
            }
        }

        // ── Legend ───────────────────────────────────────────────────
        if (showLegend) {
            Spacer(Modifier.width(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                data.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(entry.color),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = entry.value.toInt().toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = AdminOnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
