package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.ObservedSpotItem
import com.gcc.shared.model.WorldObservation

/**
 * Feeds THIS session's auto-detected litter into the [NearbyDirtyPromptGate]
 * so walking back over a fresh detection raises the same "is it still
 * there?" card old server observations get.
 *
 * Field report (TestFlight 157): the Track 2 scanner filed a phantom "some
 * litter" observation, and re-scanning the exact spot produced no update —
 * the prompt gate's input was built exclusively from server
 * `world_observations`, so a detection made seconds ago in the SAME session
 * was invisible to it, and no correction path existed at all. This class is
 * that path.
 *
 * Arming: an item only becomes promptable after the user has walked
 * [armDistanceMeters] away from it at least once. Without this, the card
 * would fire the instant a detection files — the user is standing on it,
 * still mid-scan of that very spot, and hasn't "returned" to anything.
 *
 * Synthetic ids: emitted [WorldObservation]s carry NEGATIVE ids (unique per
 * item within the session) so they can never collide with real server ids in
 * `resolvedObservationIds` / [clusterIdsAround] bookkeeping. Test with
 * [isSessionObservationId] before doing anything server-id-shaped with one.
 */
class SessionAutoDetectPromptSource(
    private val armDistanceMeters: Double = 12.0,
) {
    private val armedKeys = mutableSetOf<Long>()
    private val syntheticIds = mutableMapOf<Long, Int>()
    private var nextSyntheticId = -1

    /**
     * One location tick: returns the promptable [WorldObservation] view of
     * [observedItems] — auto-detected litter items the user has walked away
     * from since detection. Ids are stable across calls for the same item.
     * Items without an [ObservedSpotItem.atMs] stamp are never promptable
     * (the stamp is this class's identity key; addObservedItem always
     * stamps).
     */
    fun promptableObservations(
        observedItems: List<ObservedSpotItem>,
        lat: Double,
        lng: Double,
    ): List<WorldObservation> {
        val autoLitter = observedItems.filter { item ->
            item.provenance == PROVENANCE_AUTO_DETECTED &&
                (item.domain == null || item.domain == "litter") &&
                item.atMs != null
        }
        for (item in autoLitter) {
            val key = item.atMs ?: continue
            if (key in armedKeys) continue
            if (GeofenceVerification.distanceMeters(lat, lng, item.lat, item.lng) > armDistanceMeters) {
                armedKeys.add(key)
            }
        }
        return autoLitter
            .filter { it.atMs in armedKeys }
            .map { item ->
                val id = syntheticIds.getOrPut(item.atMs!!) { nextSyntheticId-- }
                WorldObservation(
                    id = id,
                    lat = item.lat,
                    lng = item.lng,
                    itemType = item.itemType,
                    domain = "litter",
                    state = "dirty",
                    sourceType = "ar_auto_detect",
                )
            }
    }

    companion object {
        /** True for ids minted by this class (session-local, no server row id). */
        fun isSessionObservationId(id: Int): Boolean = id < 0
    }
}
