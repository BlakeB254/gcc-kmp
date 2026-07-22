package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Vision.VNClassificationObservation
import platform.Vision.VNClassifyImageRequest
import platform.Vision.VNDetectHumanRectanglesRequest
import platform.Vision.VNDetectedObjectObservation
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeAnimalsRequest
import platform.Vision.VNRecognizedObjectObservation

/**
 * iOS privacy screen: three Vision requests over the evidence photo, then
 * the shared pure verdict ([litterPhotoPrivacyVerdict]). Runs on Default —
 * `performRequests` is synchronous and CPU-bound.
 *
 * Fail-CLOSED on request errors: if Vision itself throws we cannot certify
 * the photo is free of people/animals/interiors, and a lost thumbnail is
 * the cheap side of that bet (the marker still files without media).
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun screenLitterPhotoPrivacy(photoBytes: ByteArray): PhotoPrivacyVerdict =
    withContext(Dispatchers.Default) {
        try {
            val handler = VNImageRequestHandler(data = photoBytes.toPrivacyNSData(), options = emptyMap<Any?, Any>())
            val humanRequest = VNDetectHumanRectanglesRequest(completionHandler = null)
            val animalRequest = VNRecognizeAnimalsRequest(completionHandler = null)
            val sceneRequest = VNClassifyImageRequest(completionHandler = null)
            val performed = handler.performRequests(
                listOf(humanRequest, animalRequest, sceneRequest),
                error = null,
            )
            if (!performed) return@withContext PhotoPrivacyVerdict.blocked("screen-failed")

            val humanConfidence = humanRequest.results
                ?.filterIsInstance<VNDetectedObjectObservation>()
                ?.maxOfOrNull { it.confidence }
            val animalConfidence = animalRequest.results
                ?.filterIsInstance<VNRecognizedObjectObservation>()
                ?.maxOfOrNull { it.confidence }
            val sceneLabels = sceneRequest.results
                ?.filterIsInstance<VNClassificationObservation>()
                ?.map { ClassificationObservation(it.identifier, it.confidence) }
                ?: emptyList()

            litterPhotoPrivacyVerdict(humanConfidence, animalConfidence, sceneLabels)
        } catch (e: Exception) {
            DiagnosticsLog.error("photo-privacy", "privacy screen failed (photo dropped)", e)
            PhotoPrivacyVerdict.blocked("screen-failed")
        }
    }

// The two existing ByteArray.toNSData() helpers are file-private
// (OnDeviceAnalyzer/ShareManager) — same impl, distinct name to avoid an
// ambiguity if either ever goes internal.
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toPrivacyNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(
            bytes = pinned.addressOf(0),
            length = size.toULong(),
        )
    }
}
