# Indoor Gate v4 — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the indoor/outdoor gate flip fast and reliably indoors (ceiling cue, un-muted camera evidence, first-frame verdict), make UNCERTAIN deny capture by default, purge trailing indoor data, and make the manual override yield to strong evidence — on all three scan surfaces.

**Architecture:** `OutdoorGate` (pure commonMain state machine) gains new inputs/outputs; shared `TrashDetectionRules` gets weighted scoring; iosMain overlays (`ArEnvScan`, `ArFrameFeedback` used by `SpotArOverlay`/`ArRouteOverlay`, `CameraManager`) feed new signals faster; screens consume a new `captureAllowed` flow instead of `!isIndoor`. One Swift touch: `ceilingDetected()` on the RealityKit surface.

**Tech Stack:** Kotlin Multiplatform (commonMain/iosMain/commonTest), ARKit via K/N cinterop, Vision, Compose Multiplatform, Swift (`iosApp/iosApp/GccArNative.swift`).

**Spec:** `docs/plans/2026-07-05-indoor-gate-v4-design.md` (approved). Phase 2 (OSM footprints) is a separate plan/PR.

## Global Constraints

- Work in worktree `.claude/worktrees/indoor-gate-v4`, branch `indoor-gate-v4`. Never push to main.
- Test command (commonTest runs on desktop target): `./gradlew :composeApp:desktopTest` from repo root of the worktree.
- After ANY iosMain/Swift change: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` must succeed (CI cannot compile iosMain — this is the merge gate).
- If a sim UI check is needed: purge `composeApp/build/xcode-frameworks` first (stale framework = old UI).
- ARKit `ARPlaneClassification` raw values (KVO via `valueForKey("classification")`, pattern already in `ArEnvScan.kt:576`): none=0, wall=1, floor=2, ceiling=3, table=4, seat=5, window=6, door=7.
- New gate constants (verbatim from spec): `OUTDOOR_SCENE_STREAK_TO_RECOVER = 2`, `CEILING_RECENT_MS = 5_000L`, `PURGE_WINDOW_MS = 10_000L`, fast scene cadence ≈750 ms while not OUTDOOR, labels-only cadence ≈2 s while INDOOR.
- Weighted scene scoring (verbatim from spec): INDOOR when `indoorScore >= 0.5f && indoorScore > 1.25f * outdoorScore`; OUTDOOR when `outdoorScore >= 0.5f && outdoorScore >= indoorScore`; else NONE. Confidence floor per observation stays `SCENE_CONFIDENCE = 0.3f`.
- Android actuals for any new expect members are no-ops (Android is not launching; keep it compiling).
- Commit after every task with the messages given; end by pushing branch + draft PR.

---

### Task 1: OutdoorGate v4 core

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/OutdoorGate.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/OutdoorGateTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (later tasks rely on these exact signatures):
  - `val captureAllowed: StateFlow<Boolean>` — true only when state == OUTDOOR.
  - `fun onCeilingPlane(detected: Boolean, timestampMs: Long)`
  - `val lastIndoorFlipAtMs: Long?` — timestamp of the most recent transition *to* INDOOR (null if never).
  - Constants: `OutdoorGate.OUTDOOR_SCENE_STREAK_TO_RECOVER`, `OutdoorGate.CEILING_RECENT_MS`, `OutdoorGate.PURGE_WINDOW_MS`.

- [ ] **Step 1: Write the failing tests** (append to `OutdoorGateTest.kt` as a new class in the same file, matching existing style — plain `kotlin.test`, explicit timestamps `t0 + n * 1000L`):

```kotlin
class OutdoorGateV4Test {

    private val t0 = 1_000_000L

    @Test
    fun captureDisallowedWhileUncertain() {
        val gate = OutdoorGate()
        assertEquals(false, gate.captureAllowed.value)
    }

    @Test
    fun captureAllowedOnceOutdoor() {
        val gate = OutdoorGate()
        for (i in 0..6) gate.onAccuracySample(5.0, t0 + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
        assertEquals(true, gate.captureAllowed.value)
    }

    @Test
    fun ceilingPlaneFlipsIndoorInstantly() {
        val gate = OutdoorGate()
        gate.onCeilingPlane(true, t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        assertEquals(false, gate.captureAllowed.value)
    }

    @Test
    fun sceneStreakFlipsDespiteGoodGps() {
        val gate = OutdoorGate()
        // Outdoor-grade GPS in the window must no longer veto camera evidence.
        gate.onAccuracySample(5.0, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 500L)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1500L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun twoOutdoorVotesRecoverFromIndoorFast() {
        val gate = OutdoorGate()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // ~3s later the camera sees street twice — recover without 5s clean GPS.
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 3000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 4000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun outdoorVoteRecoveryBlockedByRecentCeiling() {
        val gate = OutdoorGate()
        gate.onCeilingPlane(true, t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 1000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 2000L)
        // Ceiling seen 2s ago (< CEILING_RECENT_MS) — stay INDOOR.
        assertEquals(OutdoorState.INDOOR, gate.state.value)
        // Once the ceiling is stale, the same streak recovers.
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 6000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 7000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun outdoorVoteRecoveryBlockedWhileMapContained() {
        val gate = OutdoorGate()
        gate.onMapEvidence(insideBuilding = true, timestampMs = t0)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 1000L)
        gate.onSceneVote(SceneVote.OUTDOOR, t0 + 2000L)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun overrideSurvivesWeakEvidence() {
        val gate = OutdoorGate()
        gate.forceOutdoors()
        gate.onSceneVote(SceneVote.INDOOR, t0)
        gate.onSceneVote(SceneVote.INDOOR, t0 + 1000L)
        for (i in 0..6) gate.onAccuracySample(40.0, t0 + 2000L + i * 1000L)
        assertEquals(OutdoorState.OUTDOOR, gate.state.value)
    }

    @Test
    fun overrideYieldsToMapContainment() {
        val gate = OutdoorGate()
        gate.forceOutdoors()
        gate.onMapEvidence(insideBuilding = true, timestampMs = t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun overrideYieldsToCeilingPlane() {
        val gate = OutdoorGate()
        gate.forceOutdoors()
        gate.onCeilingPlane(true, t0)
        assertEquals(OutdoorState.INDOOR, gate.state.value)
    }

    @Test
    fun lastIndoorFlipTimestampRecorded() {
        val gate = OutdoorGate()
        assertEquals(null, gate.lastIndoorFlipAtMs)
        gate.onCeilingPlane(true, t0)
        assertEquals(t0, gate.lastIndoorFlipAtMs)
    }
}
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.OutdoorGateV4Test" 2>&1 | tail -20`
Expected: compilation FAILURE (`captureAllowed`, `onCeilingPlane`, `lastIndoorFlipAtMs` unresolved).

- [ ] **Step 3: Implement OutdoorGate v4.** Apply these changes to `OutdoorGate.kt`:

Add constants to the companion:

```kotlin
        /** Consecutive camera OUTDOOR votes that recover from INDOOR without
         *  waiting for 5s of clean GPS (fast auto-recovery — the mitigation
         *  that makes aggressive flipping acceptable). */
        const val OUTDOOR_SCENE_STREAK_TO_RECOVER = 2
        /** A ceiling plane seen within this window blocks recovery. */
        const val CEILING_RECENT_MS = 5_000L
        /** On an INDOOR flip, callers purge data recorded in this trailing
         *  window (frames/observations captured while walking inside). */
        const val PURGE_WINDOW_MS = 10_000L
```

Add state fields (near the other privates):

```kotlin
    private var sceneOutdoorStreak = 0
    private var lastCeilingTs: Long? = null

    private val _captureAllowed = MutableStateFlow(false)
    /** Deny-by-default: capture/credit/persistence allowed ONLY while OUTDOOR.
     *  UNCERTAIN blocks capture (not the screen) — the privacy window closes
     *  structurally instead of racing detection latency. */
    val captureAllowed: StateFlow<Boolean> = _captureAllowed.asStateFlow()

    /** Timestamp of the most recent transition to INDOOR (purge coordination). */
    var lastIndoorFlipAtMs: Long? = null
        private set
```

Add a single state-setter and route ALL `_state.value =` writes through it (including `forceOutdoors` and `evaluate`):

```kotlin
    private fun setState(newState: OutdoorState, nowMs: Long) {
        if (newState == OutdoorState.INDOOR && _state.value != OutdoorState.INDOOR) {
            lastIndoorFlipAtMs = nowMs
        }
        _state.value = newState
        _captureAllowed.value = newState == OutdoorState.OUTDOOR
    }
```

New input — instant strong flip, clears the override (strong evidence yield):

```kotlin
    /**
     * ARKit plane evidence: a ceiling-classified plane above the camera.
     * Outdoors there is essentially never a classified ceiling — this is the
     * cheapest high-precision indoor signal and it overrides the manual pin.
     */
    fun onCeilingPlane(detected: Boolean, timestampMs: Long) {
        if (detected) {
            lastCeilingTs = timestampMs
            repeat(SCENE_VOTE_WEIGHT) { indoorTs.addLast(timestampMs) }
            if (_state.value != OutdoorState.INDOOR) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (ceiling plane detected)")
                if (manualOverride) {
                    manualOverride = false
                    DiagnosticsLog.info("outdoor-gate", "manual override cleared by ceiling plane")
                }
                setState(OutdoorState.INDOOR, timestampMs)
                return
            }
        }
        evaluate(timestampMs)
    }
```

In `onMapEvidence`, make containment clear the override the same way (replace the body of the `insideBuildingStreak >= MAP_STREAK_TO_FLIP` branch):

```kotlin
            if (insideBuildingStreak >= MAP_STREAK_TO_FLIP && _state.value != OutdoorState.INDOOR) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (GPS inside mapped building ×$insideBuildingStreak)")
                if (manualOverride) {
                    manualOverride = false
                    DiagnosticsLog.info("outdoor-gate", "manual override cleared by map containment")
                }
                setState(OutdoorState.INDOOR, timestampMs)
                return
            }
```

In `onSceneVote`:
- In the `SceneVote.OUTDOOR` branch add `sceneOutdoorStreak += 1` and the fast-recovery check; in `INDOOR` and `NONE` branches add `sceneOutdoorStreak = 0`.
- DELETE the `val gpsShowsNoOutdoor = outdoorTs.isEmpty()` guard — the streak/cue-absence flips now apply unconditionally (still respecting `manualOverride` and using `setState`):

```kotlin
            SceneVote.OUTDOOR -> {
                repeat(SCENE_VOTE_WEIGHT) { outdoorTs.addLast(timestampMs) }
                sceneIndoorStreak = 0
                noOutdoorCueStreak = 0
                sceneOutdoorStreak += 1
                // Fast auto-recovery: the camera seeing street/sky twice in a
                // row is stronger outdoor proof than urban GPS accuracy.
                val ceilingRecent = lastCeilingTs?.let { timestampMs - it <= CEILING_RECENT_MS } == true
                if (_state.value == OutdoorState.INDOOR &&
                    sceneOutdoorStreak >= OUTDOOR_SCENE_STREAK_TO_RECOVER &&
                    insideBuildingStreak == 0 && !ceilingRecent
                ) {
                    DiagnosticsLog.info(
                        "outdoor-gate",
                        "OUTDOOR (camera outdoor ×$sceneOutdoorStreak — fast recovery)",
                    )
                    // The camera saw indoors seconds ago; those stamps must not
                    // immediately re-flip via the sustained-indoor path.
                    indoorTs.clear()
                    lastSceneIndoorTs = null
                    lastIndoorAccuracyTs = null
                    setState(OutdoorState.OUTDOOR, timestampMs)
                    return
                }
            }
```

(The old fusion block becomes, without the GPS veto:)

```kotlin
        if (_state.value != OutdoorState.INDOOR) {
            if (sceneIndoorStreak >= SCENE_STREAK_TO_FLIP) {
                DiagnosticsLog.info("outdoor-gate", "INDOOR (camera indoor ×$sceneIndoorStreak)")
                if (!manualOverride) setState(OutdoorState.INDOOR, timestampMs)
                return
            }
            if (noOutdoorCueStreak >= NO_OUTDOOR_CUES_TO_FLIP && outdoorTs.isEmpty()) {
                DiagnosticsLog.info(
                    "outdoor-gate",
                    "INDOOR (no outdoor cues in $noOutdoorCueStreak samples + no outdoor-grade GPS)",
                )
                if (!manualOverride) setState(OutdoorState.INDOOR, timestampMs)
                return
            }
        }
```

NOTE: the cue-absence flip KEEPS the `outdoorTs.isEmpty()` requirement — cue absence (pointing at pavement) is genuinely ambiguous, unlike positive indoor labels. Only the positive scene-streak flip is un-muted. This is the park-bench safeguard.

In `evaluate()`:
- Replace both `_state.value = ...` writes with `setState(..., nowMs)`.
- Extend INDOOR recovery with the ceiling guard and streak reset:

```kotlin
        val ceilingRecent = lastCeilingTs?.let { nowMs - it <= CEILING_RECENT_MS } == true
        val newState = when (_state.value) {
            OutdoorState.INDOOR -> if (outdoorFlip && insideBuildingStreak == 0 && !ceilingRecent) {
                sceneIndoorStreak = 0
                noOutdoorCueStreak = 0
                sceneOutdoorStreak = 0
                OutdoorState.OUTDOOR
            } else OutdoorState.INDOOR
            ...
```

In `forceOutdoors()` change `_state.value = OutdoorState.OUTDOOR` to `setState(OutdoorState.OUTDOOR, 0L)` (0L is fine — it only stamps on transitions *to* INDOOR).

- [ ] **Step 4: Run the full gate test suite**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.OutdoorGate*" 2>&1 | tail -20`
Expected: PASS. If any PRE-EXISTING test fails, it encodes v3's GPS-veto (e.g. a test asserting camera votes do NOT flip while outdoor GPS exists): update that test to assert the v4 behavior and note it in the commit message — do not weaken v4 to satisfy v3 assertions.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/OutdoorGate.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/OutdoorGateTest.kt
git commit -m "Gate v4 core: ceiling cue, captureAllowed, un-muted camera evidence, fast recovery, yielding override"
```

---

### Task 2: Weighted scene scoring in TrashDetectionRules

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt`
- Create: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt`

**Interfaces:**
- Produces: `sceneVoteFrom(observations: List<ClassificationObservation>): SceneVote` (same signature, new semantics). No caller changes needed.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class TrashDetectionRulesTest {

    private fun obs(id: String, c: Float) = ClassificationObservation(id, c)

    @Test
    fun windowViewStillVotesIndoor() {
        // Several indoor labels + one outdoor label through the window: the
        // v3 rule (any outdoor hit kills INDOOR) was the "misses entirely" bug.
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("living_room", 0.6f), obs("sofa", 0.4f), obs("tree", 0.5f))
        )
        assertEquals(SceneVote.INDOOR, vote)
    }

    @Test
    fun streetSceneVotesOutdoor() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("street", 0.7f), obs("sidewalk", 0.5f), obs("car", 0.4f))
        )
        assertEquals(SceneVote.OUTDOOR, vote)
    }

    @Test
    fun weakSignalsVoteNone() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("lamp", 0.35f), obs("mug", 0.1f))
        )
        assertEquals(SceneVote.NONE, vote) // indoorScore 0.35 < 0.5 floor
    }

    @Test
    fun balancedSceneVotesOutdoorNotIndoor() {
        // porch: equal evidence must NOT read indoor (aggressive ≠ reckless).
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("ceiling", 0.5f), obs("yard", 0.5f))
        )
        assertEquals(SceneVote.OUTDOOR, vote) // outdoor >= indoor wins ties
    }

    @Test
    fun subConfidenceObservationsIgnored() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("kitchen", 0.29f), obs("bedroom", 0.29f))
        )
        assertEquals(SceneVote.NONE, vote)
    }

    @Test
    fun residenceInteriorKeywordsRecognized() {
        val vote = TrashDetectionRules.sceneVoteFrom(
            listOf(obs("bathtub", 0.4f), obs("mirror", 0.3f))
        )
        assertEquals(SceneVote.INDOOR, vote)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TrashDetectionRulesTest" 2>&1 | tail -15`
Expected: FAIL — `windowViewStillVotesIndoor` and `residenceInteriorKeywordsRecognized` fail under v3 logic (`bathtub`/`mirror` not in keyword set; window view returns NONE).

- [ ] **Step 3: Implement.** In `TrashDetectionRules.kt`:

Add to `INDOOR_KEYWORDS`: `"blinds", "mirror", "doorway", "fireplace", "dresser", "bathtub", "shower", "oven", "stove", "microwave", "dishwasher", "staircase"`.

Replace `sceneVoteFrom`:

```kotlin
    private const val SCENE_SCORE_MIN = 0.5f
    private const val INDOOR_DOMINANCE = 1.25f

    fun sceneVoteFrom(observations: List<ClassificationObservation>): SceneVote {
        var indoorScore = 0f
        var outdoorScore = 0f
        for (obs in observations) {
            if (obs.confidence < SCENE_CONFIDENCE) continue
            val tokens = tokensOf(obs.identifier)
            if (tokens.any { it in INDOOR_KEYWORDS }) indoorScore += obs.confidence
            if (tokens.any { it in OUTDOOR_KEYWORDS }) outdoorScore += obs.confidence
        }
        // Confidence-weighted: one "tree through the window" label no longer
        // saves an indoor scene, but ties still read outdoor (porches).
        return when {
            indoorScore >= SCENE_SCORE_MIN && indoorScore > outdoorScore * INDOOR_DOMINANCE -> SceneVote.INDOOR
            outdoorScore >= SCENE_SCORE_MIN && outdoorScore >= indoorScore -> SceneVote.OUTDOOR
            else -> SceneVote.NONE
        }
    }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TrashDetectionRulesTest" 2>&1 | tail -8`
Expected: PASS (all 6).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt
git commit -m "Weighted scene scoring + residence-interior keywords (window-view fix)"
```

---

### Task 3: Ceiling-plane cue (iosMain + Swift)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (expect class — add member)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArOverlays.kt` — IF the `SpotArOverlay`/`ArRouteOverlay` expect declarations live elsewhere, find them with `grep -rn "expect class SpotArOverlay" composeApp/src/commonMain` and edit that file.
- Modify: Android actuals for the three overlays (find with `grep -rln "actual class ArEnvScanOverlay\|actual class SpotArOverlay\|actual class ArRouteOverlay" composeApp/src/androidMain`) — no-op bodies.
- Modify: `iosApp/iosApp/GccArNative.swift`

**Interfaces:**
- Produces (screens wire these in Task 5):
  - `ArEnvScanOverlay.setCeilingListener(listener: ((Boolean) -> Unit)?)` (expect/actual; same member on `SpotArOverlay` and `ArRouteOverlay`)
  - `ArNativeSurface.ceilingDetected(): Boolean`
- Consumes: `OutdoorGate.onCeilingPlane` (Task 1).

- [ ] **Step 1: Add `ceilingDetected()` to the Kotlin interface** in `ArNativeSurface.kt`, next to `groundDetected()`:

```kotlin
    /** True when the session currently tracks a ceiling-classified plane
     *  (ARPlaneClassification.ceiling). Outdoors this is essentially never
     *  true — the OutdoorGate's strongest camera-side indoor cue. */
    fun ceilingDetected(): Boolean
```

- [ ] **Step 2: Implement it in Swift** (`iosApp/iosApp/GccArNative.swift`, inside `RealityKitArSurface` near its `groundDetected()` implementation — adding the method is REQUIRED or protocol conformance fails, which is exactly the compile-time check we want):

```swift
    func ceilingDetected() -> Bool {
        guard let frame = arView.session.currentFrame else { return false }
        return frame.anchors.contains { anchor in
            guard let plane = anchor as? ARPlaneAnchor else { return false }
            if #available(iOS 12.0, *) {
                return plane.classification == .ceiling
            }
            return false
        }
    }
```

(Existing file — no pbxproj surgery needed.)

- [ ] **Step 3: Detect ceilings on the SceneKit/frame path.** In iosMain `ArEnvScan.kt`:

Add near the other listeners: `private var ceilingListener: ((Boolean) -> Unit)? = null` and the actual member:

```kotlin
    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {
        ceilingListener = listener
    }
```

In `analyzeTick()` right after `tick += 1`, add (native and frame branches both covered; KVO pattern copied from `updatePlaneNodes`):

```kotlin
        // Ceiling cue for the OutdoorGate — checked every tick, cheap
        // (anchors are already tracked; no Vision work involved).
        val ceiling = when {
            native != null -> native.ceilingDetected()
            frame != null -> frame.anchors.filterIsInstance<ARPlaneAnchor>().any { anchor ->
                val classification = try {
                    (anchor as platform.darwin.NSObject).valueForKey("classification") as? Long ?: 0L
                } catch (_: Throwable) { 0L }
                classification == 3L // ARPlaneClassification.ceiling
            }
            else -> false
        }
        ceilingListener?.let { listener ->
            dispatch_async(dispatch_get_main_queue()) { listener(ceiling) }
        }
```

- [ ] **Step 4: Same detection in `ArFrameFeedback`** (serves `SpotArOverlay` + `ArRouteOverlay`). Add `var ceilingListener: ((Boolean) -> Unit)? = null` next to `sceneVoteListener`, and in `analyzeTick()` after `tick += 1` add the identical block (native branch uses `native.ceilingDetected()`, frame branch uses the KVO loop above). Then expose it on both overlay actuals next to their `setSceneVoteListener`:

```kotlin
    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {
        frameFeedback.ceilingListener = listener
    }
```

- [ ] **Step 5: Add the expect declarations + Android no-op actuals.** In the commonMain expect for each of `ArEnvScanOverlay`, `SpotArOverlay`, `ArRouteOverlay`:

```kotlin
    fun setCeilingListener(listener: ((Boolean) -> Unit)?)
```

Android actuals: `actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {}`.

- [ ] **Step 6: Verify everything links**

Run: `./gradlew :composeApp:desktopTest 2>&1 | tail -5` → PASS (expect/actual consistency compiles on all targets).
Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -5` → BUILD SUCCESSFUL.
Run: `cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' build 2>&1 | tail -5` → BUILD SUCCEEDED (this compiles GccArNative.swift against the new protocol — the conformance check).

- [ ] **Step 7: Commit**

```bash
git add -A composeApp/src iosApp/iosApp/GccArNative.swift
git commit -m "Ceiling-plane cue: ARKit plane classification feeds the gate on all AR paths"
```

---

### Task 4 (REVISED — Swift-first sensing): Swift Vision classification + first-frame verdict + adaptive cadence + labels-only INDOOR loop

> **Amendment 2026-07-05 (Blake):** the majority of platform sensing work must live in native Swift, not Kotlin/cinterop. Scene classification on the native-surface path now runs IN SWIFT; Kotlin keeps only policy (cadence choice, vote scoring via shared TrashDetectionRules, gate fusion). The SceneKit/AVCapture fallbacks keep their existing Kotlin Vision path as legacy.

**Files:**
- Modify: `iosApp/iosApp/GccArNative.swift` (Swift Vision classification)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArNativeSurface.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/CameraManager.kt`
- Modify: the three overlay expect declarations + Android AND desktop no-op actuals (locations already known from Task 3: grep `expect class ArEnvScanOverlay` / `SpotArOverlay` / `ArRouteOverlay` in commonMain; actuals in androidMain and desktopMain)

**Interfaces:**
- Consumes: `TrashDetectionRules.sceneVoteFrom(List<ClassificationObservation>)` (Task 2), `SceneVote`, existing `sceneVoteListener` plumbing, `ArNativeSurface.ceilingDetected()` (Task 3).
- Produces (Task 5 relies on these):
  - `ArNativeSurface.classifySceneLabels(): List<String>` — each entry `"identifier|confidence"` (e.g. `"living_room|0.83"`), top 15 by confidence, empty list when no frame or Vision error. Runs `VNClassifyImageRequest` IN SWIFT on the frameLock'd `lastFrame`.
  - `fun setSceneVoteCadence(fast: Boolean)` expect member on all three overlays (iOS actuals set the flags below; Android/desktop no-ops).
  - `ArFrameFeedback.fastSceneVotes: Boolean` (default true), same-named var on `ArEnvScanOverlay` iOS actual.
  - `CameraManager.sceneVoteFastMode: Boolean` (companion var, default true).

- [ ] **Step 1: Swift classification.** In `GccArNative.swift` (add `import Vision` at the top if absent), inside `RealityKitArSurface` next to `ceilingDetected()`:

```swift
    func classifySceneLabels() -> [String] {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return [] }
        let request = VNClassifyImageRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: frame.capturedImage, options: [:])
        guard (try? handler.perform([request])) != nil, let results = request.results else { return [] }
        return results
            .sorted { $0.confidence > $1.confidence }
            .prefix(15)
            .map { "\($0.identifier)|\($0.confidence)" }
    }
```

And the matching member on the Kotlin `ArNativeSurface` interface:

```kotlin
    /** Swift-side Vision scene classification of the current frame — top-15
     *  "identifier|confidence" entries, empty when no frame. Native path only;
     *  keeps ALL Vision work in Swift (Kotlin applies the shared scoring). */
    fun classifySceneLabels(): List<String>
```

Add a shared parser in `ArNativeSurface.kt` (file-level, next to the existing `pixelBufferOrNull` helper):

```kotlin
internal fun parseSceneLabels(entries: List<String>): List<ClassificationObservation> =
    entries.mapNotNull { entry ->
        val i = entry.lastIndexOf('|')
        if (i <= 0) return@mapNotNull null
        val confidence = entry.substring(i + 1).toFloatOrNull() ?: return@mapNotNull null
        ClassificationObservation(entry.substring(0, i), confidence)
    }
```

- [ ] **Step 2: Native path consumes Swift labels.** In BOTH `ArFrameFeedback.analyzeTick()` and `ArEnvScan.analyzeTick()`: when `native != null`, do NOT run Kotlin `VNClassifyImageRequest` on the pixel buffer. Instead: `val commonObs = parseSceneLabels(native.classifySceneLabels())`; scene vote = `TrashDetectionRules.sceneVoteFrom(commonObs)` (ArEnvScan emits to `CameraSceneVoteBus`, ArFrameFeedback to its `sceneVoteListener` — unchanged listeners); trash label = `TrashDetectionRules.trashLabelFrom(commonObs)`. Kotlin-side saliency (`VNGenerateObjectnessBasedSaliencyImageRequest` on the pixel buffer) runs ONLY when a trash label was found AND analysis is enabled — restructure so classification no longer requires pulling the pixel buffer at all on the native path. The SceneKit branch (`native == null`) keeps the existing combined Kotlin classify+saliency pass, but where it maps to common observations, route BOTH vote and trash decisions through the same `TrashDetectionRules` calls (they already do — keep behavior identical). ArEnvScan's `analyzeTrash`/element helpers may need their parameter type changed from `List<VNClassificationObservation>` to `List<ClassificationObservation>` with the SceneKit branch converting at the call site — that conversion (`.map { ClassificationObservation(it.identifier, it.confidence) }`) already exists in the current code; hoist it.

- [ ] **Step 3: First-frame verdict + adaptive tick.** In both loops (`ArFrameFeedback.start()`, `ArEnvScan.startLoop()`): analyze FIRST, delay after —

```kotlin
        job = scope.launch {
            while (true) {
                val backoff = try { analyzeTick() } catch (e: Throwable) {
                    DiagnosticsLog.error(TAG, "frame analysis tick failed (backing off)", e)
                    true
                }
                delay(if (backoff) ERROR_BACKOFF_MS else if (fastSceneVotes) FAST_TICK_MS else TICK_MS)
            }
        }
```

(`ArFrameFeedback.start()` keeps its `if (!enabled) { emitCeilingCue(); continue }` behavior from the Task 3 fix — fold it into the loop so ceiling cue + labels-only votes still run while disabled, see Step 4.) Constants both files: `FAST_TICK_MS = 750L`, `DISABLED_VOTE_EVERY_TICKS = 2`, keep `TICK_MS = 1_000L`, `SCENE_VOTE_EVERY_TICKS = 2`.

- [ ] **Step 4: Labels-only loop while blocked.** Semantics change for `setEnabled(false)` / `setFrameAnalysisEnabled(false)`: scene votes CONTINUE (at `DISABLED_VOTE_EVERY_TICKS` cadence, i.e. ~2s; or every tick in fastSceneVotes mode), ceiling cue continues, but NO saliency, NO trash/element detection, NO marker placement, NO frame persistence. Vote cadence selector in both analyzeTick()s:

```kotlin
        val voteDue = if (fastSceneVotes) true
            else if (!enabled) tick % DISABLED_VOTE_EVERY_TICKS == 0
            else tick % SCENE_VOTE_EVERY_TICKS == 0
```

(`ArEnvScan` uses `analysisEnabled` for `enabled`.) Keep ArEnvScan's thermal guard ABOVE all Vision work — thermal shedding still wins over gate reactivity; on the native path the thermal guard also skips `classifySceneLabels()`.

- [ ] **Step 5: AVCapture fallback cadence.** In `CameraManager.kt`: `private const val SCENE_SAMPLE_FAST_INTERVAL_MS = 750L`; companion `var sceneVoteFastMode: Boolean = true`; in `SceneSampleDelegate`'s throttle: `val interval = if (CameraManager.sceneVoteFastMode) SCENE_SAMPLE_FAST_INTERVAL_MS else SCENE_SAMPLE_INTERVAL_MS`. Confirm `lastSampleAtMs` starts at 0 (first frame classifies immediately).

- [ ] **Step 6: Cadence control expects.** `fun setSceneVoteCadence(fast: Boolean)` on all three overlay expects; iOS actuals set `frameFeedback.fastSceneVotes = fast` (Spot/Route) and the ArEnvScan var; Android + desktop actuals no-op.

- [ ] **Step 7: Verify**

```bash
./gradlew :composeApp:desktopTest 2>&1 | tail -3                        # expect BUILD SUCCESSFUL
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -3 # expect BUILD SUCCESSFUL
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build 2>&1 | tail -3  # expect BUILD SUCCEEDED
```

If xcodebuild reports a stale-framework symptom, `rm -rf composeApp/build/xcode-frameworks` and re-run the link step first.

- [ ] **Step 8: Commit**

```bash
git add -A composeApp/src iosApp/iosApp/GccArNative.swift
git commit -m "Swift-first scene sensing: Vision classification in GccArNative, first-frame verdict, 750ms adaptive cadence, labels-only vote loop"
```

### Task 5: captureAllowed plumbing + UNCERTAIN chip on all three surfaces

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ConfirmingOutdoorsChip.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotArScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt`

**Interfaces:**
- Consumes: `gate.captureAllowed` (Task 1), `setSceneVoteCadence` + `setCeilingListener` (Tasks 3–4).
- Produces: `RouteScanViewModel.captureAllowed: StateFlow<Boolean>` and `RouteScanViewModel.onCeilingPlane(detected: Boolean)` for `ArRouteScreen`.

- [ ] **Step 1: The chip component** (`ConfirmingOutdoorsChip.kt`):

```kotlin
package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small status pill shown while the OutdoorGate is still UNCERTAIN: the
 * camera preview runs but capture/credit is locked until outdoors is proven
 * (deny-by-default). Clears within ~1-2s for a legitimately outdoor worker.
 */
@Composable
fun ConfirmingOutdoorsChip(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Text(
        text = "Confirming you're outside…",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
```

- [ ] **Step 2: SpotScreen (env scan).** Changes, all near the existing gate block (`SpotScreen.kt:306-346`):
  - `val captureAllowed by gate.captureAllowed.collectAsState()`
  - Wire new signals in the existing `LaunchedEffect(outdoorState)`:
    ```kotlin
    LaunchedEffect(outdoorState) {
        DiagnosticsLog.info("outdoor-gate", "camera gate → $outdoorState")
        arOverlay?.setFrameAnalysisEnabled(outdoorState != OutdoorState.INDOOR)
        arOverlay?.setSceneVoteCadence(fast = outdoorState != OutdoorState.OUTDOOR)
        CameraManager.sceneVoteFastMode = outdoorState != OutdoorState.OUTDOOR
    }
    ```
  - In the `DisposableEffect(arOverlay)` block add `arOverlay?.setCeilingListener { detected -> gate.onCeilingPlane(detected, Clock.System.now().toEpochMilliseconds()) }` and clear it in `onDispose` (`setCeilingListener(null)`).
  - Gate observation forwarding — wrap the existing element listener body: `if (!gate.captureAllowed.value) return@setElementObservedListener` as the first line.
  - `EnvironmentScanEffects(active = !isIndoor, ...)` → `EnvironmentScanEffects(active = captureAllowed, ...)` (line ~714; `captureAllowed` must be hoisted/passed to that inner composable the same way `isIndoor` is today — follow the existing parameter path).
  - `val canComplete = photos.isNotEmpty() && !isIndoor` → `val canComplete = photos.isNotEmpty() && captureAllowed` (line ~781, same hoisting).
  - Fix the stale comment at line 306 ("GPS-accuracy evidence only here") to describe the real wiring (GPS + map + camera votes + ceiling).
  - Add the chip next to the existing GoOutsideOverlay call site: `ConfirmingOutdoorsChip(visible = outdoorState == OutdoorState.UNCERTAIN, modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp))` (inside the same full-screen `Box`).

- [ ] **Step 3: SpotArScreen.** Same pattern:
  - `val captureAllowed by gate.captureAllowed.collectAsState()`
  - In `DisposableEffect`: `overlay.setCeilingListener { detected -> gate.onCeilingPlane(detected, Clock.System.now().toEpochMilliseconds()) }`, cleared in `onDispose`.
  - `LaunchedEffect(outdoorState)` (merge into the existing one at ~166): `overlay.setSceneVoteCadence(fast = outdoorState != OutdoorState.OUTDOOR)`.
  - Accrual gate (line ~181): `if (gate.state.value == OutdoorState.INDOOR) return@collect` → `if (!gate.captureAllowed.value) return@collect`.
  - Chip beside `GoOutsideOverlay` (line ~317): `ConfirmingOutdoorsChip(visible = outdoorState == OutdoorState.UNCERTAIN, ...)`.

- [ ] **Step 4: Route.** `RouteScanViewModel`:

```kotlin
    val captureAllowed: StateFlow<Boolean> = outdoorGate.captureAllowed

    /** Ceiling-plane evidence from the AR screen (ArRouteScreen). */
    fun onCeilingPlane(detected: Boolean) {
        outdoorGate.onCeilingPlane(detected, Clock.System.now().toEpochMilliseconds())
    }
```

SCOUTING accrual (find `outdoorGate.onMapEvidence` at ~line 1004; a few lines below is the INDOOR `return@collect`): change the suppression condition from `outdoorGate.state.value == OutdoorState.INDOOR` to `!outdoorGate.captureAllowed.value`, keeping the `lastCoverageFix = null` reset behavior for BOTH suppressed states (UNCERTAIN must not bridge phantom distance either).

`ArRouteScreen`: next to `overlay.setSceneVoteListener` (line ~114) add `overlay.setCeilingListener { viewModel.onCeilingPlane(it) }`; in the `LaunchedEffect` that calls `setFrameAnalysisEnabled` add `overlay.setSceneVoteCadence(fast = outdoorState != OutdoorState.OUTDOOR)`; add the chip beside the `GoOutsideOverlay` call (line ~412).

- [ ] **Step 5: Verify + behavioral check**

Run: `./gradlew :composeApp:desktopTest 2>&1 | tail -5` → PASS.
Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -5` → BUILD SUCCESSFUL.
Manual reasoning check (write the answer in the commit body): at scan open outdoors, what unlocks Complete Scan? (First OUTDOOR flip — needs either 5s clean GPS, or 2 fast-cadence OUTDOOR votes at ~0.75s+1.5s → expected unlock ≈1.5–2s.)

- [ ] **Step 6: Commit**

```bash
git add -A composeApp/src
git commit -m "Deny-by-default capture: captureAllowed plumbed through all three scan surfaces + confirming chip"
```

---

### Task 6: Trailing-window purge + gate audit trail

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/EnvScanSessionRecorder.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`, `SpotArScreen.kt` (purge trigger)
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/EnvScanSessionRecorderTest.kt` (create if absent)

**Interfaces:**
- Produces:
  - `EnvScanSessionRecorder.purgeSince(cutoffMs: Long)` — drops path samples with `atMs >= cutoffMs`.
  - `EnvScanSessionRecorder.noteGateEvent(event: String)`; `EnvScanSessionDraft.gateEvents: List<String>`; `EnvScanManifest.gateEvents: List<String>? = null` (additive optional field — older manifests deserialize unchanged).
  - `SpotViewModel.purgeRecentCaptures(cutoffMs: Long)` — drops observed items AND captured photos appended at/after cutoff.

- [ ] **Step 1: Failing test** (`EnvScanSessionRecorderTest.kt`):

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvScanSessionRecorderTest {

    @Test
    fun purgeSinceDropsTrailingSamples() {
        val recorder = EnvScanSessionRecorder()
        recorder.addSample(41.0, -87.0, null, 5.0)   // stamped now
        val draftBefore = recorder.draft(emptyList())
        assertEquals(1, draftBefore.path.size)
        // Cutoff in the past ⇒ the just-added sample is inside the purge window.
        recorder.purgeSince(draftBefore.path.first().atMs)
        assertEquals(0, recorder.draft(emptyList()).path.size)
    }

    @Test
    fun gateEventsRideTheDraft() {
        val recorder = EnvScanSessionRecorder()
        recorder.noteGateEvent("indoor@123 reason=ceiling")
        recorder.noteGateEvent("purge@124 dropped=3")
        assertEquals(2, recorder.draft(emptyList()).gateEvents.size)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.EnvScanSessionRecorderTest" 2>&1 | tail -10` → compilation FAILURE (`purgeSince`/`noteGateEvent`/`gateEvents` unresolved).

- [ ] **Step 3: Implement.**

`EnvScanSessionRecorder`:

```kotlin
    private val gateEvents = mutableListOf<String>()

    /** Privacy purge: drop everything recorded at/after [cutoffMs] — called on
     *  an INDOOR flip so the trailing walk-inside seconds never upload. */
    fun purgeSince(cutoffMs: Long) {
        val before = samples.size
        samples.removeAll { it.atMs >= cutoffMs }
        noteGateEvent("purge@${Clock.System.now().toEpochMilliseconds()} dropped=${before - samples.size}")
    }

    /** Audit trail: gate flips, override presses/yields, purges. */
    fun noteGateEvent(event: String) {
        if (gateEvents.size < 200) gateEvents.add(event)
    }
```

Add `val gateEvents: List<String>` to `EnvScanSessionDraft` and populate from `draft(...)` (`gateEvents = gateEvents.toList()`). Add `val gateEvents: List<String>? = null` to `EnvScanManifest` and thread the draft's value through wherever the manifest is built from a draft (find with `grep -rn "EnvScanManifest(" composeApp/src shared/src` and set `gateEvents = draft.gateEvents` at each construction site that has a draft in scope; pass null elsewhere).

`SpotViewModel` — timestamps stay client-side (the wire model is untouched apart from the additive manifest field):

```kotlin
    private val observedItemStamps = mutableListOf<Long>()
    private val photoStamps = mutableListOf<Long>()
```

Append `Clock.System.now().toEpochMilliseconds()` in `addObservedItem` (line ~174) and wherever `capturedPhotos` gains an element (find with `grep -n "capturedPhotos" SpotViewModel.kt` — every `copy(capturedPhotos = ...+...)` site), then:

```kotlin
    /** Privacy purge on an INDOOR flip: drop items/photos captured in the
     *  gate's trailing window (walked inside while still flagged OUTDOOR). */
    fun purgeRecentCaptures(cutoffMs: Long) {
        val state = _uiState.value
        val keptItems = state.observedItems.filterIndexed { i, _ ->
            observedItemStamps.getOrNull(i)?.let { it < cutoffMs } ?: true
        }
        val keptPhotos = state.capturedPhotos.filterIndexed { i, _ ->
            photoStamps.getOrNull(i)?.let { it < cutoffMs } ?: true
        }
        val dropped = (state.observedItems.size - keptItems.size) + (state.capturedPhotos.size - keptPhotos.size)
        if (dropped > 0) {
            DiagnosticsLog.info("outdoor-gate", "privacy purge: dropped $dropped capture(s) from trailing window")
        }
        while (observedItemStamps.size > keptItems.size) observedItemStamps.removeLast()
        while (photoStamps.size > keptPhotos.size) photoStamps.removeLast()
        _uiState.value = state.copy(observedItems = keptItems, capturedPhotos = keptPhotos)
    }
```

Purge trigger — in `SpotScreen`/`SpotArScreen`'s `LaunchedEffect(outdoorState)`, on INDOOR:

```kotlin
        if (outdoorState == OutdoorState.INDOOR) {
            val cutoff = (gate.lastIndoorFlipAtMs ?: Clock.System.now().toEpochMilliseconds()) - OutdoorGate.PURGE_WINDOW_MS
            sessionRecorder.purgeSince(cutoff)      // SpotScreen only (owns the recorder)
            onPurgeRecentCaptures(cutoff)           // new param → AppNavigation → viewModel::purgeRecentCaptures
        }
        sessionRecorder.noteGateEvent("gate@${Clock.System.now().toEpochMilliseconds()} state=$outdoorState")
```

Add the `onPurgeRecentCaptures: (Long) -> Unit = {}` parameter to both screens and wire it in the navigation host where `onTrashObserved` is already wired (find with `grep -rn "onTrashObserved = " composeApp/src/commonMain/kotlin/com/gcc/app/ui/` — same file, same pattern, pointing at `viewModel::purgeRecentCaptures`). Also stamp override presses: where the screens call `gate.forceOutdoors()`, add `sessionRecorder.noteGateEvent("override@${Clock.System.now().toEpochMilliseconds()}")` (SpotScreen; for SpotArScreen/route, DiagnosticsLog already records it — manifest audit rides the env-scan surface, which is where captures happen).

- [ ] **Step 4: Run tests** — `./gradlew :composeApp:desktopTest 2>&1 | tail -8` → PASS (recorder tests + full suite; shared model change compiles everywhere).

- [ ] **Step 5: Commit**

```bash
git add -A composeApp/src shared/src
git commit -m "Privacy purge of trailing indoor captures + gate audit trail in scan manifests"
```

---

### Task 7: Full verification + ship

**Files:** none new (fixes only if something fails).

- [ ] **Step 1: Full test suite** — `./gradlew :composeApp:desktopTest 2>&1 | tail -10` → BUILD SUCCESSFUL, no failures.

- [ ] **Step 2: iOS link + app build**

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -3
rm -rf composeApp/build/xcode-frameworks
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' build 2>&1 | tail -3
```

Expected: BUILD SUCCEEDED (Swift `ceilingDetected` conformance compiles).

- [ ] **Step 3: Sim smoke test of the gate UX.** Boot a simulator, install, launch env scan; use `set_sim_location` (xcodebuild MCP) at a North Lawndale point INSIDE a `northLawndaleBuildings` rectangle → expect GoOutsideOverlay quickly (pre-emptive containment). Move location to a street point → expect recovery (5s clean-GPS path won't run on sim GPS accuracy semantics; if recovery doesn't trigger on sim, verify the overlay at least appears and note that recovery needs the device walk test — sim has no real camera or GPS accuracy stream, so UNCERTAIN-chip → unlock timing is device-only).

- [ ] **Step 4: Push + draft PR**

```bash
git push -u origin indoor-gate-v4
gh pr create --draft --title "Indoor gate v4: instant ceiling cue, deny-by-default capture, fast recovery, privacy purge" --body "Phase 1 of docs/plans/2026-07-05-indoor-gate-v4-design.md ... 🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

PR body must list: the v3→v4 behavior changes, the un-muting rationale, purge semantics, override-yield semantics, and the pending device walk test (porch / window-adjacent indoor / frame-house interior).

---

## Self-review notes (done at plan time)

- **Spec coverage:** ceiling cue → T1/T3; un-muted camera → T1; captureAllowed/deny-by-default → T1/T5; first-frame + 750ms cadence → T4; weighted scoring + keywords → T2; hard block + purge → T5/T6; labels-only recovery loop → T4; fast recovery → T1; override yields → T1; chip UX → T5; audit trail → T6; all three surfaces → T5; tests → each task + T7. Phase 2 (footprints) intentionally not in this plan.
- **Known judgment calls encoded above:** cue-absence flip keeps its GPS guard (park-bench safeguard); coverage-cell ledger events are NOT purged (10 m cells, sub-second flips expected, low residual risk — revisit in Phase 2 if field data disagrees); route/spot AR surfaces get purge via deny-by-default only (captures/observations there post immediately and are already gated).
- **Type consistency check:** `captureAllowed: StateFlow<Boolean>`, `onCeilingPlane(Boolean, Long)`, `setCeilingListener(((Boolean) -> Unit)?)`, `setSceneVoteCadence(Boolean)`, `purgeSince(Long)`, `purgeRecentCaptures(Long)`, `noteGateEvent(String)` — used identically across tasks.
