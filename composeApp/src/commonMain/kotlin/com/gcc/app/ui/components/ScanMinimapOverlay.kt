package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.map.quantizeAndTraceOutlines
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
    staleCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Cells the ledger marks freshly scanned — punched out of the veil. */
    freshCellCenters: List<Pair<Double, Double>> = emptyList(),
    freshnessCellSizeMeters: Double = 10.0,
    /** Session cells credited at CLOSE tier — drawn vivid over the grazed shade. */
    closeCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Session cells credited at SEEN tier only (sighted at distance, never
     *  walked) — the faintest wash, drawn over the veil rather than punched
     *  out of it (sight still leaves fog on the ground, spec Pillar 7). */
    seenCellCenters: List<Pair<Double, Double>> = emptyList(),
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
    /** Scene-aware flashlight beam (WS2): closed lat/lng ring from the user
     *  dot out through the current tick's ray-hit envelope. Drawn UNDER the
     *  coverage shading, over the veil. Empty = no beam this tick. */
    visibilityBeam: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Stable lattice anchor for the session-tier outline tracer (Pillar 7):
    // locked to whatever centerLat/Lng were at FIRST composition, never the
    // live/continuously-moving GPS fix `centerLat`/`centerLng` become on
    // every location tick. Using a moving anchor would shift the (row, col)
    // quantization's rounding boundary every frame, snapping the WHOLE
    // traced outline by a cell width relative to every other (directly
    // lat/lng-projected, non-quantized) element on the canvas as the user
    // walks — a visible jitter bug, not just an aesthetic nit.
    val latticeAnchorLat = remember { centerLat }
    val latticeAnchorLng = remember { centerLng }

    // Marching-squares + Chaikin smoothing (traceCoverageOutlines) is the
    // expensive part of this overlay; hoisted out of the Canvas draw scope
    // (which reruns on every recomposition — every GPS tick, since the live
    // centerLat/Lng feed toOffset below) and memoized on the actual
    // credited-cell sets, so it only reruns when scan state changes.
    val scanRings = remember(scanCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(scanCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }
    val closeRings = remember(closeCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(closeCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }
    val seenRings = remember(seenCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(seenCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }

    // WS4: the coarser ledger tiers (server-fresh / fresh-close / stale) and the
    // corridor now trace through the SAME marching-squares engine as the session
    // tiers, so they merge like paint with no interior grid lines. Memoized on
    // their cell sets off the stable lattice anchor (matches the session hoist).
    val corridorRings = remember(corridorCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(corridorCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }
    val freshRings = remember(freshCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(freshCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }
    val freshCloseRings = remember(freshCloseCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(freshCloseCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }
    val staleRings = remember(staleCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        quantizeAndTraceOutlines(staleCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }

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


        // ── Organic session-tier outlines (Pillar 7) ──────────────────
        // Session-credited cells (this scan's own GRAZED/CLOSE/SEEN sets)
        // render as smoothed swept-territory outlines instead of a per-cell
        // rect lattice — "no graph paper" (Blake). Server-fresh ledger cells
        // (freshCellCenters/freshCloseCellCenters, a different — coarser —
        // grid) keep the v1 per-cell rect rendering; only THIS session's own
        // 3m-grid cells trace through marching squares. The rings themselves
        // (scanRings/closeRings/seenRings) are already traced+smoothed above
        // (outside this draw scope, off the stable lattice anchor) — only
        // the cheap lat/lng→screen projection happens here, every frame.
        fun ringsToPath(rings: List<List<Pair<Double, Double>>>): Path {
            val path = Path().apply { fillType = PathFillType.EvenOdd }
            for (ring in rings) {
                if (ring.size < 3) continue
                ring.forEachIndexed { index, (lat, lng) ->
                    val p = toOffset(lat, lng)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
            }
            return path
        }
        // scanCellCenters is already GRAZED+ (CLOSE cells included) — its own
        // outline IS the full session union the veil needs punched out.
        val scanOutline = ringsToPath(scanRings)
        val closeOutline = ringsToPath(closeRings)
        // SEEN cells are NOT punched out of the veil (sight alone leaves the
        // ground still fogged, spec Pillar 7) — only used for the faint wash
        // drawn on TOP of the veil below.
        val seenOutline = ringsToPath(seenRings)
        // WS4: coarse ledger/corridor tiers as merged organic outlines too.
        val corridorOutline = ringsToPath(corridorRings)
        val freshOutline = ringsToPath(freshRings)
        val freshCloseOutline = ringsToPath(freshCloseRings)
        val staleOutline = ringsToPath(staleRings)

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
        holes.addPath(corridorOutline)
        holes.addPath(freshOutline)
        holes.addPath(freshCloseOutline)
        holes.addPath(scanOutline)
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

        // Scene-aware flashlight beam (WS2): projected through the SAME
        // zoom-biased toOffset as every other layer so it stays glued to the
        // streets. Subtle fill (flashlight feel), no stroke; drawn under the
        // coverage shading below.
        if (visibilityBeam.size >= 3) {
            val beamPath = Path()
            visibilityBeam.forEachIndexed { index, (lat, lng) ->
                val p = toOffset(lat, lng)
                if (index == 0) beamPath.moveTo(p.x, p.y) else beamPath.lineTo(p.x, p.y)
            }
            beamPath.close()
            drawPath(beamPath, color = GccGreen.copy(alpha = 0.12f))
        }

        // WS4 "paint the town": corridor + ledger tiers render as merged
        // organic outlines (no per-cell rects, no interior grid lines), at the
        // SAME colors/alphas as before.
        // Required corridor (block laps): re-shaded lighter over the veil.
        drawPath(corridorOutline, color = VeilSlate.copy(alpha = 0.28f))
        // Ledger-fresh: grazed = whisper (needs detail), close = established
        // faint green (already well-scanned by the community).
        drawPath(freshOutline, color = GccGreen.copy(alpha = 0.07f))
        drawPath(freshCloseOutline, color = GccGreen.copy(alpha = 0.16f))
        // Stale: scanned before, worth re-scanning — amber glow over the veil.
        drawPath(staleOutline, color = StaleAmber.copy(alpha = 0.45f))

        // This session, three shades (Pillar 7 rendering hierarchy: SEEN
        // faintest → GRAZED light → CLOSE vivid) — every credited cell gets
        // the light grazed wash, close-tier cells re-cover vivid, and SEEN
        // gets its own faint tint over the still-fogged veil. Alphas bumped
        // (0.25→0.30, 0.60→0.65) for scanned-vs-unscanned contrast.
        drawPath(seenOutline, color = GccGreen.copy(alpha = 0.11f))
        drawPath(scanOutline, color = GccGreen.copy(alpha = 0.30f))
        drawPath(closeOutline, color = GccGreen.copy(alpha = 0.65f))

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
