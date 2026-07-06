# Environment Scan Cleanup — Design

Date: 2026-07-06
Status: Approved by Blake (background session)
Scope: iOS-first (per standing direction); commonMain changes compile everywhere.

## Problem

Field testing of the standalone Environment Scan (SpotScreen scan mode) reported:

1. Ground grid appears, but no AR fog dissipates over never-scanned areas.
2. No vector AR effect over scanned/credited areas.
3. Minimap does not fill in during the session.
4. No on-screen guidance about how/where to scan.
5. No GPS heading cone on the minimap, and it doesn't rotate with facing.
6. Unclear how the on-camera grid relates to the minimap grid, and what the
   "3D scan" button is for.

## Root causes (traced 2026-07-06)

- **Heading is always null on iOS.** `LocationProvider` (iosMain) has two
  competing `CLLocationManager` delegates. `startLocationUpdates()` installs a
  delegate that overrides `didUpdateHeading` and calls
  `startUpdatingHeading()`; but every collector of `locationFlow` (SpotScreen
  has three) runs the `callbackFlow` block that reassigns
  `locationManager.delegate` to a `flowDelegate` with **no**
  `didUpdateHeading` override. CoreLocation silently drops heading callbacks.
  Downstream: no cone (`GccMap` draws the cone only when `gpsHeading != null`),
  and the coverage loop falls back from `updateWithFrustum` (credit the wedge
  you're looking at) to `updatePosition` (5 m disk at your feet) — so panning
  the camera credits nothing → no vector quads, no minimap fill.
- **Env scan has no AR fog by design gap.** `updateFogCells` exists on the
  mode-agnostic native surface and is used only by `ArRouteOverlay`.
  `ArEnvScanOverlay` has no fog API.
- **Minimap context params dropped.** `SpotScreen` computes
  `fogCells`/`staleCells`/`freshCells` but the scan-mode wrapper
  `SpotScanCapture` forwards only `scanCellCenters`; the rest default empty.
- **Guidance is route-only.** Guidance ring, frontier arrow, slow-down banner
  all live in route scan. Env scan has only the status chip and the
  "move closer" reticle hint. RealityKit path also skips the plane-coaching
  overlay (SceneKit fallback has it).
- **Ground grid is cosmetic and unaligned.** The glowing grid is a 0.5 m
  repeat texture stuck to detected planes at the plane anchor's arbitrary yaw;
  it is not geo-referenced. Only the 3 m vector cells are geo-locked. AR world
  axes are compass-true (`gravityAndHeading`), so a world-axis-aligned grid
  would visually match the north-up minimap lattice.
- **Route minimap cone hardcoded off**: `ArRouteScreen` passes
  `gpsHeading = null` even though the VM computes heading.

## Design

### A. Heading pipeline fix (foundation)

`LocationProvider` (iosMain) uses **one** shared multiplexing delegate that
handles `didUpdateLocations`, `didUpdateHeading`, and auth changes, installed
once. It publishes into shared StateFlows; `locationFlow` becomes a
subscription to those flows (plus start/stop refcounting as today) and never
reassigns `locationManager.delegate`. `startUpdatingHeading()` runs whenever
location updates run. `ArRouteScreen` passes its existing VM heading to the
minimap instead of `null`.

Acceptance: cone renders and rotates on both env-scan and route minimaps;
`CoverageTracker.updateWithFrustum` path is exercised when heading exists.

### B. AR fog on camera in env scan

`ArEnvScanOverlay` gains `updateFog(cellCenters: List<Coordinates>)`
mirroring `ArRouteOverlay.updateFog` (same cover-plane rendering via the
native surface's `updateFogCells`; SceneKit fallback path mirrors route's).
Feed: SpotScreen computes uncredited cells within ~30 m of the user from
`CoverageTracker` (grid minus `scannedCells`, same cell math) each time
location or scannedCells change. Fog planes vanish as cells credit —
dissipating fog on the camera. Fog respects `hasGeoRef` (no geo-ref → no fog,
consistent with vector cells; degraded state covered in F).

### C. Minimap parity

`SpotScanCapture` gains and forwards `fogCellCenters`, `freshCellCenters`,
`staleCellCenters` (and guidance target, see E) to `CameraCapture`.
SpotScreen's scanner branch passes the already-computed sets. The env minimap
then shows: dark veil punched by fog-context + fresh + session cells, dim
prior-coverage wash, vivid green session fill — same reading as route.

### D. Ground grid ↔ minimap alignment

Grid texture on detected planes is oriented to **world axes** (yaw 0 in AR
world space = compass north/east under `gravityAndHeading`) instead of the
plane anchor's arbitrary yaw, and the tile size changes 0.5 m → **1.5 m**
(clean divisor of the 3 m cell) in both renderers
(`GccArNative.swift` `gridTileMeters`, SceneKit `GRID_TILE_METERS`). Texture
offset anchored so grid lines land on the geo cell lattice where a geo-ref
exists (best-effort: phase the repeat texture by the fractional offset of the
nearest cell corner in world space; if no geo-ref, world-axis alignment
alone). Residual compass error rotating AR vs minimap is a known limit;
ARGeoAnchor adoption stays deferred (PR #85 note).

### E. Directional steering

- **Frontier picker** (commonMain, pure function + small state in
  SpotScreen/VM): nearest cluster of uncredited cells within the fog radius;
  recomputed when scannedCells/location change; hysteresis so the target
  doesn't flap (keep current target until credited or a new cluster is ≥30%
  closer).
- **Minimap ring**: `ScanMinimapOverlay` already supports `guidanceTarget` —
  thread it through SpotScanCapture → CameraCapture.
- **AR arrow**: `ArEnvScanOverlay` exposes `setGuidanceTarget(lat,lng)` /
  `clearGuidanceTarget()` using the native surface's existing guidance-target
  API (camera-anchored arrow re-aimed per frame); SceneKit fallback mirrors
  route's arrow.
- **Idle nudge**: if outdoors + tracking normal and no new cell credited for
  ~8 s, status chip switches to "Turn toward the highlighted area".
- **Coaching**: add `ARCoachingOverlayView` on the RealityKit path (Swift),
  matching the SceneKit fallback, hidden once grid/plane acquired (existing
  suppression rules apply).

### F. Honest degraded states + 3D scan clarity

- When location accuracy stays >20 m (geo-ref cannot pair,
  `MAX_GEO_REF_ACCURACY_METERS`), status chip shows
  "GPS too weak to anchor the scan — move to open sky" instead of silent
  nothing. Clears as soon as a usable fix pairs.
- One-time caption under the "◧ 3D scan" pill: "Measure a large pile in 3D"
  (dismisses after first tap or ~6 s; simple remember flag, no persistence
  needed this round).

## Data flow (after)

CLLocationManager → single delegate → LocationProvider StateFlows
(location, headingDegrees) → SpotScreen:
- location+heading → CoverageTracker.updateWithFrustum → scannedCells
- scannedCells + grid → fog set (uncredited ≤30 m) + frontier target
- → ArEnvScanOverlay: updateScannedCells / updateFog / setGuidanceTarget
- → SpotScanCapture → CameraCapture → GccMap(gpsHeading) +
  ScanMinimapOverlay(scan/fog/fresh/stale, guidanceTarget)

## Error handling

- No geo-ref: vector cells + fog + AR arrow suppressed (existing behavior for
  cells), F-state chip explains why.
- Heading unavailable (rare: simulator, compass interference): coverage loop
  falls back to `updatePosition` as today; cone hidden; steering still shows
  minimap ring (position-based).
- Gate not OUTDOOR: crediting stays blocked (unchanged); fog still renders
  once geo-ref exists so the scene doesn't look dead, but nothing dissipates
  until capture is allowed.

## Testing

- commonTest (composeApp — shared's commonTest is broken): frustum vs disk
  crediting delta, fog-set computation (grid minus credited, radius bound),
  frontier picker (nearest cluster, hysteresis), 1.5 m divides 3 m lattice
  phase math.
- Sim: minimap layers + cone layer present (cone rotation needs device),
  fog planes render, no regression on route scan.
- Device walk test (Blake): cone rotation, fog dissipation while sweeping,
  grid/minimap visual registration, steering arrow sanity, degraded-GPS chip.

## Out of scope

ARGeoAnchor adoption (deferred, tracked in PR #85 body); Android-native
parity beyond keeping commonMain compiling; persistence for the 3D-scan
caption; server/ledger changes (crediting stays local-first as today).
