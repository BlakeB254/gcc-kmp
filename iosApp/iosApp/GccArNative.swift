import Combine
import CoreImage
import Foundation
import ARKit
import RealityKit
import UIKit
import Vision
import ComposeApp

/// Native RealityKit render surface for the environment scan.
///
/// Implements the Kotlin-defined `ArNativeSurface` contract (see
/// `ArNativeSurface.kt`): Swift owns the `ARView`, plane-anchored ground
/// grid, vector-scan cells, markers and lock effects — Kotlin keeps owning
/// the Vision pipeline, candidate logic, geo-referencing and capture, and
/// reaches the session only through this class. Registered from
/// `AppDelegate.didFinishLaunching`; any failure there or here makes Kotlin
/// fall back to its SceneKit path, so this layer can never brick the scan.
final class RealityKitArSurface: NSObject, ArNativeSurface, ARSessionDelegate {

    private let arView: ARView
    /// Strong hold on the latest frame so the CVPixelBuffer pointer handed to
    /// Kotlin stays valid for the duration of one Vision tick.
    private var lastFrame: ARFrame?
    private let frameLock = NSLock()
    /// Background session recorder (Pillar 1) — created on the first
    /// `startCapture`. Lives entirely off the render path: `append(frame:)`
    /// is allocation-light on the delegate thread and can never throw.
    private var recorder: ArSessionRecorder?

    /// LiDAR ground-anomaly detector (litter candidates from depth geometry
    /// alone — see GroundAnomalyDetector.swift). Stateless per-call; owned
    /// here purely so `drainGroundCandidates()` has something to call.
    private let groundAnomalyDetector = GroundAnomalyDetector()
    /// Throttle for `drainGroundCandidates()`: the detector is CPU-heavy
    /// (unprojects + labels up to 3072 grid cells), so it runs at most once
    /// per `groundAnalyzeInterval` no matter how often Kotlin polls.
    private var lastGroundAnalyzeTime: CFAbsoluteTime = 0
    private static let groundAnalyzeInterval: CFTimeInterval = 0.5

    /// Minor grid interval — stays this size no matter how large a plane
    /// grows. 1.0m = a clean divisor of CoverageTracker's 3m cells (the minor
    /// interval from global constraint 7's 1m/3m grid), and the grid is
    /// world-axis aligned (compass-true under gravityAndHeading), so the
    /// on-camera lattice visually registers with the north-up minimap grid.
    /// MUST MATCH `GRID_TILE_METERS` in ArEnvScan.kt — the RealityKit and
    /// SceneKit paths phase the same lattice; the two are not read from a
    /// shared constant, so keep them in sync by hand.
    private static let gridTileMeters: Float = 1.0
    /// World size of ONE texture repeat: the grid texture is a 3×3-cell tile
    /// (thin interior minor lines, heavy outer major line), so one repeat
    /// spans 3m — the major interval and CoverageTracker's cell size. Every
    /// UV scale/offset and lattice snap for the ground grid must use THIS,
    /// not `gridTileMeters` — dividing by the minor interval would render
    /// the whole 3×3 texture per metre (minor lines every 0.33m). Matches
    /// `GRID_MAJOR_TILE_METERS` in ArEnvScan.kt.
    private static let gridMajorTileMeters: Float = gridTileMeters * 3

    /// Only one ARSession may hold the camera at a time. The Kotlin factory
    /// (`ArNativeRegistry.surfaceFactory`) makes a fresh `RealityKitArSurface`
    /// per scan session with no guarantee the previous session's `pause()`
    /// (driven by the Compose `UIKitView`'s `onRelease`) has already run —
    /// dispose ordering across the Kotlin/Native bridge isn't guaranteed.
    /// Weak so a surface that DID get released properly doesn't leak here.
    private static weak var liveSurface: RealityKitArSurface?

    /// ONE stable ground grid (spec system 2): a single RealityKit entity
    /// covering the monotonic union of every floor-classified ARPlaneAnchor's
    /// world AABB ever observed — never replaced, never shrunk. Before this,
    /// every detected ARPlaneAnchor (the floor fragmenting/re-merging as
    /// ARKit refines it, PLUS every piece of furniture) got its own grid
    /// entity, which is what produced the reported "spamming
    /// overlapping/flashing" grids: several independently-positioned quads
    /// live at once, re-snapping on every plane update. Consolidating to one
    /// entity makes "how many grids exist" a static invariant instead of a
    /// function of how ARKit happens to be segmenting the room right now.
    private var floorGrid: PlaneGridEntity?
    // The floor AABB fields below (+ floorWorldY) are guarded by `frameLock`
    // on BOTH sides: written on the session-delegate/main thread
    // (upsertPlane / teardown / setGroundGridVisible) and read off-main by
    // drainGroundCandidates() — lock every mutation AND every off-main read.
    private var floorMinX: Float?
    private var floorMaxX: Float?
    private var floorMinZ: Float?
    private var floorMaxZ: Float?
    /// AR-local origin of the geo-anchored CoverageTracker cell lattice (set
    /// from Kotlin via `setGridPhaseOrigin`). The grid snaps its bounds to
    /// multiples of `gridMajorTileMeters` from this point so the on-camera
    /// geogrid registers with the minimap's cell grid. Nil until the first
    /// geo-reference pairing — the grid phases to the AR world origin until
    /// then. TERMINAL once set (see `setGridPhaseOrigin`) — "stability beats
    /// accuracy for the grid": content (sector posts/guidance) keeps
    /// re-anchoring on every re-pair, the grid itself never does.
    private var gridPhaseOrigin: SIMD2<Float>?
    private var wallEntities: [AnchorEntity] = []
    private var floorWorldY: Float?
    private var groundLive = false
    private var groundGridVisible = true

    // Route-mode extras: camera-attached guidance arrow + warning veil.
    private let cameraAnchor = AnchorEntity(.camera)
    private var veilEntity: ModelEntity?
    /// The frontier target the beacon sits on. (The camera-attached guidance
    /// arrow stays deleted — global constraint 8; the beacon is world-anchored
    /// ground UI, not camera chrome.)
    private var guidanceTarget: SIMD3<Float>?
    private var coachingOverlay: ARCoachingOverlayView?

    // Frontier beacon (spec 2026-07-10): ONE pooled world-anchored ground
    // ring, moved on every target change — NEVER an entity per target (the
    // sector-post per-cell dictionary is the anti-pattern that caused
    // the Complete Scan entity blowup). Torn down in teardown() alongside
    // the veil.
    private var beaconAnchor: AnchorEntity?
    private var beaconModel: ModelEntity?
    private var beaconSuppressed = false
    /// Cool blue — deliberately outside the litter color language (green =
    /// confirmed, orange = expected/litter, red = warning).
    private static let beaconTint = UIColor(red: 0.3, green: 0.6, blue: 1.0, alpha: 0.7)
    private lazy var beaconTexture: TextureResource? = Self.makeBeaconRingTexture()

    // Pooled scan reticles (spec Task 5): accepted litter candidates get a
    // ground reticle with a fill bar that grows with scan evidence; at 100%
    // a scan-complete treatment holds 1.5s then dissolves. Pool of 3 —
    // matches the beacon's anti-pattern warning (never an entity per
    // candidate): entities are created once, lazily, on first use (see
    // `ensureReticlePool()`), then reused via `reticleIndexByCandidate` +
    // an LRU list. Torn down in `teardown()` alongside the beacon.
    private static let reticlePoolSize = 3
    private static let reticleGroundOffset: Float = 0.02
    private static let reticleRingDiameter: Float = 0.28
    private static let reticleFillBarWidth: Float = 0.22
    private static let reticleFillBarDepth: Float = 0.045
    private static let reticleFillBarHalfWidth: Float = reticleFillBarWidth / 2
    private static let reticleFillBarZOffset: Float = reticleRingDiameter / 2 + 0.09
    private static let reticleRingTint = UIColor.white.withAlphaComponent(0.9)
    // Matches the existing "confirmed = green" litter color language (the
    // grid line tint / confirmed marker color elsewhere in this file).
    private static let reticleCompleteTint = UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.95)
    /// AWAITING_INTENT amber — the "is this litter?" question state.
    private static let reticleIntentTint = UIColor(red: 1.0, green: 0.72, blue: 0.20, alpha: 0.95)
    // Cool blue, same system-chrome family as the frontier beacon — outside
    // the litter color language (green = confirmed, orange = expected,
    // red = warning) on purpose.
    private static let reticleFillTint = UIColor(red: 0.3, green: 0.65, blue: 1.0, alpha: 0.9)
    private var reticlePool: [PooledReticle] = []
    private var reticleIndexByCandidate: [Int64: Int] = [:]
    /// Active reticle ids, oldest-touched first — `acquireReticle` moves an
    /// id to the end on every call (new bind AND re-touch), so index 0 is
    /// always the LRU-eviction target when a 4th distinct id arrives.
    private var reticleLru: [Int64] = []

    private lazy var floorGridTexture: TextureResource? = Self.makeGridTexture(
        line: UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.9), lineWidth: 3
    )
    /// Per-texel opacity mask for the grid material — white lines on black,
    /// same geometry as `floorGridTexture`. REQUIRED: RealityKit's
    /// `.transparent(opacity:)` with a scalar does NOT derive per-texel alpha
    /// from the base-color texture, so the tile's transparent gaps rendered
    /// as premultiplied BLACK at the tint's alpha — the "large grey cover
    /// over the lot" field bug (2026-07-25 video, 3:23). With this mask the
    /// gaps are opacity 0 (invisible) and only the lines draw.
    private lazy var floorGridOpacityTexture: TextureResource? = Self.makeGridOpacityMask(lineWidth: 3)
    private lazy var beaconOpacityTexture: TextureResource? = Self.makeBeaconOpacityMask()

    override init() {
        arView = ARView(frame: .zero, cameraMode: .ar, automaticallyConfigureSession: false)
        super.init()

        // Release the previous session's camera BEFORE running this one — two
        // live ARSessions fighting over AVCaptureDevice is the crash-on-second-
        // scan root cause this guards against (see `liveSurface` kdoc above).
        Self.liveSurface?.pause()
        Self.liveSurface = self

        let config = ARWorldTrackingConfiguration()
        // Compass-true axes — the Kotlin geo-ref math (east = +X, north = -Z)
        // depends on this exactly like the SceneKit sessions.
        config.worldAlignment = .gravityAndHeading
        config.planeDetection = [.horizontal]
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.smoothedSceneDepth) {
            config.frameSemantics = .smoothedSceneDepth
        } else if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            config.frameSemantics = .sceneDepth
        }
        arView.session.delegate = self
        arView.session.run(config)

        arView.scene.addAnchor(cameraAnchor)
        // Global constraint 7: the grid must not draw over plants, fences, or
        // cars. On LiDAR devices ARKit's reconstructed mesh occludes it.
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            arView.environment.sceneUnderstanding.options.insert(.occlusion)
        }

        let coaching = ARCoachingOverlayView()
        coaching.session = arView.session
        coaching.goal = .horizontalPlane
        coaching.activatesAutomatically = true
        coaching.frame = arView.bounds
        coaching.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        arView.addSubview(coaching)
        coachingOverlay = coaching
    }

    // MARK: - ArNativeSurface

    func view() -> UIView { arView }

    func pause() {
        arView.session.pause()
        frameLock.lock()
        lastFrame = nil
        frameLock.unlock()
        if Self.liveSurface === self {
            Self.liveSurface = nil
        }
    }

    /// Full teardown, not just pause: removes EVERY created entity (planes,
    /// sector posts, walls, veil) from the RealityKit scene
    /// graph. `pause()` alone left every entity — potentially 750+ on a large
    /// scan — still anchored in the scene holding GPU resources, which is the
    /// scan-end crash-on-Complete-Scan root cause this fixes. Called from
    /// Kotlin's `ArEnvScanOverlay.stop()` (and the route/spot overlays)
    /// instead of `pause()` at composition teardown.
    func teardown() {
        // Kotlin's stop order calls stopCaptureAndDrain() BEFORE teardown;
        // if capture is somehow still live here, finalize + drop silently —
        // teardown must stay safe regardless of ordering.
        recorder?.finishSilently()
        recorder = nil
        lastGroundAnalyzeTime = 0

        floorGrid?.anchor.removeFromParent()
        floorGrid = nil
        // frameLock: the floor AABB fields are read off-main (see their kdoc).
        frameLock.lock()
        floorMinX = nil
        floorMaxX = nil
        floorMinZ = nil
        floorMaxZ = nil
        frameLock.unlock()


        for wall in wallEntities { wall.removeFromParent() }
        wallEntities.removeAll()

        veilEntity?.removeFromParent()
        veilEntity = nil
        beaconAnchor?.removeFromParent()
        beaconAnchor = nil
        beaconModel = nil
        guidanceTarget = nil

        for reticle in reticlePool { reticle.anchor.removeFromParent() }
        reticlePool.removeAll()
        reticleIndexByCandidate.removeAll()
        reticleLru.removeAll()

        cameraAnchor.removeFromParent()

        coachingOverlay?.setActive(false, animated: false)
        coachingOverlay = nil

        pause()
    }

    func currentPixelBufferAddress() -> Int64 {
        frameLock.lock()
        defer { frameLock.unlock() }
        guard let frame = lastFrame else { return 0 }
        // lastFrame stays strongly held until the next frame, so the pointer
        // remains valid while Kotlin runs one Vision pass over it.
        let pointer = Unmanaged.passUnretained(frame.capturedImage).toOpaque()
        return Int64(Int(bitPattern: pointer))
    }

    func cameraPosition() -> [KotlinDouble]? {
        frameLock.lock()
        defer { frameLock.unlock() }
        guard let frame = lastFrame else { return nil }
        let t = frame.camera.transform.columns.3
        return [KotlinDouble(double: Double(t.x)), KotlinDouble(double: Double(t.y)), KotlinDouble(double: Double(t.z))]
    }

    /// ARKit clock of the latest frame — the SAME clock the pose sidecar
    /// stamps `t` with, so a (wall-clock, this) pair at geo-ref time lets the
    /// DGX pipeline align the pose stream with the GPS track.
    func currentFrameTimestamp() -> Double {
        frameLock.lock()
        defer { frameLock.unlock() }
        return lastFrame?.timestamp ?? -1.0
    }

    func raycast(imageX: Double, imageY: Double) -> [KotlinDouble]? {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return nil }
        let point = CGPoint(x: imageX, y: imageY)
        // Preferred: ARKit's estimated-plane raycast.
        let query = frame.raycastQuery(from: point, allowing: .estimatedPlane, alignment: .any)
        if let hit = arView.session.raycast(query).first {
            let t = hit.worldTransform.columns.3
            return [KotlinDouble(double: Double(t.x)), KotlinDouble(double: Double(t.y)), KotlinDouble(double: Double(t.z))]
        }
        // Fallback 1: tracked plane geometry — estimated-plane raycasts
        // routinely return nothing on low-texture pavement even while a
        // perfectly good tracked ground plane exists (TestFlight 162: taps
        // on clearly visible ground trash died as "hold steady and tap
        // again" forever, because a single-shot estimated-plane query was
        // the only path a tap had).
        let planeQuery = frame.raycastQuery(from: point, allowing: .existingPlaneGeometry, alignment: .any)
        if let hit = arView.session.raycast(planeQuery).first {
            let t = hit.worldTransform.columns.3
            return [KotlinDouble(double: Double(t.x)), KotlinDouble(double: Double(t.y)), KotlinDouble(double: Double(t.z))]
        }
        // Fallback 2: intersect the tap's camera ray with the known ground
        // height (the same floorWorldY every other ground-anchored feature
        // uses). Only for downward rays with a plausible (<30m) hit — an
        // at-horizon tap still misses, which is honest.
        frameLock.lock()
        let knownFloorY = floorWorldY
        frameLock.unlock()
        let groundY = knownFloorY ?? cameraFallbackGroundY()
        let origin = query.origin
        let direction = query.direction
        guard direction.y < -0.05 else { return nil }
        let along = (groundY - origin.y) / direction.y
        guard along > 0, along < 30 else { return nil }
        let p = origin + direction * along
        return [KotlinDouble(double: Double(p.x)), KotlinDouble(double: Double(p.y)), KotlinDouble(double: Double(p.z))]
    }

    func groundDetected() -> Bool { groundLive }

    func ceilingDetected() -> Bool {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return false }
        return frame.anchors.contains { anchor in
            guard let plane = anchor as? ARPlaneAnchor else { return false }
            if #available(iOS 12.0, *) {
                return plane.classification == .ceiling
            }
            return false
        }
    }

    /// Swift-side Vision scene classification of the current frame — top-15
    /// "identifier|confidence" entries, empty when no frame. Native path only;
    /// keeps ALL Vision work in Swift (Kotlin applies the shared scoring).
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

    /// LiDAR ground-anomaly candidates (see GroundAnomalyDetector.swift):
    /// runs at most every `groundAnalyzeInterval`, skips under thermal
    /// pressure, and needs a floor established. Called SYNCHRONOUSLY on the
    /// caller's thread (Kotlin polls this off-main) — grabs `lastFrame` and
    /// the floor AABB fields under `frameLock`, then releases the lock
    /// before doing any of the detector's pixel work.
    func drainGroundCandidates() -> [String] {
        if ProcessInfo.processInfo.thermalState.rawValue >= 2 { return [] }
        let now = CFAbsoluteTimeGetCurrent()
        if now - lastGroundAnalyzeTime < Self.groundAnalyzeInterval { return [] }

        frameLock.lock()
        let frame = lastFrame
        let minX = floorMinX
        let maxX = floorMaxX
        let minZ = floorMinZ
        let maxZ = floorMaxZ
        let floorY = floorWorldY
        frameLock.unlock()

        guard let frame, let minX, let maxX, let minZ, let maxZ, let floorY else { return [] }
        lastGroundAnalyzeTime = now
        return groundAnomalyDetector.analyze(
            frame: frame,
            floorY: floorY,
            floorBounds: (minX: minX, maxX: maxX, minZ: minZ, maxZ: maxZ)
        )
    }

    /// Vision classification over a NORMALIZED-coordinate crop of the
    /// current frame (top-left origin, matching a `GroundCandidatePacket`'s
    /// image fields) — same request handling and error posture as
    /// `classifySceneLabels`: any failure returns `[]`, never throws.
    /// Normalized VIEW point (top-left origin, the Compose tap space) →
    /// normalized IMAGE point (capturedImage space), via ARKit's
    /// displayTransform — the authoritative aspect-fill mapping. The old
    /// Kotlin-side pure-rotation math ignored the fill crop (4:3 sensor on a
    /// ~19.5:9 screen crops ~19% of the image per side), mislocating taps by
    /// up to a meter at range — "it selected an area I didn't tap"
    /// (build 170 root cause). Empty when no frame yet.
    func floorHeightY() -> Double {
        frameLock.lock()
        defer { frameLock.unlock() }
        return floorWorldY.map(Double.init) ?? Double.nan
    }

    func viewToImagePoint(x: Double, y: Double) -> [KotlinDouble] {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return [] }
        let viewSize = arView.bounds.size
        guard viewSize.width > 0, viewSize.height > 0 else { return [] }
        // displayTransform maps normalized image coords -> normalized view
        // coords for the given orientation; invert it for view -> image.
        let transform = frame.displayTransform(for: .portrait, viewportSize: viewSize).inverted()
        let imagePoint = CGPoint(x: x, y: y).applying(transform)
        return [KotlinDouble(double: Double(imagePoint.x)), KotlinDouble(double: Double(imagePoint.y))]
    }

    /// Inverse of `viewToImagePoint` — for drawing screen-space markers over
    /// image-space boxes (tap-sweep dots). Empty when no frame yet.
    func imageToViewPoint(x: Double, y: Double) -> [KotlinDouble] {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return [] }
        let viewSize = arView.bounds.size
        guard viewSize.width > 0, viewSize.height > 0 else { return [] }
        let transform = frame.displayTransform(for: .portrait, viewportSize: viewSize)
        let viewPoint = CGPoint(x: x, y: y).applying(transform)
        return [KotlinDouble(double: Double(viewPoint.x)), KotlinDouble(double: Double(viewPoint.y))]
    }

    func classifyRegionLabels(cx: Double, cy: Double, halfW: Double, halfH: Double) -> [String] {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return [] }

        let pixelBuffer = frame.capturedImage
        let width = Double(CVPixelBufferGetWidth(pixelBuffer))
        let height = Double(CVPixelBufferGetHeight(pixelBuffer))
        let minXTopDown = max(0, cx - halfW)
        let maxXTopDown = min(1, cx + halfW)
        let minYTopDown = max(0, cy - halfH)
        let maxYTopDown = min(1, cy + halfH)
        guard maxXTopDown > minXTopDown, maxYTopDown > minYTopDown else { return [] }

        // CIImage's coordinate space is bottom-left origin, y UP — flip the
        // normalized top-down (video-orientation) rect before cropping.
        let cropRect = CGRect(
            x: minXTopDown * width,
            y: (1 - maxYTopDown) * height,
            width: (maxXTopDown - minXTopDown) * width,
            height: (maxYTopDown - minYTopDown) * height
        )
        let cropped = CIImage(cvPixelBuffer: pixelBuffer).cropped(to: cropRect)

        let request = VNClassifyImageRequest()
        let handler = VNImageRequestHandler(ciImage: cropped, options: [:])
        guard (try? handler.perform([request])) != nil, let results = request.results else { return [] }
        let appleLabels = results
            .sorted { $0.confidence > $1.confidence }
            .prefix(10)
            .map { "\($0.identifier)|\($0.confidence)" }
        // Stage-2 slot: when the purpose-trained crop classifier ships in
        // the bundle, its verdict is PREPENDED — Kotlin's first-match rules
        // then prefer it over the generic taxonomy, with the Apple labels
        // still present as vehicle/scene context. No model = no change.
        let trained = LitterCropClassifier.shared.classify(cropped: cropped)
        return trained + appleLabels
    }

    /// Camera forward ray in AR world space — the -Z column of the camera
    /// transform (camera looks down -Z), or empty before the first frame.
    func cameraForward() -> [KotlinFloat] {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return [] }
        let c = frame.camera.transform.columns.2
        return [KotlinFloat(float: -c.x), KotlinFloat(float: -c.y), KotlinFloat(float: -c.z)]
    }

    func addMarker(x: Double, y: Double, z: Double, kind: Int32) {
        DispatchQueue.main.async { [self] in
            let anchor = AnchorEntity(world: SIMD3(Float(x), Float(y), Float(z)))
            // Flat emissive disc (squashed sphere — cylinder mesh is iOS 18+).
            // kind 0 = confirmed trash (green), 1 = expected seed (orange).
            let color = kind == 0
                ? UIColor.green.withAlphaComponent(0.85)
                : UIColor.orange.withAlphaComponent(0.85)
            let disc = ModelEntity(
                mesh: .generateSphere(radius: 0.18),
                materials: [UnlitMaterial(color: color)]
            )
            disc.scale = SIMD3(1, 0.08, 1)
            anchor.addChild(disc)
            arView.scene.addAnchor(anchor)
            animateIn(disc)
            if kind == 1 {
                // Expected-trash seeds pulse: "come check this" (mirrors the
                // SceneKit EXPECTED_TRASH behavior).
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak disc] in
                    guard let disc else { return }
                    self.addPulse(to: disc, scale: 1.3, duration: 0.8)
                }
            }
        }
    }

    func trackingStateRaw() -> Int32 {
        frameLock.lock()
        defer { frameLock.unlock() }
        switch lastFrame?.camera.trackingState {
        case .normal: return 0
        case .limited: return 1
        default: return 2
        }
    }

    func setGroundGridVisible(visible: Bool) {
        DispatchQueue.main.async { [self] in
            groundGridVisible = visible
            // The plane-acquisition coaching prompt is an env-scan affordance;
            // in route/spot mode (grid hidden, posts own the ground) an
            // auto-activating "find a surface" overlay is intrusive — and the
            // SceneKit fallbacks never had one.
            coachingOverlay?.activatesAutomatically = visible
            if !visible {
                coachingOverlay?.setActive(false, animated: false)
                floorGrid?.anchor.removeFromParent()
                floorGrid = nil
                // frameLock: floor AABB fields are read off-main (see kdoc).
                frameLock.lock()
                floorMinX = nil
                floorMaxX = nil
                floorMinZ = nil
                floorMaxZ = nil
                frameLock.unlock()
                groundLive = false
            }
        }
    }

    /// ONE-SHOT LOCK (spec system 2): the first non-nil phase origin is
    /// TERMINAL. Geo-ref re-pairs arrive on every GPS fix — re-phasing the
    /// grid on each one (the pre-fix behavior) is exactly what produced the
    /// reported flashing/overlapping grid spam, since every re-phase
    /// regenerates the mesh and repositions the anchor. "Stability beats
    /// accuracy for the grid": sector posts/guidance keep re-anchoring on
    /// every re-pair; the grid locks to wherever it first landed and stays
    /// there for the rest of the session.
    func setGridPhaseOrigin(x: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            if gridPhaseOrigin != nil { return }
            gridPhaseOrigin = SIMD2(Float(x), Float(z))
            if let grid = floorGrid { applyPlaneGeometry(grid) }
        }
    }


    func setGuidanceTarget(x: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            let groundY = (floorWorldY ?? cameraFallbackGroundY())
            let position = SIMD3(Float(x), groundY + 0.02, Float(z))
            let firstShow = guidanceTarget == nil
            guidanceTarget = position
            let model = beacon()
            beaconAnchor?.setPosition(position, relativeTo: nil)
            let show = !beaconSuppressed
            if show && (firstShow || !model.isEnabled) {
                model.isEnabled = true
                animateIn(model, duration: 0.4)
            } else {
                model.isEnabled = show
            }
        }
    }

    func clearGuidanceTarget() {
        DispatchQueue.main.async { [self] in
            guidanceTarget = nil
            beaconModel?.isEnabled = false
        }
    }

    /// Credit-triggered dissolve: the patch was properly scanned. Shrinks the
    /// pooled ring out (≥0.4s, spec) then disables it — the entity is reused,
    /// never removed, so this can run every target for free.
    func dissolveGuidanceBeacon() {
        DispatchQueue.main.async { [self] in
            guard let model = beaconModel, model.isEnabled else { return }
            let restore = model.transform
            var end = restore
            end.scale = SIMD3(repeating: 0.01)
            model.move(to: end, relativeTo: beaconAnchor, duration: 0.4, timingFunction: .easeIn)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
                guard let self, let model = self.beaconModel else { return }
                model.isEnabled = false
                model.transform = restore
            }
        }
    }

    /// Confirm flows own the screen: hide while a tap pill / lock-on card is
    /// up, restore afterward only if a frontier target is still live.
    func setBeaconSuppressed(suppressed: Bool) {
        DispatchQueue.main.async { [self] in
            beaconSuppressed = suppressed
            beaconModel?.isEnabled = !suppressed && guidanceTarget != nil
        }
    }

    // MARK: - Scan reticle pool (spec Task 5)
    //
    // Called from Kotlin's tick loop, off-main — every entry hops to the
    // main queue first, exactly like the beacon methods above.

    /// Position/update the pooled scan reticle for candidate `id` at an
    /// AR-local ground point, filling its bar to `progress` (0...1).
    /// Acquires a pooled entity on first call for a given id (pool of 3,
    /// LRU-evicting the oldest active reticle past that); repeat calls for
    /// an id already showing just move/refill it.
    func setScanReticle(id: Int64, x: Float, y: Float, z: Float, progress: Float, extentM: Float, state: Int64) {
        DispatchQueue.main.async { [self] in
            // Phase gate: a COMPLETING scan is done. The Kotlin tick keeps
            // streaming setScanReticle while completeScanReticle's 1.5s
            // hold + dissolve run; letting those late updates through would
            // drag the green complete bar back down (or rebind the slot
            // mid-dissolve). They must not touch the slot at all.
            if let idx = reticleIndexByCandidate[id], reticlePool[idx].phase == .completing {
                return
            }
            let (reticle, didBind) = acquireReticle(for: id)
            reticle.anchor.position = SIMD3(x, y + Self.reticleGroundOffset, z)
            // Size the marker to the ITEM: the LiDAR packet's measured
            // footprint drives the ring diameter, so the highlight sits
            // precisely over a bottle (small ring) or a dumped bag (wide
            // ring) instead of one fixed 28cm circle over everything.
            // Scaling the ANCHOR keeps the ring's own transform animations
            // (grow-in, pulse) untouched.
            if extentM > 0 {
                let targetDiameter = min(max(extentM * 1.5, 0.22), 1.2)
                reticle.anchor.scale = SIMD3(repeating: targetDiameter / Self.reticleRingDiameter)
            } else if didBind {
                reticle.anchor.scale = SIMD3(repeating: 1)
            }
            if didBind {
                // FULL unconditional reset on every (re)bind, independent
                // of isEnabled — the slot may be mid-dissolve (shrink tween
                // in flight, complete material still applied, deferred
                // cleanup generation-invalidated) and only a hard reset
                // guarantees a sane starting state for the new occupant.
                reticle.ring.stopAllAnimations()
                reticle.fillBar.stopAllAnimations()
                reticle.ring.transform = Transform(scale: SIMD3(repeating: 1))
                reticle.ring.model?.materials = [reticle.ringDefaultMaterial]
                reticle.state = 0
                reticle.ring.isEnabled = true
                reticle.fillBar.isEnabled = true
                animateIn(reticle.ring)
            }
            // State styling: 1 = AWAITING_INTENT — the ring is the visual
            // anchor of the "is this litter?" prompt, so it must read as a
            // QUESTION (amber, gentle breathing pulse), not an in-progress
            // scan. Transitions are edge-triggered so the pulse animation
            // isn't restarted every 750ms tick.
            if state != reticle.state {
                reticle.state = state
                reticle.ring.stopAllAnimations()
                reticle.ring.transform = Transform(scale: SIMD3(repeating: 1))
                if state == 1 {
                    reticle.ring.model?.materials = [reticle.ringIntentMaterial]
                    addPulse(to: reticle.ring, scale: 1.08, duration: 0.8)
                } else {
                    reticle.ring.model?.materials = [reticle.ringDefaultMaterial]
                }
            }
            applyReticleFill(reticle, progress: progress)
        }
    }

    /// Scan-complete treatment for `id`'s reticle: swap to the complete
    /// tint with a brief 1.15x pulse, hold 1.5s, then shrink-dissolve and
    /// release the pooled entity. A no-op if `id` has no active reticle
    /// (already cleared/evicted, or never acquired one — e.g. exclusions).
    func completeScanReticle(id: Int64) {
        DispatchQueue.main.async { [self] in
            guard let idx = reticleIndexByCandidate[id],
                  reticlePool[idx].phase == .active,
                  reticlePool[idx].ring.isEnabled else { return }
            let reticle = reticlePool[idx]
            // From here until release, the slot is COMPLETING: late
            // setScanReticle ticks for this id are ignored (see the phase
            // gate there) and a second completeScanReticle is a no-op via
            // the phase guard above.
            reticle.phase = .completing
            reticle.ring.model?.materials = [reticle.ringCompleteMaterial]
            applyReticleFill(reticle, progress: 1.0)
            reticle.ring.stopAllAnimations()
            reticle.ring.transform = Transform(scale: SIMD3(repeating: 1))
            pulseOnce(reticle.ring, scale: 1.15, duration: 0.3)
            // Stale-callback guard: capture the PooledReticle OBJECT (never
            // an index into `reticlePool` — `teardown()` empties the array,
            // so a deferred closure firing after the user exits the scan
            // screen mid-completion would fatalError on the subscript; and
            // a pool rebuilt after teardown restarts generations at 0, so
            // an index+generation pair could collide with a fresh bind and
            // dissolve an innocent reticle — object identity makes both
            // impossible; the beacon closures use the optional
            // `self.beaconModel` the same way) plus its generation now, and
            // compare when each deferred closure fires. If `id` gets
            // cleared, LRU-evicted, or a new id rebinds this slot in the
            // meantime, `bindReticle`/`releaseReticle` will have bumped
            // `generation` — the mismatch means the closure must do
            // nothing. A torn-down reticle passes the guard, but it is
            // detached from the scene, so mutating it is a harmless no-op.
            let generationAtSchedule = reticle.generation
            // Pulse settle: pulseOnce SHOULD autoreverse back to 1.0x on
            // its own, but a single non-repeated autoreverse playback
            // settling at `from` can't be verified off-device — so after
            // the full up-and-back window (2 x 0.3s, + margin) explicitly
            // restore the rest transform. Harmless if the pulse already
            // settled; corrective if it parked at 1.15x. Fires well before
            // the 1.5s dissolve below, and never on a rebound slot
            // (generation guard). Touches only the captured object — no
            // `self` needed.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                guard reticle.generation == generationAtSchedule else { return }
                reticle.ring.stopAllAnimations()
                reticle.ring.transform = Transform(scale: SIMD3(repeating: 1))
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                guard let self, reticle.generation == generationAtSchedule else { return }
                self.dissolveAndReleaseReticle(reticle, id: id)
            }
        }
    }

    /// Immediate dissolve + release of `id`'s reticle — the timeout path (a
    /// SCANNING candidate that never completed). A no-op if `id` has no
    /// active reticle.
    func clearScanReticle(id: Int64) {
        DispatchQueue.main.async { [self] in
            guard let idx = reticleIndexByCandidate[id] else { return }
            dissolveAndReleaseReticle(reticlePool[idx], id: id)
        }
    }

    /// One fixed pool slot: a world anchor holding a tinted ring, with the
    /// fill bar as its child (so the ring's grow-in/pulse animations carry
    /// the bar along for free — no separate tween needed for it).
    private final class PooledReticle {
        /// Where this slot is in its occupant's lifecycle. ACTIVE = normal
        /// fill/position updates apply; COMPLETING = `completeScanReticle`
        /// ran and the 1.5s-hold/dissolve sequence owns the slot — a
        /// completing scan is DONE, so late `setScanReticle` ticks (the
        /// Kotlin tick keeps streaming them while the hold runs) must not
        /// drag the green complete bar back down or move the ring.
        enum Phase { case active, completing }

        let anchor: AnchorEntity
        let ring: ModelEntity
        let fillBar: ModelEntity
        let ringDefaultMaterial: PhysicallyBasedMaterial
        let ringCompleteMaterial: PhysicallyBasedMaterial
        /// Amber "is this litter?" styling — see `setScanReticle`'s state 1.
        let ringIntentMaterial: PhysicallyBasedMaterial
        var candidateId: Int64?
        var phase: Phase = .active
        /// Last applied state code (0 scanning / 1 awaiting-intent) —
        /// state styling is edge-triggered on changes only.
        var state: Int64 = 0
        /// Bumped every time this slot's occupant changes — bind (new id),
        /// release (clear/dissolve), or forced eviction. Deferred callbacks
        /// (the completeScanReticle hold + pulse-settle, the dissolve's
        /// trailing cleanup) capture this value at schedule time and compare
        /// it when they fire; a mismatch means the slot moved on and the
        /// closure must no-op rather than mutate whatever (or nobody) owns
        /// it now.
        var generation = 0

        init(
            anchor: AnchorEntity,
            ring: ModelEntity,
            fillBar: ModelEntity,
            ringDefaultMaterial: PhysicallyBasedMaterial,
            ringCompleteMaterial: PhysicallyBasedMaterial,
            ringIntentMaterial: PhysicallyBasedMaterial
        ) {
            self.anchor = anchor
            self.ring = ring
            self.fillBar = fillBar
            self.ringDefaultMaterial = ringDefaultMaterial
            self.ringCompleteMaterial = ringCompleteMaterial
            self.ringIntentMaterial = ringIntentMaterial
        }
    }

    /// Builds the fixed 3-slot pool on first use (arrow()/veil()/beacon()
    /// laziness pattern) — every entity is created exactly once and reused
    /// for the life of the surface via `isEnabled` toggling, never
    /// recreated per candidate.
    private func ensureReticlePool() {
        guard reticlePool.isEmpty else { return }
        for _ in 0..<Self.reticlePoolSize {
            var ringMaterial = PhysicallyBasedMaterial()
            ringMaterial.baseColor = .init(tint: Self.reticleRingTint)
            ringMaterial.roughness = 1.0
            ringMaterial.metallic = 0.0
            ringMaterial.blending = .transparent(opacity: .init(floatLiteral: 1.0))
            let ring = ModelEntity(
                mesh: .generatePlane(
                    width: Self.reticleRingDiameter,
                    depth: Self.reticleRingDiameter,
                    cornerRadius: Self.reticleRingDiameter / 2
                ),
                materials: [ringMaterial]
            )
            ring.isEnabled = false

            var completeMaterial = PhysicallyBasedMaterial()
            completeMaterial.baseColor = .init(tint: Self.reticleCompleteTint)
            completeMaterial.roughness = 1.0
            completeMaterial.metallic = 0.0
            completeMaterial.blending = .transparent(opacity: .init(floatLiteral: 1.0))

            var intentMaterial = PhysicallyBasedMaterial()
            intentMaterial.baseColor = .init(tint: Self.reticleIntentTint)
            intentMaterial.roughness = 1.0
            intentMaterial.metallic = 0.0
            intentMaterial.blending = .transparent(opacity: .init(floatLiteral: 1.0))

            var fillMaterial = PhysicallyBasedMaterial()
            fillMaterial.baseColor = .init(tint: Self.reticleFillTint)
            fillMaterial.roughness = 1.0
            fillMaterial.metallic = 0.0
            fillMaterial.blending = .transparent(opacity: .init(floatLiteral: 1.0))
            let fillBar = ModelEntity(
                mesh: .generatePlane(width: Self.reticleFillBarWidth, depth: Self.reticleFillBarDepth),
                materials: [fillMaterial]
            )
            // Sits just outside the ring's footprint (offset along Z) and a
            // hair above it (0.002m) so the two coplanar ground quads never
            // z-fight.
            fillBar.position = SIMD3(0, 0.002, Self.reticleFillBarZOffset)
            ring.addChild(fillBar)

            let anchor = AnchorEntity(world: SIMD3<Float>(0, 0, 0))
            anchor.addChild(ring)
            arView.scene.addAnchor(anchor)

            reticlePool.append(PooledReticle(
                anchor: anchor,
                ring: ring,
                fillBar: fillBar,
                ringDefaultMaterial: ringMaterial,
                ringCompleteMaterial: completeMaterial,
                ringIntentMaterial: intentMaterial
            ))
        }
    }

    /// Acquire the pool slot for `id`: an already-bound id just gets
    /// LRU-touched (`didBind` false — the slot is already showing);
    /// otherwise a free slot is taken (`didBind` true — the caller MUST run
    /// its full unconditional reset), and if the pool is full (3 active)
    /// the oldest-touched active reticle is evicted to make room.
    private func acquireReticle(for id: Int64) -> (reticle: PooledReticle, didBind: Bool) {
        ensureReticlePool()
        if let idx = reticleIndexByCandidate[id] {
            touchReticleLru(id)
            return (reticlePool[idx], false)
        }
        if let idx = reticlePool.firstIndex(where: { $0.candidateId == nil }) {
            bindReticle(at: idx, to: id)
            return (reticlePool[idx], true)
        }
        // Pool full — LRU-evict the oldest-touched active reticle. Evicted
        // INSTANTLY (no shrink tween): this same call immediately re-binds
        // and re-shows the identical slot for the new id via the caller's
        // bind-reset + `animateIn`, so an animated dissolve on the outgoing
        // entity would race the incoming grow-in on the SAME ModelEntity.
        //
        // Skip COMPLETING slots when picking the victim: a completing ring
        // stops being LRU-touched (setScanReticle early-returns), so it
        // sinks to the eviction front exactly while its 1.5s green
        // "identified" hold is playing — evicting it yanks the celebration
        // mid-animation (audit finding, build 165). The hold is bounded, so
        // preferring the next-oldest ACTIVE slot is always safe; the
        // all-completing fallback keeps the pool live regardless.
        let oldestId = reticleLru.first(where: { candidateId in
            guard let i = reticleIndexByCandidate[candidateId] else { return false }
            return reticlePool[i].phase != .completing
        }) ?? reticleLru[0]
        let idx = reticleIndexByCandidate[oldestId]!
        let evicted = reticlePool[idx]
        evicted.ring.stopAllAnimations()
        evicted.fillBar.stopAllAnimations()
        evicted.ring.isEnabled = false
        evicted.fillBar.isEnabled = false
        evicted.ring.transform = Transform(scale: SIMD3(repeating: 1))
        evicted.ring.model?.materials = [evicted.ringDefaultMaterial]
        releaseReticle(evicted, id: oldestId) // bumps generation — invalidates any pending completeScanReticle/dissolve callback for oldestId
        bindReticle(at: idx, to: id)
        return (reticlePool[idx], true)
    }

    private func bindReticle(at idx: Int, to id: Int64) {
        reticlePool[idx].candidateId = id
        reticlePool[idx].phase = .active
        reticleIndexByCandidate[id] = idx
        reticleLru.append(id)
        reticlePool[idx].generation += 1
    }

    private func touchReticleLru(_ id: Int64) {
        if let pos = reticleLru.firstIndex(of: id) {
            reticleLru.remove(at: pos)
        }
        reticleLru.append(id)
    }

    /// Vacates `reticle` and bumps its generation. The id-keyed maps are
    /// only touched when they still point at THIS object — after a
    /// teardown (or teardown + pool rebuild) a stale deferred callback may
    /// hand in a detached reticle whose id has since been bound to a fresh
    /// slot, and blindly removing by id would release the new occupant's
    /// binding out from under it.
    private func releaseReticle(_ reticle: PooledReticle, id: Int64) {
        reticle.candidateId = nil
        reticle.phase = .active
        reticle.generation += 1
        if let idx = reticleIndexByCandidate[id], reticlePool.indices.contains(idx), reticlePool[idx] === reticle {
            reticleIndexByCandidate.removeValue(forKey: id)
            if let pos = reticleLru.firstIndex(of: id) { reticleLru.remove(at: pos) }
        }
    }

    /// Sets the fill bar's visible width to `progress` while keeping its
    /// LEFT edge fixed: the bar's own mesh is generated once at full width
    /// (mesh regen is the expensive part elsewhere in this file, so this
    /// only ever touches `transform`), scaled by `progress` on X, and
    /// shifted left by the shrunk half-width so growth reads left-to-right
    /// instead of from the center.
    private func applyReticleFill(_ reticle: PooledReticle, progress: Float) {
        let clamped = min(max(progress, 0), 1)
        var t = reticle.fillBar.transform
        // Never fully zero — a literal 0 scale would leave the bar mesh
        // permanently degenerate-invisible instead of just "empty-looking".
        t.scale.x = max(clamped, 0.02)
        t.translation.x = -(1 - clamped) * Self.reticleFillBarHalfWidth
        reticle.fillBar.transform = t
    }

    /// Mirrors `dissolveGuidanceBeacon`'s shrink-out, but the reticle is
    /// released back to the pool afterward instead of just re-hidden (the
    /// beacon has exactly one instance; reticles are shared across
    /// candidates). The id is freed from the pool SYNCHRONOUSLY so a
    /// same-id `setScanReticle` call arriving right after never mistakes
    /// this slot for "still showing" and skips its reset.
    private func dissolveAndReleaseReticle(_ reticle: PooledReticle, id: Int64) {
        let wasVisible = reticle.ring.isEnabled
        releaseReticle(reticle, id: id)
        guard wasVisible else { return }
        // Kill any in-flight tween (the completion pulse, a previous
        // dissolve) before starting the shrink so nothing competes for
        // ring.transform, and restore to the CANONICAL rest transform
        // (scale 1) rather than a snapshot that might have been taken
        // mid-pulse at 1.15x.
        reticle.ring.stopAllAnimations()
        let restore = Transform(scale: SIMD3(repeating: 1))
        var end = restore
        end.scale = SIMD3(repeating: 0.01)
        reticle.ring.move(to: end, relativeTo: reticle.anchor, duration: 0.3, timingFunction: .easeIn)
        // Same stale-callback guard as completeScanReticle, with the same
        // object-not-index capture (teardown empties `reticlePool`, so a
        // pool subscript in a deferred closure is a post-teardown crash):
        // `releaseReticle` just bumped `generation`, so a re-`setScanReticle`
        // or re-eviction of this slot before the trailing cleanup fires
        // will bump it again and this closure will correctly skip. Touches
        // only the captured object — no `self` needed; on a torn-down
        // (scene-detached) reticle the mutations are harmless no-ops.
        let generationAtDissolve = reticle.generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            guard reticle.generation == generationAtDissolve else { return }
            reticle.ring.isEnabled = false
            reticle.fillBar.isEnabled = false
            reticle.ring.transform = restore
            reticle.ring.model?.materials = [reticle.ringDefaultMaterial]
        }
    }

    /// One-shot up-then-back pulse — unlike `addPulse`'s endless
    /// `.repeat()`, this plays the autoreverse exactly once. Whether a
    /// single non-repeated autoreverse playback settles back at `from`
    /// can't be verified off-device, so callers must not RELY on it:
    /// `completeScanReticle` schedules a generation-guarded explicit
    /// restore of the rest transform after the pulse window as a backstop.
    /// Used for the scan-complete "brief 1.15x pulse" (spec Task 5).
    private func pulseOnce(_ entity: Entity, scale: Float, duration: TimeInterval) {
        let start = entity.transform
        var end = start
        end.scale = start.scale * scale
        let animation = FromToByAnimation<Transform>(
            name: "gcc-reticle-complete-pulse",
            from: start,
            to: end,
            duration: duration,
            timing: .easeOut,
            bindTarget: .transform,
            repeatMode: .autoReverse
        )
        if let resource = try? AnimationResource.generate(with: animation) {
            entity.playAnimation(resource, transitionDuration: 0, startsPaused: false)
        }
    }

    /// Lazy singleton (arrow()/veil() pattern): one flat ring on a
    /// free-standing world anchor, gently pulsing. Non-emissive on purpose —
    /// it should read as sitting IN the scene (and LiDAR mesh occlusion
    /// hides it behind real objects), unlike the deliberately-neon grid.
    private func beacon() -> ModelEntity {
        if let beaconModel { return beaconModel }
        var material = PhysicallyBasedMaterial()
        if let beaconTexture {
            material.baseColor = .init(tint: Self.beaconTint.withAlphaComponent(1), texture: .init(beaconTexture))
        } else {
            material.baseColor = .init(tint: Self.beaconTint.withAlphaComponent(1))
        }
        material.roughness = 1.0
        material.metallic = 0.0
        // Same per-texel opacity fix as the grid material: the scalar form
        // rendered the ring texture's clear plane as a dark square.
        var beaconAlpha: CGFloat = 1
        Self.beaconTint.getRed(nil, green: nil, blue: nil, alpha: &beaconAlpha)
        if beaconTexture != nil, let mask = beaconOpacityTexture {
            material.blending = .transparent(
                opacity: .init(scale: Float(beaconAlpha), texture: .init(mask))
            )
        } else {
            material.blending = .transparent(opacity: .init(floatLiteral: Float(beaconAlpha)))
        }
        let model = ModelEntity(
            mesh: .generatePlane(width: 0.9, depth: 0.9, cornerRadius: 0.45),
            materials: [material]
        )
        model.isEnabled = false
        let anchor = AnchorEntity(world: SIMD3<Float>(0, 0, 0))
        anchor.addChild(model)
        arView.scene.addAnchor(anchor)
        addPulse(to: model, scale: 1.18, duration: 1.1)
        beaconAnchor = anchor
        beaconModel = model
        return model
    }

    func setBoundaryVeilVisible(visible: Bool) {
        DispatchQueue.main.async { [self] in
            veil().isEnabled = visible
        }
    }

    func updateBoundaryWalls(flatSegments: [KotlinDouble]) {
        DispatchQueue.main.async { [self] in
            for wall in wallEntities { wall.removeFromParent() }
            wallEntities.removeAll()
            let groundY = (floorWorldY ?? cameraFallbackGroundY())
            var index = 0
            // Records of [midX, midZ, lengthMeters, yawRadians, isOuter].
            while index + 4 < flatSegments.count {
                let midX = Float(truncating: flatSegments[index])
                let midZ = Float(truncating: flatSegments[index + 1])
                let length = Float(truncating: flatSegments[index + 2])
                let yaw = Float(truncating: flatSegments[index + 3])
                let isOuter = Double(truncating: flatSegments[index + 4]) >= 0.5
                index += 5
                let tint = isOuter
                    ? UIColor(red: 0.13, green: 0.77, blue: 0.37, alpha: 0.3)
                    : UIColor(red: 1.0, green: 0.72, blue: 0.0, alpha: 0.3)
                var material = PhysicallyBasedMaterial()
                material.baseColor = .init(tint: tint)
                material.emissiveColor = .init(color: tint)
                material.emissiveIntensity = 2.0
                material.roughness = 1.0
                material.metallic = 0.0
                material.blending = .transparent(opacity: .init(floatLiteral: 1.0))
                let anchor = AnchorEntity(world: SIMD3(midX, groundY + 0.45, midZ))
                let wall = ModelEntity(
                    mesh: .generatePlane(width: length, height: 0.9),
                    materials: [material]
                )
                wall.orientation = simd_quatf(angle: yaw, axis: SIMD3(0, 1, 0))
                anchor.addChild(wall)
                arView.scene.addAnchor(anchor)
                wallEntities.append(anchor)
            }
        }
    }

    // MARK: - Animation helpers (iOS 17-safe: transform-bound animations —
    // OpacityComponent and its bind targets are iOS 18+)

    /// Endless gentle scale pulse (autoreversed) — "live data", not paint.
    private func addPulse(to entity: Entity, scale: Float, duration: TimeInterval) {
        let start = entity.transform
        var end = start
        end.scale = start.scale * scale
        let animation = FromToByAnimation<Transform>(
            name: "gcc-pulse",
            from: start,
            to: end,
            duration: duration,
            timing: .easeInOut,
            bindTarget: .transform,
            repeatMode: .autoReverse
        )
        if let resource = try? AnimationResource.generate(with: animation) {
            entity.playAnimation(resource.repeat(), transitionDuration: 0, startsPaused: false)
        }
    }

    /// Grow-in on spawn: entities pop from 1% scale to their real size.
    private func animateIn(_ entity: Entity, duration: TimeInterval = 0.35) {
        let target = entity.transform
        var start = target
        start.scale = SIMD3(repeating: 0.01)
        entity.transform = start
        entity.move(to: target, relativeTo: entity.parent, duration: duration, timingFunction: .easeOut)
    }

    /// Shrink-out then remove: reveals read as the world being "cleared".
    private func animateOutAndRemove(_ anchor: AnchorEntity, duration: TimeInterval = 0.3) {
        if let model = anchor.children.first {
            var end = model.transform
            end.scale = SIMD3(repeating: 0.01)
            model.move(to: end, relativeTo: anchor, duration: duration, timingFunction: .easeIn)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + duration + 0.05) {
            anchor.removeFromParent()
        }
    }

    private func veil() -> ModelEntity {
        if let veilEntity { return veilEntity }
        let entity = ModelEntity(
            mesh: .generatePlane(width: 2.0, height: 2.0),
            materials: [UnlitMaterial(color: UIColor.red.withAlphaComponent(0.35))]
        )
        entity.position = SIMD3(0, 0, -0.8)
        entity.isEnabled = false
        cameraAnchor.addChild(entity)
        veilEntity = entity
        return entity
    }

    func emitLockEffect(x: Double, y: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            let anchor = AnchorEntity(world: SIMD3(Float(x), Float(y), Float(z)))
            let pulse = ModelEntity(
                mesh: .generateSphere(radius: 0.06),
                materials: [UnlitMaterial(color: UIColor.green.withAlphaComponent(0.8))]
            )
            anchor.addChild(pulse)
            arView.scene.addAnchor(anchor)
            // Grow-and-vanish pulse; works on every OS version.
            pulse.move(
                to: Transform(scale: SIMD3(repeating: 2.4), rotation: simd_quatf(), translation: .zero),
                relativeTo: pulse,
                duration: 0.6,
                timingFunction: .easeOut
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                anchor.removeFromParent()
            }
        }
    }

    // MARK: - Session capture (Pillar 1 — see ArSessionRecorder)

    func startCapture(sessionId: String, outputDirPath: String) {
        if recorder == nil { recorder = ArSessionRecorder() }
        recorder?.start(sessionId: sessionId, outputDirPath: outputDirPath)
    }

    func drainFinishedCaptureChunks() -> [String] {
        recorder?.drainFinishedChunks() ?? []
    }

    func drainCaptureDiagnostics() -> [String] {
        recorder?.drainDiagnostics() ?? []
    }

    func discardCurrentCaptureChunk() {
        recorder?.discardCurrentChunk()
    }

    func stopCaptureAndDrain() -> [String] {
        recorder?.stopAndDrain() ?? []
    }

    func setCaptureEnabled(enabled: Bool) {
        recorder?.setEnabled(enabled)
    }

    /// Current video tier (see `ArSessionRecorder.VideoTier`) as of the most
    /// recent append, or -1 when no recorder exists yet.
    func captureVideoTierRaw() -> Int32 { recorder?.currentTierRaw() ?? -1 }

    /// JSON mirror of the manifest's CaptureHealth block (Task 5) — call
    /// after `stopCaptureAndDrain()` so the recorder's serial queue is idle.
    /// Nil when no recorder exists yet (capture never started).
    func captureHealthJson() -> String? { recorder?.healthJson() }

    // MARK: - ARSessionDelegate

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        frameLock.lock()
        lastFrame = frame
        frameLock.unlock()
        recorder?.append(frame: frame)
    }

    func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
        for case let plane as ARPlaneAnchor in anchors { upsertPlane(plane) }
    }

    func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
        for case let plane as ARPlaneAnchor in anchors { upsertPlane(plane) }
    }

    func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
        // The grid is a single monotonic-union entity (spec system 2) that
        // does not track individual contributing anchors — ARKit merging or
        // discarding a plane underneath it must never shrink or flicker the
        // grid. Nothing to do here; the union bounds are left as they are.
    }

    // MARK: - Ground grid

    private final class PlaneGridEntity {
        let anchor: AnchorEntity
        let model: ModelEntity
        /// Snapped size the current mesh was generated with (mesh regen skip).
        var lastAppliedSize: SIMD2<Float> = .zero
        init(anchor: AnchorEntity, model: ModelEntity) {
            self.anchor = anchor
            self.model = model
        }
    }

    private func upsertPlane(_ plane: ARPlaneAnchor) {
        let extent = SIMD2(plane.planeExtent.width, plane.planeExtent.height)
        guard extent.x >= 0.4, extent.y >= 0.4 else { return }
        // Unclassified horizontal planes count as ground (matches the Kotlin
        // KVO path). Recognized furniture (table/seat/…) no longer gets a
        // grid at all — a second "faint grid" system layered over furniture
        // was itself a duplicate-grid source (spec system 2: ONE ground
        // grid), so only floor-classified planes feed the grid now.
        let isFloor: Bool
        switch plane.classification {
        case .floor: isFloor = true
        case .none:
            // Unclassified horizontal planes count as ground — outdoors ARKit
            // almost never says `.floor` — but only within a height band of
            // the established floor. Without the band, car roofs, truck beds
            // and planter tops folded into the never-shrinking grid union
            // (one contributor to the oversized ground quad behind the
            // 2026-07-25 grey-cover report).
            let y = plane.transform.columns.3.y + plane.center.y
            frameLock.lock()
            let knownFloor = floorWorldY
            frameLock.unlock()
            // ONE-SIDED band: reject only planes ABOVE the floor (car roofs,
            // truck beds). Planes BELOW must stay accepted — if the first
            // plane ARKit gave us was itself elevated (porch, hood), the real
            // ground arriving later sits far below it, and a symmetric band
            // would reject the actual ground forever (floorWorldY can only
            // drop via accepted planes — bootstrap deadlock).
            isFloor = knownFloor == nil || y - knownFloor! <= 0.35
        default: isFloor = false
        }
        // Floor height feeds walls/posts in EVERY mode — track it
        // even when the grid itself is hidden (route/spot), otherwise those
        // entities sit at the camera-offset estimate forever.
        if isFloor {
            let y = plane.transform.columns.3.y + plane.center.y
            // frameLock: drainGroundCandidates() reads floorWorldY off-main.
            frameLock.lock()
            if floorWorldY == nil || y < floorWorldY! { floorWorldY = y }
            frameLock.unlock()
        }
        guard groundGridVisible, isFloor else { return }

        // World-aligned grid: the anchor's yaw is arbitrary (whatever ARKit
        // detected first), but AR world axes are compass-true. Rendering the
        // grid world-axis aligned — sized to the world-space AABB of the
        // rotated extent — keeps grid lines north/south–east/west so they
        // register with the north-up minimap lattice.
        let m = plane.transform
        let yaw = atan2(-m.columns.0.z, m.columns.0.x)
        let worldW = abs(extent.x * cos(yaw)) + abs(extent.y * sin(yaw))
        let worldD = abs(extent.x * sin(yaw)) + abs(extent.y * cos(yaw))
        let c = plane.center
        let centerWorld = SIMD3(
            m.columns.0.x * c.x + m.columns.1.x * c.y + m.columns.2.x * c.z + m.columns.3.x,
            m.columns.0.y * c.x + m.columns.1.y * c.y + m.columns.2.y * c.z + m.columns.3.y,
            m.columns.0.z * c.x + m.columns.1.z * c.y + m.columns.2.z * c.z + m.columns.3.z
        )
        let planeMinX = centerWorld.x - worldW / 2, planeMaxX = centerWorld.x + worldW / 2
        let planeMinZ = centerWorld.z - worldD / 2, planeMaxZ = centerWorld.z + worldD / 2

        // Fold this plane's AABB into the ONE grid's monotonic union — the
        // union only ever grows, matching "stability beats accuracy for the
        // grid": ARKit merging/re-splitting/removing a contributing anchor
        // must never make the single grid entity flicker, shrink or spawn a
        // sibling.
        var grew = false
        // frameLock: drainGroundCandidates() reads these bounds off-main —
        // held only for the four field mutations, never the entity work below.
        frameLock.lock()
        if floorMinX == nil || planeMinX < floorMinX! { floorMinX = planeMinX; grew = true }
        if floorMaxX == nil || planeMaxX > floorMaxX! { floorMaxX = planeMaxX; grew = true }
        if floorMinZ == nil || planeMinZ < floorMinZ! { floorMinZ = planeMinZ; grew = true }
        if floorMaxZ == nil || planeMaxZ > floorMaxZ! { floorMaxZ = planeMaxZ; grew = true }
        frameLock.unlock()

        let entry: PlaneGridEntity
        if let existing = floorGrid {
            guard grew else { return }
            entry = existing
        } else {
            // AnchorEntity(world:) has identity orientation = world axes.
            let anchor = AnchorEntity(world: centerWorld)
            let model = ModelEntity(
                mesh: MeshResource.generatePlane(width: 1, depth: 1),
                materials: []
            )
            anchor.addChild(model)
            arView.scene.addAnchor(anchor)
            entry = PlaneGridEntity(anchor: anchor, model: model)
            floorGrid = entry
        }
        applyPlaneGeometry(entry)
        refreshGroundState()
    }

    /// (Re)build the ONE grid's render geometry from the monotonic union
    /// AABB: with a lattice phase known, the bounds snap OUTWARD to
    /// multiples of `gridMajorTileMeters` (one texture repeat = 3m) from the
    /// phase origin, so the grid edges — and every minor/major texture line
    /// between them — land exactly on the geo-anchored 3m cell lattice the
    /// minimap draws. Runs on union growth AND once when the phase origin
    /// locks (geo-ref first pairing — it never moves again, see
    /// `setGridPhaseOrigin`). Mesh regeneration is the expensive part —
    /// skipped while the snapped size is unchanged.
    private func applyPlaneGeometry(_ entry: PlaneGridEntity) {
        guard let rawMinX = floorMinX, let rawMaxX = floorMaxX,
              let rawMinZ = floorMinZ, let rawMaxZ = floorMaxZ else { return }
        let tile = Self.gridMajorTileMeters
        var minX = rawMinX, maxX = rawMaxX
        var minZ = rawMinZ, maxZ = rawMaxZ
        if let phase = gridPhaseOrigin {
            (minX, maxX) = Self.snapToLattice(minX, maxX, phase: phase.x, tile: tile)
            (minZ, maxZ) = Self.snapToLattice(minZ, maxZ, phase: phase.y, tile: tile)
        }
        let size = SIMD2(maxX - minX, maxZ - minZ)
        let y = floorWorldY ?? cameraFallbackGroundY()
        let snappedCenter = SIMD3((minX + maxX) / 2, y, (minZ + maxZ) / 2)
        entry.anchor.position = snappedCenter
        if entry.lastAppliedSize != size {
            entry.model.model?.mesh = MeshResource.generatePlane(width: size.x, depth: size.y)
            entry.lastAppliedSize = size
        }
        entry.model.model?.materials = [planeMaterial(size: size, centerWorld: snappedCenter)]
    }

    /// Snap a 1-D extent outward to the lattice `phase + k * tile` — both
    /// returned endpoints lie on lattice lines and cover the input range.
    /// Mirrors Kotlin's `snapRangeToLattice` (ArGeoMath.kt) exactly.
    private static func snapToLattice(_ lo: Float, _ hi: Float, phase: Float, tile: Float) -> (Float, Float) {
        let snappedLo = phase + tile * floor((lo - phase) / tile)
        var snappedHi = phase + tile * ceil((hi - phase) / tile)
        if snappedHi <= snappedLo { snappedHi = snappedLo + tile }
        return (snappedLo, snappedHi)
    }

    /// Monotonic: once the grid is live it stays live for the rest of the
    /// session (matches the union's own never-shrinks contract) — a
    /// transient gap in floor recognition must not flicker "ground detected"
    /// off and on. Reset only by `setGroundGridVisible(false)` (mode switch).
    private func refreshGroundState() {
        if floorGrid != nil { groundLive = true }
    }

    private func planeMaterial(
        size: SIMD2<Float>,
        centerWorld: SIMD3<Float>
    ) -> PhysicallyBasedMaterial {
        let tint = UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.55)
        var material = gridMaterial(floorGridTexture, tint: tint, uvScale: 1)
        // One texture repeat = one MAJOR tile (gridMajorTileMeters, 3m) — the
        // texture packs the 3×3 minor cells inside it, so dividing by the
        // minor interval would draw minor lines every 0.33m. The offset
        // phases the repeat so grid LINES land on multiples of the tile from
        // the lattice phase origin (the geo-anchored CoverageTracker lattice
        // once the geo-ref pairs; the AR world origin before) — every plane
        // shares one seamless lattice instead of each starting its grid at
        // its corner. With bounds snapped by applyPlaneGeometry the offset
        // is ~0; it mops up float residue and covers the unsnapped pre-phase
        // case.
        material.textureCoordinateTransform.scale = SIMD2(
            max(size.x / Self.gridMajorTileMeters, 1),
            max(size.y / Self.gridMajorTileMeters, 1)
        )
        let phaseX = gridPhaseOrigin?.x ?? 0
        let phaseZ = gridPhaseOrigin?.y ?? 0
        let originX = (centerWorld.x - size.x / 2 - phaseX) / Self.gridMajorTileMeters
        let originZ = (centerWorld.z - size.y / 2 - phaseZ) / Self.gridMajorTileMeters
        material.textureCoordinateTransform.offset = SIMD2(
            originX - floor(originX),
            originZ - floor(originZ)
        )
        return material
    }

    // PhysicallyBasedMaterial rather than UnlitMaterial: only PBM has
    // textureCoordinateTransform before iOS 18, and tiling is what keeps
    // grid squares a constant gridTileMeters as planes grow.
    private func gridMaterial(_ texture: TextureResource?, tint: UIColor, uvScale: Float) -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        // Tint alpha moves to the opacity SCALE below — baseColor tint alpha
        // does nothing useful under `.transparent(opacity:)` and the old
        // scalar-opacity form rendered the texture's transparent gaps as
        // premultiplied black (the grey-cover field bug).
        var tintAlpha: CGFloat = 1
        tint.getRed(nil, green: nil, blue: nil, alpha: &tintAlpha)
        let opaqueTint = tint.withAlphaComponent(1)
        if let texture {
            material.baseColor = .init(tint: opaqueTint, texture: .init(texture))
            material.emissiveColor = .init(color: opaqueTint, texture: .init(texture))
        } else {
            material.baseColor = .init(tint: opaqueTint)
            material.emissiveColor = .init(color: opaqueTint)
        }
        // Neon restored on Blake's word (TestFlight 145: the non-emissive grid
        // read as a shadow on the ground — invisible in daylight). The old
        // glow-through-fences complaint is handled by scene-mesh occlusion
        // (sceneUnderstanding .occlusion in init) on LiDAR devices, not by
        // dimming the grid.
        material.emissiveIntensity = 2.0
        material.roughness = 1.0
        material.metallic = 0.0
        if texture != nil, let mask = floorGridOpacityTexture {
            material.blending = .transparent(
                opacity: .init(scale: Float(tintAlpha), texture: .init(mask))
            )
        } else {
            material.blending = .transparent(opacity: .init(floatLiteral: Float(tintAlpha)))
        }
        if uvScale != 1 {
            material.textureCoordinateTransform.scale = SIMD2(repeating: uvScale)
        }
        return material
    }

    private func cameraFallbackGroundY() -> Float {
        frameLock.lock()
        defer { frameLock.unlock() }
        let cameraY = lastFrame?.camera.transform.columns.3.y ?? 0
        return cameraY - 1.4
    }

    /// Transparent 3×3-cell tile: thin lines on the interior cell boundaries
    /// (the 1m minor interval, `gridTileMeters`, alpha 0.5) and a heavier
    /// line on the texture's own outer boundary (alpha 0.9). One repeat is
    /// scaled to span `gridMajorTileMeters` (see `planeMaterial`), so the
    /// heavy edge line lands on every repeat boundary — the 3m major
    /// interval. Diagonals add an X per cell for the denser "vector scan"
    /// look. Mirrors the Kotlin SceneKit texture (ArEnvScan.kt's
    /// makeGridTexture) exactly — keep them visually identical.
    private static func makeGridTexture(line: UIColor, lineWidth: CGFloat, diagonals: Bool = false) -> TextureResource? {
        let side: CGFloat = 129
        let third = side / 3
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { ctx in
            // Minor grid: one line per interior cell boundary.
            let minorPath = UIBezierPath()
            for i in 1...2 {
                let pos = third * CGFloat(i)
                minorPath.move(to: CGPoint(x: 0, y: pos))
                minorPath.addLine(to: CGPoint(x: side, y: pos))
                minorPath.move(to: CGPoint(x: pos, y: 0))
                minorPath.addLine(to: CGPoint(x: pos, y: side))
            }
            if diagonals {
                for row in 0...2 {
                    for col in 0...2 {
                        let x0 = third * CGFloat(col)
                        let y0 = third * CGFloat(row)
                        minorPath.move(to: CGPoint(x: x0, y: y0))
                        minorPath.addLine(to: CGPoint(x: x0 + third, y: y0 + third))
                        minorPath.move(to: CGPoint(x: x0 + third, y: y0))
                        minorPath.addLine(to: CGPoint(x: x0, y: y0 + third))
                    }
                }
            }
            minorPath.lineWidth = lineWidth
            line.withAlphaComponent(0.5).setStroke()
            minorPath.stroke()

            // Major grid: only the texture's outer boundary — one heavy line
            // per repeat, i.e. every gridMajorTileMeters in the world.
            let majorPath = UIBezierPath()
            majorPath.move(to: CGPoint(x: 0, y: 0.5))
            majorPath.addLine(to: CGPoint(x: side, y: 0.5))
            majorPath.move(to: CGPoint(x: 0.5, y: 0))
            majorPath.addLine(to: CGPoint(x: 0.5, y: side))
            majorPath.lineWidth = lineWidth * 1.5
            line.withAlphaComponent(0.9).setStroke()
            majorPath.stroke()
            _ = ctx // renderer context retained for the draw
        }
        guard let cg = image.cgImage else { return nil }
        return try? TextureResource.generate(from: cg, options: .init(semantic: .color))
    }

    /// Opacity companion to `makeGridTexture`: identical line geometry drawn
    /// WHITE at each line's alpha on a BLACK background. Sampled as a single
    /// channel by `.transparent(opacity:)` — black gaps = fully invisible,
    /// line brightness = line opacity. `.raw` semantic so the values aren't
    /// run through the sRGB curve (0.5 must sample as 0.5).
    private static func makeGridOpacityMask(lineWidth: CGFloat, diagonals: Bool = false) -> TextureResource? {
        let side: CGFloat = 129
        let third = side / 3
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            let minorPath = UIBezierPath()
            for i in 1...2 {
                let pos = third * CGFloat(i)
                minorPath.move(to: CGPoint(x: 0, y: pos))
                minorPath.addLine(to: CGPoint(x: side, y: pos))
                minorPath.move(to: CGPoint(x: pos, y: 0))
                minorPath.addLine(to: CGPoint(x: pos, y: side))
            }
            if diagonals {
                for row in 0...2 {
                    for col in 0...2 {
                        let x0 = third * CGFloat(col)
                        let y0 = third * CGFloat(row)
                        minorPath.move(to: CGPoint(x: x0, y: y0))
                        minorPath.addLine(to: CGPoint(x: x0 + third, y: y0 + third))
                        minorPath.move(to: CGPoint(x: x0 + third, y: y0))
                        minorPath.addLine(to: CGPoint(x: x0, y: y0 + third))
                    }
                }
            }
            minorPath.lineWidth = lineWidth
            UIColor(white: 0.5, alpha: 1).setStroke()
            minorPath.stroke()
            let majorPath = UIBezierPath()
            majorPath.move(to: CGPoint(x: 0, y: 0.5))
            majorPath.addLine(to: CGPoint(x: side, y: 0.5))
            majorPath.move(to: CGPoint(x: 0.5, y: 0))
            majorPath.addLine(to: CGPoint(x: 0.5, y: side))
            majorPath.lineWidth = lineWidth * 1.5
            UIColor(white: 0.9, alpha: 1).setStroke()
            majorPath.stroke()
        }
        guard let cg = image.cgImage else { return nil }
        return try? TextureResource.generate(from: cg, options: .init(semantic: .raw))
    }

    /// Opacity companion to `makeBeaconRingTexture` — same fix as the grid
    /// mask (the beacon shared the scalar-opacity bug, rendering its clear
    /// plane as a dark square).
    private static func makeBeaconOpacityMask() -> TextureResource? {
        let side: CGFloat = 256
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            let ring = UIBezierPath(ovalIn: CGRect(x: 28, y: 28, width: side - 56, height: side - 56))
            ring.lineWidth = 26
            UIColor.white.setStroke()
            ring.stroke()
            let core = UIBezierPath(ovalIn: CGRect(x: side / 2 - 26, y: side / 2 - 26, width: 52, height: 52))
            UIColor(white: 0.35, alpha: 1).setFill()
            core.fill()
        }
        guard let cg = image.cgImage else { return nil }
        return try? TextureResource.generate(from: cg, options: .init(semantic: .raw))
    }

    /// Frontier-beacon ring: a white annulus + soft center dot on a clear
    /// ground plane; the material tints it beacon-blue. White-on-clear so the
    /// tint fully owns the color (same texture×tint pattern as the grid).
    private static func makeBeaconRingTexture() -> TextureResource? {
        let side: CGFloat = 256
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { _ in
            let ring = UIBezierPath(ovalIn: CGRect(x: 28, y: 28, width: side - 56, height: side - 56))
            ring.lineWidth = 26
            UIColor.white.setStroke()
            ring.stroke()
            let core = UIBezierPath(ovalIn: CGRect(x: side / 2 - 26, y: side / 2 - 26, width: 52, height: 52))
            UIColor.white.withAlphaComponent(0.35).setFill()
            core.fill()
        }
        guard let cg = image.cgImage else { return nil }
        return try? TextureResource.generate(from: cg, options: .init(semantic: .color))
    }
}

enum GccArNative {
    /// Called once from `AppDelegate.didFinishLaunching`. Registration is the
    /// opt-in: if this never runs (or throws), Kotlin renders with SceneKit.
    static func register() {
        ArNativeRegistry.shared.surfaceFactory = { RealityKitArSurface() }
    }
}
