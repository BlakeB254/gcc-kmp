# Photo Upload Pipeline — Design Spec

**Date:** 2026-04-07
**Status:** Approved
**Approach:** Batch upload at submit time, matching SpotScreen pattern

## Problem

SpotVerificationScreen and VolunteerCleanupScreen capture photos via SpotScanCapture but pass placeholder URL strings (e.g., `"verify_123.jpg"`, `"before_1.jpg"`) to their API requests instead of uploading real photo bytes. SpotScreen already handles this correctly: store bytes in memory, upload all at submit time via `apiClient.uploadSpotPhotos()`, use returned URLs in the API request.

## Solution

Apply SpotScreen's proven upload pattern to the two remaining flows. Store `CapturedPhoto` objects (with raw bytes) in ViewModel state during capture, then batch-upload bytes at submission time using the existing `apiClient.uploadSpotPhotos()` endpoint. No new API endpoints needed.

## SpotVerificationViewModel Changes

### State changes

Remove `photoUrl: String?` as the primary photo reference. Add:
- `capturedPhoto: CapturedPhoto?` — raw photo bytes for upload

Keep existing scan enrichment fields (`scanDepthAnalysis`, `scanAiAnalysis`, `scanAttestation`).

### Method changes

**`setScanPhoto(scanPhoto: ScanCapturedPhoto)`** — remove the `url: String` parameter. Build a `CapturedPhoto` from scan data bytes, store in state, store scan enrichments, advance to DECISION phase.

**`submitVerification(result)`** — before building `VerifySpotRequest`:
1. Extract bytes from `capturedPhoto`
2. Upload via `apiClient.uploadSpotPhotos(listOf(bytes))`
3. Use returned URL in `VerifySpotRequest.photoUrl`
4. If upload fails, show error and stay on DECISION phase (don't advance to SUBMITTING)

### Screen changes

`SpotVerificationScreen` when block: change from `viewModel.setScanPhoto(scanPhoto, "verify_...")` to `viewModel.setScanPhoto(scanPhoto)`.

## VolunteerCleanupViewModel Changes

### State changes

Add to UI state:
- `capturedBeforePhotos: List<CapturedPhoto>` — raw bytes for before photos
- `capturedAfterPhotos: List<CapturedPhoto>` — raw bytes for after photos
- `capturedTrashHeapPhoto: CapturedPhoto?` — raw bytes for trash heap

Keep existing `beforePhotoUrls`, `afterPhotoUrls`, `trashHeapUrl` — these get populated at upload time with real URLs.

### Method changes

**`addBeforeScanPhoto(scanPhoto)`** — build `CapturedPhoto` from scan bytes, add to `capturedBeforePhotos`. Store scan enrichment data. No placeholder URL.

**`addAfterScanPhoto(scanPhoto)`** — same pattern, add to `capturedAfterPhotos`.

**`setTrashHeapScanPhoto(scanPhoto)`** — store single `CapturedPhoto` in `capturedTrashHeapPhoto`. Store scan enrichment data.

**`startCleanup()`** — before building `StartVolunteerRequest`:
1. Upload `capturedBeforePhotos` bytes via `apiClient.uploadSpotPhotos()`
2. Use returned URLs in `StartVolunteerRequest.beforeMediaUrls`
3. If upload fails, show error, don't advance phase

**`completeCleanup()`** — before building `CompleteVolunteerRequest`:
1. Upload `capturedAfterPhotos` bytes via `apiClient.uploadSpotPhotos()`
2. Upload `capturedTrashHeapPhoto` bytes separately (single photo)
3. Use returned URLs in `CompleteVolunteerRequest.afterMediaUrls` and `trashHeapMediaUrl`
4. If upload fails, show error, don't advance phase

### Screen changes

Phase composables use `capturedBeforePhotos.size` / `capturedAfterPhotos.size` for photo count display instead of `beforePhotoUrls.size` / `afterPhotoUrls.size`.

## Upload Error Handling

All uploads follow the same pattern:
1. Set `isSubmitting = true` before upload
2. If upload fails: set `isSubmitting = false`, set `error = "Failed to upload photos: ${e.message}"`, stay on current phase
3. If upload succeeds: proceed with API request using real URLs

User can retry by tapping the submit/confirm button again — bytes are still in memory.

## Existing Infrastructure Reused

| Component | Location | Used for |
|-----------|----------|----------|
| `apiClient.uploadSpotPhotos()` | `GccApiClient.kt:625` | Multipart upload, returns URLs |
| `CapturedPhoto` | `CameraManager.kt:11` | Bytes + depth + attestation |
| `ScanCapturedPhoto` | `shared/model/` | Enriched capture with AI/geofence |

## Scope

### In scope
- SpotVerificationViewModel: bytes storage + upload at submit
- SpotVerificationScreen: remove URL parameter from setScanPhoto
- VolunteerCleanupViewModel: bytes storage + upload at start/complete
- VolunteerCleanupScreen: use bytes-based photo count

### Out of scope
- GPS EXIF embedding (no-op today, separate concern)
- New API endpoints (reuse existing uploadSpotPhotos)
- SpotScreen changes (already works correctly)
- Photo compression/resizing (CameraManager already compresses to 80% JPEG)
