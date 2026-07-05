# GccArNative v1 — Swift RealityKit render module behind the Kotlin AR interfaces

Date: 2026-07-05
Status: approved direction (Blake, 2026-07-04/05); follows PR #70's design doc §"Agreed follow-up round"

## Goal

Give the environment scan a native Swift RealityKit render layer — the full
modern iPhone AR stack (ECS entities, native plane-anchor tracking, PBR/unlit
materials, particle effects, first-class classification API) — WITHOUT
forking the app: Swift owns the AR view and effects, KMP keeps owning every
piece of logic (Vision pipeline, outdoor gate, coverage, candidates, geo-ref
math, capture, submission).

## The interop constraint that shapes everything

RealityKit exposes no ObjC headers, so Kotlin can never call it. But the
dependency arrow points the other way too: `iosApp` (where Swift lives)
depends on the `ComposeApp` framework, so Kotlin can't import app-target
Swift classes at compile time. The codebase's established answer is the
**registration pattern** (PushNotificationManager.companion.registerCallback,
GoogleSignInBridge): Kotlin defines the interface + a registry object; Swift
implements the interface and registers a factory at app launch.

## Components

### 1. Kotlin contract (iosMain): `ArNativeSurface` + `ArNativeRegistry`

```kotlin
interface ArNativeSurface {
    fun view(): UIView                       // the ARView, hosted by UIKitView
    fun pause()
    fun currentPixelBuffer(): CVPixelBufferRef?  // Vision + silent capture
    fun cameraPosition(): List<Double>?          // [x,y,z] AR world
    /** Raycast from a normalized IMAGE-space point (top-left origin) —
     *  ARFrame.raycastQuery semantics. Returns [x,y,z] world hit or null. */
    fun raycast(imageX: Double, imageY: Double): List<Double>?
    fun groundDetected(): Boolean            // any floor plane live
    /** Vector-scan cells as a flat [x0,z0,x1,z1,…] AR-local list; Swift
     *  places them on its own detected floor height. */
    fun updateVectorCells(flatXZ: List<Double>, cellSizeMeters: Double)
    fun addMarker(x: Double, y: Double, z: Double)      // confirmed-trash ring
    fun emitLockEffect(x: Double, y: Double, z: Double) // lock-on particles/pulse
}

object ArNativeRegistry {
    /** Set from Swift AppDelegate at launch; null = SceneKit path. */
    var surfaceFactory: (() -> ArNativeSurface)? = null
}
```

Kotlin interfaces surface as ObjC protocols in the framework header, so a
Swift class can `import ComposeApp` and conform. Flat `List<Double>` arrays
(→ `NSArray<NSNumber>`) keep the bridge free of custom struct marshalling.

### 2. `ArEnvScanOverlay` learns a native mode

At `bind` time: if `ArNativeRegistry.surfaceFactory` is set, create the
surface inside try/catch — **any failure falls back to the SceneKit path**
(kill switch: an untested render layer must never brick the working scan).
In native mode:
- the Compose `UIKitView` hosts `surface.view()` instead of an ARSCNView;
- the existing 1s tick loop swaps its inputs: `surface.currentPixelBuffer()`
  for Vision, `surface.raycast(x,y)` for candidate placement (distance =
  |hit − cameraPosition|, computed in Kotlin), `surface.groundDetected()`
  for the status line;
- render outputs go to the surface: vector cells (Kotlin converts lat/lng →
  AR-local x,z with the same geo-ref math), `addMarker` on confirm,
  `emitLockEffect` on lock;
- ground grid needs NO Kotlin involvement — Swift renders it from its own
  plane-anchor delegate (and uses the real `ARPlaneAnchor.classification`
  API, no KVO).
Everything else (buses, gate, candidate state machine, catalogs, capture
JPEG path, thermal guard) is untouched — same code both modes.

### 3. Swift module: `iosApp/iosApp/GccArNative.swift`

`final class RealityKitArSurface: NSObject, ArNativeSurface, ARSessionDelegate`
- `ARView(frame:.zero, cameraMode:.ar, automaticallyConfigureSession:false)`;
  `ARWorldTrackingConfiguration` mirroring the Kotlin config (gravityAndHeading,
  horizontal planes, smoothed scene depth when supported).
- **Ground grid**: `session(_:didAdd/didUpdate/didRemove:)` anchors →
  per-plane `AnchorEntity(anchor:)` + `ModelEntity(mesh:.generatePlane)` sized
  to the plane extent, `UnlitMaterial` with a runtime-drawn grid texture
  (`TextureResource(image:options:)` from a CGImage; green for
  `.floor`-classified planes, faint white otherwise). Entity scale tracks the
  growing extent so grid squares stay 0.5 m.
- **Vector cells**: world-anchored plane entities at floor height, emissive
  green grid material, slow opacity pulse (`FromToByAnimation`), node cap 350.
- **Markers**: flat emissive green disc (`generateCylinder` h=0.01) at the
  confirm position.
- **Lock effect**: `ParticleEmitterComponent` burst on `#available(iOS 18)`,
  else a one-shot scale+fade pulse entity — same call either way.
- **Raycast**: `frame.raycastQuery(from:allowing:.estimatedPlane,alignment:.any)`
  → `session.raycast` (image-space normalized point, matching what Kotlin
  already computes from Vision bboxes).
- Registration in `iosAppApp.swift` `didFinishLaunching`:
  `ArNativeRegistry.shared.surfaceFactory = { RealityKitArSurface() }`
  (CrashDiagnosticsObserver precedent).

### 4. Xcode project

Add `GccArNative.swift` to the iosApp target in `project.pbxproj` (file ref +
sources build phase entry, mirroring CrashDiagnostics.swift's entries).

## Out of scope (v1)

- Route-scan AR (`ArRouteOverlay`) stays SceneKit — it migrates in v2 once
  the env surface is field-validated.
- Object Capture photogrammetry, RoomPlan — candidate v3 features.
- Android/Desktop: unaffected (registry never set).

## Error handling / performance

- Factory or session failure → log + SceneKit fallback (feature parity kept).
- Swift delegate work is O(planes) per anchor event; entity updates on main.
- Same Vision cadence/thermal guard as before (loop unchanged in Kotlin).

## Testing

- desktopTest + iOS compile/link gates (Kotlin side).
- `xcodebuild` simulator build compiles the Swift module (the real gate).
- Physical-device walk test required: RealityKit grid fidelity vs SceneKit,
  particle availability path on the device's iOS version, fallback trigger.
