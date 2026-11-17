package com.mcqapp

import androidx.compose.ui.graphics.Color
import com.mcqapp.domain.stats.WeakCard
import com.mcqapp.ui.stats.charts.CHART_MIN_NON_TEXT_CONTRAST
import com.mcqapp.ui.stats.charts.ChartSeriesSummary
import com.mcqapp.ui.stats.charts.chartSeriesColor
import com.mcqapp.ui.stats.charts.formatCount
import com.mcqapp.ui.stats.charts.formatDuration
import com.mcqapp.ui.stats.charts.formatPct
import com.mcqapp.ui.stats.charts.niceMax
import com.mcqapp.ui.stats.charts.windowSeries
import com.mcqapp.ui.stats.charts.xAxisLabelIndices
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

    // --- non-text contrast for the series palette ---------------------------

    @Test
    fun seriesRolesKeepTheirColourWhenTheyClearNonTextContrast() {
        assertEquals(3.0, CHART_MIN_NON_TEXT_CONTRAST, 1e-9)
        // Black on white is 21:1, far above 3:1 — the role is kept.
        assertEquals(Color.Black, chartSeriesColor(Color.Black, Color.White, Color.Red))
    }

    @Test
    fun washedOutSeriesRolesFallBackToAReadableRole() {
        // White on white is 1:1. A washed-out role in a badly contrasting
        // custom theme gets swapped for a near-on-surface role, not dropped.
        assertEquals(Color.Red, chartSeriesColor(Color.White, Color.White, Color.Red))
    }

    // --- per-series spoken summary -----------------------------------------

    @Test
    fun windowSeriesKeepsEverySeriesAndBoundsEachIndependently() {
        val windows = windowSeries(
            listOf(
                ChartSeriesSummary("introduced", (1..10).map { "d$it" to "$it" }),
                ChartSeriesSummary("graduated", (1..3).map { "d$it" to "$it" }),
                ChartSeriesSummary("relapsed", emptyList())
            ),
            maxPoints = 4
        )
        // The old firstNotNullOfOrNull summary spoke only the first series;
        // every series must survive, each with its own name and value pairs.
        assertEquals(3, windows.size)
        assertEquals("introduced", windows[0].name)
        assertEquals(
            listOf("d7" to "7", "d8" to "8", "d9" to "9", "d10" to "10"),
            windows[0].shown
        )
        assertEquals(6, windows[0].hidden)
        assertEquals("graduated", windows[1].name)
        assertEquals(3, windows[1].shown.size)
        assertEquals(0, windows[1].hidden)
        assertEquals("relapsed", windows[2].name)
        assertTrue(windows[2].shown.isEmpty())
        assertEquals(0, windows[2].hidden)
    }

    @Test
    fun windowSeriesHandlesNoSeriesAndUnnamedSingleSeries() {
        assertTrue(windowSeries(emptyList()).isEmpty())
        val one = windowSeries(listOf(ChartSeriesSummary(points = listOf("a" to "1"))))
        assertEquals("", one.single().name)
        assertEquals(listOf("a" to "1"), one.single().shown)
        assertEquals(0, one.single().hidden)
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

    // --- x-axis label collision --------------------------------------------

    @Test
    fun xAxisLabelsKeepTheirSpacingAndTheLastLabelWins() {
        // Six slots with room to spare: every sparse label is kept.
        val lefts = listOf(0f, 40f, 80f, 120f, 160f, 200f)
        val widths = List(6) { 20f }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), xAxisLabelIndices(lefts, widths, gap = 4f))

        // The last label starts where its neighbour ends: the neighbour is
        // dropped, not drawn underneath it.
        val tight = listOf(0f, 20f, 40f, 60f, 80f, 90f)
        val wideLast = List(6) { 18f }.toMutableList().also { it[5] = 80f }
        assertEquals(listOf(0, 1, 2, 3, 5), xAxisLabelIndices(tight, wideLast, gap = 2f))

        // A single label is always kept, and malformed parallel lists fail
        // closed rather than indexing past an end.
        assertEquals(listOf(0), xAxisLabelIndices(listOf(0f), listOf(10f), gap = 4f))
        assertEquals(emptyList<Int>(), xAxisLabelIndices(listOf(0f), emptyList(), gap = 4f))
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
