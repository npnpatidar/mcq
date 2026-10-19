package com.mcqapp

import com.mcqapp.domain.clockMayRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock gate both sessions share. Study's question clock and the test
 * screen's dwell used to ask only "is there an answerable card", so a return
 * from the background restarted a clock for a card that was scrolled out of the
 * viewport or still covered by a dialog - the screen's visibility effect does
 * not re-fire on resume, because its key did not change.
 */
class SessionClockTest {

    @Test
    fun theClockRunsOnlyWhileVisibleUnpausedAndAnswerable() {
        for (inView in listOf(true, false)) {
            for (paused in listOf(true, false)) {
                for (answerable in listOf(true, false)) {
                    assertEquals(
                        "inView=$inView paused=$paused answerable=$answerable",
                        inView && !paused && answerable,
                        clockMayRun(
                            questionInView = inView,
                            pausedByUser = paused,
                            answerable = answerable
                        )
                    )
                }
            }
        }
    }

    @Test
    fun aForegroundDoesNotResumeAClockForAHiddenCard() {
        // The card was scrolled past the viewport (or a dialog covered it) when
        // the app was backgrounded; a foreground has to consult that report,
        // not just the fact that an answerable card is loaded.
        assertFalse(clockMayRun(questionInView = false, pausedByUser = false, answerable = true))
        assertTrue(clockMayRun(questionInView = true, pausedByUser = false, answerable = true))
        assertFalse(clockMayRun(questionInView = true, pausedByUser = true, answerable = true))
    }
}
