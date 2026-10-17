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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsAggregatorTest {

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
        elapsedSeconds: Long? = 10L,
        stateBefore: CardStateKind? = CardStateKind.NEW,
        stabilityAfter: Double? = 5.0,
        intervalBeforeDays: Int? = null,
        intervalDaysAfter: Int? = null,
        predictedRetention: Double? = null
    ) = ReplayReview(
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = 5.0,
        intervalDaysAfter = intervalDaysAfter,
        predictedRetention = predictedRetention,
        intervalBeforeDays = intervalBeforeDays
    )

    private fun card(
        questionId: String,
        paperId: String = "p1",
        state: CardStateKind = CardStateKind.NEW,
        stability: Double = 0.0,
        difficulty: Double = 0.0,
        intervalDays: Int = 0,
        dueAt: Long = 0L,
        lapses: Int = 0,
        leech: Boolean = false
    ) = CardSnapshot(
        paperId = paperId,
        state = CardState(
            questionId = questionId,
            stability = stability,
            difficulty = difficulty,
            intervalDays = intervalDays,
            dueAt = dueAt,
            state = state,
            lapses = lapses,
            leech = leech
        )
    )

    private fun input(
        reviews: List<ReplayReview> = emptyList(),
        cards: List<CardSnapshot> = emptyList(),
        at: Long = now,
        config: SchedulerConfig = SchedulerConfig(),
        categoryOfQuestion: Map<String, String> = emptyMap()
    ) = StatsAggregator.Input(
        reviews = reviews,
        cards = cards,
        categoryOfQuestion = categoryOfQuestion,
        paperTitleOf = mapOf("p1" to "Paper One", "p2" to "Paper Two"),
        scheduler = FsrsScheduler(config = config),
        config = config,
        now = at,
        zoneOffsetMillis = 0L
    )

    // --- empty input ---------------------------------------------------------

    @Test
    fun emptyInputBuildsAnAllZeroBundleWithoutFailing() {
        val bundle = StatsAggregator.build(input(), scopePaperId = null, range = StatsAggregator.Range.ALL)
        assertEquals(0, bundle.today.reviews)
        assertNull(bundle.today.correctPct)
        assertNull(bundle.kpis.retention)
        // The table's rows always exist; with no history they are all zeros.
        assertEquals(7, bundle.retentionTable.size)
        assertEquals(0, bundle.retentionTable.first().allReviews)
        assertEquals(0, bundle.calibration.samples)
        assertNull(bundle.calibration.brier)
        assertEquals(365, bundle.heatmap.days.size)
        assertEquals(0, bundle.heatmap.currentStreak)
        assertEquals(30, bundle.forecast.days.size)
        assertEquals(0.0, bundle.forecast.dailyLoad, 1e-9)
        assertTrue(bundle.reviewsPerDay.isEmpty())
        assertTrue(bundle.weakest.isEmpty())
        assertEquals(0, bundle.cardCounts.total)
    }

    // --- today strip and the 04:00 boundary -----------------------------------

    @Test
    fun todayCountsOnlyTheCurrentStudyDay() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, stateBefore = CardStateKind.NEW),
            review("q2", rating = 1, reviewedAt = todayStart - 2 * hour),
            review("q3", rating = 3, reviewedAt = todayStart + 30 * 60_000L)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        // 02:00 sits before the 04:00 boundary, so it is yesterday's study day.
        assertEquals(2, bundle.today.reviews)
        assertEquals(0, bundle.today.againCount)
        assertEquals(1.0, bundle.today.correctPct!!, 1e-9)
        assertEquals(2, bundle.today.introduced)
    }

    @Test
    fun againReviewsLowerTodaysCorrectPercentage() {
        val reviews = listOf(
            review("q1", rating = 1, reviewedAt = now - hour),
            review("q2", rating = 3, reviewedAt = now - 30 * 60_000L)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        assertEquals(1, bundle.today.againCount)
        assertEquals(0.5, bundle.today.correctPct!!, 1e-9)
    }

    @Test
    fun dueNowCountsScheduledCardsThroughTomorrowMorningButBacklogDoesNot() {
        val cards = listOf(
            card("q1", state = CardStateKind.REVIEW, dueAt = now + hour),
            card("q2", state = CardStateKind.REVIEW, dueAt = todayStart - day),
            card("q3", state = CardStateKind.LEARNING, dueAt = now - 1000),
            card("q4", state = CardStateKind.NEW, dueAt = 0L)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        assertEquals(2, bundle.today.dueNow)
        assertEquals(2, bundle.today.backlog)
    }

    // --- true retention --------------------------------------------------------

    @Test
    fun firstExposureIsExcludedFromTrueRetention() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, stateBefore = CardStateKind.NEW,
                intervalBeforeDays = null),
            review("q2", rating = 1, reviewedAt = now - hour, stateBefore = CardStateKind.REVIEW,
                intervalBeforeDays = 30)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        val today = bundle.retentionTable.first { it.label == "today" }
        assertEquals(1, today.allReviews)
        assertEquals(0, today.allPasses)
        assertEquals(0.0, bundle.kpis.retention!!, 1e-9)
    }

    @Test
    fun sameDayRelearnStepIsExcludedFromTrueRetention() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, stateBefore = CardStateKind.RELEARNING,
                intervalBeforeDays = 0),
            review("q2", rating = 3, reviewedAt = now - hour, stateBefore = CardStateKind.REVIEW,
                intervalBeforeDays = 30)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        val today = bundle.retentionTable.first { it.label == "today" }
        assertEquals(1, today.allReviews)
    }

    @Test
    fun trueRetentionCountsOnlyTheFirstReviewPerCardPerStudyDay() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - 2 * hour, intervalBeforeDays = 30),
            review("q1", rating = 1, reviewedAt = now - hour, intervalBeforeDays = 30),
            review("q2", rating = 1, reviewedAt = now - 2 * hour, intervalBeforeDays = 5)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        val today = bundle.retentionTable.first { it.label == "today" }
        assertEquals(2, today.allReviews)
        assertEquals(1, today.allPasses)
        // q1 carried a 30-day interval: mature. q2 carried 5 days: young.
        assertEquals(1, today.matureReviews)
        assertEquals(1, today.maturePasses)
        assertEquals(1, today.youngReviews)
        assertEquals(0, today.youngPasses)
    }

    @Test
    fun retentionTableWindowsAreSeparateRows() {
        val yesterday = todayStart - day + 2 * hour
        val weekAgo = todayStart - 3 * day + 2 * hour
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, intervalBeforeDays = 30),
            review("q1", rating = 3, reviewedAt = yesterday, intervalBeforeDays = 30),
            review("q2", rating = 1, reviewedAt = weekAgo, intervalBeforeDays = 5)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        val labels = bundle.retentionTable.map { it.label }
        assertEquals(listOf("today", "yesterday", "last7", "last30", "last90", "last365", "all"), labels)
        assertEquals(1, bundle.retentionTable[0].allReviews)
        assertEquals(1, bundle.retentionTable[1].allReviews)
        assertEquals(3, bundle.retentionTable[2].allReviews)
        assertEquals(3, bundle.retentionTable[6].allReviews)
    }

    // --- retention trend and weekday split -------------------------------------

    /**
     * Two of q1's reviews fall on today; only the day's first qualifies, so the
     * trend must show one review for today, not two. Rows are in per-card
     * chronological order, the order the log is streamed in.
     */
    private fun trendReviews() = listOf(
        review("q1", rating = 1, reviewedAt = todayStart - day + 2 * hour, intervalBeforeDays = 30),
        review("q1", rating = 3, reviewedAt = now - 3 * hour, intervalBeforeDays = 30),
        review("q1", rating = 1, reviewedAt = now - hour, intervalBeforeDays = 30),
        review("q2", rating = 3, reviewedAt = now - 2 * hour, intervalBeforeDays = 5)
    )

    @Test
    fun retentionTrendCountsOnePointPerStudyDayFromFirstQualifyingReviews() {
        val bundle = StatsAggregator.build(input(trendReviews()), null, StatsAggregator.Range.ALL)
        val points = bundle.retentionTrend.points
        // Oldest first, one bucket per study day, today included.
        assertEquals(listOf("14 Jun", "15 Jun"), points.map { it.label })
        assertEquals(1, points[0].count)
        assertEquals(0.0, points[0].value!!, 1e-9)
        assertEquals(2, points[1].count)
        assertEquals(1.0, points[1].value!!, 1e-9)
        // Rolling pools the buckets' counts rather than averaging percentages.
        assertEquals(3, bundle.retentionTrend.rolling[1].count)
        assertEquals(2.0 / 3.0, bundle.retentionTrend.rolling[1].value!!, 1e-9)
    }

    @Test
    fun weekdayRetentionUsesTheSameFirstOfDayRule() {
        val bundle = StatsAggregator.build(input(trendReviews()), null, StatsAggregator.Range.ALL)
        val byWeekday = bundle.weekdayRetention.associateBy { it.weekday }
        // 15 Jun 2026 is a Monday and 14 Jun a Sunday.
        assertEquals(2, byWeekday.getValue(0).reviews)
        assertEquals(2, byWeekday.getValue(0).passes)
        assertEquals(1, byWeekday.getValue(6).reviews)
        assertEquals(0, byWeekday.getValue(6).passes)
        assertEquals(0, byWeekday.getValue(1).reviews)
    }

    @Test
    fun rangeSlicesTheRetentionTrend() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = todayStart - 60 * day + 2 * hour, intervalBeforeDays = 30),
            review("q1", rating = 3, reviewedAt = now - hour, intervalBeforeDays = 30)
        )
        val all = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        val week = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        // Seven daily buckets ending today; the sixty-day-old review is outside
        // the range even though the table's own windows would keep it.
        assertEquals(7, week.retentionTrend.points.size)
        assertEquals(1, week.retentionTrend.points.sumOf { it.count })
        assertEquals(2, all.retentionTrend.points.sumOf { it.count })
    }

    // --- scope and range ---------------------------------------------------------

    @Test
    fun paperScopeFiltersEverything() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, paperId = "p1"),
            review("q2", rating = 3, reviewedAt = now - hour, paperId = "p2")
        )
        val bundle = StatsAggregator.build(input(reviews), "p1", StatsAggregator.Range.D7)
        assertEquals(1, bundle.today.reviews)
        assertEquals(1, bundle.kpis.reviews)
    }

    @Test
    fun rangeSlicesHistoryGraphsButNotTodayOrTable() {
        val old = todayStart - 60 * day
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, intervalBeforeDays = 30),
            review("q1", rating = 3, reviewedAt = old, intervalBeforeDays = 30)
        )
        val week = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        assertEquals(1, week.kpis.reviews)
        val all = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        assertEquals(2, all.kpis.reviews)
        // The retention table's own windows are unaffected by the selector.
        assertEquals(2, all.retentionTable.last().allReviews)
        assertEquals(2, week.retentionTable.last().allReviews)
    }

    @Test
    fun scopeDoesNotShrinkTheFsrsPanelsReviewCount() {
        // The FSRS panel reports how much history the library has to fit on, so
        // it counts the whole log even while the sections are scoped to one
        // paper. Pinned because the streamed/loaded parity test caught the two
        // builds disagreeing here: the streamed build pages the whole log, so
        // the loaded build must be handed the whole log too.
        val old = todayStart - 60 * day
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, intervalBeforeDays = 30),
            review("q1", rating = 3, reviewedAt = old, intervalBeforeDays = 30),
            review("q2", rating = 3, reviewedAt = old, paperId = "p2", intervalBeforeDays = 30)
        )
        val scoped = StatsAggregator.build(input(reviews), "p1", StatsAggregator.Range.ALL)
        assertEquals(3, scoped.fsrsStatus.reviewCount)
        assertEquals(2, scoped.kpis.reviews)
    }

    // --- kpis and streaks --------------------------------------------------------

    @Test
    fun streaksCountConsecutiveStudyDaysAndTodayDoesNotBreakThem() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = todayStart - 2 * hour),
            review("q1", rating = 3, reviewedAt = todayStart - day - 2 * hour),
            review("q1", rating = 3, reviewedAt = todayStart - 3 * day - 2 * hour)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        // Yesterday and the day before are consecutive; today has no reviews yet.
        assertEquals(2, bundle.heatmap.currentStreak)
        assertEquals(2, bundle.heatmap.longestStreak)
    }

    @Test
    fun todayWithReviewsExtendsTheStreak() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour),
            review("q1", rating = 3, reviewedAt = todayStart - 2 * hour)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        assertEquals(2, bundle.heatmap.currentStreak)
    }

    @Test
    fun kpisCarryTimeKnowledgeAndIntervalAverages() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, elapsedSeconds = 20),
            review("q1", rating = 3, reviewedAt = todayStart - 2 * hour, elapsedSeconds = 40)
        )
        val cards = listOf(
            card("q1", state = CardStateKind.REVIEW, stability = 30.0, difficulty = 5.0, intervalDays = 30),
            card("q2", state = CardStateKind.REVIEW, stability = 10.0, difficulty = 5.0, intervalDays = 10),
            card("q3", state = CardStateKind.NEW)
        )
        val bundle = StatsAggregator.build(input(reviews, cards), null, StatsAggregator.Range.D30)
        assertEquals(60L, bundle.kpis.timeSeconds)
        assertEquals(30.0, bundle.kpis.avgAnswerSeconds!!, 1e-9)
        assertEquals(2, bundle.kpis.days)
        // Estimated knowledge: the two reviewed cards' current retrievability.
        val expected = bundle.kpis.estimatedKnowledge
        assertTrue(expected in 1..2)
        assertEquals(20.0, bundle.kpis.avgIntervalDays!!, 1e-9)
    }

    // --- calibration -----------------------------------------------------------

    @Test
    fun calibrationBucketsPredictionsAndComputesBrier() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, predictedRetention = 0.91),
            review("q2", rating = 3, reviewedAt = now - hour, predictedRetention = 0.93),
            review("q3", rating = 3, reviewedAt = now - hour, predictedRetention = 0.95),
            review("q4", rating = 1, reviewedAt = now - hour, predictedRetention = 0.32)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        assertEquals(4, bundle.calibration.samples)
        assertEquals(0.029475, bundle.calibration.brier!!, 1e-9)
        val high = bundle.calibration.bins.last()
        assertEquals(1.0, high.actualRate!!, 1e-9)
        assertEquals(3, high.count)
        val low = bundle.calibration.bins.first { it.predictedFrom == 0.3 }
        assertEquals(0.0, low.actualRate!!, 1e-9)
        assertEquals(0.7775, bundle.calibration.predictedMean!!, 1e-9)
        assertEquals(0.75, bundle.calibration.actualMean!!, 1e-9)
    }

    @Test
    fun calibrationIgnoresReviewsWithoutAPrediction() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, predictedRetention = null),
            review("q2", rating = 3, reviewedAt = now - hour, predictedRetention = 0.9)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        assertEquals(1, bundle.calibration.samples)
    }

    // --- forecast -----------------------------------------------------------------

    @Test
    fun forecastBucketsDueCardsByDayAndCountsTheBacklog() {
        val cards = listOf(
            card("q1", state = CardStateKind.REVIEW, dueAt = now + hour, intervalDays = 10),
            card("q2", state = CardStateKind.REVIEW, dueAt = todayStart + 5 * day, intervalDays = 20),
            card("q3", state = CardStateKind.LEARNING, dueAt = todayStart + 2 * day),
            card("q4", state = CardStateKind.REVIEW, dueAt = todayStart - 3 * day, intervalDays = 30),
            card("q5", state = CardStateKind.NEW)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        assertEquals(1, bundle.forecast.overdue)
        assertEquals(1, bundle.forecast.dueToday)
        assertEquals(1, bundle.forecast.days[5].review)
        assertEquals(1, bundle.forecast.days[2].learning)
        assertEquals(0, bundle.forecast.days[2].review)
        // Daily load: 1/10 + 1/20 + 1 (learning) + 1/30.
        assertEquals(1.0 / 10 + 1.0 / 20 + 1.0 + 1.0 / 30, bundle.forecast.dailyLoad, 1e-9)
        val cumulative = bundle.forecast.cumulativeIfSkipped
        assertEquals(2, cumulative[0])
        assertEquals(4, cumulative[5])
    }

    // --- card counts and distributions --------------------------------------------

    @Test
    fun cardCountsSplitByStateAndMaturity() {
        val cards = listOf(
            card("q1", state = CardStateKind.NEW),
            card("q2", state = CardStateKind.LEARNING, dueAt = now),
            card("q3", state = CardStateKind.RELEARNING),
            card("q4", state = CardStateKind.REVIEW, intervalDays = 10),
            card("q5", state = CardStateKind.REVIEW, intervalDays = 30),
            card("q6", state = CardStateKind.REVIEW, intervalDays = 25, leech = true)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        val counts = bundle.cardCounts
        assertEquals(1, counts.fresh)
        assertEquals(1, counts.learning)
        assertEquals(1, counts.relearning)
        assertEquals(1, counts.young)
        assertEquals(2, counts.mature)
        assertEquals(1, counts.leeches)
        assertEquals(6, counts.total)
    }

    @Test
    fun overdueAgeBucketsPastDueScheduledCardsAndSkipsNew() {
        val cards = listOf(
            card("q1", state = CardStateKind.REVIEW, intervalDays = 10, dueAt = now - 6 * hour),
            card("q2", state = CardStateKind.REVIEW, intervalDays = 10, dueAt = now - 8 * day),
            card("q4", state = CardStateKind.REVIEW, intervalDays = 30, dueAt = now - 20 * day),
            card("q3", state = CardStateKind.NEW, dueAt = now - 30 * day)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        val overdue = bundle.overdueAge
        assertEquals(3, overdue.overdue)
        assertEquals(listOf("<1d", "1–2d", "2–4d", "4–7d", "1–2w", "2w+"), overdue.bins.map { it.label })
        assertEquals(1, overdue.bins.first { it.label == "<1d" }.count)
        assertEquals(1, overdue.bins.first { it.label == "1–2w" }.count)
        assertEquals(1, overdue.bins.first { it.label == "2w+" }.count)
        assertEquals(20, overdue.oldestAgeDays)
    }

    @Test
    fun paperCountsComparePapersButHideWhenScoped() {
        val cards = listOf(
            card("q1", paperId = "p1", state = CardStateKind.REVIEW, intervalDays = 10),
            card("q2", paperId = "p1", state = CardStateKind.NEW),
            card("q3", paperId = "p2", state = CardStateKind.REVIEW, intervalDays = 30),
            card("q4", paperId = "p2", state = CardStateKind.REVIEW, intervalDays = 5),
            card("q5", paperId = "p2", state = CardStateKind.LEARNING, dueAt = now)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        val papers = bundle.paperCounts
        assertEquals(listOf("p2", "p1"), papers.map { it.paperId })
        assertEquals("Paper Two", papers[0].label)
        assertEquals(3, papers[0].total)
        assertEquals(1, papers[0].mature)
        assertEquals(1, papers[0].learning)
        assertEquals(2, papers[1].total)
        assertEquals(1, papers[1].fresh)

        val scoped = StatsAggregator.build(input(cards = cards), "p1", StatsAggregator.Range.ALL)
        assertTrue(scoped.paperCounts.isEmpty())
    }

    @Test
    fun stabilityDistributionBinsLandInMatchingBuckets() {
        val cards = listOf(
            card("q1", state = CardStateKind.REVIEW, stability = 0.5, intervalDays = 1),
            card("q2", state = CardStateKind.REVIEW, stability = 5.0, intervalDays = 5),
            card("q3", state = CardStateKind.REVIEW, stability = 100.0, intervalDays = 100),
            card("q4", state = CardStateKind.REVIEW, stability = 400.0, intervalDays = 400),
            card("q5", state = CardStateKind.NEW)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        val bins = bundle.stability.bins
        assertEquals(10, bins.size)
        assertEquals(1, bins.first { it.label == "<1d" }.count)
        assertEquals(1, bins.first { it.label == "3–7d" }.count)
        assertEquals(1, bins.first { it.label == "90–180d" }.count)
        assertEquals(1, bins.first { it.label == ">1y" }.count)
        assertEquals(4, bundle.stability.bins.sumOf { it.count })
        assertEquals(126.375, bundle.stability.mean!!, 0.01)
    }

    @Test
    fun difficultyDistributionUsesIntegerBuckets() {
        val cards = listOf(
            card("q1", state = CardStateKind.REVIEW, difficulty = 5.4, stability = 1.0),
            card("q2", state = CardStateKind.REVIEW, difficulty = 5.9, stability = 1.0),
            card("q3", state = CardStateKind.REVIEW, difficulty = 9.0, stability = 1.0)
        )
        val bundle = StatsAggregator.build(input(cards = cards), null, StatsAggregator.Range.ALL)
        assertEquals(2, bundle.difficulty.bins.first { it.label == "5" }.count)
        assertEquals(1, bundle.difficulty.bins.first { it.label == "9" }.count)
    }

    // --- weakest cards ---------------------------------------------------------

    @Test
    fun weakestRanksByAgainRateAndSkipsThinHistories() {
        val reviews = listOf(
            review("bad", rating = 1, reviewedAt = now - 3 * hour),
            review("bad", rating = 1, reviewedAt = now - 2 * hour),
            review("bad", rating = 3, reviewedAt = now - hour),
            review("thin", rating = 1, reviewedAt = now - 2 * hour),
            review("thin", rating = 1, reviewedAt = now - hour),
            review("ok", rating = 3, reviewedAt = now - hour),
            review("ok", rating = 3, reviewedAt = now - 30 * 60_000L),
            review("ok", rating = 3, reviewedAt = now - 15 * 60_000L)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        assertEquals(1, bundle.weakest.size)
        val weak = bundle.weakest.first()
        assertEquals("bad", weak.questionId)
        assertEquals(3, weak.reviews)
        assertEquals(2, weak.lapses)
        assertEquals(2.0 / 3, weak.againRate, 1e-9)
    }

    // --- response time -----------------------------------------------------------

    @Test
    fun responseTimeHistogramsAndFastFailDetection() {
        val reviews = listOf(
            review("q1", rating = 1, reviewedAt = now - hour, elapsedSeconds = 2),
            review("q1", rating = 1, reviewedAt = now - 50 * 60_000L, elapsedSeconds = 2),
            review("q2", rating = 3, reviewedAt = now - 40 * 60_000L, elapsedSeconds = 7),
            review("q3", rating = 3, reviewedAt = now - 30 * 60_000L, elapsedSeconds = 90)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        val rt = bundle.responseTime
        assertEquals(2, rt.fastFails)
        assertEquals(2, rt.histogram.first { it.label == "0–5s" }.count)
        assertEquals(1, rt.histogram.first { it.label == "5–10s" }.count)
        assertEquals(1, rt.histogram.first { it.label == "1–2m" }.count)
        val again = rt.avgByGrade.first { it.grade == com.mcqapp.domain.ReviewGrade.AGAIN }
        assertEquals(2.0, again.avgSeconds!!, 1e-9)
        // Slowest cards need at least three timed reviews: none qualify here.
        assertTrue(rt.slowest.isEmpty())
    }

    @Test
    fun responseTimeSlowestRanksByMeanSeconds() {
        val reviews = listOf(
            review("slow", rating = 3, reviewedAt = now - 3 * hour, elapsedSeconds = 60),
            review("slow", rating = 3, reviewedAt = now - 2 * hour, elapsedSeconds = 60),
            review("slow", rating = 3, reviewedAt = now - hour, elapsedSeconds = 60),
            review("fast", rating = 3, reviewedAt = now - hour, elapsedSeconds = 5),
            review("fast", rating = 3, reviewedAt = now - 30 * 60_000L, elapsedSeconds = 5),
            review("fast", rating = 3, reviewedAt = now - 15 * 60_000L, elapsedSeconds = 5)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        assertEquals(2, bundle.responseTime.slowest.size)
        assertEquals("slow", bundle.responseTime.slowest.first().questionId)
    }

    // --- hourly and answer buttons ------------------------------------------------

    @Test
    fun hourlyBucketsUseWallClockHours() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour),      // 11:00 UTC
            review("q2", rating = 1, reviewedAt = now - hour - 1),  // 10:59 UTC
            review("q3", rating = 3, reviewedAt = now - 3 * hour)   // 09:00 UTC
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        assertEquals(1, bundle.hourly[11].reviews)
        assertEquals(1, bundle.hourly[10].reviews)
        assertEquals(0.0, bundle.hourly[10].passRate!!, 1e-9)
        assertEquals(1, bundle.hourly[9].reviews)
        assertEquals(24, bundle.hourly.size)
    }

    @Test
    fun answerButtonsSplitByMaturityAndKind() {
        val reviews = listOf(
            review("q1", rating = 1, reviewedAt = now - hour, stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 30),
            review("q2", rating = 3, reviewedAt = now - hour, stateBefore = CardStateKind.REVIEW, intervalBeforeDays = 5),
            review("q3", rating = 4, reviewedAt = now - hour, stateBefore = CardStateKind.NEW),
            review("q4", rating = 3, reviewedAt = now - hour, stateBefore = CardStateKind.RELEARNING),
            review("q5", rating = 3, reviewedAt = now - hour, stateBefore = null)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        val groups = bundle.answerButtons.groups.associateBy { it.kind }
        assertEquals(1, groups[com.mcqapp.domain.stats.AnswerButtonsData.Kind.MATURE]!!.again)
        assertEquals(1, groups[com.mcqapp.domain.stats.AnswerButtonsData.Kind.YOUNG]!!.good)
        assertEquals(1, groups[com.mcqapp.domain.stats.AnswerButtonsData.Kind.LEARNING]!!.easy)
        assertEquals(1, groups[com.mcqapp.domain.stats.AnswerButtonsData.Kind.RELEARNING]!!.good)
    }

    // --- memory growth ------------------------------------------------------------

    @Test
    fun memoryGrowthAveragesStabilityByReviewNumber() {
        val base = todayStart - 10 * day
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = base, stabilityAfter = 1.0),
            review("q1", rating = 3, reviewedAt = base + day, stabilityAfter = 4.0),
            // A row without a stability estimate must not consume a cohort.
            review("q1", rating = 3, reviewedAt = base + 2 * day, stabilityAfter = null, intervalDaysAfter = 3),
            review("q2", rating = 3, reviewedAt = base, stabilityAfter = 2.0)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        val first = bundle.memoryGrowth.byReviewNumber.first { it.label == "1" }
        assertEquals(1.5, first.value!!, 1e-9)
        assertEquals(2, first.count)
        val second = bundle.memoryGrowth.byReviewNumber.first { it.label == "2" }
        assertEquals(4.0, second.value!!, 1e-9)
        // No card has a third stability-bearing review in this fixture.
        assertNull(bundle.memoryGrowth.byReviewNumber[2].value)
    }

    // --- heatmap ------------------------------------------------------------------

    @Test
    fun heatmapCellsLineUpWithStudyDays() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour),
            review("q1", rating = 3, reviewedAt = todayStart - 2 * hour)
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.ALL)
        assertEquals(365, bundle.heatmap.days.size)
        assertEquals(1, bundle.heatmap.days.last().reviews)
        assertEquals(1, bundle.heatmap.days[bundle.heatmap.days.size - 2].reviews)
        assertEquals(2, bundle.heatmap.longestStreak)
    }

    @Test
    fun fsrsStatusReportsFittedWeightsAndReviewCount() {
        val fitted = SchedulerConfig(fsrsParameters = List(21) { 0.5 })
        val bundle = StatsAggregator.build(
            input(reviews = listOf(review("q1", 3, now - hour)), config = fitted),
            null, StatsAggregator.Range.ALL
        )
        assertTrue(bundle.fsrsStatus.fitted)
        assertEquals(1, bundle.fsrsStatus.reviewCount)
        assertEquals(1000, bundle.fsrsStatus.unlockReviews)
        val plain = StatsAggregator.build(input(), null, StatsAggregator.Range.ALL)
        assertFalse(plain.fsrsStatus.fitted)
    }

    @Test
    fun groupRetentionBreaksDownByPaperWhenUnscoped() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour, paperId = "p1"),
            review("q2", rating = 1, reviewedAt = now - hour, paperId = "p2"),
            review("q2", rating = 3, reviewedAt = now - 30 * 60_000L, paperId = "p2")
        )
        val bundle = StatsAggregator.build(input(reviews), null, StatsAggregator.Range.D7)
        val byPaper = bundle.groupRetention.associateBy { it.label }
        assertEquals(1.0, byPaper["Paper One"]!!.retention!!, 1e-9)
        assertEquals(0.5, byPaper["Paper Two"]!!.retention!!, 1e-9)
    }

    @Test
    fun scopedGroupRetentionBreaksDownByCategory() {
        val reviews = listOf(
            review("q1", rating = 3, reviewedAt = now - hour),
            review("q2", rating = 1, reviewedAt = now - 2 * hour),
            // A question with no category still gets a row, under the fallback.
            review("q3", rating = 3, reviewedAt = now - 3 * hour)
        )
        val bundle = StatsAggregator.build(
            input(reviews, categoryOfQuestion = mapOf("q1" to "Algebra", "q2" to "Geometry")),
            "p1",
            StatsAggregator.Range.ALL
        )
        val byLabel = bundle.groupRetention.associateBy { it.label }
        assertEquals(1, byLabel.getValue("Algebra").reviews)
        assertEquals(1, byLabel.getValue("Algebra").passes)
        assertEquals(0, byLabel.getValue("Geometry").passes)
        assertEquals(1, byLabel.getValue("—").passes)
    }
}
