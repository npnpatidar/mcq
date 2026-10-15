package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.stats.FsrsReplay
import org.junit.Assert.assertEquals
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

    @Test
    fun emptyInputReplaysToEmpty() {
        assertTrue(FsrsReplay.replay(emptyList(), scheduler).isEmpty())
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
