package com.gcc.app.ui.gamification

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Circular progress ring used in badge cards to show completion percentage.
 *
 * Animates from 0 to the target progress on first composition.
 * The ring color matches the badge tier.
 */
@Composable
fun ProgressRing(
    progress: Float, // 0.0 to 1.0
    color: Color,
    trackColor: Color = color.copy(alpha = 0.15f),
    strokeWidth: Dp = 4.dp,
    size: Dp = 48.dp,
    durationMs: Int = 1000,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    var animationPlayed by remember { mutableStateOf(false) }
    val animatedProgress by animateFloatAsState(
        targetValue = if (animationPlayed) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "progress_ring",
    )

    LaunchedEffect(progress) {
        animationPlayed = true
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
            )
            val arcSize = Size(
                this.size.width - stroke.width,
                this.size.height - stroke.width,
            )
            val topLeft = Offset(stroke.width / 2, stroke.width / 2)

            // Background track
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            // Progress arc
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
        content()
    }
}
