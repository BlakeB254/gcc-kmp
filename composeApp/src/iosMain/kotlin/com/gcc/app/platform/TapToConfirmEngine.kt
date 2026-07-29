package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.ARKit.ARFrame
import platform.ARKit.ARHitTestResult
import platform.ARKit.ARHitTestResultTypeEstimatedHorizontalPlane
import platform.ARKit.ARHitTestResultTypeExistingPlaneUsingGeometry
import platform.ARKit.ARHitTestResultTypeFeaturePoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import com.gcc.shared.geo.GeofenceVerification
import kotlinx.cinterop.useContents
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNGenerateObjectnessBasedSaliencyImageRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRectangleObservation
import platform.Vision.VNSaliencyImageObservation
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.sqrt

/** World hit for a normalized image-space point. Moved verbatim from
 *  ArEnvScanOverlay (was private there) — both AR overlays raycast taps
 *  through the one engine below. */
internal class RayHit(val x: Float, val y: Float, val z: Float, val distance: Float)

/** Crop side (fraction of the frame) for tap-to-identify classification. */
private const val TAP_CROP_FRACTION = 0.35

/**
 * THE tap-to-confirm flow (env-scan PRD Option A), shared by
 * [ArEnvScanOverlay] and [ArRouteOverlay] — the block PRD forbids the two
 * AR surfaces forking, so this is the env scan's tap code MOVED, not copied:
 * queued normalized-view tap → image-space inversion → raycast (native
 * RealityKit or SceneKit ARFrame.hitTest) → geo-resolve via the caller's
 * live geo reference → Vision region classify → [TapCandidateState]
 * reducer. Nothing persists until [confirm]; a raycasting tap ALWAYS
 * reaches AWAITING_CONFIRMATION.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TapToConfirmEngine(
    private val nativeSurfaceProvider: () -> ArNativeSurface?,
    private val localToLatLng: (x: Float, z: Float) -> Pair<Double, Double>?,
    /** Fires immediately after a successful raycast, BEFORE the
     *  [localToLatLng] geo-resolve check — i.e. on both the geo-resolved
     *  and the [TapEvent.NoGeoRef] paths. Preserves the original
     *  `processPendingFocusTap`'s behavior of setting the focus-bias fields
     *  (`focusImagePoint`/`focusWorld`/`focusUntilMs`) unconditionally on
     *  any surface hit, even before the session's GPS<->AR pairing exists. */
    private val onRaycastHit: ((hit: RayHit, imageX: Double, imageY: Double) -> Unit)? = null,
    /** Fires after the geo-resolve succeeds AND after tap-region
     *  classification — the same point the original code ran its
     *  ground-candidate `adoptTap` block, which only ever ran on the
     *  geo-resolved path (it was gated behind the same `NoGeoRef` early
     *  return) and only after `classifyTappedRegion`. */
    private val onTapGeoResolved: ((hit: RayHit) -> Unit)? = null,
) {
    private val _tapCandidate = MutableStateFlow(TapCandidateState())
    val tapCandidate: StateFlow<TapCandidateState> = _tapCandidate.asStateFlow()

    /** Last-write-wins single slot; a following tap supersedes (same
     *  threading contract the env overlay documented on focusAt). */
    private var pendingFocusTap: Pair<Double, Double>? = null

    fun queueTap(normalizedX: Float, normalizedY: Float) {
        pendingFocusTap = normalizedX.toDouble() to normalizedY.toDouble()
        _tapCandidate.update { it.reduce(TapEvent.Tapped(normalizedX, normalizedY)) }
    }

    /** Drawn-region marking (Blake 2026-07-28: draw a shape where the trash
     *  is instead of answering type popups). Same last-write-wins main→tick
     *  handoff as [pendingFocusTap]; corners are normalized VIEW coords. */
    private var pendingMarkRegion: DoubleArray? = null

    private val _markedRegion = MutableStateFlow<TapSweepItem?>(null)
    val markedRegion: StateFlow<TapSweepItem?> = _markedRegion.asStateFlow()

    fun queueMarkRegion(x0: Double, y0: Double, x1: Double, y1: Double) {
        pendingMarkRegion = doubleArrayOf(x0, y0, x1, y1)
    }

    fun consumeMarkedRegion(): TapSweepItem? {
        val item = _markedRegion.value
        _markedRegion.value = null
        return item
    }

    /** Tick-thread processing of a drawn region: view→image corners via the
     *  aspect-correct converter, raycast the center for world/geo, classify
     *  the crop for a HINT label (backend owns real typing), and publish —
     *  the drawing itself is the confirmation, so no further UI gate. Null
     *  result (no surface / no geo) publishes nothing; the caller's HUD
     *  stays silent rather than blocking a walk with an error dialog. */
    private fun processPendingMarkRegion(frame: ARFrame?) {
        val region = pendingMarkRegion ?: return
        pendingMarkRegion = null
        val surface = nativeSurfaceProvider()
        fun toImage(x: Double, y: Double): Pair<Double, Double> {
            val converted = surface?.viewToImagePoint(x, y)
            return if (converted != null && converted.size == 2) converted[0] to converted[1]
            else viewTapToImageCoords(x, y)
        }
        val (ix0, iy0) = toImage(region[0], region[1])
        val (ix1, iy1) = toImage(region[2], region[3])
        val cx = ((ix0 + ix1) / 2.0).coerceIn(0.0, 1.0)
        val cy = ((iy0 + iy1) / 2.0).coerceIn(0.0, 1.0)
        val halfW = (kotlin.math.abs(ix1 - ix0) / 2.0).coerceIn(0.02, 0.5)
        val halfH = (kotlin.math.abs(iy1 - iy0) / 2.0).coerceIn(0.02, 0.5)
        val hit = raycastImage(frame, cx, cy) ?: return
        val latLng = localToLatLng(hit.x, hit.z) ?: return
        val labels = classifyRegion(frame, cx, cy, fraction = (halfW * 2).coerceIn(0.1, 0.6))
        dispatch_async(dispatch_get_main_queue()) { triggerHapticFeedback() }
        _markedRegion.value = TapSweepItem(
            lat = latLng.first,
            lng = latLng.second,
            distanceMeters = hit.distance,
            imageCx = cx, imageCy = cy,
            imageHalfW = halfW, imageHalfH = halfH,
            viewX = (region[0] + region[2]) / 2.0,
            viewY = (region[1] + region[3]) / 2.0,
            label = TrashDetectionRules.trashLabelFrom(labels),
        )
    }

    private val _tapSweep = MutableStateFlow<TapSweepState?>(null)
    val tapSweep: StateFlow<TapSweepState?> = _tapSweep.asStateFlow()

    fun consumeTapSweep(): List<TapSweepItem> {
        val items = _tapSweep.value?.items.orEmpty()
        _tapSweep.value = null
        return items
    }

    fun dismissTapSweep() {
        _tapSweep.value = null
    }

    fun processPendingTap(frame: ARFrame?) {
        processPendingMarkRegion(frame)
        val tap = pendingFocusTap ?: return
        pendingFocusTap = null
        // Aspect-correct view→image mapping via ARKit's displayTransform when
        // the native surface is live; the legacy rotation-only math ignored
        // the aspect-fill crop and mislocated taps by up to ~19% of the frame
        // ("selected an area I didn't tap", build 170). Fallback keeps the
        // SceneKit path working.
        val converted = nativeSurfaceProvider()?.viewToImagePoint(tap.first, tap.second)
        val (imageX, imageY) = if (converted != null && converted.size == 2) {
            converted[0] to converted[1]
        } else {
            viewTapToImageCoords(tap.first, tap.second)
        }
        val hit = raycastImage(frame, imageX, imageY)
        if (hit == null) {
            // Forced evaluation (Blake 2026-07-28): even a tap that can't
            // PLACE still EVALUATES — classify the crop and let the pill
            // report what the camera saw ("Looks like: Bottle — aim at the
            // ground..."). Same below for the no-geo-ref path.
            _tapCandidate.update { it.reduce(TapEvent.RaycastMissed) }
            classifyTapped(frame, imageX, imageY)
            return
        }
        onRaycastHit?.invoke(hit, imageX, imageY)
        val latLng = localToLatLng(hit.x, hit.z) ?: run {
            // Surface hit, no AR↔GPS pairing yet — honest copy, not
            // "hold steady" (TapPhase.GPS_SYNCING).
            _tapCandidate.update { it.reduce(TapEvent.NoGeoRef) }
            classifyTapped(frame, imageX, imageY)
            return
        }
        _tapCandidate.update {
            it.reduce(TapEvent.RaycastHit(latLng.first, latLng.second, hit.distance))
        }
        dispatch_async(dispatch_get_main_queue()) { triggerHapticFeedback() }
        classifyTapped(frame, imageX, imageY)
        onTapGeoResolved?.invoke(hit)
        // Tap SWEEP (Blake 2026-07-28): one tap should identify as many
        // items in view as possible. Objectness saliency proposes regions
        // across the whole frame; each region must geo-resolve and survive
        // a fixture/backdrop veto to join the batch. ≥2 survivors surface
        // as a "Found N items — confirm all" flow; otherwise the normal
        // single-candidate pill stands alone.
        sweepFrame(frame, tappedLat = latLng.first, tappedLng = latLng.second)
    }

    /** Max sweep regions offered per tap — precision beats volume in a batch confirm. */
    private val sweepMaxItems = 5

    /** Sweep items closer than this (meters, world) to each other or to the
     *  tapped point collapse — one region per physical item. Matches the
     *  retap-dedup radius in ObservedItemCounts. */
    private val sweepDedupMeters = 0.6f

    private fun sweepFrame(frame: ARFrame?, tappedLat: Double, tappedLng: Double) {
        val pixelBuffer = nativeSurfaceProvider()?.pixelBufferOrNull() ?: frame?.capturedImage ?: return
        val saliency = VNGenerateObjectnessBasedSaliencyImageRequest()
        val handler = VNImageRequestHandler(cVPixelBuffer = pixelBuffer, options = emptyMap<Any?, Any>())
        handler.performRequests(listOf(saliency), error = null)
        val boxes = saliency.results
            ?.filterIsInstance<VNSaliencyImageObservation>()
            ?.firstOrNull()
            ?.salientObjects
            ?.filterIsInstance<VNRectangleObservation>()
            ?: return

        val accepted = mutableListOf<TapSweepItem>()
        val acceptedWorld = mutableListOf<Pair<Float, Float>>()
        for (box in boxes) {
            if (accepted.size >= sweepMaxItems) break
            box.boundingBox.useContents {
                val cx = origin.x + size.width / 2.0
                val cyTopDown = 1.0 - (origin.y + size.height / 2.0) // Vision box is bottom-left origin
                val halfW = (size.width / 2.0).coerceAtLeast(0.02)
                val halfH = (size.height / 2.0).coerceAtLeast(0.02)
                val hit = raycastImage(frame, cx, cyTopDown) ?: return@useContents
                if (hit.distance > TAP_IDENTIFY_MAX_METERS) return@useContents
                val latLng = localToLatLng(hit.x, hit.z) ?: return@useContents
                // World-dedup among sweep survivors and against the tapped
                // point itself (the single-candidate pill already owns it).
                val tooClose = acceptedWorld.any { (ax, az) ->
                    val dx = ax - hit.x
                    val dz = az - hit.z
                    dx * dx + dz * dz < sweepDedupMeters * sweepDedupMeters
                }
                if (tooClose) return@useContents
                val tappedDx = GeofenceVerification.distanceMeters(tappedLat, tappedLng, latLng.first, latLng.second)
                if (tappedDx < sweepDedupMeters) return@useContents
                // Fixture/backdrop veto on the region crop — a sweep must not
                // batch-offer bushes, poles or bare ground.
                val labels = classifyRegion(frame, cx, cyTopDown, fraction = (halfW * 2.6).coerceIn(0.08, 0.5))
                val decision = GroundExclusionRules.classify(labels)
                if (decision.exclusionLabel != null || decision.backdropLabel != null) return@useContents
                val viewPoint = nativeSurfaceProvider()?.imageToViewPoint(cx, cyTopDown)
                accepted.add(
                    TapSweepItem(
                        lat = latLng.first,
                        lng = latLng.second,
                        distanceMeters = hit.distance,
                        imageCx = cx, imageCy = cyTopDown,
                        imageHalfW = halfW, imageHalfH = halfH,
                        viewX = viewPoint?.getOrNull(0) ?: cx,
                        viewY = viewPoint?.getOrNull(1) ?: cyTopDown,
                        label = TrashDetectionRules.trashLabelFrom(labels),
                    ),
                )
                acceptedWorld.add(hit.x to hit.z)
            }
        }
        _tapSweep.value = if (accepted.isEmpty()) null else TapSweepState(accepted)
    }

    fun confirm(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Confirmed) }
        return pending
    }

    fun reject(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Rejected) }
        return pending
    }

    fun dismiss() {
        _tapCandidate.update { it.reduce(TapEvent.Dismissed) }
    }

    /**
     * Classify a crop centred on the tap, preserving surrounding context, and
     * feed the result to the reducer. A tap bypasses
     * [TrashDetectionRules.autoPromotionLabelFrom]'s parent-context suppression
     * on purpose (constraint 11): the user may confirm a detached tire.
     *
     * A null label is NOT a failure — Option A: the candidate still reaches
     * AWAITING_CONFIRMATION as "Unknown litter".
     */
    private fun classifyTapped(frame: ARFrame?, imageX: Double, imageY: Double) {
        val observations = classifyRegion(frame, imageX, imageY, TAP_CROP_FRACTION)
        val label = TrashDetectionRules.trashLabelFrom(observations)
        val confidence = label?.let { TrashDetectionRules.trashConfidenceFor(it, observations) }
        _tapCandidate.update {
            it.reduce(
                TapEvent.Classified(
                    label, confidence,
                    // The evaluated crop, for the Stage-2 training box on a
                    // confirmed tap (ObservedSpotItem.imageCx/…).
                    imageCx = imageX, imageCy = imageY, imageHalf = TAP_CROP_FRACTION / 2.0,
                ),
            )
        }
    }

    /**
     * World hit for a normalized image-space point, via whichever backend is
     * live: native RealityKit raycast (distance derived from the camera
     * position) or the SceneKit-era ARFrame.hitTest.
     *
     * `internal` (not `private`): [ArEnvScanOverlay.analyzeTrash] and
     * [ArEnvScanOverlay.registerElementIfAny] also raycast a salient-object
     * hit for the whole-frame auto-detect path — same one implementation,
     * multiple callers precedent as [classifyRegion] below.
     */
    internal fun raycastImage(frame: ARFrame?, imageX: Double, imageY: Double): RayHit? {
        nativeSurfaceProvider()?.let { surface ->
            val hit = surface.raycast(imageX, imageY) ?: return null
            val cam = surface.cameraPosition() ?: return null
            val dx = hit[0] - cam[0]
            val dy = hit[1] - cam[1]
            val dz = hit[2] - cam[2]
            return RayHit(
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
        return RayHit(world[0], world[1], world[2], hit.distance.toFloat())
    }

    /**
     * Classify a square region-of-interest centred on a normalized image-space
     * point (top-left origin, matching [imageX]/[imageY] everywhere else in
     * this file) — the tap-to-identify crop [classifyTapped] needs, as
     * opposed to whole-frame classification. Supports both render backends,
     * same precedent as [saliencyOnDemand]/[ArFrameFeedback]'s
     * `native?.pixelBufferOrNull() ?: frame?.capturedImage` pattern: the
     * native RealityKit surface when bound, the SceneKit `ARFrame` otherwise.
     */
    internal fun classifyRegion(
        frame: ARFrame?,
        imageX: Double,
        imageY: Double,
        fraction: Double,
    ): List<ClassificationObservation> {
        val pixelBuffer = nativeSurfaceProvider()?.pixelBufferOrNull() ?: frame?.capturedImage ?: return emptyList()
        val side = fraction.coerceIn(0.0, 1.0)
        val half = side / 2.0
        // Vision's regionOfInterest is bottom-left origin; imageX/imageY here
        // are top-left, so flip Y (same conversion analyzeTrash uses). The
        // origin is clamped to [0, 1 - side] so origin + size never exceeds
        // 1.0 — an off-center tap slides the crop inward (keeping its full
        // side length) instead of handing Vision an out-of-bounds ROI.
        val visionY = 1.0 - imageY
        val roi = CGRectMake(
            (imageX - half).coerceIn(0.0, 1.0 - side),
            (visionY - half).coerceIn(0.0, 1.0 - side),
            side,
            side,
        )
        val request = VNClassifyImageRequest()
        request.regionOfInterest = roi
        val handler = VNImageRequestHandler(cVPixelBuffer = pixelBuffer, options = emptyMap<Any?, Any>())
        handler.performRequests(listOf(request), error = null)
        return request.results
            ?.filterIsInstance<VNClassificationObservation>()
            ?.map { ClassificationObservation(it.identifier, it.confidence) }
            ?: emptyList()
    }
}
