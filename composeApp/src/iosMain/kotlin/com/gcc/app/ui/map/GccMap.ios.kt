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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
 * iOS actual implementation of GccMap — Canvas fallback until MapLibre is available.
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

            // --- Base map: procedural street/block/park rendering ---
            drawCityBlocks(centerLat, centerLng, zoom, w, h)
            drawParks(centerLat, centerLng, zoom, w, h)
            drawStreetGrid(centerLat, centerLng, zoom, w, h, effectiveZoom, textMeasurer)

            // --- Grid lines at route level ---
            if (showGridLines && effectiveZoom >= MapStyles.ZOOM_ROUTE) {
                drawGridLines(centerLat, centerLng, zoom, w, h, effectiveZoom)
            }

            // --- Community areas with viewport culling ---
            val visNorth = pixelToLat(0f, centerLat, zoom, h)
            val visSouth = pixelToLat(h, centerLat, zoom, h)
            val visWest = pixelToLng(0f, centerLng, zoom, w)
            val visEast = pixelToLng(w, centerLng, zoom, w)

            for (area in chicagoCommunityAreas) {
                if (area.bounds.south > visNorth || area.bounds.north < visSouth ||
                    area.bounds.west > visEast || area.bounds.east < visWest) continue

                val isTapped = area == tappedArea
                val usePolygon = area.polygon.isNotEmpty()

                if (usePolygon) {
                    drawPolygonArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                } else {
                    drawRectArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                }

                val areaTopLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
                val areaBottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
                val rectTopLeft = Offset(minOf(areaTopLeft.x, areaBottomRight.x), minOf(areaTopLeft.y, areaBottomRight.y))
                val rectSize = androidx.compose.ui.geometry.Size(
                    abs(areaBottomRight.x - areaTopLeft.x), abs(areaBottomRight.y - areaTopLeft.y)
                )

                if (rectSize.width >= 40f) {
                    val style = if (area.isServiceArea) serviceAreaLabelStyle else areaLabelStyle
                    val textResult = textMeasurer.measure(area.displayName, style)
                    val textX = rectTopLeft.x + (rectSize.width - textResult.size.width) / 2f
                    val textY = rectTopLeft.y + (rectSize.height - textResult.size.height) / 2f
                    drawText(textResult, topLeft = Offset(textX, textY))
                }
            }

            // --- Fundable blocks at route zoom ---
            if (effectiveZoom >= MapStyles.ZOOM_ROUTE) {
                drawFundableBlocks(
                    blocks = northLawndaleBlocks,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    w = w,
                    h = h,
                    textMeasurer = textMeasurer,
                    labelScaleFactor = labelScaleFactor,
                )
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

            // --- Route paths ---
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

            // --- Bounty zone pulses ---
            if (bountyPulse != null) {
                for (bountyLoc in bountyLocations) {
                    val pos = latLngToPixel(bountyLoc.lat, bountyLoc.lng, centerLat, centerLng, zoom, w, h)
                    drawBountyZonePulse(
                        center = pos,
                        pulseState = bountyPulse,
                    )
                }
            }

            // --- Context pin ---
            contextPin?.let { pin ->
                drawContextPin(Offset(pin.canvasX, pin.canvasY))
            }

            // --- GPS dot + heading cone + pulse ---
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
