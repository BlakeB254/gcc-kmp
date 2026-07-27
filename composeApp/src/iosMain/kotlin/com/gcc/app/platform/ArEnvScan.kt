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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.ARKit.ARCoachingGoal
import platform.ARKit.ARCoachingOverlayView
import platform.ARKit.ARFrame
import platform.ARKit.ARPlaneAnchor
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.valueForKey
import platform.SceneKit.SCNAction
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNMatrix4MakeScale
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNPlane
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
import com.gcc.shared.model.GeoRefEvent
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
        /** Candidate promotion: this close, for this many consecutive ticks.
         *  Field report (TestFlight 148): at 2.0m the auto path never fired on
         *  litter lying on open ground a few strides away — widened to match
         *  TAP_IDENTIFY_MAX_METERS so anything close enough to identify by
         *  tap is also close enough to auto-promote. LiDAR raycast distance
         *  is reliable well past 4m; the streak requirement stays. */
        private const val NEAR_DISTANCE_METERS = TAP_IDENTIFY_MAX_METERS
        private const val NEAR_STREAK_TICKS = 2
        /** Saliency-crop fallback (build 164): when the whole-frame classifier
         *  names no trash, classify up to this many salient-region crops per
         *  tick before giving up — a wide outdoor frame rarely ranks a small
         *  ground item at TRASH_CONFIDENCE; a crop concentrated on it does. */
        private const val CROP_CLASSIFY_MAX_PER_TICK = 2
        /** Crop half-extents scale relative to the salient box (some ground
         *  context helps the classifier), and their clamps — floor keeps a
         *  tiny box from starving the classifier of pixels, ceiling keeps the
         *  crop meaningfully tighter than the whole frame. */
        private const val CROP_CONTEXT_SCALE = 1.3
        private const val CROP_MIN_HALF_EXTENT = 0.10
        private const val CROP_MAX_HALF_EXTENT = 0.25
        /** Don't re-offer a candidate within this range of a dismissed/confirmed one. */
        private const val SUPPRESS_RADIUS_METERS = 2.0
        /** Tap-to-focus (Task 5): how long a [focusAt] tap biases detection. */
        private const val FOCUS_DURATION_MS = 5_000L
        /** Evidence-photo trigger (TestFlight 157): grab the candidate's
         *  frame the first tick its packet reports the camera this close —
         *  mirrors the tracker's CLOSE_FRAME_MAX_METERS, when the item is
         *  actually in view. */
        private const val CANDIDATE_PHOTO_MAX_DISTANCE_M = 2.0f
        /** Full camera frames are megabytes each — bound the stash; anything
         *  past the cap degrades to the completion-time fallback grab. */
        private const val CANDIDATE_PHOTO_CACHE_MAX = 6
        /** Beacon placement cap (spec 2026-07-10): latLngToLocal error grows
         *  with distance from the geo reference (compass-bias rotation), and
         *  a misplaced beacon is worse than an absent one. Much tighter than
         *  the 55m SEEN raycast ceiling on purpose. */
        private const val BEACON_MAX_RANGE_METERS = 15.0
        /** Tap-to-focus: promotion streak relaxes to 1 tick for a candidate
         *  hit within this XZ-plane distance of the tapped world point.
         *  NEAR_DISTANCE_METERS (the range gate) is unchanged by focus. */
        private const val FOCUS_RADIUS_METERS = 1.5
        /** Minor grid interval: one metre (constraint 7). The grid texture is a
         *  3×3-cell tile — thin lines on the interior cell boundaries, a heavy
         *  line on its outer edge — so ONE texture repeat spans
         *  [GRID_MAJOR_TILE_METERS] (3m, CoverageTracker's cell size) and each
         *  cell inside it spans this constant.
         *  MUST MATCH `gridTileMeters` in GccArNative.swift — the RealityKit and
         *  SceneKit paths phase the same lattice; the two are not read from a
         *  shared constant, so keep them in sync by hand. */
        private const val GRID_TILE_METERS = 1.0f
        /** World size of ONE texture repeat (the 3m major interval). Every UV
         *  scale and lattice snap for the ground grid must use THIS, not
         *  [GRID_TILE_METERS] — dividing by the minor interval would render the
         *  whole 3×3 texture per metre (minor lines at 0.33m, major at 1m). */
        private const val GRID_MAJOR_TILE_METERS = GRID_TILE_METERS * 3f
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

    private val _captureVideoDegraded = MutableStateFlow(false)
    actual val captureVideoDegraded: StateFlow<Boolean> = _captureVideoDegraded.asStateFlow()

    private val _trashCandidate = MutableStateFlow<TrashCandidate?>(null)
    actual val trashCandidate: StateFlow<TrashCandidate?> = _trashCandidate.asStateFlow()

    private val _multiItemReticles = MutableStateFlow<List<DetectionReticle>>(emptyList())
    actual val multiItemReticles: StateFlow<List<DetectionReticle>> = _multiItemReticles.asStateFlow()

    private val tapEngine = TapToConfirmEngine(
        nativeSurfaceProvider = { nativeSurface },
        localToLatLng = { x, z -> localToLatLng(x, z) }, // existing hasGeoRef-gated helper
        onRaycastHit = { hit, imageX, imageY ->
            // Env-only: focus bias, set unconditionally on any raycast hit
            // (even pre-geo-ref) — moved verbatim from the pre-extraction
            // tap flow.
            focusImagePoint = imageX to imageY
            focusWorld = Triple(hit.x, hit.y, hit.z)
            focusUntilMs = Clock.System.now().toEpochMilliseconds() + FOCUS_DURATION_MS
        },
        onTapGeoResolved = { hit ->
            // Env-only: ground-candidate tap-adoption, moved verbatim from
            // the pre-extraction tap flow — only ever ran once geo-resolve
            // succeeded.
            val scanningBeforeTap = groundCandidateTracker.activeScanning().mapTo(mutableSetOf()) { it.first }
            groundCandidateTracker.adoptTap(hit.x, hit.y, hit.z)?.let { adoptedId ->
                if (adoptedId !in scanningBeforeTap) {
                    ScanSessionTelemetry.active?.detection(
                        label = null, confidence = null,
                        distanceM = groundCandidatePackets[adoptedId]?.distanceM?.toDouble(),
                        source = "tap", outcome = "accepted",
                    )
                }
            }
        },
    )
    actual val tapCandidate: StateFlow<TapCandidateState> = tapEngine.tapCandidate

    private val _groundIntentPrompt = MutableStateFlow<GroundIntentPrompt?>(null)
    actual val groundIntentPrompt: StateFlow<GroundIntentPrompt?> = _groundIntentPrompt.asStateFlow()

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
    /** World Y of the lowest floor-classified plane. */
    private var floorY: Float? = null

    // Guidance target: the next area worth scanning. Rendered by the minimap
    // cone and text prompt (global constraint 8) — no camera-attached node
    // anymore, just the lat/lng handed to the native surface.
    private var guidanceLatLng: Pair<Double, Double>? = null

    // Trash lock-on state.
    private var nearStreak = 0
    private var candidateCounter = 0
    private var pendingWorld: Triple<Float, Float, Float>? = null
    private val suppressedWorld = mutableListOf<Triple<Float, Float, Float>>()
    private val markerPositions = mutableListOf<Triple<Float, Float, Float>>()

    // Tap-to-focus (Task 5). A tap arrives on the gesture/main thread via
    // [focusAt]; it's queued here (via [tapEngine]) and processed at the
    // START of the next [analyzeTick] (on the Vision/analysis coroutine)
    // rather than raycast synchronously off the tap — Vision/raycast work
    // belongs on that queue.
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

    // Ground-candidate flow (Task 6): LiDAR anomaly packets -> tracker
    // lifecycle -> native reticle pool. Native-surface only; var (not val)
    // so [stop] can retire this session's tracker instead of letting a
    // torn-down session's PROPOSED/SCANNING backlog bleed into whatever
    // reuses this overlay instance next.
    private var groundCandidateTracker = GroundCandidateTracker(nowMs = { Clock.System.now().toEpochMilliseconds() })
    // Latest known geometry per non-terminal candidate id -- activeScanning()
    // only returns (id, progress), so reticle placement needs this side
    // table, keyed off the same packets fed to [groundCandidateTracker].ingest.
    private val groundCandidatePackets = mutableMapOf<Long, GroundCandidatePacket>()
    private var groundCandidateCompletedListener: (
        (lat: Double, lng: Double, photo: CapturedPhoto?, candidate: CompletedCandidate) -> Unit
    )? = null
    // Close-range evidence photos (TestFlight 157): the completion-time
    // captureFrame fires AFTER the camera has panned off the item, so the
    // filed observation usually carried no usable photo. Instead, grab ONE
    // frame per SCANNING candidate the first tick it's observed close
    // (<2m — the camera is pointed at it by construction), stash it here,
    // and prefer it at completion. `candidatePhotoRequested` is touched only
    // from the tick coroutine; the byte cache crosses coroutines and is
    // mutex-guarded. Bounded: completion/timeout/exclusion evict, and the
    // cache never grows past [CANDIDATE_PHOTO_CACHE_MAX].
    private val candidatePhotoMutex = Mutex()
    private val candidatePhotoCache = mutableMapOf<Long, CapturedPhoto>()
    private val candidatePhotoRequested = mutableSetOf<Long>()

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
        val surface = nativeSurface ?: run {
            DiagnosticsLog.info("capture", "startCapture($sessionId) skipped — no native surface (SceneKit fallback)")
            return false
        }
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

    actual fun stopCapture(): Pair<List<CaptureChunkInfo>, String?> {
        val surface = nativeSurface
        val sessionId = captureSessionId
        val cellId = captureLocationCellId
        // No more tick-drain once we've asked for the final one below.
        captureChunkListener = null
        captureSessionId = null
        captureLocationCellId = null
        if (surface == null || sessionId == null || cellId == null) return emptyList<CaptureChunkInfo>() to null
        return try {
            val packets = surface.stopCaptureAndDrain()
            val infos = packets.mapNotNull(::parseCaptureChunkPacket)
            if (infos.isNotEmpty()) insertChunksAsync(sessionId, cellId, infos)
            // Read AFTER stopCaptureAndDrain — the recorder's serial queue is
            // idle by then (see ArSessionRecorder.healthJson doc).
            infos to surface.captureHealthJson()
        } catch (e: Throwable) {
            DiagnosticsLog.error("capture", "stopCapture failed for $sessionId", e)
            emptyList<CaptureChunkInfo>() to null
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
        // Shared with ArRouteOverlay's capture path — see CaptureChunkBooking.kt.
        bookCaptureChunksAsync(scope, sessionId, cellId, infos)
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
        // Ground-candidate flow (Task 6): retire this session's tracker/
        // side-table/listener rather than leaving a PROPOSED/SCANNING
        // backlog (and a stale consumer callback) live for whatever reuses
        // this overlay instance next. The native reticle pool itself is
        // already fully released by [nativeSurface]?.teardown() above.
        groundCandidateTracker = GroundCandidateTracker(nowMs = { Clock.System.now().toEpochMilliseconds() })
        groundCandidatePackets.clear()
        groundCandidateCompletedListener = null
        // Evidence-photo stash: the tick loop is cancelled above, so the
        // request set is safe to clear here; the byte cache goes through its
        // mutex like every other access.
        candidatePhotoRequested.clear()
        scope.launch { candidatePhotoMutex.withLock { candidatePhotoCache.clear() } }
        _multiItemReticles.value = emptyList()
        dispatch_async(dispatch_get_main_queue()) {
            CameraDetectionBus.update(emptyList())
        }
    }

    actual fun updateGeoReference(
        lat: Double,
        lng: Double,
        accuracyMeters: Double?,
        headingDegrees: Double?,
        headingAccuracyDegrees: Double?,
    ): GeoRefEvent? {
        val position = cameraPosition() ?: return null
        refLat = lat
        refLng = lng
        refX = position[0]
        refY = position[1]
        refZ = position[2]
        lngScale = lngScaleFor(lat)
        hasGeoRef = true
        // The pairing record the DGX splat pipeline registers the AR-local
        // pose stream with: BOTH clocks (epoch ms + ARKit frame time, the
        // pose sidecar's clock) and BOTH frames (lat/lng + AR-local camera
        // position). Returned to the caller for the session manifest.
        val arT = nativeSurface?.currentFrameTimestamp()?.takeIf { it > 0.0 }
            ?: sceneView?.session?.currentFrame?.timestamp
        val geoRefEvent = GeoRefEvent(
            atMs = Clock.System.now().toEpochMilliseconds(),
            lat = lat,
            lng = lng,
            accuracyMeters = accuracyMeters,
            arX = position[0],
            arY = position[1],
            arZ = position[2],
            arTimestampSeconds = arT,
            headingDegrees = headingDegrees,
            headingAccuracyDegrees = headingAccuracyDegrees,
        )
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
        // against the new reference so the grid/guidance don't lag a fix behind.
        dispatch_async(dispatch_get_main_queue()) {
            pushGridPhase()
            renderGuidance()
        }
        return geoRefEvent
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

    actual fun setGuidanceTarget(lat: Double, lng: Double) {
        guidanceLatLng = lat to lng
        dispatch_async(dispatch_get_main_queue()) { renderGuidance() }
    }

    actual fun clearGuidanceTarget() {
        guidanceLatLng = null
        dispatch_async(dispatch_get_main_queue()) { renderGuidance() }
    }

    actual fun dissolveGuidanceBeacon() {
        dispatch_async(dispatch_get_main_queue()) { nativeSurface?.dissolveGuidanceBeacon() }
    }

    actual fun setGuidanceBeaconSuppressed(suppressed: Boolean) {
        dispatch_async(dispatch_get_main_queue()) { nativeSurface?.setBeaconSuppressed(suppressed) }
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
     * the start of the next [analyzeTick] — see [tapEngine]'s
     * `processPendingTap`. Called from CameraCapture's gesture handler (main
     * thread); the queued tap is a plain last-write-wins overwrite (a single
     * nullable field, not a collection), which is fine for a UX signal that
     * a following tap is expected to supersede anyway.
     */
    actual fun focusAt(x: Float, y: Float) {
        // Tap telemetry at the source (t2-task-1): emit BEFORE the early
        // return below so EVERY screen tap is visible to the flight
        // recorder, not just the ones that reach the tap-candidate state
        // machine — the old LaunchedEffect(tapCandidate.phase) approach in
        // SpotScreen missed the transient LOCATING phase on Compose
        // snapshot conflation and never fired at all on this early-return
        // path (2026-07-13 field walk: tap_count = 0 despite repeated taps).
        val superseding = _trashCandidate.value != null
        ScanSessionTelemetry.active?.tap(state = if (superseding) "supersede" else null)
        // I1 (revised, post-158): a tap while the whole-frame lock-on confirm
        // card is up used to be SILENTLY discarded — the single worst "I
        // tapped trash and nothing happened" path in the field, because the
        // card can lock onto something across the street while the user is
        // deliberately tapping an item at their feet. Tap authority (Option
        // A) says explicit intent wins: dismiss the stale card and process
        // this tap normally. The original I1 hazard (the pill advancing
        // INVISIBLY behind the card and surfacing stale later) can't happen
        // anymore — the card is gone by the time the tap state machine runs.
        if (superseding) dismissCandidate()
        tapEngine.queueTap(x, y)
    }

    /**
     * [localToLatLng] resolved against this session's live geo reference —
     * the class-scoped convenience [tapEngine] needs, since the top-level
     * function (see `ArGeoMath.kt`) takes the reference explicitly and has
     * no notion of "not yet paired". Null before [hasGeoRef].
     */
    private fun localToLatLng(x: Float, z: Float): Pair<Double, Double>? {
        if (!hasGeoRef) return null
        return localToLatLng(x, z, refLat, refLng, refX, refZ, lngScale)
    }

    actual fun confirmTapCandidate(): TapCandidateState? = tapEngine.confirm()

    actual fun rejectTapCandidate(): TapCandidateState? = tapEngine.reject()

    actual fun dismissTapCandidate() {
        tapEngine.dismiss()
    }

    /** Last-write-wins single slot for the "is this litter?" answer:
     *  (candidateId, confirmed). Written on the MAIN thread by the prompt
     *  buttons, consumed at the top of [driveGroundCandidates] on the tick
     *  thread — the tracker and [groundCandidatePackets] are tick-thread
     *  structures and must never be mutated from main (audit finding, build
     *  165: the old direct calls raced ingest()/dismissedUnknown() on the
     *  exact tap the prompt exists for). Same handoff contract as
     *  [TapToConfirmEngine]'s pendingFocusTap. */
    private var pendingIntentAnswer: Pair<Long, Boolean>? = null

    actual fun confirmGroundIntentPrompt() {
        val prompt = _groundIntentPrompt.value ?: return
        // Queue for the tick thread; the tracker flips it back to SCANNING
        // with intent set there, and the same tick completes it (full
        // progress + intent) through the normal completion path: evidence
        // photo, geo-ref resolution, user_confirmed provenance.
        pendingIntentAnswer = prompt.id to true
        _groundIntentPrompt.value = null // instant UI dismiss (StateFlow is thread-safe)
    }

    actual fun rejectGroundIntentPrompt() {
        val prompt = _groundIntentPrompt.value ?: return
        pendingIntentAnswer = prompt.id to false
        _groundIntentPrompt.value = null
    }

    /** Tick-thread consumption of [pendingIntentAnswer] — all tracker and
     *  packet mutation happens here, where every other mutation runs. */
    private fun processPendingIntentAnswer(native: ArNativeSurface) {
        val (id, confirmed) = pendingIntentAnswer ?: return
        pendingIntentAnswer = null
        if (confirmed) {
            if (groundCandidateTracker.confirmIntent(id)) {
                ScanSessionTelemetry.active?.detection(
                    label = UNKNOWN_LITTER_LABEL, confidence = null,
                    distanceM = groundCandidatePackets[id]?.distanceM?.toDouble(),
                    source = "intent-prompt", outcome = "confirmed",
                )
            }
        } else if (groundCandidateTracker.rejectIntent(id)) {
            // Terminal immediately — the sweep will NOT re-emit this id, so
            // clear the reticle and photo state here (same contract as the
            // dismissedUnknown sweep).
            native.clearScanReticle(id)
            evictCandidatePhoto(id)
            val packet = groundCandidatePackets.remove(id)
            ScanSessionTelemetry.active?.detection(
                label = null, confidence = null, distanceM = packet?.distanceM?.toDouble(),
                source = "intent-prompt", outcome = "dismissed:not-litter",
            )
        }
    }

    /** Best-effort evidence frame for a tap confirmation — same silent
     *  captureFrame seam the auto path's evidence photos use. Null when no
     *  frame is available; never throws to the caller. */
    actual suspend fun captureTapEvidencePhoto(): CapturedPhoto? = try {
        captureFrame()?.takeIf { it.bytes.isNotEmpty() }
    } catch (e: Throwable) {
        DiagnosticsLog.error("env-ar", "tap evidence photo failed (item files without photo)", e)
        null
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

    actual fun setGroundCandidateCompletedListener(
        listener: ((lat: Double, lng: Double, photo: CapturedPhoto?, candidate: CompletedCandidate) -> Unit)?,
    ) {
        groundCandidateCompletedListener = listener
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
        // [TapToConfirmEngine.processPendingTap] kdoc for why it's queued
        // rather than handled synchronously off the tap.
        tapEngine.processPendingTap(frame)

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
            forwardCaptureDiagnostics(native.drainCaptureDiagnostics())
            // Thermal degrade chip (spec §1 degrade-then-warn): mirror the
            // recorder's live video tier onto the overlay state. -1 (no
            // recorder) and 0 (full) both read as "not degraded".
            _captureVideoDegraded.value = (native.captureVideoTierRaw() == 1)
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
            DetectionThermalBus.setPaused(true)
            dispatch_async(dispatch_get_main_queue()) { CameraDetectionBus.update(emptyList()) }
            return true
        }
        DetectionThermalBus.setPaused(false)

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

        // Surface context rides the same whole-frame labels — no extra Vision
        // cost. Consumers: the coverage-fan listener (per-cell ground tags)
        // and the session summary's surface breakdown.
        SurfaceContextBus.vote(commonObs)

        if (voteDue) {
            val vote = TrashDetectionRules.sceneVoteFrom(commonObs)
            dispatch_async(dispatch_get_main_queue()) { CameraSceneVoteBus.emit(vote) }
        }
        // Labels-only loop while disabled: scene votes above, but no
        // saliency, trash/element detection, or candidate promotion below.
        if (!analysisEnabled) return false

        // Ground-candidate flow (Task 6): LiDAR-only, native-surface only —
        // see [driveGroundCandidates]. Placed here, not earlier alongside the
        // capture/diag drains: thermal < 2 (checked above) AND analysisEnabled
        // (checked just above, same gate [analyzeTrash] below observes) both
        // hold at this point — a privacy/outdoor-gate pause of frame sampling
        // must stop the classification-crop Vision work this does exactly
        // like it stops [analyzeTrash]'s, not just on the fast path but also
        // on a labels-only vote-due tick, which is otherwise allowed past the
        // EARLIER `!analysisEnabled && !voteDue` guard. The detector itself
        // also thermal-gates Swift-side — skipping the whole flow above
        // thermal 2 is cheap belt-and-suspenders insurance regardless.
        if (native != null) {
            driveGroundCandidates(native, Clock.System.now().toEpochMilliseconds())
        }

        analyzeTrash(frame, native, commonObs, saliencyRequest)
        return false
    }

    /**
     * Ground-candidate flow (Task 6): drains Swift's LiDAR ground-anomaly
     * packets into [groundCandidateTracker], spends up to 2 classification
     * requests per tick resolving unclassified candidates
     * ([GroundCandidateTracker.candidatesAwaitingClassification] already caps
     * this — PROPOSED first, then any dwell/tap-promoted SCANNING candidate
     * that was never vetted), folds in this tick's dwell gaze, and mirrors
     * the tracker's SCANNING/COMPLETED/TIMED_OUT state onto the native
     * reticle pool. Tap-adoption (the tracker's 4th input) is wired
     * separately in [TapToConfirmEngine.processPendingTap] — it fires off the tap itself,
     * at the START of the tick, not here.
     *
     * Every detection event this emits fires at most once per candidate
     * lifecycle transition. Classification's accept/exclude diffs
     * [GroundCandidateTracker.activeScanning] before the loop
     * (`scanningBeforeClassify`) so it can tell the two candidatesAwaitingClassification
     * cases apart: an id NOT in that set is a genuine PROPOSED -> SCANNING
     * (or -> EXCLUDED) transition happening right now and gets an "accepted"/
     * "excluded:<label>" event; an id already IN that set is a dwell/tap-
     * promoted candidate that already emitted "accepted" (source=dwell/tap)
     * when IT transitioned earlier — classifying it now only types it (no
     * second "accepted") on a non-exclusion result, or retro-excludes it
     * (still emits "excluded:<label>" — that transition is new) and clears
     * its now-dead reticle via [ArNativeSurface.clearScanReticle], since it
     * had an active one (see `activeScanning` guaranteeing EXCLUDED ids are
     * never returned, mirrored the same way [timedOut] ids are cleared just
     * below in step 5). The dwell-accept diff below repeats the same
     * before/after pattern for its own bucket of ids; timeout and completion
     * are each terminal, emit-once transitions per the tracker's own
     * contract.
     */
    private fun driveGroundCandidates(native: ArNativeSurface, nowMs: Long) {
        // Step 0: consume any queued "is this litter?" answer FIRST — the
        // user's decision must land before this tick's sweeps (a confirm
        // returns the candidate to SCANNING so completedCandidates below
        // files it this same tick).
        processPendingIntentAnswer(native)

        // Step 1: ingest this tick's LiDAR anomaly packets. groundCandidatePackets
        // mirrors the latest geometry for every id fed to the tracker — see its
        // field kdoc for why: activeScanning() only returns (id, progress).
        val packets = native.drainGroundCandidates().mapNotNull(::parseGroundCandidatePacket)
        for (packet in packets) groundCandidatePackets[packet.id] = packet
        groundCandidateTracker.ingest(packets)

        // Step 2: classify up to 2 unclassified candidates this tick (PROPOSED,
        // or a dwell/tap-promoted SCANNING candidate still awaiting vetting).
        val scanningBeforeClassify = groundCandidateTracker.activeScanning().mapTo(mutableSetOf()) { it.first }
        for (candidate in groundCandidateTracker.candidatesAwaitingClassification()) {
            val alreadyScanning = candidate.id in scanningBeforeClassify
            val labels = parseSceneLabels(
                native.classifyRegionLabels(
                    candidate.imageCx, candidate.imageCy,
                    candidate.imageHalfW * 1.15, candidate.imageHalfH * 1.15,
                )
            )
            // classify() suppresses a fixture exclusion when a trash token
            // also matched — the crop always contains ground context, so
            // "bottle on grass" must classify as bottle, not grass. Backdrop
            // labels (grass/plant/soil) go SOFT, not terminal: the candidate
            // stays alive, attention-gated (build-164 rebuild).
            val decision = GroundExclusionRules.classify(labels)
            val exclusionLabel = decision.exclusionLabel
            val trashType = decision.trashType
            groundCandidateTracker.recordClassification(
                candidate.id, exclusionLabel, trashType, decision.backdropLabel,
            )
            if (decision.backdropLabel != null) {
                ScanSessionTelemetry.active?.detection(
                    label = decision.backdropLabel, confidence = null,
                    distanceM = candidate.distanceM.toDouble(),
                    source = "geometry", outcome = "backdrop:${decision.backdropLabel}",
                )
            }
            if (exclusionLabel != null) {
                // Silent, terminal — no caption either way. A PROPOSED id
                // never had a reticle; a dwell/tap-promoted (already-SCANNING)
                // id did, and this retro-exclude just killed it — clear it
                // explicitly, since activeScanning() will simply stop
                // reporting the id rather than signal the transition.
                groundCandidatePackets.remove(candidate.id)
                if (alreadyScanning) native.clearScanReticle(candidate.id)
                evictCandidatePhoto(candidate.id)
                ScanSessionTelemetry.active?.detection(
                    label = exclusionLabel, confidence = null, distanceM = candidate.distanceM.toDouble(),
                    source = "geometry", outcome = "excluded:$exclusionLabel",
                )
            } else if (!alreadyScanning && decision.backdropLabel == null) {
                // Only a genuine PROPOSED -> SCANNING transition is a fresh
                // acceptance; a dwell/tap-promoted candidate already emitted
                // "accepted" (source=dwell/tap) when IT transitioned — this
                // call just types it. Backdrop-soft candidates already
                // reported outcome=backdrop above.
                ScanSessionTelemetry.active?.detection(
                    label = trashType ?: UNKNOWN_LITTER_LABEL, confidence = null,
                    distanceM = candidate.distanceM.toDouble(),
                    source = "geometry", outcome = "accepted",
                )
            }
        }

        // Step 3: dwell. Camera pose is required for both the gaze gate and
        // the angle-bucket accrual — skip the frame entirely (rather than
        // faking a position) when no camera frame exists yet.
        val camPos = native.cameraPosition()
        if (camPos != null) {
            val scanningBeforeDwell = groundCandidateTracker.activeScanning().mapTo(mutableSetOf()) { it.first }
            groundCandidateTracker.recordDwell(
                cameraPos = floatArrayOf(camPos[0].toFloat(), camPos[1].toFloat(), camPos[2].toFloat()),
                cameraForward = native.cameraForward().toFloatArray(),
                frameNowMs = nowMs,
            )
            for ((id, _) in groundCandidateTracker.activeScanning()) {
                if (id in scanningBeforeDwell) continue // already SCANNING before this call — not a new promotion.
                ScanSessionTelemetry.active?.detection(
                    label = null, confidence = null,
                    distanceM = groundCandidatePackets[id]?.distanceM?.toDouble(),
                    source = "dwell", outcome = "accepted",
                )
            }
        }

        // Step 4: evidence photos (TestFlight 157). The first tick a SCANNING
        // candidate's latest packet puts the camera within
        // [CANDIDATE_PHOTO_MAX_DISTANCE_M], grab the frame NOW — the packet
        // was produced from the current frame, so the item is in view —
        // rather than at completion, by which point the camera has usually
        // panned away and the filed observation shipped photoless. One
        // request per candidate id; failures degrade silently to the
        // completion-time fallback grab below.
        for ((id, _) in groundCandidateTracker.activeScanning()) {
            val packet = groundCandidatePackets[id] ?: continue
            if (packet.distanceM >= CANDIDATE_PHOTO_MAX_DISTANCE_M) continue
            if (!candidatePhotoRequested.add(id)) continue
            scope.launch {
                try {
                    val photo = captureFrame()?.takeIf { it.bytes.isNotEmpty() } ?: return@launch
                    candidatePhotoMutex.withLock {
                        if (candidatePhotoCache.size < CANDIDATE_PHOTO_CACHE_MAX) {
                            candidatePhotoCache[id] = photo
                        }
                    }
                } catch (e: Throwable) {
                    DiagnosticsLog.error("env-ar", "close-range candidate photo failed (completion fallback remains)", e)
                }
            }
        }

        // Step 5: reap, THEN render. Ids that COMPLETE or TIME OUT this call
        // must stop streaming to setScanReticle in this SAME tick
        // (ArNativeSurface's setScanReticle contract). Reaping before
        // rendering also means a candidate that transitions to
        // AWAITING_INTENT in this tick's dismissedUnknown() stage-1 pass
        // renders its amber question state THIS tick instead of one tick
        // late (audit finding, build 165).
        groundCandidateTracker.timedOut().forEach { id ->
            native.clearScanReticle(id)
            evictCandidatePhoto(id)
            val packet = groundCandidatePackets.remove(id)
            ScanSessionTelemetry.active?.detection(
                label = null, confidence = null, distanceM = packet?.distanceM?.toDouble(),
                source = "geometry", outcome = "timeout",
            )
        }
        // Appearance gating (TestFlight 157, revised post-158): fully-scanned
        // candidates whose classification named nothing and that the user
        // never tapped first PARK as awaiting-intent (the tracker's stage 1)
        // — surfaced below as the "is this litter?" prompt — and are only
        // parked here once the prompt times out unanswered. Same
        // reticle-clear contract as timeout either way.
        groundCandidateTracker.dismissedUnknown().forEach { id ->
            native.clearScanReticle(id)
            evictCandidatePhoto(id)
            val packet = groundCandidatePackets.remove(id)
            ScanSessionTelemetry.active?.detection(
                label = null, confidence = null, distanceM = packet?.distanceM?.toDouble(),
                source = "geometry", outcome = "dismissed:unknown",
            )
        }
        val awaitingIds = groundCandidateTracker.awaitingIntent().toHashSet()
        groundCandidateTracker.activeScanning().forEach { (id, progress) ->
            val packet = groundCandidatePackets[id] ?: return@forEach
            native.setScanReticle(
                id, packet.x, packet.y, packet.z, progress,
                extentM = maxOf(packet.widthM, packet.depthM),
                state = if (id in awaitingIds) 1L else 0L,
            )
        }
        // Intent-prompt reconciliation: keep exactly one prompt mounted while
        // any candidate is awaiting the user's answer. The current prompt
        // stays until ITS id leaves the awaiting set (answered, world-tapped,
        // or reaped above); then the next awaiting id (if any) takes over.
        val awaitingIntent = groundCandidateTracker.awaitingIntent()
        val currentPrompt = _groundIntentPrompt.value
        if (currentPrompt == null || currentPrompt.id !in awaitingIntent) {
            _groundIntentPrompt.value = awaitingIntent.firstOrNull()?.let { id ->
                GroundIntentPrompt(id = id, distanceM = groundCandidatePackets[id]?.distanceM)
            }
        }
        groundCandidateTracker.completedCandidates().forEach { completed ->
            groundCandidatePackets.remove(completed.id)
            native.completeScanReticle(completed.id)
            val listener = groundCandidateCompletedListener
            if (listener != null) {
                // Same conversion the element-registration path uses
                // (registerElementIfAny below) -- GroundCandidateTracker
                // itself stays platform/geo-free, so the AR-local x/z ->
                // lat/lng resolution happens here, at the listener
                // invocation site, against the session's live geo
                // reference. Drop (never fire) if no geo-ref is paired yet
                // or the resolved coordinate is non-finite (same finiteness
                // gate registerElementIfAny applies) -- an unresolvable
                // position can't be filed as a world observation.
                val latLng = localToLatLng(completed.x, completed.z)
                if (latLng != null && latLng.first.isFinite() && latLng.second.isFinite()) {
                    val (lat, lng) = latLng
                    // Item photo: prefer the close-range frame stashed while
                    // the candidate was actually in view (Step 4) — the
                    // completion-time grab below is a fallback only, since by
                    // now the camera has usually panned off the item
                    // (TestFlight 157: observations filed photoless). Either
                    // way a failed grab degrades to a null photo, never
                    // blocks or drops the item itself.
                    scope.launch {
                        val stashed = candidatePhotoMutex.withLock { candidatePhotoCache.remove(completed.id) }
                        val photo = stashed ?: try {
                            captureFrame()?.takeIf { it.bytes.isNotEmpty() }
                        } catch (e: Throwable) {
                            DiagnosticsLog.error("env-ar", "completion photo captureFrame failed (item kept, no photo)", e)
                            null
                        }
                        dispatch_async(dispatch_get_main_queue()) { listener(lat, lng, photo, completed) }
                    }
                } else {
                    DiagnosticsLog.info(
                        "env-ar",
                        "ground candidate ${completed.id} (${completed.itemType}) completed with no usable geo-ref -- dropped",
                    )
                }
            }
        }
    }

    /** Tick-coroutine helper: forget a now-terminal candidate's photo state
     *  (request flag synchronously; the mutex-guarded byte cache async). */
    private fun evictCandidatePhoto(id: Long) {
        candidatePhotoRequested.remove(id)
        scope.launch { candidatePhotoMutex.withLock { candidatePhotoCache.remove(id) } }
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

    /**
     * Classification observations for a crop centred on a salient box — the
     * saliency-crop fallback's Vision pass. Native surfaces classify in
     * Swift ([ArNativeSurface.classifyRegionLabels], same "identifier|conf"
     * packet format as classifySceneLabels); the SceneKit path reuses the
     * tap engine's region-of-interest classify. The box arrives in Vision's
     * bottom-left-origin space; both callees want top-left image space
     * (same conversion [selectSalientObject] documents). ARKit/Vision-bound
     * — no test harness in this repo; the promotion RULE it feeds is the
     * tested part ([TrashDetectionRules.cropPromotionLabelFrom]).
     */
    private fun classifyCropObservations(
        frame: ARFrame?,
        native: ArNativeSurface?,
        box: VNRectangleObservation,
    ): List<ClassificationObservation> {
        var cx = 0.5
        var cyTopLeft = 0.5
        var halfW = CROP_MIN_HALF_EXTENT
        var halfH = CROP_MIN_HALF_EXTENT
        box.boundingBox.useContents {
            cx = origin.x + size.width / 2.0
            cyTopLeft = 1.0 - (origin.y + size.height / 2.0)
            halfW = (size.width / 2.0 * CROP_CONTEXT_SCALE)
                .coerceIn(CROP_MIN_HALF_EXTENT, CROP_MAX_HALF_EXTENT)
            halfH = (size.height / 2.0 * CROP_CONTEXT_SCALE)
                .coerceIn(CROP_MIN_HALF_EXTENT, CROP_MAX_HALF_EXTENT)
        }
        native?.let { return parseSceneLabels(it.classifyRegionLabels(cx, cyTopLeft, halfW, halfH)) }
        return tapEngine.classifyRegion(frame, cx, cyTopLeft, maxOf(halfW, halfH) * 2.0)
    }

    private fun analyzeTrash(
        frame: ARFrame?,
        native: ArNativeSurface?,
        classifications: List<ClassificationObservation>,
        saliencyRequest: VNGenerateObjectnessBasedSaliencyImageRequest?,
    ) {
        // While a candidate is pending the loop idles: the card is on screen
        // and the reticle freezes on the locked item's projected position.
        // Minor B: the multi-item layer must NOT freeze along with it — a
        // stale box list left published here could mis-tag a photo captured
        // during the confirm-card window against an item that's no longer
        // (or never was) at that position.
        if (_trashCandidate.value != null) {
            _multiItemReticles.value = emptyList()
            return
        }

        // Global constraint 10/11: automatic promotion requires parent-context
        // suppression. A user tap bypasses this entirely (see focusAt).
        var promotedLabel = TrashDetectionRules.autoPromotionLabelFrom(classifications)
        // Whichever observation list actually produced the label — whole
        // frame, or (fallback below) the winning saliency crop's. Typing and
        // confidence must read from the same list the label came from.
        var labelObservations = classifications

        // Saliency runs BEFORE the label gate now (build 164): the crop
        // fallback needs the boxes precisely on the ticks the whole-frame
        // pass names nothing. While a tap-to-focus (focusAt) is active,
        // [selectSalientObject] biases toward the candidate nearest the
        // tapped point instead of Vision's top-ranked one.
        val effectiveSaliency = saliencyOnDemand(native, saliencyRequest)
        val candidates = effectiveSaliency?.results
            ?.filterIsInstance<VNSaliencyImageObservation>()
            ?.firstOrNull()
            ?.salientObjects
            ?.filterIsInstance<VNRectangleObservation>()
            ?: emptyList()

        // Saliency-crop fallback: a wide outdoor frame rarely ranks a small
        // ground item at TRASH_CONFIDENCE, so when the whole-frame pass
        // misses, classify a crop concentrated on each salient object — the
        // same trick the tap path (classifyTapped) and the ground-candidate
        // path (classifyRegionLabels) already rely on. Every downstream gate
        // (near-streak, raycast, suppression radius, confirm card) is
        // unchanged; this only restores the sighting the classifier lost to
        // frame-wide dilution.
        var cropSalient: VNRectangleObservation? = null
        if (promotedLabel == null) {
            for (box in candidates.take(CROP_CLASSIFY_MAX_PER_TICK)) {
                val cropObs = classifyCropObservations(frame, native, box)
                val label = TrashDetectionRules.cropPromotionLabelFrom(cropObs, classifications)
                if (label != null) {
                    promotedLabel = label
                    labelObservations = cropObs
                    cropSalient = box
                    break
                }
            }
        }
        val trashLabel = promotedLabel ?: run {
            nearStreak = 0
            dispatch_async(dispatch_get_main_queue()) { CameraDetectionBus.update(emptyList()) }
            _multiItemReticles.value = emptyList()
            // No trash in view: a landmark element tick instead — trees,
            // fences, gates… register to the grid so future scans expect
            // them. (Only on trash-less ticks: a bag stuck in a bush must
            // register as the bag.)
            registerElementIfAny(frame, native, classifications, saliencyRequest)
            return
        }
        // The raw identifier that produced the label carries the fine-grained
        // tokens ("glass_bottle" vs "bottle") the canonical typing needs.
        val rawIdentifier = labelObservations.firstOrNull { obs ->
            obs.confidence >= 0.35f &&
                TrashDetectionRules.tokensOf(obs.identifier).any { it in TrashDetectionRules.TRASH_TOKENS }
        }?.identifier ?: trashLabel
        val confidence = labelObservations.firstOrNull { it.identifier == rawIdentifier }?.confidence ?: 0.5f

        // Salient region -> reticle position + angular size. A crop-promoted
        // label pins the reticle to the box that produced it.
        var visionX = 0.5
        var visionY = 0.5
        var radius = 0.12f
        var bboxWidthFrac = 0.2
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val focusActive = nowMs <= focusUntilMs
        // Task 17: multi-item reticle layer — reuses [candidates] above
        // (zero extra saliency cost); classifyRegion only spends extra Vision
        // requests when there's actually more than one box to disambiguate.
        updateMultiItemReticles(frame, candidates)
        val salient = cropSalient
            ?: selectSalientObject(candidates, if (focusActive) focusImagePoint else null)
        salient?.boundingBox?.useContents {
            visionX = origin.x + size.width / 2.0
            visionY = origin.y + size.height / 2.0
            radius = (maxOf(size.width, size.height) / 2.0).toFloat().coerceIn(0.06f, 0.35f)
            bboxWidthFrac = size.width
        }

        val imageX = visionX
        val imageY = 1.0 - visionY
        val hit = tapEngine.raycastImage(frame, imageX, imageY)
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
     * Multi-item reticle layer (Task 17), additive to the single-candidate
     * lock-on above: from the saliency boxes [analyzeTrash] already computed
     * for [selectSalientObject] (zero extra saliency cost — this is called
     * with the SAME `candidates` list), builds one [DetectionReticle] per
     * box and publishes the deduped/capped result for the overlay + capture
     * tagging.
     *
     * Performance guard: region classification ([TapToConfirmEngine.classifyRegion]) is skipped
     * entirely when there's 0-1 boxes. The 0-1 case is also where [analyzeTrash]'s
     * own whole-frame lock-on (the single [TrashCandidate] reticle) already
     * owns rendering, so this layer publishes nothing for it (I3) — only
     * when saliency finds >= 2 boxes does this spend up to
     * [MAX_MULTI_ITEM_RETICLES] additional Vision requests, one per box —
     * see the task-17 report for the full per-tick Vision request-count
     * accounting.
     */
    private fun updateMultiItemReticles(
        frame: ARFrame?,
        candidates: List<VNRectangleObservation>,
    ) {
        // I3: the 0-box AND 1-box cases both defer to the whole-frame
        // lock-on path (analyzeTrash's own TrashCandidate promotion) — it
        // already owns the single-object case end-to-end (reticle, streak,
        // promotion). Publishing a reticle here too double-drew the same
        // object via two independent overlays.
        if (candidates.size <= 1) {
            _multiItemReticles.value = emptyList()
            return
        }
        val classified = candidates.take(MAX_MULTI_ITEM_RETICLES).map { obs ->
            val box = obs.toReticleBox()
            val fraction = box.side.coerceIn(0.2, 0.5)
            val observations = tapEngine.classifyRegion(frame, box.imageX, box.imageY, fraction)
            val label = TrashDetectionRules.autoPromotionLabelFrom(observations)
            val confidence = label?.let { TrashDetectionRules.trashConfidenceFor(it, observations) }
            DetectionReticle(
                centerX = box.viewX, centerY = box.viewY,
                width = box.side.toFloat(), height = box.side.toFloat(),
                label = label, confidence = confidence, isLitter = label != null,
            )
        }
        _multiItemReticles.value = dedupeReticles(classified)
    }

    /** A saliency box's geometry in both coordinate systems Task 17 needs:
     *  [imageX]/[imageY] (Vision's bottom-left origin flipped to the top-left
     *  "image" convention [TapToConfirmEngine.classifyRegion] expects — same conversion
     *  [analyzeTrash] uses for `imageX`/`imageY`) and [viewX]/[viewY] (the
     *  portrait-screen convention [DetectionReticle]/`LiveDetection.cx`/`cy`
     *  use, i.e. `viewX = 1 - imageY`, `viewY = imageX`). [side] is the
     *  larger of the two normalized Vision bbox dimensions, clamped to
     *  double [analyzeTrash]'s `radius` clamp ([0.06, 0.35]) since it plays
     *  the same "full box size" role [DetectionReticle.width]/`height` need,
     *  where `radius` there is a half-size. */
    private class ReticleBox(val imageX: Double, val imageY: Double, val viewX: Float, val viewY: Float, val side: Double)

    private fun VNRectangleObservation.toReticleBox(): ReticleBox {
        var visionX = 0.5
        var visionY = 0.5
        var side = 0.2
        boundingBox.useContents {
            visionX = origin.x + size.width / 2.0
            visionY = origin.y + size.height / 2.0
            side = maxOf(size.width, size.height).coerceIn(0.12, 0.7)
        }
        val imageX = visionX
        val imageY = 1.0 - visionY
        val viewX = (1.0 - imageY).toFloat()
        val viewY = imageX.toFloat()
        return ReticleBox(imageX, imageY, viewX, viewY, side)
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
        val hit = tapEngine.raycastImage(frame, visionX, 1.0 - visionY) ?: return
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
        // acknowledgment.
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
            val center = vectorFloats(anchor.center)
            // Unclassified (0) planes count as ground only within a height
            // band of the established floor — car roofs/truck beds must not
            // inflate the never-shrinking union (mirrors GccArNative.swift's
            // upsertPlane band; same grey-cover follow-up).
            val isFloor = when (classification) {
                2L -> true
                0L -> {
                    // ONE-SIDED: reject only planes ABOVE the floor — a
                    // symmetric band deadlocks bootstrap when the first
                    // accepted plane was itself elevated (see the Swift
                    // mirror's comment in GccArNative.upsertPlane).
                    val knownFloor = floorY
                    val anchorY = matrixFloats(anchor.transform)[13] + center[1]
                    knownFloor == null || anchorY - knownFloor <= 0.35f
                }
                else -> false
            }
            if (!isFloor) continue
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
     * (tracker origin set + geo-ref paired). Snapping AND the UV scale both
     * use [GRID_MAJOR_TILE_METERS]: this path has no UV offset, so texture
     * repeats can only register with the lattice if the plane edges land on
     * repeat boundaries — snapped edges sit on the 3m cell lattice the
     * minimap draws (which is also the 1m minor lattice), and each repeat's
     * interior lines land on [GRID_TILE_METERS] multiples. Mirrors
     * GccArNative.swift's applyPlaneGeometry. Called on union growth; mesh
     * width/height writes are skipped when the snapped size is unchanged.
     */
    private fun applyFloorGridGeometry(node: SCNNode) {
        val rawMinX = floorMinX ?: return
        val rawMaxX = floorMaxX ?: return
        val rawMinZ = floorMinZ ?: return
        val rawMaxZ = floorMaxZ ?: return
        // One texture repeat = one MAJOR tile (3m). Dividing by the minor
        // interval instead renders the whole 3×3 texture per metre (minor
        // lines every 0.33m) — the review finding this constant fixes.
        val tile = GRID_MAJOR_TILE_METERS.toDouble()
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
        // Neon restored on Blake's word (TestFlight 145: the emission-less
        // grid read as a shadow — invisible in daylight). Mirrors the
        // RealityKit path's emissive material; keep the two identical.
        material.emission.contents = texture
        material.emission.wrapS = platform.SceneKit.SCNWrapModeRepeat
        material.emission.wrapT = platform.SceneKit.SCNWrapModeRepeat
        material.doubleSided = true
        // Obey the depth buffer so real geometry in front of the ground hides
        // the grid. Still does NOT write depth: a translucent surface that
        // writes depth occludes whatever is drawn after it.
        material.readsFromDepthBuffer = true
        material.writesToDepthBuffer = false
        material.transparency = 0.55
        plane.materials = listOf(material)

        val planeChild = SCNNode.nodeWithGeometry(plane)
        planeChild.eulerAngles = SCNVector3Make((-kotlin.math.PI / 2).toFloat(), 0f, 0f)
        val container = SCNNode()
        container.addChildNode(planeChild)
        return container
    }

    // ── Guidance ─────────────────────────────────────────────────────

    /**
     * Guidance in the camera view is the frontier BEACON (spec 2026-07-10):
     * a single pooled ground ring the native surface plants at the target —
     * the home-walkthrough-style "scan here" landmark. (The camera-attached
     * arrow stays deleted: it rendered as an unexplained neon green bar and
     * performed no scanning.) The minimap ring + text prompt remain the
     * far-range guidance; the beacon only plants within
     * [BEACON_MAX_RANGE_METERS] of the geo reference — `latLngToLocal` is
     * only trustworthy near the reference point, and a beacon planted on the
     * wrong patch is worse than no beacon.
     */
    private fun renderGuidance() {
        val target = guidanceLatLng
        if (!hasGeoRef || target == null) {
            nativeSurface?.clearGuidanceTarget()
            return
        }
        val local = latLngToLocal(target.first, target.second, refLat, refLng, refX, refZ, lngScale)
        val dx = local.first - refX
        val dz = local.second - refZ
        if (dx * dx + dz * dz > BEACON_MAX_RANGE_METERS * BEACON_MAX_RANGE_METERS) {
            nativeSurface?.clearGuidanceTarget()
            return
        }
        nativeSurface?.setGuidanceTarget(local.first, local.second)
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
     * A transparent 3×3-cell texture: a thin line on the interior cell
     * boundaries (the 1 m minor interval, [GRID_TILE_METERS]) and a heavier
     * line on the texture's own outer boundary. One texture repeat is scaled
     * to span [GRID_MAJOR_TILE_METERS] (see applyFloorGridGeometry), so the
     * heavy edge line lands on every repeat boundary — the 3 m major
     * interval. [withDiagonals] adds an X per cell for the denser "vector
     * scan" look. Minor lines draw at alpha 0.5, major lines at alpha 0.9 —
     * both derived from [line]'s hue so callers keep passing one color.
     * Mirrored by GccArNative.swift's makeGridTexture — keep them visually
     * identical.
     */
    private fun makeGridTexture(
        line: UIColor,
        lineWidth: Double,
        withDiagonals: Boolean = false,
        sizePx: Double = 129.0,
    ): UIImage? {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(sizePx, sizePx), false, 1.0)
        val third = sizePx / 3.0

        // Minor grid: one line per interior cell boundary.
        val minorPath = UIBezierPath.bezierPath()
        for (i in 1..2) {
            val pos = third * i
            minorPath.moveToPoint(CGPointMake(0.0, pos))
            minorPath.addLineToPoint(CGPointMake(sizePx, pos))
            minorPath.moveToPoint(CGPointMake(pos, 0.0))
            minorPath.addLineToPoint(CGPointMake(pos, sizePx))
        }
        if (withDiagonals) {
            for (row in 0..2) {
                for (col in 0..2) {
                    val x0 = third * col
                    val y0 = third * row
                    minorPath.moveToPoint(CGPointMake(x0, y0))
                    minorPath.addLineToPoint(CGPointMake(x0 + third, y0 + third))
                    minorPath.moveToPoint(CGPointMake(x0 + third, y0))
                    minorPath.addLineToPoint(CGPointMake(x0, y0 + third))
                }
            }
        }
        minorPath.lineWidth = lineWidth
        line.colorWithAlphaComponent(0.5).setStroke()
        minorPath.stroke()

        // Major grid: only the texture's outer boundary — repeats every 3rd
        // tile once the material wraps this texture.
        val majorPath = UIBezierPath.bezierPath()
        majorPath.moveToPoint(CGPointMake(0.0, 0.5))
        majorPath.addLineToPoint(CGPointMake(sizePx, 0.5))
        majorPath.moveToPoint(CGPointMake(0.5, 0.0))
        majorPath.addLineToPoint(CGPointMake(0.5, sizePx))
        majorPath.lineWidth = lineWidth * 1.5
        line.colorWithAlphaComponent(0.9).setStroke()
        majorPath.stroke()

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
