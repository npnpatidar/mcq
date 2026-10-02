package com.mcqapp

import com.mcqapp.domain.ExamTiming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `durationMinutes` came from imported files and was multiplied by 60 as an
 * `Int`. Past ~35.8 million that wrapped negative, so the exam timer silently
 * never started and the header showed a negative clock.
 */
class ExamTimingTest {

    @Test
    fun noDurationIsPreservedAsNoTimer() {
        assertEquals(0, ExamTiming.minutesFrom(null))
        assertEquals(0, ExamTiming.minutesFrom(0))
        assertEquals(0L, ExamTiming.secondsFrom(0))
    }

    @Test
    fun ordinaryDurationsAreUntouched() {
        assertEquals(30, ExamTiming.minutesFrom(30))
        assertEquals(1800L, ExamTiming.secondsFrom(30))
        assertEquals(90, ExamTiming.minutesFrom(90))
    }

    @Test
    fun negativeDurationsBecomeNoTimer() {
        assertEquals(0, ExamTiming.minutesFrom(-5))
        assertEquals(0L, ExamTiming.secondsFrom(-5))
    }

    @Test
    fun aHostileDurationCannotOverflow() {
        // The exact value from the finding: 40_000_000 * 60 wraps to negative.
        val hostile = 40_000_000
        assertEquals(ExamTiming.MAX_MINUTES, ExamTiming.minutesFrom(hostile))
        val seconds = ExamTiming.secondsFrom(ExamTiming.minutesFrom(hostile))
        assertEquals(true, seconds > 0)
        assertEquals(ExamTiming.MAX_MINUTES.toLong() * 60L, seconds)
        // Still inside the Int the UI state holds.
        assertEquals(true, seconds <= Int.MAX_VALUE)
    }

    @Test
    fun intMaxIsClampedRatherThanWrapped() {
        val seconds = ExamTiming.secondsFrom(ExamTiming.minutesFrom(Int.MAX_VALUE))
        assertEquals(ExamTiming.MAX_MINUTES.toLong() * 60L, seconds)
        assertEquals(true, seconds > 0)
    }

    @Test
    fun secondsFromNeverReturnsANegative() {
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 1, 1_000_000, Int.MAX_VALUE)) {
            val seconds = ExamTiming.secondsFrom(ExamTiming.minutesFrom(minutes))
            assertEquals("minutes=$minutes", true, seconds >= 0)
        }
    }

    @Test
    fun aWeekIsTheCeiling() {
        assertEquals(7 * 24 * 60, ExamTiming.MAX_MINUTES)
        assertEquals(ExamTiming.MAX_MINUTES, ExamTiming.minutesFrom(ExamTiming.MAX_MINUTES))
        assertEquals(ExamTiming.MAX_MINUTES, ExamTiming.minutesFrom(ExamTiming.MAX_MINUTES + 1))
    }
}
