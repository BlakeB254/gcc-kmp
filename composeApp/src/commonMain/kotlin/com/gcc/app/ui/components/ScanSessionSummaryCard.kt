package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.ScanScore
import com.gcc.app.platform.TrashItemCatalog
import com.gcc.app.platform.decodeImageBitmap
import com.gcc.app.viewmodel.LitterEvidenceShot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.geoToCanvasProjector
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.map.quantizeAndTraceOutlines
import com.gcc.app.ui.map.traceSessionSilhouette
import com.gcc.app.ui.theme.CardAccentGreen
import com.gcc.app.ui.theme.GccAvailable
import com.gcc.app.ui.theme.GccClaimed
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import androidx.compose.ui.text.style.TextAlign
import com.gcc.app.ui.theme.TextMuted
import com.gcc.app.platform.EnvScanSessionDraft
import com.gcc.shared.model.ObservedSpotItem
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * "Scan Session Complete" summary: a static map with every area the worker
 * covered during the environment scan shaded in, plus headline session stats.
 *
 * The shading is the session's credited [CoverageTracker] cells (3m grid)
 * drawn as a Compose Canvas overlay above a camera-locked [GccMap] — the
 * boundary-editor projection pattern: the canvas works in physical pixels
 * while the basemap camera zoom is point-space, so every geo→px conversion
 * uses `cameraZoom + mapOverlayZoomBias(density)` and the camera keeps the
 * unbiased zoom.
 */
@Composable
fun ScanSessionSummaryCard(
    session: EnvScanSessionDraft?,
    litterCount: Int,
    featureCount: Int,
    photoCount: Int,
    /** The session's identified litter — drawn on the summary map as typed
     *  glyphs + a 5m zone blob, matching the live minimap's language. Without
     *  these the finds vanish from the map the moment the scan ends, and the
     *  card's own "Litter identified" stat has no visual anchor. */
    litterItems: List<ObservedSpotItem> = emptyList(),
    /** Evidence frames retained per identified item — swipeable strip under
     *  the map so the worker can cycle through what the scan captured, with
     *  the type/weight the scan concluded per shot. */
    litterEvidence: List<LitterEvidenceShot> = emptyList(),
) {
    GccCard(accentColor = CardAccentGreen) {
        Text(
            "Scan Session Complete",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = GccGreen,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (litterItems.isEmpty()) {
                "Shaded areas show the ground you covered this session."
            } else {
                "Green is the ground you covered; orange marks the litter you identified."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )

        val cells = session?.scannedCells.orEmpty()
        if (cells.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            SessionCoverageMap(
                cellCenters = cells.map { it.lat to it.lng },
                litterItems = litterItems,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        if (litterEvidence.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            LitterEvidenceStrip(shots = litterEvidence)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val cellArea = CoverageTracker.cellSizeMeters * CoverageTracker.cellSizeMeters
            SessionStat(value = "${(cells.size * cellArea).toInt()} m²", label = "Area covered")
            SessionStat(value = sessionDurationLabel(session), label = "Duration")
            SessionStat(value = "$photoCount", label = "Photos")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Split per constraint 10: litter (report-eligible) and mapped
            // world features (vegetation/construction) are never the same
            // number, so they get their own labeled stats instead of a
            // combined "items tagged" count.
            SessionStat(value = "$litterCount", label = "Litter identified")
            SessionStat(value = "$featureCount", label = "Features mapped")
        }

        // Ground-surface breakdown of the tagged coverage cells (build 164:
        // per-cell surface context) — "grass 62% · pavement 31% · dirt 7%".
        // Only rendered when enough cells were tagged to say something.
        val surfaces = session?.surfaceBreakdown.orEmpty()
        val taggedTotal = surfaces.values.sum()
        if (taggedTotal >= 5) {
            Spacer(modifier = Modifier.height(8.dp))
            val breakdown = surfaces.entries
                .sortedByDescending { it.value }
                .joinToString(" · ") { (name, count) ->
                    "$name ${(count * 100 / taggedTotal)}%"
                }
            Text(
                "Ground: $breakdown",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        // A-F scan score (ScanScore — the completion-time sibling of the live
        // ScanQualityBadge; see ScanQualityScorer.kt for the unification
        // note). Only rendered once a session exists to score.
        if (session != null) {
            val score = remember(session, cells) {
                ScanScore.compute(
                    coveragePercent = session.coveragePercent,
                    closePercent = session.closePercent,
                    steadinessFraction = session.steadinessFraction,
                    // Same number the "Area covered" stat above shows — the
                    // grade must agree with the card's own evidence (grade
                    // recalibration, TestFlight 145: 189m² of honest sweep
                    // graded F against the synthetic corridor disc).
                    areaCoveredSquareMeters = cells.size *
                        CoverageTracker.cellSizeMeters * CoverageTracker.cellSizeMeters,
                )
            }
            // Red is reserved for an outright failed scan — a D ("Rough
            // scan") is feedback, not failure (grade recalibration).
            val gradeColor = when (score.grade) {
                'A', 'B' -> GccAvailable
                'C', 'D' -> GccClaimed
                else -> GccError
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    score.grade.toString(),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = gradeColor,
                )
                Column {
                    Text(
                        score.headline,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (score.advice != null) {
                        Text(
                            score.advice,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Swipeable strip of the session's litter-evidence frames: one page per
 * identified item, captioned with the type and estimated weight the scan
 * concluded, plus a page indicator — "cycle through the images" (Blake,
 * 2026-07-23). Bytes decode lazily per shown page; a shot whose bytes fail
 * to decode renders its caption over a placeholder box rather than crashing
 * the summary.
 */
@Composable
private fun LitterEvidenceStrip(shots: List<LitterEvidenceShot>) {
    Text(
        "What you found",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Spacer(modifier = Modifier.height(8.dp))
    val pagerState = rememberPagerState(pageCount = { shots.size })
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) { page ->
        val shot = shots[page]
        Box(modifier = Modifier.fillMaxSize()) {
            val bitmap = remember(shot.atMs) { decodeImageBitmap(shot.bytes) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Evidence photo of ${shot.itemType}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            // Caption scrim: what the scan concluded about this item.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    TrashItemCatalog.infoFor(shot.itemType).displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                shot.estimatedWeightGrams?.let { grams ->
                    Text(
                        "Est. weight ~${formatWeight(grams)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
    if (shots.size > 1) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(shots.size) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) GccGreen
                            else TextMuted.copy(alpha = 0.4f),
                        ),
                )
            }
        }
    }
}

private fun formatWeight(grams: Int): String =
    if (grams >= 1000) {
        val kg = grams / 1000.0
        val rounded = (kg * 10).toInt() / 10.0
        "$rounded kg"
    } else {
        "$grams g"
    }

@Composable
private fun SessionStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

private fun sessionDurationLabel(session: EnvScanSessionDraft?): String {
    if (session == null) return "—"
    val endMs = session.path.lastOrNull()?.atMs ?: session.startedAtMs
    val totalSeconds = ((endMs - session.startedAtMs) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * Camera-locked basemap framed to the covered area, with the credited 3m
 * cells traced into merged organic outlines (marching squares via
 * [quantizeAndTraceOutlines], same engine as the live scan minimap's
 * [ScanMinimapOverlay]) and filled on an overlay canvas — "no graph paper"
 * (Pillar 7): one swept-territory blob instead of a per-cell rect grid.
 */
@Composable
private fun SessionCoverageMap(
    cellCenters: List<Pair<Double, Double>>,
    litterItems: List<ObservedSpotItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current.density
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

        val minLat = cellCenters.minOf { it.first }
        val maxLat = cellCenters.maxOf { it.first }
        val minLng = cellCenters.minOf { it.second }
        val maxLng = cellCenters.maxOf { it.second }
        val centerLat = (minLat + maxLat) / 2
        val centerLng = (minLng + maxLng) / 2
        val cosLat = cos(centerLat * PI / 180.0)

        // Frame the covered area: pick the camera zoom whose biased overlay
        // scale fits the session bbox (padded so edge cells aren't clipped)
        // into ~80% of the canvas.
        val pad = CoverageTracker.cellSizeMeters * 4
        val spanEastMeters = (maxLng - minLng) * 111_320.0 * cosLat + pad
        val spanNorthMeters = (maxLat - minLat) * 111_320.0 + pad
        val metersPerPx = max(
            spanEastMeters / (widthPx * 0.8),
            spanNorthMeters / (heightPx * 0.8),
        )
        val bias = mapOverlayZoomBias(density)
        val fitOverlayZoom = ln(156543.03392 * cosLat / metersPerPx) / ln(2.0)
        val cameraZoom = (fitOverlayZoom - bias).coerceIn(14.0, 19.0)
        val overlayZoom = cameraZoom + bias

        // Delay mounting the map by one short beat so the AR scene's teardown
        // (Fix 1, r2a: session pause + entity cleanup on the Complete Scan
        // transition) finishes before this card asks MapLibre to allocate a
        // fresh GL surface — mounting both at once is the CAMERA→RESULT
        // crash window this closes on the composition side.
        var showMap by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(150)
            showMap = true
        }
        if (showMap) {
            GccMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = centerLat,
                centerLng = centerLng,
                zoom = cameraZoom,
                enableGestures = false,
                instantCamera = true,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        // Stable lattice anchor (tracer requirement, CoverageOutline.kt): the
        // first credited cell center, locked once per distinct cell set —
        // cellCenters is the session's final tally here (not a live-updating
        // GPS feed like the minimap's), so it only re-anchors if the session
        // itself changes.
        val anchor = remember(cellCenters) { cellCenters.first() }
        // Same gap-closing silhouette tracer as the live minimap's frontier
        // (traceSessionSilhouette) — one shared path, so the blob shown here
        // is the SAME shape the worker watched grow during the scan.
        val rings = remember(cellCenters) {
            traceSessionSilhouette(cellCenters, CoverageTracker.cellSizeMeters, anchor.first, anchor.second)
        }
        // Litter zone: same 5m-grid marching-squares shapes as the live
        // minimap, anchored to the SAME lattice as the coverage rings so the
        // zone never drifts against the green blob.
        val litterRings = remember(litterItems, cellCenters) {
            quantizeAndTraceOutlines(
                litterItems.map { it.lat to it.lng }.distinct(),
                cellSizeMeters = 5.0,
                anchorLat = anchor.first,
                anchorLng = anchor.second,
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val fill = GccGreen.copy(alpha = 0.4f)
            val edge = GccGreen.copy(alpha = 0.8f)
            val toOffset = geoToCanvasProjector(centerLat, centerLng, overlayZoom, size.width, size.height)
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
            val path = ringsToPath(rings)
            drawPath(path, color = fill)
            drawPath(path, color = edge, style = Stroke(width = 1.5.dp.toPx()))

            // The session's finds, in the live minimap's visual language:
            // orange zone shapes for where litter concentrates, per-type
            // glyphs for what was identified. The summary is the payoff
            // moment — the finds must not vanish from the map at the end.
            if (litterItems.isNotEmpty()) {
                val litterPath = ringsToPath(litterRings)
                drawPath(litterPath, color = TrashOrange.copy(alpha = 0.24f))
                drawPath(litterPath, color = TrashOrange.copy(alpha = 0.85f), style = Stroke(width = 1.2.dp.toPx()))
                for (item in litterItems) {
                    val c = toOffset(item.lat, item.lng)
                    if (c.x !in 0f..size.width || c.y !in 0f..size.height) continue
                    drawTrashGlyph(item.itemType, c)
                }
            }
        }
    }
}
