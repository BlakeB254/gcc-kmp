package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.theme.GccGreen
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ObservedSpotItem
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
 * a fixed zoom, so a Mercator meters→pixels projection biased into
 * physical-pixel space (mapOverlayZoomBias) matches the map underneath.
 * [zoom] is the MapLibre CAMERA zoom (the same value handed to GccMap);
 * the overlay applies the bias itself.
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
    /** Typed items (env-scan confirmed trash) — drawn as per-type glyphs
     *  instead of the generic orange dot. */
    typedTrashItems: List<ObservedSpotItem> = emptyList(),
    /** Legacy fog dots — superseded by the full veil; kept for call-site compat. */
    fogCellCenters: List<Pair<Double, Double>> = emptyList(),
    staleCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Cells the ledger marks freshly scanned — punched out of the veil. */
    freshCellCenters: List<Pair<Double, Double>> = emptyList(),
    freshnessCellSizeMeters: Double = 10.0,
    /** Session cells credited at CLOSE tier — drawn vivid over the grazed shade. */
    closeCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Ledger-fresh cells whose best quality is close — brighter fresh wash. */
    freshCloseCellCenters: List<Pair<Double, Double>> = emptyList(),
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
    Canvas(modifier = modifier.fillMaxSize()) {
        val lngScale = cos(centerLat * PI / 180.0)
        // DrawScope works in physical pixels; the basemap camera zoom is
        // point-space. One biased zoom keeps every layer aligned to the
        // streets MapLibre actually draws.
        val overlayZoom = zoom + mapOverlayZoomBias(density)

        fun toOffset(lat: Double, lng: Double): Offset {
            val eastMeters = (lng - centerLng) * 111_320.0 * lngScale
            val northMeters = (lat - centerLat) * 111_320.0
            return Offset(
                size.width / 2f + metersToPixels(eastMeters, centerLat, overlayZoom),
                size.height / 2f - metersToPixels(northMeters, centerLat, overlayZoom),
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


        fun cellRect(lat: Double, lng: Double, sideMeters: Double, overscan: Float): Rect? {
            // Overscan slightly so adjacent cells fuse without hairline seams.
            val side = metersToPixels(sideMeters, centerLat, overlayZoom).coerceAtLeast(2f) * overscan
            val c = toOffset(lat, lng)
            if (c.x < -side || c.x > size.width + side) return null
            if (c.y < -side || c.y > size.height + side) return null
            return Rect(Offset(c.x - side / 2f, c.y - side / 2f), Size(side, side))
        }

        fun drawCell(lat: Double, lng: Double, sideMeters: Double, color: Color, overscan: Float = 1f) {
            val r = cellRect(lat, lng, sideMeters, overscan) ?: return
            drawRect(color = color, topLeft = r.topLeft, size = r.size)
        }

        // ── Fog-of-war veil ──────────────────────────────────────────
        // The WHOLE minimap starts under one heavy dark veil: everything you
        // can see is unscanned world, and that should be unmissable. Cells
        // with scan data are cut out of the veil GEOMETRICALLY — one path
        // with even-odd holes. (Deliberately no BlendMode.Clear/offscreen
        // layer: that construct is fragile on the iOS Skia backend and a
        // failure there blanks the whole overlay.)
        // Path.combine(Difference) handles overlapping holes correctly
        // (corridor and session cells share locations; overscanned neighbors
        // overlap at edges — even-odd parity would re-fill those overlaps).
        val holes = Path()
        fun punch(lat: Double, lng: Double, sideMeters: Double, overscan: Float) {
            cellRect(lat, lng, sideMeters, overscan)?.let { holes.addRect(it) }
        }
        for ((lat, lng) in corridorCellCenters) punch(lat, lng, cellSizeMeters, 1.15f)
        for ((lat, lng) in freshCellCenters) punch(lat, lng, freshnessCellSizeMeters, 1.1f)
        for ((lat, lng) in freshCloseCellCenters) punch(lat, lng, freshnessCellSizeMeters, 1.1f)
        for ((lat, lng) in scanCellCenters) punch(lat, lng, cellSizeMeters, 1.2f)
        val veil = Path.combine(
            PathOperation.Difference,
            Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) },
            holes,
        )
        // Field feedback (Blake, TestFlight 124): the veil made the user dot
        // and the scanned/scanning cells hard to see — lightened from 0.62 to
        // 0.45 (corridor re-shade below dropped proportionally to keep the
        // same relative contrast between "assigned" and "everything else").
        drawPath(veil, color = VeilSlate.copy(alpha = 0.45f))

        // Required corridor (block laps): punched out of the heavy veil, then
        // re-shaded lighter so the assignment reads as a distinct region.
        for ((lat, lng) in corridorCellCenters) {
            drawCell(lat, lng, cellSizeMeters, VeilSlate.copy(alpha = 0.28f), overscan = 1.15f)
        }

        // Ledger-fresh cells: grazed = whisper (needs detail), close = the
        // established faint green (already well-scanned by the community).
        for ((lat, lng) in freshCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, GccGreen.copy(alpha = 0.07f), overscan = 1.1f)
        }
        for ((lat, lng) in freshCloseCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, GccGreen.copy(alpha = 0.16f), overscan = 1.1f)
        }

        // Stale cells: scanned before, worth re-scanning — amber glow over
        // the veil.
        for ((lat, lng) in staleCellCenters) {
            drawCell(lat, lng, freshnessCellSizeMeters, StaleAmber.copy(alpha = 0.45f), overscan = 1.1f)
        }

        // This session, two shades (spec §4): every credited cell gets the
        // light grazed wash; close-tier cells re-cover vivid — the live
        // "you've detail-scanned this" feedback.
        for ((lat, lng) in scanCellCenters) {
            drawCell(lat, lng, cellSizeMeters, GccGreen.copy(alpha = 0.25f), overscan = 1.2f)
        }
        for ((lat, lng) in closeCellCenters) {
            drawCell(lat, lng, cellSizeMeters, GccGreen.copy(alpha = 0.60f), overscan = 1.2f)
        }

        // ── Scan-cell grid ───────────────────────────────────────────
        // A faint lattice aligned to the CoverageTracker cell grid, over the
        // veil and fills: the minimap reads as the same "surveyed graph
        // paper" the AR ground grid projects onto the world. Anchored to a
        // real cell center when one exists so lines land on cell EDGES.
        run {
            var spacing = metersToPixels(cellSizeMeters, centerLat, overlayZoom)
            if (spacing >= 3f) {
                if (spacing < 8f) spacing *= 2f // drop to every 2nd line when dense
                val anchorCell = scanCellCenters.firstOrNull()
                    ?: corridorCellCenters.firstOrNull()
                val anchor = anchorCell?.let { toOffset(it.first, it.second) }
                    ?: Offset(size.width / 2f, size.height / 2f)
                val gridColor = Color.White.copy(alpha = 0.14f)
                var x = (anchor.x - spacing / 2f).mod(spacing)
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += spacing
                }
                var y = (anchor.y - spacing / 2f).mod(spacing)
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += spacing
                }
            }
        }

        // Block boundaries over the fills: outer outline in the brand green,
        // inner exclusion islands in amber (the not-required areas).
        drawRing(outerBoundary, GccGreen, 2.dp.toPx())
        for (island in innerBoundaries) {
            drawRing(island, StaleAmber, 1.5.dp.toPx())
        }

        // Geo-tagged trash items on top — the landmarks the lap creates.
        // Typed items draw as per-type glyphs (WHAT is there, not just that
        // something is); untyped coordinates keep the legacy orange dot.
        val typedPositions = typedTrashItems.map { it.lat to it.lng }.toSet()
        for (item in trashItems) {
            if ((item.lat to item.lng) in typedPositions) continue
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
        for (item in typedTrashItems) {
            val c = toOffset(item.lat, item.lng)
            if (c.x !in 0f..size.width || c.y !in 0f..size.height) continue
            drawTrashGlyph(item.itemType, c)
        }

        // Lap-2 guidance target: a double ring marking the problem area the
        // worker is being steered toward.
        guidanceTarget?.let { target ->
            val c = toOffset(target.lat, target.lng)
            drawCircle(color = TrashOrange, radius = 9.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.White, radius = 12.dp.toPx(), center = c, style = Stroke(width = 1.5.dp.toPx()))
        }

        // User-position dot, drawn LAST so it always sits above the veil,
        // fills and every other layer (Blake: "hard to see the user dot").
        // The minimap is centered on the live GPS fix (centerLat/centerLng),
        // so the user is always exactly at the canvas center — no projection
        // needed. Replaces reliance on any MapLibre location puck underneath.
        val selfCenter = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = selfCenter, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = GccGreen, radius = 3.5.dp.toPx(), center = selfCenter)
    }
}

/**
 * Small per-type litter glyph. Shapes stay in the litter orange/amber family
 * (glass = teal accent) with a white outline for legibility over any fill —
 * the point is that a can, a bag and a bottle read differently at a glance.
 */
private fun DrawScope.drawTrashGlyph(itemType: String, c: Offset) {
    val u = 3.dp.toPx() // half-size unit
    val outline = Color.White
    val stroke = Stroke(width = 1.dp.toPx())
    fun rect(w: Float, h: Float, fill: Color) {
        val topLeft = Offset(c.x - w / 2f, c.y - h / 2f)
        drawRect(color = fill, topLeft = topLeft, size = Size(w, h))
        drawRect(color = outline, topLeft = topLeft, size = Size(w, h), style = stroke)
    }
    fun diamond(fill: Color) {
        val path = Path().apply {
            moveTo(c.x, c.y - u * 1.3f)
            lineTo(c.x + u, c.y)
            lineTo(c.x, c.y + u * 1.3f)
            lineTo(c.x - u, c.y)
            close()
        }
        drawPath(path, color = fill)
        drawPath(path, color = outline, style = stroke)
    }
    fun triangle(fill: Color) {
        val path = Path().apply {
            moveTo(c.x - u, c.y - u)
            lineTo(c.x + u, c.y - u)
            lineTo(c.x, c.y + u * 1.2f)
            close()
        }
        drawPath(path, color = fill)
        drawPath(path, color = outline, style = stroke)
    }
    when (itemType) {
        "plastic_bottle" -> rect(u * 1.1f, u * 2.6f, TrashOrange)
        "glass_bottle" -> rect(u * 1.1f, u * 2.6f, Color(0xFF14B8A6))
        "can" -> rect(u * 1.6f, u * 1.8f, Color(0xFFEF4444))
        "plastic_bag" -> diamond(TrashOrange)
        "cup" -> triangle(StaleAmber)
        "paper", "carton" -> rect(u * 2f, u * 2f, Color(0xFFFde68A))
        "wrapper" -> rect(u * 1.8f, u * 1.0f, StaleAmber)
        "cigarette" -> rect(u * 0.7f, u * 2.2f, Color(0xFFE5E7EB))
        "food_waste" -> {
            drawCircle(color = Color(0xFF84CC16), radius = u * 1.1f, center = c)
            drawCircle(color = outline, radius = u * 1.1f, center = c, style = stroke)
        }
        "tire" -> {
            drawCircle(color = Color(0xFF374151), radius = u * 1.4f, center = c)
            drawCircle(color = outline, radius = u * 1.4f, center = c, style = stroke)
            drawCircle(color = outline, radius = u * 0.5f, center = c, style = stroke)
        }
        "mattress" -> rect(u * 2.8f, u * 1.4f, Color(0xFF93C5FD))
        // Landmark elements (auto-registered context, not trash): muted
        // greens/greys so they read as scenery behind the litter glyphs.
        "tree" -> {
            drawCircle(color = Color(0xFF16A34A).copy(alpha = 0.8f), radius = u * 1.3f, center = c)
            drawCircle(color = Color(0xFF14532D), radius = u * 0.4f, center = c)
        }
        "bush" -> drawCircle(color = Color(0xFF22C55E).copy(alpha = 0.7f), radius = u * 0.9f, center = c)
        "fence" -> rect(u * 2.6f, u * 0.7f, Color(0xFF9CA3AF))
        "gate" -> rect(u * 1.2f, u * 1.6f, Color(0xFF9CA3AF))
        "pole", "sign" -> rect(u * 0.6f, u * 2.4f, Color(0xFF9CA3AF))
        "hydrant" -> rect(u * 1.0f, u * 1.4f, Color(0xFFDC2626))
        "bench", "mailbox" -> rect(u * 1.8f, u * 1.2f, Color(0xFF6B7280))
        else -> {
            drawCircle(color = TrashOrange, radius = u * 1.15f, center = c)
            drawCircle(color = outline, radius = u * 1.15f, center = c, style = stroke)
        }
    }
}
