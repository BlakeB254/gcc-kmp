package com.gcc.app.platform

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.ARKit.ARFrame
import platform.ARKit.ARHitTestResult
import platform.ARKit.ARHitTestResultTypeEstimatedHorizontalPlane
import platform.ARKit.ARHitTestResultTypeExistingPlaneUsingGeometry
import platform.ARKit.ARHitTestResultTypeFeaturePoint
import platform.CoreGraphics.CGPointMake
import platform.darwin.simd_float4x4
import kotlin.math.sqrt

/**
 * Per-tick raycast fan for coverage crediting (spec §1): a fixed grid of
 * image-space sample points swept across the camera view. Each ray credits
 * the cell it LANDS in — rays terminate on real surfaces, so indoor walls
 * physically bound what can credit.
 *
 * Image-space orientation (portrait UI): screen-vertical = image X
 * (viewY = imageX in the reticle mapping, ArEnvScan analyzeTrash), so
 * "toward the ground" = imageX toward 1.0. imageY spans screen-horizontal.
 */
@OptIn(ExperimentalForeignApi::class)
internal object CoverageRayFan {
    /** (imageX, imageY) sample grid: 3 vertical bands (center → ground) × 5 horizontal. */
    val SAMPLE_POINTS: List<Pair<Double, Double>> = buildList {
        for (ix in listOf(0.5, 0.7, 0.85)) {
            for (iy in listOf(0.15, 0.35, 0.5, 0.65, 0.85)) add(ix to iy)
        }
    }

    class FanHit(val x: Float, val y: Float, val z: Float, val distance: Float)

    /** One world hit for a normalized image point via native surface or SceneKit frame. */
    fun raycastPoint(surface: ArNativeSurface?, frame: ARFrame?, imageX: Double, imageY: Double): FanHit? {
        surface?.let { native ->
            val hit = native.raycast(imageX, imageY) ?: return null
            val cam = native.cameraPosition() ?: return null
            val dx = hit[0] - cam[0]
            val dy = hit[1] - cam[1]
            val dz = hit[2] - cam[2]
            return FanHit(
                hit[0].toFloat(), hit[1].toFloat(), hit[2].toFloat(),
                sqrt(dx * dx + dy * dy + dz * dz).toFloat(),
            )
        }
        val hit = frame?.hitTest(
            CGPointMake(imageX, imageY),
            types = ARHitTestResultTypeExistingPlaneUsingGeometry
                or ARHitTestResultTypeEstimatedHorizontalPlane
                or ARHitTestResultTypeFeaturePoint,
        )?.firstOrNull() as? ARHitTestResult ?: return null
        val world = matrixTranslation(hit.worldTransform)
        return FanHit(world[0], world[1], world[2], hit.distance.toFloat())
    }

    /**
     * Cast the full fan and geo-resolve the hits. [toLatLng] is the caller's
     * geo-ref conversion (localToLatLng bound to its reference frame).
     * Hits beyond [CoverageTracker.GRAZE_RANGE_METERS] are dropped here so
     * listeners only ever see creditable hits.
     */
    fun cast(
        surface: ArNativeSurface?,
        frame: ARFrame?,
        toLatLng: (x: Float, z: Float) -> Pair<Double, Double>,
    ): List<RaycastCoverageHit> {
        val hits = mutableListOf<RaycastCoverageHit>()
        for ((ix, iy) in SAMPLE_POINTS) {
            val hit = raycastPoint(surface, frame, ix, iy) ?: continue
            if (hit.distance > CoverageTracker.GRAZE_RANGE_METERS) continue
            val (lat, lng) = toLatLng(hit.x, hit.z)
            hits.add(RaycastCoverageHit(lat, lng, hit.distance.toDouble()))
        }
        return hits
    }
}

/**
 * World-space translation (x, y, z) from a column-major `simd_float4x4`
 * transform — shared by [CoverageRayFan] and the AR overlays' own raycast/
 * camera-position helpers (single definition; was previously duplicated
 * per-overlay as a private helper).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun matrixTranslation(transform: CValue<simd_float4x4>): FloatArray = memScoped {
    val matrix = alloc<simd_float4x4>()
    transform.place(matrix.ptr)
    val values = matrix.ptr.reinterpret<FloatVar>()
    floatArrayOf(values[12], values[13], values[14])
}
