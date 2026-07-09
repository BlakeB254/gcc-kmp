package com.gcc.app.ui.map

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.BlockStatus
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared map drawing functions used by all platform implementations
 * (desktop, Android, iOS). These operate purely on Compose Canvas APIs
 * and data from commonMain — no platform-specific code.
 */

/** Fill city blocks with a subtle warm color to distinguish from streets. */
internal fun DrawScope.drawCityBlocks(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
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
            val blockSize = Size(abs(br.x - tl.x), abs(br.y - tl.y))
            if (blockSize.width > 1f && blockSize.height > 1f) {
                drawRect(color = MapBlockFill, topLeft = topLeft, size = blockSize)
            }
        }
    }
}

/** Draw parks as green-tinted rectangles. */
internal fun DrawScope.drawParks(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    for (park in chicagoParks) {
        val tl = latLngToPixel(park.north, park.west, centerLat, centerLng, zoom, w, h)
        val br = latLngToPixel(park.south, park.east, centerLat, centerLng, zoom, w, h)
        val topLeft = Offset(min(tl.x, br.x), min(tl.y, br.y))
        val parkSize = Size(abs(br.x - tl.x), abs(br.y - tl.y))
        drawRoundRect(
            color = MapParkFill,
            topLeft = topLeft,
            size = parkSize,
            cornerRadius = CornerRadius(4f),
        )
        drawRoundRect(
            color = MapParkStroke,
            topLeft = topLeft,
            size = parkSize,
            cornerRadius = CornerRadius(4f),
            style = Stroke(width = 1f),
        )
    }
}

/** Draw the street grid with width based on street type and labels for arterials. */
internal fun DrawScope.drawStreetGrid(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    effectiveZoom: Double,
    textMeasurer: TextMeasurer,
) {
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

        drawLine(color = MapStreetOutline, start = start, end = end, strokeWidth = outlineWidth)
        drawLine(color = streetColor, start = start, end = end, strokeWidth = streetWidth)

        if (showLabels && street.type == StreetType.ARTERIAL) {
            val labelStyle = TextStyle(
                fontSize = (8f * (effectiveZoom / MapStyles.ZOOM_NEIGHBORHOOD).toFloat().coerceIn(0.7f, 1.5f)).sp,
                color = MapStreetLabel,
                textAlign = TextAlign.Center,
            )
            val labelResult = textMeasurer.measure(street.name, labelStyle)
            val midX = (start.x + end.x) / 2f - labelResult.size.width / 2f
            val midY = (start.y + end.y) / 2f - labelResult.size.height / 2f
            val bgPadH = 4f
            val bgPadV = 1f
            drawRoundRect(
                color = MapBackground.copy(alpha = 0.85f),
                topLeft = Offset(midX - bgPadH, midY - bgPadV),
                size = Size(
                    labelResult.size.width + bgPadH * 2,
                    labelResult.size.height + bgPadV * 2,
                ),
                cornerRadius = CornerRadius(3f),
            )
            drawText(labelResult, topLeft = Offset(midX, midY))
        }
    }
}

/** Draw fundable blocks as colored polygons with funding progress labels. */
internal fun DrawScope.drawFundableBlocks(
    blocks: List<MapBlock>,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    textMeasurer: TextMeasurer,
    labelScaleFactor: Float,
) {
    for (block in blocks) {
        if (block.polygon.isEmpty()) continue

        val path = Path()
        block.polygon.forEachIndexed { index, (lat, lng) ->
            val point = latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()

        val fillColor = when (block.status) {
            BlockStatus.AVAILABLE -> BlockAvailable
            BlockStatus.FUNDED -> BlockFunded
            BlockStatus.IN_PROGRESS -> BlockInProgress
            BlockStatus.COMPLETED -> BlockCompleted
        }

        drawPath(path = path, color = fillColor)
        drawPath(path = path, color = BlockBorder, style = Stroke(width = 1.5f))

        val first = block.polygon.first()
        val firstPx = latLngToPixel(first.first, first.second, centerLat, centerLng, zoom, w, h)
        var minX = firstPx.x; var maxX = firstPx.x
        var minY = firstPx.y; var maxY = firstPx.y
        for (i in 1 until block.polygon.size) {
            val (lat, lng) = block.polygon[i]
            val px = latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
            minX = min(minX, px.x); maxX = max(maxX, px.x)
            minY = min(minY, px.y); maxY = max(maxY, px.y)
        }
        val blockWidth = maxX - minX

        if (blockWidth >= 50f) {
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f

            val nameStyle = TextStyle(
                fontSize = (8f * labelScaleFactor).sp,
                color = MapAreaLabel,
                textAlign = TextAlign.Center,
            )
            val nameResult = textMeasurer.measure(block.code, nameStyle)
            drawText(
                nameResult,
                topLeft = Offset(cx - nameResult.size.width / 2f, cy - nameResult.size.height - 2f),
            )

            val goalDollars = block.fundingGoalCents / 100
            val currentDollars = block.fundingCurrentCents / 100
            val progressText = "$$currentDollars / $$goalDollars"
            val progressStyle = TextStyle(
                fontSize = (7f * labelScaleFactor).sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
            val progressResult = textMeasurer.measure(progressText, progressStyle)

            val bgPadH = 3f
            val bgPadV = 1f
            val progX = cx - progressResult.size.width / 2f
            val progY = cy + 2f
            drawRoundRect(
                color = MapBackground.copy(alpha = 0.85f),
                topLeft = Offset(progX - bgPadH, progY - bgPadV),
                size = Size(
                    progressResult.size.width + bgPadH * 2,
                    progressResult.size.height + bgPadV * 2,
                ),
                cornerRadius = CornerRadius(3f),
            )
            drawText(progressResult, topLeft = Offset(progX, progY))

            val barWidth = blockWidth * 0.6f
            val barHeight = 4f
            val barX = cx - barWidth / 2f
            val barY = progY + progressResult.size.height + 4f
            val progress = (block.fundingCurrentCents.toFloat() / block.fundingGoalCents).coerceIn(0f, 1f)

            drawRoundRect(
                color = BlockBorder.copy(alpha = 0.2f),
                topLeft = Offset(barX, barY),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f),
            )
            if (progress > 0f) {
                val filledColor = when (block.status) {
                    BlockStatus.AVAILABLE -> MapAreaBorder
                    BlockStatus.FUNDED -> GccGreen
                    BlockStatus.IN_PROGRESS -> GccBlue
                    BlockStatus.COMPLETED -> GccSky
                }
                drawRoundRect(
                    color = filledColor,
                    topLeft = Offset(barX, barY),
                    size = Size(barWidth * progress, barHeight),
                    cornerRadius = CornerRadius(2f),
                )
            }
        }
    }
}

/** Draw light gray grid lines when zoomed to route level or deeper. */
internal fun DrawScope.drawGridLines(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
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

/** Draw a community area as a polygon outline (no fill). */
internal fun DrawScope.drawPolygonArea(
    area: CommunityArea,
    isTapped: Boolean,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    val path = Path()
    area.polygon.forEachIndexed { index, (lat, lng) ->
        val point = latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()

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
            color = if (isTapped) MapCommunityBoundary.copy(alpha = 0.8f) else MapCommunityBoundary,
            style = Stroke(width = if (isTapped) 2.5f else MapStyles.COMMUNITY_AREA_STROKE_WIDTH),
        )
    }
}

/** Fallback: draw a community area as a rectangle outline (no fill). */
internal fun DrawScope.drawRectArea(
    area: CommunityArea,
    isTapped: Boolean,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    val topLeft = latLngToPixel(area.bounds.north, area.bounds.west, centerLat, centerLng, zoom, w, h)
    val bottomRight = latLngToPixel(area.bounds.south, area.bounds.east, centerLat, centerLng, zoom, w, h)
    val rectTopLeft = Offset(minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y))
    val rectSize = Size(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y))

    if (area.isServiceArea) {
        drawRect(
            color = MapServiceBoundary,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), 0f)),
        )
    } else {
        drawRect(
            color = if (isTapped) MapCommunityBoundary.copy(alpha = 0.8f) else MapCommunityBoundary,
            topLeft = rectTopLeft,
            size = rectSize,
            style = Stroke(width = if (isTapped) 2.5f else MapStyles.COMMUNITY_AREA_STROKE_WIDTH),
        )
    }
}

/**
 * Draw the Illinois state outline as a polygon path.
 *
 * Line width scales with effective zoom so the outline looks proportional
 * at City level (bold) and doesn't overwhelm at Area/Route level (thin).
 */
internal fun DrawScope.drawStateBoundary(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    effectiveZoom: Double = zoom,
) {
    // Fade out at high zoom (community areas take over)
    val alpha = ((MapStyles.ZOOM_AREA - effectiveZoom) / 3.0).coerceIn(0.0, 1.0).toFloat()
    if (alpha <= 0f) return

    val width = when {
        effectiveZoom <= MapStyles.ZOOM_CITY -> 2.5f
        effectiveZoom <= MapStyles.ZOOM_AREA -> 1.5f
        else -> 1.0f
    }

    val path = Path()
    illinoisOutline.forEachIndexed { index, (lat, lng) ->
        val point = latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(
        path = path,
        color = MapStateBoundary.copy(alpha = alpha),
        style = Stroke(width = width),
    )
}

/**
 * Draw the Chicago city outline as a polygon path.
 *
 * Visible from City to Area zoom; fades at Route level where
 * community area boundaries provide enough context.
 */
internal fun DrawScope.drawCityBoundary(
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    effectiveZoom: Double = zoom,
) {
    // Fade out at high zoom (community areas take over)
    val alpha = ((MapStyles.ZOOM_ROUTE - effectiveZoom) / 3.0).coerceIn(0.0, 1.0).toFloat()
    if (alpha <= 0f) return

    val width = when {
        effectiveZoom <= MapStyles.ZOOM_CITY -> 2.5f
        effectiveZoom <= MapStyles.ZOOM_AREA -> 2.0f
        else -> 1.5f
    }

    val path = Path()
    chicagoCityOutline.forEachIndexed { index, (lat, lng) ->
        val point = latLngToPixel(lat, lng, centerLat, centerLng, zoom, w, h)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(
        path = path,
        color = MapCityBoundary.copy(alpha = alpha),
        style = Stroke(width = width),
    )
}

/** Draw spot report markers: terrain-style thermal wash at far zoom, orange dots at close zoom. */
internal fun DrawScope.drawSpotMarkers(
    spots: List<com.gcc.shared.model.CommunityReport>,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
    effectiveZoom: Double,
) {
    if (spots.isEmpty()) return

    val filteredSpots = spots.filter { it.status != com.gcc.shared.model.CommunityReportStatus.RESOLVED }
    if (filteredSpots.isEmpty()) return

    if (effectiveZoom < MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM) {
        val baseAlpha = when {
            effectiveZoom < 7.0 -> 0.14f
            effectiveZoom < 8.0 -> 0.16f
            effectiveZoom < 10.0 -> 0.24f
            effectiveZoom < 12.0 -> 0.30f
            effectiveZoom < 13.0 -> 0.27f
            effectiveZoom < 14.0 -> 0.20f
            else -> {
                val fade = ((effectiveZoom - 14.0) / (MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM - 14.0)).toFloat().coerceIn(0f, 1f)
                0.08f * (1f - fade)
            }
        }
        drawRect(color = MapStyles.SPOT_SCRIM_COLOR.copy(alpha = (baseAlpha * 1.8f).coerceAtMost(0.55f)))
    }

    for (spot in filteredSpots) {
        val pos = latLngToPixel(spot.coordinates.lat, spot.coordinates.lng, centerLat, centerLng, zoom, w, h)
        // Skip if off screen
        if (pos.x < -50f || pos.x > w + 50f || pos.y < -50f || pos.y > h + 50f) continue

        if (effectiveZoom < MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM) {
            val growthT = ((effectiveZoom - MapStyles.SPOT_HEATMAP_MIN_ZOOM) / 10.0).toFloat().coerceIn(0f, 1f)
            // Fade the heat out over the last zoom unit before dots take over (z[DOTS-1, DOTS)),
            // so the blooms are full at overview and gone by the dots handoff.
            val fadeT = ((effectiveZoom - (MapStyles.SPOT_DOTS_MIN_ZOOM - 1.0)) / 1.0).toFloat().coerceIn(0f, 1f)
            val baseRadius = MapStyles.SPOT_HEATMAP_RADIUS_FAR +
                (MapStyles.SPOT_HEATMAP_RADIUS_MID - MapStyles.SPOT_HEATMAP_RADIUS_FAR) * growthT
            val radius = baseRadius * (1f - fadeT)
            val weight = canvasSpotHeatWeight(spot)
            val opacity = (0.44f + 0.11f * weight) * (1f - fadeT)
            if (opacity > 0f) {
                drawCircle(color = MapStyles.SPOT_HEAT_EDGE.copy(alpha = opacity * 0.5f), radius = radius * 2.0f, center = pos)
                drawCircle(color = MapStyles.SPOT_HEAT_LOW.copy(alpha = opacity * 0.66f), radius = radius * 1.16f, center = pos)
                drawCircle(color = MapStyles.SPOT_HEAT_MEDIUM.copy(alpha = opacity * 0.74f), radius = radius * 0.78f, center = pos)
                drawCircle(color = MapStyles.SPOT_HEAT_HIGH.copy(alpha = opacity * 0.7f), radius = radius * 0.48f, center = pos)
                if (weight > 1.4f) {
                    drawCircle(color = MapStyles.SPOT_HEAT_EXTREME.copy(alpha = opacity * 0.66f), radius = radius * 0.3f, center = pos)
                }
                if (weight > 2.4f) {
                    drawCircle(color = MapStyles.SPOT_HEAT_NEAR_PEAK.copy(alpha = opacity * 0.55f), radius = radius * 0.17f, center = pos)
                }
            }
        }

        if (effectiveZoom >= MapStyles.SPOT_DOTS_MIN_ZOOM) {
            // Close zoom: individual orange dots
            val t = ((effectiveZoom - MapStyles.SPOT_DOTS_MIN_ZOOM) / 3.0).toFloat().coerceIn(0f, 1f)
            val dotRadius = 2f + 4f * t
            drawCircle(
                color = MapStyles.SPOT_MARKER_COLOR,
                radius = dotRadius,
                center = pos,
            )
            drawCircle(
                color = Color.White,
                radius = dotRadius,
                center = pos,
                style = Stroke(width = 1f),
            )
        }
    }
}

private fun canvasSpotHeatWeight(spot: com.gcc.shared.model.CommunityReport): Float {
    val statusWeight = when (spot.status) {
        com.gcc.shared.model.CommunityReportStatus.VERIFIED -> 1.45f
        com.gcc.shared.model.CommunityReportStatus.ACKNOWLEDGED -> 1.25f
        com.gcc.shared.model.CommunityReportStatus.PENDING -> 1f
        else -> 0.8f
    }
    val bountyBoost = if (spot.bountyId != null) 0.4f else 0f
    val verificationBoost = if (spot.needsVerification) 0.25f else 0f
    val rewardBoost = spot.observerRewardPoints.coerceIn(0, 25) / 100f
    val radiusBoost = (((spot.affectedRadiusMeters - 20.0) / 180.0).coerceIn(0.0, 0.35)).toFloat()
    return (statusWeight + bountyBoost + verificationBoost + rewardBoost + radiusBoost).coerceIn(0.65f, 2.4f)
}

/** Draw a green circle node at the given canvas position for route evidence photos. */
internal fun DrawScope.drawPhotoStar(cx: Float, cy: Float, radius: Float = 11f) {
    // White border
    drawCircle(color = Color.White, radius = radius, center = Offset(cx, cy))
    // Green fill (matching waypoint nodes)
    drawCircle(color = Color(0xFF22C55E), radius = radius - 2f, center = Offset(cx, cy))
    drawCircle(color = Color.White, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2f))
}

/** Draw a 5-pointed gold star at the given canvas position for display image locations. */
internal fun DrawScope.drawImageLocationStar(cx: Float, cy: Float, outerRadius: Float = 14f) {
    val innerRadius = outerRadius * 0.45f
    val starColor = Color(0xFFFFB300) // Amber gold
    val path = Path()
    val pi = 3.14159265358979
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerRadius else innerRadius
        // Start from top (-PI/2 rotation)
        val angle = (pi / 5.0) * i - pi / 2.0
        val x = cx + (r * cos(angle)).toFloat()
        val y = cy + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    // White shadow/outline
    drawPath(path, color = Color.White, style = Stroke(width = 3.5f))
    // Fill with gold
    drawPath(path, color = starColor)
    // Darker stroke for definition
    drawPath(path, color = Color(0xFFF57F17), style = Stroke(width = 1.2f))
}

/** Draw a semi-transparent route corridor polygon. */
internal fun DrawScope.drawRoutePolygon(
    polygon: List<com.gcc.shared.model.Coordinates>,
    centerLat: Double, centerLng: Double, zoom: Double, w: Float, h: Float,
) {
    if (polygon.size < 3) return
    val path = Path()
    polygon.forEachIndexed { i, coord ->
        val px = latLngToPixel(coord.lat, coord.lng, centerLat, centerLng, zoom, w, h)
        if (i == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
    }
    path.close()
    drawPath(path, color = MapRouteGreen.copy(alpha = 0.15f))
    drawPath(path, color = MapRouteGreen.copy(alpha = 0.4f), style = Stroke(width = 2f))
}

/** Draw a context pin marker (red teardrop shape). */
internal fun DrawScope.drawContextPin(position: Offset) {
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
        color = Color.White,
        radius = 3f,
        center = Offset(position.x, position.y - pinHeight * 0.78f),
    )
}
