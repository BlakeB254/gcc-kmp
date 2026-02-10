package com.gcc.admin.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.sp
import com.gcc.shared.model.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun AdminMap(
    modifier: Modifier = Modifier,
    viewModel: AdminMapViewModel = koinInject(),
    initialZoom: Double = ZOOM_CITY,
    initialCenterLat: Double = CHICAGO_CENTER_LAT,
    initialCenterLng: Double = CHICAGO_CENTER_LNG,
    enableGestures: Boolean = true,
    onBlockClick: ((Block) -> Unit)? = null,
    onAreaClick: ((CommunityArea) -> Unit)? = null,
    onSpotClick: ((CommunityReport) -> Unit)? = null,
    onBountyClick: ((Bounty) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    // --- Gesture state ---
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }
    var gestureScale by remember { mutableFloatStateOf(1f) }

    val effectiveZoom by remember {
        derivedStateOf {
            initialZoom + kotlin.math.log2(gestureScale.toDouble().coerceAtLeast(0.01))
        }
    }

    // --- Tile loading state ---
    var tileLoadCount by remember { mutableIntStateOf(0) }
    val zoomInt = initialZoom.toInt()
    val centerTileX = remember(initialCenterLng, zoomInt) { MapProjection.tileX(initialCenterLng, zoomInt) }
    val centerTileY = remember(initialCenterLat, zoomInt) { MapProjection.tileY(initialCenterLat, zoomInt) }

    LaunchedEffect(centerTileX, centerTileY, zoomInt) {
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

    // --- Label style ---
    val labelScaleFactor by remember {
        derivedStateOf {
            (effectiveZoom / ZOOM_NEIGHBORHOOD).toFloat().coerceIn(0.6f, 2.0f)
        }
    }

    val areaLabelStyle by remember(labelScaleFactor) {
        derivedStateOf {
            TextStyle(
                fontSize = (9f * labelScaleFactor).sp,
                color = MapCommunityLabel,
                textAlign = TextAlign.Center,
                lineHeight = (11f * labelScaleFactor).sp,
            )
        }
    }

    Box(modifier = modifier.fillMaxWidth().background(MapDarkBackground)) {
        val gestureModifier = if (enableGestures) {
            Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        gestureScale = (gestureScale * gestureZoom).coerceIn(0.3f, 8f)
                        gestureOffsetX += pan.x
                        gestureOffsetY += pan.y
                    }
                }
                .pointerInput(onBlockClick, onAreaClick, onSpotClick, onBountyClick) {
                    detectTapGestures(
                        onDoubleTap = { _ ->
                            // Cycle zoom presets
                            val zoomPresets = listOf(1.0f, 2.0f, 4.0f, 8.0f)
                            val nextScale = zoomPresets.firstOrNull { it > gestureScale + 0.1f }
                                ?: zoomPresets.first()
                            gestureScale = nextScale
                        },
                        onTap = { tapOffset ->
                            val totalScale = gestureScale
                            val canvasX = (tapOffset.x - gestureOffsetX - size.width / 2f * (totalScale - 1f)) / totalScale
                            val canvasY = (tapOffset.y - gestureOffsetY - size.height / 2f * (totalScale - 1f)) / totalScale
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()

                            // Hit-test blocks first
                            if (onBlockClick != null) {
                                val hitBlock = state.blocks.find { block ->
                                    val boundary = block.boundary ?: return@find false
                                    if (boundary.coordinates.isEmpty()) return@find false
                                    val ring = boundary.coordinates[0]
                                    val bbox = geoBBox(ring, initialCenterLat, initialCenterLng, initialZoom, w, h)
                                    canvasX in bbox.first.x..bbox.second.x && canvasY in bbox.first.y..bbox.second.y
                                }
                                if (hitBlock != null) {
                                    viewModel.selectBlock(hitBlock.id)
                                    onBlockClick(hitBlock)
                                    return@detectTapGestures
                                }
                            }

                            // Hit-test spots (distance < 12px)
                            if (onSpotClick != null) {
                                val hitSpot = state.spots.find { spot ->
                                    val px = MapProjection.latLngToPixel(
                                        spot.coordinates.lat, spot.coordinates.lng,
                                        initialCenterLat, initialCenterLng, initialZoom, w, h,
                                    )
                                    val dx = canvasX - px.x
                                    val dy = canvasY - px.y
                                    dx * dx + dy * dy < 144f // 12px radius
                                }
                                if (hitSpot != null) {
                                    viewModel.selectSpot(hitSpot.id)
                                    onSpotClick(hitSpot)
                                    return@detectTapGestures
                                }
                            }

                            // Hit-test bounties (distance < 16px)
                            if (onBountyClick != null) {
                                val hitBounty = state.bounties.find { bounty ->
                                    val px = MapProjection.latLngToPixel(
                                        bounty.locationLat, bounty.locationLng,
                                        initialCenterLat, initialCenterLng, initialZoom, w, h,
                                    )
                                    val dx = canvasX - px.x
                                    val dy = canvasY - px.y
                                    dx * dx + dy * dy < 256f // 16px radius
                                }
                                if (hitBounty != null) {
                                    viewModel.selectBounty(hitBounty.id)
                                    onBountyClick(hitBounty)
                                    return@detectTapGestures
                                }
                            }

                            // Hit-test community areas
                            if (onAreaClick != null) {
                                val hitArea = chicagoCommunityAreas.find { area ->
                                    val topLeft = MapProjection.latLngToPixel(
                                        area.bounds.north, area.bounds.west,
                                        initialCenterLat, initialCenterLng, initialZoom, w, h,
                                    )
                                    val bottomRight = MapProjection.latLngToPixel(
                                        area.bounds.south, area.bounds.east,
                                        initialCenterLat, initialCenterLng, initialZoom, w, h,
                                    )
                                    val minX = min(topLeft.x, bottomRight.x)
                                    val maxX = max(topLeft.x, bottomRight.x)
                                    val minY = min(topLeft.y, bottomRight.y)
                                    val maxY = max(topLeft.y, bottomRight.y)
                                    canvasX in minX..maxX && canvasY in minY..maxY
                                }
                                if (hitArea != null) {
                                    onAreaClick(hitArea)
                                    return@detectTapGestures
                                }
                            }

                            // Tap on empty space clears selection
                            viewModel.clearSelection()
                        },
                    )
                }
        } else Modifier

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(gestureModifier)
                .graphicsLayer {
                    scaleX = gestureScale
                    scaleY = gestureScale
                    translationX = gestureOffsetX
                    translationY = gestureOffsetY
                }
        ) {
            val w = size.width
            val h = size.height

            // Subscribe to tile recomposition
            @Suppress("UNUSED_EXPRESSION")
            tileLoadCount

            // 1. Dark background fill
            drawRect(color = MapDarkBackground, size = size)

            // 2. CartoDB dark tiles
            for (dx in -3..3) {
                for (dy in -3..3) {
                    val tx = centerTileX + dx
                    val ty = centerTileY + dy
                    val tile = AdminTileLoader.getCachedTile(zoomInt, tx, ty)
                    if (tile != null) {
                        val tileTopLat = MapProjection.tileLat(ty, zoomInt)
                        val tileLeftLng = MapProjection.tileLng(tx, zoomInt)
                        val tileBottomLat = MapProjection.tileLat(ty + 1, zoomInt)
                        val tileRightLng = MapProjection.tileLng(tx + 1, zoomInt)

                        val topLeft = MapProjection.latLngToPixel(
                            tileTopLat, tileLeftLng, initialCenterLat, initialCenterLng, initialZoom, w, h,
                        )
                        val bottomRight = MapProjection.latLngToPixel(
                            tileBottomLat, tileRightLng, initialCenterLat, initialCenterLng, initialZoom, w, h,
                        )

                        drawImage(
                            image = tile,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(tile.width, tile.height),
                            dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                            dstSize = IntSize(
                                (bottomRight.x - topLeft.x).toInt().coerceAtLeast(1),
                                (bottomRight.y - topLeft.y).toInt().coerceAtLeast(1),
                            ),
                        )
                    }
                }
            }

            // Viewport bounds for culling
            val visNorth = MapProjection.pixelToLat(0f, initialCenterLat, initialZoom, h)
            val visSouth = MapProjection.pixelToLat(h, initialCenterLat, initialZoom, h)
            val visWest = MapProjection.pixelToLng(0f, initialCenterLng, initialZoom, w)
            val visEast = MapProjection.pixelToLng(w, initialCenterLng, initialZoom, w)

            // 3. Community area polygons
            if (state.showCommunityAreas) {
                for (area in chicagoCommunityAreas) {
                    if (area.bounds.south > visNorth || area.bounds.north < visSouth ||
                        area.bounds.west > visEast || area.bounds.east < visWest) continue

                    drawCommunityAreaPolygon(
                        area, initialCenterLat, initialCenterLng, initialZoom, w, h,
                    )

                    // Area label
                    val areaTopLeft = MapProjection.latLngToPixel(
                        area.bounds.north, area.bounds.west, initialCenterLat, initialCenterLng, initialZoom, w, h,
                    )
                    val areaBottomRight = MapProjection.latLngToPixel(
                        area.bounds.south, area.bounds.east, initialCenterLat, initialCenterLng, initialZoom, w, h,
                    )
                    val rectWidth = abs(areaBottomRight.x - areaTopLeft.x)
                    if (rectWidth >= 40f) {
                        val rectTopLeft = Offset(
                            min(areaTopLeft.x, areaBottomRight.x),
                            min(areaTopLeft.y, areaBottomRight.y),
                        )
                        val rectSize = Size(rectWidth, abs(areaBottomRight.y - areaTopLeft.y))
                        val textResult = textMeasurer.measure(area.displayName, areaLabelStyle)
                        drawText(
                            textResult,
                            topLeft = Offset(
                                rectTopLeft.x + (rectSize.width - textResult.size.width) / 2f,
                                rectTopLeft.y + (rectSize.height - textResult.size.height) / 2f,
                            ),
                        )
                    }
                }
            }

            // 4. Service area boundaries (dashed)
            for (sa in state.serviceAreas) {
                val boundary = sa.boundary ?: continue
                if (boundary.coordinates.isEmpty()) continue
                val ring = boundary.coordinates[0]
                drawGeoPolygon(
                    ring = ring,
                    fillColor = MapServiceAreaFill,
                    strokeColor = MapServiceAreaBorder,
                    strokeWidth = 2f,
                    dashed = true,
                    centerLat = initialCenterLat,
                    centerLng = initialCenterLng,
                    zoom = initialZoom,
                    w = w, h = h,
                )
            }

            // 5. Block polygons at ZOOM_NEIGHBORHOOD+
            if (state.showBlocks && effectiveZoom >= ZOOM_NEIGHBORHOOD) {
                for (block in state.blocks) {
                    val boundary = block.boundary ?: continue
                    if (boundary.coordinates.isEmpty()) continue
                    val ring = boundary.coordinates[0]

                    val fillColor = when (block.status) {
                        BlockStatus.AVAILABLE -> MapBlockAvailable
                        BlockStatus.FUNDED -> MapBlockFunded
                        BlockStatus.IN_PROGRESS -> MapBlockInProgress
                        BlockStatus.COMPLETED -> MapBlockCompleted
                    }

                    val isSelected = state.selectedBlockId == block.id
                    drawGeoPolygon(
                        ring = ring,
                        fillColor = fillColor,
                        strokeColor = if (isSelected) MapSelectionOutline else MapBlockBorder,
                        strokeWidth = if (isSelected) 3f else 1.5f,
                        dashed = false,
                        centerLat = initialCenterLat,
                        centerLng = initialCenterLng,
                        zoom = initialZoom,
                        w = w, h = h,
                    )

                    // Block labels when zoomed enough
                    if (effectiveZoom >= ZOOM_ROUTE) {
                        drawBlockLabel(
                            block, ring, initialCenterLat, initialCenterLng, initialZoom,
                            w, h, textMeasurer, labelScaleFactor,
                        )
                    }
                }
            }

            // 6. Route paths at ZOOM_ROUTE+
            if (state.showRoutes && effectiveZoom >= ZOOM_ROUTE) {
                for (route in state.routes) {
                    val geometry = route.geometry ?: continue
                    if (geometry.coordinates.size < 2) continue

                    val path = Path()
                    geometry.coordinates.forEachIndexed { index, point ->
                        val lat = point[1]
                        val lng = point[0]
                        val px = MapProjection.latLngToPixel(
                            lat, lng, initialCenterLat, initialCenterLng, initialZoom, w, h,
                        )
                        if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
                    }
                    drawPath(
                        path = path,
                        color = MapRouteLine,
                        style = Stroke(
                            width = 3f,
                            pathEffect = PathEffect.cornerPathEffect(8f),
                        ),
                    )

                    // Checkpoint dots
                    for (checkpoint in route.checkpoints) {
                        val cpPx = MapProjection.latLngToPixel(
                            checkpoint.coordinates.lat, checkpoint.coordinates.lng,
                            initialCenterLat, initialCenterLng, initialZoom, w, h,
                        )
                        drawCircle(color = MapCheckpointDot, radius = 4f, center = cpPx)
                    }
                }
            }

            // 7. Spot markers
            if (state.showSpots) {
                for (spot in state.spots) {
                    val px = MapProjection.latLngToPixel(
                        spot.coordinates.lat, spot.coordinates.lng,
                        initialCenterLat, initialCenterLng, initialZoom, w, h,
                    )
                    // Radius circle
                    drawCircle(color = MapSpotRadius, radius = 18f, center = px)
                    // Marker dot
                    val isSelected = state.selectedSpotId == spot.id
                    drawCircle(
                        color = if (isSelected) MapSelectionOutline else MapSpotMarker,
                        radius = 6f,
                        center = px,
                    )
                    if (isSelected) {
                        drawCircle(
                            color = MapSelectionOutline,
                            radius = 10f,
                            center = px,
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }

            // 8. Bounty zones
            if (state.showBounties) {
                for (bounty in state.bounties) {
                    val px = MapProjection.latLngToPixel(
                        bounty.locationLat, bounty.locationLng,
                        initialCenterLat, initialCenterLng, initialZoom, w, h,
                    )
                    // Approximate pixels per meter at this zoom
                    val metersPerPixel = 156543.03 * kotlin.math.cos(
                        bounty.locationLat * kotlin.math.PI / 180.0
                    ) / (1 shl initialZoom.toInt()).toDouble()
                    val radiusPx = (bounty.radiusMeters / metersPerPixel).toFloat().coerceIn(8f, 200f)

                    val zoneColor = if (bounty.claimedBy != null) MapBountyClaimed else MapBountyZone
                    drawCircle(color = zoneColor.copy(alpha = 0.15f), radius = radiusPx, center = px)
                    drawCircle(
                        color = zoneColor,
                        radius = radiusPx,
                        center = px,
                        style = Stroke(width = 2f),
                    )
                    drawCircle(color = zoneColor, radius = 5f, center = px)

                    val isSelected = state.selectedBountyId == bounty.id
                    if (isSelected) {
                        drawCircle(
                            color = MapSelectionOutline,
                            radius = radiusPx + 4f,
                            center = px,
                            style = Stroke(width = 2.5f),
                        )
                    }
                }
            }

            // 9. Selection highlight glow (for blocks with boundary)
            state.selectedBlockId?.let { selectedId ->
                val block = state.blocks.find { it.id == selectedId }
                val boundary = block?.boundary
                if (boundary != null && boundary.coordinates.isNotEmpty()) {
                    val ring = boundary.coordinates[0]
                    drawGeoPolygon(
                        ring = ring,
                        fillColor = Color.Transparent,
                        strokeColor = MapSelectionOutline,
                        strokeWidth = 4f,
                        dashed = false,
                        centerLat = initialCenterLat,
                        centerLng = initialCenterLng,
                        zoom = initialZoom,
                        w = w, h = h,
                    )
                }
            }

            // 10. Grid lines at ZOOM_STREET+
            if (state.showGrid && effectiveZoom >= ZOOM_STREET) {
                drawAdminGridLines(initialCenterLat, initialCenterLng, initialZoom, w, h, effectiveZoom)
            }
        }

        // Overlay content
        content()
    }
}

// --- Drawing helpers ---

private fun DrawScope.drawCommunityAreaPolygon(
    area: CommunityArea,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
) {
    if (area.polygon.isNotEmpty()) {
        val path = Path()
        area.polygon.forEachIndexed { index, (lat, lng) ->
            val point = MapProjection.latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(path = path, color = MapCommunityFill)
        drawPath(path = path, color = MapCommunityStroke, style = Stroke(width = 1f))
    } else {
        val topLeft = MapProjection.latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
        val bottomRight = MapProjection.latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
        val rectTopLeft = Offset(min(topLeft.x, bottomRight.x), min(topLeft.y, bottomRight.y))
        val rectSize = Size(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y))
        drawRect(color = MapCommunityFill, topLeft = rectTopLeft, size = rectSize)
        drawRect(color = MapCommunityStroke, topLeft = rectTopLeft, size = rectSize, style = Stroke(width = 1f))
    }
}

private fun DrawScope.drawGeoPolygon(
    ring: List<Point>,
    fillColor: Color,
    strokeColor: Color,
    strokeWidth: Float,
    dashed: Boolean,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
) {
    if (ring.isEmpty()) return
    val path = Path()
    ring.forEachIndexed { index, point ->
        val lat = point[1]
        val lng = point[0]
        val px = MapProjection.latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
    }
    path.close()
    drawPath(path = path, color = fillColor)
    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(
            width = strokeWidth,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f) else null,
        ),
    )
}

private fun DrawScope.drawBlockLabel(
    block: Block,
    ring: List<Point>,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelScaleFactor: Float,
) {
    // Compute bounding box of block polygon
    var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
    var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
    for (point in ring) {
        val px = MapProjection.latLngToPixel(point[1], point[0], centerLat, centerLng, zoom, w, h)
        minX = min(minX, px.x); maxX = max(maxX, px.x)
        minY = min(minY, px.y); maxY = max(maxY, px.y)
    }
    val blockWidth = maxX - minX
    if (blockWidth < 50f) return

    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f

    // Block code label
    val nameStyle = TextStyle(
        fontSize = (8f * labelScaleFactor).sp,
        color = MapCommunityLabel,
        textAlign = TextAlign.Center,
    )
    val nameResult = textMeasurer.measure(block.code, nameStyle)
    drawText(nameResult, topLeft = Offset(cx - nameResult.size.width / 2f, cy - nameResult.size.height - 2f))

    // Funding progress
    val goal = block.fundingGoal ?: 0
    val current = block.fundingCurrent
    if (goal > 0) {
        val progressText = "\$${current / 100} / \$${goal / 100}"
        val progressStyle = TextStyle(
            fontSize = (7f * labelScaleFactor).sp,
            color = MapControlIcon.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        val progressResult = textMeasurer.measure(progressText, progressStyle)
        val progX = cx - progressResult.size.width / 2f
        val progY = cy + 2f

        // Background pill
        drawRoundRect(
            color = MapDarkBackground.copy(alpha = 0.85f),
            topLeft = Offset(progX - 3f, progY - 1f),
            size = Size(progressResult.size.width + 6f, progressResult.size.height + 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f),
        )
        drawText(progressResult, topLeft = Offset(progX, progY))

        // Progress bar
        val barWidth = blockWidth * 0.6f
        val barHeight = 4f
        val barX = cx - barWidth / 2f
        val barY = progY + progressResult.size.height + 4f
        val progress = (current.toFloat() / goal).coerceIn(0f, 1f)

        drawRoundRect(
            color = MapBlockBorder.copy(alpha = 0.2f),
            topLeft = Offset(barX, barY),
            size = Size(barWidth, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
        )
        if (progress > 0f) {
            val filledColor = when (block.status) {
                BlockStatus.AVAILABLE -> MapBlockAvailable
                BlockStatus.FUNDED -> MapBlockFunded
                BlockStatus.IN_PROGRESS -> MapBlockInProgress
                BlockStatus.COMPLETED -> MapBlockCompleted
            }
            drawRoundRect(
                color = filledColor.copy(alpha = 0.9f),
                topLeft = Offset(barX, barY),
                size = Size(barWidth * progress, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
            )
        }
    }
}

private fun DrawScope.drawAdminGridLines(
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
    effectiveZoom: Double,
) {
    val gridSpacing = if (effectiveZoom >= 20.0) 0.0005 else 0.001
    val latRange = 0.02 / (1 shl (zoom.toInt() - 14).coerceAtLeast(0))
    val lngRange = 0.03 / (1 shl (zoom.toInt() - 14).coerceAtLeast(0))

    val startLat = ((centerLat - latRange) / gridSpacing).toInt() * gridSpacing
    val startLng = ((centerLng - lngRange) / gridSpacing).toInt() * gridSpacing

    var lat = startLat
    while (lat <= centerLat + latRange) {
        val left = MapProjection.latLngToPixel(lat, centerLng - lngRange, centerLat, centerLng, zoom, w, h)
        val right = MapProjection.latLngToPixel(lat, centerLng + lngRange, centerLat, centerLng, zoom, w, h)
        drawLine(color = MapGridLine, start = left, end = right, strokeWidth = 0.5f)
        lat += gridSpacing
    }

    var lng = startLng
    while (lng <= centerLng + lngRange) {
        val top = MapProjection.latLngToPixel(centerLat + latRange, lng, centerLat, centerLng, zoom, w, h)
        val bottom = MapProjection.latLngToPixel(centerLat - latRange, lng, centerLat, centerLng, zoom, w, h)
        drawLine(color = MapGridLine, start = top, end = bottom, strokeWidth = 0.5f)
        lng += gridSpacing
    }
}

/** Compute AABB for a GeoJSON ring in pixel space. */
private fun geoBBox(
    ring: List<Point>,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    w: Float,
    h: Float,
): Pair<Offset, Offset> {
    var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
    var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
    for (point in ring) {
        val px = MapProjection.latLngToPixel(point[1], point[0], centerLat, centerLng, zoom, w, h)
        minX = min(minX, px.x); maxX = max(maxX, px.x)
        minY = min(minY, px.y); maxY = max(maxY, px.y)
    }
    return Offset(minX, minY) to Offset(maxX, maxY)
}
