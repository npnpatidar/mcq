package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * The one-time FSRS-6 → FSRS-7 card-state rebuild.
 *
 * FSRS-6 stored one stability; FSRS-7 stores two. The second one cannot be
 * invented from the first — the reference migration guide is explicit that the
 * fast trace has to come from replaying the review history through the new
 * model, without an old-model initialState. This job does exactly that: it
 * replays each card's log with real fractional elapsed time and writes only
 * `(stability, stabilityFast, difficulty)`.
 *
 * Nothing else moves. In particular `dueAt`, `intervalDays`, `reps`, `lapses`,
 * `leech`, `lastReviewedAt`, `contentHash` and `ease` keep the values the
 * learner's current schedule already shows, so a card due today stays due
 * today; only the memory numbers behind its *future* intervals change.
 *
 * Safety:
 *  - **Resumable.** The last processed `review_log` id is persisted, so a
 *    process kill costs one page, not the whole replay.
 *  - **Races the study loop safely.** The replay never holds a lock, and the
 *    write is a compare-and-set on the **last logged review's** timestamp, not
 *    on a stamp read separately: history and predicate describe the same
 *    snapshot. A review that committed between the history read and the write
 *    advanced `lastReviewedAt`, so the predicate misses and that review wins.
 *    (Reading a stamp after the history could record the newer timestamp and
 *    let the stale replay match — the race this ordering removes.)
 *  - **Repairs dropped fast traces.** Rows saved before the write-side fix
 *    lost `stabilityFast`; a one-shot repair copies the latest review-log
 *    `stabilityFastAfter`, which is the value the review actually produced.
 *  - **Cards with no log.** A pure Anki import carries memory but no review
 *    history. Their fast trace is set to the slow one at the end — the same
 *    "unknown fast trace" rule the scheduler applies at read time, never the
 *    0.8× initialization ratio, which would be a fabrication.
 *  - **Plain reads and writes, no interval solving.** [FsrsScheduler.advanceMemory]
 *    runs the review formula only; the rebuild never needs a due date, so it
 *    never pays for the numerical interval solve.
 */
internal class Fsrs7Upgrade(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    /**
     * Runs the rebuild unless it has already completed, reporting
     * `(cardsDone, cardsTotal)` as it goes. Idempotent, and safe to call on
     * every launch: a completed run returns immediately.
     */
    suspend fun runIfNeeded(onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            if (!settings.fastTraceRepairDone()) {
                val repaired = db.cardStateDao().repairFastStabilityFromLog()
                settings.completeFastTraceRepair()
                Logger.i("FSRS7", "fast-trace repair: $repaired row(s) restored from the log")
            }
            if (settings.fsrs7RebuildDone()) return@withContext
            val startedAt = System.currentTimeMillis()
            val config = settings.schedulerConfigNow()
            val scheduler = FsrsScheduler(
                desiredRetention = config.desiredRetention,
                config = config
            )
            val total = db.reviewLogDao().countCards()
            var cursor = settings.fsrsRebuildCursor()
            var done = 0
            Logger.i("FSRS7", "rebuild starting: $total cards, cursor $cursor")
            onProgress(0, total)

            while (true) {
                val page = db.reviewLogDao().pageCardKeysAfterRowId(cursor, PAGE_CARDS)
                if (page.isEmpty()) break
                val questionIds = page.map { it.questionId }.distinct()
                val keys = page.mapTo(HashSet()) { it.paperId to it.questionId }
                val rowsByCard = db.reviewLogDao()
                    .rowsForQuestions(questionIds)
                    .filter { (it.paperId to it.questionId) in keys }
                    .groupBy { it.paperId to it.questionId }

                db.withTransaction {
                    for ((cardKey, rows) in rowsByCard) {
                        val rebuilt = replay(rows, scheduler) ?: continue
                        // The predicate is the replay's own last review, so a
                        // review that committed after these rows were read
                        // makes the stamp differ and this write a no-op.
                        db.cardStateDao().rebuildMemoryIfUnchanged(
                            paperId = cardKey.first,
                            questionId = cardKey.second,
                            lastReviewedAt = rebuilt.lastReviewedAt,
                            stability = rebuilt.stability,
                            stabilityFast = rebuilt.stabilityFast,
                            difficulty = rebuilt.difficulty
                        )
                    }
                }

                cursor = page.last().firstRowId
                settings.setFsrsRebuildCursor(cursor)
                done += page.size
                onProgress(done, total)
                // One page per transaction; let a review or a UI read in
                // between instead of monopolising the write lock.
                yield()
            }

            val filled = db.cardStateDao().fillMissingFastStability()
            settings.completeFsrs7Rebuild()
            Logger.i(
                "FSRS7",
                "rebuild complete: $done cards, $filled without a log, " +
                    "${System.currentTimeMillis() - startedAt} ms"
            )
            onProgress(done, total)
        }

    /** The memory state the replay recovered. */
    private class Rebuilt(
        val stability: Double,
        val stabilityFast: Double,
        val difficulty: Double,
        /** The rewritten history's last review — the compare-and-set stamp. */
        val lastReviewedAt: Long
    )

    /**
     * Replays one card's rows in the order the app wrote them: `reviewedAt`
     * first, `id` breaking exact ties, matching [FsrsReplay] and the optimizer.
     *
     * A history whose first row begins in review (an Anki import, or a log
     * written after card state existed) cannot be replayed from a new card: its
     * recorded post-state seeds the replay, and only the rows after it are
     * applied. This is the same seed rule [FsrsReplay] uses, with the fast trace
     * added; a pre-v27 row carries no fast trace, so the slow one stands in.
     */
    private fun replay(rows: List<ReviewLogEntity>, scheduler: FsrsScheduler): Rebuilt? {
        if (rows.isEmpty()) return null
        val ordered = rows.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
        val first = ordered.first()
        val seeded = first.stateBefore != null &&
            first.stateBefore != CardStateKind.NEW.name &&
            first.stabilityAfter != null &&
            first.difficultyAfter != null

        var state = if (seeded) {
            val stability = first.stabilityAfter!!
            CardState(
                questionId = first.questionId,
                stability = stability,
                stabilityFast = first.stabilityFastAfter ?: stability,
                difficulty = first.difficultyAfter!!,
                intervalDays = first.intervalDaysAfter ?: 0,
                lastReviewedAt = first.reviewedAt,
                state = CardStateKind.REVIEW,
                reviewCount = 1
            )
        } else {
            CardState(questionId = first.questionId)
        }

        for (index in (if (seeded) 1 else 0) until ordered.size) {
            val row = ordered[index]
            state = scheduler.advanceMemory(
                state = state,
                rating = ratingOf(row.rating),
                elapsedDays = elapsedDays(state, row.reviewedAt)
            ).copy(lastReviewedAt = row.reviewedAt)
        }

        return Rebuilt(
            stability = state.stability,
            stabilityFast = state.stabilityFast,
            difficulty = state.difficulty,
            lastReviewedAt = state.lastReviewedAt
        )
    }

    /**
     * Fractional days between the state's last review and [now], mirroring
     * [FsrsScheduler.next]'s rule for a card with memory but no timestamp (an
     * import whose history is unknown): its own interval stands in, so the
     * update is a long-gap one rather than an instantaneous one.
     */
    private fun elapsedDays(state: CardState, now: Long): Double {
        if (state.lastReviewedAt == 0L) {
            return if (state.stability > 0.0) {
                state.intervalDays.toDouble().coerceAtLeast(1.0)
            } else {
                0.0
            }
        }
        return (now - state.lastReviewedAt).coerceAtLeast(0L) /
            FsrsScheduler.DAY_MS.toDouble()
    }

    /**
     * The engine's 1..4 grade for a stored rating. A corrupt value folds to
     * Good, matching [com.mcqapp.domain.stats.FsrsReplay] and the optimizer, so
     * one hand-edited row cannot poison the rebuild.
     */
    private fun ratingOf(rating: Int): Int =
        if (rating in 1..4) rating else ReviewGrade.GOOD.ordinal + 1

    private companion object {
        /** Cards per transaction — bounded memory, bounded lock time. */
        const val PAGE_CARDS = 500
    }
}
