package com.mcqapp

import com.mcqapp.util.IntervalFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The labels on the answer buttons are the only way a learner compares the four
 * grades before committing, so the unit ladder is pinned here: a delay that
 * rounds to `0m` would be indistinguishable from no delay at all, and a card
 * that is not scheduled must not look like a very short one.
 */
class IntervalFormatTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `sub-minute delays read as seconds`() {
        assertEquals("1s", IntervalFormat.format(400))
        assertEquals("30s", IntervalFormat.format(30 * second))
        assertEquals("59s", IntervalFormat.format(59 * second))
    }

    @Test
    fun `sub-hour delays read as minutes`() {
        assertEquals("1m", IntervalFormat.format(minute))
        assertEquals("10m", IntervalFormat.format(10 * minute))
        assertEquals("59m", IntervalFormat.format(59 * minute))
    }

    @Test
    fun `sub-day delays read as hours`() {
        assertEquals("1h", IntervalFormat.format(hour))
        assertEquals("8h", IntervalFormat.format(8 * hour))
        assertEquals("23h", IntervalFormat.format(23 * hour))
    }

    @Test
    fun `a part hour keeps one decimal`() {
        assertEquals("8.5h", IntervalFormat.format(8 * hour + 30 * minute))
    }

    @Test
    fun `sub-month delays read as whole days`() {
        assertEquals("1d", IntervalFormat.format(day))
        assertEquals("3d", IntervalFormat.format(3 * day))
        assertEquals("29d", IntervalFormat.format(29 * day))
    }

    @Test
    fun `months carry one decimal`() {
        assertEquals("1.5mo", IntervalFormat.format(45 * day))
        assertEquals("2.5mo", IntervalFormat.format(75 * day))
    }

    @Test
    fun `years carry one decimal`() {
        assertEquals("1.1y", IntervalFormat.format(400 * day))
        assertEquals("1.2y", IntervalFormat.format(440 * day))
    }

    @Test
    fun `a delay that is never scheduled is not a zero delay`() {
        assertEquals(IntervalFormat.NEVER, IntervalFormat.format(0L))
        assertEquals(IntervalFormat.NEVER, IntervalFormat.format(-1L))
        assertEquals(IntervalFormat.NEVER, IntervalFormat.format(-day))
    }

    @Test
    fun `a rounding-to-zero delay still shows a unit`() {
        // The relearn delay is ten minutes, but a custom one could be a second;
        // neither may render as "0m".
        assertEquals("1s", IntervalFormat.format(1))
        assertEquals("1s", IntervalFormat.format(500))
        // 30s stays in seconds rather than rounding up to the next rung.
        assertEquals("30s", IntervalFormat.format(30 * second))
    }

    @Test
    fun `a fraction of a day rounds to a whole day`() {
        // Just under a month still reads in days, rounding up to a clean 30d
        // rather than "29.10d".
        assertEquals("30d", IntervalFormat.format(29 * day + 23 * hour + 59 * minute))
    }

    @Test
    fun `a fraction of a month carries into the month`() {
        // 30.1 days is over the month threshold and reads "1mo" via the carry.
        assertEquals("1mo", IntervalFormat.format(30 * day + 3 * hour))
    }

    @Test
    fun `the maximum interval stays readable`() {
        assertEquals("100y", IntervalFormat.format(36_500 * day))
    }

}
