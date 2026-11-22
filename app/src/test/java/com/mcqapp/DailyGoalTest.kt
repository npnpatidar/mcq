package com.mcqapp

import com.mcqapp.domain.DailyGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily goal is a gauge, not a quota. These are the two facts worth
 * pinning without a device: an install that never touched the preferences sees
 * the off/20 defaults, and going past the target changes nothing about study
 * availability.
 */
class DailyGoalTest {

    @Test
    fun freshInstallDefaultsToOffWithATargetOfTwenty() {
        val progress = DailyGoal.progress(
            reviews = 0,
            count = DailyGoal.DEFAULT_COUNT,
            enabled = DailyGoal.DEFAULT_ENABLED
        )
        assertFalse(DailyGoal.DEFAULT_ENABLED)
        assertEquals(20, DailyGoal.DEFAULT_COUNT)
        assertEquals(20, progress.target)
        assertEquals(0f, progress.fraction, 0f)
        assertFalse(progress.reached)
        assertFalse(progress.exceeded)
    }

    @Test
    fun disabledGoalReportsNothingButNeverBlocksStudy() {
        val progress = DailyGoal.progress(reviews = 57, count = 20, enabled = false)
        assertEquals(0f, progress.fraction, 0f)
        assertFalse(progress.reached)
        assertFalse(progress.exceeded)
        assertTrue("an off goal cannot gate study", DailyGoal.mayStudy(progress))
    }

    @Test
    fun enabledGoalTracksProgressAndReportsExcessHonestly() {
        val below = DailyGoal.progress(reviews = 14, count = 20, enabled = true)
        assertEquals(14, below.reviews)
        assertEquals(0.7f, below.fraction, 0.001f)
        assertFalse(below.reached)
        assertFalse(below.exceeded)

        val at = DailyGoal.progress(reviews = 20, count = 20, enabled = true)
        assertTrue(at.reached)
        assertFalse("hitting the target is not exceeding it", at.exceeded)
        assertEquals(1f, at.fraction, 0f)

        val over = DailyGoal.progress(reviews = 25, count = 20, enabled = true)
        assertTrue(over.exceeded)
        assertTrue(over.reached)
        assertEquals("the bar stops at the end, the label still says 25", 1f, over.fraction, 0f)
        assertEquals(25, over.reviews)
    }

    @Test
    fun exceedingTheGoalStillAllowsStudy() {
        // Owner ruling: a learner on a goal of 1 who has already done 50
        // reviews must be able to keep studying. The goal has no say in it.
        val over = DailyGoal.progress(reviews = 50, count = 1, enabled = true)
        assertTrue(over.exceeded)
        assertTrue(DailyGoal.mayStudy(over))
        assertTrue(DailyGoal.mayStudy(DailyGoal.progress(10_000, 20, true)))
    }

    @Test
    fun corruptCountsAreClampedBeforeTheyReachTheBar() {
        assertEquals(DailyGoal.MIN_COUNT, DailyGoal.normalizeCount(0))
        assertEquals(DailyGoal.MIN_COUNT, DailyGoal.normalizeCount(-5))
        assertEquals(DailyGoal.MAX_COUNT, DailyGoal.normalizeCount(10_000))
        assertEquals(20, DailyGoal.normalizeCount(20))

        // A zero or negative stored count must not divide by zero or invert the bar.
        val bad = DailyGoal.progress(reviews = 3, count = 0, enabled = true)
        assertEquals(1, bad.target)
        assertEquals(1f, bad.fraction, 0f)
        assertTrue(bad.exceeded)
    }
}
