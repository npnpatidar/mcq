package com.mcqapp

import com.mcqapp.ui.study.StudyUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The summary's time split is arithmetic on the two figures the ViewModel
 * banks: question time is the question clock, and explanation time is
 * whatever the sitting clock kept running for (the answer and its explanation).
 * Pure JVM, so it runs on every host, unlike the DB-backed ViewModel tests.
 */
class StudySummaryTimesTest {

    @Test
    fun explanationTimeIsTheRestOfTheSitting() {
        val state = StudyUiState(questionSeconds = 120L, sessionTotalSeconds = 300L)
        assertEquals(180L, state.explanationSeconds)
    }

    @Test
    fun aSittingSpentOnlyOnQuestionsHasNoExplanationTime() {
        val state = StudyUiState(questionSeconds = 95L, sessionTotalSeconds = 95L)
        assertEquals(0L, state.explanationSeconds)
    }

    /**
     * The two clocks are banked at slightly different instants (a stop pauses
     * the question clock, then the sitting clock), so the split must clamp
     * rather than render a negative explanation time.
     */
    @Test
    fun theSplitNeverGoesNegative() {
        val state = StudyUiState(questionSeconds = 7L, sessionTotalSeconds = 5L)
        assertEquals(0L, state.explanationSeconds)
    }

    @Test
    fun aSessionThatNeverStartedHasNoTimesToShow() {
        val state = StudyUiState()
        assertEquals(0L, state.questionSeconds)
        assertEquals(0L, state.sessionTotalSeconds)
        assertEquals(0L, state.explanationSeconds)
    }
}
