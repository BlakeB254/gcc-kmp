import ARKit
import CoreVideo
import Foundation
import simd

/// LiDAR ground-anomaly detector: finds on-ground protrusions (litter
/// candidates) purely from depth geometry. Swift does pixel work ONLY here —
/// no classification, no lifecycle, no filtering beyond the geometric gates
/// documented inline. Kotlin decides everything else (dedup, scoring,
/// lifecycle) from the packets `analyze(frame:floorY:floorBounds:)` returns;
/// see `ArNativeSurface.drainGroundCandidates` / `GroundCandidatePacket.kt`
/// for the Kotlin side of the contract.
///
/// Height is measured against a LOCAL ground estimate (windowed minimum of
/// the depth grid itself, pass 2 in `analyze`), NOT against the plane-tracked
/// `floorY` — that global reference broke detection on sloped/stepped
/// terrain and under ARKit Y drift (build-167 root cause; three field rounds
/// of "zero identifications"). `floorY` now only sanity-bands the local
/// estimate.
///
/// Coordinate conventions (ARKit):
///  - `capturedImage` and the depth map share the sensor's native landscape
///    orientation, top-left pixel origin, v increasing downward.
///  - `frame.camera.intrinsics` are defined for the FULL-RES `capturedImage`
///    (`frame.camera.imageResolution`). The depth map is lower resolution,
///    so before indexing depth-map pixels the intrinsics are scaled by
///    `(depthWidth / imageWidth, depthHeight / imageHeight)`.
///  - Unprojection: for depth-map pixel `(u, v)` with depth `d` (meters,
///    scaled intrinsics `fx, fy, cx, cy`):
///      x = (u - cx) / fx * d
///      y = (v - cy) / fy * d
///      z = -d                              (camera looks down -Z)
///      world = camera.transform * [x, -y, z, 1]   (Vision/CVPixelBuffer y-flip)
///  - Forward projection (world -> normalized `capturedImage` coordinates,
///    used for the reported image-space bbox) is the algebraic inverse of
///    the above, using the UNSCALED full-res intrinsics:
///      camPoint = camera.transform.inverse * world
///      u = cx - fx * camPoint.x / camPoint.z
///      v = cy + fy * camPoint.y / camPoint.z
final class GroundAnomalyDetector {

    private static let minDepthM: Float = 0.5
    private static let maxDepthM: Float = 6.0
    private static let minHeightM: Float = 0.03
    /// 0.4 → 0.9 (build 167): dumped garbage bags and standing tires are
    /// 0.5–0.8m tall — the 2026-07-25 field walk's dumping pile failed this
    /// gate outright. Fixture/backdrop classification and the user-confirm
    /// flow guard the top end; the geometric cap only needs to exclude
    /// people/cars, which the footprint cap already does.
    static var maxHeightM: Float = 0.9
    private static let minFootprintM: Float = 0.05
    /// 1.0 → 1.4 (build 171, Blake's 170 field run): a large dumped trash
    /// bag spans >1m and was rejected outright — only the small can beside
    /// it identified. Cars stay excluded by span in their long axis plus
    /// the fixture veto downstream; people by the height cap.
    /// `var` (build 175): remote-tunable via ScanTuning/setGroundSensorCaps
    /// — written once at scan open before analysis starts, read on the
    /// analysis queue.
    static var maxFootprintM: Float = 1.4
    private static let maxGridWidth = 64
    private static let maxGridHeight = 48
    private static let maxCandidates = 8
    /// Half-width (cells) of the windowed-minimum LOCAL ground estimate —
    /// see `analyze`. 17×17 cells ≈ 1.1m on the ground at 3m viewing
    /// distance: wider than the max candidate footprint, so a real object
    /// can't fully occupy its own window and suppress itself.
    private static let groundWindowRadius = 8
    /// Local ground estimates further than this from the plane-tracked floor
    /// are junk (a rooftop seen over a fence, a truck bed) — generous enough
    /// to survive multi-minute ARKit Y drift, which the old global gate
    /// wasn't (build 167 root cause).
    private static let groundSanityBandM: Float = 2.0

    /// Runs the full geometric pipeline over one frame: downsample depth →
    /// unproject + gate → 4-connected components → per-component packet.
    /// Pure function of its arguments — no shared mutable state, no locks —
    /// so callers (`RealityKitArSurface.drainGroundCandidates`) grab
    /// everything it needs under their own lock first, then call this off
    /// that lock.
    func analyze(
        frame: ARFrame,
        floorY: Float,
        floorBounds: (minX: Float, maxX: Float, minZ: Float, maxZ: Float)
    ) -> [String] {
        guard let depthMap = (frame.smoothedSceneDepth ?? frame.sceneDepth)?.depthMap else { return [] }

        // ARKit scene-depth maps are Float32 today; fail safe (not crash in
        // assumingMemoryBound) if Apple ever changes the format.
        guard CVPixelBufferGetPixelFormatType(depthMap) == kCVPixelFormatType_DepthFloat32 else { return [] }

        let depthWidth = CVPixelBufferGetWidth(depthMap)
        let depthHeight = CVPixelBufferGetHeight(depthMap)
        guard depthWidth > 0, depthHeight > 0 else { return [] }

        let imageResolution = frame.camera.imageResolution
        guard imageResolution.width > 0, imageResolution.height > 0 else { return [] }
        let imageWidth = Float(imageResolution.width)
        let imageHeight = Float(imageResolution.height)

        // Full-res intrinsics (used as-is for the forward/image projection)
        // and a depth-map-scaled copy (used for unprojecting depth pixels).
        let intrinsics = frame.camera.intrinsics
        let fx = intrinsics.columns.0.x
        let fy = intrinsics.columns.1.y
        let cx = intrinsics.columns.2.x
        let cy = intrinsics.columns.2.y
        let scaleX = Float(depthWidth) / imageWidth
        let scaleY = Float(depthHeight) / imageHeight
        let depthFx = fx * scaleX
        let depthFy = fy * scaleY
        let depthCx = cx * scaleX
        let depthCy = cy * scaleY

        let gridW = min(Self.maxGridWidth, depthWidth)
        let gridH = min(Self.maxGridHeight, depthHeight)
        guard gridW > 0, gridH > 0 else { return [] }

        CVPixelBufferLockBaseAddress(depthMap, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(depthMap, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(depthMap) else { return [] }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(depthMap)

        let cameraTransform = frame.camera.transform
        var worldPoints = [SIMD3<Float>?](repeating: nil, count: gridW * gridH)
        var kept = [Bool](repeating: false, count: gridW * gridH)

        // Pass 1: unproject every depth-valid cell. No height gating yet —
        // the ground reference is derived from these points in pass 2.
        var cellY = [Float](repeating: .infinity, count: gridW * gridH)
        for gy in 0..<gridH {
            let v = min(depthHeight - 1, (gy * depthHeight) / gridH)
            let rowPointer = base.advanced(by: v * bytesPerRow).assumingMemoryBound(to: Float32.self)
            for gx in 0..<gridW {
                let u = min(depthWidth - 1, (gx * depthWidth) / gridW)
                let depth = rowPointer[u]
                guard depth.isFinite, depth >= Self.minDepthM, depth <= Self.maxDepthM else { continue }

                let x = (Float(u) - depthCx) / depthFx * depth
                let y = (Float(v) - depthCy) / depthFy * depth
                let cameraSpacePoint = SIMD4<Float>(x, -y, -depth, 1)
                let worldPoint4 = cameraTransform * cameraSpacePoint
                let idx = gy * gridW + gx
                worldPoints[idx] = SIMD3<Float>(worldPoint4.x, worldPoint4.y, worldPoint4.z)
                cellY[idx] = worldPoint4.y
            }
        }

        // Pass 2: LOCAL ground estimate — a separable windowed MINIMUM of
        // cell heights. The pre-167 code measured height against the
        // session-global minimum plane Y, which broke on any real walk:
        // local terrain more than 0.4m above the session's lowest point
        // (curb/street transitions, sloped lots) and plain multi-minute
        // ARKit Y drift both pushed ALL litter out of the height band —
        // the root cause of "zero identifications" across three field
        // rounds. The windowed minimum tracks the ground the item actually
        // sits on, wherever the session wanders.
        let radius = Self.groundWindowRadius
        var rowMin = [Float](repeating: .infinity, count: gridW * gridH)
        for gy in 0..<gridH {
            let rowBase = gy * gridW
            for gx in 0..<gridW {
                var m = Float.infinity
                for dx in max(0, gx - radius)...min(gridW - 1, gx + radius) {
                    m = min(m, cellY[rowBase + dx])
                }
                rowMin[rowBase + gx] = m
            }
        }
        var groundY = [Float](repeating: .infinity, count: gridW * gridH)
        for gy in 0..<gridH {
            for gx in 0..<gridW {
                var m = Float.infinity
                for dy in max(0, gy - radius)...min(gridH - 1, gy + radius) {
                    m = min(m, rowMin[dy * gridW + gx])
                }
                groundY[gy * gridW + gx] = m
            }
        }

        // Pass 3: gates against the LOCAL ground. floorY only sanity-bands
        // the ground estimate (rooftop over a fence ≠ ground); it no longer
        // participates in the height measurement itself.
        for idx in 0..<(gridW * gridH) {
            guard let worldPoint = worldPoints[idx] else { continue }
            guard worldPoint.x >= floorBounds.minX, worldPoint.x <= floorBounds.maxX,
                  worldPoint.z >= floorBounds.minZ, worldPoint.z <= floorBounds.maxZ else { continue }
            let ground = groundY[idx]
            guard ground.isFinite, abs(ground - floorY) <= Self.groundSanityBandM else { continue }
            let height = worldPoint.y - ground
            guard height >= Self.minHeightM, height <= Self.maxHeightM else { continue }
            kept[idx] = true
        }

        let components = Self.connectedComponents(kept: kept, gridW: gridW, gridH: gridH, worldPoints: worldPoints)

        let cameraPosition = SIMD3<Float>(
            cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z
        )
        let inverseCameraTransform = cameraTransform.inverse

        // Ground-plane spacing of adjacent grid cells at 1m depth — used to
        // inflate component spans, since a span measured between CELL
        // CENTERS underestimates the true object extent by up to one cell
        // per axis (a single-cell component has span 0).
        let cellPitchAtUnitDepth = (Float(depthWidth) / Float(gridW)) / depthFx

        var candidates: [(distance: Float, packet: String)] = []
        for points in components {
            guard let candidate = Self.makeCandidate(
                points: points,
                cameraPosition: cameraPosition,
                inverseCameraTransform: inverseCameraTransform,
                fx: fx, fy: fy, cx: cx, cy: cy,
                imageWidth: imageWidth, imageHeight: imageHeight,
                cellPitchAtUnitDepth: cellPitchAtUnitDepth
            ) else { continue }
            candidates.append(candidate)
        }

        return candidates
            .sorted { $0.distance < $1.distance }
            .prefix(Self.maxCandidates)
            .map { $0.packet }
    }

    /// 4-connected component labeling over the kept grid cells: one raster
    /// pass builds a union-find over cell indices, then one resolve pass
    /// groups cells by their canonical root — never more than
    /// `maxGridWidth * maxGridHeight` (3072) cells.
    private static func connectedComponents(
        kept: [Bool], gridW: Int, gridH: Int, worldPoints: [SIMD3<Float>?]
    ) -> [[SIMD3<Float>]] {
        var parent = [Int](repeating: -1, count: gridW * gridH)

        func find(_ start: Int) -> Int {
            var i = start
            while parent[i] != i {
                parent[i] = parent[parent[i]]
                i = parent[i]
            }
            return i
        }
        func union(_ a: Int, _ b: Int) {
            let rootA = find(a)
            let rootB = find(b)
            if rootA != rootB { parent[rootA] = rootB }
        }

        for gy in 0..<gridH {
            for gx in 0..<gridW {
                let idx = gy * gridW + gx
                guard kept[idx] else { continue }
                parent[idx] = idx
                if gx > 0, kept[idx - 1] { union(idx, idx - 1) }
                if gy > 0, kept[idx - gridW] { union(idx, idx - gridW) }
            }
        }

        var components: [Int: [SIMD3<Float>]] = [:]
        for idx in 0..<(gridW * gridH) where kept[idx] {
            let root = find(idx)
            components[root, default: []].append(worldPoints[idx]!)
        }
        return Array(components.values)
    }

    /// Builds one candidate packet from a component's world points, or nil
    /// if it fails the footprint gate or can't be projected into the image.
    private static func makeCandidate(
        points: [SIMD3<Float>],
        cameraPosition: SIMD3<Float>,
        inverseCameraTransform: simd_float4x4,
        fx: Float, fy: Float, cx: Float, cy: Float,
        imageWidth: Float, imageHeight: Float,
        cellPitchAtUnitDepth: Float
    ) -> (distance: Float, packet: String)? {
        // ≥2 cells: a single isolated depth-noise cell must not become a
        // candidate now that the footprint gate below no longer rejects
        // small components outright.
        guard points.count >= 2 else { return nil }
        var minP = points[0]
        var maxP = points[0]
        for p in points {
            minP = min(minP, p)
            maxP = max(maxP, p)
        }

        let centroid = (minP + maxP) * 0.5
        let distance = simd_distance(centroid, cameraPosition)

        // Spans measured between downsampled CELL CENTERS underestimate the
        // object by up to one cell pitch per axis — a 2-cell component has
        // span 0 in one axis, and the pre-167 gate (raw span >= 0.05 in BOTH
        // axes) therefore rejected ALL small litter (a cup at 2m is 1-2
        // cells) unconditionally. Gate the minimum on the pitch-inflated
        // span; keep the maximum on the raw span so inflation never rejects
        // a legitimately large pile.
        let cellPitch = cellPitchAtUnitDepth * distance
        let footprintW = (maxP.x - minP.x) + cellPitch
        let footprintD = (maxP.z - minP.z) + cellPitch
        guard footprintW >= minFootprintM, footprintD >= minFootprintM,
              (maxP.x - minP.x) <= maxFootprintM, (maxP.z - minP.z) <= maxFootprintM else { return nil }

        let widthM = footprintW
        let heightM = maxP.y - minP.y
        let depthM = footprintD

        // Project the 8 world-AABB corners into normalized capturedImage
        // coordinates (full-res intrinsics — see file-level doc) for the
        // reported image-space bbox.
        var minU: Float = .greatestFiniteMagnitude, maxU: Float = -.greatestFiniteMagnitude
        var minV: Float = .greatestFiniteMagnitude, maxV: Float = -.greatestFiniteMagnitude
        var projectedAny = false
        for cornerX in [minP.x, maxP.x] {
            for cornerY in [minP.y, maxP.y] {
                for cornerZ in [minP.z, maxP.z] {
                    let worldCorner = SIMD4<Float>(cornerX, cornerY, cornerZ, 1)
                    let cameraCorner = inverseCameraTransform * worldCorner
                    guard cameraCorner.z < 0 else { continue } // behind the camera — skip
                    let u = cx - fx * cameraCorner.x / cameraCorner.z
                    let v = cy + fy * cameraCorner.y / cameraCorner.z
                    minU = min(minU, u); maxU = max(maxU, u)
                    minV = min(minV, v); maxV = max(maxV, v)
                    projectedAny = true
                }
            }
        }
        guard projectedAny else { return nil }

        let normMinU = max(0, min(1, minU / imageWidth))
        let normMaxU = max(0, min(1, maxU / imageWidth))
        let normMinV = max(0, min(1, minV / imageHeight))
        let normMaxV = max(0, min(1, maxV / imageHeight))
        guard normMaxU > normMinU, normMaxV > normMinV else { return nil }
        let imageCx = (normMinU + normMaxU) / 2
        let imageCy = (normMinV + normMaxV) / 2
        let imageHalfW = (normMaxU - normMinU) / 2
        let imageHalfH = (normMaxV - normMinV) / 2

        // Spatial hash on the world centroid, quantized to half-meter cells
        // — stable across re-observations of roughly the same spot.
        let hashX = Int64((centroid.x * 2).rounded())
        let hashZ = Int64((centroid.z * 2).rounded())
        let id = (hashX &* 73_856_093) ^ (hashZ &* 19_349_663)

        let packet = [
            String(id),
            fmt(centroid.x), fmt(centroid.y), fmt(centroid.z),
            fmt(widthM), fmt(heightM), fmt(depthM),
            fmt(distance),
            fmt(imageCx), fmt(imageCy), fmt(imageHalfW), fmt(imageHalfH),
        ].joined(separator: "|")

        return (distance, packet)
    }

    private static func fmt(_ value: Float) -> String {
        String(format: "%.4g", value)
    }
}
