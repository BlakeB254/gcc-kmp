package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** Standard zoom presets using defined MapStyles constants. */
val defaultZoomPresets = listOf(
    ZoomPreset("City", MapStyles.ZOOM_CITY),
    ZoomPreset("Area", MapStyles.ZOOM_NEIGHBORHOOD),
    ZoomPreset("Route", MapStyles.ZOOM_ROUTE),
    ZoomPreset("Street", MapStyles.ZOOM_STREET),
)

/**
 * Floating map control overlay with zoom buttons and preset zoom bar.
 *
 * Positioned bottom-right (by the caller via Modifier.align). Uses Fibonacci
 * spacing tokens from GccTheme.spacing throughout.
 *
 * Layout (vertical, bottom-right):
 * ```
 *   [ + ]          <- 34dp diameter zoom-in button
 *   [ - ]          <- 34dp diameter zoom-out button
 *   ┌─────┐
 *   │City │
 *   │Area │        <- Vertical pill of preset buttons
 *   │Route│
 *   │St.  │
 *   └─────┘
 * ```
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
    val spacing = GccTheme.spacing
    val dims = GccTheme.dimensions
    val elevation = GccTheme.elevation

    Column(
        modifier = modifier.padding(end = spacing.sm, bottom = spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Zoom in button: 34dp circle
        ZoomButton(
            text = "+",
            onClick = onZoomIn,
            size = dims.avatarSm,  // 34dp
        )

        // Zoom out button: 34dp circle
        ZoomButton(
            text = "\u2212",  // minus sign
            onClick = onZoomOut,
            size = dims.avatarSm,
        )

        Spacer(modifier = Modifier.height(spacing.xxs))

        // Preset zoom bar: vertical pill
        ZoomPresetBar(
            presets = presets,
            currentZoom = currentZoom,
            onPresetSelect = onPresetSelect,
        )
    }
}

/**
 * A circular zoom button (+/-).
 */
@Composable
private fun ZoomButton(
    text: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        shadowElevation = GccTheme.elevation.raised,
        color = MapControlBg,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = text,
                fontSize = 13.sp,  // Fibonacci: 13
                fontWeight = FontWeight.Bold,
                color = MapControlIcon,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Vertical pill of zoom preset buttons.
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

    Surface(
        shape = RoundedCornerShape(GccTheme.spacing.sm),  // 8dp Fibonacci radius
        shadowElevation = GccTheme.elevation.raised,
        color = MapControlBg,
    ) {
        Column {
            presets.forEachIndexed { index, preset ->
                val isActive = index == activeIndex
                val bgColor = if (isActive) GccGreen else Color.Transparent
                val textColor = if (isActive) Color.White else MapControlIcon

                Box(
                    modifier = Modifier
                        .width(GccTheme.dimensions.avatarSm)  // 34dp
                        .clickable { onPresetSelect(preset.zoom) }
                        .background(bgColor)
                        .padding(
                            horizontal = GccTheme.spacing.xxs,
                            vertical = GccTheme.spacing.xs,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preset.label,
                        fontSize = 8.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }

                // Divider between presets (except last)
                if (index < presets.lastIndex && !isActive && index + 1 != activeIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(DividerColor),
                    )
                }
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

    // Clamp to reasonable pixel range (21..144dp as Fibonacci-adjacent)
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
 *
 * Returns a pair of (distanceInMeters, displayLabel).
 */
private fun selectScaleDistance(metersPerPixel: Double): Pair<Double, String> {
    // Target bar width ~80px
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
