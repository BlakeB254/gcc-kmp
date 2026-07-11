import Combine
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
    /// accuracy for the grid": content (fog/vector cells/guidance) keeps
    /// re-anchoring on every re-pair, the grid itself never does.
    private var gridPhaseOrigin: SIMD2<Float>?
    private var fogCellEntities: [String: AnchorEntity] = [:]
    private var sectorPostEntities: [String: AnchorEntity] = [:]
    private var wallEntities: [AnchorEntity] = []
    private var floorWorldY: Float?
    private var groundLive = false
    private var groundGridVisible = true

    // Route-mode extras: camera-attached guidance arrow + warning veil.
    // Mirrors the Kotlin SceneKit path's MAX_FOG_NODES (ArEnvScan.kt) — one
    // shared cap (was 600 vs 400, audit finding) even though Kotlin already
    // truncates the shipped list to this count before it reaches Swift; kept
    // here too as the defensive receiving-end limit.
    private static let maxFogCells = 400
    private let cameraAnchor = AnchorEntity(.camera)
    private var veilEntity: ModelEntity?
    /// Recorded but no longer aimed at by anything in this file — the camera-
    /// attached guidance arrow was deleted (global constraint 8). Kept so the
    /// surface still knows the frontier target if a future feature needs it;
    /// the minimap cone and text prompt are the guidance UI now.
    private var guidanceTarget: SIMD3<Float>?
    private var coachingOverlay: ARCoachingOverlayView?

    private lazy var floorGridTexture: TextureResource? = Self.makeGridTexture(
        line: UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.9), lineWidth: 3
    )

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
    /// vector/fog cells, sector posts, walls, veil) from the RealityKit scene
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

        floorGrid?.anchor.removeFromParent()
        floorGrid = nil
        floorMinX = nil
        floorMaxX = nil
        floorMinZ = nil
        floorMaxZ = nil

        for (_, anchor) in fogCellEntities { anchor.removeFromParent() }
        fogCellEntities.removeAll()

        for (_, anchor) in sectorPostEntities { anchor.removeFromParent() }
        sectorPostEntities.removeAll()

        for wall in wallEntities { wall.removeFromParent() }
        wallEntities.removeAll()

        veilEntity?.removeFromParent()
        veilEntity = nil
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

    func raycast(imageX: Double, imageY: Double) -> [KotlinDouble]? {
        frameLock.lock()
        let frame = lastFrame
        frameLock.unlock()
        guard let frame else { return nil }
        let query = frame.raycastQuery(
            from: CGPoint(x: imageX, y: imageY),
            allowing: .estimatedPlane,
            alignment: .any
        )
        guard let hit = arView.session.raycast(query).first else { return nil }
        let t = hit.worldTransform.columns.3
        return [KotlinDouble(double: Double(t.x)), KotlinDouble(double: Double(t.y)), KotlinDouble(double: Double(t.z))]
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

    // WS5: the neon emissive coverage quads (green-rectangle bleed in the
    // camera view — field report) are removed. Coverage feedback lives on the
    // minimap now; this is a no-op kept for the ArNativeSurface protocol. The
    // matte ground grid is a separate path (setGridPhaseOrigin) and stays crisp.
    func updateVectorCells(flatXZ: [KotlinDouble], cellSizeMeters: Double) {}

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
            // in route/spot mode (grid hidden, fog/posts own the ground) an
            // auto-activating "find a surface" overlay is intrusive — and the
            // SceneKit fallbacks never had one.
            coachingOverlay?.activatesAutomatically = visible
            if !visible {
                coachingOverlay?.setActive(false, animated: false)
                floorGrid?.anchor.removeFromParent()
                floorGrid = nil
                floorMinX = nil
                floorMaxX = nil
                floorMinZ = nil
                floorMaxZ = nil
                groundLive = false
            }
        }
    }

    /// ONE-SHOT LOCK (spec system 2): the first non-nil phase origin is
    /// TERMINAL. Geo-ref re-pairs arrive on every GPS fix — re-phasing the
    /// grid on each one (the pre-fix behavior) is exactly what produced the
    /// reported flashing/overlapping grid spam, since every re-phase
    /// regenerates the mesh and repositions the anchor. "Stability beats
    /// accuracy for the grid": fog/vector cells/guidance keep re-anchoring on
    /// every re-pair; the grid locks to wherever it first landed and stays
    /// there for the rest of the session.
    func setGridPhaseOrigin(x: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            if gridPhaseOrigin != nil { return }
            gridPhaseOrigin = SIMD2(Float(x), Float(z))
            if let grid = floorGrid { applyPlaneGeometry(grid) }
        }
    }

    func updateFogCells(flatXZ: [KotlinDouble], cellSizeMeters: Double) {
        DispatchQueue.main.async { [self] in
            let groundY = (floorWorldY ?? cameraFallbackGroundY())
            var wanted = Set<String>()
            var index = 0
            while index + 1 < flatXZ.count {
                let x = Float(truncating: flatXZ[index])
                let z = Float(truncating: flatXZ[index + 1])
                index += 2
                let key = "\(Int((x * 10).rounded())):\(Int((z * 10).rounded()))"
                wanted.insert(key)
                if let existing = fogCellEntities[key] {
                    if abs(existing.position.y - groundY) > 0.08 {
                        existing.position.y = groundY
                    }
                    continue
                }
                if fogCellEntities.count >= Self.maxFogCells { continue }
                let anchor = AnchorEntity(world: SIMD3(x, groundY, z))
                var material = PhysicallyBasedMaterial()
                material.baseColor = .init(tint: UIColor(white: 0.5, alpha: 0.45))
                material.roughness = 1.0
                material.metallic = 0.0
                material.blending = .transparent(opacity: .init(floatLiteral: 1.0))
                let mesh = MeshResource.generatePlane(
                    width: Float(cellSizeMeters), depth: Float(cellSizeMeters)
                )
                anchor.addChild(ModelEntity(mesh: mesh, materials: [material]))
                arView.scene.addAnchor(anchor)
                fogCellEntities[key] = anchor
            }
            // Reveal-by-omission: scanned cells shrink away — walking a cell
            // visibly "clears" the world instead of blinking out.
            for (key, anchor) in fogCellEntities where !wanted.contains(key) {
                fogCellEntities.removeValue(forKey: key)
                animateOutAndRemove(anchor)
            }
        }
    }

    func updateSectorPosts(flatXZ: [KotlinDouble]) {
        DispatchQueue.main.async { [self] in
            let groundY = (floorWorldY ?? cameraFallbackGroundY())
            var wanted = Set<String>()
            var index = 0
            while index + 1 < flatXZ.count {
                let x = Float(truncating: flatXZ[index])
                let z = Float(truncating: flatXZ[index + 1])
                index += 2
                let key = "\(Int((x * 10).rounded())):\(Int((z * 10).rounded()))"
                wanted.insert(key)
                if let existing = sectorPostEntities[key] {
                    if abs(existing.position.y - (groundY + 0.25)) > 0.08 {
                        existing.position.y = groundY + 0.25
                    }
                    continue
                }
                // Thin emissive pillar (cylinder mesh is iOS 18+).
                let anchor = AnchorEntity(world: SIMD3(x, groundY + 0.25, z))
                let post = ModelEntity(
                    mesh: .generateBox(size: SIMD3(0.06, 0.5, 0.06)),
                    materials: [UnlitMaterial(color: UIColor.orange.withAlphaComponent(0.9))]
                )
                anchor.addChild(post)
                arView.scene.addAnchor(anchor)
                sectorPostEntities[key] = anchor
                animateIn(post)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak post] in
                    guard let post else { return }
                    self.addPulse(to: post, scale: 1.12, duration: 0.9)
                }
            }
            // Covered sectors shrink away — progress reads spatially.
            for (key, anchor) in sectorPostEntities where !wanted.contains(key) {
                sectorPostEntities.removeValue(forKey: key)
                animateOutAndRemove(anchor)
            }
        }
    }

    func setGuidanceTarget(x: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            let groundY = (floorWorldY ?? cameraFallbackGroundY())
            guidanceTarget = SIMD3(Float(x), groundY, Float(z))
        }
    }

    func clearGuidanceTarget() {
        DispatchQueue.main.async { [self] in
            guidanceTarget = nil
        }
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

    func discardCurrentCaptureChunk() {
        recorder?.discardCurrentChunk()
    }

    func stopCaptureAndDrain() -> [String] {
        recorder?.stopAndDrain() ?? []
    }

    func setCaptureEnabled(enabled: Bool) {
        recorder?.setEnabled(enabled)
    }

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
        case .none: isFloor = true
        default: isFloor = false
        }
        // Floor height feeds fog/walls/posts/cells in EVERY mode — track it
        // even when the grid itself is hidden (route/spot), otherwise those
        // entities sit at the camera-offset estimate forever.
        if isFloor {
            let y = plane.transform.columns.3.y + plane.center.y
            if floorWorldY == nil || y < floorWorldY! { floorWorldY = y }
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
        if floorMinX == nil || planeMinX < floorMinX! { floorMinX = planeMinX; grew = true }
        if floorMaxX == nil || planeMaxX > floorMaxX! { floorMaxX = planeMaxX; grew = true }
        if floorMinZ == nil || planeMinZ < floorMinZ! { floorMinZ = planeMinZ; grew = true }
        if floorMaxZ == nil || planeMaxZ > floorMaxZ! { floorMaxZ = planeMaxZ; grew = true }

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
        let tint = UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.28)
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
}

enum GccArNative {
    /// Called once from `AppDelegate.didFinishLaunching`. Registration is the
    /// opt-in: if this never runs (or throws), Kotlin renders with SceneKit.
    static func register() {
        ArNativeRegistry.shared.surfaceFactory = { RealityKitArSurface() }
    }
}
