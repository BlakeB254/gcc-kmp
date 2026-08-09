package com.gcc.app.polling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PollingGateTest {

    private val gate = PollingGate(
        baseIntervalMillis = 25_000L,
        maxIntervalMillis = 120_000L,
    )

    @Test
    fun backgroundVisibilityIssuesNoScheduledPoll() {
        assertNull(gate.nextDelayMillis(AppVisibility.Background, consecutiveUnchanged = 0))
        assertNull(gate.nextDelayMillis(AppVisibility.Background, consecutiveUnchanged = 5))
    }

    @Test
    fun foregroundUsesBaseCadenceAfterChangeOrStart() {
        assertEquals(25_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 0))
    }

    @Test
    fun consecutiveUnchangedAppliesCappedBackoff() {
        assertEquals(50_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 1))
        assertEquals(100_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 2))
        assertEquals(120_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 3))
        assertEquals(120_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 10))
    }

    @Test
    fun changedResponseResetsBackoffToBase() {
        assertEquals(120_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 8))
        assertEquals(25_000L, gate.nextDelayMillis(AppVisibility.Foreground, consecutiveUnchanged = 0))
    }

    @Test
    fun rejectsNonPositiveIntervals() {
        assertFailsWith<IllegalArgumentException> {
            PollingGate(baseIntervalMillis = 0L, maxIntervalMillis = 1_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            PollingGate(baseIntervalMillis = 30_000L, maxIntervalMillis = 10_000L)
        }
    }
}
