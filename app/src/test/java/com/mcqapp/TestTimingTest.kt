package com.mcqapp

import com.mcqapp.domain.Dwell
import com.mcqapp.domain.TimerWarnings
import org.junit.Assert.assertEquals
import org.junit.Test

class TestTimingTest {

    @Test
    fun warningsFireOnceWhenCrossingThreshold() {
        assertEquals(listOf(300), TimerWarnings.newlyDue(301, 300))
        assertEquals(listOf(300), TimerWarnings.newlyDue(500, 250))
        assertEquals(listOf(300, 60), TimerWarnings.newlyDue(301, 59))
        assertEquals(emptyList<Int>(), TimerWarnings.newlyDue(300, 299))
        assertEquals(emptyList<Int>(), TimerWarnings.newlyDue(61, 61))
        assertEquals(emptyList<Int>(), TimerWarnings.newlyDue(30, 400))
    }

    @Test
    fun warningMessagesAreSpecific() {
        assertEquals("5 minutes left", TimerWarnings.message(300))
        assertEquals(
            "1 minute left — the test auto-submits at zero",
            TimerWarnings.message(60)
        )
    }

    @Test
    fun dwellAccumulatesPerQuestion() {
        var dwell = emptyMap<String, Long>()
        dwell = Dwell.add(dwell, "q1", 12)
        dwell = Dwell.add(dwell, "q1", 8)
        dwell = Dwell.add(dwell, "q2", 5)
        dwell = Dwell.add(dwell, "q2", 0)
        assertEquals(mapOf("q1" to 20L, "q2" to 5L), dwell)
    }

    @Test
    fun dwellFormatsWithoutPaddingMinutes() {
        assertEquals("0:00", Dwell.format(0))
        assertEquals("0:42", Dwell.format(42))
        assertEquals("4:05", Dwell.format(245))
        assertEquals("61:01", Dwell.format(3661))
    }
}
