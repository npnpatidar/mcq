package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.SessionsData
import com.mcqapp.domain.stats.StatsAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Sessions tab and the sidebar history. Sittings follow the Workload
 * summary's rule exactly — a gap of more than 30 minutes, or a backwards
 * clock, starts a new one — and this pins the new per-sitting facts: duration,
 * distinct questions, grade mix, topics, and the start-time / weekday / length
 * distributions the statistics tab charts.
 */
class SessionsTest {

    private val day = DayBoundary.DAY_MS
    private val minute = 60_000L
    private val hour = 3_600_000L

    /** 15 Jun 2026, 12:00 UTC — the study day started at 04:00 that morning. */
    private val now = LocalDate.of(2026, 6, 15).toEpochDay() * day + 12 * hour
    private val todayStart = DayBoundary.startOfDay(now)
    private val todayDay = LocalDate.ofEpochDay(todayStart / day).toEpochDay()

    private fun review(
        questionId: String = "q1",
        paperId: String = "p1",
        rating: Int = 3,
        reviewedAt: Long = todayStart + hour,
        elapsedSeconds: Long? = 10L
    ) = ReplayReview(
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = CardStateKind.REVIEW,
        stabilityAfter = 5.0,
        difficultyAfter = 5.0,
        intervalDaysAfter = 3,
        predictedRetention = null,
        intervalBeforeDays = 3
    )

    private fun sessions(
        reviews: List<ReplayReview>,
        range: StatsAggregator.Range = StatsAggregator.Range.ALL,
        scopePaperId: String? = null,
        collectEntries: Boolean = true,
        categoryOfQuestion: Map<String, String> = emptyMap(),
        paperTitleOf: Map<String, String> = mapOf("p1" to "Paper One", "p2" to "Paper Two")
    ): SessionsData = StatsAggregator.buildSessions(
        StatsAggregator.Input(
            reviews = reviews,
            cards = emptyList(),
            categoryOfQuestion = categoryOfQuestion,
            paperTitleOf = paperTitleOf,
            scheduler = FsrsScheduler(config = SchedulerConfig()),
            config = SchedulerConfig(),
            now = now,
            zoneOffsetMillis = 0L
        ),
        scopePaperId = scopePaperId,
        range = range,
        collectEntries = collectEntries
    )

    // --- grouping ------------------------------------------------------------

    @Test
    fun exactlyThirtyMinutesStaysInOneSittingAndThirtyPlusOneSplits() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = first),
                // Exactly 30 minutes is not "more than" the gap: one sitting.
                review("q2", reviewedAt = first + 30 * minute),
                // One millisecond later starts the second sitting.
                review("q3", reviewedAt = first + 30 * minute + 1 + 30 * minute + 1)
            )
        )
        assertEquals(2, data.totalSessions)
        assertEquals(3, data.totalReviews)
        // The history lists the newest sitting first.
        assertEquals(listOf(1, 2), data.sessions.map { it.reviews })
    }

    @Test
    fun aBackwardsClockAlsoStartsANewSitting() {
        val first = todayStart + 3 * hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = first),
                review("q2", reviewedAt = first - minute)
            )
        )
        assertEquals(2, data.totalSessions)
        // Neither sitting can have a negative duration.
        assertTrue(data.sessions.all { it.durationSeconds >= 0 })
    }

    @Test
    fun aSittingRunsFromItsFirstReviewToItsLast() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = first, elapsedSeconds = 5L),
                review("q2", reviewedAt = first + 20 * minute, elapsedSeconds = 15L)
            )
        )
        val sitting = data.sessions.single()
        assertEquals(first, sitting.startedAt)
        assertEquals(first + 20 * minute, sitting.endedAt)
        assertEquals(20 * 60L, sitting.durationSeconds)
        assertEquals(20L, sitting.timedSeconds)
        assertEquals(2, sitting.timedReviews)
        assertEquals(10.0, sitting.meanSecondsPerReview!!, 1e-9)
    }

    @Test
    fun questionsCountDistinctCardsWhileReviewsCountEveryAnswer() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                // The same card answered twice inside one sitting.
                review("q1", reviewedAt = first, rating = 1),
                review("q1", reviewedAt = first + 10 * minute, rating = 3),
                review("q2", reviewedAt = first + 15 * minute, rating = 3)
            )
        )
        val sitting = data.sessions.single()
        assertEquals(3, sitting.reviews)
        assertEquals(2, sitting.questions)
    }

    // --- grades and accuracy -------------------------------------------------

    @Test
    fun theGradeMixAndAccuracyFollowThePassRule() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = first, rating = 1),
                review("q2", reviewedAt = first + minute, rating = 2),
                review("q3", reviewedAt = first + 2 * minute, rating = 3),
                review("q4", reviewedAt = first + 3 * minute, rating = 4)
            )
        )
        val sitting = data.sessions.single()
        assertEquals(1, sitting.again)
        assertEquals(1, sitting.hard)
        assertEquals(1, sitting.good)
        assertEquals(1, sitting.easy)
        // Again is the only failing grade.
        assertEquals(3, sitting.passed)
        assertEquals(0.75, sitting.accuracy!!, 1e-9)
    }

    @Test
    fun unmeasuredReviewsDoNotDiluteTheCardTimeMean() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = first, elapsedSeconds = null),
                review("q2", reviewedAt = first + minute, elapsedSeconds = 30L)
            )
        )
        val sitting = data.sessions.single()
        assertEquals(30L, sitting.timedSeconds)
        assertEquals(1, sitting.timedReviews)
        assertEquals(30.0, sitting.meanSecondsPerReview!!, 1e-9)
    }

    // --- scope and range -----------------------------------------------------

    @Test
    fun theScopeDropsOtherPapersBeforeGrouping() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", paperId = "p1", reviewedAt = first),
                review("q2", paperId = "p2", reviewedAt = first + minute)
            ),
            scopePaperId = "p1"
        )
        assertEquals(1, data.totalReviews)
        assertEquals("Paper One", data.sessions.single().papers.single().label)
    }

    @Test
    fun theRangeDropsReviewsBeforeItsWindow() {
        val first = todayStart + hour
        val reviews = listOf(
            review("q1", reviewedAt = first - 10 * day),
            review("q2", reviewedAt = first)
        )
        assertEquals(2, sessions(reviews, StatsAggregator.Range.ALL).totalReviews)
        assertEquals(1, sessions(reviews, StatsAggregator.Range.D7).totalReviews)
    }

    // --- papers and topics ---------------------------------------------------

    @Test
    fun papersAndTopicsAreRankedByTheirReviewCounts() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", paperId = "p1", reviewedAt = first),
                review("q1", paperId = "p1", reviewedAt = first + minute),
                review("q2", paperId = "p2", reviewedAt = first + 2 * minute)
            ),
            categoryOfQuestion = mapOf("q1" to "Algebra", "q2" to "Geometry")
        )
        val sitting = data.sessions.single()
        assertEquals(listOf("Paper One", "Paper Two"), sitting.papers.map { it.label })
        assertEquals(listOf(2, 1), sitting.papers.map { it.reviews })
        assertEquals(listOf("Algebra", "Geometry"), sitting.categories.map { it.label })
        assertEquals(listOf(2, 1), sitting.categories.map { it.reviews })
    }

    @Test
    fun aTopicLookupThatFindsNothingLeavesTheTopicListEmpty() {
        val data = sessions(
            listOf(review("q1", reviewedAt = todayStart + hour)),
            categoryOfQuestion = mapOf("someone-else" to "Algebra")
        )
        assertTrue(data.sessions.single().categories.isEmpty())
    }

    @Test
    fun anUnknownPaperFallsBackToItsId() {
        val data = sessions(
            listOf(review("q1", paperId = "p9", reviewedAt = todayStart + hour))
        )
        assertEquals("p9", data.sessions.single().papers.single().label)
    }

    // --- chart distributions -------------------------------------------------

    @Test
    fun sessionsPerDayCountsByTheStudyDayTheSittingStarted() {
        val yesterdayNoon = todayStart - 12 * hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = yesterdayNoon),
                review("q2", reviewedAt = todayStart + hour)
            )
        )
        assertEquals(2, data.totalSessions)
        assertEquals(2, data.sessionsPerDay.sumOf { it.total })
        assertTrue(data.sessionsPerDay.all { it.total <= 1 })
    }

    @Test
    fun theStartHourIsTheWallClockHourOfTheFirstReview() {
        // 04:00 is the study day's boundary; +1h is 05:00 local.
        val data = sessions(listOf(review("q1", reviewedAt = todayStart + hour)))
        assertEquals(24, data.startHours.size)
        assertEquals(1, data.startHours.single { it.hour == 5 }.sessions)
        assertEquals(1, data.startHours.sumOf { it.sessions })
    }

    @Test
    fun aLateNightSittingKeepsItsOwnHourEvenIfItBelongsToTheSameStudyDay() {
        // 02:00 belongs to the previous 04:00 day but the hour is still 2.
        val data = sessions(listOf(review("q1", reviewedAt = todayStart + 22 * hour)))
        assertEquals(1, data.startHours.single { it.hour == 2 }.sessions)
    }

    @Test
    fun sessionsByWeekdayFollowTheStartDaysWeekday() {
        val data = sessions(listOf(review("q1", reviewedAt = todayStart + hour)))
        assertEquals(7, data.weekdays.size)
        assertEquals(1, data.weekdays.sumOf { it.sessions })
        val expected = LocalDate.ofEpochDay(todayDay).dayOfWeek.value - 1
        assertEquals(1, data.weekdays.single { it.weekday == expected }.sessions)
    }

    @Test
    fun theLengthHistogramUsesTheDocumentedBins() {
        val base = todayStart + hour
        val data = sessions(
            listOf(
                // 0–4m: two reviews two minutes apart.
                review("q1", reviewedAt = base),
                review("q2", reviewedAt = base + 2 * minute),
                // 5–14m: starts 38 minutes after the first sitting ended.
                review("q3", reviewedAt = base + 40 * minute),
                review("q4", reviewedAt = base + 50 * minute),
                // 15–29m.
                review("q5", reviewedAt = base + 100 * minute),
                review("q6", reviewedAt = base + 120 * minute)
            ) +
                // 2h+: a long sitting is a chain of 30-minute gaps, because a
                // single longer gap would start a new one.
                (0..6).map { step ->
                    review(
                        "long$step",
                        reviewedAt = base + (200 + step * 30) * minute
                    )
                }
        )
        val counts = data.durationHistogram.map { it.count }
        assertEquals(4, counts.sum())
        assertEquals(1, counts[0])
        assertEquals(1, counts[1])
        assertEquals(1, counts[2])
        assertEquals(1, counts[5])
        assertEquals(listOf("0–4m", "5–14m", "15–29m", "30–59m", "1–2 h", "2 h+"),
            data.durationHistogram.map { it.label })
    }

    @Test
    fun theAccuracyTrendWeightsEverySittingEqually() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                // Sitting one: perfect.
                review("q1", reviewedAt = first, rating = 3),
                review("q2", reviewedAt = first + minute, rating = 3),
                // Sitting two, after a gap: nothing recalled.
                review("q3", reviewedAt = first + 40 * minute, rating = 1),
                review("q4", reviewedAt = first + 41 * minute, rating = 1)
            )
        )
        assertEquals(2, data.totalSessions)
        // Both sittings land on the same day, so (1.0 + 0.0) / 2 = 0.5, not
        // the 2/4 the review-weighted rate would give.
        val point = data.accuracyTrend.filter { it.count > 0 }.single()
        assertEquals(0.5, point.value!!, 1e-9)
        assertEquals(2, point.count)
    }

    // --- history list --------------------------------------------------------

    @Test
    fun theHistoryIsNewestFirst() {
        val first = todayStart + hour
        val data = sessions(
            listOf(
                review("q1", reviewedAt = first),
                review("q2", reviewedAt = first + 2 * hour)
            )
        )
        assertEquals(2, data.sessions.size)
        assertTrue(data.sessions[0].startedAt > data.sessions[1].startedAt)
    }

    @Test
    fun theChartBuildPaysNothingForEntriesItWillNotShow() {
        val data = sessions(
            listOf(review("q1", reviewedAt = todayStart + hour)),
            collectEntries = false
        )
        assertEquals(1, data.totalSessions)
        assertEquals(1, data.totalReviews)
        assertTrue(data.sessions.isEmpty())
    }

    @Test
    fun theWholePageBuildCarriesTheSittings() {
        val bundle = StatsAggregator.build(
            StatsAggregator.Input(
                reviews = listOf(review("q1", reviewedAt = todayStart + hour)),
                cards = emptyList(),
                categoryOfQuestion = emptyMap(),
                paperTitleOf = mapOf("p1" to "Paper One"),
                scheduler = FsrsScheduler(config = SchedulerConfig()),
                config = SchedulerConfig(),
                now = now,
                zoneOffsetMillis = 0L
            ),
            scopePaperId = null,
            range = StatsAggregator.Range.ALL
        )
        assertEquals(1, bundle.sessions.totalSessions)
        assertEquals(1, bundle.sessions.sessions.size)
    }

    @Test
    fun anEmptyLogBuildsEmptyCharts() {
        val data = sessions(emptyList())
        assertEquals(0, data.totalSessions)
        assertEquals(0, data.totalReviews)
        assertEquals(0L, data.totalSeconds)
        assertTrue(data.sessionsPerDay.isEmpty())
        assertTrue(data.accuracyTrend.isEmpty())
        assertEquals(24, data.startHours.size)
        assertTrue(data.startHours.all { it.sessions == 0 })
        assertEquals(7, data.weekdays.size)
        assertEquals(6, data.durationHistogram.size)
        assertTrue(data.durationHistogram.all { it.count == 0 })
        assertEquals(todayDay, data.todayDay)
        assertTrue(data.sessions.isEmpty())
        assertNull(data.sessions.firstOrNull()?.accuracy)
    }
}
