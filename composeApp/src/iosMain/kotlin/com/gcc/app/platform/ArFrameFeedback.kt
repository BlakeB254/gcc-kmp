package com.gcc.app.platform

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.ARKit.ARFrame
import platform.ARKit.ARHitTestResult
import platform.ARKit.ARHitTestResultTypeEstimatedHorizontalPlane
import platform.ARKit.ARHitTestResultTypeFeaturePoint
import platform.ARKit.ARPlaneAnchor
import platform.ARKit.ARSCNView
import platform.CoreGraphics.CGPointMake
import platform.Foundation.valueForKey
import platform.SceneKit.SCNAction
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNTorus
import platform.SceneKit.SCNVector3Make
import platform.UIKit.UIColor
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNGenerateObjectnessBasedSaliencyImageRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRectangleObservation
import platform.Vision.VNSaliencyImageObservation
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.simd_float4x4
import kotlin.math.sqrt

/**
 * Shared per-overlay AR frame-sampling pipeline (iOS only): every tick it
 * pulls `session.currentFrame`, runs one Vision pass, and produces
 *
 *  - indoor/outdoor [SceneVote]s for the OutdoorGate (every other tick),
 *  - [LiveDetection]s when the classification looks like trash, positioned
 *    via the objectness saliency bounding box,
 *  - persistent world-anchored ring markers once a detection has been close
 *    for two consecutive ticks (see [MarkerPromotionTracker]) — the
 *    "scanned close, so recreate it in the world" behavior.
 *
 * Vision work runs off the main thread; SceneKit mutations and the vote
 * listener are dispatched back to the main queue. A Vision error backs the
 * loop off (thermal/throttling guard) rather than failing the AR session.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ArFrameFeedback(
    private val sceneViewProvider: () -> ARSCNView?,
    /** When it returns a surface, frames/raycasts/markers go through the
     *  native RealityKit layer instead of the ARSCNView. */
    private val nativeSurfaceProvider: () -> ArNativeSurface? = { null },
) {
    companion object {
        private const val TICK_MS = 1_000L
        private const val ERROR_BACKOFF_MS = 3_000L
        private const val SCENE_VOTE_EVERY_TICKS = 2
        private const val MARKER_DEDUPE_METERS = 3.0

    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var tick = 0
    private val promotionTracker = MarkerPromotionTracker()
    private var enabled = true

    /** World positions of placed markers, for the 3m dedupe. */
    private val markerPositions = mutableListOf<Triple<Float, Float, Float>>()

    private val _liveDetections = MutableStateFlow<List<LiveDetection>>(emptyList())
    val liveDetections: StateFlow<List<LiveDetection>> = _liveDetections.asStateFlow()

    var sceneVoteListener: ((SceneVote) -> Unit)? = null
    var ceilingListener: ((Boolean) -> Unit)? = null
    var markerPlacedListener: ((x: Float, y: Float, z: Float, label: String) -> Unit)? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) _liveDetections.value = emptyList()
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                delay(TICK_MS)
                if (!enabled) {
                    try { emitCeilingCue() } catch (_: Throwable) {}
                    continue
                }
                val backoff = try {
                    analyzeTick()
                } catch (e: Throwable) {
                    DiagnosticsLog.error("ar-vision", "frame analysis tick failed (backing off)", e)
                    true
                }
                if (backoff) delay(ERROR_BACKOFF_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _liveDetections.value = emptyList()
    }

    /** Returns true when the loop should back off (error / no frame). */
    private fun analyzeTick(): Boolean {
        emitCeilingCue()

        val native = nativeSurfaceProvider()
        val frame = if (native == null) sceneViewProvider()?.session?.currentFrame else null
        val pixelBuffer = (native?.pixelBufferOrNull() ?: frame?.capturedImage) ?: return false
        tick += 1

        val classifyRequest = VNClassifyImageRequest()
        val saliencyRequest = VNGenerateObjectnessBasedSaliencyImageRequest()
        val handler = VNImageRequestHandler(
            cVPixelBuffer = pixelBuffer,
            options = emptyMap<Any?, Any>(),
        )
        handler.performRequests(listOf(classifyRequest, saliencyRequest), error = null)

        val observations = classifyRequest.results
            ?.filterIsInstance<VNClassificationObservation>()
            ?.sortedByDescending { it.confidence }
            ?.take(15)
            ?: return true

        if (tick % SCENE_VOTE_EVERY_TICKS == 0) {
            emitSceneVote(observations)
        }

        val trashLabel = TrashDetectionRules.trashLabelFrom(
            observations.map { ClassificationObservation(it.identifier, it.confidence) }
        )
        if (trashLabel == null) {
            promotionTracker.onTick(null)
            _liveDetections.value = emptyList()
            return false
        }

        // Salient region → reticle position; fall back to frame center.
        var visionX = 0.5
        var visionY = 0.5
        var radius = 0.12f
        val salient = saliencyRequest.results
            ?.filterIsInstance<VNSaliencyImageObservation>()
            ?.firstOrNull()
            ?.salientObjects
            ?.filterIsInstance<VNRectangleObservation>()
            ?.firstOrNull()
        salient?.boundingBox?.useContents {
            visionX = origin.x + size.width / 2.0
            visionY = origin.y + size.height / 2.0
            radius = (maxOf(size.width, size.height) / 2.0).toFloat().coerceIn(0.06f, 0.35f)
        }

        // Vision uses bottom-left origin; ARFrame.hitTest wants top-left image space.
        val imageX = visionX
        val imageY = 1.0 - visionY
        var hitWorld: FloatArray? = null
        var distance: Float? = null
        if (native != null) {
            val rayHit = native.raycast(imageX, imageY)
            val cam = native.cameraPosition()
            if (rayHit != null && cam != null) {
                hitWorld = floatArrayOf(rayHit[0].toFloat(), rayHit[1].toFloat(), rayHit[2].toFloat())
                val dx = rayHit[0] - cam[0]
                val dy = rayHit[1] - cam[1]
                val dz = rayHit[2] - cam[2]
                distance = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
            }
        } else {
            val hit = frame?.hitTest(
                CGPointMake(imageX, imageY),
                types = ARHitTestResultTypeEstimatedHorizontalPlane or ARHitTestResultTypeFeaturePoint,
            )?.firstOrNull() as? ARHitTestResult
            if (hit != null) {
                hitWorld = matrixTranslation(hit.worldTransform).toFloatArray()
                distance = hit.distance.toFloat()
            }
        }

        // Landscape sensor image → portrait view: rotate 90° clockwise.
        val viewX = (1.0 - imageY).toFloat()
        val viewY = imageX.toFloat()
        // Close for two consecutive ticks → persist a world marker at the hit.
        // Called before building this tick's LiveDetection so the reticle can
        // show this tick's progress, not last tick's.
        val progress = promotionTracker.onTick(distance?.toDouble())
        _liveDetections.value = listOf(
            LiveDetection(
                cx = viewX, cy = viewY, r = radius, label = trashLabel, distanceMeters = distance,
                streakProgress = progress.streak, streakTicks = progress.streakTicks,
                justPromoted = progress.promoted,
            )
        )
        if (progress.promoted && hitWorld != null) {
            addMarkerAt(hitWorld[0], hitWorld[1], hitWorld[2], WorldMarkerKind.CONFIRMED_TRASH, trashLabel)
        }
        return false
    }

    /**
     * Ceiling cue for the OutdoorGate — cheap (anchors are already tracked;
     * no Vision work involved) so it can run every tick, including while
     * disabled (e.g. once the gate has flipped INDOOR and Vision analysis
     * itself is paused).
     */
    private fun emitCeilingCue() {
        val native = nativeSurfaceProvider()
        val ceiling = if (native != null) {
            native.ceilingDetected()
        } else {
            sceneViewProvider()?.session?.currentFrame?.anchors
                ?.filterIsInstance<ARPlaneAnchor>()
                ?.any { anchor ->
                    val classification = try {
                        (anchor as platform.darwin.NSObject).valueForKey("classification") as? Long ?: 0L
                    } catch (_: Throwable) { 0L }
                    classification == 3L // ARPlaneClassification.ceiling
                } ?: false
        }
        ceilingListener?.let { listener ->
            dispatch_async(dispatch_get_main_queue()) { listener(ceiling) }
        }
    }

    private fun emitSceneVote(observations: List<VNClassificationObservation>) {
        val vote = SceneVoteVision.voteFrom(observations)
        val listener = sceneVoteListener ?: return
        // NONE flows too — repeated absence of outdoor cues is evidence.
        dispatch_async(dispatch_get_main_queue()) { listener(vote) }
    }

    /**
     * Add a persistent ring marker at an AR world position (main queue),
     * unless one already exists within [MARKER_DEDUPE_METERS] horizontally.
     */
    fun addMarkerAt(x: Float, y: Float, z: Float, kind: WorldMarkerKind, label: String = "") {
        val exists = markerPositions.any { (mx, _, mz) ->
            val dx = (mx - x).toDouble()
            val dz = (mz - z).toDouble()
            sqrt(dx * dx + dz * dz) < MARKER_DEDUPE_METERS
        }
        if (exists) return
        markerPositions.add(Triple(x, y, z))
        DiagnosticsLog.info("ar-marker", "placed $kind marker #${markerPositions.size}")

        if (kind == WorldMarkerKind.CONFIRMED_TRASH) {
            val listener = markerPlacedListener
            if (listener != null) {
                dispatch_async(dispatch_get_main_queue()) { listener(x, y, z, label) }
            }
        }

        nativeSurfaceProvider()?.let { surface ->
            dispatch_async(dispatch_get_main_queue()) {
                surface.addMarker(
                    x.toDouble(), y.toDouble(), z.toDouble(),
                    kind = if (kind == WorldMarkerKind.CONFIRMED_TRASH) 0 else 1,
                )
            }
            return
        }

        dispatch_async(dispatch_get_main_queue()) {
            val scene = sceneViewProvider()?.scene ?: return@dispatch_async
            val torus = SCNTorus.torusWithRingRadius(0.18, 0.02)
            val material = SCNMaterial()
            material.diffuse.contents = when (kind) {
                WorldMarkerKind.CONFIRMED_TRASH ->
                    UIColor.greenColor.colorWithAlphaComponent(0.9)
                WorldMarkerKind.EXPECTED_TRASH ->
                    UIColor.orangeColor.colorWithAlphaComponent(0.9)
            }
            torus.materials = listOf(material)
            val node = SCNNode.nodeWithGeometry(torus)
            node.position = SCNVector3Make(x, y, z)
            if (kind == WorldMarkerKind.EXPECTED_TRASH) {
                // Gentle pulse so expected items read as "come check this".
                node.runAction(
                    SCNAction.repeatActionForever(
                        SCNAction.sequence(
                            listOf(
                                SCNAction.scaleTo(1.35, duration = 0.8),
                                SCNAction.scaleTo(1.0, duration = 0.8),
                            )
                        )
                    )
                )
            }
            scene.rootNode.addChildNode(node)
        }
    }

    private fun matrixTranslation(transform: CValue<simd_float4x4>): List<Float> = memScoped {
        val matrix = alloc<simd_float4x4>()
        transform.place(matrix.ptr)
        val values = matrix.ptr.reinterpret<FloatVar>()
        // Column-major simd_float4x4: translation lives at indices 12..14.
        listOf(values[12], values[13], values[14])
    }
}
