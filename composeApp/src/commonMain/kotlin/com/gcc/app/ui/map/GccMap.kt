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
import com.gcc.shared.model.AffectedBlock
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
 * @param affectedBlocks      Blocks in any non-trivial state (funded, scheduled, cleaning, completed) to render
 *                            as an intensity-weighted heatmap at low zoom and as colored polygons at street zoom.
 *                            Drives the dynamic "affected areas" view in the app.
 * @param onAffectedBlockTap  Callback fired with a block id when the user taps a polygon.
 * @param content             Overlay content (composables positioned in the Box)
 */
@Composable
expect fun GccMap(
    modifier: Modifier = Modifier,
    centerLat: Double = MapStyles.WORLD_CENTER_LAT,
    centerLng: Double = MapStyles.WORLD_CENTER_LNG,
    zoom: Double = MapStyles.ZOOM_WORLD,
    currentLocation: Coordinates? = null,
    gpsHeading: Float? = null,
    enableGestures: Boolean = false,
    routeCoordinates: List<List<Coordinates>> = emptyList(),
    /** Indices into [routeCoordinates] of the worker's assigned route
     *  boundaries, if any — rendered with a distinct (thicker/brighter)
     *  highlight so they still stand out now that they're drawn as part of
     *  the union rather than exclusively (see MapFullScreen's
     *  mapRouteCoordinates). */
    activeRouteIndices: List<Int> = emptyList(),
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
    /** Snap the camera instantly to center/zoom changes (no easing, no
     *  min-delta threshold) — REQUIRED under editor overlays that redraw
     *  synchronously, or the map visibly detaches during zoom/pan. */
    instantCamera: Boolean = false,
    /** Editor mode: never synthesize the 8m-inset inner ring for loops with
     *  no authored exclusion zones (an uneditable ghost in the editor). */
    innerBoundaryFallback: Boolean = true,
    showGridLines: Boolean = true,
    showControls: Boolean = false,
    statusBarPadding: Boolean = false,
    zoomPresets: List<ZoomPreset> = overviewZoomPresets,
    cameraBounds: AreaBounds? = null,
    mapMode: MapMode = MapMode.MY_AREA,
    imageStars: List<Coordinates> = emptyList(),
    routePolygons: List<Pair<Int, List<Coordinates>>> = emptyList(),
    /** Ledger coverage cells for the foundational clean/stale/fog tiles. */
    coverageCells: List<com.gcc.shared.model.MapCoverageCell> = emptyList(),
    /** Authored inner-boundary rings (route-v2); empty → parallel inset. */
    innerBoundaryRings: List<List<Coordinates>> = emptyList(),
    /** Draw the exclusion zones and the tinted scan corridor between the outer
     *  and inner rings. Defaults true for the screens whose whole purpose is a
     *  single block (route detail, boundary editor, proposal preview). The
     *  browsing map passes FALSE unless a block is selected: exclusion detail
     *  on every block at once is noise you cannot act on, and it multiplies as
     *  blocks are added. */
    showInnerBoundary: Boolean = true,
    /** Hide block outlines below [MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM] — the
     *  zoom where the far-zoom choropleth finishes fading out, so the two
     *  systems hand off with no overlap and no gap.
     *
     *  Opt-in, because it is only correct for the browsing map. Surfaces that
     *  deliberately frame ONE block (route detail, boundary editor, proposal
     *  preview) and the dashboard preview — which sits at ZOOM_NEIGHBORHOOD
     *  (14.0), just below the threshold — must keep drawing their outline at
     *  whatever zoom they chose. */
    gateBlockOutlinesByZoom: Boolean = false,
    /** Explorer scan-state per community area ("clean"|"dirty"|"stale"). */
    explorerAreaStates: Map<String, String> = emptyMap(),
    affectedBlocks: List<AffectedBlock> = emptyList(),
    onAffectedBlockTap: ((blockId: Int) -> Unit)? = null,
    /** Tap on a scanned coverage tile (fresh or stale) — receives the
     *  locationCellId carried in the tile's feature properties. */
    onCoverageCellTap: ((locationCellId: String) -> Unit)? = null,
    /** Reports live camera zoom (throttled to actual changes) so callers can
     *  zoom-gate chrome like the heat legend to when the layer is on screen. */
    onZoomChanged: ((Double) -> Unit)? = null,
    /** Reports the live camera target while the user pans. Map data fetches
     *  must follow this viewport target rather than remaining anchored to the
     *  worker's service-area home center. */
    onCenterChanged: ((Coordinates) -> Unit)? = null,
    /** World observations carrying widthMeters/yawDegrees (Pillar 3) — driven
     *  into true-scale oriented footprint polygons at close zoom (>=17),
     *  rendered under the pin/glyph layers. Empty → no footprint layer. */
    observationFootprints: List<com.gcc.shared.model.WorldObservation> = emptyList(),
    /** Explorer "Needs scanning" lens (P5): renders [coverageCells] whose
     *  `priority` > 0 as one amber→red choropleth FillLayer atop the
     *  standard coverage tint, instead of it (spec Pillar 5). False by
     *  default — an opt-in toggle in Explorer mode, not automatic. */
    showNeedsScanningLens: Boolean = false,
    /** Tap on a close-zoom litter footprint (Pillar 8 Task 2, Part C) —
     *  receives the tapped [com.gcc.shared.model.WorldObservation.id]. */
    onObservationTap: ((observationId: Int) -> Unit)? = null,
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
