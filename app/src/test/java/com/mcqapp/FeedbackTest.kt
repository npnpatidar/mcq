package com.mcqapp

import com.mcqapp.domain.Feedback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

    @Test
    fun examModeNeedsManualReveal() {
        assertFalse(Feedback.liveReveal(false, false, false))
        assertTrue(Feedback.liveReveal(false, true, false))
        assertFalse(Feedback.showExplanation(false, false))
        assertTrue(Feedback.showExplanation(false, true))
    }

    @Test
    fun practiceModeRevealsLive() {
        assertTrue(Feedback.liveReveal(true, false, false))
        assertTrue(Feedback.liveReveal(true, true, false))
        assertTrue(Feedback.showExplanation(true, false))
    }

    @Test
    fun ungradedNeverGetsCorrectnessColors() {
        assertFalse(Feedback.liveReveal(true, false, true))
        assertFalse(Feedback.liveReveal(false, true, true))
        // Explanations are still useful without a key.
        assertTrue(Feedback.showExplanation(true, false))
        assertTrue(Feedback.showExplanation(false, true))
    }
}
