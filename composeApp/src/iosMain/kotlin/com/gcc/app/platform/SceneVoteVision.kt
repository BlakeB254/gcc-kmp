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

    fun tokensOf(identifier: String): List<String> = TrashDetectionRules.tokensOf(identifier)

    /** Vote from an already-run classification pass (AR pipeline reuses its own). */
    fun voteFrom(observations: List<VNClassificationObservation>): SceneVote =
        TrashDetectionRules.sceneVoteFrom(observations.map { ClassificationObservation(it.identifier, it.confidence) })

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

    val commonObs = observations.map { ClassificationObservation(it.identifier, it.confidence) }
    val trashLabel = TrashDetectionRules.trashLabelFrom(commonObs)
        ?: return ViewfinderFrameResult(vote, null, topLabels)

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
        severity = LiveSeverityEstimator.estimate(trashLabel, radius),
        confidence = TrashDetectionRules.trashConfidenceFor(trashLabel, commonObs),
    )
    return ViewfinderFrameResult(vote, detection, topLabels)
}
