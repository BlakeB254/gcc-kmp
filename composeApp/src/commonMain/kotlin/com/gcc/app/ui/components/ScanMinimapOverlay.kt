package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.theme.GccGreen
import com.gcc.shared.model.Coordinates
import kotlin.math.PI
import kotlin.math.cos

private val StaleAmber = Color(0xFFF59E0B)
private val TrashOrange = Color(0xFFF97316)
private val VeilSlate = Color(0xFF1E293B)

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
    /** Legacy fog dots — superseded by the full veil; kept for call-site compat. */
    fogCellCenters: List<Pair<Double, Double>> = emptyList(),
    staleCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Cells the ledger marks freshly scanned — punched out of the veil. */
    freshCellCenters: List<Pair<Double, Double>> = emptyList(),
    freshnessCellSizeMeters: Double = 10.0,
    /** Block outline (lat, lng ring) — drawn as the minimap's boundary. */
    outerBoundary: List<Pair<Double, Double>> = emptyList(),
    /** Inner exclusion islands (private property) — amber outlines. */
    innerBoundaries: List<List<Pair<Double, Double>>> = emptyList(),
    /**
     * Required-area cell centers. Drawn as a dim base under the green
     * scanned cells so the block visibly "fills in" as the lap progresses.
     */
    corridorCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Lap-2 target: the problem area currently being guided to. */
    guidanceTarget: Coordinates? = null,
    modifier: Modifier = Modifier,
) {
    // Offscreen compositing so BlendMode.Clear punches holes in the veil
    // instead of the whole minimap stack.
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val lngScale = cos(centerLat * PI / 180.0)

        fun toOffset(lat: Double, lng: Double): Offset {
            val eastMeters = (lng - centerLng) * 111_320.0 * lngScale
            val northMeters = (lat - centerLat) * 111_320.0
            return Offset(
                size.width / 2f + metersToPixels(eastMeters, centerLat, zoom),
                size.height / 2f - metersToPixels(northMeters, centerLat, zoom),
            )
        }

        fun drawRing(ring: List<Pair<Double, Double>>, color: Color, widthPx: Float) {
            if (ring.size < 3) return
            val path = Path()
            ring.forEachIndexed { index, (lat, lng) ->
                val p = toOffset(lat, lng)
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, color = color, style = Stroke(width = widthPx))
        }


        fun drawCell(
            lat: Double,
            lng: Double,
            sideMeters: Double,
            color: Color,
            blendMode: BlendMode = DrawScope.DefaultBlendMode,
            // Punched holes look ragged if adjacent cells leave hairline
            // seams — overscan each cell slightly so neighbors fuse.
            overscan: Float = 1f,
        ) {
            val side = metersToPixels(sideMeters, centerLat, zoom).coerceAtLeast(2f) * overscan
            val c = toOffset(lat, lng)
            if (c.x < -side || c.x > size.width + side) return
            if (c.y < -side || c.y > size.height + side) return
            drawRect(
                color = color,
                topLeft = Offset(c.x - side / 2f, c.y - side / 2f),
                size = Size(side, side),
                blendMode = blendMode,
            )
        }

        // ── Fog-of-war veil ──────────────────────────────────────────
        // The WHOLE minimap starts under one heavy dark veil: everything you
        // can see is unscanned world, and that should be unmissable. Scanned
        // areas get punched out of the veil below — the map literally lights
        // up where data exists.
        drawRect(color = VeilSlate.copy(alpha = 0.62f), size = size)

        // Required corridor (block laps): lighten the veil over the required
        // area so the assignment reads as a distinct region to clear.
        for ((lat, lng) in corridorCellCenters) {
            drawCell(lat, lng, cellSizeMeters, VeilSlate.copy(alpha = 0.35f), BlendMode.Src, overscan = 1.15f)
        }

        // Ledger-fresh cells: already scanned by the community — clear sky.
        for ((lat, lng) in freshCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, Color.Transparent, BlendMode.Clear, overscan = 1.1f)
            drawCell(lat, lng, freshnessCellSizeMeters, GccGreen.copy(alpha = 0.14f), overscan = 1.1f)
        }

        // Stale cells: scanned before, worth re-scanning — amber glow through
        // a thinned veil.
        for ((lat, lng) in staleCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, StaleAmber.copy(alpha = 0.55f), BlendMode.Src, overscan = 1.1f)
        }

        // This session's credited zones: fully punched + vivid green. This is
        // the live "you just scanned this" feedback.
        for ((lat, lng) in scanCellCenters) {
            drawCell(lat, lng, cellSizeMeters, Color.Transparent, BlendMode.Clear, overscan = 1.2f)
            drawCell(lat, lng, cellSizeMeters, GccGreen.copy(alpha = 0.60f), overscan = 1.2f)
        }

        // Block boundaries over the fills: outer outline in the brand green,
        // inner exclusion islands in amber (the not-required areas).
        drawRing(outerBoundary, GccGreen, 2.dp.toPx())
        for (island in innerBoundaries) {
            drawRing(island, StaleAmber, 1.5.dp.toPx())
        }

        // Geo-tagged trash items on top — the landmarks the lap creates.
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

        // Lap-2 guidance target: a double ring marking the problem area the
        // worker is being steered toward.
        guidanceTarget?.let { target ->
            val c = toOffset(target.lat, target.lng)
            drawCircle(color = TrashOrange, radius = 9.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.White, radius = 12.dp.toPx(), center = c, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
