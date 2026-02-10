package com.gcc.app.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Coordinates
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Data class for animated route path drawing state.
 *
 * @param dashOffset The current animated dash offset (cycles 0..dashLength+gapLength)
 * @param progress  Overall draw progress 0f..1f (for progressive reveal)
 */
data class RouteAnimationState(
    val dashOffset: Float = 0f,
    val progress: Float = 1f,
)

/**
 * Creates an infinite animated dash offset for the "drawing" effect on route paths.
 *
 * Returns a float that cycles through the dash pattern, giving the illusion
 * of movement along the route line. Uses a Fibonacci-timed animation (1597ms ~ F17).
 */
@Composable
fun rememberRouteDashOffset(): Float {
    val transition = rememberInfiniteTransition(label = "routeDash")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 30f,  // dashLength(20f) + gapLength(10f)
        animationSpec = infiniteRepeatable(
            animation = tween(1597, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "routeDashOffset",
    )
    return offset
}

/**
 * Draws a route path with animated dashes for a "drawing" effect.
 *
 * The dash pattern scrolls along the path to suggest movement/activity.
 */
fun DrawScope.drawAnimatedRoutePath(
    routeCoords: List<Coordinates>,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
    dashOffset: Float,
    lineColor: Color = GccGreen,
    lineWidth: Float = MapStyles.ROUTE_LINE_WIDTH,
) {
    if (routeCoords.size < 2) return

    val path = Path()
    routeCoords.forEachIndexed { index, coord ->
        val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }

    // Background solid line (subtle)
    drawPath(
        path = path,
        color = lineColor.copy(alpha = 0.25f),
        style = Stroke(
            width = lineWidth,
            pathEffect = PathEffect.cornerPathEffect(8f),
        ),
    )

    // Animated dashed foreground
    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(
            width = lineWidth,
            pathEffect = PathEffect.chainPathEffect(
                outer = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(20f, 10f),
                    phase = dashOffset,
                ),
                inner = PathEffect.cornerPathEffect(8f),
            ),
        ),
    )
}

/**
 * Bounty zone pulse animation state.
 *
 * @param radius   Current pulse ring radius
 * @param alpha    Current pulse ring alpha (fades out as it expands)
 */
data class BountyPulseState(
    val radius: Float,
    val alpha: Float,
)

/**
 * Creates animated bounty zone pulse state.
 *
 * Returns a [BountyPulseState] with expanding radius and fading alpha,
 * timed to a 2-second loop (near Fibonacci 2584ms ~ F18, but rounded for UX).
 */
@Composable
fun rememberBountyPulse(): BountyPulseState {
    val transition = rememberInfiniteTransition(label = "bountyPulse")
    val radius by transition.animateFloat(
        initialValue = 12f,
        targetValue = 55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bountyPulseRadius",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bountyPulseAlpha",
    )
    return BountyPulseState(radius, alpha)
}

/**
 * Draws an animated bounty zone pulse on the canvas.
 *
 * Renders concentric expanding circles at the bounty location,
 * colored orange to distinguish from the GPS blue pulse.
 */
fun DrawScope.drawBountyZonePulse(
    center: Offset,
    pulseState: BountyPulseState,
    baseRadius: Float = 21f,
    color: Color = MapStyles.BOUNTY_PULSE_COLOR,
) {
    // Static base circle
    drawCircle(
        color = color.copy(alpha = 0.2f),
        radius = baseRadius,
        center = center,
    )

    // Expanding pulse ring
    drawCircle(
        color = color.copy(alpha = pulseState.alpha),
        radius = pulseState.radius,
        center = center,
        style = Stroke(width = 3f),
    )

    // Inner dot
    drawCircle(
        color = color,
        radius = 5f,
        center = center,
    )
}

/**
 * Area highlight fill color animation target.
 *
 * Returns an animated [Color] that transitions smoothly when the
 * tapped state changes. Duration uses Fibonacci timing (377ms ~ F14).
 */
@Composable
fun animateAreaFillColor(
    isTapped: Boolean,
    isServiceArea: Boolean,
): Color {
    val targetColor = when {
        isTapped && isServiceArea -> GccGreen.copy(alpha = 0.3f)
        isTapped -> MapCommunityFill.copy(alpha = 0.4f)
        isServiceArea -> GccGreen.copy(alpha = 0.15f)
        else -> MapCommunityFill
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(377, easing = FastOutSlowInEasing),
        label = "areaFillColor",
    )
    return animatedColor
}

/**
 * Draws a GPS heading indicator cone from the GPS dot position.
 *
 * @param center      The GPS dot center on canvas
 * @param headingDeg  Compass heading in degrees (0 = north, 90 = east)
 * @param coneLength  Length of the heading cone in pixels
 * @param coneAngle   Half-angle of the cone in degrees (default 21 from Fibonacci)
 * @param color       Cone fill color
 */
fun DrawScope.drawGpsHeadingCone(
    center: Offset,
    headingDeg: Float,
    coneLength: Float = 34f,
    coneAngle: Float = 21f,
    color: Color = MapGpsBlue.copy(alpha = 0.25f),
) {
    val headingRad = (headingDeg - 90f) * PI.toFloat() / 180f
    val halfAngleRad = coneAngle * PI.toFloat() / 180f

    val tipX = center.x + coneLength * cos(headingRad)
    val tipY = center.y + coneLength * sin(headingRad)

    val leftX = center.x + (coneLength * 0.3f) * cos(headingRad - halfAngleRad)
    val leftY = center.y + (coneLength * 0.3f) * sin(headingRad - halfAngleRad)

    val rightX = center.x + (coneLength * 0.3f) * cos(headingRad + halfAngleRad)
    val rightY = center.y + (coneLength * 0.3f) * sin(headingRad + halfAngleRad)

    val path = Path().apply {
        moveTo(center.x, center.y)
        lineTo(leftX, leftY)
        lineTo(tipX, tipY)
        lineTo(rightX, rightY)
        close()
    }

    drawPath(path = path, color = color)
}
