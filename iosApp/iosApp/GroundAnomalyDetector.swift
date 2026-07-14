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
    private static let maxHeightM: Float = 0.4
    private static let minFootprintM: Float = 0.05
    private static let maxFootprintM: Float = 1.0
    private static let maxGridWidth = 64
    private static let maxGridHeight = 48
    private static let maxCandidates = 8

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
                let worldPoint = SIMD3<Float>(worldPoint4.x, worldPoint4.y, worldPoint4.z)

                guard worldPoint.x >= floorBounds.minX, worldPoint.x <= floorBounds.maxX,
                      worldPoint.z >= floorBounds.minZ, worldPoint.z <= floorBounds.maxZ else { continue }
                let height = worldPoint.y - floorY
                guard height >= Self.minHeightM, height <= Self.maxHeightM else { continue }

                let idx = gy * gridW + gx
                worldPoints[idx] = worldPoint
                kept[idx] = true
            }
        }

        let components = Self.connectedComponents(kept: kept, gridW: gridW, gridH: gridH, worldPoints: worldPoints)

        let cameraPosition = SIMD3<Float>(
            cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z
        )
        let inverseCameraTransform = cameraTransform.inverse

        var candidates: [(distance: Float, packet: String)] = []
        for points in components {
            guard let candidate = Self.makeCandidate(
                points: points,
                cameraPosition: cameraPosition,
                inverseCameraTransform: inverseCameraTransform,
                fx: fx, fy: fy, cx: cx, cy: cy,
                imageWidth: imageWidth, imageHeight: imageHeight
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
        imageWidth: Float, imageHeight: Float
    ) -> (distance: Float, packet: String)? {
        guard !points.isEmpty else { return nil }
        var minP = points[0]
        var maxP = points[0]
        for p in points {
            minP = min(minP, p)
            maxP = max(maxP, p)
        }

        let footprintW = maxP.x - minP.x
        let footprintD = maxP.z - minP.z
        guard footprintW >= minFootprintM, footprintW <= maxFootprintM,
              footprintD >= minFootprintM, footprintD <= maxFootprintM else { return nil }

        let widthM = maxP.x - minP.x
        let heightM = maxP.y - minP.y
        let depthM = maxP.z - minP.z
        let centroid = (minP + maxP) * 0.5
        let distance = simd_distance(centroid, cameraPosition)

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
