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
 * One finished background-capture artifact chunk (Pillar 1 session capture)
 * drained from the native recorder. Mirrors the Swift/iosMain
 * `"index|kind|path|sizeBytes"` packet convention (kind: video|sidecar|
 * depth) — see `ArNativeSurface.drainFinishedCaptureChunks`/
 * `stopCaptureAndDrain`. Android/Desktop never produce these (capture is
 * iOS-only in v1 — the scope guard in the session-capture plan).
 */
data class CaptureChunkInfo(
    val index: Long,
    val kind: String,
    val filePath: String,
    val sizeBytes: Long,
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

    /**
     * Every identified/salient item box in the current frame (Task 17) —
     * additive display layer alongside [trashCandidate]'s single lock-on
     * flow, which this never affects or replaces. Capped at
     * [MAX_MULTI_ITEM_RETICLES]. Always empty off-iOS.
     */
    val multiItemReticles: StateFlow<List<DetectionReticle>>

    /** The one pending user-tapped candidate. See [TapCandidateState]. */
    val tapCandidate: StateFlow<TapCandidateState>

    /**
     * Persist the pending tapped candidate as user-confirmed litter and reset.
     * Returns the state that was confirmed, or null if nothing was pending.
     */
    fun confirmTapCandidate(): TapCandidateState?

    /**
     * Record the pending tapped candidate as a negative training example and
     * reset. Returns the rejected state, or null if nothing was pending.
     */
    fun rejectTapCandidate(): TapCandidateState?

    /** Drop the pending tapped candidate. Records nothing (accidental tap). */
    fun dismissTapCandidate()

    /**
     * Pair the worker's latest GPS fix with the AR camera's current position.
     * Returns the recorded pairing (both clocks + both coordinate frames) so
     * the caller can persist it into the session manifest — the DGX splat
     * pipeline needs these events to place the AR-local pose stream into the
     * world splat layer (see [com.gcc.shared.model.GeoRefEvent]). Null when
     * no AR camera frame exists yet (nothing was paired).
     */
    fun updateGeoReference(lat: Double, lng: Double, accuracyMeters: Double? = null): com.gcc.shared.model.GeoRefEvent?

    /** Plant the frontier beacon at the next area worth scanning (a pooled
     *  ground ring in the camera view — the home-walkthrough-style "scan
     *  here" landmark; spec 2026-07-10 AR frontier beacons). No-op off-iOS. */
    fun setGuidanceTarget(lat: Double, lng: Double)

    /** Hide the frontier beacon (no frontier left, or no usable geo-ref). */
    fun clearGuidanceTarget()

    /** Fade the beacon out because its patch was properly scanned (reached
     *  CLOSE credit) — the reward cue, distinct from a plain target change.
     *  The next [setGuidanceTarget] re-shows it at the new frontier. */
    fun dissolveGuidanceBeacon()

    /** Hide/restore the beacon while a confirm flow owns the screen (tap
     *  reticle pill or lock-on card) — it must never compete with a
     *  confirmation for attention. */
    fun setGuidanceBeaconSuppressed(suppressed: Boolean)

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
     * User tapped the camera view at normalized view coords (0..1, top-left
     * origin). Biases detection at that region for a few seconds: immediate
     * raycast + saliency selection nearest the tap, and a lowered promotion
     * threshold near the tapped world point. No-op off-iOS.
     */
    fun focusAt(x: Float, y: Float)

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

    // ── Session capture (Pillar 1) ───────────────────────────────────
    // iOS-only (native RealityKit surface); no-op everywhere else. Every
    // failure must be caught by the CALLER (SpotScreen) and treated as
    // "capture disabled for this session" — never let it interrupt the scan.

    /**
     * Begin full-session background capture (HEVC video chunks + pose/
     * intrinsics sidecar + LiDAR depth keyframes) for [sessionId]. Returns
     * true only when capture GENUINELY started — false on the SceneKit
     * fallback, before a geo reference pairs, on any native failure, and
     * always on Android/Desktop. Callers must only flip their recording
     * state (REC chip) on true.
     */
    fun startCapture(sessionId: String): Boolean

    /**
     * Stop capture, finalize the in-progress chunk, and return every
     * not-yet-drained finished chunk. Bounded ~5s (video writer finalize) —
     * callers MUST invoke this from a background/Default coroutine, never
     * the main thread, and BEFORE tearing the overlay down via [stop].
     */
    fun stopCapture(): List<CaptureChunkInfo>

    /** Privacy path (INDOOR gate flip): discard the in-progress chunk
     *  entirely (~60s over-purge, deliberate) rather than trim it. */
    fun discardCurrentCaptureChunk()

    /** Pause/resume capture without discarding the in-progress chunk —
     *  outdoor-gate driven, mirrors [setFrameAnalysisEnabled]. */
    fun setCaptureEnabled(enabled: Boolean)

    /** Advisory: true when the device thermal state is at/above critical —
     *  the level at which the native recorder auto-pauses appends (Task 4
     *  thermal policy). Read on the caller's cadence to tint the REC chip
     *  "paused"; it does NOT drive capture behavior (the Swift recorder owns
     *  that) and may lag the recorder's actual pause by a tick. Always false
     *  off-iOS. */
    fun thermalPausedLikely(): Boolean

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
