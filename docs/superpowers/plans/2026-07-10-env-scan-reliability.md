# Environment Scan Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the iOS environment scanner honest and usable in the field — a persistent heading cone, a readable minimap, a tap that always produces a confirmable litter candidate and never silently files a report, no green AR bar, no clipped pills, no blob-collision 500s, and a display that stays awake so the user never taps the screen by accident.

**Architecture:** All decision logic lives in pure Kotlin in `commonMain` (`TapCandidateState`, `TrashDetectionRules`, `headingConePolygon`, litter/feature counting) where it is unit-testable with `kotlin.test`. ARKit/RealityKit/SceneKit and Compose layout are thin, untestable shells driven by that logic — they are verified by the physical-device gate in Task 11, never by a unit test that asserts nothing. The Next.js app remains the control plane; the only backend change is a collision-proof blob pathname and an additive, migration-free provenance field carried in the existing `world_observations.attributes` jsonb.

**Tech Stack:** Kotlin Multiplatform (Compose), ARKit/RealityKit/SceneKit (Swift), MapLibre via maplibre-compose, Next.js 16 + Drizzle/Neon, `@vercel/blob` 2.3.1, `node:test` + `tsx`.

**Scope:** This plan implements **Phase 1 only** — "Immediate scanner reliability" — from the Delivery sequence of `docs/superpowers/specs/2026-07-09-environment-scan-reconstruction-map-design.md` (line 601), plus two product requirements added by Blake after the spec was written (Global Constraints 2 and 3 below). Phases 2–7 (durable ingest contract, capture schema v2, DGX decoder, reconstruction orchestration, map consumption, marketing concepts) are **separate subsystems and need their own plans.** Phase 1 leaves the app strictly better and observable on its own; it does not wait on reconstruction.

## Global Constraints

Every task's requirements implicitly include this section.

1. **A tap is authoritative (Option A, approved by Blake).** Tapping always opens a *provisional* litter candidate, even when the classifier returns nothing. The model suggests a type when it can, otherwise the label is exactly `Unknown litter`.
2. **A tap never assumes litter, and nothing is persisted until the user confirms.** Every tap raises a reticle plus a bottom confirm/cancel pill. `Confirm litter` persists a user-confirmed observation. `Not litter` records a negative training example. Dismiss or timeout records **nothing** — accidental taps must be free.
3. **The scanner holds the display awake for the duration of the scan** and restores the previous setting on exit. Users must never tap the screen to keep it alive.
4. Valid reticle language, exactly: dashed orange `Checking…`; orange `Move closer to identify`; red outline with a check `Litter identified`; gray outline `Feature mapped`; crossed/soft gray `Not litter`.
5. The heading cone is **persistent**, 55° wide, with an outer radius of `CoverageTracker.SEEN_RANGE_METERS` (55.0 m), and remains visible **even when AR raycasts return no polygon**.
6. The live visibility beam sits **inside** the cone and is live sensing, not credited coverage. Cone, beam, and coverage paint must remain visually distinct.
7. The ground grid is **non-emissive**, obeys depth, uses a 1 m minor interval and a 3 m major interval, and must not draw over plants, fences, or cars.
8. The camera-attached green bar is **deleted**. Directional guidance lives in the cone, the minimap, and a text prompt.
9. **No pill may be clipped by the top safe-area inset.**
10. Whole-frame image classification is a **weak routing signal only**. It may never auto-file a report. Auto-promotion requires a localized region, sufficient size, temporal persistence, and a context check.
11. A `tire` prediction is suppressed when its region overlaps a car, truck, wheel, wheel well, or bicycle in use. A user may still explicitly confirm a detached tire as litter.
12. `Litter identified` and `Features mapped` are counted and displayed **separately**.
13. The app must never use `Scan complete` to imply reconstruction data reached the backend.
14. **No new test framework.** Kotlin tests use `kotlin.test` (`@Test`, `assertEquals`, `assertTrue`, `assertNull`). Site tests use `node:test` + `tsx`. No vitest, no jest, no `kotlinx-coroutines-test`.
15. **No database migration in this plan.** Provenance rides in the existing `world_observations.attributes` jsonb column. Negative (rejected) examples ride in the R2 `EnvScanManifest`, never in `world_observations`.
16. **No Ktor server deployment.** `server/` is out of the Gradle build and stays that way.
17. `@Synchronized` and `@Volatile` are JVM-only and **unavailable in `commonMain`** (it must compile for Kotlin/Native). Use `MutableStateFlow.update {}` / `compareAndSet`.
18. `composeApp`'s JVM test task is `:composeApp:desktopTest` (the target is `jvm("desktop")`), **not** `jvmTest`. `commonTest` must also compile for iOS — anything needing a JVM-only dependency (e.g. `JdbcSqliteDriver`) goes in `desktopTest`.
19. `expect class ArEnvScanOverlay` has **three** actuals — `iosMain`, `androidMain`, `desktopMain`. Any signature change must land in all three or `:composeApp:linkDebugFrameworkIosSimulatorArm64` fails.
20. R2 presigned PUTs must keep using the token-free `siteClient` and `takeFromRawEncoded`. Do not touch `uploadToPresignedUrl`.
21. **Some tasks have no unit test.** Swift/ARKit rendering and Compose layout have no harness in this repo. Those tasks say so explicitly and are verified by the Task 11 device gate. **Never write a test that cannot fail in order to satisfy a checkbox.**

## Verification commands

```bash
# Kotlin — focused
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.<TestClass>"
# Kotlin — full gates (iOS link is the Kotlin/Native check)
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
# Site
cd site && npx tsx --test src/lib/<file>.test.ts && npx tsc --noEmit
```

**Disk gotcha (recurring):** `:composeApp:linkDebugFrameworkIosSimulatorArm64` needs ~2.2 GB free. If it fails with a bare Gradle worker stack trace, grep the log for `No space left on device` before believing any other diagnosis. Reclaim by deleting `*/build` directories in stale worktrees under `.claude/worktrees/`. Do not retry-and-hope.

---

### Task 1: Keep the scanner's display awake

Removes the root cause of the accidental taps: the user currently taps the screen to stop it sleeping, and (before Task 2) every tap silently captures a frame.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ScreenWake.kt`
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ScreenWake.kt`
- Create: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ScreenWake.kt`
- Create: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ScreenWake.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (inside `CameraPhase`'s full-bleed `Box`, opened at `SpotScreen.kt:895`)

**Interfaces:**
- Produces: `expect fun setKeepScreenAwake(enabled: Boolean)` and `@Composable fun KeepScreenAwake()`.
- Consumes: nothing.

This mirrors the existing `HapticFeedback.kt` expect/actual layout exactly — same package, same filename in each source set.

**No unit test.** This is a one-line platform call with no branching logic; any test would assert only that a no-op does not throw. Verified by the Task 11 device gate ("screen does not dim during a 90-second scan").

- [ ] **Step 1: Write the `expect` declaration and the Compose helper**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ScreenWake.kt`:

```kotlin
package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Hold the display awake. The environment scan runs for 60–90 seconds with no
 * touch input by design; without this the screen dims and users tap the
 * viewfinder purely to wake it, which used to fire a silent frame capture
 * (see [com.gcc.app.ui.components.CameraCapture]).
 *
 * Always paired: every `true` must be followed by a `false`. Prefer
 * [KeepScreenAwake], which pairs them for you.
 */
expect fun setKeepScreenAwake(enabled: Boolean)

/** Holds the display awake for as long as this composable is in composition. */
@Composable
fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        setKeepScreenAwake(true)
        onDispose { setKeepScreenAwake(false) }
    }
}
```

- [ ] **Step 2: Write the three `actual` implementations**

`composeApp/src/iosMain/kotlin/com/gcc/app/platform/ScreenWake.kt`:

```kotlin
package com.gcc.app.platform

import platform.UIKit.UIApplication
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * `idleTimerDisabled` is a main-thread UIKit property. Compose calls
 * DisposableEffect on the main dispatcher, so no hop is needed here.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun setKeepScreenAwake(enabled: Boolean) {
    UIApplication.sharedApplication.idleTimerDisabled = enabled
}
```

`composeApp/src/androidMain/kotlin/com/gcc/app/platform/ScreenWake.kt`:

```kotlin
package com.gcc.app.platform

/**
 * No-op. Android's FLAG_KEEP_SCREEN_ON needs the host Activity's Window,
 * which this platform layer does not hold. This increment is iOS-first
 * (spec non-goal: "Android raw reconstruction capture in this increment");
 * Android only has to compile.
 */
actual fun setKeepScreenAwake(enabled: Boolean) {
    // Intentionally empty — see KDoc.
}
```

`composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ScreenWake.kt`:

```kotlin
package com.gcc.app.platform

/** No screen-sleep concept for the desktop test/dev harness. */
actual fun setKeepScreenAwake(enabled: Boolean) {
    // Intentionally empty.
}
```

- [ ] **Step 3: Call it from the scanner**

In `SpotScreen.kt`, inside `CameraPhase`'s full-bleed `Box` (opened at `SpotScreen.kt:895`), add as the first statement in the `Box` body:

```kotlin
        // Global constraint 3: the scan runs for 60–90s with no touch input.
        KeepScreenAwake()
```

Add the import `import com.gcc.app.platform.KeepScreenAwake` alongside the file's other `com.gcc.app.platform` imports.

- [ ] **Step 4: Verify it compiles on every target**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`. The iOS link is what proves the `iosMain` actual is valid Kotlin/Native. Baseline test count must be unchanged (this task adds no tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ScreenWake.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/ScreenWake.kt \
        composeApp/src/androidMain/kotlin/com/gcc/app/platform/ScreenWake.kt \
        composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ScreenWake.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt
git commit -m "feat(scan): hold the display awake during an environment scan"
```

---

### Task 2: Tap state machine (pure, tested)

The reducer that makes Global Constraints 1, 2 and 4 true. Pure Kotlin, no ARKit, no Compose — this is the only part of the tap contract that can be unit-tested, and per the spec's automated-test list it must cover "success, unknown, no raycast, retry, confirm, reject."

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/TapCandidateState.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/TapCandidateStateTest.kt`

**Interfaces:**
- Produces: `TapPhase`, `TapCandidateState`, `TapEvent`, `TapCandidateState.reduce(TapEvent)`, `TapCandidateState.isIdentified`, `TapPillCopy`, `tapPillCopy(TapCandidateState)`, and the constants `TAP_IDENTIFY_MAX_METERS`, `TAP_IDENTIFY_MIN_CONFIDENCE`, `UNKNOWN_LITTER_LABEL`.
- Consumes: nothing.

Semantics that the tests must pin:
- A new `Tapped` **always** supersedes whatever came before (a second tap retargets).
- Out-of-order events are ignored (a `RaycastHit` while `IDLE` changes nothing).
- `Classified(null, …)` yields the label `Unknown litter` and still reaches `AWAITING_CONFIRMATION` — this is Option A.
- `Confirmed` / `Rejected` / `Dismissed` all return `IDLE`. **The caller must read the state before reducing**, because the reducer discards it. Say so in the KDoc.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/TapCandidateStateTest.kt`:

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapCandidateStateTest {

    private val idle = TapCandidateState()

    private fun tappedAndLocated(distance: Float = 2.0f): TapCandidateState =
        idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
            .reduce(TapEvent.RaycastHit(41.86, -87.72, distance))

    @Test
    fun tapMovesToLocating() {
        val state = idle.reduce(TapEvent.Tapped(0.25f, 0.75f))
        assertEquals(TapPhase.LOCATING, state.phase)
        assertEquals(0.25f, state.normalizedX)
        assertEquals(0.75f, state.normalizedY)
    }

    @Test
    fun raycastHitMovesToChecking() {
        val state = tappedAndLocated()
        assertEquals(TapPhase.CHECKING, state.phase)
        assertEquals(41.86, state.lat)
        assertEquals(2.0f, state.distanceMeters)
    }

    @Test
    fun raycastMissAsksTheUserToRetry() {
        val state = idle.reduce(TapEvent.Tapped(0.5f, 0.5f)).reduce(TapEvent.RaycastMissed)
        assertEquals(TapPhase.NO_RAYCAST, state.phase)
        assertEquals("Hold steady and tap the item again", tapPillCopy(state)?.title)
    }

    @Test
    fun unknownClassificationStillCreatesAConfirmableCandidate() {
        // Option A: a tap is authoritative even when the model names nothing.
        val state = tappedAndLocated().reduce(TapEvent.Classified(label = null, confidence = null))
        assertEquals(TapPhase.AWAITING_CONFIRMATION, state.phase)
        assertEquals(UNKNOWN_LITTER_LABEL, state.label)
        assertTrue(!state.isIdentified)
    }

    @Test
    fun closeConfidentClassificationReadsAsIdentified() {
        val state = tappedAndLocated(distance = 1.5f).reduce(TapEvent.Classified("Bottle", 0.8f))
        assertEquals(TapPhase.AWAITING_CONFIRMATION, state.phase)
        assertTrue(state.isIdentified)
        assertEquals("Bottle — is this litter?", tapPillCopy(state)?.title)
    }

    @Test
    fun distantCandidateAsksTheUserToMoveCloser() {
        val state = tappedAndLocated(distance = 12.0f).reduce(TapEvent.Classified("Bottle", 0.9f))
        assertTrue(!state.isIdentified, "12m is beyond TAP_IDENTIFY_MAX_METERS")
        assertEquals("Move closer to identify — is this litter?", tapPillCopy(state)?.title)
    }

    @Test
    fun lowConfidenceCandidateAsksTheUserToMoveCloser() {
        val state = tappedAndLocated(distance = 1.0f).reduce(TapEvent.Classified("Bottle", 0.2f))
        assertTrue(!state.isIdentified)
    }

    @Test
    fun awaitingConfirmationOffersBothActions() {
        val copy = tapPillCopy(tappedAndLocated().reduce(TapEvent.Classified("Can", 0.9f)))
        assertEquals("Confirm litter", copy?.confirmLabel)
        assertEquals("Not litter", copy?.rejectLabel)
    }

    @Test
    fun aSecondTapSupersedesTheFirst() {
        val state = tappedAndLocated().reduce(TapEvent.Tapped(0.1f, 0.2f))
        assertEquals(TapPhase.LOCATING, state.phase)
        assertEquals(0.1f, state.normalizedX)
        assertNull(state.lat, "the superseded candidate's raycast must be discarded")
    }

    @Test
    fun outOfOrderEventsAreIgnored() {
        assertEquals(idle, idle.reduce(TapEvent.RaycastHit(1.0, 2.0, 3.0f)))
        assertEquals(idle, idle.reduce(TapEvent.Classified("Bottle", 0.9f)))
        // A classification arriving while still LOCATING must not skip CHECKING.
        val locating = idle.reduce(TapEvent.Tapped(0.5f, 0.5f))
        assertEquals(locating, locating.reduce(TapEvent.Classified("Bottle", 0.9f)))
    }

    @Test
    fun confirmRejectAndDismissAllReturnToIdle() {
        val pending = tappedAndLocated().reduce(TapEvent.Classified("Can", 0.9f))
        assertEquals(idle, pending.reduce(TapEvent.Confirmed))
        assertEquals(idle, pending.reduce(TapEvent.Rejected))
        assertEquals(idle, pending.reduce(TapEvent.Dismissed))
    }

    @Test
    fun idleShowsNoPill() {
        assertNull(tapPillCopy(idle))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TapCandidateStateTest"
```

Expected: `BUILD FAILED` with `Unresolved reference 'TapCandidateState'` (and the other new symbols). The failure is expected because the production file does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/TapCandidateState.kt`:

```kotlin
package com.gcc.app.platform

/** At/below this raycast distance a tapped candidate is close enough to identify. */
const val TAP_IDENTIFY_MAX_METERS = 4.0

/** At/above this classifier confidence a tapped candidate is "identified". */
const val TAP_IDENTIFY_MIN_CONFIDENCE = 0.5f

/** The label a tapped candidate carries when the classifier names nothing. */
const val UNKNOWN_LITTER_LABEL = "Unknown litter"

enum class TapPhase { IDLE, LOCATING, CHECKING, AWAITING_CONFIRMATION, NO_RAYCAST }

/**
 * The one pending tap-to-identify candidate. A tap is authoritative (Option A):
 * it always reaches [TapPhase.AWAITING_CONFIRMATION] if it raycasts, even when
 * the model returns no label. Nothing is persisted until the user confirms.
 */
data class TapCandidateState(
    val phase: TapPhase = TapPhase.IDLE,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val distanceMeters: Float? = null,
    val label: String? = null,
    val confidence: Float? = null,
)

sealed interface TapEvent {
    data class Tapped(val normalizedX: Float, val normalizedY: Float) : TapEvent
    data class RaycastHit(val lat: Double, val lng: Double, val distanceMeters: Float) : TapEvent
    object RaycastMissed : TapEvent
    data class Classified(val label: String?, val confidence: Float?) : TapEvent
    object Confirmed : TapEvent
    object Rejected : TapEvent
    object Dismissed : TapEvent
}

/**
 * Fold one [TapEvent] into the candidate.
 *
 * [TapEvent.Confirmed], [TapEvent.Rejected] and [TapEvent.Dismissed] all
 * return a fresh IDLE state — the caller MUST read the outgoing state (to
 * persist a confirmation or a negative example) BEFORE reducing, because the
 * reducer discards it.
 *
 * Events that do not belong to the current phase are ignored, so a late
 * raycast or classification from a superseded tap cannot resurrect it.
 */
fun TapCandidateState.reduce(event: TapEvent): TapCandidateState = when (event) {
    // A new tap always supersedes whatever was pending — including its raycast.
    is TapEvent.Tapped -> TapCandidateState(
        phase = TapPhase.LOCATING,
        normalizedX = event.normalizedX,
        normalizedY = event.normalizedY,
    )
    is TapEvent.RaycastHit ->
        if (phase != TapPhase.LOCATING) this
        else copy(
            phase = TapPhase.CHECKING,
            lat = event.lat,
            lng = event.lng,
            distanceMeters = event.distanceMeters,
        )
    TapEvent.RaycastMissed ->
        if (phase != TapPhase.LOCATING) this else copy(phase = TapPhase.NO_RAYCAST)
    is TapEvent.Classified ->
        if (phase != TapPhase.CHECKING) this
        else copy(
            phase = TapPhase.AWAITING_CONFIRMATION,
            label = event.label ?: UNKNOWN_LITTER_LABEL,
            confidence = event.confidence,
        )
    TapEvent.Confirmed, TapEvent.Rejected, TapEvent.Dismissed -> TapCandidateState()
}

/** The model named it, was confident, and it is close enough to trust. */
val TapCandidateState.isIdentified: Boolean
    get() = label != null &&
        label != UNKNOWN_LITTER_LABEL &&
        (confidence ?: 0f) >= TAP_IDENTIFY_MIN_CONFIDENCE &&
        (distanceMeters?.toDouble() ?: Double.MAX_VALUE) <= TAP_IDENTIFY_MAX_METERS

/** Copy for the bottom pill. `null` means: draw no pill. */
data class TapPillCopy(
    val title: String,
    val confirmLabel: String?,
    val rejectLabel: String?,
)

fun tapPillCopy(state: TapCandidateState): TapPillCopy? = when (state.phase) {
    TapPhase.IDLE -> null
    TapPhase.LOCATING -> TapPillCopy("Locating…", null, null)
    TapPhase.CHECKING -> TapPillCopy("Checking…", null, null)
    TapPhase.NO_RAYCAST -> TapPillCopy("Hold steady and tap the item again", null, null)
    TapPhase.AWAITING_CONFIRMATION -> TapPillCopy(
        title = if (state.isIdentified) {
            "${state.label} — is this litter?"
        } else {
            "Move closer to identify — is this litter?"
        },
        confirmLabel = "Confirm litter",
        rejectLabel = "Not litter",
    )
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TapCandidateStateTest"
```

Expected: `BUILD SUCCESSFUL`, 12 tests, 0 failures, no warnings.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/TapCandidateState.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/TapCandidateStateTest.kt
git commit -m "feat(scan): pure tap-to-identify state machine"
```

---

### Task 3: Decouple the tap from photo capture

Today one tap does two things: `arOverlay.focusAt(...)` **and** an unconditional `manager.captureFrame()` (`CameraCapture.kt:124-154`). That is why a stray tap "took one image" of a car. In environment-scan mode a tap must only target; it must never capture.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt:123-154`

**Interfaces:**
- Consumes: `ArEnvScanOverlay.focusAt` (unchanged in this task; Task 5 renames the flow).
- Produces: nothing new.

`CameraCapture` is shared by non-scan flows (`SpotVerificationViewModel`, `CleanScreen`, volunteer cleanup) that **rely** on tap-to-capture. Gate the change on the existing `environmentScanMode` flag so their behavior is untouched.

**No unit test.** `detectTapGestures` has no harness here. The behavior change is verified by the Task 11 device gate ("tapping the viewfinder during a scan does not increase the photo count").

- [ ] **Step 1: Replace the tap handler**

In `CameraCapture.kt`, replace the whole `pointerInput` block (currently `CameraCapture.kt:126-153`) with:

```kotlin
                .pointerInput(atLimit, environmentScanMode) {
                    detectTapGestures { offset ->
                        if (environmentScanMode) {
                            // Global constraint 2: in a scan, a tap TARGETS.
                            // It never captures. Accidental taps (and the taps
                            // users make to keep the screen awake — see
                            // KeepScreenAwake) must cost nothing. The tap opens
                            // a provisional candidate the user must confirm.
                            arOverlay?.focusAt(offset.x / size.width, offset.y / size.height)
                            return@detectTapGestures
                        }
                        // Non-scan flows keep tap-to-capture.
                        if (arOverlay != null) {
                            arOverlay.focusAt(offset.x / size.width, offset.y / size.height)
                        }
                        if (!isCapturing && !atLimit) {
                            isCapturing = true
                            coroutineScope.launch {
                                val photo = manager.capturePhoto()
                                if (photo != null) onCapture(photo)
                                isCapturing = false
                            }
                        }
                    }
                },
```

Note the `pointerInput` key gains `environmentScanMode` so the gesture detector is rebuilt if the mode ever flips.

- [ ] **Step 2: Verify nothing else depended on scan-mode tap capture**

Run:

```bash
grep -rn "environmentScanMode" composeApp/src/commonMain/kotlin/com/gcc/app/
grep -rn "captureFrame()" composeApp/src/commonMain/kotlin/com/gcc/app/
```

Expected: the only remaining `captureFrame()` call in a scan path is `SpotScanCapture.kt`'s 2.5-second auto-capture (`SpotScanCapture.kt:196-214`), which Task 6 addresses. If `grep` shows any other scan-mode caller relying on tap-to-capture, STOP and report it — do not silently adapt.

- [ ] **Step 3: Verify it compiles on every target**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`, test count unchanged from Task 2, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt
git commit -m "fix(scan): a viewfinder tap no longer silently captures a frame"
```

---

### Task 4: Vehicle-context suppression and demoted whole-frame classification

Global constraints 10 and 11. `TrashDetectionRules.trashLabelFrom` accepts `tire` at 0.35 confidence from a **whole-frame** classifier, which is how a tire mounted on a car became a litter report. Whole-frame labels stay as a routing signal; a new function gates *auto-promotion*.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt`

**Interfaces:**
- Produces: `TrashDetectionRules.VEHICLE_CONTEXT_KEYWORDS`, `TrashDetectionRules.PARENT_ATTACHED_LABELS`, `TrashDetectionRules.hasVehicleContext(observations)`, `TrashDetectionRules.autoPromotionLabelFrom(observations)`.
- Consumes: existing `tokensOf`, `trashLabelFrom`, `TRASH_TOKENS`, `ClassificationObservation`.

`trashLabelFrom` keeps its exact current behavior — other callers depend on it and it is the weak routing signal. `autoPromotionLabelFrom` is the new, stricter gate. A user tap must **bypass** it entirely (constraint 11: a detached tire can still be user-confirmed), so nothing in Task 5's tap path may call it.

- [ ] **Step 1: Write the failing tests**

Append to `composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt`, inside the existing class, matching its `obs(...)` helper idiom:

```kotlin
    @Test
    fun tireOnACarDoesNotAutoPromote() {
        // The exact field failure: a whole-frame classifier saw "tire" and "car".
        val observations = listOf(obs("tire", 0.6f), obs("car", 0.7f))
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun tireNearATruckOrBicycleDoesNotAutoPromote() {
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("tire", 0.6f), obs("truck", 0.5f))))
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("tire", 0.6f), obs("bicycle", 0.5f))))
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("wheel", 0.6f), obs("car", 0.5f))))
    }

    @Test
    fun detachedTireWithNoVehicleContextStillAutoPromotes() {
        val observations = listOf(obs("tire", 0.6f), obs("grass", 0.5f))
        assertEquals("Tire", TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun vehicleContextDoesNotSuppressUnrelatedLitter() {
        // A bottle on the sidewalk next to a parked car is still a bottle.
        val observations = listOf(obs("bottle", 0.6f), obs("car", 0.7f))
        assertEquals("Bottle", TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun weakVehicleEvidenceDoesNotSuppress() {
        // Below SCENE_CONFIDENCE (0.3) the "car" label is noise, not context.
        val observations = listOf(obs("tire", 0.6f), obs("car", 0.1f))
        assertEquals("Tire", TrashDetectionRules.autoPromotionLabelFrom(observations))
    }

    @Test
    fun autoPromotionStillHonoursTheConfidenceFloor() {
        assertNull(TrashDetectionRules.autoPromotionLabelFrom(listOf(obs("bottle", 0.2f))))
    }

    @Test
    fun hasVehicleContextIsIndependentlyUsable() {
        assertTrue(TrashDetectionRules.hasVehicleContext(listOf(obs("car", 0.7f))))
        assertTrue(!TrashDetectionRules.hasVehicleContext(listOf(obs("grass", 0.7f))))
    }

    @Test
    fun wholeFrameRoutingSignalIsUnchangedBySuppression() {
        // trashLabelFrom stays a weak routing signal: it still reports "Tire".
        // Only auto-promotion is gated. This pins that we did not change it.
        val observations = listOf(obs("tire", 0.6f), obs("car", 0.7f))
        assertEquals("Tire", TrashDetectionRules.trashLabelFrom(observations))
    }
```

Add `import kotlin.test.assertTrue` to the test file's imports (it currently imports only `Test`, `assertEquals`, `assertNull`).

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TrashDetectionRulesTest"
```

Expected: `BUILD FAILED` with `Unresolved reference 'autoPromotionLabelFrom'` and `Unresolved reference 'hasVehicleContext'`.

- [ ] **Step 3: Write the implementation**

Add to `TrashDetectionRules` in `composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt`, below `TRASH_TOKENS`:

```kotlin
    /**
     * Labels that mean "a vehicle is in this frame". A whole-frame classifier
     * cannot tell us whether the tire it saw is ON one of these — so when a
     * vehicle is present at real confidence, a tire/wheel prediction is never
     * auto-promoted. The user can still tap a detached tire and confirm it.
     */
    val VEHICLE_CONTEXT_KEYWORDS = setOf(
        "car", "truck", "van", "bus", "vehicle", "automobile", "motorcycle",
        "bicycle", "bike", "wheel", "wheels", "tyre", "trailer", "suv", "jeep",
    )

    /**
     * Trash labels that are commonly a PART of a larger, non-discarded parent
     * object. Only these are suppressed by [hasVehicleContext]; a bottle next
     * to a parked car is still a bottle.
     */
    val PARENT_ATTACHED_LABELS = setOf("Tire")

    /** True when a vehicle label is present at or above [SCENE_CONFIDENCE]. */
    fun hasVehicleContext(observations: List<ClassificationObservation>): Boolean =
        observations.any { obs ->
            obs.confidence >= SCENE_CONFIDENCE &&
                tokensOf(obs.identifier).any { it in VEHICLE_CONTEXT_KEYWORDS }
        }

    /**
     * The label an AUTOMATIC detection may be promoted with, or null.
     *
     * Distinct from [trashLabelFrom], which stays a weak routing signal for the
     * whole-frame classifier and is deliberately unchanged. This applies the
     * parent-context suppression required before anything auto-files.
     *
     * A user tap MUST NOT call this — a tap is authoritative and may confirm a
     * detached tire even in a driveway full of cars.
     */
    fun autoPromotionLabelFrom(observations: List<ClassificationObservation>): String? {
        val label = trashLabelFrom(observations) ?: return null
        if (label in PARENT_ATTACHED_LABELS && hasVehicleContext(observations)) return null
        return label
    }
```

Note: `"wheel"` appears in `VEHICLE_CONTEXT_KEYWORDS`, so a bare `wheel` + `car` frame is suppressed. `wheel` is not itself in `TRASH_TOKENS`, so `trashLabelFrom` returns null for it anyway — the `wheel`+`car` test asserts the null, which both paths satisfy.

- [ ] **Step 4: Run the tests and verify GREEN**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TrashDetectionRulesTest"
```

Expected: `BUILD SUCCESSFUL`, 18 tests (10 pre-existing + 8 new), 0 failures.

- [ ] **Step 5: Route automatic promotion through the new gate**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`, inside `analyzeTrash` (currently `ArEnvScan.kt:866-1006`), change the label lookup at `ArEnvScan.kt:877` from:

```kotlin
        val trashLabel = TrashDetectionRules.trashLabelFrom(classifications)
```

to:

```kotlin
        // Global constraint 10/11: automatic promotion requires parent-context
        // suppression. A user tap bypasses this entirely (see focusAt).
        val trashLabel = TrashDetectionRules.autoPromotionLabelFrom(classifications)
```

Leave the rest of `analyzeTrash` unchanged. The `rawIdentifier` lookup below it (`ArEnvScan.kt:880-884`) still re-derives from `classifications` and is unaffected.

- [ ] **Step 6: Verify the full gates**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt
git commit -m "fix(scan): never auto-promote a tire attached to a vehicle"
```

---

### Task 5: Wire the tap state machine into the AR overlay and the bottom pill

Connects Task 2's reducer to ARKit and to a Compose pill. This is the task that makes a tap produce a visible, confirmable candidate.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (the `expect class ArEnvScanOverlay`, declared at `ArEnvScan.kt:61`)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
- Modify: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/TapCandidatePill.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt`

**Interfaces:**
- Consumes: `TapCandidateState`, `TapEvent`, `reduce`, `tapPillCopy` (Task 2).
- Produces: `ArEnvScanOverlay.tapCandidate: StateFlow<TapCandidateState>`, `ArEnvScanOverlay.confirmTapCandidate(): TapCandidateState?`, `ArEnvScanOverlay.rejectTapCandidate(): TapCandidateState?`, `ArEnvScanOverlay.dismissTapCandidate()`, and `@Composable fun TapCandidatePill(...)`.

**Constraint 19 applies:** all three actuals must be updated. Start by confirming their locations:

```bash
grep -rn "actual class ArEnvScanOverlay" composeApp/src/
```

Expected exactly three files: `iosMain`, `androidMain`, `desktopMain`.

**No unit test for the ARKit or Compose wiring** — Task 2 already tests every branch of the reducer, and this repo has no ARKit or Compose test harness (see `composeApp/src/desktopTest/kotlin/com/gcc/app/platform/ArEnvScanFocusTest.kt`, whose own KDoc says "no simulator runs in this repo's environment"). Do not write one. Verified by the Task 11 device gate.

- [ ] **Step 1: Extend the `expect` class**

In `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`, add to `expect class ArEnvScanOverlay` (after the existing `val trashCandidate: StateFlow<TrashCandidate?>`):

```kotlin
    /** The one pending user-tapped candidate. See [TapCandidateState]. */
    val tapCandidate: StateFlow<TapCandidateState>

    /**
     * Persist the pending tapped candidate as user-confirmed litter and reset.
     * Returns the state that was confirmed, or null if nothing was pending.
     */
    fun confirmTapCandidate(): TapCandidateState?

    /**
     * Record the pending tapped candidate as a negative training example and
     * reset. Returns the rejected state, or null if nothing was pending.
     */
    fun rejectTapCandidate(): TapCandidateState?

    /** Drop the pending tapped candidate. Records nothing (accidental tap). */
    fun dismissTapCandidate()
```

- [ ] **Step 2: Implement on iOS**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`:

Add the backing flow beside the existing `_trashCandidate`:

```kotlin
    private val _tapCandidate = MutableStateFlow(TapCandidateState())
    actual val tapCandidate: StateFlow<TapCandidateState> = _tapCandidate.asStateFlow()
```

Replace `focusAt` (currently `ArEnvScan.kt:598-600`) with:

```kotlin
    actual fun focusAt(x: Float, y: Float) {
        pendingFocusTap = x.toDouble() to y.toDouble()
        _tapCandidate.update { it.reduce(TapEvent.Tapped(x, y)) }
    }
```

Replace `processPendingFocusTap` (currently `ArEnvScan.kt:606-621`) so a miss is no longer silent, and a hit drives the reducer:

```kotlin
    private fun processPendingFocusTap(frame: ARFrame?) {
        val tap = pendingFocusTap ?: return
        pendingFocusTap = null
        val (imageX, imageY) = viewTapToImageCoords(tap.first, tap.second)
        val hit = raycastImage(frame, imageX, imageY)
        if (hit == null) {
            // Global constraint 2: a tap is NEVER silent.
            _tapCandidate.update { it.reduce(TapEvent.RaycastMissed) }
            return
        }
        focusImagePoint = imageX to imageY
        focusWorld = Triple(hit.x, hit.y, hit.z)
        focusUntilMs = Clock.System.now().toEpochMilliseconds() + FOCUS_DURATION_MS
        val latLng = localToLatLng(hit.x, hit.z) ?: run {
            _tapCandidate.update { it.reduce(TapEvent.RaycastMissed) }
            return
        }
        _tapCandidate.update {
            it.reduce(TapEvent.RaycastHit(latLng.first, latLng.second, hit.distance))
        }
        dispatch_async(dispatch_get_main_queue()) { triggerHapticFeedback() }
        classifyTappedRegion(frame, imageX, imageY)
    }

    /**
     * Classify a crop centred on the tap, preserving surrounding context, and
     * feed the result to the reducer. A tap bypasses
     * [TrashDetectionRules.autoPromotionLabelFrom]'s parent-context suppression
     * on purpose (constraint 11): the user may confirm a detached tire.
     *
     * A null label is NOT a failure — Option A: the candidate still reaches
     * AWAITING_CONFIRMATION as "Unknown litter".
     */
    private fun classifyTappedRegion(frame: ARFrame?, imageX: Double, imageY: Double) {
        val observations = classifyRegion(frame, imageX, imageY, TAP_CROP_FRACTION)
        val label = TrashDetectionRules.trashLabelFrom(observations)
        val confidence = label?.let { TrashDetectionRules.trashConfidenceFor(it, observations) }
        _tapCandidate.update { it.reduce(TapEvent.Classified(label, confidence)) }
    }
```

Add near `FOCUS_DURATION_MS` (`ArEnvScan.kt:107`):

```kotlin
    /** Side of the square crop classified around a tap, as a fraction of the
     *  image's smaller dimension. Wide enough to keep context (a bottle needs
     *  the ground around it), tight enough to exclude the rest of the scene. */
    private const val TAP_CROP_FRACTION = 0.35
```

Implement `classifyRegion(frame, imageX, imageY, fraction): List<ClassificationObservation>` next to the existing `saliencyOnDemand` (`ArEnvScan.kt:795`). It must:
1. take the frame's pixel buffer (`native?.pixelBufferOrNull()`), returning `emptyList()` if absent;
2. build a `VNImageRequestHandler` with `regionOfInterest` set to the normalized square centred on `(imageX, imageY)`, clamped to `[0,1]` on both axes;
3. run a `VNClassifyImageRequest`;
4. map results to `ClassificationObservation(identifier, confidence)`.

If `localToLatLng` does not already exist as the inverse of the file's `latLngToLocal`, add it beside that function using the same `refLat`/`refLng`/`refX`/`refZ`/`lngScale` fields, returning `null` when `!hasGeoRef`.

Implement the three actions:

```kotlin
    actual fun confirmTapCandidate(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Confirmed) }
        return pending
    }

    actual fun rejectTapCandidate(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Rejected) }
        return pending
    }

    actual fun dismissTapCandidate() {
        _tapCandidate.update { it.reduce(TapEvent.Dismissed) }
    }
```

Note the read-then-reduce order: the reducer discards the state, so `pending` is captured first (this is exactly what Task 2's `reduce` KDoc requires).

- [ ] **Step 3: Implement the Android and desktop stubs**

In `composeApp/src/androidMain/.../ArEnvScan.kt` and `composeApp/src/desktopMain/.../ArEnvScan.kt`, add — matching whatever stub idiom each file already uses for `trashCandidate`:

```kotlin
    private val _tapCandidate = MutableStateFlow(TapCandidateState())
    actual val tapCandidate: StateFlow<TapCandidateState> = _tapCandidate.asStateFlow()
    actual fun confirmTapCandidate(): TapCandidateState? = null
    actual fun rejectTapCandidate(): TapCandidateState? = null
    actual fun dismissTapCandidate() {}
```

- [ ] **Step 4: Write the bottom pill**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/TapCandidatePill.kt`:

```kotlin
package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.TapCandidateState
import com.gcc.app.platform.TapPhase
import com.gcc.app.platform.tapPillCopy
import com.gcc.app.ui.theme.GccError
import kotlinx.coroutines.delay

/** An unconfirmed candidate self-dismisses after this long. Accidental taps cost nothing. */
private const val TAP_CANDIDATE_TIMEOUT_MS = 12_000L

/**
 * Bottom confirm/cancel pill for a tapped litter candidate (global constraint 2).
 * Nothing is persisted until [onConfirm]. [onDismiss] records nothing.
 */
@Composable
fun TapCandidatePill(
    state: TapCandidateState,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = tapPillCopy(state) ?: return

    // Any phase change restarts the timer; an untouched candidate expires.
    LaunchedEffect(state.phase, state.normalizedX, state.normalizedY) {
        if (state.phase == TapPhase.IDLE) return@LaunchedEffect
        delay(TAP_CANDIDATE_TIMEOUT_MS)
        onDismiss()
    }

    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = copy.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        if (copy.confirmLabel != null && copy.rejectLabel != null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onReject) {
                    Text(copy.rejectLabel, color = Color.White.copy(alpha = 0.75f))
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = GccError),
                ) {
                    Text(copy.confirmLabel, color = Color.White)
                }
            }
        }
    }
}
```

- [ ] **Step 5: Mount the pill in the scanner**

In `SpotScreen.kt`'s `CameraPhase`, alongside the existing `val trashCandidate by ...` at `SpotScreen.kt:324`, add:

```kotlin
        val tapCandidate by (arOverlay?.tapCandidate ?: remember { MutableStateFlow(TapCandidateState()) })
            .collectAsState()
```

Then, inside the full-bleed `Box` (opened at `SpotScreen.kt:895`), directly **after** the existing `if (trashCandidate == null) DetectionReticleOverlay(...)` line (`SpotScreen.kt:1089`), add:

```kotlin
        // A tapped candidate outranks the passive auto-detect reticle.
        if (trashCandidate == null && tapCandidate.phase != TapPhase.IDLE) {
            TapCandidatePill(
                state = tapCandidate,
                onConfirm = {
                    arOverlay?.confirmTapCandidate()?.let { confirmed ->
                        viewModel.addConfirmedTapItem(confirmed)
                    }
                },
                onReject = {
                    arOverlay?.rejectTapCandidate()?.let { rejected ->
                        viewModel.addRejectedTapItem(rejected)
                    }
                },
                onDismiss = { arOverlay?.dismissTapCandidate() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
```

`bottom = 96.dp` clears the Complete Scan pill's band, matching the minimap's own clearance at `CameraCapture.kt:234`.

`addConfirmedTapItem` and `addRejectedTapItem` are added in Task 7. Until then this step will not compile — implement Tasks 5 and 7 together, or stub the two ViewModel functions here and fill them in Task 7. **If you stub them, they must be empty functions with a `TODO(Task 7)` comment, never silent no-ops that ship.**

- [ ] **Step 6: Verify the full gates**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`. The iOS link is the real check here — it is what catches a missing `actual`.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
        composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
        composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArEnvScan.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/TapCandidatePill.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt
git commit -m "feat(scan): tap opens a confirmable litter candidate"
```

---

### Task 6: Litter evidence and separate summary counts

Global constraint 12, and the fix for "5 Items tagged" counting vegetation and for the leaves report. Today `completeScan` files a report when `aiAnalysis.trashTypes` is non-empty — that is the **whole-frame classifier** filing a report, which constraint 10 forbids.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ObservedItemCounts.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ObservedItemCountsTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt` (`completeScan`, currently `SpotViewModel.kt:445-487`)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt:264-268` and `ResultPhase` (signature `SpotScreen.kt:1867`)

**Interfaces:**
- Produces: `countLitter(items)`, `countFeatures(items)`, `hasUserConfirmedLitter(items)`, and the `ObservedSpotItem.provenance` values `PROVENANCE_USER_CONFIRMED`, `PROVENANCE_AUTO_DETECTED`, `PROVENANCE_USER_REJECTED`.
- Consumes: `ObservedSpotItem` (`shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt:29-58`).

- [ ] **Step 1: Add the `provenance` field to the shared model**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt`, add to `ObservedSpotItem` as the last property (nullable with a default, so every existing serialized payload still decodes):

```kotlin
    /** How this observation came to exist: see ObservedItemCounts' PROVENANCE_* constants.
     *  Null on legacy payloads written before provenance existed. */
    val provenance: String? = null,
```

- [ ] **Step 2: Write the failing test**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ObservedItemCountsTest.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservedItemCountsTest {

    private fun item(
        domain: String?,
        itemType: String = "plastic_bottle",
        provenance: String? = null,
    ) = ObservedSpotItem(
        lat = 41.86, lng = -87.72, itemType = itemType, domain = domain, provenance = provenance,
    )

    @Test
    fun litterAndFeaturesAreCountedSeparately() {
        val items = listOf(
            item("litter"),
            item("vegetation", "tree"),
            item("construction", "fence"),
            item(null), // legacy: null domain means litter
        )
        assertEquals(2, countLitter(items))
        assertEquals(2, countFeatures(items))
    }

    @Test
    fun rejectedItemsCountAsNeitherLitterNorFeature() {
        val items = listOf(item("litter", provenance = PROVENANCE_USER_REJECTED))
        assertEquals(0, countLitter(items))
        assertEquals(0, countFeatures(items))
    }

    @Test
    fun emptyListCountsZero() {
        assertEquals(0, countLitter(emptyList()))
        assertEquals(0, countFeatures(emptyList()))
    }

    @Test
    fun onlyUserConfirmedLitterIsReportEvidence() {
        // Constraint 10: an automatic whole-frame detection may never file a report.
        assertTrue(!hasUserConfirmedLitter(listOf(item("litter", provenance = PROVENANCE_AUTO_DETECTED))))
        assertTrue(hasUserConfirmedLitter(listOf(item("litter", provenance = PROVENANCE_USER_CONFIRMED))))
    }

    @Test
    fun aConfirmedFeatureIsNotReportEvidence() {
        assertTrue(!hasUserConfirmedLitter(listOf(item("vegetation", "tree", PROVENANCE_USER_CONFIRMED))))
    }

    @Test
    fun aRejectedConfirmationIsNotReportEvidence() {
        assertTrue(!hasUserConfirmedLitter(listOf(item("litter", provenance = PROVENANCE_USER_REJECTED))))
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.ObservedItemCountsTest"
```

Expected: `BUILD FAILED` with `Unresolved reference 'countLitter'`.

- [ ] **Step 4: Write the implementation**

Create `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ObservedItemCounts.kt`:

```kotlin
package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem

/** An automatic detection. Never sufficient to file a report (constraint 10). */
const val PROVENANCE_AUTO_DETECTED = "auto_detected"

/** The user tapped it and pressed "Confirm litter". A strong training label. */
const val PROVENANCE_USER_CONFIRMED = "user_confirmed"

/** The user tapped it and pressed "Not litter". A negative training example. */
const val PROVENANCE_USER_REJECTED = "user_rejected"

/** Legacy rows carry a null domain, which has always meant litter. */
private fun ObservedSpotItem.isLitter(): Boolean = domain == null || domain == "litter"

private fun ObservedSpotItem.isRejected(): Boolean = provenance == PROVENANCE_USER_REJECTED

/** Litter the scan found. Rejected candidates are excluded — they are not litter. */
fun countLitter(items: List<ObservedSpotItem>): Int =
    items.count { !it.isRejected() && it.isLitter() }

/** Non-litter world features mapped (vegetation, construction, land use). */
fun countFeatures(items: List<ObservedSpotItem>): Int =
    items.count { !it.isRejected() && !it.isLitter() }

/**
 * Whether this session has evidence strong enough to file an incident report.
 *
 * Only a USER-CONFIRMED litter observation qualifies. A whole-frame classifier
 * label is a weak routing signal and may never file a report on its own — that
 * is how a photo of a car became a tire report, and how foliage became a
 * "leaves" report.
 */
fun hasUserConfirmedLitter(items: List<ObservedSpotItem>): Boolean =
    items.any { it.provenance == PROVENANCE_USER_CONFIRMED && it.isLitter() }
```

- [ ] **Step 5: Run the test and verify GREEN**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.ObservedItemCountsTest"
```

Expected: `BUILD SUCCESSFUL`, 6 tests, 0 failures.

- [ ] **Step 6: Gate report creation on user-confirmed litter**

In `SpotViewModel.kt`'s `completeScan` (currently `SpotViewModel.kt:445-487`), replace the `litterEvidence` computation:

```kotlin
        val litterEvidence = state.observedItems.any { it.domain == null || it.domain == "litter" } ||
            state.aiAnalysis?.trashTypes?.isNotEmpty() == true
```

with:

```kotlin
        // Constraint 10: the whole-frame classifier (aiAnalysis.trashTypes) is a
        // weak routing signal and may NOT file a report. Only a candidate the
        // user explicitly confirmed is evidence.
        val litterEvidence = hasUserConfirmedLitter(state.observedItems)
```

Add `import com.gcc.app.platform.hasUserConfirmedLitter`. Leave the rest of `completeScan` — the coverage-only archive path, `pendingSessionDraft`, `applyAiDescription()`, `submitReport()` — exactly as it is.

- [ ] **Step 7: Show the two counts separately**

In `SpotScreen.kt:264-268`, replace `itemCount = uiState.observedItems.size` with:

```kotlin
                    litterCount = countLitter(uiState.observedItems),
                    featureCount = countFeatures(uiState.observedItems),
```

Update `ResultPhase`'s signature (`SpotScreen.kt:1867`) to take `litterCount: Int, featureCount: Int` in place of `itemCount: Int`, and update its body (around `SpotScreen.kt:1890`) to render two rows with these exact labels:

- `Litter identified` → `litterCount`
- `Features mapped` → `featureCount`

Add `import com.gcc.app.platform.countFeatures` and `import com.gcc.app.platform.countLitter`.

- [ ] **Step 8: Verify the full gates**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/SpotModels.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/platform/ObservedItemCounts.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/platform/ObservedItemCountsTest.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt
git commit -m "fix(scan): only user-confirmed litter files a report; count features separately"
```

---

### Task 7: Persist confirmations and rejections with provenance

Closes Task 5's two stubs. Confirmed litter becomes a `world_observations` row carrying its provenance in the existing `attributes` jsonb (no migration, constraint 15). Rejections never become observations — they ride the R2 manifest as negative training examples.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/ArDetectedMarker.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt` (`EnvScanManifest`, `EnvScanSession.kt:61`)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt` (`addObservedItem` at `SpotViewModel.kt:236-290`; `uploadEnvScanSession` at `SpotViewModel.kt:495-577`)
- Modify: `site/src/app/api/observations/ar-detected/route.ts`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/model/ArDetectedMarkerWireCompatTest.kt`

**Interfaces:**
- Consumes: `TapCandidateState` (Task 2), `PROVENANCE_*` (Task 6).
- Produces: `SpotViewModel.addConfirmedTapItem(TapCandidateState)`, `SpotViewModel.addRejectedTapItem(TapCandidateState)`, `ArDetectedMarkerRequest.provenance`, `EnvScanManifest.rejectedItems`.

Wire compatibility is mandatory: an old app must keep POSTing successfully, and an old manifest must keep decoding. Both new fields are optional with defaults. There is an existing precedent to match — `composeApp/src/commonTest/kotlin/com/gcc/app/model/WorldObservationWireCompatTest.kt`.

- [ ] **Step 1: Write the failing wire-compat test**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/model/ArDetectedMarkerWireCompatTest.kt`:

```kotlin
package com.gcc.app.model

import com.gcc.shared.model.ArDetectedMarkerRequest
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ArDetectedMarkerWireCompatTest {

    /** A payload written by a build that predates `provenance`. */
    private val legacyJson = """
        {"lat":41.86,"lng":-87.72,"label":"tire","capturedAt":"2026-07-10T12:00:00Z"}
    """.trimIndent()

    @Test
    fun legacyPayloadWithoutProvenanceStillDecodes() {
        val decoded: ArDetectedMarkerRequest = GccJson.decodeFromString(legacyJson)
        assertEquals("tire", decoded.label)
        assertEquals("auto_detected", decoded.provenance, "legacy markers are automatic by definition")
    }

    @Test
    fun provenanceRoundTrips() {
        val marker = GccJson.decodeFromString<ArDetectedMarkerRequest>(legacyJson)
            .copy(provenance = "user_confirmed")
        val reDecoded: ArDetectedMarkerRequest = GccJson.decodeFromString(GccJson.encodeToString(marker))
        assertEquals("user_confirmed", reDecoded.provenance)
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.model.ArDetectedMarkerWireCompatTest"
```

Expected: `BUILD FAILED` with `No parameter with name 'provenance' found` / `Unresolved reference 'provenance'`.

- [ ] **Step 3: Add the wire fields**

In `shared/src/commonMain/kotlin/com/gcc/shared/model/ArDetectedMarker.kt`, add to `ArDetectedMarkerRequest` as the last property:

```kotlin
    /** "auto_detected" | "user_confirmed" | "user_rejected". Defaults to
     *  auto_detected so a marker from an older client decodes unchanged. */
    val provenance: String = "auto_detected",
```

In `shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt`, add to `EnvScanManifest` as the last property:

```kotlin
    /** Candidates the user tapped and explicitly marked "Not litter". Negative
     *  training examples for the litter detector — deliberately NOT sent to
     *  world_observations, where they would paint the map red. */
    val rejectedItems: List<ObservedSpotItem>? = null,
```

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.model.ArDetectedMarkerWireCompatTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests, 0 failures.

- [ ] **Step 5: Implement the two ViewModel entry points**

In `SpotViewModel.kt`, add a `rejectedItems` field to the class (a plain `MutableList<ObservedSpotItem>`, mirroring how `observedItemStamps` is held), and add:

```kotlin
    /** The user tapped a candidate and pressed "Confirm litter" (constraint 2). */
    fun addConfirmedTapItem(candidate: TapCandidateState) {
        val lat = candidate.lat ?: return
        val lng = candidate.lng ?: return
        addObservedItem(
            ObservedSpotItem(
                lat = lat,
                lng = lng,
                itemType = TrashItemCatalog.canonicalType(candidate.label ?: UNKNOWN_LITTER_LABEL),
                confidence = candidate.confidence,
                domain = "litter",
                provenance = PROVENANCE_USER_CONFIRMED,
            ),
        )
    }

    /** The user tapped a candidate and pressed "Not litter" — a negative example.
     *  Kept OUT of world_observations; it rides the R2 manifest only. */
    fun addRejectedTapItem(candidate: TapCandidateState) {
        val lat = candidate.lat ?: return
        val lng = candidate.lng ?: return
        rejectedItems.add(
            ObservedSpotItem(
                lat = lat,
                lng = lng,
                itemType = TrashItemCatalog.canonicalType(candidate.label ?: UNKNOWN_LITTER_LABEL),
                confidence = candidate.confidence,
                domain = "litter",
                provenance = PROVENANCE_USER_REJECTED,
            ),
        )
        DiagnosticsLog.info("env-scan", "negative example recorded at $lat,$lng")
    }
```

In `addObservedItem` (`SpotViewModel.kt:236-290`), pass the provenance through to the queue. Change the `ArDetectedMarkerRequest(...)` construction (`SpotViewModel.kt:270-276`) to add:

```kotlin
                            provenance = stamped.provenance ?: PROVENANCE_AUTO_DETECTED,
```

In `uploadEnvScanSession` (`SpotViewModel.kt:495-577`), add to the `EnvScanManifest(...)` construction:

```kotlin
                    rejectedItems = rejectedItems.toList().ifEmpty { null },
```

Add the imports for `TapCandidateState`, `PROVENANCE_USER_CONFIRMED`, `PROVENANCE_USER_REJECTED`, `PROVENANCE_AUTO_DETECTED`, and `UNKNOWN_LITTER_LABEL`.

- [ ] **Step 6: Persist provenance server-side without a migration**

In `site/src/app/api/observations/ar-detected/route.ts`, add `provenance?: string` to the `ArDetectedMarker` interface, and fold it into the `attributes` jsonb of the `recordWorldObservation` call:

```typescript
      const provenance = typeof marker.provenance === 'string' ? marker.provenance : 'auto_detected'
      await recordWorldObservation({
        sourceType: 'ar_auto_detect',
        lat: marker.lat,
        lng: marker.lng,
        itemType: marker.label,
        state: 'dirty',
        attributes: {
          provenance,
          ...(estimatedWeightGrams !== undefined ? { estimatedWeightGrams } : {}),
          ...(estimatedWidthMeters !== undefined ? { estimatedWidthMeters } : {}),
        },
        mediaUrl: marker.mediaUrl ?? undefined,
        capturedAt: marker.capturedAt,
      })
```

`attributes` is an existing `jsonb` column (`site/src/db/schema.ts:1685-1717`), so this needs **no migration**. Note the previous code passed `attributes: undefined` when neither estimate was present; it now always passes at least `provenance`.

Reject `user_rejected` at this endpoint — a rejection must never become a dirty observation. Immediately after parsing `provenance`:

```typescript
      if (provenance === 'user_rejected') continue
```

- [ ] **Step 7: Verify the full gates**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
cd site && npx tsc --noEmit && cd ..
```

Expected: `BUILD SUCCESSFUL`, 0 failures; `tsc` exits 0.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/model/ArDetectedMarker.kt \
        shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/SpotViewModel.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/model/ArDetectedMarkerWireCompatTest.kt \
        site/src/app/api/observations/ar-detected/route.ts
git commit -m "feat(scan): record tap provenance; rejections become training data"
```

---

### Task 8: Delete the green AR bar and fix the ground grid

Global constraints 7 and 8. The "neon green bar" is `arrow()` — a `0.05 × 0.05 × 0.28 m` `UnlitMaterial` box in `UIColor.green` parented to the camera anchor, re-aimed every frame by `aimArrow()`. It performs no scanning.

**Files:**
- Modify: `iosApp/iosApp/GccArNative.swift` (lines 81-82, 115-120, 180-184, 418-429, 519-533, 548-558, 766-815)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (`renderGuidance` at `ArEnvScan.kt:1270-1295`; `makePlaneGridNode` at `ArEnvScan.kt:1244-1266`; `GRID_TILE_METERS` at `ArEnvScan.kt:116`)

**Interfaces:**
- Consumes: `ArNativeSurface.setGuidanceTarget` / `clearGuidanceTarget` (kept on the protocol — the route AR overlay also calls them; only the env-scan RealityKit *entity* is removed).
- Produces: nothing.

**Scope guard:** `ArRouteOverlay`'s SceneKit frontier arrow is a **different** object and stays. Only the RealityKit camera-attached box in `GccArNative.swift` and the env-scan SceneKit `makeArrowNode` fallback are removed.

**No unit test.** Swift/RealityKit/SceneKit have no harness in this repo. Verified by the Task 11 device gate ("green bar absent; grid confined to surfaces").

- [ ] **Step 1: Delete the arrow entity from Swift**

In `iosApp/iosApp/GccArNative.swift`:

1. Delete the `arrow()` function entirely (`GccArNative.swift:519-533`).
2. Delete `aimArrow()` entirely (`GccArNative.swift:548-558`).
3. Delete the property `private var arrowEntity: ModelEntity?` (`GccArNative.swift:82`).
4. In `init`, delete the `SceneEvents.Update` subscription that calls `aimArrow()` (`GccArNative.swift:116-120`) **and** the now-unused `updateSubscription` property if nothing else uses it. Verify with `grep -n "updateSubscription" iosApp/iosApp/GccArNative.swift` before deleting — if another feature subscribes, keep the property and remove only the `self?.aimArrow()` call.
5. In `setGuidanceTarget` (`GccArNative.swift:418-424`), delete `arrow().isEnabled = true`. The function keeps recording `guidanceTarget` — the minimap and text prompt consume guidance now.
6. In `clearGuidanceTarget` (`GccArNative.swift:426-429`), delete `arrowEntity?.isEnabled = false`.
7. In `teardown()` (`GccArNative.swift:180-184`), delete the two `arrowEntity` lines. Keep the `veilEntity` and `cameraAnchor` lines.

- [ ] **Step 2: Delete the SceneKit arrow fallback from Kotlin**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`, rewrite `renderGuidance` (`ArEnvScan.kt:1270-1295`) to:

```kotlin
    /**
     * Guidance is a minimap ring and a text prompt (global constraint 8). The
     * camera-attached arrow was deleted: it rendered as an unexplained neon
     * green bar across the viewfinder and performed no scanning.
     *
     * The native target is still set so the surface can reason about the
     * frontier; nothing is drawn in the camera view.
     */
    private fun renderGuidance() {
        val target = guidanceLatLng
        if (!hasGeoRef || target == null) {
            nativeSurface?.clearGuidanceTarget()
            return
        }
        val local = latLngToLocal(target.first, target.second, refLat, refLng, refX, refZ, lngScale)
        nativeSurface?.setGuidanceTarget(local.first, local.second)
    }
```

Then delete `makeArrowNode`, the `arrowNode` field, and the `frontierTarget` field from this file — but first run `grep -n "arrowNode\|frontierTarget\|makeArrowNode" composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` and delete only what is unreferenced afterward. **`ArRouteOverlay` has its own identically-named members in a different file — do not touch them.**

- [ ] **Step 3: Make the RealityKit grid non-emissive**

In `GccArNative.swift`, in `gridMaterial` (`GccArNative.swift:790-815`), remove the emissive channel and let the base color carry the grid:

```swift
    private func gridMaterial(_ texture: TextureResource?, tint: UIColor, uvScale: Float) -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        if let texture {
            material.baseColor = .init(tint: tint, texture: .init(texture))
        } else {
            material.baseColor = .init(tint: tint)
        }
        // Global constraint 7: the grid is a surface marking, not a light source.
        // It used to glow (emissiveIntensity 2.0) straight through vegetation,
        // fences and parked cars.
        material.emissiveIntensity = 0
        material.roughness = 1.0
        material.metallic = 0.0
        material.blending = .transparent(opacity: .init(floatLiteral: 1.0))
        if uvScale != 1 {
            material.textureCoordinateTransform.scale = SIMD2(repeating: uvScale)
        }
        return material
    }
```

In `planeMaterial` (`GccArNative.swift:766-789`), soften the tint:

```swift
        let tint = UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.28)
```

- [ ] **Step 4: Occlude the grid against real geometry**

Depth occlusion for the RealityKit path comes from ARKit scene understanding, not from the material. In `GccArNative.swift`, where `arView` is configured (near the `arView.scene.addAnchor(cameraAnchor)` call at `GccArNative.swift:115`), add:

```swift
        // Global constraint 7: the grid must not draw over plants, fences, or
        // cars. On LiDAR devices ARKit's reconstructed mesh occludes it.
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            arView.environment.sceneUnderstanding.options.insert(.occlusion)
        }
```

This is capability-gated: non-LiDAR devices simply keep the plane-anchored grid with no mesh occlusion, which is the honest best available. Record that limitation in the commit message.

- [ ] **Step 5: Make the SceneKit fallback grid read depth and stop emitting**

In `ArEnvScan.kt`, rewrite `makePlaneGridNode`'s material block (`ArEnvScan.kt:1244-1266`):

```kotlin
        val material = SCNMaterial()
        material.diffuse.contents = texture
        material.diffuse.wrapS = platform.SceneKit.SCNWrapModeRepeat
        material.diffuse.wrapT = platform.SceneKit.SCNWrapModeRepeat
        // Global constraint 7: no emission channel — the grid is not a light.
        material.doubleSided = true
        // Obey the depth buffer so real geometry in front of the ground hides
        // the grid. Still does NOT write depth: a translucent surface that
        // writes depth occludes whatever is drawn after it.
        material.readsFromDepthBuffer = true
        material.writesToDepthBuffer = false
        material.transparency = 0.28
        plane.materials = listOf(material)
```

- [ ] **Step 6: Set the 1 m minor / 3 m major grid interval**

In `ArEnvScan.kt:116`, change:

```kotlin
    private const val GRID_TILE_METERS = 1.5f
```

to:

```kotlin
    /** One texture tile == one metre (constraint 7's minor interval). The
     *  texture itself draws a heavier line every 3rd tile for the major
     *  interval, and 1.0 stays an exact divisor of CoverageTracker's 3m cells. */
    private const val GRID_TILE_METERS = 1.0f
```

In `GccArNative.swift:30-34`, change `gridTileMeters` from `1.5` to `1.0` to match. **These two constants must stay equal** — the RealityKit and SceneKit paths phase the same lattice.

In `makeGridTexture` (`ArEnvScan.kt`, called at `ArEnvScan.kt:1245`), draw a 3×3 tile texture with a thin line on every cell boundary and a heavier line on the outer boundary, so tiling yields a 1 m minor / 3 m major grid. Keep the existing `UIColor.colorWithRed(0.20, 0.85, 0.45, …)` hue; drop the alpha to `0.5` for the minor lines and keep `0.9` for the major lines.

- [ ] **Step 7: Verify it builds**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` and `** BUILD SUCCEEDED **`. **Purge `composeApp/build/xcode-frameworks` before the `xcodebuild` run** — a stale framework silently ships the old UI (documented repo gotcha). Do not pipe `xcodebuild` through `tail` and trust the exit code; check for the literal `** BUILD SUCCEEDED **` string.

- [ ] **Step 8: Commit**

```bash
git add iosApp/iosApp/GccArNative.swift \
        composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt
git commit -m "fix(scan): delete the camera-attached green bar; ground grid obeys depth"
```

---

### Task 9: Safe-area top rail

Global constraint 9. `ScanQualityBadge` already applies `.statusBarsPadding()` (`SpotScreen.kt:1090-1105`); `ConfirmingOutdoorsChip` (`SpotScreen.kt:1155-1158`) and `CaptureModeBadge` (`SpotScanCapture.kt:253-258`) do not, so they clip under the Dynamic Island. `ArRouteScreen.kt:482-487` shows the correct pattern.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt:1155-1158`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt:253-258`

**Interfaces:** none changed.

**No unit test.** Compose layout has no harness here, and any "test" that a modifier chain contains `statusBarsPadding()` would be asserting on source text, not behavior. Verified by the Task 11 simulator screenshot and device gate.

- [ ] **Step 1: Fix `ConfirmingOutdoorsChip` in the scanner**

In `SpotScreen.kt:1155-1158`, change the modifier to match `ArRouteScreen.kt:485`:

```kotlin
        ConfirmingOutdoorsChip(
            visible = outdoorState == OutdoorState.UNCERTAIN,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
        )
```

Confirm `androidx.compose.foundation.layout.statusBarsPadding` is imported in `SpotScreen.kt` (it is — `ScanQualityBadge` uses it at `SpotScreen.kt:1101`).

- [ ] **Step 2: Fix `CaptureModeBadge`**

In `SpotScanCapture.kt:253-258`:

```kotlin
        CaptureModeBadge(
            deviceClass = deviceCapabilities.deviceClass,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 8.dp),
        )
```

Add `import androidx.compose.foundation.layout.statusBarsPadding` to `SpotScanCapture.kt`.

`CaptureModeBadge` (TopEnd) and `ScanQualityBadge` (TopEnd) now compete for the same corner. `ScanQualityBadge` uses `top = 12.dp, end = 16.dp`; give `CaptureModeBadge` `end = 8.dp` and let the quality badge sit outboard, or — preferred, and what the spec's "one safe-area-aware horizontal rail" asks for — move `CaptureModeBadge` to `top = 12.dp` and have `ScanQualityBadge` offset below it with `top = 52.dp`. Pick one, apply it, and note which you picked in the commit body. Do not leave both at the same offset.

- [ ] **Step 3: Verify it compiles**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`, test count unchanged, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt
git commit -m "fix(scan): top pills respect the safe-area inset"
```

---

### Task 10: Persistent heading cone and a readable minimap

Global constraints 5 and 6. `ScanMinimapOverlay` has **no** `headingDegrees` parameter — the cone was never merely disabled, it was never drawn there. `CameraCapture.kt:242-252` passes `currentLocation = null, gpsHeading = null` to the minimap's `GccMap`, so `GccMap`'s own cone layer (`GccMap.ios.kt:1283-1292`) can never render either.

The cone must be drawn in `ScanMinimapOverlay`'s Canvas so it shares the one authoritative projection (`toOffset`, `ScanMinimapOverlay.kt:118-126`) with the puck, beam, and coverage — that is what the spec means by "one authoritative projection snapshot."

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/HeadingCone.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/ui/map/HeadingConeTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ScanMinimapOverlay.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt:242-270`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt` (parameter pass-through)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt` (pass `gpsHeading`, already collected at `SpotScreen.kt:313`)

**Interfaces:**
- Produces: `headingConePolygon(selfLat, selfLng, headingDegrees, radiusMeters, spreadDegrees, arcSteps): List<Pair<Double, Double>>`.
- Consumes: `CoverageTracker.SEEN_RANGE_METERS` (= 55.0), `visibilityBeamPolygon` (unchanged), `mapOverlayZoomBias`.

`headingConeGeoJson` (`HeadingCone.kt:20-47`) already builds this ring as a GeoJSON string for the main map's `FillLayer`. Extract the geometry into `headingConePolygon` and have `headingConeGeoJson` delegate to it — DRY, and the polygon is what the Canvas needs.

- [ ] **Step 1: Write the failing geometry test**

Create `composeApp/src/commonTest/kotlin/com/gcc/app/ui/map/HeadingConeTest.kt`, matching `VisibilityBeamTest.kt`'s idiom:

```kotlin
package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.METERS_PER_DEG_LAT
import com.gcc.app.platform.lngScaleFor
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadingConeTest {

    private val selfLat = 41.8600
    private val selfLng = -87.7200

    private fun meters(lat: Double, lng: Double): Double {
        val north = (lat - selfLat) * METERS_PER_DEG_LAT
        val east = (lng - selfLng) * METERS_PER_DEG_LAT * lngScaleFor(selfLat)
        return sqrt(north * north + east * east)
    }

    @Test
    fun ringStartsAndEndsAtSelf() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f)
        assertTrue(ring.size >= 3)
        assertEquals(selfLat to selfLng, ring.first())
        assertEquals(selfLat to selfLng, ring.last())
    }

    @Test
    fun coneAlwaysRendersEvenWithNoRaycasts() {
        // Constraint 5: the cone is not a function of ray hits. It only needs a heading.
        assertTrue(headingConePolygon(selfLat, selfLng, headingDegrees = 137f).size >= 3)
    }

    @Test
    fun outerRadiusIsTheSeenRange() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f)
        val reach = ring.drop(1).dropLast(1).maxOf { (la, ln) -> meters(la, ln) }
        assertTrue(
            abs(reach - CoverageTracker.SEEN_RANGE_METERS) < 0.5,
            "cone reach $reach should be SEEN_RANGE_METERS",
        )
    }

    @Test
    fun northHeadingPointsNorth() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f, arcSteps = 2)
        val tip = ring[2] // arc midpoint at arcSteps=2 lies on the heading
        assertTrue(tip.first > selfLat, "north heading must increase latitude")
        assertTrue(abs(tip.second - selfLng) < 1e-6, "north heading must not shift longitude")
    }

    @Test
    fun eastHeadingPointsEast() {
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 90f, arcSteps = 2)
        val tip = ring[2]
        assertTrue(tip.second > selfLng, "east heading must increase longitude")
        assertTrue(abs(tip.first - selfLat) < 1e-6, "east heading must not shift latitude")
    }

    @Test
    fun spreadIsFiftyFiveDegrees() {
        // Constraint 5. Sample the two arc extremes and compare their bearings.
        val ring = headingConePolygon(selfLat, selfLng, headingDegrees = 0f, arcSteps = 2)
        val left = ring[1]
        val right = ring[3]
        fun bearing(p: Pair<Double, Double>): Double {
            val north = (p.first - selfLat) * METERS_PER_DEG_LAT
            val east = (p.second - selfLng) * METERS_PER_DEG_LAT * lngScaleFor(selfLat)
            return kotlin.math.atan2(east, north) * 180.0 / kotlin.math.PI
        }
        assertTrue(abs((bearing(right) - bearing(left)) - 55.0) < 0.5)
    }

    @Test
    fun geoJsonDelegatesToTheSamePolygon() {
        // The main map's FillLayer and the minimap Canvas must not drift apart.
        val json = headingConeGeoJson(selfLat, selfLng, 0f)
        assertTrue(json.startsWith("""{"type":"FeatureCollection""""))
        assertTrue(json.contains("Polygon"))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.ui.map.HeadingConeTest"
```

Expected: `BUILD FAILED` with `Unresolved reference 'headingConePolygon'`.

- [ ] **Step 3: Extract the polygon and delegate the GeoJSON to it**

Rewrite `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/HeadingCone.kt`:

```kotlin
package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Closed (lat, lng) ring for the persistent heading cone: apex at the user,
 * arc at [radiusMeters] spanning [spreadDegrees] centred on [headingDegrees].
 *
 * Global constraint 5: this is a pure function of the compass heading. It does
 * NOT depend on raycasts, so the cone stays on screen when the AR fan returns
 * nothing. The live [visibilityBeamPolygon] is drawn inside it and IS a
 * function of ray hits — the two must remain visually distinct.
 */
fun headingConePolygon(
    selfLat: Double,
    selfLng: Double,
    headingDegrees: Float,
    radiusMeters: Double = CoverageTracker.SEEN_RANGE_METERS,
    spreadDegrees: Double = 55.0,
    arcSteps: Int = 12,
): List<Pair<Double, Double>> {
    val metersPerDegLat = 111_000.0
    val metersPerDegLng = 111_320.0 * cos(selfLat * PI / 180.0)
    val start = headingDegrees - spreadDegrees / 2.0
    val step = spreadDegrees / arcSteps

    val ring = ArrayList<Pair<Double, Double>>(arcSteps + 3)
    ring.add(selfLat to selfLng)
    for (i in 0..arcSteps) {
        // Bearing: 0 = north (+lat), 90 = east (+lng).
        val bearingRad = (start + step * i) * PI / 180.0
        val dLat = radiusMeters * cos(bearingRad) / metersPerDegLat
        val dLng = radiusMeters * sin(bearingRad) / metersPerDegLng
        ring.add((selfLat + dLat) to (selfLng + dLng))
    }
    ring.add(selfLat to selfLng)
    return ring
}

/**
 * The same cone as a GeoJSON FeatureCollection for MapLibre FillLayers.
 * Delegates to [headingConePolygon] so the main map's layer and the minimap's
 * Canvas can never drift apart.
 *
 * Note the historical default radius of 22m is preserved for the main map's
 * user-location cone, which is a UI affordance, not the 55m scan range.
 */
fun headingConeGeoJson(
    lat: Double,
    lng: Double,
    headingDegrees: Float,
    radiusMeters: Double = 22.0,
    spreadDegrees: Double = 55.0,
    arcSteps: Int = 10,
): String {
    val ring = headingConePolygon(lat, lng, headingDegrees, radiusMeters, spreadDegrees, arcSteps)
        .joinToString(",") { (la, ln) -> "[$ln,$la]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$ring]]},"properties":{}}]}"""
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.ui.map.HeadingConeTest"
```

Expected: `BUILD SUCCESSFUL`, 7 tests, 0 failures.

Then run the existing map suites to prove the GeoJSON delegation broke nothing:

```bash
./gradlew :composeApp:desktopTest --tests "com.gcc.app.ui.map.*"
```

Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Draw the cone in the minimap, under the beam**

In `ScanMinimapOverlay.kt`, add a parameter after `visibilityBeam`:

```kotlin
    /** Live compass heading. The persistent 55° cone rotates with it and is
     *  drawn even when [visibilityBeam] is empty (global constraint 5). */
    headingDegrees: Float? = null,
```

Add the cone colour beside `VeilSlate` (`ScanMinimapOverlay.kt:28-30`):

```kotlin
private val ConeBlue = Color(0xFF38BDF8)
```

Inside the `Canvas` block, **before** the `if (visibilityBeam.size >= 3)` block (`ScanMinimapOverlay.kt:205`), draw the cone so the brighter beam paints on top of it:

```kotlin
        // Persistent facing cone. Answers "where am I pointing?" — independent
        // of whether any ray landed this tick (constraint 5).
        if (headingDegrees != null) {
            val cone = headingConePolygon(centerLat, centerLng, headingDegrees)
            if (cone.size >= 3) {
                val conePath = Path()
                cone.forEachIndexed { index, (lat, lng) ->
                    val p = toOffset(lat, lng)
                    if (index == 0) conePath.moveTo(p.x, p.y) else conePath.lineTo(p.x, p.y)
                }
                conePath.close()
                drawPath(conePath, color = ConeBlue.copy(alpha = 0.16f))
                drawPath(conePath, color = ConeBlue.copy(alpha = 0.55f), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
```

Add `import com.gcc.app.ui.map.headingConePolygon`.

- [ ] **Step 6: Lighten the veil**

In `ScanMinimapOverlay.kt:203`, change:

```kotlin
        drawPath(veil, color = VeilSlate.copy(alpha = 0.30f))
```

to:

```kotlin
        // Field report (Blake, TestFlight): "too dark to see anything" in
        // daylight. The basemap is already the light OpenFreeMap Liberty
        // style — the dimness was entirely this scrim. Unscanned ground must
        // still read as fog, so the veil stays; it just stops drowning the
        // streets, the cone, and the coverage paint.
        drawPath(veil, color = VeilSlate.copy(alpha = 0.18f))
```

- [ ] **Step 7: Thread the heading through and stop the basemap lagging**

In `CameraCapture.kt:242-252`, keep `currentLocation = null` (it prevents the second puck — see the existing comment) but add `instantCamera = true`, because the Compose overlay recentres on every GPS fix while `GccMap`'s camera only eases past a minimum delta, which is exactly the "overlay/basemap state can diverge" the spec describes:

```kotlin
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = currentLocation.lat,
                        centerLng = currentLocation.lng,
                        zoom = MapStyles.ZOOM_ACTIVE_JOB,
                        // currentLocation=null: ScanMinimapOverlay draws the one
                        // authoritative centered dot — the basemap puck here was
                        // a SECOND dot (field report). Framing uses centerLat/Lng.
                        currentLocation = null,
                        gpsHeading = null, // the cone is drawn by ScanMinimapOverlay, on its own projection
                        // The overlay recentres every GPS fix; without this the
                        // basemap eases behind it and the two visibly drift.
                        instantCamera = true,
                    )
```

In the `ScanMinimapOverlay(...)` call directly below (`CameraCapture.kt:255-270`), add `headingDegrees = headingDegrees,`.

Add `headingDegrees: Float? = null` to `CameraCapture`'s parameter list and to `SpotScanCapture`'s parameter list (`SpotScanCapture.kt:43-85`), passing it straight through — this mirrors the existing `visibilityBeam` pass-through exactly.

In `SpotScreen.kt`, pass `headingDegrees = gpsHeading` to `SpotScanCapture`. `gpsHeading` is already collected at `SpotScreen.kt:313` (`locationProvider.headingDegrees.collectAsState()`); no new plumbing is needed.

- [ ] **Step 8: Verify the full gates**

Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/HeadingCone.kt \
        composeApp/src/commonTest/kotlin/com/gcc/app/ui/map/HeadingConeTest.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/ScanMinimapOverlay.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/CameraCapture.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/SpotScanCapture.kt \
        composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotScreen.kt
git commit -m "feat(scan): persistent heading cone; readable daylight minimap"
```

---

### Task 11: Fix the report-photo blob collision

Production returns HTTP 500 `This blob already exists`. `site/src/app/api/spots/upload/route.ts:38-44` builds `spots/{workerId}/{Date.now()}-{key}.jpg` and calls `put()` **without** `addRandomSuffix`, which defaults to `false` for `put()` in `@vercel/blob@2.3.1` (verified in `site/node_modules/@vercel/blob/dist/index.d.ts:356`). Two uploads in the same millisecond with the same form key (`photo_0`) collide.

**Files:**
- Create: `site/src/lib/spot-photo-path.ts`
- Create: `site/src/lib/spot-photo-path.test.ts`
- Modify: `site/src/app/api/spots/upload/route.ts`

**Interfaces:**
- Produces: `spotPhotoPathname(workerId: number, formKey: string): string`.
- Consumes: nothing.

**Constraint 14:** site tests are `node:test` + `tsx`. Run them with `npx tsx --test <file>`. Note that `tsx` resolves the `@/` tsconfig alias for runtime value imports, but this helper needs no imports.

- [ ] **Step 1: Write the failing test**

Create `site/src/lib/spot-photo-path.test.ts`:

```typescript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spotPhotoPathname } from './spot-photo-path'

test('namespaces photos under the worker', () => {
  assert.ok(spotPhotoPathname(42, 'photo_0').startsWith('spots/42/'))
})

test('ends in .jpg', () => {
  assert.ok(spotPhotoPathname(42, 'photo_0').endsWith('.jpg'))
})

test('sanitises a hostile form key', () => {
  const path = spotPhotoPathname(42, '../../etc/passwd')
  assert.equal(path.includes('..'), false)
  assert.equal(path.split('/').length, 3, 'must not escape spots/<worker>/')
})

test('an empty form key still yields a valid path', () => {
  const path = spotPhotoPathname(42, '')
  assert.ok(path.startsWith('spots/42/'))
  assert.ok(path.endsWith('.jpg'))
})
```

Note there is deliberately **no** test asserting uniqueness of two same-millisecond calls: uniqueness is delivered by `addRandomSuffix: true` inside `put()`, not by this helper, and a test that called `spotPhotoPathname` twice and asserted inequality would be asserting on `Date.now()` resolution — flaky and misleading. The uniqueness guarantee is asserted in Step 4's route change instead.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd site && npx tsx --test src/lib/spot-photo-path.test.ts
```

Expected: FAIL — `Cannot find module './spot-photo-path'`.

- [ ] **Step 3: Write the helper**

Create `site/src/lib/spot-photo-path.ts`:

```typescript
/**
 * Blob pathname for one uploaded spot photo.
 *
 * This is a PREFIX, not a unique name. Uniqueness comes from `put()`'s
 * `addRandomSuffix: true` — which defaults to FALSE, and whose absence is why
 * production returned HTTP 500 "This blob already exists" when two uploads
 * landed in the same millisecond with the same multipart form key (`photo_0`).
 */
export function spotPhotoPathname(workerId: number, formKey: string): string {
  // Strip anything that could escape the worker's folder or confuse the store.
  const safeKey = formKey.replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 32) || 'photo'
  return `spots/${workerId}/${Date.now()}-${safeKey}.jpg`
}
```

- [ ] **Step 4: Run the test and verify GREEN, then fix the route**

Run:

```bash
cd site && npx tsx --test src/lib/spot-photo-path.test.ts
```

Expected: PASS, 4/4.

Then in `site/src/app/api/spots/upload/route.ts`, replace the filename construction and the `put()` call (`route.ts:38-44`):

```typescript
      const blob = await put(spotPhotoPathname(auth.workerId, key), processed, {
        access: 'public',
        contentType: 'image/jpeg',
        // Defaults to FALSE for put(). Without it, two uploads in the same
        // millisecond with the same form key collide and Blob answers
        // HTTP 500 "This blob already exists" — the production failure.
        addRandomSuffix: true,
      })
```

Add `import { spotPhotoPathname } from '@/lib/spot-photo-path'` and delete the now-unused `const timestamp = Date.now()` and `const filename = ...` lines.

- [ ] **Step 5: Verify typecheck and build**

Run:

```bash
cd site && npx tsc --noEmit && npm run build
```

Expected: `tsc` exits 0; `next build` exits 0 and prints `Compiled successfully`. Do not pipe through `tail` and trust the exit code — this shell is zsh, where `$PIPESTATUS` is a bash-ism. Capture the exit code directly.

- [ ] **Step 6: Commit**

```bash
git add site/src/lib/spot-photo-path.ts site/src/lib/spot-photo-path.test.ts \
        site/src/app/api/spots/upload/route.ts
git commit -m "fix(api): collision-proof spot photo blob names"
```

---

### Task 12: Build, install to the simulator, and upload to TestFlight

Blake wants the simulator build refreshed while TestFlight processes, so he can start an early punchlist.

**Files:** none — verification and release only. Fix code only if a gate exposes a defect.

**Interfaces:** verifies all outputs of Tasks 1–11 together.

**Honest scope note.** The environment scanner **cannot run on the iOS Simulator** — ARKit needs a real camera. What the simulator *can* verify is only the non-AR surface. State this plainly to Blake rather than implying a full check:

| Verifiable on the simulator | Requires the physical iPhone |
|---|---|
| Safe-area pills unclipped (Task 9) | Heading cone rotating with the phone (Task 10) |
| Result screen shows `Litter identified` / `Features mapped` separately (Task 6) | Live beam changing reach inside the cone (Task 10) |
| App launches, admin/map tabs render | Green bar absent; grid confined to surfaces (Task 8) |
| No launch crash from the new expect/actuals | Tap → reticle + confirm/cancel pill (Tasks 2, 3, 5) |
| | Tire-on-car not auto-promoted (Task 4) |
| | Screen does not dim during a 90s scan (Task 1) |

- [ ] **Step 1: Run every automated gate**

```bash
./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64
cd site && npx tsx --test src/lib/spot-photo-path.test.ts && npx tsc --noEmit && npm run build && cd ..
```

Expected: `BUILD SUCCESSFUL`, 0 failures; site tests pass; `tsc` exits 0; `next build` exits 0.

Report any pre-existing unrelated failure verbatim. **Do not claim a passing build you did not observe.** If the iOS link dies with a bare Gradle worker stack trace, grep for `No space left on device` before any other diagnosis, and reclaim disk from `*/build` directories in stale worktrees.

- [ ] **Step 2: Build and install to the running simulator**

Purge the stale framework first — a cached `xcode-frameworks` silently ships the previous UI, and this has bitten the repo before:

```bash
rm -rf composeApp/build/xcode-frameworks
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' \
  -derivedDataPath /tmp/gcc-sim-build build
```

Expected: the literal string `** BUILD SUCCEEDED **`. Then install and launch:

```bash
xcrun simctl install booted "$(find /tmp/gcc-sim-build -name 'iosApp.app' -maxdepth 6 | head -1)"
xcrun simctl launch booted com.globalcommunitycleaners.worker
```

Take a screenshot of the scanner's top rail and the result screen and attach them to the report:

```bash
xcrun simctl io booted screenshot /tmp/gcc-toprail.png
```

- [ ] **Step 3: Bump the build number and upload to TestFlight**

Query the real latest build first — the `Info.plist` number is **not** authoritative and has been wrong before:

```bash
cd iosApp && fastlane run latest_testflight_build_number
```

Set `CFBundleVersion` in `iosApp/iosApp/Info.plist` to that value **+1**. Then, because a full `fastlane` run exceeds the 600 s background-Bash cap, launch it detached and poll:

```bash
cd iosApp && nohup zsh -lc 'fastlane ios release; echo "FASTLANE_EXIT=$?"' > /tmp/fastlane.log 2>&1 &
until grep -q "FASTLANE_EXIT=" /tmp/fastlane.log; do sleep 15; done
grep "FASTLANE_EXIT=" /tmp/fastlane.log
grep -c "Successfully uploaded" /tmp/fastlane.log
```

Expected: `FASTLANE_EXIT=0` and at least one `Successfully uploaded`. The ASC key must exist at `~/.appstoreconnect/private_keys/AuthKey_XKAYZ4N88V.p8` (the real key lives at `~/private_keys/`; `cp` it across if the lane cannot find it). Revert the lane's incidental `report.xml` and `.pbxproj` mutations afterwards.

- [ ] **Step 4: Commit the build bump**

```bash
git add iosApp/iosApp/Info.plist
git commit -m "chore(ios): bump build for env-scan reliability TestFlight"
```

- [ ] **Step 5: Physical iPhone release gate — hand off to Blake**

This gate cannot be run from here. Report it to Blake as a checklist, one clip per issue, 30–60 s each, starting ~3 s before the action:

1. Top pills fully visible below the Dynamic Island.
2. Minimap readable in daylight; user puck and **persistent cone rotating with the phone**.
3. Live beam changes reach **without** replacing the cone.
4. Green camera bar **absent**; grid stays on the ground and does not draw over plants/fences/cars.
5. A clear litter tap → immediate pulse + reticle + bottom `Is this litter?` pill; `Confirm litter` persists it.
6. An unrecognised object still offers confirmation as `Unknown litter`.
7. An attached car tire does **not** auto-promote; a detached tire can still be tapped and confirmed.
8. Tapping the viewfinder does **not** increase the photo count.
9. The screen does not dim during a 90-second scan.
10. The result screen shows `Litter identified` and `Features mapped` as separate rows.
11. Completing a scan with zero confirmed litter files **no** report and says `Scan saved on this iPhone`.

**Do not claim this gate passed unless Blake ran it.**

---

## Self-review

**Spec coverage (Phase 1, spec line 601).** Every clause maps to a task: *restore cone* → Task 10; *remove green bar* → Task 8; *fix safe area* → Task 9; *minimap brightness* → Task 10; *implement explicit tap candidates* → Tasks 2, 3, 5, 7; *stop auto-reporting broad whole-frame classifications* → Tasks 4, 6; *fix report-photo collision* → Task 11. Blake's two added constraints: confirm/cancel pill → Tasks 2, 5; keep-awake → Task 1. Spec's separate litter/feature counts (line 56) → Task 6. Spec's provenance labels for the supervised dataset (line 121) → Task 7. Ground-grid depth occlusion (line 96) → Task 8.

**Deliberately out of scope**, and needing their own plans: durable ingest contract and per-artifact state (spec line 602); capture schema v2 (603); DGX decoder (604); reconstruction orchestration (605); map consumption and live-zoom LOD (606); marketing concepts (607). Also the spec's `Uploading x of y` / `3D processing` status rows (lines 141-147) depend on Phase 2's backend artifact table and are **not** implemented here — Task 12's gate item 11 only asserts the honest offline string, which today's code can already produce.

**Type consistency.** `TapCandidateState` / `TapPhase` / `TapEvent` / `tapPillCopy` are defined in Task 2 and used with identical names in Tasks 5 and 7. `PROVENANCE_USER_CONFIRMED` / `PROVENANCE_USER_REJECTED` / `PROVENANCE_AUTO_DETECTED` are defined in Task 6 and used in Tasks 6 and 7. `headingConePolygon`'s signature in Task 10 Step 3 matches its call in Step 5 and its test in Step 1. `spotPhotoPathname(workerId, formKey)` matches between Task 11's helper, test, and route.

**Known ordering dependency.** Task 5 Step 5 calls `viewModel.addConfirmedTapItem` / `addRejectedTapItem`, which Task 7 Step 5 defines. Execute Task 5 and Task 7 back-to-back, or stub the two functions in Task 5 with an explicit `TODO(Task 7)`. This is called out in Task 5 Step 5 rather than left for the implementer to discover.

**Untested by design, and why.** Tasks 1, 3, 8, 9 have no unit test: `idleTimerDisabled`, `detectTapGestures`, RealityKit/SceneKit materials, and Compose modifier chains have no harness in this repo (see `ArEnvScanFocusTest.kt`'s own KDoc: "no simulator runs in this repo's environment"). Each says so explicitly and names the Task 12 gate item that covers it. Per Global Constraint 21, **no placeholder test may be added to fill those checkboxes.**
