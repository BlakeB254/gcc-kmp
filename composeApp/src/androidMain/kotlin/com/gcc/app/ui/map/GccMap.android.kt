package com.gcc.app.ui.map

// TODO: Replace with MapLibre Compose implementation for real map tiles (requires Kotlin 2.2+)

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.abs

/**
 * Android actual implementation of GccMap — Canvas fallback until MapLibre is available.
 */
@Composable
actual fun GccMap(
    modifier: Modifier,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    currentLocation: Coordinates?,
    gpsHeading: Float?,
    enableGestures: Boolean,
    routeCoordinates: List<List<Coordinates>>,
    animateRoutes: Boolean,
    bountyLocations: List<Coordinates>,
    onAreaTap: ((CommunityArea) -> Unit)?,
    onContextPin: ((lat: Double, lng: Double) -> Unit)?,
    showBuildings: Boolean,
    showGridLines: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()

    // --- Gesture state ---
    val animatedScale = remember { Animatable(1f) }
    val animOffsetX = remember { Animatable(0f) }
    val animOffsetY = remember { Animatable(0f) }

    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }
    var gestureScale by remember { mutableFloatStateOf(1f) }

    val effectiveOffsetX by remember { derivedStateOf { gestureOffsetX + animOffsetX.value } }
    val effectiveOffsetY by remember { derivedStateOf { gestureOffsetY + animOffsetY.value } }
    val effectiveScale by remember { derivedStateOf { gestureScale * animatedScale.value } }

    var tappedArea by remember { mutableStateOf<CommunityArea?>(null) }
    var contextPin by remember { mutableStateOf<ContextPin?>(null) }

    val effectiveZoom by remember(zoom) {
        derivedStateOf {
            zoom + kotlin.math.log2(effectiveScale.toDouble().coerceAtLeast(0.01))
        }
    }

    // --- Animations ---
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

    val routeDashOffset = if (animateRoutes) rememberRouteDashOffset() else 0f
    val bountyPulse = if (bountyLocations.isNotEmpty()) rememberBountyPulse() else null

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

    val zoomPresets = listOf(1.0f, 2.0f, 4.0f, 8.0f)

    Box(modifier = modifier.fillMaxWidth().background(MapBackground)) {
        val gestureModifier = if (enableGestures) {
            Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
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
                            val currentScale = gestureScale * animatedScale.value
                            val nextScale = zoomPresets.firstOrNull { it > currentScale + 0.1f }
                                ?: zoomPresets.first()
                            val targetAnimScale = nextScale / gestureScale
                            coroutineScope.launch {
                                animatedScale.animateTo(
                                    targetValue = targetAnimScale,
                                    animationSpec = tween(377, easing = FastOutSlowInEasing),
                                )
                                gestureScale *= animatedScale.value
                                gestureScale = gestureScale.coerceIn(0.3f, 8f)
                                animatedScale.snapTo(1f)
                            }
                        },
                        onLongPress = { tapOffset ->
                            val totalScale = gestureScale * animatedScale.value
                            val canvasX = (tapOffset.x - effectiveOffsetX - size.width / 2f * (totalScale - 1f)) / totalScale
                            val canvasY = (tapOffset.y - effectiveOffsetY - size.height / 2f * (totalScale - 1f)) / totalScale
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()

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

            if (showGridLines && effectiveZoom >= MapStyles.ZOOM_ROUTE) {
                drawGridLines(centerLat, centerLng, zoom, w, h, effectiveZoom)
            }

            for (area in chicagoCommunityAreas) {
                val isTapped = area == tappedArea
                val usePolygon = area.polygon.isNotEmpty()

                if (usePolygon) {
                    drawPolygonArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                } else {
                    drawRectArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                }

                val style = if (area.isServiceArea) serviceAreaLabelStyle else areaLabelStyle
                val textResult = textMeasurer.measure(area.displayName, style)

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
                        for (coord in routeCoords) {
                            val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                            drawCircle(color = GccGreenDark, radius = 4f, center = point)
                        }
                    }
                }
            }

            if (bountyPulse != null) {
                for (bountyLoc in bountyLocations) {
                    val pos = latLngToPixel(bountyLoc.lat, bountyLoc.lng, centerLat, centerLng, zoom, w, h)
                    drawBountyZonePulse(
                        center = pos,
                        pulseState = bountyPulse,
                    )
                }
            }

            contextPin?.let { pin ->
                drawContextPin(Offset(pin.canvasX, pin.canvasY))
            }

            if (currentLocation != null) {
                val gpsPos = latLngToPixel(currentLocation.lat, currentLocation.lng, centerLat, centerLng, zoom, w, h)

                if (gpsHeading != null) {
                    drawGpsHeadingCone(
                        center = gpsPos,
                        headingDeg = gpsHeading,
                    )
                }

                drawCircle(
                    color = MapGpsBlue.copy(alpha = pulseAlpha),
                    radius = pulseRadius,
                    center = gpsPos,
                )
                drawCircle(color = MapGpsBlue, radius = 6f, center = gpsPos)
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White,
                    radius = 6f,
                    center = gpsPos,
                    style = Stroke(width = 2f),
                )
            }
        }

        content()

        tappedArea?.let { area ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 13.dp)
                    .offset(y = tooltipOffsetY)
                    .graphicsLayer { alpha = tooltipAlpha },
                shape = RoundedCornerShape(8.dp),
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

    val topLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
    val bottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
    val cx = (topLeft.x + bottomRight.x) / 2f
    val cy = (topLeft.y + bottomRight.y) / 2f
    val radius = maxOf(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y)) / 2f

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

private fun DrawScope.drawGridLines(
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
    effectiveZoom: Double,
) {
    val gridColor = MapStyles.GRID_COLOR
    val gridSpacingDeg = when {
        effectiveZoom >= MapStyles.ZOOM_STREET -> 0.0005
        effectiveZoom >= MapStyles.ZOOM_ACTIVE_JOB -> 0.001
        else -> 0.002
    }

    val latRange = 0.02 / (1 shl (zoom.toInt() - 14).coerceAtLeast(0))
    val lngRange = 0.03 / (1 shl (zoom.toInt() - 14).coerceAtLeast(0))

    val startLat = ((centerLat - latRange) / gridSpacingDeg).toInt() * gridSpacingDeg
    val startLng = ((centerLng - lngRange) / gridSpacingDeg).toInt() * gridSpacingDeg

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

private fun DrawScope.drawContextPin(position: Offset) {
    val pinHeight = 21f
    val pinRadius = 8f

    drawCircle(
        color = MapStyles.PIN_SHADOW,
        radius = 5f,
        center = Offset(position.x + 1f, position.y + 1f),
    )

    val pinPath = Path().apply {
        moveTo(position.x, position.y)
        lineTo(position.x - pinRadius, position.y - pinHeight * 0.6f)
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

    drawCircle(
        color = androidx.compose.ui.graphics.Color.White,
        radius = 3f,
        center = Offset(position.x, position.y - pinHeight * 0.78f),
    )
}
