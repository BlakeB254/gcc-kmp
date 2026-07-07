package com.gcc.app.model

import com.gcc.shared.model.WorldObservation
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pillar 3 Task 2 added `widthMeters`/`yawDegrees` to WorldObservation.
 * Deployed clients (and stored payloads) that predate this change never sent
 * those fields — GccJson (ignoreUnknownKeys + defaults) must still decode
 * them cleanly, with both new fields defaulting to null.
 */
class WorldObservationWireCompatTest {

    @Test
    fun legacyObservationJsonDefaultsFootprintFieldsToNull() {
        val json = """{"id":7,"lat":41.8603,"lng":-87.7228,"itemType":"plastic_bottle",
            "state":"dirty","sourceType":"route_scan"}"""
        val observation = GccJson.decodeFromString<WorldObservation>(json)
        assertNull(observation.widthMeters)
        assertNull(observation.yawDegrees)
        assertNull(observation.estimatedWeightGrams)
    }

    @Test
    fun estimatedWeightGramsDecodesFromTheServerFieldNameAndRoundTrips() {
        // Pillar 8 Task 2, Part C: /api/map/observations already returns
        // estimatedWeightGrams in every row (site/src/app/api/map/observations/
        // route.ts) — this pins that @Serializable + the matching field name
        // is enough, no custom SerialName needed.
        val json = """{"id":9,"lat":41.8603,"lng":-87.7228,"itemType":"tire",
            "state":"dirty","sourceType":"ar_auto_detect","estimatedWeightGrams":9000}"""
        val observation = GccJson.decodeFromString<WorldObservation>(json)
        assertEquals(9000, observation.estimatedWeightGrams)

        val roundTripped = GccJson.decodeFromString<WorldObservation>(GccJson.encodeToString(observation))
        assertEquals(observation, roundTripped)
    }

    @Test
    fun observationWithFootprintFieldsRoundTrips() {
        val observation = WorldObservation(
            id = 7,
            lat = 41.8603,
            lng = -87.7228,
            itemType = "fence",
            state = "dirty",
            sourceType = "route_scan",
            widthMeters = 3.2,
            yawDegrees = 187.5,
        )
        val json = GccJson.encodeToString(observation)
        val decoded = GccJson.decodeFromString<WorldObservation>(json)
        assertEquals(observation, decoded)
    }
}
