package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.map.metersToPixels
import com.gcc.app.ui.theme.CardAccentGreen
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted
import com.gcc.app.platform.EnvScanSessionDraft
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
    itemCount: Int,
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
            SessionStat(value = "$itemCount", label = "Items tagged")
            SessionStat(value = "$photoCount", label = "Photos")
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
 * Camera-locked basemap framed to the covered area, with each credited 3m
 * cell shaded on an overlay canvas. Cells are drawn slightly oversized so
 * adjacent cells fuse into one continuous swept region instead of reading as
 * a dotted grid.
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

        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = cameraZoom,
            enableGestures = false,
            instantCamera = true,
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellPx = metersToPixels(CoverageTracker.cellSizeMeters, centerLat, overlayZoom)
            // 1.2× so neighbouring cells overlap into continuous shading.
            val drawSize = cellPx * 1.2f
            val fill = GccGreen.copy(alpha = 0.4f)
            cellCenters.forEach { (lat, lng) ->
                val eastMeters = (lng - centerLng) * 111_320.0 * cosLat
                val northMeters = (lat - centerLat) * 111_320.0
                val cx = size.width / 2f + metersToPixels(eastMeters, centerLat, overlayZoom)
                val cy = size.height / 2f - metersToPixels(northMeters, centerLat, overlayZoom)
                drawRect(
                    color = fill,
                    topLeft = Offset(cx - drawSize / 2f, cy - drawSize / 2f),
                    size = Size(drawSize, drawSize),
                )
            }
        }
    }
}
