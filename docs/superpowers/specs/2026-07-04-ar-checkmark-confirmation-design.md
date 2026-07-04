# AR Checkmark Confirmation UI — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Make the existing, already-working detection→promotion pipeline visible to the user. Passive feedback only — auto-promotion behavior is unchanged, this only adds a progress ring and a checkmark flash. Not a new tap-to-confirm interaction (that would change the core detection UX, considered and explicitly declined).

---

## 1. Background

Today, `ArFrameFeedback.analyzeTick()` (iOS-only, `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArFrameFeedback.kt:114-184`) runs once per second: classifies the camera frame, and if it looks like trash (`TrashDetectionRules.trashLabelFrom`), draws a plain green reticle circle via `DetectionReticleOverlay` (`composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt`) — cosmetic only, no click target. In parallel, `MarkerPromotionTracker` (`composeApp/src/commonMain/kotlin/com/gcc/app/platform/MarkerPromotionTracker.kt`) tracks a streak: if the same close-range hit persists for 2 consecutive ticks, a permanent 3D marker (SceneKit torus ring) silently appears in the AR world — `ArFrameFeedback.kt:179-182`.

**The gap**: nothing visible connects these two things. The reticle looks identical whether the streak is 0, 1, or about to promote. The marker just appears with zero interstitial feedback — no progress indicator, no confirmation moment. A user has no way to tell "the app is building toward placing a marker here" versus "this is just a passing detection."

There's already a reusable visual precedent in this codebase: `CheckpointMarker` (`composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CheckpointMarker.kt:53-59`) shows a circle that swaps to a bold white `"✓"` (✓) when a checkpoint completes. This design reuses that exact glyph/weight for consistency.

**Platform note**: this whole pipeline (`ArFrameFeedback`, live detection, promotion) is iOS-only today — Android's equivalent ML Kit-based pipeline doesn't exist yet (deferred, tracked separately as the paused Android AR parity work). Everything in this design lives in commonMain (`LiveDetection`, `MarkerPromotionTracker`, `DetectionReticleOverlay`), so it will work automatically on iOS immediately, compile cleanly everywhere else, and "just work" on Android once that pipeline eventually lands — no changes will be needed here for that to happen.

## 2. Design

### `MarkerPromotionTracker` — expose progress, not just the promotion boolean

Confirmed via direct reading (`MarkerPromotionTracker.kt`): `_liveDetections.value` is always exactly 0 or 1 items — the salient-region detection each tick — and `promotionTracker.onTick(distance)` is called with that *same* tick's hit distance. So the streak is inherently tied to "the current single detection," not a list of independently-tracked objects; no new identity/id field is needed anywhere in this design.

`onTick()` changes from returning `Boolean` to returning a small result type carrying the in-progress count:

```kotlin
data class PromotionProgress(
    val promoted: Boolean,
    val streak: Int,
    val streakTicks: Int,
)
```

```kotlin
class MarkerPromotionTracker(
    private val distanceThresholdMeters: Double = 2.0,
    private val streakTicks: Int = 2,
) {
    private var streak = 0

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

This is a breaking signature change (2 call sites, 1 existing test file) — deliberately chosen over a side-channel mutable property because it keeps "what this tick's result means" as one self-contained value, not two things a caller has to remember to read in the right order.

### `LiveDetection` — carry the progress through to the UI

```kotlin
data class LiveDetection(
    val cx: Float,
    val cy: Float,
    val r: Float,
    val label: String,
    val distanceMeters: Float? = null,
    val streakProgress: Int = 0,
    val streakTicks: Int = 2,
    val justPromoted: Boolean = false,
)
```

### `ArFrameFeedback.analyzeTick()` — reorder so the tracker call happens before the detection is built

Today, `_liveDetections.value` is assigned (`ArFrameFeedback.kt:174-176`) *before* `promotionTracker.onTick(...)` is called (`ArFrameFeedback.kt:179`). This needs reordering so the same tick's `LiveDetection` can carry that tick's progress:

```kotlin
val progress = promotionTracker.onTick(distance?.toDouble())
_liveDetections.value = listOf(
    LiveDetection(
        cx = viewX, cy = viewY, r = radius, label = trashLabel, distanceMeters = distance,
        streakProgress = progress.streak, streakTicks = progress.streakTicks, justPromoted = progress.promoted,
    )
)
if (progress.promoted && hit != null) {
    val floats = matrixTranslation(hit.worldTransform)
    addMarkerAt(floats[0], floats[1], floats[2], WorldMarkerKind.CONFIRMED_TRASH)
}
```

The early-return path (no trash label this tick, `ArFrameFeedback.kt:140-144`) already calls `promotionTracker.onTick(null)` and clears `_liveDetections` — unchanged, since there's no detection to show progress on.

### `DetectionReticleOverlay` — render the progress ring and the checkmark flash

Two additions to the existing `Canvas` draw loop (`composeApp/src/commonMain/kotlin/com/gcc/app/ui/components/DetectionReticleOverlay.kt`):

1. **Progress ring**: instead of a full-circle stroke always, draw a `drawArc` sweep proportional to `streakProgress / streakTicks` (e.g. a full stroke circle in a dim color as the "track", plus a brighter arc overlay for "progress" — mirroring a standard circular-progress-indicator visual). At `streakProgress == 0` this looks like today's plain circle; as it climbs toward `streakTicks` the bright arc sweeps toward a full circle.
2. **Checkmark flash on promotion**: when `justPromoted == true`, briefly show `"✓"` (same glyph/weight as `CheckpointMarker`) centered in the reticle, fading out over ~900ms using an `Animatable` alpha driven by a `LaunchedEffect(justPromoted)` block — the effect re-launches whenever `justPromoted`'s value changes, so it fires exactly once per real promotion event even though the underlying `LiveDetection` list only holds that flag `true` for a single ~1-second tick.

```kotlin
val currentDetection = detections.firstOrNull()
val justPromoted = currentDetection?.justPromoted == true
val checkmarkAlpha = remember { Animatable(0f) }
LaunchedEffect(justPromoted) {
    if (justPromoted) {
        checkmarkAlpha.snapTo(1f)
        checkmarkAlpha.animateTo(0f, animationSpec = tween(durationMillis = 900))
    }
}
```

The progress-ring arc and the checkmark are mutually exclusive per frame (draw the arc when `checkmarkAlpha.value == 0f`, draw the fading checkmark text otherwise) so they don't visually overlap mid-fade.

## 3. Test coverage

- `MarkerPromotionTrackerTest`: updated for the new `PromotionProgress` return type — the existing "far tick resets streak" / "null distance resets streak" behaviors are preserved, plus new assertions on `streak`/`streakTicks` values at each step (e.g. tick 1 → `streak=1, promoted=false`; tick 2 → `streak=2` momentarily before reset, `promoted=true`). Fully testable, no platform dependency.
- `ArFrameFeedback.kt`'s wiring change is iOS-only Kotlin/Native code using real ARKit/Vision APIs — not unit-testable in isolation, consistent with this file having no existing test coverage today. Verified by careful reading only.
- `DetectionReticleOverlay`'s Canvas/animation changes: no Compose UI test harness exists anywhere in this codebase (confirmed via search) — verified by careful reading only; a genuine visual check needs a real iOS device, since Android/Desktop's AR overlays are empty stubs with nothing to render regardless.

## 4. Out of scope

- Tap-to-confirm / reject interaction — considered, explicitly declined in favor of passive feedback (see the scoping conversation this spec followed from).
- Android's detection pipeline itself (ML Kit equivalent of `ArFrameFeedback`) — separate, already-tracked, paused work. This design's commonMain pieces (`LiveDetection`, `MarkerPromotionTracker`, `DetectionReticleOverlay`) will automatically light up once that pipeline exists; no changes needed here for that to happen.
- Any change to the underlying promotion timing/threshold (`distanceThresholdMeters`, `streakTicks` defaults) — purely visual feedback of the existing values.
- `SpotArScreen.kt`'s rectify-sheet flow (GPS-proximity-driven, server-known `WorldObservation`s) — unrelated mechanism, not touched.
