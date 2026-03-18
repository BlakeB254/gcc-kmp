# Scout & Clean: AR Route Scanning Feature

**Date:** 2026-03-18
**Status:** Approved
**Target:** Build 45+

## Summary

A two-pass route cleaning system inspired by Pokemon Go's scanning mechanics. Users walk a route twice: first a **Scout Lap** to document trash conditions with AI-powered detection, then a **Clean Lap** guided by a minimap of identified hotspots with camera-based confirmation.

AR-capable devices (iPhone Pro with LiDAR, Android ARCore devices) get the full spatial scanning experience. Non-AR devices get a degraded GPS + manual photo fallback.

## Core Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| AR vs GPS | Degraded GPS fallback for non-AR | Inclusive — every worker can participate |
| Scout coverage model | Paint-the-road fog-of-war | Most immersive, ensures comprehensive scanning |
| Scout primary view | Full AR camera + minimap overlay | AR camera primary with top-right minimap showing scanned/unscanned |
| Starting point | No fixed start — user begins anywhere on route | Real-world flexibility |
| Coverage completion | 85%+ coverage AND loop closure | Ensures thoroughness and clean handoff |
| Clean lap navigation | Free roam + proximity alerts | No forced order — phone buzzes near hotspots |
| Trash detection depth | Full: type + severity + volume | Leverages existing LiDAR/depth infrastructure |
| Cleanup confirmation | Tiered by device | AR: spatial re-scan. GPS: photo + geofence |
| Data model | New RouteScan entity (separate from spots) | Clean separation, purpose-built for scan sessions |

## Session State Machine

```
IDLE → SCOUTING → SCOUT_REVIEW → CLEANING → SUBMITTING → COMPLETE
```

### IDLE
User selects a route and taps "Start Scout & Clean". Device capability check determines AR or GPS tier.

### SCOUTING
- AR camera active as primary view with minimap overlay (top-right corner)
- Fog-of-war overlay: dark areas = unscanned, clear areas = scanned
- Fog clears based on camera frustum intersection with route corridor (AR) or GPS path radius (GPS)
- AI detects trash in real-time, places spatial anchors (AR) or tags manual photos (GPS)
- AI direction banner: "Pan left — alley not scanned", "Look behind you", etc.
- Coverage percentage bar at bottom
- Hotspot counter badge
- User walks route freely from any starting position

**Scout → Scout Review transition:** Coverage ≥ 85% AND GPS detects user returned within 20m of their starting position (loop closure).

### SCOUT_REVIEW
- Summary screen: X hotspots found, estimated total volume, estimated cleanup time
- Map showing all detected hotspots with before-photos
- User reviews and confirms to begin cleaning
- Option to re-scout missed areas if coverage is borderline

### CLEANING
- Full map view with hotspot pins:
  - Green checkmark = cleaned and confirmed
  - Red pulsing = nearby (within ~20m), proximity alert active
  - Yellow = pending, not yet approached
- Proximity alert card appears when near a hotspot:
  - Scout photo thumbnail
  - Trash type, severity, estimated volume
  - Distance remaining
  - "CLEAN" button opens camera confirmation
- Progress segments at top: X/Y hotspots cleaned
- Timer running for total cleanup duration
- No forced order — user approaches hotspots freely

**Confirmation flow (per hotspot):**
- AR tier: Re-scan the area. AI compares 3D spatial data before/after. Auto pass/fail with confidence score.
- GPS tier: Take after photo near hotspot GPS location. AI compares before/after images. Geofence verification (within 15m).

### SUBMITTING
API call bundles all scan data: scout path, clean path, hotspot before/after pairs, timing, device info.

### COMPLETE
Points awarded. Session summary with stats. Evidence submitted for admin review.

## Data Model

### RouteScan (new table)

```kotlin
data class RouteScan(
    val id: Int,
    val routeId: Int,
    val workerId: Int,
    val deviceType: DeviceType,           // AR or GPS
    val phase: ScanPhase,                 // SCOUTING, SCOUT_REVIEW, CLEANING, SUBMITTING, COMPLETE
    val coveragePercent: Double,          // 0.0 to 100.0
    val scoutStartedAt: Instant,
    val scoutCompletedAt: Instant?,
    val cleanStartedAt: Instant?,
    val cleanCompletedAt: Instant?,
    val startLat: Double,                 // Where user began (for loop detection)
    val startLng: Double,
    val scoutPathGeoJson: String,         // LineString of GPS breadcrumbs during scout
    val cleanPathGeoJson: String?,        // LineString of GPS breadcrumbs during clean
    val totalTrashVolumeLiters: Double?,  // Sum of all hotspot volumes
    val totalTrashWeightLbs: Double?,     // Sum of all hotspot weights
    val pointsAwarded: Int,
    val status: ScanStatus                // IN_PROGRESS, COMPLETED, ABANDONED
)
```

### ScanHotspot (new table)

```kotlin
data class ScanHotspot(
    val id: Int,
    val scanId: Int,                      // FK → RouteScan
    val lat: Double,
    val lng: Double,
    val trashTypes: List<SpotTag>,        // Reuses existing SpotTag enum
    val severity: Int,                    // 1-5 scale
    val estimatedVolumeLiters: Double?,   // From LiDAR depth or AI estimate
    val estimatedWeightLbs: Double?,
    val confidence: Float,                // 0.0-1.0 detection confidence
    val beforePhotoUrl: String,
    val afterPhotoUrl: String?,
    val arAnchorData: String?,            // JSON blob of AR anchor for spatial re-scan
    val depthData: String?,               // JSON blob of LiDAR depth analysis
    val detectedAt: Instant,
    val cleanedAt: Instant?,
    val verificationScore: Float?,        // 0.0-1.0 before/after similarity delta
    val status: HotspotStatus             // DETECTED, CLEANED, SKIPPED
)
```

### Enums

```kotlin
enum class DeviceType { AR, GPS }
enum class ScanPhase { SCOUTING, SCOUT_REVIEW, CLEANING, SUBMITTING, COMPLETE }
enum class ScanStatus { IN_PROGRESS, COMPLETED, ABANDONED }
enum class HotspotStatus { DETECTED, CLEANED, SKIPPED }
```

## Device Capability Tiers

| Feature | AR Tier (LiDAR) | GPS Tier |
|---------|-----------------|----------|
| Scout: Coverage tracking | Camera frustum fog-of-war | GPS path coverage radius (~5m) |
| Scout: Trash detection | Real-time AR object detection + depth | Manual photo capture + on-device ML |
| Scout: Volume estimation | LiDAR depth → volume calculation | AI estimate from photo dimensions |
| Scout: AI guidance | Directional: "Pan left", "Look behind you" | Positional: "Walk to unscanned section ahead" |
| Scout: Spatial anchors | ARKit/ARCore world anchors per hotspot | GPS coordinates only |
| Clean: Hotspot navigation | AR markers visible in camera view + map | Map-only with proximity alerts |
| Clean: Confirmation | 3D spatial re-scan, AI scene comparison | Photo comparison + geofence check |
| Clean: Verification score | High confidence (spatial + visual) | Moderate confidence (visual only) |

### AR Device Detection

Existing infrastructure: `CameraManager.hasLiDAR()` returns true for iPhone Pro/iPad Pro models. Extend with ARCore availability check on Android.

```kotlin
// In commonMain
enum class DeviceType { AR, GPS }

expect fun detectDeviceType(): DeviceType

// iOS: hasLiDAR() → AR, else GPS
// Android: ARCore availability check → AR, else GPS
// Desktop: always GPS
```

## Scout Lap: Fog-of-War System

### AR Tier

The route corridor is modeled as a polygon (route LineString buffered by ~15m on each side). This polygon is divided into a grid of cells (~3m x 3m).

Each frame, the AR camera's frustum is projected onto the ground plane. Grid cells intersecting the frustum are marked as "scanned". The fog-of-war overlay renders unscanned cells as a dark semi-transparent layer.

Coverage % = (scanned cells / total cells) * 100.

### GPS Tier

Each GPS update marks cells within a 5m radius of the user's position as "scanned". No camera frustum — coverage is purely positional. User manually captures photos when they see trash, triggering on-device ML analysis.

### AI Direction System

Every 3-5 seconds, the system evaluates the nearest cluster of unscanned cells relative to the user's position and heading:

- **AR tier:** Compute bearing from user to unscanned cluster center. Compare with device heading from ARKit/ARCore. Generate directional prompt: "Pan left", "Turn around", "Look up at the overhang".
- **GPS tier:** Compute bearing and distance to nearest unscanned route segment. Generate navigation prompt: "Walk 30m ahead to unscanned section", "Cross the street to scan the other side".

Prompts are displayed as a banner at the top of the screen. They should feel helpful, not nagging — throttled to one prompt per 5 seconds with a cooldown after the user acts on one.

## Clean Lap: Proximity Alert System

During the clean lap, the system continuously monitors the user's GPS distance to each uncleaned hotspot.

**Alert thresholds:**
- 20m: Hotspot pin begins pulsing red on minimap
- 15m: Proximity alert card slides up from bottom with scout photo + details
- Phone vibrates (haptic feedback) on first card appearance
- Card persists until user moves away (>25m) or taps "CLEAN"

**"CLEAN" button flow:**
1. Opens camera (AR or standard depending on tier)
2. AR tier: Initiates spatial re-scan. User points camera at the area. AI compares current 3D scene against stored AR anchor data.
3. GPS tier: User captures after photo. AI runs `compareImages(beforeBytes, afterBytes)` for similarity delta.
4. Verification score computed. Hotspot marked as CLEANED if score > threshold (0.3 delta = significant visual change).
5. Card dismisses, hotspot turns green on map.

**Skip flow:** User can long-press a hotspot on the map and choose "Skip" with a reason (inaccessible, hazardous, already clean). Hotspot marked as SKIPPED.

## API Endpoints (new)

### Worker endpoints (authenticated)

```
POST   /api/scans                    → Create new RouteScan (returns scan ID)
PATCH  /api/scans/{id}/phase         → Advance phase (SCOUTING → SCOUT_REVIEW → CLEANING → etc.)
POST   /api/scans/{id}/hotspots      → Add detected hotspot with before photo
PATCH  /api/scans/{id}/hotspots/{hid} → Update hotspot (after photo, cleaned status)
PATCH  /api/scans/{id}/coverage      → Update coverage percentage + scout path
POST   /api/scans/{id}/complete      → Submit completed scan session
GET    /api/scans/{id}               → Get scan with all hotspots
```

### Admin endpoints

```
GET    /api/admin/scans              → List all scans (filterable by route, worker, status)
GET    /api/admin/scans/{id}         → Full scan detail with before/after comparisons
PATCH  /api/admin/scans/{id}/review  → Admin review/approve completed scan
```

## New Files (estimated)

### Shared models
- `shared/.../model/RouteScan.kt` — RouteScan, ScanHotspot, enums
- `shared/.../model/ScanCoverage.kt` — Coverage grid cell model

### Mobile app
- `composeApp/.../viewmodel/RouteScanViewModel.kt` — Main session state machine
- `composeApp/.../viewmodel/ScoutViewModel.kt` — Scout lap logic (fog-of-war, AI detection, coverage)
- `composeApp/.../viewmodel/CleanViewModel.kt` — Clean lap logic (proximity, confirmation)
- `composeApp/.../ui/screens/RouteScanScreen.kt` — Orchestrator screen (delegates to phase screens)
- `composeApp/.../ui/screens/ScoutScreen.kt` — AR camera + fog overlay + minimap
- `composeApp/.../ui/screens/ScoutReviewScreen.kt` — Summary before cleaning
- `composeApp/.../ui/screens/CleanScreen.kt` — Map + proximity cards + confirmation
- `composeApp/.../ui/screens/ScanCompleteScreen.kt` — Results + points
- `composeApp/.../ui/map/FogOfWarOverlay.kt` — Coverage grid rendering
- `composeApp/.../ui/map/HotspotMarker.kt` — Animated hotspot pins
- `composeApp/.../ui/components/ProximityAlertCard.kt` — Bottom sheet alert
- `composeApp/.../ui/components/CoverageBar.kt` — Progress bar component
- `composeApp/.../ui/components/AiDirectionBanner.kt` — Top instruction banner
- `composeApp/.../platform/ArScanner.kt` — expect/actual AR scanning (ARKit/ARCore/stub)
- `composeApp/.../platform/CoverageTracker.kt` — expect/actual fog-of-war tracking

### Server
- `server/.../routes/ScanRoutes.kt` — API endpoint handlers
- `server/.../services/ScanService.kt` — Business logic
- `server/.../db/tables/RouteScans.kt` — Exposed table definition
- `server/.../db/tables/ScanHotspots.kt` — Exposed table definition

### Database migration
- New tables: `route_scans`, `scan_hotspots`
- New enums: `device_type`, `scan_phase`, `scan_status`, `hotspot_status`

## Integration Points

- **Existing `OnDeviceAnalyzer`**: Used for trash detection during scout and before/after comparison during clean. Already has `analyzePhoto()`, `compareImages()`, and `generateEmbedding()`.
- **Existing `LocationProvider`**: GPS tracking for both tiers. Background tracking during active scan sessions.
- **Existing `CameraManager`**: Photo capture, GPS EXIF embedding, LiDAR depth analysis. `hasLiDAR()` for device tier detection.
- **Existing `GeofenceVerification`**: Distance calculations for proximity alerts and loop closure detection.
- **Existing `GccApiClient`**: HTTP client for all new API calls. Multipart upload for photos.
- **Existing `PointsService`**: Award points on scan completion. Scoring formula TBD (factors: hotspot count, total volume, coverage %, speed).

## Out of Scope (v1)

- Multiplayer scanning (multiple workers on same route simultaneously)
- Historical scan comparison (comparing scans of same route over time)
- Real-time sync of scan data to admin dashboard during session
- Video recording during scout (photos only for v1)
- Custom route drawing (uses existing published routes only)
- Offline mode (requires network for photo upload and API calls)
