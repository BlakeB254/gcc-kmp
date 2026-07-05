package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerPromotionTrackerTest {

    @Test
    fun promotesAfterTwoConsecutiveCloseTicks() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        val first = tracker.onTick(1.5)
        assertFalse(first.promoted)
        assertEquals(1, first.streak)
        assertEquals(2, first.streakTicks)

        val second = tracker.onTick(1.5)
        assertTrue(second.promoted)
        assertEquals(2, second.streak)
    }

    @Test
    fun farTickResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5).promoted)

        val farTick = tracker.onTick(5.0) // too far — resets
        assertFalse(farTick.promoted)
        assertEquals(0, farTick.streak)

        assertFalse(tracker.onTick(1.5).promoted) // streak restarts at 1
        assertTrue(tracker.onTick(1.5).promoted)  // now at 2
    }

    @Test
    fun nullDistanceResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5).promoted)

        val nullTick = tracker.onTick(null)
        assertFalse(nullTick.promoted)
        assertEquals(0, nullTick.streak)

        assertFalse(tracker.onTick(1.5).promoted)
    }

    @Test
    fun requiresFreshStreakAfterPromotion() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5).promoted)
        assertTrue(tracker.onTick(1.5).promoted)  // first promotion

        val afterPromotion = tracker.onTick(1.5)
        assertFalse(afterPromotion.promoted) // must NOT immediately re-promote — streak restarts at 1
        assertEquals(1, afterPromotion.streak)

        assertTrue(tracker.onTick(1.5).promoted) // now back at 2, promotes again
    }
}
