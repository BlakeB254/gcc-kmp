package com.gcc.shared.serialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LenientInstantSerializerTest {

    @Test
    fun parsesStandardIso() {
        assertEquals(
            "2026-07-03T15:35:28.218012Z",
            parseFlexibleInstant("2026-07-03T15:35:28.218012Z").toString(),
        )
    }

    @Test
    fun parsesPostgresSpaceAndBareOffset() {
        // The exact string that took down the bounties tab.
        val parsed = parseFlexibleInstant("2026-07-03 15:35:28.218012+00")
        assertEquals("2026-07-03T15:35:28.218012Z", parsed.toString())
    }

    @Test
    fun parsesFourDigitOffset() {
        val parsed = parseFlexibleInstant("2026-07-03 10:35:28-0500")
        assertEquals("2026-07-03T15:35:28Z", parsed.toString())
    }

    @Test
    fun parsesNaiveTimestampAsUtc() {
        val parsed = parseFlexibleInstant("2026-07-03 15:35:28")
        assertEquals("2026-07-03T15:35:28Z", parsed.toString())
    }

    @Test
    fun garbageStillFails() {
        assertFailsWith<Exception> { parseFlexibleInstant("not a date") }
    }
}
