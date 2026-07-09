package com.gcc.app.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.Coordinates
import org.maplibre.spatialk.geojson.Position
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.em
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.HeatmapLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android actual implementation of GccMap — MapLibre Compose with real tile-based maps.
 */
@Composable
actual fun GccMap(
    modifier: Modifier,
    centerLat: Double,
    centerLng: Double,
    zoom: Double,
    currentLocation: Coordinates?,
    gpsHeading: Float?,
    enableGestures: Boolean,
    routeCoordinates: List<List<Coordinates>>,
    activeRouteIndices: List<Int>,
    animateRoutes: Boolean,
    bountyLocations: List<Coordinates>,
    bounties: List<com.gcc.shared.model.Bounty>,
    spots: List<CommunityReport>,
    onAreaTap: ((CommunityArea) -> Unit)?,
    onRouteTap: ((routeIndex: Int) -> Unit)?,
    onSpotTap: ((spotId: Int) -> Unit)?,
    onBountyTap: ((bountyId: Int) -> Unit)?,
    onContextPin: ((lat: Double, lng: Double) -> Unit)?,
    showBuildings: Boolean,
    instantCamera: Boolean,
    innerBoundaryFallback: Boolean,
    showGridLines: Boolean,
    showControls: Boolean,
    statusBarPadding: Boolean,
    zoomPresets: List<ZoomPreset>,
    cameraBounds: AreaBounds?,
    mapMode: MapMode,
    imageStars: List<Coordinates>,
    routePolygons: List<Pair<Int, List<Coordinates>>>,
    coverageCells: List<com.gcc.shared.model.MapCoverageCell>,
    innerBoundaryRings: List<List<Coordinates>>,
    explorerAreaStates: Map<String, String>,
    affectedBlocks: List<com.gcc.shared.model.AffectedBlock>,
    onAffectedBlockTap: ((blockId: Int) -> Unit)?,
    onCoverageCellTap: ((String) -> Unit)?,
    onZoomChanged: ((Double) -> Unit)?,
    observationFootprints: List<com.gcc.shared.model.WorldObservation>,
    // Unused on this platform: Android's GccMap actual doesn't render the
    // coverage-tile FillLayer stack at all yet (pre-existing gap, out of
    // scope here — see iOS actual for the implementation this lens sits
    // alongside).
    @Suppress("UNUSED_PARAMETER") showNeedsScanningLens: Boolean,
    // Unused on this platform: Android's GccMap actual doesn't render the
    // observation-footprint layer at all yet (same pre-existing gap as
    // showNeedsScanningLens above — see iOS actual for the tap wiring this
    // sits alongside, Pillar 8 Task 2 Part C).
    @Suppress("UNUSED_PARAMETER") onObservationTap: ((observationId: Int) -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    // Memoize GeoJSON strings — rebuilt only when inputs change
    val communityAreasJson = remember { buildCommunityAreasGeoJson() }
    val serviceAreaJson = remember { buildServiceAreaGeoJson() }
    val stateJson = remember { buildStateBoundaryGeoJson() }
    val cityJson = remember { buildCityBoundaryGeoJson() }
    val spotCleanZoneJson = remember { buildSpotCleanZoneBaseGeoJson() }
    val routesJson = remember(routeCoordinates) { buildRoutesGeoJson(routeCoordinates) }
    val routePolygonsJson = remember(routeCoordinates) { buildRoutePolygonsGeoJson(routeCoordinates) }
    val labelsJson = remember { buildCommunityAreaLabelsGeoJson() }
    val bountyJson = remember(bounties) { buildBountyMarkersGeoJson(bounties) }
    val spotJson = remember(spots) { buildSpotMarkersGeoJson(spots) }
    val bountiedSpotJson = remember(spots) { buildBountiedSpotMarkersGeoJson(spots) }

    // Area tap state for tooltip overlay
    var tappedArea by remember { mutableStateOf<CommunityArea?>(null) }

    // Camera
    val camera = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = centerLat, longitude = centerLng),
            zoom = zoom,
        )
    )

    // Soft camera bounding — snap back if user pans outside bounds
    if (cameraBounds != null) {
        val padding = 0.15
        val latSpan = cameraBounds.north - cameraBounds.south
        val lngSpan = cameraBounds.east - cameraBounds.west
        val paddedBounds = remember(cameraBounds) {
            AreaBounds(
                north = cameraBounds.north + latSpan * padding,
                south = cameraBounds.south - latSpan * padding,
                east = cameraBounds.east + lngSpan * padding,
                west = cameraBounds.west - lngSpan * padding,
            )
        }

        LaunchedEffect(paddedBounds) {
            snapshotFlow { camera.position }
                .collect { pos ->
                    val lat = pos.target.latitude
                    val lng = pos.target.longitude
                    val clampedLat = lat.coerceIn(paddedBounds.south, paddedBounds.north)
                    val clampedLng = lng.coerceIn(paddedBounds.west, paddedBounds.east)
                    if (clampedLat != lat || clampedLng != lng) {
                        camera.animateTo(
                            finalPosition = pos.copy(
                                target = Position(latitude = clampedLat, longitude = clampedLng)
                            ),
                            duration = 300.milliseconds,
                        )
                    }
                }
        }
    }

    val scaleBarZoom by remember { derivedStateOf { camera.position.zoom } }

    // Report live zoom to the caller (legend gating). snapshotFlow keeps the
    // camera read out of composition — same flicker guard as the bounds check.
    if (onZoomChanged != null) {
        LaunchedEffect(onZoomChanged) {
            snapshotFlow { camera.position.zoom }
                .collect { onZoomChanged(it) }
        }
    }

    Box(modifier = modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = camera,
            baseStyle = BaseStyle.Uri(MapStyles.STYLE_URL),
            options = MapOptions(
                gestureOptions = GestureOptions(
                    isScrollEnabled = enableGestures,
                    isZoomEnabled = enableGestures,
                    isRotateEnabled = false,
                    isTiltEnabled = false,
                ),
                ornamentOptions = OrnamentOptions(
                    isLogoEnabled = false,
                    isScaleBarEnabled = false,
                    isCompassEnabled = false,
                    isAttributionEnabled = false,
                ),
            ),
        ) {
            // --- Sources ---
            val stateSrc = rememberGeoJsonSource(GeoJsonData.JsonString(stateJson))
            val citySrc = rememberGeoJsonSource(GeoJsonData.JsonString(cityJson))
            val areaSrc = rememberGeoJsonSource(GeoJsonData.JsonString(communityAreasJson))
            val serviceAreaSrc = rememberGeoJsonSource(GeoJsonData.JsonString(serviceAreaJson))
            val spotCleanZoneSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotCleanZoneJson))
            val routesSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routesJson))
            val routePolygonsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routePolygonsJson))
            // --- Layers (bottom to top) ---
            // Boundary lines use zoom-based width: visible at overview, fade at street level
            // to avoid obscuring the base map's street names and POI labels.

            // State outline (Illinois) — visible at zoom 6-14, fades out above
            LineLayer(
                id = MapStyles.LAYER_STATE_OUTLINE,
                source = stateSrc,
                color = const(MapStyles.STATE_OUTLINE_COLOR),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    6 to const(0.5.dp),
                    11 to const(1.dp),
                    14 to const(0.5.dp),
                    16 to const(0.dp),
                ),
            )

            // City outline glow — wide semi-transparent halo for depth
            LineLayer(
                id = MapStyles.LAYER_CITY_OUTLINE_GLOW,
                source = citySrc,
                color = const(MapStyles.CITY_OUTLINE_GLOW_COLOR.copy(alpha = 0.2f)),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    8 to const(2.dp),
                    11 to const(8.dp),
                    14 to const(4.dp),
                    16 to const(0.dp),
                ),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )

            // City outline (Chicago) — pronounced deep blue-gray
            LineLayer(
                id = MapStyles.LAYER_CITY_OUTLINE,
                source = citySrc,
                color = const(MapStyles.CITY_OUTLINE_COLOR),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    8 to const(1.dp),
                    11 to const(3.dp),
                    14 to const(2.dp),
                    16 to const(0.dp),
                ),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )

            // Service area fill — hidden while the global heatmap baseline is active.
            FillLayer(
                id = "service-area-fill",
                source = serviceAreaSrc,
                color = const(MapStyles.GEOFENCE_FILL_COLOR),
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    10 to const(0f),
                    15 to const(0f),
                    16 to const(0.5f),
                    17 to const(1f),
                ),
            )

            // Community area outlines — visible at zoom 10-15, fades out above
            LineLayer(
                id = MapStyles.LAYER_COMMUNITY_AREAS_OUTLINE,
                source = areaSrc,
                color = const(MapStyles.COMMUNITY_AREA_STROKE_COLOR),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(0.5.dp),
                    12 to const(1.5.dp),
                    15 to const(0.5.dp),
                    17 to const(0.dp),
                ),
            )

            // Community area tap target (transparent fill for click detection)
            FillLayer(
                id = MapStyles.LAYER_COMMUNITY_AREAS,
                source = areaSrc,
                color = const(Color.Transparent),
                onClick = { features ->
                    val name = features.firstOrNull()?.properties?.get("name") as? String
                    val area = chicagoCommunityAreas.find {
                        it.displayName.replace("\n", " ") == name
                    }
                    tappedArea = area
                    area?.let { onAreaTap?.invoke(it) }
                    if (area != null) ClickResult.Consume else ClickResult.Pass
                },
            )

            // Community area name labels — at centroids, visible zoom 11-15
            val labelsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(labelsJson))
            @Suppress("UNCHECKED_CAST")
            val nameExpr = feature["name"] as org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.StringValue>
            SymbolLayer(
                id = MapStyles.LAYER_COMMUNITY_LABELS,
                source = labelsSrc,
                textField = format(span(nameExpr)),
                textSize = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(0f).em,
                    11 to const(0.6f).em,
                    13 to const(0.8f).em,
                    15 to const(0.65f).em,
                    16 to const(0f).em,
                ),
                textColor = const(Color(0xFF4B5563)),
                textHaloColor = const(Color.White),
                textHaloWidth = const(1.5.dp),
                textAllowOverlap = const(false),
                textIgnorePlacement = const(false),
            )

            // Note: the planet-spanning clean-zone-base FillLayer that used
            // to live here has been removed for parity with the simplified
            // website maps. The source was a polygon covering the entire
            // earth ([-180,-85] to [180,85]); painting it as a tint hid
            // OpenFreeMap's road and label layers underneath. Spot density
            // is now conveyed by dot clustering, not an overlay wash.

            // ── Spot density heat surface (BELOW the route stack) ──
            // Heat is texture, routes are structure: the wash renders under
            // the green loops so route opportunities stay readable at
            // overview zoom. Opacity ramps to 0 at SPOT_HEATMAP_FADE_OUT_ZOOM
            // — a real crossfade with the spot dots (which fade in from
            // SPOT_DOTS_MIN_ZOOM) instead of the old hard clip at 12.99.
            val spotSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotJson))
            val spotHeatWeightExpr = feature["heatWeight"] as org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.FloatValue>

            // Terrain-style field: broad green/yellow density that reads at
            // overview zoom before hot cores resolve into individual spots.
            HeatmapLayer(
                id = MapStyles.LAYER_SPOT_THERMAL_FIELD,
                source = spotSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM.toFloat(),
                color = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = org.maplibre.compose.expressions.dsl.heatmapDensity(),
                    0.0 to const(Color(0x00000000)),
                    0.01 to const(MapStyles.SPOT_HEAT_EDGE.copy(alpha = 0.62f)),
                    0.08 to const(MapStyles.SPOT_HEAT_EDGE),
                    0.18 to const(MapStyles.SPOT_HEAT_LOW),
                    0.34 to const(MapStyles.SPOT_HEAT_MEDIUM),
                    0.54 to const(MapStyles.SPOT_HEAT_HIGH),
                    0.74 to const(MapStyles.SPOT_HEAT_EXTREME),
                    1.0 to const(MapStyles.SPOT_HEAT_NEAR_PEAK),
                ),
                weight = spotHeatWeightExpr,
                intensity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.20f),
                    4 to const(0.32f),
                    6 to const(0.50f),
                    8 to const(0.72f),
                    10 to const(0.92f),
                    12 to const(1.05f),
                    13 to const(0.90f),
                    13.75 to const(0.55f),
                    14.1 to const(0.45f),
                    MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(0.38f),
                ),
                // Smaller low-zoom radius so concentrated clusters keep an organic
                // shape instead of melting into a perfect-circle bullseye (mirrors web).
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(18.dp),
                    4 to const(30.dp),
                    6 to const(50.dp),
                    8 to const(74.dp),
                    10 to const(100.dp),
                    12 to const(128.dp),
                    13 to const(120.dp),
                    13.75 to const(88.dp),
                    14.1 to const(68.dp),
                    MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(54.dp),
                ),
                // Capped peak at ~0.45 so the OpenFreeMap base style's road
                // lines and street labels remain readable underneath. Previously
                // peaked at 0.98 which blanketed all street detail.
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.32f),
                    4 to const(0.40f),
                    8 to const(0.45f),
                    12 to const(0.45f),
                    13 to const(0.35f),
                    13.75 to const(0.20f),
                    14.1 to const(0.10f),
                    MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(0f),
                ),
            )

            // ── Spot scrim: neutral off-white tint beneath heatmap ──
            // Planet-spanning fill sourced from the same clean-zone GeoJSON.
            // Fades from 0.55 opacity at overview zoom to 0 by
            // SPOT_HEATMAP_FADE_OUT_ZOOM so it doesn't compete with street
            // labels at close zoom. Replaces the former green CLEAN_BASE_COLOR.
            // Gated on spots being present so toggle-off and zero-report areas
            // don't show a gray wash (mirrors the web version's guard).
            if (spots.isNotEmpty()) {
                FillLayer(
                    id = MapStyles.LAYER_SPOT_CLEAN_ZONE_BASE,
                    source = spotCleanZoneSrc,
                    color = const(MapStyles.SPOT_SCRIM_COLOR),
                    opacity = interpolate(
                        type = org.maplibre.compose.expressions.dsl.linear(),
                        input = zoom(),
                        MapStyles.SPOT_HEATMAP_MIN_ZOOM to const(0.55f),
                        MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(0f),
                    ),
                )
            }

            HeatmapLayer(
                id = MapStyles.LAYER_SPOT_HEATMAP,
                source = spotSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = MapStyles.SPOT_HEATMAP_MAX_ZOOM.toFloat(),
                color = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = org.maplibre.compose.expressions.dsl.heatmapDensity(),
                    0.0 to const(Color(0x00000000)),
                    0.04 to const(Color(0x00000000)),
                    0.12 to const(MapStyles.SPOT_HEAT_MEDIUM.copy(alpha = 0.78f)),
                    0.24 to const(MapStyles.SPOT_HEAT_HIGH),
                    0.4 to const(MapStyles.SPOT_HEAT_EXTREME),
                    0.62 to const(MapStyles.SPOT_HEAT_NEAR_PEAK),
                    1.0 to const(MapStyles.SPOT_HEAT_PEAK),
                ),
                weight = spotHeatWeightExpr,
                intensity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.05f),
                    5 to const(0.22f),
                    8 to const(0.52f),
                    10 to const(0.78f),
                    12 to const(0.92f),
                    13 to const(0.7f),
                    13.75 to const(0.32f),
                    14.1 to const(0.26f),
                    MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(0.2f),
                ),
                // Tighter low-zoom radius keeps the hot core organic, not a solid disk.
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(7.dp),
                    5 to const(14.dp),
                    8 to const(26.dp),
                    10 to const(42.dp),
                    12 to const(66.dp),
                    13 to const(76.dp),
                    13.75 to const(40.dp),
                    14.1 to const(28.dp),
                    MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(22.dp),
                ),
                // Capped at ~0.55 peak so street lines and labels show through.
                // Old peak was 0.98 which (stacked with SPOT_THERMAL_FIELD)
                // hid the entire OpenFreeMap base style at neighborhood zoom.
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.12f),
                    5 to const(0.32f),
                    8 to const(0.50f),
                    10 to const(0.55f),
                    12 to const(0.55f),
                    13 to const(0.44f),
                    13.75 to const(0.18f),
                    14.1 to const(0.10f),
                    MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM to const(0f),
                ),
            )

            // ── Route polygon fill — invisible tap target ──
            // Was previously a solid green fill (zoom 10-14 dominant, fading
            // by 16) but it overlapped the affected-block heatmap and the
            // clean-zone base layer so it read as a duplicated heatmap. The
            // line stack below (glow + casing + core) carries the route
            // visual; this fill stays ONLY for tap targeting at all zooms.
            FillLayer(
                id = MapStyles.LAYER_ROUTE_FILL,
                source = routePolygonsSrc,
                color = const(MapStyles.ROUTE_FILL_COLOR),
                opacity = const(0.001f),
                onClick = if (onRouteTap != null) { features ->
                    val idx = (features.firstOrNull()?.properties?.get("routeIndex") as? Number)?.toInt()
                    if (idx != null) {
                        onRouteTap(idx)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                } else null,
            )

            // ── Route rendering: 3-layer stack (glow → casing → core) ──
            // All widths scale with zoom: thin at overview, full at street level.

            // Route glow — wide semi-transparent halo for emphasis
            LineLayer(
                id = MapStyles.LAYER_ROUTE_GLOW,
                source = routesSrc,
                color = const(MapStyles.ROUTE_GLOW_COLOR.copy(alpha = MapStyles.ROUTE_GLOW_OPACITY)),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(1.dp),
                    13 to const(3.dp),
                    15 to const(8.dp),
                    17 to const(14.dp),
                ),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )

            // Route casing — dark border for depth and contrast
            LineLayer(
                id = MapStyles.LAYER_ROUTE_CASING,
                source = routesSrc,
                color = const(MapStyles.ROUTE_CASING_COLOR),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(0.5.dp),
                    13 to const(2.dp),
                    15 to const(5.dp),
                    17 to const(8.dp),
                ),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )

            // Route core — bright green main line
            LineLayer(
                id = MapStyles.LAYER_ROUTE_LINE,
                source = routesSrc,
                color = const(GccGreen),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(0.5.dp),
                    13 to const(1.5.dp),
                    15 to const(3.dp),
                    17 to const(5.dp),
                ),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
                onClick = if (onRouteTap != null) { features ->
                    val idx = (features.firstOrNull()?.properties?.get("routeIndex") as? Number)?.toInt()
                    if (idx != null) {
                        onRouteTap(idx)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                } else null,
            )

            // ── Image location star markers: amber dots for display image locations ──
            val imageStarJson = remember(imageStars) {
                if (imageStars.isEmpty()) """{"type":"FeatureCollection","features":[]}"""
                else {
                    val features = imageStars.joinToString(",") { coord ->
                        """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${coord.lng},${coord.lat}]}}"""
                    }
                    """{"type":"FeatureCollection","features":[$features]}"""
                }
            }
            val imageStarSrc = rememberGeoJsonSource(GeoJsonData.JsonString(imageStarJson))

            // White outer ring
            CircleLayer(
                id = "image-star-outer",
                source = imageStarSrc,
                color = const(Color.White),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(4.dp),
                    14 to const(7.dp),
                    17 to const(14.dp),
                ),
            )

            // Amber fill
            CircleLayer(
                id = "image-star-inner",
                source = imageStarSrc,
                color = const(Color(0xFFFFB300)),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(3.dp),
                    14 to const(5.dp),
                    17 to const(12.dp),
                ),
                strokeColor = const(Color(0xFFF57F17)),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(0.5.dp),
                    14 to const(1.dp),
                    17 to const(1.5.dp),
                ),
            )

            // ── Bounty markers: red dots with radius and amount labels ──
            val bountySrc = rememberGeoJsonSource(GeoJsonData.JsonString(bountyJson))

            CircleLayer(
                id = MapStyles.LAYER_BOUNTY_RADIUS,
                source = bountySrc,
                color = const(MapStyles.BOUNTY_RADIUS_FILL),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(5.dp),
                    17 to const(40.dp),
                ),
            )

            CircleLayer(
                id = MapStyles.LAYER_BOUNTY_DOTS,
                source = bountySrc,
                color = const(MapStyles.BOUNTY_DOT_COLOR),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(3.dp),
                    12 to const(4.dp),
                    14 to const(5.dp),
                ),
                strokeColor = const(Color.White),
                strokeWidth = const(1.5.dp),
            )

            @Suppress("UNCHECKED_CAST")
            val amountExpr = feature["amountLabel"] as org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.StringValue>
            SymbolLayer(
                id = MapStyles.LAYER_BOUNTY_LABELS,
                source = bountySrc,
                textField = format(span(amountExpr)),
                textSize = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(0f).em,
                    13 to const(0.6f).em,
                    15 to const(0.8f).em,
                ),
                textColor = const(MapStyles.BOUNTY_DOT_COLOR),
                textHaloColor = const(Color.White),
                textHaloWidth = const(1.5.dp),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )

            // ── Spot report markers ──
            // (spotSrc + the heat field + scrim moved BELOW the route stack:
            // heat is texture, routes are structure — green loops must stay
            // readable over the wash at overview zooms.)

            // Soft halos are STREET-LEVEL detail only: at neighborhood zoom a
            // dense scan area stacks dozens of 18dp halos into an opaque
            // orange blob that buries the route line. Once pins are sparse on
            // screen they earn the ink.
            CircleLayer(
                id = MapStyles.LAYER_SPOT_MARKER_HALOS,
                source = spotSrc,
                minZoom = MapStyles.SPOT_DETAIL_MIN_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_MARKER_COLOR.copy(alpha = 0.10f)),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    15.5 to const(8.dp),
                    17 to const(12.dp),
                    19 to const(15.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    15.5 to const(0f),
                    16 to const(0.6f),
                    17 to const(0.8f),
                ),
            )

            val bountiedSpotSrc = rememberGeoJsonSource(GeoJsonData.JsonString(bountiedSpotJson))
            CircleLayer(
                id = MapStyles.LAYER_BOUNTIED_SPOT_HALOS,
                source = bountiedSpotSrc,
                minZoom = MapStyles.SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.Transparent),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(7.dp),
                    15 to const(12.dp),
                    17 to const(17.dp),
                ),
                strokeColor = const(MapStyles.BOUNTY_DOT_COLOR),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(1.dp),
                    15 to const(2.dp),
                    17 to const(3.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0f),
                    14 to const(0.85f),
                    17 to const(1f),
                ),
            )

            // Spot tap target — invisible larger circle for close-zoom report actions.
            CircleLayer(
                id = "spot-tap-target",
                source = spotSrc,
                minZoom = MapStyles.SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.Transparent),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(16.dp),
                    16 to const(20.dp),
                ),
                onClick = if (onSpotTap != null) { features ->
                    val id = (features.firstOrNull()?.properties?.get("id") as? Number)?.toInt()
                    if (id != null) {
                        onSpotTap(id)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                } else null,
            )

            // Small ADDITIVE dots — fade in across the heat crossfade window,
            // capped below full opacity so overlapping reports in a dense
            // area sum into visibly darker orange (density reads without an
            // opaque mass) and the route line stays legible beneath.
            CircleLayer(
                id = MapStyles.LAYER_SPOT_MARKERS,
                source = spotSrc,
                minZoom = MapStyles.SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_MARKER_COLOR),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(3.dp),
                    15 to const(4.5.dp),
                    16 to const(6.dp),
                    17 to const(7.dp),
                    20 to const(9.dp),
                ),
                strokeColor = const(Color.White),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(0.8.dp),
                    16 to const(1.2.dp),
                    18 to const(1.6.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0f),
                    13.5 to const(0.35f),
                    14.5 to const(0.75f),
                    16 to const(0.9f),
                ),
                onClick = if (onSpotTap != null) { features ->
                    val id = (features.firstOrNull()?.properties?.get("id") as? Number)?.toInt()
                    if (id != null) {
                        onSpotTap(id)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                } else null,
            )

            // ── Affected-block heatmap + polygon fills ──
            //
            // At overview zoom (11-13) the block centers glow as a density
            // heatmap, at street zoom (14+) each block polygon fills with
            // its status color at an alpha keyed to the intensity score.
            // Tapping a polygon fires onAffectedBlockTap(blockId).
            if (affectedBlocks.isNotEmpty()) {
                val affectedJson = remember(affectedBlocks) { affectedBlocksToGeoJson(affectedBlocks) }
                val affectedSrc = rememberGeoJsonSource(GeoJsonData.JsonString(affectedJson))

                // Status-colored fill — per-block polygon visible at zoom 13+
                FillLayer(
                    id = "affected-block-fill",
                    source = affectedSrc,
                    // Warm amber — reads against the clean-zone green base
                    // wash so active blocks pop as "something is happening
                    // here" rather than blending into the baseline.
                    color = const(Color(0xFFFF6B35)),
                    opacity = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        10 to const(0f),
                        11 to const(0.14f),
                        12 to const(0.34f),
                        13 to const(0.46f),
                        14 to const(0.42f),
                        15 to const(0.5f),
                        17 to const(0.62f),
                    ),
                    onClick = if (onAffectedBlockTap != null) { features ->
                        val id = (features.firstOrNull()?.properties?.get("blockId") as? Number)?.toInt()
                        if (id != null) {
                            onAffectedBlockTap(id)
                            ClickResult.Consume
                        } else {
                            ClickResult.Pass
                        }
                    } else null,
                )

                // Overview-zoom density glow — a large soft circle per block
                // center, weighted by intensity. Fades out at street zoom
                // where the per-block polygons take over.
                CircleLayer(
                    id = "affected-block-heatmap",
                    source = affectedSrc,
                    minZoom = 8f,
                    maxZoom = 14f,
                    color = const(Color(0xFFFF6B35)), // warm amber accent — reads against both the green baseline map and any polygon fill
                    radius = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        8 to const(4.dp),
                        10 to const(8.dp),
                        11 to const(14.dp),
                        12 to const(22.dp),
                        13 to const(16.dp),
                        14 to const(0.dp),
                    ),
                    opacity = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        8 to const(0.12f),
                        10 to const(0.26f),
                        11 to const(0.36f),
                        12 to const(0.46f),
                        13 to const(0.32f),
                        14 to const(0f),
                    ),
                )
            }

            // ── User location blue dot (Apple Maps style) ──
            if (currentLocation != null) {
                val userLocationJson = remember(currentLocation) {
                    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${currentLocation.lng},${currentLocation.lat}]},"properties":{}}]}"""
                }
                val userLocationSrc = rememberGeoJsonSource(GeoJsonData.JsonString(userLocationJson))

                // Directional cone showing which way the user is facing.
                // Replaces the accuracy halo whenever a compass heading exists.
                if (gpsHeading != null) {
                    val headingConeJson = remember(currentLocation, gpsHeading) {
                        headingConeGeoJson(currentLocation.lat, currentLocation.lng, gpsHeading)
                    }
                    val headingConeSrc = rememberGeoJsonSource(GeoJsonData.JsonString(headingConeJson))
                    FillLayer(
                        id = "user-location-heading-cone",
                        source = headingConeSrc,
                        color = const(Color(0xFF007AFF).copy(alpha = 0.30f)),
                    )
                } else {
                    // Accuracy halo — semi-transparent blue ring
                    CircleLayer(
                        id = "user-location-halo",
                        source = userLocationSrc,
                        color = const(Color(0xFF007AFF).copy(alpha = 0.12f)),
                        radius = interpolate(
                            type = exponential(1.5f),
                            input = zoom(),
                            10 to const(8.dp),
                            14 to const(24.dp),
                            17 to const(48.dp),
                        ),
                    )
                }

                // White border ring
                CircleLayer(
                    id = "user-location-border",
                    source = userLocationSrc,
                    color = const(Color.White),
                    radius = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        10 to const(5.dp),
                        14 to const(7.dp),
                        17 to const(9.dp),
                    ),
                )

                // Blue dot center
                CircleLayer(
                    id = "user-location-dot",
                    source = userLocationSrc,
                    color = const(Color(0xFF007AFF)),
                    radius = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        10 to const(3.5.dp),
                        14 to const(5.dp),
                        17 to const(7.dp),
                    ),
                )
            }
        }

        // Overlay content (composables positioned in the Box)
        content()

        // Zoom controls and scale bar — bottom-left stack
        if (showControls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 72.dp),
            ) {
                ZoomButtons(
                    onZoomIn = {
                        coroutineScope.launch {
                            camera.animateTo(
                                finalPosition = camera.position.copy(
                                    zoom = (camera.position.zoom + 1.0).coerceAtMost(20.0)
                                ),
                                duration = 300.milliseconds,
                            )
                        }
                    },
                    onZoomOut = {
                        coroutineScope.launch {
                            camera.animateTo(
                                finalPosition = camera.position.copy(
                                    zoom = (camera.position.zoom - 1.0).coerceAtLeast(1.0)
                                ),
                                duration = 300.milliseconds,
                            )
                        }
                    },
                )
                ScaleBar(
                    zoom = scaleBarZoom,
                )
            }
        }

        // Area tooltip overlay
        tappedArea?.let { area ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 13.dp),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = GccTheme.elevation.floating,
                color = GccSurface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Text(
                        area.displayName.replace("\n", " "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (area.isServiceArea) {
                        Text(
                            "Active Service Area",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccGreen,
                        )
                    }
                }
            }
        }
    }
}
