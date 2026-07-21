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

    /**
     * Pair the worker's latest GPS fix with the AR camera's current position.
     * Returns the recorded pairing (both clocks + both coordinate frames) so
     * the caller can persist it into the lap's session manifest — the DGX
     * splat pipeline registers the lap's pose stream into the world splat
     * layer with these (see [com.gcc.shared.model.GeoRefEvent]). [headingDegrees]
     * and [headingAccuracyDegrees] are the device compass reading at the same
     * instant, carried through onto the returned event. Null when no AR
     * camera frame exists yet.
     */
    fun updateGeoReference(
        lat: Double,
        lng: Double,
        accuracyMeters: Double? = null,
        headingDegrees: Double? = null,
        headingAccuracyDegrees: Double? = null,
    ): com.gcc.shared.model.GeoRefEvent?

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

    // ── Background session capture (route-lap parity with the env scan) ──
    // Same reconstruction-grade recorder the environment scan runs (HEVC
    // video + per-frame pose sidecar + LiDAR depth keyframes, spooled as
    // rotating chunks): the route lap's walk IS raw multi-view input for the
    // DGX world-splat pipeline, and until this seam existed block-route laps
    // recorded no video/depth/pose at all (known gap, closed 2026-07-11).
    // iOS native (RealityKit) surface only: SceneKit fallback and
    // Android/Desktop return false / no-op — callers key the REC chip off
    // startCapture's honest return value.

    /** Begin background capture for this lap. False = capture did NOT start
     *  (no native surface, no geo-ref yet, or a native failure). */
    fun startCapture(sessionId: String): Boolean

    /** Stop capture, finalize the in-progress chunk, book every not-yet-
     *  drained chunk for upload, and return them alongside the session's
     *  captureHealth JSON (splat-capture reliability spec, Task 5) — null
     *  off-iOS/no recorder/capture never started. Call BEFORE the lap
     *  manifest is built so chunk keys can ride it. */
    fun stopCapture(): Pair<List<CaptureChunkInfo>, String?>

    /** Privacy path (INDOOR gate flip): delete the in-progress chunk's files
     *  entirely (≤60s over-purge, deliberate). */
    fun discardCurrentCaptureChunk()

    /** Pause/resume capture without teardown (outdoor-gate driven). */
    fun setCaptureEnabled(enabled: Boolean)

    // ── Tap-to-confirm (env-scan parity, block PRD lap-1 requirement) ──
    // Same TapToConfirmEngine the env scan runs — the block PRD forbids the
    // AR surfaces forking. iOS only; Android/Desktop expose an idle flow.

    /** The one pending user-tapped candidate. See [TapCandidateState]. */
    val tapCandidate: StateFlow<TapCandidateState>

    /** Queue a normalized view-space tap (top-left origin) for raycast +
     *  identify. A tap is NEVER silent — every outcome surfaces a pill. */
    fun focusAt(x: Float, y: Float)

    /** Persist the pending tapped candidate as user-confirmed litter and
     *  reset. Returns the confirmed state, or null if nothing was pending. */
    fun confirmTapCandidate(): TapCandidateState?

    /** Record the pending tapped candidate as a negative example and reset. */
    fun rejectTapCandidate(): TapCandidateState?

    /** Drop the pending tapped candidate. Records nothing (accidental tap). */
    fun dismissTapCandidate()

    /** Best-effort downscaled JPEG of the current frame — the confirmed
     *  item's hotspot before-photo / marker photo. Null when no frame
     *  (and always off-iOS). */
    suspend fun captureTapEvidencePhoto(): ByteArray?
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
