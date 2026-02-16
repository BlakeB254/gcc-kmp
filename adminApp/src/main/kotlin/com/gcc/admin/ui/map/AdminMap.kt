package com.gcc.admin.ui.map

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.gcc.admin.ui.theme.*
import com.gcc.shared.model.*
import kotlinx.coroutines.launch
import kotlin.math.*

// ── Admin Map Color Constants ──────────────────────────────────────────────

private val MapBackground = Color(0xFFF5F5F4)
private val BlockAvailableFill = Color(0x3322C55E)      // green 20%
private val BlockFundedFill = Color(0x333B82F6)          // blue 20%
private val BlockInProgressFill = Color(0x33F59E0B)      // amber 20%
private val BlockCompletedFill = Color(0x3314B8A6)       // teal 20%
private val BlockBorderColor = Color(0xFF6B7280)         // gray-500
private val BlockSelectedBorder = Color(0xFFEF4444)      // red-500
private val BlockSelectedFill = Color(0x40EF4444)        // red 25%
private val RouteLineColor = Color(0xFF22C55E)           // GCC green
private val RouteLineShadow = Color(0x4422C55E)          // green 27%
private val CheckpointColor = Color(0xFFEF4444)          // red
private val CheckpointOutline = Color(0xFFFFFFFF)
private val ServiceAreaBorder = Color(0xFF8B5CF6)        // purple-500
private val ServiceAreaFill = Color(0x1A8B5CF6)          // purple 10%

// ── Mercator Projection Helpers ────────────────────────────────────────────

/**
 * Simple Mercator projection converting lat/lng to pixel coordinates
 * relative to the canvas center. Matches the composeApp implementation.
 */
fun adminLatLngToPixel(
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

/** Inverse Mercator: convert pixel Y to latitude. */
fun adminPixelToLat(
    pixelY: Float,
    centerLat: Double,
    zoom: Double,
    canvasHeight: Float,
): Double {
    val scale = (256.0 * (1 shl zoom.toInt()))
    val centerWorldY = (1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale
    val worldY = centerWorldY + (pixelY - canvasHeight / 2.0)
    val n = PI - 2.0 * PI * worldY / scale
    return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
}

/** Inverse Mercator: convert pixel X to longitude. */
fun adminPixelToLng(
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

// ── Main Admin Map Composable ──────────────────────────────────────────────

/**
 * Admin map composable using Canvas-based rendering with OSM tiles.
 * Supports block selection, route path display, service area overlays,
 * and checkpoint placement for route editing.
 *
 * @param centerLat Map center latitude
 * @param centerLng Map center longitude
 * @param zoom Base zoom level
 * @param blocks List of blocks to render as colored polygons
 * @param routes List of routes (selected route's checkpoints are drawn as a path)
 * @param serviceAreas List of service areas to render as boundary overlays
 * @param selectedBlockId Currently selected block ID (highlighted with red border)
 * @param selectedRouteId Currently selected route ID (its path is drawn)
 * @param placedCheckpoints List of (lat, lng) pairs for checkpoint placement mode
 * @param isLoading Whether data is currently loading
 * @param onBlockClick Callback when a block is clicked
 * @param onMapClick Callback when the map is clicked (for checkpoint placement)
 * @param modifier Composable modifier
 */
@Composable
fun AdminMap(
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    blocks: List<Block> = emptyList(),
    routes: List<Route> = emptyList(),
    serviceAreas: List<ServiceArea> = emptyList(),
    selectedBlockId: Int? = null,
    selectedRouteId: Int? = null,
    placedCheckpoints: List<Pair<Double, Double>> = emptyList(),
    isLoading: Boolean = false,
    onBlockClick: ((Int) -> Unit)? = null,
    onMapClick: ((lat: Double, lng: Double) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()

    // --- Gesture state ---
    val animatedScale = remember { Animatable(1f) }
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }
    var gestureScale by remember { mutableFloatStateOf(1f) }

    val effectiveOffsetX by remember { derivedStateOf { gestureOffsetX } }
    val effectiveOffsetY by remember { derivedStateOf { gestureOffsetY } }
    val effectiveScale by remember { derivedStateOf { gestureScale * animatedScale.value } }

    val effectiveZoom by remember(zoom) {
        derivedStateOf {
            zoom + log2(effectiveScale.toDouble().coerceAtLeast(0.01))
        }
    }

    // --- Tile loading state ---
    var tileLoadCount by remember { mutableIntStateOf(0) }
    val zoomInt = zoom.toInt()
    val centerTileX = remember(centerLng, zoomInt) { AdminTileLoader.tileX(centerLng, zoomInt) }
    val centerTileY = remember(centerLat, zoomInt) { AdminTileLoader.tileY(centerLat, zoomInt) }

    // Trigger tile loading when center or zoom changes
    LaunchedEffect(centerTileX, centerTileY, zoomInt) {
        // Load a 5x5 grid of tiles around the center
        for (dx in -2..2) {
            for (dy in -2..2) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                if (!AdminTileLoader.isCached(zoomInt, tx, ty)) {
                    launch {
                        AdminTileLoader.loadTile(zoomInt, tx, ty)
                        tileLoadCount++
                    }
                }
            }
        }
    }

    // --- Checkpoint pulse animation ---
    val pulseTransition = rememberInfiniteTransition(label = "checkpointPulse")
    val checkpointPulseRadius by pulseTransition.animateFloat(
        initialValue = 8f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseOut), RepeatMode.Restart),
        label = "checkpointPulseRadius",
    )
    val checkpointPulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseOut), RepeatMode.Restart),
        label = "checkpointPulseAlpha",
    )

    // Double-tap zoom presets
    val doubleTapScales = listOf(1.0f, 2.0f, 4.0f, 8.0f)

    // Pre-compute block hit areas for click detection
    val blockBounds = remember(blocks) {
        blocks.mapNotNull { block ->
            val boundary = block.boundary ?: return@mapNotNull null
            if (boundary.coordinates.isEmpty() || boundary.coordinates.first().isEmpty()) return@mapNotNull null
            val ring = boundary.coordinates.first()
            val lats = ring.map { it[1] }
            val lngs = ring.map { it[0] }
            block.id to BlockBounds(
                north = lats.max(),
                south = lats.min(),
                east = lngs.max(),
                west = lngs.min(),
            )
        }
    }

    Box(modifier = modifier.fillMaxWidth().background(MapBackground)) {
        val gestureModifier = Modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    gestureScale = (gestureScale * gestureZoom).coerceIn(0.3f, 8f)
                    gestureOffsetX += pan.x
                    gestureOffsetY += pan.y
                }
            }
            .pointerInput(onBlockClick, onMapClick) {
                detectTapGestures(
                    onDoubleTap = { _ ->
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
                    onTap = { tapOffset ->
                        val totalScale = gestureScale * animatedScale.value
                        val canvasX = (tapOffset.x - effectiveOffsetX - size.width / 2f * (totalScale - 1f)) / totalScale
                        val canvasY = (tapOffset.y - effectiveOffsetY - size.height / 2f * (totalScale - 1f)) / totalScale
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()

                        val tapLat = adminPixelToLat(canvasY, centerLat, zoom, h)
                        val tapLng = adminPixelToLng(canvasX, centerLng, zoom, w)

                        // Check block hit testing
                        var hitBlockId: Int? = null
                        for ((blockId, bounds) in blockBounds) {
                            if (tapLat in bounds.south..bounds.north &&
                                tapLng in bounds.west..bounds.east
                            ) {
                                hitBlockId = blockId
                                break
                            }
                        }

                        if (hitBlockId != null) {
                            onBlockClick?.invoke(hitBlockId)
                        } else {
                            onMapClick?.invoke(tapLat, tapLng)
                        }
                    },
                )
            }

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

            // Subscribe to tile recomposition
            @Suppress("UNUSED_EXPRESSION")
            tileLoadCount

            // ── Layer 1: OSM Raster Tiles ──────────────────────────────────

            for (dx in -3..3) {
                for (dy in -3..3) {
                    val tx = centerTileX + dx
                    val ty = centerTileY + dy
                    val tile = AdminTileLoader.getCachedTile(zoomInt, tx, ty)
                    if (tile != null) {
                        val tileTopLat = AdminTileLoader.tileLat(ty, zoomInt)
                        val tileLeftLng = AdminTileLoader.tileLng(tx, zoomInt)
                        val tileBottomLat = AdminTileLoader.tileLat(ty + 1, zoomInt)
                        val tileRightLng = AdminTileLoader.tileLng(tx + 1, zoomInt)

                        val topLeft = adminLatLngToPixel(tileTopLat, tileLeftLng, centerLat, centerLng, zoom, w, h)
                        val bottomRight = adminLatLngToPixel(tileBottomLat, tileRightLng, centerLat, centerLng, zoom, w, h)

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

            // ── Layer 2: Service Area Boundaries ───────────────────────────

            for (area in serviceAreas) {
                drawServiceAreaOverlay(
                    area = area,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    w = w,
                    h = h,
                    textMeasurer = textMeasurer,
                )
            }

            // ── Layer 3: Block Overlays ────────────────────────────────────

            for (block in blocks) {
                val isSelected = block.id == selectedBlockId
                drawBlockOverlay(
                    block = block,
                    isSelected = isSelected,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    w = w,
                    h = h,
                    textMeasurer = textMeasurer,
                    effectiveZoom = effectiveZoom,
                )
            }

            // ── Layer 4: Route Path (selected route) ───────────────────────

            val selectedRoute = routes.find { it.id == selectedRouteId }
            if (selectedRoute != null) {
                drawRoutePath(
                    route = selectedRoute,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoom,
                    w = w,
                    h = h,
                )
            }

            // ── Layer 5: Placed Checkpoint Markers ─────────────────────────

            if (placedCheckpoints.isNotEmpty()) {
                // Draw connecting line between placed checkpoints
                if (placedCheckpoints.size >= 2) {
                    val checkpointPath = Path()
                    placedCheckpoints.forEachIndexed { index, (lat, lng) ->
                        val point = adminLatLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
                        if (index == 0) checkpointPath.moveTo(point.x, point.y)
                        else checkpointPath.lineTo(point.x, point.y)
                    }
                    // Shadow line
                    drawPath(
                        path = checkpointPath,
                        color = CheckpointColor.copy(alpha = 0.3f),
                        style = Stroke(
                            width = 5f,
                            pathEffect = PathEffect.cornerPathEffect(8f),
                        ),
                    )
                    // Main line
                    drawPath(
                        path = checkpointPath,
                        color = CheckpointColor,
                        style = Stroke(
                            width = 3f,
                            pathEffect = PathEffect.cornerPathEffect(8f),
                        ),
                    )
                }

                // Draw each checkpoint marker with pulse
                placedCheckpoints.forEachIndexed { index, (lat, lng) ->
                    val pos = adminLatLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)

                    // Pulse ring
                    drawCircle(
                        color = CheckpointColor.copy(alpha = checkpointPulseAlpha),
                        radius = checkpointPulseRadius,
                        center = pos,
                    )
                    // Outer circle
                    drawCircle(
                        color = CheckpointOutline,
                        radius = 9f,
                        center = pos,
                    )
                    // Inner filled circle
                    drawCircle(
                        color = CheckpointColor,
                        radius = 7f,
                        center = pos,
                    )
                    // Sequence number label
                    val label = "${index + 1}"
                    val labelStyle = TextStyle(
                        fontSize = 9.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    val labelResult = textMeasurer.measure(label, labelStyle)
                    drawText(
                        labelResult,
                        topLeft = Offset(
                            pos.x - labelResult.size.width / 2f,
                            pos.y - labelResult.size.height / 2f,
                        ),
                    )
                }
            }
        }

        // ── Loading Indicator ──────────────────────────────────────────────

        if (isLoading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = AdminSurface.copy(alpha = 0.9f),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AdminPrimary,
                    )
                    Text(
                        "Loading map data...",
                        style = MaterialTheme.typography.bodySmall,
                        color = AdminOnSurface,
                    )
                }
            }
        }

        // ── Zoom Level Indicator ───────────────────────────────────────────

        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            shape = RoundedCornerShape(4.dp),
            color = Color.White.copy(alpha = 0.85f),
            shadowElevation = 2.dp,
        ) {
            Text(
                "Zoom: ${"%.1f".format(effectiveZoom)}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF374151),
            )
        }
    }
}

// ── Block Bounds Helper ────────────────────────────────────────────────────

private data class BlockBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

// ── Drawing Extension Functions ────────────────────────────────────────────

/**
 * Draw a block as a colored polygon overlay. Selected blocks get a brighter
 * fill and thicker red border.
 */
private fun DrawScope.drawBlockOverlay(
    block: Block,
    isSelected: Boolean,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    effectiveZoom: Double,
) {
    val boundary = block.boundary ?: return
    if (boundary.coordinates.isEmpty() || boundary.coordinates.first().isEmpty()) return

    val ring = boundary.coordinates.first()
    val path = Path()
    ring.forEachIndexed { index, point ->
        // GeoJSON format: [lng, lat]
        val lng = point[0]
        val lat = point[1]
        val px = adminLatLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
    }
    path.close()

    // Fill color based on status
    val fillColor = if (isSelected) {
        BlockSelectedFill
    } else {
        when (block.status) {
            BlockStatus.AVAILABLE -> BlockAvailableFill
            BlockStatus.FUNDED -> BlockFundedFill
            BlockStatus.IN_PROGRESS -> BlockInProgressFill
            BlockStatus.COMPLETED -> BlockCompletedFill
        }
    }

    drawPath(path = path, color = fillColor)

    // Border
    val borderColor = if (isSelected) BlockSelectedBorder else BlockBorderColor
    val borderWidth = if (isSelected) 3f else 1.5f
    drawPath(
        path = path,
        color = borderColor,
        style = Stroke(width = borderWidth),
    )

    // Block code label (only at higher zoom levels and if block is large enough on screen)
    if (effectiveZoom >= 15.0) {
        val points = ring.map { p ->
            adminLatLngToPixel(p[1], p[0], centerLat, centerLng, zoom, w, h)
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val blockWidth = maxX - minX

        if (blockWidth >= 40f) {
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f

            val scaleFactor = (effectiveZoom / 15.0).toFloat().coerceIn(0.7f, 1.5f)
            val labelStyle = TextStyle(
                fontSize = (9f * scaleFactor).sp,
                color = if (isSelected) BlockSelectedBorder else Color(0xFF374151),
                textAlign = TextAlign.Center,
            )
            val labelResult = textMeasurer.measure(block.code, labelStyle)
            val bgPad = 3f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(
                    cx - labelResult.size.width / 2f - bgPad,
                    cy - labelResult.size.height / 2f - bgPad,
                ),
                size = Size(
                    labelResult.size.width + bgPad * 2,
                    labelResult.size.height + bgPad * 2,
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
            )
            drawText(
                labelResult,
                topLeft = Offset(
                    cx - labelResult.size.width / 2f,
                    cy - labelResult.size.height / 2f,
                ),
            )
        }
    }
}

/**
 * Draw a route's checkpoints connected by a green line path,
 * with small dots at each checkpoint.
 */
private fun DrawScope.drawRoutePath(
    route: Route,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    val checkpoints = route.checkpoints
    if (checkpoints.isEmpty()) {
        // Fall back to geometry LineString if available
        val geometry = route.geometry
        if (geometry != null && geometry.coordinates.size >= 2) {
            val path = Path()
            geometry.coordinates.forEachIndexed { index, point ->
                val px = adminLatLngToPixel(point[1], point[0], centerLat, centerLng, zoom, w, h)
                if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
            }
            // Shadow
            drawPath(
                path = path,
                color = RouteLineShadow,
                style = Stroke(width = 7f, pathEffect = PathEffect.cornerPathEffect(8f)),
            )
            // Main line
            drawPath(
                path = path,
                color = RouteLineColor,
                style = Stroke(width = 4f, pathEffect = PathEffect.cornerPathEffect(8f)),
            )
            // Dots at each coordinate
            for (point in geometry.coordinates) {
                val px = adminLatLngToPixel(point[1], point[0], centerLat, centerLng, zoom, w, h)
                drawCircle(color = Color.White, radius = 5f, center = px)
                drawCircle(color = RouteLineColor, radius = 3.5f, center = px)
            }
        }
        return
    }

    // Draw from checkpoint coordinates
    if (checkpoints.size >= 2) {
        val sortedCheckpoints = checkpoints.sortedBy { it.sequenceOrder }
        val path = Path()
        sortedCheckpoints.forEachIndexed { index, cp ->
            val px = adminLatLngToPixel(cp.coordinates.lat, cp.coordinates.lng, centerLat, centerLng, zoom, w, h)
            if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
        }
        // Shadow
        drawPath(
            path = path,
            color = RouteLineShadow,
            style = Stroke(width = 7f, pathEffect = PathEffect.cornerPathEffect(8f)),
        )
        // Main line
        drawPath(
            path = path,
            color = RouteLineColor,
            style = Stroke(width = 4f, pathEffect = PathEffect.cornerPathEffect(8f)),
        )
    }

    // Checkpoint dots
    for (cp in checkpoints) {
        val px = adminLatLngToPixel(cp.coordinates.lat, cp.coordinates.lng, centerLat, centerLng, zoom, w, h)
        drawCircle(color = Color.White, radius = 6f, center = px)
        drawCircle(color = RouteLineColor, radius = 4f, center = px)
    }
}

/**
 * Draw a service area boundary as a dashed polygon outline with a subtle fill.
 * Includes a name label at the centroid.
 */
private fun DrawScope.drawServiceAreaOverlay(
    area: ServiceArea,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val boundary = area.boundary ?: return
    if (boundary.coordinates.isEmpty() || boundary.coordinates.first().isEmpty()) return

    val ring = boundary.coordinates.first()
    val path = Path()
    ring.forEachIndexed { index, point ->
        val lng = point[0]
        val lat = point[1]
        val px = adminLatLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
    }
    path.close()

    // Subtle fill
    drawPath(path = path, color = ServiceAreaFill)

    // Dashed border
    drawPath(
        path = path,
        color = ServiceAreaBorder,
        style = Stroke(
            width = 2.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f),
        ),
    )

    // Name label at centroid
    val points = ring.map { p ->
        adminLatLngToPixel(p[1], p[0], centerLat, centerLng, zoom, w, h)
    }
    val cx = points.map { it.x }.average().toFloat()
    val cy = points.map { it.y }.average().toFloat()

    val labelStyle = TextStyle(
        fontSize = 11.sp,
        color = ServiceAreaBorder,
        textAlign = TextAlign.Center,
    )
    val labelResult = textMeasurer.measure(area.name, labelStyle)
    val bgPad = 4f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.85f),
        topLeft = Offset(
            cx - labelResult.size.width / 2f - bgPad,
            cy - labelResult.size.height / 2f - bgPad,
        ),
        size = Size(
            labelResult.size.width + bgPad * 2,
            labelResult.size.height + bgPad * 2,
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
    )
    drawText(
        labelResult,
        topLeft = Offset(
            cx - labelResult.size.width / 2f,
            cy - labelResult.size.height / 2f,
        ),
    )
}
