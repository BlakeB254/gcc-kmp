package com.gcc.app.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
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
import org.maplibre.compose.util.FeaturesClickHandler
import kotlin.time.Duration.Companion.milliseconds

// Mirror MapStyles' zoom bands (single source of truth) — heat crossfades out
// while dots fade in between DOTS_MIN and HEAT_FADE. (SPOT_DETAIL_MIN_ZOOM/
// SPOT_GLYPH_MIN_ZOOM mirrors were dropped here — Pillar 8 Task 2 removed the
// street-level halo/glyph/verification-ring layers they fed; MapStyles still
// keeps the constants for the Android actual, which still uses them.)
private val IOS_SPOT_HEAT_FADE_ZOOM = MapStyles.SPOT_HEATMAP_FADE_OUT_ZOOM
private val IOS_SPOT_DOTS_MIN_ZOOM = MapStyles.SPOT_DOTS_MIN_ZOOM

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
    activeRouteIndices: List<Int>,
    animateRoutes: Boolean,
    bountyLocations: List<Coordinates>,
    bounties: List<com.gcc.shared.model.Bounty>,
    spots: List<CommunityReport>,
    onAreaTap: ((CommunityArea) -> Unit)?,
    onRouteTap: ((routeIndex: Int) -> Unit)?,
    // Unused inside this file as of Pillar 8 Task 2 (spot tap targets were
    // removed along with the pin/glyph layers) — kept only for wire/caller
    // compat, since MapFullScreen.kt still passes it and the SpotInfoCard
    // flow it feeds still exists. Candidate for removal once that flow is
    // retired.
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
    showNeedsScanningLens: Boolean,
    onObservationTap: ((observationId: Int) -> Unit)?,
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
    val routeInnerJson = remember(routeCoordinates, innerBoundaryRings, innerBoundaryFallback) {
        buildRouteInnerBoundariesGeoJson(
            routeCoordinates, innerBoundaryRings,
            fallbackInset = innerBoundaryFallback,
        )
    }
    val routeCorridorJson = remember(routeCoordinates, innerBoundaryRings) {
        buildRouteCorridorGeoJson(routeCoordinates, innerBoundaryRings)
    }
    // Active-route highlight: a source built from just the assigned routes'
    // boundaries so they can be drawn with a brighter/thicker outer line on
    // top of the regular route stack (Fix: active blocks no longer render
    // exclusively, so they need their own visual emphasis).
    val activeRouteJson = remember(routeCoordinates, activeRouteIndices) {
        buildRoutesGeoJson(activeRouteIndices.mapNotNull { routeCoordinates.getOrNull(it) })
    }
    // Organic outline mode (Pillar 7) activates at street-level zoom. Gated
    // on the caller's TARGET zoom (the [zoom] param — what zoom presets/
    // navigation set), not the live pinch-gesture zoom: re-tracing every
    // coverage cell into marching-squares rings on every fractional pinch
    // tick would be expensive Compose churn for a purely cosmetic mode.
    // Deferred: reacting to live pinch-zoom crossing the threshold.
    // WS4 "paint the town": merged organic outlines are the ONLY coverage path
    // now (was zoom-gated at ZOOM_STREET, leaving squares at wider zooms). Cell
    // taps resolve via the map-level lat/lng->cell hit-test (Plan B Task 7), so
    // losing per-cell cellIds on the fill features is fine.
    val useOutlineCoverage = true
    val coverageStateJsons = remember(coverageCells, spots, useOutlineCoverage) {
        if (coverageCells.isEmpty()) null
        else {
            // WORLDWIDE fog-of-war (product direction): every unscanned place
            // on the planet wears the veil; scans punch holes through it.
            // (Public-land masking — water, parks, private parcels — comes
            // from the DGX world model later.)
            buildCoverageStateGeoJsons(
                coverageCells, spots, centerLat, centerLng,
                fogBounds = doubleArrayOf(-85.0, -180.0, 85.0, 180.0),
                useOutlines = useOutlineCoverage,
            )
        }
    }
    val labelsJson = remember { buildCommunityAreaLabelsGeoJson() }
    val explorerLabelsJson = remember(bounties) { buildExplorerLabelsGeoJson(bounties) }
    val bountyJson = remember(bounties) { buildBountyMarkersGeoJson(bounties) }
    val hasActiveSpots = remember(spots) { spots.any { it.status != CommunityReportStatus.RESOLVED } }
    val spotJson = remember(spots) { buildSpotMarkersGeoJson(spots) }
    val hasHeatActivity = hasActiveSpots

    // ── Cell-health choropleth (v2 Pillar 8 amendment) ──
    // Replaces the point-density spot-heat CircleLayers that used to live
    // here: instead of a blurred kernel-density surface over spot/report
    // points, each far-zoom tile is colored by its OWN constituents (litter
    // weight vs clean-coverage vs stale), aggregated from the SAME
    // coverageCells/observationFootprints already fed to the coverage-state
    // tiles and footprint layers below — no new fetch. tileMeters is chosen
    // from the caller's TARGET zoom (not live pinch — see
    // choroplethTileMetersForZoom's doc, same deferred-reactivity trade-off
    // useOutlineCoverage above already accepts).
    val choroplethTileMeters = remember(zoom) { choroplethTileMetersForZoom(zoom) }
    val choroplethTiles = remember(coverageCells, observationFootprints, spots, choroplethTileMeters) {
        aggregateCellHealth(coverageCells, observationFootprints, spots, choroplethTileMeters)
    }
    val choroplethJson = remember(choroplethTiles, choroplethTileMeters) {
        buildChoroplethGeoJson(choroplethTiles, choroplethTileMeters)
    }

    // Pillar 3 footprints: true-scale oriented shapes at close zoom, split by
    // domain into three JSON strings so each domain gets its own tinted
    // fill/outline pair (coverage-tile precedent) — simpler than one shared
    // source plus a color-match expression for three fixed buckets.
    //
    // Pillar 8 Task 2, Part B.1: buildObservationFootprintsGeoJson no longer
    // skips observations with a null/non-finite widthMeters — it falls back
    // to a type-default width instead, so gating on "has a real measurement"
    // would wrongly hide the footprint layer when every observation in view
    // is unmeasured (the common case). Gate on non-empty input instead.
    val hasFootprintData = observationFootprints.isNotEmpty()
    val footprintVegetationJson = remember(observationFootprints, centerLat) {
        buildObservationFootprintsGeoJson(observationFootprints.filter { it.domain == "vegetation" }, centerLat)
    }
    val footprintConstructionJson = remember(observationFootprints, centerLat) {
        buildObservationFootprintsGeoJson(observationFootprints.filter { it.domain == "construction" }, centerLat)
    }
    val footprintOtherJson = remember(observationFootprints, centerLat) {
        buildObservationFootprintsGeoJson(
            observationFootprints.filter { it.domain != "vegetation" && it.domain != "construction" },
            centerLat,
        )
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
            // detaches from the basemap during zoom/pan. Direct position
            // assignment — animateTo(0ms) does not reliably move the
            // camera (the editor rendered its geometry half a block off
            // because the camera silently stayed at the initial position).
            camera.position = CameraPosition(
                target = Position(latitude = targetLat, longitude = targetLng),
                zoom = zoom,
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
            // WS4: merged coverage outlines carry no cellId, so the per-layer
            // cellIdClickHandler Passes — resolve the tapped lat/lng back to a
            // cell here so tap-to-info still works on the painted coverage.
            onMapClick = onMapClick@{ pos, _ ->
                val tap = onCoverageCellTap ?: return@onMapClick ClickResult.Pass
                val cellId = coverageCellIdAt(pos.latitude, pos.longitude)
                if (coverageCells.any { it.locationCellId == cellId }) {
                    tap(cellId)
                    ClickResult.Consume
                } else {
                    ClickResult.Pass
                }
            },
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
            // routesSrc/routePolygonsSrc/routeInnerSrc/routeCorridorSrc are
            // remembered further down, inside the route-data-gated groups that use them —
            // the scan summary card passes an empty routeCoordinates list and must not pay
            // for these GeoJSON sources at all (Fix 2, r2a).

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

            // ── Cell-health choropleth (BELOW the route stack) ──
            // Texture-under-structure, same z-order the point-density heat
            // it replaces used to occupy: routes stay readable at overview
            // zoom. Gated on spots for the scrim (still keyed off the static
            // clean-zone polygon, no allocation cost) and on the aggregated
            // tiles for the choropleth itself — the scan summary card
            // renders GccMap with no data and must not allocate sources/
            // layers it never paints (Fix 2, r2a).
            if (spots.isNotEmpty()) {
                // ── Spot scrim: neutral off-white tint beneath the choropleth ──
                // Planet-spanning fill sourced from the same clean-zone GeoJSON.
                // Fades from 0.55 opacity at overview zoom to 0 by
                // IOS_SPOT_HEAT_FADE_ZOOM so it doesn't compete with street
                // labels at close zoom. Kept from the old point-density heat —
                // still the right backdrop for the choropleth's data-driven
                // fills, muting the basemap under whatever tiles render.
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

            // Cell-health choropleth: one data-driven FillLayer, colored per
            // tile by [HealthTile.dominant] (categorical grey override for
            // never-scanned ground) falling back to an interpolate over
            // [HealthTile.health] (green -> amber -> red). Gated on non-empty
            // tiles independent of `spots` — the aggregation is fed by
            // coverageCells/observationFootprints, not spot reports, so an
            // area with scan/litter data but zero community reports still
            // gets a choropleth. Fades OUT exactly at IOS_SPOT_HEAT_FADE_ZOOM
            // (the same constant the footprint layer below fades IN from) so
            // the choropleth and true-scale footprints never both show.
            if (choroplethTiles.isNotEmpty()) {
                val choroplethSrc = rememberGeoJsonSource(GeoJsonData.JsonString(choroplethJson))
                @Suppress("UNCHECKED_CAST")
                val choroplethHealthExpr = feature["health"] as org.maplibre.compose.expressions.ast.Expression<
                    org.maplibre.compose.expressions.value.NumberValue<Number>>
                @Suppress("UNCHECKED_CAST")
                val choroplethDominantExpr = feature["dominant"] as org.maplibre.compose.expressions.ast.Expression<
                    org.maplibre.compose.expressions.value.StringValue>

                FillLayer(
                    id = "choropleth-cell-health",
                    source = choroplethSrc,
                    minZoom = MapStyles.SPOT_HEATMAP_MIN_ZOOM.toFloat(),
                    maxZoom = IOS_SPOT_HEAT_FADE_ZOOM.toFloat(),
                    // Same green/red/amber/grey vocabulary as the coverage-
                    // state tiles (coverage-clean/dirty/stale/fog above) —
                    // "one visual system" per the Global Constraint.
                    // MUST be `interpolate`, never `interpolateHcl`: MapLibre
                    // Native (the iOS/Android engine) implements no
                    // `interpolate-hcl` / `interpolate-lab` operator — those are
                    // MapLibre GL JS only. Passing one makes
                    // `-[MLNFillStyleLayer setFillColor:]` raise an ObjC
                    // NSException that Kotlin/Native cannot catch → SIGABRT the
                    // instant this layer is built (crashed TestFlight 139).
                    color = org.maplibre.compose.expressions.dsl.switch(
                        choroplethDominantExpr,
                        org.maplibre.compose.expressions.dsl.case("unscanned", const(Color(0xFF4B5563))),
                        fallback = interpolate(
                            type = org.maplibre.compose.expressions.dsl.linear(),
                            input = choroplethHealthExpr,
                            0.0 to const(Color(0xFFDC2626)),
                            0.25 to const(Color(0xFFF97316)),
                            0.5 to const(Color(0xFFF59E0B)),
                            0.75 to const(Color(0xFF84CC16)),
                            1.0 to const(Color(0xFF22C55E)),
                        ),
                    ),
                    opacity = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        MapStyles.SPOT_HEATMAP_MIN_ZOOM to const(0.5f),
                        12.5 to const(0.6f),
                        13.5 to const(0.48f),
                        IOS_SPOT_HEAT_FADE_ZOOM to const(0f),
                    ),
                )
            }

            // ── Route polygon fill + 3-layer line stack (glow/casing/core) ──
            // Gated on routeCoordinates: the scan summary card renders GccMap
            // with no routes and must not allocate these sources/layers at all
            // (Fix 2, r2a) — z-order preserved, this whole group stays exactly
            // where it sat unconditionally before.
            if (routeCoordinates.isNotEmpty()) {
                val routesSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routesJson))
                val routePolygonsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routePolygonsJson))

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
                    onClick = routeIndexClickHandler(onRouteTap),
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
                    onClick = routeIndexClickHandler(onRouteTap),
                )

                // Active-route highlight: brighter/thicker outer lines on top
                // of the regular stack so the worker's assigned blocks still
                // read as "the ones to work" now that they render alongside
                // (not instead of) the rest of the service area. Deliberately
                // NON-tappable: this source is built from a sublist, so its
                // feature `routeIndex` properties are sublist-local and would
                // misreport the union index — taps fall through to the
                // underlying route core/fill layers, which carry the correct
                // indices for the same geometry.
                if (activeRouteIndices.isNotEmpty()) {
                    val activeRouteSrc = rememberGeoJsonSource(GeoJsonData.JsonString(activeRouteJson))
                    LineLayer(
                        id = MapStyles.LAYER_ROUTE_LINE + "-active-glow",
                        source = activeRouteSrc,
                        color = const(GccClaimed.copy(alpha = 0.55f)),
                        width = interpolate(
                            type = exponential(1.5f),
                            input = zoom(),
                            10 to const(2.dp),
                            13 to const(5.dp),
                            15 to const(11.dp),
                            17 to const(18.dp),
                        ),
                        cap = const(LineCap.Round),
                        join = const(LineJoin.Round),
                    )
                    LineLayer(
                        id = MapStyles.LAYER_ROUTE_LINE + "-active-core",
                        source = activeRouteSrc,
                        color = const(GccClaimed),
                        width = interpolate(
                            type = exponential(1.5f),
                            input = zoom(),
                            10 to const(1.dp),
                            13 to const(2.5.dp),
                            15 to const(4.5.dp),
                            17 to const(7.dp),
                        ),
                        cap = const(LineCap.Round),
                        join = const(LineJoin.Round),
                    )
                }
            }

            // ── Foundational coverage-state tiles (under heat + markers) ──
            // fog = never scanned (attention), green = scanned clean,
            // faded amber = stale (>24h) — dirty grading stays with the
            // spot-density heat layers above.
            if (coverageStateJsons != null) {
                val cleanSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.clean))
                val cleanGrazedSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.cleanGrazed))
                val seenSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.seen))
                val dirtySrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.dirty))
                val staleSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.stale))
                val fogSrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.fog))
                // Zoom-gated: at overview zooms the bounded fog patch would
                // read as a broken partial grid — fade it in at street level
                // where its bounded radius covers the whole viewport.
                // ── Delineation mandate (Pillar 8 Task 2, Part D3 — owner
                // amendment): "the map must clearly communicate scanned-in-
                // the-past vs never-scanned... unmistakable, without burying
                // basemap labels." Two changes from the previous 0.05-0.24
                // range (called out as "too subtle"):
                //  1. Fog stays gentle (0.08-0.16, was 0.08-0.22) — it must
                //     never compete with the basemap over the vast unscanned
                //     majority of the map.
                //  2. Scanned tiles (clean/grazed/dirty/stale) all move up
                //     meaningfully (clean 0.20->0.36, dirty 0.20->0.34, stale
                //     0.24->0.36, grazed 0.10->0.16) so ANY color reads as
                //     "this has been scanned" at a glance against fog/
                //     basemap — the FOG-VS-SCANNED contrast is what needs to
                //     read instantly, not any single value in isolation.
                //  Applies identically in MY_AREA and EXPLORER — coverageCells/
                //  coverageStateJsons are never mode-gated (MapFullScreen
                //  fetches coverage for both modes; only the separate opt-in
                //  "needs scanning" lens below is Explorer-only).
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
                        14 to const(0.14f),
                        16 to const(0.16f),
                    ),
                )
                // The tile visual system (one glance = scan status):
                //   green = scanned clean · red = scanned, litter on record
                //   amber = stale, ready for re-scan · grey veil = never
                // All scanned tiles are tappable: cellId rides in each
                // feature's properties (buildCoverageStateGeoJsons) and the
                // caller resolves it to the cell for the last-scan card.
                // Seen-fresh (Pillar 7): sighted at a distance, never walked —
                // the faintest tile of the three, drawn under clean-grazed.
                // Left deliberately faint/unchanged — it's an explicitly
                // lower-confidence tier (sighted, never walked), not part of
                // the "unmistakable scanned" contrast the owner asked for.
                // Outline-mode rings (street zoom) carry no cellId, so taps
                // don't resolve to a cell there — same trade-off as clean/
                // clean-grazed in that mode.
                FillLayer(
                    id = "coverage-seen",
                    source = seenSrc,
                    color = const(Color(0xFF22C55E)),
                    opacity = const(0.05f),
                    onClick = cellIdClickHandler(onCoverageCellTap),
                )
                // Grazed-fresh: scanned at sweep quality only — lighter green,
                // the map-level "come back and detail-scan me" signal.
                FillLayer(
                    id = "coverage-clean-grazed",
                    source = cleanGrazedSrc,
                    color = const(Color(0xFF22C55E)),
                    opacity = const(0.16f),
                    onClick = cellIdClickHandler(onCoverageCellTap),
                )
                FillLayer(
                    id = "coverage-clean",
                    source = cleanSrc,
                    color = const(Color(0xFF22C55E)),
                    opacity = const(0.36f),
                    onClick = cellIdClickHandler(onCoverageCellTap),
                )
                FillLayer(
                    id = "coverage-dirty",
                    source = dirtySrc,
                    color = const(Color(0xFFEF4444)),
                    opacity = const(0.34f),
                    onClick = cellIdClickHandler(onCoverageCellTap),
                )
                // Stale is a CALL TO ACTION ("scan me again") — it must not
                // be the faintest tile on the map (it was 0.10, then 0.24).
                FillLayer(
                    id = "coverage-stale",
                    source = staleSrc,
                    color = const(Color(0xFFF59E0B)),
                    opacity = const(0.36f),
                    onClick = cellIdClickHandler(onCoverageCellTap),
                )

                // ── Boundary line treatment (Part D3) ──
                // The organic outline tracing from PR #108 (CoverageOutline.kt's
                // traceCoverageOutlines, wired in via useOutlineCoverage/
                // qualityCollection above) already produces clean boundary
                // geometry for clean/cleanGrazed at street zoom — nothing
                // previously drew a LINE along it (only filled polygons).
                // A crisp edge reads clearly without raising fill opacity to
                // the point of obscuring basemap labels — likely the single
                // highest-leverage change for "unmistakable... without
                // burying labels", so it's added independent of (and on top
                // of) the fill-opacity bump above. Dirty/stale stay per-cell
                // quads even at street zoom (see buildCoverageStateGeoJsons
                // doc), so their boundary line traces blockier cell edges —
                // still a real, visible edge, just less smooth than clean's.
                // Reuses the SAME sources as the fills above (no extra
                // source/layer allocation beyond the line layer itself).
                val coverageBoundaryWidth = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    13 to const(0.5.dp),
                    16 to const(1.2.dp),
                    18 to const(1.8.dp),
                )
                LineLayer(
                    id = "coverage-clean-grazed-boundary",
                    source = cleanGrazedSrc,
                    color = const(Color(0xFF16A34A)),
                    width = coverageBoundaryWidth,
                    opacity = const(0.45f),
                )
                LineLayer(
                    id = "coverage-clean-boundary",
                    source = cleanSrc,
                    color = const(Color(0xFF15803D)),
                    width = coverageBoundaryWidth,
                    opacity = const(0.65f),
                )
                LineLayer(
                    id = "coverage-dirty-boundary",
                    source = dirtySrc,
                    color = const(Color(0xFFB91C1C)),
                    width = coverageBoundaryWidth,
                    opacity = const(0.55f),
                )
                LineLayer(
                    id = "coverage-stale-boundary",
                    source = staleSrc,
                    color = const(Color(0xFFB45309)),
                    width = coverageBoundaryWidth,
                    opacity = const(0.55f),
                )

                // P5 "Needs scanning" lens: an opt-in single FillLayer atop the
                // tint above, data-driven off each feature's "priority"
                // property (0..1) rather than a bucketed set like the tiles
                // above it — amber (low urgency) to red (high urgency).
                // Toggle-gated AND non-empty-gated (Global Constraints: no
                // source/layer allocation when there's nothing to show).
                if (showNeedsScanningLens && coverageStateJsons.priority != EMPTY_GEOJSON_FEATURE_COLLECTION) {
                    val prioritySrc = rememberGeoJsonSource(GeoJsonData.JsonString(coverageStateJsons.priority))
                    @Suppress("UNCHECKED_CAST")
                    val priorityExpr = feature["priority"] as org.maplibre.compose.expressions.ast.Expression<
                        org.maplibre.compose.expressions.value.NumberValue<Number>>
                    FillLayer(
                        id = "coverage-needs-scanning",
                        source = prioritySrc,
                        // `interpolate`, not `interpolateHcl` — see the
                        // choropleth layer above: MapLibre Native has no
                        // interpolate-hcl operator and aborts the process.
                        color = interpolate(
                            type = linear(),
                            input = priorityExpr,
                            0f to const(Color(0xFFF59E0B)), // amber — just crossed the age threshold
                            1f to const(Color(0xFFDC2626)), // red — long overdue / grazed-only
                        ),
                        opacity = interpolate(
                            type = linear(),
                            input = priorityExpr,
                            0f to const(0.20f),
                            1f to const(0.50f),
                        ),
                        onClick = cellIdClickHandler(onCoverageCellTap),
                    )
                }
            }

            // ── Scan corridor + inner boundary (double-lined routes) ──
            // Gated on routeCoordinates — same reasoning as the route fill/line
            // stack above: no route means none of this should allocate a
            // source or layer (Fix 2, r2a). Kept as its own group (rather than
            // merged with the fill/line stack) because the coverage-state
            // tiles sit between them in z-order and must not move.
            if (routeCoordinates.isNotEmpty()) {
                val routeInnerSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routeInnerJson))
                val routeCorridorSrc = rememberGeoJsonSource(GeoJsonData.JsonString(routeCorridorJson))
                // The band BETWEEN the two boundaries is the assignment: tinted
                // so "scan here" is explicit. Inside the inner ring (private
                // property) and outside the outer ring are excluded.
                FillLayer(
                    id = "route-corridor-band",
                    source = routeCorridorSrc,
                    color = const(GccGreen),
                    // Strengthens toward work zoom: the corridor IS the
                    // opportunity, so it earns more presence as spot detail
                    // resolves around it — while staying a quiet base at the
                    // handoff zooms where dots are fading in.
                    opacity = interpolate(
                        type = exponential(1.5f),
                        input = zoom(),
                        13 to const(0.06f),
                        15 to const(0.12f),
                        16.5 to const(0.18f),
                    ),
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

            }

            // Gated on imageStars (Fix 2, r2a) — the scan summary card never
            // has image-location stars and must not allocate these sources/layers.
            if (imageStars.isNotEmpty()) {
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
            }

            // Gated on bounties (Fix 2, r2a) — the scan summary card never has
            // bounties and must not allocate this source/layers.
            if (bounties.isNotEmpty()) {
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
            }

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

            // ── Observation footprints (Pillar 3; THE close-zoom litter-truth
            // layer as of Pillar 8 Task 2 — replaces the removed spot pins) ──
            // True-scale, oriented shapes at close zoom — declared BEFORE the
            // user-location dot AND (formerly) the pin/glyph layers below so
            // footprints render UNDER them (z-order = declaration order); a
            // footprint must never sit atop the user's own position dot.
            // Gated on non-empty footprint data (Fix 2, r2a convention) and
            // zoom (>=17 via opacity interpolate) — heatmap owns the far
            // zoom story, footprints are the close-zoom "what does it
            // actually look like" detail. Domain-tinted per the geometry-
            // approximation table: vegetation green, construction grey,
            // everything else (litter/unspecified) orange. No clustering —
            // buildObservationFootprintsGeoJson maps every observation to
            // its own feature (Part B.4); nothing to remove or add here.
            if (hasFootprintData) {
                val footprintVegetationSrc = rememberGeoJsonSource(GeoJsonData.JsonString(footprintVegetationJson))
                val footprintConstructionSrc = rememberGeoJsonSource(GeoJsonData.JsonString(footprintConstructionJson))
                val footprintOtherSrc = rememberGeoJsonSource(GeoJsonData.JsonString(footprintOtherJson))
                // Zoom hand-off fix (owner follow-up on commit 8bf83fd): with
                // spot pins removed (Part A), the old 16.5->17 fade-in left a
                // dead band between IOS_SPOT_HEAT_FADE_ZOOM (14.2, where the
                // heat texture above finishes fading OUT) and 16.5 — that gap
                // used to be covered by spot pins/dots, which no longer exist.
                // The footprint fade-in now starts at IOS_SPOT_HEAT_FADE_ZOOM
                // itself (not a separate magic number) so the two layers hand
                // off with zero gap by construction — if MapStyles' heat
                // fade-out zoom ever moves, this moves with it — and reaches
                // full legibility by z15.5, well inside the "close zoom"
                // range where these shapes are meant to read as authoritative.
                val footprintFadeInStartZoom = IOS_SPOT_HEAT_FADE_ZOOM
                val footprintFadeInFullZoom = 15.5
                val footprintOutlineOpacity = interpolate(
                    type = exponential(1.5f),
                    input = zoom(),
                    footprintFadeInStartZoom to const(0f),
                    footprintFadeInFullZoom to const(1f),
                )
                // Part B.3 — diagnostic-state styling, now wired to the real
                // per-feature signal (Pillar 8 Task 4 adds diagnosticStatus
                // end to end). buildObservationFootprintsGeoJson
                // (GeoJsonLayers.kt) emits a "diagnosed" boolean property per
                // feature (diagnosticStatus != "observed" — true for both a
                // real diagnosed value AND null/legacy rows, see
                // WorldObservation.diagnosticStatus's KDoc). maplibre-compose
                // exposes boolean feature properties the same way string/
                // number ones are already used elsewhere in this file
                // (`feature["priority"] as Expression<NumberValue<...>>`,
                // `feature["itemType"] as Expression<StringValue>`) — cast to
                // Expression<BooleanValue> and use directly as a layer
                // `filter`, the exact pattern this file used pre-Task-2 for
                // `filter = feature.has("itemType")` / `filter =
                // !feature.has(...)` (see git history on this file, e.g.
                // commit 8bf83fdc's parent). So each domain's footprint layer
                // pair is split into a diagnosed-filtered (solid) pair and an
                // observed-filtered (hollow) pair instead of a single flat
                // isDiagnosed constant — solid = full fill opacity + thin
                // outline, hollow = fill scaled down (~35%) + thicker outline
                // so it reads as a deliberate ring, not a bug.
                @Suppress("UNCHECKED_CAST")
                val diagnosedExpr = feature["diagnosed"] as org.maplibre.compose.expressions.ast.Expression<
                    org.maplibre.compose.expressions.value.BooleanValue>
                val observedExpr = !diagnosedExpr
                val footprintOutlineWidthDiagnosed = const(0.5.dp)
                val footprintOutlineWidthObserved = const(1.2.dp)
                val footprintTapHandler = observationIdClickHandler(onObservationTap)

                fun fullOpacityExpr(target: Float) = interpolate(
                    type = exponential(1.5f), input = zoom(),
                    footprintFadeInStartZoom to const(0f),
                    footprintFadeInFullZoom to const(target),
                )

                val footprintDomains = listOf(
                    Triple("vegetation", footprintVegetationSrc, Color(0xFF16A34A)) to 0.18f,
                    Triple("construction", footprintConstructionSrc, Color(0xFF6B7280)) to 0.18f,
                    Triple("other", footprintOtherSrc, Color(0xFFF97316)) to 0.20f,
                )
                for ((domainInfo, fullOpacityTarget) in footprintDomains) {
                    val (domainName, domainSrc, domainColor) = domainInfo

                    FillLayer(
                        id = "observation-footprint-fill-$domainName-diagnosed",
                        source = domainSrc,
                        filter = diagnosedExpr,
                        color = const(domainColor),
                        opacity = fullOpacityExpr(fullOpacityTarget),
                        onClick = footprintTapHandler,
                    )
                    LineLayer(
                        id = "observation-footprint-outline-$domainName-diagnosed",
                        source = domainSrc,
                        filter = diagnosedExpr,
                        color = const(domainColor),
                        width = footprintOutlineWidthDiagnosed,
                        opacity = footprintOutlineOpacity,
                    )

                    FillLayer(
                        id = "observation-footprint-fill-$domainName-observed",
                        source = domainSrc,
                        filter = observedExpr,
                        color = const(domainColor),
                        opacity = fullOpacityExpr(fullOpacityTarget * 0.35f),
                        onClick = footprintTapHandler,
                    )
                    LineLayer(
                        id = "observation-footprint-outline-$domainName-observed",
                        source = domainSrc,
                        filter = observedExpr,
                        color = const(domainColor),
                        width = footprintOutlineWidthObserved,
                        opacity = footprintOutlineOpacity,
                    )
                }
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

            // Spot pin/marker/verification-ring layers removed (Pillar 8 Task 2,
            // Part A): the observation-footprint layer above ("Observation
            // footprints" block) is now THE close-zoom litter-truth visualization,
            // replacing what spot-rect-fill/spot-rect-outline, LAYER_SPOT_MARKER_HALOS,
            // LAYER_BOUNTIED_SPOT_HALOS, the spot-markers tap target, LAYER_SPOT_MARKERS,
            // "${LAYER_SPOT_MARKERS}-typed-dots", the spot-markers-glyphs SymbolLayer, and
            // spot-verification-ring used to show. Heat layers (spot-density-heat-*,
            // above) and spot-rect/bountied-spot JSON builders untouched — `spots` still
            // feeds those. See composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/
            // GeoJsonLayers.kt's buildObservationFootprintsGeoJson for the replacement.

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

/**
 * Shared tap handler for the four coverage-state fill layers (clean-grazed,
 * clean, dirty, stale): every layer's features carry the scanned cell's id
 * in a `cellId` property (buildCoverageStateGeoJsons) — this reads it and
 * forwards to [onTap]. Falls back to [ClickResult.Pass] when the tapped
 * feature has no usable cellId, and returns null (no handler at all) when
 * the caller passed no [onTap] — matching the layers' `onClick: T? = null`
 * contract so an absent callback disables hit-testing entirely.
 */
private fun cellIdClickHandler(onTap: ((String) -> Unit)?): FeaturesClickHandler? =
    if (onTap != null) { features ->
        val cellId = features.firstOrNull()?.properties?.get("cellId")
            ?.toString()?.trim('"')
        if (!cellId.isNullOrBlank()) {
            onTap(cellId)
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    } else null

/**
 * Shared tap handler for the three observation-footprint fill layers
 * (vegetation/construction/other, Pillar 8 Task 2 Part C): every feature
 * carries the source observation's id in an `id` property
 * (buildObservationFootprintsGeoJson) — this reads it and forwards to
 * [onTap]. Mirrors [cellIdClickHandler]/onAffectedBlockTap's exact
 * structure. Returns null (no handler) when the caller passed no [onTap].
 */
private fun observationIdClickHandler(onTap: ((Int) -> Unit)?): FeaturesClickHandler? =
    if (onTap != null) { features ->
        val raw = features.firstOrNull()?.properties?.get("id")
        val id = (raw as? Number)?.toInt() ?: raw?.toString()?.toIntOrNull()
        if (id != null) {
            onTap(id)
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    } else null

/**
 * Shared tap handler for the route fill/line layers: every feature carries
 * the tapped route's index in a `routeIndex` property — this reads it and
 * forwards to [onTap]. Returns null (no handler) when the caller passed no
 * [onTap], matching the layers' `onClick: T? = null` contract.
 */
private fun routeIndexClickHandler(onTap: ((Int) -> Unit)?): FeaturesClickHandler? =
    if (onTap != null) { features ->
        val idx = (features.firstOrNull()?.properties?.get("routeIndex") as? Number)?.toInt()
        if (idx != null) {
            onTap(idx)
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    } else null

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
