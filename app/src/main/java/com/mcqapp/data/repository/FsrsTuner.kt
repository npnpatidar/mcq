package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.FsrsOptimizer
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
     * Fits the 21 weights to the full review log and stores them. Runs the
     * fit on the optimizer's own dispatcher; [onProgress] fires after each
     * Adam step and cancelling the caller stops between steps.
     *
     * @throws com.mcqapp.domain.FsrsFitInsufficientData when the log is too
     *   thin to fit anything.
     */
    suspend fun optimize(onProgress: (Int, Int) -> Unit = { _, _ -> }): FsrsFitResult {
        val cards = buildFsrsTrainingCards(db.reviewLogDao().getAll())
        val current = settings.schedulerConfigNow().fsrsParameters
        val result = FsrsOptimizer.fit(cards, current, onProgress)
        settings.setSchedulerConfig(
            settings.schedulerConfigNow().copy(fsrsParameters = result.parameters)
        )
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
        settings.setSchedulerConfig(
            settings.schedulerConfigNow()
                .copy(fsrsParameters = com.mcqapp.domain.FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        )
        Logger.i("TUNER", "FSRS parameters restored to defaults")
    }

    companion object {

        /**
         * Groups [logs] into one training card per question, oldest first.
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
                    val ordered = rows.sortedWith(compareBy({ it.reviewedAt }, { it.id }))
                    val first = ordered.first()
                    val seeded = first.stateBefore != null &&
                        first.stateBefore != CardStateKind.NEW.name &&
                        first.stabilityAfter != null &&
                        first.difficultyAfter != null
                    if (seeded) {
                        val reviews = ordered.drop(1)
                        if (reviews.isEmpty()) return@mapNotNull null
                        FsrsTrainingCard(
                            reviews = reviews.map { FsrsTrainingReview(it.rating, it.reviewedAt) },
                            seedStability = first.stabilityAfter,
                            seedDifficulty = first.difficultyAfter,
                            seedReviewedAt = first.reviewedAt
                        )
                    } else {
                        FsrsTrainingCard(
                            reviews = ordered.map { FsrsTrainingReview(it.rating, it.reviewedAt) }
                        )
                    }
                }
    }
}
