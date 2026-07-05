# Route AR → RealityKit migration (GccArNative v2)

Date: 2026-07-05
Status: approved direction (Blake); follows PR #71's design doc §Follow-ups

## Goal

The "Begin Route" AR experience renders through the same native RealityKit
surface the environment scan got in PR #71 — fog-of-war cells, guidance
arrow, boundary walls, outside-boundary veil, and trash markers become
RealityKit entities, while every piece of route logic (CoverageTracker,
Vision loop, geo-referencing, marker queue) stays in Kotlin. SceneKit remains
the automatic fallback (registry pattern unchanged).

## Interface additions (`ArNativeSurface`)

Route rendering needs six new capabilities; one existing signature changes:

```kotlin
fun trackingStateRaw(): Int                       // 0 normal / 1 limited / 2 unavailable
fun setGroundGridVisible(visible: Boolean)        // route: false (fog owns the ground)
fun updateFogCells(flatXZ: List<Double>, cellSizeMeters: Double)
fun setGuidanceTarget(x: Double, z: Double)       // nearest-frontier arrow target
fun clearGuidanceTarget()
fun setBoundaryVeilVisible(visible: Boolean)      // camera-attached red warning veil
fun updateBoundaryWalls(flatSegments: List<Double>) // [midX, midZ, len, yawRad, isOuter] × n
fun addMarker(x: Double, y: Double, z: Double, kind: Int)  // 0 confirmed / 1 expected
```

Kotlin keeps the fog radius/cap policy (35 m, ≤600) and the wall segment
sampling (≤5 m segments, 45 m window, ≤200) — the surface just renders what
it's told, so SceneKit and RealityKit stay policy-identical.

## Kotlin changes

- **`ArRouteOverlay`**: `bindNative(surface)` mirror of the env scan; every
  render method branches `nativeSurface?.let { … } ?: sceneKitPath`. Fog
  conversion reuses the existing latLngToLocal math; the frontier arrow
  target ships as AR-local (x, z). `ArRouteOverlayView` factory tries the
  registry first, falls back to ARSCNView on any throw (same kill switch).
  v1 caveat: `trackingState` polls `trackingStateRaw()` on geo updates in
  native mode (no ARSessionDelegate there).
- **`ArFrameFeedback`** gains an optional `nativeSurfaceProvider` — pixel
  buffer + raycast come from the surface when present, and promoted markers
  render via `surface.addMarker(kind)`. `SpotArOverlay` passes nothing and
  keeps its SceneKit behavior (default parameter).
- The address→CVPixelBuffer helper moves to `ArNativeSurface.kt` as an
  internal extension shared by both overlays.

## Swift changes (`RealityKitArSurface`)

- **Fog cells**: grey translucent quads keyed like vector cells (own dict,
  cap 600); removed keys reveal.
- **Guidance arrow**: camera-anchored elongated emissive box at
  (0, −0.4, −1.0); a `SceneEvents.Update` subscription re-aims it each frame
  (`setOrientation(simd_quatf(from: -Z, to: direction))`) at the current
  target — RealityKit has no LookAt constraint, per-frame aiming replaces it.
  (Cone/pyramid meshes are iOS 18+; the box pointer works on 17.2.)
- **Veil**: camera-anchored red plane at −0.8 m, toggled via `isEnabled`.
- **Walls**: vertical plane entities from the flat segment list — green
  (outer) / amber (island) PBM materials with emissive glow, rebuilt per call.
- **Markers**: kind 0 = green disc (existing), kind 1 = orange disc
  (expected-trash seed).
- **`trackingStateRaw`** from `lastFrame.camera.trackingState`.
- **`setGroundGridVisible(false)`** skips/removes plane grid entities so the
  route view isn't grid+fog soup.

## Out of scope

- SpotArOverlay migration (v3, tiny surface).
- Fog reveal animation, expected-marker pulse (polish pass after device test).

## Testing

desktopTest + iOS compile/link + xcodebuild sim (Swift compile against the
regenerated header — purge `build/xcode-frameworks` first, known staleness).
Physical-device route walk test before merge: fog reveal, arrow aim, wall
alignment, veil trigger, fallback path.
