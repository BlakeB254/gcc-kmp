# Object Capture round — measured 3D models of large dumps (GccArNative v4)

Date: 2026-07-05
Status: approved direction (Blake); the last item on the native-module roadmap

## Goal

For large litter (mattresses, tire piles, dump heaps) a photo undersells the
problem and defeats weight estimation. iOS 17's on-device Object Capture
gives us a guided orbit capture → photogrammetry reconstruction → USDZ model
**with real measured bounds**. The round wires that into the environment-scan
flow: worker taps "3D scan", orbits the pile, and the submission carries the
model's R2 key + measured dimensions + a volume-derived weight estimate.

## Architecture (same split as v1–v3)

Object Capture is Swift-only (`ObjectCaptureSession`, `ObjectCaptureView`
SwiftUI, `PhotogrammetrySession`) → same registration-pattern bridge:

### Kotlin contract (iosMain)

```kotlin
interface ObjectCaptureBridgeSurface {
    fun view(): UIView                        // guided capture UI (SwiftUI hosted)
    fun advance()                             // ready→detecting→capturing
    fun finishCapture()                       // done orbiting → reconstruction
    fun cancel()
    fun setListener(
        onState: ((stateRaw: Int, progress: Double) -> Unit)?,
        // usdzPath (null on failure), width/height/depth meters, volumeLiters
        onResult: ((String?, Double, Double, Double, Double) -> Unit)?,
    )
}
object ObjectCaptureRegistry { var factory: (() -> ObjectCaptureBridgeSurface)? }
```

State raw values: 0 initializing, 1 ready, 2 detecting, 3 capturing,
4 reconstructing, 5 completed, 6 failed. Swift registers the factory ONLY
when `ObjectCaptureSession.isSupported && PhotogrammetrySession.isSupported`
(LiDAR + A14 gate) — absence of the factory is the capability check.

### Swift (`ObjectCapturePhotogrammetry.swift`)

- `ObjectCaptureSession.start(imagesDirectory:)` into a tmp dir;
  `ObjectCaptureView(session:)` in a `UIHostingController`, its `view`
  handed to Compose (UIKitView).
- State observation task maps session states to the raw codes; capture
  completes → `PhotogrammetrySession(input: imagesDir, configuration:)` with
  `.reduced` detail → requests `[.modelFile(url:), .bounds]`; progress from
  `.requestProgress`; result callback with the USDZ path + bbox dims +
  `volumeLiters = bboxVolume × 0.5 fill factor × 1000`.
- Files: USDZ in `Documents/object-captures/<uuid>.usdz`; images dir cleaned
  after reconstruction.

### Compose / flow

- `ObjectCaptureController` (expect; Android/Desktop stubs unsupported) with
  a `StateFlow<ObjectCaptureStage>` + result; `ObjectCaptureHostView`.
- SpotScreen camera phase: a "3D scan" pill (visible when supported) opens a
  full-screen capture phase — host view + one bottom action button driven by
  stage (Start → Capture → Finish → Reconstructing progress → Done) + Cancel
  (this is an optional sub-flow; the no-escape rule applies to the scan
  session itself, which stays active behind it).
- Result lands in `SpotViewModel.objectCaptureResult`; submission carries
  `CreateSpotRequest.objectCapture` (new shared model `ObjectCaptureResult`:
  r2Key?, widthMeters, heightMeters, depthMeters, volumeLiters,
  estimatedWeightGrams); `uploadEnvScanSession` uploads the USDZ via the
  existing presigned-url flow (`ext = "usdz"`) and fills r2Key.
- Weight: `TrashItemCatalog.estimateWeightFromVolumeLiters` — loose mixed
  litter bulk density ≈ 130 g/L, clamped (commonTest-covered).

### Server

`/api/spots` accepts optional `objectCapture` and merges it into the anchor
world observation's `attributes` jsonb (objectCaptureR2Key, dimensions,
volumeLiters, estimatedWeightGrams). No migration.

## Out of scope

- Viewing the USDZ in-app (QuickLook round later); admin site 3D viewer.
- Object Capture from the route flow (spot/env scan only for v1).

## Testing

desktopTest (weight-from-volume), Kotlin iOS compile, xcodebuild sim (purge
xcode-frameworks first — header gains the bridge). Physical device REQUIRED
for the actual capture flow (simulator has no camera/LiDAR); flow must
degrade to "button hidden" on unsupported devices.
