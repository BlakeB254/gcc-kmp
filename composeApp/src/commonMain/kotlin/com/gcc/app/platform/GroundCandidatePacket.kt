package com.gcc.app.platform

/**
 * One LiDAR ground-anomaly candidate from Swift's
 * `ArNativeSurface.drainGroundCandidates()` — a purely geometric protrusion
 * above the floor plane found by `GroundAnomalyDetector.swift`. Swift does
 * pixel work ONLY (depth unprojection + geometric gates); Kotlin owns
 * everything past this point — dedup, scoring, classification, lifecycle.
 *
 * [x]/[y]/[z] = AR-world centroid, meters. [widthM]/[heightM]/[depthM] =
 * world-space AABB extents, meters. [distanceM] = camera-to-centroid
 * distance, meters. [imageCx]/[imageCy]/[imageHalfW]/[imageHalfH] =
 * normalized (top-left origin) `capturedImage`-space bbox center/half-extents
 * — feed these straight into [ArNativeSurface.classifyRegionLabels].
 *
 * Deliberately lives in commonMain rather than next to the iOS-only
 * [ArNativeSurface] interface: [parseGroundCandidatePacket] needs to be
 * unit-testable from commonTest across every target (including desktop),
 * which an iosMain-only declaration can't be — mirrors how [CaptureChunkInfo]
 * / `ClassificationObservation` (the other Swift packet payload types) sit
 * in commonMain while their parsers stay next to the interface.
 */
data class GroundCandidatePacket(
    val id: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val widthM: Float,
    val heightM: Float,
    val depthM: Float,
    val distanceM: Float,
    val imageCx: Double,
    val imageCy: Double,
    val imageHalfW: Double,
    val imageHalfH: Double,
)

/**
 * Parses Swift's `"id|x|y|z|w|h|d|dist|cx|cy|hw|hh"` ground-candidate packet
 * (12 pipe-separated fields, all `%.4g` floats except the Long-decimal
 * `id`). Malformed input — wrong field count, any non-numeric field —
 * returns null rather than throwing; candidate ingestion must never crash
 * the scan.
 */
fun parseGroundCandidatePacket(packet: String): GroundCandidatePacket? {
    val parts = packet.split('|')
    if (parts.size != 12) return null
    val id = parts[0].toLongOrNull() ?: return null
    val x = parts[1].toFloatOrNull() ?: return null
    val y = parts[2].toFloatOrNull() ?: return null
    val z = parts[3].toFloatOrNull() ?: return null
    val widthM = parts[4].toFloatOrNull() ?: return null
    val heightM = parts[5].toFloatOrNull() ?: return null
    val depthM = parts[6].toFloatOrNull() ?: return null
    val distanceM = parts[7].toFloatOrNull() ?: return null
    val imageCx = parts[8].toDoubleOrNull() ?: return null
    val imageCy = parts[9].toDoubleOrNull() ?: return null
    val imageHalfW = parts[10].toDoubleOrNull() ?: return null
    val imageHalfH = parts[11].toDoubleOrNull() ?: return null
    return GroundCandidatePacket(
        id = id,
        x = x,
        y = y,
        z = z,
        widthM = widthM,
        heightM = heightM,
        depthM = depthM,
        distanceM = distanceM,
        imageCx = imageCx,
        imageCy = imageCy,
        imageHalfW = imageHalfW,
        imageHalfH = imageHalfH,
    )
}
