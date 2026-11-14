package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.ReviewLogDao
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.FsrsTrainingCard
import com.mcqapp.domain.FsrsTrainingReview
import com.mcqapp.util.Logger

/**
 * The settings-side bridge between the immutable review log and
 * [FsrsOptimizer]: groups logged reviews into per-card training histories,
 * runs the fit, and persists the result through [SettingsStore] so the next
 * review schedules on the fitted weights. Nothing here touches card state —
 * an optimization changes how future intervals are computed, never a schedule
 * that already exists.
 */
internal class FsrsTuner(
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    /** Reviews logged across every paper: the optimizer's unlock gauge. */
    fun observeReviewCount() = db.reviewLogDao().observeCount()

    /**
     * Fits the 34 weights to the full review log and stores them. The log is
     * paged into per-card histories ([buildTrainingCards]), so a million rows
     * are never held as entities. The fit itself runs on Dispatchers.Default
     * (inside [FsrsOptimizer.fit]); [onProgress] fires after each Adam step and
     * cancelling the caller stops between steps.
     *
     * The fit is stored only when it is **strictly better** than the current
     * weights on the same predictions. Current Anki/rslib evaluates the
     * candidate and keeps the current set when it is no worse
     * (`rslib/src/scheduler/fsrs/params.rs`); an equal loss is therefore not
     * an improvement either, and the learner keeps what they already had.
     *
     * A log too thin to train returns the official defaults untouched
     * (`steps = 0`), as the reference does.
     */
    suspend fun optimize(onProgress: (Int, Int) -> Unit = { _, _ -> }): FsrsFitResult {
        // A fit must never race the one-time model migration: the weights it
        // reads and writes have to be FSRS-7's, not a legacy 21-value set.
        settings.ensureFsrs7()
        val cards = buildTrainingCards(db.reviewLogDao())
        val current = settings.schedulerConfigNow().fsrsParameters
        val result = FsrsOptimizer.fit(cards, current, onProgress)
        val improves = storeIfImproved(result)
        Logger.i(
            "TUNER",
            "FSRS optimized on ${result.predictions} predictions " +
                "(${result.items} items, ${result.cards} cards, ${result.steps} steps): " +
                "loss ${result.lossBefore} -> ${result.lossAfter} " +
                (if (improves) "(stored)" else "(kept current weights)")
        )
        return result
    }

    /**
     * Persists [result] when it is a **strict** improvement, and reports whether
     * it did.
     *
     * The policy is deliberately narrow:
     *  - `lossAfter < lossBefore` — an equal loss is not an improvement, so the
     *    learner keeps the weights they already had (current Anki/rslib keeps
     *    the current set when the candidate is no worse).
     *  - both losses must be finite. A non-finite prediction makes the loss
     *    `NaN`, and `NaN < x` is false, but the explicit check says so instead
     *    of relying on the comparison's polarity.
     *
     * The write is a single-key DataStore edit: no read-modify-write of the
     * whole config, so a racing pacing slider write cannot clobber the fit (or
     * the fit cannot clobber it).
     */
    internal suspend fun storeIfImproved(result: FsrsFitResult): Boolean {
        val improves = result.lossBefore.isFinite() &&
            result.lossAfter.isFinite() &&
            result.lossAfter < result.lossBefore
        if (improves) settings.setFsrsParameters(result.parameters)
        return improves
    }

    /**
     * Drops fitted weights; scheduling returns to the official defaults. The
     * fit version goes back to 0, not up: a reset is not a fit, and rows must
     * not claim they were scheduled under a weight set nobody fitted.
     */
    suspend fun restoreDefaults() {
        settings.writeWeights(FsrsScheduler.DEFAULT_FSRS_PARAMETERS, forceVersion = 0)
        Logger.i("TUNER", "FSRS parameters restored to defaults")
    }

    companion object {

        /** Cards per page when streaming the log into training cards. */
        private const val QUESTION_PAGE = 500

        /**
         * The training cards for the whole log, built a page of **cards** at a
         * time.
         *
         * `getAll()` used to return the log as one `List<ReviewLogEntity>` and
         * `groupBy` then built a second full-bank structure beside the cards;
         * at a million reviews that was the peak of the fit. Only one page of
         * rows and the cards themselves are live here — the cards the fit needs
         * anyway. [buildFsrsTrainingCards] stays as the test oracle.
         *
         * Identity is `(paperId, questionId)`, the app's card key: two papers
         * can carry the same question, and merging their logs would interleave
         * unrelated review events into one training history (and compute
         * elapsed gaps across cards). The page query is the same composite-key
         * page the FSRS-7 rebuild uses, ordered by each card's first row id.
         */
        internal suspend fun buildTrainingCards(
            dao: ReviewLogDao,
            pageSize: Int = QUESTION_PAGE
        ): List<FsrsTrainingCard> {
            val cards = ArrayList<FsrsTrainingCard>()
            var cursor = 0L
            while (true) {
                val page = dao.pageCardKeysAfterRowId(cursor, pageSize)
                if (page.isEmpty()) break
                cursor = page.last().firstRowId
                val questionIds = page.map { it.questionId }.distinct()
                val keys = page.mapTo(HashSet()) { it.paperId to it.questionId }
                // A question id can appear in another paper; drop rows of
                // cards outside this page so the later page owns them.
                val rowsByCard = dao.rowsForQuestions(questionIds)
                    .filter { (it.paperId to it.questionId) in keys }
                    .groupBy { it.paperId to it.questionId }
                for (row in page) {
                    val key = row.paperId to row.questionId
                    val rows = rowsByCard[key] ?: continue
                    trainingCardOf(rows.sortedWith(compareBy({ it.reviewedAt }, { it.id })))
                        ?.let { cards += it }
                }
            }
            return cards
        }

        /**
         * Groups [logs] into one training card per `(paperId, questionId)`,
         * oldest first. The tests' oracle for [buildTrainingCards].
         *
         * A history whose first logged review begins already in review (an
         * Anki import carrying memory, or a log written after card state
         * existed) cannot predict that first row — there is no prior state —
         * so it seeds from the state that row recorded and trains on the rest.
         * Anything else is a complete history starting from its first
         * exposure, exactly as the optimizer replays a new card.
         */
        internal fun buildFsrsTrainingCards(logs: List<ReviewLogEntity>): List<FsrsTrainingCard> =
            logs
                .groupBy { it.paperId to it.questionId }
                .values
                .mapNotNull { rows ->
                    trainingCardOf(rows.sortedWith(compareBy({ it.reviewedAt }, { it.id })))
                }

        /**
         * One card's rows — already chronological — as a training card, or
         * null when a seeded history has nothing after the seed. Shared by the
         * in-memory and the paged build, so the two cannot drift.
         */
        private fun trainingCardOf(rows: List<ReviewLogEntity>): FsrsTrainingCard? {
            val first = rows.first()
            val seeded = first.stateBefore != null &&
                first.stateBefore != CardStateKind.NEW.name &&
                first.stabilityAfter != null &&
                first.difficultyAfter != null
            return if (seeded) {
                val reviews = rows.drop(1)
                if (reviews.isEmpty()) return null
                FsrsTrainingCard(
                    reviews = reviews.map { FsrsTrainingReview(it.rating, it.reviewedAt) },
                    seedStability = first.stabilityAfter,
                    seedStabilityFast = first.stabilityFastAfter,
                    seedDifficulty = first.difficultyAfter,
                    seedReviewedAt = first.reviewedAt
                )
            } else {
                FsrsTrainingCard(
                    reviews = rows.map { FsrsTrainingReview(it.rating, it.reviewedAt) }
                )
            }
        }
    }
}
