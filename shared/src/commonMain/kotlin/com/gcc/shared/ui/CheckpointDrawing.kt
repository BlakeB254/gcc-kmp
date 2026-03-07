package com.gcc.shared.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Shared DrawScope extension functions for procedural checkpoint thumbnails.
 *
 * These draw scene-based Canvas illustrations representing each checkpoint type:
 * - START: green road with flag and directional arrow
 * - END: red checkered finish line with flag pole
 * - PHOTO: teal intersection with camera icon
 * - ALLEY: brown perspective alley with directional arrow
 *
 * Used by both the mobile app (CheckpointThumbnail) and the admin desktop app
 * (AdminCheckpointThumbnail) to maintain visual consistency across platforms.
 */

fun DrawScope.drawStartScene() {
    val w = size.width
    val h = size.height
    // Green gradient background
    drawRect(Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF81C784))))
    // Road
    drawRect(Color(0xFF616161).copy(alpha = 0.4f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    // Center line
    drawRect(Color(0xFFFFEB3B).copy(alpha = 0.6f), Offset(w * 0.48f, 0f), Size(w * 0.04f, h))
    // Start flag
    val flagPole = Offset(w * 0.55f, h * 0.15f)
    drawLine(Color.White, flagPole, Offset(flagPole.x, h * 0.65f), strokeWidth = 3f)
    val flag = Path().apply {
        moveTo(flagPole.x, flagPole.y)
        lineTo(flagPole.x + w * 0.3f, flagPole.y + h * 0.1f)
        lineTo(flagPole.x, flagPole.y + h * 0.2f)
        close()
    }
    drawPath(flag, Color(0xFF4CAF50))
    // Arrow pointing down
    val arrow = Path().apply {
        moveTo(w * 0.25f, h * 0.3f)
        lineTo(w * 0.25f, h * 0.6f)
        lineTo(w * 0.15f, h * 0.6f)
        lineTo(w * 0.25f, h * 0.75f)
        lineTo(w * 0.35f, h * 0.6f)
        lineTo(w * 0.25f, h * 0.6f)
    }
    drawPath(arrow, Color.White.copy(alpha = 0.8f))
}

fun DrawScope.drawEndScene() {
    val w = size.width
    val h = size.height
    // Red-orange gradient background
    drawRect(Brush.linearGradient(listOf(Color(0xFFC62828), Color(0xFFEF5350))))
    // Road
    drawRect(Color(0xFF424242).copy(alpha = 0.4f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    // Checkered finish pattern
    val cellSize = w * 0.12f
    for (row in 0..3) {
        for (col in 0..3) {
            if ((row + col) % 2 == 0) {
                drawRect(
                    Color.White.copy(alpha = 0.7f),
                    Offset(w * 0.2f + col * cellSize, h * 0.15f + row * cellSize),
                    Size(cellSize, cellSize),
                )
            } else {
                drawRect(
                    Color.Black.copy(alpha = 0.5f),
                    Offset(w * 0.2f + col * cellSize, h * 0.15f + row * cellSize),
                    Size(cellSize, cellSize),
                )
            }
        }
    }
    // Finish flag pole
    drawLine(Color.White, Offset(w * 0.15f, h * 0.1f), Offset(w * 0.15f, h * 0.7f), strokeWidth = 3f)
}

fun DrawScope.drawPhotoPointScene() {
    val w = size.width
    val h = size.height
    // Blue-teal gradient
    drawRect(Brush.linearGradient(listOf(Color(0xFF00796B), Color(0xFF4DB6AC))))
    // Intersection cross-roads
    drawRect(Color(0xFF616161).copy(alpha = 0.35f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    drawRect(Color(0xFF616161).copy(alpha = 0.35f), Offset(0f, h * 0.35f), Size(w, h * 0.3f))
    // Camera icon shape
    val camBody = Size(w * 0.4f, h * 0.25f)
    val camOffset = Offset(w * 0.3f, h * 0.55f)
    drawRoundRect(
        Color.White.copy(alpha = 0.7f),
        camOffset,
        camBody,
        CornerRadius(4f),
    )
    // Camera lens
    drawCircle(
        Color(0xFF00695C),
        w * 0.08f,
        Offset(camOffset.x + camBody.width / 2, camOffset.y + camBody.height / 2),
    )
    // Camera flash/viewfinder top
    drawRoundRect(
        Color.White.copy(alpha = 0.5f),
        Offset(camOffset.x + camBody.width * 0.3f, camOffset.y - h * 0.06f),
        Size(camBody.width * 0.3f, h * 0.07f),
        CornerRadius(2f),
    )
}

fun DrawScope.drawAlleyPointScene() {
    val w = size.width
    val h = size.height
    // Brown-amber gradient
    drawRect(Brush.linearGradient(listOf(Color(0xFF5D4037), Color(0xFF8D6E63))))
    // Alley perspective walls
    val leftWall = Path().apply {
        moveTo(0f, 0f)
        lineTo(w * 0.3f, h * 0.25f)
        lineTo(w * 0.3f, h)
        lineTo(0f, h)
        close()
    }
    drawPath(leftWall, Color(0xFF795548).copy(alpha = 0.6f))
    val rightWall = Path().apply {
        moveTo(w, 0f)
        lineTo(w * 0.7f, h * 0.25f)
        lineTo(w * 0.7f, h)
        lineTo(w, h)
        close()
    }
    drawPath(rightWall, Color(0xFF795548).copy(alpha = 0.6f))
    // Alley floor
    drawRect(Color(0xFF9E9E9E).copy(alpha = 0.3f), Offset(w * 0.3f, h * 0.25f), Size(w * 0.4f, h * 0.75f))
    // Arrow pointing into alley
    val arrow = Path().apply {
        moveTo(w * 0.5f, h * 0.1f)
        lineTo(w * 0.4f, h * 0.25f)
        lineTo(w * 0.45f, h * 0.25f)
        lineTo(w * 0.45f, h * 0.45f)
        lineTo(w * 0.55f, h * 0.45f)
        lineTo(w * 0.55f, h * 0.25f)
        lineTo(w * 0.6f, h * 0.25f)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = 0.6f))
}
