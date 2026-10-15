package com.mcqapp

import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.charts.formatCount
import com.mcqapp.ui.stats.charts.formatDuration
import com.mcqapp.ui.stats.charts.formatPct
import com.mcqapp.ui.stats.charts.niceMax
import com.mcqapp.ui.stats.charts.xAxisLabelStep
import com.mcqapp.ui.stats.charts.yTicks
import com.mcqapp.ui.stats.statsCardSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Positive and negative cases for the pure chart maths and axis formatters. */
class StatsChartKitTest {

    // --- niceMax ------------------------------------------------------------

    @Test
    fun niceMaxPicksA125Or10Headline() {
        assertEquals(1.0, niceMax(1.0), 1e-9)
        assertEquals(2.0, niceMax(1.5), 1e-9)
        assertEquals(5.0, niceMax(3.0), 1e-9)
        assertEquals(10.0, niceMax(6.0), 1e-9)
        assertEquals(20.0, niceMax(15.0), 1e-9)
        assertEquals(100.0, niceMax(87.0), 1e-9)
    }

    @Test
    fun niceMaxNeverReturnsZeroOrANegativeCeiling() {
        assertEquals(1.0, niceMax(0.0), 1e-9)
        assertEquals(1.0, niceMax(-4.0), 1e-9)
        assertTrue(niceMax(0.07) > 0.0)
        assertTrue(niceMax(0.07) >= 0.07)
    }

    // --- yTicks -------------------------------------------------------------

    @Test
    fun yTicksSpanZeroToTheCeiling() {
        val ticks = yTicks(10.0, count = 4)
        assertEquals(listOf(0.0, 2.5, 5.0, 7.5, 10.0), ticks)
    }

    @Test
    fun yTicksOfANonPositiveCeilingIsJustZero() {
        assertEquals(listOf(0.0), yTicks(0.0))
        assertEquals(listOf(0.0), yTicks(-3.0))
    }

    // --- xAxisLabelStep -----------------------------------------------------

    @Test
    fun xAxisLabelStepStaysLabelable() {
        assertEquals(1, xAxisLabelStep(0))
        assertEquals(1, xAxisLabelStep(1))
        assertEquals(1, xAxisLabelStep(6))
        assertEquals(2, xAxisLabelStep(12))
        assertEquals(10, xAxisLabelStep(60))
    }

    // --- formatters ---------------------------------------------------------

    @Test
    fun formatPctRoundsToWholePercent() {
        assertEquals("0%", formatPct(0.0))
        assertEquals("91%", formatPct(0.912))
        assertEquals("100%", formatPct(1.0))
    }

    @Test
    fun formatCountDropsAFakeFraction() {
        assertEquals("42", formatCount(42.0))
        assertEquals("4", formatCount(4.0))
        // Sub-unit means get one decimal; whole numbers never do.
        assertEquals("4.2", formatCount(4.2))
        assertEquals("10", formatCount(10.0))
    }

    @Test
    fun formatDurationClimbsUnits() {
        assertEquals("45 s", formatDuration(45.0))
        assertEquals("1m", formatDuration(90.0))
        assertEquals("59m", formatDuration(59.0 * 60))
        assertEquals("1h", formatDuration(3600.0))
        assertEquals("1 h 30 m", formatDuration(5400.0))
    }

    // --- ranked-row summary -------------------------------------------------

    private fun card(
        reviews: Int = 12,
        lapses: Int = 3,
        againRate: Double = 0.25,
        avgSeconds: Double? = 8.4
    ) = WeakCard(
        paperId = "p1",
        questionId = "q1",
        reviews = reviews,
        lapses = lapses,
        againRate = againRate,
        avgSeconds = avgSeconds,
        lastReviewedAt = 0L,
        leech = false
    )

    @Test
    fun statsCardSummaryReadsAsCountsRateAndSpeed() {
        assertEquals("12× · 3× again · 25% · 8s", statsCardSummary(card()))
    }

    @Test
    fun statsCardSummaryOmitsSpeedWhenNeverTimed() {
        val summary = statsCardSummary(card(avgSeconds = null))
        assertEquals("12× · 3× again · 25%", summary)
        assertTrue(!summary.contains("s ·") && !summary.endsWith("s"))
    }
}
