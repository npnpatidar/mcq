package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.stats.FsrsReplay
import com.mcqapp.domain.stats.ReplayReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FsrsReplay.Stream] is the path the statistics screen uses: it replays one
 * keyset page at a time, in `id` order, keeping state per card instead of the
 * per-review maps [FsrsReplay.replay] needs.
 *
 * Contract: **append (`id`) order is authoritative for [FsrsReplay.Stream]**.
 * The store feeds rows by ascending `id` — each card's own append order — and
 * the stream does not re-sort by `reviewedAt`. [FsrsReplay.replay] does sort
 * each card's history by `(reviewedAt, id)`, so the two agree only while
 * `reviewedAt` is monotonic with `id`; [nonMonotonicTimestampsFollowAppendOrder]
 * pins the case where it is not. Scheduling never reads the log, so this
 * cannot move a card's schedule.
 */
class FsrsReplayStreamTest {

    private val scheduler = FsrsScheduler()
    private val day = FsrsScheduler.DAY_MS
    private val t0 = 1_750_000_000_000L

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
     * A log in the shape the app writes: cards interleaved by append order,
     * each card's own rows in time order, and one card whose history was seeded
     * from memory that predates the log.
     */
    private val log = listOf(
        row(1, questionId = "a", rating = 3, reviewedAt = t0),
        row(2, questionId = "b", rating = 1, reviewedAt = t0 + 1_000L),
        // b again the same day: short-term memory, no whole-day interval yet.
        row(3, questionId = "b", rating = 3, reviewedAt = t0 + 60_000L),
        // a a week later, so its second row has a real prediction.
        row(4, questionId = "a", rating = 2, reviewedAt = t0 + 7 * day),
        row(
            5, questionId = "c", rating = 3, reviewedAt = t0,
            stateBefore = CardStateKind.REVIEW,
            stabilityAfter = 5.0,
            difficultyAfter = 6.0,
            intervalDaysAfter = 5
        ),
        row(6, questionId = "c", rating = 4, reviewedAt = t0 + 3 * day, paperId = "p2")
    )

    private fun streamed(rows: List<FsrsReplay.RawReview>): List<ReplayReview> {
        val stream = FsrsReplay.Stream(scheduler)
        return rows.map { stream.accept(it) }
    }

    @Test
    fun theStreamAgreesWithTheBatchReplayRowForRow() {
        assertEquals(FsrsReplay.replay(log, scheduler), streamed(log))
    }

    @Test
    fun aPageBoundaryDoesNotResetCardState() {
        // The store splits the log into keyset pages; a card whose history
        // straddles two pages must not be re-seeded by the page break.
        val stream = FsrsReplay.Stream(scheduler)
        val firstPage = log.take(3).map { stream.accept(it) }
        val secondPage = log.drop(3).map { stream.accept(it) }
        assertEquals(FsrsReplay.replay(log, scheduler), firstPage + secondPage)
    }

    @Test
    fun nonMonotonicTimestampsFollowAppendOrder() {
        // A clock rollback (or a restored log) can append a row stamped before
        // its predecessor. By contract the stream replays it in append order,
        // so it *has* a pre-state; the batch oracle re-sorts the card's rows by
        // `reviewedAt`, so it sees that row first and has none. Pin both, so
        // neither the contract nor the oracle can drift silently.
        val rollback = listOf(
            row(1, questionId = "d", rating = 3, reviewedAt = t0 + 7 * day),
            row(2, questionId = "d", rating = 2, reviewedAt = t0)
        )
        val streamedRows = streamed(rollback)
        assertNotNull(
            "append order gives the second row the first row's state",
            streamedRows.last().predictedRetention
        )
        assertNull(
            "the batch oracle re-sorted it first, so it has no pre-state",
            FsrsReplay.replay(rollback, scheduler).last().predictedRetention
        )
        // In the normal (id-monotonic) case the two are still identical.
        assertEquals(FsrsReplay.replay(log, scheduler), streamed(log))
    }

    @Test
    fun anEmptyLogReplaysToNothing() {
        assertEquals(emptyList<ReplayReview>(), streamed(emptyList()))
    }
}
