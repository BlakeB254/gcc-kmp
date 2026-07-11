package com.gcc.app.platform

import kotlin.math.min

/**
 * Fused geo-reference estimator — fixes the "coverage lands in the middle
 * of the street" bias (observed 2026-07-11).
 *
 * Diagnosis: the AR→GPS geo reference used to re-pair to EVERY fix that
 * passed the 20 m accuracy gate, last-fix-wins. Real session manifests show
 * 14–15 m-accuracy fixes passing that gate; a 14 m error is wider than a
 * residential street, and each accepted fix teleported the WHOLE geo frame
 * (every coverage cell, every observation) by that fix's error — commonly
 * street-ward, since urban GPS biases toward open sky over the roadway.
 * (The CLLocationManager config itself was already correct:
 * CLActivityTypeFitness — pedestrian, no road-snapping — plus
 * BestForNavigation; see LocationProvider.kt.)
 *
 * Fix: instead of adopting each fix outright, blend it into the current
 * anchor with a gain proportional to its accuracy (an accuracy-weighted
 * exponential filter), and reject fixes whose implied jump is implausible
 * for a walking session. A 4 m fix moves the frame decisively; a 14 m fix
 * nudges it. AR tracking supplies drift-free relative geometry between
 * fixes, so a slowly-converging absolute anchor is exactly the right
 * split of responsibilities.
 *
 * Pure common code (no platform APIs) so the behavior is unit-tested on
 * the desktop target; the iosMain overlays call [fuse] from their
 * updateGeoReference with the previous and proposed pairings.
 */
object GeoRefFuser {

    /** Best accuracy that earns the maximum gain — a fix this good mostly wins. */
    const val FULL_TRUST_ACCURACY_METERS = 4.0

    /** Hard ceiling on how much of a single fix's correction is adopted. */
    const val MAX_GAIN = 0.35

    /** A proposed anchor jump beyond this is a GPS glitch, not a walk: skip it
     *  entirely (walking speed can't move the frame 25 m between fixes). */
    const val MAX_PLAUSIBLE_JUMP_METERS = 25.0

    data class Anchor(val lat: Double, val lng: Double)

    /**
     * Blend a new fix-implied anchor into the current one.
     *
     * @param current      the anchor in use (null = first usable fix, adopt as-is)
     * @param proposed     the anchor this fix implies (fix lat/lng re-based to
     *                     the camera position, i.e. what last-fix-wins would adopt)
     * @param accuracyMeters the fix's reported horizontal accuracy
     * @return the anchor to use — never a raw jump to a low-quality fix.
     */
    fun fuse(current: Anchor?, proposed: Anchor, accuracyMeters: Double): Anchor {
        if (current == null) return proposed

        val northMeters = (proposed.lat - current.lat) * METERS_PER_DEG_LAT
        val eastMeters = (proposed.lng - current.lng) * METERS_PER_DEG_LAT * lngScaleFor(current.lat)
        val jump = kotlin.math.sqrt(northMeters * northMeters + eastMeters * eastMeters)
        if (jump > MAX_PLAUSIBLE_JUMP_METERS) return current

        // Gain: full-trust accuracy → MAX_GAIN; degrades quadratically —
        // a 14 m fix gets MAX_GAIN * (4/14)² ≈ 0.03 (a ~3% nudge).
        val ratio = FULL_TRUST_ACCURACY_METERS / accuracyMeters.coerceAtLeast(FULL_TRUST_ACCURACY_METERS)
        val gain = min(MAX_GAIN, MAX_GAIN * ratio * ratio)

        return Anchor(
            lat = current.lat + (proposed.lat - current.lat) * gain,
            lng = current.lng + (proposed.lng - current.lng) * gain,
        )
    }
}
