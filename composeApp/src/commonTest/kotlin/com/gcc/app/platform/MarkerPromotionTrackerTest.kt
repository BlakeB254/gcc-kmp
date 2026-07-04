package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerPromotionTrackerTest {

    @Test
    fun promotesAfterTwoConsecutiveCloseTicks() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertTrue(tracker.onTick(1.5))
    }

    @Test
    fun farTickResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertFalse(tracker.onTick(5.0)) // too far — resets
        assertFalse(tracker.onTick(1.5)) // streak restarts at 1
        assertTrue(tracker.onTick(1.5))  // now at 2
    }

    @Test
    fun nullDistanceResetsStreak() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertFalse(tracker.onTick(null))
        assertFalse(tracker.onTick(1.5))
    }

    @Test
    fun requiresFreshStreakAfterPromotion() {
        val tracker = MarkerPromotionTracker(distanceThresholdMeters = 2.0, streakTicks = 2)
        assertFalse(tracker.onTick(1.5))
        assertTrue(tracker.onTick(1.5))  // first promotion
        assertFalse(tracker.onTick(1.5)) // must NOT immediately re-promote — streak restarts at 1
        assertTrue(tracker.onTick(1.5))  // now back at 2, promotes again
    }
}
