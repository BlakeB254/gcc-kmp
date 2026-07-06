package com.gcc.app.platform

import kotlin.math.cos

/** Meters per degree of latitude -- a fixed constant, accurate enough at the scale of a single AR session's local coordinate frame. */
const val METERS_PER_DEG_LAT = 111_320.0

/** cos(latDegrees), for scaling east-west (longitude) distances at a given latitude. */
fun lngScaleFor(latDegrees: Double): Double = cos(latDegrees * kotlin.math.PI / 180.0)

/**
 * Worst GPS horizontal accuracy allowed to (re)anchor the AR geo-reference.
 * Every fix that pairs re-centers ALL geo-derived AR geometry (fog, vector
 * cells, walls, marker lat/lng resolution), so a bad fix doesn't just skip
 * credit — it drags the whole frame. 20m matches the OutdoorGate's
 * indoor-evidence threshold: fixes worse than this were already treated as
 * "not trustworthy outdoor GPS" for gating; now they can't move the anchor
 * either. Null accuracy (invalid/unknown fix) is rejected — deny-by-default,
 * the next good fix pairs within a second or two outdoors.
 */
const val MAX_GEO_REF_ACCURACY_METERS = 20.0

/** True when a fix with this horizontal accuracy may become the AR geo-reference. */
fun isGeoRefFixUsable(accuracyMeters: Double?): Boolean =
    accuracyMeters != null && accuracyMeters <= MAX_GEO_REF_ACCURACY_METERS

/** (lat, lng) -> AR-local (x, z) meters, relative to the given geo reference. */
fun latLngToLocal(
    lat: Double, lng: Double,
    refLat: Double, refLng: Double, refX: Float, refZ: Float, lngScale: Double,
): Pair<Double, Double> {
    val northMeters = (lat - refLat) * METERS_PER_DEG_LAT
    val eastMeters = (lng - refLng) * METERS_PER_DEG_LAT * lngScale
    return (refX + eastMeters) to (refZ - northMeters)
}

/** AR-local (x, z) meters -> (lat, lng), the exact inverse of [latLngToLocal], using the SAME geo reference the local coordinates were measured against. */
fun localToLatLng(
    x: Float, z: Float,
    refLat: Double, refLng: Double, refX: Float, refZ: Float, lngScale: Double,
): Pair<Double, Double> {
    val lat = refLat + (refZ - z) / METERS_PER_DEG_LAT
    val lng = refLng + (x - refX) / (METERS_PER_DEG_LAT * lngScale)
    return lat to lng
}

/**
 * Snap a 1-D extent [minV, maxV] OUTWARD to the lattice whose lines sit at
 * `phase + k * tile` (k integer): the returned endpoints lie exactly on
 * lattice lines and cover the input range. Used to size AR ground-grid
 * planes so their edges — and every repeated tile line between them — land
 * on the CoverageTracker cell lattice the minimap draws, making the
 * on-camera grid and the minimap grid one geogrid. A degenerate input still
 * yields at least one full tile.
 */
fun snapRangeToLattice(minV: Double, maxV: Double, phase: Double, tile: Double): Pair<Double, Double> {
    val lo = phase + tile * kotlin.math.floor((minV - phase) / tile)
    var hi = phase + tile * kotlin.math.ceil((maxV - phase) / tile)
    if (hi <= lo) hi = lo + tile
    return lo to hi
}
