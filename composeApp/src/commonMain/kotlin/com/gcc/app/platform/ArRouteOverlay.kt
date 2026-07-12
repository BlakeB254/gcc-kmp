package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-screen AR overlay for the "Begin Route" experience: directional
 * guidance toward the nearest unscanned frontier and a red boundary veil when
 * the worker steps outside the route's assigned polygon.
 *
 * Call [updateGeoReference] on every GPS fix (pairs the fix with the AR
 * camera position — placement is a no-op until the first pairing).
 *
 * iOS: SceneKit (`ARSCNView`) overlay via platform.ARKit/platform.SceneKit.
 * Android/Desktop: no-op stub — on-screen AR rendering is iOS-only for v1.
 */
expect class ArRouteOverlay() {
    /** AR tracking quality; LIMITED should surface a "hold steady" prompt. */
    val trackingState: StateFlow<TrackingState>

    /** Pair the worker's latest GPS fix with the AR camera's current position. */
    fun updateGeoReference(lat: Double, lng: Double, accuracyMeters: Double? = null)

    /**
     * Geographic origin of the CoverageTracker cell lattice. The AR ground
     * grid phases its lines to this point (converted through the live geo
     * reference, re-derived on every re-pair) so the on-camera geogrid
     * registers with the cell grid the minimap draws. iOS native path only;
     * no-op on Android/Desktop and on the SceneKit fallback (no grid there).
     */
    fun setGridLatticeOrigin(lat: Double, lng: Double)

    /**
     * World-anchored boundary walls: the route/block edge rendered as vertical
     * translucent strips on the ground, so panning the camera toward the
     * assignment edge shows the boundary line in place. [outer] is the block's
     * outer ring, [islands] the inner exclusion polygons — both as (lat, lng)
     * pairs. Pass an empty [outer] to skip walls (routes with no block polygon).
     * iOS only; no-op on Android/Desktop.
     */
    fun updateBoundaries(outer: List<Pair<Double, Double>>, islands: List<List<Pair<Double, Double>>>)

    fun updateBoundaryStatus(isOutside: Boolean)
    fun start()
    fun stop()

    /** Live trash detections from sampled AR frames (iOS only; empty elsewhere). */
    val liveDetections: StateFlow<List<LiveDetection>>

    /** Indoor/outdoor scene votes from sampled AR frames, for the OutdoorGate
     *  (iOS only; never invoked elsewhere). Listener is called on the main thread. */
    fun setSceneVoteListener(listener: ((SceneVote) -> Unit)?)

    /** Fires when a live-detected (CONFIRMED_TRASH) marker is placed, with its
     *  real lat/lng (converted from AR-local coordinates), item label, and a
     *  best-effort downscaled JPEG of the frame that prompted the promotion
     *  (null if capture/conversion failed — never blocks the marker).
     *  iOS only; never invoked elsewhere. Listener is called on the main thread. */
    fun setMarkerPlacedListener(listener: ((lat: Double, lng: Double, label: String, photoBytes: ByteArray?) -> Unit)?)

    /** Pause/resume frame sampling (scene votes + detections) without tearing
     *  down the ARSession — used while the OutdoorGate reads INDOOR. */
    fun setFrameAnalysisEnabled(enabled: Boolean)

    /** Scene-vote sampling cadence: true (~750ms) for fast response near a
     *  gate transition, false for the slower steady-state cadence. iOS only;
     *  no-op on Android/Desktop. */
    fun setSceneVoteCadence(fast: Boolean)

    /** Fires with the current ceiling-plane detection state on every sampled
     *  AR frame (see [OutdoorGate.onCeilingPlane]). iOS only; never invoked
     *  elsewhere. Listener is called on the main thread. */
    fun setCeilingListener(listener: ((Boolean) -> Unit)?)

    /**
     * Fires ~1/sec with the coverage raycast fan's geo-resolved hits (where
     * rays LANDED, with surface distance) while a geo-reference is paired.
     * Listener runs on the main thread; iOS only. Feed straight into
     * [CoverageTracker.updateWithRaycastHits].
     */
    fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?)

    /** Drop a persistent world-anchored marker at a lat/lng (no-op off-iOS,
     *  and before the first geo-reference pairing exists). */
    fun addWorldMarker(lat: Double, lng: Double, kind: WorldMarkerKind)
}

/**
 * Hosts the platform AR view that [ArRouteOverlay] renders into.
 *
 * iOS: a Compose `UIKitView` wrapping an `ARSCNView`, bound to [overlay] so
 * boundary updates land on live SceneKit nodes.
 * Android/Desktop: an empty placeholder — on-screen AR is iOS-only for v1.
 */
@Composable
expect fun ArRouteOverlayView(overlay: ArRouteOverlay, modifier: Modifier)
