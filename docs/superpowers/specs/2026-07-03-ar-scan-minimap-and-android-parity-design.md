# AR Route-Scan Minimap + Android AR Parity — Design Spec

**Date:** 2026-07-03
**Status:** Awaiting review
**Scope:** Two pieces, prioritized ahead of the other AR-UX gaps found in the "ar-scan-experience" audit (checkmark-confirmation UI, ground-coupled vector scan effect — both deferred):

- **Part A** — wire the existing, working `ScanMinimapOverlay` into the AR route-scan screen (currently only used in spot-capture mode).
- **Part B** — bring Android's AR route-scan experience to full parity with iOS: ARCore session + fog-of-war ground reveal + live object detection + world markers, matching `ArRouteOverlay`/`ArScanner`'s existing expect/actual contract exactly, so `ArRouteScreen.kt` (fully platform-agnostic today) needs zero changes once Android's actuals are real.

---

## Part A — Route-scan minimap

### Current state

`ScanMinimapOverlay.kt` (commonMain) is a real, working Canvas overlay — fog/stale/session-scanned cells + trash pins, proper Mercator projection — already wired into `CameraCapture.kt` for spot-mode. `ArRouteScreen.kt` has no minimap; coverage is communicated only via `CoverageBar.kt`, a flat animated progress bar.

The gap isn't just "wire the composable" — `RouteScanViewModel` has no equivalent of `SpotScreen.kt`'s server-ledger fetch (`apiClient.getMapCoverage(bbox)` → `fogCellCenters`/`staleCellCenters`). It only tracks this-session-scanned cells locally via `CoverageTracker`.

### Design

1. Add a `fetchServerCoverage(bbox)` call to `RouteScanViewModel`, mirroring `SpotScreen.kt`'s pattern exactly (same `apiClient.getMapCoverage` call, same response shape) — populate `fogCellCenters`/`staleCellCenters` state, refreshed on route start and periodically (match whatever interval `SpotScreen` already uses).
2. In `ArRouteScreen.kt`, add `ScanMinimapOverlay` in the same corner-overlay position/style `CameraCapture.kt` uses, fed by: `scanCellCenters` from `coverageTracker.scannedCells` (already available), `fogCellCenters`/`staleCellCenters` from the new fetch, `trashItems` from the route's detected hotspots (already tracked for `CoverageBar`'s trash-pin equivalent, if any — otherwise from `scanHotspots` state). `cellSizeMeters = 3.0`, matching `CameraCapture`'s existing hardcoded value for visual consistency between modes.
3. `CoverageBar` stays — it's a compact always-visible summary; the minimap adds spatial detail, it doesn't replace the bar.

No platform-specific work — this is common code exercising an already-proven common component.

---

## Part B — Android AR parity

### What Android must implement

The `expect` contracts (`ArScanner`, `ArRouteOverlay` — both commonMain, unchanged) that `ArRouteScreen.kt` already consumes with zero iOS-specific branches:

- `ArScanner`: `isSessionActive`, `startSession()/stopSession()`, `placeAnchor(lat,lng): ArAnchor?`, `getCameraFovDegrees()`, `getArHeading()`
- `ArRouteOverlay`: `trackingState`, `updateGeoReference(lat,lng)`, `updateFog(unscannedCells, cellCenter)`, `updateBoundaryStatus`, `start()/stop()`, `liveDetections`, `setSceneVoteListener`, `setFrameAnalysisEnabled`, `addWorldMarker(lat,lng,WorldMarkerKind)`, plus the `ArRouteOverlayView` composable.

Both are currently no-op stubs, explicitly documented as "iOS-only for v1" — this spec reverses that.

### Rendering stack: SceneView (ARCore + Filament)

Google archived Sceneform in 2021; **SceneView** (`io.github.sceneview`) is the actively-maintained successor — ARCore for perception, Filament (Google's own production rendering engine) for rendering, Jetpack Compose-native API. This is the direct Android analogue of iOS's `ARSCNView`/SceneKit choice (same reasoning: a maintained, interop-friendly framework over hand-rolled OpenGL). Confirmed current and maintained (latest release January 2026) via web search during this session — not assumed from training data.

### `ArScanner.android.kt`

- `startSession()`: `ArCoreApk.getInstance().checkAvailability(context)` → request install via `requestInstall()` if `SUPPORTED_NOT_INSTALLED`; create `Session`, `Config` with `PlaneFindingMode.HORIZONTAL`; set `_isSessionActive = true`.
- `stopSession()`: pause/close session, `_isSessionActive = false`.
- `placeAnchor(lat, lng)`: **matches iOS's actual (simpler-than-expected) behavior** — iOS does *not* do a real geo→AR-space transform here; it anchors at the current camera pose and stores `lat`/`lng` purely as passthrough metadata on the returned `ArAnchor`. Android does the same: `session.createAnchor(camera.pose)`, return `ArAnchor(id, lat, lng, worldTransform=<JSON metadata>)`.
- `getCameraFovDegrees()`: unlike iOS's single hardcoded 63° (one device family), Android hardware varies enormously — query the active camera's real FOV via `Camera2` characteristics (`SENSOR_INFO_PHYSICAL_SIZE` + `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`), falling back to a 65° constant if characteristics are unavailable. More correct than iOS's approach, small added scope, worth doing given device diversity.
- `getArHeading()`: derived from the ARCore camera pose's rotation, same forward-vector-to-heading math as iOS (`atan2` on the pose's rotation matrix column).

### `ArRouteOverlay.android.kt` — geo alignment

ARCore's default world-tracking frame is **not** compass-aligned like ARKit's `ARWorldAlignmentGravityAndHeading`. Per user decision: use the **device compass/rotation-vector sensor** to align ARCore's local frame to true north once at session start (not ARCore's Geospatial API — that needs a Google Cloud API key + billing + upfront location permission, out of scope for this pass). Read `SensorManager.getRotationMatrix`/`getOrientation` once when the session binds, compute the offset between ARCore's arbitrary local +Z and true north, and apply that rotation when converting `updateGeoReference`'s lat/lng pairing into local meters — otherwise identical math to iOS: `refPosition + (eastMeters, 0, -northMeters)` using the same meters-per-degree constant. This will drift more than iOS's continuous ARKit-native compass alignment over a long session; acceptable for v1 given iOS itself only renders fog within a 35m radius (small absolute error budget).

- Fog planes: SceneView/Filament plane primitives (or a tiny bundled quad model) at each unscanned cell's local position, translucent gray, matching iOS's `makeFogNode` sizing/offset (`GROUND_OFFSET_METERS = 1.4f` below camera).
- World markers (`addWorldMarker`): bundled small GLB torus models (green = `CONFIRMED_TRASH`, orange pulsing = `EXPECTED_TRASH`) — **reuse this session's own `scripts/convert-scan-asset.py`/Blender pipeline** (built earlier for scan-asset conversion) to author these two simple torus GLBs rather than hand-authoring or sourcing them elsewhere.
- `trackingState`: map ARCore's `Camera.trackingState`/`TrackingFailureReason` to `NORMAL`/`LIMITED`/`NOT_AVAILABLE` (natural 1:1 mapping).

### Live detection pipeline (parity with iOS's Vision-based approach)

iOS: one `VNImageRequestHandler` call per 1000ms tick produces both classification (top 15, sorted) and a saliency bounding box; a trash-keyword match (`TRASH_TOKENS`, confidence ≥ 0.35) plus the saliency box becomes a `LiveDetection`. Scene-vote (indoor/outdoor) reuses the *same* per-tick classification results, computed every 2nd tick.

ML Kit has no single equivalent — Image Labeling returns whole-frame labels with no bounding box; **Object Detection & Tracking** (`com.google.mlkit:object-detection`, stream mode) returns bounding boxes but coarse/no fine-grained labels. Android mirrors iOS's two-request pattern with two ML Kit calls per tick instead of one Vision call: Object Detection for the reticle's bounding box, Image Labeling on the cropped region for the trash-keyword confidence check — same 1000ms cadence, same 0.35 threshold, same `TRASH_TOKENS` list.

**Refactor called out as part of this design** (per "improve what you're touching," not unrelated scope-creep): the keyword-matching logic (`TRASH_TOKENS`, indoor/outdoor vote-from-observations) and the 2m/2-tick promotion-to-confirmed-marker streak logic are currently embedded in iOS's platform-specific `ArFrameFeedback.kt`/`SceneVoteVision.kt`, even though none of that logic touches a platform API — it's pure business logic operating on `(label, confidence)` tuples and hit-test distances. Extract both into commonMain (a shared `TrashDetectionRules.kt`-style file) so Android calls the *identical* code rather than a hand-copied reimplementation that can silently drift from iOS's thresholds over time. Confirm during implementation whether `SceneVoteVision.kt` is already common or iOS-only before assuming the extraction's exact diff shape.

Reticle coordinate conversion (sensor-space bounding box → normalized 0..1 top-left view-space `cx,cy,r`) needs the same rotation handling iOS does (portrait view, landscape sensor) — standard Android `ImageAnalysis`/Camera2 coordinate math, not novel, but real work; verify against the device's actual `Surface.rotation` rather than assuming a fixed orientation.

### Prerequisite: real CAMERA runtime permission

**Pre-existing gap, blocking for this work**: no real Android runtime CAMERA permission flow exists today. `PermissionManager.android.kt` is an explicit stub ("wired in Phase 8"); the existing spot-mode `CameraPreview` composable calls `ProcessCameraProvider`/`bindToLifecycle` with no permission check at all. ARCore session creation will simply fail without granted CAMERA permission. This spec includes implementing `PermissionManager.android.kt` for real (`rememberLauncherForActivityResult(RequestPermission())`), wired in ahead of `ArScanner.startSession()` — both because ARCore needs it and because it fixes a real latent bug in the existing spot-capture flow too.

### New Gradle dependencies

`com.google.ar:core`, `com.google.mlkit:object-detection`, `com.google.mlkit:image-labeling`, `io.github.sceneview:arsceneview` (exact version TBD at implementation time — pin latest stable). `compileSdk 36 / minSdk 26 / targetSdk 35` and the existing `google()` repository (already content-filtered to `com.google`) all satisfy these without further Gradle config changes.

## Testing plan

No unit-test scaffolding covers AR code on either platform today. Verification for this work: build + run on a real ARCore-capable Android device (this repo's Android target builds on this DGX via Gradle — unlike iOS, which needs the MacBook) — confirm fog planes appear/disappear as `updateFog` is called with synthetic cell sets, confirm a bright litter-colored test object in frame produces a `LiveDetection` reticle at roughly the right screen position, confirm the 2m/2-tick promotion places a torus marker, confirm `ArRouteScreen.kt` needs no changes (compile + manual smoke test only). Minimap: mirror whatever manual verification pattern the original spot-mode minimap used, applied to the route-scan screen.

## Out of scope (separate future work)

Checkmark-style confirm-on-detect UI, and a true ground-coupled "vector scan" coverage effect (as opposed to the current decorative screen-space sweep) — both identified in the earlier audit, not designed here.
