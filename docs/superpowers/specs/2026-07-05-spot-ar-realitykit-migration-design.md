# Spot AR → RealityKit migration (GccArNative v3)

Date: 2026-07-05
Status: approved direction (Blake); follows PR #72's design doc §Remaining

## Goal

`SpotArOverlay` (the ring of capture-angle sector posts around a litter item
+ guidance arrow) renders through the native RealityKit surface, completing
the migration of all three AR experiences. Same split as v1/v2: Kotlin owns
sector math, geo-referencing and the Vision loop; Swift renders.

## Scope

One new `ArNativeSurface` primitive:

```kotlin
/** Spot mode: uncovered capture-angle posts as flat [x0, z0, …] AR-local
 *  positions; covered sectors disappear (reveal-by-omission). */
fun updateSectorPosts(flatXZ: List<Double>)
```

Swift renders posts as thin emissive orange pillars (0.06 × 0.5 × 0.06 boxes
— cylinder meshes are iOS 18+) standing on the detected floor height.

Kotlin `SpotArOverlay` gets the identical native-mode treatment the route
overlay got in v2: `bindNative` (grid hidden, `ArFrameFeedback` native
provider already wired via its constructor), `updateCoverage` ships post
positions + reuses `setGuidanceTarget`/`clearGuidanceTarget` for the arrow,
tracking mirrored via `ArSessionHealth.overrideState` on geo updates, and the
registry-first view factory with SceneKit fallback.

## Testing

desktopTest + iOS compile + xcodebuild sim (purge `build/xcode-frameworks`
before the Xcode build — header gains a method). Device spot-scan test
pending alongside the route walk test.
