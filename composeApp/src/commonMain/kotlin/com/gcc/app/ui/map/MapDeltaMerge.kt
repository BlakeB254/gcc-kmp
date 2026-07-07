package com.gcc.app.ui.map

import com.gcc.shared.model.MapCoverageCell
import com.gcc.shared.model.WorldObservation
import kotlinx.datetime.Instant

/**
 * Live-map delta polling (Pillar 2, docs/superpowers/plans/2026-07-07-pillar2-
 * live-map.md). A poll response is server-filtered by `since` and only
 * contains rows that changed — callers MUST upsert it into their existing
 * state rather than replacing wholesale, or an empty/partial delta would
 * erase everything already on screen.
 *
 * Both merges upsert by key (coverage: locationCellId; observations: id),
 * keeping every existing row whose key isn't present in the delta and
 * overwriting (in place, by position) any row the delta does touch.
 */
fun mergeCoverageCells(
    existing: List<MapCoverageCell>,
    delta: List<MapCoverageCell>,
): List<MapCoverageCell> {
    if (delta.isEmpty()) return existing
    val merged = existing.associateBy { it.locationCellId }.toMutableMap()
    delta.forEach { merged[it.locationCellId] = it }
    return merged.values.toList()
}

fun mergeObservations(
    existing: List<WorldObservation>,
    delta: List<WorldObservation>,
): List<WorldObservation> {
    if (delta.isEmpty()) return existing
    val merged = existing.associateBy { it.id }.toMutableMap()
    delta.forEach { merged[it.id] = it }
    return merged.values.toList()
}

/**
 * Advances the poll's `since` cursor to the latest of [current] and every
 * timestamp in [candidates] (nulls ignored). This is the server-clock
 * authority for the next poll: it must be built from timestamps the server
 * itself returned (lastScannedAt / capturedAt), never from the device clock,
 * so a slow/fast device clock can't cause the client to miss or re-fetch
 * rows.
 */
fun latestIsoTimestamp(current: String?, candidates: List<String?>): String? {
    var latest = current
    for (candidate in candidates) {
        if (candidate == null) continue
        latest = if (latest == null || isoTimestampAfter(candidate, latest)) candidate else latest
    }
    return latest
}

private fun isoTimestampAfter(a: String, b: String): Boolean {
    val instantA = runCatching { Instant.parse(a) }.getOrNull()
    val instantB = runCatching { Instant.parse(b) }.getOrNull()
    if (instantA != null && instantB != null) return instantA > instantB
    // ISO-8601 timestamps of consistent format sort correctly as plain
    // strings; fall back to that if either side failed to parse.
    return a > b
}
