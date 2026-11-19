package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.stats.ExamGroup
import com.mcqapp.domain.stats.ExamStatsBuilder
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.ui.stats.charts.CHART_BASE_HEIGHT_DP
import com.mcqapp.ui.stats.charts.chartHeightDp
import com.mcqapp.ui.stats.charts.stackedSegmentSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Round-2 high fixes for localized labels and chart accessibility:
 *  - ST2: chart date/axis labels use the selected locale, not hard-coded
 *    English; numeric formatting is deliberately untouched.
 *  - ST6: canvas height scales with the font scale, and stacked segments get
 *    named series so a screen reader can distinguish them from bar totals.
 *
 * Pure JVM; no Robolectric.
 */
class Round2StatsLocalizationTest {

    // --- ST2: StatsAggregator date labels follow Input.locale ----------------

    private val day = DayBoundary.DAY_MS
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * 3_600_000L

    private fun input(locale: Locale? = null) = StatsAggregator.Input(
        reviews = emptyList(),
        cards = emptyList(),
        categoryOfQuestion = emptyMap(),
        paperTitleOf = emptyMap(),
        scheduler = FsrsScheduler(config = SchedulerConfig()),
        config = SchedulerConfig(),
        now = now,
        zone = DayBoundary.utc()
    ).let { base -> if (locale != null) base.copy(locale = locale) else base }

    @Test
    fun forecastDatesUseTheGivenLocaleNotHardcodedEnglish() {
        val english = StatsAggregator.buildWorkload(input(Locale.ENGLISH), null, StatsAggregator.Range.ALL)
        val hindi = StatsAggregator.buildWorkload(input(Locale("hi")), null, StatsAggregator.Range.ALL)
        assertEquals(30, english.forecast.days.size)
        // Positive: the Hindi locale changes the month label away from English.
        assertNotEquals(english.forecast.days.first().label, hindi.forecast.days.first().label)
        // Negative: an Input that omits locale still yields the stable English
        // label, so existing callers/tests that rely on "d MMM" are unaffected.
        val default = StatsAggregator.buildWorkload(input(), null, StatsAggregator.Range.ALL)
        assertEquals(english.forecast.days.first().label, default.forecast.days.first().label)
    }

    // --- ST2: ExamStats trend labels follow the given locale ----------------

    private fun attempt(id: Long, score: Double, finishedAt: Long, duration: Long) = Attempt(
        id = id,
        paperId = "p1",
        title = "Paper p1",
        totalQuestions = 20,
        correctCount = 10,
        wrongCount = 5,
        skippedCount = 5,
        score = score,
        maxScore = 20.0,
        durationSeconds = duration,
        finishedAt = finishedAt,
        testType = TestType.RANDOM.name,
        requestedCount = 20,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = ExclusionMode.NONE.name,
        variant = null,
        compositionJson = ""
    )

    @Test
    fun examTrendLabelsUseTheGivenLocale() {
        // One comparable attempt a month apart forces MONTHLY bucketing, where
        // the label is "MMM yy"; a Hindi locale must differ from English.
        val t0 = LocalDate.of(2025, 1, 1).toEpochDay() * day
        val attempts = listOf(
            attempt(1, score = 16.0, finishedAt = t0, duration = 1200),
            attempt(2, score = 18.0, finishedAt = t0 + 200 * day, duration = 1200)
        )
        val english = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT, locale = Locale.ENGLISH)
        val hindi = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT, locale = Locale("hi"))
        assertTrue(english.trend.isNotEmpty())
        assertTrue(hindi.trend.isNotEmpty())
        assertNotEquals(english.trend.first().label, hindi.trend.first().label)
        // Default stays English for existing callers.
        val default = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT)
        assertEquals(english.trend.first().label, default.trend.first().label)
    }

    // --- ST6: font-scale-aware canvas ---------------------------------------

    @Test
    fun chartHeightScalesWithFontScaleWithinBounds() {
        assertEquals(CHART_BASE_HEIGHT_DP, chartHeightDp(1f), 1e-6f)
        assertEquals(CHART_BASE_HEIGHT_DP * 1.5f, chartHeightDp(1.5f), 1e-6f)
        // Clamped so an extreme accessibility scale cannot dominate the screen.
        assertEquals(CHART_BASE_HEIGHT_DP * 2f, chartHeightDp(3f), 1e-6f)
        assertTrue(chartHeightDp(0.5f) >= CHART_BASE_HEIGHT_DP)
    }

    // --- ST6: named stacked segments ----------------------------------------

    @Test
    fun stackedSegmentSeriesNamesEachSegmentAndKeepsOnlyPresentValues() {
        val series = stackedSegmentSeries(
            barLabels = listOf("d1", "d2"),
            barSegments = listOf(listOf(3.0, 1.0), listOf(2.0, 4.0)),
            segmentNames = listOf("Learning", "Review"),
            labelValue = { it.toInt().toString() }
        )
        assertEquals(2, series.size)
        assertEquals("Learning", series[0].name)
        assertEquals(listOf("d1" to "3", "d2" to "2"), series[0].points)
        assertEquals("Review", series[1].name)
        assertEquals(listOf("d1" to "1", "d2" to "4"), series[1].points)

        // A bar missing a segment is skipped rather than inventing a value.
        val sparse = stackedSegmentSeries(
            barLabels = listOf("a", "b"),
            barSegments = listOf(listOf(1.0), listOf(2.0, 5.0)),
            segmentNames = listOf("A", "B"),
            labelValue = { it.toInt().toString() }
        )
        assertEquals(listOf("a" to "1", "b" to "2"), sparse[0].points)
        assertEquals(listOf("b" to "5"), sparse[1].points)
    }
}
