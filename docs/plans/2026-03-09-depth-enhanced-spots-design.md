# Depth-Enhanced Spot Reports — Design Document

**Date**: 2026-03-09
**Status**: Approved
**Approach**: AVDepthData Passthrough (Approach A)

## Goal

On LiDAR-capable iPhones (12 Pro+), automatically capture a depth map alongside every spot photo. Use the depth data to estimate debris volume and affected area. Zero UX change — the same capture button works on all devices; depth is silently captured when available.

## Architecture

```
[Camera Capture] → CapturedPhoto (bytes + optional depthAnalysis)
       ↓
[SpotViewModel] → Upload photo (multipart) + depth metadata (JSON)
       ↓
[API /api/spots] → Store depth analysis alongside the report
       ↓
[UI] → "3D" badge on depth-enhanced photos, volume estimate in spot card
```

### Platform boundary (KMP expect/actual)

- **commonMain**: `CapturedPhoto` gets optional `depthAnalysis: DepthAnalysis?`
- **iosMain**: iOS `CameraManager` configures `AVCaptureDepthDataOutput` when LiDAR available, processes depth on capture
- **androidMain / desktopMain**: `depthAnalysis` is always `null`

## Model Changes

### New: DepthAnalysis (commonMain)

```kotlin
data class DepthAnalysis(
    val hasDepth: Boolean = false,
    val estimatedVolumeCubicFt: Double? = null,
    val estimatedAreaSqFt: Double? = null,
    val maxDepthMeters: Double? = null,
    val depthConfidence: Float? = null,
    val depthMapThumbnailBase64: String? = null, // 64x48 colored depth viz
)
```

### Extended: CapturedPhoto (commonMain)

```kotlin
data class CapturedPhoto(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val depthAnalysis: DepthAnalysis? = null, // null on non-LiDAR
)
```

### Extended: CommunityReport (shared model)

```kotlin
val depthAnalysis: DepthAnalysis? = null,
```

## iOS Camera Changes

1. **Capability detection**: Check `AVCaptureDevice.default(.builtInLiDARDepthCamera, .video, .back)` exists
2. **Configure depth output**: Add `AVCaptureDepthDataOutput` to existing `AVCaptureSession`, set `isFilteringEnabled = true`
3. **Capture with depth**: Read `AVCapturePhoto.depthData` in photo callback
4. **On-device processing**:
   - Find ground plane (median depth at bottom 20% of frame)
   - Calculate volume above ground plane in center 80%
   - Generate 64x48 colorized depth thumbnail
5. **Fallback**: No LiDAR → session runs exactly as today, `depthAnalysis = null`

## Upload & API

- SpotViewModel: Include `depthAnalysis` JSON in request body when non-null
- API POST `/api/spots`: Accept optional `depthAnalysis` object
- DB: Add `depth_analysis` jsonb column to `communityReports`
- GET `/api/spots`: Return `depthAnalysis` in `toCommunityReport()`

## UI Changes

1. **"3D" badge**: Small chip on photo thumbnails with depth data
2. **Volume estimate**: Below description in spot card — "Est. volume: ~2.3 cu ft"
3. **Depth thumbnail toggle**: In full-screen gallery, overlay colored depth map
4. **Non-LiDAR**: Completely unaffected, no UI changes

## Key Principles

- **Zero UX friction** — same capture button, depth is automatic
- **Graceful degradation** — non-LiDAR devices completely unaffected
- **Lightweight** — JSON metadata + tiny thumbnail, not raw depth maps
- **Incremental value** — volume/area estimates help prioritize cleanup
