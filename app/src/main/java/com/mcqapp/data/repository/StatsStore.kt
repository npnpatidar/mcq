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
        val raw = db.reviewLogDao().getAll().map { it.toRawReview() }
        val reviews = FsrsReplay.replay(raw, scheduler)
        val cards = db.cardStateDao().getAll().map { CardSnapshot(it.paperId, it.toDomain()) }
        val categoryTitle = db.categoryDao().getAll().associate { it.id to it.title }
        val categoryOfQuestion = db.questionDao().getAllIdCategories()
            .mapNotNull { row -> categoryTitle[row.categoryId]?.let { row.id to it } }
            .toMap()
        val paperTitleOf = db.paperDao().getAll().associate { it.id to it.title }
        return RawData(reviews, cards, categoryOfQuestion, paperTitleOf, config)
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
}
