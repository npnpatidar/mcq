package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.stats.FsrsReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsReplayTest {

    private val scheduler = FsrsScheduler()
    private val day = FsrsScheduler.DAY_MS

    private fun row(
        id: Long,
        questionId: String = "q1",
        rating: Int,
        reviewedAt: Long,
        stateBefore: CardStateKind? = CardStateKind.NEW,
        stabilityAfter: Double? = null,
        difficultyAfter: Double? = null,
        intervalDaysAfter: Int? = null,
        paperId: String = "p1"
    ) = FsrsReplay.RawReview(
        id = id,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = null,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter
    )

    /**
     * A question reused in two papers is two cards. Grouping by `questionId`
     * alone compared one paper's label against the other paper's memory state;
     * the history must be per (paperId, questionId).
     */
    @Test
    fun aQuestionReusedInTwoPapersReplaysSeparately() {
        val t0 = 1_750_000_000_000L
        val seeded = CardStateKind.REVIEW
        val reviews = listOf(
            row(
                1, questionId = "q1", paperId = "p1", rating = 3, reviewedAt = t0,
                stateBefore = seeded, stabilityAfter = 10.0, difficultyAfter = 5.0,
                intervalDaysAfter = 10
            ),
            row(
                2, questionId = "q1", paperId = "p1", rating = 3,
                reviewedAt = t0 + 3 * day, stateBefore = seeded
            ),
            row(
                3, questionId = "q1", paperId = "p2", rating = 3,
                reviewedAt = t0 + 1_000, stateBefore = seeded, stabilityAfter = 1.0,
                difficultyAfter = 5.0, intervalDaysAfter = 1
            ),
            row(
                4, questionId = "q1", paperId = "p2", rating = 3,
                reviewedAt = t0 + 1_000 + 3 * day, stateBefore = seeded
            )
        )
        val replayed = FsrsReplay.replay(reviews, scheduler).associateBy { it.paperId to it.reviewedAt }
        val p1 = replayed["p1" to t0 + 3 * day]!!.predictedRetention
        val p2 = replayed["p2" to t0 + 1_000 + 3 * day]!!.predictedRetention
        assertNotNull(p1)
        assertNotNull(p2)
        assertTrue("the two cards' forecasts must come from their own memory", p1 != p2)
    }

    @Test
    fun emptyInputReplaysToEmpty() {
        assertTrue(FsrsReplay.replay(emptyList(), scheduler).isEmpty())
    }

    /**
     * An out-of-range rating only exists on a corrupt or hand-edited row. It
     * folds into Good for the graded replay — the same rule the FSRS-7 rebuild
     * applies — so one bad row cannot abort a whole history, while the enriched
     * row still reports the stored rating for the screens that display it.
     */
    @Test
    fun anOutOfRangeRatingReplaysAsGood() {
        val t0 = 1_750_000_000_000L
        val corrupt = FsrsReplay.replay(
            listOf(row(1, rating = 0, reviewedAt = t0), row(2, rating = 9, reviewedAt = t0 + 3 * day)),
            scheduler
        )
        val good = FsrsReplay.replay(
            listOf(
                row(1, rating = ReviewGrade.GOOD.ordinal + 1, reviewedAt = t0),
                row(2, rating = ReviewGrade.GOOD.ordinal + 1, reviewedAt = t0 + 3 * day)
            ),
            scheduler
        )
        assertEquals(good.size, corrupt.size)
        corrupt.zip(good).forEach { (actual, expected) ->
            assertEquals(expected.predictedRetention, actual.predictedRetention)
            assertEquals(expected.intervalBeforeDays, actual.intervalBeforeDays)
            assertEquals(expected.wouldBeGoodIntervalDays, actual.wouldBeGoodIntervalDays)
        }
        assertEquals(listOf(0, 9), corrupt.map { it.rating })
    }

    /**
     * Two rows written in the same millisecond — a double tap, or an import that
     * kept second-resolution timestamps. The log's autoincrement `id` decides
     * the order, so the second row predicts from the first row's post-state at
     * zero elapsed rather than the two replaying in caller order.
     */
    @Test
    fun rowsWithTheSameTimestampReplayInIdOrder() {
        val t0 = 1_750_000_000_000L
        // Fed the wrong way round on purpose.
        val replayed = FsrsReplay.replay(
            listOf(row(2, rating = 1, reviewedAt = t0), row(1, rating = 3, reviewedAt = t0)),
            scheduler
        )
        val afterGood = scheduler.next(scheduler.initial("q1"), ReviewGrade.GOOD, t0)
        val second = replayed.single { it.rating == ReviewGrade.AGAIN.ordinal + 1 }
        assertEquals(
            scheduler.retention(afterGood, t0),
            second.predictedRetention!!,
            1e-12
        )
        assertEquals(afterGood.intervalDays, second.intervalBeforeDays)
        // Row 1 is the first exposure: nothing to predict from.
        val first = replayed.single { it.rating == ReviewGrade.GOOD.ordinal + 1 }
        assertNull(first.predictedRetention)
    }

    @Test
    fun firstReviewHasNoPredictionAndSecondPredictsFromAdvancedState() {
        val t0 = 1_750_000_000_000L
        val t1 = t0 + 3 * day
        val reviews = listOf(
            row(1, rating = ReviewGrade.GOOD.ordinal + 1, reviewedAt = t0),
            row(2, rating = ReviewGrade.GOOD.ordinal + 1, reviewedAt = t1)
        )
        val replayed = FsrsReplay.replay(reviews, scheduler)
        assertEquals(2, replayed.size)
        assertNull(replayed[0].predictedRetention)
        assertNull(replayed[0].intervalBeforeDays)
        val stateAfterFirst = scheduler.next(scheduler.initial("q1"), ReviewGrade.GOOD, t0)
        assertEquals(
            scheduler.retention(stateAfterFirst, t1),
            replayed[1].predictedRetention!!,
            1e-12
        )
        assertTrue(replayed[1].predictedRetention!! in 0.0..1.0)
        assertEquals(stateAfterFirst.intervalDays, replayed[1].intervalBeforeDays)
    }

    @Test
    fun seededHistoryExcludesFirstRowAndSeedsTheRest() {
        val t0 = 1_750_000_000_000L
        val t1 = t0 + 2 * day
        val reviews = listOf(
            row(
                1, rating = 3, reviewedAt = t0,
                stateBefore = CardStateKind.REVIEW,
                stabilityAfter = 5.0,
                difficultyAfter = 6.0,
                intervalDaysAfter = 5
            ),
            row(2, rating = 3, reviewedAt = t1)
        )
        val replayed = FsrsReplay.replay(reviews, scheduler)
        assertNull(replayed[0].predictedRetention)
        // The seed is the first row's own post-state, so the second row's
        // prediction reads the seed's stability two days later, not 1.0.
        val seed = CardState(
            questionId = "q1",
            stability = 5.0,
            difficulty = 6.0,
            intervalDays = 5,
            lastReviewedAt = t0,
            state = CardStateKind.REVIEW,
            reviewCount = 1
        )
        assertEquals(scheduler.retention(seed, t1), replayed[1].predictedRetention!!, 1e-12)
        assertTrue(replayed[1].predictedRetention!! < 1.0)
        assertEquals(5, replayed[1].intervalBeforeDays)
    }

    @Test
    fun sameDayReviewsAdvanceThroughShortTermStability() {
        val t0 = 1_750_000_000_000L
        val t1 = t0 + 60_000L
        val t2 = t0 + 120_000L
        val reviews = listOf(
            row(1, rating = 1, reviewedAt = t0),
            row(2, rating = 3, reviewedAt = t1),
            row(3, rating = 3, reviewedAt = t2)
        )
        val replayed = FsrsReplay.replay(reviews, scheduler)
        assertNull(replayed[0].predictedRetention)
        assertNull(replayed[1].predictedRetention)
        // The third review sees whatever stability the same-day step produced,
        // which is non-zero memory even though no day has passed.
        assertTrue(replayed[2].predictedRetention!! > 0.0)
        assertTrue(replayed[2].predictedRetention!! <= 1.0)
    }

    @Test
    fun cardsReplayIndependentlyAndCallerOrderIsPreserved() {
        val t0 = 1_750_000_000_000L
        val t1 = t0 + day
        val reviews = listOf(
            row(1, questionId = "b", rating = 3, reviewedAt = t1, paperId = "p2"),
            row(2, questionId = "a", rating = 3, reviewedAt = t0),
            row(3, questionId = "a", rating = 3, reviewedAt = t1)
        )
        val replayed = FsrsReplay.replay(reviews, scheduler)
        assertEquals("b", replayed[0].questionId)
        assertNull(replayed[0].predictedRetention)
        assertNull(replayed[1].predictedRetention)
        assertTrue(replayed[2].predictedRetention != null)
    }
}
