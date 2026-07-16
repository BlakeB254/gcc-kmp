package com.gcc.app.platform

import com.gcc.shared.model.ObservedSpotItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionAutoDetectPromptSourceTest {

    // ~0.0001 deg lat ≈ 11.1m; ~0.00003 ≈ 3.3m at the equator-ish scale
    // GeofenceVerification uses. All items sit at (41.86, -87.71) — Chicago.
    private val itemLat = 41.86
    private val itemLng = -87.71

    private fun autoItem(atMs: Long = 1_000L, domain: String? = "litter", provenance: String? = PROVENANCE_AUTO_DETECTED) =
        ObservedSpotItem(
            lat = itemLat,
            lng = itemLng,
            itemType = "bottle",
            domain = domain,
            atMs = atMs,
            provenance = provenance,
        )

    /** A point [meters] north of the item. */
    private fun latAtMeters(meters: Double): Double = itemLat + meters / 111_320.0

    @Test
    fun notPromptableWhileUserIsStillAtTheSpot() {
        val source = SessionAutoDetectPromptSource()
        val result = source.promptableObservations(listOf(autoItem()), itemLat, itemLng)
        assertTrue(result.isEmpty(), "item must not prompt while the user has never left it")
    }

    @Test
    fun armsAfterWalkingAwayAndStaysArmedOnReturn() {
        val source = SessionAutoDetectPromptSource()
        val items = listOf(autoItem())
        // Still standing on it: nothing.
        assertTrue(source.promptableObservations(items, itemLat, itemLng).isEmpty())
        // Walked 20m away: arms (and, being armed, is now promptable).
        assertEquals(1, source.promptableObservations(items, latAtMeters(20.0), itemLng).size)
        // Walked back onto it: STAYS promptable — this is the re-scan case.
        val back = source.promptableObservations(items, itemLat, itemLng)
        assertEquals(1, back.size)
        assertEquals("dirty", back.single().state)
        assertEquals("ar_auto_detect", back.single().sourceType)
    }

    @Test
    fun doesNotArmInsideTheArmRadius() {
        val source = SessionAutoDetectPromptSource(armDistanceMeters = 12.0)
        val items = listOf(autoItem())
        assertTrue(source.promptableObservations(items, latAtMeters(8.0), itemLng).isEmpty())
        // Returning to the spot after only an 8m excursion: still nothing.
        assertTrue(source.promptableObservations(items, itemLat, itemLng).isEmpty())
    }

    @Test
    fun syntheticIdIsNegativeAndStableAcrossCalls() {
        val source = SessionAutoDetectPromptSource()
        val items = listOf(autoItem())
        val first = source.promptableObservations(items, latAtMeters(20.0), itemLng).single()
        val second = source.promptableObservations(items, itemLat, itemLng).single()
        assertTrue(first.id < 0, "session observations must use negative synthetic ids")
        assertEquals(first.id, second.id, "id must be stable so cluster/resolve bookkeeping holds")
        assertTrue(SessionAutoDetectPromptSource.isSessionObservationId(first.id))
    }

    @Test
    fun distinctItemsGetDistinctIds() {
        val source = SessionAutoDetectPromptSource()
        val items = listOf(autoItem(atMs = 1_000L), autoItem(atMs = 2_000L))
        val observations = source.promptableObservations(items, latAtMeters(20.0), itemLng)
        assertEquals(2, observations.size)
        assertEquals(2, observations.map { it.id }.toSet().size)
    }

    @Test
    fun onlyAutoDetectedLitterIsEligible() {
        val source = SessionAutoDetectPromptSource()
        val items = listOf(
            autoItem(atMs = 1L, provenance = PROVENANCE_USER_CONFIRMED),
            autoItem(atMs = 2L, provenance = PROVENANCE_USER_REJECTED),
            autoItem(atMs = 3L, domain = "vegetation"),
            autoItem(atMs = 4L, domain = null), // legacy null domain = litter
            autoItem(atMs = 5L),
        )
        val observations = source.promptableObservations(items, latAtMeters(20.0), itemLng)
        assertEquals(2, observations.size, "only auto-detected litter (incl. legacy null domain) prompts")
    }

    @Test
    fun unstampedItemsNeverPrompt() {
        val source = SessionAutoDetectPromptSource()
        val unstamped = ObservedSpotItem(
            lat = itemLat, lng = itemLng, itemType = "bottle",
            domain = "litter", provenance = PROVENANCE_AUTO_DETECTED,
        )
        assertTrue(source.promptableObservations(listOf(unstamped), latAtMeters(20.0), itemLng).isEmpty())
    }
}
