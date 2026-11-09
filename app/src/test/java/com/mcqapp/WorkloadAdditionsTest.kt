package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The workload additions in plan §7: due windows, grade mix and sessions. */
class WorkloadAdditionsTest {

    private val day = DayBoundary.DAY_MS
    private val minute = 60_000L
    private val hour = 3_600_000L

    /** 15 Jun 2026, 12:00 UTC — the study day started at 04:00 that morning. */
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * hour
    private val todayStart = DayBoundary.startOfDay(now)
    private val tomorrowStart = todayStart + day

    private fun review(
        questionId: String = "q1",
        rating: Int = 3,
        reviewedAt: Long = todayStart + hour,
        elapsedSeconds: Long? = 10L,
        stateBefore: CardStateKind = CardStateKind.REVIEW
    ) = ReplayReview(
        paperId = "p1",
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 3,
        predictedRetention = null,
        intervalBeforeDays = 3
    )

    private fun card(
        questionId: String,
        dueAt: Long,
        state: CardStateKind = CardStateKind.REVIEW,
        intervalDays: Int = 5
    ) = CardSnapshot(
        paperId = "p1",
        state = CardState(
            questionId = questionId,
            stability = 5.0,
            difficulty = 5.0,
            intervalDays = intervalDays,
            dueAt = dueAt,
            state = state,
            lapses = 0,
            leech = false
        )
    )

    private fun workload(
        reviews: List<ReplayReview> = emptyList(),
        cards: List<CardSnapshot> = emptyList(),
        range: StatsAggregator.Range = StatsAggregator.Range.ALL
    ) = StatsAggregator.buildWorkload(
        StatsAggregator.Input(
            reviews = reviews,
            cards = cards,
            categoryOfQuestion = emptyMap(),
            paperTitleOf = mapOf("p1" to "Paper One"),
            scheduler = FsrsScheduler(config = SchedulerConfig()),
            config = SchedulerConfig(),
            now = now,
            zoneOffsetMillis = 0L
        ),
        scopePaperId = null,
        range = range
    )

    // --- due windows ---------------------------------------------------------

    @Test
    fun dueWindowsExcludeTheBacklogAndSplitOnStudyDays() {
        val bundle = workload(
            cards = listOf(
                // Backlog: before today's 04:00 boundary.
                card("backlog", todayStart - hour),
                // Due later today: this week, not tomorrow.
                card("today", todayStart + hour),
                // The first instant of tomorrow: both windows.
                card("tomorrow", tomorrowStart),
                // The last instant before the day after tomorrow: both.
                card("tomorrow-end", tomorrowStart + day - 1),
                // Day after tomorrow: this week only.
                card("in-two-days", tomorrowStart + day),
                // The last day of the week: still this week.
                card("day-six", todayStart + 6 * day),
                // Exactly seven days out: neither.
                card("day-seven", todayStart + 7 * day),
                // New cards are not scheduled and never count.
                card("new", todayStart, state = CardStateKind.NEW)
            )
        )
        val forecast = bundle.forecast
        assertEquals(2, forecast.dueTomorrow)
        assertEquals(5, forecast.dueThisWeek)
        assertEquals(1, forecast.overdue)
    }

    // --- grade mix -----------------------------------------------------------

    @Test
    fun gradeMixCountsEachRatingInItsBucket() {
        val bundle = workload(
            reviews = listOf(
                review("q1", rating = 1),
                review("q2", rating = 2),
                review("q3", rating = 3),
                review("q4", rating = 4),
                // A second Again on the same day joins the same bucket.
                review("q1", rating = 1)
            )
        )
        val point = bundle.ratingTrend.single { it.total > 0 }
        assertEquals(2, point.again)
        assertEquals(1, point.hard)
        assertEquals(1, point.good)
        assertEquals(1, point.easy)
        assertEquals(5, point.total)
        assertEquals(5, bundle.ratingTrend.sumOf { it.total })
    }

    @Test
    fun gradeMixIsEmptyWithoutReviews() {
        val bundle = workload()
        assertTrue(bundle.ratingTrend.all { it.total == 0 })
        assertEquals(0, bundle.sessions.count)
        assertNull(bundle.sessions.meanReviews)
        assertNull(bundle.sessions.meanMinutes)
        assertNull(bundle.sessions.longestMinutes)
        assertNull(bundle.sessions.meanSecondsPerReview)
    }

    // --- sessions ------------------------------------------------------------

    @Test
    fun aGapOfExactlyThirtyMinutesStaysInTheSameSitting() {
        val first = todayStart + hour
        val bundle = workload(
            reviews = listOf(
                review("q1", reviewedAt = first),
                // 30 minutes exactly is not "more than" the gap: one sitting.
                review("q2", reviewedAt = first + 30 * minute)
            )
        )
        assertEquals(1, bundle.sessions.count)
        assertEquals(2.0, bundle.sessions.meanReviews!!, 1e-9)
        assertEquals(30.0, bundle.sessions.meanMinutes!!, 1e-9)
    }

    @Test
    fun aLongerGapStartsANewSittingAndABackwardsClockDoesToo() {
        val first = todayStart + hour
        val bundle = workload(
            reviews = listOf(
                review("q1", reviewedAt = first),
                // 30 minutes plus a millisecond starts the second sitting.
                review("q2", reviewedAt = first + 30 * minute + 1),
                // A backwards clock also starts a new sitting.
                review("q3", reviewedAt = first + 10 * minute)
            )
        )
        assertEquals(3, bundle.sessions.count)
        assertEquals(1.0, bundle.sessions.meanReviews!!, 1e-9)
        // Every sitting is a single review, so each is zero minutes long.
        assertEquals(0.0, bundle.sessions.longestMinutes!!, 1e-9)
    }

    @Test
    fun sessionSummaryReportsLengthAndPace() {
        val first = todayStart + hour
        val bundle = workload(
            reviews = listOf(
                review("q1", reviewedAt = first, elapsedSeconds = 10L),
                review("q2", reviewedAt = first + 10 * minute, elapsedSeconds = 20L),
                review("q3", reviewedAt = first + 20 * minute, elapsedSeconds = null),
                // A second, one-review sitting with an unmeasured review.
                review("q4", reviewedAt = first + hour, elapsedSeconds = null)
            )
        )
        val sessions = bundle.sessions
        assertEquals(2, sessions.count)
        assertEquals(2.0, sessions.meanReviews!!, 1e-9)
        // The first sitting is 20 minutes long, the second a single review: 10.
        assertEquals(10.0, sessions.meanMinutes!!, 1e-9)
        assertEquals(20.0, sessions.longestMinutes!!, 1e-9)
        // Only the two timed reviews contribute: (10 + 20) / 2.
        assertEquals(15.0, sessions.meanSecondsPerReview!!, 1e-9)
    }

    @Test
    fun theHistogramBucketsSittingsByReviewCount() {
        val base = todayStart + hour
        // Build sittings of 1, 2, 5, 10, 20 and 50 reviews, one minute apart,
        // separated by a gap that always starts a new sitting.
        val counts = listOf(1, 2, 5, 10, 20, 50)
        val reviews = mutableListOf<ReplayReview>()
        var cursor = base
        counts.forEach { size ->
            repeat(size) { i ->
                reviews += review("q$i", reviewedAt = cursor + i * minute)
            }
            cursor += (size + 1) * minute + 31 * minute
        }
        val histogram = workload(reviews).sessions.histogram
        assertEquals(listOf("1", "2–4", "5–9", "10–19", "20–49", "50+"), histogram.map { it.label })
        assertEquals(listOf(1, 1, 1, 1, 1, 1), histogram.map { it.count })
    }
}
