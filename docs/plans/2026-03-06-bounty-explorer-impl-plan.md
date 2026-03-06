# Bounty System & Explorer Map — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add dual-mode map (My Area / Explorer), GPS-gated bounty markers, community area labels with aggregate counters, and enhanced city boundary rendering.

**Architecture:** Single MapLibre map instance with a segmented control toggling between bounded "My Area" mode and unrestricted "Explorer" mode. Bounty/spot data layers are GPS-gated in Explorer. Community area centroids get GeoJSON symbol labels. All new layers integrate into the existing GccMap composable via additional GeoJSON sources.

**Tech Stack:** Kotlin Multiplatform (KMP), Compose Multiplatform, MapLibre Compose, Ktor (API client), Decompose (navigation)

---

## Phase 1: Map Enhancements (City Boundary, Area Labels)

### Task 1: Enhance City Boundary Rendering

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt:74-77`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt:133-145`

**Step 1: Update city boundary style constants**

In `MapStyles.kt`, change the city outline to a deeper blue-gray and wider stroke. Add a glow layer ID.

```kotlin
// Replace lines 75-77 with:
val CITY_OUTLINE_COLOR = Color(0xFF1E3A5F)        // deep blue-gray (was 0xFF4B5563)
const val CITY_OUTLINE_WIDTH = 3.0f                // wider (was 1.5f)
val CITY_OUTLINE_GLOW_COLOR = Color(0xFF1E3A5F)    // same color, used at low opacity
const val LAYER_CITY_OUTLINE_GLOW = "city-outline-glow"
```

**Step 2: Add city boundary glow layer in GccMap.ios.kt**

Insert a glow layer BEFORE the existing city outline layer (around line 133). This creates a 2-layer stack (glow + core) similar to route rendering:

```kotlin
// City outline glow — wide semi-transparent halo
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
```

Then update the existing city outline width interpolation to match the new wider spec:

```kotlin
// City outline (Chicago) — more pronounced
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
)
```

**Step 3: Build, install, verify**

Run: `./gradlew :composeApp:iosSimulatorArm64MainBinaries`
Then Xcode build, install on simulator, check city boundary is more pronounced at zoom 11.

**Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt \
       composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt
git commit -m "feat(map): enhance city boundary with glow layer and deeper color"
```

---

### Task 2: Add Community Area Name Labels

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt`

**Step 1: Add label layer constants to MapStyles.kt**

```kotlin
// Add after line 43:
const val LAYER_COMMUNITY_LABELS = "community-labels"
const val SOURCE_COMMUNITY_LABELS = "community-labels-src"
```

**Step 2: Create GeoJSON builder for community area centroids**

Add to `GeoJsonLayers.kt` (after `buildServiceAreaGeoJson`):

```kotlin
/**
 * GeoJSON FeatureCollection of community area centroids for label placement.
 * Each point has properties: name, number, isServiceArea.
 */
fun buildCommunityAreaLabelsGeoJson(): String {
    val features = chicagoCommunityAreas.map { area ->
        val centroidLat = (area.bounds.north + area.bounds.south) / 2.0
        val centroidLng = (area.bounds.east + area.bounds.west) / 2.0
        val name = area.displayName.replace("\n", " ")
        """{"type":"Feature","properties":{"name":"$name","number":${area.number},"isServiceArea":${area.isServiceArea}},"geometry":{"type":"Point","coordinates":[$centroidLng,$centroidLat]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}
```

**Step 3: Add the label source and SymbolLayer in GccMap.ios.kt**

After the community area tap target layer (around line 183), add:

```kotlin
// Community area name labels — positioned at centroid of each area
val labelsJson = remember { buildCommunityAreaLabelsGeoJson() }
val labelsSrc = rememberGeoJsonSource(GeoJsonData.JsonString(labelsJson))

SymbolLayer(
    id = MapStyles.LAYER_COMMUNITY_LABELS,
    source = labelsSrc,
    textField = get("name"),
    textSize = interpolate(
        type = exponential(1.5f),
        input = zoom(),
        10 to const(0.dp),     // hidden
        11 to const(9.dp),     // small
        13 to const(12.dp),    // readable
        15 to const(10.dp),    // shrink
        16 to const(0.dp),     // fade out
    ),
    textColor = const(Color(0xFF4B5563)),  // dark gray
    textHaloColor = const(Color.White),
    textHaloWidth = const(1.5.dp),
    textAllowOverlap = const(false),
    textIgnorePlacement = const(false),
)
```

Note: `SymbolLayer` needs to be imported from `org.maplibre.compose.layers.SymbolLayer`. Check MapLibre Compose API — if `SymbolLayer` isn't available, use a workaround with `TextLayer` or native annotation. Verify against the MapLibre Compose docs.

**Step 4: Build and verify labels appear at zoom 11-15**

**Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt \
       composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt
git commit -m "feat(map): add community area name labels at centroids"
```

---

### Task 3: Implement Soft Camera Bounding for My Area Mode

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt:59-78` (add new params)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt:77-82` (camera bounding logic)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CommunityAreaData.kt:3-8` (expose bounds padding helper)

**Step 1: Add cameraBounds parameter to GccMap expect declaration**

In `GccMap.kt`, add a new parameter to the expect function:

```kotlin
// Add after line 77 (before content parameter):
cameraBounds: AreaBounds? = null,  // soft camera bounding — null = no constraint
```

**Step 2: Implement soft bounding in GccMap.ios.kt**

After the camera setup (line 82), add a LaunchedEffect that monitors camera position:

```kotlin
// Soft camera bounding — snap back if user pans outside bounds
if (cameraBounds != null) {
    val padding = 0.15 // 15% padding
    val latSpan = cameraBounds.north - cameraBounds.south
    val lngSpan = cameraBounds.east - cameraBounds.west
    val paddedBounds = AreaBounds(
        north = cameraBounds.north + latSpan * padding,
        south = cameraBounds.south - latSpan * padding,
        east = cameraBounds.east + lngSpan * padding,
        west = cameraBounds.west - lngSpan * padding,
    )

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
```

**Step 3: Pass bounds from MapFullScreen**

In `MapFullScreen.kt`, pass the service area bounds:

```kotlin
GccMap(
    // ... existing params ...
    cameraBounds = if (mapMode == MapMode.MY_AREA) serviceArea?.bounds else null,
)
```

(The `mapMode` state will be added in Task 4.)

**Step 4: Commit**

```bash
git commit -m "feat(map): implement soft camera bounding for assigned area"
```

---

## Phase 2: Dual-Mode Map Toggle

### Task 4: Add My Area / Explorer Segmented Control

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapModeToggle.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`

**Step 1: Create MapMode enum and toggle composable**

Create `MapModeToggle.kt`:

```kotlin
package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*

enum class MapMode { MY_AREA, EXPLORER }

@Composable
fun MapModeToggle(
    currentMode: MapMode,
    onModeChange: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
        color = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
        ) {
            MapMode.entries.forEach { mode ->
                val isSelected = mode == currentMode
                val label = when (mode) {
                    MapMode.MY_AREA -> "My Area"
                    MapMode.EXPLORER -> "Explorer"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (isSelected) GccGreen else Color.Transparent)
                        .clickable { onModeChange(mode) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (isSelected) Color.White else TextMuted,
                    )
                }
            }
        }
    }
}
```

**Step 2: Add map mode state to MapFullScreen.kt**

Add state and wire the toggle into the map overlay:

```kotlin
// Add at the top of MapFullScreen composable:
var mapMode by remember { mutableStateOf(MapMode.MY_AREA) }

// In the GccMap content lambda, add the toggle:
MapModeToggle(
    currentMode = mapMode,
    onModeChange = { mapMode = it },
    modifier = Modifier
        .align(Alignment.TopCenter)
        .statusBarsPadding()
        .padding(top = 4.dp),
)

// CommunityInfoPanel should only show in MY_AREA mode:
if (mapMode == MapMode.MY_AREA) {
    CommunityInfoPanel(...)
}
```

**Step 3: Wire camera bounds to mode**

```kotlin
// Pass bounds only in My Area mode:
cameraBounds = if (mapMode == MapMode.MY_AREA) serviceArea?.bounds else null,

// Adjust zoom on mode switch:
zoom = if (mapMode == MapMode.MY_AREA) MapStyles.ZOOM_SERVICE_AREA else MapStyles.ZOOM_CITY,
```

**Step 4: Build and verify toggle works**

**Step 5: Commit**

```bash
git commit -m "feat(map): add My Area / Explorer segmented toggle with camera mode switching"
```

---

## Phase 3: Bounty & Spot Markers on Map

### Task 5: Add Bounty Markers GeoJSON Layer

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt:100-106`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt:59-78`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`

**Step 1: Enhance bounty GeoJSON builder with amount/visibility properties**

Replace `buildBountyMarkersGeoJson` in GeoJsonLayers.kt:

```kotlin
/**
 * GeoJSON for bounty markers with amount and visibility metadata.
 * Only includes public bounties (secret bounties are never shown on map).
 */
fun buildBountyMarkersGeoJson(bounties: List<Bounty>): String {
    if (bounties.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = bounties.filter { it.status == BountyStatus.OPEN }
        .map { b ->
            val dollars = b.amount / 100
            val cents = b.amount % 100
            val amountStr = if (cents == 0) "$$dollars" else "$$dollars.${cents.toString().padStart(2, '0')}"
            """{"type":"Feature","properties":{"id":${b.id},"amount":${b.amount},"amountLabel":"$amountStr","communityArea":"${b.communityArea ?: ""}","radiusMeters":${b.radiusMeters}},"geometry":{"type":"Point","coordinates":[${b.locationLng},${b.locationLat}]}}"""
        }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}
```

**Step 2: Add bounty layer constants to MapStyles.kt**

```kotlin
// Bounty marker layers
const val LAYER_BOUNTY_DOTS = "bounty-dots"
const val LAYER_BOUNTY_LABELS = "bounty-labels"
const val LAYER_BOUNTY_RADIUS = "bounty-radius"
val BOUNTY_DOT_COLOR = Color(0xFFEF4444)  // red — matches PIN_RED
val BOUNTY_RADIUS_FILL = Color(0x1AEF4444) // 10% opacity red
```

**Step 3: Add bounties parameter to GccMap expect/actual**

In `GccMap.kt` add parameter. In `GccMap.ios.kt`, add the source and layers:

```kotlin
// Source
val bountyJson = remember(bounties) { buildBountyMarkersGeoJson(bounties) }
val bountySrc = rememberGeoJsonSource(GeoJsonData.JsonString(bountyJson))

// Bounty radius circles (zoom 14+)
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
)

// Bounty amount labels — "$XX" text above dots
// NOTE: This requires SymbolLayer support in MapLibre Compose.
// If not available, implement as Compose overlays projected from coordinates.
```

**Step 4: Wire bounty data from MapFullScreen**

Load bounties in MapFullScreen and pass to GccMap:

```kotlin
// In MapFullScreen:
val bountyViewModel = koinInject<BountyViewModel>()
val bountyState by bountyViewModel.uiState.collectAsState()

LaunchedEffect(Unit) {
    bountyViewModel.loadBounties()
}

// Pass to GccMap:
GccMap(
    bounties = bountyState.bounties,
    // ... other params
)
```

**Step 5: Commit**

```bash
git commit -m "feat(map): add bounty marker dots with amount labels and radius circles"
```

---

### Task 6: Add Spot Report Markers Layer

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt` (add function)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`

**Step 1: Create spot markers GeoJSON builder**

```kotlin
fun buildSpotMarkersGeoJson(spots: List<CommunityReport>): String {
    if (spots.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = spots.map { s ->
        """{"type":"Feature","properties":{"id":${s.id},"type":"${s.type}","points":${s.observerRewardPoints}},"geometry":{"type":"Point","coordinates":[${s.coordinates.lng},${s.coordinates.lat}]}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}
```

**Step 2: Add spot layer constants**

```kotlin
const val LAYER_SPOT_MARKERS = "spot-markers"
val SPOT_MARKER_COLOR = Color(0xFFF97316)  // orange
```

**Step 3: Add CircleLayer for spots in GccMap.ios.kt**

Orange circles, visible at zoom 13+:

```kotlin
CircleLayer(
    id = MapStyles.LAYER_SPOT_MARKERS,
    source = spotSrc,
    color = const(MapStyles.SPOT_MARKER_COLOR),
    radius = interpolate(
        type = exponential(1.5f),
        input = zoom(),
        12 to const(0.dp),
        13 to const(3.dp),
        15 to const(6.dp),
    ),
    strokeColor = const(Color.White),
    strokeWidth = const(1.dp),
)
```

**Step 4: Load spots via API and pass to map**

Add a `loadSpots` function to a ViewModel (or extend DashboardViewModel):

```kotlin
// GET /api/spots?limit=100 for current area
```

**Step 5: Commit**

```bash
git commit -m "feat(map): add orange spot report markers visible at zoom 13+"
```

---

## Phase 4: GPS-Gated Bounty Visibility

### Task 7: Implement Point-in-Polygon GPS Check

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoUtils.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`

**Step 1: Create point-in-polygon utility**

```kotlin
package com.gcc.app.ui.map

/**
 * Ray-casting point-in-polygon check.
 * Polygon is a list of (lat, lng) pairs forming a closed ring.
 */
fun isPointInPolygon(lat: Double, lng: Double, polygon: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val yi = polygon[i].first
        val xi = polygon[i].second
        val yj = polygon[j].first
        val xj = polygon[j].second
        if (((yi > lat) != (yj > lat)) &&
            (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Find which community area (if any) contains the given GPS coordinates.
 */
fun findCommunityAreaForLocation(lat: Double, lng: Double): CommunityArea? {
    return chicagoCommunityAreas.find { area ->
        // Quick bounding box check first
        lat in area.bounds.south..area.bounds.north &&
        lng in area.bounds.west..area.bounds.east &&
        // Then precise polygon check
        area.polygon.isNotEmpty() && isPointInPolygon(lat, lng, area.polygon)
    }
}
```

**Step 2: Use GPS location to filter bounty visibility in Explorer mode**

In `MapFullScreen.kt`, when in Explorer mode:

```kotlin
// Track current GPS community area
val currentGpsArea = remember(currentLocation) {
    currentLocation?.let { findCommunityAreaForLocation(it.lat, it.lng) }
}

// Filter bounties: in Explorer mode, only show bounties in the user's current GPS area
val visibleBounties = remember(bountyState.bounties, mapMode, currentGpsArea) {
    if (mapMode == MapMode.MY_AREA) {
        bountyState.bounties // show all in assigned area
    } else {
        // Explorer: only show bounties in the community area user is physically in
        val areaName = currentGpsArea?.name
        if (areaName != null) {
            bountyState.bounties.filter { it.communityArea == areaName }
        } else {
            emptyList()
        }
    }
}
```

**Step 3: Commit**

```bash
git commit -m "feat(map): GPS-gated bounty visibility using point-in-polygon check"
```

---

## Phase 5: Community Aggregate Counters

### Task 8: Add Community Stats API and Aggregate Labels

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GeoJsonLayers.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CommunityInfoPanel.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/ui/map/GccMap.ios.kt`

**Step 1: Add community stats data model**

```kotlin
@Serializable
data class CommunityStats(
    val communityArea: String,
    val messesCount: Int = 0,
    val bountiesCount: Int = 0,
    val totalBountyAmount: Int = 0, // cents
)
```

**Step 2: Add stats loading to ViewModel**

```kotlin
fun loadCommunityStats(areaName: String) {
    scope.launch {
        try {
            val response = apiClient.httpClient.get("${apiClient.baseUrl}/api/community-stats") {
                parameter("area", areaName)
            }
            val stats = response.body<ApiResponse<CommunityStats>>()
            _uiState.value = _uiState.value.copy(communityStats = stats.data)
        } catch (_: Exception) { }
    }
}
```

**Step 3: Update CommunityInfoPanel to show counters**

Add parameters for messes/bounties/bountyAmount and render a new row below the stats.

**Step 4: Build aggregate label GeoJSON for Explorer mode**

Create `buildCommunityAggregateLabelsGeoJson(statsMap: Map<String, CommunityStats>)` that adds messes/bounties/$ to the centroid label text.

**Step 5: Commit**

```bash
git commit -m "feat(map): add community area aggregate stats counters"
```

---

## Phase 6: Gamification Points (Server-Side)

### Task 9: Add Gamification Point Awards to Server

**Files (on DGX server):**
- Server routes and services at `/home/codex450/CDX-DEV-PROJECTS/gcc/server/`

This task requires server-side work on the DGX. The implementation involves:

1. Create a `GamificationService` that calculates points based on action type
2. Add point constants matching the design doc values
3. Wire point awards into existing completion handlers (spot cleanup, bounty completion, route completion)
4. Add early cleanup bonus check (< 1 hour from spot creation)
5. Add reporter-cleans-own-spot detection (250 pts)
6. Add daily cap enforcement (2000 pts/day)
7. Add streak tracking (consecutive active days)

**Step 1: Create gamification points table migration**

```sql
CREATE TABLE IF NOT EXISTS gamification_points (
    id SERIAL PRIMARY KEY,
    worker_id INTEGER NOT NULL REFERENCES workers(id),
    action_type VARCHAR(50) NOT NULL,
    base_points INTEGER NOT NULL,
    multiplier DECIMAL(3,2) DEFAULT 1.0,
    total_points INTEGER NOT NULL,
    reference_id INTEGER,
    reference_type VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_gamification_worker ON gamification_points(worker_id);
CREATE INDEX idx_gamification_date ON gamification_points(created_at);
```

**Step 2: Create `GamificationService.kt`**

Implement point calculation with:
- Action type enum matching design doc
- Streak multiplier lookup
- Daily cap check
- Early cleanup bonus detection

**Step 3: Wire into existing completion endpoints**

**Step 4: Commit server changes**

---

## Phase Summary

| Phase | Tasks | Focus |
|---|---|---|
| 1 | Tasks 1-3 | Map enhancements: city boundary, area labels, camera bounding |
| 2 | Task 4 | Dual-mode toggle: My Area / Explorer segmented control |
| 3 | Tasks 5-6 | Bounty + spot markers on map |
| 4 | Task 7 | GPS-gated visibility using point-in-polygon |
| 5 | Task 8 | Community aggregate counters |
| 6 | Task 9 | Gamification point system (server) |

**Total estimated tasks:** 9 implementation tasks across 6 phases.

**Key dependencies:**
- Task 3 (camera bounding) is needed by Task 4 (mode toggle)
- Task 5 (bounty markers) is needed by Task 7 (GPS gating)
- Task 8 (stats API) requires server endpoint — can stub initially
- Task 9 (gamification) is server-only and independent of frontend tasks

**Testing approach:** Each task ends with a build + simulator verification. MapLibre layers are visual — use screenshots to verify rendering at multiple zoom levels.
