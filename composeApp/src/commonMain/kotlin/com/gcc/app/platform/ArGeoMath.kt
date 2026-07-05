package com.gcc.app.platform

import kotlin.math.cos

/** Meters per degree of latitude -- a fixed constant, accurate enough at the scale of a single AR session's local coordinate frame. */
const val METERS_PER_DEG_LAT = 111_320.0

/** cos(latDegrees), for scaling east-west (longitude) distances at a given latitude. */
fun lngScaleFor(latDegrees: Double): Double = cos(latDegrees * kotlin.math.PI / 180.0)

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
