# Global Location Awareness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all hardcoded Chicago/North Lawndale location defaults so the app works globally — centering maps on the user's verified service area or GPS position, always showing the user's location dot, and adding a "zoom to my location" button.

**Architecture:** Cascading map center resolution: verified service area → GPS → world fallback. A new `resolveMapCenter()` utility provides the single source of truth used by DashboardScreen and MapFullScreen. A new `MyLocationButton` composable provides the zoom-to-GPS action. Signup form loses its Chicago defaults.

**Tech Stack:** Kotlin Multiplatform (Compose Multiplatform), MapLibre (iOS), Ktor server (Exposed ORM)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `shared/.../constants/GccConstants.kt` | Modify | Remove `DEFAULT_CITY`, `DEFAULT_STATE` |
| `shared/.../model/ServiceArea.kt` | Modify | Remove GccConstants default references for city/state |
| `composeApp/.../ui/map/MapStyles.kt` | Modify | Rename Chicago constants, add `ZOOM_WORLD` |
| `composeApp/.../ui/map/MapCenterResolver.kt` | **Create** | `resolveMapCenter()` utility |
| `composeApp/.../ui/map/MyLocationButton.kt` | **Create** | Reusable locate-me FAB composable |
| `composeApp/.../ui/map/GccMap.kt` | Modify | Update default param values |
| `composeApp/.../viewmodel/DashboardViewModel.kt` | Modify | Store full `ServiceArea?` in state |
| `composeApp/.../viewmodel/RegisterViewModel.kt` | Modify | Remove Chicago/IL defaults |
| `composeApp/.../ui/screens/RegisterScreen.kt` | Modify | Remove Chicago/IL defaults, update labels |
| `composeApp/.../ui/screens/DashboardScreen.kt` | Modify | Use `resolveMapCenter()`, add MyLocationButton |
| `composeApp/.../ui/screens/MapFullScreen.kt` | Modify | Use `resolveMapCenter()`, add MyLocationButton |
| `composeApp/.../ui/map/CommunityInfoPanel.kt` | Modify | Remove "North Lawndale" fallback |
| `server/.../services/AuthService.kt` | Modify | Remove Chicago defaults on worker creation |
| `server/.../db/tables/Tables.kt` | Modify | Make address columns nullable, remove defaults |

---

### Task 1: Remove Hardcoded City/State Constants

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/constants/GccConstants.kt:16-17`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/ServiceArea.kt:11-12`

- [ ] **Step 1: Remove DEFAULT_CITY and DEFAULT_STATE from GccConstants**

In `shared/src/commonMain/kotlin/com/gcc/shared/constants/GccConstants.kt`, delete lines 16-17:

```kotlin
// DELETE these two lines:
const val DEFAULT_CITY = "Chicago"
const val DEFAULT_STATE = "IL"
```

The file should now look like:
```kotlin
object GccConstants {
    // Geofence config
    const val CHECKPOINT_RADIUS_METERS = 15.0
    const val GPS_ACCURACY_THRESHOLD_METERS = 20.0
    const val MIN_CHECKPOINT_DWELL_MS = 5000L
    const val PATH_RECORDING_INTERVAL_MS = 5000L

    // North Lawndale
    val NORTH_LAWNDALE_CENTER = Coordinates(lng = -87.7158, lat = 41.8666)

    // API
    const val DEFAULT_API_PORT = 7310
}
```

- [ ] **Step 2: Update ServiceArea model defaults**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/ServiceArea.kt`, replace lines 11-12:

```kotlin
// FROM:
val city: String = GccConstants.DEFAULT_CITY,
val state: String = GccConstants.DEFAULT_STATE,

// TO:
val city: String = "",
val state: String = "",
```

Also remove the `GccConstants` import (line 3) since it's no longer used:
```kotlin
// DELETE:
import com.gcc.shared.constants.GccConstants
```

- [ ] **Step 3: Verify no other files reference DEFAULT_CITY or DEFAULT_STATE**

Run: `grep -r "DEFAULT_CITY\|DEFAULT_STATE" shared/ composeApp/ server/`

Expected: No matches (the only references were in GccConstants.kt and ServiceArea.kt).

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/constants/GccConstants.kt \
       shared/src/commonMain/kotlin/com/gcc/shared/model/ServiceArea.kt
git commit -m "refactor: remove hardcoded DEFAULT_CITY/DEFAULT_STATE constants"
```

---

### Task 2: Rename Map Constants and Add World Zoom

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt:14-16`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt:60-61`

- [ ] **Step 1: Rename Chicago constants in MapStyles.kt**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt`, replace lines 14-16:

```kotlin
// FROM:
// Chicago center coordinates (North Lawndale area)
const val CHICAGO_CENTER_LAT = 41.8603
const val CHICAGO_CENTER_LNG = -87.7228

// TO:
// North Lawndale service area center (used for seed data and community area features)
const val NORTH_LAWNDALE_CENTER_LAT = 41.8603
const val NORTH_LAWNDALE_CENTER_LNG = -87.7228

// Global fallback when no service area or GPS is available
const val WORLD_CENTER_LAT = 0.0
const val WORLD_CENTER_LNG = 0.0
const val ZOOM_WORLD = 2.0
```

- [ ] **Step 2: Update GccMap default parameters**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt`, replace lines 60-62:

```kotlin
// FROM:
centerLat: Double = MapStyles.CHICAGO_CENTER_LAT,
centerLng: Double = MapStyles.CHICAGO_CENTER_LNG,
zoom: Double = MapStyles.ZOOM_NEIGHBORHOOD,

// TO:
centerLat: Double = MapStyles.WORLD_CENTER_LAT,
centerLng: Double = MapStyles.WORLD_CENTER_LNG,
zoom: Double = MapStyles.ZOOM_WORLD,
```

- [ ] **Step 3: Fix all remaining references to CHICAGO_CENTER_LAT/LNG**

Run: `grep -rn "CHICAGO_CENTER" composeApp/ shared/`

For each match, replace with `NORTH_LAWNDALE_CENTER_LAT` / `NORTH_LAWNDALE_CENTER_LNG` if it's North Lawndale-specific code, or remove the reference if the caller should use `resolveMapCenter()` instead (which we create in Task 3).

Known references that need updating:
- `DashboardScreen.kt:109` — will be replaced by `resolveMapCenter()` in Task 5
- `MapFullScreen.kt:122-124,127-128` — will be replaced by `resolveMapCenter()` in Task 6

For now, temporarily update them to compile:
```kotlin
// In DashboardScreen.kt line 109, replace:
) else Pair(MapStyles.CHICAGO_CENTER_LAT, MapStyles.CHICAGO_CENTER_LNG)
// With:
) else Pair(MapStyles.NORTH_LAWNDALE_CENTER_LAT, MapStyles.NORTH_LAWNDALE_CENTER_LNG)

// In MapFullScreen.kt lines 122-124, replace:
val myAreaCenterLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 }
    ?: MapStyles.CHICAGO_CENTER_LAT
val myAreaCenterLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 }
    ?: MapStyles.CHICAGO_CENTER_LNG
// With:
val myAreaCenterLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 }
    ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
val myAreaCenterLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 }
    ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG

// In MapFullScreen.kt lines 127-128, replace both occurrences:
MapStyles.CHICAGO_CENTER_LAT -> MapStyles.NORTH_LAWNDALE_CENTER_LAT
MapStyles.CHICAGO_CENTER_LNG -> MapStyles.NORTH_LAWNDALE_CENTER_LNG
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapStyles.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/GccMap.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "refactor: rename CHICAGO_CENTER to NORTH_LAWNDALE_CENTER, add ZOOM_WORLD"
```

---

### Task 3: Create MapCenterResolver Utility

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapCenterResolver.kt`

- [ ] **Step 1: Create MapCenterResolver.kt**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapCenterResolver.kt`:

```kotlin
package com.gcc.app.ui.map

import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ServiceArea

/**
 * Resolved map center with coordinates and zoom level.
 */
data class MapCenter(
    val lat: Double,
    val lng: Double,
    val zoom: Double,
)

/**
 * Resolves the best map center using a cascading priority:
 *
 * 1. Worker's assigned service area (if it has a mapCenter)
 * 2. User's current GPS location
 * 3. World-level fallback (0, 0 at zoom 2)
 *
 * @param serviceArea   The worker's assigned service area from the API (may be null)
 * @param currentLocation  The user's current GPS coordinates (may be null if permission denied)
 * @param defaultZoom   Zoom level used for GPS-based centering (defaults to neighborhood)
 */
fun resolveMapCenter(
    serviceArea: ServiceArea?,
    currentLocation: Coordinates?,
    defaultZoom: Double = MapStyles.ZOOM_NEIGHBORHOOD,
): MapCenter {
    // Priority 1: Verified service area with a map center
    serviceArea?.mapCenter?.let { center ->
        return MapCenter(
            lat = center.lat,
            lng = center.lng,
            zoom = serviceArea.mapZoom.takeIf { it > 0 } ?: MapStyles.ZOOM_SERVICE_AREA,
        )
    }

    // Priority 2: GPS location
    currentLocation?.let { loc ->
        return MapCenter(
            lat = loc.lat,
            lng = loc.lng,
            zoom = defaultZoom,
        )
    }

    // Priority 3: World fallback
    return MapCenter(
        lat = MapStyles.WORLD_CENTER_LAT,
        lng = MapStyles.WORLD_CENTER_LNG,
        zoom = MapStyles.ZOOM_WORLD,
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapCenterResolver.kt
git commit -m "feat: add resolveMapCenter() cascading location utility"
```

---

### Task 4: Update DashboardViewModel to Store Full ServiceArea

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt:15-24,81-88,112-153`

- [ ] **Step 1: Add serviceArea field to DashboardUiState**

In `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt`, update the data class (lines 15-24):

```kotlin
// FROM:
data class DashboardUiState(
    val routes: List<Route> = emptyList(),
    val worker: Worker? = null,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val spots: List<CommunityReport> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val serviceAreaName: String? = null,
    val error: String? = null,
)

// TO:
data class DashboardUiState(
    val routes: List<Route> = emptyList(),
    val worker: Worker? = null,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val spots: List<CommunityReport> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val serviceArea: ServiceArea? = null,
    val serviceAreaName: String? = null,
    val error: String? = null,
)
```

- [ ] **Step 2: Store full ServiceArea in loadServiceArea()**

Update `loadServiceArea()` (lines 81-88):

```kotlin
// FROM:
fun loadServiceArea() {
    scope.launch {
        try {
            val area = apiClient.getMyServiceArea()
            _uiState.value = _uiState.value.copy(serviceAreaName = area?.name)
        } catch (_: Exception) { }
    }
}

// TO:
fun loadServiceArea() {
    scope.launch {
        try {
            val area = apiClient.getMyServiceArea()
            _uiState.value = _uiState.value.copy(
                serviceArea = area,
                serviceAreaName = area?.name,
            )
        } catch (_: Exception) { }
    }
}
```

- [ ] **Step 3: Update loadAll() to store full ServiceArea**

In `loadAll()`, update the state copy (around line 136-142):

```kotlin
// FROM:
_uiState.value = _uiState.value.copy(
    routes = routes,
    leaderboard = leaderboard,
    worker = worker,
    serviceAreaName = serviceArea?.name,
    spots = spots ?: emptyList(),
    isLoading = false,
)

// TO:
_uiState.value = _uiState.value.copy(
    routes = routes,
    leaderboard = leaderboard,
    worker = worker,
    serviceArea = serviceArea,
    serviceAreaName = serviceArea?.name,
    spots = spots ?: emptyList(),
    isLoading = false,
)
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/DashboardViewModel.kt
git commit -m "feat: store full ServiceArea in DashboardUiState for map center resolution"
```

---

### Task 5: Create MyLocationButton Composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MyLocationButton.kt`

- [ ] **Step 1: Create MyLocationButton.kt**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MyLocationButton.kt`:

```kotlin
package com.gcc.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A circular FAB that zooms the map to the user's current GPS location.
 * Hidden when GPS is unavailable. Renders a crosshair icon matching
 * the iOS system location button style.
 *
 * @param onClick       Callback to trigger camera animation to user location
 * @param modifier      Standard Compose modifier for positioning
 */
@Composable
fun MyLocationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        containerColor = Color.White,
        contentColor = Color(0xFF007AFF), // iOS system blue — matches GPS dot color
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp,
        ),
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width * 0.32f
            val armLength = size.width * 0.18f
            val gap = size.width * 0.06f
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            val color = Color(0xFF007AFF)

            // Outer circle (location ring)
            drawCircle(
                color = color,
                radius = radius,
                center = center,
                style = stroke,
            )

            // Center dot
            drawCircle(
                color = color,
                radius = 2.5.dp.toPx(),
                center = center,
            )

            // Four crosshair arms extending outward from the ring
            // Top arm
            drawLine(
                color = color,
                start = Offset(center.x, center.y - radius - gap),
                end = Offset(center.x, center.y - radius - gap - armLength),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Bottom arm
            drawLine(
                color = color,
                start = Offset(center.x, center.y + radius + gap),
                end = Offset(center.x, center.y + radius + gap + armLength),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Left arm
            drawLine(
                color = color,
                start = Offset(center.x - radius - gap, center.y),
                end = Offset(center.x - radius - gap - armLength, center.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Right arm
            drawLine(
                color = color,
                start = Offset(center.x + radius + gap, center.y),
                end = Offset(center.x + radius + gap + armLength, center.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MyLocationButton.kt
git commit -m "feat: add MyLocationButton composable with crosshair icon"
```

---

### Task 6: Update DashboardScreen to Use resolveMapCenter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt:92-111,203-248`

- [ ] **Step 1: Replace hardcoded map center with resolveMapCenter()**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt`, replace the map centering logic (lines 102-111):

```kotlin
// DELETE lines 102-111 (the serviceArea/mapCenter/mapZoom block):
// Always center on the user's service area (community area) for a consistent view
val serviceArea = remember { chicagoCommunityAreas.find { it.isServiceArea } }
val mapCenter = remember(serviceArea) {
    val sa = serviceArea
    if (sa != null) Pair(
        (sa.bounds.north + sa.bounds.south) / 2.0,
        (sa.bounds.east + sa.bounds.west) / 2.0,
    ) else Pair(MapStyles.CHICAGO_CENTER_LAT, MapStyles.CHICAGO_CENTER_LNG)
}
val mapZoom = MapStyles.ZOOM_SERVICE_AREA

// REPLACE WITH:
val mapCenter = remember(uiState.serviceArea, currentLocation) {
    resolveMapCenter(
        serviceArea = uiState.serviceArea,
        currentLocation = currentLocation,
        defaultZoom = MapStyles.ZOOM_NEIGHBORHOOD,
    )
}
```

Add the import at the top of the file:
```kotlin
import com.gcc.app.ui.map.resolveMapCenter
import com.gcc.app.ui.map.MyLocationButton
```

Remove the now-unused import:
```kotlin
// DELETE:
import com.gcc.app.ui.map.chicagoCommunityAreas
```

- [ ] **Step 2: Update GccMap call to use resolved center**

Update the `GccMap` call (around line 210-219):

```kotlin
// FROM:
GccMap(
    modifier = Modifier.fillMaxSize(),
    centerLat = mapCenter.first,
    centerLng = mapCenter.second,
    zoom = mapZoom,
    ...

// TO:
GccMap(
    modifier = Modifier.fillMaxSize(),
    centerLat = mapCenter.lat,
    centerLng = mapCenter.lng,
    zoom = mapCenter.zoom,
    ...
```

- [ ] **Step 3: Add MyLocationButton to the dashboard map**

Inside the `Box` that wraps the dashboard map (the one starting around line 204), add the MyLocationButton after the expand affordance Surface, but only if `currentLocation` is non-null. Replace the existing expand affordance with a Row containing both buttons:

```kotlin
// REPLACE the expand affordance Surface block (lines 230-247) with:
Row(
    modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(8.dp),
    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
) {
    // My location button — only shown when GPS is available
    if (currentLocation != null) {
        MyLocationButton(
            onClick = { /* Dashboard map is non-interactive; tapping opens full map */ onMapClick() },
            modifier = Modifier.size(28.dp),
        )
    }
    // Expand affordance
    Surface(
        modifier = Modifier.size(28.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.85f),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "\u2922",
                fontSize = 14.sp,
                color = GccGreenDark,
            )
        }
    }
}
```

Note: On the dashboard, the mini-map is non-interactive (there's a tap overlay that opens MapFullScreen). So the MyLocationButton on dashboard just opens the full map — the user can zoom to their location there. Alternatively, if you want the button to actually re-center the dashboard mini-map, add a mutable state variable for map center override, but given the map is small and taps already open the full map, navigating to the full map is the simpler approach.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/DashboardScreen.kt
git commit -m "feat: dashboard map uses resolveMapCenter() instead of hardcoded Chicago"
```

---

### Task 7: Update MapFullScreen to Use resolveMapCenter

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt:117-129,182-184,253-293`

- [ ] **Step 1: Replace hardcoded map center logic with resolveMapCenter()**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`, replace the camera center computation (lines 117-129):

```kotlin
// DELETE lines 117-129:
// Compute camera center from the service area bounds (North Lawndale)
val serviceArea = remember {
    chicagoCommunityAreas.find { it.isServiceArea }
}
val myAreaCenterLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 }
    ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
val myAreaCenterLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 }
    ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG

// Camera center and zoom depend on mode (overridden when tapping a spot/bounty)
val centerLat = overrideCenterLat ?: if (mapMode == MapMode.MY_AREA) myAreaCenterLat else MapStyles.NORTH_LAWNDALE_CENTER_LAT
val centerLng = overrideCenterLng ?: if (mapMode == MapMode.MY_AREA) myAreaCenterLng else MapStyles.NORTH_LAWNDALE_CENTER_LNG
val mapZoom = overrideZoom ?: if (mapMode == MapMode.MY_AREA) MapStyles.ZOOM_SERVICE_AREA else MapStyles.ZOOM_CITY

// REPLACE WITH:
val resolved = remember(uiState.serviceArea, currentLocation) {
    resolveMapCenter(
        serviceArea = uiState.serviceArea,
        currentLocation = currentLocation,
    )
}
val centerLat = overrideCenterLat ?: resolved.lat
val centerLng = overrideCenterLng ?: resolved.lng
val mapZoom = overrideZoom ?: resolved.zoom
```

Add imports at the top:
```kotlin
import com.gcc.app.ui.map.resolveMapCenter
import com.gcc.app.ui.map.MyLocationButton
```

Remove the now-unused import:
```kotlin
// DELETE (if no longer referenced elsewhere in the file):
import com.gcc.app.ui.map.chicagoCommunityAreas
```

Note: `chicagoCommunityAreas` is still used in `currentGpsArea` on line 134. Keep that import. Only delete it if the grep confirms it's unused.

- [ ] **Step 2: Update areaName fallback**

Replace the area name fallback (lines 182-184):

```kotlin
// FROM:
val areaName = uiState.serviceAreaName
    ?: serviceArea?.displayName?.replace("\n", " ")
    ?: "North Lawndale"

// TO:
val areaName = uiState.serviceAreaName ?: "Your Area"
```

- [ ] **Step 3: Update cameraBounds to use API service area**

The `cameraBounds` parameter on line 253 currently uses the local `serviceArea` variable (from `chicagoCommunityAreas`). Since we removed that variable, update it:

```kotlin
// FROM:
cameraBounds = if (mapMode == MapMode.MY_AREA && overrideCenterLat == null) serviceArea?.bounds else null,

// TO:
cameraBounds = null, // Camera centering now handled by resolveMapCenter()
```

- [ ] **Step 4: Add MyLocationButton to the map**

Inside the `GccMap`'s `content` lambda (the `{ ... }` block starting at line 257), add the MyLocationButton. Place it after the route overlay section (around line 324), before the spot info card:

```kotlin
// Add after the RouteOverlay block and before the selectedSpot block:

// My Location button — bottom-right, above route chips
if (currentLocation != null && selectedSpot == null) {
    MyLocationButton(
        onClick = {
            overrideCenterLat = currentLocation?.lat
            overrideCenterLng = currentLocation?.lng
            overrideZoom = MapStyles.ZOOM_NEIGHBORHOOD
        },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = if (mapMode == MapMode.MY_AREA && uiState.routes.isNotEmpty()) 80.dp else 16.dp),
    )
}
```

The bottom padding is conditional: when route chips are showing (MY_AREA mode with routes), the button sits above them (80dp). Otherwise it sits at the standard 16dp from the bottom.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt
git commit -m "feat: MapFullScreen uses resolveMapCenter() + adds MyLocationButton"
```

---

### Task 8: Update CommunityInfoPanel Default Text

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CommunityInfoPanel.kt:45`

- [ ] **Step 1: Replace "North Lawndale" fallback**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CommunityInfoPanel.kt`, replace line 45:

```kotlin
// FROM:
val areaDisplay = communityAreaName ?: "North Lawndale"

// TO:
val areaDisplay = communityAreaName ?: "Your Area"
```

- [ ] **Step 2: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CommunityInfoPanel.kt
git commit -m "fix: CommunityInfoPanel no longer defaults to 'North Lawndale'"
```

---

### Task 9: Update Registration Flow

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RegisterViewModel.kt:35-36`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RegisterScreen.kt:37-38,131-140,160-166`

- [ ] **Step 1: Remove defaults from RegisterViewModel**

In `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RegisterViewModel.kt`, replace lines 35-36:

```kotlin
// FROM:
addressCity: String = "Chicago",
addressState: String = "IL",

// TO:
addressCity: String = "",
addressState: String = "",
```

- [ ] **Step 2: Remove defaults from RegisterScreen**

In `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RegisterScreen.kt`, replace lines 37-38:

```kotlin
// FROM:
var addressCity by remember { mutableStateOf("Chicago") }
var addressState by remember { mutableStateOf("IL") }

// TO:
var addressCity by remember { mutableStateOf("") }
var addressState by remember { mutableStateOf("") }
```

- [ ] **Step 3: Update address section label for global context**

Replace lines 131-140 (the address section header):

```kotlin
// FROM:
Text(
    "Your Address",
    style = MaterialTheme.typography.titleSmall,
    color = TextMuted,
)
Text(
    "Required for neighborhood verification",
    style = MaterialTheme.typography.bodySmall,
    color = TextMuted.copy(alpha = 0.7f),
)

// TO:
Text(
    "Your Address (Optional)",
    style = MaterialTheme.typography.titleSmall,
    color = TextMuted,
)
Text(
    "Can be added later during verification",
    style = MaterialTheme.typography.bodySmall,
    color = TextMuted.copy(alpha = 0.7f),
)
```

- [ ] **Step 4: Update State field label for global users**

In the address fields Row (around line 160-166), update the State label:

```kotlin
// FROM:
label = "State",

// TO:
label = "State / Province",
```

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RegisterViewModel.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/RegisterScreen.kt
git commit -m "fix: remove Chicago/IL prefill from signup, make address optional"
```

---

### Task 10: Update Server Defaults

**Files:**
- Modify: `server/src/main/kotlin/com/gcc/server/db/tables/Tables.kt:45-46`
- Modify: `server/src/main/kotlin/com/gcc/server/services/AuthService.kt:56-57`

- [ ] **Step 1: Update Tables.kt column definitions**

In `server/src/main/kotlin/com/gcc/server/db/tables/Tables.kt`, replace lines 45-46:

```kotlin
// FROM:
val addressCity = varchar("address_city", 255).default("Chicago")
val addressState = varchar("address_state", 50).default("IL")

// TO:
val addressCity = varchar("address_city", 255).default("")
val addressState = varchar("address_state", 50).default("")
```

Note: We keep `.default("")` instead of `.nullable()` to avoid breaking existing queries that don't expect null. Empty string is a safe default that doesn't imply any location.

- [ ] **Step 2: Update AuthService worker creation**

In `server/src/main/kotlin/com/gcc/server/services/AuthService.kt`, replace lines 56-57:

```kotlin
// FROM:
it[addressCity] = "Chicago"
it[addressState] = "IL"

// TO:
it[addressCity] = request.addressCity ?: ""
it[addressState] = request.addressState ?: ""
```

This passes through whatever the client sent (which may be empty if the user didn't fill in the address fields), rather than hardcoding Chicago.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/gcc/server/db/tables/Tables.kt \
       server/src/main/kotlin/com/gcc/server/services/AuthService.kt
git commit -m "fix: server no longer defaults new workers to Chicago/IL"
```

---

### Task 11: Verify and Clean Up

- [ ] **Step 1: Search for any remaining Chicago/North Lawndale hardcoding in map logic**

Run these searches and verify no location-biased defaults remain outside of legitimate data files:

```bash
grep -rn "Chicago" composeApp/src/commonMain/ --include="*.kt" | grep -v "test" | grep -v "SeedData" | grep -v "CommunityAreaData"
grep -rn '"North Lawndale"' composeApp/src/commonMain/ --include="*.kt" | grep -v "CommunityAreaData"
grep -rn "CHICAGO_CENTER" composeApp/ shared/
```

Expected: No matches for `CHICAGO_CENTER`. No matches for `"Chicago"` in the compose app main source (outside test/seed data). The only `"North Lawndale"` match should be in `CommunityAreaData.kt`.

- [ ] **Step 2: Verify the desktop LocationProvider still works**

The desktop `LocationProvider` has hardcoded North Lawndale coordinates for development preview. This is fine — it's a dev-only fallback. Verify it's not affected:

```bash
grep -n "chicagoCoords" composeApp/src/desktopMain/
```

Expected: Still shows the dev coordinates. No changes needed.

- [ ] **Step 3: Build check**

Run the shared module and compose app compilation to ensure no broken references:

```bash
cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc
./gradlew :shared:compileKotlinIosArm64 :composeApp:compileKotlinIosArm64 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. If there are compilation errors from missed `CHICAGO_CENTER` references, fix them.

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git add -A && git commit -m "chore: clean up remaining Chicago/North Lawndale references"
```

Only run this if Step 3 required fixes. Skip if the build was clean.
