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
 * A fully-scanned ground candidate whose appearance the classifier could not
 * name and that the user hasn't tapped — parked by [GroundCandidateTracker]
 * in its awaiting-intent stage and surfaced as an "is this litter?" prompt.
 * The reticle stays on screen (full) as the prompt's visual anchor. One
 * prompt at a time; unanswered prompts self-dismiss after
 * [INTENT_PROMPT_TIMEOUT_SECONDS].
 */
data class GroundIntentPrompt(
    val id: Long,
    /** Camera distance at last observation — copy hint only. */
    val distanceM: Float? = null,
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

    /** True while the session recorder is encoding video at the reduced
     *  thermal tier (device at/above `.serious` — degrade, then warn). Drives
     *  the "Phone hot — 3D capture reduced" chip; informational only, never
     *  gates capture (the Swift recorder owns the degrade decision). Always
     *  false off-iOS and on the SceneKit fallback. */
    val captureVideoDegraded: StateFlow<Boolean>

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

    /** The one pending "is this litter?" prompt for a fully-scanned,
     *  appearance-unknown ground candidate. Always null off-iOS. */
    val groundIntentPrompt: StateFlow<GroundIntentPrompt?>

    /** Items a tap SWEEP found beyond the tapped point (objectness regions
     *  that geo-resolved and survived the fixture/backdrop veto) — offered
     *  as one batch confirmation. Null when the last tap swept nothing. */
    val tapSweep: StateFlow<TapSweepState?>

    /** Confirm-all: returns the sweep's items (caller files them) and clears. */
    fun consumeTapSweep(): List<TapSweepItem>

    /** Dismiss the sweep offer without filing anything. */
    fun dismissTapSweep()

    /** A drawn rectangle (normalized view corners) marking trash — the
     *  drawing IS the confirmation; the region files directly with its box
     *  for backend analysis. */
    fun markRegion(x0: Double, y0: Double, x1: Double, y1: Double)

    /** The last drawn region's resolved item (world + box), published once
     *  the tick thread geo-resolves it. Consume-once via [consumeMarkedRegion]. */
    val markedRegion: StateFlow<TapSweepItem?>

    fun consumeMarkedRegion(): TapSweepItem?

    /** Plant a persistent green "confirmed trash" AR disc at an AR-world
     *  point — every confirm path (tap, sweep, drawn, auto) anchors one so
     *  the spot stays visibly marked for the rest of the session
     *  (Blake 2026-07-29). */
    fun addConfirmedTrashMarker(worldX: Float, worldY: Float, worldZ: Float)

    /** Project the server's known dirty observations into AR as pulsing
     *  orange "expected trash" discs — a RETURNING scan renders yesterday's
     *  finds in place, so verifying "still there / now clean" is a
     *  look-at-the-spot check instead of map reading. Geo-ref-gated
     *  internally (queued until the pairing lands); duplicate lat/lngs are
     *  planted once per session. */
    fun syncExpectedTrashMarkers(points: List<Pair<Double, Double>>)

    /** Push remote ScanTuning sensor caps down to the LiDAR detector. */
    fun setGroundSensorCaps(maxFootprintM: Float, maxHeightM: Float)

    /** The user answered "yes, litter" on [groundIntentPrompt]: the candidate
     *  completes through the normal filing path (evidence photo, geo-ref,
     *  weight) with user-intent provenance. No-op when nothing is pending. */
    /** @param itemType optional user-assigned label from the prompt's chips /
     *   "Other…" (display label; canonicalized at the item-build site).
     *  @param labelSource "user_chip" | "user_text" when [itemType] is set. */
    fun confirmGroundIntentPrompt(itemType: String? = null, labelSource: String? = null)

    /** The user answered "not litter" on [groundIntentPrompt]: terminal
     *  dismissal, reticle cleared, nothing files. No-op when nothing pending. */
    fun rejectGroundIntentPrompt()

    /** Best-effort current-frame grab to attach as evidence to a tap
     *  confirmation — a full [CapturedPhoto] so it can join the report's
     *  photo set. Null when no frame is available (and always off-iOS). */
    suspend fun captureTapEvidencePhoto(): CapturedPhoto?

    /**
     * Pair the worker's latest GPS fix with the AR camera's current position.
     * Returns the recorded pairing (both clocks + both coordinate frames) so
     * the caller can persist it into the session manifest — the DGX splat
     * pipeline needs these events to place the AR-local pose stream into the
     * world splat layer (see [com.gcc.shared.model.GeoRefEvent]). [headingDegrees]
     * and [headingAccuracyDegrees] are the device compass reading at the same
     * instant, carried through onto the returned event. Null when no AR
     * camera frame exists yet (nothing was paired).
     */
    fun updateGeoReference(
        lat: Double,
        lng: Double,
        accuracyMeters: Double? = null,
        headingDegrees: Double? = null,
        headingAccuracyDegrees: Double? = null,
    ): com.gcc.shared.model.GeoRefEvent?

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

    /**
     * Fires when a ground candidate (Task 4's [GroundCandidateTracker]) reaches
     * 100% scan progress and is retired to COMPLETED — the payload carries
     * everything needed to file it as a confirmed litter item (world position,
     * physical extents, classified [CompletedCandidate.itemType], and the
     * image-space crop geometry for a follow-up photo). [lat]/[lng] are
     * [CompletedCandidate.x]/[CompletedCandidate.z] resolved through the
     * session's live geo reference — the same `localToLatLng` conversion
     * [setElementObservedListener]'s registration path uses — because
     * [GroundCandidateTracker] is deliberately geo-ref-free (no platform
     * imports, per its class doc), so the conversion has to happen here at
     * the iOS invocation site instead of inside [CompletedCandidate] itself.
     * Only fires once a geo reference is paired and the conversion is
     * finite; a candidate that completes before pairing or resolving to a
     * non-finite coordinate is dropped (mirrors
     * [setElementObservedListener]'s `hasGeoRef` + finiteness guards).
     * [photo] is a best-effort silent frame grab (the same
     * `captureFrame` seam CameraManager's external frame source uses) taken
     * at the first close-range SCANNING tick (completion-time fallback) —
     * the item's photo per spec §4, a full [CapturedPhoto] so a user-intent
     * completion can also land in the report's photo set; null when no
     * frame could be captured. Called on the main thread; iOS only. No-op
     * on Android/Desktop — there is no LiDAR ground-candidate pipeline off
     * the native RealityKit surface.
     */
    fun setGroundCandidateCompletedListener(
        listener: ((lat: Double, lng: Double, photo: CapturedPhoto?, candidate: CompletedCandidate) -> Unit)?,
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
     * not-yet-drained finished chunk alongside the session's captureHealth
     * JSON (splat-capture reliability spec, Task 5) — null off-iOS/no
     * recorder/capture never started. Bounded ~5s (video writer finalize) —
     * callers MUST invoke this from a background/Default coroutine, never
     * the main thread, and BEFORE tearing the overlay down via [stop].
     */
    fun stopCapture(): Pair<List<CaptureChunkInfo>, String?>

    /** Privacy path (INDOOR gate flip): discard the in-progress chunk
     *  entirely (~60s over-purge, deliberate) rather than trim it. Callers
     *  debounce by [OutdoorGate.CAPTURE_DISCARD_DEBOUNCE_MS] — appends are
     *  already paused via [setCaptureEnabled] the moment the gate leaves
     *  OUTDOOR, so waiting out a false INDOOR flip records nothing indoor. */
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
