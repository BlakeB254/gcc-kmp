package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.ARKit.ARCoachingGoal
import platform.ARKit.ARCoachingOverlayView
import platform.ARKit.ARFrame
import platform.ARKit.ARHitTestResult
import platform.ARKit.ARHitTestResultTypeEstimatedHorizontalPlane
import platform.ARKit.ARHitTestResultTypeExistingPlaneUsingGeometry
import platform.ARKit.ARHitTestResultTypeFeaturePoint
import platform.ARKit.ARPlaneAnchor
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.valueForKey
import platform.SceneKit.SCNAction
import platform.SceneKit.SCNLookAtConstraint
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNMatrix4MakeScale
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPlane
import platform.SceneKit.SCNPyramid
import platform.SceneKit.SCNTorus
import platform.SceneKit.SCNVector3Make
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNGenerateObjectnessBasedSaliencyImageRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRectangleObservation
import platform.Vision.VNSaliencyImageObservation
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.simd_float4x4
import com.gcc.shared.cache.CaptureChunkRecord
import com.gcc.shared.cache.CaptureChunkStore
import com.gcc.app.platform.capture.CaptureChunkUploader
import kotlinx.datetime.Clock
import org.koin.mp.KoinPlatform
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ARKit-backed environment-scan surface (see commonMain KDoc).
 *
 * Same architecture decisions as [ArRouteOverlay]/[ArFrameFeedback]:
 * SceneKit over RealityKit (Swift-only API doesn't bridge through K/N ObjC
 * interop), and a 1s tick loop over `session.currentFrame` instead of
 * ARSCNViewDelegate node callbacks — the delegate's `renderer:didAdd/
 * didUpdate/didRemove` overloads differ only by ObjC parameter name, which
 * Kotlin cannot overload on, while polling `frame.anchors` gets the same
 * plane data with zero interop hazard at the cadence we already sample.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ArEnvScanOverlay actual constructor() {

    companion object {
        private const val TICK_MS = 1_000L
        private const val FAST_TICK_MS = 750L
        private const val ERROR_BACKOFF_MS = 3_000L
        private const val SCENE_VOTE_EVERY_TICKS = 2
        private const val DISABLED_VOTE_EVERY_TICKS = 2
        /** Candidate promotion: this close, for this many consecutive ticks. */
        private const val NEAR_DISTANCE_METERS = 2.0
        private const val NEAR_STREAK_TICKS = 2
        /** Don't re-offer a candidate within this range of a dismissed/confirmed one. */
        private const val SUPPRESS_RADIUS_METERS = 2.0
        /** Tap-to-focus (Task 5): how long a [focusAt] tap biases detection. */
        private const val FOCUS_DURATION_MS = 5_000L
        /** Tap-to-focus: promotion streak relaxes to 1 tick for a candidate
         *  hit within this XZ-plane distance of the tapped world point.
         *  NEAR_DISTANCE_METERS (the range gate) is unchanged by focus. */
        private const val FOCUS_RADIUS_METERS = 1.5
        /** Side of the square crop classified around a tap, as a fraction of the
         *  image's smaller dimension. Wide enough to keep context (a bottle needs
         *  the ground around it), tight enough to exclude the rest of the scene. */
        private const val TAP_CROP_FRACTION = 0.35
        /** Grid squares stay this size no matter how large the plane grows.
         *  1.5m = a clean divisor of CoverageTracker's 3m cells; with the
         *  world-axis alignment below, the on-camera lattice reads as the
         *  same grid the north-up minimap draws. */
        private const val GRID_TILE_METERS = 1.5f
        /** Fallback ground offset when no floor plane is detected yet. */
        private const val GROUND_OFFSET_METERS = 1.4f
        /** Landmark elements: registration range, per-type dedupe, session cap. */
        private const val ELEMENT_MAX_DISTANCE_METERS = 12.0
        private const val ELEMENT_DEDUPE_METERS = 4.0
        private const val MAX_ELEMENTS_PER_SESSION = 60
    }

    private var sceneView: ARSCNView? = null
    /** Native RealityKit render surface — when set, SceneKit paths are inert. */
    private var nativeSurface: ArNativeSurface? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var tick = 0
    private var analysisEnabled = true

    /** Scene-vote cadence: true samples every tick (~750ms), false the
     *  [SCENE_VOTE_EVERY_TICKS]/[DISABLED_VOTE_EVERY_TICKS] cadence at the
     *  slower [TICK_MS] tick. Set via `setSceneVoteCadence`. */
    var fastSceneVotes: Boolean = true

    /**
     * Session-capture drain hook (Pillar 1): finished capture-chunk packets
     * ("index|kind|path|sizeBytes", see [ArNativeSurface.drainFinishedCaptureChunks])
     * polled from the native recorder on the existing tick cadence and handed
     * here on the tick coroutine (Dispatchers.Default). Task 5 assigns the
     * listener (bookkeeping insert + upload kick); until then — and always on
     * the SceneKit fallback path — the drain is skipped entirely.
     */
    var captureChunkListener: ((List<String>) -> Unit)? = { packets -> handleFinishedCaptureChunks(packets) }

    /** Session id passed to [startCapture]; null when capture isn't running. */
    private var captureSessionId: String? = null
    /** `gridCell10m` of the session anchor at [startCapture] time — the same
     *  cell id format the R2/world-model layer expects ("int:int"). */
    private var captureLocationCellId: String? = null

    private val sessionHealth = ArSessionHealth()
    actual val trackingState: StateFlow<TrackingState> = sessionHealth.trackingState

    private val _groundDetected = MutableStateFlow(false)
    actual val groundDetected: StateFlow<Boolean> = _groundDetected.asStateFlow()

    private val _trashCandidate = MutableStateFlow<TrashCandidate?>(null)
    actual val trashCandidate: StateFlow<TrashCandidate?> = _trashCandidate.asStateFlow()

    private val _tapCandidate = MutableStateFlow(TapCandidateState())
    actual val tapCandidate: StateFlow<TapCandidateState> = _tapCandidate.asStateFlow()

    // GPS <-> AR pairing, same model as ArRouteOverlay.
    private var hasGeoRef = false
    private var refLat = 0.0
    private var refLng = 0.0
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    private var lngScale = 1.0

    // Geographic origin of the CoverageTracker cell lattice (its padded SW
    // corner). Converted through the geo reference into [gridPhase] so the
    // ground grid can snap its lines onto the same lattice the minimap draws.
    private var latticeLat: Double? = null
    private var latticeLng: Double? = null
    /** AR-local lattice phase (x, z) — null until origin AND geo-ref exist. */
    private var gridPhase: Pair<Double, Double>? = null

    // Ground plane: SINGLE grid node covering the monotonic union of every
    // floor-classified ARPlaneAnchor's world AABB ever observed — never
    // replaced, never shrunk. Mirrors GccArNative.swift's floorGrid
    // (RealityKit path): this SceneKit fallback used to keep one grid PER
    // anchor (planeNodes.getOrPut(id)), which stacked overlapping
    // semi-transparent grids as ARKit split/merged/re-detected planes.
    // Furniture ("rough") grids are no longer rendered either, matching the
    // RealityKit path's "ONE ground grid" contract (spec system 2).
    private var floorGridNode: SCNNode? = null
    private var floorGridLastSize: Pair<Double, Double>? = null
    private var floorMinX: Double? = null
    private var floorMaxX: Double? = null
    private var floorMinZ: Double? = null
    private var floorMaxZ: Double? = null
    /** World Y of the lowest floor-classified plane (vector cells sit on it). */
    private var floorY: Float? = null

    // Guidance arrow toward the next area worth scanning.
    private var guidanceLatLng: Pair<Double, Double>? = null
    private var arrowNode: SCNNode? = null
    private var frontierTarget: SCNNode? = null

    // Trash lock-on state.
    private var nearStreak = 0
    private var candidateCounter = 0
    private var pendingWorld: Triple<Float, Float, Float>? = null
    private val suppressedWorld = mutableListOf<Triple<Float, Float, Float>>()
    private val markerPositions = mutableListOf<Triple<Float, Float, Float>>()

    // Tap-to-focus (Task 5). A tap arrives on the gesture/main thread via
    // [focusAt]; it's queued here and processed at the START of the next
    // [analyzeTick] (on the Vision/analysis coroutine) rather than raycast
    // synchronously off the tap — Vision/raycast work belongs on that queue.
    /** Queued view-space tap awaiting the next tick; consumed then nulled. */
    private var pendingFocusTap: Pair<Double, Double>? = null
    /** Image-space point (top-left origin) the active focus raycast used —
     *  reused by [selectSalientObject] to bias candidate selection. */
    private var focusImagePoint: Pair<Double, Double>? = null
    /** World point of the focus raycast hit; null when no focus is active. */
    private var focusWorld: Triple<Float, Float, Float>? = null
    /** Focus expires when `now > focusUntilMs` — no timer, checked inline. */
    private var focusUntilMs: Long = 0L

    // Landmark elements registered this session: (itemType, x, z) for dedupe.
    private val registeredElements = mutableListOf<Triple<String, Float, Float>>()
    private var elementObservedListener: (
        (
            lat: Double, lng: Double, itemType: String, domain: String, confidence: Float,
            widthMeters: Double?, yawDegrees: Double?,
        ) -> Unit
    )? = null
    private var ceilingListener: ((Boolean) -> Unit)? = null
    private var coverageHitListener: ((List<RaycastCoverageHit>) -> Unit)? = null

    private var floorGridTexture: UIImage? = null

    /**
     * The exact closure instance handed to [CameraManager.externalFrameSource]
     * (see [bind]/[bindNative]). [stop] must only clear the shared slot if it
     * still holds THIS instance's closure — if dispose ordering ever lets a
     * stale session's stop() run after a new session's bind() (Koin-singleton
     * VM + Compose UIKitView release timing aren't strictly ordered), an
     * unconditional clear would steal the new session's frame source and
     * captureFrame() would fall through to the UIImagePicker modal over a
     * live ARView.
     */
    private var frameSourceClosure: (suspend () -> CapturedPhoto?)? = null

    /** Called by the Compose UIKitView factory once the ARSCNView exists. */
    fun bind(view: ARSCNView) {
        sceneView = view
        val config = ARWorldTrackingConfiguration()
        config.worldAlignment = ARWorldAlignment.ARWorldAlignmentGravityAndHeading
        config.planeDetection = platform.ARKit.ARPlaneDetectionHorizontal
        // LiDAR depth fusion when available (same guard ArScanner uses).
        try {
            if (ARWorldTrackingConfiguration.supportsFrameSemantics(
                    platform.ARKit.ARFrameSemanticSmoothedSceneDepth
                )
            ) {
                config.frameSemantics = platform.ARKit.ARFrameSemanticSmoothedSceneDepth
            } else if (ARWorldTrackingConfiguration.supportsFrameSemantics(
                    platform.ARKit.ARFrameSemanticSceneDepth
                )
            ) {
                config.frameSemantics = platform.ARKit.ARFrameSemanticSceneDepth
            }
        } catch (e: Throwable) {
            DiagnosticsLog.error("env-ar", "frameSemantics probe failed (continuing without depth)", e)
        }
        view.session.runWithConfiguration(config)
        sessionHealth.bind(view.session)
        val closure: suspend () -> CapturedPhoto? = { captureFrame() }
        frameSourceClosure = closure
        CameraManager.externalFrameSource = closure
        startLoop()
        DiagnosticsLog.info("env-ar", "ARKit env-scan surface started (plane detection on)")
    }

    /**
     * Bind to a native RealityKit surface instead of an ARSCNView (see
     * [ArNativeRegistry]). The Swift side owns the session, ground grid and
     * effect rendering; this class keeps the identical Vision/candidate/
     * geo-ref loop, reading frames + raycasts through [ArNativeSurface].
     */
    fun bindNative(surface: ArNativeSurface) {
        nativeSurface = surface
        surface.setGroundGridVisible(true)
        val closure: suspend () -> CapturedPhoto? = { captureFrame() }
        frameSourceClosure = closure
        CameraManager.externalFrameSource = closure
        startLoop()
        DiagnosticsLog.info("env-ar", "RealityKit native env-scan surface started")
    }

    // ── Session capture (Pillar 1) ───────────────────────────────────

    actual fun startCapture(sessionId: String): Boolean {
        // SceneKit fallback: capture unsupported — report honestly so the
        // caller never shows a REC chip for a recording that isn't happening.
        val surface = nativeSurface ?: return false
        return try {
            val cellId = geoCellIdOrNull()
            if (cellId == null) {
                DiagnosticsLog.info("capture", "startCapture($sessionId) skipped — no geo reference yet")
                return false
            }
            val outputDir = documentsPath() + "/capture-sessions/" + sessionId
            NSFileManager.defaultManager.createDirectoryAtPath(
                outputDir, withIntermediateDirectories = true, attributes = null, error = null,
            )
            captureSessionId = sessionId
            captureLocationCellId = cellId
            surface.startCapture(sessionId, outputDir)
            DiagnosticsLog.info("capture", "session capture started: $sessionId (cell $cellId)")
            true
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "startCapture($sessionId) failed — capture disabled for this session", e)
            captureSessionId = null
            captureLocationCellId = null
            false
        }
    }

    actual fun stopCapture(): List<CaptureChunkInfo> {
        val surface = nativeSurface
        val sessionId = captureSessionId
        val cellId = captureLocationCellId
        // No more tick-drain once we've asked for the final one below.
        captureChunkListener = null
        captureSessionId = null
        captureLocationCellId = null
        if (surface == null || sessionId == null || cellId == null) return emptyList()
        return try {
            val packets = surface.stopCaptureAndDrain()
            val infos = packets.mapNotNull(::parseCaptureChunkPacket)
            if (infos.isNotEmpty()) insertChunksAsync(sessionId, cellId, infos)
            infos
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "stopCapture failed for $sessionId", e)
            emptyList()
        }
    }

    actual fun discardCurrentCaptureChunk() {
        try {
            nativeSurface?.discardCurrentCaptureChunk()
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "discardCurrentCaptureChunk failed", e)
        }
    }

    actual fun setCaptureEnabled(enabled: Boolean) {
        try {
            nativeSurface?.setCaptureEnabled(enabled)
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "setCaptureEnabled($enabled) failed", e)
        }
    }

    /** Advisory only (see the expect kdoc): same thermalState KVO read the
     *  tick's Vision guard uses (critical = 3, the level Task 4's recorder
     *  auto-pauses at). The Swift recorder owns the REAL pause decision —
     *  this just lets the REC chip tint "paused" at the caller's cadence. */
    actual fun thermalPausedLikely(): Boolean = try {
        val thermal = (NSProcessInfo.processInfo as platform.darwin.NSObject)
            .valueForKey("thermalState") as? Long ?: 0L
        thermal >= 3L
    } catch (_: Throwable) {
        false
    }

    /** `gridCell10m` of the current geo reference (session anchor), formatted
     *  "int:int" — the server's `LOCATION_CELL_ID_PATTERN`. Null until a geo
     *  reference has paired ([updateGeoReference]). */
    private fun geoCellIdOrNull(): String? {
        if (!hasGeoRef) return null
        val (latIdx, lngIdx) = SessionPointsEstimator.gridCell10m(refLat, refLng)
        return "$latIdx:$lngIdx"
    }

    /**
     * Tick-drain hook (see [captureChunkListener] kdoc) — parses packets and
     * inserts rows via [CaptureChunkStore], then kicks [CaptureChunkUploader]
     * best-effort. Reached through Koin's global context (`KoinPlatform`) —
     * the same pattern `MainViewController`/`MainActivity` already use to
     * reach Koin singletons from non-Composable, non-DI-constructed code —
     * because this overlay is a plain `ArEnvScanOverlay()` instantiated by
     * `remember { }` in SpotScreen, outside the Koin graph. Every failure is
     * caught here: chunk bookkeeping must never crash the scan, and worst
     * case an unrecorded chunk just waits for the next drain/app launch.
     */
    private fun handleFinishedCaptureChunks(packets: List<String>) {
        val sessionId = captureSessionId ?: return
        val cellId = captureLocationCellId ?: return
        val infos = packets.mapNotNull(::parseCaptureChunkPacket)
        if (infos.isEmpty()) return
        insertChunksAsync(sessionId, cellId, infos)
    }

    private fun insertChunksAsync(sessionId: String, cellId: String, infos: List<CaptureChunkInfo>) {
        scope.launch {
            try {
                val koin = KoinPlatform.getKoin()
                val store = koin.getOrNull<CaptureChunkStore>() ?: return@launch
                val nowMs = Clock.System.now().toEpochMilliseconds()
                for (info in infos) {
                    store.insertChunk(
                        CaptureChunkRecord(
                            sessionId = sessionId,
                            chunkIndex = info.index,
                            kind = info.kind,
                            filePath = info.filePath,
                            sizeBytes = info.sizeBytes,
                            locationCellId = cellId,
                            r2Key = null,
                            uploadedAtMs = null,
                            createdAtMs = nowMs,
                        )
                    )
                }
                koin.getOrNull<CaptureChunkUploader>()?.flushPending()
            } catch (e: Throwable) {
                DiagnosticsLog.error("capture", "chunk bookkeeping failed for $sessionId (${infos.size} chunk(s))", e)
            }
        }
    }

    actual fun stop() {
        // Capture bookkeeping: if the overlay tears down without an explicit
        // stopCapture() (abandoned/backgrounded scan), just drop the Kotlin-
        // side session state — native teardown (Task 4) owns finalizing or
        // discarding whatever the recorder was mid-writing.
        captureChunkListener = null
        captureSessionId = null
        captureLocationCellId = null
        job?.cancel()
        job = null
        // Identity-checked: only clear the shared slot if it's still THIS
        // session's closure (see [frameSourceClosure] kdoc) — an unconditional
        // clear here could steal a newer session's frame source if stop()
        // ever runs after that session's bind()/bindNative().
        if (CameraManager.externalFrameSource === frameSourceClosure) {
            CameraManager.externalFrameSource = null
        }
        frameSourceClosure = null
        // Full teardown (not just pause): cancels the native surface's
        // per-frame scene subscription and releases every created entity —
        // pause() alone left 750+ potential entities anchored in the scene
        // holding GPU resources, the Complete Scan crash-on-tap root cause.
        nativeSurface?.teardown()
        nativeSurface = null
        sceneView?.session?.pause()
        // SceneKit fallback: release every node this overlay created — same
        // "don't just pause, tear down" principle as the native surface.
        floorGridNode?.removeFromParentNode()
        floorGridNode = null
        floorGridLastSize = null
        floorMinX = null
        floorMaxX = null
        floorMinZ = null
        floorMaxZ = null
        arrowNode?.removeFromParentNode()
        arrowNode = null
        frontierTarget?.removeFromParentNode()
        frontierTarget = null
        dispatch_async(dispatch_get_main_queue()) {
            CameraDetectionBus.update(emptyList())
        }
    }

    actual fun updateGeoReference(lat: Double, lng: Double) {
        val position = cameraPosition() ?: return
        refLat = lat
        refLng = lng
        refX = position[0]
        refY = position[1]
        refZ = position[2]
        lngScale = lngScaleFor(lat)
        hasGeoRef = true
        // Native mode has no ARSessionDelegate — mirror tracking quality on
        // the same cadence the route/spot overlays do.
        nativeSurface?.let { surface ->
            sessionHealth.overrideState(
                when (surface.trackingStateRaw()) {
                    0 -> TrackingState.NORMAL
                    1 -> TrackingState.LIMITED
                    else -> TrackingState.NOT_AVAILABLE
                }
            )
        }
        // Re-pairing shifts the whole geo-derived frame — re-place everything
        // against the new reference so the grid/arrow don't lag a fix behind.
        dispatch_async(dispatch_get_main_queue()) {
            pushGridPhase()
            renderGuidance()
        }
    }

    actual fun setGridLatticeOrigin(lat: Double, lng: Double) {
        latticeLat = lat
        latticeLng = lng
        dispatch_async(dispatch_get_main_queue()) { pushGridPhase() }
    }

    /**
     * Re-derive the AR-local grid phase from the lattice origin + current geo
     * reference and hand it to whichever renderer is live (main queue). The
     * SceneKit path picks [gridPhase] up on the next plane tick; the native
     * surface re-phases its plane entities immediately.
     *
     * ONE-SHOT LOCK (Task 2 — spec system 2, "stability beats accuracy for
     * the grid"): the first non-null [gridPhase] is TERMINAL. This call runs
     * on every geo-reference re-pair (i.e. every GPS fix); re-deriving and
     * re-sending the phase on each one was the flashing/overlapping-grid
     * spam this fixes, both for the SceneKit path (which reads [gridPhase]
     * directly every tick) and by keeping the Kotlin layer from holding
     * divergent state that would out-live the native surface's own terminal
     * guard in [com.gcc.app.platform.ArNativeSurface.setGridPhaseOrigin].
     */
    private fun pushGridPhase() {
        if (gridPhase != null) return
        val lat = latticeLat ?: return
        val lng = latticeLng ?: return
        if (!hasGeoRef) return
        val local = latLngToLocal(lat, lng, refLat, refLng, refX, refZ, lngScale)
        gridPhase = local
        nativeSurface?.setGridPhaseOrigin(local.first, local.second)
    }

    actual fun setFrameAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
        if (!enabled) {
            dispatch_async(dispatch_get_main_queue()) { CameraDetectionBus.update(emptyList()) }
        }
    }

    // Env-scan AR ground overlays removed: the neon "digitized" coverage quads
    // (updateScannedCells) and the ground fog (updateFog) no longer render on
    // the camera surface — coverage + fog now live entirely on the minimap
    // (merged "painted" outlines + veil). Kept as no-op `actual`s to satisfy
    // the ArEnvScanOverlay interface; the calm matte ground grid is unaffected.
    actual fun updateScannedCells(cellCenters: List<Pair<Double, Double>>) {}

    actual fun updateFog(cellCenters: List<Pair<Double, Double>>) {}

    actual fun setGuidanceTarget(lat: Double, lng: Double) {
        guidanceLatLng = lat to lng
        dispatch_async(dispatch_get_main_queue()) { renderGuidance() }
    }

    actual fun clearGuidanceTarget() {
        guidanceLatLng = null
        dispatch_async(dispatch_get_main_queue()) { renderGuidance() }
    }

    actual fun confirmCandidate(overrideItemType: String?): TrashCandidate? {
        val candidate = _trashCandidate.value ?: return null
        val world = pendingWorld
        _trashCandidate.value = null
        pendingWorld = null
        nearStreak = 0
        if (world != null) {
            suppressedWorld.add(world)
            placeMarker(world.first, world.second, world.third)
        }
        dispatch_async(dispatch_get_main_queue()) {
            CameraDetectionBus.update(emptyList())
            UINotificationFeedbackGenerator().notificationOccurred(
                UINotificationFeedbackType.UINotificationFeedbackTypeSuccess
            )
        }
        val finalType = overrideItemType ?: candidate.itemType
        return if (finalType == candidate.itemType) candidate else candidate.copy(
            itemType = finalType,
            estimatedWeightGrams = TrashItemCatalog.estimateWeightGrams(
                finalType, candidate.estimatedWidthMeters,
            ),
        )
    }

    actual fun dismissCandidate() {
        pendingWorld?.let { suppressedWorld.add(it) }
        pendingWorld = null
        _trashCandidate.value = null
        nearStreak = 0
        dispatch_async(dispatch_get_main_queue()) { CameraDetectionBus.update(emptyList()) }
    }

    /**
     * Queue a normalized view-space tap (top-left origin) for processing at
     * the start of the next [analyzeTick] — see [processPendingFocusTap].
     * Called from CameraCapture's gesture handler (main thread); reading/
     * writing [pendingFocusTap] from both the main thread here and the
     * analysis coroutine there is a plain last-write-wins overwrite (a
     * single nullable field, not a collection), which is fine for a UX
     * signal that a following tap is expected to supersede anyway.
     */
    actual fun focusAt(x: Float, y: Float) {
        pendingFocusTap = x.toDouble() to y.toDouble()
        _tapCandidate.update { it.reduce(TapEvent.Tapped(x, y)) }
    }

    /**
     * Consume a queued [focusAt] tap: invert the image→view mapping
     * [analyzeTrash] uses ([viewTapToImageCoords]) to recover the
     * normalized image-space point, then raycast immediately. A hit
     * becomes the focus bias for [FOCUS_DURATION_MS] — see
     * [selectSalientObject] (saliency selection) and the promotion gate in
     * [analyzeTrash] (lowered streak threshold) — and drives [tapCandidate]
     * toward [TapPhase.AWAITING_CONFIRMATION] via [classifyTappedRegion].
     * Haptic fires on FOCUS ACQUIRED (raycast hit), not on every tap. A miss
     * is NEVER silent (global constraint 2): it reduces [TapEvent.RaycastMissed]
     * so the pill can say so, instead of leaving the user staring at nothing.
     */
    private fun processPendingFocusTap(frame: ARFrame?) {
        val tap = pendingFocusTap ?: return
        pendingFocusTap = null
        val (imageX, imageY) = viewTapToImageCoords(tap.first, tap.second)
        val hit = raycastImage(frame, imageX, imageY)
        if (hit == null) {
            // Global constraint 2: a tap is NEVER silent.
            _tapCandidate.update { it.reduce(TapEvent.RaycastMissed) }
            return
        }
        focusImagePoint = imageX to imageY
        focusWorld = Triple(hit.x, hit.y, hit.z)
        focusUntilMs = Clock.System.now().toEpochMilliseconds() + FOCUS_DURATION_MS
        val latLng = localToLatLng(hit.x, hit.z) ?: run {
            _tapCandidate.update { it.reduce(TapEvent.RaycastMissed) }
            return
        }
        _tapCandidate.update {
            it.reduce(TapEvent.RaycastHit(latLng.first, latLng.second, hit.distance))
        }
        dispatch_async(dispatch_get_main_queue()) { triggerHapticFeedback() }
        classifyTappedRegion(frame, imageX, imageY)
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
    private fun classifyTappedRegion(frame: ARFrame?, imageX: Double, imageY: Double) {
        val observations = classifyRegion(frame, imageX, imageY, TAP_CROP_FRACTION)
        val label = TrashDetectionRules.trashLabelFrom(observations)
        val confidence = label?.let { TrashDetectionRules.trashConfidenceFor(it, observations) }
        _tapCandidate.update { it.reduce(TapEvent.Classified(label, confidence)) }
    }

    /**
     * [localToLatLng] resolved against this session's live geo reference —
     * the class-scoped convenience [processPendingFocusTap] needs, since the
     * top-level function (see `ArGeoMath.kt`) takes the reference explicitly
     * and has no notion of "not yet paired". Null before [hasGeoRef].
     */
    private fun localToLatLng(x: Float, z: Float): Pair<Double, Double>? {
        if (!hasGeoRef) return null
        return localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)
    }

    actual fun confirmTapCandidate(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Confirmed) }
        return pending
    }

    actual fun rejectTapCandidate(): TapCandidateState? {
        val pending = _tapCandidate.value
        if (pending.phase != TapPhase.AWAITING_CONFIRMATION) return null
        _tapCandidate.update { it.reduce(TapEvent.Rejected) }
        return pending
    }

    actual fun dismissTapCandidate() {
        _tapCandidate.update { it.reduce(TapEvent.Dismissed) }
    }

    actual fun setElementObservedListener(
        listener: (
            (
                lat: Double, lng: Double, itemType: String, domain: String, confidence: Float,
                widthMeters: Double?, yawDegrees: Double?,
            ) -> Unit
        )?,
    ) {
        elementObservedListener = listener
    }

    actual fun setCeilingListener(listener: ((Boolean) -> Unit)?) {
        ceilingListener = listener
    }

    actual fun setCoverageHitListener(listener: ((List<RaycastCoverageHit>) -> Unit)?) {
        coverageHitListener = listener
    }

    actual fun setSceneVoteCadence(fast: Boolean) {
        fastSceneVotes = fast
    }

    // ── Frame loop ───────────────────────────────────────────────────

    private fun startLoop() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                val backoff = try {
                    analyzeTick()
                } catch (e: Throwable) {
                    DiagnosticsLog.error("env-ar", "tick failed (backing off)", e)
                    true
                }
                delay(if (backoff) ERROR_BACKOFF_MS else if (fastSceneVotes) FAST_TICK_MS else TICK_MS)
            }
        }
    }

    /** Returns true when the loop should back off (error / no frame). */
    private fun analyzeTick(): Boolean {
        val native = nativeSurface
        val frame: ARFrame? = if (native == null) sceneView?.session?.currentFrame else null
        if (native == null && frame == null) return false
        tick += 1

        // Tap-to-focus (Task 5): process at the START of the tick, before
        // any other Vision/raycast work this tick does — see
        // [processPendingFocusTap] kdoc for why it's queued rather than
        // handled synchronously off the tap.
        processPendingFocusTap(frame)

        // Ceiling cue for the OutdoorGate — checked every tick, cheap
        // (anchors are already tracked; no Vision work involved).
        val ceiling = when {
            native != null -> native.ceilingDetected()
            frame != null -> frame.anchors.filterIsInstance<ARPlaneAnchor>().any { anchor ->
                val classification = try {
                    (anchor as platform.darwin.NSObject).valueForKey("classification") as? Long ?: 0L
                } catch (_: Throwable) { 0L }
                classification == 3L // ARPlaneClassification.ceiling
            }
            else -> false
        }
        ceilingListener?.let { listener ->
            dispatch_async(dispatch_get_main_queue()) { listener(ceiling) }
        }

        if (native != null) {
            // Native mode: Swift renders its own classified plane grid; we
            // only mirror its ground state for the status line.
            val detected = native.groundDetected()
            if (_groundDetected.value != detected) _groundDetected.value = detected
            // Poll-drain finished capture chunks (Pillar 1) — no-op until a
            // listener is registered; the drain itself is a locked list swap.
            captureChunkListener?.let { listener ->
                val packets = native.drainFinishedCaptureChunks()
                if (packets.isNotEmpty()) listener(packets)
            }
        } else if (frame != null) {
            // Ground grid tracks detected planes even while Vision is paused.
            dispatch_async(dispatch_get_main_queue()) { updatePlaneNodes(frame) }
        }

        // Coverage raycast fan: cheap (no Vision), runs whenever analysis is
        // live and a geo-ref exists. Rays stop on real surfaces — this is the
        // measured "what did the camera actually see" signal (spec §1).
        if (analysisEnabled && hasGeoRef) {
            coverageHitListener?.let { listener ->
                val fanHits = CoverageRayFan.cast(nativeSurface, frame) { x, z ->
                    localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)
                }
                if (fanHits.isNotEmpty()) {
                    dispatch_async(dispatch_get_main_queue()) { listener(fanHits) }
                }
            }
        }

        // Vote cadence: fastSceneVotes samples every tick; otherwise the
        // DISABLED/enabled cadence below (labels-only loop while blocked —
        // scene votes keep flowing at a slower cadence, everything else stops).
        val voteDue = if (fastSceneVotes) true
            else if (!analysisEnabled) tick % DISABLED_VOTE_EVERY_TICKS == 0
            else tick % SCENE_VOTE_EVERY_TICKS == 0
        if (!analysisEnabled && !voteDue) return false

        // Thermal guard: continuous Vision + plane detection + depth is the
        // hottest path in the app — shed the Vision load first, ABOVE all
        // Vision work (full analysis or the labels-only vote below), on
        // both the SceneKit and native (classifySceneLabels) paths.
        // KVO — cinterop doesn't expose thermalState (serious=2, critical=3).
        val thermal = try {
            (NSProcessInfo.processInfo as platform.darwin.NSObject)
                .valueForKey("thermalState") as? Long ?: 0L
        } catch (_: Throwable) { 0L }
        if (thermal >= 2L) {
            dispatch_async(dispatch_get_main_queue()) { CameraDetectionBus.update(emptyList()) }
            return true
        }

        val commonObs: List<ClassificationObservation>
        // SceneKit-only: the combined classify+saliency pass computed together
        // (native path runs saliency separately, only if/when a trash label
        // is actually found — see analyzeTrash/registerElementIfAny).
        var saliencyRequest: VNGenerateObjectnessBasedSaliencyImageRequest? = null

        if (native != null) {
            // Native path: ALL Vision classification runs in Swift
            // (GccArNative.classifySceneLabels) — Kotlin only scores the
            // shared "identifier|confidence" packets. Empty + frame present
            // means Vision failed — back off like the SceneKit path instead
            // of hot-polling the failure; empty with no frame is just the
            // session warming up (normal cadence, matches SceneKit no-frame).
            commonObs = parseSceneLabels(native.classifySceneLabels())
            if (commonObs.isEmpty()) return native.pixelBufferOrNull() != null
        } else {
            val pixelBuffer = frame?.capturedImage ?: return false
            val classifyRequest = VNClassifyImageRequest()
            // Labels-only ticks (analysis paused, scene vote due) never use
            // saliency — don't pay for it just to throw it away below.
            val saliency = if (analysisEnabled) VNGenerateObjectnessBasedSaliencyImageRequest() else null
            val handler = VNImageRequestHandler(cVPixelBuffer = pixelBuffer, options = emptyMap<Any?, Any>())
            handler.performRequests(listOfNotNull(classifyRequest, saliency), error = null)

            val observations = classifyRequest.results
                ?.filterIsInstance<VNClassificationObservation>()
                ?.sortedByDescending { it.confidence }
                ?.take(15)
                ?: return true
            commonObs = observations.map { ClassificationObservation(it.identifier, it.confidence) }
            saliencyRequest = saliency
        }

        if (voteDue) {
            val vote = TrashDetectionRules.sceneVoteFrom(commonObs)
            dispatch_async(dispatch_get_main_queue()) { CameraSceneVoteBus.emit(vote) }
        }
        // Labels-only loop while disabled: scene votes above, but no
        // saliency, trash/element detection, or candidate promotion below.
        if (!analysisEnabled) return false

        analyzeTrash(frame, native, commonObs, saliencyRequest)
        return false
    }

    /**
     * SceneKit path already ran saliency alongside classification — pass it
     * straight through. Native path only pulls the pixel buffer and runs
     * saliency here, on demand (trash label or landmark element found), so
     * classification itself never touches the pixel buffer.
     */
    private fun saliencyOnDemand(
        native: ArNativeSurface?,
        computed: VNGenerateObjectnessBasedSaliencyImageRequest?,
    ): VNGenerateObjectnessBasedSaliencyImageRequest? {
        if (computed != null) return computed
        val pixelBuffer = native?.pixelBufferOrNull() ?: return null
        val request = VNGenerateObjectnessBasedSaliencyImageRequest()
        val handler = VNImageRequestHandler(cVPixelBuffer = pixelBuffer, options = emptyMap<Any?, Any>())
        handler.performRequests(listOf(request), error = null)
        return request
    }

    /**
     * Classify a square region-of-interest centred on a normalized image-space
     * point (top-left origin, matching [imageX]/[imageY] everywhere else in
     * this file) — the tap-to-identify crop [classifyTappedRegion] needs, as
     * opposed to whole-frame classification. Supports both render backends,
     * same precedent as [saliencyOnDemand]/[ArFrameFeedback]'s
     * `native?.pixelBufferOrNull() ?: frame?.capturedImage` pattern: the
     * native RealityKit surface when bound, the SceneKit `ARFrame` otherwise.
     */
    private fun classifyRegion(
        frame: ARFrame?,
        imageX: Double,
        imageY: Double,
        fraction: Double,
    ): List<ClassificationObservation> {
        val pixelBuffer = nativeSurface?.pixelBufferOrNull() ?: frame?.capturedImage ?: return emptyList()
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

    /**
     * Pick which salient candidate to lock onto: with no active focus, the
     * Vision-ranked first candidate (existing behavior, unchanged). With an
     * active [focusAt] focus, the candidate whose bounding-box center is
     * nearest [focusImagePoint] in image space — candidate centers are
     * computed in Vision's bottom-left-origin space and converted to the
     * same top-left "image" convention [focusImagePoint] uses before
     * comparing (mirrors the `imageX = visionX; imageY = 1 - visionY`
     * conversion in [analyzeTrash]). ARKit/Vision-bound — no test harness in
     * this repo, kept small and readable instead of extracted.
     */
    private fun selectSalientObject(
        candidates: List<VNRectangleObservation>,
        focusImagePoint: Pair<Double, Double>?,
    ): VNRectangleObservation? {
        if (focusImagePoint == null) return candidates.firstOrNull()
        val (focusImageX, focusImageY) = focusImagePoint
        return candidates.minByOrNull { candidate ->
            var distanceSquared = Double.MAX_VALUE
            candidate.boundingBox.useContents {
                val candidateImageX = origin.x + size.width / 2.0
                val candidateImageY = 1.0 - (origin.y + size.height / 2.0)
                val dx = candidateImageX - focusImageX
                val dy = candidateImageY - focusImageY
                distanceSquared = dx * dx + dy * dy
            }
            distanceSquared
        }
    }

    private class RayHit(val x: Float, val y: Float, val z: Float, val distance: Float)

    /**
     * World hit for a normalized image-space point, via whichever backend is
     * live: native RealityKit raycast (distance derived from the camera
     * position) or the SceneKit-era ARFrame.hitTest.
     */
    private fun raycastImage(frame: ARFrame?, imageX: Double, imageY: Double): RayHit? {
        nativeSurface?.let { surface ->
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

    private fun analyzeTrash(
        frame: ARFrame?,
        native: ArNativeSurface?,
        classifications: List<ClassificationObservation>,
        saliencyRequest: VNGenerateObjectnessBasedSaliencyImageRequest?,
    ) {
        // While a candidate is pending the loop idles: the card is on screen
        // and the reticle freezes on the locked item's projected position.
        if (_trashCandidate.value != null) return

        // Global constraint 10/11: automatic promotion requires parent-context
        // suppression. A user tap bypasses this entirely (see focusAt).
        val trashLabel = TrashDetectionRules.autoPromotionLabelFrom(classifications)
        if (trashLabel == null) {
            nearStreak = 0
            dispatch_async(dispatch_get_main_queue()) { CameraDetectionBus.update(emptyList()) }
            // No trash in view: a landmark element tick instead — trees,
            // fences, gates… register to the grid so future scans expect
            // them. (Only on trash-less ticks: a bag stuck in a bush must
            // register as the bag.)
            registerElementIfAny(frame, native, classifications, saliencyRequest)
            return
        }
        // The raw identifier that produced the label carries the fine-grained
        // tokens ("glass_bottle" vs "bottle") the canonical typing needs.
        val rawIdentifier = classifications.firstOrNull { obs ->
            obs.confidence >= 0.35f &&
                TrashDetectionRules.tokensOf(obs.identifier).any { it in TrashDetectionRules.TRASH_TOKENS }
        }?.identifier ?: trashLabel
        val confidence = classifications.firstOrNull { it.identifier == rawIdentifier }?.confidence ?: 0.5f

        // Salient region -> reticle position + angular size. SceneKit already
        // computed this alongside classification; native path runs it now,
        // on demand, only because a trash label was actually found. While a
        // tap-to-focus (focusAt) is active, [selectSalientObject] biases
        // toward the candidate nearest the tapped point instead of Vision's
        // top-ranked one.
        var visionX = 0.5
        var visionY = 0.5
        var radius = 0.12f
        var bboxWidthFrac = 0.2
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val focusActive = nowMs <= focusUntilMs
        val effectiveSaliency = saliencyOnDemand(native, saliencyRequest)
        val candidates = effectiveSaliency?.results
            ?.filterIsInstance<VNSaliencyImageObservation>()
            ?.firstOrNull()
            ?.salientObjects
            ?.filterIsInstance<VNRectangleObservation>()
            ?: emptyList()
        val salient = selectSalientObject(candidates, if (focusActive) focusImagePoint else null)
        salient?.boundingBox?.useContents {
            visionX = origin.x + size.width / 2.0
            visionY = origin.y + size.height / 2.0
            radius = (maxOf(size.width, size.height) / 2.0).toFloat().coerceIn(0.06f, 0.35f)
            bboxWidthFrac = size.width
        }

        val imageX = visionX
        val imageY = 1.0 - visionY
        val hit = raycastImage(frame, imageX, imageY)
        val distance = hit?.distance

        val viewX = (1.0 - imageY).toFloat()
        val viewY = imageX.toFloat()

        val isNear = distance != null && distance <= NEAR_DISTANCE_METERS
        nearStreak = if (isNear) nearStreak + 1 else 0

        // Lowered promotion threshold (Task 5): while focus is active and
        // this tick's hit lands within FOCUS_RADIUS_METERS (XZ-plane, same
        // idiom as the suppression check below) of the tapped world point,
        // one near tick promotes instead of NEAR_STREAK_TICKS.
        // NEAR_DISTANCE_METERS (the range gate `isNear` uses above) is
        // unchanged by focus — only the streak count relaxes.
        val fw = focusWorld
        val effectiveStreakTicks = if (
            focusActive && hit != null && fw != null &&
            run {
                val dx = (hit.x - fw.first).toDouble()
                val dz = (hit.z - fw.third).toDouble()
                sqrt(dx * dx + dz * dz) < FOCUS_RADIUS_METERS
            }
        ) 1 else NEAR_STREAK_TICKS

        dispatch_async(dispatch_get_main_queue()) {
            CameraDetectionBus.update(
                listOf(
                    LiveDetection(
                        cx = viewX, cy = viewY, r = radius, label = trashLabel,
                        distanceMeters = distance,
                        streakProgress = nearStreak.coerceAtMost(effectiveStreakTicks),
                        streakTicks = effectiveStreakTicks,
                        severity = LiveSeverityEstimator.estimate(trashLabel, radius),
                        confidence = confidence,
                    )
                )
            )
        }

        if (nearStreak < effectiveStreakTicks || hit == null || distance == null) return

        val world = floatArrayOf(hit.x, hit.y, hit.z)
        // Skip items already handled (confirmed or "not trash").
        val suppressed = suppressedWorld.any { (sx, _, sz) ->
            val dx = (sx - world[0]).toDouble()
            val dz = (sz - world[2]).toDouble()
            sqrt(dx * dx + dz * dz) < SUPPRESS_RADIUS_METERS
        }
        if (suppressed || !hasGeoRef) return

        val widthMeters = physicalWidthMeters(bboxWidthFrac, distance.toDouble())
        val cam = cameraPosition()
        val yawDegrees = cam?.let { bearingDegrees(hit.x - it[0], hit.z - it[2]) }
        val itemType = TrashItemCatalog.canonicalType(rawIdentifier)
        val (lat, lng) = localToLatLng(world[0], world[2], refLat, refLng, refX, refZ, lngScale)
        // Bad AR math (degenerate geo-ref pairing, etc.) must never reach a
        // candidate/report with a non-finite coordinate downstream.
        if (!lat.isFinite() || !lng.isFinite()) return
        candidateCounter += 1
        pendingWorld = Triple(world[0], world[1], world[2])
        _trashCandidate.value = TrashCandidate(
            id = candidateCounter,
            label = trashLabel,
            itemType = itemType,
            lat = lat,
            lng = lng,
            distanceMeters = distance,
            estimatedWidthMeters = widthMeters,
            estimatedWeightGrams = TrashItemCatalog.estimateWeightGrams(itemType, widthMeters),
            confidence = confidence,
            yawDegrees = yawDegrees,
        )
        DiagnosticsLog.info(
            "env-ar",
            "candidate #$candidateCounter $itemType at ${distance}m width=${widthMeters ?: "?"}m",
        )
        nativeSurface?.emitLockEffect(world[0].toDouble(), world[1].toDouble(), world[2].toDouble())
        dispatch_async(dispatch_get_main_queue()) {
            UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                .impactOccurred()
        }
    }

    /**
     * Register a landmark element (vegetation/infrastructure) at its raycast
     * world position — auto, no confirm: these aren't actionable like trash,
     * they're the stable context the grid should expect next time. Deduped
     * per type within 4m; capped per session.
     */
    private fun registerElementIfAny(
        frame: ARFrame?,
        native: ArNativeSurface?,
        classifications: List<ClassificationObservation>,
        saliencyRequest: VNGenerateObjectnessBasedSaliencyImageRequest?,
    ) {
        val listener = elementObservedListener ?: return
        if (!hasGeoRef || registeredElements.size >= MAX_ELEMENTS_PER_SESSION) return
        val (element, confidence) = EnvironmentElementCatalog.elementFrom(classifications) ?: return

        var visionX = 0.5
        var visionY = 0.5
        // No default: a real measurement only exists when saliency actually
        // returns a salient object's bbox. Falling back to a guessed
        // fraction here would fabricate a widthMeters value for elements
        // that were never measured — null must propagate instead.
        var bboxWidthFrac: Double? = null
        saliencyOnDemand(native, saliencyRequest)?.results
            ?.filterIsInstance<VNSaliencyImageObservation>()
            ?.firstOrNull()
            ?.salientObjects
            ?.filterIsInstance<VNRectangleObservation>()
            ?.firstOrNull()
            ?.boundingBox?.useContents {
                visionX = origin.x + size.width / 2.0
                visionY = origin.y + size.height / 2.0
                bboxWidthFrac = size.width
            }
        val hit = raycastImage(frame, visionX, 1.0 - visionY) ?: return
        if (hit.distance > ELEMENT_MAX_DISTANCE_METERS) return

        val world = floatArrayOf(hit.x, hit.y, hit.z)
        val duplicate = registeredElements.any { (type, x, z) ->
            type == element.itemType && run {
                val dx = (x - world[0]).toDouble()
                val dz = (z - world[2]).toDouble()
                sqrt(dx * dx + dz * dz) < ELEMENT_DEDUPE_METERS
            }
        }
        if (duplicate) return

        // Geo-resolve BEFORE registering into the dedupe set: a non-finite
        // result must not permanently suppress this element for the session.
        val (lat, lng) = localToLatLng(world[0], world[2], refLat, refLng, refX, refZ, lngScale)
        if (!lat.isFinite() || !lng.isFinite()) return
        val widthMeters = bboxWidthFrac?.let { physicalWidthMeters(it, hit.distance.toDouble()) }
        val cam = cameraPosition()
        val yawDegrees = cam?.let { bearingDegrees(hit.x - it[0], hit.z - it[2]) }
        registeredElements.add(Triple(element.itemType, world[0], world[2]))
        DiagnosticsLog.info(
            "env-ar",
            "registered ${element.domain}/${element.itemType} #${registeredElements.size} at ${hit.distance}m " +
                "width=${widthMeters ?: "?"}m yaw=${yawDegrees ?: "?"}",
        )
        // Vector-scan analysis effect (spec system 3): trash candidates get
        // this on lock-on (above); landmark elements never did, so a newly
        // identified tree/fence/gate registered silently with no on-screen
        // acknowledgment. Every registration — litter or landmark — now
        // fires the same effect at its world position.
        native?.emitLockEffect(world[0].toDouble(), world[1].toDouble(), world[2].toDouble())
        dispatch_async(dispatch_get_main_queue()) {
            listener(lat, lng, element.itemType, element.domain, confidence, widthMeters, yawDegrees)
        }
    }

    /**
     * Pinhole width: w = d * pixelWidth / fx, with fx from the camera
     * intrinsics (column-major 3x3: fx at index 0) and pixelWidth from the
     * normalized bbox against the sensor resolution.
     */
    private fun physicalWidthMeters(bboxWidthFrac: Double, distance: Double): Double? {
        if (bboxWidthFrac <= 0.0 || bboxWidthFrac >= 1.0) return null
        // Angular size with the iPhone wide lens's ~63° horizontal FOV —
        // camera intrinsics don't bridge cleanly through cinterop, and
        // weight-class estimation doesn't need per-pixel precision.
        val hFovRad = 63.0 * kotlin.math.PI / 180.0
        val width = 2.0 * distance * kotlin.math.tan(0.5 * bboxWidthFrac * hFovRad)
        return if (width.isFinite() && width in 0.01..3.0) {
            (width * 100).roundToInt() / 100.0
        } else null
    }

    /**
     * AR-local compass bearing from the camera to a hit position: world is
     * gravityAndHeading-aligned with +X east / -Z north (the same convention
     * [localToLatLng] uses), so bearing = atan2(east, north) =
     * atan2(dx, -dz), normalized to [0, 360). Null for a degenerate
     * (zero-length or non-finite) camera→hit vector.
     */
    private fun bearingDegrees(dx: Float, dz: Float): Double? {
        if (dx == 0f && dz == 0f) return null
        val radians = kotlin.math.atan2(dx.toDouble(), -dz.toDouble())
        val degrees = (radians * 180.0 / kotlin.math.PI + 360.0) % 360.0
        return if (degrees.isFinite()) degrees else null
    }

    // ── Ground grid ──────────────────────────────────────────────────

    /**
     * Sync the ONE ground grid with the frame's current plane anchors (main
     * queue). Mirrors GccArNative.swift's upsertPlane/floorGrid: every
     * floor-classified ARPlaneAnchor folds its world AABB into a monotonic
     * union (grows only, never shrinks) that backs a SINGLE SCNNode — this
     * used to create one grid node per anchor (planeNodes.getOrPut(id)),
     * which visibly stacked overlapping semi-transparent grids whenever
     * ARKit split, merged or re-detected planes. Furniture ("rough") planes
     * no longer render a grid at all, matching the RealityKit path's "ONE
     * ground grid" contract (spec system 2).
     */
    private fun updatePlaneNodes(frame: ARFrame) {
        val scene = sceneView?.scene ?: return
        val anchors = frame.anchors.filterIsInstance<ARPlaneAnchor>()
        var lowestFloor: Float? = null
        var grew = false

        for (anchor in anchors) {
            // KVO — raw ARPlaneClassification: none=0, floor=2 (unclassified
            // horizontal planes count as ground; only these feed the grid).
            val classification = try {
                (anchor as platform.darwin.NSObject).valueForKey("classification") as? Long ?: 0L
            } catch (_: Throwable) { 0L }
            val isFloor = classification == 2L || classification == 0L
            if (!isFloor) continue
            val center = vectorFloats(anchor.center)
            val extent = vectorFloats(anchor.extent)
            if (extent[0] < 0.4f || extent[2] < 0.4f) continue // ignore slivers

            // World-aligned AABB: the anchor's yaw is arbitrary (whatever
            // ARKit detected first), but AR world axes are compass-true —
            // computing the world-space AABB of the rotated extent keeps the
            // eventual grid lines north/south–east/west, registering with
            // the north-up minimap lattice.
            val m = matrixFloats(anchor.transform)
            val yaw = kotlin.math.atan2(-m[2].toDouble(), m[0].toDouble())
            val cosYaw = kotlin.math.cos(yaw)
            val sinYaw = kotlin.math.sin(yaw)
            val ex = extent[0].toDouble()
            val ez = extent[2].toDouble()
            val worldW = abs(ex * cosYaw) + abs(ez * sinYaw)
            val worldD = abs(ex * sinYaw) + abs(ez * cosYaw)
            val centerWorldX = m[12] + (center[0] * cosYaw + center[2] * sinYaw)
            val centerWorldZ = m[14] + (-center[0] * sinYaw + center[2] * cosYaw)
            val planeMinX = centerWorldX - worldW / 2
            val planeMaxX = centerWorldX + worldW / 2
            val planeMinZ = centerWorldZ - worldD / 2
            val planeMaxZ = centerWorldZ + worldD / 2

            // Fold this plane's AABB into the ONE grid's monotonic union —
            // the union only ever grows, matching "stability beats accuracy
            // for the grid": ARKit merging/re-splitting/removing a
            // contributing anchor must never make the single grid entity
            // flicker, shrink or spawn a sibling.
            if (floorMinX == null || planeMinX < floorMinX!!) { floorMinX = planeMinX; grew = true }
            if (floorMaxX == null || planeMaxX > floorMaxX!!) { floorMaxX = planeMaxX; grew = true }
            if (floorMinZ == null || planeMinZ < floorMinZ!!) { floorMinZ = planeMinZ; grew = true }
            if (floorMaxZ == null || planeMaxZ > floorMaxZ!!) { floorMaxZ = planeMaxZ; grew = true }

            val y = m[13] + center[1]
            if (lowestFloor == null || y < lowestFloor!!) lowestFloor = y
        }

        if (lowestFloor != null) floorY = lowestFloor

        val existing = floorGridNode
        if (existing == null) {
            if (floorMinX == null) {
                return
            }
            val newNode = makePlaneGridNode().also { scene.rootNode.addChildNode(it) }
            floorGridNode = newNode
            applyFloorGridGeometry(newNode)
        } else if (grew) {
            applyFloorGridGeometry(existing)
        }

        val detected = floorGridNode != null
        if (_groundDetected.value != detected) {
            _groundDetected.value = detected
            if (detected) DiagnosticsLog.info("env-ar", "ground recognized — grid live")
        }
    }

    /**
     * (Re)build the ONE grid's render geometry from the monotonic union AABB
     * — snapped OUTWARD to the coverage lattice once its phase is known
     * (tracker origin set + geo-ref paired), so the grid edges — and every
     * repeated [GRID_TILE_METERS] texture line between them — land exactly
     * on the 3m cell lattice the minimap draws. Mirrors
     * GccArNative.swift's applyPlaneGeometry. Called on union growth; mesh
     * width/height writes are skipped when the snapped size is unchanged.
     */
    private fun applyFloorGridGeometry(node: SCNNode) {
        val rawMinX = floorMinX ?: return
        val rawMaxX = floorMaxX ?: return
        val rawMinZ = floorMinZ ?: return
        val rawMaxZ = floorMaxZ ?: return
        val tile = GRID_TILE_METERS.toDouble()
        val phase = gridPhase
        val (left, right) = if (phase != null) {
            snapRangeToLattice(rawMinX, rawMaxX, phase.first, tile)
        } else rawMinX to rawMaxX
        val (nearZ, farZ) = if (phase != null) {
            snapRangeToLattice(rawMinZ, rawMaxZ, phase.second, tile)
        } else rawMinZ to rawMaxZ

        val width = right - left
        val depth = farZ - nearZ
        val y = floorY ?: (refY - GROUND_OFFSET_METERS)
        node.position = SCNVector3Make(((left + right) / 2).toFloat(), y, ((nearZ + farZ) / 2).toFloat())

        val planeChild = node.childNodes.firstOrNull() as? SCNNode ?: return
        val size = width to depth
        if (floorGridLastSize != size) {
            (planeChild.geometry as? SCNPlane)?.let { plane ->
                plane.width = width
                plane.height = depth
            }
            floorGridLastSize = size
        }
        (planeChild.geometry as? SCNPlane)?.let { plane ->
            val uv = SCNMatrix4MakeScale(
                (width / tile).toFloat().coerceAtLeast(1f),
                (depth / tile).toFloat().coerceAtLeast(1f),
                1f,
            )
            plane.firstMaterial?.diffuse?.contentsTransform = uv
            plane.firstMaterial?.emission?.contentsTransform = uv
        }
    }

    private fun makePlaneGridNode(): SCNNode {
        val texture = floorGridTexture ?: makeGridTexture(
            UIColor.colorWithRed(0.20, 0.85, 0.45, 0.9), lineWidth = 3.0,
        ).also { floorGridTexture = it }
        val plane = SCNPlane.planeWithWidth(1.0, 1.0)
        val material = SCNMaterial()
        material.diffuse.contents = texture
        material.diffuse.wrapS = platform.SceneKit.SCNWrapModeRepeat
        material.diffuse.wrapT = platform.SceneKit.SCNWrapModeRepeat
        material.emission.contents = texture
        material.emission.wrapS = platform.SceneKit.SCNWrapModeRepeat
        material.emission.wrapT = platform.SceneKit.SCNWrapModeRepeat
        material.doubleSided = true
        material.writesToDepthBuffer = false
        material.transparency = 0.55
        plane.materials = listOf(material)

        val planeChild = SCNNode.nodeWithGeometry(plane)
        planeChild.eulerAngles = SCNVector3Make((-kotlin.math.PI / 2).toFloat(), 0f, 0f)
        val container = SCNNode()
        container.addChildNode(planeChild)
        return container
    }

    // ── Guidance arrow ───────────────────────────────────────────────

    /** Aim (or hide) the camera-attached arrow at the current guidance target. */
    private fun renderGuidance() {
        val target = guidanceLatLng
        if (!hasGeoRef || target == null) {
            nativeSurface?.clearGuidanceTarget()
            arrowNode?.hidden = true
            return
        }
        val local = latLngToLocal(target.first, target.second, refLat, refLng, refX, refZ, lngScale)
        nativeSurface?.let { surface ->
            surface.setGuidanceTarget(local.first, local.second)
            return
        }
        // SceneKit fallback: pyramid on the camera with a LookAt constraint at
        // an invisible world node — SceneKit re-aims it every frame for free
        // (same construction as ArRouteOverlay's frontier arrow).
        val scene = sceneView?.scene ?: return
        val groundY = floorY ?: (refY - GROUND_OFFSET_METERS)
        val targetNode = frontierTarget ?: SCNNode().also {
            frontierTarget = it
            scene.rootNode.addChildNode(it)
        }
        targetNode.position = SCNVector3Make(local.first.toFloat(), groundY, local.second.toFloat())
        val arrow = arrowNode ?: makeArrowNode(targetNode) ?: return
        arrow.hidden = false
    }

    private fun makeArrowNode(target: SCNNode): SCNNode? {
        val camera = sceneView?.pointOfView ?: return null
        val pyramid = SCNPyramid.pyramidWithWidth(0.12, 0.25, 0.12)
        val material = SCNMaterial()
        material.diffuse.contents = UIColor.greenColor
        pyramid.materials = listOf(material)

        // SCNPyramid points +Y; rotate so the tip points -Z, the axis
        // SCNLookAtConstraint aims at its target.
        val geometryNode = SCNNode.nodeWithGeometry(pyramid)
        geometryNode.eulerAngles = SCNVector3Make((-kotlin.math.PI / 2).toFloat(), 0f, 0f)

        val container = SCNNode()
        container.addChildNode(geometryNode)
        container.position = SCNVector3Make(0f, -0.5f, -1.2f)
        val constraint = SCNLookAtConstraint.lookAtConstraintWithTarget(target)
        constraint.gimbalLockEnabled = true
        container.constraints = listOf(constraint)

        camera.addChildNode(container)
        arrowNode = container
        return container
    }

    // ── Markers / capture / helpers ──────────────────────────────────

    private fun placeMarker(x: Float, y: Float, z: Float) {
        markerPositions.add(Triple(x, y, z))
        nativeSurface?.let { surface ->
            dispatch_async(dispatch_get_main_queue()) {
                surface.addMarker(x.toDouble(), y.toDouble(), z.toDouble(), kind = 0)
            }
            return
        }
        dispatch_async(dispatch_get_main_queue()) {
            val scene = sceneView?.scene ?: return@dispatch_async
            val torus = SCNTorus.torusWithRingRadius(0.18, 0.02)
            val material = SCNMaterial()
            material.diffuse.contents = UIColor.greenColor.colorWithAlphaComponent(0.9)
            torus.materials = listOf(material)
            val node = SCNNode.nodeWithGeometry(torus)
            node.position = SCNVector3Make(x, y, z)
            scene.rootNode.addChildNode(node)
        }
    }

    /**
     * Silent frame grab from the AR feed (CameraManager's frame source).
     * Polls briefly: the ARSession needs a moment after launch before the
     * first frame exists, and the auto-scan fires just 2.5s after open —
     * without the retry the first capture of every session could fail.
     */
    suspend fun captureFrame(): CapturedPhoto? {
        val deferred = CompletableDeferred<CapturedPhoto?>()
        scope.launch {
            var photo: CapturedPhoto? = null
            var attempts = 0
            while (photo == null && attempts < 10) {
                val buffer = nativeSurface?.pixelBufferOrNull()
                    ?: sceneView?.session?.currentFrame?.capturedImage
                photo = buffer?.let { cvPixelBufferToPortraitPhoto(it) }
                attempts++
                if (photo == null) delay(300L)
            }
            deferred.complete(photo)
        }
        return withTimeoutOrNull(4_000L) { deferred.await() }
    }

    private fun cameraPosition(): List<Float>? {
        nativeSurface?.let { surface ->
            return surface.cameraPosition()?.map { it.toFloat() }
        }
        val transform = sceneView?.session?.currentFrame?.camera?.transform ?: return null
        return matrixTranslation(transform).toList()
    }

    private fun matrixFloats(transform: CValue<simd_float4x4>): FloatArray = memScoped {
        val matrix = alloc<simd_float4x4>()
        transform.place(matrix.ptr)
        val values = matrix.ptr.reinterpret<FloatVar>()
        FloatArray(16) { index -> values[index] }
    }

    private fun vectorFloats(vector: kotlinx.cinterop.Vector128): FloatArray =
        floatArrayOf(vector.getFloatAt(0), vector.getFloatAt(1), vector.getFloatAt(2))

    /**
     * A transparent tile with lines on its leading edges — repeat-wrapped it
     * tiles into a seamless grid; [withDiagonals] adds an X for the denser
     * "vector scan" look.
     */
    private fun makeGridTexture(
        line: UIColor,
        lineWidth: Double,
        withDiagonals: Boolean = false,
        sizePx: Double = 128.0,
    ): UIImage? {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(sizePx, sizePx), false, 1.0)
        val path = UIBezierPath.bezierPath()
        path.moveToPoint(CGPointMake(0.0, 0.5))
        path.addLineToPoint(CGPointMake(sizePx, 0.5))
        path.moveToPoint(CGPointMake(0.5, 0.0))
        path.addLineToPoint(CGPointMake(0.5, sizePx))
        if (withDiagonals) {
            path.moveToPoint(CGPointMake(0.0, 0.0))
            path.addLineToPoint(CGPointMake(sizePx, sizePx))
            path.moveToPoint(CGPointMake(sizePx, 0.0))
            path.addLineToPoint(CGPointMake(0.0, sizePx))
        }
        path.lineWidth = lineWidth
        line.setStroke()
        path.stroke()
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return image
    }
}

actual fun arEnvScanSupported(): Boolean = ARWorldTrackingConfiguration.isSupported()

/**
 * iOS host: `ARSCNView` + `ARCoachingOverlayView` (Apple's standard plane-
 * acquisition guidance) in a Compose `UIKitView` — the [ArRouteOverlayView]
 * pattern.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ArEnvScanView(overlay: ArEnvScanOverlay, modifier: Modifier) {
    UIKitView(
        factory = {
            // Native RealityKit surface when the Swift module registered one;
            // ANY failure falls back to the proven SceneKit path — the render
            // layer must never brick the scan.
            val nativeView = try {
                ArNativeRegistry.surfaceFactory?.invoke()?.let { surface ->
                    overlay.bindNative(surface)
                    surface.view()
                }
            } catch (e: Throwable) {
                DiagnosticsLog.error("env-ar", "native AR surface failed — SceneKit fallback", e)
                null
            }
            nativeView ?: run {
                val view = ARSCNView()
                overlay.bind(view)
                val coaching = ARCoachingOverlayView()
                coaching.session = view.session
                coaching.goal = ARCoachingGoal.ARCoachingGoalHorizontalPlane
                coaching.activatesAutomatically = true
                coaching.setFrame(view.bounds)
                coaching.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                view.addSubview(coaching)
                view
            }
        },
        modifier = modifier,
        onRelease = {
            overlay.stop()
        },
    )
}
