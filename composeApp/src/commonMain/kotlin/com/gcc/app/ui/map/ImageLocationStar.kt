package com.gcc.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A gold 5-pointed star composable for marking display image locations.
 * Renders as an actual star shape — distinct from circle checkpoint markers
 * and orange spot dots.
 */
@Composable
fun ImageLocationStar(
    modifier: Modifier = Modifier,
    size: Float = 28f,
) {
    Canvas(modifier = modifier.size(size.dp)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outerR = this.size.minDimension / 2f * 0.9f
        val innerR = outerR * 0.42f
        val starColor = Color(0xFFFFB300)
        val pi = 3.14159265358979

        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = (pi / 5.0) * i - pi / 2.0
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // White outline for visibility on any background
        drawPath(path, color = Color.White, style = Stroke(width = 3f))
        // Gold fill
        drawPath(path, color = starColor)
        // Dark amber stroke for definition
        drawPath(path, color = Color(0xFFF57F17), style = Stroke(width = 1.2f))
    }
}
