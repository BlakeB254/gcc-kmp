package com.gcc.admin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.gcc.shared.model.CheckpointType

/**
 * Procedural checkpoint thumbnail for the admin checkpoint editor.
 *
 * Mirrors the mobile app's CheckpointThumbnail composable to maintain
 * visual consistency across platforms: START (green flag), END (red
 * checkered), PHOTO/RECORD/VERIFY (teal camera), ALLEY (brown alley).
 */
@Composable
fun AdminCheckpointThumbnail(
    type: CheckpointType,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (type) {
                CheckpointType.ROUTE_START, CheckpointType.CLEAN -> drawStartScene()
                CheckpointType.ROUTE_END, CheckpointType.VERIFY -> drawEndScene()
                CheckpointType.PHOTO_POINT, CheckpointType.RECORD,
                CheckpointType.RECORD_CLEAN_VERIFY -> drawPhotoScene()
                CheckpointType.ALLEY_ENTRANCE -> drawAlleyScene()
            }
        }
    }
}

private fun DrawScope.drawStartScene() {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF81C784))))
    drawRect(Color(0xFF616161).copy(alpha = 0.4f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    drawRect(Color(0xFFFFEB3B).copy(alpha = 0.6f), Offset(w * 0.48f, 0f), Size(w * 0.04f, h))
    val flagPole = Offset(w * 0.55f, h * 0.15f)
    drawLine(Color.White, flagPole, Offset(flagPole.x, h * 0.65f), strokeWidth = 2f)
    val flag = Path().apply {
        moveTo(flagPole.x, flagPole.y)
        lineTo(flagPole.x + w * 0.3f, flagPole.y + h * 0.1f)
        lineTo(flagPole.x, flagPole.y + h * 0.2f)
        close()
    }
    drawPath(flag, Color(0xFF4CAF50))
}

private fun DrawScope.drawEndScene() {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFFC62828), Color(0xFFEF5350))))
    drawRect(Color(0xFF424242).copy(alpha = 0.4f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    val cellSize = w * 0.15f
    for (row in 0..2) {
        for (col in 0..2) {
            val alpha = if ((row + col) % 2 == 0) 0.7f else 0f
            if (alpha > 0f) {
                drawRect(
                    Color.White.copy(alpha = alpha),
                    Offset(w * 0.2f + col * cellSize, h * 0.2f + row * cellSize),
                    Size(cellSize, cellSize),
                )
            }
        }
    }
}

private fun DrawScope.drawPhotoScene() {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFF00796B), Color(0xFF4DB6AC))))
    drawRect(Color(0xFF616161).copy(alpha = 0.35f), Offset(w * 0.35f, 0f), Size(w * 0.3f, h))
    drawRect(Color(0xFF616161).copy(alpha = 0.35f), Offset(0f, h * 0.35f), Size(w, h * 0.3f))
    val camBody = Size(w * 0.4f, h * 0.25f)
    val camOffset = Offset(w * 0.3f, h * 0.55f)
    drawRoundRect(Color.White.copy(alpha = 0.7f), camOffset, camBody, CornerRadius(3f))
    drawCircle(
        Color(0xFF00695C),
        w * 0.08f,
        Offset(camOffset.x + camBody.width / 2, camOffset.y + camBody.height / 2),
    )
}

private fun DrawScope.drawAlleyScene() {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFF5D4037), Color(0xFF8D6E63))))
    val leftWall = Path().apply {
        moveTo(0f, 0f); lineTo(w * 0.3f, h * 0.25f); lineTo(w * 0.3f, h); lineTo(0f, h); close()
    }
    drawPath(leftWall, Color(0xFF795548).copy(alpha = 0.6f))
    val rightWall = Path().apply {
        moveTo(w, 0f); lineTo(w * 0.7f, h * 0.25f); lineTo(w * 0.7f, h); lineTo(w, h); close()
    }
    drawPath(rightWall, Color(0xFF795548).copy(alpha = 0.6f))
    drawRect(Color(0xFF9E9E9E).copy(alpha = 0.3f), Offset(w * 0.3f, h * 0.25f), Size(w * 0.4f, h * 0.75f))
}
