package com.mcqapp.data.repository

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.SchedulerConfig
import com.mcqapp.domain.stats.CardSnapshot
import com.mcqapp.domain.stats.FsrsReplay
import com.mcqapp.domain.stats.ReplayReview
import com.mcqapp.domain.stats.RetentionData
import com.mcqapp.domain.stats.ReviewHistoryEntry
import com.mcqapp.domain.stats.StatsAggregator

/**
 * Loads what the statistics page needs: the review log replayed through the
 * current scheduler, every card's current schedule, and the paper/category
 * titles the breakdowns group by. The list-shaped read is kept whole so the
 * ViewModel can re-slice ranges and scopes in memory without touching the
 * database; the retention tab, which is the largest reader of the log, streams
 * past it instead via [loadRetention].
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
        val cards = loadCards()
        val categoryOfQuestion = loadCategoryOfQuestion()
        val paperTitleOf = db.paperDao().getAll().associate { it.id to it.title }
        return RawData(reviews, cards, categoryOfQuestion, paperTitleOf, config)
    }

    /**
     * The retention tab, built while the log streams past instead of from a
     * list of it: pages are replayed straight into
     * [StatsAggregator.RetentionAccumulator], which keeps only per-card state
     * and counters. This tab is the page's memory high-water mark otherwise,
     * because it is the one section that reads the whole log on its own scope
     * as well as on the selected range.
     */
    suspend fun loadRetention(scopePaperId: String?, range: StatsAggregator.Range): RetentionData {
        val config = settings.schedulerConfigNow()
        val now = System.currentTimeMillis()
        val scheduler = FsrsScheduler(desiredRetention = config.desiredRetention, config = config)
        val accumulator = StatsAggregator.RetentionAccumulator(
            scopePaperId = scopePaperId,
            range = range,
            now = now,
            cfg = config,
            zone = DayBoundary.zoneOffset(now),
            paperTitleOf = db.paperDao().getAll().associate { it.id to it.title },
            // Only the scoped breakdown labels by category, and the map is one
            // entry per question, so the unscoped view skips building it.
            categoryOfQuestion = if (scopePaperId == null) emptyMap() else loadCategoryOfQuestion()
        )
        val stream = FsrsReplay.Stream(scheduler)
        var afterId = 0L
        while (true) {
            val page = db.reviewLogDao().pageAfterId(afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { accumulator.add(stream.accept(it.toRawReview())) }
            afterId = page.last().id
            if (page.size < PAGE_SIZE) break
        }
        return accumulator.toData()
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
            val page = db.reviewLogDao().pageAfterId(afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { reviews += stream.accept(it.toRawReview()) }
            afterId = page.last().id
            if (page.size < PAGE_SIZE) break
        }
        return reviews
    }

    /**
     * Every card, keyset-paged in physical order. `getAll()` materialised all
     * 250k rows as entities before mapping any of them; here one page is live
     * at a time and the order the breakdowns tie-break on is unchanged.
     */
    private suspend fun loadCards(): List<CardSnapshot> {
        val cards = ArrayList<CardSnapshot>()
        var afterRowId = 0L
        while (true) {
            val page = db.cardStateDao().pageAfterRowId(afterRowId, PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { cards += CardSnapshot(it.state.paperId, it.state.toDomain()) }
            afterRowId = page.last().rowid
            if (page.size < PAGE_SIZE) break
        }
        return cards
    }

    /**
     * questionId → category title, for the per-category breakdown. The old
     * `getAllIdCategories()` built a 250k-element list of pairs on the way to
     * this map; paging folds each page in as it arrives.
     */
    private suspend fun loadCategoryOfQuestion(): Map<String, String> {
        val titles = db.categoryDao().getAll().associate { it.id to it.title }
        val out = HashMap<String, String>()
        var page = db.questionDao().pageIdCategoriesFirst(PAGE_SIZE)
        while (true) {
            if (page.isEmpty()) break
            page.forEach { row -> titles[row.categoryId]?.let { out[row.id] = it } }
            if (page.size < PAGE_SIZE) break
            page = db.questionDao().pageIdCategoriesAfter(page.last().id, PAGE_SIZE)
        }
        return out
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
         * Rows per keyset page while loading. Bounds each transient read only;
         * the enriched rows the aggregator reads still span the whole log.
         */
        const val PAGE_SIZE = 5_000
    }
}
