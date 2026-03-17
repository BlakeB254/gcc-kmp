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
import com.gcc.shared.geo.MapProjection
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.Coordinates
import kotlin.math.abs

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
    bounties: List<Bounty> = emptyList(),
    spots: List<CommunityReport> = emptyList(),
    onAreaTap: ((CommunityArea) -> Unit)? = null,
    onRouteTap: ((routeIndex: Int) -> Unit)? = null,
    onSpotTap: ((spotId: Int) -> Unit)? = null,
    onBountyTap: ((bountyId: Int) -> Unit)? = null,
    onContextPin: ((lat: Double, lng: Double) -> Unit)? = null,
    showBuildings: Boolean = true,
    showGridLines: Boolean = true,
    showControls: Boolean = false,
    statusBarPadding: Boolean = false,
    zoomPresets: List<ZoomPreset> = overviewZoomPresets,
    cameraBounds: AreaBounds? = null,
    mapMode: MapMode = MapMode.MY_AREA,
    checkpointPhotos: List<Pair<Coordinates, String>> = emptyList(),
    imageStars: List<Coordinates> = emptyList(),
    routePolygons: List<Pair<Int, List<Coordinates>>> = emptyList(),
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
 * Delegates to [MapProjection.latLngToPixel] and wraps the result as a Compose [Offset].
 */
fun latLngToPixel(
    lat: Double, lng: Double,
    centerLat: Double, centerLng: Double,
    zoom: Double, canvasWidth: Float, canvasHeight: Float,
): Offset {
    val (px, py) = MapProjection.latLngToPixel(lat, lng, centerLat, centerLng, zoom, canvasWidth, canvasHeight)
    return Offset(px, py)
}

/**
 * Inverse Mercator: convert pixel Y to latitude.
 * Delegates to [MapProjection.pixelToLat].
 */
fun pixelToLat(
    pixelY: Float,
    centerLat: Double,
    zoom: Double,
    canvasHeight: Float,
): Double = MapProjection.pixelToLat(pixelY, centerLat, zoom, canvasHeight)

/**
 * Inverse Mercator: convert pixel X to longitude.
 * Delegates to [MapProjection.pixelToLng].
 */
fun pixelToLng(
    pixelX: Float,
    centerLng: Double,
    zoom: Double,
    canvasWidth: Float,
): Double = MapProjection.pixelToLng(pixelX, centerLng, zoom, canvasWidth)

// formatCoord is now in MapStyles.kt
