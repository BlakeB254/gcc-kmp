package com.gcc.app.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
import com.gcc.shared.model.Coordinates
import org.maplibre.spatialk.geojson.Position
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.em
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
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

private const val IOS_SPOT_HEAT_FADE_ZOOM = 12.99
private const val IOS_SPOT_DOTS_MIN_ZOOM = 13.0

/**
 * iOS actual implementation of GccMap — MapLibre Compose with real tile-based maps.
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
    showGridLines: Boolean,
    showControls: Boolean,
    statusBarPadding: Boolean,
    zoomPresets: List<ZoomPreset>,
    cameraBounds: AreaBounds?,
    mapMode: MapMode,
    checkpointPhotos: List<Pair<Coordinates, String>>,
    imageStars: List<Coordinates>,
    routePolygons: List<Pair<Int, List<Coordinates>>>,
    coverageCells: List<com.gcc.shared.model.MapCoverageCell>,
    innerBoundaryRings: List<List<Coordinates>>,
    explorerAreaStates: Map<String, String>,
    affectedBlocks: List<com.gcc.shared.model.AffectedBlock>,
    onAffectedBlockTap: ((Int) -> Unit)?,
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
    val routeInnerJson = remember(routeCoordinates, innerBoundaryRings) {
        buildRouteInnerBoundariesGeoJson(routeCoordinates, innerBoundaryRings)
    }
    val routeCorridorJson = remember(routeCoordinates, innerBoundaryRings) {
        buildRouteCorridorGeoJson(routeCoordinates, innerBoundaryRings)
    }
    val coverageStateJsons = remember(coverageCells, spots) {
        if (coverageCells.isEmpty()) null
        else {
            // WORLDWIDE fog-of-war (product direction): every unscanned place
            // on the planet wears the veil; scans punch holes through it.
            // (Public-land masking — water, parks, private parcels — comes
            // from the DGX world model later.)
            buildCoverageStateGeoJsons(
                coverageCells, spots, centerLat, centerLng,
                fogBounds = doubleArrayOf(-85.0, -180.0, 85.0, 180.0),
            )
        }
    }
    val checkpointsJson = remember(routeCoordinates) { buildCheckpointMarkersGeoJson(routeCoordinates) }
    val labelsJson = remember { buildCommunityAreaLabelsGeoJson() }
    val explorerLabelsJson = remember(bounties) { buildExplorerLabelsGeoJson(bounties) }
    val bountyJson = remember(bounties) { buildBountyMarkersGeoJson(bounties) }
    val hasActiveSpots = remember(spots) { spots.any { it.status != CommunityReportStatus.RESOLVED } }
    val spotJson = remember(spots) { buildSpotMarkersGeoJson(spots) }
    val spotDensityHeatEdgeJson = remember(spots) { buildSpotDensityHeatEdgeGeoJson(spots) }
    val spotDensityHeatLowJson = remember(spots) { buildSpotDensityHeatLowGeoJson(spots) }
    val spotDensityHeatMediumJson = remember(spots) { buildSpotDensityHeatMediumGeoJson(spots) }
    val spotDensityHeatHighJson = remember(spots) { buildSpotDensityHeatHighGeoJson(spots) }
    val spotDensityHeatExtremeJson = remember(spots) { buildSpotDensityHeatExtremeGeoJson(spots) }
    val bountiedSpotJson = remember(spots) { buildBountiedSpotMarkersGeoJson(spots) }
    val spotRectJson = remember(spots) { buildSpotRectanglePolygonsGeoJson(spots) }
    val hasHeatActivity = hasActiveSpots

    // Per-type litter glyphs for close-zoom pins. Stable instances so the
    // MapLibre image manager registers each drawable once (keyed by painter
    // identity) instead of re-uploading on every recomposition.
    val litterGlyphPainters = remember {
        LITTER_GLYPH_TYPES.associateWith { LitterGlyphPainter(it) }
    }

    // Area tap state for tooltip overlay
    var tappedArea by remember { mutableStateOf<CommunityArea?>(null) }

    // Camera
    val camera = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = centerLat, longitude = centerLng),
            zoom = zoom,
        )
    )

    // Re-center camera when center coordinates or zoom change (e.g., navigating to a new route)
    LaunchedEffect(centerLat, centerLng, zoom) {
        val currentPos = camera.position
        val targetLat = centerLat
        val targetLng = centerLng
        if (instantCamera) {
            // Editor overlays redraw synchronously against (center, zoom):
            // the map must SNAP with zero threshold or geometry visibly
            // detaches from the basemap during zoom/pan.
            camera.animateTo(
                finalPosition = CameraPosition(
                    target = Position(latitude = targetLat, longitude = targetLng),
                    zoom = zoom,
                ),
                duration = 0.milliseconds,
            )
            return@LaunchedEffect
        }
        // Only animate if the position has actually changed meaningfully
        if (kotlin.math.abs(currentPos.target.latitude - targetLat) > 0.0001 ||
            kotlin.math.abs(currentPos.target.longitude - targetLng) > 0.0001 ||
            kotlin.math.abs(currentPos.zoom - zoom) > 0.1
        ) {
            camera.animateTo(
                finalPosition = CameraPosition(
                    target = Position(latitude = targetLat, longitude = targetLng),
                    zoom = zoom,
                ),
                duration = 500.milliseconds,
            )
        }
    }

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

        // Bounds check — snap back if user pans too far outside service area.
        // Uses snapshotFlow so camera.position is NOT read in the composition scope,
        // which would otherwise cause the entire composable tree (including all MapLibre
        // layers) to recompose on every frame during pan — producing heatmap flicker.
        LaunchedEffect(paddedBounds) {
            snapshotFlow { camera.position }
                .collect { pos ->
                    val lat = pos.target.latitude
                    val lng = pos.target.longitude
                    val outsideLat = lat < paddedBounds.south - 0.005 || lat > paddedBounds.north + 0.005
                    val outsideLng = lng < paddedBounds.west - 0.005 || lng > paddedBounds.east + 0.005
                    if (outsideLat || outsideLng) {
                        val clampedLat = lat.coerceIn(paddedBounds.south, paddedBounds.north)
                        val clampedLng = lng.coerceIn(paddedBounds.west, paddedBounds.east)
                        camera.animateTo(
                            finalPosition = CameraPosition(
                                target = Position(latitude = clampedLat, longitude = clampedLng),
                                zoom = pos.zoom,
                            ),
                            duration = 300.milliseconds,
                        )
                    }
                }
        }
    }

    // Keep the camera zoom read out of the main map composition. The scale bar
    // can update independently while MapLibre handles heatmap zoom interpolation
    // natively, avoiding layer-tree churn during pinch gestures.
    val scaleBarZoom = remember { derivedStateOf { camera.position.zoom } }

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
            val routeInnerSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routeInnerJson))
            val routeCorridorSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routeCorridorJson))
            val checkpointsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(checkpointsJson))

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

            // Service area fill — hidden while heatmap active
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

            // Explorer scan-state color coding: area fills bucketed by scan
            // state — grey = unscanned (the whole city today), green = clean,
            // heat-red = dirty, faded amber = stale. My Area mode skips them.
            if (mapMode != MapMode.MY_AREA) {
                // Unscanned baseline covers ALL landmass worldwide (deep
                // grey), not just Chicago's boundary — the scan economy's
                // frontier is planetary. Colored area states paint over it.
                val worldUnscannedSrc = rememberGeoJsonSource(
                    GeoJsonData.JsonString(
                        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-180,-85],[180,-85],[180,85],[-180,85],[-180,-85]]]}}]}"""
                    )
                )
                FillLayer(
                    id = "explorer-scanstate-unscanned",
                    source = worldUnscannedSrc,
                    color = const(Color(0xFF4B5563)),
                    opacity = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        9 to const(0.34f),
                        13 to const(0.24f),
                        15 to const(0f),
                    ),
                )
                // Scan intensity stays HEATMAP-style (the same spot-density
                // gradient layers My Area uses render over this baseline) —
                // no flat single-color area fills, per product direction.
            }

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

            // Community area name labels — My Area mode (simple names)
            val labelsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(labelsJson))
            @Suppress("UNCHECKED_CAST")
            val nameExpr = feature["name"] as org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.StringValue>
            val isMyArea = mapMode == MapMode.MY_AREA
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
                textOpacity = const(if (isMyArea) 1f else 0f),
            )

            // Explorer mode: area names + bounty count/amount on second line
            val explorerSrc = rememberGeoJsonSource(GeoJsonData.JsonString(explorerLabelsJson))
            @Suppress("UNCHECKED_CAST")
            val explorerLabelExpr = feature["explorerLabel"] as org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.StringValue>
            SymbolLayer(
                id = MapStyles.LAYER_EXPLORER_LABELS,
                source = explorerSrc,
                textField = format(span(explorerLabelExpr)),
                textSize = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(0f).em,
                    11 to const(0.6f).em,
                    13 to const(0.8f).em,
                    15 to const(0.65f).em,
                    16 to const(0f).em,
                ),
                textColor = const(Color(0xFF1F2937)),
                textHaloColor = const(Color.White),
                textHaloWidth = const(2.0.dp),
                textAllowOverlap = const(false),
                textIgnorePlacement = const(false),
                textOpacity = const(if (isMyArea) 0f else 1f),
            )

            // Note: the planet-spanning clean-zone-base FillLayer that used
            // to live here has been removed. It painted a green tint over
            // EVERY pixel of the visible map (the source is a polygon from
            // [-180,-85] to [180,85]) which obscured OpenFreeMap's road and
            // label layers underneath. The simplified map keeps just the
            // basemap + colored spot dots; density is conveyed by how
            // many dots cluster, not by an overlay wash.

            // ── Route polygon fill — invisible tap target ──
            // Was previously a solid green fill at high opacity, but it
            // overlapped the affected-block heatmap and the clean-zone base
            // layer to look like a duplicated heatmap. The line stack below
            // (glow + casing + core) carries the visual route presence;
            // this fill is kept ONLY for tap targeting at all zoom levels.
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

            // ── Foundational coverage-state tiles (under heat + markers) ──
            // fog = never scanned (attention), green = scanned clean,
            // faded amber = stale (>24h) — dirty grading stays with the
            // spot-density heat layers above.
            if (coverageStateJsons != null) {
                val cleanSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.first))
                val staleSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.second))
                val fogSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.third))
                // Zoom-gated: at overview zooms the bounded fog patch would
                // read as a broken partial grid — fade it in at street level
                // where its bounded radius covers the whole viewport.
                FillLayer(
                    id = "coverage-fog",
                    source = fogSrc,
                    color = const(Color(0xFF4B5563)),
                    // Gentle at overview, present at street level — the
                    // world-veil must never bury basemap labels.
                    opacity = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        10 to const(0.08f),
                        14 to const(0.18f),
                        16 to const(0.22f),
                    ),
                )
                FillLayer(
                    id = "coverage-clean",
                    source = cleanSrc,
                    color = const(Color(0xFF22C55E)),
                    opacity = const(0.18f),
                )
                FillLayer(
                    id = "coverage-stale",
                    source = staleSrc,
                    color = const(Color(0xFFF59E0B)),
                    opacity = const(0.10f),
                )
            }

            // ── Scan corridor + inner boundary (double-lined routes) ──
            // The band BETWEEN the two boundaries is the assignment: tinted
            // so "scan here" is explicit. Inside the inner ring (private
            // property) and outside the outer ring are excluded.
            FillLayer(
                id = "route-corridor-band",
                source = routeCorridorSrc,
                color = const(GccGreen),
                opacity = const(0.12f),
            )
            // Exclusion zones (inner boundaries): deliberately NOT the
            // outer's twin — grey line + grey fill so "excluded from the
            // scan area" reads at a glance against the green perimeter.
            FillLayer(
                id = MapStyles.LAYER_ROUTE_INNER_BOUNDARY + "-excluded-fill",
                source = routeInnerSrc,
                color = const(Color(0xFF9E9E9E)),
                opacity = const(0.22f),
            )
            LineLayer(
                id = MapStyles.LAYER_ROUTE_INNER_BOUNDARY + "-casing",
                source = routeInnerSrc,
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
            LineLayer(
                id = MapStyles.LAYER_ROUTE_INNER_BOUNDARY,
                source = routeInnerSrc,
                color = const(Color(0xFF757575)),
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
            )

            CircleLayer(
                id = MapStyles.LAYER_CHECKPOINT_OUTER,
                source = checkpointsSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.White),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(3.dp),
                    17 to const(8.dp),
                ),
                strokeColor = const(GccGreenDark),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(0.5.dp),
                    17 to const(1.5.dp),
                ),
            )

            CircleLayer(
                id = MapStyles.LAYER_CHECKPOINT_INNER,
                source = checkpointsSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(GccGreen),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(2.dp),
                    17 to const(5.5.dp),
                ),
            )

            // ── Photo star markers: gold dots for checkpoints with photos ──
            val photoStarJson = remember(checkpointPhotos) {
                if (checkpointPhotos.isEmpty()) """{"type":"FeatureCollection","features":[]}"""
                else {
                    val features = checkpointPhotos.joinToString(",") { (coord, url) ->
                        """{"type":"Feature","properties":{"imageUrl":"${url.replace("\"", "\\\"")}"},"geometry":{"type":"Point","coordinates":[${coord.lng},${coord.lat}]}}"""
                    }
                    """{"type":"FeatureCollection","features":[$features]}"""
                }
            }
            val photoStarSrc = rememberGeoJsonSource(GeoJsonData.JsonString(photoStarJson))

            // Checkpoint photo nodes — green circles matching waypoints
            CircleLayer(
                id = "photo-star-outer",
                source = photoStarSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.White),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(5.dp),
                    17 to const(11.dp),
                ),
            )

            CircleLayer(
                id = "photo-star-inner",
                source = photoStarSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color(0xFF22C55E)),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(4.dp),
                    17 to const(9.dp),
                ),
                strokeColor = const(Color.White),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(0.5.dp),
                    17 to const(1.5.dp),
                ),
            )

            // ── Image location markers: gold diamond shapes via FillLayer ──
            val dLat = 0.00006  // ~7m vertical arm
            val dLng = 0.00008  // ~7m horizontal arm
            val diamondJson = remember(imageStars) {
                if (imageStars.isEmpty()) """{"type":"FeatureCollection","features":[]}"""
                else {
                    val features = imageStars.joinToString(",") { c ->
                        val top = "[${c.lng},${c.lat + dLat}]"
                        val right = "[${c.lng + dLng},${c.lat}]"
                        val bottom = "[${c.lng},${c.lat - dLat}]"
                        val left = "[${c.lng - dLng},${c.lat}]"
                        """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$top,$right,$bottom,$left,$top]]}}"""
                    }
                    """{"type":"FeatureCollection","features":[$features]}"""
                }
            }
            // Smaller inner diamond for contrast
            val innerScale = 0.5
            val innerDiamondJson = remember(imageStars) {
                if (imageStars.isEmpty()) """{"type":"FeatureCollection","features":[]}"""
                else {
                    val features = imageStars.joinToString(",") { c ->
                        val d2Lat = dLat * innerScale
                        val d2Lng = dLng * innerScale
                        val top = "[${c.lng},${c.lat + d2Lat}]"
                        val right = "[${c.lng + d2Lng},${c.lat}]"
                        val bottom = "[${c.lng},${c.lat - d2Lat}]"
                        val left = "[${c.lng - d2Lng},${c.lat}]"
                        """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$top,$right,$bottom,$left,$top]]}}"""
                    }
                    """{"type":"FeatureCollection","features":[$features]}"""
                }
            }
            val diamondSrc = rememberGeoJsonSource(data = GeoJsonData.JsonString(diamondJson))
            val innerDiamondSrc = rememberGeoJsonSource(data = GeoJsonData.JsonString(innerDiamondJson))

            // Outer gold diamond
            FillLayer(
                id = "image-star-fill",
                source = diamondSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color(0xFFFFB300)),
            )
            // White inner diamond (creates a framed look)
            FillLayer(
                id = "image-star-inner",
                source = innerDiamondSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.White),
            )

            // ── Bounty markers: red dots with radius and amount labels ──
            val bountySrc = rememberGeoJsonSource(GeoJsonData.JsonString(bountyJson))

            // Bounty radius circles (visible at zoom 14+)
            CircleLayer(
                id = MapStyles.LAYER_BOUNTY_RADIUS,
                source = bountySrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(MapStyles.BOUNTY_RADIUS_FILL),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(5.dp),
                    17 to const(40.dp),
                ),
            )

            // Bounty dots — red circles, zoom-adaptive
            CircleLayer(
                id = MapStyles.LAYER_BOUNTY_DOTS,
                source = bountySrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
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
                onClick = if (onBountyTap != null) { features ->
                    val raw = features.firstOrNull()?.properties?.get("id")
                    val id = (raw as? Number)?.toInt() ?: raw?.toString()?.toIntOrNull()
                    if (id != null) {
                        onBountyTap(id)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                } else null,
            )

            // Bounty amount labels — "$XX" text above dots
            @Suppress("UNCHECKED_CAST")
            val amountExpr = feature["amountLabel"] as org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.StringValue>
            SymbolLayer(
                id = MapStyles.LAYER_BOUNTY_LABELS,
                source = bountySrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
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

            // ── Affected-block polygon fills ──
            //
            // These are close-zoom block details only. Overview heat is driven
            // by spot reports so blocks without a report remain part of the
            // clean green baseline.
            if (affectedBlocks.isNotEmpty()) {
                val affectedJson = remember(affectedBlocks) { affectedBlocksToGeoJson(affectedBlocks) }
                val affectedSrc = rememberGeoJsonSource(GeoJsonData.JsonString(affectedJson))

                FillLayer(
                    id = "affected-block-fill",
                    source = affectedSrc,
                    minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
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

            }

            // ── User location blue dot ──
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
                    // Accuracy halo — semi-transparent blue circle
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

            // ── Spot rectangle area fills — rendered as polygons for rectangle-type spots ──
            val spotRectSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotRectJson))
            FillLayer(
                id = "spot-rect-fill",
                source = spotRectSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_MARKER_COLOR.copy(alpha = 0.15f)),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0f),
                    14 to const(0.7f),
                ),
            )
            LineLayer(
                id = "spot-rect-outline",
                source = spotRectSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_MARKER_COLOR.copy(alpha = 0.6f)),
                width = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    15 to const(1.5.dp),
                    17 to const(2.dp),
                ),
            )

            // ── Spot report markers ──
            val spotSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotJson))
            val spotDensityHeatEdgeSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotDensityHeatEdgeJson))
            val spotDensityHeatLowSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotDensityHeatLowJson))
            val spotDensityHeatMediumSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotDensityHeatMediumJson))
            val spotDensityHeatHighSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotDensityHeatHighJson))
            val spotDensityHeatExtremeSrc = rememberGeoJsonSource(GeoJsonData.JsonString(spotDensityHeatExtremeJson))

            // ── Spot scrim: neutral off-white tint beneath heatmap ──
            // Planet-spanning fill sourced from the same clean-zone GeoJSON.
            // Fades from 0.55 opacity at overview zoom to 0 by
            // IOS_SPOT_HEAT_FADE_ZOOM so it doesn't compete with street
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
                        IOS_SPOT_HEAT_FADE_ZOOM to const(0f),
                    ),
                )
            }

            // Sampled overview heat over the neutral scrim baseline. These
            // sources are density field samples, not raw spot points, so the
            // layers read as one continuous surface at overview zooms.
            CircleLayer(
                id = "spot-density-heat-low-fringe",
                source = spotDensityHeatEdgeSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = IOS_SPOT_HEAT_FADE_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_HEAT_EDGE.copy(alpha = 0.72f)),
                blur = const(1.35f),
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(2.2.dp),
                    7 to const(5.2.dp),
                    9 to const(8.8.dp),
                    11 to const(13.5.dp),
                    12.5 to const(18.dp),
                    13.1 to const(15.dp),
                    13.8 to const(12.dp),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(10.dp),
                ),
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.1f),
                    7 to const(0.18f),
                    9 to const(0.28f),
                    11 to const(0.34f),
                    12.5 to const(0.42f),
                    13.1 to const(0.3f),
                    13.8 to const(0.16f),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(0.14f),
                ),
            )
            CircleLayer(
                id = "spot-density-heat-low-core",
                source = spotDensityHeatLowSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = IOS_SPOT_HEAT_FADE_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_HEAT_LOW.copy(alpha = 0.82f)),
                blur = const(1.25f),
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(1.7.dp),
                    7 to const(4.2.dp),
                    9 to const(7.2.dp),
                    11 to const(11.5.dp),
                    12.5 to const(16.dp),
                    13.1 to const(13.dp),
                    13.8 to const(10.5.dp),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(8.4.dp),
                ),
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.12f),
                    7 to const(0.2f),
                    9 to const(0.32f),
                    11 to const(0.44f),
                    12.5 to const(0.54f),
                    13.1 to const(0.36f),
                    13.8 to const(0.2f),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(0.18f),
                ),
            )
            CircleLayer(
                id = "spot-density-heat-mid",
                source = spotDensityHeatMediumSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = IOS_SPOT_HEAT_FADE_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_HEAT_MEDIUM),
                blur = const(1.1f),
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(1.2.dp),
                    7 to const(3.3.dp),
                    9 to const(6.1.dp),
                    11 to const(10.dp),
                    12.5 to const(13.8.dp),
                    13.1 to const(10.8.dp),
                    13.8 to const(8.8.dp),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(7.dp),
                ),
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.08f),
                    7 to const(0.18f),
                    9 to const(0.4f),
                    11 to const(0.62f),
                    12.5 to const(0.78f),
                    13.1 to const(0.48f),
                    13.8 to const(0.26f),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(0.24f),
                ),
            )
            CircleLayer(
                id = "spot-density-heat-high",
                source = spotDensityHeatHighSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = IOS_SPOT_HEAT_FADE_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_HEAT_HIGH),
                blur = const(1.05f),
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.8.dp),
                    7 to const(2.7.dp),
                    9 to const(5.6.dp),
                    11 to const(9.2.dp),
                    12.5 to const(12.8.dp),
                    13.1 to const(9.4.dp),
                    13.8 to const(7.6.dp),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(6.dp),
                ),
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.06f),
                    7 to const(0.16f),
                    9 to const(0.5f),
                    11 to const(0.84f),
                    12.5 to const(0.96f),
                    13.1 to const(0.62f),
                    13.8 to const(0.3f),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(0.26f),
                ),
            )
            CircleLayer(
                id = "spot-density-heat-extreme",
                source = spotDensityHeatExtremeSrc,
                minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                maxZoom = IOS_SPOT_HEAT_FADE_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_HEAT_EXTREME),
                blur = const(1.0f),
                radius = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0.dp),
                    7 to const(1.2.dp),
                    9 to const(2.6.dp),
                    11 to const(4.4.dp),
                    12.5 to const(6.dp),
                    13.1 to const(4.4.dp),
                    13.8 to const(4.6.dp),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(3.6.dp),
                ),
                opacity = interpolate(
                    type = org.maplibre.compose.expressions.dsl.linear(),
                    input = zoom(),
                    2 to const(0f),
                    7 to const(0.12f),
                    9 to const(0.32f),
                    11 to const(0.62f),
                    12.5 to const(0.78f),
                    13.1 to const(0.48f),
                    13.8 to const(0.18f),
                    IOS_SPOT_HEAT_FADE_ZOOM to const(0.14f),
                ),
            )

            CircleLayer(
                id = MapStyles.LAYER_SPOT_MARKER_HALOS,
                source = spotSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(MapStyles.SPOT_MARKER_COLOR.copy(alpha = 0.16f)),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(10.dp),
                    15 to const(13.dp),
                    17 to const(18.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(0f),
                    14.5 to const(0.8f),
                    16 to const(1f),
                ),
            )

            val bountiedSpotSrc = rememberGeoJsonSource(GeoJsonData.JsonString(bountiedSpotJson))
            CircleLayer(
                id = MapStyles.LAYER_BOUNTIED_SPOT_HALOS,
                source = bountiedSpotSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
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

            // Invisible expanded tap target for spots (larger hit area)
            CircleLayer(
                id = "${MapStyles.LAYER_SPOT_MARKERS}-tap",
                source = spotSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.Transparent),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(12.dp),
                    15 to const(14.dp),
                    16 to const(16.dp),
                    18 to const(18.dp),
                ),
                onClick = if (onSpotTap != null) { features ->
                    val raw = features.firstOrNull()?.properties?.get("id")
                    val id = (raw as? Number)?.toInt() ?: raw?.toString()?.toIntOrNull()
                    if (id != null) {
                        onSpotTap(id)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                } else null,
            )

            // Visible spot dots — close zoom only. The overview heatmap uses the
            // same switch zoom as maxZoom, so markers and heat never render together.
            // Only UNTYPED pins render as the plain orange dot; pins carrying a
            // canonical "itemType" are drawn by the per-type glyph SymbolLayer below.
            CircleLayer(
                id = MapStyles.LAYER_SPOT_MARKERS,
                source = spotSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                filter = !feature.has("itemType"),
                color = const(MapStyles.SPOT_MARKER_COLOR),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(4.dp),
                    14.5 to const(5.dp),
                    16 to const(7.dp),
                    17 to const(8.dp),
                    20 to const(10.dp),
                ),
                strokeColor = const(Color.White),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(1.dp),
                    16 to const(1.6.dp),
                    18 to const(2.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(0.85f),
                    14.5 to const(0.95f),
                    15 to const(1f),
                ),
            )

            // Per-type litter glyphs — pins with a canonical itemType are drawn
            // as a small badge depicting WHAT was scanned (bottle, can, bag …)
            // instead of the uniform dot. The tap target below the halos already
            // covers these features, so no onClick is needed here.
            @Suppress("UNCHECKED_CAST")
            val itemTypeExpr = feature["itemType"] as
                org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.StringValue>
            val glyphSize = DpSize(30.dp, 30.dp)
            SymbolLayer(
                id = "spot-markers-glyphs",
                source = spotSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                filter = feature.has("itemType"),
                iconImage = switch(
                    itemTypeExpr,
                    case("plastic_bottle", image(litterGlyphPainters.getValue("plastic_bottle"), glyphSize)),
                    case("glass_bottle", image(litterGlyphPainters.getValue("glass_bottle"), glyphSize)),
                    case("can", image(litterGlyphPainters.getValue("can"), glyphSize)),
                    case("plastic_bag", image(litterGlyphPainters.getValue("plastic_bag"), glyphSize)),
                    case("cup", image(litterGlyphPainters.getValue("cup"), glyphSize)),
                    case("paper", image(litterGlyphPainters.getValue("paper"), glyphSize)),
                    case("wrapper", image(litterGlyphPainters.getValue("wrapper"), glyphSize)),
                    case("carton", image(litterGlyphPainters.getValue("carton"), glyphSize)),
                    case("cigarette", image(litterGlyphPainters.getValue("cigarette"), glyphSize)),
                    case("food_waste", image(litterGlyphPainters.getValue("food_waste"), glyphSize)),
                    case("tire", image(litterGlyphPainters.getValue("tire"), glyphSize)),
                    case("mattress", image(litterGlyphPainters.getValue("mattress"), glyphSize)),
                    case("debris_pile", image(litterGlyphPainters.getValue("debris_pile"), glyphSize)),
                    case("tree", image(litterGlyphPainters.getValue("tree"), glyphSize)),
                    case("bush", image(litterGlyphPainters.getValue("bush"), glyphSize)),
                    case("fence", image(litterGlyphPainters.getValue("fence"), glyphSize)),
                    case("gate", image(litterGlyphPainters.getValue("gate"), glyphSize)),
                    case("pole", image(litterGlyphPainters.getValue("pole"), glyphSize)),
                    case("hydrant", image(litterGlyphPainters.getValue("hydrant"), glyphSize)),
                    case("bench", image(litterGlyphPainters.getValue("bench"), glyphSize)),
                    case("mailbox", image(litterGlyphPainters.getValue("mailbox"), glyphSize)),
                    case("sign", image(litterGlyphPainters.getValue("sign"), glyphSize)),
                    fallback = image(litterGlyphPainters.getValue("other"), glyphSize),
                ),
                iconSize = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(0.5f),
                    16 to const(0.72f),
                    18 to const(0.9f),
                ),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )

            // ── Verification indicator — orange ring on spots needing verification ──
            val verificationSpots = remember(spots) {
                val needsVerif = spots.filter { it.needsVerification && it.status != CommunityReportStatus.RESOLVED }
                if (needsVerif.isEmpty()) """{"type":"FeatureCollection","features":[]}"""
                else {
                    val features = needsVerif.joinToString(",") { s ->
                        """{"type":"Feature","properties":{"id":${s.id}},"geometry":{"type":"Point","coordinates":[${s.coordinates.lng},${s.coordinates.lat}]}}"""
                    }
                    """{"type":"FeatureCollection","features":[$features]}"""
                }
            }
            val verifSrc = rememberGeoJsonSource(GeoJsonData.JsonString(verificationSpots))
            CircleLayer(
                id = "spot-verification-ring",
                source = verifSrc,
                minZoom = IOS_SPOT_DOTS_MIN_ZOOM.toFloat(),
                color = const(Color.Transparent),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(7.dp),
                    15 to const(8.dp),
                    17 to const(10.dp),
                ),
                strokeColor = const(Color(0xFFFF8C00)),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    14 to const(1.5.dp),
                    15 to const(2.dp),
                    17 to const(2.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13.5 to const(0f),
                    14 to const(1f),
                ),
            )

        }

        // Overlay content (composables positioned in the Box)
        content()

        // Zoom controls and scale bar — bottom-left stack
        if (showControls) {
            MapZoomControlOverlay(
                zoomState = scaleBarZoom,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 72.dp),
                onZoomIn = {
                    coroutineScope.launch {
                        val nextZoom = (camera.position.zoom + 1.0).coerceAtMost(20.0)
                        camera.animateTo(
                            finalPosition = camera.position.copy(
                                zoom = nextZoom,
                            ),
                            duration = 250.milliseconds,
                        )
                    }
                },
                onZoomOut = {
                    coroutineScope.launch {
                        val nextZoom = (camera.position.zoom - 1.0).coerceAtLeast(1.0)
                        camera.animateTo(
                            finalPosition = camera.position.copy(
                                zoom = nextZoom,
                            ),
                            duration = 250.milliseconds,
                        )
                    }
                },
            )
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

@Composable
private fun MapZoomControlOverlay(
    zoomState: State<Double>,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ZoomButtons(
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
        )
        ScaleBar(zoom = zoomState.value)
    }
}

/**
 * Canonical litter types that get a per-type close-zoom map glyph. Mirrors
 * [com.gcc.app.platform.TrashItemCatalog]; "other" is the catch-all and also
 * the [switch] fallback for any unexpected type string.
 */
private val LITTER_GLYPH_TYPES = listOf(
    "plastic_bottle", "glass_bottle", "can", "plastic_bag", "cup", "paper",
    "wrapper", "carton", "cigarette", "food_waste", "tire", "mattress",
    // Object-capture piles + landmark elements (world-model context):
    "debris_pile", "tree", "bush", "fence", "gate", "pole", "hydrant",
    "bench", "mailbox", "sign", "other",
)

/**
 * Badge tint for each litter glyph. The whole set stays in the orange/amber
 * litter family with a per-type hue nudge so the markers read as one system;
 * glass_bottle breaks to teal to echo its real-world color cue.
 */
private fun litterBadgeColor(itemType: String): Color = when (itemType) {
    "plastic_bottle" -> Color(0xFFF97316)
    "glass_bottle" -> Color(0xFF0E9F9A)
    "can" -> Color(0xFFEA580C)
    "plastic_bag" -> Color(0xFFFB923C)
    "cup" -> Color(0xFFF59E0B)
    "paper" -> Color(0xFFF6B740)
    "wrapper" -> Color(0xFFEF6A16)
    "carton" -> Color(0xFFD97706)
    "cigarette" -> Color(0xFFFBBF24)
    "food_waste" -> Color(0xFFB45309)
    "tire" -> Color(0xFF9A3412)
    "mattress" -> Color(0xFFF08A3C)
    "debris_pile" -> Color(0xFF92400E)
    // Landmark elements: greens for vegetation, greys for structures, so
    // scenery reads as scenery next to the orange litter family.
    "tree" -> Color(0xFF16A34A)
    "bush" -> Color(0xFF22C55E)
    "fence", "gate", "pole", "bench", "mailbox", "sign" -> Color(0xFF6B7280)
    "hydrant" -> Color(0xFFDC2626)
    else -> Color(0xFFF97316)
}

/**
 * Draws a small white-stroked circular badge tinted per litter type with a
 * simple white glyph depicting the item. Rasterized once by the MapLibre image
 * manager and referenced from the spot-markers-glyphs SymbolLayer. Kept simple
 * and flat so shapes stay legible at ~15-27dp on the map.
 */
private class LitterGlyphPainter(private val itemType: String) : Painter() {
    override val intrinsicSize: Size = Size(30f, 30f)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        val ring = r * 0.13f
        val badge = litterBadgeColor(itemType)
        // White badge ring + tinted fill — the "dot that shows what it is".
        drawCircle(color = Color.White, radius = r, center = Offset(cx, cy))
        drawCircle(color = badge, radius = r - ring, center = Offset(cx, cy))

        val ink = Color.White
        when (itemType) {
            "plastic_bottle" -> {
                val bw = w * 0.20f
                val bh = h * 0.46f
                drawRoundRect(ink, Offset(cx - bw / 2f, cy - bh / 2f + h * 0.04f), Size(bw, bh), CornerRadius(bw * 0.5f))
                drawRoundRect(ink, Offset(cx - bw * 0.28f, cy - bh / 2f - h * 0.02f), Size(bw * 0.56f, h * 0.09f), CornerRadius(bw * 0.15f))
            }
            "glass_bottle" -> {
                val bw = w * 0.22f
                val bh = h * 0.50f
                drawRoundRect(ink, Offset(cx - bw / 2f, cy - bh / 2f + h * 0.04f), Size(bw, bh), CornerRadius(bw * 0.28f))
                drawRoundRect(ink, Offset(cx - bw * 0.22f, cy - bh / 2f - h * 0.03f), Size(bw * 0.44f, h * 0.10f), CornerRadius(bw * 0.1f))
            }
            "can" -> {
                val bw = w * 0.42f
                val bh = h * 0.34f
                drawRoundRect(ink, Offset(cx - bw / 2f, cy - bh / 2f), Size(bw, bh), CornerRadius(bw * 0.16f))
            }
            "plastic_bag" -> {
                val d = w * 0.28f
                val p = Path().apply {
                    moveTo(cx, cy - d)
                    lineTo(cx + d, cy)
                    lineTo(cx, cy + d)
                    lineTo(cx - d, cy)
                    close()
                }
                drawPath(p, ink)
            }
            "cup" -> {
                val topHalf = w * 0.24f
                val botHalf = w * 0.15f
                val hh = h * 0.24f
                val p = Path().apply {
                    moveTo(cx - topHalf, cy - hh)
                    lineTo(cx + topHalf, cy - hh)
                    lineTo(cx + botHalf, cy + hh)
                    lineTo(cx - botHalf, cy + hh)
                    close()
                }
                drawPath(p, ink)
            }
            "paper" -> {
                val s = w * 0.42f
                val half = s / 2f
                val fold = s * 0.34f
                val p = Path().apply {
                    moveTo(cx - half, cy - half)
                    lineTo(cx + half - fold, cy - half)
                    lineTo(cx + half, cy - half + fold)
                    lineTo(cx + half, cy + half)
                    lineTo(cx - half, cy + half)
                    close()
                }
                drawPath(p, ink)
                val fp = Path().apply {
                    moveTo(cx + half - fold, cy - half)
                    lineTo(cx + half, cy - half + fold)
                    lineTo(cx + half - fold, cy - half + fold)
                    close()
                }
                drawPath(fp, badge)
            }
            "wrapper" -> {
                val bw = w * 0.42f
                val bh = h * 0.24f
                drawRoundRect(ink, Offset(cx - bw / 2f, cy - bh / 2f), Size(bw, bh), CornerRadius(bh * 0.3f))
                val left = Path().apply {
                    moveTo(cx - bw / 2f, cy - bh / 2f)
                    lineTo(cx - bw / 2f - w * 0.08f, cy)
                    lineTo(cx - bw / 2f, cy + bh / 2f)
                    close()
                }
                drawPath(left, ink)
                val right = Path().apply {
                    moveTo(cx + bw / 2f, cy - bh / 2f)
                    lineTo(cx + bw / 2f + w * 0.08f, cy)
                    lineTo(cx + bw / 2f, cy + bh / 2f)
                    close()
                }
                drawPath(right, ink)
            }
            "carton" -> {
                val s = w * 0.40f
                drawRoundRect(ink, Offset(cx - s / 2f, cy - s / 2f), Size(s, s), CornerRadius(s * 0.14f))
                drawLine(badge, Offset(cx, cy - s / 2f), Offset(cx, cy + s / 2f), strokeWidth = w * 0.03f)
            }
            "cigarette" -> {
                rotate(degrees = -35f, pivot = Offset(cx, cy)) {
                    val bw = w * 0.5f
                    val bh = h * 0.14f
                    drawRoundRect(ink, Offset(cx - bw / 2f, cy - bh / 2f), Size(bw * 0.7f, bh), CornerRadius(bh * 0.2f))
                    drawRoundRect(Color(0xFFB45309), Offset(cx + bw * 0.2f, cy - bh / 2f), Size(bw * 0.3f, bh), CornerRadius(bh * 0.2f))
                }
            }
            "food_waste" -> {
                val fr = w * 0.20f
                drawCircle(ink, radius = fr, center = Offset(cx, cy))
                drawCircle(badge, radius = fr * 0.62f, center = Offset(cx + fr * 0.9f, cy - fr * 0.2f))
                drawLine(ink, Offset(cx, cy - fr), Offset(cx + fr * 0.3f, cy - fr * 1.4f), strokeWidth = w * 0.03f)
            }
            "tire" -> {
                val outer = w * 0.24f
                drawCircle(ink, radius = outer, center = Offset(cx, cy), style = Stroke(width = w * 0.11f))
            }
            "mattress" -> {
                val bw = w * 0.5f
                val bh = h * 0.3f
                drawRoundRect(ink, Offset(cx - bw / 2f, cy - bh / 2f), Size(bw, bh), CornerRadius(bh * 0.35f))
                val dot = w * 0.025f
                drawCircle(badge, radius = dot, center = Offset(cx - bw * 0.22f, cy))
                drawCircle(badge, radius = dot, center = Offset(cx, cy))
                drawCircle(badge, radius = dot, center = Offset(cx + bw * 0.22f, cy))
            }
            "debris_pile" -> {
                val d = w * 0.30f
                val p = Path().apply {
                    moveTo(cx - d, cy + d * 0.6f)
                    lineTo(cx - d * 0.3f, cy - d * 0.5f)
                    lineTo(cx + d * 0.15f, cy + d * 0.05f)
                    lineTo(cx + d * 0.45f, cy - d * 0.35f)
                    lineTo(cx + d, cy + d * 0.6f)
                    close()
                }
                drawPath(p, ink)
            }
            "tree" -> {
                drawCircle(ink, radius = w * 0.20f, center = Offset(cx, cy - h * 0.08f))
                drawRoundRect(ink, Offset(cx - w * 0.04f, cy + h * 0.06f), Size(w * 0.08f, h * 0.20f), CornerRadius(w * 0.02f))
            }
            "bush" -> {
                drawCircle(ink, radius = w * 0.16f, center = Offset(cx - w * 0.08f, cy + h * 0.02f))
                drawCircle(ink, radius = w * 0.14f, center = Offset(cx + w * 0.10f, cy + h * 0.04f))
            }
            "fence" -> {
                drawRoundRect(ink, Offset(cx - w * 0.24f, cy - h * 0.03f), Size(w * 0.48f, h * 0.06f), CornerRadius(w * 0.01f))
                drawRoundRect(ink, Offset(cx - w * 0.18f, cy - h * 0.16f), Size(w * 0.06f, h * 0.32f), CornerRadius(w * 0.01f))
                drawRoundRect(ink, Offset(cx + w * 0.12f, cy - h * 0.16f), Size(w * 0.06f, h * 0.32f), CornerRadius(w * 0.01f))
            }
            "gate" -> {
                drawRoundRect(ink, Offset(cx - w * 0.18f, cy - h * 0.18f), Size(w * 0.36f, h * 0.36f), CornerRadius(w * 0.03f))
                drawRoundRect(badge, Offset(cx - w * 0.10f, cy - h * 0.10f), Size(w * 0.20f, h * 0.28f), CornerRadius(w * 0.02f))
            }
            "pole", "sign" -> {
                drawRoundRect(ink, Offset(cx - w * 0.035f, cy - h * 0.20f), Size(w * 0.07f, h * 0.40f), CornerRadius(w * 0.02f))
                if (itemType == "sign") {
                    drawRoundRect(ink, Offset(cx - w * 0.14f, cy - h * 0.22f), Size(w * 0.28f, h * 0.14f), CornerRadius(w * 0.02f))
                }
            }
            "hydrant" -> {
                drawRoundRect(ink, Offset(cx - w * 0.10f, cy - h * 0.10f), Size(w * 0.20f, h * 0.28f), CornerRadius(w * 0.05f))
                drawCircle(ink, radius = w * 0.10f, center = Offset(cx, cy - h * 0.12f))
            }
            "bench" -> {
                drawRoundRect(ink, Offset(cx - w * 0.22f, cy - h * 0.04f), Size(w * 0.44f, h * 0.08f), CornerRadius(w * 0.02f))
                drawRoundRect(ink, Offset(cx - w * 0.18f, cy + h * 0.04f), Size(w * 0.05f, h * 0.14f), CornerRadius(w * 0.01f))
                drawRoundRect(ink, Offset(cx + w * 0.13f, cy + h * 0.04f), Size(w * 0.05f, h * 0.14f), CornerRadius(w * 0.01f))
            }
            "mailbox" -> {
                drawRoundRect(ink, Offset(cx - w * 0.15f, cy - h * 0.14f), Size(w * 0.30f, h * 0.18f), CornerRadius(w * 0.06f))
                drawRoundRect(ink, Offset(cx - w * 0.03f, cy + h * 0.04f), Size(w * 0.06f, h * 0.16f), CornerRadius(w * 0.01f))
            }
            else -> {
                // "other" / unknown: plain tinted badge, matching the legacy dot.
            }
        }
    }
}
