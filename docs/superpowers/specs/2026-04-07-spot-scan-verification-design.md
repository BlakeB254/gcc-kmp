# Spot Scan Verification — Design Spec

**Date:** 2026-04-07
**Status:** Approved
**Approach:** Lightweight scan — shared component upgrades existing flows

## Problem

Spot reports, spot verifications, and volunteer cleanups currently use basic camera capture without structured verification. Workers can submit photos from anywhere, there's no depth analysis on verification photos, and no consistent anti-fraud attestation. Routes already have LiDAR depth capture, AI classification, and geofence enforcement — spots should get the same scan quality.

## Solution

Create a shared `SpotScanCapture` composable that wraps the camera + geofence + LiDAR + AI pipeline. All three spot flows embed this component at their photo capture steps instead of using raw `CameraCapture`.

No new screens, no new phases, no new navigation. Same user flows, upgraded capture quality.

## Architecture

### SpotScanCapture Component

A drop-in replacement for `CameraCapture` in spot flows.

**Parameters:**

```kotlin
@Composable
fun SpotScanCapture(
    spotLocation: Coordinates,
    geofenceRadius: Double = 30.0,
    maxPhotos: Int = 3,
    requireGeofence: Boolean = true,
    onPhotosReady: (List<ScanCapturedPhoto>) -> Unit,
)
```

**Internal pipeline:**

1. Calls `detectDeviceType()` to determine LiDAR (AR) vs standard (GPS) mode
2. Shows live camera preview via existing `CameraManager`
3. Displays geofence proximity indicator using `GeofenceVerification.distanceMeters()`
4. On capture:
   - Extracts depth data via `CameraManager.processDepthData()` (Pro devices only)
   - Runs `OnDeviceAnalyzer.analyzePhoto()` for AI classification
   - Records GPS coordinates at capture moment
   - Builds `CaptureAttestation` (device model, OS, timestamp, session ID)
5. Returns `List<ScanCapturedPhoto>` with all enriched data

**Existing infrastructure reused (no new implementations):**

| Component | Already exists | Used for |
|-----------|---------------|----------|
| `CameraManager` | Yes (iosMain) | Photo capture, depth extraction |
| `OnDeviceAnalyzer` | Yes (iosMain) | AI classification, severity scoring |
| `GeofenceVerification` | Yes (shared/geo) | Distance calculation |
| `detectDeviceType()` | Yes (iosMain) | LiDAR vs GPS detection |
| `LocationProvider` | Yes (iosMain) | GPS position |
| `CaptureAttestation` | Yes (shared/model) | Anti-fraud metadata |

**File location:** `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt`

### ScanCapturedPhoto Data Class

```kotlin
data class ScanCapturedPhoto(
    val photo: CapturedPhoto,
    val aiAnalysis: OnDeviceAnalysisResult?,
    val captureLocation: Coordinates,
    val attestation: CaptureAttestation,
    val distanceFromSpot: Double,
)
```

**File location:** `shared/src/commonMain/kotlin/com/gcc/shared/model/ScanCapturedPhoto.kt`

## Integration Points

### Spot Report (SpotScreen)

- **Phase affected:** CAMERA
- **Change:** Replace `CameraCapture` with `SpotScanCapture`
- **Config:** `geofenceRadius = 50.0`, `maxPhotos = 3`
- **API impact:** None — `CreateSpotRequest` already has `onDeviceAnalysis` and `depthAnalysis` fields

### Spot Verification (SpotVerificationScreen)

- **Phase affected:** CAMERA
- **Change:** Replace photo capture with `SpotScanCapture`
- **Config:** `geofenceRadius = 30.0`, `maxPhotos = 1`
- **API impact:** `VerifySpotRequest` gains optional fields:
  - `depthAnalysis: DepthAnalysis?`
  - `aiAnalysis: OnDeviceAnalysisResult?`
  - `captureAttestation: CaptureAttestation?`

### Volunteer Cleanup (VolunteerCleanupScreen)

- **Phases affected:** BEFORE_PHOTOS, AFTER_PHOTOS, TRASH_HEAP_PHOTO
- **Changes:**
  - BEFORE_PHOTOS: `SpotScanCapture` with `geofenceRadius = 30.0`
  - AFTER_PHOTOS: `SpotScanCapture` with `geofenceRadius = 30.0`
  - TRASH_HEAP_PHOTO: `SpotScanCapture` with `requireGeofence = false` (heap may be at a dumpster away from spot)
- **API impact:** Additive optional fields:
  - `StartVolunteerRequest` gains: `beforeDepthAnalysis: DepthAnalysis?`, `beforeAiAnalysis: OnDeviceAnalysisResult?`
  - `CompleteVolunteerRequest` gains: `afterDepthAnalysis: DepthAnalysis?`, `afterAiAnalysis: OnDeviceAnalysisResult?`

## API Changes

All changes are additive. New fields are optional/nullable. Server ignores them if absent (backward compatible with older app versions). Server stores them when present.

| Model | New Fields | Required |
|-------|-----------|----------|
| `CreateSpotRequest` | None (fields already exist) | N/A |
| `VerifySpotRequest` | `depthAnalysis`, `aiAnalysis`, `captureAttestation` | All optional |
| `StartVolunteerRequest` | `beforeDepthAnalysis`, `beforeAiAnalysis` | All optional |
| `CompleteVolunteerRequest` | `afterDepthAnalysis`, `afterAiAnalysis` | All optional |

## Geofence UX

Overlay on camera preview showing proximity to spot:

**In-range (within geofenceRadius):**
- Green pill badge: "{distance}m from spot"
- Capture button enabled

**Out-of-range:**
- Red pill badge: "{distance}m away — move closer"
- Capture button disabled (greyed out)
- Camera preview still visible for orientation

**GPS unavailable:**
- Yellow pill badge: "Waiting for GPS..."
- Capture button disabled
- After 10 seconds without GPS: show "Enable Location Services" prompt

**requireGeofence = false (trash heap photo):**
- No distance badge
- Capture always enabled
- GPS still recorded in attestation

## Scope

### In scope
- `SpotScanCapture` shared composable
- `ScanCapturedPhoto` data class
- Camera swap in SpotScreen, SpotVerificationScreen, VolunteerCleanupScreen
- Additive API request field changes
- Geofence proximity UX overlay
- LiDAR 3D badge indicator (reuse existing route scan UI)

### Out of scope (future)
- Server-side before/after comparison scoring
- Coverage grid for spots
- Automated "still dirty vs clean" AI decision
- Multi-lap spot verification
- Server-side API endpoint changes (this spec covers client only; server stores new fields when present)

## Testing

- Verify `SpotScanCapture` shows geofence badge at various distances
- Verify capture is blocked when out of range (requireGeofence = true)
- Verify capture works without geofence (requireGeofence = false)
- Verify LiDAR depth data captured on Pro device simulator
- Verify AI analysis runs on first photo capture
- Verify all three flows (report, verify, cleanup) use the new component
- Verify API requests include new fields when data is available
- Verify backward compatibility — app works on devices without LiDAR (graceful degradation)
