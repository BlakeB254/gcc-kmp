package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*

/**
 * Zoom preset definition.
 *
 * @param label Short display label for the button
 * @param zoom  Target zoom level
 */
data class ZoomPreset(
    val label: String,
    val zoom: Double,
)

/** Overview presets for Dashboard and Map tab — no route/street level. */
val overviewZoomPresets = listOf(
    ZoomPreset("City", MapStyles.ZOOM_CITY),
    ZoomPreset("Area", MapStyles.ZOOM_AREA),
)

/** Active job presets — includes route zoom for navigation. */
val activeJobZoomPresets = listOf(
    ZoomPreset("Area", MapStyles.ZOOM_AREA),
    ZoomPreset("Route", MapStyles.ZOOM_ROUTE),
    ZoomPreset("Street", MapStyles.ZOOM_STREET),
)

/** Full preset list — all zoom levels. */
val defaultZoomPresets = listOf(
    ZoomPreset("City", MapStyles.ZOOM_CITY),
    ZoomPreset("Area", MapStyles.ZOOM_AREA),
    ZoomPreset("Route", MapStyles.ZOOM_ROUTE),
    ZoomPreset("Street", MapStyles.ZOOM_STREET),
)

/**
 * Floating map control bar — compact horizontal layout at the top of the map.
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────────────┐
 * │  [−] [+]        [City][Area][Route][St.] │
 * └──────────────────────────────────────────┘
 * ```
 *
 * Positioned at the top by the caller via Modifier.align(Alignment.TopCenter).
 * Uses a semi-transparent background to avoid obstructing the map.
 *
 * @param currentZoom      The current effective zoom level
 * @param onZoomIn         Called when + button is tapped
 * @param onZoomOut        Called when - button is tapped
 * @param onPresetSelect   Called with the target zoom when a preset is tapped
 * @param presets          The list of zoom presets (defaults to [defaultZoomPresets])
 * @param modifier         Modifier for positioning
 */
@Composable
fun MapControls(
    currentZoom: Double,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onPresetSelect: (Double) -> Unit,
    presets: List<ZoomPreset> = defaultZoomPresets,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.85f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: zoom in/out buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoomButton(text = "\u2212", onClick = onZoomOut)  // minus
                ZoomButton(text = "+", onClick = onZoomIn)
            }

            // Right: preset selector (horizontal segmented control)
            ZoomPresetBar(
                presets = presets,
                currentZoom = currentZoom,
                onPresetSelect = onPresetSelect,
            )
        }
    }
}

/**
 * A small circular zoom button (+/-).
 */
@Composable
private fun ZoomButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MapControlBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MapControlIcon,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Horizontal segmented zoom preset bar.
 *
 * The active preset (closest to currentZoom) is highlighted with GccGreen.
 */
@Composable
private fun ZoomPresetBar(
    presets: List<ZoomPreset>,
    currentZoom: Double,
    onPresetSelect: (Double) -> Unit,
) {
    val activeIndex = findActivePresetIndex(presets, currentZoom)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MapControlBg),
    ) {
        presets.forEachIndexed { index, preset ->
            val isActive = index == activeIndex
            val bgColor = if (isActive) GccGreen else Color.Transparent
            val textColor = if (isActive) Color.White else MapControlIcon

            Box(
                modifier = Modifier
                    .clickable { onPresetSelect(preset.zoom) }
                    .background(bgColor)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = preset.label,
                    fontSize = 10.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Scale bar indicator showing approximate distance.
 *
 * Positioned bottom-left (by caller via Modifier.align). Renders a simple
 * line with distance text above it.
 *
 * @param zoom     Current zoom level
 * @param modifier Modifier for positioning
 */
@Composable
fun ScaleBar(
    zoom: Double,
    modifier: Modifier = Modifier,
) {
    val spacing = GccTheme.spacing

    // Approximate meters per pixel at Chicago latitude (~41.86) for given zoom
    // Formula: metersPerPixel = 156543.03 * cos(lat) / 2^zoom
    // At lat 41.86: cos(41.86 deg) ≈ 0.7441
    val metersPerPixel = 156543.03 * 0.7441 / (1 shl zoom.toInt())

    // Choose a round distance for the scale bar
    val (distanceMeters, label) = selectScaleDistance(metersPerPixel)
    val barWidthPx = (distanceMeters / metersPerPixel).toFloat()

    // Clamp to reasonable pixel range
    val barWidthDp = barWidthPx.coerceIn(21f, 144f).dp

    Column(
        modifier = modifier.padding(start = spacing.sm, bottom = spacing.sm),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MapScaleBar,
        )
        Box(
            modifier = Modifier
                .width(barWidthDp)
                .height(2.dp)
                .background(MapScaleBar),
        )
    }
}

/**
 * Selects a round distance value for the scale bar based on meters per pixel.
 */
private fun selectScaleDistance(metersPerPixel: Double): Pair<Double, String> {
    val targetMeters = metersPerPixel * 80.0
    return when {
        targetMeters >= 10000 -> 10000.0 to "10 km"
        targetMeters >= 5000  -> 5000.0 to "5 km"
        targetMeters >= 2000  -> 2000.0 to "2 km"
        targetMeters >= 1000  -> 1000.0 to "1 km"
        targetMeters >= 500   -> 500.0 to "500 m"
        targetMeters >= 200   -> 200.0 to "200 m"
        targetMeters >= 100   -> 100.0 to "100 m"
        targetMeters >= 50    -> 50.0 to "50 m"
        targetMeters >= 20    -> 20.0 to "20 m"
        else                  -> 10.0 to "10 m"
    }
}

/**
 * Finds the index of the preset closest to the current zoom level.
 */
private fun findActivePresetIndex(presets: List<ZoomPreset>, currentZoom: Double): Int {
    var bestIndex = 0
    var bestDiff = Double.MAX_VALUE
    presets.forEachIndexed { index, preset ->
        val diff = kotlin.math.abs(preset.zoom - currentZoom)
        if (diff < bestDiff) {
            bestDiff = diff
            bestIndex = index
        }
    }
    return bestIndex
}
