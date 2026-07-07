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
import com.gcc.app.ui.theme.GccClaimed
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import kotlin.math.min
import kotlin.math.roundToInt

/** Same 1-5 scale as [com.gcc.app.platform.LiveSeverityEstimator] and
 *  OnDeviceAnalyzer's post-capture severity -- low reuses the existing brand
 *  green so a low-severity reticle still reads as "unchanged" at a glance. */
private fun reticleColorFor(severity: Int): Color = when {
    severity >= 4 -> GccError
    severity == 3 -> GccClaimed
    else -> GccGreen
}

/**
 * Draws a circle reticle + label over each live trash detection from the AR
 * frame pipeline. Detections arrive in normalized view coordinates
 * (top-left origin), so this just scales to the canvas size.
 *
 * The reticle also shows the detection's promotion progress: a white arc
 * overlay sweeps around the (unchanged, full-opacity) base circle as
 * MarkerPromotionTracker's streak builds, and a checkmark briefly flashes in
 * place of the arc the tick a world marker gets placed. At streakProgress=0
 * the base circle renders identically to how it did before this feature.
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
            val reticleColor = reticleColorFor(detection.severity)

            // Base circle -- color now carries the live severity estimate
            // (green/amber/red); shape/position/animation are otherwise
            // unchanged from the pre-severity-coding appearance.
            drawCircle(
                color = reticleColor,
                radius = radius,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = reticleColor.copy(alpha = 0.15f),
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
                // Distinct overlay color + thicker stroke than the base circle so
                // this reads as "something building up" on top of the unchanged
                // base, rather than a brightness contrast against a dimmed base.
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * sweepFraction,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 5.dp.toPx()),
                )
            }

            val distanceSuffix = detection.distanceMeters?.let { d ->
                // Lock-on needs ≤2m (NEAR_DISTANCE_METERS); past that the
                // streak arc can never fill — say so instead of letting the
                // user wonder. Also walks them into LiDAR/depth range.
                val hint = if (d > 2.2) " · move closer" else ""
                " · ${(d * 10).roundToInt() / 10.0}m$hint"
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
