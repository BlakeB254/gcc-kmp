package com.gcc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.LiveDetection
import com.gcc.app.ui.theme.GccGreen
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a circle reticle + label over each live trash detection from the AR
 * frame pipeline. Detections arrive in normalized view coordinates
 * (top-left origin), so this just scales to the canvas size.
 *
 * The reticle also shows the detection's promotion progress: a brighter arc
 * sweeps around the dim base circle as MarkerPromotionTracker's streak
 * builds, and a checkmark briefly flashes in place of the arc the tick a
 * world marker gets placed.
 */
@Composable
fun DetectionReticleOverlay(
    detections: List<LiveDetection>,
    modifier: Modifier = Modifier,
) {
    if (detections.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
    val checkmarkStyle = TextStyle(
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )

    val justPromoted = detections.first().justPromoted
    val checkmarkAlpha = remember { Animatable(0f) }
    LaunchedEffect(justPromoted) {
        if (justPromoted) {
            checkmarkAlpha.snapTo(1f)
            checkmarkAlpha.animateTo(0f, animationSpec = tween(durationMillis = 900))
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val minDim = min(size.width, size.height)
        for (detection in detections) {
            val center = Offset(detection.cx * size.width, detection.cy * size.height)
            val radius = (detection.r * minDim).coerceIn(28.dp.toPx(), minDim * 0.45f)

            // Dim base circle -- the "track" the progress arc sweeps around.
            drawCircle(
                color = GccGreen.copy(alpha = 0.35f),
                radius = radius,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = GccGreen.copy(alpha = 0.15f),
                radius = radius,
                center = center,
            )

            if (checkmarkAlpha.value > 0f) {
                val checkmark = textMeasurer.measure("✓", checkmarkStyle)
                drawText(
                    textLayoutResult = checkmark,
                    topLeft = Offset(
                        center.x - checkmark.size.width / 2f,
                        center.y - checkmark.size.height / 2f,
                    ),
                    alpha = checkmarkAlpha.value,
                )
            } else if (detection.streakTicks > 0 && detection.streakProgress > 0) {
                val sweepFraction = (detection.streakProgress.toFloat() / detection.streakTicks)
                    .coerceIn(0f, 1f)
                drawArc(
                    color = GccGreen,
                    startAngle = -90f,
                    sweepAngle = 360f * sweepFraction,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            val distanceSuffix = detection.distanceMeters?.let { d ->
                " · ${(d * 10).roundToInt() / 10.0}m"
            } ?: ""
            val label = textMeasurer.measure(detection.label + distanceSuffix, labelStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    (center.x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width),
                    (center.y - radius - label.size.height - 6.dp.toPx()).coerceAtLeast(0f),
                ),
            )
        }
    }
}
