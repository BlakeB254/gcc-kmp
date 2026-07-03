# AR Scan Experience Overhaul — Design

**Date:** 2026-07-03
**Scope:** iOS scan experience (spot report + block route), Compose Multiplatform client + small `site/` API additions.
**Follows:** PR #11 (points economy, rectify backend), PR #12 (AR coordinate math, fog, guidance arrow, coverage events).

## Goal

Make the camera/AR scan feel like a real AR product before the next TestFlight build:
full-screen camera with minimal chrome, an outdoor-only gate, and live AR feedback that
brings the production map data (coverage, world observations, points) into the camera view.

## Context (as built today)

- `SpotScreen` camera phase is letterboxed: custom top bar (back, phase title, `GccLogo`),
  camera inside `padding(16.dp)`, bottom "Skip Photo / Next: Set Location" button,
  "Submit Report" two phases later (`SpotScreen.kt:80-116, 207-260, 272, 729`).
- `ArRouteScreen` / `SpotArScreen` are full-bleed but use hardcoded `48.dp`/`96.dp`
  offsets instead of safe-area insets.
- Route fog (`ArRouteOverlay`, iosMain) renders session-local unscanned cells only;
  server coverage state (`/api/map/coverage`, fresh/stale) is not consulted.
- Trash identification is on-device Apple Vision (`OnDeviceAnalyzer`); no live-frame
  detection (CameraManager is single-shot `capturePhoto`).
- Points: distance-based coverage ledger (`/api/scans/coverage`), rates mirrored in
  `shared` (`PointsAction`); `POST /api/scans/{id}/rectify` exists server-side with
  **no client caller**. No live points UI during scanning.
- No client-side building footprints or indoor/outdoor logic; map buildings are
  synthetic rectangles (`BuildingData.kt`); exclusion zones are server-side only.

## Design

### 1. Full-screen spot camera + one-tap Complete Scan

- Camera phase renders edge-to-edge (no top bar, no letterbox padding, camera behind
  the status bar). Floating chrome only, all inset-aware (`statusBarsPadding` /
  `navigationBarsPadding`): circular ✕ top-left, geofence + capture-mode pills as today.
- Remove "Skip Photo". Add a **Complete Scan** pill button, bottom-right corner,
  enabled once ≥1 usable capture exists.
- **Complete Scan submits directly** (one-tap flow): embeds GPS EXIF, uploads photos,
  creates the spot report using on-device analysis output (`generatedDescription`,
  `suggestedTags`, severity, estimated weight) and the current GPS fix, then shows the
  existing RESULT phase with points. The LOCATION/DESCRIPTION phases remain in the code
  and reachable via a small "Add details" text link next to Complete Scan for users who
  want to override the AI-generated fields.
- The non-camera phases (details/result) keep the existing top bar; only the camera
  phase goes chrome-less.
- Fix `SpotArScreen` / `ArRouteScreen` hardcoded offsets → real insets. Give both AR
  screens the same low-corner Complete/Done affordance (route AR: "Complete Scout",
  wiring to the existing scout-complete transition; spot AR: returns with captures).

### 2. Outdoor gate ("Go outside")

New `OutdoorGate` (commonMain class + platform signal sources) with three states:
`OUTDOOR`, `UNCERTAIN`, `INDOOR`. Signals, combined with hysteresis (state changes only
after ~5s of consistent evidence, so brief GPS flutter doesn't flap the UI):

1. **GPS accuracy** (all devices): sustained `horizontalAccuracy > 25m` while stationary
   is the standard indoor proxy; `< 10m` sustained is strong outdoor evidence.
2. **Vision scene classification** (AR-capable devices): classify one `ARFrame` every
   ~2s via `VNClassifyImageRequest`; indoor-taxonomy labels (room/indoor/furniture
   cluster) above confidence threshold vote INDOOR, outdoor labels vote OUTDOOR.
   ARKit frames sidestep the CameraManager single-shot limitation.

Behavior when `INDOOR`: full-screen scrim "Go outside to scan" with icon + explanation,
capture button disabled, coverage-event emission suppressed, AR fog/marker updates
paused. Clears automatically when signals recover. `UNCERTAIN` shows nothing (benefit
of the doubt). BASIC (non-AR) devices use GPS-only evidence and therefore mostly sit in
`UNCERTAIN`/`OUTDOOR` — acceptable: the gate is a deterrent, not a security boundary.
Applies to: spot camera phase, spot AR, route AR.

### 3. Live AR feedback

**3a. Server-seeded fog.** On route AR entry, fetch `/api/map/coverage` for the block
area; cells whose state is missing or `stale` seed the fog set (in addition to the
session-local unscanned set). Fog dissipates as coverage events land, exactly as today.
Result: the production digital twin's freshness is visible in the camera. (Spot AR keeps
its sector ring — fog is meaningless at a single point.)

**3b. Trash highlighting.** New `LiveDetector` (iosMain): throttled ~1 Hz pipeline on
`ARFrame`s running `VNClassifyImageRequest` + objectness-based saliency to get candidate
bounding regions. Confident trash classifications render a circular reticle (Compose
canvas overlay, projected from the salient region's image coordinates) with the label.
When the user closes within ~2m (ARKit raycast/LiDAR depth at the salient point), the
detection is **persisted as a world-anchored 3D marker** (billboarded ring + icon node)
so it stays in place as the camera moves — the "recreated" object.

**3c. Points-in-the-moment.** Session points HUD pill (top area, next to coverage pill):
running total of estimated points this session, computed client-side from coverage-event
classifications using the mirrored rates (new-area 10/m, stale 5/m). Each qualifying
coverage event spawns a floating "+N pts" pip that drifts up and fades. Reconciliation
note shown at completion (server ledger remains the source of truth).

**3d. Expected-data (rectify) flow.** On AR entry, fetch `/api/map/observations` near
the user; render `dirty` observations as expected-trash AR markers (distinct color from
live detections). When within ~5m of one, surface a bottom confirm sheet: "Still there"
/ "Already clean" (+ optional photo). Wires to `POST /api/scans/{id}/rectify` — the
missing client half — earning the 25-point rectification bonus. Observation markers
update locally on confirmation.

### Component boundaries

- `OutdoorGate` (commonMain state machine; expect/actual `SceneClassifier` iosMain) —
  input: location flow + scene votes; output: `StateFlow<OutdoorState>`. Testable in
  commonTest with synthetic signal sequences.
- `LiveDetector` (iosMain, owned by the AR overlay classes; results surfaced to Compose
  via `StateFlow<List<LiveDetection>>` — image-space region + label + distance).
- `SessionPointsEstimator` (commonMain, pure) — folds coverage-event classifications
  into an estimated total; unit-testable.
- Overlay API additions: `ArRouteOverlay.seedFog(staleCells)`,
  `addPersistentMarker(lat,lng,type)`; both no-ops on Android/Desktop stubs.

### Error handling

- Coverage/observations fetch failures: degrade silently to session-local behavior
  (fog still works as today), log once.
- Rectify POST failures: queue-and-retry through the existing `CoverageEventQueue`
  pattern (separate small queue), never block the scan.
- Vision failures / thermal throttling: LiveDetector backs off (2s → 5s cadence).

### Testing

- commonTest: `OutdoorGate` hysteresis + signal combination; `SessionPointsEstimator`
  math; rectify queue behavior.
- Simulator smoke: `:composeApp:linkDebugFrameworkIosSimulatorArm64` + xcodebuild
  (AR renders nothing in sim, but the build and non-AR camera chrome are verifiable).
- **Physical-device walk test required before TestFlight**: outdoor gate behavior,
  reticle projection accuracy, anchored-marker stability, fog seeding.

## Alternatives considered

- **Complete Scan → existing details phases** (instead of one-tap submit): rejected —
  keeps the Submit button the request removes; the AI analysis already produces every
  field the manual phases collect. "Add details" link preserves the manual path.
- **Building-footprint indoor detection**: rejected — no real footprint data exists
  client- or server-side; synthetic `BuildingData` rectangles are demo-only.
- **CameraManager frame-streaming rewrite** for detection outside AR: deferred — AR
  frames cover AR-capable devices; BASIC devices keep single-shot analysis on capture.

## Out of scope

Android AR implementations (stubs remain), real building footprints, exclusion-zone
geometry on the client, TestFlight upload (pending device walk test).

## Assumptions (autonomous session — flag on PR review)

1. One-tap Complete Scan auto-submit is the intended reading of "removing the skip and
   submit buttons"; manual details remain reachable via "Add details".
2. "Recreated" close-scanned trash = persistent world-anchored 3D marker.
3. "Easy ways to trigger flows for updates of expected data" = the rectify confirm
   sheet against nearby world observations.
4. Indoor gating is heuristic (GPS + scene classification), not geometry-based.
