package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreVideo.CVPixelBufferRef
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNGenerateObjectnessBasedSaliencyImageRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRectangleObservation
import platform.Vision.VNSaliencyImageObservation

/**
 * Indoor/outdoor scene voting from Vision classifications — shared by the AR
 * frame pipeline ([ArFrameFeedback]) and the plain AVCapture viewfinder tap
 * (see CameraManager's scene sampling), so both camera modes feed the
 * OutdoorGate the same way.
 */
@OptIn(ExperimentalForeignApi::class)
internal object SceneVoteVision {

    private const val SCENE_CONFIDENCE = 0.3f

    val INDOOR_KEYWORDS = setOf(
        "indoor", "room", "ceiling", "wall", "kitchen", "bathroom", "bedroom",
        "office", "hallway", "furniture", "sofa", "couch", "desk", "carpet",
        "curtain", "shelf", "stairs", "interior", "chair", "table",
        "television", "lamp", "bed", "cabinet", "refrigerator", "laptop",
        "monitor", "screen", "keyboard", "bookcase", "book", "rug", "pillow",
        "blanket", "sink", "counter", "cupboard", "appliance", "tile",
        "computer", "floor", "flooring", "hardwood", "printer", "document",
        "paper", "drawer", "wardrobe", "closet", "mug", "houseplant",
    )
    val OUTDOOR_KEYWORDS = setOf(
        "outdoor", "street", "road", "sidewalk", "sky", "tree", "grass",
        "park", "building", "asphalt", "pavement", "cloud", "field",
        "vehicle", "car", "house", "fence", "yard", "highway", "lawn",
        "garden", "trail", "bush", "truck", "bicycle", "traffic", "bridge",
        "alley", "playground", "landscape", "horizon",
    )

    /** Token → reticle label; matches `_`/space-split Vision identifiers. */
    val TRASH_TOKENS = mapOf(
        "garbage" to "Trash", "trash" to "Trash", "waste" to "Trash",
        "litter" to "Litter", "rubbish" to "Trash", "debris" to "Debris",
        "bottle" to "Bottle", "can" to "Can", "cup" to "Cup", "bag" to "Bag",
        "plastic" to "Plastic", "wrapper" to "Wrapper", "carton" to "Carton",
        "tire" to "Tire", "mattress" to "Mattress",
    )
    private const val TRASH_CONFIDENCE = 0.35f

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
            // Enclosed spaces show NO outdoor cues — any indoor hit with zero
            // outdoor hits is decisive (walls/door/ceiling, no sky).
            indoorHits > 0 && outdoorHits == 0 -> SceneVote.INDOOR
            outdoorHits > 0 && outdoorHits >= indoorHits -> SceneVote.OUTDOOR
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


/** One-pass frame analysis for the AVCapture viewfinder: scene vote + trash reticle. */
internal data class ViewfinderFrameResult(
    val vote: SceneVote,
    val detection: LiveDetection?,
    /** Top classifications, for the diagnostics panel ("what does Vision see?"). */
    val topLabels: String = "",
)

@OptIn(ExperimentalForeignApi::class)
internal fun SceneVoteVision.analyzeViewfinderFrame(pixelBuffer: CVPixelBufferRef): ViewfinderFrameResult {
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
        ?: return ViewfinderFrameResult(SceneVote.NONE, null)

    val vote = voteFrom(observations)
    val topLabels = observations.take(4).joinToString(", ") {
        "${it.identifier}(${(it.confidence * 100).toInt()}%)"
    }

    val trashLabel = observations.firstNotNullOfOrNull { obs ->
        if (obs.confidence < 0.35f) return@firstNotNullOfOrNull null
        tokensOf(obs.identifier).firstNotNullOfOrNull { SceneVoteVision.TRASH_TOKENS[it] }
    } ?: return ViewfinderFrameResult(vote, null, topLabels)

    // Salient region → reticle position; fall back to frame center.
    var visionX = 0.5
    var visionY = 0.5
    var radius = 0.12f
    saliencyRequest.results
        ?.filterIsInstance<VNSaliencyImageObservation>()
        ?.firstOrNull()
        ?.salientObjects
        ?.filterIsInstance<VNRectangleObservation>()
        ?.firstOrNull()
        ?.boundingBox
        ?.useContents {
            visionX = origin.x + size.width / 2.0
            visionY = origin.y + size.height / 2.0
            radius = (maxOf(size.width, size.height) / 2.0).toFloat().coerceIn(0.06f, 0.35f)
        }

    // Landscape sensor image → portrait view (same mapping as the AR pipeline).
    val imageY = 1.0 - visionY
    val detection = LiveDetection(
        cx = (1.0 - imageY).toFloat(),
        cy = visionX.toFloat(),
        r = radius,
        label = trashLabel,
        distanceMeters = null,
    )
    return ViewfinderFrameResult(vote, detection, topLabels)
}
