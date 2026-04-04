package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * A rounded-square FAB that zooms the map to the user's current GPS location.
 * Clean Apple Maps-inspired design with a navigation arrow icon.
 */
@Composable
fun MyLocationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = Color.White,
        contentColor = Color(0xFF007AFF),
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
        ),
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val color = Color(0xFF007AFF)

            // Navigation arrow (Apple Maps style)
            val arrowSize = size.width * 0.38f
            val path = androidx.compose.ui.graphics.Path().apply {
                // Top point
                moveTo(cx, cy - arrowSize)
                // Bottom-right
                lineTo(cx + arrowSize * 0.7f, cy + arrowSize * 0.6f)
                // Center notch
                lineTo(cx, cy + arrowSize * 0.15f)
                // Bottom-left
                lineTo(cx - arrowSize * 0.7f, cy + arrowSize * 0.6f)
                close()
            }
            drawPath(path, color)
        }
    }
}
