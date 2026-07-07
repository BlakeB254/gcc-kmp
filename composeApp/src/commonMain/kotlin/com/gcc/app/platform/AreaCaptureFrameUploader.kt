package com.gcc.app.platform

import com.gcc.shared.api.CaptureStorageNotConfiguredException
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.CapturedFrame
import com.gcc.shared.model.CaptureUploadUrlRequest

/**
 * Uploads one periodically-captured color(+depth) frame pair for the DGX
 * reconstruction pipeline (Task 7). Best-effort like photo uploads in
 * [EnvScanSessionUploader] -- NOT queued/retried on failure, since retrying a
 * queue of multi-hundred-KB raw frames would bloat local storage far more
 * than the tiny JSON manifests that queue already exists for. A dropped
 * frame just means slightly sparser raw input for that session; the
 * metadata (GPS/coverage/detections) that actually drives points/map state
 * is unaffected either way.
 */
class AreaCaptureFrameUploader(private val apiClient: GccApiClient) {

    /**
     * Uploads the color frame (required) and depth frame (optional -- omit
     * on ticks where depth capture failed or the device has no LiDAR).
     * Returns null on any failure; callers simply don't record this tick's
     * frame rather than surfacing an error mid-scan.
     */
    suspend fun uploadFrame(
        locationCellId: String,
        deviceId: String,
        colorJpegBytes: ByteArray,
        depthPngBytes: ByteArray?,
        lat: Double,
        lng: Double,
        headingDegrees: Float?,
        cameraTransform: List<Float>?,
        intrinsics: List<Float>?,
        capturedAtMs: Long,
    ): CapturedFrame? {
        val capturedAtSuffix = "$capturedAtMs-${(0..9999).random()}"
        val colorKey = uploadOne(
            locationCellId, deviceId, "frame-$capturedAtSuffix", "jpg", colorJpegBytes, "image/jpeg",
        ) ?: return null

        val depthKey = depthPngBytes?.let {
            uploadOne(locationCellId, deviceId, "frame-$capturedAtSuffix-depth", "png", it, "image/png")
        }

        return CapturedFrame(
            colorKey = colorKey,
            depthKey = depthKey,
            lat = lat,
            lng = lng,
            headingDegrees = headingDegrees,
            cameraTransform = cameraTransform,
            intrinsics = intrinsics,
            capturedAtMs = capturedAtMs,
        )
    }

    private suspend fun uploadOne(
        locationCellId: String,
        deviceId: String,
        capturedAt: String,
        ext: String,
        bytes: ByteArray,
        contentType: String,
    ): String? {
        return try {
            val ticket = apiClient.createCaptureUploadUrl(
                CaptureUploadUrlRequest(
                    locationCellId = locationCellId,
                    deviceId = deviceId,
                    capturedAt = capturedAt,
                    ext = ext,
                )
            )
            apiClient.uploadToPresignedUrl(ticket.uploadUrl, bytes, contentType)
            ticket.r2Key
        } catch (e: CaptureStorageNotConfiguredException) {
            null
        } catch (e: Exception) {
            DiagnosticsLog.error("env-capture", "raw frame upload failed (dropped, not queued)", e)
            null
        }
    }
}
