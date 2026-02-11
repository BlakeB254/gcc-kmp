package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.MapGpsBlue
import com.gcc.shared.model.Coordinates
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Data class for a long-press context pin marker.
 *
 * @param canvasX  X position on the canvas (pre-transform)
 * @param canvasY  Y position on the canvas (pre-transform)
 * @param lat      Latitude of the pin
 * @param lng      Longitude of the pin
 */
data class ContextPin(
    val canvasX: Float,
    val canvasY: Float,
    val lat: Double,
    val lng: Double,
)

/**
 * Cross-platform map composable. Desktop uses Canvas fallback,
 * mobile platforms will use MapLibre when Kotlin 2.2+ is available.
 *
 * @param modifier           Standard Compose modifier
 * @param centerLat          Map center latitude
 * @param centerLng          Map center longitude
 * @param zoom               Base zoom level (Mercator tile zoom)
 * @param currentLocation    GPS coordinates for the blue dot
 * @param gpsHeading         Compass heading in degrees (0=N, 90=E) for directional cone
 * @param enableGestures     Enable pan/zoom/tap gestures
 * @param routeCoordinates   Lists of route polyline coordinates
 * @param animateRoutes      Enable animated dash drawing on routes
 * @param bountyLocations    Coordinates for bounty zone pulse markers
 * @param onAreaTap          Callback when a community area is tapped
 * @param onContextPin       Callback when a long-press pin is placed (lat, lng)
 * @param showBuildings      Show isometric buildings at street zoom
 * @param showGridLines      Show grid lines at route zoom and above
 * @param showControls       Show zoom controls (+ / - buttons and preset bar) and scale bar
 * @param statusBarPadding   Add status bar padding to controls (use in full-screen map)
 * @param zoomPresets        Which zoom presets to show (defaults to overview: City/Area)
 * @param content            Overlay content (composables positioned in the Box)
 */
@Composable
expect fun GccMap(
    modifier: Modifier = Modifier,
    centerLat: Double = MapStyles.CHICAGO_CENTER_LAT,
    centerLng: Double = MapStyles.CHICAGO_CENTER_LNG,
    zoom: Double = MapStyles.ZOOM_NEIGHBORHOOD,
    currentLocation: Coordinates? = null,
    gpsHeading: Float? = null,
    enableGestures: Boolean = false,
    routeCoordinates: List<List<Coordinates>> = emptyList(),
    animateRoutes: Boolean = false,
    bountyLocations: List<Coordinates> = emptyList(),
    onAreaTap: ((CommunityArea) -> Unit)? = null,
    onContextPin: ((lat: Double, lng: Double) -> Unit)? = null,
    showBuildings: Boolean = true,
    showGridLines: Boolean = true,
    showControls: Boolean = false,
    statusBarPadding: Boolean = false,
    zoomPresets: List<ZoomPreset> = overviewZoomPresets,
    content: @Composable BoxScope.() -> Unit = {},
)

@Composable
fun GpsDot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MapGpsBlue.copy(alpha = 0.3f))
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(MapGpsBlue)
        )
    }
}

/**
 * Simple Mercator projection converting lat/lng to pixel coordinates.
 */
fun latLngToPixel(
    lat: Double, lng: Double,
    centerLat: Double, centerLng: Double,
    zoom: Double, canvasWidth: Float, canvasHeight: Float,
): Offset {
    val scale = (256.0 * (1 shl zoom.toInt())).toFloat()
    val worldX = ((lng + 180.0) / 360.0 * scale).toFloat()
    val worldY = ((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
    val centerWorldX = ((centerLng + 180.0) / 360.0 * scale).toFloat()
    val centerWorldY = ((1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale).toFloat()
    return Offset(
        x = canvasWidth / 2f + (worldX - centerWorldX),
        y = canvasHeight / 2f + (worldY - centerWorldY),
    )
}

/**
 * Inverse Mercator: convert pixel Y to latitude.
 */
fun pixelToLat(
    pixelY: Float,
    centerLat: Double,
    zoom: Double,
    canvasHeight: Float,
): Double {
    val scale = (256.0 * (1 shl zoom.toInt()))
    val centerWorldY = (1.0 - ln(tan(centerLat * PI / 180.0) + 1.0 / cos(centerLat * PI / 180.0)) / PI) / 2.0 * scale
    val worldY = centerWorldY + (pixelY - canvasHeight / 2.0)
    val n = PI - 2.0 * PI * worldY / scale
    return 180.0 / PI * kotlin.math.atan(0.5 * (kotlin.math.exp(n) - kotlin.math.exp(-n)))
}

/**
 * Inverse Mercator: convert pixel X to longitude.
 */
fun pixelToLng(
    pixelX: Float,
    centerLng: Double,
    zoom: Double,
    canvasWidth: Float,
): Double {
    val scale = (256.0 * (1 shl zoom.toInt()))
    val centerWorldX = (centerLng + 180.0) / 360.0 * scale
    val worldX = centerWorldX + (pixelX - canvasWidth / 2.0)
    return worldX / scale * 360.0 - 180.0
}

/** Format a coordinate value to 4 decimal places (no String.format). */
internal fun formatCoord(value: Double): String {
    val sign = if (value < 0) "-" else ""
    val absVal = abs(value)
    val intPart = absVal.toInt()
    val fracPart = ((absVal - intPart) * 10000).toInt()
    val fracStr = fracPart.toString().padStart(4, '0')
    return "$sign$intPart.$fracStr"
}
