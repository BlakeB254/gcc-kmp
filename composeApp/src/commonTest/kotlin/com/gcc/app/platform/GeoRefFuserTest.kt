package com.gcc.app.platform

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoRefFuserTest {

    private val base = GeoRefFuser.Anchor(lat = 41.8600, lng = -87.7130)

    @Test
    fun firstFixIsAdoptedAsIs() {
        assertEquals(base, GeoRefFuser.fuse(null, base, accuracyMeters = 14.0))
    }

    @Test
    fun lowAccuracyFixOnlyNudgesTheAnchor() {
        // A 14 m fix proposing a ~10 m street-ward jump must NOT teleport the
        // frame (the old last-fix-wins behavior) — gain ≈ 0.35*(4/14)² ≈ 3%.
        val streetWard = GeoRefFuser.Anchor(base.lat + 10.0 / METERS_PER_DEG_LAT, base.lng)
        val fused = GeoRefFuser.fuse(base, streetWard, accuracyMeters = 14.0)
        val movedMeters = (fused.lat - base.lat) * METERS_PER_DEG_LAT
        assertTrue(movedMeters < 0.5, "14m fix moved anchor ${movedMeters}m — should be a nudge")
        assertTrue(movedMeters > 0.0, "nudge should still be toward the fix")
    }

    @Test
    fun highAccuracyFixMovesDecisively() {
        val proposed = GeoRefFuser.Anchor(base.lat + 10.0 / METERS_PER_DEG_LAT, base.lng)
        val fused = GeoRefFuser.fuse(base, proposed, accuracyMeters = 4.0)
        val movedMeters = (fused.lat - base.lat) * METERS_PER_DEG_LAT
        assertTrue(abs(movedMeters - 10.0 * GeoRefFuser.MAX_GAIN) < 0.01,
            "4m fix should apply MAX_GAIN, moved ${movedMeters}m")
    }

    @Test
    fun implausibleJumpIsRejectedOutright() {
        val glitch = GeoRefFuser.Anchor(base.lat + 60.0 / METERS_PER_DEG_LAT, base.lng)
        assertEquals(base, GeoRefFuser.fuse(base, glitch, accuracyMeters = 5.0))
    }

    @Test
    fun repeatedGoodFixesConvergeOnTheTruth() {
        val truth = GeoRefFuser.Anchor(base.lat + 8.0 / METERS_PER_DEG_LAT, base.lng)
        var anchor = base
        repeat(20) { anchor = GeoRefFuser.fuse(anchor, truth, accuracyMeters = 5.0) }
        val remaining = abs(truth.lat - anchor.lat) * METERS_PER_DEG_LAT
        assertTrue(remaining < 0.5, "after 20 good fixes anchor should converge, ${remaining}m left")
    }
}
