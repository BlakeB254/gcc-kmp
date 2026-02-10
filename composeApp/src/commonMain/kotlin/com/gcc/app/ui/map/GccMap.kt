package com.gcc.app.ui.map

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.map.BuildingRenderer.drawBuildings
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Data class for a long-press context pin marker.
 *
 * @param canvasX  X position on the canvas (pre-transform)
 * @param canvasY  Y position on the canvas (pre-transform)
 * @param lat      Latitude of the pin
 * @param lng      Longitude of the pin
 */
data class ContextPin(
    val canvasX: Float,
    val canvasY: Float,
    val lat: Double,
    val lng: Double,
)

/**
 * Canvas-based map composable rendering Chicago community areas.
 *
 * Features:
 * - Polygon-based community area outlines (not just rectangles)
 * - Pan and pinch-to-zoom gestures via graphicsLayer transform
 * - Double-tap zoom to next preset level
 * - Momentum scrolling with velocity-based deceleration
 * - Animated smooth zoom transitions
 * - Tap-on-area interaction with callback
 * - Animated GPS pulse ring for current location
 * - GPS heading indicator cone
 * - Route path polylines (when routes provided)
 * - Radial gradient area fills
 * - Zoom-scaled labels
 * - Grid lines at street level
 * - Slide-up tooltip animation
 * - Long-press context pin
 * - Isometric buildings at street zoom
 * - Animated route path drawing
 * - Bounty zone pulse animation
 *
 * @param modifier           Standard Compose modifier
 * @param centerLat          Map center latitude
 * @param centerLng          Map center longitude
 * @param zoom               Base zoom level (Mercator tile zoom)
 * @param currentLocation    GPS coordinates for the blue dot
 * @param gpsHeading         Compass heading in degrees (0=N, 90=E) for directional cone
 * @param enableGestures     Enable pan/zoom/tap gestures
 * @param routeCoordinates   Lists of route polyline coordinates
 * @param animateRoutes      Enable animated dash drawing on routes
 * @param bountyLocations    Coordinates for bounty zone pulse markers
 * @param onAreaTap          Callback when a community area is tapped
 * @param onContextPin       Callback when a long-press pin is placed (lat, lng)
 * @param showBuildings      Show isometric buildings at street zoom
 * @param showGridLines      Show grid lines at route zoom and above
 * @param content            Overlay content (composables positioned in the Box)
 */
@Composable
fun GccMap(
    modifier: Modifier = Modifier,
    centerLat: Double = MapStyles.CHICAGO_CENTER_LAT,
    centerLng: Double = MapStyles.CHICAGO_CENTER_LNG,
    zoom: Double = MapStyles.ZOOM_NEIGHBORHOOD,
    currentLocation: Coordinates? = null,
    gpsHeading: Float? = null,
    enableGestures: Boolean = false,
    routeCoordinates: List<List<Coordinates>> = emptyList(),
    animateRoutes: Boolean = false,
    bountyLocations: List<Coordinates> = emptyList(),
    onAreaTap: ((CommunityArea) -> Unit)? = null,
    onContextPin: ((lat: Double, lng: Double) -> Unit)? = null,
    showBuildings: Boolean = true,
    showGridLines: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()

    // --- Gesture state ---

    // Animated scale for smooth zoom transitions
    val animatedScale = remember { Animatable(1f) }
    // Momentum scrolling animatables
    val animOffsetX = remember { Animatable(0f) }
    val animOffsetY = remember { Animatable(0f) }

    // Current effective values (combine animated + direct gesture offsets)
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }
    var gestureScale by remember { mutableFloatStateOf(1f) }

    // The effective offset includes both gesture drag and momentum animation
    val effectiveOffsetX by remember { derivedStateOf { gestureOffsetX + animOffsetX.value } }
    val effectiveOffsetY by remember { derivedStateOf { gestureOffsetY + animOffsetY.value } }
    val effectiveScale by remember { derivedStateOf { gestureScale * animatedScale.value } }

    // Tapped area state
    var tappedArea by remember { mutableStateOf<CommunityArea?>(null) }

    // Context pin state (long-press)
    var contextPin by remember { mutableStateOf<ContextPin?>(null) }

    // Compute effective zoom for feature toggling (base zoom + gesture scale)
    val effectiveZoom by remember(zoom) {
        derivedStateOf {
            zoom + kotlin.math.log2(effectiveScale.toDouble().coerceAtLeast(0.01))
        }
    }

    // --- Animations ---

    // GPS pulse
    val pulseTransition = rememberInfiniteTransition(label = "gpsPulse")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 8f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseOut), RepeatMode.Restart),
        label = "gpsPulseRadius",
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseOut), RepeatMode.Restart),
        label = "gpsPulseAlpha",
    )

    // Route dash animation
    val routeDashOffset = if (animateRoutes) rememberRouteDashOffset() else 0f

    // Bounty pulse animation
    val bountyPulse = if (bountyLocations.isNotEmpty()) rememberBountyPulse() else null

    // Tooltip slide-up animation
    val tooltipVisible by remember { derivedStateOf { tappedArea != null } }
    val tooltipOffsetYFloat by animateFloatAsState(
        targetValue = if (tooltipVisible) 0f else 34f,
        animationSpec = tween(377, easing = FastOutSlowInEasing),
        label = "tooltipSlideUp",
    )
    val tooltipOffsetY = tooltipOffsetYFloat.dp
    val tooltipAlpha by animateFloatAsState(
        targetValue = if (tooltipVisible) 1f else 0f,
        animationSpec = tween(377, easing = FastOutSlowInEasing),
        label = "tooltipAlpha",
    )

    // --- Zoom-scaled label styles ---
    // Labels scale with effective zoom: smaller at city, larger at street level
    val labelScaleFactor by remember { derivedStateOf { (effectiveZoom / MapStyles.ZOOM_NEIGHBORHOOD).toFloat().coerceIn(0.6f, 2.0f) } }

    val areaLabelStyle by remember(labelScaleFactor) {
        derivedStateOf {
            TextStyle(
                fontSize = (9f * labelScaleFactor).sp,
                color = GccGreenDark,
                textAlign = TextAlign.Center,
                lineHeight = (11f * labelScaleFactor).sp,
            )
        }
    }

    val serviceAreaLabelStyle by remember(labelScaleFactor) {
        derivedStateOf {
            TextStyle(
                fontSize = (10f * labelScaleFactor).sp,
                color = GccGreenDark,
                textAlign = TextAlign.Center,
                lineHeight = (12f * labelScaleFactor).sp,
            )
        }
    }

    // --- Zoom presets for double-tap ---
    val zoomPresets = listOf(1.0f, 2.0f, 4.0f, 8.0f)

    Box(modifier = modifier.fillMaxWidth().background(MapBackground)) {
        val gestureModifier = if (enableGestures) {
            Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        // Stop any active momentum animation
                        coroutineScope.launch { animOffsetX.snapTo(0f) }
                        coroutineScope.launch { animOffsetY.snapTo(0f) }

                        gestureScale = (gestureScale * gestureZoom).coerceIn(0.3f, 8f)
                        gestureOffsetX += pan.x
                        gestureOffsetY += pan.y
                    }
                }
                .pointerInput(onAreaTap, onContextPin) {
                    detectTapGestures(
                        onDoubleTap = { _ ->
                            // Animate to next zoom preset level
                            val currentScale = gestureScale * animatedScale.value
                            val nextScale = zoomPresets.firstOrNull { it > currentScale + 0.1f }
                                ?: zoomPresets.first()
                            // Target gestureScale so that gestureScale * animatedScale.value = nextScale
                            val targetAnimScale = nextScale / gestureScale
                            coroutineScope.launch {
                                animatedScale.animateTo(
                                    targetValue = targetAnimScale,
                                    animationSpec = tween(377, easing = FastOutSlowInEasing),
                                )
                                // After animation completes, fold into gestureScale and reset animatable
                                gestureScale *= animatedScale.value
                                gestureScale = gestureScale.coerceIn(0.3f, 8f)
                                animatedScale.snapTo(1f)
                            }
                        },
                        onLongPress = { tapOffset ->
                            // Place a context pin at long-press location
                            val totalScale = gestureScale * animatedScale.value
                            val canvasX = (tapOffset.x - effectiveOffsetX - size.width / 2f * (totalScale - 1f)) / totalScale
                            val canvasY = (tapOffset.y - effectiveOffsetY - size.height / 2f * (totalScale - 1f)) / totalScale
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()

                            // Reverse Mercator to get lat/lng from pixel
                            val pinLat = pixelToLat(canvasY, centerLat, zoom, h)
                            val pinLng = pixelToLng(canvasX, centerLng, zoom, w)

                            contextPin = ContextPin(canvasX, canvasY, pinLat, pinLng)
                            onContextPin?.invoke(pinLat, pinLng)
                        },
                        onTap = { tapOffset ->
                            if (onAreaTap != null) {
                                val totalScale = gestureScale * animatedScale.value
                                val canvasX = (tapOffset.x - effectiveOffsetX - size.width / 2f * (totalScale - 1f)) / totalScale
                                val canvasY = (tapOffset.y - effectiveOffsetY - size.height / 2f * (totalScale - 1f)) / totalScale
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()

                                // Hit-test against area bounding boxes
                                val hit = chicagoCommunityAreas.find { area ->
                                    val topLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
                                    val bottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
                                    val minX = minOf(topLeft.x, bottomRight.x)
                                    val maxX = maxOf(topLeft.x, bottomRight.x)
                                    val minY = minOf(topLeft.y, bottomRight.y)
                                    val maxY = maxOf(topLeft.y, bottomRight.y)
                                    canvasX in minX..maxX && canvasY in minY..maxY
                                }
                                tappedArea = hit
                                // Clear context pin on regular tap
                                contextPin = null
                                hit?.let { onAreaTap(it) }
                            }
                        },
                    )
                }
        } else Modifier

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(gestureModifier)
                .graphicsLayer {
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    translationX = effectiveOffsetX
                    translationY = effectiveOffsetY
                }
        ) {
            val w = size.width
            val h = size.height

            // --- Grid lines at street level ---
            if (showGridLines && effectiveZoom >= MapStyles.ZOOM_ROUTE) {
                drawGridLines(centerLat, centerLng, zoom, w, h, effectiveZoom)
            }

            // --- Draw each community area ---
            for (area in chicagoCommunityAreas) {
                val isTapped = area == tappedArea
                val usePolygon = area.polygon.isNotEmpty()

                if (usePolygon) {
                    drawPolygonArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                } else {
                    drawRectArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                }

                // Area label (zoom-scaled)
                val style = if (area.isServiceArea) serviceAreaLabelStyle else areaLabelStyle
                val textResult = textMeasurer.measure(area.displayName, style)

                // Label position: center of bounding box
                val topLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
                val bottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
                val rectTopLeft = Offset(minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y))
                val rectSize = androidx.compose.ui.geometry.Size(
                    abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y)
                )
                val textX = rectTopLeft.x + (rectSize.width - textResult.size.width) / 2f
                val textY = rectTopLeft.y + (rectSize.height - textResult.size.height) / 2f
                drawText(textResult, topLeft = Offset(textX, textY))
            }

            // --- Isometric buildings at street zoom ---
            if (showBuildings && effectiveZoom >= MapStyles.ZOOM_STREET) {
                drawBuildings(
                    buildings = northLawndaleBuildings,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    canvasWidth = w,
                    canvasHeight = h,
                )
            }

            // --- Draw route path lines ---
            if (animateRoutes) {
                for (routeCoords in routeCoordinates) {
                    drawAnimatedRoutePath(
                        routeCoords = routeCoords,
                        centerLat = centerLat,
                        centerLng = centerLng,
                        zoom = zoom,
                        w = w,
                        h = h,
                        dashOffset = routeDashOffset,
                    )
                    // Checkpoint dots
                    for (coord in routeCoords) {
                        val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                        drawCircle(color = GccGreenDark, radius = 4f, center = point)
                    }
                }
            } else {
                for (routeCoords in routeCoordinates) {
                    if (routeCoords.size >= 2) {
                        val path = Path()
                        routeCoords.forEachIndexed { index, coord ->
                            val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
                        drawPath(
                            path = path,
                            color = GccGreen,
                            style = Stroke(width = MapStyles.ROUTE_LINE_WIDTH, pathEffect = PathEffect.cornerPathEffect(8f)),
                        )
                        // Checkpoint dots
                        for (coord in routeCoords) {
                            val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                            drawCircle(color = GccGreenDark, radius = 4f, center = point)
                        }
                    }
                }
            }

            // --- Bounty zone pulse markers ---
            if (bountyPulse != null) {
                for (bountyLoc in bountyLocations) {
                    val pos = latLngToPixel(bountyLoc.lat, bountyLoc.lng, centerLat, centerLng, zoom, w, h)
                    drawBountyZonePulse(
                        center = pos,
                        pulseState = bountyPulse,
                    )
                }
            }

            // --- Context pin marker ---
            contextPin?.let { pin ->
                drawContextPin(Offset(pin.canvasX, pin.canvasY))
            }

            // --- Draw animated GPS pulse + dot + heading cone ---
            if (currentLocation != null) {
                val gpsPos = latLngToPixel(currentLocation.lat, currentLocation.lng, centerLat, centerLng, zoom, w, h)

                // GPS heading cone (behind the dot)
                if (gpsHeading != null) {
                    drawGpsHeadingCone(
                        center = gpsPos,
                        headingDeg = gpsHeading,
                    )
                }

                // Expanding pulse ring
                drawCircle(
                    color = MapGpsBlue.copy(alpha = pulseAlpha),
                    radius = pulseRadius,
                    center = gpsPos,
                )
                // Solid dot
                drawCircle(color = MapGpsBlue, radius = 6f, center = gpsPos)
                // White border
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = 6f,
                    center = gpsPos,
                    style = Stroke(width = 2f),
                )
            }
        }

        // Overlay content
        content()

        // Tapped area tooltip with slide-up animation
        tappedArea?.let { area ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 13.dp)  // Fibonacci: 13dp
                    .offset(y = tooltipOffsetY)
                    .graphicsLayer { alpha = tooltipAlpha },
                shape = RoundedCornerShape(8.dp),  // Fibonacci radius: 8dp
                shadowElevation = GccTheme.elevation.floating,
                color = GccSurface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Text(
                        area.displayName.replace("\n", " "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (area.isServiceArea) {
                        Text(
                            "Active Service Area",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccGreen,
                        )
                    }
                }
            }
        }

        // Context pin callout
        contextPin?.let { pin ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 13.dp),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = GccTheme.elevation.raised,
                color = GccSurface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Text(
                        "Pinned Location",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${formatCoord(pin.lat)}, ${formatCoord(pin.lng)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

// --- Drawing helpers ---

/** Draw a community area as a polygon path with radial gradient fill. */
private fun DrawScope.drawPolygonArea(
    area: CommunityArea,
    isTapped: Boolean,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
) {
    val path = Path()
    area.polygon.forEachIndexed { index, (lat, lng) ->
        val point = latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()

    // Compute polygon center for radial gradient
    val topLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
    val bottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
    val cx = (topLeft.x + bottomRight.x) / 2f
    val cy = (topLeft.y + bottomRight.y) / 2f
    val radius = maxOf(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y)) / 2f

    // Radial gradient fill
    val (gradCenter, gradEdge) = when {
        isTapped && area.isServiceArea -> GccGreen.copy(alpha = 0.35f) to GccGreen.copy(alpha = 0.12f)
        isTapped -> MapAreaGradientCenter.copy(alpha = 0.5f) to MapAreaGradientEdge.copy(alpha = 0.15f)
        area.isServiceArea -> MapServiceGradientCenter to MapServiceGradientEdge
        else -> MapAreaGradientCenter to MapAreaGradientEdge
    }

    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(gradCenter, gradEdge),
            center = Offset(cx, cy),
            radius = radius.coerceAtLeast(1f),
        ),
    )

    // Stroke
    if (area.isServiceArea) {
        drawPath(
            path = path,
            color = MapServiceBoundary,
            style = Stroke(
                width = if (isTapped) 3.5f else 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f),
            ),
        )
    } else {
        drawPath(
            path = path,
            color = if (isTapped) GccGreen else MapCommunityStroke,
            style = Stroke(width = if (isTapped) 2f else 1f),
        )
    }
}

/** Fallback: draw a community area as a rectangle (when no polygon data). */
private fun DrawScope.drawRectArea(
    area: CommunityArea,
    isTapped: Boolean,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
) {
    val topLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
    val bottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
    val rectTopLeft = Offset(minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y))
    val rectSize = androidx.compose.ui.geometry.Size(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y))

    // Radial gradient fill for rectangles too
    val cx = rectTopLeft.x + rectSize.width / 2f
    val cy = rectTopLeft.y + rectSize.height / 2f
    val radius = maxOf(rectSize.width, rectSize.height) / 2f

    val (gradCenter, gradEdge) = when {
        isTapped && area.isServiceArea -> GccGreen.copy(alpha = 0.35f) to GccGreen.copy(alpha = 0.12f)
        isTapped -> MapAreaGradientCenter.copy(alpha = 0.5f) to MapAreaGradientEdge.copy(alpha = 0.15f)
        area.isServiceArea -> MapServiceGradientCenter to MapServiceGradientEdge
        else -> MapAreaGradientCenter to MapAreaGradientEdge
    }

    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(gradCenter, gradEdge),
            center = Offset(cx, cy),
            radius = radius.coerceAtLeast(1f),
        ),
        topLeft = rectTopLeft,
        size = rectSize,
    )

    if (area.isServiceArea) {
        drawRect(
            color = MapServiceBoundary,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)),
        )
    } else {
        drawRect(
            color = MapCommunityStroke,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = 1f),
        )
    }
}

/** Draw light gray grid lines when zoomed to route level or deeper. */
private fun DrawScope.drawGridLines(
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
    effectiveZoom: Double,
) {
    val gridColor = MapStyles.GRID_COLOR
    // Grid spacing scales inversely with zoom — finer grid at higher zoom
    val gridSpacingDeg = when {
        effectiveZoom >= MapStyles.ZOOM_STREET -> 0.0005
        effectiveZoom >= MapStyles.ZOOM_ACTIVE_JOB -> 0.001
        else -> 0.002
    }

    // Calculate visible lat/lng range (approximate)
    val halfH = h / 2f
    val halfW = w / 2f

    // Use center +/- some range based on zoom
    val latRange = 0.02 / (1 shl (zoom.toInt() - 14).coerceAtLeast(0))
    val lngRange = 0.03 / (1 shl (zoom.toInt() - 14).coerceAtLeast(0))

    val startLat = ((centerLat - latRange) / gridSpacingDeg).toInt() * gridSpacingDeg
    val startLng = ((centerLng - lngRange) / gridSpacingDeg).toInt() * gridSpacingDeg

    // Horizontal grid lines (constant latitude)
    var lat = startLat
    while (lat <= centerLat + latRange) {
        val left = latLngToPixel(lat, centerLng - lngRange, centerLat, centerLng, zoom, w, h)
        val right = latLngToPixel(lat, centerLng + lngRange, centerLat, centerLng, zoom, w, h)
        drawLine(
            color = gridColor,
            start = Offset(left.x, left.y),
            end = Offset(right.x, right.y),
            strokeWidth = 0.5f,
        )
        lat += gridSpacingDeg
    }

    // Vertical grid lines (constant longitude)
    var lng = startLng
    while (lng <= centerLng + lngRange) {
        val top = latLngToPixel(centerLat + latRange, lng, centerLat, centerLng, zoom, w, h)
        val bottom = latLngToPixel(centerLat - latRange, lng, centerLat, centerLng, zoom, w, h)
        drawLine(
            color = gridColor,
            start = Offset(top.x, top.y),
            end = Offset(bottom.x, bottom.y),
            strokeWidth = 0.5f,
        )
        lng += gridSpacingDeg
    }
}

/** Draw a context pin marker (red teardrop shape). */
private fun DrawScope.drawContextPin(position: Offset) {
    val pinHeight = 21f  // Fibonacci: 21
    val pinRadius = 8f   // Fibonacci: 8

    // Shadow
    drawCircle(
        color = MapStyles.PIN_SHADOW,
        radius = 5f,
        center = Offset(position.x + 1f, position.y + 1f),
    )

    // Pin shaft (triangle/teardrop)
    val pinPath = Path().apply {
        moveTo(position.x, position.y)
        lineTo(position.x - pinRadius, position.y - pinHeight * 0.6f)
        // Arc approximation using quadratic bezier
        quadraticTo(
            position.x - pinRadius, position.y - pinHeight,
            position.x, position.y - pinHeight,
        )
        quadraticTo(
            position.x + pinRadius, position.y - pinHeight,
            position.x + pinRadius, position.y - pinHeight * 0.6f,
        )
        close()
    }
    drawPath(path = pinPath, color = MapStyles.PIN_RED)
    drawPath(path = pinPath, color = MapStyles.PIN_RED.copy(alpha = 0.6f), style = Stroke(width = 1f))

    // White inner dot on pin head
    drawCircle(
        color = androidx.compose.ui.graphics.Color.White,
        radius = 3f,
        center = Offset(position.x, position.y - pinHeight * 0.78f),
    )
}

/** Format a coordinate value to 4 decimal places (no String.format). */
private fun formatCoord(value: Double): String {
    val sign = if (value < 0) "-" else ""
    val absVal = abs(value)
    val intPart = absVal.toInt()
    val fracPart = ((absVal - intPart) * 10000).toInt()
    val fracStr = fracPart.toString().padStart(4, '0')
    return "$sign$intPart.$fracStr"
}

@Composable
fun GpsDot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MapGpsBlue.copy(alpha = 0.3f))
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(MapGpsBlue)
        )
    }
}

/**
 * Simple Mercator projection converting lat/lng to pixel coordinates.
 */
fun latLngToPixel(
    lat: Double, lng: Double,
    centerLat: Double, centerLng: Double,
    zoom: Double, canvasWidth: Float, canvasHeight: Float,
): Offset {
    val scale = (256.0 * (1 shl zoom.toInt())).toFloat()
    val worldX = ((lng + 180.0) / 360.0 * scale).toFloat()
    val worldY = ((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
    val centerWorldX = ((centerLng + 180.0) / 360.0 * scale).toFloat()
    val centerWorldY = ((1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
    return Offset(
        x = canvasWidth / 2f + (worldX - centerWorldX),
        y = canvasHeight / 2f + (worldY - centerWorldY),
    )
}

/**
 * Inverse Mercator: convert pixel Y to latitude.
 */
fun pixelToLat(
    pixelY: Float,
    centerLat: Double,
    zoom: Double,
    canvasHeight: Float,
): Double {
    val scale = (256.0 * (1 shl zoom.toInt()))
    val centerWorldY = (1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale
    val worldY = centerWorldY + (pixelY - canvasHeight / 2.0)
    val n = PI - 2.0 * PI * worldY / scale
    return 180.0 / PI * kotlin.math.atan(0.5 * (kotlin.math.exp(n) - kotlin.math.exp(-n)))
}

/**
 * Inverse Mercator: convert pixel X to longitude.
 */
fun pixelToLng(
    pixelX: Float,
    centerLng: Double,
    zoom: Double,
    canvasWidth: Float,
): Double {
    val scale = (256.0 * (1 shl zoom.toInt()))
    val centerWorldX = (centerLng + 180.0) / 360.0 * scale
    val worldX = centerWorldX + (pixelX - canvasWidth / 2.0)
    return worldX / scale * 360.0 - 180.0
}
