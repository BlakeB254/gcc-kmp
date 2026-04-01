package com.gcc.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A circular FAB that zooms the map to the user's current GPS location.
 * Hidden when GPS is unavailable. Renders a crosshair icon matching
 * the iOS system location button style.
 *
 * @param onClick       Callback to trigger camera animation to user location
 * @param modifier      Standard Compose modifier for positioning
 */
@Composable
fun MyLocationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        containerColor = Color.White,
        contentColor = Color(0xFF007AFF),
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp,
        ),
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width * 0.32f
            val armLength = size.width * 0.18f
            val gap = size.width * 0.06f
            val color = Color(0xFF007AFF)

            // Outer circle (location ring)
            drawCircle(
                color = color,
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // Center dot
            drawCircle(
                color = color,
                radius = 2.5.dp.toPx(),
                center = center,
            )

            // Top arm
            drawLine(
                color = color,
                start = Offset(center.x, center.y - radius - gap),
                end = Offset(center.x, center.y - radius - gap - armLength),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Bottom arm
            drawLine(
                color = color,
                start = Offset(center.x, center.y + radius + gap),
                end = Offset(center.x, center.y + radius + gap + armLength),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Left arm
            drawLine(
                color = color,
                start = Offset(center.x - radius - gap, center.y),
                end = Offset(center.x - radius - gap - armLength, center.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Right arm
            drawLine(
                color = color,
                start = Offset(center.x + radius + gap, center.y),
                end = Offset(center.x + radius + gap + armLength, center.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
