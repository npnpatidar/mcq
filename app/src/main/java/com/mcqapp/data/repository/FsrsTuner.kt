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
     * Fits the 21 weights to the full review log and stores them. The log is
     * paged into per-card histories ([buildTrainingCards]), so a million rows
     * are never held as entities. The fit itself runs on Dispatchers.Default
     * (inside [FsrsOptimizer.fit]); [onProgress] fires after each Adam step and
     * cancelling the caller stops between steps.
     *
     * The weights are persisted even when a re-fit's loss lands above the
     * learner's current set (Anki persists regardless); the Settings done
     * line labels the comparison against the current weights, so a regression
     * is visible rather than silent.
     *
     * @throws com.mcqapp.domain.FsrsFitInsufficientData when the log is too
     *   thin to fit anything.
     */
    suspend fun optimize(onProgress: (Int, Int) -> Unit = { _, _ -> }): FsrsFitResult {
        val cards = buildTrainingCards(db.reviewLogDao())
        val current = settings.schedulerConfigNow().fsrsParameters
        val result = FsrsOptimizer.fit(cards, current, onProgress)
        // A single-key DataStore edit: no read-modify-write of the whole
        // config, so a racing pacing slider write cannot clobber the fit (or
        // the fit cannot clobber it).
        settings.setFsrsParameters(result.parameters)
        Logger.i(
            "TUNER",
            "FSRS optimized on ${result.predictions} predictions " +
                "(${result.items} items, ${result.cards} cards, ${result.steps} steps): " +
                "loss ${result.lossBefore} -> ${result.lossAfter}"
        )
        return result
    }

    /** Drops fitted weights; scheduling returns to the official defaults. */
    suspend fun restoreDefaults() {
        settings.setFsrsParameters(FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        Logger.i("TUNER", "FSRS parameters restored to defaults")
    }

    companion object {

        /** Questions per page when streaming the log into training cards. */
        private const val QUESTION_PAGE = 500

        /**
         * The training cards for the whole log, built a page of questions at a
         * time.
         *
         * `getAll()` used to return the log as one `List<ReviewLogEntity>` and
         * `groupBy` then built a second full-bank structure beside the cards;
         * at a million reviews that was the peak of the fit. Only one page of
         * rows and the cards themselves are live here — the cards the fit needs
         * anyway. [buildFsrsTrainingCards] stays as the test oracle.
         *
         * The order is what `groupBy` produced: questions first seen earliest
         * in the id-ordered log, i.e. by each question's minimum row id. The
         * pages come back alphabetically, so the cards are re-sorted by that
         * minimum before the fit sees them.
         */
        internal suspend fun buildTrainingCards(
            dao: ReviewLogDao,
            pageSize: Int = QUESTION_PAGE
        ): List<FsrsTrainingCard> {
            val cards = ArrayList<FsrsTrainingCard>()
            val firstIds = ArrayList<Long>()
            var after = ""
            while (true) {
                val questionIds = dao.pageQuestionIdsAfter(after, pageSize)
                if (questionIds.isEmpty()) break
                after = questionIds.last()
                val rows = dao.rowsForQuestions(questionIds)
                var index = 0
                while (index < rows.size) {
                    val questionId = rows[index].questionId
                    var minId = Long.MAX_VALUE
                    val start = index
                    while (index < rows.size && rows[index].questionId == questionId) {
                        if (rows[index].id < minId) minId = rows[index].id
                        index++
                    }
                    val card = trainingCardOf(rows.subList(start, index)) ?: continue
                    cards += card
                    firstIds += minId
                }
            }
            val order = cards.indices.sortedBy { firstIds[it] }
            return order.map { cards[it] }
        }

        /**
         * Groups [logs] into one training card per question, oldest first. The
         * tests' oracle for [buildTrainingCards].
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
                .groupBy { it.questionId }
                .values
                .mapNotNull { rows ->
                    trainingCardOf(rows.sortedWith(compareBy({ it.reviewedAt }, { it.id })))
                }

        /**
         * One question's rows — already chronological — as a training card, or
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
