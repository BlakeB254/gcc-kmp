package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.map.quantizeAndTraceOutlines
import com.gcc.app.ui.theme.CardAccentGreen
import com.gcc.app.ui.theme.GccAvailable
import com.gcc.app.ui.theme.GccClaimed
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted
import com.gcc.app.platform.EnvScanSessionDraft
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
) {
    GccCard(accentColor = CardAccentGreen) {
        Text(
            "Scan Session Complete",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = GccGreen,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Shaded areas show the ground you covered this session.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )

        val cells = session?.scannedCells.orEmpty()
        if (cells.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            SessionCoverageMap(
                cellCenters = cells.map { it.lat to it.lng },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
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
        val rings = remember(cellCenters) {
            quantizeAndTraceOutlines(cellCenters, CoverageTracker.cellSizeMeters, anchor.first, anchor.second)
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val fill = GccGreen.copy(alpha = 0.4f)
            val edge = GccGreen.copy(alpha = 0.8f)
            val path = Path().apply { fillType = PathFillType.EvenOdd }
            for (ring in rings) {
                if (ring.size < 3) continue
                ring.forEachIndexed { index, (lat, lng) ->
                    val eastMeters = (lng - centerLng) * 111_320.0 * cosLat
                    val northMeters = (lat - centerLat) * 111_320.0
                    val x = size.width / 2f + metersToPixels(eastMeters, centerLat, overlayZoom)
                    val y = size.height / 2f - metersToPixels(northMeters, centerLat, overlayZoom)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            drawPath(path, color = fill)
            drawPath(path, color = edge, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
