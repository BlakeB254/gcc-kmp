# Global Location Awareness

> Remove Chicago/North Lawndale hardcoding. Make the app location-aware globally — centering maps on the user's verified service area or GPS position, showing a user location dot on all maps, and adding a "zoom to my location" button.

## Context

The app was built for North Lawndale, Chicago. Hardcoded coordinates and city defaults appear in 10+ files across client and server. As the app distributes globally via the App Store, every user sees Chicago regardless of where they are. This spec globalizes the location experience while preserving North Lawndale as the first (and currently only) active service area.

### Business Rules

- **Routes** ($100 payouts): restricted to the user's verified service area (e.g., North Lawndale). Verified via residency forms, not signup.
- **Reports & cleanups**: available anywhere globally.
- **Bounties**: available anywhere but require KYC identity verification.
- **Signup location**: not collected at registration. Residency verification is the authoritative source. If a user states a location and later verifies a different one, the verified location overwrites.

## Design

### 1. Remove Signup Location Prefill

**Files:**
- `shared/.../GccConstants.kt` — delete `DEFAULT_CITY` and `DEFAULT_STATE` constants
- `shared/.../model/ServiceArea.kt` — remove `GccConstants.DEFAULT_CITY` and `GccConstants.DEFAULT_STATE` defaults from `city`/`state` fields; change to `city: String = ""`, `state: String = ""`
- `composeApp/.../RegisterViewModel.kt` — change `addressCity` and `addressState` defaults from `"Chicago"`/`"IL"` to `""`
- `composeApp/.../RegisterScreen.kt` — change `mutableStateOf("Chicago")` and `mutableStateOf("IL")` to `mutableStateOf("")`
- `server/.../Tables.kt` — remove `.default("Chicago")` and `.default("IL")` from workers table columns; make them `.nullable()`
- `server/.../AuthService.kt` — remove `addressCity = "Chicago"` default when creating new worker records

The signup form retains the address fields (users can optionally fill them) but nothing is prefilled. Placeholder text in the fields should read "City" and "State/Province" respectively.

### 2. Map Center Resolution

New shared utility: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MapCenterResolver.kt`

```kotlin
data class MapCenter(val lat: Double, val lng: Double, val zoom: Double)

fun resolveMapCenter(
    serviceArea: ServiceArea?,          // worker's assigned service area (has mapCenter)
    currentLocation: Coordinates?,      // from GPS
    defaultZoom: Double = MapStyles.ZOOM_NEIGHBORHOOD
): MapCenter
```

**Priority cascade:**

1. **Verified service area** — if the worker has an assigned `ServiceArea` with a non-null `mapCenter`, center on those coordinates at the area's `mapZoom` (or `ZOOM_SERVICE_AREA` 13.2 as fallback)
2. **GPS location** — if `currentLocation` is non-null, center on the user's position at `defaultZoom`
3. **World fallback** — center at `(0.0, 0.0)` at `ZOOM_WORLD` (2.0) — a neutral global view

**Applied to:**

| Screen | Current behavior | New behavior |
|--------|-----------------|-------------|
| `DashboardScreen` | Centers on `CHICAGO_CENTER_LAT/LNG` | Uses `resolveMapCenter()` |
| `MapFullScreen` (MY_AREA) | Centers on North Lawndale service area bounds | Uses `resolveMapCenter()` |
| `MapFullScreen` (EXPLORER) | Centers on "Chicago downtown" | Uses `resolveMapCenter()` (user's position or verified area) |

**MapStyles.kt changes:**
- Rename `CHICAGO_CENTER_LAT` → `NORTH_LAWNDALE_CENTER_LAT`
- Rename `CHICAGO_CENTER_LNG` → `NORTH_LAWNDALE_CENTER_LNG`
- Add `ZOOM_WORLD = 2.0`
- These constants remain available for North Lawndale-specific features (e.g., seed data, community area definitions) but are no longer used as app-wide defaults.

**CommunityInfoPanel.kt:**
- Replace `communityAreaName ?: "North Lawndale"` with `communityAreaName ?: "Your Area"`
- If no community is resolved and no GPS available, hide the community panel entirely.

### 3. User Location Dot

The iOS implementation in `GccMap.ios.kt` already renders a 3-layer blue dot (halo + border + center) when `currentLocation != null`. No rendering changes needed.

**What needs to change:** ensure every call site that uses `GccMap` passes `currentLocation` from `LocationProvider`. Audit:
- `DashboardScreen` — already passes it ✓
- `MapFullScreen` — already passes it ✓
- Any other `GccMap` call sites — verify and fix

The dot is conditionally rendered, so when location permission is denied (`currentLocation == null`), no dot appears. This is correct behavior — no broken state.

### 4. "My Location" Button

New composable: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/MyLocationButton.kt`

**Visual design:**
- Circular white FAB, 48dp diameter
- Location crosshair icon (standard mobile map pattern)
- Subtle elevation shadow
- Positioned bottom-right of the map area, 16dp margin from edges
- On `MapFullScreen`: above the bottom navigation bar
- On `DashboardScreen`: bottom-right corner of the mini-map

**Behavior:**
- Tap: animate camera to `currentLocation` at `ZOOM_NEIGHBORHOOD` (14.0)
- Uses existing `overrideCenterLat/Lng` and `overrideZoom` state in `MapFullScreen`
- For `DashboardScreen`: similarly override the map center state
- Hidden when `currentLocation == null` (location permission denied or not yet available)

**Icon:** Use a simple Canvas-drawn crosshair/location arrow, or use an existing icon resource if available. Keep it consistent with iOS system location button styling.

### 5. Unchanged Files

These files contain North Lawndale-specific data that is correct and should not change:

- `SeedData.kt` — seed routes are for North Lawndale, that's the real data
- `CommunityAreaData.kt` — defines community areas including North Lawndale; `isServiceArea = true` is correct
- `GeoUtils.kt` — point-in-polygon detection works for any community area
- `LocationProvider.kt` (iOS/Android) — real GPS implementations, no hardcoded coords
- `LocationProvider.kt` (desktop) — dev-only hardcoded coords for preview, fine to keep

### 6. Server Schema Migration

The `addressCity` and `addressState` column changes need a migration:

```sql
ALTER TABLE workers ALTER COLUMN address_city DROP DEFAULT;
ALTER TABLE workers ALTER COLUMN address_city DROP NOT NULL;
ALTER TABLE workers ALTER COLUMN address_state DROP DEFAULT;
ALTER TABLE workers ALTER COLUMN address_state DROP NOT NULL;
```

Existing rows with "Chicago"/"IL" remain as-is — they represent real data for existing North Lawndale users.

## iOS-Specific Considerations

- Location permission flow unchanged — iOS prompts via `CLLocationManager.requestWhenInUseAuthorization()` on first map interaction
- The blue dot rendering uses MapLibre circle layers which are performant on iOS
- Background location tracking (for active jobs) is unaffected
- Info.plist location usage descriptions remain appropriate
- No new iOS-specific code needed — all changes are in shared `commonMain` or existing iOS map layer

## What Success Looks Like

1. New user in any city signs up with no prefilled location
2. On dashboard, they see their GPS position on the map (or world view if location denied)
3. On map tab, same — their area, not Chicago
4. Blue dot shows their position on all maps
5. "My Location" button zooms to their position
6. A user verified in North Lawndale sees North Lawndale on dashboard/map by default
7. North Lawndale routes, community data, and seed data all still work correctly
