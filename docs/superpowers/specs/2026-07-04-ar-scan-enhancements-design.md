# AR Scan Enhancements — ground grid, trash lock-on, vector scan, AR boundaries, type-aware map litter

Date: 2026-07-04
Status: approved for implementation (autonomous session; user gave feature list directly)

## Goals (user's words, distilled)

1. Grid on the env-scan minimap; when the camera recognizes the ground, an AR grid effect appears on the ground itself.
2. While scanning, likely-trash objects get a tracking highlight; walking up and focusing triggers a confirm flow that adds weight + type estimates to our data.
3. Keep/verify: never-scanned fog effect, minimap scanned-area fill + route percent fill (env scan and route scan both).
4. Previously scanned litter must be depicted accurately and persistently on the minimap and main map — not generic dots; depict *what* is there, placed via user location + heading + LiDAR distances.
5. Vector-scan effect over ground already confirmed scanned during env scans (camera view).
6. Route scans: boundary areas visible in the camera output when the user pans toward them.
7. Use ARKit/RealityKit to the fullest; add any other effects that enhance the experience.

## Key architectural findings

- **Two camera stacks exist.** Env scan = AVCaptureSession (`CameraManager.kt`, `CameraPreviewWithAVCapture`); route AR = ARKit `ARSCNView` + SceneKit (`ArRouteOverlay.kt`). No plane detection is configured anywhere; `frameSemantics=sceneDepth` is set in `ArScanner.kt` but the depth map is never read. Distance today = `frame.hitTest(EstimatedHorizontalPlane|FeaturePoint)`.
- **RealityKit is Swift-only** (no ObjC headers) → unreachable from Kotlin/Native cinterop. SceneKit is soft-deprecated only (WWDC25 #288: maintenance mode, keeps working). Decision: **stay on ARSCNView/SceneKit**; "use ARKit fully" = plane detection + classification, raycasting, sceneDepth, world anchors, coaching overlay. A Swift `@objc` bridge for RealityKit (pattern exists: `CrashDiagnosticsObserver`) is deferred until a RealityKit-exclusive feature is actually needed.
- `world_observations.attributes` jsonb already carries `{severity, estimatedVolumeLiters, estimatedWeightLbs}`-style data; `/api/map/observations` already returns `itemType`. Weight/size need no schema migration.
- Mobile main map renders uniform orange dots at close zoom (`GccMap.ios.kt` spot-markers layer); no per-type iconography.
- Env-scan minimap (`ScanMinimapOverlay.kt`) has fog veil (Path.combine Difference — deliberately not BlendMode.Clear), corridor fill, trash dots. No grid.

## Design

### A. Env scan moves to an ARKit surface (`ArEnvScanView`)

New iosMain component modeled on `ArRouteOverlay`'s UIKitView pattern: `ARSCNView` + `ARWorldTrackingConfiguration` with
`worldAlignment = gravityAndHeading`, `planeDetection = horizontal`, `frameSemantics = smoothedSceneDepth` when supported.

- **Preserves existing contracts:** the ARSession delegate feeds the same `CameraSceneVoteBus`/`CameraDetectionBus` (Vision on `ARFrame.capturedImage`, throttled ~1.5 s as today) so OutdoorGate keeps working; silent frame capture reads `session.currentFrame.capturedImage` through the existing CVPixelBuffer→JPEG path, so `EnvScanManifest`/R2 capture is unchanged.
- **Fallback:** if `ARWorldTrackingConfiguration.isSupported` is false, keep the current AVCapture preview (also keeps the Compose preview compiling for sim; ARKit compiles but doesn't run on simulator).
- `ARCoachingOverlayView` (goal `.horizontalPlane`) during plane acquisition.
- Thermal guard: skip Vision ticks and disable depth semantics when `ProcessInfo.thermalState >= .serious`.

### B. Ground grid (camera) — `GroundGridRenderer`

`ARSCNViewDelegate.didAdd/didUpdate` for `ARPlaneAnchor`: attach `ARSCNPlaneGeometry(device:)`, call `update(from: anchor.geometry)` on every didUpdate so the grid grows with recognition. Material = runtime-generated tiled grid texture (CoreGraphics-drawn UIImage; repeat wrap, contentsTransform scaled to meters), emissive GCC green, alpha ≈ 0.35, `writesToDepthBuffer=false`. Gate the "ground" styling on `anchor.classification == .floor` where available (A12+); other horizontal planes get a fainter neutral grid.

### C. Vector-scan effect over scanned cells (camera) — `VectorScanRenderer`

For cells the `CoverageTracker` has credited (same `scanned/freshCellCenters` state the minimap consumes): flat 3 m SCNPlane quads laid on detected floor height (fallback: geo-ref ground offset −1.4 m), wireframe-style dense grid texture, emissive green, slow opacity pulse (SCNAction). Placed via the existing GPS↔AR geo-reference (inverse `localToLatLng`). Cap ~350 nodes nearest-first (fog-node pattern, `MAX_FOG_NODES` precedent). Scanned ground reads as "digitized"; unscanned ground shows only the plain plane grid (B) — and never-scanned fog stays as-is.

### D. Trash lock-on + close-range confirm — `TrashLockController` (extends `ArFrameFeedback` behavior)

- **Far mode (exists, kept):** 1 Hz `VNClassifyImageRequest` + objectness saliency → reticle + streak; reticle icon upgraded to a "tracking" look (corner brackets + pulse) in `DetectionReticleOverlay`.
- **Lock:** when a candidate has streak ≥ 2, raycast the bbox center (`existingPlaneGeometry` → fallback `estimatedPlane` → fallback sceneDepth sample with confidence gating) → world position → `ARAnchor`; highlight reprojects via `projectPoint` each frame; haptic on lock.
- **Near mode:** when anchor distance ≤ 2.0 m and roughly centered for ≥ 2 ticks → classify the cropped ROI at higher priority, estimate physical width from depth + bbox angular size (`2·d·tan(θ/2)` using camera intrinsics), map to canonical item type + weight via `TrashWeightEstimator`, then show a Compose **confirm card**: "Plastic bottle · ~0.4 m · est 20 g — Confirm / Not trash / change type". Confirm → `ObservedSpotItem` with new fields + torus world marker + haptic; "Not trash" suppresses that anchor.
- **Type taxonomy (commonMain):** canonical types `plastic_bottle, glass_bottle, can, plastic_bag, cup, paper, wrapper, cigarette, food_waste, other` with per-type typical weights and size-scaling (type lookup beats volume×density: litter is hollow). Unit-tested in desktopTest.

### E. Data flow: estimates → server → maps

- `ObservedSpotItem` (+`estimatedWeightGrams: Int?`, `estimatedWidthMeters: Double?`); position from the raycast world coordinate → `localToLatLng` (LiDAR-accurate placement instead of "2.5 m forward along GPS heading").
- Ingest: observedItems payloads carry the estimates; `recordWorldObservation` stores them in `attributes` jsonb (no migration).
- `/api/map/observations` additionally lifts `estimatedWeightGrams`/`estimatedWidthMeters` from attributes.
- Route/env scan path stops submitting `observedItems = emptyList()` — confirmed items flow from the AR session into the scan submission, so scanned litter persists (supersede logic from PR #65 already retires stale nearby reports).

### F. Type-aware litter depiction (minimap + main map)

- **Minimap:** trash dots become small per-type glyphs (distinct shapes/colors per canonical type — e.g. bottle=triangle-on-stem, can=square, bag=rounded diamond), drawn in the existing Canvas; cluster count badge when >3 in a cell.
- **Main map (close zoom):** replace uniform orange circles with a MapLibre SymbolLayer using runtime-registered per-type glyph images (CoreGraphics-drawn, registered via `style.addImage`), data-driven on the `itemType` feature property; unknown type falls back to the current dot. Heat surface at overview zooms unchanged. Observation pins get `itemType` included in their GeoJSON properties.

### G. AR boundary walls (route scan camera) — `BoundaryWallRenderer` in `ArRouteOverlay`

`updateBoundaries(outer, islands)` fed from RouteScanViewModel's existing block boundary data. Edges within ~45 m of the camera (re-evaluated on geo-ref updates) are sampled into ≤5 m segments and rendered as vertical translucent wall strips (0.9 m tall, bottom at ground): green emissive for the outer boundary, amber for inner exclusion islands, double-sided, alpha ≈ 0.3 with a brighter top edge line. Panning toward the assignment edge shows the wall exactly on the boundary line. (Replaces nothing — the camera-attached red proximity veil stays as the "you're crossing it" alarm.)

### H. Minimap grid

Grid lines aligned to the CoverageTracker cell lattice (3 m cells; skip to every 2nd line when pixel spacing < 8 px), alpha ~0.12, drawn beneath fill/veil layers in `ScanMinimapOverlay` — same canvas used by both env-scan and block-framed route minimaps, so both get it.

### I. Verify existing coverage effects (no redesign)

Fog veil, corridor fill, `LAP_COMPLETE_PERCENT` fill bar, freshCellCenters wiring — verified as present; this round only re-tests them around the new AR surface wiring.

## Error handling & performance

- Vision runs off-main, latest-frame-only, drop-while-in-flight (existing pattern); detector cadence 1 Hz far / 2 Hz near; depth sampling only during near-mode measurement.
- Raycast failures degrade: plane → estimated plane → depth sample → GPS-heading projection (today's behavior).
- Node caps: grid planes unlimited (ARKit merges planes), vector cells ≤350, boundary segments ≤200, fog ≤600 (existing).
- All new AR code is iosMain-only; commonMain gets models + estimator + tests so desktopTest covers logic.

## Testing

- desktopTest: TrashWeightEstimator (label→type mapping, width→weight scaling), boundary-segment sampling math, minimap grid spacing logic (if extracted).
- Compile gates: `:composeApp:linkDebugFrameworkIosSimulatorArm64`, xcodebuild sim, `npx tsc --noEmit` in site/.
- Physical-device walk test required for: plane classification behavior, raycast placement accuracy, wall alignment, thermal behavior. Flagged for Blake's next field session.

## Agreed follow-up round: native Swift RealityKit module

Per Blake (2026-07-04): the AR render layer should eventually move to a
native Swift module to get the full modern iPhone stack — RealityKit ECS,
particle systems, PBR materials, post-processing, Object Capture
photogrammetry (measured 3D models of large dumps), RoomPlan-style scans.
Architecture: a `GccArNative` Swift target in iosApp/ exposing an `@objc`
view + callback protocol (CrashDiagnostics.swift precedent), plugged in
BEHIND the Kotlin-facing `ArEnvScanOverlay`/`ArRouteOverlay` interfaces this
round establishes — Swift owns the AR view, KMP keeps owning business logic,
screens, and the API layer. NOT a separate Swift app (that would fork the
product and lose the shared coverage/points/offline machinery). Scope it as
its own design + PR after this round's device test.

## Out of scope (this round)

- The RealityKit Swift module above (SceneKit covers this round's effects).
- Custom TACO/YOLO CoreML litter detector — biggest future accuracy win for typed detection, but training/converting a model is its own project. Current round uses VNClassify + saliency, which the codebase already trusts.
- Android parity (iOS-first per 2026-07-04 direction).
