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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
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
    showGridLines: Boolean,
    showControls: Boolean,
    statusBarPadding: Boolean,
    zoomPresets: List<ZoomPreset>,
    cameraBounds: AreaBounds?,
    mapMode: MapMode,
    checkpointPhotos: List<Pair<Coordinates, String>>,
    imageStars: List<Coordinates>,
    routePolygons: List<Pair<Int, List<Coordinates>>>,
    content: @Composable BoxScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    // Memoize GeoJSON strings — rebuilt only when inputs change
    val communityAreasJson = remember { buildCommunityAreasGeoJson() }
    val serviceAreaJson = remember { buildServiceAreaGeoJson() }
    val stateJson = remember { buildStateBoundaryGeoJson() }
    val cityJson = remember { buildCityBoundaryGeoJson() }
    val routesJson = remember(routeCoordinates) { buildRoutesGeoJson(routeCoordinates) }
    val routePolygonsJson = remember(routeCoordinates) { buildRoutePolygonsGeoJson(routeCoordinates) }
    val checkpointsJson = remember(routeCoordinates) { buildCheckpointMarkersGeoJson(routeCoordinates) }
    val labelsJson = remember { buildCommunityAreaLabelsGeoJson() }
    val explorerLabelsJson = remember(bounties) { buildExplorerLabelsGeoJson(bounties) }
    val bountyJson = remember(bounties) { buildBountyMarkersGeoJson(bounties) }
    val spotJson = remember(spots) { buildSpotMarkersGeoJson(spots) }
    val spotRectJson = remember(spots) { buildSpotRectanglePolygonsGeoJson(spots) }

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

        LaunchedEffect(camera.position) {
            val pos = camera.position
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
            val routesSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routesJson))
            val routePolygonsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routePolygonsJson))
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

            // Service area fill (green tint) — fades at street zoom
            FillLayer(
                id = "service-area-fill",
                source = serviceAreaSrc,
                color = const(MapStyles.GEOFENCE_FILL_COLOR),
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

            // ── Route polygon fill — tappable at all zoom levels ──
            // Visible green at overview, fades to near-invisible at street level
            // but stays non-transparent so taps still register on the polygon.
            FillLayer(
                id = MapStyles.LAYER_ROUTE_FILL,
                source = routePolygonsSrc,
                color = const(MapStyles.ROUTE_FILL_COLOR),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    10 to const(1f),
                    14 to const(0.8f),
                    16 to const(0.05f),
                ),
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

            // ── Checkpoint markers: zoom-adaptive circles ──
            // Hidden at overview, appear at neighborhood zoom, full size at street level.

            // Waypoint outer ring (white border)
            CircleLayer(
                id = MapStyles.LAYER_CHECKPOINT_OUTER,
                source = checkpointsSrc,
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

            // Waypoint inner fill (green)
            CircleLayer(
                id = MapStyles.LAYER_CHECKPOINT_INNER,
                source = checkpointsSrc,
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
                color = const(Color(0xFFFFB300)),
            )
            // White inner diamond (creates a framed look)
            FillLayer(
                id = "image-star-inner",
                source = innerDiamondSrc,
                color = const(Color.White),
            )

            // ── Bounty markers: red dots with radius and amount labels ──
            val bountySrc = rememberGeoJsonSource(GeoJsonData.JsonString(bountyJson))

            // Bounty radius circles (visible at zoom 14+)
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

            // Bounty dots — red circles, zoom-adaptive
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

            // ── User location blue dot ──
            if (currentLocation != null) {
                val userLocationJson = remember(currentLocation) {
                    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${currentLocation.lng},${currentLocation.lat}]},"properties":{}}]}"""
                }
                val userLocationSrc = rememberGeoJsonSource(GeoJsonData.JsonString(userLocationJson))

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

            // Native MapLibre heatmap layer — smooth kernel density cloud
            // Matches the website's heatmap implementation exactly
            HeatmapLayer(
                id = MapStyles.LAYER_SPOT_HEATMAP,
                source = spotSrc,
                minZoom = 12f,
                maxZoom = 13.5f,
                // Intensity ramps up as you zoom into the area
                intensity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(0.5f),
                    13 to const(1f),
                ),
                // Tight radius so heatmap stays within the actual data footprint
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(6.dp),
                    13 to const(12.dp),
                ),
                // Fully gone before dots appear at 13.5
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(0.5f),
                    13 to const(0.3f),
                    13.5 to const(0f),
                ),
            )

            // Invisible click-target layer — covers ALL zoom levels for tap handling
            // This catches taps even when the heatmap is visible (zoom 12-13.5)
            CircleLayer(
                id = "spot-click-target",
                source = spotSrc,
                color = const(androidx.compose.ui.graphics.Color.Transparent),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    12 to const(12.dp),
                    14 to const(16.dp),
                    16 to const(20.dp),
                ),
                opacity = const(0.01f), // Nearly invisible but still receives taps
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

            // Individual spot dots — solid orange, appear after heatmap fades
            // Larger radius for better visibility and touch targeting
            CircleLayer(
                id = MapStyles.LAYER_SPOT_MARKERS,
                source = spotSrc,
                color = const(MapStyles.SPOT_MARKER_COLOR),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    13.5 to const(5.dp),
                    14 to const(7.dp),
                    15 to const(9.dp),
                    17 to const(12.dp),
                ),
                opacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0f),
                    13.5 to const(0.7f),
                    14 to const(1f),
                ),
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
                color = const(Color.Transparent),
                radius = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    14 to const(11.dp),
                    16 to const(16.dp),
                ),
                strokeColor = const(Color(0xFFFF8C00)),
                strokeWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.dp),
                    14 to const(2.dp),
                    16 to const(3.dp),
                ),
                minZoom = MapStyles.SPOT_DOTS_MIN_ZOOM.toFloat(),
            )
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
                    zoom = camera.position.zoom,
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
