# Litter Detection + AR Scan Feedback Implementation Plan (Track 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On-ground litter is detected geometrically (LiDAR ground-anomaly), vetted by a silent exclusion classifier, scanned with a reticle + fill-bar → 1.5 s complete-icon AR treatment, and persisted with a real LiDAR-derived weight to the map and live minimap.

**Architecture:** Swift does pixel work only (depth grid → candidate blobs, cropped Vision classification, pooled reticle entities); Kotlin owns every decision (candidate lifecycle, exclusion rules, dwell/tap intent, weight estimation) in pure commonMain objects that commonTest can exercise. Data crosses the bridge as string packets on the established per-tick drain idiom.

**Tech Stack:** Swift/ARKit/RealityKit/Vision (iosApp), Kotlin Multiplatform + kotlinx.serialization (shared/composeApp), kotlin.test (commonTest/desktopTest).

**Spec:** `docs/superpowers/specs/2026-07-14-litter-detection-ar-feedback-design.md` (decisions: dwell ≥1.8 s; exclusions silent in AR, flight-recorder-only).

## Global Constraints

- Branch from **current origin/main** (`8a1435d4`+), which already has Track 1: `ArNativeSurface.drainCaptureDiagnostics()`/`forwardCaptureDiagnostics`, `enqueueDiagnostic` on the recorder, `captureVideoDegraded`, thermal tiering. Exploration line numbers below were read pre-Track-1 — anchor on symbols, not line numbers.
- iosApp has **NO Swift unit-test target** — every decision that can be pure Kotlin MUST be pure Kotlin (commonMain object + commonTest). Swift changes verify by sim build + flight-recorder field evidence.
- Detector cadence 2 Hz, skipped at thermal `.serious`+ (spec §1); the analysis tick already sheds Vision work at `thermalState >= 2` — the detector obeys the same guard.
- Dwell promote: `DWELL_PROMOTE_SECONDS = 1.8`, `DWELL_MAX_METERS = 2.0`, `DWELL_MAX_ANGLE_DEGREES = 8.0` — named constants in one place (Kotlin tracker).
- Exclusion: silent in AR — NO entity, NO caption for excluded candidates; every exclusion IS a flight-recorder detection event (`outcome = "excluded:<label>"`).
- Candidate blob gates (spec §1): height 0.03–0.4 m, footprint 0.05–1.0 m, range 0.5–6 m.
- Weight: `volume(w×h×d) × 0.3 × densityByClass` g/L: plastic-film 40, rigid-plastic 90, glass 300, metal-can 120, paper 80, unknown 80.
- After ANY Kotlin edit, before sim xcodebuild: `rm -rf composeApp/build/xcode-frameworks`. Check `df -g /System/Volumes/Data` before every gradle/xcodebuild run; if <3 GB free, reclaim (stale worktree builds, old DerivedData variants) or report. No pipes through tail/grep on gates. Kotlin comments never contain `/*` in bodies.
- Gates: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosSimulatorArm64`; sim `xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build`.
- PR: single branch `feat/litter-detection-ar-feedback`, draft PR at the end; Task 1 is also cherry-pickable alone if a hotfix build is wanted.

---

### Task 1: Tap telemetry at the source (the tap_count=0 fix)

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (`focusAt`), `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (remove phase-derived tap effect)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SessionEventLog.kt` (tap accepts an optional `state`)
- Test: `composeApp/src/desktopTest/kotlin/com/gcc/app/platform/SessionEventLogTapTest.kt` (create)

**Interfaces:**
- Consumes: `SessionEventLog.tap()` (exists), `ScanSessionTelemetry.active` (exists).
- Produces: `SessionEventLog.tap(state: String? = null)`; `ArEnvScanOverlay.focusAt` emits the event for EVERY screen tap, including suppressed ones.

**Background:** Blake's July 13 walk logged `tap_count = 0`. The `tap` event is emitted only by `LaunchedEffect(tapCandidate.phase)` in SpotScreen when it observes the transient `LOCATING` phase — Compose snapshot conflation can skip that phase entirely, repeat taps keep the phase at `LOCATING` (no re-fire), and `focusAt`'s early-return (auto confirm card up) never sets the phase at all. Fix: emit at the source of truth.

- [ ] **Step 1: Failing test** — in the new test file:

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class SessionEventLogTapTest {
    @Test
    fun tapEventCarriesOptionalState() {
        val log = SessionEventLog(sessionId = "t", directoryPath = null)
        log.tap(state = "suppressed")
        val lines = log.snapshotLines()
        assertTrue(lines.any { it.contains("\"type\":\"tap\"") && it.contains("\"state\":\"suppressed\"") })
    }
}
```

(Match the real `SessionEventLog` constructor — read `SessionEventLogTest.kt` first and copy its construction/snapshot idiom exactly; the assertion payload is what matters.)

- [ ] **Step 2:** `./gradlew :composeApp:desktopTest --tests '*SessionEventLogTapTest*'` → FAIL (no `state` param).
- [ ] **Step 3: Implement.** `SessionEventLog.tap()` becomes `fun tap(state: String? = null)` recording `ScanSessionEvent(t = elapsed(), type = "tap", state = state)`. In `ArEnvScan.kt` `focusAt`: emit on EVERY call via `ScanSessionTelemetry.active?.tap(...)` — `state = "suppressed"` on the `_trashCandidate != null` early-return path, `state = null` on the normal path (emit BEFORE the early return). Delete the `LaunchedEffect(tapCandidate.phase) { if (phase == LOCATING) sessionEventLog.tap() }` block in SpotScreen (the pill-phase events remain — only the tap emission moves).
- [ ] **Step 4:** targeted test PASS, then `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosSimulatorArm64` → green.
- [ ] **Step 5: Commit** — `fix(scan): record tap events at the source — taps were invisible to the flight recorder`.

---

### Task 2: `source`/`outcome` on detection events

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/ScanSessionEvent.kt`, `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SessionEventLog.kt` (`detection` signature), `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (the one call site passes `source = "vision"`)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/model/ScanSessionEventSourceTest.kt` (create)

**Interfaces:**
- Produces: `ScanSessionEvent.source: String? = null`, `.outcome: String? = null`; `SessionEventLog.detection(label, confidence, distanceM, source: String? = null, outcome: String? = null)`. Values used later: source ∈ `geometry|dwell|tap|vision`; outcome ∈ `accepted|excluded:<label>|timeout`.

- [ ] **Step 1: Failing test** — round-trip `ScanSessionEvent(t=1.0, type="detection", label="bottle", source="geometry", outcome="accepted")` through `ScanEventJson`, assert both fields decode; plus a legacy-JSON decode (no new keys) asserting null. Mirror the existing compact-envelope tests' style.
- [ ] **Step 2:** run targeted → FAIL. **Step 3:** add both nullable fields (compact envelope: `encodeDefaults=false` means zero wire cost when null); extend `detection(...)`; the SpotScreen element-observed call site passes `source = "vision"`. Backend `EventLogMetrics` is tolerant of unknown fields — no backend change. **Step 4:** full desktopTest + iOS compile green. **Step 5: Commit** — `feat(scan): detection events carry source and outcome`.

---

### Task 3: Swift GroundAnomalyDetector + candidate drain

**Files:**
- Create: `iosApp/iosApp/GroundAnomalyDetector.swift`
- Modify: `iosApp/iosApp/GccArNative.swift` (own a detector; `drainGroundCandidates()`, `classifyRegionLabels(...)`, `cameraForward()` bridges)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt` (interface defaults + packet parser)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/GroundCandidatePacketTest.kt` (create — parser only)

**Interfaces:**
- Produces (Swift): `final class GroundAnomalyDetector` with `func analyze(frame: ARFrame, floorY: Float, floorBounds: (minX: Float, maxX: Float, minZ: Float, maxZ: Float)) -> [String]` returning candidate packets; `RealityKitArSurface.drainGroundCandidates() -> [String]` (runs the detector at most every 0.5 s on the stored `lastFrame`, skips when `ProcessInfo.processInfo.thermalState.rawValue >= 2`, returns packets); `classifyRegionLabels(cx: Double, cy: Double, halfW: Double, halfH: Double) -> [String]` (VNClassifyImageRequest on a normalized-coords crop of `lastFrame.capturedImage`, top-10 `"identifier|confidence"`, same format as `classifySceneLabels`); `cameraForward() -> [Float]` (3 floats, −Z column of `frame.camera.transform`).
- Produces (Kotlin): `ArNativeSurface` defaults `fun drainGroundCandidates(): List<String> = emptyList()`, `fun classifyRegionLabels(cx: Double, cy: Double, halfW: Double, halfH: Double): List<String> = emptyList()`, `fun cameraForward(): List<Float> = emptyList()`; `data class GroundCandidatePacket(val id: Long, val x: Float, val y: Float, val z: Float, val widthM: Float, val heightM: Float, val depthM: Float, val distanceM: Float, val imageCx: Double, val imageCy: Double, val imageHalfW: Double, val imageHalfH: Double)`; `fun parseGroundCandidatePacket(packet: String): GroundCandidatePacket?` (pipe-separated, 12 fields, malformed → null).
- Packet format (one candidate): `"id|x|y|z|w|h|d|dist|cx|cy|hw|hh"` — all `%.4g` floats except id (Long decimal).

**Detector algorithm (Swift, complete spec):** downsample the depth map to a ≤64×48 grid; for each cell with valid depth 0.5–6 m, unproject via `frame.camera.intrinsics` + transform to world; keep points with `floorBounds` XZ containment and height `y - floorY` in 0.03–0.4 m; 4-connected component labeling on the kept grid cells (single-pass union-find, ≤3072 cells); per component compute world AABB → w/h/d, centroid, distance, projected image-space bbox center/half-extent (normalized); reject components whose XZ footprint is outside 0.05–1.0 m; id = spatial hash `(round(cx*2) * 73856093) xor (round(cz*2) * 19349663)` on world centroid halved-meters (stable across re-observations). Cap 8 candidates per analyze, nearest-first. NO filtering beyond geometry — Kotlin decides everything else.

- [ ] **Step 1: Failing parser test (Kotlin)** — `parseGroundCandidatePacket("42|1.0|0.1|2.0|0.2|0.15|0.2|2.3|0.5|0.6|0.05|0.04")` asserts every field; malformed (11 fields, non-numeric) → null.
- [ ] **Step 2:** targeted desktopTest FAIL → **Step 3:** implement parser + interface defaults (Kotlin) and the full Swift detector + bridges per the algorithm above. `drainGroundCandidates()` reads `lastFrame` under `frameLock` and the floor AABB fields the surface already maintains (`floorMinX/floorMaxX/floorMinZ/floorMaxZ`, `floorWorldY`); returns `[]` when no floor yet. **Step 4:** desktopTest + iOS compile + framework purge + sim xcodebuild → green. **Step 5: Commit** — `feat(scan): LiDAR ground-anomaly detector proposes on-ground candidates`.

---

### Task 4: Kotlin candidate lifecycle — GroundCandidateTracker + exclusion rules

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/GroundCandidateTracker.kt`, `composeApp/src/commonMain/kotlin/com/gcc/app/platform/GroundExclusionRules.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/GroundCandidateTrackerTest.kt`, `GroundExclusionRulesTest.kt` (create)

**Interfaces:**
- Produces: `object GroundExclusionRules { const val EXCLUSION_CONFIDENCE = 0.45f; fun exclusionLabelFrom(labels: List<ClassificationObservation>): String?; fun trashTypeFrom(labels: List<ClassificationObservation>): String? }` — exclusion vocabulary: bench, pole/street light/lamp, sign, hydrant, mailbox, bollard, planter, plant/bush/tree/grass, curb/step, car/tire/wheel, animal (match by identifier substring, case-insensitive, confidence ≥ 0.45); trash vocabulary: bottle, cup, can, bag, paper, wrapper, box, carton (else null → caller uses `"Unknown litter"`).
- Produces: `class GroundCandidateTracker(val nowMs: () -> Long)` — pure state machine, no platform deps:
  - `fun ingest(packets: List<GroundCandidatePacket>)` — upsert by id (re-observation refreshes position/dims/lastSeenMs; counts an observation angle bucket).
  - `fun candidatesAwaitingClassification(): List<GroundCandidatePacket>` (state PROPOSED, ≤2 per tick)
  - `fun recordClassification(id: Long, exclusionLabel: String?, trashType: String?)` — exclusionLabel≠null → state EXCLUDED (terminal, silent); else state SCANNING with `itemType = trashType ?: "Unknown litter"`.
  - `fun recordDwell(cameraPos: FloatArray, cameraForward: FloatArray, nowMs: Long)` — accumulates dwell on the one candidate within `DWELL_MAX_METERS`=2.0 and `DWELL_MAX_ANGLE_DEGREES`=8.0 of the forward ray; ≥`DWELL_PROMOTE_SECONDS`=1.8 s continuous → PROPOSED candidates skip classification-wait and go straight to SCANNING (classification still runs and may retro-exclude silently).
  - `fun adoptTap(worldX: Float, worldY: Float, worldZ: Float): Long?` — nearest candidate within 1.0 m → immediately SCANNING (user intent), returns id.
  - `fun progress(id: Long): Float` — scan evidence 0..1 = 0.4×min(angleBuckets/3,1) + 0.4×min(closeFrames/4,1) + 0.2×min(observations/6,1); closeFrame = re-observation at <2 m.
  - `fun completedCandidates(): List<CompletedCandidate>` — progress ≥ 1.0, emitted once; `data class CompletedCandidate(id, itemType, x, y, z, widthM, heightM, depthM, imageCx, imageCy, imageHalfW, imageHalfH)`.
  - `fun timedOut(): List<Long>` — SCANNING not re-observed for 20 s → TIMEOUT (terminal), emitted once.
  - `fun activeScanning(): List<Pair<Long, Float>>` — (id, progress) for reticle rendering.

- [ ] **Step 1: Failing tests** — exclusion: bench@0.6 → "bench"; bench@0.3 → null; bottle@0.5 → trashType "bottle"; nothing → null/null. Tracker: ingest→PROPOSED; recordClassification(exclusion) → never appears in activeScanning/completed; dwell 1.7 s → not promoted, 1.9 s → promoted; progress reaches 1.0 after 3 angle buckets + 4 close frames; completedCandidates emits exactly once; timeout after 20 s silence. Use an injected `nowMs` lambda — no real clocks.
- [ ] **Step 2:** targeted FAIL → **Step 3:** implement both files (tracker internal states: PROPOSED/SCANNING/EXCLUDED/COMPLETED/TIMED_OUT; angle bucket = 60° sector of approach bearing around the candidate). **Step 4:** full desktopTest + iOS compile green. **Step 5: Commit** — `feat(scan): ground-candidate lifecycle tracker + silent exclusion rules`.

---

### Task 5: Pooled AR reticle + fill bar + complete icon (Swift)

**Files:**
- Modify: `iosApp/iosApp/GccArNative.swift` (reticle pool on `RealityKitArSurface`), `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt` (interface defaults)

**Interfaces:**
- Produces (Swift + Kotlin defaults no-op): `func setScanReticle(id: Long, x: Float, y: Float, z: Float, progress: Float)` — acquires a pooled entity for id (pool of 3; LRU-evict oldest when full), positions it, updates the fill; `func completeScanReticle(id: Long)` — swap to complete-icon material, hold 1.5 s (`DispatchQueue.main.asyncAfter`), dissolve (shrink-out like `dissolveGuidanceBeacon`), release to pool; `func clearScanReticle(id: Long)` — immediate dissolve + release (timeout/exclusion path — though exclusions never acquire one).
- Visuals, following the beacon pattern exactly (`beacon()`/`addPulse`/`animateIn`, main-queue mutations, PBM materials, iOS-17-safe transform animations): reticle = flat ring `generatePlane` entity tinted white α0.9 sitting 2 cm above the candidate; fill bar = a second thin plane child whose `scale.x` = progress (anchored left by offsetting x by `(1-progress)*halfWidth`); complete icon = ring tint swaps to green + a brief 1.15× pulse. All entities created once at pool init, `isEnabled` toggled; torn down in `teardown()` beside the beacon.

- [ ] **Step 1:** Implement the pool + three methods per above (no Swift tests possible — the Kotlin defaults keep every other platform compiling).
- [ ] **Step 2:** iOS compile + framework purge + sim xcodebuild → BUILD SUCCEEDED. **Step 3: Commit** — `feat(scan): pooled AR reticle with fill bar and scan-complete treatment`.

---

### Task 6: Tick integration — drive detector → tracker → reticles

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (expect: listener setter), `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (tick wiring), android/desktop actuals (no-op setter)

**Interfaces:**
- Consumes: Tasks 3–5 surface methods; Task 4 tracker; Task 2 detection events.
- Produces: `ArEnvScanOverlay.setGroundCandidateCompletedListener(listener: ((CompletedCandidate) -> Unit)?)` (expect + 3 actuals, pattern-copy of `setElementObservedListener`); the iOS overlay owns one `GroundCandidateTracker(nowMs = { epochMillis() })`.

**Tick wiring (inside `analyzeTick`, after the existing capture/diag drains, all behind the same thermal shed guard):**
1. `tracker.ingest(native.drainGroundCandidates().mapNotNull(::parseGroundCandidatePacket))`
2. For ≤2 `candidatesAwaitingClassification()`: `val labels = parseSceneLabels(native.classifyRegionLabels(c.imageCx, c.imageCy, c.imageHalfW * 1.15, c.imageHalfH * 1.15))`; `tracker.recordClassification(c.id, GroundExclusionRules.exclusionLabelFrom(labels), GroundExclusionRules.trashTypeFrom(labels))`; emit detection event — excluded: `detection(label, confidence=null, distanceM=c.distanceM, source="geometry", outcome="excluded:$label")`; accepted: `outcome="accepted"`.
3. `tracker.recordDwell(native.cameraPosition(), native.cameraForward(), nowMs)` — promotions emit `detection(source="dwell", outcome="accepted")`.
4. In `processPendingFocusTap`'s raycast-hit branch: `tracker.adoptTap(hit.x, hit.y, hit.z)?.let { detection(source="tap", outcome="accepted") }` (existing tap pill flow unchanged — adoption just accelerates the candidate).
5. Render: `tracker.activeScanning().forEach { (id, p) -> native.setScanReticle(id, …candidate pos…, p) }`; `tracker.timedOut().forEach { native.clearScanReticle(it); detection(source="geometry", outcome="timeout") }`; `tracker.completedCandidates().forEach { native.completeScanReticle(it.id); groundCandidateCompletedListener?.invoke(it) }`.

- [ ] **Step 1:** implement wiring + expect/actuals. **Step 2:** full desktopTest + iOS compile + sim build green. **Step 3: Commit** — `feat(scan): ground candidates flow detector → tracker → AR reticles`.

---

### Task 7: Persistence — weight, map, live minimap, photo

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/model/LitterWeightEstimator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (listener → `addObservedItem`), `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt` (`enqueueLitterMarker` passes weight), `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt` ONLY IF `ArDetectedMarkerRequest` lives there (add `estimatedWeightGrams: Int? = null`)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/model/LitterWeightEstimatorTest.kt` (create)

**Interfaces:**
- Produces: `object LitterWeightEstimator { fun estimateGrams(itemType: String, widthM: Float, heightM: Float, depthM: Float): Int }` — `volumeLiters = w*h*d*1000`, `grams = (volumeLiters * 0.3 * densityFor(itemType)).roundToInt().coerceAtLeast(1)`; densities g/L: bag/wrapper/film → 40, bottle/cup/container → 90, glass → 300, can → 120, paper/box/carton → 80, else 80. Constants public for tuning.

**Completion flow (SpotScreen `DisposableEffect(arOverlay)` — pattern-copy the element-observed listener):** on `CompletedCandidate` → convert AR x/z to lat/lng the same way the element path does; build `ObservedSpotItem(lat, lng, itemType, domain = "litter", confidence = null, estimatedWeightGrams = LitterWeightEstimator.estimateGrams(...), estimatedWidthMeters = widthM.toDouble(), atMs = nowMs, provenance = PROVENANCE_AUTO_DETECTED)`; capture best-crop photo bytes via the existing photo path used by tap confirm (`captureFrame` seam) and call `viewModel.addObservedItem(item, photoBytes)` — the minimap glyphs, stats-chip Σweight, manifest `observedItems`, and `world_observations` ingestion all flow from that one call (verified: minimap filters `domain == null || "litter"`; the ar-detected endpoint already accepts `estimatedWeightGrams` — the client marker request just never passed it, fix that).

- [ ] **Step 1: Failing test** — bottle 0.1×0.25×0.1 m → `estimateGrams("bottle", …) == (0.0025*1000*0.3*90).roundToInt() == 675`... compute expected in-test from the constants (no magic mirror numbers); film bag → density 40 branch; unknown label → 80; degenerate 0-dims → ≥1.
- [ ] **Step 2:** FAIL → **Step 3:** implement estimator + listener wiring + weight passthrough on the marker request. **Step 4:** full desktopTest + iOS compile + sim build green. **Step 5: Commit** — `feat(scan): completed litter scans persist with LiDAR-derived weight to map and minimap`.

---

### Task 8: Whole-branch verification + PR

- [ ] **Step 1:** Full gates: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosSimulatorArm64`; framework purge; sim xcodebuild. Disk check before each.
- [ ] **Step 2:** Fresh-eyes spec walk (§1–6 + resolved decisions: 1.8 s dwell constant present exactly once; zero AR feedback on any excluded path; every candidate lifecycle event carries source/outcome).
- [ ] **Step 3:** Push `feat/litter-detection-ar-feedback`, draft PR linking the spec, PR body names the field acceptance gate: staged walk, ~10 planted items (bottles/cups/bags) on sidewalk/parkway — ≥70% get reticle + completion on a normal walking approach, zero benches/poles/signs tagged, every completed item live on the minimap and in `world_observations` with weight set; plus tap telemetry: every screen tap now yields a `tap` event (state `suppressed` when the confirm card owned it).

## Self-Review Notes

- Spec coverage: §1→Task 3, §2→Task 4 (rules) + Task 6 (crop call), §3→Tasks 1/4/6 (tap fix, dwell, adoption), §4→Tasks 5/6/7 (reticle, completion, persistence), §5→Task 7, §6→Tasks 2/6 (events) + Task 8 (acceptance). Resolved decisions (1.8 s, silent exclusions) are constants/behavior in Task 4 and wiring in Task 6.
- Out of scope honored: no server-side re-detection, no Android detector (defaults no-op), no custom CoreML.
- Type consistency: `GroundCandidatePacket` fields identical in Task 3 parser, Task 4 tracker, Task 6 wiring; `CompletedCandidate` produced by Task 4, consumed by Tasks 6/7; packet format 12 fields everywhere.
