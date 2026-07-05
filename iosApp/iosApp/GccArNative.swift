import Combine
import Foundation
import ARKit
import RealityKit
import UIKit
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

    /// Grid squares stay this size no matter how large a plane grows.
    private static let gridTileMeters: Float = 0.5
    /// Vector-scan overlay caps (mirrors the Kotlin SceneKit path).
    private static let maxVectorCells = 350

    private var planeEntities: [UUID: PlaneGridEntity] = [:]
    private var vectorCellEntities: [String: AnchorEntity] = [:]
    private var fogCellEntities: [String: AnchorEntity] = [:]
    private var wallEntities: [AnchorEntity] = []
    private var floorWorldY: Float?
    private var groundLive = false
    private var groundGridVisible = true

    // Route-mode extras: camera-attached guidance arrow + warning veil.
    private static let maxFogCells = 600
    private let cameraAnchor = AnchorEntity(.camera)
    private var arrowEntity: ModelEntity?
    private var veilEntity: ModelEntity?
    private var guidanceTarget: SIMD3<Float>?
    private var updateSubscription: Cancellable?

    private lazy var floorGridTexture: TextureResource? = Self.makeGridTexture(
        line: UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.9), lineWidth: 3
    )
    private lazy var roughGridTexture: TextureResource? = Self.makeGridTexture(
        line: UIColor(white: 1.0, alpha: 0.4), lineWidth: 2
    )
    private lazy var vectorGridTexture: TextureResource? = Self.makeGridTexture(
        line: UIColor(red: 0.15, green: 0.95, blue: 0.55, alpha: 1.0), lineWidth: 4, diagonals: true
    )

    override init() {
        arView = ARView(frame: .zero, cameraMode: .ar, automaticallyConfigureSession: false)
        super.init()

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
        // RealityKit has no LookAt constraint — a per-frame scene update
        // re-aims the guidance arrow at the current frontier target.
        updateSubscription = arView.scene.subscribe(to: SceneEvents.Update.self) { [weak self] _ in
            self?.aimArrow()
        }

        let coaching = ARCoachingOverlayView()
        coaching.session = arView.session
        coaching.goal = .horizontalPlane
        coaching.activatesAutomatically = true
        coaching.frame = arView.bounds
        coaching.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        arView.addSubview(coaching)
    }

    // MARK: - ArNativeSurface

    func view() -> UIView { arView }

    func pause() {
        arView.session.pause()
        frameLock.lock()
        lastFrame = nil
        frameLock.unlock()
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

    func updateVectorCells(flatXZ: [KotlinDouble], cellSizeMeters: Double) {
        DispatchQueue.main.async { [self] in
            let groundY = (floorWorldY ?? cameraFallbackGroundY()) + 0.02
            var wanted = Set<String>()
            var index = 0
            while index + 1 < flatXZ.count {
                let x = Float(truncating: flatXZ[index])
                let z = Float(truncating: flatXZ[index + 1])
                index += 2
                let key = "\(Int((x * 10).rounded())):\(Int((z * 10).rounded()))"
                wanted.insert(key)
                if vectorCellEntities[key] != nil { continue }
                if vectorCellEntities.count >= Self.maxVectorCells { continue }
                let anchor = AnchorEntity(world: SIMD3(x, groundY, z))
                let mesh = MeshResource.generatePlane(
                    width: Float(cellSizeMeters), depth: Float(cellSizeMeters)
                )
                anchor.addChild(ModelEntity(mesh: mesh, materials: [gridMaterial(vectorGridTexture, tint: UIColor(red: 0.15, green: 0.95, blue: 0.55, alpha: 0.5), uvScale: 1)]))
                arView.scene.addAnchor(anchor)
                vectorCellEntities[key] = anchor
            }
            for (key, anchor) in vectorCellEntities where !wanted.contains(key) {
                anchor.removeFromParent()
                vectorCellEntities.removeValue(forKey: key)
            }
        }
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
            if !visible {
                for (_, entity) in planeEntities { entity.anchor.removeFromParent() }
                planeEntities.removeAll()
                refreshGroundState()
            }
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
                if fogCellEntities[key] != nil { continue }
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
            // Reveal-by-omission: scanned cells vanish.
            for (key, anchor) in fogCellEntities where !wanted.contains(key) {
                anchor.removeFromParent()
                fogCellEntities.removeValue(forKey: key)
            }
        }
    }

    func setGuidanceTarget(x: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            let groundY = (floorWorldY ?? cameraFallbackGroundY())
            guidanceTarget = SIMD3(Float(x), groundY, Float(z))
            arrow().isEnabled = true
        }
    }

    func clearGuidanceTarget() {
        DispatchQueue.main.async { [self] in
            guidanceTarget = nil
            arrowEntity?.isEnabled = false
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

    // MARK: - Guidance arrow internals

    /// Elongated emissive box pointer (cone/pyramid meshes are iOS 18+),
    /// hanging below the camera; long axis is Z so look-style aiming works.
    private func arrow() -> ModelEntity {
        if let arrowEntity { return arrowEntity }
        let entity = ModelEntity(
            mesh: .generateBox(size: SIMD3(0.05, 0.05, 0.28)),
            materials: [UnlitMaterial(color: UIColor.green.withAlphaComponent(0.95))]
        )
        entity.position = SIMD3(0, -0.4, -1.0)
        cameraAnchor.addChild(entity)
        arrowEntity = entity
        return entity
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

    private func aimArrow() {
        guard let target = guidanceTarget, let arrow = arrowEntity, arrow.isEnabled else { return }
        let from = arrow.position(relativeTo: nil)
        let direction = target - from
        let flat = SIMD3(direction.x, direction.y, direction.z)
        guard simd_length(flat) > 0.01 else { return }
        arrow.setOrientation(
            simd_quatf(from: SIMD3(0, 0, -1), to: simd_normalize(flat)),
            relativeTo: nil
        )
    }

    func emitLockEffect(x: Double, y: Double, z: Double) {
        DispatchQueue.main.async { [self] in
            let anchor = AnchorEntity(world: SIMD3(Float(x), Float(y), Float(z)))
            let pulse = ModelEntity(
                mesh: .generateSphere(radius: 0.06),
                materials: [UnlitMaterial(color: UIColor.green.withAlphaComponent(0.8))]
            )
            if #available(iOS 18.0, *) {
                var emitter = ParticleEmitterComponent.Presets.sparks
                emitter.isEmitting = true
                pulse.components.set(emitter)
            }
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

    // MARK: - ARSessionDelegate

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        frameLock.lock()
        lastFrame = frame
        frameLock.unlock()
    }

    func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
        for case let plane as ARPlaneAnchor in anchors { upsertPlane(plane) }
    }

    func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
        for case let plane as ARPlaneAnchor in anchors { upsertPlane(plane) }
    }

    func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
        for case let plane as ARPlaneAnchor in anchors {
            planeEntities.removeValue(forKey: plane.identifier)?.anchor.removeFromParent()
        }
        refreshGroundState()
    }

    // MARK: - Ground grid

    private final class PlaneGridEntity {
        let anchor: AnchorEntity
        let model: ModelEntity
        var lastExtent: SIMD2<Float> = .zero
        var isFloor: Bool
        init(anchor: AnchorEntity, model: ModelEntity, isFloor: Bool) {
            self.anchor = anchor
            self.model = model
            self.isFloor = isFloor
        }
    }

    private func upsertPlane(_ plane: ARPlaneAnchor) {
        guard groundGridVisible else { return }
        let extent = SIMD2(plane.planeExtent.width, plane.planeExtent.height)
        guard extent.x >= 0.4, extent.y >= 0.4 else { return }
        // Unclassified horizontal planes count as ground (matches the Kotlin
        // KVO path); recognized furniture (table/seat/…) gets the faint grid.
        let isFloor: Bool
        switch plane.classification {
        case .floor: isFloor = true
        case .none: isFloor = true
        default: isFloor = false
        }

        if let existing = planeEntities[plane.identifier] {
            existing.model.position = plane.center
            // Regenerating the mesh is the expensive part — only when the
            // plane has actually grown noticeably.
            if abs(extent.x - existing.lastExtent.x) > 0.25 || abs(extent.y - existing.lastExtent.y) > 0.25 {
                existing.lastExtent = extent
                existing.model.model?.mesh = MeshResource.generatePlane(width: extent.x, depth: extent.y)
                existing.model.model?.materials = [planeMaterial(isFloor: existing.isFloor, extent: extent)]
            }
            return
        }

        let anchor = AnchorEntity(anchor: plane)
        let model = ModelEntity(
            mesh: MeshResource.generatePlane(width: extent.x, depth: extent.y),
            materials: [planeMaterial(isFloor: isFloor, extent: extent)]
        )
        model.position = plane.center
        anchor.addChild(model)
        arView.scene.addAnchor(anchor)
        planeEntities[plane.identifier] = PlaneGridEntity(anchor: anchor, model: model, isFloor: isFloor)

        if isFloor {
            let y = plane.transform.columns.3.y + plane.center.y
            if floorWorldY == nil || y < floorWorldY! { floorWorldY = y }
        }
        refreshGroundState()
    }

    private func refreshGroundState() {
        groundLive = !planeEntities.isEmpty
    }

    private func planeMaterial(isFloor: Bool, extent: SIMD2<Float>) -> PhysicallyBasedMaterial {
        let texture = isFloor ? floorGridTexture : roughGridTexture
        let tint = isFloor
            ? UIColor(red: 0.20, green: 0.85, blue: 0.45, alpha: 0.55)
            : UIColor(white: 1.0, alpha: 0.3)
        var material = gridMaterial(texture, tint: tint, uvScale: 1)
        material.textureCoordinateTransform.scale = SIMD2(
            max(extent.x / Self.gridTileMeters, 1),
            max(extent.y / Self.gridTileMeters, 1)
        )
        return material
    }

    // PhysicallyBasedMaterial rather than UnlitMaterial: only PBM has
    // textureCoordinateTransform before iOS 18, and tiling is what keeps
    // grid squares a constant 0.5m as planes grow. Emissive channel keeps
    // the glow regardless of scene lighting.
    private func gridMaterial(_ texture: TextureResource?, tint: UIColor, uvScale: Float) -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        if let texture {
            material.baseColor = .init(tint: tint, texture: .init(texture))
            material.emissiveColor = .init(color: tint, texture: .init(texture))
        } else {
            material.baseColor = .init(tint: tint)
            material.emissiveColor = .init(color: tint)
        }
        material.emissiveIntensity = 2.0
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

    /// Transparent tile with lines on its leading edges — repeat-tiled by the
    /// UV transform it becomes a seamless grid; diagonals give the denser
    /// "vector scan" look. Mirrors the Kotlin SceneKit texture exactly.
    private static func makeGridTexture(line: UIColor, lineWidth: CGFloat, diagonals: Bool = false) -> TextureResource? {
        let side: CGFloat = 128
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { ctx in
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 0, y: 0.5))
            path.addLine(to: CGPoint(x: side, y: 0.5))
            path.move(to: CGPoint(x: 0.5, y: 0))
            path.addLine(to: CGPoint(x: 0.5, y: side))
            if diagonals {
                path.move(to: .zero)
                path.addLine(to: CGPoint(x: side, y: side))
                path.move(to: CGPoint(x: side, y: 0))
                path.addLine(to: CGPoint(x: 0, y: side))
            }
            path.lineWidth = lineWidth
            line.setStroke()
            path.stroke()
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
