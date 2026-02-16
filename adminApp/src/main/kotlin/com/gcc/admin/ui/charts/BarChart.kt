package com.gcc.admin.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminOutlineVariant

/**
 * A single bar entry.
 */
data class BarChartData(
    val label: String,
    val value: Float,
    val color: Color,
)

/**
 * Canvas-based bar chart with rounded bars, optional labels, and
 * animated height transitions.
 */
@Composable
fun BarChart(
    data: List<BarChartData>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    animate: Boolean = true,
) {
    if (data.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    // Animate from 0 -> 1
    var targetFraction by remember { mutableStateOf(if (animate) 0f else 1f) }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 600),
    )
    LaunchedEffect(data) { targetFraction = 1f }

    val maxValue = data.maxOf { it.value }.coerceAtLeast(1f)

    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = AdminOnSurfaceVariant,
    )
    val scaleStyle = TextStyle(
        fontSize = 9.sp,
        color = AdminOnSurfaceVariant,
    )

    Canvas(
        modifier = modifier.heightIn(min = 200.dp),
    ) {
        val chartLeft = 40f
        val chartBottom = if (showLabels) size.height - 28f else size.height - 8f
        val chartTop = 8f
        val chartHeight = chartBottom - chartTop
        val chartWidth = size.width - chartLeft - 8f

        val barGap = 8.dp.toPx()
        val totalGaps = (data.size - 1).coerceAtLeast(0) * barGap
        val barWidth = ((chartWidth - totalGaps) / data.size).coerceAtLeast(4f)

        // ── Y-axis scale lines (0%, 50%, 100%) ──────────────────────
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = chartBottom - (chartHeight * fraction)
            drawLine(
                color = AdminOutlineVariant,
                start = Offset(chartLeft, y),
                end = Offset(size.width - 8f, y),
                strokeWidth = 1f,
            )
            val scaleText = "${(maxValue * fraction).toInt()}"
            val measured = textMeasurer.measure(scaleText, scaleStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = chartLeft - measured.size.width - 4f,
                    y = y - measured.size.height / 2f,
                ),
            )
        }

        // ── Bars ─────────────────────────────────────────────────────
        data.forEachIndexed { index, entry ->
            val x = chartLeft + index * (barWidth + barGap)
            val normalised = (entry.value / maxValue) * animatedFraction
            val barHeight = chartHeight * normalised

            drawRoundRect(
                color = entry.color,
                topLeft = Offset(x, chartBottom - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4f, 4f),
            )

            // ── Label below bar ──────────────────────────────────────
            if (showLabels) {
                val measured = textMeasurer.measure(entry.label, labelStyle)
                val labelX = x + (barWidth - measured.size.width) / 2f
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(labelX, chartBottom + 6f),
                )
            }
        }
    }
}
