package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.ProgressData
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The pure Progress aggregation (plan §5.1/§5.3). */
class ProgressStatsTest {

    private val day = DayBoundary.DAY_MS
    private val hour = 3_600_000L

    /** 15 Jun 2026, 12:00 UTC — the study day started at 04:00 that morning. */
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * hour
    private val todayStart = DayBoundary.startOfDay(now)

    private fun review(
        questionId: String,
        rating: Int,
        reviewedAt: Long,
        paperId: String = "p1",
        stateBefore: CardStateKind? = CardStateKind.NEW,
        elapsedSeconds: Long? = 10L
    ) = ReplayReview(
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 3,
        predictedRetention = null,
        intervalBeforeDays = null
    )

    private fun progress(
        reviews: List<ReplayReview>,
        range: StatsAggregator.Range = StatsAggregator.Range.ALL,
        scopePaperId: String? = null
    ): ProgressData {
        val accumulator = StatsAggregator.ProgressAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = SchedulerConfig(),
            zone = 0L
        )
        reviews.forEach(accumulator::add)
        return accumulator.toData()
    }

    @Test
    fun emptyAllRangeHasNoComparisonOrTrend() {
        val data = progress(emptyList())
        assertEquals(0, data.totals.reviews)
        assertEquals(0, data.totals.introduced)
        assertEquals(0, data.totals.graduated)
        assertEquals(0, data.totals.relapsed)
        assertEquals(0, data.totals.again)
        assertNull(data.totals.againRate)
        assertNull(data.totals.lapseRate)
        assertNull(data.comparison)
        assertTrue(data.trend.isEmpty())
    }

    @Test
    fun emptyDayRangeKeepsTheComparisonAndEmptyBuckets() {
        val data = progress(emptyList(), StatsAggregator.Range.D30)
        val comparison = data.comparison!!
        assertEquals(0, comparison.currentReviews)
        assertEquals(0, comparison.previousReviews)
        assertNull(comparison.currentPassRate)
        assertNull(comparison.currentAvgSeconds)
        assertEquals(0, comparison.currentStudyDays)
        assertEquals(0, comparison.reviewsDelta)
        assertEquals(30, data.trend.size)
        assertTrue(data.trend.all { it.reviews == 0 })
    }

    @Test
    fun definitionsFollowThePreStateAndFirstReview() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = todayStart - 2 * day, stateBefore = CardStateKind.NEW),
            review("q1", rating = 3, reviewedAt = todayStart - day, stateBefore = CardStateKind.REVIEW),
            review("q2", rating = 3, reviewedAt = todayStart - 2 * day, stateBefore = CardStateKind.LEARNING),
            review("q3", rating = 3, reviewedAt = todayStart - 2 * day, stateBefore = CardStateKind.RELEARNING),
            review("q4", rating = 1, reviewedAt = todayStart - day, stateBefore = CardStateKind.REVIEW),
            review("q5", rating = 3, reviewedAt = todayStart - day, stateBefore = CardStateKind.REVIEW)
        )
        val data = progress(reviews)
        assertEquals(6, data.totals.reviews)
        // One introduction per card, not per review: q1's second review adds none.
        assertEquals(5, data.totals.introduced)
        // NEW, LEARNING and RELEARNING passes graduate; REVIEW passes do not.
        assertEquals(3, data.totals.graduated)
        // Only the Again on a REVIEW card is a relapse.
        assertEquals(1, data.totals.relapsed)
        assertEquals(1, data.totals.again)
        assertEquals(1.0 / 6.0, data.totals.againRate!!, 1e-9)
        assertEquals(1.0 / 6.0, data.totals.lapseRate!!, 1e-9)
    }

    @Test
    fun comparisonUsesTheEqualLengthPreviousWindow() {
        val reviews = listOf(
            // Current window (todayStart - 6d .. now).
            review("q1", rating = 3, reviewedAt = todayStart - day, elapsedSeconds = 10),
            review("q2", rating = 1, reviewedAt = todayStart - day, elapsedSeconds = 20),
            review("q3", rating = 3, reviewedAt = todayStart, elapsedSeconds = 30),
            // Previous window (todayStart - 13d .. todayStart - 7d).
            review("q4", rating = 3, reviewedAt = todayStart - 8 * day, elapsedSeconds = 5),
            review("q5", rating = 1, reviewedAt = todayStart - 10 * day, elapsedSeconds = 15)
        )
        val comparison = progress(reviews, StatsAggregator.Range.D7).comparison!!
        assertEquals(3, comparison.currentReviews)
        assertEquals(2, comparison.previousReviews)
        assertEquals(1, comparison.reviewsDelta)
        assertEquals(2.0 / 3.0, comparison.currentPassRate!!, 1e-9)
        assertEquals(0.5, comparison.previousPassRate!!, 1e-9)
        assertEquals(1.0 / 6.0, comparison.passRateDelta!!, 1e-9)
        assertEquals(20.0, comparison.currentAvgSeconds!!, 1e-9)
        assertEquals(10.0, comparison.previousAvgSeconds!!, 1e-9)
        assertEquals(10.0, comparison.avgSecondsDelta!!, 1e-9)
        assertEquals(2, comparison.currentStudyDays)
        assertEquals(2, comparison.previousStudyDays)
        assertEquals(0, comparison.studyDaysDelta)
    }

    @Test
    fun dayRangeTrendHasOneBucketPerDay() {
        val data = progress(
            listOf(review("q1", rating = 3, reviewedAt = todayStart)),
            StatsAggregator.Range.D7
        )
        assertEquals(7, data.trend.size)
        assertEquals(1, data.trend.last().reviews)
        assertEquals(1, data.trend.last().introduced)
        assertEquals(1, data.trend.last().graduated)
        assertTrue(data.trend.dropLast(1).all { it.reviews == 0 })
    }

    @Test
    fun longRangesSwitchToWeeklyAndMonthlyBuckets() {
        val weekly = progress(
            listOf(review("q1", rating = 3, reviewedAt = todayStart - 80 * day)),
            StatsAggregator.Range.D90
        )
        assertTrue("weekly buckets: ${weekly.trend.size}", weekly.trend.size in 12..14)

        val monthly = progress(
            listOf(review("q1", rating = 3, reviewedAt = todayStart - 300 * day)),
            StatsAggregator.Range.D365
        )
        assertTrue("monthly buckets: ${monthly.trend.size}", monthly.trend.size in 12..14)
    }

    @Test
    fun allRangeBucketsFromTheEarliestReview() {
        val data = progress(
            listOf(
                review("q1", rating = 3, reviewedAt = todayStart - 400 * day),
                review("q2", rating = 3, reviewedAt = todayStart)
            )
        )
        assertNull(data.comparison)
        assertTrue("all-range buckets: ${data.trend.size}", data.trend.size >= 13)
        assertEquals(2, data.trend.sumOf { it.reviews })
    }

    @Test
    fun scopeKeepsOnlyTheSelectedPaper() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = todayStart, paperId = "p1"),
            review("q2", rating = 1, reviewedAt = todayStart, paperId = "p2")
        )
        val scoped = progress(reviews, scopePaperId = "p1")
        assertEquals(1, scoped.totals.reviews)
        assertEquals(1, scoped.totals.introduced)
        assertEquals(0, scoped.totals.again)

        val everyPaper = progress(reviews)
        assertEquals(2, everyPaper.totals.reviews)
        assertEquals(1, everyPaper.totals.again)
    }
}
