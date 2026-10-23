package com.mcqapp

import com.mcqapp.domain.Countdown
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exam countdown is anchored on elapsed real time, not on tick counts, so
 * a fat tick — or a paused scheduler — cannot steal or gift a second. The
 * helper is pure; the ViewModel's tick loop calls it with the same anchor.
 */
class CountdownWallClockTest {

    @Test
    fun countsDownExactlyPerSecond() {
        val anchor = 1_000_000L
        for (elapsed in 0..10) {
            assertEquals(
                "remaining after ${elapsed}s elapsed must track the wall clock",
                60 - elapsed,
                Countdown.remainingAt(60, anchor, anchor + elapsed * 1000L)
            )
        }
    }

    @Test
    fun aFatTickCatchesUpInsteadOfSlipping() {
        // A tick that ran 5s late used to leave remaining 4s too high (it
        // subtracted one per iteration regardless of how late it ran); the
        // anchor recomputes the whole gap in one call.
        assertEquals(55, Countdown.remainingAt(60, 0L, 5_000L))
        assertEquals(0, Countdown.remainingAt(60, 0L, 60_000L))
    }

    @Test
    fun saturatesAtZeroForever() {
        assertEquals(0, Countdown.remainingAt(10, 0L, 10_000L))
        // An hour behind: still zero, never negative.
        assertEquals(0, Countdown.remainingAt(10, 0L, 3_710_000L))
    }

    @Test
    fun aClockRunningBackwardsDoesNotGiftSeconds() {
        // elapsedRealtime never moves back, but a caller handing a wall-clock
        // timestamp after a time change could: elapsed is clamped at zero, so
        // remaining never grows past the anchor's remaining.
        assertEquals(60, Countdown.remainingAt(60, 5_000L, 0L))
        assertEquals(60, Countdown.remainingAt(60, 5_000L, 1_000L))
    }

    @Test
    fun hugeElapsedStaysRepresentable() {
        // An idle session 30 real days later must not overflow on its way to 0.
        assertEquals(0, Countdown.remainingAt(60, 0L, 30L * 24 * 60 * 60 * 1000L))
    }
}
