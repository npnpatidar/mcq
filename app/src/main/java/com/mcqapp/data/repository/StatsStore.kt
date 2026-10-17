package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.FsrsReplay
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.ReviewHistoryEntry

/**
 * Loads everything the statistics page needs in one pass: the whole review log
 * replayed through the current scheduler, every card's current schedule, and
 * the paper/category titles the breakdowns group by. Deliberately one fat
 * read — the same scale the optimizer's fit already pulls — so the ViewModel
 * can re-slice ranges and scopes in memory without touching the database.
 */
internal class StatsStore(private val db: AppDatabase, private val settings: SettingsStore) {

    data class RawData(
        val reviews: List<ReplayReview>,
        val cards: List<CardSnapshot>,
        /** questionId → category title. */
        val categoryOfQuestion: Map<String, String>,
        /** paperId → title. */
        val paperTitleOf: Map<String, String>,
        val config: SchedulerConfig
    )

    suspend fun load(): RawData {
        val config = settings.schedulerConfigNow()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val reviews = replayLog(scheduler)
        val cards = db.cardStateDao().getAll().map { CardSnapshot(it.paperId, it.toDomain()) }
        val categoryTitle = db.categoryDao().getAll().associate { it.id to it.title }
        val categoryOfQuestion = db.questionDao().getAllIdCategories()
            .mapNotNull { row -> categoryTitle[row.categoryId]?.let { row.id to it } }
            .toMap()
        val paperTitleOf = db.paperDao().getAll().associate { it.id to it.title }
        return RawData(reviews, cards, categoryOfQuestion, paperTitleOf, config)
    }

    /**
     * Replays the whole log in append (`id`) order, one keyset page at a time.
     * The old `getAll().map { … }` held every row as an entity and then every
     * enriched row, while [FsrsReplay.replay] built two maps keyed by *row* id —
     * a million reviews meant three million-entry structures live at once.
     * [FsrsReplay.Stream] needs only per-card state, so what survives is the
     * enriched rows the aggregator reads plus a single page.
     */
    private suspend fun replayLog(scheduler: FsrsScheduler): List<ReplayReview> {
        val reviews = ArrayList<ReplayReview>()
        val stream = FsrsReplay.Stream(scheduler)
        var afterId = 0L
        while (true) {
            val page = db.reviewLogDao().pageAfterId(afterId, REVIEW_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { reviews += stream.accept(it.toRawReview()) }
            afterId = page.last().id
            if (page.size < REVIEW_PAGE_SIZE) break
        }
        return reviews
    }

    /** One card's logged reviews, oldest first, for the drill-down sheet. */
    suspend fun reviewHistory(questionId: String): List<ReviewHistoryEntry> =
        db.reviewLogDao().historyForQuestion(questionId).map { it.toHistoryEntry() }

    private fun ReviewLogEntity.toRawReview() = FsrsReplay.RawReview(
        id = id,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore.toCardStateKind(),
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter
    )

    private fun ReviewLogEntity.toHistoryEntry() = ReviewHistoryEntry(
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore.toCardStateKind(),
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter
    )

    /** The stored enum name; an unknown name (old build's value) reads as null. */
    private fun String?.toCardStateKind(): CardStateKind? =
        this?.let { runCatching { CardStateKind.valueOf(it) }.getOrNull() }

    private fun CardStateEntity.toDomain() = CardState(
        questionId = questionId,
        ease = ease,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt,
        difficulty = difficulty,
        stability = stability,
        state = cardState,
        reviewCount = reviewCount
    )

    private companion object {
        /**
         * Rows per keyset page while replaying. Bounds the transient entity
         * list only; the enriched result still spans the whole log.
         */
        const val REVIEW_PAGE_SIZE = 5_000
    }
}
