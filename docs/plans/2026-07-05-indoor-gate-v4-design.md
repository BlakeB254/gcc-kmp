# Indoor Gate v4: fast, hard indoor-in-residence detection

**Date:** 2026-07-05
**Status:** Approved design, pre-implementation
**Scope:** All three scan surfaces — environment scan (ARKit `ArEnvScanOverlay` + AVCapture fallback in `SpotScreen`), route scan (`ArRouteScreen`/`RouteScanViewModel`), spot AR (`SpotArScreen`).

## Problem

Field testing (builds 113–118) shows the outdoor gate is simultaneously too slow, too blind, and — after the build-117 override fix — too easy to disable:

1. **Camera evidence is muted by good GPS.** The 2-vote scene streak and 4-sample cue-absence flips in `OutdoorGate` only fire when no outdoor-grade (<10 m) GPS fix exists in the 15 s window. Indoors near windows, urban multipath routinely reports <10 m, so the gate never flips ("misses entirely").
2. **Scene classification is timid.** `TrashDetectionRules.sceneVoteFrom` votes INDOOR only with *zero* outdoor-keyword hits; one "tree/sky through the window" label kills the vote. Votes arrive only every 1.5–2 s and nothing runs on the first frame.
3. **Map containment barely exists.** `isInsideMappedBuilding` = 28 hardcoded axis-aligned rectangles in North Lawndale, bbox containment, no residential awareness, useless anywhere else.
4. **Free ARKit signals are unused.** Plane classification (ceiling!) and ambient light estimates are ignored.
5. **UNCERTAIN never blocks.** Capture/analysis run from scan open until INDOOR is proven, so privacy exposure equals detection latency. Compliance requirement: frames from inside private residences must never be captured or uploaded.
6. **`forceOutdoors()` pins OUTDOOR for the whole session** (build-117 field fix for a real outdoor false positive). Pressed indoors, it disables all protection for the session.

## Agreed requirements

- Any indoors blocks (residences are the motivating case, not a separate class).
- Hard block: stop frame capture/persistence and suppress credit/observations, not just show the overlay.
- Bias aggressive; the mitigation is fast auto-recovery (a few seconds) once outdoor evidence returns.
- All three scan surfaces behave identically.
- Two PRs: Phase 1 (gate v4, client-only) then Phase 2 (real building footprints).

## Phase 1 — Gate v4 (client-only PR)

### OutdoorGate changes (`composeApp/commonMain/.../platform/OutdoorGate.kt`, stays pure/testable)

- **New input `onCeilingPlane(detected: Boolean, timestampMs: Long)`.** A ceiling-classified ARKit plane above the camera flips INDOOR instantly (rank of map containment). Ceiling evidence participates in the recovery guard (no OUTDOOR until no ceiling cue for ≥5 s).
- **Un-mute camera evidence.** Remove the `outdoorTs.isEmpty()` precondition on the scene-streak and cue-absence flips. Good GPS remains outdoor *evidence* for the sustained path but is never a veto. Camera OUTDOOR votes still reset the streaks.
- **`captureAllowed: StateFlow<Boolean>` = (state == OUTDOOR).** Deny-by-default: at scan open (UNCERTAIN) the camera preview runs but nothing is persisted, detected, credited, or submittable until OUTDOOR is proven. This closes the privacy window structurally.
- **Fast recovery path.** 2 consecutive OUTDOOR scene votes AND `insideBuildingStreak == 0` AND no ceiling cue within 5 s → OUTDOOR (≈3 s), alongside the existing 5 s clean-GPS path. Recovery from INDOOR still requires being outside all footprints.
- **Override yields to strong evidence.** `forceOutdoors()` keeps pinning OUTDOOR against weak/ambiguous flips (degraded GPS, cue absence, scene streaks), but map containment or a ceiling cue clears the pin and flips INDOOR. Every press and every yield is logged (`DiagnosticsLog` tag `outdoor-gate`) and stamped into the scan manifest gate events for audit.

### Camera classification (`TrashDetectionRules`, shared common)

- **Weighted scoring** replaces binary hits: sum Vision confidences (≥0.3 each, as today) per side. INDOOR when `indoorScore ≥ 0.5 && indoorScore > 1.25 × outdoorScore`; OUTDOOR when `outdoorScore ≥ 0.5 && outdoorScore ≥ indoorScore`; NONE otherwise. Constants live beside the keyword lists and the window-view case (several indoor labels plus one outdoor label) must vote INDOOR in tests.
- **Expanded residence-interior keywords:** lamp, bed, couch, staircase, curtain, blinds, rug, cabinet, sink, toilet, mirror, door frame, fireplace, wardrobe, dresser, bathtub, shower, oven, stove, microwave, dishwasher.
- Stays in commonMain so iOS Vision and Android ML Kit share thresholds; covered by a new `TrashDetectionRulesTest`.

### Cadence and first frame

- **First-frame verdict:** both paths (`ArFrameFeedback` tick loop, `CameraManager.SceneSampleDelegate`) classify the first available frame at scan open instead of waiting a full interval.
- **Adaptive cadence:** ≈750 ms between scene votes while gate ≠ OUTDOOR; relax to current 1.5–2 s once OUTDOOR (battery/thermal). The cadence source reads the gate state (or a `fastMode` flag set by the screen).
- **Ceiling detection:** AR paths already poll `frame.anchors` in the 1 s tick (K/N cannot override ARSCNViewDelegate node callbacks). Detect `ARPlaneAnchor` horizontal planes above camera height with classification `ceiling`. `ARPlaneClassification` does not bridge to Kotlin — read via KVO `valueForKey` (known gotcha), or on the RealityKit native surface read it in Swift and pass a Bool through `ArNativeSurface`.

### Hard block and purge

- On INDOOR flip: stop capture and detection analysis; **purge frames/observations recorded in the trailing 10 s** from the in-flight session bundle (`EnvScanSessionRecorder`/`EnvScanManifest`) before any upload; suppress observation/coverage/credit recording whenever `captureAllowed == false` (today's suppression is INDOOR-only and per-surface — unify on the flow).
- While INDOOR, keep a **labels-only Vision loop** at ~2 s cadence (no frame persistence, labels never leave the device) so recovery does not depend on GPS alone. Today `setFrameAnalysisEnabled(false)` kills camera evidence entirely while INDOOR, which is why recovery is sticky.
- Block `capturePhoto`/`captureFrame` calls while `captureAllowed == false`.

### UX

- UNCERTAIN: small "Confirming you're outside…" chip; Complete Scan pill disabled; no scrim.
- INDOOR: existing `GoOutsideOverlay` + "I'm outside — continue" (evidence-yielding, audited).
- Diagnostics panel: ceiling-cue events, weighted scene scores, override presses/yields, purge events.

## Phase 2 — Real building footprints (server + client PR)

### Server (`site/`)

- New worker-authenticated route `GET /api/map/buildings?bbox=` backed by new Neon table `building_footprints` (osm_id, polygon jsonb, building_type, is_residential, fetched_at).
- On cache miss or stale tile (>30 d): fetch `way["building"](bbox)` with tags from Overpass (reuse the integration pattern in `site/src/lib/inner-boundary-generator.ts`), simplify polygons, store. `building=house|residential|apartments|detached|terrace|bungalow` or `landuse=residential` context → `is_residential`.
- Bbox capped (~1.2 km); responses paged.

### Client

- New `BuildingFootprintStore` mirroring `ScanAreaCacheStore`: SQLDelight tile cache (~550 m tiles, 30 d prune), prefetch at scan open around last-known position, refresh on >200 m movement.
- Replace `isInsideMappedBuilding` bbox check with true point-in-polygon (ray-cast helper exists in `CoverageTracker`) over cached footprints; keep the 28 hardcoded rectangles only as offline/empty-cache fallback.
- Containment in any footprint = instant INDOOR. Residential tag stamped into diagnostics and manifest gate events; **residential containment refuses to yield to the manual override** (strong-evidence rule where stakes are highest).

### Error handling

- Overpass 504 → serve cached data or an empty short-TTL tile.
- Client offline → cached tiles, then hardcoded fallback.
- Every failure degrades to camera+GPS evidence only; footprint problems never block scan start and never crash the gate. Gate evaluation stays synchronous and pure; lookups happen outside and push evidence in.

## Testing

- `OutdoorGateTest` additions: ceiling-cue instant flip; scene streak flips despite <10 m GPS; UNCERTAIN blocks `captureAllowed`; 2-OUTDOOR-vote fast recovery; override yields on containment/ceiling but survives weak flips; purge-window bookkeeping.
- New `TrashDetectionRulesTest`: weighted scoring incl. the window-view case.
- `BuildingFootprintStore`: tile keys, prune, point-in-polygon.
- Site route test with mocked Overpass (hit, stale, 504).
- Sim pass with `set_sim_location` inside/outside footprints (chip → unlock; containment → overlay). Final validation: device walk test — porch, window-adjacent indoor, frame-house interior — same protocol as prior gate rounds.

## Sequencing and risks

- PR 1 (Phase 1) is independently valuable and entirely client-side. PR 2 upgrades `onMapEvidence` quality. Both touch iosMain → local iOS link-check before merge (CI cannot see iosMain).
- Risk: aggressive UNCERTAIN-blocks-capture could annoy legit users if outdoor proof is slow — mitigated by first-frame classification + 750 ms cadence + GPS path (expected unlock <2 s outdoors).
- Risk: porch/awning false INDOOR from footprint GPS noise — accepted per requirements; mitigations are ~3 s auto-recovery and the audited override.
- Risk: Vision label drift across iOS versions — weighted scoring is less brittle than zero-outdoor-hits, and thresholds live in shared code under test.
