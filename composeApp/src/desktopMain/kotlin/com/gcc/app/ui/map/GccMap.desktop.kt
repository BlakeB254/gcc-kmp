package com.gcc.app.ui.map

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.map.BuildingRenderer.drawBuildings
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Desktop actual implementation of GccMap using Canvas-based rendering.
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

    // --- Tile loading state ---
    var tileLoadCount by remember { mutableIntStateOf(0) }
    val zoomInt = zoom.toInt()
    val centerTileX = remember(centerLng, zoomInt) { TileLoader.tileX(centerLng, zoomInt) }
    val centerTileY = remember(centerLat, zoomInt) { TileLoader.tileY(centerLat, zoomInt) }
    val tilesLoaded = tileLoadCount > 0 // true once at least one tile has loaded

    // Trigger tile loading when center or zoom changes
    LaunchedEffect(centerTileX, centerTileY, zoomInt) {
        // Load a 5x5 grid of tiles around the center
        for (dx in -2..2) {
            for (dy in -2..2) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                if (!TileLoader.isCached(zoomInt, tx, ty)) {
                    launch {
                        TileLoader.loadTile(zoomInt, tx, ty)
                        tileLoadCount++
                    }
                }
            }
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

            // Access tileLoadCount to subscribe to recomposition on new tiles
            @Suppress("UNUSED_EXPRESSION")
            tileLoadCount

            // --- Raster map tiles (real OpenStreetMap imagery) ---
            var anyTilesDrawn = false
            for (dx in -3..3) {
                for (dy in -3..3) {
                    val tx = centerTileX + dx
                    val ty = centerTileY + dy
                    val tile = TileLoader.getCachedTile(zoomInt, tx, ty)
                    if (tile != null) {
                        anyTilesDrawn = true
                        val tileTopLat = TileLoader.tileLat(ty, zoomInt)
                        val tileLeftLng = TileLoader.tileLng(tx, zoomInt)
                        val tileBottomLat = TileLoader.tileLat(ty + 1, zoomInt)
                        val tileRightLng = TileLoader.tileLng(tx + 1, zoomInt)

                        val topLeft = latLngToPixel(tileTopLat, tileLeftLng, centerLat, centerLng, zoom, w, h)
                        val bottomRight = latLngToPixel(tileBottomLat, tileRightLng, centerLat, centerLng, zoom, w, h)

                        val dstX = topLeft.x.toInt()
                        val dstY = topLeft.y.toInt()
                        val dstW = (bottomRight.x - topLeft.x).toInt().coerceAtLeast(1)
                        val dstH = (bottomRight.y - topLeft.y).toInt().coerceAtLeast(1)

                        drawImage(
                            image = tile,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(tile.width, tile.height),
                            dstOffset = IntOffset(dstX, dstY),
                            dstSize = IntSize(dstW, dstH),
                        )
                    }
                }
            }

            // --- Fallback layers (only drawn when tiles haven't loaded yet) ---
            if (!anyTilesDrawn) {
                drawCityBlocks(centerLat, centerLng, zoom, w, h)
                drawParks(centerLat, centerLng, zoom, w, h)
                drawStreetGrid(centerLat, centerLng, zoom, w, h, effectiveZoom, textMeasurer)
            }

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

        // Overlay content
        content()

        // Tapped area tooltip with slide-up animation
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

/** Draw a context pin marker (red teardrop shape). */
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

// --- Realistic map rendering layers ---

/** Fill city blocks with a subtle warm color to distinguish from streets. */
private fun DrawScope.drawCityBlocks(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    // Draw block-fill rectangles between major streets in the North Lawndale area
    // This creates the visual separation between streets (white) and blocks (tan)
    val blockLatPairs = listOf(
        41.8750 to 41.8700, 41.8700 to 41.8665, 41.8665 to 41.8640,
        41.8640 to 41.8625, 41.8625 to 41.8605, 41.8605 to 41.8585,
        41.8585 to 41.8558, 41.8558 to 41.8535, 41.8535 to 41.8510,
        41.8510 to 41.8440,
    )
    val blockLngPairs = listOf(
        -87.7450 to -87.7370, -87.7370 to -87.7340, -87.7340 to -87.7310,
        -87.7310 to -87.7280, -87.7280 to -87.7260, -87.7260 to -87.7225,
        -87.7225 to -87.7210, -87.7210 to -87.7195, -87.7195 to -87.7180,
        -87.7180 to -87.7165, -87.7165 to -87.7135, -87.7135 to -87.7105,
        -87.7105 to -87.7075, -87.7075 to -87.7045, -87.7045 to -87.7000,
        -87.7000 to -87.6940, -87.6940 to -87.6860,
    )

    for ((latN, latS) in blockLatPairs) {
        for ((lngW, lngE) in blockLngPairs) {
            val tl = latLngToPixel(latN, lngW, centerLat, centerLng, zoom, w, h)
            val br = latLngToPixel(latS, lngE, centerLat, centerLng, zoom, w, h)
            val topLeft = Offset(min(tl.x, br.x), min(tl.y, br.y))
            val blockSize = androidx.compose.ui.geometry.Size(
                abs(br.x - tl.x), abs(br.y - tl.y),
            )
            if (blockSize.width > 1f && blockSize.height > 1f) {
                drawRect(
                    color = MapBlockFill,
                    topLeft = topLeft,
                    size = blockSize,
                )
            }
        }
    }
}

/** Draw parks as green-tinted rectangles. */
private fun DrawScope.drawParks(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    for (park in chicagoParks) {
        val tl = latLngToPixel(park.north, park.west, centerLat, centerLng, zoom, w, h)
        val br = latLngToPixel(park.south, park.east, centerLat, centerLng, zoom, w, h)
        val topLeft = Offset(min(tl.x, br.x), min(tl.y, br.y))
        val parkSize = androidx.compose.ui.geometry.Size(
            abs(br.x - tl.x), abs(br.y - tl.y),
        )
        drawRoundRect(
            color = MapParkFill,
            topLeft = topLeft,
            size = parkSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
        )
        drawRoundRect(
            color = MapParkStroke,
            topLeft = topLeft,
            size = parkSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
            style = Stroke(width = 1f),
        )
    }
}

/** Draw the street grid with width based on street type and labels for arterials. */
private fun DrawScope.drawStreetGrid(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    effectiveZoom: Double,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    // Only show collectors at zoom >= 13.5, arterials always visible at neighborhood zoom
    val showCollectors = effectiveZoom >= 13.5
    val showLabels = effectiveZoom >= 13.0

    for (street in chicagoStreets) {
        if (street.type == StreetType.COLLECTOR && !showCollectors) continue
        if (street.type == StreetType.LOCAL) continue

        val start = latLngToPixel(street.startLat, street.startLng, centerLat, centerLng, zoom, w, h)
        val end = latLngToPixel(street.endLat, street.endLng, centerLat, centerLng, zoom, w, h)

        val streetWidth = when (street.type) {
            StreetType.ARTERIAL -> 6f
            StreetType.COLLECTOR -> 3f
            StreetType.LOCAL -> 1.5f
        }
        val streetColor = when (street.type) {
            StreetType.ARTERIAL -> MapStreetArterial
            else -> MapStreetCollector
        }
        val outlineWidth = when (street.type) {
            StreetType.ARTERIAL -> 8f
            StreetType.COLLECTOR -> 4.5f
            StreetType.LOCAL -> 2f
        }

        // Street outline (subtle border effect)
        drawLine(
            color = MapStreetOutline,
            start = start,
            end = end,
            strokeWidth = outlineWidth,
        )
        // Street fill
        drawLine(
            color = streetColor,
            start = start,
            end = end,
            strokeWidth = streetWidth,
        )

        // Labels for arterials
        if (showLabels && street.type == StreetType.ARTERIAL) {
            val labelStyle = TextStyle(
                fontSize = (8f * (effectiveZoom / MapStyles.ZOOM_NEIGHBORHOOD).toFloat().coerceIn(0.7f, 1.5f)).sp,
                color = MapStreetLabel,
                textAlign = TextAlign.Center,
            )
            val labelResult = textMeasurer.measure(street.name, labelStyle)

            // Place label at midpoint of street
            val midX = (start.x + end.x) / 2f - labelResult.size.width / 2f
            val midY = (start.y + end.y) / 2f - labelResult.size.height / 2f

            // Background pill for readability
            val bgPadH = 4f
            val bgPadV = 1f
            drawRoundRect(
                color = MapBackground.copy(alpha = 0.85f),
                topLeft = Offset(midX - bgPadH, midY - bgPadV),
                size = androidx.compose.ui.geometry.Size(
                    labelResult.size.width + bgPadH * 2,
                    labelResult.size.height + bgPadV * 2,
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
            )

            drawText(labelResult, topLeft = Offset(midX, midY))
        }
    }
}
