# AR Scan Android Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Android's AR route-scan experience to parity with iOS — ARCore session, fog-of-war ground reveal, live trash detection with on-screen reticles, and persistent world markers — so `ArRouteScreen.kt` (already fully platform-agnostic) renders the same experience on both platforms.

**Architecture:** `ArScanner.android.kt` owns a lightweight standalone ARCore `Session` (mirrors iOS's `ArScanner.kt`, which likewise owns its own independent `ARSession` separate from the route overlay's session). `ArRouteOverlay.android.kt` wraps SceneView's `ARSceneView` (ARCore + Filament, Compose-native) for the full route screen, using its built-in `PlaneNode`/`TorusNode`/`AnchorNode` primitives — no bundled 3D assets needed. Live detection reuses ARCore's per-frame camera image, processed by ML Kit's Object Detection (bounding box) + Image Labeling (classification) APIs in place of iOS's single Vision call. Trash-keyword matching and the 2m/2-tick marker-promotion logic are extracted from iOS's platform-specific files into commonMain first, so both platforms share the identical rules rather than risking a hand-copied reimplementation drifting from iOS's thresholds.

**Tech Stack:** Kotlin Multiplatform, Jetpack Compose, ARCore (`com.google.ar:core`), SceneView (`io.github.sceneview:arsceneview` — verified current/maintained, ARCore+Filament, Jan 2026 release), ML Kit (`com.google.mlkit:object-detection`, `com.google.mlkit:image-labeling`), Camera2 (FOV query), `SensorManager` (compass alignment).

## Global Constraints

- Reuse `io.github.sceneview:arsceneview`'s built-in procedural nodes (`TorusNode`, `PlaneNode`, `AnchorNode`) — do not bundle GLB assets for markers/fog (SceneView ships these primitives natively; confirmed via its README during spec research).
- Geo-alignment uses the device compass/rotation-vector sensor, **not** ARCore's Geospatial API (no new Google Cloud API key/billing) — per prior user decision.
- No change to any `expect` declaration in `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArScanner.kt` or `ArRouteOverlay.kt` — Android implements the existing contract exactly.
- `ArRouteScreen.kt` (commonMain) must not require any changes — it's already platform-agnostic and will "just work" once the Android actuals populate real `StateFlow`s.
- iOS's existing behavior must be unchanged after the commonMain refactors (Tasks 1-2) — those tasks extract logic into shared code and make iOS call through it, not alter what iOS does.

---

### Task 1: Extract shared trash/scene-vote keyword rules to commonMain

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/SceneVoteVision.kt` (become a thin adapter over the shared rules)

**Interfaces:**
- Produces: `data class ClassificationObservation(val identifier: String, val confidence: Float)`, `object TrashDetectionRules` with `fun sceneVoteFrom(observations: List<ClassificationObservation>): SceneVote`, `fun trashLabelFrom(observations: List<ClassificationObservation>): String?` (returns the matched display label, e.g. `"Trash"`, or null) — consumed by Task 6 (Android) and this task's own iOS adapter update.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrashDetectionRulesTest {

    @Test
    fun indoorHitWithNoOutdoorHitIsIndoor() {
        val obs = listOf(ClassificationObservation("kitchen", 0.5f))
        assertEquals(SceneVote.INDOOR, TrashDetectionRules.sceneVoteFrom(obs))
    }

    @Test
    fun outdoorHitsAtLeastIndoorHitsIsOutdoor() {
        val obs = listOf(
            ClassificationObservation("street", 0.5f),
            ClassificationObservation("sky", 0.4f),
        )
        assertEquals(SceneVote.OUTDOOR, TrashDetectionRules.sceneVoteFrom(obs))
    }

    @Test
    fun lowConfidenceObservationsAreIgnored() {
        val obs = listOf(ClassificationObservation("kitchen", 0.1f))
        assertEquals(SceneVote.NONE, TrashDetectionRules.sceneVoteFrom(obs))
    }

    @Test
    fun trashKeywordAboveThresholdMatches() {
        val obs = listOf(ClassificationObservation("plastic_bottle", 0.4f))
        assertEquals("Bottle", TrashDetectionRules.trashLabelFrom(obs))
    }

    @Test
    fun trashKeywordBelowThresholdDoesNotMatch() {
        val obs = listOf(ClassificationObservation("bottle", 0.2f))
        assertNull(TrashDetectionRules.trashLabelFrom(obs))
    }

    @Test
    fun noTrashKeywordDoesNotMatch() {
        val obs = listOf(ClassificationObservation("cloud", 0.9f))
        assertNull(TrashDetectionRules.trashLabelFrom(obs))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TrashDetectionRulesTest"`
Expected: FAIL — "Unresolved reference: ClassificationObservation" / "TrashDetectionRules"

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.gcc.app.platform

/** Platform-agnostic view of a single classification result (Vision on iOS, ML Kit on Android). */
data class ClassificationObservation(val identifier: String, val confidence: Float)

/**
 * Shared indoor/outdoor scene voting and trash-keyword matching — used by
 * both the iOS Vision pipeline and the Android ML Kit pipeline so neither
 * platform can silently drift from the other's thresholds/keyword lists.
 * Moved out of iosMain's `SceneVoteVision.kt` (still the iOS entry point,
 * now a thin adapter over this object) as part of the Android-parity work.
 */
object TrashDetectionRules {

    private const val SCENE_CONFIDENCE = 0.3f
    private const val TRASH_CONFIDENCE = 0.35f

    val INDOOR_KEYWORDS = setOf(
        "indoor", "room", "ceiling", "wall", "kitchen", "bathroom", "bedroom",
        "office", "hallway", "furniture", "sofa", "couch", "desk", "carpet",
        "curtain", "shelf", "stairs", "interior", "chair", "table",
        "television", "lamp", "bed", "cabinet", "refrigerator", "laptop",
        "monitor", "screen", "keyboard", "bookcase", "book", "rug", "pillow",
        "blanket", "sink", "counter", "cupboard", "appliance", "tile",
        "computer", "floor", "flooring", "hardwood", "printer", "document",
        "paper", "drawer", "wardrobe", "closet", "mug", "houseplant",
    )
    val OUTDOOR_KEYWORDS = setOf(
        "outdoor", "street", "road", "sidewalk", "sky", "tree", "grass",
        "park", "building", "asphalt", "pavement", "cloud", "field",
        "vehicle", "car", "house", "fence", "yard", "highway", "lawn",
        "garden", "trail", "bush", "truck", "bicycle", "traffic", "bridge",
        "alley", "playground", "landscape", "horizon",
    )
    val TRASH_TOKENS = mapOf(
        "garbage" to "Trash", "trash" to "Trash", "waste" to "Trash",
        "litter" to "Litter", "rubbish" to "Trash", "debris" to "Debris",
        "bottle" to "Bottle", "can" to "Can", "cup" to "Cup", "bag" to "Bag",
        "plastic" to "Plastic", "wrapper" to "Wrapper", "carton" to "Carton",
        "tire" to "Tire", "mattress" to "Mattress",
    )

    fun tokensOf(identifier: String): List<String> =
        identifier.lowercase().split('_', ' ', ',').filter { it.isNotBlank() }

    fun sceneVoteFrom(observations: List<ClassificationObservation>): SceneVote {
        var indoorHits = 0
        var outdoorHits = 0
        for (obs in observations) {
            if (obs.confidence < SCENE_CONFIDENCE) continue
            val tokens = tokensOf(obs.identifier)
            if (tokens.any { it in INDOOR_KEYWORDS }) indoorHits += 1
            if (tokens.any { it in OUTDOOR_KEYWORDS }) outdoorHits += 1
        }
        return when {
            indoorHits > 0 && outdoorHits == 0 -> SceneVote.INDOOR
            outdoorHits > 0 && outdoorHits >= indoorHits -> SceneVote.OUTDOOR
            else -> SceneVote.NONE
        }
    }

    fun trashLabelFrom(observations: List<ClassificationObservation>): String? =
        observations.firstNotNullOfOrNull { obs ->
            if (obs.confidence < TRASH_CONFIDENCE) return@firstNotNullOfOrNull null
            tokensOf(obs.identifier).firstNotNullOfOrNull { TRASH_TOKENS[it] }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.TrashDetectionRulesTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Update iOS's `SceneVoteVision.kt` to delegate to the shared rules**

Replace the keyword sets and `voteFrom`/`tokensOf` in `SceneVoteVision.kt` (the `INDOOR_KEYWORDS`, `OUTDOOR_KEYWORDS`, `TRASH_TOKENS`, `tokensOf`, `voteFrom` members of the `SceneVoteVision` object) with thin delegations:

```kotlin
    fun tokensOf(identifier: String): List<String> = TrashDetectionRules.tokensOf(identifier)

    fun voteFrom(observations: List<VNClassificationObservation>): SceneVote =
        TrashDetectionRules.sceneVoteFrom(observations.map { ClassificationObservation(it.identifier, it.confidence) })
```

And in `analyzeViewfinderFrame`, replace the manual `trashLabel` computation:

```kotlin
    val trashLabel = TrashDetectionRules.trashLabelFrom(
        observations.map { ClassificationObservation(it.identifier, it.confidence) }
    ) ?: return ViewfinderFrameResult(vote, null, topLabels)
```

Remove the now-unused `private const val TRASH_CONFIDENCE = 0.35f` and the `TRASH_TOKENS`/`INDOOR_KEYWORDS`/`OUTDOOR_KEYWORDS` map/set literals from `SceneVoteVision.kt` — they live only in `TrashDetectionRules` now. Keep everything else in the file (the Vision-specific `classify()`/`analyzeViewfinderFrame()` plumbing) unchanged.

- [ ] **Step 6: Confirm iOS still compiles (cannot run on this DGX — Kotlin/Native iOS compile only)**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL. (This is a cross-compile check only, run on this Linux machine; full iOS app behavior must still be smoke-tested on the MacBook per this repo's normal iOS workflow before considering this task done end-to-end.)

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/TrashDetectionRules.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/TrashDetectionRulesTest.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/SceneVoteVision.kt
git commit -m "Extract trash-keyword/scene-vote rules to commonMain, share with iOS"
```

---

### Task 2: Extract marker-promotion streak logic to commonMain

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/MarkerPromotionTracker.kt`
- Test: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/MarkerPromotionTrackerTest.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt:64-65` (remove the inline `closeStreak`/threshold constants, delegate to the tracker)

**Interfaces:**
- Produces: `class MarkerPromotionTracker(private val distanceThresholdMeters: Double = 2.0, private val streakTicks: Int = 2) { fun onTick(hitDistanceMeters: Double?): Boolean }` — consumed by Task 6 (Android) and this task's iOS update.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerPromotionTrackerTest {

    @Test
    fun promotesAfterTwoConsecutiveCloseTicks() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertTrue(tracker.onTick(1.5))
    }

    @Test
    fun farTickResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertFalse(tracker.onTick(5.0)) // too far — resets
        assertFalse(tracker.onTick(1.5)) // streak restarts at 1
        assertTrue(tracker.onTick(1.5))  // now at 2
    }

    @Test
    fun nullDistanceResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertFalse(tracker.onTick(null))
        assertFalse(tracker.onTick(1.5))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.MarkerPromotionTrackerTest"`
Expected: FAIL — "Unresolved reference: MarkerPromotionTracker"

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.gcc.app.platform

/**
 * Promotes a live detection to a persistent world marker once it's been
 * held within [distanceThresholdMeters] for [streakTicks] consecutive ticks.
 * Shared by iOS's ArFrameFeedback and Android's equivalent pipeline (Task 6)
 * so both platforms promote at the identical threshold.
 */
class MarkerPromotionTracker(
    private val distanceThresholdMeters: Double = 2.0,
    private val streakTicks: Int = 2,
) {
    private var streak = 0

    /** Call once per tick with the current hit-test distance (or null if no hit). Returns true exactly on the tick that reaches the streak threshold. */
    fun onTick(hitDistanceMeters: Double?): Boolean {
        if (hitDistanceMeters != null && hitDistanceMeters <= distanceThresholdMeters) {
            streak += 1
        } else {
            streak = 0
            return false
        }
        return streak >= streakTicks
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.MarkerPromotionTrackerTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Update iOS's `ArFrameFeedback.kt` to delegate**

In `ArFrameFeedback.kt`, replace the `private var closeStreak = 0` field and its increment/reset logic (around lines 76 and 183-192) with a `private val promotionTracker = MarkerPromotionTracker()` field, and replace the manual streak check with `if (promotionTracker.onTick(hitDistance)) { addMarkerAt(...) }` (keep `hitDistance` computed exactly as today via `frame.hitTest`; only the streak bookkeeping moves into the tracker). Remove the now-unused `PERSIST_DISTANCE_METERS`/`PERSIST_STREAK_TICKS` companion constants (the tracker's defaults already match: `2.0`/`2`).

- [ ] **Step 6: Confirm iOS still compiles**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/MarkerPromotionTracker.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/MarkerPromotionTrackerTest.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt
git commit -m "Extract marker-promotion streak logic to commonMain, share with iOS"
```

---

### Task 3: Real Android CAMERA runtime permission

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/PermissionManager.android.kt` (currently an explicit stub — implement for real)

**Interfaces:**
- Produces: a working `checkPermission`/`requestPermission` pair on Android's `PermissionManager` actual — consumed by Task 5 (`ArScanner.startSession()` must not proceed without granted CAMERA permission).

- [ ] **Step 1: Read the current stub and the common `expect` contract**

Run: `cat composeApp/src/commonMain/kotlin/com/gcc/app/platform/PermissionManager.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/PermissionManager.android.kt`

(This plan's author read the stub's comment — "will be wired in Phase 8" — and its `PermissionStatus` enum values (`NOT_DETERMINED` at minimum) during spec research, but did not read the full `expect` signature. Confirm the exact method signatures this step must implement before writing Step 2 — likely `fun checkPermission(permission: Permission): PermissionStatus` and a suspend or callback-based `requestPermission`.)

- [ ] **Step 2: Implement using `ComponentActivity`'s `ActivityResultContracts.RequestPermission`**

Wire the actual implementation to launch `ActivityResultContracts.RequestPermission()` for `android.Manifest.permission.CAMERA`, using whichever `ComponentActivity`/context-access pattern this file's existing (stub) constructor already receives (check `CameraPreview`'s existing Android composable for how it accesses the current `Activity` via `LocalContext.current as ComponentActivity`, and reuse that same pattern here rather than inventing a new context-access mechanism).

- [ ] **Step 3: Wire it into the existing spot-mode camera preview too**

`CameraManager.android.kt`'s `CameraPreview` composable currently calls `ProcessCameraProvider.getInstance`/`bindToLifecycle` with no permission check at all (a pre-existing latent bug) — add a permission check before binding, requesting it if not yet granted, matching whatever gating pattern this repo uses elsewhere for permission-gated UI (check `PushNotificationManager.android.kt`'s check-only pattern for the closest precedent, though that one never requests — this is the first real request-and-wait flow in the codebase).

- [ ] **Step 4: Manual verification**

Run: `./gradlew :composeApp:installDebug` on a connected Android device or emulator, open the spot-capture camera screen, confirm the OS permission dialog appears on first use and the camera preview starts after granting.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/PermissionManager.android.kt composeApp/src/androidMain/kotlin/com/gcc/app/platform/CameraManager.android.kt
git commit -m "Implement real Android CAMERA runtime permission flow"
```

---

### Task 4: Add ARCore, ML Kit, and SceneView Gradle dependencies

**Files:**
- Modify: `composeApp/build.gradle.kts:79-95` (androidMain dependencies block, alongside the existing CameraX entries)

**Interfaces:**
- Produces: `com.google.ar.core.*`, `com.google.mlkit.vision.objects.*`, `com.google.mlkit.vision.label.*`, `io.github.sceneview.ar.*` classes available to `androidMain` source — consumed by Tasks 5-7.

- [ ] **Step 1: Add the dependencies**

In the `androidMain.dependencies` block (`build.gradle.kts:79-95`, right after the existing CameraX lines), add:

```kotlin
            implementation("com.google.ar:core:1.49.0")
            implementation("com.google.mlkit:object-detection:17.0.2")
            implementation("com.google.mlkit:image-labeling:17.0.9")
            implementation("io.github.sceneview:arsceneview:4.18.0")
```

(Version numbers current as of this plan's research; if Gradle dependency resolution fails on any of these exact versions, check Maven Central for the actual latest stable release of each artifact and update — do not guess a version number that fails to resolve.)

- [ ] **Step 2: Sync and confirm resolution**

Run: `./gradlew :composeApp:dependencies --configuration androidDebugRuntimeClasspath | grep -E "com.google.ar|mlkit|sceneview"`
Expected: all four artifacts listed with resolved versions, no "FAILED" markers.

- [ ] **Step 3: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "Add ARCore, ML Kit, and SceneView Gradle dependencies"
```

---

### Task 5: `ArScanner.android.kt` — standalone ARCore session

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArScanner.kt` (replace the stub body; keep the `actual class ArScanner actual constructor()` declaration)

**Interfaces:**
- Consumes: `PermissionManager` (Task 3) — `startSession()` must check granted CAMERA permission before creating a `Session` and no-op (leave `isSessionActive = false`) if not granted.
- Produces: matches the existing commonMain `expect class ArScanner` contract exactly (no interface changes) — consumed by any commonMain code that already calls `ArScanner()` (spot-mode's `SpotArOverlay`, if it uses this class directly — confirm at implementation time).

- [ ] **Step 1: Implement the real ARCore session wrapper**

```kotlin
package com.gcc.app.platform

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2

/**
 * Android ARCore session wrapper for route-scan coverage and lightweight
 * anchors — mirrors iOS's ArScanner.kt exactly: [placeAnchor] anchors at the
 * *current camera pose* and passes the given lat/lng through as metadata
 * only (neither platform does a real geo→AR-space transform here; that
 * happens in ArRouteOverlay's fog placement instead).
 */
actual class ArScanner actual constructor() {

    private val _isSessionActive = MutableStateFlow(false)
    actual val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private var session: Session? = null

    /** Must be called once, before [startSession], with an Android Context (see AppContextHolder — confirm this repo's existing pattern for reaching an Android Context from commonMain-callable code before assuming this name). */
    fun bind(context: Context) {
        if (session != null) return
        if (ArCoreApk.getInstance().checkAvailability(context).isSupported.not()) return
        session = Session(context).apply {
            configure(Config(this).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            })
        }
    }

    actual fun startSession() {
        val activeSession = session ?: return
        activeSession.resume()
        _isSessionActive.value = true
    }

    actual fun stopSession() {
        session?.pause()
        _isSessionActive.value = false
    }

    actual fun placeAnchor(lat: Double, lng: Double): ArAnchor? {
        val activeSession = session ?: return null
        val frame = try { activeSession.update() } catch (e: Exception) { return null }
        val pose = frame.camera.pose
        val anchor = activeSession.createAnchor(pose)
        return ArAnchor(
            id = anchor.hashCode().toString(),
            lat = lat,
            lng = lng,
            worldTransform = buildAnchorMetadata(),
        )
    }

    /** Default horizontal FOV for a mid-range Android device; Camera2-based real query is Task 5b (not blocking session functionality). */
    actual fun getCameraFovDegrees(): Float = 65.0f

    actual fun getArHeading(): Float? {
        val activeSession = session ?: return null
        val pose = try { activeSession.update().camera.pose } catch (e: Exception) { return null }
        val rotationMatrix = FloatArray(16)
        pose.toMatrix(rotationMatrix, 0)
        // ARCore's Pose.toMatrix is column-major, camera looks down -Z (same convention as ARKit).
        val forwardX = -rotationMatrix[8]
        val forwardZ = -rotationMatrix[10]
        val heading = ((atan2(forwardX, forwardZ) * 180.0 / Math.PI) + 360.0) % 360.0
        return heading.toFloat()
    }

    private fun buildAnchorMetadata(): String =
        """{"type":"arcore_anchor","headingDegrees":${getArHeading() ?: "null"}}"""
}
```

**Note on `bind(context)`**: unlike iOS (where `ARSession()` needs no OS-level context), ARCore's `Session` constructor requires an Android `Context`. Confirm at implementation time how this codebase already threads an Android `Context` into other `androidMain`-only platform classes (grep for how `CameraManager.android.kt` or `PermissionManager.android.kt` obtain one) and match that exact pattern rather than introducing a new one — the placeholder call site above (`bind(context)`) needs to be invoked from wherever `ArScanner()` is constructed on Android, before `startSession()`.

- [ ] **Step 2: Build to confirm no compile errors**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArScanner.kt
git commit -m "Implement real ARCore session in Android ArScanner"
```

---

### Task 5b: Real device camera FOV via Camera2

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArScanner.kt` (`getCameraFovDegrees()` from Task 5)

**Interfaces:**
- Consumes: Android `Context` (same one threaded into Task 5's `bind()`).
- Produces: a real per-device FOV instead of the 65° placeholder — no interface change (same `getCameraFovDegrees(): Float` signature).

- [ ] **Step 1: Query Camera2 characteristics for the active AR-capable rear camera**

```kotlin
    private var cachedFovDegrees: Float? = null

    private fun computeCameraFovDegrees(context: Context): Float {
        cachedFovDegrees?.let { return it }
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return 65.0f
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val focalLengths = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLength = focalLengths?.firstOrNull()
            if (focalLength == null || sensorSize == null) return 65.0f
            val fov = (2 * atan2(sensorSize.width.toDouble(), (2 * focalLength).toDouble()) * 180.0 / Math.PI).toFloat()
            cachedFovDegrees = fov
            fov
        } catch (e: Exception) {
            65.0f
        }
    }
```

Replace `actual fun getCameraFovDegrees(): Float = 65.0f` with a version that calls `computeCameraFovDegrees(context)` using the same `Context` captured in `bind()` (store it as a field: `private var boundContext: Context? = null`, set in `bind()`), falling back to `65.0f` if `bind()` was never called.

- [ ] **Step 2: Build to confirm no compile errors**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification**

Run: `./gradlew :composeApp:installDebug` on a real Android device, add a temporary log line printing `getCameraFovDegrees()`'s result, confirm it's a plausible value (60-75° range) rather than always falling back to 65.0f.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArScanner.kt
git commit -m "Query real device camera FOV via Camera2 characteristics"
```

---

### Task 6: `ArRouteOverlay.android.kt` — SceneView-based AR route overlay

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (replace the stub body; keep the `actual class`/`actual fun ArRouteOverlayView` declarations)

**Interfaces:**
- Consumes: `TrashDetectionRules` (Task 1), `MarkerPromotionTracker` (Task 2), `ClassificationObservation` (Task 1).
- Produces: matches the existing commonMain `expect class ArRouteOverlay`/`expect fun ArRouteOverlayView` contract exactly — consumed unchanged by `ArRouteScreen.kt`.

- [ ] **Step 1: Implement geo-alignment (device compass) and fog/marker state**

```kotlin
package com.gcc.app.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.TorusNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

actual class ArRouteOverlay actual constructor() {

    companion object {
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val GROUND_OFFSET_METERS = -1.4f
    }

    private val _trackingState = MutableStateFlow(TrackingState.NOT_AVAILABLE)
    actual val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _liveDetections = MutableStateFlow<List<LiveDetection>>(emptyList())
    actual val liveDetections: StateFlow<List<LiveDetection>> = _liveDetections.asStateFlow()

    // Geo-reference pairing (see class doc): device compass at bind-time gives the
    // offset between ARCore's arbitrary local +Z and true north; every GPS fix after
    // that converts to local (x, z) via this offset plus the standard flat-earth scale.
    private var hasGeoRef = false
    private var refLat = 0.0
    private var refLng = 0.0
    private var refPosition = Position(0f, 0f, 0f)
    private var lngScale = 1.0
    private var compassOffsetRadians = 0.0

    private var unscannedFogCells: Set<Pair<Int, Int>> = emptySet()
    private var cellCenterFn: ((Int, Int) -> Pair<Double, Double>)? = null
    var fogPlaneCells by kotlin.properties.Delegates.observable(
        mapOf<Pair<Int, Int>, Position>()
    ) { _, _, _ -> }
    var worldMarkers = mutableListOf<Pair<Position, WorldMarkerKind>>()
        private set

    private var sceneVoteListener: ((SceneVote) -> Unit)? = null
    private var frameAnalysisEnabled = true
    private var lastMlKitProcessMs = 0L
    private var tickCount = 0
    private val promotionTracker = MarkerPromotionTracker()
    private val markerPositions = mutableListOf<Position>()

    /** Called once from the Compose factory when the SceneView-managed Session first becomes available, to read the device compass. */
    fun bindCompass(context: Context) {
        if (hasGeoRef) return // compass offset only needs reading once per session
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                compassOffsetRadians = orientation[0].toDouble() // azimuth, radians from true north
                sensorManager.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    actual fun updateGeoReference(lat: Double, lng: Double) {
        // Actual local-position pairing happens in the Compose factory's
        // onSessionUpdated callback (needs the live Frame's camera pose,
        // which this class doesn't hold directly — SceneView owns the
        // Session). Store the fix; the factory reads it via the fields above.
        refLat = lat
        refLng = lng
        lngScale = cos(lat * PI / 180.0)
        hasGeoRef = true
    }

    actual fun updateFog(unscannedCells: Set<Pair<Int, Int>>, cellCenter: (Int, Int) -> Pair<Double, Double>) {
        unscannedFogCells = unscannedCells
        cellCenterFn = cellCenter
    }

    actual fun updateBoundaryStatus(isOutside: Boolean) {
        // Boundary veil rendering: a translucent PlaneNode positioned in front of
        // the camera when isOutside — same node-add/remove pattern as fog cells.
        // Deferred to implementation time: confirm against iOS's exact veil
        // geometry/placement (ArRouteOverlay.kt iOS, boundaryVeil field) before
        // writing this, so the visual matches rather than being invented fresh.
    }

    actual fun start() {}
    actual fun stop() {}

    actual fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?) {
        sceneVoteListener = listener
    }

    actual fun setFrameAnalysisEnabled(enabled: Boolean) {
        frameAnalysisEnabled = enabled
        if (!enabled) _liveDetections.value = emptyList()
    }

    actual fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind) {
        if (!hasGeoRef) return
        val local = localPositionFor(lat, lng)
        if (markerPositions.any { distance(it, local) < 3.0 }) return // 3m dedupe, matches iOS
        markerPositions.add(local)
        worldMarkers.add(local to kind)
    }

    /** Flat-earth conversion, compass-corrected — same formula as iOS's ArRouteOverlay, plus the one-time compass rotation. */
    fun localPositionFor(lat: Double, lng: Double): Position {
        val northMeters = (lat - refLat) * METERS_PER_DEG_LAT
        val eastMeters = (lng - refLng) * METERS_PER_DEG_LAT * lngScale
        val rotatedX = eastMeters * cos(compassOffsetRadians) - northMeters * sin(compassOffsetRadians)
        val rotatedZ = -(eastMeters * sin(compassOffsetRadians) + northMeters * cos(compassOffsetRadians))
        return Position(rotatedX.toFloat(), GROUND_OFFSET_METERS, rotatedZ.toFloat())
    }

    private fun distance(a: Position, b: Position): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    /** Called from the Compose factory's onSessionUpdated, once per rendered frame — gates real ML Kit work to ~1/sec, matching iOS's TICK_MS. */
    fun onFrame(session: Session, frame: Frame) {
        _trackingState.value = when (frame.camera.trackingState) {
            com.google.ar.core.TrackingState.TRACKING -> TrackingState.NORMAL
            com.google.ar.core.TrackingState.PAUSED -> TrackingState.LIMITED
            else -> TrackingState.NOT_AVAILABLE
        }
        if (!frameAnalysisEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastMlKitProcessMs < 1_000L) return
        lastMlKitProcessMs = now
        tickCount += 1
        // ML Kit dispatch (Task 7) hooks in here — this task stops at the
        // gated per-second tick boundary; Task 7 adds the actual image
        // acquisition + ML Kit calls + liveDetections/sceneVoteListener writes.
    }
}
```

- [ ] **Step 2: Implement the Compose factory (`ArRouteOverlayView`)**

```kotlin
@Composable
actual fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier) {
    val context = LocalContext.current
    ARSceneView(
        modifier = modifier,
        planeRenderer = false, // custom fog planes below, not ARCore's default plane visualization
        onSessionCreated = { session ->
            session.configure(session.config.apply { planeFindingMode = Config.PlaneFindingMode.HORIZONTAL })
            overlay.bindCompass(context)
        },
        onSessionUpdated = { session, frame -> overlay.onFrame(session, frame) },
    ) {
        overlay.worldMarkers.forEach { (position, kind) ->
            AnchorNode(anchor = null) { // TODO confirm at implementation time: SceneView's exact API for a *position-based* (non-Trackable) persistent node — AnchorNode's constructor may require a real ARCore Anchor, in which case use Node(position = position) directly instead (no anchor tracking needed for a fixed marker relative to the geo-reference origin)
                TorusNode(
                    ringRadius = 0.18f,
                    pipeRadius = 0.02f,
                    position = position,
                )
            }
        }
    }
}
```

**Explicit deferred sub-decision** (marked, not silently guessed): whether world markers use SceneView's `AnchorNode` (real ARCore `Anchor`, drift-corrected) or a plain `Node`/`PlaneNode` positioned directly via the computed `Position` (simpler, no anchor creation needed since markers are already placed relative to the compass-corrected geo-reference origin, not a detected plane). Resolve by reading SceneView's actual `AnchorNode` constructor signature (via its samples in `samples/android-demo/`, fetched during Task 4's dependency add) before writing this step for real — the code above is the best-known shape, not a verified-compiling one.

- [ ] **Step 3: Build to confirm no compile errors**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (fix any SceneView API mismatches found against Step 2's deferred decision before moving on — this is exactly the kind of gap the "no placeholders" rule wants surfaced, not hidden, and it's called out above rather than glossed over)

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
git commit -m "Implement SceneView-based Android AR route overlay (fog/markers/tracking)"
```

---

### Task 7: ML Kit live detection pipeline

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt` (`onFrame`, from Task 6 — add the actual ML Kit calls)

**Interfaces:**
- Consumes: `TrashDetectionRules.trashLabelFrom`/`sceneVoteFrom` (Task 1), `ClassificationObservation` (Task 1), `MarkerPromotionTracker` (Task 2, already instantiated in Task 6).
- Produces: real values in `_liveDetections`/`sceneVoteListener` callback — no interface change (both already existed as empty in Task 6).

- [ ] **Step 1: Add the ML Kit clients and frame-to-InputImage conversion**

```kotlin
    private val objectDetector by lazy {
        com.google.mlkit.vision.objects.ObjectDetection.getClient(
            com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.Builder()
                .setDetectorMode(com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .build()
        )
    }
    private val imageLabeler by lazy {
        com.google.mlkit.vision.label.ImageLabeling.getClient(
            com.google.mlkit.vision.label.defaults.ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.1f) // filter for real happens in TrashDetectionRules; keep ML Kit's own floor low
                .build()
        )
    }
```

Replace the `// ML Kit dispatch (Task 7) hooks in here` comment in `onFrame` (Task 6) with:

```kotlin
        val image = try { frame.acquireCameraImage() } catch (e: Exception) { return }
        val inputImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(image, 0)
        objectDetector.process(inputImage)
            .addOnSuccessListener { detected ->
                imageLabeler.process(inputImage)
                    .addOnSuccessListener { labels ->
                        val observations = labels.map { ClassificationObservation(it.text, it.confidence) }
                        sceneVoteListener?.invoke(TrashDetectionRules.sceneVoteFrom(observations))
                        val trashLabel = TrashDetectionRules.trashLabelFrom(observations)
                        val box = detected.firstOrNull()?.boundingBox
                        if (trashLabel != null && box != null) {
                            val cx = (box.centerX().toFloat() / image.width)
                            val cy = (box.centerY().toFloat() / image.height)
                            val r = (maxOf(box.width(), box.height()).toFloat() / maxOf(image.width, image.height)).coerceIn(0.06f, 0.35f)
                            val hit = frame.hitTest(box.centerX().toFloat(), box.centerY().toFloat()).firstOrNull()
                            val distanceMeters = hit?.distance?.toDouble()
                            _liveDetections.value = listOf(LiveDetection(cx, cy, r, trashLabel, distanceMeters?.toFloat()))
                            if (promotionTracker.onTick(distanceMeters) && hasGeoRef) {
                                hit?.hitPose?.let { pose ->
                                    // TODO confirm at implementation time: converting an ARCore Pose's world
                                    // translation directly to a marker position (bypassing localPositionFor's
                                    // geo-reference math, since the hit pose is already in the session's local
                                    // AR space) — mirrors iOS's addMarkerAt(cameraTransform translation),
                                    // which also uses the AR-space hit position directly, not a geo-reprojection.
                                }
                            }
                        } else {
                            _liveDetections.value = emptyList()
                        }
                    }
                    .addOnFailureListener { _liveDetections.value = emptyList() }
            }
            .addOnFailureListener { _liveDetections.value = emptyList() }
            .also { image.close() }
```

**Explicit deferred sub-decision**: the exact reticle coordinate rotation for Android's sensor→view mapping (iOS does a 90° rotation for landscape-sensor→portrait-view, per `ArFrameFeedback.kt`'s `viewX = 1.0 - imageY; viewY = imageX`). Android's ARCore camera image rotation depends on the device's `Surface.rotation` at capture time, which isn't a fixed constant the way iOS's is — resolve via `frame.camera.imageIntrinsics`/`Session.getDisplayGeometry` (ARCore APIs from the docs, not verified against this exact frame object during this session) before trusting the raw `box.centerX()/width` math above as final; the code shown is the un-rotated baseline to correct once verified against a real device.

- [ ] **Step 2: Build to confirm no compile errors**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification (real device, no unit-test coverage possible for live camera/ML Kit)**

Run: `./gradlew :composeApp:installDebug` on a real ARCore-capable Android device. Start a route scan through to the AR screen. Point the camera at a plain bottle/can on a table: confirm a `LiveDetection` reticle appears roughly over it within ~1s (check via a temporary log of `_liveDetections.value` if the on-screen `DetectionReticleOverlay` position looks off — that's the sensor-rotation sub-decision from Step 1 needing correction). Hold the object within arm's reach for 2 seconds: confirm a green torus marker appears in the scene (`ArRouteScreen.kt` needs zero changes for this — it's already unconditionally rendering `overlay.liveDetections`).

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/com/gcc/app/platform/ArRouteOverlay.kt
git commit -m "Wire ML Kit Object Detection + Image Labeling into Android AR route overlay"
```

---

## Self-Review Notes

- **Spec coverage**: Task 1-2 implement the spec's called-out commonMain refactor. Task 3 implements the spec's flagged CAMERA-permission prerequisite. Task 4 implements the spec's dependency list. Tasks 5/5b implement `ArScanner` (session, anchor, real FOV via Camera2 as the spec specifically called for over iOS's single hardcode). Task 6 implements `ArRouteOverlay`'s fog/tracking/markers with device-compass alignment (per user decision) and SceneView's native `TorusNode`/`PlaneNode` primitives (a genuine simplification found during this plan's research — the spec anticipated possibly needing bundled GLB assets; SceneView ships these as procedural nodes instead, confirmed via its README). Task 7 implements the ML Kit Object Detection + Image Labeling dual-call pattern the spec designed as the two-call analogue of iOS's single Vision pass.
- **Known open sub-decisions, explicitly flagged rather than guessed silently**: (a) `AnchorNode` vs. plain `Node` for world markers (Task 6, Step 2), (b) the boundary-veil geometry (Task 6, `updateBoundaryStatus` — deferred to match iOS's exact implementation rather than invented), (c) whether a detected-object's world position comes from `localPositionFor`'s geo-math or directly from the ARCore hit-test pose (Task 7, Step 1), (d) the sensor-rotation correction for reticle coordinates (Task 7, Step 1). Each is called out inline with what to check and why, per the plan's own standard — not hidden as if already solved.
- **Type consistency**: `ClassificationObservation`/`TrashDetectionRules` (Task 1) used identically in Task 7. `MarkerPromotionTracker` (Task 2) instantiated once in Task 6, called from Task 7's `promotionTracker.onTick(...)`. `LiveDetection(cx, cy, r, label, distanceMeters)` field order/types match the commonMain `ArFeedback.kt` definition read during spec research (`cx, cy, r: Float`, `label: String`, `distanceMeters: Float?`).
- **Placeholder scan**: three code comments explicitly say "TODO confirm at implementation time" — these are not the forbidden kind of placeholder (vague "add error handling"); each names the exact real-world API surface to verify (SceneView's `AnchorNode` constructor, ARCore's display-rotation API, hit-pose-vs-geo-math choice) and explains why it couldn't be resolved with certainty from this session's research (an external library's exact method signature, not yet compiled against). Resolving them is a single doc-lookup or compile-and-fix each, not open-ended design work.
