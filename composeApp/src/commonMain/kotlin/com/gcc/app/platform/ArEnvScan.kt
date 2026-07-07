package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * A close-range trash candidate awaiting user confirmation on the environment
 * scan. Produced when a tracked detection has been locked, close (≤2m) and
 * roughly centered long enough to measure — carries the LiDAR/raycast-derived
 * world position and physical estimates the confirm card shows.
 */
data class TrashCandidate(
    val id: Int,
    /** Display label from the classifier (e.g. "Bottle"). */
    val label: String,
    /** Canonical type from [TrashItemCatalog] (e.g. "plastic_bottle"). */
    val itemType: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Float,
    /** Physical width from depth + bounding-box angular size; null if unmeasurable. */
    val estimatedWidthMeters: Double?,
    val estimatedWeightGrams: Int,
    val confidence: Float,
    /** AR-local compass bearing (degrees, [0, 360)) camera→hit; null if unmeasurable. */
    val yawDegrees: Double? = null,
)

/**
 * ARKit-backed environment-scan surface (iOS): replaces the plain AVCapture
 * viewfinder with an `ARSCNView` so the scan gets true world tracking —
 *
 *  - a grid that spreads over the ground as ARKit recognizes floor planes,
 *  - a "vector scan" overlay marking cells already credited this session,
 *  - tracked trash lock-on with close-range type/weight estimation,
 *  - LiDAR/raycast world placement of confirmed items (instead of the
 *    "2.5m forward along GPS heading" approximation).
 *
 * Scene votes and live detections flow through the existing global
 * [CameraSceneVoteBus]/[CameraDetectionBus], and the surface registers itself
 * as [CameraManager]'s frame source, so the OutdoorGate, reticle overlay and
 * silent-capture flows are unchanged. Android/Desktop: no-op stub — the env
 * scan keeps the AVCapture path there (iOS-first).
 */
expect class ArEnvScanOverlay() {
    /** AR tracking quality; LIMITED should surface a "hold steady" prompt. */
    val trackingState: StateFlow<TrackingState>

    /** True once ARKit has recognized ground and the grid effect is live. */
    val groundDetected: StateFlow<Boolean>

    /** Non-null while a close-range candidate awaits Confirm / Not trash. */
    val trashCandidate: StateFlow<TrashCandidate?>

    /** Pair the worker's latest GPS fix with the AR camera's current position. */
    fun updateGeoReference(lat: Double, lng: Double)

    /** Cells credited this session — rendered as the vector-scan ground overlay. */
    fun updateScannedCells(cellCenters: List<Pair<Double, Double>>)

    /**
     * Uncredited cells near the worker — rendered as translucent AR fog planes
     * on the ground that dissipate as cells get credited (route-scan parity).
     */
    fun updateFog(cellCenters: List<Pair<Double, Double>>)

    /** Aim the AR guidance arrow at the next area worth scanning. */
    fun setGuidanceTarget(lat: Double, lng: Double)

    /** Hide the AR guidance arrow (no frontier left, or no usable geo-ref). */
    fun clearGuidanceTarget()

    /**
     * Geographic origin of the CoverageTracker cell lattice. The AR ground
     * grid phases its lines to this point (converted through the live geo
     * reference, re-derived on every re-pair) so the on-camera geogrid
     * registers with the cell grid the minimap draws.
     */
    fun setGridLatticeOrigin(lat: Double, lng: Double)

    /**
     * Confirm the pending candidate (optionally re-typed by the user); places
     * the world marker and returns the final candidate with weight re-estimated
     * for the chosen type. Null when no candidate is pending.
     */
    fun confirmCandidate(overrideItemType: String?): TrashCandidate?

    /** Dismiss the pending candidate ("Not trash") and suppress that anchor. */
    fun dismissCandidate()

    /**
     * Fires when a landmark element (tree, fence, gate… — see
     * [EnvironmentElementCatalog]) is auto-registered to the grid, with its
     * raycast world position, physical width (from the saliency bbox +
     * distance; null if unmeasurable) and AR-local compass bearing camera→hit
     * (null if unmeasurable). Called on the main thread; iOS only.
     */
    fun setElementObservedListener(
        listener: (
            (
                lat: Double, lng: Double, itemType: String, domain: String, confidence: Float,
                widthMeters: Double?, yawDegrees: Double?,
            ) -> Unit
        )?,
    )

    /** Pause/resume frame sampling without tearing down the ARSession. */
    fun setFrameAnalysisEnabled(enabled: Boolean)

    /** Scene-vote sampling cadence: true (~750ms) for fast response near a
     *  gate transition, false for the slower steady-state cadence. iOS only;
     *  no-op on Android/Desktop. */
    fun setSceneVoteCadence(fast: Boolean)

    /** Fires with the current ceiling-plane detection state on every AR tick
     *  (see [OutdoorGate.onCeilingPlane]). iOS only; never invoked elsewhere.
     *  Listener is called on the main thread. */
    fun setCeilingListener(listener: ((Boolean) -> Unit)?)

    /**
     * Fires ~1/sec with the coverage raycast fan's geo-resolved hits (where
     * rays LANDED, with surface distance) while a geo-reference is paired.
     * Listener runs on the main thread; iOS only. Feed straight into
     * [CoverageTracker.updateWithRaycastHits].
     */
    fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?)

    fun stop()
}

/** True on devices where the environment scan can run on an ARKit surface. */
expect fun arEnvScanSupported(): Boolean

/**
 * Hosts the platform AR view for [ArEnvScanOverlay] (iOS: `ARSCNView` in a
 * `UIKitView`; elsewhere: empty placeholder, callers must gate on
 * [arEnvScanSupported]).
 */
@Composable
expect fun ArEnvScanView(overlay: ArEnvScanOverlay, modifier: Modifier)
