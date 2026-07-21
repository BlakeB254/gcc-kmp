# Route-Lap Tap-to-Confirm Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping trash in the block-lap AR camera raises the same reticle-free confirm pill the environment scan uses, and a confirmation files a photo-backed hotspot (lap-2 cleanup target) plus a user-confirmed world marker — closing PRD gap G2 ("Lap 1 litter tagged via the same reticle/tap flow as the env scan").

**Architecture:** Extract the env scan's tap flow (queued tap → raycast → geo-resolve → region classify → `TapCandidateState` machine) out of `ArEnvScanOverlay` into one shared iosMain `TapToConfirmEngine`; `ArEnvScanOverlay` delegates to it with zero behavior change, and `ArRouteOverlay` instantiates a second engine wired to its own geo-reference. The PRD forbids forking the AR stacks, so the engine is MOVED code, never copied. UI reuses the existing `TapCandidatePill` composable and the existing `TapCandidateState`/`tapPillCopy` commonMain pure layer as-is.

**Tech Stack:** Kotlin Multiplatform (commonMain + iosMain/androidMain/desktopMain actuals), Compose Multiplatform, ARKit via cinterop (`ArNativeSurface` RealityKit path + `ARSCNView` SceneKit fallback), Vision (`VNClassifyImageRequest`), kotlin.test.

## Global Constraints

- **Block AR must never fork from env AR** (docs/prd/block-assignment-2-lap-prd.md, foundation principle + deprecation row "Separate/forked AR stack for route laps — FORBIDDEN"). Shared engine = moved code; a copy of `raycastImage`/`classifyRegion` into ArRouteOverlay is a plan failure.
- Env-scan behavior must be byte-identical after Task 1 (pure extraction refactor; the env scan is field-validated as of TestFlight 159 — do not change its semantics).
- Every `expect class` member added needs ALL THREE actuals (iosMain, androidMain, desktopMain) or the iOS link fails.
- A tap is NEVER silent (env-scan global constraint 2): every drop path must show a pill state, a toast, or an error banner.
- Hotspots require a real uploaded before-photo (existing product rule — `RouteScanScreen.kt` refuses no-photo hotspots). A failed photo upload degrades to marker-only + visible error, never a photoless hotspot.
- Only `user_confirmed` provenance is report/training-grade evidence; tap confirms must carry it (`PROVENANCE_USER_CONFIRMED`).
- Gates for every task: `./gradlew :composeApp:desktopTest` (all green) and `./gradlew :composeApp:compileKotlinIosArm64` (clean). Final task adds the xcodebuild sim build.
- Commit after every task (small, revertable commits).

## File Structure (locked decisions)

| File | Responsibility |
|---|---|
| `composeApp/src/iosMain/kotlin/com/gcc/app/platform/TapToConfirmEngine.kt` (NEW) | The one tap flow: pending-tap queue, raycast (native + SceneKit), geo-resolve, region classify, `TapCandidateState` reducer driving, confirm/reject/dismiss. Owns `RayHit`. |
| `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` | Delegates its existing tap API to an engine instance; keeps env-only concerns (supersede-lock-on, focus bias, ground-candidate `adoptTap`, telemetry). |
| `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` | expect additions: `tapCandidate`, `focusAt`, `confirmTapCandidate`, `rejectTapCandidate`, `dismissTapCandidate`, `captureTapEvidencePhoto`. |
| `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` | Instantiates the engine (geo-ref-gated `localToLatLng`), processes taps immediately, produces evidence JPEGs via `cvPixelBufferToMarkerPhotoJpeg`. |
| `composeApp/src/{androidMain,desktopMain}/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` | No-op stubs for the new members. |
| `composeApp/src/commonMain/kotlin/com/gcc/app/platform/RouteTapConfirm.kt` (NEW) | Pure filing math: canonical type + typical-weight lbs + severity for a confirmed tap. Testable in commonTest. |
| `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt` | `confirmRouteTapItem` / `rejectRouteTapItem`: gate guard, photo upload, hotspot + provenance-carrying world marker. |
| `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt` | Tap gesture on the AR surface, `TapCandidatePill` mount, confirm/reject wiring. |
| `composeApp/src/commonTest/kotlin/com/gcc/app/platform/RouteTapConfirmTest.kt` (NEW) | Tests for the pure filing math. |
| `docs/prd/block-assignment-2-lap-prd.md` | Mark the lap-1 tap-flow requirement implemented via the shared engine. |

Out of scope (explicitly): LiDAR ground-candidate auto-detector parity on route laps (`GroundCandidateTracker` drive loop stays env-only for now — separate plan), lap-2 AR beacon (blocked on CleanScreen having no AR surface), typed hotspot glyphs on the route minimap.

---

### Task 1: Extract `TapToConfirmEngine` from `ArEnvScanOverlay` (pure refactor, zero behavior change)

**Files:**
- Create: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/TapToConfirmEngine.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt` (delete moved privates; delegate)

**Interfaces:**
- Consumes: `ArNativeSurface` (`raycast(imageX, imageY)`, `cameraPosition()`, `pixelBufferOrNull()`), `ARFrame` (SceneKit fallback: `hitTest`, `capturedImage`), commonMain `TapCandidateState`/`TapEvent`/`reduce`, `viewTapToImageCoords` (commonMain `ArTapFocusMath.kt`), `TrashDetectionRules.trashLabelFrom/trashConfidenceFor`, `triggerHapticFeedback()`.
- Produces (used by Tasks 2): `internal class RayHit(val x: Float, val y: Float, val z: Float, val distance: Float)` and
  ```kotlin
  internal class TapToConfirmEngine(
      private val nativeSurfaceProvider: () -> ArNativeSurface?,
      private val localToLatLng: (x: Float, z: Float) -> Pair<Double, Double>?,
      /** After a successful raycast+geo-resolve, before classification —
       *  env uses this for focus bias + ground-candidate adoptTap. */
      private val onRaycastHit: ((hit: RayHit, imageX: Double, imageY: Double) -> Unit)? = null,
  ) {
      val tapCandidate: StateFlow<TapCandidateState>
      fun queueTap(normalizedX: Float, normalizedY: Float)
      fun processPendingTap(frame: ARFrame?)
      fun confirm(): TapCandidateState?
      fun reject(): TapCandidateState?
      fun dismiss()
  }
  ```

- [ ] **Step 1: Create the engine file with the MOVED code**

Move (do not copy) from `ArEnvScan.kt` into the new file, adjusting only visibility and the dependency seams:
- `private class RayHit(...)` (`ArEnvScan.kt:1359`) → `internal class RayHit` (top level of the new file).
- `raycastImage(frame, imageX, imageY)` (`ArEnvScan.kt:1366-1387`) → engine private, with `nativeSurface?.let {...}` becoming `nativeSurfaceProvider()?.let {...}`.
- `classifyRegion(frame, imageX, imageY, fraction)` (`ArEnvScan.kt:1298-1327`) → engine private, same provider substitution. Bring `TAP_CROP_FRACTION = 0.35` along as a private engine constant (delete the env copy).
- The tap-machine parts of `processPendingFocusTap` (`ArEnvScan.kt:715-756`) EXCEPT the env-only lines (focus bias fields, adoptTap block) — those stay in env via the `onRaycastHit` callback.
- `classifyTappedRegion` (`ArEnvScan.kt:767-772`) → engine private `classifyTapped(frame, imageX, imageY)`.

```kotlin
package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.ARKit.ARFrame
import platform.ARKit.ARHitTestResult
import platform.ARKit.ARHitTestResultTypeEstimatedHorizontalPlane
import platform.ARKit.ARHitTestResultTypeExistingPlaneUsingGeometry
import platform.ARKit.ARHitTestResultTypeFeaturePoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNImageRequestHandler
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.sqrt

/** World hit for a normalized image-space point. Moved verbatim from
 *  ArEnvScanOverlay (was private there) — both AR overlays raycast taps
 *  through the one engine below. */
internal class RayHit(val x: Float, val y: Float, val z: Float, val distance: Float)

/** Crop side (fraction of the frame) for tap-to-identify classification. */
private const val TAP_CROP_FRACTION = 0.35

/**
 * THE tap-to-confirm flow (env-scan PRD Option A), shared by
 * [ArEnvScanOverlay] and [ArRouteOverlay] — the block PRD forbids the two
 * AR surfaces forking, so this is the env scan's tap code MOVED, not copied:
 * queued normalized-view tap → image-space inversion → raycast (native
 * RealityKit or SceneKit ARFrame.hitTest) → geo-resolve via the caller's
 * live geo reference → Vision region classify → [TapCandidateState]
 * reducer. Nothing persists until [confirm]; a raycasting tap ALWAYS
 * reaches AWAITING_CONFIRMATION.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TapToConfirmEngine(
    private val nativeSurfaceProvider: () -> ArNativeSurface?,
    private val localToLatLng: (x: Float, z: Float) -> Pair<Double, Double>?,
    private val onRaycastHit: ((hit: RayHit, imageX: Double, imageY: Double) -> Unit)? = null,
) {
    private val _tapCandidate = MutableStateFlow(TapCandidateState())
    val tapCandidate: StateFlow<TapCandidateState> = _tapCandidate.asStateFlow()

    /** Last-write-wins single slot; a following tap supersedes (same
     *  threading contract the env overlay documented on focusAt). */
    private var pendingFocusTap: Pair<Double, Double>? = null

    fun queueTap(normalizedX: Float, normalizedY: Float) {
        pendingFocusTap = normalizedX.toDouble() to normalizedY.toDouble()
        _tapCandidate.update { it.reduce(TapEvent.Tapped(normalizedX, normalizedY)) }
    }

    fun processPendingTap(frame: ARFrame?) {
        val tap = pendingFocusTap ?: return
        pendingFocusTap = null
        val (imageX, imageY) = viewTapToImageCoords(tap.first, tap.second)
        val hit = raycastImage(frame, imageX, imageY)
        if (hit == null) {
            _tapCandidate.update { it.reduce(TapEvent.RaycastMissed) }
            return
        }
        val latLng = localToLatLng(hit.x, hit.z) ?: run {
            // Surface hit, no AR↔GPS pairing yet — honest copy, not
            // "hold steady" (TapPhase.GPS_SYNCING).
            _tapCandidate.update { it.reduce(TapEvent.NoGeoRef) }
            return
        }
        _tapCandidate.update {
            it.reduce(TapEvent.RaycastHit(latLng.first, latLng.second, hit.distance))
        }
        dispatch_async(dispatch_get_main_queue()) { triggerHapticFeedback() }
        onRaycastHit?.invoke(hit, imageX, imageY)
        classifyTapped(frame, imageX, imageY)
    }

    fun confirm(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Confirmed) }
        return pending
    }

    fun reject(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Rejected) }
        return pending
    }

    fun dismiss() {
        _tapCandidate.update { it.reduce(TapEvent.Dismissed) }
    }

    private fun classifyTapped(frame: ARFrame?, imageX: Double, imageY: Double) {
        val observations = classifyRegion(frame, imageX, imageY, TAP_CROP_FRACTION)
        val label = TrashDetectionRules.trashLabelFrom(observations)
        val confidence = label?.let { TrashDetectionRules.trashConfidenceFor(it, observations) }
        _tapCandidate.update { it.reduce(TapEvent.Classified(label, confidence)) }
    }

    // raycastImage + classifyRegion bodies: moved VERBATIM from
    // ArEnvScan.kt:1366-1387 / :1298-1327 with `nativeSurface` replaced by
    // `nativeSurfaceProvider()`. (Bodies omitted here for brevity in the
    // plan ONLY because they are strict moves — the implementer copies the
    // exact code out of ArEnvScan.kt at the cited lines and deletes the
    // originals. Any edit beyond the provider substitution is a plan
    // violation.)
}
```

- [ ] **Step 2: Delegate `ArEnvScanOverlay` to the engine**

In `ArEnvScan.kt` (iosMain):

```kotlin
// Replaces: private val _tapCandidate = MutableStateFlow(TapCandidateState())
private val tapEngine = TapToConfirmEngine(
    nativeSurfaceProvider = { nativeSurface },
    localToLatLng = { x, z -> localToLatLng(x, z) }, // existing hasGeoRef-gated helper (ArEnvScan.kt:780)
    onRaycastHit = { hit, imageX, imageY ->
        // Env-only concerns, verbatim from the old processPendingFocusTap:
        focusImagePoint = imageX to imageY
        focusWorld = Triple(hit.x, hit.y, hit.z)
        focusUntilMs = Clock.System.now().toEpochMilliseconds() + FOCUS_DURATION_MS
        val scanningBeforeTap = groundCandidateTracker.activeScanning().mapTo(mutableSetOf()) { it.first }
        groundCandidateTracker.adoptTap(hit.x, hit.y, hit.z)?.let { adoptedId ->
            if (adoptedId !in scanningBeforeTap) {
                ScanSessionTelemetry.active?.detection(
                    label = null, confidence = null,
                    distanceM = groundCandidatePackets[adoptedId]?.distanceM?.toDouble(),
                    source = "tap", outcome = "accepted",
                )
            }
        }
    },
)
actual val tapCandidate: StateFlow<TapCandidateState> = tapEngine.tapCandidate
```

- `focusAt` keeps its telemetry + supersede-lock-on lines, then ends with `tapEngine.queueTap(x, y)` (delete the old two lines that set `pendingFocusTap` and reduced `Tapped`).
- `analyzeTick`'s `processPendingFocusTap(frame)` call becomes `tapEngine.processPendingTap(frame)`; delete the old `processPendingFocusTap`, `classifyTappedRegion`, `raycastImage`, `classifyRegion`, `RayHit`, `pendingFocusTap`, and `TAP_CROP_FRACTION` from `ArEnvScan.kt`. NOTE: `classifyRegion` has one other caller (`analyzeTrash`, `ArEnvScan.kt:1577`, and the ground-candidate classify at `:1040` uses `native.classifyRegionLabels` — different path, untouched). For `analyzeTrash`, make the engine's `classifyRegion` `internal fun classifyRegion(...)` on the engine and call `tapEngine.classifyRegion(frame, box.imageX, box.imageY, fraction)` — one implementation, two callers, still no copy.
- `confirmTapCandidate()`/`rejectTapCandidate()`/`dismissTapCandidate()` actuals become one-line delegations to `tapEngine.confirm()/reject()/dismiss()`.

- [ ] **Step 3: Verify no dead references remain**

Run: `grep -n "pendingFocusTap\|processPendingFocusTap\|classifyTappedRegion" composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`
Expected: no matches.

- [ ] **Step 4: Gates**

Run: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosArm64 --console=plain`
Expected: BUILD SUCCESSFUL, desktop tests all pass (no common code changed — count unchanged).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gcc/app/platform/TapToConfirmEngine.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt
git commit -m "refactor(ar): extract TapToConfirmEngine from ArEnvScanOverlay (pure move, no behavior change)"
```

---

### Task 2: Route overlay tap API — expect members + all three actuals

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (expect class, after `setCoverageHitListener`, ~line 100)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`
- Modify: `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`

**Interfaces:**
- Consumes: Task 1's `TapToConfirmEngine`; route overlay's existing `hasGeoRef`/`localToLatLng(x,z)` (iosMain `ArRouteOverlay.kt:101` + the non-null private helper — MUST be wrapped with the `hasGeoRef` gate, since the route helper computes garbage pre-pairing), `sceneView?.session?.currentFrame`, `cvPixelBufferToMarkerPhotoJpeg` (the marker-photo JPEG helper `ArFrameFeedback` uses).
- Produces (used by Task 5):
  ```kotlin
  val tapCandidate: StateFlow<TapCandidateState>
  fun focusAt(x: Float, y: Float)
  fun confirmTapCandidate(): TapCandidateState?
  fun rejectTapCandidate(): TapCandidateState?
  fun dismissTapCandidate()
  suspend fun captureTapEvidencePhoto(): ByteArray?
  ```

- [ ] **Step 1: Add the expect members**

Append to the expect class body (after `setCoverageHitListener`), with this doc:

```kotlin
    // ── Tap-to-confirm (env-scan parity, block PRD lap-1 requirement) ──
    // Same TapToConfirmEngine the env scan runs — the block PRD forbids the
    // AR surfaces forking. iOS only; Android/Desktop expose an idle flow.

    /** The one pending user-tapped candidate. See [TapCandidateState]. */
    val tapCandidate: StateFlow<TapCandidateState>

    /** Queue a normalized view-space tap (top-left origin) for raycast +
     *  identify. A tap is NEVER silent — every outcome surfaces a pill. */
    fun focusAt(x: Float, y: Float)

    /** Persist the pending tapped candidate as user-confirmed litter and
     *  reset. Returns the confirmed state, or null if nothing was pending. */
    fun confirmTapCandidate(): TapCandidateState?

    /** Record the pending tapped candidate as a negative example and reset. */
    fun rejectTapCandidate(): TapCandidateState?

    /** Drop the pending tapped candidate. Records nothing (accidental tap). */
    fun dismissTapCandidate()

    /** Best-effort downscaled JPEG of the current frame — the confirmed
     *  item's hotspot before-photo / marker photo. Null when no frame
     *  (and always off-iOS). */
    suspend fun captureTapEvidencePhoto(): ByteArray?
```

- [ ] **Step 2: iosMain actual**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`, next to the `frameFeedback` field (~line 97):

```kotlin
    private val tapEngine = TapToConfirmEngine(
        nativeSurfaceProvider = { nativeSurface },
        // The route helper computes even pre-pairing (refLat 0.0) — gate it,
        // same contract as the env overlay's null-before-hasGeoRef helper,
        // so a pre-GPS tap gets the honest GPS_SYNCING pill.
        localToLatLng = { x, z -> if (!hasGeoRef) null else localToLatLng(x, z) },
    )
    actual val tapCandidate: StateFlow<TapCandidateState> = tapEngine.tapCandidate

    actual fun focusAt(x: Float, y: Float) {
        ScanSessionTelemetry.active?.tap(state = null)
        tapEngine.queueTap(x, y)
        // Process immediately: the native raycast needs no ARFrame, and the
        // SceneKit fallback reads the session's current frame. (The env scan
        // defers to its analyze tick; the route overlay has no such tick at
        // tap-friendly cadence — its fan loop is 1 Hz.)
        dispatch_async(dispatch_get_main_queue()) {
            tapEngine.processPendingTap(sceneView?.session?.currentFrame)
        }
    }

    actual fun confirmTapCandidate(): TapCandidateState? = tapEngine.confirm()
    actual fun rejectTapCandidate(): TapCandidateState? = tapEngine.reject()
    actual fun dismissTapCandidate() = tapEngine.dismiss()

    actual suspend fun captureTapEvidencePhoto(): ByteArray? {
        val pixelBuffer = nativeSurface?.pixelBufferOrNull()
            ?: sceneView?.session?.currentFrame?.capturedImage
            ?: return null
        return cvPixelBufferToMarkerPhotoJpeg(pixelBuffer)
    }
```

(If `cvPixelBufferToMarkerPhotoJpeg` is file-private in `ArFrameFeedback.kt`, widen it to `internal` — do not duplicate it.)

- [ ] **Step 3: android + desktop stub actuals**

In BOTH `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` and `composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt`, mirroring the existing stub style:

```kotlin
    actual val tapCandidate: StateFlow<TapCandidateState> = MutableStateFlow(TapCandidateState())
    actual fun focusAt(x: Float, y: Float) {}
    actual fun confirmTapCandidate(): TapCandidateState? = null
    actual fun rejectTapCandidate(): TapCandidateState? = null
    actual fun dismissTapCandidate() {}
    actual suspend fun captureTapEvidencePhoto(): ByteArray? = null
```

- [ ] **Step 4: Gates**

Run: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosArm64 --console=plain`
Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt composeApp/src/desktopMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
git commit -m "feat(route-ar): tap-to-confirm API on ArRouteOverlay via shared TapToConfirmEngine"
```

---

### Task 3: Pure filing math — `routeTapFiling` (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/RouteTapConfirm.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/RouteTapConfirmTest.kt`

**Interfaces:**
- Consumes: `TapCandidateState` (lat/lng/label), `TrashItemCatalog.canonicalType(label)` + `TrashItemCatalog.estimateWeightGrams(itemType, widthMeters = null)` (typical weight), `UNKNOWN_LITTER_LABEL`.
- Produces (used by Task 4):
  ```kotlin
  data class RouteTapFiling(
      val itemType: String,           // canonical
      val estimatedWeightGrams: Int,  // typical for the type
      val estimatedWeightLbs: Double, // grams / 453.59237, 2-decimal rounded
      val severity: Int,              // 1 — a single tapped item
  )
  fun routeTapFiling(candidate: TapCandidateState): RouteTapFiling?  // null if lat/lng missing
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTapConfirmTest {

    private fun candidate(label: String?) = TapCandidateState(
        phase = TapPhase.AWAITING_CONFIRMATION,
        lat = 41.86, lng = -87.72, label = label,
    )

    @Test
    fun filingCanonicalizesTheLabelAndCarriesTypicalWeight() {
        val filing = routeTapFiling(candidate("bottle"))!!
        assertEquals(TrashItemCatalog.canonicalType("bottle"), filing.itemType)
        assertEquals(
            TrashItemCatalog.estimateWeightGrams(filing.itemType, null),
            filing.estimatedWeightGrams,
        )
        assertTrue(filing.estimatedWeightLbs > 0.0)
        assertEquals(1, filing.severity)
    }

    @Test
    fun nullLabelFilesAsUnknownLitter() {
        val filing = routeTapFiling(candidate(null))!!
        assertEquals(TrashItemCatalog.canonicalType(UNKNOWN_LITTER_LABEL), filing.itemType)
    }

    @Test
    fun poundsAreGramsOver453point59237RoundedToTwoDecimals() {
        val filing = routeTapFiling(candidate("bottle"))!!
        val expected = kotlin.math.round(filing.estimatedWeightGrams / 453.59237 * 100) / 100
        assertEquals(expected, filing.estimatedWeightLbs)
    }

    @Test
    fun missingCoordinatesYieldNull() {
        assertNull(routeTapFiling(TapCandidateState(phase = TapPhase.AWAITING_CONFIRMATION, label = "bottle")))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.RouteTapConfirmTest" --console=plain`
Expected: FAIL — unresolved reference `routeTapFiling`.

- [ ] **Step 3: Implement**

```kotlin
package com.gcc.app.platform

import kotlin.math.round

/** grams per pound — hotspots speak lbs (CreateHotspotRequest.estimatedWeightLbs),
 *  markers speak grams (ArDetectedMarkerRequest.estimatedWeightGrams). */
private const val GRAMS_PER_POUND = 453.59237

data class RouteTapFiling(
    val itemType: String,
    val estimatedWeightGrams: Int,
    val estimatedWeightLbs: Double,
    val severity: Int,
)

/**
 * Filing payload for a route-lap tap confirmation: canonical type plus the
 * type's TYPICAL weight (no LiDAR box on the tap path — same fallback the
 * env scan's tap confirm uses), in both units its two consumers need.
 * Null when the candidate never geo-resolved (no lat/lng) — callers must
 * treat that as "nothing to file", never a zero-coordinate hotspot.
 */
fun routeTapFiling(candidate: TapCandidateState): RouteTapFiling? {
    if (candidate.lat == null || candidate.lng == null) return null
    val itemType = TrashItemCatalog.canonicalType(candidate.label ?: UNKNOWN_LITTER_LABEL)
    val grams = TrashItemCatalog.estimateWeightGrams(itemType, null)
    return RouteTapFiling(
        itemType = itemType,
        estimatedWeightGrams = grams,
        estimatedWeightLbs = round(grams / GRAMS_PER_POUND * 100) / 100,
        severity = 1,
    )
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.RouteTapConfirmTest" --console=plain`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/RouteTapConfirm.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/RouteTapConfirmTest.kt
git commit -m "feat(route-ar): routeTapFiling — pure filing math for tap confirmations"
```

---

### Task 4: `RouteScanViewModel.confirmRouteTapItem` / `rejectRouteTapItem`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt` (add next to `onMarkerPlaced`, ~line 345; also extend `onMarkerPlaced`'s enqueue)

**Interfaces:**
- Consumes: Task 3's `routeTapFiling`; existing `outdoorGate.captureAllowed` (`RouteScanViewModel.kt:388-390`), `apiClient.uploadSpotPhotos`, `addDetectedHotspot(...)` (`:833`, requires non-null `beforePhotoUrl`), `arMarkerQueue`, `launchDetached`, `ScanSessionTelemetry`, `PROVENANCE_USER_CONFIRMED`.
- Produces (used by Task 5): `fun confirmRouteTapItem(candidate: TapCandidateState, photoBytes: ByteArray?)`, `fun rejectRouteTapItem(candidate: TapCandidateState)`.

- [ ] **Step 1: Implement the confirm/reject handlers**

```kotlin
    /**
     * A lap-1 tap confirmation (env-scan tap parity): files BOTH surfaces the
     * block flow needs — a photo-backed hotspot (the lap-2 cleanup target;
     * hotspots REQUIRE a before-photo, so a failed upload degrades to
     * marker-only with a visible error) and a user-confirmed world marker
     * (report/training-grade evidence, same provenance rule as the env scan).
     */
    fun confirmRouteTapItem(candidate: TapCandidateState, photoBytes: ByteArray?) {
        if (!outdoorGate.captureAllowed.value) {
            // Never silent (env-scan constraint 2 carried over).
            _uiState.value = _uiState.value.copy(error = "Scan paused indoors — step outside and tap the item again")
            return
        }
        val filing = routeTapFiling(candidate) ?: return
        val lat = candidate.lat ?: return
        val lng = candidate.lng ?: return
        ScanSessionTelemetry.active?.confirm(candidate.label ?: UNKNOWN_LITTER_LABEL)
        launchDetached {
            val photoUrl = photoBytes?.let { bytes ->
                try {
                    apiClient.uploadSpotPhotos(listOf(bytes)).firstOrNull()
                } catch (e: Exception) {
                    DiagnosticsLog.error("route-tap", "confirm photo upload failed (marker-only)", e)
                    null
                }
            }
            if (photoUrl != null) {
                addDetectedHotspot(
                    lat = lat,
                    lng = lng,
                    severity = filing.severity,
                    beforePhotoUrl = photoUrl,
                    confidence = candidate.confidence ?: 0f,
                    estimatedWeightLbs = filing.estimatedWeightLbs,
                    cleanupTarget = true,
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    error = "Photo didn't upload — item saved to the map, but no cleanup hotspot was created",
                )
            }
            arMarkerQueue.enqueue(
                ArDetectedMarkerRequest(
                    lat = lat, lng = lng,
                    label = filing.itemType,
                    capturedAt = Clock.System.now(),
                    mediaUrl = photoUrl,
                    provenance = PROVENANCE_USER_CONFIRMED,
                    estimatedWeightGrams = filing.estimatedWeightGrams,
                ),
            )
            arMarkerQueue.flush()
        }
    }

    /** "Not litter" on a route-lap tap — a negative training example.
     *  Telemetry-only for now (route lap manifests don't carry a rejected
     *  list; parity item noted in the PRD open items). */
    fun rejectRouteTapItem(candidate: TapCandidateState) {
        ScanSessionTelemetry.active?.reject(candidate.label ?: UNKNOWN_LITTER_LABEL)
    }
```

Imports to add at the top of `RouteScanViewModel.kt`: `com.gcc.app.platform.routeTapFiling`, `com.gcc.app.platform.PROVENANCE_USER_CONFIRMED`, `com.gcc.app.platform.UNKNOWN_LITTER_LABEL`, `com.gcc.app.platform.TapCandidateState` (check which already exist — `ScanSessionTelemetry` and `ArDetectedMarkerRequest` are already imported for `onMarkerPlaced`).

- [ ] **Step 2: Gates**

Run: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosArm64 --console=plain`
Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/RouteScanViewModel.kt
git commit -m "feat(route-ar): confirmRouteTapItem files photo-backed hotspot + user-confirmed marker"
```

---

### Task 5: ArRouteScreen — tap gesture + TapCandidatePill

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt` (AR Box starts ~line 277)

**Interfaces:**
- Consumes: Task 2's overlay members, Task 4's VM handlers, existing `TapCandidatePill` (`ui/components/TapCandidatePill.kt` — has an optional `titleOverride`; not needed here), `viewModel.captureAllowed: StateFlow<Boolean>` (`RouteScanViewModel.kt:390`).
- Produces: nothing downstream.

- [ ] **Step 1: Capture taps on the AR surface**

`ArRouteOverlayView` wraps a `UIKitView` — attach the gesture on a Compose layer ABOVE it (a transparent full-size Box with `pointerInput`), mirroring `CameraCapture.kt:148`'s normalized-coordinate pattern:

```kotlin
        ArRouteOverlayView(overlay = overlay, modifier = Modifier.fillMaxSize())
        // Tap-to-confirm (env-scan parity): normalized view-space tap →
        // overlay raycast → confirm pill. Sits under the HUD chrome so
        // buttons/pills keep priority.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        overlay.focusAt(
                            offset.x / size.width.toFloat(),
                            offset.y / size.height.toFloat(),
                        )
                    }
                },
        )
```

Imports: `androidx.compose.foundation.gestures.detectTapGestures`, `androidx.compose.ui.input.pointer.pointerInput`.

- [ ] **Step 2: Collect the candidate + mount the pill**

Near the other `collectAsState()` calls at the top of the composable:

```kotlin
    val tapCandidate by overlay.tapCandidate.collectAsState()
    val captureAllowed by viewModel.captureAllowed.collectAsState()
    val tapConfirmScope = rememberCoroutineScope()
```

Inside the root Box, after the existing HUD elements (so it draws above the gesture layer), bottom-center ABOVE the lap fill bar (which sits at the bottom, `ArRouteScreen.kt:340-365` — use `padding(bottom = 120.dp)` to clear it):

```kotlin
        if (tapCandidate.phase != TapPhase.IDLE) {
            TapCandidatePill(
                state = tapCandidate,
                onConfirm = {
                    if (!captureAllowed) {
                        overlay.dismissTapCandidate()
                        viewModel.confirmRouteTapItem(tapCandidate, null) // surfaces the gate error banner
                    } else {
                        overlay.confirmTapCandidate()?.let { confirmed ->
                            tapConfirmScope.launch {
                                val photo = overlay.captureTapEvidencePhoto()
                                viewModel.confirmRouteTapItem(confirmed, photo)
                            }
                        }
                    }
                },
                onReject = {
                    overlay.rejectTapCandidate()?.let { rejected ->
                        viewModel.rejectRouteTapItem(rejected)
                    }
                },
                onDismiss = { overlay.dismissTapCandidate() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
            )
        }
```

Imports: `com.gcc.app.ui.components.TapCandidatePill`, `com.gcc.app.platform.TapPhase`, `androidx.compose.runtime.rememberCoroutineScope`, `kotlinx.coroutines.launch` (check which already exist).

- [ ] **Step 3: Gates**

Run: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosArm64 --console=plain`
Expected: BUILD SUCCESSFUL, tests green.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/ArRouteScreen.kt
git commit -m "feat(route-ar): tap gesture + confirm pill on the block-lap AR surface"
```

---

### Task 6: PRD close-out + full gate + device watch-items

**Files:**
- Modify: `docs/prd/block-assignment-2-lap-prd.md` (the lap-1 tagging requirement + open-items table)

- [ ] **Step 1: Update the PRD**

In the lap-1 section (the "via the same reticle/tap flow as the env scan" requirement, ~lines 31-33): append `— implemented via the shared TapToConfirmEngine (2026-07, route-lap tap parity plan)`. In the open-items table, remove/strike the tap-parity row; leave the lap-2 AR beacon row (still blocked on CleanScreen having no AR surface) and ADD a row: "Route-lap LiDAR ground-candidate auto-detect parity (GroundCandidateTracker drive loop is still env-only)" and "Rejected route taps ride telemetry only (no manifest rejected list)".

- [ ] **Step 2: Full gates**

Run: `./gradlew :composeApp:desktopTest :composeApp:compileKotlinIosArm64 --console=plain`
Expected: green.
Run: `rm -rf composeApp/build/xcode-frameworks && cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/gcc-ddx-tap build CODE_SIGNING_ALLOWED=NO 2>&1 | tail -3`
Expected: `** BUILD SUCCEEDED **`. Then `rm -rf /tmp/gcc-ddx-tap` (disk).

- [ ] **Step 3: Commit + draft PR**

```bash
git add docs/prd/block-assignment-2-lap-prd.md
git commit -m "docs(prd): mark route-lap tap-to-confirm parity implemented"
git push -u origin <branch>
gh pr create --draft --title "feat(route-ar): route-lap tap-to-confirm parity (shared TapToConfirmEngine)" --body "<summarize tasks 1-6, gates, and the device watch-items below>"
```

**Device watch-items (for the PR body — Blake's field pass):**
1. During a block scout lap in AR, tap a piece of trash → pill appears within ~1s, names the item or "is this litter?".
2. Confirm → found-trash tally chip increments; the hotspot appears on the route minimap (orange dot) and later as a lap-2 cleanup target with the before-photo attached.
3. Tap before GPS pairs → "GPS is syncing" pill (not "hold steady").
4. Tap indoors / gate-blocked confirm → visible error banner, nothing files.
5. Env scan taps behave exactly as on TestFlight 159 (regression check on the extraction).

---

## Self-Review (completed)

- **Spec coverage:** PRD G2 (lap-1 tap flow parity) → Tasks 1-5; "never fork" → Task 1 extraction + Task 2 reuse; hotspot-needs-photo rule → Task 4 degrade path; never-silent-tap → Tasks 4/5 gate banners + pill states. Out-of-scope items (auto-detect parity, lap-2 beacon) explicitly listed and PRD-tracked in Task 6.
- **Placeholder scan:** raycastImage/classifyRegion bodies are cited as verbatim MOVES with exact source lines (deliberate — restating them would invite drift from the field-validated code); all new code is written out in full.
- **Type consistency:** `TapToConfirmEngine.queueTap/processPendingTap/confirm/reject/dismiss` used identically in Tasks 1, 2; `routeTapFiling` → `RouteTapFiling(itemType, estimatedWeightGrams, estimatedWeightLbs, severity)` consumed with those exact names in Task 4; `confirmRouteTapItem(TapCandidateState, ByteArray?)` matches Task 5's call; `captureTapEvidencePhoto(): ByteArray?` (route) is deliberately DIFFERENT from the env overlay's `CapturedPhoto?` variant — route consumers need raw JPEG bytes for upload, not a CapturedPhoto.
