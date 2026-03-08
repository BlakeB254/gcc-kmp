# Face ID Auto Re-Login + Spot Camera Integration Design

**Date:** 2026-03-07
**Status:** Approved

## Feature 1: Face ID Auto Re-Login with Keychain Credentials

### Problem
When the JWT expires, Face ID fails and the user must type their password again. The biometric token is just a copy of the JWT, so it expires too.

### Solution
Store email+password in the iOS Keychain with biometric-protected access. On Face ID unlock, read credentials from Keychain and automatically call the login API for a fresh JWT.

### Architecture
- New `expect class KeychainManager` (common) with `actual` iOS impl using Security framework (`SecItemAdd`/`SecItemCopyMatching`)
- Desktop/Android actuals return null (no-op)
- `LoginViewModel` gets `loginWithBiometric()` that reads credentials from Keychain and calls `login()`
- Face ID button calls `loginWithBiometric()` — tries biometric token fast-path first, falls back to Keychain re-login

### Files Changed
- `KeychainManager.kt` — new (common expect + iOS actual + desktop actual)
- `LoginViewModel.kt` — add `loginWithBiometric()`
- `LoginScreen.kt` — Face ID button calls `loginWithBiometric()`
- `AppModule.kt` — register KeychainManager in Koin

## Feature 2: Spot Report Camera + Location Integration

### Problem
- Camera captures photos but bytes are discarded — only placeholder filenames stored
- Photos never uploaded to server
- Camera phase shows dark placeholder instead of live preview
- No GPS EXIF data in photos
- No thumbnail strip for captured photos

### Solution

#### Photo Storage
- `SpotUiState.capturedPhotos: List<CapturedPhoto>` replaces `capturedPhotoUrls: List<String>`
- Actual photo bytes retained in memory until upload

#### Upload Endpoint
- New `POST /api/spots/upload` — accepts multipart JPEG images
- Uses `@vercel/blob` for storage, returns public URLs
- Called before spot report creation; URLs passed to existing POST /api/spots

#### Live Camera Preview
- Replace dark placeholder in CameraPhase with `CameraPreview()` composable (already implemented in iOS CameraManager)

#### Thumbnail Strip
- After capture, show horizontal row of small thumbnails at bottom of camera phase
- Tap thumbnail to remove photo

#### GPS EXIF Embedding
- Before upload, embed current GPS coordinates into JPEG EXIF using `CGImageDestination` on iOS
- Provides forensic proof of location alongside the separate lat/lng in the report

### Files Changed
- `SpotViewModel.kt` — store CapturedPhoto objects, add upload step
- `SpotScreen.kt` — use CameraPreview(), thumbnail strip, pass bytes on capture
- `site/src/app/api/spots/upload/route.ts` — new multipart upload endpoint
- `GccApiClient.kt` — add `uploadSpotPhotos()` method
- iOS `CameraManager.kt` — GPS EXIF embedding helper
- `package.json` — add `@vercel/blob` dependency
