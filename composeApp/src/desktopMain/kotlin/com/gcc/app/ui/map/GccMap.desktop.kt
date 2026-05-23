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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import com.gcc.shared.model.AffectedBlock
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

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
    bounties: List<com.gcc.shared.model.Bounty>,
    spots: List<com.gcc.shared.model.CommunityReport>,
    onAreaTap: ((CommunityArea) -> Unit)?,
    onRouteTap: ((routeIndex: Int) -> Unit)?,
    onSpotTap: ((spotId: Int) -> Unit)?,
    onBountyTap: ((bountyId: Int) -> Unit)?,
    onContextPin: ((lat: Double, lng: Double) -> Unit)?,
    showBuildings: Boolean,
    showGridLines: Boolean,
    showControls: Boolean,
    statusBarPadding: Boolean,
    zoomPresets: List<ZoomPreset>,
    cameraBounds: AreaBounds?,
    mapMode: MapMode,
    checkpointPhotos: List<Pair<Coordinates, String>>,
    imageStars: List<Coordinates>,
    routePolygons: List<Pair<Int, List<Coordinates>>>,
    affectedBlocks: List<AffectedBlock>,
    onAffectedBlockTap: ((blockId: Int) -> Unit)?,
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
    val doubleTapScales = listOf(1.0f, 2.0f, 4.0f, 8.0f)

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
                            val nextScale = doubleTapScales.firstOrNull { it > currentScale + 0.1f }
                                ?: doubleTapScales.first()
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

            // --- Geographic boundaries (drawn below community areas) ---
            drawStateBoundary(centerLat, centerLng, zoom, w, h, effectiveZoom)
            drawCityBoundary(centerLat, centerLng, zoom, w, h, effectiveZoom)

            // --- Grid lines at street level ---
            if (showGridLines && effectiveZoom >= MapStyles.ZOOM_ROUTE) {
                drawGridLines(centerLat, centerLng, zoom, w, h, effectiveZoom)
            }

            // --- Draw each community area (with viewport culling) ---
            // Compute visible geographic bounds for culling
            val visNorth = pixelToLat(0f, centerLat, zoom, h)
            val visSouth = pixelToLat(h, centerLat, zoom, h)
            val visWest = pixelToLng(0f, centerLng, zoom, w)
            val visEast = pixelToLng(w, centerLng, zoom, w)

            for (area in chicagoCommunityAreas) {
                // Viewport culling: skip areas whose bounds don't intersect the visible region
                if (area.bounds.south > visNorth || area.bounds.north < visSouth ||
                    area.bounds.west > visEast || area.bounds.east < visWest) continue

                val isTapped = area == tappedArea
                val usePolygon = area.polygon.isNotEmpty()

                if (usePolygon) {
                    drawPolygonArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                } else {
                    drawRectArea(area, isTapped, centerLat, centerLng, zoom, w, h)
                }

                // Area label (zoom-scaled) — only draw if area is large enough on screen
                val areaTopLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
                val areaBottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
                val rectTopLeft = Offset(minOf(areaTopLeft.x, areaBottomRight.x), minOf(areaTopLeft.y, areaBottomRight.y))
                val rectSize = androidx.compose.ui.geometry.Size(
                    abs(areaBottomRight.x - areaTopLeft.x), abs(areaBottomRight.y - areaTopLeft.y)
                )

                // Only show labels if the area is at least 40px wide on screen
                if (rectSize.width >= 40f) {
                    val style = if (area.isServiceArea) serviceAreaLabelStyle else areaLabelStyle
                    val textResult = textMeasurer.measure(area.displayName, style)
                    val textX = rectTopLeft.x + (rectSize.width - textResult.size.width) / 2f
                    val textY = rectTopLeft.y + (rectSize.height - textResult.size.height) / 2f
                    drawText(textResult, topLeft = Offset(textX, textY))
                }
            }

            // --- Draw fundable blocks at route zoom (15+) ---
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

            // --- Draw route corridor polygons (buffered fill behind route lines) ---
            for ((_, polygon) in routePolygons) {
                drawRoutePolygon(polygon, centerLat, centerLng, zoom, w, h)
            }

            // --- Draw route path lines (3-layer: glow → casing → core) ---
            for (routeCoords in routeCoordinates) {
                if (routeCoords.size < 2) continue
                // Close the loop
                val closed = if (routeCoords.first().lat == routeCoords.last().lat &&
                    routeCoords.first().lng == routeCoords.last().lng) routeCoords
                else routeCoords + routeCoords.first()

                val path = Path()
                closed.forEachIndexed { index, coord ->
                    val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }

                // Layer 1: Glow
                drawPath(
                    path = path,
                    color = MapStyles.ROUTE_GLOW_COLOR.copy(alpha = MapStyles.ROUTE_GLOW_OPACITY),
                    style = Stroke(width = MapStyles.ROUTE_GLOW_WIDTH, pathEffect = PathEffect.cornerPathEffect(8f)),
                )

                // Layer 2: Casing (dark border)
                drawPath(
                    path = path,
                    color = MapStyles.ROUTE_CASING_COLOR,
                    style = Stroke(width = MapStyles.ROUTE_CASING_WIDTH, pathEffect = PathEffect.cornerPathEffect(8f)),
                )

                // Layer 3: Core line (animated or static)
                if (animateRoutes) {
                    drawPath(
                        path = path,
                        color = GccGreen,
                        style = Stroke(
                            width = MapStyles.ROUTE_LINE_WIDTH,
                            pathEffect = PathEffect.chainPathEffect(
                                outer = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), phase = routeDashOffset),
                                inner = PathEffect.cornerPathEffect(8f),
                            ),
                        ),
                    )
                } else {
                    drawPath(
                        path = path,
                        color = GccGreen,
                        style = Stroke(width = MapStyles.ROUTE_LINE_WIDTH, pathEffect = PathEffect.cornerPathEffect(8f)),
                    )
                }

                // Checkpoint markers with start/end distinction
                routeCoords.forEachIndexed { index, coord ->
                    val point = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                    val isStart = index == 0
                    val isEnd = index == routeCoords.lastIndex
                    val outerR = if (isStart || isEnd) MapStyles.CHECKPOINT_START_END_OUTER else MapStyles.CHECKPOINT_OUTER_RADIUS
                    val innerR = if (isStart || isEnd) MapStyles.CHECKPOINT_START_END_INNER else MapStyles.CHECKPOINT_INNER_RADIUS
                    val fillColor = when {
                        isStart -> MapStyles.CHECKPOINT_START_COLOR
                        isEnd -> MapStyles.CHECKPOINT_END_COLOR
                        else -> GccGreen
                    }
                    // White outer ring with border
                    drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = outerR, center = point)
                    drawCircle(color = GccGreenDark, radius = outerR, center = point, style = Stroke(width = 1.5f))
                    // Colored inner fill
                    drawCircle(color = fillColor, radius = innerR, center = point)
                }
            }

            // --- Spot report markers (heatmap wash + dots) ---
            if (spots.isNotEmpty()) {
                drawSpotMarkers(spots, centerLat, centerLng, zoom, w, h, effectiveZoom)
            }

            // --- Photo star markers (gold dots at checkpoints with photos) ---
            for ((coord, _) in checkpointPhotos) {
                val pos = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
                if (pos.x > -20f && pos.x < w + 20f && pos.y > -20f && pos.y < h + 20f) {
                    drawPhotoStar(pos.x, pos.y)
                }
            }

            // --- Image location star markers (gold 5-pointed stars) ---
            for (starCoord in imageStars) {
                val pos = latLngToPixel(starCoord.lat, starCoord.lng, centerLat, centerLng, zoom, w, h)
                if (pos.x > -20f && pos.x < w + 20f && pos.y > -20f && pos.y < h + 20f) {
                    drawImageLocationStar(pos.x, pos.y)
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

        if (showControls) {
            MapControls(
                currentZoom = effectiveZoom,
                presets = zoomPresets,
                onZoomIn = {
                    val targetScale = (gestureScale * 1.5f).coerceAtMost(8f)
                    val ratio = targetScale / gestureScale
                    coroutineScope.launch {
                        animatedScale.animateTo(ratio, animationSpec = tween(300, easing = FastOutSlowInEasing))
                        gestureScale *= animatedScale.value
                        animatedScale.snapTo(1f)
                    }
                },
                onZoomOut = {
                    val targetScale = (gestureScale / 1.5f).coerceAtLeast(0.3f)
                    val ratio = targetScale / gestureScale
                    coroutineScope.launch {
                        animatedScale.animateTo(ratio, animationSpec = tween(300, easing = FastOutSlowInEasing))
                        gestureScale *= animatedScale.value
                        animatedScale.snapTo(1f)
                    }
                },
                onPresetSelect = { targetZoom ->
                    val targetScale = 2.0.pow(targetZoom - zoom).toFloat()
                    val ratio = targetScale / gestureScale
                    coroutineScope.launch {
                        animatedScale.animateTo(ratio, animationSpec = tween(500, easing = FastOutSlowInEasing))
                        gestureScale *= animatedScale.value
                        animatedScale.snapTo(1f)
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            ScaleBar(
                zoom = effectiveZoom,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

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
