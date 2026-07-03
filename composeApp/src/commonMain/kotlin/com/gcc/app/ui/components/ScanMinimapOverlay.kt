package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.theme.GccGreen
import com.gcc.shared.model.Coordinates
import kotlin.math.PI
import kotlin.math.cos

private val StaleAmber = Color(0xFFF59E0B)
private val FogGray = Color(0xFF6B7280)
private val TrashOrange = Color(0xFFF97316)

/**
 * Minimap overlay for the environment scan: draws (in map space, projected
 * around the map's fixed center) the freshness of the surrounding coverage
 * ledger, the zones this scan session has credited, and geo-tagged trash
 * items. Rendered in GccMap's content slot — the minimap has no gestures and
 * a fixed zoom, so a straight Mercator meters→pixels projection matches the
 * map underneath.
 *
 * Visual language (mirrors the AR fog): never-scanned = fog dots,
 * stale = amber tint, this session's credited zones = green fill,
 * trash items = orange dot.
 */
@Composable
fun ScanMinimapOverlay(
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    scanCellCenters: List<Pair<Double, Double>>,
    cellSizeMeters: Double,
    trashItems: List<Coordinates>,
    fogCellCenters: List<Pair<Double, Double>> = emptyList(),
    staleCellCenters: List<Pair<Double, Double>> = emptyList(),
    freshnessCellSizeMeters: Double = 10.0,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val lngScale = cos(centerLat * PI / 180.0)

        fun toOffset(lat: Double, lng: Double): Offset {
            val eastMeters = (lng - centerLng) * 111_320.0 * lngScale
            val northMeters = (lat - centerLat) * 111_320.0
            return Offset(
                size.width / 2f + metersToPixels(eastMeters, centerLat, zoom),
                size.height / 2f - metersToPixels(northMeters, centerLat, zoom),
            )
        }

        fun drawCell(lat: Double, lng: Double, sideMeters: Double, color: Color) {
            val side = metersToPixels(sideMeters, centerLat, zoom).coerceAtLeast(2f)
            val c = toOffset(lat, lng)
            if (c.x < -side || c.x > size.width + side) return
            if (c.y < -side || c.y > size.height + side) return
            drawRect(
                color = color,
                topLeft = Offset(c.x - side / 2f, c.y - side / 2f),
                size = Size(side, side),
            )
        }

        // Ledger freshness first (underneath): fog = never scanned, amber = stale.
        for ((lat, lng) in fogCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, FogGray.copy(alpha = 0.45f))
        }
        for ((lat, lng) in staleCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, StaleAmber.copy(alpha = 0.30f))
        }

        // This session's credited zones.
        for ((lat, lng) in scanCellCenters) {
            drawCell(lat, lng, cellSizeMeters, GccGreen.copy(alpha = 0.45f))
        }

        // Geo-tagged trash items on top.
        for (item in trashItems) {
            val c = toOffset(item.lat, item.lng)
            if (c.x !in 0f..size.width || c.y !in 0f..size.height) continue
            drawCircle(color = TrashOrange, radius = 3.5.dp.toPx(), center = c)
            drawCircle(
                color = Color.White,
                radius = 3.5.dp.toPx(),
                center = c,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
