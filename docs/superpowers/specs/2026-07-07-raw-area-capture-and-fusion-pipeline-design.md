# Raw Area-Capture Path & DGX Fusion Pipeline — Design + Handoff

**Date:** 2026-07-07 · **Author:** Claude (session audit + implementation) · **Status:** Partially implemented — shared plumbing shipped, native iOS capture hook needs MacBook build/test

**Relationship to canon:** finishes the gap the 2026-07-03 phase3-reconstruction-fusion-research
spike and the 2026-07-02 scan-guidance-world-model-and-rewards-design left open: neither
committed to an actual raw-capture producer. This session's audit confirmed the real blocker
wasn't R2 plumbing (that got fixed in the field this week, see updated memory
`project_gcc-scan-r2-worldmodel-pipeline-status`) — it's that **no scan session has ever
captured anything beyond GPS/detection metadata and a couple of manual JPEGs.** ARKit scene
depth is read live on-device for detection quality and discarded every frame. `ObjectCaptureResult`
(guided photogrammetry) exists but is scoped to one large litter item, not area coverage. This
doc covers what was actually built this session and exactly what's left.

## 1. What shipped this session (verified, desktop-Kotlin-target compiles clean)

- **`CapturedFrame`** (`shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt`) —
  one periodic color(+depth) frame: `colorKey`, `depthKey?`, `lat`/`lng`/`headingDegrees`,
  `cameraTransform` (row-major 4x4, 16 floats), `intrinsics` (fx,fy,cx,cy), `capturedAtMs`.
  Depth values are millimeters, matching the common RGBD-dataset convention (`DEPTH_PNG_SCALE_MM_PER_UNIT`)
  so a generic tool needs no GCC-specific documentation to read them.
- **`EnvScanManifest.capturedFrames: List<CapturedFrame>`** — additive field, defaults to
  empty so older manifests still deserialize.
- **`AreaCaptureFrameUploader`** (`composeApp/.../platform/AreaCaptureFrameUploader.kt`) —
  uploads one color+depth pair via the existing `createCaptureUploadUrl`/`uploadToPresignedUrl`
  primitives (same ones the manifest uploader already uses, already carrying this week's two
  field-fix corrections for R2 presigned-PUT auth/encoding). Best-effort, matching the existing
  photo-upload philosophy documented in `EnvScanSessionUploader`'s KDoc — **not queued/retried**;
  a dropped frame just means slightly sparser raw input for that session, not a data-integrity
  problem, since coverage/points/detections don't depend on it.
- **`AreaCaptureThrottle`** (`composeApp/.../platform/AreaCaptureThrottle.kt`) — gates capture to
  roughly one pair per 2s **and** 1.5m of movement (both configurable), reusing the existing
  `GeofenceVerification.distanceMeters` haversine rather than duplicating it. Standing still
  (e.g. reading a "hold steady" prompt) never re-captures a near-duplicate frame.
- **Backend**: `png` added to the content-type allowlist on both `/api/captures/upload-url`
  (the one `EnvScanSessionUploader`/env-scan sessions actually call) and `/api/scans/[id]/upload-url`
  — no other backend change needed, since `rawScanKey()`'s existing per-call key structure
  (`raw/{locationCellId}/{deviceId}/{capturedAt}.{ext}`) already supports "many small files per
  session," one call per frame.

## 2. What's NOT done: the native iOS capture hook

`composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`'s `analyzeTick()` (line ~435)
is the right place to call `AreaCaptureThrottle.shouldCapture(...)` and, when it returns true,
grab and encode a frame. This was deliberately **not** attempted blind this session, for two
concrete reasons discovered while investigating:

1. **The pixel-buffer interop is already fragile by necessity, not oversight.** `ArNativeSurface.currentPixelBufferAddress()`
   returns a raw pointer as a `Long` — its own KDoc explains cinterop silently drops ObjC
   pointer types from the framework header if a protocol method returns one directly. There's a
   dual code path (`native != null` Swift-bridged RealityKit surface vs. direct `ARFrame`
   SceneKit path) with different depth-access mechanics on each side.
2. **No existing utility encodes a depth `CVPixelBuffer` to anything.** `cvPixelBufferToPortraitPhoto()`
   (`CameraManager.kt:794`) is a proven, reusable primitive for the **color** side (JPEG via
   `VTCreateCGImageFromCVPixelBuffer` + `UIImageJPEGRepresentation`) — reuse it directly for
   `colorJpegBytes`. But `VTCreateCGImageFromCVPixelBuffer` is built for video pixel formats;
   ARKit's `sceneDepth.depthMap` is `kCVPixelFormatType_DepthFloat32`, a totally different
   buffer shape. Encoding it to a 16-bit grayscale PNG needs a hand-built `CGImage` from raw
   bytes (`CGDataProviderCreateWithData` + `CGImageCreate` with an explicit 16-bit grayscale
   bitmap layout), which is genuinely new code, not a call to something already proven.

Per this repo's own documented policy (`docs/superpowers/specs/2026-07-01-...-design.md` §4,
CLAUDE.md's `cdx-mobile` workflow), native AR rendering/capture work is written on the DGX but
**built and iterated on the MacBook** — this specific piece needs that loop, not a
best-guess merge. Concrete next steps, in order:

1. In `analyzeTick()`, after the existing ceiling/ground/coverage-raycast checks and before the
   thermal-guard early-return, resolve current lat/lng from `nativeSurface?.cameraPosition()` +
   the existing `localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)` helper (already used
   elsewhere in this file for the coverage raycast), call `AreaCaptureThrottle.shouldCapture(now, lat, lng)`.
2. On a hit: grab the color pixel buffer (`native.pixelBufferOrNull()` or `frame.capturedImage`,
   both already read elsewhere in this function) and encode via the existing
   `cvPixelBufferToPortraitPhoto()`.
3. Write and validate (on the MacBook) a new `depthPixelBufferToPng16(pixelBuffer): ByteArray?`
   sibling to `cvPixelBufferToPortraitPhoto` in `CameraManager.kt`, reading `frame.sceneDepth?.depthMap`
   (only available on the direct-`ARFrame` path today — confirm whether `GccArNative` needs a
   new bridge method to expose depth on the native path, or whether depth capture is scoped to
   SceneKit-path devices only for v1).
4. Launch the upload (`AreaCaptureFrameUploader.uploadFrame(...)`) on a background coroutine so
   it never blocks the tick loop; accumulate successful `CapturedFrame`s into a session-scoped
   list, threaded into the final `EnvScanManifest.capturedFrames` at Complete Scan.
5. Verify against the existing thermal guard (`thermalState >= 2` early-return at line ~498) —
   capture+encode+upload is real additional CPU/battery cost on top of "continuous Vision +
   plane detection + depth [already being] the hottest path in the app" (this file's own
   comment) — confirm the throttle's 2s/1.5m defaults are conservative enough on a real device
   before loosening them.

## 3. Non-goals for this increment

Auto-capturing a representative photo at the moment a live detection is promoted/confirmed (the
"images on hover even without a manual photo" ask) — this needs the same color-frame-capture
primitive above, wired to `WorldMarkerKind.CONFIRMED_TRASH`'s `addMarkerAt` call sites in
`ArFrameFeedback.kt`/`ArEnvScan.kt`, plus a new `mediaUrl` field threaded through
`ObservedSpotItem`/`CreateSpotRequest` to the server's `recordWorldObservation` call. Scoped out
of this pass specifically because it's additional untested native code layered on the same
fragile surface — do it as a fast-follow once §2's capture hook is validated on a real device,
not blind alongside it.

Depth capture on the Swift-native (RealityKit) code path, if `GccArNative` doesn't already
expose `sceneDepth` — investigate during §2 step 3, not assumed here.
