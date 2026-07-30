package com.gcc.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.geoToCanvasProjector
import com.gcc.app.ui.map.headingConePolygon
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.map.quantizeAndTraceOutlines
import com.gcc.app.ui.map.traceSessionSilhouette
import com.gcc.app.ui.map.unwrapHeading
import com.gcc.app.ui.theme.GccGreen
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ObservedSpotItem
import kotlin.math.PI
import kotlin.math.cos

private val StaleAmber = Color(0xFFF59E0B)
internal val TrashOrange = Color(0xFFF97316)
private val VeilSlate = Color(0xFF1E293B)
private val ConeBlue = Color(0xFF38BDF8)

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
    /** Live user coordinate. Defaults to the map center for the traditional
     * user-following environment minimap; block-route maps pass it explicitly
     * so the camera can stay fitted to the whole assignment. */
    userLat: Double = centerLat,
    userLng: Double = centerLng,
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
    /** Litter-pattern zone grid. Deliberately finer than the 10m freshness
     *  grid: quantizing litter on 10m cells inflated a SINGLE ~20cm item into
     *  a ~10m blob — field report (TestFlight 157) read it as a "big red
     *  circle" over nothing. 5m keeps genuine clusters merging (adjacent-cell
     *  items still fuse into one zone) while a lone item stays a modest mark. */
    litterZoneCellSizeMeters: Double = 5.0,
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
    /** Live compass heading. The persistent 55° cone rotates with it and is
     *  drawn even when [visibilityBeam] is empty (global constraint 5). */
    headingDegrees: Float? = null,
    modifier: Modifier = Modifier,
) {
    // ADDENDUM (Blake, binding): rotate the cone with a shortest-path
    // unwrap so a heading crossing the 0/360 seam (350 -> 10) animates the
    // short way (+20), not the long way around through 0. `cumulativeHeading`
    // is intentionally NOT mod-360 — animateFloatAsState needs a monotonic
    // target to interpolate the short step; unwrapHeading only depends on
    // (next - previous) mod 360, so an ever-growing cumulative value is safe.
    var cumulativeHeading by remember { mutableStateOf(headingDegrees ?: 0f) }
    LaunchedEffect(headingDegrees) {
        headingDegrees?.let { raw ->
            cumulativeHeading += unwrapHeading(cumulativeHeading, raw)
        }
    }
    val animatedHeading by animateFloatAsState(
        targetValue = cumulativeHeading,
        animationSpec = tween(durationMillis = 320, easing = LinearEasing),
        label = "coneHeading",
    )
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
    // centerLat/Lng feed toOffset below) and memoized on the actual cell sets,
    // so tracing only reruns when THAT tier's scan state changes.
    // Session tiers (3m grid) + coarser ledger/corridor tiers (10m
    // freshness grid) all route through the same engine (WS4 "paint the town").
    // Litter-pattern zones: every known litter position (this session's
    // confirmed items + the world model's dirty spots) quantized onto the
    // litter-zone grid and traced into merged organic shapes — clustered
    // litter reads as a contiguous "problem zone" glued to the map tiles,
    // not just individual glyph pins. Uses the same lattice anchor as every
    // other tier so the shapes never drift against the coverage paint.
    val litterCoords = remember(trashItems, typedTrashItems) {
        (trashItems.map { it.lat to it.lng } + typedTrashItems.map { it.lat to it.lng }).distinct()
    }
    val litterRings = rememberRings(litterCoords, litterZoneCellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    val closeRings = rememberRings(closeCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    // Swept-territory silhouette: GRAZED+ (the credited coverage — the SAME
    // set the Scan Session Complete summary map draws) traced through the
    // shared gap-closing tracer. Two prior rounds tried to make the live map
    // read as one blob by unioning SEEN into the frontier (#208) and filling
    // that union (#213); both failed in the field because fan-SEEN cells at
    // range are SPARSE — discrete rays land >1 cell apart past ~12m — so the
    // union traced into dozens of individually-outlined capsules ("streaks
    // that never connect", TestFlight 161). The frontier now follows the
    // gap-closed GRAZED+ silhouette (traceSessionSilhouette, shared with the
    // summary card), and SEEN drops out of the frontier entirely — it renders
    // as a faint strokeless wash below, its original Pillar 7 role.
    val silhouetteRings = remember(scanCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng) {
        traceSessionSilhouette(scanCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    }
    val seenRings = rememberRings(seenCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    val corridorRings = rememberRings(corridorCellCenters, cellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    val freshRings = rememberRings(freshCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    val freshCloseRings = rememberRings(freshCloseCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng)
    val staleRings = rememberRings(staleCellCenters, freshnessCellSizeMeters, latticeAnchorLat, latticeAnchorLng)

    Canvas(modifier = modifier.fillMaxSize()) {
        // DrawScope works in physical pixels; the basemap camera zoom is
        // point-space. One biased zoom keeps every layer aligned to the
        // streets MapLibre actually draws.
        val overlayZoom = zoom + mapOverlayZoomBias(density)
        val toOffset = geoToCanvasProjector(centerLat, centerLng, overlayZoom, size.width, size.height)

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
        // (scanRings/closeRings) are already traced+smoothed above
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
        // scanCellCenters is already GRAZED+ (CLOSE cells included); the
        // silhouette is that set with fan-sampling seams closed — the one
        // connected shape the veil is punched out of and the bright frontier
        // edge follows, identical by construction to the summary card's blob.
        val closeOutline = ringsToPath(closeRings)
        val silhouetteOutline = ringsToPath(silhouetteRings)
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
        holes.addPath(silhouetteOutline)
        val veil = Path.combine(
            PathOperation.Difference,
            Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) },
            holes,
        )
        // Field feedback (Blake, TestFlight 124): the veil made the user dot
        // and the scanned/scanning cells hard to see — lightened from 0.62 to
        // 0.45 (corridor re-shade below dropped proportionally to keep the
        // same relative contrast between "assigned" and "everything else").
        // Field report (env scan): the minimap read "too darkened". Veil
        // lightened 0.45 -> 0.30 so unscanned ground still reads as fog without
        // drowning the beam, the painted coverage, and the user dot.
        // Field report (Blake, TestFlight): "too dark to see anything" in
        // daylight. The basemap is already the light OpenFreeMap Liberty
        // style — the dimness was entirely this scrim. Unscanned ground must
        // still read as fog, so the veil stays; it just stops drowning the
        // streets, the cone, and the coverage paint.
        drawPath(veil, color = VeilSlate.copy(alpha = 0.18f))

        // ── Scan limit (block assignments) ───────────────────────────
        // Everything OUTSIDE the block's outer boundary is out of scope for
        // the assignment: a heavier slate wash over the base veil makes the
        // boundary read as a hard limit, mirroring the AR barrier walls in
        // the camera view. Geometric difference like the veil (no BlendMode).
        if (outerBoundary.size >= 3) {
            val boundaryPath = Path()
            outerBoundary.forEachIndexed { index, (lat, lng) ->
                val p = toOffset(lat, lng)
                if (index == 0) boundaryPath.moveTo(p.x, p.y) else boundaryPath.lineTo(p.x, p.y)
            }
            boundaryPath.close()
            val outside = Path.combine(
                PathOperation.Difference,
                Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) },
                boundaryPath,
            )
            drawPath(outside, color = VeilSlate.copy(alpha = 0.35f))
        }

        // Persistent facing cone. Answers "where am I pointing?" — independent
        // of whether any ray landed this tick (constraint 5). Drawn UNDER the
        // beam so the brighter, scene-aware beam paints on top of it.
        // Field report (TestFlight 148): the flat 0.16 fill read as a barely-
        // there wash in daylight — the cone now carries a radial gradient
        // (saturated at the user dot, fading with distance) plus a heavier
        // edge so the facing direction is unmissable at a glance.
        if (headingDegrees != null) {
            val cone = headingConePolygon(userLat, userLng, animatedHeading)
            if (cone.size >= 3) {
                val coneCenter = toOffset(userLat, userLng)
                val conePath = Path()
                var coneReachPx = 0f
                cone.forEachIndexed { index, (lat, lng) ->
                    val p = toOffset(lat, lng)
                    if (index == 0) conePath.moveTo(p.x, p.y) else conePath.lineTo(p.x, p.y)
                    val d = (p - coneCenter).getDistance()
                    if (d > coneReachPx) coneReachPx = d
                }
                conePath.close()
                if (coneReachPx > 1f) {
                    drawPath(
                        conePath,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ConeBlue.copy(alpha = 0.55f),
                                ConeBlue.copy(alpha = 0.28f),
                                ConeBlue.copy(alpha = 0.06f),
                            ),
                            center = coneCenter,
                            radius = coneReachPx,
                        ),
                    )
                }
                drawPath(conePath, color = ConeBlue.copy(alpha = 0.9f), style = Stroke(width = 2.dp.toPx()))
                drawPath(conePath, color = Color.White.copy(alpha = 0.35f), style = Stroke(width = 0.8.dp.toPx()))
            }
        }

        // Scene-aware flashlight beam (WS2): projected through the SAME
        // zoom-biased toOffset as every other layer so it stays glued to the
        // streets. Filled with a RADIAL GRADIENT from the user dot — bright at
        // the origin, fading to nothing at the reach — so it reads as an actual
        // flashlight beam (Blake's "flashlight effect") instead of a flat blob.
        if (visibilityBeam.size >= 3) {
            val beamCenter = toOffset(userLat, userLng)
            val beamPath = Path()
            var maxReachPx = 0f
            visibilityBeam.forEachIndexed { index, (lat, lng) ->
                val p = toOffset(lat, lng)
                if (index == 0) beamPath.moveTo(p.x, p.y) else beamPath.lineTo(p.x, p.y)
                val dx = p.x - beamCenter.x
                val dy = p.y - beamCenter.y
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d > maxReachPx) maxReachPx = d
            }
            beamPath.close()
            if (maxReachPx > 1f) {
                drawPath(
                    beamPath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GccGreen.copy(alpha = 0.34f),
                            GccGreen.copy(alpha = 0.12f),
                            GccGreen.copy(alpha = 0.0f),
                        ),
                        center = beamCenter,
                        radius = maxReachPx,
                    ),
                )
            }
        }

        // WS4 "paint the town": corridor + ledger tiers render as merged
        // organic outlines (no per-cell rects, no interior grid lines), at the
        // SAME colors/alphas as before.
        // Required corridor (block laps): your assigned block. With the veil
        // now lighter (0.30), a 0.28 re-shade blended into the fog — dropped to
        // 0.10 so the corridor reads as a distinctly LIGHTER "your zone" cut
        // out of the surrounding fog (block-route legibility).
        drawPath(corridorOutline, color = VeilSlate.copy(alpha = 0.10f))
        // Ledger-fresh: grazed = whisper (needs detail), close = established
        // faint green (already well-scanned by the community).
        drawPath(freshOutline, color = GccGreen.copy(alpha = 0.07f))
        drawPath(freshCloseOutline, color = GccGreen.copy(alpha = 0.16f))
        // Stale: scanned before, worth re-scanning — amber glow over the veil.
        drawPath(staleOutline, color = StaleAmber.copy(alpha = 0.45f))

        // This session, three shades (Pillar 7 rendering hierarchy: SEEN
        // faintest → GRAZED light → CLOSE vivid) — every credited cell gets
        // the light grazed wash, close-tier cells re-cover vivid, and SEEN
        // gets its own faint tint over the still-fogged veil.
        // Field report (TestFlight 145): with the veil lightened to 0.18 the
        // live fill stopped reading at all in daylight — the punch-out no
        // longer carries the contrast, so the session paint itself must.
        // Alphas raised (0.30→0.42, 0.65→0.78) and the swept-territory blob
        // gets a bright edge stroke so "where I've scanned" pops as it grows.
        // Field report (TestFlight 157): SEEN at 0.14 was invisible over the
        // basemap, so the swept ground CONNECTING the walked/close patches
        // rendered as nothing — the live map read as disconnected green
        // islands while the summary (final GRAZED+ set, auto-fit zoom) showed
        // one blob. SEEN must stay clearly below GRAZED (0.42) — it's credit-
        // less sweep, not coverage — but high enough to read as tissue.
        // Field report (post-157): raising SEEN alone wasn't enough — the
        // bright edge stroke around each GRAZED island kept the map reading
        // as separate blobs. Field report (TestFlight 159): stroking the
        // union without FILLING it still left low-tier tissue reading as an
        // unscanned streak. Field report (TestFlight 161): filling + stroking
        // the GRAZED∪SEEN union outlined every sparse fan-SEEN cell at range
        // as its own capsule — the "streaks that never connect". The frontier
        // (floor fill + bright edge) now follows the gap-closed GRAZED+
        // silhouette — the summary map's exact set and treatment — so the
        // live map grows the SAME connected blob the completion card shows.
        // SEEN renders first as a faint strokeless wash over the veil
        // (Pillar 7's original role: sighted-at-distance, credit-less), so
        // distant sparse fan hits read as soft speckle, never outlined shapes.
        drawPath(seenOutline, color = GccGreen.copy(alpha = 0.18f))
        drawPath(silhouetteOutline, color = GccGreen.copy(alpha = 0.40f))
        drawPath(closeOutline, color = GccGreen.copy(alpha = 0.45f))
        drawPath(silhouetteOutline, color = GccGreen.copy(alpha = 0.85f), style = Stroke(width = 1.2.dp.toPx()))

        // Litter-pattern zones over the coverage paint: merged orange shapes
        // marking WHERE the litter concentrates, under the per-item glyphs
        // (the glyphs say what; the zone shape says how it spreads).
        val litterOutline = ringsToPath(litterRings)
        drawPath(litterOutline, color = TrashOrange.copy(alpha = 0.24f))
        drawPath(litterOutline, color = TrashOrange.copy(alpha = 0.85f), style = Stroke(width = 1.2.dp.toPx()))

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
        // worker is being steered toward. Field report (TestFlight 145): at
        // scan start the frontier pick is the nearest uncredited cell — the
        // ground underfoot — so the ring drew AS a circle around the user dot
        // and read as a bug. Guidance to "right where you stand" carries no
        // information; only draw the ring once the target clears the dot.
        guidanceTarget?.let { target ->
            val c = toOffset(target.lat, target.lng)
            val selfC = toOffset(userLat, userLng)
            val awayPx = (c - selfC).getDistance()
            if (awayPx >= 26.dp.toPx()) {
                drawCircle(color = TrashOrange, radius = 9.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = Color.White, radius = 12.dp.toPx(), center = c, style = Stroke(width = 1.5.dp.toPx()))
            }
        }

        // User-position dot, drawn LAST so it always sits above the veil,
        // fills and every other layer (Blake: "hard to see the user dot").
        // Route maps may stay centered on the entire assigned block, while
        // environment minimaps keep the historical user-follow camera. One
        // projected coordinate supports both without a duplicate map puck.
        val selfCenter = toOffset(userLat, userLng)
        // Dark backing disc first: the dot's white ring + green core would
        // otherwise sit green-on-green over CLOSE-scanned tiles (0.65 green) and
        // the green beam, washing out. A translucent dark pad guarantees the
        // dot reads on ANY underlying layer.
        drawCircle(color = Color.Black.copy(alpha = 0.38f), radius = 7.dp.toPx(), center = selfCenter)
        drawCircle(color = Color.White, radius = 5.dp.toPx(), center = selfCenter, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = GccGreen, radius = 3.5.dp.toPx(), center = selfCenter)
    }
}

/**
 * Small per-type litter glyph. Shapes stay in the litter orange/amber family
 * (glass = teal accent) with a white outline for legibility over any fill —
 * the point is that a can, a bag and a bottle read differently at a glance.
 */
/** Trace + memoize one tier's cell set into smoothed marching-squares rings,
 *  keyed so it only re-traces when that tier's cells (or the lattice) change. */
@Composable
private fun rememberRings(
    cells: List<Pair<Double, Double>>,
    cellSizeMeters: Double,
    anchorLat: Double,
    anchorLng: Double,
): List<List<Pair<Double, Double>>> =
    remember(cells, cellSizeMeters, anchorLat, anchorLng) {
        quantizeAndTraceOutlines(cells, cellSizeMeters, anchorLat, anchorLng)
    }

internal fun DrawScope.drawTrashGlyph(itemType: String, c: Offset) {
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
        // Landmark element types (tree/bush/fence/gate/pole/sign/hydrant/
        // bench/mailbox — domain "vegetation"/"construction") never reach
        // this function: minimapLitterItems() filters them out upstream in
        // SpotScreen.kt before typedTrashItems is built. See punchlist
        // "little white squares on the minimap" — those were this branch's
        // rect() outline.
        else -> {
            drawCircle(color = TrashOrange, radius = u * 1.15f, center = c)
            drawCircle(color = outline, radius = u * 1.15f, center = c, style = stroke)
        }
    }
}
