package com.mcqapp

import com.mcqapp.domain.ExamTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun theDefaultPaceIs60Seconds() {
        assertEquals(60, ExamTiming.DEFAULT_SECONDS_PER_QUESTION)
        assertEquals(20, ExamTiming.MIN_SECONDS_PER_QUESTION)
        assertEquals(180, ExamTiming.MAX_SECONDS_PER_QUESTION)
    }

    @Test
    fun suggestedMinutesMirrorsTheLengthPresets() {
        assertEquals(20, ExamTiming.suggestedMinutes(20))
        assertEquals(50, ExamTiming.suggestedMinutes(50))
        assertEquals(80, ExamTiming.suggestedMinutes(80))
        assertEquals(100, ExamTiming.suggestedMinutes(100))
        assertEquals(120, ExamTiming.suggestedMinutes(120))
        assertEquals(150, ExamTiming.suggestedMinutes(150))
    }

    @Test
    fun suggestedMinutesRoundsUpAndHasAFloor() {
        // 20 questions at 20 s = 400 s = 6.67 min -> 7.
        assertEquals(7, ExamTiming.suggestedMinutes(20, secondsPerQuestion = 20))
        assertEquals(30, ExamTiming.suggestedMinutes(20, secondsPerQuestion = 90))
        assertEquals(1, ExamTiming.suggestedMinutes(1))
        assertEquals(1, ExamTiming.suggestedMinutes(0))
    }

    @Test
    fun thePaceClampIs20To180() {
        assertEquals(20, ExamTiming.clampPace(0))
        assertEquals(20, ExamTiming.clampPace(5))
        assertEquals(60, ExamTiming.clampPace(60))
        assertEquals(180, ExamTiming.clampPace(300))
    }

    @Test
    fun aPlausiblePaperPaceIsOffered() {
        // 120 minutes over 100 questions = 72 s/question.
        assertEquals(72, ExamTiming.paperPaceSeconds(paperMinutes = 120, totalQuestions = 100))
        assertEquals(20, ExamTiming.paperPaceSeconds(paperMinutes = 10, totalQuestions = 30))
        assertEquals(180, ExamTiming.paperPaceSeconds(paperMinutes = 30, totalQuestions = 10))
    }

    @Test
    fun anImplausiblePaperPaceIsNotOffered() {
        // 120 minutes over 1,000 questions = 7.2 s/question: absurd as a target.
        assertNull(ExamTiming.paperPaceSeconds(paperMinutes = 120, totalQuestions = 1_000))
        assertNull(ExamTiming.paperPaceSeconds(paperMinutes = 600, totalQuestions = 10))
        assertNull(ExamTiming.paperPaceSeconds(paperMinutes = 0, totalQuestions = 100))
        assertNull(ExamTiming.paperPaceSeconds(paperMinutes = 120, totalQuestions = 0))
    }
}
