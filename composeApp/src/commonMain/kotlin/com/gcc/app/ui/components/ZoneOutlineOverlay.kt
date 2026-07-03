package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ground-plane polygon outlines projected over the live camera feed: each
 * credited scan zone renders as a green quad "painted onto the street" ahead
 * of the worker — the registration feedback the zone counter used to convey,
 * now shown spatially (Pokémon-GO style) instead of as a number.
 *
 * Projection model (no ARKit pose available on the AVCapture surface): pinhole
 * camera at [CAMERA_HEIGHT_METERS] above ground, fixed downward pitch, yaw
 * from the compass heading. Approximate by design — cells swim a little as
 * the phone tilts, which reads as "AR-ish" rather than wrong. The true ARKit
 * version rides the AR surfaces' SceneKit fog/marker pipeline.
 */
@Composable
fun ZoneOutlineOverlay(
    userLat: Double?,
    userLng: Double?,
    headingDegrees: Float?,
    cellCenters: List<Pair<Double, Double>>,
    cellSizeMeters: Double = 3.0,
    modifier: Modifier = Modifier,
) {
    if (userLat == null || userLng == null || headingDegrees == null) return
    if (cellCenters.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val lngScale = cos(userLat * PI / 180.0)
        val headingRad = headingDegrees * PI / 180.0
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)
        val horizonY = size.height * 0.42f
        val focal = size.width * 0.9f
        val half = cellSizeMeters / 2.0

        fun project(eastM: Double, northM: Double): Offset? {
            // Camera frame: forward along heading, right perpendicular.
            val forward = cosH * northM + sinH * eastM
            val right = cosH * eastM - sinH * northM
            if (forward < 1.2 || forward > 28.0) return null
            val x = size.width / 2f + (right / forward * focal).toFloat()
            val y = horizonY + (CAMERA_HEIGHT_METERS / forward * focal).toFloat()
            if (y < horizonY || y > size.height * 1.4f) return null
            return Offset(x, y)
        }

        for ((lat, lng) in cellCenters) {
            val northM = (lat - userLat) * 111_320.0
            val eastM = (lng - userLng) * 111_320.0 * lngScale
            val corners = listOf(
                project(eastM - half, northM - half),
                project(eastM + half, northM - half),
                project(eastM + half, northM + half),
                project(eastM - half, northM + half),
            )
            if (corners.any { it == null }) continue
            val path = Path().apply {
                moveTo(corners[0]!!.x, corners[0]!!.y)
                corners.drop(1).forEach { lineTo(it!!.x, it.y) }
                close()
            }
            // Distance fade: nearer zones read stronger.
            val distance = kotlin.math.sqrt(eastM * eastM + northM * northM)
            val alpha = (1.0 - (distance / 30.0)).coerceIn(0.15, 0.75).toFloat()
            drawPath(path, color = GccGreen.copy(alpha = alpha * 0.22f))
            drawPath(
                path,
                color = GccGreen.copy(alpha = alpha),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private const val CAMERA_HEIGHT_METERS = 1.4f
