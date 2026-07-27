package com.gcc.app.platform

import com.gcc.shared.model.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceContextTest {

    private fun obs(id: String, c: Float) = ClassificationObservation(id, c)

    // ── SurfaceContextClassifier ──

    @Test
    fun grassLabelsClassifyAsGrass() {
        assertEquals(
            SurfaceType.GRASS,
            SurfaceContextClassifier.fromSceneLabels(listOf(obs("grass", 0.6f), obs("lawn", 0.3f))),
        )
    }

    @Test
    fun surfaceVotesAreConfidenceSummedAcrossLabels() {
        // Two moderate pavement labels outvote one stronger grass label.
        val labels = listOf(obs("grass", 0.5f), obs("sidewalk", 0.3f), obs("concrete", 0.3f))
        assertEquals(SurfaceType.PAVEMENT, SurfaceContextClassifier.fromSceneLabels(labels))
    }

    @Test
    fun roadAndAsphaltClassifyAsStreet() {
        assertEquals(
            SurfaceType.STREET,
            SurfaceContextClassifier.fromSceneLabels(listOf(obs("asphalt_road", 0.4f))),
        )
    }

    @Test
    fun bareGroundClassifiesAsDirt() {
        assertEquals(
            SurfaceType.DIRT,
            SurfaceContextClassifier.fromSceneLabels(listOf(obs("soil", 0.3f), obs("gravel", 0.2f))),
        )
    }

    @Test
    fun horizonFrameYieldsNoSurface() {
        assertNull(SurfaceContextClassifier.fromSceneLabels(listOf(obs("sky", 0.9f), obs("building", 0.7f))))
    }

    @Test
    fun weakSurfaceLabelsDoNotVote() {
        assertNull(SurfaceContextClassifier.fromSceneLabels(listOf(obs("grass", 0.19f))))
    }

    // ── SurfaceContextTracker (rolling majority) ──

    @Test
    fun singleFlickerDoesNotChangeMajority() {
        val tracker = SurfaceContextTracker(windowSize = 5)
        repeat(4) { tracker.vote(SurfaceType.GRASS) }
        tracker.vote(SurfaceType.STREET) // one stray vote while panning
        assertEquals(SurfaceType.GRASS, tracker.current())
    }

    @Test
    fun sustainedNewSurfaceTakesOver() {
        val tracker = SurfaceContextTracker(windowSize = 5)
        repeat(5) { tracker.vote(SurfaceType.GRASS) }
        repeat(3) { tracker.vote(SurfaceType.PAVEMENT) }
        assertEquals(SurfaceType.PAVEMENT, tracker.current())
    }

    @Test
    fun nullVotesAreIgnored() {
        val tracker = SurfaceContextTracker(windowSize = 5)
        tracker.vote(SurfaceType.DIRT)
        repeat(10) { tracker.vote(null) }
        assertEquals(SurfaceType.DIRT, tracker.current())
    }

    @Test
    fun noVotesMeansNoSurface() {
        assertNull(SurfaceContextTracker().current())
    }

    // ── CoverageTracker surface tagging ──

    private fun freshTracker(): CoverageTracker {
        val tracker = CoverageTracker()
        tracker.initFromRoute(
            listOf(Coordinates(lat = 41.87, lng = -87.71)),
            paddingMeters = CoverageTracker.FREE_SCAN_PADDING_METERS,
        )
        return tracker
    }

    @Test
    fun closeHitTagsCellWithCurrentSurface() {
        val tracker = freshTracker()
        tracker.updateWithRaycastHits(
            hits = listOf(RaycastCoverageHit(lat = 41.87, lng = -87.71, distanceMeters = 3.0)),
            nowMs = 1_000,
            surface = SurfaceType.GRASS,
        )
        assertEquals(mapOf(SurfaceType.GRASS to 1), tracker.surfaceCellCounts())
    }

    @Test
    fun seenTierHitDoesNotTagSurface() {
        val tracker = freshTracker()
        // 40m out: credited SEEN, but the frame's surface says nothing about
        // that distant cell's ground.
        tracker.updateWithRaycastHits(
            hits = listOf(RaycastCoverageHit(lat = 41.87, lng = -87.71, distanceMeters = 40.0)),
            nowMs = 1_000,
            surface = SurfaceType.GRASS,
        )
        assertTrue(tracker.surfaceCellCounts().isEmpty())
    }

    @Test
    fun firstSurfaceTagSticks() {
        val tracker = freshTracker()
        val hit = RaycastCoverageHit(lat = 41.87, lng = -87.71, distanceMeters = 3.0)
        tracker.updateWithRaycastHits(hits = listOf(hit), nowMs = 1_000, surface = SurfaceType.PAVEMENT)
        tracker.updateWithRaycastHits(hits = listOf(hit), nowMs = 2_000, surface = SurfaceType.GRASS)
        assertEquals(mapOf(SurfaceType.PAVEMENT to 1), tracker.surfaceCellCounts())
    }

    @Test
    fun nullSurfaceCreditsWithoutTagging() {
        val tracker = freshTracker()
        tracker.updateWithRaycastHits(
            hits = listOf(RaycastCoverageHit(lat = 41.87, lng = -87.71, distanceMeters = 3.0)),
            nowMs = 1_000,
            surface = null,
        )
        assertTrue(tracker.surfaceCellCounts().isEmpty())
        assertTrue(tracker.creditedCells.value.isNotEmpty())
    }
}
