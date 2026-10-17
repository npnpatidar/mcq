package com.mcqapp.domain.stats

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade

/**
 * Replays every card's review history through the current [FsrsScheduler],
 * recovering for each log row the two facts the scheduler knew at the moment
 * of answering but the log does not store: the predicted recall probability
 * just before the grade, and the interval the card was carrying into it.
 *
 * This is the same grouping the optimizer's training set uses (see
 * `FsrsTuner.buildFsrsTrainingCards`): a history whose first logged review
 * begins already in review — an Anki import carrying memory, or a log written
 * after card state existed — cannot predict that first row, so it seeds from
 * the memory state that row recorded and replays the rest. A complete history
 * replays from its first exposure, with a null prediction on row one.
 *
 * Pure: the scheduler is clock-free and each card is replayed in one linear
 * pass, so the cost is one [FsrsScheduler.next] per logged review.
 */
object FsrsReplay {

    /**
     * A review-log row before enrichment; [id] only has to be unique within
     * one question's history so rows order deterministically on ties.
     */
    data class RawReview(
        val id: Long,
        val paperId: String,
        val questionId: String,
        val rating: Int,
        val reviewedAt: Long,
        val elapsedSeconds: Long?,
        val stateBefore: CardStateKind?,
        val stabilityAfter: Double?,
        val difficultyAfter: Double?,
        val intervalDaysAfter: Int?
    )

    /**
     * Enriches [reviews] (unsorted, possibly spanning many cards) with
     * pre-answer predictions. The returned list preserves the caller's order.
     */
    fun replay(
        reviews: List<RawReview>,
        scheduler: FsrsScheduler
    ): List<ReplayReview> {
        if (reviews.isEmpty()) return emptyList()
        val predicted = HashMap<Long, Double?>()
        val intervalBefore = HashMap<Long, Int?>()
        reviews.groupBy { it.questionId }.forEach { (_, rows) ->
            val ordered = rows.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
            // A seeded history's first row has no knowable pre-state: it is the
            // record of memory that predates the log. Its post-state, which the
            // row itself carries, seeds everything after it.
            val seeded = isSeeded(ordered.first())
            var state: CardState? = null
            var previousInterval: Int? = null
            ordered.forEachIndexed { index, row ->
                val pre = state
                // Only a graduated card carries a knowable recall prediction:
                // learning-step rows have short-term memory, not a schedule.
                predicted[row.id] = pre
                    ?.takeIf {
                        it.state == CardStateKind.REVIEW || it.state == CardStateKind.RELEARNING
                    }
                    ?.takeIf { it.stability > 0.0 }
                    ?.let { scheduler.retention(it, row.reviewedAt) }
                intervalBefore[row.id] = previousInterval
                val post = if (index == 0 && seeded) {
                    seedState(row)
                } else {
                    scheduler.next(
                        pre ?: scheduler.initial(row.questionId),
                        gradeOf(row.rating),
                        row.reviewedAt
                    )
                }
                state = post
                // The next row was carrying this state's interval, not whatever
                // the log recorded — replay recovers what the scheduler decided.
                previousInterval = post.intervalDays
            }
        }
        return reviews.map { row ->
            ReplayReview(
                paperId = row.paperId,
                questionId = row.questionId,
                rating = row.rating,
                reviewedAt = row.reviewedAt,
                elapsedSeconds = row.elapsedSeconds,
                stateBefore = row.stateBefore,
                stabilityAfter = row.stabilityAfter,
                difficultyAfter = row.difficultyAfter,
                intervalDaysAfter = row.intervalDaysAfter,
                predictedRetention = predicted[row.id],
                intervalBeforeDays = intervalBefore[row.id]
            )
        }
    }

    /**
     * A one-pass replayer for a log read in `id` order — the order rows were
     * appended, which is also each card's own chronological order. [replay]
     * has to hold two maps keyed by *row* id (one entry per logged review, so a
     * million-row log needs millions of entries) because it re-sorts an
     * arbitrarily ordered list. This keeps only per-card state instead:
     * [CardState] plus the interval carried out of the last review, one entry
     * per card — a quarter of the size for the 1M-review/250k-card target, and
     * it never holds a whole page's enriched rows.
     *
     * The caller must feed rows in ascending `id` and the same rows [replay]
     * would sort within each card; the two are pinned equal by
     * `FsrsReplayStreamTest`, which exists because this is the path the
     * statistics screen actually uses.
     */
    class Stream(private val scheduler: FsrsScheduler) {
        private val states = HashMap<String, CardState>()
        private val previousIntervals = HashMap<String, Int>()

        fun accept(row: RawReview): ReplayReview {
            val pre = states[row.questionId]
            val predicted = pre
                ?.takeIf {
                    it.state == CardStateKind.REVIEW || it.state == CardStateKind.RELEARNING
                }
                ?.takeIf { it.stability > 0.0 }
                ?.let { scheduler.retention(it, row.reviewedAt) }
            val intervalBefore = previousIntervals[row.questionId]
            // A seeded history's first row has no knowable pre-state: it is the
            // record of memory that predates the log. Its post-state, which the
            // row itself carries, seeds everything after it.
            val post = if (pre == null && isSeeded(row)) {
                seedState(row)
            } else {
                scheduler.next(
                    pre ?: scheduler.initial(row.questionId),
                    gradeOf(row.rating),
                    row.reviewedAt
                )
            }
            states[row.questionId] = post
            previousIntervals[row.questionId] = post.intervalDays
            return ReplayReview(
                paperId = row.paperId,
                questionId = row.questionId,
                rating = row.rating,
                reviewedAt = row.reviewedAt,
                elapsedSeconds = row.elapsedSeconds,
                stateBefore = row.stateBefore,
                stabilityAfter = row.stabilityAfter,
                difficultyAfter = row.difficultyAfter,
                intervalDaysAfter = row.intervalDaysAfter,
                predictedRetention = predicted,
                intervalBeforeDays = intervalBefore
            )
        }
    }

    private fun isSeeded(row: RawReview): Boolean =
        row.stateBefore != null &&
            row.stateBefore != CardStateKind.NEW &&
            row.stabilityAfter != null &&
            row.difficultyAfter != null

    /** The memory state the seeded first row records as its result. */
    private fun seedState(row: RawReview): CardState = CardState(
        questionId = row.questionId,
        stability = row.stabilityAfter ?: 0.0,
        difficulty = row.difficultyAfter ?: 0.0,
        intervalDays = row.intervalDaysAfter ?: 0,
        lastReviewedAt = row.reviewedAt,
        state = CardStateKind.REVIEW,
        reviewCount = 1
    )

    private fun gradeOf(rating: Int): ReviewGrade =
        ReviewGrade.entries.getOrElse(rating - 1) { ReviewGrade.GOOD }
}
