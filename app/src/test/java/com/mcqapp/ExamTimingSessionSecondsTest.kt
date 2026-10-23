package com.mcqapp

import com.mcqapp.domain.ExamTiming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The duration picker's contract: which of the three candidate durations —
 * drill minutes, the per-attempt override, the paper's stored value — rules a
 * new session, and that 0 stays a legal "untimed" rather than collapsing into
 * the paper default.
 */
class ExamTimingSessionSecondsTest {

    private val paperMinutes = 20

    @Test
    fun noOverrideFallsBackToThePaperDuration() {
        assertEquals(
            (paperMinutes * 60).toLong(),
            ExamTiming.sessionSeconds(
                isDrill = false,
                drillMinutes = 0,
                overrideMinutes = ExamTiming.USE_PAPER_DURATION,
                paperMinutes = paperMinutes
            )
        )
    }

    @Test
    fun anExplicitOverrideBeatsThePaperDuration() {
        assertEquals(
            45L * 60,
            ExamTiming.sessionSeconds(
                isDrill = false,
                drillMinutes = 0,
                overrideMinutes = 45,
                paperMinutes = paperMinutes
            )
        )
    }

    @Test
    fun anOverrideOfZeroMeansUntimedNotThePaperDefault() {
        assertEquals(
            0L,
            ExamTiming.sessionSeconds(
                isDrill = false,
                drillMinutes = 0,
                overrideMinutes = 0,
                paperMinutes = paperMinutes
            )
        )
    }

    @Test
    fun drillMinutesStillWinInsideADrill() {
        assertEquals(
            5L * 60,
            ExamTiming.sessionSeconds(
                isDrill = true,
                drillMinutes = 5,
                overrideMinutes = 90,
                paperMinutes = paperMinutes
            )
        )
    }

    @Test
    fun aDrillWithoutMinutesUsesTheOverrideLikeAnySession() {
        assertEquals(
            30L * 60,
            ExamTiming.sessionSeconds(
                isDrill = true,
                drillMinutes = 0,
                overrideMinutes = 30,
                paperMinutes = paperMinutes
            )
        )
    }

    @Test
    fun anUntimedPaperStaysUntimedWithoutAnOverride() {
        assertEquals(
            0L,
            ExamTiming.sessionSeconds(
                isDrill = false,
                drillMinutes = 0,
                overrideMinutes = ExamTiming.USE_PAPER_DURATION,
                paperMinutes = 0
            )
        )
    }

    @Test
    fun anAbsurdOverrideIsClampedToTheSameCeilingAsImports() {
        assertEquals(
            ExamTiming.MAX_MINUTES.toLong() * 60,
            ExamTiming.sessionSeconds(
                isDrill = false,
                drillMinutes = 0,
                overrideMinutes = 10_000_000,
                paperMinutes = paperMinutes
            )
        )
    }
}
