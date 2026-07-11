package com.gcc.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class ScanCapturedPhoto(
    val photoBytes: ByteArray? = null,
    val photoWidth: Int = 0,
    val photoHeight: Int = 0,
    val depthAnalysis: DepthAnalysis? = null,
    val aiAnalysis: OnDeviceAnalysisResult? = null,
    val captureLocation: Coordinates? = null,
    val attestation: CaptureAttestation? = null,
    val distanceFromSpot: Double? = null,
    /** True when >= 2 identified-litter multi-item reticles (Task 17) were
     *  on screen at the instant this photo was captured — the "best shots
     *  for our display image in the backend extraction mechanisms". Computed
     *  by the capture site (SpotScanCapture), which has the AR overlay;
     *  threaded through to [com.gcc.shared.model.EnvScanManifest.multiItemPhotoKeys]
     *  at manifest-build time. */
    val isMultiItemBestShot: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanCapturedPhoto) return false
        return photoBytes?.contentEquals(other.photoBytes ?: byteArrayOf()) == true &&
            photoWidth == other.photoWidth &&
            photoHeight == other.photoHeight &&
            depthAnalysis == other.depthAnalysis
    }
    override fun hashCode(): Int {
        var result = photoBytes?.contentHashCode() ?: 0
        result = 31 * result + photoWidth
        result = 31 * result + photoHeight
        result = 31 * result + (depthAnalysis?.hashCode() ?: 0)
        return result
    }
}
