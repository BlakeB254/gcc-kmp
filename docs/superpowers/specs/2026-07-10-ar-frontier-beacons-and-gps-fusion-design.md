# AR Frontier Beacons & VIO-Fused Scan Position — Design

Status: NOT YET APPROVED for implementation (design-only follow-up to the
env-scan punchlist branch). Owner: Blake, approved in principle 2026-07-10.

## Problem

Two gaps remain in the environment scan's AR experience once the punchlist
branch lands:

1. **No on-ground focus cue.** The scan already steers the worker via
   `EnvScanGuidance.pickFrontier`/`pickTarget` (minimap ring + camera arrow),
   but nothing is planted IN the world at the target cell — the worker has
   to translate "arrow direction" into "which patch of ground." Blake wants
   a landmark-style beacon on the ground the user can walk to and visually
   confirm is done, without repeating the entity-count blowup that caused
   the Complete Scan crash (`RealityKitArSurface.teardown()` kdoc,
   `iosApp/iosApp/GccArNative.swift:146-153`: "potentially 750+ [entities] on
   a large scan — still anchored in the scene holding GPU resources").
2. **Minimap position is raw-GPS-only between fixes.** `SpotScreen.kt:1090-1091`
   feeds the minimap's `userLat`/`userLng` straight from
   `locationProvider.currentLocation`, which only updates on the OS's GPS
   cadence (seconds apart, worse indoors-adjacent). ARKit's visual-inertial
   odometry already runs at 60fps for tracking — its relative motion is
   smoother and often more precise than consecutive GPS fixes, and is
   currently thrown away except at the instant of `updateGeoReference`.

Both are explicitly follow-ups: this doc specs them for a later session, it
does not implement either.

## Goals

- G1: A single, reused ground beacon that marks the current frontier target,
  visible without adding meaningfully to per-scan entity count.
- G2: The beacon must dissolve automatically the instant its target
  neighborhood earns real credit — it should never linger over emptied fog.
- G3: A smoother, VIO-assisted minimap dot between GPS fixes, with a defined,
  bounded correction so it can never drift the display further from truth
  than GPS already does.

## Non-goals

- No new entity types beyond one pooled beacon (no particles, no trail, no
  multi-beacon "field" of markers).
- No change to `pickFrontier`/`pickTarget`'s target-selection logic — beacons
  and VIO fusion are purely presentational consumers of it.
- No change to what gets *recorded* — coverage credit, litter items, and the
  server ledger keep using raw GPS/raycast geometry exactly as today; Feature
  2 is minimap-display-only in this phase.
- No `ARGeoAnchor`/compass-recalibration rework — heading bias is a known,
  deferred problem this design mitigates but does not fix.

## Design — Feature 1: AR frontier beacons

### Target source: consume, don't reimplement

`SpotScreen.kt:827-852` already recomputes a `GuidancePick` on every
`(scanCellCenters, closeCellCenters, trackerReady, currentLocation)` change,
via `EnvScanGuidance.pickTarget(current, uncredited, grazedNotClose, userLat,
userLng, coveragePercent)` (`EnvScanGuidance.kt:83-103`), which already has
the hysteresis this needs (`SWITCH_DISTANCE_RATIO = 0.7`,
`SAME_CELL_EPSILON_METERS = 1.0`) so the target doesn't flap as GPS jitters —
the beacon rides the exact same `pick.target` the arrow already uses:

```
guidanceTarget = pick?.target
if (target != null) arOverlay.setGuidanceTarget(target.first, target.second)
else arOverlay.clearGuidanceTarget()
```

`ArEnvScanOverlay.setGuidanceTarget`/`clearGuidanceTarget`
(`ArEnvScan.kt:101-105`, iosMain impl `ArEnvScan.kt:555-563`) are the native
seam that deliberately survived the green-bar/neon-quad deletion (see the
no-op comment at `ArEnvScan.kt:546-550`) — they convert the lat/lng target
through `latLngToLocal` and forward to `ArNativeSurface.setGuidanceTarget(x,
z)` (`RealityKitArSurface.swift:418-424`), which today only drives the
camera-attached arrow (`arrow()`/`aimArrow()`, lines 523-556). The beacon
plugs into this same call — no new Kotlin↔Swift bridge method, just a
second consumer of the existing `guidanceTarget: SIMD3<Float>?`.

### Entity pooling: one beacon, moved, never spawned per target

This is the load-bearing constraint. Contrast the two existing entity
patterns in `RealityKitArSurface`:

- **Anti-pattern (what NOT to do):** `fogCellEntities`/`sectorPostEntities`
  are `[String: AnchorEntity]` dictionaries — one entity per world cell,
  capped at `maxFogCells = 400` (line 80) specifically *because* per-cell
  entities don't scale. This is the shape of the original crash. Beacons
  must not become a third such dictionary.
- **Pattern to reuse:** `arrow()` (lines 523-533) is a lazy singleton —
  `if let arrowEntity { return arrowEntity }`, created once, reused for the
  session's whole lifetime, repositioned every tick in `aimArrow()`, toggled
  via `.isEnabled`. `veil()` (535-546) is the same shape. The beacon should
  be a third lazy singleton: `beacon()` creates ONE `ModelEntity` (soft ring)
  anchored to a free-standing `AnchorEntity(world:)` (not camera-attached —
  it must sit at a fixed ground point). `setGuidanceTarget(x, z)` (already
  called every frontier recompute) MOVES that single anchor's world position
  instead of creating a new one. Owner's "2-3 max" headroom is
  forward-looking for the spacing rule below — `pickTarget` returns exactly
  one target today, so the base case is one live beacon entity, torn down in
  `RealityKitArSurface.teardown()` alongside `arrowEntity`/`veilEntity`.

### Visual spec

- Soft pulsing ring, neutral/blue tint — reuse `addPulse(to:scale:duration:)`
  (lines 480-496, the `FromToByAnimation<Transform>` autoreversed scale pulse
  already driving the `EXPECTED_TRASH` marker and sector-post pulses) rather
  than a new animation primitive.
- Depth-occluded: same non-emissive, `writesToDepthBuffer`-respecting
  material approach the punchlist's matte ground grid uses (contrast the
  removed neon-emissive coverage quads, `updateVectorCells` no-op comment at
  line 258-262) — the beacon should read as sitting IN the scene.
- Fade in/out ≥400ms: reuse `animateIn`/`animateOutAndRemove` (lines
  499-517), already `duration`-parameterized (currently 0.3-0.35s default);
  beacon call sites pass `duration: 0.4`+ explicitly.
- **Never red/orange** (litter-language constraint) — every existing marker
  color is already semantically loaded (`addMarker`'s green/orange kind=0/1,
  the orange veil/sector posts). Use a cool blue/cyan no other entity uses,
  e.g. `UIColor(red: 0.3, green: 0.6, blue: 1.0, alpha: 0.7)`.
- No particles — a flat ring mesh (`generatePlane`/thin torus, matching the
  existing `placeMarker`/`addMarker` mesh vocabulary); `ParticleEmitterComponent`
  is unused anywhere in this file today — no precedent, no budget for one.

### Dissolve trigger

Blake's phrasing — "disappear when the area is properly scanned," "CLOSE or
GOOD quality" — maps onto one gate already in `CoverageTracker.kt`, not two:
`coverageQualityForDistanceGated` (`CoverageTracker.kt:65-68`) only allows a
raycast hit to credit `CoverageQuality.CLOSE` "when `deepAllowed` (scan
quality was GOOD at credit time)", where `deepAllowed` is
`ScanQualityScorer.meetsDeepScanQuality(signal) == ScanQualityTier.GOOD`
(`ScanQualityScorer.kt:71-72`). So the beacon's dissolve condition is: **the
target cell (or its immediate corridor neighborhood, to tolerate the target
being re-picked a cell over by hysteresis) reaches `CoverageQuality.CLOSE`
in `creditedCells`.**

Concretely: in the same `LaunchedEffect(scanCellCenters, closeCellCenters,
…)` block that recomputes `guidanceTarget` (`SpotScreen.kt:827`), check
whether `guidanceTarget`'s cell (via `tracker.cellForLatLng`) — or any cell
within one grid step — is now `CLOSE` in `creditedCells`. If so, call a new
`arOverlay.dissolveBeacon()` that fades the ring out via
`animateOutAndRemove` before `pickTarget` re-picks the next target, so the
fade always precedes the jump rather than teleporting. This is a tighter bar
than the arrow's own steering handoff (which moves off a target the moment
something closer appears, independent of credit) — the beacon should reward
"you actually finished this patch," not just "guidance moved on."

### Positioning constraints

- **≤15m from the AR geo-reference:** `latLngToLocal` (`ArGeoMath.kt:28-35`)
  is only trustworthy near the reference point — the further a target is
  from `(refLat, refLng)`, the more a heading/compass bias error (Feature
  2's risk) visibly misplaces it. 15m is much tighter than the raycast fan
  ceiling (`CoverageTracker.SEEN_RANGE_METERS = 55.0`): a misplaced beacon
  is worse than an absent one. Farther targets skip beacon placement for
  that tick (arrow keeps working; only the beacon suppresses).
- **Yields to tap confirmation:** while `TapCandidateState.phase ==
  TapPhase.AWAITING_CONFIRMATION` (`TapCandidateState.kt:12`, which already
  drives the bottom pill/reticle at `TapCandidateState.kt:92-106` and
  `SpotScreen.kt:1108-1111`), hide the beacon (`beacon().isEnabled = false`,
  same pattern `clearGuidanceTarget()` uses); restore on
  `Confirmed`/`Rejected`/`Dismissed`.
- **≥10m spacing, if ever multiple:** `pickTarget` returns exactly one
  target today, so this has no live trigger — forward-looking guidance for a
  future multi-target picker, which must enforce ≥10m separation itself.

## Design — Feature 2: VIO-fused minimap position

### Current state

`SpotScreen.kt:1090-1091` passes `currentLocation?.lat`/`currentLocation?.lng`
(raw `LocationProvider.currentLocation`, CoreLocation-driven, already at
`kCLLocationAccuracyBestForNavigation` + `CLActivityTypeFitness` per #94)
straight to the minimap. The AR geo-reference pairs on every *usable* GPS fix
(`SpotScreen.kt:344-352`: `locationProvider.locationFlow.collect { … if
(isGeoRefFixUsable(...)) arOverlay.updateGeoReference(loc.lat, loc.lng) }`),
which snapshots `refX/refY/refZ = cameraPosition()` (`ArEnvScan.kt:480-506`)
— but between fixes nothing reads the camera's continued motion for display;
`cameraPosition()` (`ArEnvScan.kt:1478-1484`) is only sampled at
`updateGeoReference` and candidate-confirm time today, never on a display tick.

### Fusion approach

1. **Per-tick AR-local delta.** Add a periodic sample (precedent: the
   ScanScore steadiness accumulator, `SpotScreen.kt:353-358`, "sampled
   once/sec so a steady session doesn't recompose per sample" — a
   non-Compose sampler outside the recomposition path). Each sample reads
   `cameraPosition()` and computes `(dx, dz)` since the last sample.
2. **Map the delta through the live geo-reference, inverted.** This is
   exactly `localToLatLng` (`ArGeoMath.kt:38-45`), the documented "exact
   inverse of `latLngToLocal`, using the SAME geo reference" — already used
   for raycast-hit resolution (`ArEnvScan.kt:637, 670-673, 1091, 1169`).
   Feed the current `(x, z)` camera position through `localToLatLng(x, z,
   refLat, refLng, refX, refZ, lngScale)` for a VIO-projected lat/lng,
   reusing the same fields `updateGeoReference` already maintains — no new
   native-side state, lives in `ArEnvScanOverlay` (iosMain).
3. **Complementary filter at each new GPS fix.** On every fix that reaches
   `updateGeoReference` today, instead of hard-snapping the displayed
   position, blend: `displayedLatLng = 0.9 * vioProjectedLatLng + 0.1 *
   gpsFixLatLng` (weights per Blake's ask; tunable — see Risks). This keeps
   AR-smooth motion dominant between fixes while still pulling toward ground
   truth so VIO drift never compounds unbounded.
4. **Divergence snap.** If `distance(vioProjectedLatLng, gpsFixLatLng) >
   locationProvider.horizontalAccuracyMeters.value` (the same accuracy
   signal `isGeoRefFixUsable` already reads, `SpotScreen.kt:326,347`) at a
   new fix, skip the blend and hard-snap to the GPS fix — treat it as a
   mispair, not an incremental correction toward a biased frame. Reuses the
   accuracy-radius concept already gating geo-ref re-anchoring
   (`ArGeoMath.kt:11-21`, `MAX_GEO_REF_ACCURACY_METERS`) as a snap threshold.
5. **Consumption:** a new `fusedLocation` StateFlow feeds the minimap's
   `userLat`/`userLng` (`SpotScreen.kt:1090-1091`) instead of raw
   `currentLocation`. Everything else — `CoverageTracker.updatePosition`,
   coverage-event stampers, the server payload — keeps consuming raw
   `currentLocation`/GPS exactly as today (display-only in this phase).

### Compass/heading bias risk and mitigation

`ARWorldTrackingConfiguration.worldAlignment = .gravityAndHeading`
(`GccArNative.swift:105`, "Compass-true axes — the Kotlin geo-ref math (east
= +X, north = -Z) depends on this exactly like the SceneKit sessions") ties
the AR-local frame's north to the device compass at session start/re-pair.
Compass bias (magnetic interference, an uncalibrated compass) rotates the
whole AR-local frame relative to true north, so VIO deltas mapped through
`localToLatLng` drift in a *consistent rotational direction*, not randomly —
the failure mode a deferred `ARGeoAnchor` adoption would fix at the OS
level. Mitigations in its absence:

- The complementary filter's GPS pull (step 3) bounds how far this can
  compound between any two fixes — a correction on a timescale of
  single-digit seconds, not minutes.
- The divergence snap (step 4) is the hard backstop: a heading bias large
  enough to matter eventually pushes the VIO-projected point outside the GPS
  accuracy radius, triggering a hard re-pair like a bad GPS fix would.
- Open question: re-derive `lngScale`/re-snap more eagerly if
  `locationProvider.headingDegrees` (already tracked, `SpotScreen.kt:325`)
  jumps by more than a few degrees between samples — a cheap proxy for "the
  compass frame just moved" without waiting for the accuracy-radius snap.

## Delivery plan sketch

### Feature 1 (beacons)
1. Add `beacon()` lazy singleton + `showBeacon(x,z)`/`hideBeacon()` to
   `RealityKitArSurface`, wired off the existing `setGuidanceTarget`/
   `clearGuidanceTarget` calls (reuse `addPulse`/`animateIn`/
   `animateOutAndRemove`); no new `ArNativeSurface` protocol method needed if
   piggybacked on guidance-target calls, or one new `dissolveBeacon()` if the
   credit-triggered fade needs distinguishing from a target-changed fade.
2. Wire the CLOSE-credit dissolve check into the
   `LaunchedEffect(scanCellCenters, closeCellCenters, trackerReady,
   currentLocation)` block in `SpotScreen.kt` (~line 827), gated by the
   15m-from-geo-ref cap and `tapCandidate.phase != AWAITING_CONFIRMATION`.
3. Manual on-device verification (simulator can't exercise ARKit
   camera+depth): one beacon entity persists across multiple target changes
   (no entity-count growth), fades correctly on both target-change and
   credit-dissolve, stays hidden during tap confirmation.

### Feature 2 (VIO fusion)
1. Add the per-tick `cameraPosition()` sampler + `localToLatLng`-projected
   position and complementary-filter blend inside `ArEnvScanOverlay` (iosMain
   `ArEnvScan.kt`), exposed as a new `StateFlow<Coordinates?>` (mirrors the
   existing `trackingState`/`groundDetected` shape on the `expect class`).
2. Swap the minimap's `userLat`/`userLng` source at `SpotScreen.kt:1090-1091`
   to the fused flow, falling back to raw `currentLocation` when null (no AR
   overlay, Android/Desktop, or pre-first-geo-ref).
3. Field-test divergence-snap behavior walking indoor-adjacent → outdoor
   transitions and near reflective/magnetic surfaces (parking structures,
   steel-framed storefronts) where compass bias is worst.

## Risks

- **Beacon entity churn.** If `setGuidanceTarget` fires faster than the fade
  completes (rapid `pickTarget` flapping despite hysteresis), overlapping
  `animateIn`/`move(to:)` calls on the same singleton could stutter.
  RealityKit's `move(to:)` replaces the prior animation on the same entity by
  default — verify this holds here as it already does for `aimArrow()`.
- **VIO drift in enclosed/urban-canyon segments.** GPS multipath near
  buildings is common on outdoor litter routes. The divergence snap is the
  safety net, but a too-VIO-heavy filter weight could show a visibly wrong
  dot for seconds before the next fix corrects it. Start conservative
  (e.g. 70/30 GPS-favored) and push toward 90/10 only after field testing.
- **Per-tick sampler on a file already under performance scrutiny** (the
  punchlist branch exists partly because of AR-session resource pressure).
  Keep cadence modest (≤10Hz is plenty for minimap motion) and reuse the
  once/sec-sampler precedent's "don't recompose per sample" discipline.

## Open questions

- Exact complementary-filter split (90/10 is Blake's ask; needs field
  validation — see Risks).
- Does the beacon need its own `ArNativeSurface` protocol method, or can it
  piggyback on `setGuidanceTarget`/`clearGuidanceTarget` plus one new
  `dissolveBeacon()`? Leaning latter, but the credit-triggered fade must
  precede the next `pickTarget` swap, which may need it distinguishable from
  a plain target-changed hide.
- Should the heading-jump heuristic ship alongside the divergence snap, or
  is the snap alone sufficient for v1? Leaning toward snap-only first.
- Android/Desktop: `ArEnvScanOverlay` there is a no-op stub today (iOS-first)
  — both features stay iOS-only; no parity work implied.
