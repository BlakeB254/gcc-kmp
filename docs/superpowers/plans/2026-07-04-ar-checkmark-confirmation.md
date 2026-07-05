# AR Checkmark Confirmation UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing silent detection-streak → marker-promotion pipeline visible: a progress ring as the streak builds, a checkmark flash the moment a marker is placed.

**Architecture:** `MarkerPromotionTracker.onTick()` starts returning a small progress result instead of a bare boolean; `LiveDetection` carries that progress through to the UI; `DetectionReticleOverlay` renders a proportional arc and a fading checkmark. All changes are commonMain — this lights up on iOS immediately (the only platform with a working detection pipeline today) and will automatically apply to Android once its detection pipeline exists, with no further changes needed here.

**Tech Stack:** Kotlin Multiplatform (`composeApp` commonMain/iosMain/commonTest), Jetpack Compose Canvas + animation APIs.

## Global Constraints

- Passive feedback only — no new tap-to-confirm interaction, no change to promotion timing/thresholds (`distanceThresholdMeters`, `streakTicks` defaults stay as-is).
- `_liveDetections` is always 0 or 1 items — no per-object identity tracking needed anywhere in this plan.
- No Compose UI test harness exists in this codebase — `DetectionReticleOverlay`'s rendering/animation changes cannot be automatically tested; verify by careful reading only.
- `ArFrameFeedback.kt` is iOS-only Kotlin/Native code (real ARKit/Vision APIs) — not unit-testable in isolation, matching its existing (zero) test coverage.

---

### Task 1: `MarkerPromotionTracker` returns progress, not just a boolean

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/MarkerPromotionTracker.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/gcc/app/platform/MarkerPromotionTrackerTest.kt`

**Interfaces:**
- Produces: `data class PromotionProgress(val promoted: Boolean, val streak: Int, val streakTicks: Int)`, `MarkerPromotionTracker.onTick(hitDistanceMeters: Double?): PromotionProgress` — consumed by Task 2.

- [ ] **Step 1: Write the failing test**

Replace `composeApp/src/commonTest/kotlin/com/gcc/app/platform/MarkerPromotionTrackerTest.kt` in full with:

```kotlin
package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerPromotionTrackerTest {

    @Test
    fun promotesAfterTwoConsecutiveCloseTicks() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        val first = tracker.onTick(1.5)
        assertFalse(first.promoted)
        assertEquals(1, first.streak)
        assertEquals(2, first.streakTicks)

        val second = tracker.onTick(1.5)
        assertTrue(second.promoted)
        assertEquals(2, second.streak)
    }

    @Test
    fun farTickResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5).promoted)

        val farTick = tracker.onTick(5.0) // too far — resets
        assertFalse(farTick.promoted)
        assertEquals(0, farTick.streak)

        assertFalse(tracker.onTick(1.5).promoted) // streak restarts at 1
        assertTrue(tracker.onTick(1.5).promoted)  // now at 2
    }

    @Test
    fun nullDistanceResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5).promoted)

        val nullTick = tracker.onTick(null)
        assertFalse(nullTick.promoted)
        assertEquals(0, nullTick.streak)

        assertFalse(tracker.onTick(1.5).promoted)
    }

    @Test
    fun requiresFreshStreakAfterPromotion() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5).promoted)
        assertTrue(tracker.onTick(1.5).promoted)  // first promotion

        val afterPromotion = tracker.onTick(1.5)
        assertFalse(afterPromotion.promoted) // must NOT immediately re-promote — streak restarts at 1
        assertEquals(1, afterPromotion.streak)

        assertTrue(tracker.onTick(1.5).promoted) // now back at 2, promotes again
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.MarkerPromotionTrackerTest"`
Expected: FAIL — compile error, `onTick(...)` returns `Boolean`, which has no `.promoted`/`.streak`/`.streakTicks` members.

- [ ] **Step 3: Change `MarkerPromotionTracker` to return `PromotionProgress`**

Replace `composeApp/src/commonMain/kotlin/com/gcc/app/platform/MarkerPromotionTracker.kt` in full with:

```kotlin
package com.gcc.app.platform

/** Result of one [MarkerPromotionTracker.onTick] call. */
data class PromotionProgress(
    val promoted: Boolean,
    val streak: Int,
    val streakTicks: Int,
)

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

    /** Call once per tick with the current hit-test distance (or null if no hit). */
    fun onTick(hitDistanceMeters: Double?): PromotionProgress {
        if (hitDistanceMeters != null && hitDistanceMeters <= distanceThresholdMeters) {
            streak += 1
        } else {
            streak = 0
            return PromotionProgress(promoted = false, streak = 0, streakTicks = streakTicks)
        }
        val progressStreak = streak
        val promoted = streak >= streakTicks
        if (promoted) streak = 0
        return PromotionProgress(promoted = promoted, streak = progressStreak, streakTicks = streakTicks)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "com.gcc.app.platform.MarkerPromotionTrackerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: This breaks `ArFrameFeedback.kt`'s existing call site — expected, fixed in Task 2**

`ArFrameFeedback.kt:179` (`if (promotionTracker.onTick(distance?.toDouble()) && hit != null)`) will now fail to compile, since `onTick(...)` no longer returns `Boolean`. This is expected — do not fix it in this task. Note it in your report so Task 2's implementer isn't surprised to find iOS in a broken state before their own edit.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/MarkerPromotionTracker.kt composeApp/src/commonTest/kotlin/com/gcc/app/platform/MarkerPromotionTrackerTest.kt
git commit -m "MarkerPromotionTracker.onTick() returns PromotionProgress, not Boolean"
```

---

### Task 2: `LiveDetection` carries progress; wire `ArFrameFeedback`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArFeedback.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt`

**Interfaces:**
- Consumes: `PromotionProgress`, `MarkerPromotionTracker.onTick(...): PromotionProgress` (Task 1).
- Produces: `LiveDetection(cx, cy, r, label, distanceMeters, streakProgress: Int = 0, streakTicks: Int = 2, justPromoted: Boolean = false)` — consumed by Task 3.

- [ ] **Step 1: Add the new fields to `LiveDetection`**

In `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArFeedback.kt`, change:

```kotlin
data class LiveDetection(
    val cx: Float,
    val cy: Float,
    /** Normalized reticle radius (fraction of the view's smaller dimension). */
    val r: Float,
    val label: String,
    /** Estimated distance to the detection, when an AR hit-test resolved one. */
    val distanceMeters: Float? = null,
)
```

to:

```kotlin
data class LiveDetection(
    val cx: Float,
    val cy: Float,
    /** Normalized reticle radius (fraction of the view's smaller dimension). */
    val r: Float,
    val label: String,
    /** Estimated distance to the detection, when an AR hit-test resolved one. */
    val distanceMeters: Float? = null,
    /** Consecutive close-range ticks so far this streak (see [MarkerPromotionTracker]). */
    val streakProgress: Int = 0,
    /** Ticks required to promote -- for rendering streakProgress as a fraction. */
    val streakTicks: Int = 2,
    /** True for exactly the one tick a world marker was just placed. */
    val justPromoted: Boolean = false,
)
```

- [ ] **Step 2: Read the real current `ArFrameFeedback.kt` before editing**

Read `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt` in full (it's short, ~245 lines) to confirm lines 174-182 still match what's shown below before applying the diff — this file may have shifted slightly since this plan was written.

- [ ] **Step 3: Reorder the tracker call and thread progress into the detection**

In `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt`, change:

```kotlin
        _liveDetections.value = listOf(
            LiveDetection(cx = viewX, cy = viewY, r = radius, label = trashLabel, distanceMeters = distance)
        )

        // Close for two consecutive ticks → persist a world marker at the hit.
        if (promotionTracker.onTick(distance?.toDouble()) && hit != null) {
            val floats = matrixTranslation(hit.worldTransform)
            addMarkerAt(floats[0], floats[1], floats[2], WorldMarkerKind.CONFIRMED_TRASH)
        }
        return false
```

to:

```kotlin
        // Close for two consecutive ticks → persist a world marker at the hit.
        // Called before building this tick's LiveDetection so the reticle can
        // show this tick's progress, not last tick's.
        val progress = promotionTracker.onTick(distance?.toDouble())
        _liveDetections.value = listOf(
            LiveDetection(
                cx = viewX, cy = viewY, r = radius, label = trashLabel, distanceMeters = distance,
                streakProgress = progress.streak, streakTicks = progress.streakTicks,
                justPromoted = progress.promoted,
            )
        )
        if (progress.promoted && hit != null) {
            val floats = matrixTranslation(hit.worldTransform)
            addMarkerAt(floats[0], floats[1], floats[2], WorldMarkerKind.CONFIRMED_TRASH)
        }
        return false
```

The early-return path above this (`if (trashLabel == null) { promotionTracker.onTick(null); _liveDetections.value = emptyList(); return false }`, `ArFrameFeedback.kt:140-144`) is unaffected — `onTick(null)` now returns a `PromotionProgress` instead of a `Boolean`, but that return value was never checked there (only used to reset internal state), so no change is needed on that line. Confirm this against the real file rather than assuming.

- [ ] **Step 4: Verify**

This task's only change with cross-platform effect is `LiveDetection`'s new fields (all defaulted, so no other call site breaks). `ArFrameFeedback.kt` itself is iOS-only and cannot be compiled on this host.

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (confirms `LiveDetection`'s new fields don't break Desktop's stub `ArRouteOverlay`/`SpotArOverlay`, which reference `LiveDetection` only via the empty-list `StateFlow` type, not by constructing one).

Run: `./gradlew :composeApp:compileDebugKotlinAndroid -x processDebugResources -x mergeDebugResources -x packageDebugResources -x processDebugManifest -x processDebugMainManifest`
Expected: BUILD SUCCESSFUL (same reasoning — Android's stub doesn't construct `LiveDetection` either).

iOS itself cannot be compiled on this host — verify Step 3's edit by careful re-reading against the real file instead.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/platform/ArFeedback.kt composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt
git commit -m "Thread promotion progress through LiveDetection"
```

---

### Task 3: `DetectionReticleOverlay` renders the progress ring and checkmark flash

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt`

**Interfaces:**
- Consumes: `LiveDetection.streakProgress`, `LiveDetection.streakTicks`, `LiveDetection.justPromoted` (Task 2).

- [ ] **Step 1: Read the real current file before editing**

Read `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt` in full to confirm its exact current structure (imports, the `Canvas` draw loop) before applying the changes below.

- [ ] **Step 2: Add the progress arc and checkmark flash**

Replace `composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt` in full with:

```kotlin
package com.gcc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.LiveDetection
import com.gcc.app.ui.theme.GccGreen
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a circle reticle + label over each live trash detection from the AR
 * frame pipeline. Detections arrive in normalized view coordinates
 * (top-left origin), so this just scales to the canvas size.
 *
 * The reticle also shows the detection's promotion progress: a brighter arc
 * sweeps around the dim base circle as MarkerPromotionTracker's streak
 * builds, and a checkmark briefly flashes in place of the arc the tick a
 * world marker gets placed.
 */
@Composable
fun DetectionReticleOverlay(
    detections: List<LiveDetection>,
    modifier: Modifier = Modifier,
) {
    if (detections.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
    val checkmarkStyle = TextStyle(
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )

    val justPromoted = detections.first().justPromoted
    val checkmarkAlpha = remember { Animatable(0f) }
    LaunchedEffect(justPromoted) {
        if (justPromoted) {
            checkmarkAlpha.snapTo(1f)
            checkmarkAlpha.animateTo(0f, animationSpec = tween(durationMillis = 900))
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val minDim = min(size.width, size.height)
        for (detection in detections) {
            val center = Offset(detection.cx * size.width, detection.cy * size.height)
            val radius = (detection.r * minDim).coerceIn(28.dp.toPx(), minDim * 0.45f)

            // Dim base circle -- the "track" the progress arc sweeps around.
            drawCircle(
                color = GccGreen.copy(alpha = 0.35f),
                radius = radius,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = GccGreen.copy(alpha = 0.15f),
                radius = radius,
                center = center,
            )

            if (checkmarkAlpha.value > 0f) {
                val checkmark = textMeasurer.measure("✓", checkmarkStyle)
                drawText(
                    textLayoutResult = checkmark,
                    topLeft = Offset(
                        center.x - checkmark.size.width / 2f,
                        center.y - checkmark.size.height / 2f,
                    ),
                    alpha = checkmarkAlpha.value,
                )
            } else if (detection.streakTicks > 0 && detection.streakProgress > 0) {
                val sweepFraction = (detection.streakProgress.toFloat() / detection.streakTicks)
                    .coerceIn(0f, 1f)
                drawArc(
                    color = GccGreen,
                    startAngle = -90f,
                    sweepAngle = 360f * sweepFraction,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            val distanceSuffix = detection.distanceMeters?.let { d ->
                " · ${(d * 10).roundToInt() / 10.0}m"
            } ?: ""
            val label = textMeasurer.measure(detection.label + distanceSuffix, labelStyle)
            drawText(
                textLayoutResult = label,
                topLeft = Offset(
                    (center.x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width),
                    (center.y - radius - label.size.height - 6.dp.toPx()).coerceAtLeast(0f),
                ),
            )
        }
    }
}
```

Notes on this diff versus the original file: the outer stroke circle changed from full-opacity `GccGreen` to a dimmed `alpha = 0.35f` "track" (so the brighter progress arc reads as an overlay on top of it, standard circular-progress-indicator convention); the arc only draws when `streakProgress > 0` (so at streak 0 it looks exactly like today's plain dim circle, no regression for the very first tick of a new detection); the checkmark and the arc are mutually exclusive per the `if (checkmarkAlpha.value > 0f) ... else if (...)` structure, so they never visually overlap mid-fade.

- [ ] **Step 3: Verify**

No Compose UI test harness exists in this codebase (confirmed during design) — this cannot be automatically tested. Verify by:

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (confirms the Compose code compiles, even though Desktop's AR overlay stub never actually calls `DetectionReticleOverlay` with real detections to visually check).

A genuine visual check needs a real iOS device running `ArRouteScreen`/`SpotArScreen` — note this explicitly in your report as unverified beyond compilation and careful reading.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt
git commit -m "Render promotion progress ring and checkmark flash on the AR reticle"
```

---

## Self-Review Notes

- **Spec coverage**: §2's `MarkerPromotionTracker`/`PromotionProgress` change → Task 1. §2's `LiveDetection` field additions + `ArFrameFeedback` reordering → Task 2. §2's `DetectionReticleOverlay` progress-arc + checkmark-flash → Task 3. §3's test-coverage notes (tracker fully testable, `ArFrameFeedback`/overlay not) → carried into each task's own verification steps, not just the spec. §4 (out of scope) — no tap-to-confirm anywhere, no threshold/timing changes, `SpotArScreen`'s rectify sheet untouched.
- **Placeholder scan**: no TBD/TODO. Task 2 Step 2 and Task 3 Step 1 each have an explicit "read the real file first, confirm it matches" instruction rather than presenting the plan's own reconstruction as verified-exact — both are single-file, bounded verifications, not open-ended design work.
- **Type consistency**: `PromotionProgress(promoted, streak, streakTicks)` (Task 1) is constructed and read identically in Task 1's test and Task 2's `ArFrameFeedback` wiring. `LiveDetection`'s new fields (`streakProgress`, `streakTicks`, `justPromoted`) (Task 2) are populated identically in Task 2's `ArFrameFeedback` edit and read identically in Task 3's `DetectionReticleOverlay` edit — same names, same types, no drift.
- **Caught during self-review**: Task 3's original draft used a fully-qualified `androidx.compose.ui.geometry.Size(...)` inline instead of importing it, inconsistent with the rest of the file's style. Fixed by adding `import androidx.compose.ui.geometry.Size` and using the bare `Size(...)` constructor.
