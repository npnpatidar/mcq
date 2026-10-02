package com.mcqapp

import com.mcqapp.domain.Feedback
import org.junit.Assert.assertEquals
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

    @Test
    fun revealMarkerTicksOnlyTheCorrectOption() {
        assertEquals(Feedback.RevealMarker.CORRECT, Feedback.revealMarker(true, true))
        assertEquals(Feedback.RevealMarker.CORRECT, Feedback.revealMarker(true, false))
    }

    @Test
    fun revealMarkerCrossesOnlyAWrongSelection() {
        assertEquals(Feedback.RevealMarker.WRONG, Feedback.revealMarker(false, true))
    }

    @Test
    fun revealMarkerIsSilentForUntouchedWrongOptions() {
        // Practice mode reveals every option, so an unpicked wrong option is
        // the common case: it must not be marked, least of all with a tick.
        assertEquals(Feedback.RevealMarker.NONE, Feedback.revealMarker(false, false))
    }
}
