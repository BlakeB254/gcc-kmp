package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.CVPixelBufferRef
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNImageRequestHandler

/**
 * Indoor/outdoor scene voting from Vision classifications — shared by the AR
 * frame pipeline ([ArFrameFeedback]) and the plain AVCapture viewfinder tap
 * (see CameraManager's scene sampling), so both camera modes feed the
 * OutdoorGate the same way.
 */
@OptIn(ExperimentalForeignApi::class)
internal object SceneVoteVision {

    private const val SCENE_CONFIDENCE = 0.4f

    val INDOOR_KEYWORDS = setOf(
        "indoor", "room", "ceiling", "wall", "kitchen", "bathroom", "bedroom",
        "office", "hallway", "furniture", "sofa", "couch", "desk", "carpet",
        "curtain", "shelf", "stairs", "interior",
    )
    val OUTDOOR_KEYWORDS = setOf(
        "outdoor", "street", "road", "sidewalk", "sky", "tree", "grass",
        "park", "building", "asphalt", "pavement", "cloud", "field",
        "vehicle", "car", "house", "fence", "yard",
    )

    fun tokensOf(identifier: String): List<String> =
        identifier.lowercase().split('_', ' ', ',').filter { it.isNotBlank() }

    /** Vote from an already-run classification pass (AR pipeline reuses its own). */
    fun voteFrom(observations: List<VNClassificationObservation>): SceneVote {
        var indoorHits = 0
        var outdoorHits = 0
        for (obs in observations) {
            if (obs.confidence < SCENE_CONFIDENCE) continue
            val tokens = tokensOf(obs.identifier)
            if (tokens.any { it in INDOOR_KEYWORDS }) indoorHits += 1
            if (tokens.any { it in OUTDOOR_KEYWORDS }) outdoorHits += 1
        }
        return when {
            indoorHits > outdoorHits && indoorHits > 0 -> SceneVote.INDOOR
            outdoorHits > indoorHits && outdoorHits > 0 -> SceneVote.OUTDOOR
            else -> SceneVote.NONE
        }
    }

    /** Run a full classify pass on a raw camera frame and vote. */
    fun classify(pixelBuffer: CVPixelBufferRef): SceneVote {
        val request = VNClassifyImageRequest()
        val handler = VNImageRequestHandler(
            cVPixelBuffer = pixelBuffer,
            options = emptyMap<Any?, Any>(),
        )
        handler.performRequests(listOf(request), error = null)
        val observations = request.results
            ?.filterIsInstance<VNClassificationObservation>()
            ?.sortedByDescending { it.confidence }
            ?.take(15)
            ?: return SceneVote.NONE
        return voteFrom(observations)
    }
}
