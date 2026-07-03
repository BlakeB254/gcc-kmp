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
import platform.ARKit.ARSCNView
import platform.CoreGraphics.CGPointMake
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
 *    (< [PERSIST_DISTANCE_METERS]) for two consecutive ticks — the
 *    "scanned close, so recreate it in the world" behavior.
 *
 * Vision work runs off the main thread; SceneKit mutations and the vote
 * listener are dispatched back to the main queue. A Vision error backs the
 * loop off (thermal/throttling guard) rather than failing the AR session.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ArFrameFeedback(
    private val sceneViewProvider: () -> ARSCNView?,
) {
    companion object {
        private const val TICK_MS = 1_000L
        private const val ERROR_BACKOFF_MS = 3_000L
        private const val SCENE_VOTE_EVERY_TICKS = 2
        private const val SCENE_CONFIDENCE = 0.4f
        private const val TRASH_CONFIDENCE = 0.35f
        private const val PERSIST_DISTANCE_METERS = 2.0
        private const val PERSIST_STREAK_TICKS = 2
        private const val MARKER_DEDUPE_METERS = 3.0

        private val INDOOR_KEYWORDS = setOf(
            "indoor", "room", "ceiling", "wall", "kitchen", "bathroom", "bedroom",
            "office", "hallway", "furniture", "sofa", "couch", "desk", "carpet",
            "curtain", "shelf", "stairs", "interior",
        )
        private val OUTDOOR_KEYWORDS = setOf(
            "outdoor", "street", "road", "sidewalk", "sky", "tree", "grass",
            "park", "building", "asphalt", "pavement", "cloud", "field",
            "vehicle", "car", "house", "fence", "yard",
        )

        /** Token → reticle label. Tokens match `_`/space-split Vision identifiers. */
        private val TRASH_TOKENS = mapOf(
            "garbage" to "Trash", "trash" to "Trash", "waste" to "Trash",
            "litter" to "Litter", "rubbish" to "Trash", "debris" to "Debris",
            "bottle" to "Bottle", "can" to "Can", "cup" to "Cup", "bag" to "Bag",
            "plastic" to "Plastic", "wrapper" to "Wrapper", "carton" to "Carton",
            "tire" to "Tire", "mattress" to "Mattress",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var tick = 0
    private var closeStreak = 0
    private var enabled = true

    /** World positions of placed markers, for the 3m dedupe. */
    private val markerPositions = mutableListOf<Triple<Float, Float, Float>>()

    private val _liveDetections = MutableStateFlow<List<LiveDetection>>(emptyList())
    val liveDetections: StateFlow<List<LiveDetection>> = _liveDetections.asStateFlow()

    var sceneVoteListener: ((SceneVote) -> Unit)? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) _liveDetections.value = emptyList()
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                delay(TICK_MS)
                if (!enabled) continue
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
        val frame = sceneViewProvider()?.session?.currentFrame ?: return false
        val pixelBuffer = frame.capturedImage ?: return false
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

        val trashLabel = observations.firstNotNullOfOrNull { obs ->
            if (obs.confidence < TRASH_CONFIDENCE) return@firstNotNullOfOrNull null
            tokensOf(obs.identifier).firstNotNullOfOrNull { TRASH_TOKENS[it] }
        }
        if (trashLabel == null) {
            closeStreak = 0
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
        val hit = frame.hitTest(
            CGPointMake(imageX, imageY),
            types = ARHitTestResultTypeEstimatedHorizontalPlane or ARHitTestResultTypeFeaturePoint,
        )?.firstOrNull() as? ARHitTestResult
        val distance = hit?.distance?.toFloat()

        // Landscape sensor image → portrait view: rotate 90° clockwise.
        val viewX = (1.0 - imageY).toFloat()
        val viewY = imageX.toFloat()
        _liveDetections.value = listOf(
            LiveDetection(cx = viewX, cy = viewY, r = radius, label = trashLabel, distanceMeters = distance)
        )

        // Close for two consecutive ticks → persist a world marker at the hit.
        if (distance != null && distance <= PERSIST_DISTANCE_METERS) {
            closeStreak += 1
            if (closeStreak >= PERSIST_STREAK_TICKS && hit != null) {
                val floats = matrixTranslation(hit.worldTransform)
                addMarkerAt(floats[0], floats[1], floats[2], WorldMarkerKind.CONFIRMED_TRASH)
                closeStreak = 0
            }
        } else {
            closeStreak = 0
        }
        return false
    }

    private fun emitSceneVote(observations: List<VNClassificationObservation>) {
        var indoorHits = 0
        var outdoorHits = 0
        for (obs in observations) {
            if (obs.confidence < SCENE_CONFIDENCE) continue
            val tokens = tokensOf(obs.identifier)
            if (tokens.any { it in INDOOR_KEYWORDS }) indoorHits += 1
            if (tokens.any { it in OUTDOOR_KEYWORDS }) outdoorHits += 1
        }
        val vote = when {
            indoorHits > outdoorHits && indoorHits > 0 -> SceneVote.INDOOR
            outdoorHits > indoorHits && outdoorHits > 0 -> SceneVote.OUTDOOR
            else -> SceneVote.NONE
        }
        if (vote == SceneVote.NONE) return
        val listener = sceneVoteListener ?: return
        dispatch_async(dispatch_get_main_queue()) { listener(vote) }
    }

    /**
     * Add a persistent ring marker at an AR world position (main queue),
     * unless one already exists within [MARKER_DEDUPE_METERS] horizontally.
     */
    fun addMarkerAt(x: Float, y: Float, z: Float, kind: WorldMarkerKind) {
        val exists = markerPositions.any { (mx, _, mz) ->
            val dx = (mx - x).toDouble()
            val dz = (mz - z).toDouble()
            sqrt(dx * dx + dz * dz) < MARKER_DEDUPE_METERS
        }
        if (exists) return
        markerPositions.add(Triple(x, y, z))
        DiagnosticsLog.info("ar-marker", "placed $kind marker #${markerPositions.size}")

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

    private fun tokensOf(identifier: String): List<String> =
        identifier.lowercase().split('_', ' ', ',').filter { it.isNotBlank() }

    private fun matrixTranslation(transform: CValue<simd_float4x4>): List<Float> = memScoped {
        val matrix = alloc<simd_float4x4>()
        transform.place(matrix.ptr)
        val values = matrix.ptr.reinterpret<FloatVar>()
        // Column-major simd_float4x4: translation lives at indices 12..14.
        listOf(values[12], values[13], values[14])
    }
}
