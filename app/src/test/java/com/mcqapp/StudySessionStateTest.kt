package com.mcqapp

import com.mcqapp.ui.study.StudyUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The break strip's rule, in isolation: it suggests at every tenth review,
 * a dismissal holds until the next milestone, and it never blocks anything.
 * Pure state arithmetic — the strip's rendering is covered on the emulator
 * in [com.mcqapp.StudyAndSelectionUiTest].
 */
class StudySessionStateTest {

    @Test
    fun theStripSuggestsAtEveryTenthReview() {
        assertFalse(StudyUiState(reviewed = 0).showBreakStrip)
        assertFalse(StudyUiState(reviewed = 9).showBreakStrip)
        assertTrue(StudyUiState(reviewed = 10).showBreakStrip)
        assertFalse(StudyUiState(reviewed = 11).showBreakStrip)
        assertTrue(StudyUiState(reviewed = 20).showBreakStrip)
    }

    @Test
    fun aDismissalHoldsUntilTheNextMilestone() {
        val dismissed = StudyUiState(reviewed = 10, breakDismissedAt = 10)
        assertFalse(dismissed.showBreakStrip)
        // The next milestone brings it back...
        assertTrue(StudyUiState(reviewed = 20, breakDismissedAt = 10).showBreakStrip)
        // ...and dismissing there silences it again until the one after.
        assertFalse(StudyUiState(reviewed = 20, breakDismissedAt = 20).showBreakStrip)
    }
}
