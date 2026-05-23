package com.gcc.app.ui.map

import com.gcc.shared.model.AffectedBlock
import com.gcc.shared.model.AffectedBlockStatus
import com.gcc.shared.model.Coordinates

/**
 * Serialize a list of [AffectedBlock] into a GeoJSON FeatureCollection
 * string suitable for `rememberGeoJsonSource(GeoJsonData.JsonString(...))`.
 *
 * Each feature's geometry is the block's polygon. Properties carry the
 * numeric fields MapLibre's heatmap + fill layers reference by name:
 *   - `blockId`   — tapped-feature identifier
 *   - `intensity` — drives the heatmap weight and the fill alpha
 *   - `status`    — drives the fill color ramp
 *   - `sponsorCount` — surfaced in the tap info sheet
 *   - `scheduledCleaningAt` — surfaced in the tap info sheet
 *
 * Blocks with no polygon ring are skipped (MapLibre's heatmap needs
 * actual geometry — a point-only fallback would pollute the map).
 */
internal fun affectedBlocksToGeoJson(blocks: List<AffectedBlock>): String {
    // Skip AVAILABLE blocks — the map already paints the full world as a
    // green "clean zone" baseline, so fully-available blocks would render a
    // second green fill on top of that and read as a duplicate layer.
    // Only emit polygons for blocks with real activity (funded / in-progress
    // / completed) so the affected-block layer visually adds information.
    val features = blocks
        .filter { it.polygon.size >= 3 && it.status != AffectedBlockStatus.AVAILABLE }
        .joinToString(separator = ",", transform = ::blockFeature)
    return """{"type":"FeatureCollection","features":[$features]}"""
}

internal fun affectedBlockCentersToGeoJson(blocks: List<AffectedBlock>): String {
    val features = blocks
        .filter { it.polygon.size >= 3 && it.status != AffectedBlockStatus.AVAILABLE }
        .joinToString(separator = ",", transform = ::blockCenterFeature)
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun blockFeature(block: AffectedBlock): String {
    val coords = block.polygon.joinToString(separator = ",") { it.toCoordPair() }
    val status = block.status.wireName()
    val sched = block.scheduledCleaningAt?.let { "\"$it\"" } ?: "null"
    val code = block.code?.jsonEscape() ?: "null"
    val name = block.name?.jsonEscape() ?: "null"
    return """{"type":"Feature",""" +
        """"geometry":{"type":"Polygon","coordinates":[[$coords]]},""" +
        """"properties":{""" +
        """"blockId":${block.blockId},""" +
        """"code":$code,""" +
        """"name":$name,""" +
        """"status":"$status",""" +
        """"intensity":${block.intensity},""" +
        """"sponsorCount":${block.sponsorCount},""" +
        """"fundingCurrent":${block.fundingCurrent},""" +
        """"fundingGoal":${block.fundingGoal},""" +
        """"scheduledCleaningAt":$sched""" +
        """}}"""
}

private fun blockCenterFeature(block: AffectedBlock): String {
    val center = block.polygonCenter()
    val status = block.status.wireName()
    val code = block.code?.jsonEscape() ?: "null"
    val name = block.name?.jsonEscape() ?: "null"
    return """{"type":"Feature",""" +
        """"geometry":{"type":"Point","coordinates":[${center.lng},${center.lat}]},""" +
        """"properties":{""" +
        """"blockId":${block.blockId},""" +
        """"code":$code,""" +
        """"name":$name,""" +
        """"status":"$status",""" +
        """"intensity":${block.intensity},""" +
        """"sponsorCount":${block.sponsorCount},""" +
        """"fundingCurrent":${block.fundingCurrent},""" +
        """"fundingGoal":${block.fundingGoal}""" +
        """}}"""
}

private fun AffectedBlock.polygonCenter(): Coordinates {
    val lat = polygon.map { it.lat }.average()
    val lng = polygon.map { it.lng }.average()
    return Coordinates(lat = lat, lng = lng)
}

private fun Coordinates.toCoordPair(): String = "[$lng,$lat]"

private fun AffectedBlockStatus.wireName(): String = when (this) {
    AffectedBlockStatus.AVAILABLE -> "available"
    AffectedBlockStatus.FUNDED -> "funded"
    AffectedBlockStatus.IN_PROGRESS -> "in_progress"
    AffectedBlockStatus.COMPLETED -> "completed"
}

/**
 * Minimal JSON string escape for GeoJSON property values. Handles quotes,
 * backslashes, and the four control chars that break JSON parsers. No
 * Unicode escaping — MapLibre handles UTF-8 natively.
 */
private fun String.jsonEscape(): String {
    val sb = StringBuilder(length + 2)
    sb.append('"')
    for (c in this) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
